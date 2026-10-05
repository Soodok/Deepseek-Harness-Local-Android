package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务（m1.30 读屏升级 v1.1.0）：
 *  - 手势注入：tap(x,y) / swipe —— 模拟点击与滑动
 *  - 读屏：dumpScreenJson() 遍历可见节点树，输出文本+坐标+可点击性（"非盲"能力）
 *  - 按文本点击：tapText("确定") —— 在节点树里找含该文本的可点击节点并点它
 *
 * 权限边界（privacy-first）：读屏能力由 canRetrieveWindowContent 开关（xml）授权；
 * 服务必须由用户在系统设置手动开启（Android 安全模型），关闭即所有能力失效。
 *
 * Agent 调用入口：AgentBridge (127.0.0.1:3083) 的 GET /screen、POST /tap，
 * 引擎内经 `scr` 包装器使用。
 */
class DshAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 订阅触摸交互事件（v1.2.54）：用于检测"人突然接管"。
        // 服务 xml 里 accessibilityEventTypes 已含 typeAllMask，但触摸类事件
        // （typeTouchInteractionStart/End）还需要在运行时显式加入过滤列表，
        // 否则部分 ROM 不会派发（实测需要 setServiceInfo 重新声明）。
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.eventTypes = info.eventTypes or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
            serviceInfo = info
        }.onFailure { android.util.Log.w("DshA11y", "touch event subscribe failed: ${it.message}") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // 记录"有人碰了屏幕"。无法区分是 AI 的注入手势还是真手指
                // （注入的手势同样产生触摸事件），故只记录时间戳，
                // 由调用方结合"AI 刚做过动作"的时间窗来判断是否为外部干预。
                lastTouchAt = System.currentTimeMillis()
                touchCount++
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                lastTouchEndAt = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 前台窗口变化（切 App / 弹新页）——自动化流程里这是重要信号
                lastWindowChangeAt = System.currentTimeMillis()
                foregroundPkgCache = e.packageName?.toString() ?: foregroundPkgCache
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ==================== 外部干预感知（v1.2.54） ====================
    //
    // 用户反馈："有的时候人操作突然接管，AI 也不知道"。
    // 真实场景：AI 正在跑多步流程，用户拿过手机自己点了两下 —— AI 继续按原计划
    // 操作，结果点在完全不同的界面上，或者把用户的输入覆盖掉。
    //
    // 机制：记录触摸/窗口变化的时间戳。调用方（AI）在关键步骤前后比对，
    // 若在自己"没做动作"的时间窗内出现了触摸或窗口切换，即判定为外部干预。

    @Volatile private var lastTouchAt: Long = 0L
    @Volatile private var lastTouchEndAt: Long = 0L
    @Volatile private var lastWindowChangeAt: Long = 0L
    @Volatile private var touchCount: Int = 0
    @Volatile private var foregroundPkgCache: String? = null

    /** 干预快照：交给调用方做前后比对 */
    data class InterferenceSnapshot(
        val lastTouchAt: Long,
        val lastWindowChangeAt: Long,
        val touchCount: Int,
        val pkg: String?,
    )

    /** 取当前干预快照 */
    fun interferenceSnapshot(): InterferenceSnapshot = InterferenceSnapshot(
        lastTouchAt = lastTouchAt,
        lastWindowChangeAt = lastWindowChangeAt,
        touchCount = touchCount,
        pkg = foregroundPackage(),
    )

    /**
     * 判断自 [since] 以来是否有外部干预。
     *
     * ⚠️ 诚实说明：无障碍注入的手势**也会**产生 TYPE_TOUCH_INTERACTION_START，
     * 系统不区分来源。因此判定逻辑必须由调用方提供"我刚做过动作"的时间点：
     * 只有发生在 `since` 之后、且调用方在该窗口内**没有**自行操作时，才算外部干预。
     * 桥接层用 `markSelfAction()` 打标记来实现这一点。
     *
     * @param since 起始时间戳（毫秒）
     * @param ignoreUntil 该时刻之前的触摸视为 AI 自身动作的回声，忽略
     */
    fun interferedSince(since: Long, ignoreUntil: Long): Boolean {
        if (lastWindowChangeAt > since && lastWindowChangeAt > ignoreUntil) return true
        return lastTouchAt > since && lastTouchAt > ignoreUntil
    }

    /** AI 自己刚派发过动作 → 记下时刻，用于把自身手势的回声排除在"外部干预"之外 */
    @Volatile private var lastSelfActionAt: Long = 0L
    fun markSelfAction() {
        lastSelfActionAt = System.currentTimeMillis()
    }
    fun lastSelfAction(): Long = lastSelfActionAt

    // ==================== 读屏 ====================

    /**
     * 遍历活跃窗口可见节点，输出 JSON：
     * {"ok":true,"width":..,"height":..,"count":N,
     *  "nodes":[{"index":0,"text":"..","desc":"..","cls":"..","rid":"..",
     *            "x":..,"y":..,"w":..,"h":..,"clickable":true,
     *            "scrollable":false,"editable":false}]}
     * 只保留「有文本/描述」或「可点击」的节点，上限 200 个防超大界面。
     *
     * v1.2.30 增强：新增 rid（viewIdResourceName）/scrollable/editable/index（遍历序，
     * 同一 UI 状态下稳定，可作点击定位的次优选择）；可选只输出可点击节点（省 token）。
     * @param clickableOnly true = 只输出可点击节点
     */
    fun dumpScreenJson(clickableOnly: Boolean = false): String {
        val arr = JSONArray()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= MAX_NODES) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val visible = rect.width() > 0 && rect.height() > 0 &&
                rect.top < rootHeight && rect.bottom > 0
            if (visible) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if ((text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) &&
                    (!clickableOnly || node.isClickable)
                ) {
                    arr.put(JSONObject().apply {
                        put("index", count)
                        put("text", text)
                        put("desc", desc)
                        put("cls", node.className?.toString() ?: "")
                        put("rid", node.viewIdResourceName ?: "")
                        put("x", rect.centerX())
                        put("y", rect.centerY())
                        put("w", rect.width())
                        put("h", rect.height())
                        put("clickable", node.isClickable)
                        // Compose 的滚动容器常不置 isScrollable，用滚动 action 探测兜底
                        put("scrollable", node.isScrollable ||
                            node.actionList.any { a ->
                                a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                                    a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
                            })
                        put("editable", node.isEditable)
                    })
                    count++
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return JSONObject()
            .put("ok", true)
            .put("width", resources.displayMetrics.widthPixels)
            .put("height", rootHeight)
            .put("count", count)
            .put("nodes", arr).toString()
    }

    /**
     * 原始节点树转储（保留父子层级，供调用方判断"哪个容器可滚动"等结构信息）。
     * 每行一个节点：缩进即深度；含 cls/id/bounds/三开关/text/desc。
     * viewIdResourceName 在上游应用未混淆 id 时可直接用于精准定位。
     */
    fun screenXml(): String {
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val attrs = buildList {
                add("cls=\"${node.className ?: ""}\"")
                add("id=\"${node.viewIdResourceName ?: ""}\"")
                add("bounds=\"${rect.left},${rect.top},${rect.right},${rect.bottom}\"")
                add("clickable=${node.isClickable}")
                add("scrollable=${node.isScrollable}")
                add("editable=${node.isEditable}")
                val t = node.text?.toString()
                if (!t.isNullOrEmpty()) add("text=\"$t\"")
                val d = node.contentDescription?.toString()
                if (!d.isNullOrEmpty()) add("desc=\"$d\"")
            }.joinToString(" ")
            sb.append("  ".repeat(depth)).append("<node ").append(attrs).append("/>\n")
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(rootInActiveWindow, 0)
        return sb.toString()
    }

    /**
     * 截取当前屏幕为 PNG 并返回 (宽, 高, base64)。**API 30+ 可用**（takeScreenshot）。
     * 同步等待结果（AgentBridge 的请求线程阻塞在此，默认超时 3s）。
     * 返回 null = 平台不支持或截图失败。
     */
    fun screenshotBase64(timeoutMs: Long = 3000L): Triple<Int, Int, String>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                }
                screenshot.hardwareBuffer.close()
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, { it.run() }, callback)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val bitmap = bmp ?: return null
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return Triple(bitmap.width, bitmap.height, Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
    }

    /** 屏幕上是否存在文本/描述包含 q 的节点（/wait 的服务端判定） */
    fun screenContains(q: String): Boolean {
        val query = q.trim()
        val root = rootInActiveWindow ?: return false
        var found = false
        fun walk(node: AccessibilityNodeInfo?) {
            if (found || node == null) return
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.contains(query, true) || d.contains(query, true)) {
                found = true
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return found
    }

    private val rootHeight: Int
        get() = resources.displayMetrics.heightPixels

    // ==================== 点击 ====================

    /** 按文本查找可点击节点并点击（text+desc、trim、忽略大小写；完全>前缀>包含）。
     *  找不到回退坐标点击其中心。
     *
     *  拟人化（v1.2.54）：抖动半径按**节点实际尺寸**收缩 —— 小控件（复选框、
     *  图标按钮）不能被抖出边界，否则点击落空。半径取 min(默认抖动, 节点短边/4)。 */
    fun tapText(text: String): Boolean {
        val target = findNodeByText(text) ?: return false
        val rect = Rect().also { target.getBoundsInScreen(it) }
        val jitter = jitterFor(rect)
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY(), jitter)
    }

    /** 按 contentDescription 查找并点击（侧边栏图标按钮等 desc-only 节点） */
    fun tapDesc(desc: String): Boolean {
        val target = findNodeByText(desc, byDesc = true) ?: return false
        val rect = Rect().also { target.getBoundsInScreen(it) }
        val jitter = jitterFor(rect)
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY(), jitter)
    }

    /** 按节点尺寸决定抖动半径：短边/4 是安全上限（中心 ± 该值仍在节点内） */
    private fun jitterFor(rect: Rect): Int {
        val shortSide = minOf(rect.width(), rect.height())
        return minOf(DEFAULT_TAP_JITTER, shortSide / 4).coerceAtLeast(0)
    }

    /** 匹配打分：完全相等 > 前缀 > 包含（全部 trim + 忽略大小写，text 与 desc 同权） */
    private fun matchScore(value: String, query: String): Int {
        val v = value.lowercase()
        val q = query.lowercase()
        return when {
            v == q -> 3
            v.startsWith(q) -> 2
            v.contains(q) -> 1
            else -> 0
        }
    }

    private fun findNodeByText(query: String, byDesc: Boolean = false): AccessibilityNodeInfo? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestClickable = false

        fun consider(node: AccessibilityNodeInfo) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val score = maxOf(
                if (byDesc) 0 else matchScore(t, q),
                matchScore(d, q),
            )
            if (score == 0) return
            val clickable = node.isClickable || hasClickableAncestor(node)
            // 更高分优先；同分优先「本身就是可点击节点」
            if (score > bestScore || (score == bestScore && clickable && !bestClickable)) {
                best = node
                bestScore = score
                bestClickable = clickable
            }
        }

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            consider(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        if (best == null) return null
        return if (bestClickable) clickableAncestor(best!!) else best
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node.parent
        while (cur != null) {
            if (cur.isClickable) return true
            cur = cur.parent
        }
        return false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo = node
        while (!cur.isClickable) {
            cur = cur.parent ?: return node
        }
        return cur
    }

    // ==================== companion ====================

    companion object {
        @Volatile
        internal var instance: DshAccessibilityService? = null

        /** 服务是否已启用（用户在系统设置开启后为 true） */
        fun isEnabled(): Boolean = instance != null

        /** 是否支持手势注入（API 24+ 且服务已连接） */
        fun canTap(): Boolean = instance != null && Build.VERSION.SDK_INT >= 24

        /** dumpScreen 的最大节点数（防超大界面卡顿） */
        private const val MAX_NODES = 200

        /** 滚动后等待动画结束的时间（节点树读到中间态会导致误判"没找到"） */
        private const val SCROLL_SETTLE_MS = 260L

        /** 默认点击抖动半径（像素）：小屏设备约 2–6px，兼顾拟人化与命中率 */
        private const val DEFAULT_TAP_JITTER = 4

        /**
         * 模拟点击屏幕 (x, y)（物理像素坐标）。
         * @param jitterPx 落点随机偏移半径；0 = 精确点击
         * @return true 表示已成功派发合成点击
         */
        fun tap(x: Float, y: Float, jitterPx: Int = DEFAULT_TAP_JITTER): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < 24) return false
            return svc.dispatchTap(x, y, jitterPx)
        }
    }

    /** 实例内手势派发（companion.tap 与 tapText 共用）。
     *
     *  拟人化（v1.2.54）：默认在目标点附近做小幅随机偏移。
     *  机器特征里最容易识别的一条就是「每次都点同一个像素」——真实手指
     *  落点天然有分布。偏移半径 [jitterPx]，0 = 精确（需要像素级操作时用）。
     *  ⚠️ 诚实说明：这削弱的是「模式识别」，不是「指纹识别」——
     *  无障碍注入的手势在系统层仍有固有特征（deviceId、pressure 恒为 1.0），
     *  要彻底隐形需 Root 层 uinput 注入，不在本版范围。 */
    internal fun dispatchTap(x: Float, y: Float, jitterPx: Int = DEFAULT_TAP_JITTER): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val (jx, jy) = if (jitterPx > 0) {
            val r = kotlin.random.Random
            val dx = r.nextInt(-jitterPx, jitterPx + 1).toFloat()
            val dy = r.nextInt(-jitterPx, jitterPx + 1).toFloat()
            // 限制在屏幕内，避免抖出边界导致手势无效
            val (sw, sh) = screenSize()
            (x + dx).coerceIn(1f, sw - 2f) to (y + dy).coerceIn(1f, sh - 2f)
        } else x to y
        val path = Path().apply { moveTo(jx, jy) }
        // 按压时长也在人类范围内抖动（真实点按约 50–130ms，恒定 60ms 是机器特征）
        val duration = if (jitterPx > 0) kotlin.random.Random.nextLong(55L, 135L) else 60L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        // 标记"这是我自己发的"——注入手势也会产生 TYPE_TOUCH_INTERACTION_START，
        // 不打标记的话下一轮干预检测会把自己的动作误判成用户接管
        markSelfAction()
        return dispatchGesture(gesture, null, null)
    }

    /** 任意滑动/手势（无障碍 dispatchGesture，无需 Shizuku/Root 权限） */
    internal fun dispatchSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 长按（单点长时长 stroke） */
    internal fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 全局动作（back/home/recents/notifications/quick_settings）。
     *  ⚠️ 实测本应用内 back 会把整个 Activity 弹到桌面而非关闭弹层，
     *  调用方（sctl 焦点守卫）应检测前台包名并提示恢复。 */
    fun performGlobalActionByName(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(action)
    }

    // ==================== 文字输入（v1.2.54） ====================
    //
    // 为什么必须有：此前只有 tap/swipe/key，AI 能点开搜索框却打不了字 ——
    // 搜索、登录、发消息、填表全部卡死在这一步（用户反馈"无障碍基本用不了"
    // 的头号原因）。文字输入是自动化闭环的最后一块拼图。

    /**
     * 向当前聚焦的可编辑节点写入文本。
     *
     * 两条路径，按可靠性排序：
     *  ① `ACTION_SET_TEXT` —— 无障碍原生接口，直接设定内容，不走 IME、不受
     *    输入法语言/联想干扰（首选）。
     *  ② 剪贴板 + `ACTION_PASTE` —— ①失败时回退（部分自绘输入框/WebView
     *     不实现 ①，但支持粘贴）。
     *
     * @param text 要写入的文本
     * @param append true = 追加到现有内容末尾，false = 覆盖
     * @param targetText 可选的定位文本：先找到该节点（如输入框的提示文字）并聚焦，
     *        再写入。省略则直接用当前焦点节点。
     * @return true 表示已成功写入
     */
    fun inputText(text: String, append: Boolean = false, targetText: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false

        // 定位目标节点：显式目标 > 当前焦点 > 首个可编辑节点
        val target: AccessibilityNodeInfo? = when {
            !targetText.isNullOrBlank() -> findEditableByText(root, targetText)
            else -> null
        } ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)

        val node = target ?: return false

        // 未聚焦时先请求焦点（否则 SET_TEXT 可能作用不到）
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val finalText = if (append) {
            (node.text?.toString().orEmpty()) + text
        } else {
            text
        }

        // ① ACTION_SET_TEXT
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        // ② 剪贴板 + 粘贴（SET_TEXT 不被支持时的回退）
        return runCatching {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", finalText))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    /** 按文本/描述找到可编辑节点（输入框常以 hint 文本暴露） */
    private fun findEditableByText(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = query.trim().lowercase()
        var hit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (hit != null || node == null) return
            val t = node.text?.toString()?.trim().orEmpty().lowercase()
            val d = node.contentDescription?.toString()?.trim().orEmpty().lowercase()
            val h = if (Build.VERSION.SDK_INT >= 26) {
                node.hintText?.toString()?.trim().orEmpty().lowercase()
            } else ""
            if (node.isEditable && (t.contains(q) || d.contains(q) || h.contains(q))) {
                hit = node
                return
            }
            // 命中的容器内含可编辑子节点（如 hint 在父布局上）
            if (t.contains(q) || d.contains(q) || h.contains(q)) {
                findFirstEditable(node)?.let { hit = it; return }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return hit
    }

    /** 深度优先找第一个可编辑节点 */
    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    // ==================== 滚动查找（v1.2.54） ====================
    //
    // 为什么必须有：目标在屏幕外时，此前只能「swipe → dump → 没找到 → 再 swipe」
    // 手动循环，一次找元素烧 5-10 个来回。滚动查找把整个循环压到服务端一次调用。

    /**
     * 在可滚动容器内滚动查找文本，找到则返回命中节点的矩形。
     *
     * 返回 Rect（而非仅坐标）是为了让调用方能按**节点尺寸**决定点击抖动半径 ——
     * 小控件不能被抖出边界（见 jitterFor）。
     *
     * @param query 目标文本（含 contentDescription，忽略大小写）
     * @param maxSwipes 最多滚动次数（默认 8）
     * @param forward true = 向下/向前滚动，false = 向上/向后
     * @return 找到时返回节点屏幕矩形；未找到返回 null
     */
    fun scrollToFind(query: String, maxSwipes: Int = 8, forward: Boolean = true): Rect? {
        val q = query.trim()
        if (q.isEmpty()) return null

        // 先在当前屏找
        findNodeByText(q)?.let { return Rect().also { rr -> it.getBoundsInScreen(rr) } }

        val root = rootInActiveWindow ?: return null
        val scroller = findScrollable(root) ?: return null

        repeat(maxSwipes.coerceIn(1, 30)) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (!scroller.performAction(action)) return null
            // 等滚动动画结束再读节点树（否则读到中间态）
            Thread.sleep(SCROLL_SETTLE_MS)
            findNodeByText(q)?.let { return Rect().also { rr -> it.getBoundsInScreen(rr) } }
        }
        return null
    }

    /** 找第一个真正可滚动的容器（Compose 常不置 isScrollable，用 action 探测兜底） */
    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val canScroll = node.isScrollable || node.actionList.any { a ->
            a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        }
        if (canScroll) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    // ==================== 等待界面稳定（v1.2.54） ====================
    //
    // 为什么必须有：点击后立刻读屏会拿到「旧界面」，AI 以为没生效 → 重复点。
    // 此前只能靠 wait <文本> 猜或硬 sleep。稳定检测让"点完等它安静下来"变成一次调用。

    /** 当前节点树签名（文本+描述+坐标的稳定摘要），用于判断界面是否变化 */
    private fun treeSignature(): String {
        val sb = StringBuilder()
        var n = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || n >= MAX_NODES) return
            val r = Rect().also { node.getBoundsInScreen(it) }
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() || d.isNotEmpty() || node.isClickable) {
                sb.append(t).append('|').append(d).append('|')
                    .append(r.centerX()).append(',').append(r.centerY()).append(';')
                n++
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return sb.toString()
    }

    /**
     * 阻塞等待界面稳定：连续 [stableReads] 次读取签名不变即认为稳定。
     *
     * @param timeoutMs 总超时上限（到点即使未稳定也返回，交由调用方判断）
     * @param quietMs 每次采样间隔
     * @param stableReads 需要连续几次不变才算稳定
     * @return true = 已稳定，false = 超时（界面仍在变）
     */
    fun waitForIdle(
        timeoutMs: Long = 3000L,
        quietMs: Long = 120L,
        stableReads: Int = 3,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        var sameCount = 0
        while (System.currentTimeMillis() < deadline) {
            val sig = treeSignature()
            if (sig == last && sig.isNotEmpty()) {
                sameCount++
                if (sameCount >= stableReads) return true
            } else {
                sameCount = 0
                last = sig
            }
            Thread.sleep(quietMs)
        }
        return false
    }

    /** 当前前台应用包名（供调用方确认"是否还在目标界面"） */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** 屏幕尺寸（供坐标换算/比例点击） */
    fun screenSize(): Pair<Int, Int> =
        resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels

    /**
     * 点击一个节点矩形（供桥接层在"滚动找到"后点击）。
     * 抖动半径按矩形尺寸自适应，小控件不会被抖出边界。
     */
    fun dispatchTapRect(rect: Rect): Boolean {
        val jitter = minOf(DEFAULT_TAP_JITTER, minOf(rect.width(), rect.height()) / 4)
            .coerceAtLeast(0)
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY(), jitter)
    }

    // ==================== 批量动作执行（v1.2.54） ====================
    //
    // 用户反馈的核心问题："太慢"。根因不是单次动作慢（桥接往返只有几十毫秒），
    // 而是**每个动作都要 AI 往返一次**——dump → LLM 思考（秒级）→ 点 → dump → …
    // 一个 10 步流程就是 10 次 LLM 调用。
    //
    // 解法：一次调用执行一串动作，只在最后回一次结果。
    // AI 只思考一次（"打开设置 → 点 WLAN → 等它稳定"），剩下的在服务端跑完。

    /** 批量动作的一步执行结果 */
    data class StepResult(
        val index: Int,
        val action: String,
        val ok: Boolean,
        val detail: String,
    )

    /**
     * 执行一串动作，每步之间自动等待界面稳定。
     *
     * 支持的步骤类型（step 是 Map，字段与桥接层 JSON 一致）：
     *   {"type":"tap","text":"WLAN"} / {"type":"tap","x":1,"y":2}
     *   {"type":"input","text":"hello"}
     *   {"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"durationMs":300}
     *   {"type":"key","action":"back"}
     *   {"type":"wait","text":"已连接","timeoutMs":5000}
     *   {"type":"idle","timeoutMs":2000}
     *
     * 每步默认等界面稳定（`settleMs`），避免"点了立刻读旧界面"导致的连锁失败。
     * 任一步失败即停止（除非该步标了 `"optional":true`），返回已执行的结果列表。
     *
     * @param steps 动作序列
     * @param settleMs 每步后等待稳定的上限
     * @param stopOnError 失败是否中止（默认 true）
     * @return 每步的结果
     */
    fun runBatch(
        steps: List<Map<String, Any?>>,
        settleMs: Long = 2_000L,
        stopOnError: Boolean = true,
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()
        steps.forEachIndexed { i, step ->
            val type = (step["type"] as? String).orEmpty()
            val optional = step["optional"] == true
            var ok = false
            var detail = ""

            try {
                ok = when (type) {
                    "tap" -> {
                        val text = step["text"] as? String
                        val desc = step["desc"] as? String
                        when {
                            !desc.isNullOrBlank() -> tapDesc(desc)
                            !text.isNullOrBlank() -> tapText(text)
                            else -> {
                                val x = (step["x"] as? Number)?.toFloat()
                                val y = (step["y"] as? Number)?.toFloat()
                                if (x != null && y != null) dispatchTap(x, y) else false
                            }
                        }
                    }
                    "long_press" -> {
                        val x = (step["x"] as? Number)?.toFloat()
                        val y = (step["y"] as? Number)?.toFloat()
                        val ms = (step["durationMs"] as? Number)?.toLong() ?: 600L
                        if (x != null && y != null) dispatchLongPress(x, y, ms) else false
                    }
                    "swipe" -> {
                        val x1 = (step["x1"] as? Number)?.toFloat()
                        val y1 = (step["y1"] as? Number)?.toFloat()
                        val x2 = (step["x2"] as? Number)?.toFloat()
                        val y2 = (step["y2"] as? Number)?.toFloat()
                        val ms = (step["durationMs"] as? Number)?.toLong() ?: 300L
                        if (x1 != null && y1 != null && x2 != null && y2 != null) {
                            dispatchSwipe(x1, y1, x2, y2, ms)
                        } else false
                    }
                    "input" -> {
                        val t = step["text"] as? String ?: ""
                        inputText(t, step["append"] == true, step["target"] as? String)
                    }
                    "key" -> performGlobalActionByName((step["action"] as? String).orEmpty())
                    "scroll_find" -> {
                        val t = step["text"] as? String ?: ""
                        val forward = step["back"] != true
                        val maxSwipes = (step["maxSwipes"] as? Number)?.toInt() ?: 8
                        val hit = scrollToFind(t, maxSwipes, forward)
                        if (hit != null) {
                            detail = "found at ${hit.centerX()},${hit.centerY()}"
                            if (step["tap"] == true) dispatchTapRect(hit) else true
                        } else false
                    }
                    "wait" -> {
                        val t = step["text"] as? String ?: ""
                        val gone = step["gone"] == true
                        val timeout = (step["timeoutMs"] as? Number)?.toLong() ?: 5_000L
                        waitForText(t, gone, timeout)
                    }
                    "idle" -> {
                        val timeout = (step["timeoutMs"] as? Number)?.toLong() ?: settleMs
                        waitForIdle(timeout)
                    }
                    "sleep" -> {
                        Thread.sleep(((step["ms"] as? Number)?.toLong() ?: 500L).coerceIn(0L, 10_000L))
                        true
                    }
                    else -> {
                        detail = "unknown step type"
                        false
                    }
                }
            } catch (e: Exception) {
                ok = false
                detail = e.message.orEmpty()
            }

            results.add(StepResult(i, type, ok, detail))

            if (!ok && !optional) {
                if (stopOnError) return results
            }
            // 非阻塞的同步类步骤不需要再等稳定
            if (type != "wait" && type != "idle" && type != "sleep" && ok) {
                waitForIdle(settleMs)
            }
        }
        return results
    }

    /** 等待文本出现/消失（批量步骤与 /wait 共用） */
    fun waitForText(text: String, gone: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = screenContains(text)
            if (found != gone) return true
            Thread.sleep(150)
        }
        return false
    }
}

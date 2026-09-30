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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 常规无障碍事件流：本服务不监听特定事件，保留空实现以符合契约
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

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
                        put("scrollable", node.isScrollable)
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
     *  找不到回退坐标点击其中心。 */
    fun tapText(text: String): Boolean {
        val target = findNodeByText(text) ?: return false
        val rect = Rect().also { target.getBoundsInScreen(it) }
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    /** 按 contentDescription 查找并点击（侧边栏图标按钮等 desc-only 节点） */
    fun tapDesc(desc: String): Boolean {
        val target = findNodeByText(desc, byDesc = true) ?: return false
        val rect = Rect().also { target.getBoundsInScreen(it) }
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    /** 匹配打分：完全相等 > 前缀 > 包含（全部 trim + 忽略大小写，text 与 desc 同权） */
    private fun matchScore(value: String, query: String): Int = when {
        value.equals(query, true) -> 3
        value.startsWith(query, true) -> 2
        value.contains(query, true) -> 1
        else -> 0
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

        /**
         * 模拟点击屏幕 (x, y)（物理像素坐标）。
         * @return true 表示已成功派发合成点击
         */
        fun tap(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < 24) return false
            return svc.dispatchTap(x, y)
        }
    }

    /** 实例内手势派发（companion.tap 与 tapText 共用） */
    internal fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
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
}

package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * 编号屏幕文档（v1.6.19）。
 *
 * ## 它解决什么
 *
 * 紧凑格式（dumpScreenCompact）的每行是「文本 + 坐标」。模型要点第三个「确定」时
 * 没有别的抓手，只能靠文本匹配取第一个命中——于是点错。
 *
 * 本类把一次读屏变成一份**带编号的文档**：编号按阅读顺序分配、按屏幕位置分区、
 * 按嵌套深度缩进。模型说「点 [3]」而不是「点那个确定」，歧义消失。
 *
 * ## 与既有格式的关系
 *
 * **不改 dumpScreenCompact 的默认输出。** 那是 loop 执行器、PICK 匹配、
 * 场景记忆共同依赖的格式，改它会牵动一整片。本类是独立入口：
 * 新端点 GET /screen?format=doc 与 POST /tap-ref 用它，旧路径零改动。
 *
 * ## 编号的有效期
 *
 * 只对**最新一次构建**有效。每次构建把编号与指纹一起存进 latest，
 * resolveForTap 拿它做预校验：编号还在、指纹对得上，才真的点。
 * 页面在 dump 与 tap 之间变了，就拒绝执行而不是点在旧坐标上。
 */
object ScreenDoc {

    /** 一个元素。 */
    class Element(
        val id: Int,
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val tapX: Int,
        val tapY: Int,
        val role: String,
        val label: String,
        val rawLabel: String,
        val viewId: String,
        val className: String,
        val flags: String,
        val checked: Boolean,
        val depth: Int,
        val centerY: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val sensitive: Boolean,
    ) {
        /** 身份指纹：不含 bounds（滚动会移动位置，但元素本身没变）。 */
        val fingerprint: String
            get() = listOf(viewId, role, rawLabel, if (checked) "1" else "0", className).joinToString("|")
    }

    /** 一次构建的结果。 */
    class Doc(
        val screenId: String,
        val text: String,
        val elements: List<Element>,
        val packageName: String,
        val appLabel: String,
        val protectedPage: Boolean,
        val poorTree: Boolean,
    ) {
        /** 有序指纹序列：判断两屏是否同一份内容（编号顺序也参与比较）。 */
        fun signature(): List<String> = elements.map { it.id.toString() + ":" + it.fingerprint }
    }

    private const val LABEL_CAP = 60
    private const val MAX_NODES = 200
    private const val POOR_TREE_THRESHOLD = 5
    private const val TOP_ZONE = 0.25f
    private const val BOTTOM_ZONE = 0.80f

    /** 敏感动作词：按下去有后果的按钮，行首标 ⚠。 */
    private val SENSITIVE_WORDS = listOf(
        "发送", "发布", "提交", "支付", "付款", "购买", "下单", "转账", "红包",
        "删除", "清空", "卸载", "注销", "退出登录", "授权", "同意", "拨打",
    )

    private val SENSITIVE_ID_WORDS = setOf(
        "send", "publish", "submit", "pay", "payment", "checkout", "purchase", "buy",
        "transfer", "delete", "uninstall", "logout", "signout", "unregister", "authorize", "dial",
    )

    private val counter = AtomicLong(0)

    @Volatile
    private var latest: Doc? = null

    fun latestDoc(): Doc? = latest

    /** 构建编号文档；无 root 窗口或屏幕尺寸异常时返回 null。 */
    fun build(svc: AccessibilityService): Doc? {
        val root = svc.rootInActiveWindow ?: return null
        // ★坐标空间必须与 /screenshot 同源（v1.7.6 真机）：AccessibilityNodeInfo
        //   .getBoundsInScreen 给的是**整屏**坐标，而 resources.displayMetrics 在
        //   服务上下文里常常是「去掉状态栏/导航栏后的窗口尺寸」（真机 1216x2449 vs
        //   截图 1216x2640，差 191px）。头部尺寸与节点坐标不在同一空间时，任何按
        //   坐标注入的手势都会系统性偏移 —— 所以这里取 realMetrics。
        // 与 DshAccessibilityService.realScreenSize() 共用同一份实现 ——
        // 此前这里自己抄了一遍，两份只要有一份改了回落策略就会悄悄分叉
        // （v1.7.8 复查）。
        val (screenW, screenH) = DshAccessibilityService.realScreenSizeOf(svc)
        if (screenW <= 0 || screenH <= 0) return null

        val raws = ArrayList<Raw>(64)
        var visited = 0
        var truncated = false

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            if (visited >= MAX_NODES) {
                truncated = true
                return
            }
            visited++
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val visible = rect.width() > 0 && rect.height() > 0 && rect.top < screenH && rect.bottom > 0
            if (visible) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                var hint = ""
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    hint = node.hintText?.toString()?.trim().orEmpty()
                }
                val editable = node.isEditable
                val clickable = node.isClickable
                val scrollable = node.isScrollable
                // 空输入框 placeholder 只在 hintText 里（Chromium/WebView 尤其如此）
                val label = when {
                    text.isNotEmpty() -> text
                    desc.isNotEmpty() -> desc
                    hint.isNotEmpty() -> hint
                    else -> ""
                }
                if (label.isNotEmpty() || clickable || editable || scrollable) {
                    val flags = buildString {
                        if (clickable) append('c')
                        if (scrollable) append('s')
                        if (editable) append('e')
                        if (node.isCheckable) append(if (node.isChecked) 'v' else 'o')
                    }
                    raws.add(
                        Raw(
                            node = node,
                            bounds = Rect(rect),
                            label = label.replace('\n', ' '),
                            role = roleOf(node, clickable),
                            flags = flags,
                            checked = node.isCheckable && node.isChecked,
                            scrollable = scrollable,
                            depth = depth,
                            viewId = node.viewIdResourceName.orEmpty(),
                            className = node.className?.toString().orEmpty(),
                            clickable = clickable,
                            editable = editable,
                            password = node.isPassword,
                            hasText = label.isNotEmpty(),
                        ),
                    )
                }
                // 深度只在可滚动节点上递增 —— 缩进表达「在第几层列表里」
                val childDepth = if (scrollable) depth + 1 else depth
                for (i in 0 until node.childCount) walk(node.getChild(i), childDepth)
            } else {
                for (i in 0 until node.childCount) walk(node.getChild(i), depth)
            }
        }
        walk(root, 0)
        if (raws.isEmpty()) return null

        // 阅读顺序：先 top 后 left
        raws.sortWith(compareBy({ it.bounds.top }, { it.bounds.left }))

        val elements = ArrayList<Element>(raws.size)
        var textNodeCount = 0
        var idx = 0
        for (r in raws) {
            if (r.hasText) textNodeCount++
            val maskedRaw = ScreenMask.mask(r.label)
            val masked = if (maskedRaw.length > LABEL_CAP) maskedRaw.take(LABEL_CAP) + "…(" + maskedRaw.length + ")" else maskedRaw
            val vis = Rect(r.bounds)
            val cx: Int
            val cy: Int
            if (vis.intersect(0, 0, screenW, screenH)) {
                cx = vis.centerX()
                cy = vis.centerY()
            } else {
                cx = r.bounds.centerX()
                cy = r.bounds.centerY()
            }
            idx++
            elements.add(
                Element(
                    id = idx,
                    node = r.node,
                    bounds = r.bounds,
                    tapX = cx,
                    tapY = cy,
                    role = r.role,
                    label = masked,
                    rawLabel = r.label,
                    viewId = r.viewId,
                    className = r.className,
                    flags = r.flags,
                    checked = r.checked,
                    depth = r.depth,
                    centerY = r.bounds.centerY(),
                    clickable = r.clickable,
                    editable = r.editable,
                    password = r.password,
                    sensitive = isSensitive(r.label, r.viewId),
                ),
            )
        }

        val pkg = root.packageName?.toString().orEmpty()
        val appLabel = runCatching {
            val pm = svc.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
        // 标题候选：把「文本 + 位置 + 缩进层级」交给 ProtectedPages 判。
        // ⚠️ 传 depth 而不是「是否在滚动容器里」：Android 设置页的标题本身就在
        // RecyclerView 里，按容器属性排除会把所有设置子页标题都排除掉，
        // 结果是保护页判定永远 false（真机实测踩过）。
        val titleItems = elements
            .filter { it.label.isNotEmpty() && !it.editable }
            .map { ProtectedPages.TitleItem(it.label, it.centerY, false, it.depth) }
        val protectedPage = ProtectedPages.isProtected(pkg, ProtectedPages.titleTexts(titleItems, screenH))

        val screenId = "s" + counter.incrementAndGet()
        val poorTree = textNodeCount < POOR_TREE_THRESHOLD
        val text = render(elements, screenId, appLabel, pkg, screenW, screenH, protectedPage, poorTree, truncated)

        val doc = Doc(screenId, text, elements, pkg, appLabel, protectedPage, poorTree)
        latest = doc
        return doc
    }
    /** 渲染成文本。抽出来是为了能脱离设备在 JVM 侧测格式。 */
    fun render(
        elements: List<Element>,
        screenId: String,
        appLabel: String,
        packageName: String,
        screenW: Int,
        screenH: Int,
        protectedPage: Boolean,
        poorTree: Boolean,
        truncated: Boolean,
    ): String {
        val orientation = if (screenW > screenH) "横屏" else "竖屏"
        val app = appLabel.ifEmpty { packageName.ifEmpty { "未知" } }
        val head = StringBuilder()
        head.append("屏幕 ").append(screenId).append(" · ").append(app)
            .append(" · ").append(orientation)
            .append(" · 共 ").append(elements.size).append(" 项\n")
        if (protectedPage) {
            head.append("提示：这是系统权限 / 安全页面。可以读，但不能在这里点击或输入；")
                .append("需要操作请让用户自己完成。\n")
        }
        if (poorTree) {
            head.append("提示：本页可读文字很少（游戏、自绘界面常见）。可用截图配合视觉定位。\n")
        }

        val top = ArrayList<Element>()
        val middle = ArrayList<Element>()
        val bottom = ArrayList<Element>()
        for (e in elements) {
            when {
                e.centerY < screenH * TOP_ZONE -> top.add(e)
                e.centerY > screenH * BOTTOM_ZONE -> bottom.add(e)
                else -> middle.add(e)
            }
        }

        val topsb = StringBuilder()
        emitZone(topsb, "顶部", top)
        val midsb = StringBuilder()
        emitZone(midsb, "中部", middle)
        val botsb = StringBuilder()
        emitZone(botsb, "底部", bottom)
        val note = if (truncated) "…（节点数超过上限，只列出了前 " + elements.size + " 项）\n" else ""
        return ScreenBudget.assemble(head.toString(), topsb.toString(), midsb.toString(), botsb.toString(), middle.size, note)
    }
    private fun emitZone(sb: StringBuilder, name: String, list: List<Element>) {
        if (list.isEmpty()) return
        sb.append(name).append('\n')
        for (e in list) sb.append(line(e)).append('\n')
    }

    /** 一行：[3] 开关 WLAN 已开 @900,412 co；⚠ 前缀 = 敏感动作。 */
    fun line(e: Element): String {
        val sb = StringBuilder()
        sb.append("  ".repeat(e.depth.coerceAtLeast(0) + 1))
        if (e.sensitive) sb.append("⚠")
        sb.append('[').append(e.id).append("] ")
        if (e.role.isNotEmpty()) sb.append(e.role).append(' ')
        sb.append(e.label.ifEmpty { "(无标签)" })
        if (e.role == "switch" || e.role == "radio") sb.append(if (e.checked) " 已开" else " 已关")
        if (e.editable && e.password) sb.append(" 密码")
        sb.append(" @").append(e.tapX).append(',').append(e.tapY)
        if (e.flags.isNotEmpty()) sb.append(' ').append(e.flags)
        return sb.toString()
    }

    private fun roleOf(node: AccessibilityNodeInfo, clickable: Boolean): String = when {
        // v1.7.6：isCheckable 只说明控件带选中态（很多图标按钮都会带），
        // 真开关看类名 —— 否则「复制」这类动作按钮会被标成 switch。
        // v1.7.8：RadioButton 单独成 [radio]。switch 的语义是「点一下翻转、
        // 再点一下翻回来」，而单选是组内互斥 —— 模型按 switch 语义去"关掉"
        // 同一个按钮会白点一次并误判失败。
        node.isCheckable && isRadioClass(node) -> "radio"
        node.isCheckable && isSwitchClass(node) -> "switch"
        node.isCheckable -> "checkable"
        node.isEditable -> "edit"
        else -> {
            val cn = node.className?.toString().orEmpty()
            when {
                cn.contains("Button") -> "button"
                cn.contains("SeekBar") -> "slider"
                cn.contains("RecyclerView") || cn.contains("ListView") -> "list"
                cn.contains("ScrollView") -> "scroll"
                clickable -> "row"
                else -> ""
            }
        }
    }

    private fun isSwitchClass(node: AccessibilityNodeInfo): Boolean {
        val cn = node.className?.toString().orEmpty()
        return cn.contains("Switch", true) || cn.contains("CheckBox", true) ||
            cn.contains("ToggleButton", true) ||
            cn.contains("CompoundButton", true)
    }

    private fun isRadioClass(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString().orEmpty().contains("RadioButton", true)

    private fun isSensitive(label: String, viewId: String): Boolean {
        if (SENSITIVE_WORDS.any { label.contains(it) }) return true
        val idTail = viewId.substringAfterLast('/').lowercase()
        if (idTail.isEmpty()) return false
        return SENSITIVE_ID_WORDS.any { w -> idTail.contains(w) }
    }

    /**
     * 按编号取元素并重新读树校验指纹。
     * 校验用的构建不覆盖 latest —— 读失败时不能把上一份有效文档冲掉。
     */
    fun resolveForTap(svc: AccessibilityService, screenId: String, id: Int): Resolve {
        val doc = latest ?: return Resolve.NoDoc
        if (doc.screenId != screenId) return Resolve.StaleId(doc.screenId)
        val recorded = doc.elements.firstOrNull { it.id == id } ?: return Resolve.NoSuchId
        val saved = latest
        val nowDoc = build(svc)
        latest = saved
        if (nowDoc == null) return Resolve.NoDoc
        val now = nowDoc.elements.firstOrNull { it.id == id } ?: return Resolve.Changed(recorded, null)
        if (now.fingerprint != recorded.fingerprint) return Resolve.Changed(recorded, now)
        return Resolve.Ok(now, recorded, nowDoc.protectedPage)
    }

    private class Raw(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val label: String,
        val role: String,
        val flags: String,
        val checked: Boolean,
        val scrollable: Boolean,
        val depth: Int,
        val viewId: String,
        val className: String,
        val clickable: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val hasText: Boolean,
    )

    sealed class Resolve {
        /**
         * 校验通过。
         *
         * [nowDocProtected] 是**重建那份**文档的保护页判定 —— 调用方必须用它，
         * 不能用「点击前」那份。dump 与 tap 之间页面可能已经切到权限页，
         * 而那正是最危险的一瞬。
         */
        class Ok(
            val now: Element,
            val recorded: Element,
            val nowDocProtected: Boolean,
        ) : Resolve()
        class StaleId(val latestId: String) : Resolve()
        object NoSuchId : Resolve()
        object NoDoc : Resolve()
        class Changed(val recorded: Element, val now: Element?) : Resolve()
    }
}
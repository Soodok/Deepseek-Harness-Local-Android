package app.dsh.mobile

/**
 * 受保护页面清单（v1.6.19）。
 *
 * ## 防护的是什么
 *
 * 一个能操作无障碍的 agent，理论上可以自己点「允许」——给自己授予无障碍、
 * 打开开发者选项、安装未知来源应用、绕过锁屏。这不是模型可靠性的问题，
 * 是**能力边界**问题：即使模型完全听话，也不该有这条路径。
 *
 * 所以这些页面：**可以读，不可以点**。
 * 读是必需的（模型得知道自己被拦在哪），点是永远不行。
 *
 * ## 两类判据
 *
 * 1. **整包保护**：这个包只做安装/权限/安全 UI，进来就没有正当的自动操作场景
 * 2. **设置类按标题**：设置 App 本身是正常界面，只有特定子页才拦
 *
 * 第二条必须按标题而不能整包拦——否则「用 agent 改个壁纸」都会被误伤。
 *
 * ## 为什么没有「解除」开关
 *
 * 密码框、支付按钮这类可以给用户一个「本次放行」的开关（有些场景确实需要）。
 * 但「让 agent 自己开无障碍权限」不存在正当场景。宁可功能少，不留这条路。
 *
 * 无 Android 依赖，纯 Kotlin，可直接在 JVM 单测里跑。
 */
object ProtectedPages {

    /**
     * `/key` 的**纯导航**动作：在保护页上也放行。
     *
     * 为什么需要：保护页闸门拦的是「授权/改设置」，而 back/home/recents 只能离开页面，
     * 什么权限都给不了。一律拦掉的后果是 AI 被**困在**保护页里 —— 实测在
     * `com.android.settings.intelligence`（设置搜索）里连 `/key home` 都 403，
     * 只能人手动按。危险面没变，可用性没了。
     *
     * 保守起见只认这三个；`enter` 可能提交表单，不算导航。
     */
    private val NAV_KEYS = setOf("back", "home", "recents")

    /** `/key` 请求体里的动作名（`action` 或 `name`，与桥的字段兼容）。 */
    private val KEY_BODY = Regex("\"(?:action|name)\"\\s*:\\s*\"([A-Za-z_]+)\"")

    fun isNavigationKey(body: String): Boolean {
        if (body.isBlank()) return false
        val m = KEY_BODY.find(body) ?: return false
        return m.groupValues[1].lowercase() in NAV_KEYS
    }

    /**
     * 整包保护。来源：AOSP 标准包名 + 各 OEM 的实际包名（真机/社区反馈核实）。
     */
    val PACKAGES: Set<String> = setOf(
        // AOSP / Google
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // 小米 / 红米
        "com.miui.packageinstaller",
        "com.miui.securitycenter",
        "com.miui.securityadd",
        "com.lbe.security.miui",
        // OPPO / 一加 / realme
        "com.oplus.packageinstaller",
        "com.coloros.packageinstaller",
        "com.coloros.securitypermission",
        "com.oplus.securitypermission",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        // vivo / iQOO
        "com.vivo.permissionmanager",
        "com.vivo.safecenter",
        "com.iqoo.secure",
        // 华为 / 荣耀
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        "com.huawei.securitymgr",
        // 三星
        "com.samsung.android.packageinstaller",
        // Settings 的智能推荐层（会引导去权限页）
        "com.android.settings.intelligence",
    )

    /**
     * 设置类应用：**本身不拦**，只在标题命中 [TITLE_WORDS] 的子页拦。
     */
    val SETTINGS_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.miui.settings",
        "com.coloros.settings",
        "com.oplus.settings",
        "com.vivo.settings",
        "com.huawei.settings",
        "com.hihonor.settings",
        "com.samsung.android.settings",
        "com.google.android.settings",
    )

    /**
     * 标题命中即拦。中英并用——国产 ROM 多是中文，AOSP/国际版是英文。
     */
    val TITLE_WORDS: List<String> = listOf(
        // 权限与授权
        "无障碍", "辅助功能", "Accessibility",
        "开发者选项", "开发人员选项", "Developer options",
        "设备管理", "设备管理器", "Device admin",
        "特殊应用权限", "权限", "Permission",
        "安装未知应用", "未知来源", "安装外部来源", "Install unknown",
        // 锁与生物识别
        "锁屏", "屏幕锁定", "密码", "指纹", "人脸", "生物识别",
        "Security", "Privacy", "安全", "隐私",
    )

    /**
     * 是否是受保护页面。
     *
     * @param packageName 当前前台包名
     * @param titles 当前页面能看到的标题文本（见 [titleTexts] 的取值口径）
     */
    fun isProtected(packageName: String?, titles: List<String>): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName in PACKAGES) return true
        if (packageName !in SETTINGS_PACKAGES) return false
        return titles.any { t -> TITLE_WORDS.any { w -> t.contains(w, ignoreCase = true) } }
    }

    /**
     * 动作端点的**快速**保护判据（v1.6.20）。
     *
     * ## 为什么需要第二个入口
     *
     * [isProtected] 需要先取「页面标题候选」，那要读整棵节点树。而 `/tap`、`/gesture`、
     * `/input` 这些旧端点每次动作都读一遍树的话，会把无障碍 IPC 池占满 ——
     * 正是 `/screen` 被顶成 504 的那条老路。
     *
     * 所以动作侧用两个**便宜**的判据，任一成立就拒绝：
     *
     * 1. **整包保护**：安装器 / 权限控制器 / OEM 安全中心。一个包名就够，零成本。
     * 2. **设置类 + 命中「硬标题词」**：只比对 [HARD_TITLE_WORDS]（无障碍、开发者选项、
     *    安装未知应用、设备管理这几类）。这些词的命中不需要「哪个是标题」这个判断 ——
     *    屏幕上任何位置出现它们，都说明这一页与授权/调试有关。
     *
     * ## 与 [isProtected] 的分工
     *
     * - `/screen?format=doc` 用 [isProtected]：它已经拿到完整标题候选，判得更细
     *   （锁屏/密码/隐私这类泛词也能拦）。
     * - 动作端点用本函数：宁可少拦几个泛词，也不能因为读树把池子占满。
     *   代价是「设置 → 隐私」这类页面在旧端点上不拦 —— 但那类页面没有授权能力，
     *   真正的风险面（无障碍/开发者选项/装未知应用/设备管理）都在硬词里。
     *
     * @param packageName 当前前台包名
     * @param screenText 当前屏幕可见文本（可含坐标，只做子串匹配）
     */
    fun isProtectedFast(packageName: String?, screenText: String): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName in PACKAGES) return true
        if (packageName !in SETTINGS_PACKAGES) return false
        if (screenText.isEmpty()) return false
        return HARD_TITLE_WORDS.any { w -> screenText.contains(w, ignoreCase = true) }
    }

    /**
     * 动作端点用的「硬」标题词：命中即拦，不需要判断哪个是标题。
     *
     * 只放**与授权/调试能力直接相关**的词。放泛词（安全、隐私、密码）会在设置首页
     * 就误拦（首页有「安全与隐私」入口），把正常操作全堵死。
     */
    val HARD_TITLE_WORDS: List<String> = listOf(
        "无障碍", "辅助功能", "Accessibility",
        "开发者选项", "开发人员选项", "Developer options",
        "安装未知应用", "未知来源", "安装外部来源", "Install unknown",
        "设备管理", "设备管理器", "Device admin",
        "特殊应用权限", "Special app access",
        "使用情况访问", "Usage access",
        "悬浮窗", "Display over other apps",
    )

    /**
     * 从屏幕文本里取「页面标题候选」。
     *
     * ## 口径（这里是整个安全层最容易做错的一步）
     *
     * 判据是**位置**，不是容器属性。
     *
     * 曾经写成「在可滚动容器里的文本一律不算标题」，理由是「设置首页滚到中间时
     * 「安全与隐私」是列表项」。实测证明这个口径是错的：**Android 设置页的标题
     * 本身就在 RecyclerView 里**（整页一个滚动容器，标题是它的第一项）。按容器属性
     * 排除，等于把所有设置子页的标题全排除 → isProtected 永远返回 false →
     * 「无障碍 / 开发者选项 / 安装未知应用」这些页面拦不住。
     *
     * 真机实测（模拟器 Android 14，`am start -a android.settings.ACCESSIBILITY_SETTINGS`）：
     *
     *     屏幕 s21 · 设置 · 竖屏 · 共 23 项
     *     顶部
     *         [2] 无障碍 @540,279      ← 4 空格缩进，确实是滚动容器的子节点
     * 
     * 而当时 `protected=false`，一次点击就点在「无障碍」上。
     *
     * 现在改成两个正交条件：
     * - **标题区**：heading 语义，或落在屏幕上部 20% 内
     * - **不在列表深处**：缩进层级 depth 不超过 1（滚动容器的直接子项仍算标题候选，
     *   更深层的是列表行内部结构，那是内容）
     *
     * 这样既不会把列表内容当标题，也不会因为「标题恰好在一个滚动容器里」而失效。
     *
     * @param items 屏幕上的文本项
     * @param screenHeight 屏幕像素高
     * @param limit 最多取几个候选
     */
    fun titleTexts(
        items: List<TitleItem>,
        screenHeight: Int,
        limit: Int = 8,
    ): List<String> {
        if (screenHeight <= 0) return emptyList()
        val out = ArrayList<String>(limit)
        for (item in items) {
            if (out.size >= limit) break
            val text = item.text.trim()
            if (text.isEmpty()) continue
            val inTop = item.centerY < screenHeight * 0.2
            val inHeadingZone = item.heading && item.centerY < screenHeight * 0.3
            // 列表深处的文本是内容，不是标题。
            // 注意：不能用 inScrollable —— 设置页的标题**本身**就在 RecyclerView 里。
            if (item.depth > 1) continue
            if (inHeadingZone || inTop) out.add(text)
        }
        return out
    }

    /** [titleTexts] 的输入项：一个屏幕文本 + 它的位置与缩进层级。 */
    data class TitleItem(
        val text: String,
        val centerY: Int,
        val heading: Boolean = false,
        /** 缩进层级（每层滚动容器 +1）。>1 表示在列表项内部，那是内容不是标题。 */
        val depth: Int = 0,
    )
}

package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.widget.ImageView
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineModeSelector
import app.dsh.mobile.engine.ExtensionManager
import app.dsh.mobile.engine.LinuxExtensionBridge
import app.dsh.mobile.engine.LinuxPackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扩展中心（v1.2.0）：自由下载环境扩展（Python / Git / JDK / FFmpeg…）。
 *
 * 三态红绿灯：
 *  - 红  未下载        → 按钮【下载】
 *  - 黄  已下载未激活  → 按钮【激活】（并入引擎 PATH，自动重启引擎）
 *  - 绿  已激活可用    → 按钮【停用】；长按整行可卸载
 *
 * 列表为程序化构建（清单约 18 项，无需引入 RecyclerView）；
 * 下载在协程 IO 线程执行，进度经 runOnUiThread 回刷行内 ProgressBar。
 */
class ExtensionStoreActivity : ThemedActivity() {
    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true


    private val manager by lazy { (application as DshApp).extensionManager }

    /**
     * 当前是否 Linux 容器模式。
     *
     * Linux 模式下扩展的**包体系完全不同**：Termux 的 `.deb` → Alpine 的 `.apk`，
     * 装到 rootfs 全局位置而非各扩展独立目录。所以本页所有「装了什么/装/删」
     * 都要分派到 [LinuxExtensionBridge]，不能走 [ExtensionManager]。
     *
     * 判定放在 lazy 里只算一次：模式在页面存活期间不会变（切换模式要重启引擎）。
     *
     * Linux 下载通道由 [EXT_LINUX_DOWNLOAD_ENABLED] 控制（当前已开启）。
     * 容器内 `apk add` 主链路已在模拟器实测通过；真机的健壮性回归
     * （无网 / 源不可达 / 批量失败）待真机恢复在线后补。
     */
    private val linuxMode: Boolean by lazy {
        EXT_LINUX_DOWNLOAD_ENABLED && EngineModeSelector.isLinuxEnabled(this)
    }

    /** Linux 模式的包管理器（仅 linuxMode 为 true 时用） */
    private val pkgManager by lazy { LinuxPackageManager.get(this) }

    /**
     * Linux 模式下已装包的快照（包名 -> 版本）。
     *
     * `apk info -v` 是进程调用，不能每行都跑一次（20 行 = 20 次容器启动）。
     * 构建列表前取一次，之后全部查内存。
     */
    private var linuxInstalled: Map<String, String>? = null

    /**
     * 用户为每个扩展选中的版本 id（仅对带 [ExtensionManager.Extension.versions]
     * 的条目有效）。没选过就不在表里 —— 此时按「默认版本」处理，与旧行为一致。
     *
     * 为什么要持久化到 SharedPreferences 而不是只存内存：选 openjdk21 的意图
     * 应该活过 App 重启。否则用户重启后看到的是「默认版本未装」，
     * 而他明明装过 21 —— 会被当成丢状态。
     */
    private fun verPrefs() = getSharedPreferences("ext_versions", MODE_PRIVATE)

    private fun selectedVersion(ext: ExtensionManager.Extension): String? {
        if (ext.versions.isEmpty()) return null
        val v = verPrefs().getString(ext.id, null) ?: return null
        // 版本表里已经没有这个 id（catalog 升级后删掉某版本）→ 视为未选
        return v.takeIf { id -> ext.versions.any { it.id == id } }
    }

    private fun setSelectedVersion(ext: ExtensionManager.Extension, versionId: String) {
        verPrefs().edit().putString(ext.id, versionId).apply()
    }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 任务状态迁移记录（完成/失败 Toast 去重） */
    private val lastSeenTaskState = mutableMapOf<String, ExtensionManager.TaskState>()

    private lateinit var container: LinearLayout
    private lateinit var tvSubtitle: TextView
    private var items: List<ExtensionManager.Extension> = emptyList()
    private val rowRefs = mutableMapOf<String, RowRefs>()

    /** 行内可变控件的引用集（刷新单行用） */
    private class RowRefs(
        val dot: View,
        val stateText: TextView,
        val action: TextView,
        val more: android.widget.ImageView,
        val progress: ProgressBar,
        /**
         * 版本胶囊行（v1.5.5）。仅对 `ext.versions.size > 1` 的条目存在内容。
         *
         * 原来是「点安装 → 弹 setSingleChoiceItems 对话框 → 选完才开始装」，
         * 用户每次装一个多版本扩展都要过一次阻断式弹窗。改成行内胶囊后：
         * 版本切换是**单击**，装/换版本都不再打断阅读。
         */
        val versionBar: LinearLayout,
    )

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.4.18：跟随应用朝向偏好；平板横屏下列表限宽，避免行被拉满整屏
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_extension_store)
        findViewById<MaxWidthLinearLayout>(R.id.extensionStoreBody).maxWidthDp =
            STORE_CONTENT_MAX_WIDTH_DP

        container = findViewById(R.id.listContainer)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        items = manager.loadCatalog()
        buildList()
        // 一键工具条（v1.2.43）：检测 / 全部安装 / 修复损坏 —— 用户与 Agent 共用同一套判定。
        // ⚠️ 必须在 buildList() 之后插入：buildList 开头会 removeAllViews()（v1.2.43 首版
        // 顺序写反 → 工具条被自己清掉，用户「没看到一键检测」）。
        container.addView(buildTools(), 0)
        refreshHeader()

        // 下载任务状态流：Activity 重建后自动恢复进度显示；离开/销毁本页任务继续（后台下载）。
        // 任务在 ExtensionManager 的进程级 scope 执行，本 Activity 只是观察者之一。
        // 初始态全部记入已见：避免把历史完成重放成 Toast（只提示进入之后的新变化）。
        lastSeenTaskState.putAll(manager.tasks.value.mapValues { it.value.state })
        uiScope.launch {
            manager.tasks.collect {
                notifyTaskTransitions()
                items.forEach { refreshRow(it) }
                refreshHeader()
            }
        }
    }

    /** 回到本页时重新对齐朝向：外观页可能刚改过开关/偏好。 */
    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    // ================= 列表构建 =================

    private fun buildList() {
        container.removeAllViews()
        rowRefs.clear()
        // Linux 模式：一次性取回已装包表（`apk info -v` 是容器进程调用，逐行取会把页面卡死）
        if (linuxMode) linuxInstalled = runCatching { pkgManager.installed() }.getOrNull()
        // 工具条常驻列表顶部（buildList 可能被多次调用，如修复后刷新）
        if (toolsBar != null) container.addView(toolsBar)
        var lastCategory: String? = null
        items.forEach { ext ->
            if (ext.category != lastCategory) {
                lastCategory = ext.category
                container.addView(sectionHeader(ext.category))
            }
            container.addView(buildRow(ext))
        }
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Accent.current(this@ExtensionStoreActivity).argb)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(18), dp(8), dp(8))
    }

    private fun buildRow(ext: ExtensionManager.Extension): View {
        val refs = RowRefs(
            dot = View(this).apply {
                setBackgroundResource(R.drawable.bg_status_dot)
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
            },
            stateText = TextView(this).apply {
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            },
            action = TextView(this).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(64)
                 minHeight = dp(48)
                setPadding(dp(14), dp(5), dp(14), dp(5))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
            },
            more = android.widget.ImageView(this).apply {
                // 次要操作（重新安装 / 卸载）收进 ⋯ 菜单：行内只留一个主操作按钮，
                // 把宽度让给扩展名与介绍文案（旧版 ⟳/✕ 两个文字符号按钮把描述挤没了）
                setImageResource(R.drawable.ic_more_vert)
                setColorFilter(getColor(R.color.text_secondary))
                contentDescription = getString(R.string.ext_more_cd)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                val pad = dp(6)
                setPadding(pad, pad, pad, pad)
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                if (tv.resourceId != 0) background = getDrawable(tv.resourceId)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(2) }
                isClickable = true
                isFocusable = true
            },
            progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@ExtensionStoreActivity).argb)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
                ).apply { topMargin = dp(6) }
            },
            versionBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE       // 只有多版本条目才显示（见 refreshVersionBar）
                setPadding(0, dp(7), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            },
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            // 行内主体：图标 + 文案（名称/介绍/状态）+ 状态点 + 主操作 + ⋯ 菜单
            val main = LinearLayout(this@ExtensionStoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@ExtensionStoreActivity).apply {
                    // 品牌图标：dark 芯片 + 分类色字形（v1.2.37 换肤，旧版整块彩底偏刺眼）
                    val resId = ext.iconRes.takeIf { it.isNotEmpty() }
                        ?.let { resources.getIdentifier(it, "drawable", packageName) } ?: 0
                    if (resId != 0) {
                        setImageResource(resId)
                        setColorFilter(getColor(R.color.text_secondary), PorterDuff.Mode.SRC_IN)
                    }
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = getDrawable(R.drawable.bg_icon_chip_soft)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })
                addView(LinearLayout(this@ExtensionStoreActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = dp(12); marginEnd = dp(6) }
                    addView(TextView(this@ExtensionStoreActivity).apply {
                        text = ext.name
                        textSize = 16f
                        setTextColor(getColor(R.color.text_primary))
                    })
                    if (ext.desc.isNotEmpty()) addView(TextView(this@ExtensionStoreActivity).apply {
                        // 介绍文案（catalog desc）：上限两行，超出省略号，不再被按钮挤成竖条
                        text = ext.desc
                        textSize = 12f
                        setTextColor(getColor(R.color.text_secondary))
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    })
                    addView(refs.stateText.apply { setPadding(0, dp(3), 0, 0) })
                })
                addView(refs.dot)
                addView(refs.action)
                addView(refs.more)
            }
            addView(main, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            // 版本胶囊在主体之下、进度条之上：它属于「这一条」的配置项，
            // 放在状态行下方能紧贴扩展名，不会让人以为它属于下一个条目。
            addView(refs.versionBar)
            addView(refs.progress)
        }

        refs.action.setOnClickListener { onAction(ext) }
        refs.more.setOnClickListener { showMoreMenu(ext, it) }
        row.setOnLongClickListener {
            confirmReinstall(ext)
            true
        }
        rowRefs[ext.id] = refs
        refreshRow(ext)
        return row
    }

    /** ⋯ 菜单：已装扩展的次要操作（重新安装 / 卸载）。行内只留主操作按钮，描述文案不再被挤。
     *  自绘 PopupWindow（圆角深色卡片）：无 AppCompat 时框架 PopupMenu 是方形浅色老样式，
     *  与暗色 UI 不搭（用户实测反馈）。 */
    private fun showMoreMenu(ext: ExtensionManager.Extension, anchor: View) {
        if (stateOf(ext) == ExtensionManager.ExtState.NOT_DOWNLOADED) return
        val content = layoutInflater.inflate(R.layout.menu_ext_actions, null)
        val popup = android.widget.PopupWindow(
            content,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = dp(10).toFloat()
            // 透明背景 + outsideTouchable：点外部关闭（PopupWindow 的既定要求）
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            isOutsideTouchable = true
            isClippingEnabled = false
        }
        content.findViewById<View>(R.id.menuReinstall).setOnClickListener {
            popup.dismiss()
            confirmReinstall(ext)
        }
        content.findViewById<View>(R.id.menuUninstall).setOnClickListener {
            popup.dismiss()
            confirmUninstall(ext)
        }
        // 右对齐到 ⋮ 下方：先量出菜单宽，再偏移（锚点宽 - 菜单宽）
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        popup.showAsDropDown(anchor, anchor.width - content.measuredWidth, dp(4))
    }

    // ================= 状态刷新 =================

    /**
     * 统一的状态查询入口：按当前模式分派。
     *
     * 原生模式看 `engine/extensions/<id>/` 目录与 MARKER 文件；Linux 模式看
     * rootfs 里的 apk 包数据库。两者语义不同，但对外都归一到
     * [ExtensionManager.ExtState] 三态，UI 才能共用一套渲染逻辑。
     */
    private fun stateOf(ext: ExtensionManager.Extension): ExtensionManager.ExtState =
        if (!linuxMode) manager.state(ext.id)
        else LinuxExtensionBridge.state(this, ext, linuxInstalled, selectedVersion(ext))

    private fun refreshRow(ext: ExtensionManager.Extension) {
        val refs = rowRefs[ext.id] ?: return
        // AI 通道（/ext/install）与 UI 共享 installing 状态源。
        // 任务快照（StateFlow）也并入判定：Activity 重建后 downloading 集合虽已丢失，
        // 但 tasks 流会立即把「安装中」状态恢复到行上（后台下载 + 进度恢复）。
        val task = manager.tasks.value[ext.id]
        val downloadingNow = manager.isInstalling(ext.id) ||
            (task != null && (task.state == ExtensionManager.TaskState.QUEUED ||
                task.state == ExtensionManager.TaskState.RUNNING))
        val state = stateOf(ext)

        // Linux 模式没有「下载 / 激活」两步语义：apk 装完即可用，故换一套文案。
        // 同一句「Not downloaded」放在容器场景里是错的（用户没在下东西，是装包）。
        val linuxUnavailable = linuxMode && LinuxExtensionBridge.unavailableReason(ext) != null
        val (stateLabel, dotColor) = when {
            linuxUnavailable -> getString(R.string.ext_linux_state_unavailable) to getColor(COLOR_RED)
            downloadingNow -> getString(R.string.ext_state_installing) to getColor(COLOR_YELLOW)
            state == ExtensionManager.ExtState.ACTIVATED -> {
                if (linuxMode) getString(R.string.ext_linux_state_installed) to getColor(COLOR_GREEN)
                else if (manager.needsRestart(ext.id))
                    getString(R.string.ext_state_pending_restart) to getColor(COLOR_YELLOW)
                else getString(R.string.ext_state_activated) to getColor(COLOR_GREEN)
            }
            state == ExtensionManager.ExtState.DOWNLOADED -> {
                if (linuxMode) getString(R.string.ext_linux_state_partial) to getColor(COLOR_YELLOW)
                else getString(R.string.ext_state_downloaded) to getColor(COLOR_YELLOW)
            }
            else -> {
                if (linuxMode) getString(R.string.ext_linux_state_none) to getColor(COLOR_RED)
                else getString(R.string.ext_state_none) to getColor(COLOR_RED)
            }
        }
        // 版本胶囊随之刷新：安装后 ✓ 标记要出现，切换选中态要立刻反映
        refreshVersionBar(ext)

        // 已装扩展在状态行追加实际版本（安装时从 Termux 仓库索引记录）
        val ver = if (downloadingNow || state == ExtensionManager.ExtState.NOT_DOWNLOADED) ""
        else if (linuxMode) {
            // Linux 模式：版本来自 apk 数据库（多个包时取第一个有版本的）。
            // 多版本条目优先显示**用户选中的那个版本**的包版本 —— 否则选了
            // openjdk21 但 17 也装着时，这里会显示 17 的版本号，是误导。
            val have = linuxInstalled ?: emptyMap()
            val verPkgs = LinuxExtensionBridge.resolvePackages(ext, selectedVersion(ext))
                ?: ext.packages.let { LinuxExtensionBridge.alpinePackagesOf(it) }
            verPkgs.firstNotNullOfOrNull { have[it] }?.let { " · v$it" } ?: ""
        } else manager.installedVersion(ext.id)?.let { " · v$it" } ?: ""
        refs.stateText.text = stateLabel + ver
        refs.dot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(dotColor)

        // 按钮：下载(蓝实心) / 激活(橙实心) / 停用(灰描边)；下载中隐藏
        when {
            downloadingNow -> {
                refs.action.visibility = View.GONE
                refs.more.visibility = View.GONE
                refs.progress.visibility = View.VISIBLE
                refs.action.isClickable = false
                // 进度与阶段文案来自任务快照（StateFlow 广播）；无快照时保持既有状态行
                val p = task?.progress ?: 0f
                val bar = refs.progress
                if (p <= 0f) {
                    bar.isIndeterminate = true
                    refs.stateText.text = task?.stage?.ifEmpty { null } ?: stateLabel
                } else {
                    bar.isIndeterminate = false
                    bar.progress = (p * 100).toInt()
                    refs.stateText.text = if (p < 0.95f)
                        getString(R.string.ext_downloading_pct, ext.name, (p * 100).toInt())
                    else task?.stage?.ifEmpty { null } ?: stateLabel
                }
            }
            else -> {
                refs.progress.visibility = View.GONE
                refs.action.visibility = View.VISIBLE
                // ⋯ 仅在已安装态显示（未装时「下载」即是全新安装）
                refs.more.visibility =
                    if (state == ExtensionManager.ExtState.NOT_DOWNLOADED) View.GONE
                    else View.VISIBLE
                when {
                    linuxUnavailable -> {
                        // 该条目在容器里根本没有对应包：置灰且不可点（点了只会得到
                        // `ERROR: unable to select packages`，比置灰体验更差）
                        styleAction(refs.action, getString(R.string.ext_linux_action_unavailable), 0, false)
                        refs.action.isClickable = false
                    }
                    linuxMode && state == ExtensionManager.ExtState.ACTIVATED -> {
                        styleAction(refs.action, getString(R.string.ext_linux_action_remove), 0, false)
                    }
                    linuxMode -> {
                        styleAction(refs.action, getString(R.string.ext_linux_action_install), accentColor(this), true)
                    }
                    state == ExtensionManager.ExtState.NOT_DOWNLOADED -> {
                        styleAction(refs.action, getString(R.string.ext_action_download), accentColor(this), true)
                    }
                    state == ExtensionManager.ExtState.DOWNLOADED -> {
                        styleAction(refs.action, getString(R.string.ext_action_activate), getColor(COLOR_ORANGE), true)
                    }
                    else -> {
                        styleAction(refs.action, getString(R.string.ext_action_deactivate), 0, false)
                    }
                }
            }
        }
    }

    /**
     * 行内主操作按钮的样式（v1.5.5 视觉统一）。
     *
     * ## 为什么从「实心填充」改成「描边」
     * 实测截图（1080×2280）：原按钮是约 65×40dp 的**高饱和 accent 实心块**，
     * 一屏出现 6~7 个，整页视觉重心全被按钮抢走，扩展名与描述反而退成次要信息。
     * 主按钮铺满屏幕时，它就不再是「强调」了 —— 这是廉价感的直接来源。
     *
     * 改成描边 + 近乎透明的底：可点面积不变、对比度仍达标（accent 在深底约 8.9:1），
     * 视觉重量大幅下降。
     *
     * ## 为什么「已装」仍用填充
     * 若未装与已装都是描边、只换颜色，暗色 UI 上「浅蓝描边」与「浅绿描边」的
     * 重量几乎相同，扫一眼分不出哪个装好了。填充块明显更重 ——
     * 状态差异由此变成**重量 + 颜色**双重编码，不只靠颜色。
     *
     * @param filled true = 已装/可操作态（填充 + 绿）；false = 待安装态（描边 + accent）
     * @param muted  true = 不可用（描边 + text_muted + 降透明度 + 不可点）
     */
    /**
     * 主操作按钮的三种外观。
     *
     * ## 为什么带 bgColor 而不是写死颜色
     * 「下载」是 accent 蓝、「激活」是橙、「停用」是灰描边 —— 三种语义靠颜色区分，
     * 颜色必须由调用点决定。
     *
     * ## v1.5.5 回退说明
     * 曾短暂改成「幽灵按钮 + 玻璃卡」（去掉 bgColor、由 drawable 定色），
     * 用户看过后要求退回原样式（「感觉不如之前那种」）。这里恢复为
     * 实心/描边 + 代码 tint 的原实现，但**保留**了 bgColor 参数的显式传递
     * —— 它比"由 drawable 悄悄决定"更好读，也是这次回退中唯一值得留的改动。
     */
    private fun styleAction(btn: TextView, label: String, bgColor: Int, filled: Boolean) {
        btn.text = label
        btn.visibility = View.VISIBLE
        btn.isClickable = true
        if (filled) {
            btn.setBackgroundResource(R.drawable.bg_btn_accent)
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
            btn.setTextColor(0xFF101418.toInt())
        } else {
            btn.setBackgroundResource(R.drawable.bg_btn_outline)
            btn.backgroundTintList = null
            btn.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun refreshHeader() {
        if (linuxMode) {
            // Linux 模式的「已装」是 rootfs 全局包数，与原生模式的扩展目录口径不同，
            // 故换一条文案（同一条文案套两种语义会让用户以为数字算错了）
            val have = linuxInstalled ?: emptyMap()
            val active = items.count { LinuxExtensionBridge.state(this, it, have) == ExtensionManager.ExtState.ACTIVATED }
            val osName = runCatching {
                app.dsh.mobile.engine.LinuxEnvironmentManager.get(this).activeOption().displayName
            }.getOrDefault("Linux")
            tvSubtitle.text = getString(
                R.string.ext_subtitle_linux, osName, active, items.size
            )
            return
        }
        val active = manager.activeCount()
        tvSubtitle.text = getString(
            R.string.ext_subtitle, manager.deviceAbiKey(), active, items.size
        )
    }

    // ================= 动作 =================

    private fun onAction(ext: ExtensionManager.Extension) {
        // Linux 模式没有「下载 → 激活」两步：apk 装完即可用，装/删都是单步。
        if (linuxMode) {
            // v1.5.5：版本选择已在行内胶囊里完成，主按钮直接执行「按当前选中版本装/卸」，
            // 不再先弹选择框。少一次打断，且用户本来就能在装之前看到当前选的是哪版。
            if (stateOf(ext) == ExtensionManager.ExtState.ACTIVATED) linuxRemove(ext)
            else linuxInstall(ext)
            return
        }
        when (stateOf(ext)) {
            ExtensionManager.ExtState.NOT_DOWNLOADED -> startDownload(ext)
            ExtensionManager.ExtState.DOWNLOADED -> activate(ext)
            ExtensionManager.ExtState.ACTIVATED -> deactivate(ext)
        }
    }

    /**
     * 多版本选择对话框。
     *
     * 为什么不能做成「装新替旧」：Alpine 里这些版本是**并存**的
     * （openjdk8/11/17/21 有各自独立的 /usr/lib/jvm/jdk-* 目录，
     * clang15~20 各有 /usr/lib/llvm*），装新版本不会替换旧版本。
     * 所以必须让用户明确选一个，而不是自动挑最新的 ——
     * 「最新」未必是他项目要的那个。
     */
    /**
     * 渲染行内版本胶囊（v1.5.5）。
     *
     * ## 为什么要从弹窗改成行内
     * 原实现是：点「安装」→ 弹 `setSingleChoiceItems` 单选对话框 → 选一个 →
     * 才开始装。用户原话「每次弹窗都很难受」—— 对一个**高频、可预测**的
     * 配置项（选哪个版本），阻断式弹窗是过度设计：它把「读列表」和「选版本」
     * 两次操作强行串成一次打断。
     *
     * 改成行内胶囊后：版本始终可见（当前选中态一眼看到），切换是单击，
     * 装/换版本都不再弹窗。
     *
     * ## 胶囊上的标记
     * - 实心胶囊 = 用户选中的版本
     * - 尾部 `✓` = 这个版本的包**已全部装进 rootfs**
     * - 尾部 `n/m` = 部分安装（容器里这几个包是并存的多包条目）
     * 这是用户唯一能看出「哪个版本真装上了」的地方 —— 只标选中不标安装状态，
     * 会出现「选着 21 但装的是 17」的困惑。
     */
    private fun refreshVersionBar(ext: ExtensionManager.Extension) {
        val refs = rowRefs[ext.id] ?: return
        // 单版本条目没有可选项，整行隐藏（不占位）
        if (ext.versions.size <= 1) {
            refs.versionBar.visibility = View.GONE
            return
        }
        refs.versionBar.visibility = View.VISIBLE
        refs.versionBar.removeAllViews()
        val have = linuxInstalled ?: emptyMap()
        val cur = selectedVersion(ext)

        ext.versions.forEach { v ->
            val pkgs = LinuxExtensionBridge.alpinePackagesOf(v.packages)
            val n = pkgs.count { have.containsKey(it) }
            val fully = pkgs.isNotEmpty() && n == pkgs.size
            val partially = n > 0 && !fully
            val selected = (cur == v.id) || (cur == null && v == ext.versions.first())

            val chip = TextView(this).apply {
                // 只显示版本号：胶囊宽度有限，"OpenJDK 17" 这种 label 会被截断，
                // 而条目名（OpenJDK）已经在上一行写着了，这里只需要 17 / 21。
                text = buildString {
                    append(v.label.substringAfterLast(' '))
                    when {
                        fully -> append("  ✓")
                        partially -> append("  ").append(n).append('/').append(pkgs.size)
                    }
                }
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.md3_text_caption),
                )
                gravity = Gravity.CENTER
                val ph = dp(11)
                val pv = dp(5)
                setPadding(ph, pv, ph, pv)
                if (selected) {
                    background = getDrawable(R.drawable.bg_chip_selected)
                    setTextColor(getColor(R.color.accent))
                    typeface = Typeface.DEFAULT_BOLD
                } else {
                    background = getDrawable(R.drawable.bg_chip)
                    setTextColor(getColor(R.color.text_secondary))
                }
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    if (selected) return@setOnClickListener
                    setSelectedVersion(ext, v.id)
                    // 只改选择、不自动装卸：用户可能只是想看看这个版本的状态。
                    // 真正的装卸仍由右侧主按钮触发 —— 单击胶囊=选，点按钮=执行。
                    refreshVersionBar(ext)
                    refreshRow(ext)
                    refreshHeader()
                }
            }
            refs.versionBar.addView(chip)
        }
    }

    /**
     * Linux 模式安装：`apk add <包名...>`。
     *
     * 与原生模式的差异：
     * - 装到 rootfs 全局位置，所有扩展共享同一份（不再各自独立目录）
     * - 没有「激活」步骤，装完即可在容器终端里直接用
     * - 耗时明显更长（要解包、跑 post-install 脚本），故全程显示进度不可取消
     */
    private fun linuxInstall(ext: ExtensionManager.Extension) {
        LinuxExtensionBridge.unavailableReason(ext)?.let { reason ->
            AlertDialog.Builder(this)
                .setTitle(ext.name)
                .setMessage(getString(R.string.ext_linux_unavailable, reason))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val ver = selectedVersion(ext)
        val pkgs = LinuxExtensionBridge.resolvePackages(ext, ver) ?: emptyList()
        val shownName = ver?.let { id -> ext.versions.firstOrNull { it.id == id }?.label ?: id }
            ?.let { "$ver ($it)" } ?: ext.name
        val progress = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.ext_linux_installing, shownName, pkgs.joinToString(" ")))
            setCancelable(false)
            show()
        }
        Thread({
            val r = runCatching { LinuxExtensionBridge.install(this, ext, ver) }
                .getOrElse { LinuxPackageManager.OpResult(false, pkgs, it.message ?: "unknown error") }
            runOnUiThread {
                progress.dismiss()
                // 装完必须重取已装表，否则状态点不更新（缓存里还是旧的）
                linuxInstalled = runCatching { pkgManager.installed() }.getOrNull()
                if (r.ok) {
                    Toast.makeText(
                        this,
                        getString(R.string.ext_linux_install_done, ext.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.ext_linux_install_failed, ext.name))
                        .setMessage(r.rawOutput.ifBlank { getString(R.string.ext_unknown_error) })
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                refreshRow(ext)
                refreshHeader()
            }
        }, "ext-linux-install").start()
    }

    /** Linux 模式卸载：`apk del <包名...>` */
    private fun linuxRemove(ext: ExtensionManager.Extension) {
        val pkgs = LinuxExtensionBridge.alpinePackagesOf(ext.packages)
        if (pkgs.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_uninstall_title, ext.name))
            .setMessage(getString(R.string.ext_linux_uninstall_msg, pkgs.joinToString(" ")))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.ext_dialog_uninstall)) { _, _ ->
                val progress = android.app.ProgressDialog(this).apply {
                    setMessage(getString(R.string.ext_linux_removing, ext.name))
                    setCancelable(false)
                    show()
                }
                Thread({
                    val r = runCatching { LinuxExtensionBridge.remove(this, ext) }
                        .getOrElse { LinuxPackageManager.OpResult(false, pkgs, it.message ?: "unknown error") }
                    runOnUiThread {
                        progress.dismiss()
                        linuxInstalled = runCatching { pkgManager.installed() }.getOrNull()
                        if (!r.ok) {
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.ext_linux_remove_failed, ext.name))
                                .setMessage(r.rawOutput.ifBlank { getString(R.string.ext_unknown_error) })
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        refreshRow(ext)
                        refreshHeader()
                    }
                }, "ext-linux-remove").start()
            }
            .show()
    }

    private fun startDownload(ext: ExtensionManager.Extension) {
        // 入队即返回：任务在 Manager 的进程级 scope 执行（后台下载，离开本页不中断），
        // 进度经 tasks 状态流广播 —— 本 Activity 只是观察者之一。
        Toast.makeText(this, getString(R.string.ext_download_start, ext.name), Toast.LENGTH_SHORT).show()
        runCatching { manager.enqueue(ext) }
            .onFailure { e ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ext_download_failed, ext.name))
                    .setMessage(e.message ?: getString(R.string.ext_unknown_error))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        refreshRow(ext)
        refreshHeader()
    }

    /** tasks 状态迁移检测：DONE/FAILED 时弹 Toast/对话框（与 enqueue 入口解耦） */
    private fun notifyTaskTransitions() {
        manager.tasks.value.forEach { (id, task) ->
            val last = lastSeenTaskState[id]
            if (last == task.state) return@forEach
            lastSeenTaskState[id] = task.state
            val name = items.firstOrNull { it.id == id }?.name ?: id
            when (task.state) {
                ExtensionManager.TaskState.DONE ->
                    Toast.makeText(this, getString(R.string.ext_download_done, name), Toast.LENGTH_SHORT).show()
                ExtensionManager.TaskState.FAILED ->
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.ext_download_failed, name))
                        .setMessage(task.error ?: getString(R.string.ext_unknown_error))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                else -> {}
            }
        }
    }

    private fun activate(ext: ExtensionManager.Extension) {
        runCatching { manager.activate(ext.id) }
            .onSuccess {
                // 不自动重启引擎：主线程等待引擎退出会 ANR（实测事故）。
                // 行上显示「重启引擎后生效」警告，用户手动重启（healthy）后自动解除。
                Toast.makeText(this, getString(R.string.ext_activate_toast), Toast.LENGTH_SHORT).show()
                refreshRow(ext)
                refreshHeader()
            }
    }

    private fun deactivate(ext: ExtensionManager.Extension) {
        manager.deactivate(ext.id)
        Toast.makeText(this, getString(R.string.ext_deactivate_toast), Toast.LENGTH_SHORT).show()
        refreshRow(ext)
        refreshHeader()
    }

    // ================= 一键工具条（检测 / 全部安装 / 修复损坏） =================

    private var toolsBar: View? = null

    private fun buildTools(): View = toolsBar ?: LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(10), dp(12), dp(4))
        fun tool(label: String, color: Int, filled: Boolean, onClick: () -> Unit): TextView =
            TextView(this@ExtensionStoreActivity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { onClick() }
                styleAction(this, label, color, filled)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(4); marginEnd = dp(4) }
            }
        addView(tool(getString(R.string.ext_tool_check), accentColor(this@ExtensionStoreActivity), true) { runHealthCheck() })
        addView(tool(getString(R.string.ext_tool_install_all), getColor(COLOR_ORANGE), true) { installAllMissing() })
        addView(tool(getString(R.string.ext_tool_repair), 0, false) { repairBroken() })
    }.also { toolsBar = it }

    /** 一键检测：只读健康检查（同 AI 的 GET /ext/check 判定），结果弹窗给出行级原因 */
    private fun runHealthCheck() {
        val progress = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.ext_checking)); setCancelable(false); show()
        }
        Thread({
            val health = runCatching { manager.checkHealth() }.getOrElse { emptyList() }
            runOnUiThread {
                progress.dismiss()
                val installed = health.filter { it.installed }
                if (installed.isEmpty()) {
                    alert(getString(R.string.ext_check_title), getString(R.string.ext_check_none)); return@runOnUiThread
                }
                val broken = installed.filter { !it.ok }
                val detail = if (broken.isEmpty()) ""
                    else broken.joinToString("\n") { "· ${it.name}: ${it.reason()}" }
                val msg = if (broken.isEmpty())
                    getString(R.string.ext_check_all_ok, installed.size)
                else getString(R.string.ext_check_summary, installed.size, installed.size - broken.size, broken.size, detail)
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ext_check_title))
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .apply {
                        if (broken.isNotEmpty()) setNeutralButton(getString(R.string.ext_repair_go)) { _, _ -> confirmRepair(broken) }
                    }
                    .show()
            }
        }, "ext-health").apply { isDaemon = true; start() }
    }

    private fun alert(title: String, msg: String) {
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    /** 一键安装全部：把未安装的扩展全部入队（并发下载 + 按扩展并发解包） */
    private fun installAllMissing() {
        val missing = items.filter { stateOf(it) == ExtensionManager.ExtState.NOT_DOWNLOADED }
        if (missing.isEmpty()) { alert(getString(R.string.ext_install_all_title), getString(R.string.ext_install_all_none)); return }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_install_all_title))
            .setMessage(getString(R.string.ext_install_all_msg, missing.size))
            .setPositiveButton(getString(R.string.ext_install_all_go)) { _, _ ->
                if (linuxMode) {
                    // Linux 模式一次 apk add 全部包名：apk 自己会解析依赖闭包，
                    // 分多次调用反而重复解析索引（实测 20 个扩展逐个装要 4 分钟+）
                    val pkgs = missing.flatMap { LinuxExtensionBridge.alpinePackagesOf(it.packages) }
                        .distinct()
                    val progress = android.app.ProgressDialog(this).apply {
                        setMessage(getString(R.string.ext_linux_installing_all, pkgs.size))
                        setCancelable(false)
                        show()
                    }
                    Thread({
                        val r = runCatching { pkgManager.install(pkgs) }
                            .getOrElse { LinuxPackageManager.OpResult(false, pkgs, it.message ?: "unknown error") }
                        runOnUiThread {
                            progress.dismiss()
                            linuxInstalled = runCatching { pkgManager.installed() }.getOrNull()
                            Toast.makeText(
                                this,
                                if (r.ok) getString(R.string.ext_install_all_started, missing.size)
                                else getString(R.string.ext_linux_install_failed, missing.size) +
                                    ": " + r.rawOutput.take(200),
                                Toast.LENGTH_LONG
                            ).show()
                            items.forEach { refreshRow(it) }
                            refreshHeader()
                        }
                    }, "ext-linux-install-all").start()
                } else {
                    missing.forEach { runCatching { manager.enqueue(it) } }
                    Toast.makeText(this, getString(R.string.ext_install_all_started, missing.size), Toast.LENGTH_SHORT).show()
                    refreshHeader()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 修复损坏：健康检查不通过的已装扩展 → 覆盖重装（走同一队列） */
    private fun repairBroken() {
        // Linux 模式没有 ExtensionManager 的 MARKER/健康检查体系：包是否完整由 apk 自己
        // 的数据库保证（`apk fix` 可修），所以「修复」改为对已装条目重跑一次 apk add。
        if (linuxMode) {
            val progress = android.app.ProgressDialog(this).apply {
                setMessage(getString(R.string.ext_checking)); setCancelable(false); show()
            }
            Thread({
                val have = runCatching { pkgManager.installed() }.getOrElse { emptyMap() }
                val partial = items.filter {
                    LinuxExtensionBridge.state(this, it, have) == ExtensionManager.ExtState.DOWNLOADED
                }
                runOnUiThread {
                    progress.dismiss()
                    if (partial.isEmpty()) {
                        alert(getString(R.string.ext_repair_title), getString(R.string.ext_repair_none))
                        return@runOnUiThread
                    }
                    val pkgs = partial.flatMap { LinuxExtensionBridge.alpinePackagesOf(it.packages) }.distinct()
                    val p2 = android.app.ProgressDialog(this).apply {
                        setMessage(getString(R.string.ext_linux_installing_all, pkgs.size))
                        setCancelable(false); show()
                    }
                    Thread({
                        val r = runCatching { pkgManager.install(pkgs) }
                            .getOrElse { LinuxPackageManager.OpResult(false, pkgs, it.message ?: "unknown error") }
                        runOnUiThread {
                            p2.dismiss()
                            linuxInstalled = runCatching { pkgManager.installed() }.getOrNull()
                            Toast.makeText(
                                this,
                                if (r.ok) getString(R.string.ext_repair_started, partial.size)
                                else getString(R.string.ext_unknown_error) + ": " + r.rawOutput.take(200),
                                Toast.LENGTH_LONG
                            ).show()
                            items.forEach { refreshRow(it) }
                            refreshHeader()
                        }
                    }, "ext-linux-repair").start()
                }
            }, "ext-linux-repair-scan").start()
            return
        }
        val progress = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.ext_checking)); setCancelable(false); show()
        }
        Thread({
            val broken = runCatching { manager.checkHealth() }.getOrElse { emptyList() }
                .filter { it.installed && !it.ok }
            runOnUiThread {
                progress.dismiss()
                if (broken.isEmpty()) { alert(getString(R.string.ext_repair_title), getString(R.string.ext_repair_none)); return@runOnUiThread }
                confirmRepair(broken)
            }
        }, "ext-repair-scan").apply { isDaemon = true; start() }
    }

    private fun confirmRepair(broken: List<ExtensionManager.ExtHealth>) {
        val detail = broken.joinToString("\n") { "\u00b7 ${it.name}: ${it.reason()}" }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_repair_title))
            .setMessage(getString(R.string.ext_repair_msg, broken.size, detail))
            .setPositiveButton(getString(R.string.ext_repair_go)) { _, _ ->
                val byId = items.associateBy { it.id }
                var n = 0
                broken.forEach { h -> byId[h.id]?.let { runCatching { manager.enqueue(it) }.onSuccess { n++ } } }
                Toast.makeText(this, getString(R.string.ext_repair_started, n), Toast.LENGTH_SHORT).show()
                refreshHeader()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmUninstall(ext: ExtensionManager.Extension) {
        if (stateOf(ext) == ExtensionManager.ExtState.NOT_DOWNLOADED) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_uninstall_title))
            .setMessage(getString(R.string.ext_uninstall_msg, ext.name))
            .setPositiveButton(getString(R.string.ext_dialog_uninstall)) { _, _ ->
                if (linuxMode) {
                    linuxRemove(ext)
                } else {
                    manager.remove(ext.id)   // 不自动重启（同激活）：行上警告需重启清除残留
                    refreshRow(ext)
                    refreshHeader()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 重新安装：覆盖式重下（先删旧目录再解包发布）——修复历史残缺安装（负载缺失）。 */
    private fun confirmReinstall(ext: ExtensionManager.Extension) {
        if (stateOf(ext) == ExtensionManager.ExtState.NOT_DOWNLOADED) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_reinstall_title))
            .setMessage(
                if (linuxMode)
                    getString(R.string.ext_linux_reinstall_msg, ext.name,
                        LinuxExtensionBridge.alpinePackagesOf(ext.packages).joinToString(" + "))
                else getString(R.string.ext_reinstall_msg, ext.name, ext.packages.joinToString(" + "))
            )
            .setPositiveButton(getString(R.string.ext_dialog_reinstall)) { _, _ ->
                if (linuxMode) {
                    linuxInstall(ext)   // apk 无「重下」概念，重新 add 即覆盖修复
                    return@setPositiveButton
                }
                Toast.makeText(this, getString(R.string.ext_reinstall_start, ext.name), Toast.LENGTH_SHORT).show()
                runCatching { manager.enqueue(ext) }
                    .onFailure { e ->
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.ext_download_failed, ext.name))
                            .setMessage(e.message ?: getString(R.string.ext_unknown_error))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                refreshRow(ext)
                refreshHeader()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ================= 杂项 =================

    /**
     * 分类 → 图标着色。
     *
     * 不能用 else 一把兜住所有未知分类：那会让「调试分析 / 交叉编译」
     * 与「系统与多媒体」同色，用户靠颜色分辨分类的能力就没了。
     * 新分类必须显式给色，漏给时才会落到 accent_alt。
     */
    private fun categoryColor(category: String): Int = when (category) {
        "Language runtimes" -> getColor(R.color.success)
        "Build & tooling" -> getColor(R.color.warning)
        "Debugging" -> getColor(R.color.danger)
        "Cross compiling" -> getColor(R.color.accent_alt)
        "System & media" -> getColor(R.color.accent)
        // 中文 catalog 的分类名走资源，不写字面量（check-i18n 的 D 段会拦 CJK）
        getString(R.string.ext_cat_lang) -> getColor(R.color.success)
        getString(R.string.ext_cat_build) -> getColor(R.color.warning)
        getString(R.string.ext_cat_debug) -> getColor(R.color.danger)
        getString(R.string.ext_cat_cross) -> getColor(R.color.accent_alt)
        getString(R.string.ext_cat_media) -> getColor(R.color.accent)
        else -> getColor(R.color.accent_alt)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        // 徽章/按钮用色改为主题资源引用（companion 存 resId，实例侧 getColor 解析）
        val COLOR_RED = R.color.danger
        val COLOR_YELLOW = R.color.warning
        val COLOR_GREEN = R.color.success
        /** 强调色（v1.4.23 起随配色方案变化，故不能在 companion 里固化） */
        fun accentColor(ctx: android.content.Context): Int = Accent.current(ctx).argb
        val COLOR_ORANGE = R.color.warning

        /** 平板/横屏下列表内容最大宽度（dp） */
        const val STORE_CONTENT_MAX_WIDTH_DP = 900

        /**
         * Linux 模式下的扩展下载通道开关。
         *
         * **当前为 true**（2026-11-07 按用户要求开启）。
         *
         * 开启依据——容器内 `apk add` 全链路已在**模拟器（x86_64 / Android 14）**
         * 实测通过：
         *  · `apk update` → `OK: 26337 distinct packages available`；
         *  · `apk add sway` → 91 包 / 360 MiB 从 mirrors.aliyun.com 正常装完；
         *  · `apk add grim wlr-protocols foot font-noto` → 全部成功；
         *  · 装完的 `sway` 能真正跑起来并开出窗口（同轮 GUI 实测）。
         *
         * **仍未完成的验收**（真机 arm64 连续多轮离线，无法执行）：
         *  1. 真机（arm64）重复上述 `apk add` 与「装完可用」；
         *  2. 失败路径（无网、源不可达、包名不存在）不卡死 UI；
         *  3. 「全部安装」批量路径不因单包失败而整批中断。
         *
         * 三条都是**健壮性**验证，不影响主链路的正确性；模拟器已证明主链路通。
         * 真机恢复在线后应优先补 1~3。
         *
         * 开关只改本页的行为，不动 rootfs 与 runner。
         */
        const val EXT_LINUX_DOWNLOAD_ENABLED = true
    }
}

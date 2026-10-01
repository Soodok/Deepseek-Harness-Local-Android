package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
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
import app.dsh.mobile.engine.ExtensionManager
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
class ExtensionStoreActivity : Activity() {

    private val manager by lazy { (application as DshApp).extensionManager }
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
    )

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_extension_store)

        container = findViewById(R.id.listContainer)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        items = manager.loadCatalog()
        buildList()
        // 一键工具条（v1.2.43）：检测 / 全部安装 / 修复损坏 —— 用户与 Agent 共用同一套判定。
        // ⚠️ 必须在 buildList() 之后插入：buildList 开头会 removeAllViews()（v1.2.43 首版
        // 顺序写反 → 工具条被自己清掉，用户"没看到一键检测"）。
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

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    // ================= 列表构建 =================

    private fun buildList() {
        container.removeAllViews()
        rowRefs.clear()
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
        setTextColor(0xFF7DD3FC.toInt())
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
                setTextColor(0xFF8A94A3.toInt())
            },
            action = TextView(this).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(64)
                setPadding(dp(14), dp(5), dp(14), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
            },
            more = android.widget.ImageView(this).apply {
                // 次要操作（重新安装 / 卸载）收进 ⋯ 菜单：行内只留一个主操作按钮，
                // 把宽度让给扩展名与介绍文案（旧版 ⟳/✕ 两个文字符号按钮把描述挤没了）
                setImageResource(R.drawable.ic_more_vert)
                setColorFilter(0xFF8A94A3.toInt())
                contentDescription = getString(R.string.ext_more_cd)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                val pad = dp(6)
                setPadding(pad, pad, pad, pad)
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                if (tv.resourceId != 0) background = getDrawable(tv.resourceId)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(2) }
                isClickable = true
                isFocusable = true
            },
            progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF7DD3FC.toInt())
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
                ).apply { topMargin = dp(6) }
            },
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
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
                        setColorFilter(categoryColor(ext.category), PorterDuff.Mode.SRC_IN)
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
                        setTextColor(0xFFFFFFFF.toInt())
                    })
                    if (ext.desc.isNotEmpty()) addView(TextView(this@ExtensionStoreActivity).apply {
                        // 介绍文案（catalog desc）：上限两行，超出省略号，不再被按钮挤成竖条
                        text = ext.desc
                        textSize = 12f
                        setTextColor(0xFF9AA5B4.toInt())
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
        if (manager.state(ext.id) == ExtensionManager.ExtState.NOT_DOWNLOADED) return
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

    private fun refreshRow(ext: ExtensionManager.Extension) {
        val refs = rowRefs[ext.id] ?: return
        // AI 通道（/ext/install）与 UI 共享 installing 状态源。
        // 任务快照（StateFlow）也并入判定：Activity 重建后 downloading 集合虽已丢失，
        // 但 tasks 流会立即把「安装中」状态恢复到行上（后台下载 + 进度恢复）。
        val task = manager.tasks.value[ext.id]
        val downloadingNow = manager.isInstalling(ext.id) ||
            (task != null && (task.state == ExtensionManager.TaskState.QUEUED ||
                task.state == ExtensionManager.TaskState.RUNNING))
        val state = manager.state(ext.id)

        val (stateLabel, dotColor) = when {
            downloadingNow -> getString(R.string.ext_state_installing) to COLOR_YELLOW
            state == ExtensionManager.ExtState.ACTIVATED ->
                if (manager.needsRestart(ext.id))
                    getString(R.string.ext_state_pending_restart) to COLOR_YELLOW
                else getString(R.string.ext_state_activated) to COLOR_GREEN
            state == ExtensionManager.ExtState.DOWNLOADED ->
                getString(R.string.ext_state_downloaded) to COLOR_YELLOW
            else -> getString(R.string.ext_state_none) to COLOR_RED
        }
        // 已装扩展在状态行追加实际版本（安装时从 Termux 仓库索引记录）
        val ver = if (!downloadingNow && state != ExtensionManager.ExtState.NOT_DOWNLOADED)
            manager.installedVersion(ext.id)?.let { " · v$it" } ?: "" else ""
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
                when (state) {
                    ExtensionManager.ExtState.NOT_DOWNLOADED -> {
                        styleAction(refs.action, getString(R.string.ext_action_download), COLOR_BLUE, true)
                    }
                    ExtensionManager.ExtState.DOWNLOADED -> {
                        styleAction(refs.action, getString(R.string.ext_action_activate), COLOR_ORANGE, true)
                    }
                    ExtensionManager.ExtState.ACTIVATED -> {
                        styleAction(refs.action, getString(R.string.ext_action_deactivate), 0, false)
                    }
                }
            }
        }
    }

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
            btn.setTextColor(0xFFB0BAC7.toInt())
        }
    }

    private fun refreshHeader() {
        val active = manager.activeCount()
        tvSubtitle.text = getString(
            R.string.ext_subtitle, manager.deviceAbiKey(), active, items.size
        )
    }

    // ================= 动作 =================

    private fun onAction(ext: ExtensionManager.Extension) {
        when (manager.state(ext.id)) {
            ExtensionManager.ExtState.NOT_DOWNLOADED -> startDownload(ext)
            ExtensionManager.ExtState.DOWNLOADED -> activate(ext)
            ExtensionManager.ExtState.ACTIVATED -> deactivate(ext)
        }
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
        addView(tool(getString(R.string.ext_tool_check), COLOR_BLUE, true) { runHealthCheck() })
        addView(tool(getString(R.string.ext_tool_install_all), COLOR_ORANGE, true) { installAllMissing() })
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
                    else broken.joinToString("\n") { "· ${it.name}：${it.reason()}" }
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
        val missing = items.filter { manager.state(it.id) == ExtensionManager.ExtState.NOT_DOWNLOADED }
        if (missing.isEmpty()) { alert(getString(R.string.ext_install_all_title), getString(R.string.ext_install_all_none)); return }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_install_all_title))
            .setMessage(getString(R.string.ext_install_all_msg, missing.size))
            .setPositiveButton(getString(R.string.ext_install_all_go)) { _, _ ->
                missing.forEach { runCatching { manager.enqueue(it) } }
                Toast.makeText(this, getString(R.string.ext_install_all_started, missing.size), Toast.LENGTH_SHORT).show()
                refreshHeader()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 修复损坏：健康检查不通过的已装扩展 → 覆盖重装（走同一队列） */
    private fun repairBroken() {
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
        val detail = broken.joinToString("\n") { "· ${it.name}：${it.reason()}" }
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
        if (manager.state(ext.id) == ExtensionManager.ExtState.NOT_DOWNLOADED) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_uninstall_title))
            .setMessage(getString(R.string.ext_uninstall_msg, ext.name))
            .setPositiveButton(getString(R.string.ext_dialog_uninstall)) { _, _ ->
                manager.remove(ext.id)   // 不自动重启（同激活）：行上警告需重启清除残留
                refreshRow(ext)
                refreshHeader()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 重新安装：覆盖式重下（先删旧目录再解包发布）——修复历史残缺安装（负载缺失）。 */
    private fun confirmReinstall(ext: ExtensionManager.Extension) {
        if (manager.state(ext.id) == ExtensionManager.ExtState.NOT_DOWNLOADED) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_reinstall_title))
            .setMessage(getString(R.string.ext_reinstall_msg, ext.name, ext.packages.joinToString(" + ")))
            .setPositiveButton(getString(R.string.ext_dialog_reinstall)) { _, _ ->
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

    private fun categoryColor(category: String): Int = when (category) {
        "语言运行时" -> 0xFF6EE7B7.toInt()
        "编译构建" -> 0xFFFFB74D.toInt()
        else -> 0xFFA78BFA.toInt()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val COLOR_RED = 0xFFFF5A5F.toInt()
        val COLOR_YELLOW = 0xFFFFB74D.toInt()
        val COLOR_GREEN = 0xFF6EE7B7.toInt()
        val COLOR_BLUE = 0xFF7DD3FC.toInt()
        val COLOR_ORANGE = 0xFFFFB74D.toInt()
    }
}

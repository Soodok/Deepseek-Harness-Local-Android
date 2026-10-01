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
        val del: TextView,
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
            del = TextView(this).apply {
                text = "✕"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setTextColor(0xFF8A94A3.toInt())
                background = getDrawable(R.drawable.bg_btn_outline)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
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
            // 行内主体：图标 + 文案 + 状态点 + 按钮
            val main = LinearLayout(this@ExtensionStoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@ExtensionStoreActivity).apply {
                    // 官方品牌图标：17 个 Simple Icons/Material 矢量 + ImageMagick 官方 logo PNG，
                    // 统一 SRC_IN 白色（彩色 chip 上剪影风格）；catalog iconRes 字段驱动
                    val resId = ext.iconRes.takeIf { it.isNotEmpty() }
                        ?.let { resources.getIdentifier(it, "drawable", packageName) } ?: 0
                    if (resId != 0) {
                        setImageResource(resId)
                        setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                    }
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = getDrawable(R.drawable.bg_icon_chip)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        categoryColor(ext.category)
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                })
                addView(LinearLayout(this@ExtensionStoreActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = dp(14); marginEnd = dp(8) }
                    addView(TextView(this@ExtensionStoreActivity).apply {
                        text = ext.name
                        textSize = 16f
                        setTextColor(0xFFFFFFFF.toInt())
                    })
                    addView(TextView(this@ExtensionStoreActivity).apply {
                        text = subLine(ext)
                        textSize = 12f
                        setTextColor(0xFF8A94A3.toInt())
                        setPadding(0, dp(2), 0, 0)
                    })
                    addView(refs.stateText.apply { setPadding(0, dp(2), 0, 0) })
                })
                addView(refs.dot)
                addView(refs.action)
                addView(refs.del)
            }
            addView(main, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(refs.progress)
        }

        refs.action.setOnClickListener { onAction(ext) }
        refs.del.setOnClickListener { confirmUninstall(ext) }
        row.setOnLongClickListener {
            confirmUninstall(ext)
            true
        }
        rowRefs[ext.id] = refs
        refreshRow(ext)
        return row
    }

    private fun subLine(ext: ExtensionManager.Extension): String =
        getString(R.string.ext_repo_line, ext.packages.joinToString(" + "))

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
                refs.del.visibility = View.GONE
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
                refs.del.visibility =
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

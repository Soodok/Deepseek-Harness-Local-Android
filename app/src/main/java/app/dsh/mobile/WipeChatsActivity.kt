package app.dsh.mobile

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.ChatWiper
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.PluginMarket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 删除对话（v1.6.17）。
 *
 * 用户需求原文：「在维护与高级里加一个删除对话内容的新功能，允许在里面直接让
 * 用户删对话，或者一键清空所有对话记录」+「不要启动时读取，一定不要让任何事情
 * 影响启动速度」。
 *
 * ## 懒加载是硬约束，不是优化
 *
 * 会话枚举会遍历 `sessions/` 下所有目录、读投影缓存、统计体积 —— 几百个会话时
 * 是几百次 IO。**绝不能挂在启动路径上**。所以：
 *
 * · 本 Activity 只在用户主动点「删除对话」时创建；
 * · 列表在 [android.os.HandlerThread] 之外的普通 [Thread] 里扫（见 [load]）；
 * · 在此之前 UI 只显示一个 ProgressBar，不预先算任何东西。
 *
 * `MainActivity` / `DshApp` / `EngineSupervisor` 的启动序列完全不引用本类与
 * [ChatWiper]，这一点由探针反向锁定。
 *
 * ## 为什么删除要停引擎
 *
 * 引擎把 `workspace.json` 当独占文件（启动读一次进内存，之后整份写回）。
 * 运行期删了会话文件，引擎下一次变更会用内存状态把注册表写回来 —— 列表里就会
 * 出现一堆点进去空白的幽灵条目。[ChatWiper] 的类注释里记了 `ChatRecovery`
 * 踩这个坑的实测。
 *
 * 所以本页面的删除动作固定三步：**停引擎 → 删 → 重启引擎**，全程在一个
 * 后台线程里顺序执行，中途失败也会把引擎重新拉起来（不能让用户卡在「引擎停了」
 * 的状态里）。
 */
class WipeChatsActivity : ThemedActivity() {

    /** 属于设置体系：启用「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    private data class Row(val item: ChatWiper.Item, val check: CheckBox)

    private val rows = ArrayList<Row>()
    private var snapshot: ChatWiper.Snapshot? = null
    private var busy = false
    private var loading = false

    private lateinit var listChats: LinearLayout
    private lateinit var boxLoading: LinearLayout
    private lateinit var txtEmpty: TextView
    private lateinit var txtSelection: TextView
    private lateinit var btnDeleteSelected: TextView
    private lateinit var itemSelectAll: android.view.MenuItem

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_wipe_chats)

        listChats = findViewById(R.id.listChats)
        boxLoading = findViewById(R.id.boxLoading)
        txtEmpty = findViewById(R.id.txtEmpty)
        txtSelection = findViewById(R.id.txtSelection)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        itemSelectAll = toolbar.menu.findItem(R.id.actionSelectAll)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.actionSelectAll) {
                toggleSelectAll()
                true
            } else {
                false
            }
        }

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        
        btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        findViewById<TextView>(R.id.btnClearAll).setOnClickListener { confirmClearAll() }

        load()
    }

    override fun onResume() {
        super.onResume()
        // 与其它设置页一致：从外观页返回时朝向/开关可能已变，重新对齐一次。
        // 值未变时是空操作。**这里不触发任何会话扫描**（扫描只在 onCreate 与
        // 删除成功后发生），避免用户切出切入时重复 IO。
        OrientationPrefs.applyToSettingsPage(this)
    }

    // ── 扫描 ────────────────────────────────────────────────────────────────

    /**
     * 枚举会话。
     *
     * 首次进入与删除后都会调这个。**始终在后台线程**，UI 只显示进度。
     */
    private fun load() {
        if (loading) return
        loading = true
        boxLoading.visibility = LinearLayout.VISIBLE
        txtEmpty.visibility = TextView.GONE
        btnDeleteSelected.alpha = 0.4f
        itemSelectAll.isEnabled = false

        Thread {
            val snap = runCatching { ChatWiper.list(this) }.getOrNull()
            runOnUiThread {
                loading = false
                boxLoading.visibility = LinearLayout.GONE
                itemSelectAll.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (snap == null) {
                    txtEmpty.visibility = TextView.VISIBLE
                    txtSelection.setText(R.string.wipe_chats_load_failed)
                    return@runOnUiThread
                }
                snapshot = snap
                render(snap)
            }
        }.apply { name = "wipe-chats-scan" }.start()
    }

    private fun render(snap: ChatWiper.Snapshot) {
        listChats.removeAllViews()
        rows.clear()

        if (snap.items.isEmpty()) {
            txtEmpty.visibility = TextView.VISIBLE
            updateSelection()
            return
        }
        txtEmpty.visibility = TextView.GONE

        snap.items.forEachIndexed { index, item ->
            if (index > 0) listChats.addView(divider())
            listChats.addView(buildRow(item))
        }
        updateSelection()
    }

    private fun divider(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1 * resources.displayMetrics.density).toInt(),
        )
        setBackgroundColor(getColor(R.color.divider))
    }

    private fun buildRow(item: ChatWiper.Item): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setBackgroundResource(R.drawable.bg_menu_item)
        }

        val check = CheckBox(this).apply {
            isClickable = false
            isFocusable = false
        }
        row.addView(check, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (10 * resources.displayMetrics.density).toInt() }
        }
        val title = TextView(this).apply {
            text = item.title
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        column.addView(title)

        // 副标题：时间 · 体积 · 归档标记 · 幽灵标记
        val bits = ArrayList<String>(4)
        if (item.updatedAt > 0) bits += fmtDate(item.updatedAt)
        PluginMarket.humanSize(item.bytes).takeIf { it.isNotEmpty() }?.let { bits += it }
        if (item.archived) bits += getString(R.string.wipe_chats_tag_archived)
        if (item.file == null) bits += getString(R.string.wipe_chats_tag_missing)
        val sub = TextView(this).apply {
            text = bits.joinToString(" · ")
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(sub)
        row.addView(column)

        // 整行可点：点在任意位置都切换勾选（CheckBox 自己不可点，避免双重响应）
        row.setOnClickListener {
            check.isChecked = !check.isChecked
            updateSelection()
        }
        check.isChecked = false
        rows += Row(item, check)
        return row
    }

    // ── 选择 ────────────────────────────────────────────────────────────────

    private fun selectedIds(): List<String> =
        rows.filter { it.check.isChecked }.map { it.item.sessionId }

    private fun toggleSelectAll() {
        if (rows.isEmpty()) return
        val all = rows.all { it.check.isChecked }
        rows.forEach { it.check.isChecked = !all }
        updateSelection()
    }

    private fun updateSelection() {
        val n = rows.count { it.check.isChecked }
        val total = rows.size
        txtSelection.text = if (n == 0) {
            getString(R.string.wipe_chats_none_selected)
        } else {
            getString(R.string.wipe_chats_selected, n, total)
        }
        btnDeleteSelected.alpha = if (n == 0) 0.4f else 1f
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    private fun confirmDeleteSelected() {
        val ids = selectedIds()
        if (ids.isEmpty()) return
        val bytes = rows.filter { it.check.isChecked }.sumOf { it.item.bytes }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wipe_chats_delete_selected)
            .setMessage(getString(R.string.wipe_chats_confirm_delete, ids.size, PluginMarket.humanSize(bytes)))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> runWipe(ids) }
            .show()
    }

    private fun confirmClearAll() {
        val snap = snapshot
        val count = snap?.items?.size ?: 0
        if (count == 0) return
        val bytes = snap?.totalBytes ?: 0L
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wipe_chats_clear_all)
            .setMessage(getString(R.string.wipe_chats_confirm_clear, count, PluginMarket.humanSize(bytes)))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                runWipe(snap?.items?.map { it.sessionId } ?: emptyList(), "clear-all")
            }
            .show()
    }

    /**
     * 执行删除：**停引擎 → 删 → 重启引擎**。
     *
     * 三步必须在同一个后台线程里顺序跑完 —— 中间任何一步失败也要保证引擎被
     * 重新拉起来，否则用户会停在「引擎已停」的状态里（界面全白、无法对话）。
     */
    private fun runWipe(ids: List<String>, mode: String = "delete") {
        if (busy || ids.isEmpty()) return
        busy = true
        showBusy(true, getString(R.string.wipe_chats_working))

        Thread {
            val sup = (application as DshApp).supervisor
            var ok = false
            var removed = 0
            try {
                sup.stopForMaintenance()
                removed = runCatching {
                    if (mode == "clear-all") ChatWiper.clearAllIds(this, ids)
                    else ChatWiper.delete(this, ids)
                }.getOrDefault(0)
                // 顺手清掉过期回收站，避免删了几百个之后 trash 无限长
                runCatching { ChatWiper.purgeTrash(this) }
                ok = true
            } catch (e: Exception) {
                android.util.Log.e("WipeChats", "wipe failed: ${e.message}", e)
            } finally {
                // 顺序有讲究：restart() 内部第一件事就是 `maintenanceStop = false`，
                // 所以必须**先** allowStartAfterMaintenance() 再 restart() —— 反过来
                // 也能work（restart 自己会清），但显式写出来才能表达「维护窗口已关闭」。
                // restart() 是异步的（内部 scope.launch），所以这里不等它 —— 用户会
                // 在列表刷新后看到一个「引擎启动中」的正常启动流程。
                sup.allowStartAfterMaintenance()
                runCatching { sup.restart() }
            }
            runOnUiThread {
                busy = false
                showBusy(false, null)
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(
                    this,
                    if (ok) {
                        getString(R.string.wipe_chats_done, removed)
                    } else {
                        getString(R.string.wipe_chats_failed)
                    },
                    Toast.LENGTH_LONG,
                ).show()
                if (ok) load() else updateSelection()
            }
        }.apply { name = "wipe-chats-run" }.start()
    }

    private fun showBusy(on: Boolean, message: String?) {
        val bar = findViewById<LinearLayout>(R.id.boxLoading)
        bar.visibility = if (on) LinearLayout.VISIBLE else LinearLayout.GONE
        if (on && message != null) {
            (bar.getChildAt(1) as? TextView)?.text = message
        } else {
            (bar.getChildAt(1) as? TextView)?.setText(R.string.wipe_chats_loading)
        }
        listChats.alpha = if (on) 0.4f else 1f
        btnDeleteSelected.isEnabled = !on
        itemSelectAll.isEnabled = !on
    }

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

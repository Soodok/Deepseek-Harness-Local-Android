package app.dsh.mobile.pet

import android.app.Activity
import app.dsh.mobile.ThemedActivity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.DshApp
import app.dsh.mobile.LocaleHelper
import app.dsh.mobile.MaxWidthLinearLayout
import app.dsh.mobile.OrientationPrefs
import app.dsh.mobile.PluginStoreActivity
import app.dsh.mobile.R
import app.dsh.mobile.service.EngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌宠独立设置页：权限、桥插件、动画包、大小、测试气泡、总开关。
 *
 * 所有状态都从文件/StateFlow 现读，不缓存“已安装”之类的内存标记。
 * 例外：资源包体积要逐文件 stat，结果缓存在 [packSizes]，由后台线程填。
 */
@Suppress("DEPRECATION")
class PetSettingsActivity : ThemedActivity() {

    private lateinit var content: LinearLayout
    private var enableSwitch: Switch? = null
    /** 当前展示的进度对话框：同一时刻只允许一个，统一由 [dismissProgress] 收口。 */
    private var progressDlg: ProgressDialog? = null
    /** 已销毁标记：后台回调靠它丢弃 UI 更新，别再去碰一个死掉的 Activity。 */
    private val dead = AtomicBoolean(false)
    /** 订阅后台下载状态；Activity 销毁时取消，避免泄漏。 */
    private val uiScope = CoroutineScope(Dispatchers.Main)
    /** 后台任务状态行的文本/进度条：进度推进时只改这两个，不整页重排。 */
    private var packTaskRow: TextView? = null
    private var packTaskBar: android.widget.ProgressBar? = null
    private var lastTaskPhase = PetPackPhase.IDLE
    /** 资源包体积缓存（id → 字节）：主线程只读它，真正的 stat 在后台线程做。 */
    private val packSizes = HashMap<String, Long>()
    private var warming = false
    private var updatingSwitch = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_pet)
        findViewById<MaxWidthLinearLayout>(R.id.petContentWidth).maxWidthDp = MAX_WIDTH_DP
        content = findViewById(R.id.petContent)
        findViewById<View>(R.id.btnPetBack).setOnClickListener { finish() }
        render()
        warmPackDirs()
        // 资源包下载已经交给前台服务：这里只是订阅者。
        // 用户中途退出去忙别的、再回来，进度和结果都还在。
        uiScope.launch {
            PetPackDownloadBus.state.collectLatest { task -> onPackTask(task) }
        }
    }

    /**
     * 资源包目录的整理（旧包迁移 + 半成品清扫）和体积统计都放后台。
     *
     * 前者可能整包复制几百 MB、后者要对每个文件 stat 一遍，都不该占主线程；
     * 算完回主线程补一次渲染（体积先显示 "-"，通常几十毫秒后补齐）。
     */
    private fun warmPackDirs() {
        if (warming) return
        warming = true
        Thread {
            runCatching { PetPack.ensureHeavy(this) }
            val sizes = runCatching {
                PetPack.listInstalled(this).associate { it.id to it.bytes }
            }.getOrDefault(emptyMap())
            uiIfAlive {
                warming = false
                if (sizes.isEmpty()) return@uiIfAlive
                val changed = sizes.any { (id, bytes) -> packSizes[id] != bytes }
                packSizes.putAll(sizes)
                if (changed && progressDlg?.isShowing != true) render()
            }
        }.apply { isDaemon = true; name = "dsh-pet-pack-io" }.start()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        // 导入/下载的进度框不会自己消失：Activity 被销毁时窗口还挂着就是 WindowLeaked。
        dead.set(true)
        dismissProgress()
        uiScope.cancel()
        super.onDestroy()
    }

    /** 后台回调回主线程的统一闸门：Activity 已经销毁就直接丢，连 UI 对象都不碰。 */
    private inline fun uiIfAlive(crossinline block: () -> Unit) {
        runOnUiThread {
            if (dead.get() || isFinishing || isDestroyed) return@runOnUiThread
            block()
        }
    }

    /** 同一时刻只留一个进度框：先收掉上一个，再建新的。 */
    private fun showProgress(build: ProgressDialog.() -> Unit = {}): ProgressDialog {
        dismissProgress()
        val d = ProgressDialog(this).apply {
            setCancelable(false)
            build()
            show()
        }
        progressDlg = d
        return d
    }

    private fun dismissProgress() {
        val d = progressDlg ?: return
        progressDlg = null
        runCatching { d.dismiss() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PACK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            render()
            return
        }
        startImport(uri)
    }

    // ------------------------------------------------------------ UI

    private fun render() {
        // 重渲染（onResume / 体积补齐）要保住滚动位置，别把用户弹回页首
        val scroller = content.parent?.parent as? android.widget.ScrollView
        val scrollY = scroller?.scrollY ?: 0
        if (content.childCount > 0) content.removeAllViews()
        val status = PetStatusBus.state.value
        val running = PetOverlayService.isRunning()
        val plugin = PetPluginDetector.detect(this)
        val canDraw = Settings.canDrawOverlays(this)
        val engineRunning = EngineService.isRunning()
        // 门禁是否正在挡着：服务自己报的 bridgeMissing 为准（它才是真正决定画不画的人），
        // 再叠加一次现算，避免设置页刚打开、服务还没跑起来时看不到提示。
        // 现算直接复用上面那份快照，别为了同一个结论再读一遍 package.json。
        val bridgeBlocked = status.bridgeMissing || (running && !plugin.bridgeReady)

        enableSwitch = makeSwitchRow(
            title = getString(R.string.pet_enable),
            subtitle = getString(R.string.pet_enable_sub),
            checked = running,
        ) { checked ->
            if (!updatingSwitch) {
                if (checked) enablePet(canDraw, plugin) else disablePet()
            }
        }

        section(getString(R.string.pet_title))

        // 状态/错误
        status.lastError?.let { error ->
            card().also { c ->
                c.addView(rowText(error, getColor(R.color.warning)))
                content.addView(c)
            }
        }

        card().also { c ->
            c.addView(switchRowView(enableSwitch))
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_permission),
                    getString(R.string.pet_permission_sub),
                    getString(if (canDraw) R.string.pet_permission_granted else R.string.pet_permission_denied),
                    if (canDraw) getColor(R.color.success) else getColor(R.color.warning),
                ) {
                    openOverlaySettings()
                },
            )
            c.addView(divider())
            val pluginValue = when {
                !plugin.profileExists -> getString(R.string.pet_status_engine_down)
                // 装了但版本不达标（含上游 0.1.0）：先于「已就绪 / 请重启」判断，
                // 否则会误导用户去重启一个永远不通的旧版插件。
                plugin.bridgeInstalled && !plugin.bridgeVersionOk ->
                    getString(R.string.pet_status_plugin_outdated)
                plugin.bridgeReady -> getString(R.string.pet_plugin_installed)
                plugin.bridgeInstalled -> getString(R.string.pet_status_plugin_disabled)
                plugin.webPetInstalled -> getString(R.string.pet_plugin_web_only)
                else -> getString(R.string.pet_plugin_missing)
            }
            val pluginColor = if (plugin.bridgeReady) getColor(R.color.success) else getColor(R.color.warning)
            c.addView(
                row(
                    getString(R.string.pet_plugin),
                    getString(R.string.pet_plugin_hint),
                    pluginValue,
                    pluginColor,
                ) {
                    when {
                        plugin.bridgeInstalled && !plugin.bridgeVersionOk -> openPluginStore()
                        plugin.bridgeInstalled && !plugin.bridgeEnabled -> restartEngine()
                        else -> openPluginStore()
                    }
                },
            )
            // 被门禁挡下时给一条能点走的行：光靠上面"未启用"那一小截灰字，
            // 用户看不出"开关是开的、宠物却不见了"是门禁造成的。
            if (bridgeBlocked) {
                c.addView(divider())
                c.addView(
                    row(
                        getString(R.string.pet_status_bridge_missing),
                        getString(R.string.pet_bridge_missing_hint),
                        getString(R.string.pet_plugin),
                        getColor(R.color.warning),
                    ) { openPluginStore() },
                )
            }
            content.addView(c)
        }

        if (plugin.bridgeInstalled) {
            card().also { c ->
                c.addView(
                    row(
                        getString(R.string.pet_plugin_restart),
                        getString(R.string.pet_plugin_restart_hint),
                        "",
                        getColor(R.color.text_secondary),
                    ) { restartEngine() },
                )
                content.addView(c)
            }
        }

        section(getString(R.string.pet_pack))
        renderPackCard()

        section(getString(R.string.pet_size))
        card().also { c ->
            c.addView(
                row(
                    getString(R.string.pet_size),
                    getString(R.string.pet_size_hint),
                    getString(R.string.pet_size_value, PetPrefs.sizeDp(this)),
                    getColor(R.color.text_secondary),
                ) { chooseSize() },
            )
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_test_bubble),
                    getString(R.string.pet_test_bubble_text),
                    "",
                    getColor(R.color.text_secondary),
                ) {
                    if (running) {
                        PetOverlayService.showTestBubble(getString(R.string.pet_test_bubble_text))
                    } else {
                        toast(getString(R.string.pet_status_stopped))
                    }
                },
            )
            content.addView(c)
        }

        section(getString(R.string.pet_sound_section))
        card().also { c ->
            c.addView(switchRowFor(
                title = getString(R.string.pet_sound),
                subtitle = getString(R.string.pet_sound_sub),
                checked = PetPrefs.soundEnabled(this),
            ) { checked ->
                PetPrefs.setSoundEnabled(this, checked)
            })
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_lines),
                    getString(R.string.pet_lines_sub),
                    getString(R.string.pet_lines_count, PetLines.totalCount(this)),
                ) { startActivity(Intent(this, PetLinesActivity::class.java)) },
            )
            content.addView(c)
        }

        if (!engineRunning) {
            content.addView(rowText(getString(R.string.pet_status_engine_down), getColor(R.color.text_secondary)))
        }

        // 状态副标题（运行中/已连接/等待/被门禁暂停）
        if (running) {
            if (bridgeBlocked) {
                content.addView(
                    rowText(getString(R.string.pet_status_bridge_missing), getColor(R.color.warning)),
                )
            } else {
                val st = when {
                    status.connected -> getString(R.string.pet_status_connected)
                    status.listening -> getString(R.string.pet_status_waiting)
                    else -> getString(R.string.pet_status_running)
                }
                content.addView(rowText(st, getColor(R.color.success)))
            }
        }

        if (scrollY > 0) scroller?.post { scroller.scrollTo(0, scrollY) }
    }

    private fun enablePet(canDraw: Boolean, plugin: PetPluginDetector.Snapshot) {
        // 硬门禁（2026-10-27 起，用户明确要求不能跳过）：
        // 悬浮窗权限 / 资源包 / 桥插件，三者缺一都不允许开启，弹窗只做引导、没有旁路。
        if (!canDraw) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pet_permission)
                .setMessage(R.string.pet_permission_rationale)
                .setPositiveButton(R.string.pet_permission) { _, _ -> openOverlaySettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            render()
            return
        }
        if (PetPrefs.packId(this) == null) {
            // 没资源包：桌宠没有任何可画的内容，直接不给开，引导去装包。
            AlertDialog.Builder(this)
                .setTitle(R.string.pet_pack)
                .setMessage(R.string.pet_gate_pack_missing_msg)
                .setPositiveButton(R.string.pet_pack_download) { _, _ ->
                    render()
                    downloadPacks()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> render() }
                .setOnCancelListener { render() }
                .show()
            return
        }
        val outdated = plugin.bridgeInstalled && !plugin.bridgeVersionOk
        if (outdated) {
            // 硬门禁：< 0.3.1 的桥插件不带 token，协议 v2 下必然 403，
            // 开了也是「连接着但永远没气泡」，不给任何旁路。
            AlertDialog.Builder(this)
                .setTitle(R.string.pet_plugin)
                .setMessage(R.string.pet_plugin_outdated_msg)
                .setPositiveButton(R.string.pet_plugin_upgrade) { _, _ ->
                    render()
                    openPluginStore()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> render() }
                .setOnCancelListener { render() }
                .show()
            return
        }
        if (!plugin.bridgeReady) {
            // 没装 / 装了没启用：同样是硬门禁 —— 没桥插件的桌宠只会待机，
            // 永远不会响应工作状态，不允许「仍然开启」。
            AlertDialog.Builder(this)
                .setTitle(R.string.pet_plugin)
                .setMessage(R.string.pet_gate_plugin_missing_msg)
                .setPositiveButton(R.string.pet_gate_install) { _, _ ->
                    render()
                    openPluginStore()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> render() }
                .setOnCancelListener { render() }
                .show()
            return
        }
        doStart()
    }

    private fun doStart() {
        runCatching {
            PetPrefs.setEnabled(this, true)
            PetOverlayService.start(this)
        }.onFailure {
            PetPrefs.setEnabled(this, false)
            toast(it.message ?: getString(R.string.pet_error_no_permission))
        }
        content.postDelayed({ render() }, 400L)
    }

    private fun disablePet() {
        PetPrefs.setEnabled(this, false)
        PetOverlayService.stop(this)
        content.postDelayed({ render() }, 250L)
    }

    private fun openOverlaySettings() {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(i) }.onFailure {
            toast(it.message ?: getString(R.string.pet_error_no_permission))
        }
    }

    private fun openPluginStore() {
        startActivity(
            Intent(this, PluginStoreActivity::class.java)
                // 搜索用短词：npm 搜索不认 scoped 全名，靠短词 + 目录项合并命中官方包。
                .putExtra(PluginStoreActivity.EXTRA_QUERY, PetPluginDetector.BRIDGE_SEARCH_QUERY),
        )
    }

    private fun restartEngine() {
        runCatching { (application as DshApp).supervisor.restart() }
        toast(getString(R.string.pet_plugin_restart_hint))
        content.postDelayed({ render() }, 600L)
    }

    private fun pickPack() {
        // 只认 zip：资源包不再接受 APK（安装包和资源包不是一回事）。
        // 有些文件管理器把 zip 报成 octet-stream，所以额外放行它；
        // APK 的 MIME 是 application/vnd.android.package-archive，不在列表里。
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
        runCatching { startActivityForResult(i, REQ_PACK) }.onFailure {
            toast(it.message ?: getString(R.string.pet_pack_failed, "?"))
        }
    }

    /** 失败原因码 → 本地化文案；技术串（message）只留给日志，不直接甩给用户。 */
    private fun importError(result: PetPackImportResult): String {
        val detail = result.message ?: ""
        return when (result.code) {
            PetPackErrors.APK -> getString(R.string.pet_pack_err_apk)
            PetPackErrors.NOT_ZIP -> getString(R.string.pet_pack_err_not_zip)
            PetPackErrors.OPEN_FAILED -> getString(R.string.pet_pack_err_open)
            PetPackErrors.UNSAFE_PATH -> getString(R.string.pet_pack_err_unsafe)
            PetPackErrors.NO_CONFIG -> getString(R.string.pet_pack_err_no_config)
            PetPackErrors.BAD_CONFIG -> getString(R.string.pet_pack_err_bad_config)
            PetPackErrors.NO_WEBP -> getString(R.string.pet_pack_err_no_webp)
            PetPackErrors.BAD_WEBP -> getString(R.string.pet_pack_err_bad_webp, detail)
            PetPackErrors.MISSING_ANIM -> getString(R.string.pet_pack_err_missing_anim, detail)
            PetPackErrors.TOO_MANY_ENTRIES -> getString(R.string.pet_pack_err_too_many)
            PetPackErrors.ENTRY_TOO_LARGE, PetPackErrors.TOO_LARGE -> getString(R.string.pet_pack_err_too_large)
            PetPackErrors.NO_SPACE -> getString(R.string.pet_pack_err_no_space)
            PetPackErrors.BUSY -> getString(R.string.pet_pack_err_busy)
            PetPackErrors.ENCRYPTED -> getString(R.string.pet_pack_err_encrypted)
            else -> detail.ifBlank { "?" }
        }
    }

    private fun startImport(uri: Uri) {
        showProgress {
            setTitle(R.string.pet_pack)
            setMessage(getString(R.string.pet_pack_keep_open))
            isIndeterminate = false
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        }
        PetPackInstaller.install(
            this,
            uri,
            onProgress = { p -> uiIfAlive { runCatching { progressDlg?.progress = p.coerceIn(0, 100) } } },
            onDone = { result ->
                // 导入回调在后台线程：UI 一律经 uiIfAlive 回主线程；Activity 已经没了
                // 时由 onDestroy 收掉对话框，这里直接丢。
                uiIfAlive {
                    dismissProgress()
                    if (result.ok) {
                        val id = result.packId
                        if (id != null) PetPrefs.setPackId(this, id)
                        PetOverlayService.reload(this)
                        toast(getString(R.string.pet_pack_imported, result.animationCount))
                    } else {
                        toast(getString(R.string.pet_pack_failed, importError(result)))
                    }
                    render()
                    // 新包的体积只有后台算得出来，补一遍，别让摘要停在 "-"
                    if (result.ok) warmPackDirs()
                }
            },
        )
    }

    // ------------------------------------------------------------ 资源包区块

    private fun renderPackCard() {
        // 体积走后台缓存的快照，主线程不再逐文件 stat（291MB 包几百个文件）
        val packs = PetPack.listInstalled(this, withSizes = false)
        val current = packs.firstOrNull { it.current }
        // 重排会重建状态行，先丢掉旧引用，避免指向已被移除的 View
        packTaskRow = null
        packTaskBar = null
        card().also { c ->
            c.addView(
                row(
                    getString(R.string.pet_pack_select),
                    current?.name ?: getString(R.string.pet_pack_none_hint),
                    current?.let { summary(it) } ?: getString(R.string.pet_pack_none),
                    if (current != null) getColor(R.color.success) else null,
                ) { if (packs.isNotEmpty()) showPackSwitchDialog(packs) else downloadPacks() },
            )
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_pack_download),
                    getString(R.string.pet_pack_download_sub),
                    "",
                ) { downloadPacks() },
            )
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_pack_import),
                    getString(R.string.pet_pack_import_sub),
                    "",
                ) { pickPack() },
            )
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_pack_source),
                    getString(R.string.pet_pack_source_sub),
                    "",
                ) { editIndexUrl() },
            )
            // 后台任务状态（有任务时才有）：下载中显示进度 + 取消，
            // 结束后显示结果 + 关闭
            packTaskView()?.let { task ->
                c.addView(divider())
                c.addView(task)
            }
            content.addView(c)
        }
    }

    private fun summary(p: PetPackSummary): String =
        getString(R.string.pet_pack_summary, p.animations, sizeOf(packSizes[p.id] ?: -1L))

    private fun sizeOf(bytes: Long): String = when {
        bytes <= 0L -> "-"
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.0f MB", bytes / 1024.0 / 1024)
        else -> String.format("%.0f KB", bytes / 1024.0)
    }

    private fun showPackSwitchDialog(packs: List<PetPackSummary>) {
        val labels = packs.map {
            "${it.name} · ${summary(it)}${if (it.current) "  ✓" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_pack_switch)
            .setItems(labels) { dlg, which ->
                dlg.dismiss()
                val p = packs[which]
                PetPrefs.setPackId(this, p.id)
                PetOverlayService.reload(this)
                toast(getString(R.string.pet_pack_switched, p.name))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.pet_pack_delete) { dlg, _ ->
                dlg.dismiss()
                confirmDeletePack(packs)
            }
            .show()
    }

    /** 同名包靠安装时间区分；列表行与二次确认共用一种格式。 */
    private fun installedAtText(p: PetPackSummary): String =
        if (p.installedAt > 0L) {
            getString(
                R.string.pet_pack_installed_at,
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(p.installedAt)),
            )
        } else ""

    private fun confirmDeletePack(packs: List<PetPackSummary>) {
        // 每行带包名 + 安装时间：同名资源包（导入两次同素材）只有时间能区分开，
        // 只显示名字删错包就找不回来了。
        val labels = packs.map { p ->
            val t = installedAtText(p)
            if (t.isBlank()) p.name else "${p.name} · $t"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_pack_clear)
            .setItems(labels) { _, which ->
                val p = packs[which]
                val t = installedAtText(p)
                val msg = getString(R.string.pet_pack_clear_confirm, p.name) +
                    if (t.isBlank()) "" else "\n$t"
                AlertDialog.Builder(this)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok) { d, _ ->
                        d.dismiss()
                        deletePack(p)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 删包放后台：一个 480p 包是几百个文件 / 291MB，主线程 `deleteRecursively()`
     * 足够卡一下甚至 ANR。删完回主线程刷新。
     */
    private fun deletePack(p: PetPackSummary) {
        Thread {
            val ok = runCatching { PetPrefs.removePack(this, p.id) }.getOrDefault(false)
            if (ok) packSizes.remove(p.id)
            uiIfAlive {
                if (ok) {
                    PetOverlayService.reload(this)
                    toast(getString(R.string.pet_pack_deleted, p.name))
                } else {
                    toast(getString(R.string.pet_pack_delete_failed, p.name))
                }
                render()
            }
        }.apply { isDaemon = true; name = "dsh-pet-pack-io" }.start()
    }

    private fun editIndexUrl() {
        val input = EditText(this).apply {
            setText(PetPrefs.packIndexUrl(this@PetSettingsActivity))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_pack_source)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
                PetPrefs.setPackIndexUrl(this, input.text?.toString()?.trim())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ 远端下载

    private fun downloadPacks() {
        val url = PetPrefs.packIndexUrl(this)
        showProgress {
            setMessage(getString(R.string.pet_pack_download))
            isIndeterminate = true
            setCancelable(true)
        }
        Thread {
            val result = runCatching { PetPackIndexClient.fetch(this@PetSettingsActivity, url) }
            uiIfAlive {
                dismissProgress()
                result.onSuccess { index -> showRemotePackList(index) }
                    .onFailure { e ->
                        toast(getString(R.string.pet_pack_fetch_failed, e.message ?: "?"))
                    }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showRemotePackList(index: PetPackIndex) {
        if (index.packs.isEmpty()) {
            toast(getString(R.string.pet_pack_none))
            return
        }
        val labels = index.packs.map { p ->
            buildString {
                append(p.name)
                if (!p.resolution.isNullOrBlank()) append(" · ${p.resolution}")
                append("\n")
                append(getString(R.string.pet_pack_summary, p.animations, sizeOf(p.size)))
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_pack_download)
            .setItems(labels) { dlg, which ->
                dlg.dismiss()
                startDownload(index.packs[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 把下载交给 [PetPackDownloadService]。
     *
     * 以前这里是一个 setCancelable(false) 的横向进度框：用户被锁在页面上、
     * 按返回键都退不出去，几百 MB 只能干等；更糟的是页面一旦销毁，
     * 下载完成回调会把文件直接删掉——等于白下。现在任务在服务里跑，
     * 进度看通知栏，本页只显示一行状态。
     */
    private fun startDownload(pack: RemotePack) {
        if (!PetPackDownloadService.start(this, pack)) {
            toast(getString(R.string.pet_pack_bg_busy))
            return
        }
        render()
    }

    // ------------------------------------------------------------ 后台任务状态

    /**
     * 后台任务的 UI 收敛点。
     *
     * 阶段变化才整页重排（包列表/体积都会变），纯进度推进只改那一行文本和进度条——
     * 否则下载期间每 1% 重排整页，用户想滚到别处都会被弹回去。
     */
    private fun onPackTask(task: PetPackTask) {
        if (dead.get() || isFinishing || isDestroyed) return
        val phaseChanged = task.phase != lastTaskPhase
        val prev = lastTaskPhase
        lastTaskPhase = task.phase
        if (!phaseChanged && task.running) {
            packTaskRow?.text = packTaskText(task)
            packTaskBar?.let { bar ->
                if (!bar.isIndeterminate) bar.progress = task.percent
            }
            return
        }
        if (task.phase == PetPackPhase.DONE && prev != PetPackPhase.DONE) {
            // 新包装完了：体积缓存要重算，页面上"已安装的包"才会更新
            warmPackDirs()
        }
        render()
    }

    private fun packTaskText(t: PetPackTask): String = when (t.phase) {
        // 和通知栏保持同一句话：用户可能一边看通知一边看这页，线路标注不能只有一处有
        PetPackPhase.DOWNLOADING -> getString(
            R.string.pet_pack_bg_downloading_src,
            t.packName,
            t.percent,
            when {
                t.source == null -> getString(R.string.pet_pack_src_checking)
                t.source.viaProxy -> getString(R.string.pet_pack_src_proxy)
                else -> getString(R.string.pet_pack_src_direct)
            },
        )
        PetPackPhase.INSTALLING -> getString(R.string.pet_pack_install_progress, t.packName)
        PetPackPhase.DONE -> getString(R.string.pet_pack_bg_done, t.packName)
        PetPackPhase.CANCELLED -> getString(R.string.pet_pack_bg_cancelled)
        PetPackPhase.FAILED -> getString(R.string.pet_pack_download_failed, t.error ?: "?")
        PetPackPhase.IDLE -> ""
    }

    /** 资源包卡片底部的后台任务行：进度 + 取消；结束后变成结果 + 关闭。 */
    private fun packTaskView(): View? {
        val task = PetPackDownloadBus.state.value
        if (task.phase == PetPackPhase.IDLE) return null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = packTaskText(task)
            textSize = 13f
            setTextColor(
                getColor(if (task.phase == PetPackPhase.FAILED) R.color.warning else R.color.text_secondary),
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        line.addView(label)
        packTaskRow = label
        if (task.running) {
            line.addView(
                taskAction(getString(android.R.string.cancel)) {
                    PetPackDownloadBus.requestCancel()
                    toast(getString(R.string.pet_pack_bg_cancelling))
                },
            )
        } else {
            line.addView(
                taskAction(getString(R.string.pet_pack_bg_dismiss)) {
                    PetPackDownloadBus.clear()
                    render()
                },
            )
        }
        box.addView(line)
        if (task.running) {
            val bar = android.widget.ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal,
            ).apply {
                max = 100
                isIndeterminate = task.phase == PetPackPhase.INSTALLING
                if (!isIndeterminate) progress = task.percent
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            }
            packTaskBar = bar
            box.addView(bar)
        } else {
            packTaskBar = null
        }
        return box
    }

    private fun taskAction(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.accent))
        setPadding(dp(10), dp(4), 0, dp(4))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun chooseSize() {
        val values = PetPrefs.SIZE_LEVELS
        val labels = values.map { getString(R.string.pet_size_value, it) }.toTypedArray()
        val current = values.indexOfFirst { it == PetPrefs.sizeDp(this) }
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_size)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                PetPrefs.setSizeDp(this, values[which])
                dlg.dismiss()
                PetOverlayService.reload(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ 视图小工具

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(18), dp(8), dp(8))
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1).coerceAtLeast(1),
        ).apply { marginStart = dp(52) }
        setBackgroundColor(0x22FFFFFF)
    }

    private fun switchRowView(sw: Switch?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.pet_enable)
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.pet_enable_sub)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        if (sw != null) row.addView(sw)
        return row
    }

    private fun makeSwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): Switch {
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value ->
                if (!updatingSwitch) onChange(value)
            }
        }
        return sw
    }

    /** 卡片内直接可用的开关行（标题/副标题/开关状态/回调）。 */
    private fun switchRowFor(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val sw = makeSwitchRow(title, subtitle, checked, onChange)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
        })
        if (subtitle.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = subtitle
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(col)
        row.addView(sw)
        return row
    }

    private fun row(
        title: String,
        subtitle: String,
        value: String,
        // 右侧状态值默认跟着主题走：写死的灰是按深色色调的，浅色下偏淡看不清。
        // 只有真正要强调（成功/警告）才在调用处传色，其余交给 [R.color.text_secondary]。
        valueColor: Int? = null,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
        })
        if (subtitle.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = subtitle
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(col)
        if (value.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = value
                setTextColor(valueColor ?: getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(dp(10), 0, 0, 0)
            })
        }
        return row
    }

    private fun rowText(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 12f
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PACK = 4301
        private const val MAX_WIDTH_DP = 680
    }
}

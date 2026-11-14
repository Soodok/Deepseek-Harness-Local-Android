package app.dsh.mobile

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.ChatRecovery
import app.dsh.mobile.engine.FullBackup

/**
 * 备份与恢复（v1.4.46）：从设置主页数据区收拢的低频数据操作子页。
 *
 * 四个条目：导出数据（系统文件选择器）、导入数据（合并式恢复，不覆盖现有）、
 * 恢复对话（扫描未登记的会话）、配置文件（常用配置直接编辑）。
 * 原来的交互与线程语义全部原样迁移 —— 只是换了个入口页。
 */
class BackupActivity : ThemedActivity() {
    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true


    /** 导出行的副标题（进度 / 结果都写在这里，与「对话恢复」行同一交互） */
    private var exportSub: TextView? = null

    /** 导入行的副标题（v1.4.29） */
    private var importSub: TextView? = null
    private var pendingOptions: FullBackup.Options? = null
    private var pendingRequest = 0

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey(STATE_PLUGINS) == true) {
            pendingOptions = FullBackup.Options(
                includePlugins = savedInstanceState.getBoolean(STATE_PLUGINS),
                includeCredentials = savedInstanceState.getBoolean(STATE_CREDENTIALS),
                includeExtensions = savedInstanceState.getBoolean(STATE_EXTENSIONS),
                includeLinux = savedInstanceState.getBoolean(STATE_LINUX),
                replaceExisting = savedInstanceState.getBoolean(STATE_REPLACE_EXISTING, false),
            )
            pendingRequest = savedInstanceState.getInt(STATE_REQUEST)
        }
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_backup)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 数据导出（issue #31 第一步）：系统文件选择器选保存位置 ——
        exportSub = findViewById(R.id.txtExportSub)
        findViewById<LinearLayout>(R.id.rowExportData).setOnClickListener {
            if (exporting || importing || pendingRequest != 0) return@setOnClickListener
            showBackupOptions(isExport = true) { options ->
                pendingOptions = options
                pendingRequest = REQ_EXPORT
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val pick = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "dsh-backup-$stamp.zip")
                runCatching { startActivityForResult(pick, REQ_EXPORT) }.onFailure {
                    pendingOptions = null
                    pendingRequest = 0
                    exportSub?.text = getString(R.string.export_failed, it.message ?: it.javaClass.simpleName)
                }
            }
        }

        // —— 数据导入（v1.4.29，issue #31 第二步）：合并式恢复，不覆盖现有数据 ——
        importSub = findViewById(R.id.txtImportSub)
        findViewById<LinearLayout>(R.id.rowImportData).setOnClickListener {
            if (importing || exporting || pendingRequest != 0) return@setOnClickListener
            showBackupOptions(isExport = false) { options ->
                pendingOptions = options
                pendingRequest = REQ_IMPORT
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                runCatching { startActivityForResult(pick, REQ_IMPORT) }.onFailure {
                    pendingOptions = null
                    pendingRequest = 0
                    importSub?.text = getString(R.string.import_failed, it.message ?: it.javaClass.simpleName)
                }
            }
        }

        // —— 配置文件（v1.4.29，issue #25）：列出常用配置，点哪个开哪个 ——
        findViewById<LinearLayout>(R.id.rowConfigFiles).setOnClickListener {
            showConfigFilePicker()
        }

        // —— 对话恢复（v1.4.4）：扫描未登记进工作区的会话，确认后登记回去 ——
        val recoverSub = findViewById<TextView>(R.id.txtRecoverSub)
        findViewById<LinearLayout>(R.id.rowRecoverChats).setOnClickListener {
            recoverSub.text = getString(R.string.recover_scanning)
            Thread({
                val result = runCatching { ChatRecovery.scan(this) }.getOrNull()
                runOnUiThread {
                    if (result == null) {
                        recoverSub.text = getString(
                            R.string.recover_failed, getString(R.string.file_editor_read_failed),
                        )
                        return@runOnUiThread
                    }
                    if (result.orphans.isEmpty()) {
                        recoverSub.text = getString(R.string.recover_none)
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.setting_recover_chats)
                        .setMessage(getString(R.string.recover_confirm, result.orphans.size))
                        .setPositiveButton(R.string.recover_confirm_yes) { _, _ ->
                            recoverSub.text = getString(R.string.recover_scanning)
                            Thread({
                                // ⚠️ v1.4.7（用户实测反馈，关键）：**必须先把引擎停掉**。
                                // 引擎把 workspace.json 当独占文件：启动时读一次进内存，
                                // 之后每次变更整份写回，既不监视文件也不比对时间戳。
                                // 在引擎运行期间改这个文件，改动会被它的旧内存状态抹掉 ——
                                // 用户实测：写进 7 个工作区，3 分钟后回落到 1 个，日志无记录。
                                // 于是「恢复成功 → 重启 → 什么都没有 → 卸载」，
                                // 私有目录被清空，历史真的丢了。
                                val sup = (application as DshApp).supervisor
                                runCatching { sup.stop(graceMs = 3_000, explicit = false) }
                                // 给引擎一点时间真正退出并落盘
                                Thread.sleep(800)
                                val n = runCatching {
                                    ChatRecovery.recover(this, result.orphans)
                                }.getOrElse { -1 }
                                // 恢复完再启动：引擎这次启动会重新从磁盘读 registry
                                runCatching { sup.start((application as DshApp).appScope) }
                                runOnUiThread {
                                    recoverSub.text = if (n >= 0) {
                                        getString(R.string.recover_done, n) + " · " +
                                            getString(R.string.recover_restart_hint)
                                    } else {
                                        getString(
                                            R.string.recover_failed,
                                            getString(R.string.file_editor_read_failed),
                                        )
                                    }
                                }
                            }, "chat-recover-write").start()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }, "chat-recover-scan").start()
        }
    }

    /** 回到本页时重新对齐朝向：外观页可能刚改过开关/偏好。 */
    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingOptions?.let {
            outState.putBoolean(STATE_PLUGINS, it.includePlugins)
            outState.putBoolean(STATE_CREDENTIALS, it.includeCredentials)
            outState.putBoolean(STATE_EXTENSIONS, it.includeExtensions)
            outState.putBoolean(STATE_LINUX, it.includeLinux)
            outState.putBoolean(STATE_REPLACE_EXISTING, it.replaceExisting)
            outState.putInt(STATE_REQUEST, pendingRequest)
        }
        super.onSaveInstanceState(outState)
    }

    private fun showBackupOptions(isExport: Boolean, onSelected: (FullBackup.Options) -> Unit) {
        val checked = booleanArrayOf(false, false, false, false)
        val labels = arrayOf(
            getString(R.string.backup_option_plugins),
            getString(R.string.backup_option_credentials),
            getString(R.string.backup_option_extensions),
            getString(R.string.backup_option_linux),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isExport) R.string.setting_export_data else R.string.setting_import_data)
            .setMultiChoiceItems(labels, checked) { _, which, selected -> checked[which] = selected }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (exporting || importing || pendingRequest != 0) return@setPositiveButton
                val options = FullBackup.Options(
                    includePlugins = checked[0],
                    includeCredentials = checked[1],
                    includeExtensions = checked[2],
                    includeLinux = checked[3],
                )
                if (options.includeCredentials || isExport) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(if (options.includeCredentials) R.string.backup_credentials_warning_title else R.string.backup_plain_warning_title)
                        .setMessage(if (options.includeCredentials) R.string.backup_credentials_warning else R.string.backup_plain_warning)
                        .setPositiveButton(if (options.includeCredentials) R.string.backup_credentials_confirm else android.R.string.ok) { _, _ ->
                            if (!exporting && !importing && pendingRequest == 0) {
                                if (isExport) onSelected(options) else showImportConflictPolicy(options, onSelected)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    showImportConflictPolicy(options, onSelected)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showImportConflictPolicy(options: FullBackup.Options, onSelected: (FullBackup.Options) -> Unit) {
        var replaceExisting = false
        val labels = arrayOf(
            getString(R.string.backup_conflict_keep),
            getString(R.string.backup_conflict_replace),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_conflict_title)
            .setSingleChoiceItems(labels, 0) { _, which -> replaceExisting = which == 1 }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (exporting || importing || pendingRequest != 0) return@setPositiveButton
                val selected = options.copy(replaceExisting = replaceExisting)
                if (replaceExisting) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.backup_replace_warning_title)
                        .setMessage(R.string.backup_replace_warning)
                        .setPositiveButton(R.string.backup_replace_confirm) { _, _ ->
                            if (!exporting && !importing && pendingRequest == 0) onSelected(selected)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    onSelected(selected)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 常用配置文件选择器（v1.4.29，issue #25）。
     *
     * 只列「改坏了能恢复 / 有意义」的文件 —— 全部在应用私有目录内，
     * FileEditorActivity 的路径检查天然放行：
     *  · 工作区注册表      workspace.json（列表里看不到会话时排查）
     *  · 插件 profile      profiles/web/package.json（加错插件起不来时手改）
     *
     * ⚠️ **刻意不列 AGENTS.md**（v1.7.6，用户要求）：那是系统提示词的全文，
     * 原先在这里能整份改。两个理由：
     *  1. 界面上没有任何护栏 —— 一个误删、少个空行，AI 的人设与全部规矩就没了，
     *     而用户根本不知道刚才改的是「系统提示词」，以为在编辑一个配置文件；
     *  2. 想改自定义指令有专门的入口（设置 → 自定义指令），那里只编辑用户自己
     *     追加的那一段，不会碰到随版本更新变化的内置提示词。
     * 保留这两个「结构文件」是因为它们出问题时正好需要手改，且改坏了不涉及
     * AI 行为约定（workspace.json 只是目录登记，package.json 是插件清单）。
     */
    private fun showConfigFilePicker() {
        val home = app.dsh.mobile.engine.EngineConfig.dshHome(this)
        val candidates: List<Triple<String, java.io.File, String>> = listOf(
            Triple("workspace.json", java.io.File(home, "storages/workspace.json"), "Workspace registry"),
            Triple("profile package.json", java.io.File(home, "profiles/web/package.json"), "Installed plugin list"),
        ).filter { it.second.isFile }

        if (candidates.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.config_files_none, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { it.first + "\n" + it.third }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setting_config_files)
            .setItems(labels) { d, which ->
                d.dismiss()
                val f = candidates[which].second
                runCatching {
                    startActivity(
                        android.content.Intent(this, FileEditorActivity::class.java)
                            .putExtra(FileEditorActivity.EXTRA_PATH, f.absolutePath),
                    )
                }.onFailure {
                    android.widget.Toast.makeText(
                        this, getString(R.string.config_files_failed, it.message ?: "?"),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT && requestCode != REQ_EXPORT) return
        if (requestCode != pendingRequest) return
        val options = pendingOptions
        pendingOptions = null
        pendingRequest = 0
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || options == null || importing || exporting) return
        if (requestCode == REQ_IMPORT) runImport(uri, options) else runExport(uri, options)
    }

    /**
     * 后台导入（v1.4.29，issue #31）。合并式：不覆盖现有文件，只补缺失的。
     *
     * 先把 URI 复制到私有缓存再解包 —— ContentResolver 的流不可 seek，
     * 而 ZipFile 需要随机访问。
     */
    private fun runImport(uri: Uri, options: FullBackup.Options) {
        importing = true
        importSub?.text = getString(R.string.import_preparing)
        Thread({
            val result = runCatching {
                val cache = java.io.File(cacheDir, "restore-${System.currentTimeMillis()}.zip")
                try {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        cache.outputStream().use { outs -> ins.copyTo(outs, 128 * 1024) }
                    } ?: throw java.io.IOException("cannot open $uri")
                    var lastShown = 0L
                    FullBackup.import(this, cache, options) { done, total ->
                        val now = System.currentTimeMillis()
                        if (done == total || now - lastShown >= 200) {
                            lastShown = now
                            runOnUiThread {
                                importSub?.text = getString(R.string.import_progress, done, total)
                            }
                        }
                    }
                } finally {
                    runCatching { cache.delete() }
                }
            }
            importing = false
            runOnUiThread {
                result.onSuccess { s ->
                    importSub?.text = getString(R.string.backup_import_result, s.files, s.skipped)
                    if (s.files > 0 && !isFinishing && !isDestroyed) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.import_done_title)
                            .setMessage(getString(R.string.backup_import_restart, s.files))
                            .setPositiveButton(R.string.import_done_restart) { _, _ ->
                                (application as DshApp).supervisor.restart()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }.onFailure { e ->
                    val msg = e.message.orEmpty()
                    val text = when (msg) {
                        "not_a_zip" -> getString(R.string.import_err_not_zip)
                        "encrypted_zip" -> getString(R.string.import_err_encrypted)
                        else -> msg.ifBlank { e.javaClass.simpleName }
                    }
                    importSub?.text = getString(R.string.import_failed, text)
                }
            }
        }, "data-import").apply { isDaemon = true }.start()
    }

    /**
     * 后台导出到 [uri]。失败时删掉已创建的半成品文件，避免用户误以为备份可用。
     */
    private fun runExport(uri: Uri, options: FullBackup.Options) {
        exporting = true
        exportSub?.text = getString(R.string.export_preparing)
        Thread({
            var lastShown = 0L
            val result = runCatching {
                val out = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("cannot open $uri")
                out.use { stream ->
                    FullBackup.export(this, stream, options) { done, total ->
                        // 节流：最多每 200ms 刷新一次文字，最后一个文件必刷
                        val now = System.currentTimeMillis()
                        if (done == total || now - lastShown >= 200) {
                            lastShown = now
                            runOnUiThread { exportSub?.text = getString(R.string.export_progress, done, total) }
                        }
                    }
                }
            }
            if (result.isFailure) {
                runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            }
            exporting = false
            runOnUiThread {
                result.onSuccess { s ->
                    val done = getString(R.string.backup_export_result, s.files, s.skipped)
                    exportSub?.text = done
                    val msg = buildString {
                        append(done)
                        append("\n\n").append(getString(R.string.backup_plain_warning))
                    }
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.setting_export_data)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }.onFailure { e ->
                    val failed = getString(R.string.export_failed, e.message ?: e.javaClass.simpleName)
                    exportSub?.text = failed
                    // 失败也弹窗：只改行内小字很容易看漏（真机实测反馈）
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.setting_export_data)
                            .setMessage(failed + "\n\n" + getString(R.string.backup_plain_warning))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }, "data-export").start()
    }

    private companion object {
        @Volatile var importing = false
        @Volatile var exporting = false
        const val REQ_EXPORT = 4204
        const val REQ_IMPORT = 4205
        const val STATE_PLUGINS = "backup_plugins"
        const val STATE_CREDENTIALS = "backup_credentials"
        const val STATE_EXTENSIONS = "backup_extensions"
        const val STATE_LINUX = "backup_linux"
        const val STATE_REPLACE_EXISTING = "backup_replace_existing"
        const val STATE_REQUEST = "backup_request"
    }
}

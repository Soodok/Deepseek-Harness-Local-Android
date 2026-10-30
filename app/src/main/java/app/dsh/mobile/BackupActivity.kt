package app.dsh.mobile

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.ChatRecovery

/**
 * 备份与恢复（v1.4.46）：从设置主页数据区收拢的低频数据操作子页。
 *
 * 四个条目：导出数据（系统文件选择器）、导入数据（合并式恢复，不覆盖现有）、
 * 恢复对话（扫描未登记的会话）、配置文件（常用配置直接编辑）。
 * 原来的交互与线程语义全部原样迁移 —— 只是换了个入口页。
 */
class BackupActivity : ThemedActivity() {

    /** 导出行的副标题（进度 / 结果都写在这里，与「对话恢复」行同一交互） */
    private var exportSub: TextView? = null

    /** 导入行的副标题（v1.4.29） */
    private var importSub: TextView? = null
    @Volatile private var importing = false
    @Volatile private var exporting = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.forcePortrait(this)
        setContentView(R.layout.activity_backup)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 数据导出（issue #31 第一步）：系统文件选择器选保存位置 ——
        exportSub = findViewById(R.id.txtExportSub)
        findViewById<LinearLayout>(R.id.rowExportData).setOnClickListener {
            if (exporting) return@setOnClickListener
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val pick = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "dsh-backup-$stamp.zip")
            runCatching { startActivityForResult(pick, REQ_EXPORT) }.onFailure {
                exportSub?.text = getString(R.string.export_failed, it.message ?: it.javaClass.simpleName)
            }
        }

        // —— 数据导入（v1.4.29，issue #31 第二步）：合并式恢复，不覆盖现有数据 ——
        importSub = findViewById(R.id.txtImportSub)
        findViewById<LinearLayout>(R.id.rowImportData).setOnClickListener {
            if (importing) return@setOnClickListener
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
            runCatching { startActivityForResult(pick, REQ_IMPORT) }.onFailure {
                importSub?.text = getString(R.string.import_failed, it.message ?: it.javaClass.simpleName)
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

    /**
     * 常用配置文件选择器（v1.4.29，issue #25）。
     *
     * 只列「改坏了能恢复 / 有意义」的文件 —— 全部在应用私有目录内，
     * FileEditorActivity 的路径检查天然放行：
     *  · AGENTS.md        用户给 AI 的长期指令（自定义指令页也写这个）
     *  · 工作区注册表      workspace.json（列表里看不到会话时排查）
     *  · 插件 profile      profiles/web/package.json（加错插件起不来时手改）
     */
    private fun showConfigFilePicker() {
        val home = app.dsh.mobile.engine.EngineConfig.dshHome(this)
        val candidates: List<Triple<String, java.io.File, String>> = listOf(
            Triple("AGENTS.md", java.io.File(home, "AGENTS.md"), "Instructions given to the AI"),
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
        if (requestCode == REQ_IMPORT) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) runImport(uri)
            return
        }
        if (requestCode != REQ_EXPORT) return
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) runExport(uri)
    }

    /**
     * 后台导入（v1.4.29，issue #31）。合并式：不覆盖现有文件，只补缺失的。
     *
     * 先把 URI 复制到私有缓存再解包 —— ContentResolver 的流不可 seek，
     * 而 ZipFile 需要随机访问。
     */
    private fun runImport(uri: Uri) {
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
                    app.dsh.mobile.engine.DataExport.import(this, cache) { done, total ->
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
            runOnUiThread {
                importing = false
                result.onSuccess { s ->
                    importSub?.text = getString(
                        R.string.import_done, s.sessionFiles, s.workspaceFiles, s.overwritten,
                    )
                    if (s.sessionFiles > 0 || s.workspaceFiles > 0) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.import_done_title)
                            .setMessage(getString(R.string.import_done_body, s.sessionFiles + s.workspaceFiles))
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
     * 详细过程与失败原因见 engine.log 的 `[export]` 行（DataExport 负责写）。
     */
    private fun runExport(uri: Uri) {
        exporting = true
        exportSub?.text = getString(R.string.export_preparing)
        Thread({
            var lastShown = 0L
            val result = runCatching {
                val out = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("cannot open $uri")
                out.use { stream ->
                    app.dsh.mobile.engine.DataExport.export(this, stream) { done, total ->
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
            runOnUiThread {
                exporting = false
                result.onSuccess { s ->
                    val done = getString(R.string.export_done, s.sessionFiles, s.workspaceFiles)
                    exportSub?.text = done
                    val msg = buildString {
                        append(done).append("\n\n").append(getString(R.string.export_no_creds))
                        if (!s.hasRegistry) append("\n\n").append(getString(R.string.export_no_registry))
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
                            .setMessage(failed + "\n\n" + getString(R.string.export_log_hint))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }, "data-export").start()
    }

    private companion object {
        const val REQ_EXPORT = 4204
        const val REQ_IMPORT = 4205
    }
}

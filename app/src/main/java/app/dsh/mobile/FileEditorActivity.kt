package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * 应用内文件编辑页（v1.4.0，issue #25）。
 *
 * ## 为什么有这个页面
 * 上游 dsh 的「打开配置文件」把路径交给**桌面**文本编辑器：
 * macOS `open -t`、Windows 资源管理器、Linux `xdg-open`。Android 三者皆无，
 * `dsh-native-command` 的 `openNativePathWithIntent` 直接抛
 * `native path opener is unsupported on android`，前端把它显示成
 * 「无法打开配置文件」—— 用户以为文件坏了，其实只是没有可用的目标程序。
 *
 * 本页提供应用内的等价能力：读、改、原子保存，不依赖任何外部程序。
 * 配置文件本身在应用私有目录（`files/dsh-home/profiles/...`），
 * root 之外只有本应用读得到，所以「在应用内编辑」本来就是唯一可靠的形态。
 *
 * ## 入口
 * AgentBridge 的 `POST /open-file`（token 鉴权，只有引擎内 Agent 调得到）
 * 以 `FLAG_ACTIVITY_NEW_TASK` 拉起本页。
 *
 * ## 保存的原子性
 * 先写同目录临时文件、继承原文件权限、再 rename 覆盖 —— 避免写到一半失败留下
 * 半截配置（引擎读到坏 YAML 会触发 ProfileGuardian 安全模式，用户看到的是
 * 「我的插件全没了」）。rename 在同一文件系统内是原子的，要么全新要么全旧。
 */
class FileEditorActivity : ThemedActivity() {

    companion object {
        /** 目标文件绝对路径（由桥传入）。 */
        const val EXTRA_PATH = "path"

        /**
         * 可直接编辑的上限。
         *
         * 1MB 是刻意保守的值：EditText 是单节点全量文本控件，超过这个量级输入与
         * 滚动都会明显卡顿（本项目的 WebView 长会话卡顿就是同类问题的放大版）。
         * 更大的文件引导用户让 AI 在会话里处理，那里有真正的流式读写工具。
         */
        private const val MAX_EDIT_BYTES = 1_000_000L
    }

    private lateinit var editor: EditText
    private lateinit var txtTitle: TextView
    private lateinit var txtPath: TextView
    private lateinit var txtNotice: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnReload: TextView

    /** 目标文件；null 表示入口参数无效（页面只展示错误）。 */
    private var target: File? = null

    /** 载入时的内容快照，用于判断「有没有改过」。 */
    private var loadedText: String = ""

    /** 文件是否可编辑（过大或非 UTF-8 时为 false，此时隐藏保存）。 */
    private var editable = true

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_editor)

        editor = findViewById(R.id.editorFile)
        txtTitle = findViewById(R.id.txtTitle)
        txtPath = findViewById(R.id.txtPath)
        txtNotice = findViewById(R.id.txtNotice)
        btnSave = findViewById(R.id.btnSave)
        btnReload = findViewById(R.id.btnReload)

        val raw = intent?.getStringExtra(EXTRA_PATH).orEmpty()
        if (raw.isBlank()) {
            finish()
            return
        }
        val f = File(raw)
        target = f
        txtTitle.text = f.name
        txtPath.text = f.absolutePath

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { confirmDiscardThenFinish() }
        findViewById<android.view.View>(R.id.btnCopyPath).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("path", f.absolutePath))
            Toast.makeText(this, getString(R.string.file_editor_path_copied), Toast.LENGTH_SHORT).show()
        }
        btnSave.setOnClickListener { save() }
        btnReload.setOnClickListener { load(reloading = true) }

        load(reloading = false)
    }

    override fun onBackPressed() {
        confirmDiscardThenFinish()
    }

    // ── 读 ────────────────────────────────────────────────────────────────

    /**
     * 把文件读进编辑框。
     *
     * @param reloading true 表示用户主动点了「重新载入」（按钮本身就在
     *                  「放弃并读回磁盘」的语义里，无需再确认）。
     */
    private fun load(reloading: Boolean) {
        val f = target ?: return
        if (!f.isFile) {
            markUneditable(getString(R.string.file_editor_read_failed))
            return
        }
        if (f.length() > MAX_EDIT_BYTES) {
            markUneditable(getString(R.string.file_editor_too_large, humanSize(f.length())))
            return
        }
        val bytes = runCatching { f.readBytes() }.getOrElse {
            markUneditable(getString(R.string.file_editor_read_failed))
            return
        }
        // 二进制判定：NUL 字节。UTF-8 文本里不会出现裸 NUL，图片/可执行文件几乎必有。
        if (bytes.any { it == 0.toByte() }) {
            markUneditable(getString(R.string.file_editor_binary))
            return
        }
        // 严格解码：非法 UTF-8（GBK 等）会抛错，此时不猜编码 —— 猜错保存回去
        // 就是数据损坏。告诉用户这是非 UTF-8 文本，让他用别的方式处理。
        val text = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            markUneditable(getString(R.string.file_editor_binary))
            return
        }

        editable = true
        loadedText = text
        editor.setText(text)
        editor.isEnabled = true
        editor.isFocusable = true
        btnSave.visibility = android.view.View.VISIBLE
        btnReload.visibility = android.view.View.VISIBLE
        if (reloading) {
            Toast.makeText(this, getString(R.string.file_editor_reloaded), Toast.LENGTH_SHORT).show()
        }
    }

    /** 文件不可编辑：展示原因、隐藏保存、编辑框设为只读并展示已读到的内容（若有）。 */
    private fun markUneditable(reason: String) {
        editable = false
        txtNotice.text = reason
        txtNotice.visibility = android.view.View.VISIBLE
        btnSave.visibility = android.view.View.GONE
        editor.isEnabled = false
        val f = target
        if (f != null && f.isFile && f.length() <= MAX_EDIT_BYTES) {
            runCatching { editor.setText(String(f.readBytes(), StandardCharsets.UTF_8)) }
        }
    }

    // ── 写 ────────────────────────────────────────────────────────────────

    /**
     * 原子保存。
     *
     * 顺序：同目录临时文件 → 继承原文件权限 → rename 覆盖。
     * 为什么不直接写原文件：写到一半失败会留下半截配置，引擎读到坏 YAML 会
     * 触发 ProfileGuardian 的安全模式（把配置归档并清空）—— 用户看到的是
     * 「我的插件全没了」。rename 在同一文件系统内是原子的，要么全新要么全旧。
     */
    private fun save() {
        val f = target ?: return
        if (!editable) return
        val text = editor.text.toString()
        val tmp = File(f.parentFile, ".${f.name}.dsh-tmp")
        try {
            tmp.writeText(text, StandardCharsets.UTF_8)
            // 权限：沿用原文件。配置文件常是 0600/0644，改写不该改变可读范围。
            runCatching {
                val perms: Set<PosixFilePermission> = Files.getPosixFilePermissions(f.toPath())
                Files.setPosixFilePermissions(tmp.toPath(), perms)
            }
            if (!tmp.renameTo(f)) {
                // 少数情况（目标被别的进程持有）：退化为覆盖，但仍先完整落盘。
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            loadedText = text
            Toast.makeText(this, getString(R.string.file_editor_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Toast.makeText(
                this,
                getString(R.string.file_editor_save_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** 有未保存修改时先确认，再决定是否退出。 */
    private fun confirmDiscardThenFinish() {
        if (editable && editor.text.toString() != loadedText) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_editor_unsaved_title)
                .setMessage(R.string.file_editor_unsaved_body)
                .setPositiveButton(R.string.file_editor_discard) { _, _ -> finish() }
                .setNegativeButton(R.string.file_editor_keep_editing, null)
                .show()
            return
        }
        finish()
    }

    /** 人类可读的文件大小（只用于提示文案）。 */
    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}

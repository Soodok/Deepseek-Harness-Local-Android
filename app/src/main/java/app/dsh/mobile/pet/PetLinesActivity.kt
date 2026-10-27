package app.dsh.mobile.pet

import android.app.Activity
import app.dsh.mobile.ThemedActivity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.LocaleHelper
import app.dsh.mobile.MaxWidthLinearLayout
import app.dsh.mobile.OrientationPrefs
import app.dsh.mobile.R

/**
 * 台词库编辑器：11 个分类、一行一句；支持导出/导入文件、分类恢复默认、全部恢复内置。
 *
 * 存储：见 [PetLines]（filesDir/pet/lines/<cat>.txt）。编辑的是用户覆盖层，
 * 内置台词永远不修改，「恢复默认/全部恢复内置」只是删除覆盖文件。
 */
class PetLinesActivity : ThemedActivity() {

    private lateinit var content: LinearLayout
    private var editingCat: String? = null
    private var editor: EditText? = null

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
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> exportTo(uri)
            REQ_IMPORT -> importFrom(uri)
        }
    }

    // ------------------------------------------------------------ 列表页

    private fun render() {
        if (editingCat != null) {
            renderEditor(editingCat!!)
            return
        }
        content.removeAllViews()

        section(getString(R.string.pet_lines), true)
        card().also { c ->
            c.addView(
                row(getString(R.string.pet_lines_export), getString(R.string.pet_lines_export_sub)) {
                    pickExport()
                },
            )
            c.addView(divider())
            c.addView(
                row(getString(R.string.pet_lines_import), getString(R.string.pet_lines_import_sub)) {
                    pickImport()
                },
            )
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_lines_reset_all),
                    getString(R.string.pet_lines_reset_all_sub),
                ) { confirmResetAll() },
            )
            content.addView(c)
        }

        section(getString(R.string.pet_lines_categories), false)
        card().also { c ->
            PetLines.CATEGORIES.forEachIndexed { i, cat ->
                if (i > 0) c.addView(divider())
                val count = PetLines.lines(this, cat.id).size
                val customized = runCatching {
                    java.io.File(PetLines.dir(this), "${cat.id}.txt").isFile
                }.getOrDefault(false)
                c.addView(
                    row(
                        getString(cat.titleRes),
                        getString(R.string.pet_lines_count, count) +
                            if (customized) getString(R.string.pet_lines_customized) else "",
                    ) { openEditor(cat.id) },
                )
            }
            content.addView(c)
        }
    }

    private fun openEditor(catId: String) {
        editingCat = catId
        render()
    }

    // ------------------------------------------------------------ 编辑页

    private fun renderEditor(catId: String) {
        content.removeAllViews()
        val title = getString(PetLines.CATEGORIES.first { it.id == catId }.titleRes)

        content.addView(headerRow(title))
        content.addView(rowText(getString(R.string.pet_lines_edit_hint)))

        card().also { c ->
            val et = EditText(this).apply {
                setText(PetLines.lines(this@PetLinesActivity, catId).joinToString("\n"))
                setHint(R.string.pet_lines_edit_hint)
                minLines = 8
                gravity = Gravity.TOP
                setTextSize(14f)
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(0xFF8A94A3.toInt())
                setBackgroundResource(android.R.color.transparent)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            editor = et
            c.addView(
                et,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            content.addView(c)
        }

        card().also { c ->
            c.addView(
                row(getString(R.string.pet_lines_save), "") { saveEditor(catId) },
            )
            c.addView(divider())
            c.addView(
                row(getString(R.string.pet_lines_reset_cat), "") { confirmResetCategory(catId) },
            )
            content.addView(c)
        }
    }

    private fun headerRow(title: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 22f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(10), dp(6), dp(14), dp(6))
            isClickable = true
            setOnClickListener {
                editingCat = null
                editor = null
                render()
            }
        }
        val tv = TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }
        row.addView(back)
        row.addView(tv)
        return row
    }

    private fun saveEditor(catId: String) {
        val et = editor ?: return
        val lines = et.text.toString().split('\n')
        PetLines.save(this, catId, lines)
        toast(getString(R.string.pet_lines_saved, PetLines.lines(this, catId).size))
    }

    private fun confirmResetCategory(catId: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_lines_reset_cat)
            .setMessage(R.string.pet_lines_reset_cat_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PetLines.resetCategory(this, catId)
                editor?.setText(PetLines.lines(this, catId).joinToString("\n"))
                toast(getString(R.string.pet_lines_reset_done))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_lines_reset_all)
            .setMessage(R.string.pet_lines_reset_all_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PetLines.resetAll(this)
                toast(getString(R.string.pet_lines_reset_done))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ 导入导出

    private fun pickExport() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "pet-lines.json")
        runCatching { startActivityForResult(i, REQ_EXPORT) }
            .onFailure { toast(it.message ?: getString(R.string.pet_lines_import_failed)) }
    }

    private fun exportTo(uri: android.net.Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(PetLines.exportJson(this).toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("output stream null")
            toast(getString(R.string.pet_lines_exported))
        }.onFailure {
            toast(getString(R.string.pet_lines_export_failed))
        }
    }

    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        runCatching { startActivityForResult(i, REQ_IMPORT) }
            .onFailure { toast(it.message ?: getString(R.string.pet_lines_import_failed)) }
    }

    private fun importFrom(uri: android.net.Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { ins ->
                ins.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            toast(getString(R.string.pet_lines_import_failed))
            return
        }
        val saved = PetLines.importJson(this, text)
        if (saved > 0) {
            toast(getString(R.string.pet_lines_imported, saved))
            render()
        } else {
            toast(getString(R.string.pet_lines_import_failed))
        }
    }

    // ------------------------------------------------------------ 视图小工具（与 PetSettingsActivity 同风格）

    private fun section(title: String, first: Boolean) {
        content.addView(TextView(this).apply {
            text = title
            setTextColor(0xFF7DD3FC.toInt())
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), if (first) dp(8) else dp(18), dp(8), dp(8))
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

    private fun row(title: String, subtitle: String, onClick: () -> Unit): View {
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
                setTextColor(0xFF8A94A3.toInt())
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(col)
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 16f
            setTextColor(0xFF8A94A3.toInt())
            setPadding(dp(10), 0, 0, 0)
        })
        return row
    }

    private fun rowText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFF8A94A3.toInt())
        textSize = 12f
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EXPORT = 4401
        private const val REQ_IMPORT = 4402
        private const val MAX_WIDTH_DP = 680
    }
}

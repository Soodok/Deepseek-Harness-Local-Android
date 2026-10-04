package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.PresetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class PresetActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var exportingId: String? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.presets_title)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.presets_description)
            textSize = 14f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(android.widget.Button(this).apply {
            text = getString(R.string.presets_import)
            setOnClickListener { choosePreset() }
        })
        status = TextView(this).apply {
            text = getString(R.string.presets_trust_warning)
            textSize = 13f
            setTextColor(0xFFFFCC80.toInt())
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    @Deprecated("The system document picker is used for Android 8 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQUEST_IMPORT -> previewImport(data.data!!)
            REQUEST_EXPORT -> exportingId?.let { exportPreset(it, data.data!!) }
        }
    }

    private fun choosePreset() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"),
            REQUEST_IMPORT,
        )
    }

    private fun previewImport(uri: Uri) {
        scope.launch {
            val staged = File(cacheDir, "preset-${System.nanoTime()}.dshpreset")
            try {
                val preset = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri) ?: throw IOException(getString(R.string.presets_source_unavailable))
                    input.use { source ->
                        staged.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_PACKAGE_BYTES) throw IOException(getString(R.string.presets_too_large))
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    PresetManager.preview(staged)
                }
                AlertDialog.Builder(this@PresetActivity)
                    .setTitle(getString(R.string.presets_preview_title, preset.name))
                    .setMessage(getString(R.string.presets_preview_message, preset.id, preset.description.ifBlank { getString(R.string.presets_no_description) }))
                    .setPositiveButton(R.string.presets_install) { _, _ -> installPreset(preset) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (error: Exception) {
                showError(error)
            } finally {
                staged.delete()
            }
        }
    }

    private fun installPreset(preset: PresetManager.Preset) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { PresetManager.install(this@PresetActivity, preset) }
                (application as DshApp).supervisor.restart()
                Toast.makeText(this@PresetActivity, R.string.presets_installed, Toast.LENGTH_LONG).show()
                refresh()
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun exportPreset(id: String, uri: Uri) {
        scope.launch {
            val staged = File(cacheDir, "preset-export-${System.nanoTime()}.dshpreset")
            try {
                withContext(Dispatchers.IO) { PresetManager.export(this@PresetActivity, id, staged) }
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException(getString(R.string.presets_destination_unavailable))
                withContext(Dispatchers.IO) {
                    output.use { target -> staged.inputStream().use { it.copyTo(target) } }
                }
                Toast.makeText(this@PresetActivity, R.string.presets_exported, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                showError(error)
            } finally {
                exportingId = null
                staged.delete()
            }
        }
    }

    private fun refresh() {
        scope.launch {
            try {
                val presets = withContext(Dispatchers.IO) { PresetManager.list(this@PresetActivity) }
                list.removeAllViews()
                if (presets.isEmpty()) {
                    list.addView(TextView(this@PresetActivity).apply {
                        text = getString(R.string.presets_empty)
                        setTextColor(0xFF9CA3AF.toInt())
                        setPadding(0, dp(8), 0, dp(8))
                    })
                }
                presets.forEach { preset ->
                    val row = TextView(this@PresetActivity).apply {
                        text = "${preset.name}\n${preset.id}\n${getString(R.string.presets_row_actions)}"
                        textSize = 15f
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = getDrawable(R.drawable.bg_card)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { showActions(preset) }
                        setOnLongClickListener {
                            confirmRemove(preset)
                            true
                        }
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    params.bottomMargin = dp(8)
                    list.addView(row, params)
                }
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun showActions(preset: PresetManager.Preset) {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.presets_export), getString(R.string.presets_remove))) { _, which ->
                if (which == 0) {
                    exportingId = preset.id
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/vnd.dsh.preset+zip")
                            .putExtra(Intent.EXTRA_TITLE, "${preset.id}.dshpreset"),
                        REQUEST_EXPORT,
                    )
                } else {
                    confirmRemove(preset)
                }
            }
            .show()
    }

    private fun confirmRemove(preset: PresetManager.Preset) {
        AlertDialog.Builder(this)
            .setTitle(R.string.presets_remove)
            .setMessage(getString(R.string.presets_remove_confirm, preset.name))
            .setPositiveButton(R.string.presets_remove) { _, _ ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { PresetManager.remove(this@PresetActivity, preset.id) }
                        (application as DshApp).supervisor.restart()
                        refresh()
                    } catch (error: Exception) {
                        showError(error)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showError(error: Throwable) {
        Toast.makeText(this, error.message ?: getString(R.string.presets_operation_failed), Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_IMPORT = 1
        const val REQUEST_EXPORT = 2
        const val MAX_PACKAGE_BYTES = 5L shl 20
    }
}

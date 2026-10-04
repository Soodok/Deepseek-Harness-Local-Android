package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.ProfileGuardian
import app.dsh.mobile.engine.RecoveryDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecoveryActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var safeModeButton: Button
    private lateinit var report: TextView

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.recovery_title)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        })
        safeModeButton = Button(this)
        safeModeButton.setOnClickListener { confirmSafeModeAction() }
        root.addView(safeModeButton)
        root.addView(Button(this).apply {
            text = getString(R.string.recovery_export)
            setOnClickListener { confirmExport() }
        })
        val scroll = ScrollView(this)
        report = TextView(this).apply {
            setTextColor(0xFFE5E7EB.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        }
        scroll.addView(report)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val guardian = ProfileGuardian(this)
        val inSafeMode = guardian.inSafeMode()
        safeModeButton.text = getString(
            if (inSafeMode) R.string.recovery_exit_safe_mode else R.string.recovery_start_safe_mode,
        )
        safeModeButton.visibility = View.VISIBLE
        scope.launch {
            report.text = withContext(Dispatchers.IO) {
                val engineState = (application as DshApp).supervisor.state.value.toString()
                val runtime = runCatching {
                    File(EngineConfig.engineRoot(this@RecoveryActivity), ".runtime-version").readText().trim()
                }.getOrDefault("unknown")
                val archives = EngineConfig.dshHome(this@RecoveryActivity).listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("profiles.crash-archive-") }
                    ?.sortedByDescending { it.name }
                    ?.map { it.name }
                    .orEmpty()
                val logTail = runCatching {
                    val file = (application as DshApp).supervisor.logFile()
                    if (file.isFile) file.readText().takeLast(64 * 1024) else "No engine log is available."
                }.getOrDefault("Engine log could not be read.")
                RecoveryDiagnostics.report(engineState, runtime, archives, logTail)
            }
        }
    }

    private fun confirmSafeModeAction() {
        val entering = !ProfileGuardian(this).inSafeMode()
        AlertDialog.Builder(this)
            .setTitle(if (entering) R.string.recovery_start_safe_mode else R.string.recovery_exit_safe_mode)
            .setMessage(if (entering) R.string.recovery_start_confirm else R.string.recovery_exit_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val guardian = ProfileGuardian(this@RecoveryActivity)
                            if (entering) guardian.enterSafeMode("user requested safe mode")
                            else guardian.exitSafeMode()
                        }
                        (application as DshApp).supervisor.restart()
                        refresh()
                    }.onFailure {
                        Toast.makeText(
                            this@RecoveryActivity,
                            it.message ?: getString(R.string.recovery_operation_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmExport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.recovery_export)
            .setMessage(R.string.recovery_export_warning)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TITLE, "dsh-recovery-report.txt"),
                    REQUEST_REPORT,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("The system document picker is used for Android 8 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_REPORT || resultCode != RESULT_OK || data?.data == null) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(data.data!!)?.use {
                        it.write(report.text.toString().toByteArray(Charsets.UTF_8))
                    } ?: error(getString(R.string.recovery_destination_failed))
                }
            }.onFailure {
                Toast.makeText(this@RecoveryActivity, it.message ?: getString(R.string.recovery_destination_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_REPORT = 5201
    }
}

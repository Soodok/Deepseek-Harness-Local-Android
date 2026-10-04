package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.LocalDataBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BackupActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var pendingPassphrase: CharArray? = null
    private var restoreUri: Uri? = null
    private var busy = false
    private lateinit var status: TextView

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.backup_title)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.backup_description)
            textSize = 14f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(8), 0, dp(16))
        })
        root.addView(android.widget.Button(this).apply {
            text = getString(R.string.backup_export)
            setOnClickListener { promptPassphrase(exporting = true) }
        })
        root.addView(android.widget.Button(this).apply {
            text = getString(R.string.backup_import)
            setOnClickListener { chooseBackup() }
        })
        status = TextView(this).apply {
            text = getString(R.string.backup_idle)
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(status)
        setContentView(root)
    }

    private fun promptPassphrase(exporting: Boolean) {
        val password = EditText(this).apply {
            hint = getString(R.string.backup_passphrase_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmation = if (exporting) EditText(this).apply {
            hint = getString(R.string.backup_passphrase_confirm)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else null
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
            addView(password)
            confirmation?.let(::addView)
        }
        AlertDialog.Builder(this)
            .setTitle(if (exporting) R.string.backup_export else R.string.backup_import)
            .setView(fields)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = password.text.toString()
                if (value.length < 12 || (confirmation != null && value != confirmation.text.toString())) {
                    password.text.clear()
                    confirmation?.text?.clear()
                    toast(R.string.backup_passphrase_invalid)
                    return@setPositiveButton
                }
                pendingPassphrase?.fill('\u0000')
                pendingPassphrase = value.toCharArray()
                password.text.clear()
                confirmation?.text?.clear()
                if (exporting) {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/vnd.dsh.backup")
                            .putExtra(Intent.EXTRA_TITLE, "dsh-mobile-backup.dshbackup"),
                        REQUEST_CREATE,
                    )
                } else {
                    confirmAndRestore()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseBackup() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"),
            REQUEST_OPEN,
        )
    }

    @Deprecated("The system document picker is used for Android 8 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) {
            pendingPassphrase?.fill('\u0000')
            pendingPassphrase = null
            restoreUri = null
            return
        }
        when (requestCode) {
            REQUEST_CREATE -> exportTo(data.data!!)
            REQUEST_OPEN -> {
                restoreUri = data.data
                promptPassphrase(exporting = false)
            }
        }
    }

    private fun confirmAndRestore() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_restore_confirm_title)
            .setMessage(R.string.backup_restore_confirm_message)
            .setPositiveButton(R.string.backup_import) { _, _ -> restoreSelectedBackup() }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingPassphrase?.fill('\u0000')
                pendingPassphrase = null
                restoreUri = null
            }
            .show()
    }

    private fun exportTo(uri: Uri) {
        runBackupOperation {
            val encrypted = File(cacheDir, "backup-${System.nanoTime()}.dshbackup")
            try {
                LocalDataBackup.export(this, encrypted, requireNotNull(pendingPassphrase))
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: error(getString(R.string.backup_destination_unavailable))
                output.use { target -> encrypted.inputStream().use { it.copyTo(target) } }
            } finally {
                pendingPassphrase?.fill('\u0000')
                pendingPassphrase = null
                encrypted.delete()
            }
        }
    }

    private fun restoreSelectedBackup() {
        val uri = restoreUri ?: run {
            toast(R.string.backup_source_unavailable)
            return
        }
        restoreUri = null
        runBackupOperation {
            val encrypted = File(cacheDir, "restore-${System.nanoTime()}.dshbackup")
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: error(getString(R.string.backup_source_unavailable))
                input.use { source -> encrypted.outputStream().use { source.copyTo(it) } }
                LocalDataBackup.restore(this, encrypted, requireNotNull(pendingPassphrase))
            } finally {
                pendingPassphrase?.fill('\u0000')
                pendingPassphrase = null
                encrypted.delete()
            }
        }
    }

    private fun runBackupOperation(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        status.text = getString(R.string.backup_working)
        scope.launch {
            val app = application as DshApp
            val currentState = app.supervisor.state.value
            val wasRunning = (
                currentState is EngineSupervisor.State.Healthy ||
                    currentState is EngineSupervisor.State.SafeMode ||
                    currentState is EngineSupervisor.State.Starting ||
                    currentState is EngineSupervisor.State.Installing ||
                    currentState is EngineSupervisor.State.Backoff
                ) && !app.supervisor.isUserStopped()
            try {
                withContext(Dispatchers.IO) {
                    if (wasRunning) app.supervisor.stop()
                    operation()
                }
                status.text = getString(R.string.backup_complete)
            } catch (e: Exception) {
                status.text = getString(R.string.backup_failed, e.message ?: "")
            } finally {
                if (wasRunning) app.supervisor.start(app.appScope)
                busy = false
            }
        }
    }

    private fun toast(message: Int) =
        Toast.makeText(this, getString(message), Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        pendingPassphrase?.fill('\u0000')
        pendingPassphrase = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CREATE = 5101
        private const val REQUEST_OPEN = 5102
    }
}

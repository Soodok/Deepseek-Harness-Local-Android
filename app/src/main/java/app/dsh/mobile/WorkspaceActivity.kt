package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WorkspaceActivity : Activity() {

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main)
    private lateinit var workspaceList: LinearLayout

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workspace)
        workspaceList = findViewById(R.id.workspaceList)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCreateWorkspace).setOnClickListener { showCreateDialog() }
        refresh()
    }

    override fun onDestroy() {
        scopeJob.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch {
            val workspaces = withContext(Dispatchers.IO) { WorkspaceManager.list(this@WorkspaceActivity) }
            workspaceList.removeAllViews()
            workspaces.forEach { workspace ->
                val row = TextView(this@WorkspaceActivity).apply {
                    text = buildString {
                        append(if (workspace.active) getString(R.string.workspace_active_prefix) else "")
                        append(if (workspace.id == WorkspaceManager.DEFAULT_ID) getString(R.string.workspace_default) else workspace.name)
                        append("\n")
                        append(workspace.directory.name)
                    }
                    setTextColor(if (workspace.active) 0xFF6EE7B7.toInt() else 0xFFFFFFFF.toInt())
                    textSize = 16f
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = getDrawable(R.drawable.bg_card)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { select(workspace) }
                    setOnLongClickListener {
                        if (workspace.id != WorkspaceManager.DEFAULT_ID) showActions(workspace)
                        true
                    }
                }
                workspaceList.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) },
                )
            }
        }
    }

    private fun select(workspace: WorkspaceManager.Workspace) {
        if (workspace.active) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { WorkspaceManager.select(this@WorkspaceActivity, workspace.id) }
            }.onSuccess {
                (application as DshApp).supervisor.restart()
                Toast.makeText(this@WorkspaceActivity, R.string.workspace_selected, Toast.LENGTH_SHORT).show()
                refresh()
            }.onFailure { showError(it) }
        }
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.workspace_name_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.workspace_create)
            .setView(input)
            .setPositiveButton(R.string.workspace_create) { _, _ ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            WorkspaceManager.create(this@WorkspaceActivity, input.text.toString())
                        }
                    }.onSuccess {
                        refresh()
                    }.onFailure { showError(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showActions(workspace: WorkspaceManager.Workspace) {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.workspace_rename), getString(R.string.workspace_delete))) { _, which ->
                if (which == 0) showRenameDialog(workspace) else confirmDelete(workspace)
            }
            .show()
    }

    private fun showRenameDialog(workspace: WorkspaceManager.Workspace) {
        val input = EditText(this).apply {
            setText(workspace.name)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.workspace_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            WorkspaceManager.rename(this@WorkspaceActivity, workspace.id, input.text.toString())
                        }
                    }.onSuccess { renamed ->
                        if (renamed.active) (application as DshApp).supervisor.restart()
                        refresh()
                    }.onFailure { showError(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(workspace: WorkspaceManager.Workspace) {
        AlertDialog.Builder(this)
            .setTitle(R.string.workspace_delete)
            .setMessage(getString(R.string.workspace_delete_confirm, workspace.name))
            .setPositiveButton(R.string.workspace_delete) { _, _ ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { WorkspaceManager.delete(this@WorkspaceActivity, workspace.id) }
                    }.onSuccess {
                        if (workspace.active) (application as DshApp).supervisor.restart()
                        refresh()
                    }.onFailure { showError(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showError(error: Throwable) {
        Toast.makeText(this, error.message ?: getString(R.string.workspace_operation_failed), Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

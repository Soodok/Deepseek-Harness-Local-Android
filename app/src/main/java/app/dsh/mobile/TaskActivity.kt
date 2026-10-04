package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Task management activity (Phase 3).
 * Shows the list of agent tasks with their status, priority, and controls.
 */
class TaskActivity : Activity() {

    private lateinit var taskContainer: LinearLayout
    private lateinit var emptyView: TextView
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task)

        taskContainer = findViewById(R.id.taskContainer)
        emptyView = findViewById(R.id.taskEmpty)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        scope.launch {
            TaskManager.storageError.collectLatest { message ->
                if (message != null) {
                    Toast.makeText(this@TaskActivity, getString(R.string.agent_task_storage_error, message), Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<LinearLayout>(R.id.rowCreateTask).setOnClickListener {
            showCreateTaskDialog()
        }

        // Subscribe to task list
        scope.launch {
            TaskManager.taskList.collectLatest { tasks ->
                renderTasks(tasks)
            }
        }
    }

    private fun renderTasks(tasks: List<TaskManager.AgentTask>) {
        taskContainer.removeAllViews()
        if (tasks.isEmpty()) {
            emptyView.visibility = android.view.View.VISIBLE
            return
        }
        emptyView.visibility = android.view.View.GONE
        for (task in tasks) {
            val item = layoutInflater.inflate(R.layout.item_task, taskContainer, false) as LinearLayout
            item.findViewById<TextView>(R.id.taskTitle).text = task.title
            item.findViewById<TextView>(R.id.taskDescription).text = task.description
            item.findViewById<TextView>(R.id.taskStatus).text = task.status.name
            item.findViewById<TextView>(R.id.taskPriority).text = task.priority.name

            // Status color
            val statusView = item.findViewById<TextView>(R.id.taskStatus)
            val color = when (task.status) {
                TaskManager.Status.DONE -> 0xFF6EE7B7.toInt()
                TaskManager.Status.FAILED -> 0xFFFFB74D.toInt()
                TaskManager.Status.RUNNING -> 0xFF7DD3FC.toInt()
                TaskManager.Status.CANCELED -> 0xFF8A94A3.toInt()
                else -> 0xFF9CA3AF.toInt()
            }
            statusView.setTextColor(color)

            // Priority color
            val prioView = item.findViewById<TextView>(R.id.taskPriority)
            val prioColor = when (task.priority) {
                TaskManager.Priority.HIGH -> 0xFFFFB74D.toInt()
                TaskManager.Priority.NORMAL -> 0xFF7DD3FC.toInt()
                TaskManager.Priority.LOW -> 0xFF8A94A3.toInt()
            }
            prioView.setTextColor(prioColor)

            // Cancel button
            item.findViewById<LinearLayout>(R.id.btnCancelTask).setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    try {
                        if (TaskManager.cancel(task.id)) launch { toast(R.string.agent_task_canceled) }
                    } catch (e: Exception) {
                        launch { Toast.makeText(this@TaskActivity, e.message ?: getString(R.string.agent_task_storage_error, ""), Toast.LENGTH_LONG).show() }
                    }
                }
            }

            taskContainer.addView(item)
        }
    }

    private fun showCreateTaskDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val titleInput = EditText(this).apply {
            hint = getString(R.string.agent_task_prompt_hint)
            setSingleLine(true)
        }
        val descInput = EditText(this).apply {
            hint = getString(R.string.agent_task_description_hint)
            setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            minHeight = dp(120)
        }
        val priorityGroup = RadioGroup(this)
        priorityGroup.orientation = RadioGroup.HORIZONTAL
        val priorities = listOf(TaskManager.Priority.HIGH, TaskManager.Priority.NORMAL, TaskManager.Priority.LOW)
        val priorityLabels = listOf(
            getString(R.string.agent_task_priority_high),
            getString(R.string.agent_task_priority_normal),
            getString(R.string.agent_task_priority_low)
        )
        priorities.forEachIndexed { i, p ->
            val rb = RadioButton(this)
            rb.text = priorityLabels[i]
            rb.id = i
            priorityGroup.addView(rb)
        }
        priorityGroup.check(1) // Default: NORMAL

        box.addView(titleInput)
        box.addView(descInput)
        box.addView(priorityGroup)

        AlertDialog.Builder(this)
            .setTitle(R.string.agent_task_create)
            .setView(box)
            .setPositiveButton(R.string.agent_task_create_btn) { _, _ ->
                val title = titleInput.text.toString().trim()
                val desc = descInput.text.toString().trim()
                if (title.isBlank()) {
                    toast(R.string.agent_task_prompt_hint)
                    return@setPositiveButton
                }
                val priority = priorities[priorityGroup.checkedRadioButtonId]
                scope.launch(Dispatchers.IO) {
                    try {
                        TaskManager.create(title, desc.ifEmpty { title }, priority)
                        launch { toast(R.string.agent_task_created, title) }
                    } catch (e: Exception) {
                        launch { Toast.makeText(this@TaskActivity, e.message ?: getString(R.string.agent_task_storage_error, ""), Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(msg: Int, vararg args: String) {
        val text = if (args.isEmpty()) getString(msg) else getString(msg, *args)
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

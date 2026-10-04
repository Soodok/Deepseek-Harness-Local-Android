package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object TaskManager {

    private const val TAG = "TaskManager"
    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_DESCRIPTION_LENGTH = 16_384
    private const val MAX_FEEDBACK_LENGTH = 4_096
    private const val MAX_FEEDBACK_PER_TASK = 100

    private val mutex = Mutex()
    private val tasks = linkedMapOf<String, AgentTask>()
    private val feedback = linkedMapOf<String, MutableList<FeedbackEntry>>()
    private val taskIdSeq = AtomicLong(0)
    @Volatile private var initialized = false
    @Volatile private var store: TaskStore? = null

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val taskList: StateFlow<List<AgentTask>> = _tasks

    private val _feedback = MutableStateFlow<Map<String, List<FeedbackEntry>>>(emptyMap())
    val feedbackList: StateFlow<Map<String, List<FeedbackEntry>>> = _feedback

    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError

    enum class Priority { HIGH, NORMAL, LOW }

    enum class Status { PENDING, RUNNING, DONE, FAILED, CANCELED }

    data class AgentTask(
        val id: String,
        val title: String,
        val description: String,
        val priority: Priority = Priority.NORMAL,
        val status: Status = Status.PENDING,
        val createdAt: Long = System.currentTimeMillis(),
        val startedAt: Long? = null,
        val completedAt: Long? = null,
        val result: String? = null,
        val error: String? = null,
    ) {
        fun priorityValue(): Int = when (priority) {
            Priority.HIGH -> 3
            Priority.NORMAL -> 2
            Priority.LOW -> 1
        }
    }

    data class FeedbackEntry(
        val id: String,
        val taskId: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val taskStore = TaskStore(File(context.filesDir, "dsh-home/tasks.bin"))
        try {
            val snapshot = taskStore.load()
            val recovered = recoverInterrupted(snapshot.tasks, "process restart")
            if (recovered != snapshot.tasks) taskStore.save(recovered, snapshot.feedback)
            tasks.putAll(recovered.associateBy { it.id })
            snapshot.feedback.forEach { (taskId, entries) -> feedback[taskId] = entries.toMutableList() }
            taskIdSeq.set(recovered.mapNotNull { it.id.removePrefix("task_").toLongOrNull() }.maxOrNull() ?: 0)
            store = taskStore
            publishSnapshot()
            initialized = true
            _storageError.value = null
            val recoveredCount = snapshot.tasks.count { it.status == Status.RUNNING }
            if (recoveredCount > 0) {
                AuditLogger.log("task_recovered", "$recoveredCount interrupted task(s) requeued")
            }
        } catch (e: Exception) {
            _storageError.value = e.message ?: "Task storage could not be opened"
            Log.e(TAG, "Unable to initialize durable task storage", e)
        }
    }

    suspend fun create(
        title: String,
        description: String,
        priority: Priority = Priority.NORMAL,
    ): AgentTask {
        require(title.isNotBlank()) { "Task title must not be blank" }
        require(title.length <= MAX_TITLE_LENGTH) { "Task title exceeds $MAX_TITLE_LENGTH characters" }
        require(description.isNotBlank()) { "Task description must not be blank" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "Task description exceeds $MAX_DESCRIPTION_LENGTH characters"
        }
        val task = AgentTask(
            id = "task_" + taskIdSeq.incrementAndGet(),
            title = title,
            description = description,
            priority = priority,
        )
        transact { nextTasks, nextFeedback ->
            nextTasks[task.id] = task
            nextFeedback[task.id] = mutableListOf()
            task
        }.also {
            AuditLogger.log("task_created", "Task: ${task.id} (${task.title}) priority=${task.priority}")
        }
    }

    suspend fun nextTask(): AgentTask? = transact { nextTasks, _ ->
        val next = nextTasks.values.filter { it.status == Status.PENDING }
            .maxWithOrNull { a, b ->
                val priority = a.priorityValue().compareTo(b.priorityValue())
                if (priority != 0) priority else b.createdAt.compareTo(a.createdAt)
            } ?: return@transact null
        next.copy(status = Status.RUNNING, startedAt = System.currentTimeMillis())
            .also { nextTasks[next.id] = it }
    }

    suspend fun claim(id: String): AgentTask? = transact { nextTasks, _ ->
        val task = nextTasks[id] ?: return@transact null
        if (task.status != Status.PENDING) return@transact null
        task.copy(status = Status.RUNNING, startedAt = System.currentTimeMillis())
            .also { nextTasks[id] = it }
    }

    suspend fun requeueRunning(reason: String): Int {
        val count = transact { nextTasks, _ ->
            val running = nextTasks.values.filter { it.status == Status.RUNNING }
            running.forEach { task ->
                nextTasks[task.id] = task.copy(
                    status = Status.PENDING,
                    startedAt = null,
                    result = "Requeued after $reason; prior execution may have partially completed.",
                )
            }
            running.size
        }
        if (count > 0) AuditLogger.log("task_recovered", "$count interrupted task(s) requeued after $reason")
        return count
    }

    suspend fun complete(id: String, result: String? = null): Boolean {
        val completed = transact { nextTasks, _ ->
            val task = nextTasks[id] ?: return@transact null
            if (task.status != Status.RUNNING && task.status != Status.PENDING) return@transact null
            task.copy(
                status = Status.DONE,
                result = result?.take(MAX_DESCRIPTION_LENGTH),
                completedAt = System.currentTimeMillis(),
            ).also { nextTasks[id] = it }
        } ?: return false
        AuditLogger.log("task_executed", "Task complete: ${completed.id} — ${completed.title}")
        return true
    }

    suspend fun fail(id: String, error: String? = null): Boolean {
        val failed = transact { nextTasks, _ ->
            val task = nextTasks[id] ?: return@transact null
            if (task.status !in setOf(Status.RUNNING, Status.PENDING)) return@transact null
            task.copy(
                status = Status.FAILED,
                error = error?.take(MAX_DESCRIPTION_LENGTH),
                completedAt = System.currentTimeMillis(),
            ).also { nextTasks[id] = it }
        } ?: return false
        AuditLogger.log("task_executed", "Task failed: ${failed.id} — ${failed.error ?: "unknown"}")
        return true
    }

    suspend fun cancel(id: String): Boolean = transact { nextTasks, _ ->
        val task = nextTasks[id] ?: return@transact false
        if (task.status !in setOf(Status.PENDING, Status.RUNNING)) return@transact false
        nextTasks[id] = task.copy(status = Status.CANCELED, completedAt = System.currentTimeMillis())
        true
    }

    fun get(id: String): AgentTask? = _tasks.value.firstOrNull { it.id == id }

    fun getFeedback(taskId: String): List<FeedbackEntry> = _feedback.value[taskId].orEmpty()

    suspend fun addFeedback(taskId: String, text: String): FeedbackEntry? {
        require(text.isNotBlank()) { "Feedback text must not be blank" }
        require(text.length <= MAX_FEEDBACK_LENGTH) {
            "Feedback exceeds $MAX_FEEDBACK_LENGTH characters"
        }
        return transact { nextTasks, nextFeedback ->
            if (taskId !in nextTasks) return@transact null
            require(nextFeedback[taskId].orEmpty().size < MAX_FEEDBACK_PER_TASK) {
                "Task feedback limit reached"
            }
            FeedbackEntry(UUID.randomUUID().toString(), taskId, text)
                .also { nextFeedback.getOrPut(taskId) { mutableListOf() }.add(it) }
        }
    }

    suspend fun nextWithFeedback(): Pair<AgentTask, List<FeedbackEntry>>? {
        val next = _tasks.value.firstOrNull { it.status == Status.PENDING } ?: return null
        return next to getFeedback(next.id)
    }

    private suspend fun <T> transact(
        change: (MutableMap<String, AgentTask>, MutableMap<String, MutableList<FeedbackEntry>>) -> T,
    ): T = mutex.withLock {
        check(initialized) {
            _storageError.value ?: "Task storage is not initialized"
        }
        val nextTasks = tasks.toMutableMap()
        val nextFeedback = feedback.mapValuesTo(linkedMapOf()) { (_, entries) -> entries.toMutableList() }
        val result = change(nextTasks, nextFeedback)
        if (nextTasks == tasks && nextFeedback == feedback) return@withLock result
        try {
            checkNotNull(store) { "Task storage is unavailable" }.save(
                nextTasks.values,
                nextFeedback.mapValues { it.value.toList() },
            )
            _storageError.value = null
        } catch (e: Exception) {
            _storageError.value = e.message ?: "Unable to persist task queue"
            Log.e(TAG, "Unable to persist task queue", e)
            throw e
        }
        tasks.clear()
        tasks.putAll(nextTasks)
        feedback.clear()
        feedback.putAll(nextFeedback)
        publishSnapshot()
        result
    }

    private fun publishSnapshot() {
        _tasks.value = tasks.values.sortedWith(
            compareByDescending<AgentTask> { it.priorityValue() }.thenBy { it.createdAt },
        )
        _feedback.value = feedback.mapValues { it.value.toList() }
    }

    internal fun recoverInterrupted(tasks: List<AgentTask>, reason: String): List<AgentTask> =
        tasks.map { task ->
            if (task.status == Status.RUNNING) {
                task.copy(
                    status = Status.PENDING,
                    startedAt = null,
                    result = task.result ?: "Requeued after $reason; prior execution may have partially completed.",
                )
            } else {
                task
            }
        }
}

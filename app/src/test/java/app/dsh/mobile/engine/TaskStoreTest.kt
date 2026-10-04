package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAndReloadsTasksAndFeedback() {
        val store = TaskStore(temporaryFolder.newFile("tasks.bin"))
        val task = TaskManager.AgentTask(
            id = "task_1",
            title = "Build the app",
            description = "Compile and verify",
            priority = TaskManager.Priority.HIGH,
            status = TaskManager.Status.RUNNING,
            createdAt = 10,
            startedAt = 11,
        )
        val entry = TaskManager.FeedbackEntry("feedback-1", task.id, "Keep the current theme", 12)

        store.save(listOf(task), mapOf(task.id to listOf(entry)))

        assertEquals(TaskStore.Snapshot(listOf(task), mapOf(task.id to listOf(entry))), store.load())
    }

    @Test
    fun recoversFromInterruptedReplacementUsingBackup() {
        val file = temporaryFolder.newFile("tasks.bin")
        val store = TaskStore(file)
        val previous = TaskManager.AgentTask("task_1", "Old", "Saved queue")
        store.save(listOf(previous), emptyMap())
        store.save(listOf(previous.copy(title = "New")), emptyMap())
        file.writeBytes(byteArrayOf(0, 1, 2))

        assertEquals(listOf(previous), store.load().tasks)
        assertEquals(listOf(previous), TaskStore(file).load().tasks)
    }

    @Test
    fun rejectsCorruptStoreWithoutBackup() {
        val file = temporaryFolder.newFile("tasks.bin")
        file.writeBytes(byteArrayOf(0, 1, 2))

        assertThrows(java.io.IOException::class.java) { TaskStore(file).load() }
    }

    @Test
    fun requeuesOnlyInterruptedTasksAndPreservesCompletedTasks() {
        val interrupted = TaskManager.AgentTask(
            id = "task_1",
            title = "Interrupted",
            description = "May have partially run",
            status = TaskManager.Status.RUNNING,
            startedAt = 20,
        )
        val completed = TaskManager.AgentTask(
            id = "task_2",
            title = "Completed",
            description = "Keep result",
            status = TaskManager.Status.DONE,
            result = "Done",
        )

        val recovered = TaskManager.recoverInterrupted(listOf(interrupted, completed), "test restart")

        assertEquals(TaskManager.Status.PENDING, recovered[0].status)
        assertEquals(null, recovered[0].startedAt)
        assertEquals("Requeued after test restart; prior execution may have partially completed.", recovered[0].result)
        assertEquals(completed, recovered[1])
    }
}

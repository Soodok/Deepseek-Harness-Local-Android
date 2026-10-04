package app.dsh.mobile.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class TaskStore(private val file: File) {

    data class Snapshot(
        val tasks: List<TaskManager.AgentTask>,
        val feedback: Map<String, List<TaskManager.FeedbackEntry>>,
    )

    fun load(): Snapshot {
        val backup = File(file.parentFile, "${file.name}.bak")
        if (file.isFile) {
            try {
                return read(file)
            } catch (primaryError: Exception) {
                if (!backup.isFile) throw IOException("Task store is corrupt and no backup exists", primaryError)
                val restored = try {
                    read(backup)
                } catch (backupError: Exception) {
                    primaryError.addSuppressed(backupError)
                    throw IOException("Task store and its backup are corrupt", primaryError)
                }
                restoreBackup(backup)
                return restored
            }
        }
        if (backup.isFile) {
            val restored = read(backup)
            restoreBackup(backup)
            return restored
        }
        return Snapshot(emptyList(), emptyMap())
    }

    fun save(
        tasks: Collection<TaskManager.AgentTask>,
        feedback: Map<String, List<TaskManager.FeedbackEntry>>,
    ) {
        if (tasks.size > MAX_TASKS) throw IOException("Task count exceeds the storage limit")
        val parent = file.parentFile ?: throw IOException("Task store has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Unable to create task store directory")
        val temporary = File(parent, "${file.name}.tmp")
        val backup = File(parent, "${file.name}.bak")
        try {
            FileOutputStream(temporary).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(tasks.size)
                    tasks.forEach { task ->
                        output.writeString(task.id)
                        output.writeString(task.title)
                        output.writeString(task.description)
                        output.writeString(task.priority.name)
                        output.writeString(task.status.name)
                        output.writeLong(task.createdAt)
                        output.writeNullableLong(task.startedAt)
                        output.writeNullableLong(task.completedAt)
                        output.writeNullableString(task.result)
                        output.writeNullableString(task.error)
                        val entries = feedback[task.id].orEmpty()
                        if (entries.size > MAX_FEEDBACK_PER_TASK) {
                            throw IOException("Feedback count exceeds the storage limit")
                        }
                        output.writeInt(entries.size)
                        entries.forEach { entry ->
                            output.writeString(entry.id)
                            output.writeString(entry.text)
                            output.writeLong(entry.timestamp)
                        }
                    }
                    output.flush()
                    stream.fd.sync()
                }
            }
            if (temporary.length() > MAX_STORE_BYTES) throw IOException("Task queue exceeds the storage limit")
            if (file.isFile) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            replace(temporary, file)
        } catch (e: Exception) {
            temporary.delete()
            throw IOException("Unable to persist task queue", e)
        }
    }

    private fun read(source: File): Snapshot =
        DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            if (source.length() > MAX_STORE_BYTES) throw IOException("Task store exceeds the storage limit")
            if (input.readInt() != MAGIC) throw IOException("Invalid task store signature")
            if (input.readInt() != VERSION) throw IOException("Unsupported task store version")
            val count = input.readInt().checkedCount(MAX_TASKS, "task")
            val tasks = ArrayList<TaskManager.AgentTask>(count)
            val feedback = LinkedHashMap<String, List<TaskManager.FeedbackEntry>>()
            repeat(count) {
                val id = input.readString()
                val task = TaskManager.AgentTask(
                    id = id,
                    title = input.readString(),
                    description = input.readString(),
                    priority = TaskManager.Priority.valueOf(input.readString()),
                    status = TaskManager.Status.valueOf(input.readString()),
                    createdAt = input.readLong(),
                    startedAt = input.readNullableLong(),
                    completedAt = input.readNullableLong(),
                    result = input.readNullableString(),
                    error = input.readNullableString(),
                )
                val feedbackCount = input.readInt().checkedCount(MAX_FEEDBACK_PER_TASK, "feedback")
                tasks += task
                feedback[id] = List(feedbackCount) {
                    TaskManager.FeedbackEntry(
                        id = input.readString(),
                        taskId = id,
                        text = input.readString(),
                        timestamp = input.readLong(),
                    )
                }
            }
            if (input.read() != -1) throw IOException("Unexpected trailing task store data")
            Snapshot(tasks, feedback)
        }

    private fun replace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restoreBackup(backup: File) {
        val restored = File(file.parentFile, "${file.name}.restore.tmp")
        Files.copy(backup.toPath(), restored.toPath(), StandardCopyOption.REPLACE_EXISTING)
        replace(restored, file)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) throw IOException("Task store string exceeds the size limit")
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt().checkedCount(MAX_STRING_BYTES, "string byte")
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private fun Int.checkedCount(maximum: Int, label: String): Int {
        if (this !in 0..maximum) throw IOException("Invalid $label count or size: $this")
        return this
    }

    private companion object {
        const val MAGIC = 0x44534854
        const val VERSION = 1
        const val MAX_TASKS = 5_000
        const val MAX_FEEDBACK_PER_TASK = 100
        const val MAX_STRING_BYTES = 1 shl 20
        const val MAX_STORE_BYTES = 128L shl 20
    }
}

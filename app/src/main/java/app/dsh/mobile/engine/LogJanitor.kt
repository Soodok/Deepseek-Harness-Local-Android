package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 日志卫生（v1.4.57，用户反馈「日志累积到 96 万行、不会定时清除」）。
 *
 * ## 现状与为什么仍需这个类
 *
 * 主日志 `engine/engine.log` 与 `engine/boot.log` 各自有环形上限
 * （[EngineLog.MAX_BYTES] = 2MB / BootTrace 128KB），**写入时**超限会截断。
 * 但有三类残留它们管不到：
 *
 * 1. **plugin-manager 每次操作建一个目录**
 *    `dsh-home/profiles/<p>/.plugin-manager/logs/operation-XXXXXX/pnpm.log`
 *    由引擎侧的 dsh-plugin-manager 生成，卸载插件也不会删。用户装几十次
 *    插件就是几十个目录；pnpm 的安装输出很容易单个几万行。
 * 2. **只读不写时永不收缩**：环形截断发生在 append 路径上。引擎起不来
 *    （退避重试）时日志只被 Health 探针追加少量内容，2MB 的旧内容会
 *    原样留着；用户看到的「96 万行」大概率来自这里或第 1 类。
 * 3. **其他引擎自建日志**：上游或第三方插件可能往 dsh-home 下写自己的
 *    log 文件，路径不可枚举。
 *
 * 本类在**每次引擎启动前**做一次卫生：把超过上限的日志按尾部裁剪到一半，
 * 并清理过期的 plugin-manager 操作日志目录。只删日志，不碰任何用户数据。
 *
 * ## 边界
 * - 只处理**文件名以 .log 结尾**的文件，以及 `.plugin-manager/logs/` 下的
 *   整个 operation-* 目录；
 * - 单文件上限 [MAX_LOG_BYTES]（4MB，比 engine.log 的 2MB 宽松，因为这里
 *   处理的是「已经超限的历史遗留」）；
 * - 单个文件裁剪保留尾部一半，与 [EngineLog] 策略一致（保留最近的内容更有用）；
 * - 失败静默：日志卫生不该阻止引擎启动。
 */
object LogJanitor {

    private const val TAG = "LogJanitor"

    /** 单文件上限：超过就裁到一半。 */
    const val MAX_LOG_BYTES: Long = 4L shl 20

    /** plugin-manager 操作日志保留的份数（按修改时间保留最新的 N 个）。 */
    private const val KEEP_OPERATION_DIRS = 3

    data class Result(
        val trimmedFiles: Int,
        val freedBytes: Long,
        val removedOpDirs: Int,
    )

    /** 在引擎启动前调用；耗时通常在毫秒级（只 stat + 少量读写）。 */
    fun sweep(ctx: Context): Result {
        val home = runCatching { EngineConfig.dshHome(ctx) }.getOrNull()
            ?: return Result(0, 0, 0)
        var trimmed = 0
        var freed = 0L
        var removedDirs = 0

        // ① 所有 *.log 文件：超限则裁到尾部一半
        runCatching {
            walkLogFiles(home) { f ->
                val len = runCatching { f.length() }.getOrDefault(0L)
                if (len > MAX_LOG_BYTES) {
                    val keep = MAX_LOG_BYTES / 2
                    val saved = trimToTail(f, keep)
                    if (saved > 0) {
                        trimmed++
                        freed += saved
                        Log.i(TAG, "trimmed ${f.name}: ${len} -> ${len - saved} bytes")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "sweep logs failed: ${it.message}") }

        // ② plugin-manager 操作日志：按修改时间只留最新 N 个
        runCatching {
            removedDirs = pruneOperationLogs(home)
        }.onFailure { Log.w(TAG, "prune operation logs failed: ${it.message}") }

        if (trimmed > 0 || removedDirs > 0) {
            Log.i(TAG, "log sweep: trimmed=$trimmed freed=${freed / 1024}KB opDirs=$removedDirs")
        }
        return Result(trimmed, freed, removedDirs)
    }

    /**
     * 遍历 .log 文件。**不跟随符号链接**（sessions/workspaces 可能外置）。
     * 深度限制 8 层 —— 正常日志都在 profiles/<name>/.plugin-manager/logs/x/y.log
     * 这个深度内，更深的结构不是日志。
     */
    private fun walkLogFiles(root: File, depth: Int = 0, visit: (File) -> Unit) {
        if (depth > 8) return
        val children = runCatching { root.listFiles() }.getOrNull() ?: return
        for (f in children) {
            val isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }
                .getOrDefault(false)
            if (isLink) continue
            if (f.isDirectory) {
                walkLogFiles(f, depth + 1, visit)
            } else if (f.name.endsWith(".log", ignoreCase = true)) {
                runCatching { visit(f) }
            }
        }
    }

    /**
     * 把文件裁到只保留尾部 [keep] 字节，返回释放的字节数。
     * 与 [EngineLog] 一样跳过 UTF-8 续字节，避免行首出现替换符。
     */
    private fun trimToTail(f: File, keep: Long): Long {
        return runCatching {
            val cur = f.length()
            if (cur <= keep) return@runCatching 0L
            val start = cur - keep
            var tail = java.io.RandomAccessFile(f, "rw").use { raf ->
                raf.seek(start)
                val buf = ByteArray((cur - start).toInt())
                raf.readFully(buf)
                buf
            }
            var i = 0
            while (i < tail.size && (tail[i].toInt() and 0xC0) == 0x80) i++
            if (i > 0) tail = tail.copyOfRange(i, tail.size)
            // 标记一行，让用户知道这里被裁剪过（否则日志开头看起来像丢了内容）
            val marker = "...[earlier log trimmed by LogJanitor]...${System.lineSeparator()}".toByteArray()
            f.writeBytes(marker + tail)
            cur - (marker.size + tail.size)
        }.getOrDefault(0L)
    }

    /**
     * 清理 plugin-manager 的操作日志目录，只保留最新的 [KEEP_OPERATION_DIRS] 个。
     *
     * 路径形如 `<profile>/.plugin-manager/logs/operation-XXXXXX/`。
     * 这些目录是 pnpm 安装过程的产物，事后只剩排障价值；保留最近几次足够。
     */
    private fun pruneOperationLogs(home: File): Int {
        var removed = 0
        val logsDirs = mutableListOf<File>()
        runCatching {
            findDirsNamed(home, ".plugin-manager", 0) { pm ->
                val logs = File(pm, "logs")
                if (logs.isDirectory) logsDirs += logs
            }
        }
        for (logs in logsDirs) {
            val ops = runCatching { logs.listFiles() }.getOrNull()?.filter {
                it.isDirectory && it.name.startsWith("operation-")
            }?.sortedByDescending { it.lastModified() } ?: continue
            ops.drop(KEEP_OPERATION_DIRS).forEach { old ->
                if (deleteNoFollow(old)) removed++
            }
        }
        return removed
    }

    private fun findDirsNamed(root: File, name: String, depth: Int, visit: (File) -> Unit) {
        if (depth > 6) return
        val children = runCatching { root.listFiles() }.getOrNull() ?: return
        for (f in children) {
            val isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }
                .getOrDefault(false)
            if (isLink || !f.isDirectory) continue
            if (f.name == name) visit(f) else findDirsNamed(f, name, depth + 1, visit)
        }
    }

    private fun deleteNoFollow(f: File): Boolean = runCatching {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
            f.delete()
        } else if (f.isDirectory) {
            var ok = true
            f.listFiles()?.forEach { if (!deleteNoFollow(it)) ok = false }
            if (ok) f.delete() else false
        } else {
            f.delete()
        }
    }.getOrDefault(false)
}

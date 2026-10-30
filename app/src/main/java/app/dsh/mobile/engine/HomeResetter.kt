package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * dsh-home 的强制清理与重置（v1.4.56）。
 *
 * ## 为什么需要（用户反馈）
 * 「全面体检与修复基本没啥用，有些人遇到一些问题还是修不好」。自检只覆盖
 * 可诊断的损坏（属主、符号链接、注册表路径、锁目录）。当 dsh-home 里出现
 * 自检不认识的状态（插件写了畸形配置、profile 元数据损坏、某个 JSON 半截
 * 写入……）时，自检报「一切正常」而引擎依旧起不来 —— 用户只能卸载重装，
 * 而 noruntime 包重装要重新下载 130MB 运行时，代价过高。
 *
 * 本类提供**不重装 App、只重建 dsh-home** 的逃生通道。
 *
 * ## 两级语义
 *
 * Mode.DEEP：删 .cache / .local / .dsh / 插件残留 / 健康标记 / 日志，
 *            保留凭证、会话、工作区、profile、SSH 密钥。
 * Mode.RESET：删整个 dsh-home 全部内容，随后重建骨架。
 *
 * ## 明确不做的事
 * - 不动 $F/engine：运行时由 RuntimeInstaller 管理，删它等于重下 130MB。
 * - 不跟随符号链接：sessions / workspaces 可能外置成软链指向公共目录，
 *   deleteRecursively() 会把外部真实数据删掉。这里用 lstat 语义先 unlink 链接。
 * - 不重启引擎：由调用方决定（用户可能想先看备份）。
 */
object HomeResetter {

    private const val TAG = "HomeResetter"

    enum class Mode { DEEP, RESET }

    data class Result(
        val ok: Boolean,
        val mode: Mode,
        val removedBytes: Long,
        val removedEntries: Int,
        val failures: List<String>,
        val preserved: List<String>,
        val error: String? = null,
    )

    /**
     * DEEP 模式白名单：保留的顶层条目。
     *
     * 用白名单而非黑名单，是为了让「将来引擎新增某个用户数据目录」默认被保护 ——
     * 漏删（可再点 RESET）远好于误删（API key / 会话 / SSH 私钥不可恢复）。
     */
    private val DEEP_PRESERVE = listOf(
        ".credentials.yaml",
        "sessions",
        "workspaces",
        "profiles",
        ".ssh",
        "AGENTS.md",
        "android.jar",
        "storages",
        "tools",
        ".anonymous-user-id",
    )

    data class Usage(
        val totalBytes: Long,
        val cacheBytes: Long,
        val dataBytes: Long,
        val entries: Int,
    )

    private const val MAX_SCAN_ENTRIES = 200_000

    fun usage(ctx: Context): Usage {
        val home = EngineConfig.dshHome(ctx)
        var cache = 0L
        var data = 0L
        var entries = 0
        for (f in home.listFiles().orEmpty()) {
            entries++
            val size = treeSize(f)
            if (f.name in DEEP_PRESERVE) data += size else cache += size
        }
        return Usage(cache + data, cache, data, entries)
    }

    /**
     * 递归体积，带条目上限。
     *
     * 实测 dsh-home/.cache 只有 456 个文件（pnpm store 在 .local 里），全量遍历
     * 是毫秒级。之前的 ANR 根因是「在 Activity.onCreate 里同步做 I/O」，而不是
     * 统计本身太慢 —— 现已整体移到后台线程（见 MaintenanceActivity.refreshHomeUsage）。
     * 上限只是防御：万一某目录膨胀到十万级，宁可少算也不让 UI 卡住。
     */
    private fun treeSize(root: File): Long {
        var scanned = 0
        fun walk(file: File, depth: Int): Long {
            if (++scanned > MAX_SCAN_ENTRIES) return 0L
            return try {
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) return 0L
                if (file.isFile) return file.length()
                if (!file.isDirectory) return 0L
                if (depth > 24) return 0L
                var sum = 0L
                for (c in file.listFiles().orEmpty()) sum += walk(c, depth + 1)
                sum
            } catch (e: Exception) {
                0L
            }
        }
        return walk(root, 0)
    }

    /**
     * 把需要备份的用户数据打包到给定输出流。
     *
     * 只备份 DEEP_PRESERVE 里的条目 —— 缓存没有备份价值，且会让 zip 膨胀十倍。
     * RESET 模式下这份备份是用户唯一的救命稻草，所以：符号链接跟随内容
     * （sessions 外置时用户要的是内容）；隐藏文件必须包含。
     *
     * @return 实际写入的字节数
     */
    fun backupTo(ctx: Context, out: java.io.OutputStream, onProgress: ((Long) -> Unit)? = null): Long {
        val home = EngineConfig.dshHome(ctx)
        var written = 0L
        ZipOutputStream(java.io.BufferedOutputStream(out)).use { zos ->
            for (name in DEEP_PRESERVE) {
                val f = File(home, name)
                if (!f.exists()) continue
                written += zipTree(zos, f, name, onProgress)
            }
            zos.finish()
        }
        return written
    }

    private fun zipTree(
        zos: ZipOutputStream,
        file: File,
        pathInZip: String,
        onProgress: ((Long) -> Unit)?,
    ): Long {
        // 符号链接：跟随真实内容（用户要的是数据；链接本身恢复时无意义）
        val real = runCatching { file.canonicalFile }.getOrDefault(file)
        if (real.isDirectory) {
            var sum = 0L
            runCatching {
                zos.putNextEntry(ZipEntry("$pathInZip/"))
                zos.closeEntry()
            }
            for (child in real.listFiles().orEmpty()) {
                sum += zipTree(zos, child, "$pathInZip/${child.name}", onProgress)
            }
            return sum
        }
        if (!real.isFile) return 0
        var sum = 0L
        runCatching {
            zos.putNextEntry(ZipEntry(pathInZip))
            real.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    zos.write(buf, 0, n)
                    sum += n
                    onProgress?.invoke(sum)
                }
            }
            zos.closeEntry()
        }.onFailure { Log.w(TAG, "backup $pathInZip failed: ${it.message}") }
        return sum
    }

    /** 执行清理。必须在引擎停止后调用。 */
    fun run(ctx: Context, mode: Mode): Result {
        val home = EngineConfig.dshHome(ctx)
        if (!home.isDirectory) {
            return Result(true, mode, 0, 0, emptyList(), emptyList())
        }
        var bytes = 0L
        var count = 0
        val failures = mutableListOf<String>()
        val preserved = mutableListOf<String>()

        val targets: List<File> = when (mode) {
            Mode.DEEP -> home.listFiles().orEmpty().filter { it.name !in DEEP_PRESERVE }
            Mode.RESET -> home.listFiles().orEmpty().toList()
        }

        if (mode == Mode.DEEP) {
            home.listFiles().orEmpty().filter { it.name in DEEP_PRESERVE }
                .forEach { preserved += it.name }
        }

        for (f in targets) {
            val size = treeSize(f)
            if (deleteNoFollow(f)) {
                bytes += size
                count++
            } else {
                failures += f.name
            }
        }

        if (mode == Mode.RESET) {
            runCatching { home.mkdirs() }
                .onFailure { failures += "mkdir:${it.message}" }
        }

        Log.i(TAG, "reset done mode=$mode entries=$count bytes=$bytes failures=${failures.size}")
        return Result(
            ok = failures.isEmpty(),
            mode = mode,
            removedBytes = bytes,
            removedEntries = count,
            failures = failures,
            preserved = preserved,
        )
    }

    /**
     * 安全递归删除：不跟随符号链接。
     *
     * 不能用 File.deleteRecursively()：它按 isDirectory() 决定是否进入子目录，
     * 而 isDirectory() 跟随软链 —— dsh-home/sessions 若是指向公共目录的软链，
     * 删「目录」会把公共目录里的真实会话逐个删掉。
     */
    private fun deleteNoFollow(f: File): Boolean = try {
        val isLink = java.nio.file.Files.isSymbolicLink(f.toPath())
        if (isLink) {
            f.delete()
        } else if (f.isDirectory) {
            val children = f.listFiles()
            var allOk = true
            children?.forEach { if (!deleteNoFollow(it)) allOk = false }
            if (allOk) f.delete() else false
        } else {
            f.delete()
        }
    } catch (e: Exception) {
        Log.w(TAG, "delete ${f.name} failed: ${e.message}")
        false
    }
}

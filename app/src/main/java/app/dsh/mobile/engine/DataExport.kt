package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 数据导出（issue #31 第一步：只导出，不导入）。
 *
 * ## 包里有什么
 *   manifest.json                  schemaVersion / 版本号 / 导出时间 / 各目录文件数
 *   sessions/...                   对话（dsh-home/sessions，通常是指向公开目录的软链）
 *   workspaces/...                 工作区文件
 *   storages/workspace.json        工作区注册表 —— 漏了它，恢复后会话文件在、列表里没有
 *
 * ## 刻意不包含
 *   · profiles/（API key、登录态）：zip 会被随手转发，凭据不能默认进包；
 *   · engine/extensions/：体积大、可重装；
 *   · node_modules/：可由包管理器重建，且动辄数万文件；
 *   · 软链：只记录跳过数量，绝不跟随（防把链指向的外部内容打进包）。
 *
 * ## 失败策略
 * 任何一个文件读不出来就整体失败并抛出，由调用方删掉半成品 —— 宁可让用户看到
 * 「导出失败：<路径>」，也不能给出一个「看起来成功、其实缺东西」的备份。
 * 每一步都写进 engine.log（前缀 `[export]`），用户反馈时导出日志即可定位。
 *
 * 只读操作：不停引擎、不改任何文件。引擎运行中追加写入的会话，导出的是当时的快照。
 */
object DataExport {

    const val SCHEMA_VERSION = 1

    private const val TAG = "DataExport"
    private const val REGISTRY = "storages/workspace.json"
    private val DIRS = listOf("sessions", "workspaces")

    data class Summary(
        val sessionFiles: Int,
        val workspaceFiles: Int,
        val hasRegistry: Boolean,
        val skippedLinks: Int,
        val bytes: Long,
    )

    class ExportException(message: String) : IOException(message)

    /**
     * 把用户数据写成 zip 到 [out]（调用方负责打开与关闭 [out]）。
     * 同步执行，调用方放到后台线程。
     *
     * @param onProgress 已写入文件数 / 文件总数；每个文件回调一次，UI 侧自行节流
     */
    fun export(
        ctx: Context,
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Summary {
        val home = EngineConfig.dshHome(ctx)
        log(ctx, "start: home=${home.absolutePath}")
        try {
            // 1) 先列清单：总数驱动进度，计数写进 manifest。目录项以 "/" 结尾（保留空目录）。
            val entries = mutableListOf<Pair<String, File>>()
            val counts = mutableMapOf<String, Int>()
            var skippedLinks = 0
            for (dir in DIRS) {
                val root = File(home, dir)
                // walkNoFollow 对列不出内容的目录是静默跳过的；导出不能接受「看似成功、其实缺数据」，
                // 所以悬空软链 / 读不了的目录在这里显式报错。
                if (EngineConfig.isSymbolicLink(root) && !root.exists()) {
                    throw ExportException("$dir/ points to a missing location")
                }
                if (root.exists() && root.list() == null) throw ExportException("cannot list $dir/")
                var n = 0
                val complete = EngineConfig.walkNoFollow(
                    root,
                    skipDir = { it.name == "node_modules" },
                ) { f, link ->
                    val rel = "$dir/" + f.relativeTo(root).invariantSeparatorsPath
                    when {
                        link -> skippedLinks++
                        f.isDirectory -> {
                            if (f.list() == null) throw ExportException("cannot list $rel/")
                            entries += "$rel/" to f
                        }
                        f.isFile -> { entries += rel to f; n++ }
                    }
                }
                if (!complete) throw ExportException("too many entries under $dir/")
                counts[dir] = n
                log(
                    ctx,
                    "scan $dir: $n file(s) (path=${root.absolutePath}, " +
                        "symlink=${EngineConfig.isSymbolicLink(root)})",
                )
            }
            val registry = File(home, REGISTRY).takeIf { it.isFile }
            if (registry != null) {
                entries += REGISTRY to registry
            } else {
                log(ctx, "warning: $REGISTRY not found; exporting without the workspace registry")
            }
            val total = entries.count { !it.first.endsWith("/") }

            val manifest = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("appVersion", appVersion(ctx))
                .put("engineVersion", engineVersion(ctx))
                .put("exportedAt", java.time.Instant.now().toString())
                .put(
                    "counts",
                    JSONObject()
                        .put("sessions", counts["sessions"] ?: 0)
                        .put("workspaces", counts["workspaces"] ?: 0)
                        .put("workspaceRegistry", if (registry != null) 1 else 0),
                )
                .put("skippedSymlinks", skippedLinks)
                .put(
                    "excluded",
                    JSONArray(listOf("profiles/", "engine/extensions/", "node_modules/", "symlinks")),
                )

            // 2) 写包。会话文件本身已是 zstd 压缩，用最快档位即可。
            var done = 0
            var bytes = 0L
            val zip = ZipOutputStream(out.buffered())
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            onProgress(0, total)
            for ((name, f) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                if (!name.endsWith("/")) {
                    try {
                        f.inputStream().use { bytes += it.copyTo(zip) }
                    } catch (e: IOException) {
                        throw ExportException("cannot read $name: ${e.message}")
                    }
                    onProgress(++done, total)
                }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()

            val summary = Summary(
                sessionFiles = counts["sessions"] ?: 0,
                workspaceFiles = counts["workspaces"] ?: 0,
                hasRegistry = registry != null,
                skippedLinks = skippedLinks,
                bytes = bytes,
            )
            log(ctx, "done: $summary")
            return summary
        } catch (e: Throwable) {
            log(ctx, "FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun appVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** 上游 dsh 包版本（如 0.2.1-rc.2）；运行时未安装时为 "?"。 */
    private fun engineVersion(ctx: Context): String = runCatching {
        val pkg = File(EngineConfig.engineRoot(ctx), "lib/node_modules/@deepseek-ai/dsh/package.json")
        JSONObject(pkg.readText()).getString("version")
    }.getOrNull() ?: "?"

    private fun log(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        EngineLog.append(File(EngineConfig.engineRoot(ctx), "engine.log"), "[export] $msg\n")
    }

    // ══════════════════════════════════════════════════════════════════════
    // 导入（v1.4.29，issue #31 第二步）
    // ══════════════════════════════════════════════════════════════════════

    /** 导入结果：写入了多少文件、跳过了多少同名文件、忽略了哪些条目 */
    data class ImportSummary(
        val sessionFiles: Int,
        val workspaceFiles: Int,
        val hasRegistry: Boolean,
        val overwritten: Int,
        val skipped: List<String>,
    )

    class ImportException(message: String) : IOException(message)

    /**
     * 从 zip 恢复用户数据（**合并式**，不是覆盖式）。
     *
     * ## 为什么是合并而不是替换
     *
     * 用户要的是「换手机后把旧数据带过来」。如果整目录替换，备份之后新产生的
     * 会话就没了 —— 那比不恢复更糟。所以：
     *  · 目标已有同名文件 → **跳过**（保留现有），计数报给 UI；
     *  · 只写入目标不存在的文件。
     *
     * 例外是注册表 `storages/workspace.json`：必须**合并**而不是跳过，否则恢复出来的
     * 会话文件在磁盘上、但列表里看不见（v1.4.6 踩过：用户看到「已恢复 N 个」，
     * 重启后一个都没有）。按 workspaceIds 取并集处理。
     *
     * ## 安全
     *
     *  · **zip-slip 防护**：条目解析后必须落在 dshHome 之内；绝对路径、`..`、
     *    Windows 盘符一律拒绝；
     *  · **路径白名单**：只接受 `sessions/`、`workspaces/`、`storages/workspace.json`。
     *    其余（尤其 `profiles/`、`engine/`）即使出现在包里也跳过 —— 备份包里本不该有，
     *    出现了说明是构造的包，不能让它写进敏感位置；
     *  · **先全量校验再写入**：条目数 / 单条目 / 总量都有上限，超限直接失败，
     *    不会先写一半再报错。
     *
     * 同步执行，调用方放后台线程。
     */
    fun import(
        ctx: Context,
        zip: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportSummary {
        val home = EngineConfig.dshHome(ctx).canonicalFile
        log(ctx, "import start: zip=${zip.absolutePath} home=${home.absolutePath}")
        requireZipContainer(zip)

        val allowedRoots = listOf("sessions/", "workspaces/").map { home.resolve(it).canonicalPath }
        val registryTarget = home.resolve(REGISTRY).canonicalPath

        var sessionFiles = 0
        var workspaceFiles = 0
        var overwritten = 0
        var hasRegistry = false
        val skipped = mutableListOf<String>()

        try {
            java.util.zip.ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                if (entries.size > MAX_ENTRIES) {
                    throw ImportException("archive has ${entries.size} entries (limit $MAX_ENTRIES)")
                }

                // ① 全量校验（不写任何东西）
                var totalUnpacked = 0L
                for (e in entries) {
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    val dest = resolveInside(home, name)
                    if (dest == null) { skipped += name; continue }
                    if (e.size > MAX_ENTRY_BYTES) {
                        throw ImportException("entry too large: $name (${e.size} bytes)")
                    }
                    totalUnpacked += e.size
                    if (totalUnpacked > MAX_TOTAL_BYTES) {
                        throw ImportException(
                            "archive too large when unpacked (limit ${MAX_TOTAL_BYTES / 1048576}MB)",
                        )
                    }
                    val path = dest.canonicalPath
                    if (path == registryTarget) { hasRegistry = true; continue }
                    if (allowedRoots.none { path.startsWith(it + File.separator) }) {
                        skipped += name   // profiles/ engine/ 等：包里不该有
                    }
                }
                log(ctx, "import validated: ${entries.size} entries, ${skipped.size} skipped, registry=$hasRegistry")

                // ② 写入（合并式：不覆盖已有文件）
                val files = entries.filter { !it.isDirectory }
                var done = 0
                for (e in files) {
                    done++
                    onProgress(done, files.size)
                    val name = e.name.replace('\\', '/')
                    val dest = resolveInside(home, name) ?: continue
                    val path = dest.canonicalPath
                    if (path == registryTarget) continue           // 注册表最后单独合并
                    if (allowedRoots.none { path.startsWith(it + File.separator) }) continue
                    if (dest.exists()) { overwritten++; continue }
                    dest.parentFile?.mkdirs()
                    zf.getInputStream(e).use { ins ->
                        dest.outputStream().use { outs -> ins.copyTo(outs, 64 * 1024) }
                    }
                    when {
                        name.startsWith("sessions/") -> sessionFiles++
                        name.startsWith("workspaces/") -> workspaceFiles++
                    }
                }

                // ③ 注册表并集合并
                if (hasRegistry) {
                    files.firstOrNull { it.name.replace('\\', '/') == REGISTRY }?.let { entry ->
                        mergeRegistry(ctx, registryTarget, zf.getInputStream(entry).readBytes())
                    }
                }
            }
            val sum = ImportSummary(sessionFiles, workspaceFiles, hasRegistry, overwritten, skipped)
            log(
                ctx,
                "import done: sessions=$sessionFiles workspaces=$workspaceFiles " +
                    "overwritten=$overwritten skipped=${skipped.size}",
            )
            return sum
        } catch (e: Exception) {
            log(ctx, "import FAILED: ${e.javaClass.simpleName}: ${e.message}")
            // 加密 zip：JDK 只会抛 "encrypted ZIP entry not supported"，
            // 换成稳定原因码，UI 侧好映射成本地化文案。
            if (e.message.orEmpty().contains("encrypted", ignoreCase = true)) {
                throw ImportException("encrypted_zip")
            }
            throw e
        }
    }

    /**
     * 备份包必须是 zip 容器。先验 PK 签名：用户很容易选到 .rar / .7z / 图片，
     * 直接丢给 ZipFile 只会抛 "zip END header not found" 这类底层错误。
     */
    private fun requireZipContainer(zip: File) {
        val head = ByteArray(4)
        val n = runCatching { zip.inputStream().use { it.read(head) } }.getOrDefault(0)
        val ok = n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            ((head[2] == 3.toByte() && head[3] == 4.toByte()) ||
                (head[2] == 5.toByte() && head[3] == 6.toByte()) ||
                (head[2] == 7.toByte() && head[3] == 8.toByte()))
        if (!ok) throw ImportException("not_a_zip")
    }

    /**
     * 注册表按 **workspaceIds 并集** 合并：保留现有的、追加备份里新的；
     * workspaces 表里已存在的条目以当前设备为准（不覆盖）。
     */
    private fun mergeRegistry(ctx: Context, target: String, incomingBytes: ByteArray) {
        val targetFile = File(target)
        val incoming = runCatching { JSONObject(incomingBytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: run { log(ctx, "registry: backup copy unparseable, skipped"); return }
        val current = runCatching {
            if (targetFile.isFile) JSONObject(targetFile.readText()) else JSONObject()
        }.getOrElse {
            log(ctx, "registry: local copy unparseable, using backup copy")
            JSONObject()
        }

        fun idsOf(o: JSONObject?): MutableList<String> {
            val arr = o?.optJSONArray("workspaceIds") ?: return mutableListOf()
            return (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                .toMutableList()
        }

        val g = current.optJSONObject("global") ?: JSONObject()
        val mergedIds = idsOf(g).also { list ->
            idsOf(incoming.optJSONObject("global")).forEach { if (it !in list) list.add(it) }
        }
        g.put("workspaceIds", JSONArray(mergedIds))
        current.put("global", g)

        val tables = current.optJSONObject("tables") ?: JSONObject()
        incoming.optJSONObject("tables")?.optJSONObject("workspaces")?.let { inc ->
            val local = tables.optJSONObject("workspaces") ?: JSONObject()
            inc.keys().forEach { k -> if (!local.has(k)) local.put(k, inc.get(k)) }
            tables.put("workspaces", local)
        }
        current.put("tables", tables)

        targetFile.parentFile?.mkdirs()
        targetFile.writeText(current.toString())
        log(ctx, "registry merged: ${mergedIds.size} workspace id(s)")
    }

    /**
     * zip 条目名 → dshHome 内的安全路径。
     * @return null = 不安全（绝对路径 / `..` / 逃出 home / 盘符 / NUL）
     */
    private fun resolveInside(home: File, name: String): File? {
        // 绝对路径直接拒绝（v1.4.29 测试发现）：旧实现用 trimStart('/') 会把
        // "/etc/passwd" 归一成 "etc/passwd" 而接受 —— 结果虽仍落在 home 内（无害），
        // 但语义上这是「构造的包」，不该被当成正常条目。
        if (name.startsWith("/") || name.startsWith("\\")) return null
        val n = name.trimStart('/')
        if (n.isEmpty() || n.contains('\u0000')) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        if (n.split('/').any { it == ".." }) return null
        val dest = File(home, n)
        val canonical = runCatching { dest.canonicalPath }.getOrNull() ?: return null
        return if (canonical.startsWith(home.canonicalPath + File.separator)) dest else null
    }

    private const val MAX_ENTRIES = 200_000
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
}

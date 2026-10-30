package app.dsh.mobile.engine

import app.dsh.mobile.R

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** 换装日志相位（v1.4.26）：意图已落盘 → 条目已全部换完。 */
private const val PHASE_SWAP = "swap"
private const val PHASE_COMMITTED = "committed"

/**
 * Node 运行时安装器。
 *
 * 两种包形态（由构建期 MANIFEST 的 `package` 决定）：
 *  - `full`：assets/runtime.zip 随 APK 分发，离线安装；
 *  - `noruntime`：APK 不含运行时，按 MANIFEST 的 `runtime.urls` 下载（强制 SHA-256）。
 *
 * 版本语义（v1.4.20）：
 *  - full 包：包内 runtime 版本比已安装的新 → 本地重装（不下载）；
 *  - noruntime 包：**已装好就复用**，绝不因 App 升级重下运行时；更新走显式云更新。
 *
 * 安装 = 解压到 filesDir/engine + 恢复可执行位。
 * zip 不保存 Unix 权限，因此解压后对 bin/ 下所有文件 chmod 755；
 * targetSdk 28 下 SELinux 允许对 filesDir 内文件 execve，无需 jniLibs 伪装。
 */
class RuntimeInstaller(private val ctx: Context) {

    data class RuntimeInfo(
        val version: String,
        val builtAt: Long,
        val bundled: Boolean,
        val url: String?,
        val urls: List<String>,
        val sha256: String?,
        val size: Long,
        val unpackedBytes: Long,
    )

    data class Manifest(
        val packageName: String,
        val abi: String,
        val runtime: RuntimeInfo,
        val indexUrl: String?,
        val proxies: List<String>,
    )

    private data class Installed(
        val version: String,
        val builtAt: Long,
        val source: String,
    )

    private val root get() = EngineConfig.engineRoot(ctx)
    private val stampFile get() = File(root, ".runtime-version")
    private val metaFile get() = File(root, ".runtime.json")

    fun readManifest(): Manifest {
        val raw = ctx.assets.open("runtime/MANIFEST.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(raw)
        val rt = obj.optJSONObject("runtime")
        val legacy = rt == null
        val urls = mutableListOf<String>()
        if (rt != null) {
            rt.optJSONArray("urls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i)
                    if (u.isNotBlank() && u !in urls) urls.add(u)
                }
            }
            rt.optString("url").takeIf { it.isNotBlank() }?.let { if (it !in urls) urls.add(0, it) }
        } else {
            obj.optString("url").takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        val runtime = RuntimeInfo(
            version = if (legacy) obj.optString("version", "unknown") else rt!!.optString("version", "unknown"),
            builtAt = if (legacy) 0L else rt!!.optLong("builtAt", 0L),
            bundled = if (legacy) hasAsset("runtime.zip") else rt!!.optBoolean("bundled", false),
            url = if (legacy) obj.optString("url").takeIf { it.isNotBlank() } else rt!!.optString("url").takeIf { it.isNotBlank() },
            urls = urls,
            sha256 = (if (legacy) obj.optString("sha256") else rt!!.optString("sha256")).takeIf { it.isNotBlank() },
            size = if (legacy) 0L else rt!!.optLong("size", 0L),
            unpackedBytes = if (legacy) 0L else rt!!.optLong("unpackedBytes", 0L),
        )
        val packageName = obj.optString("package").ifBlank { if (runtime.bundled) "full" else "noruntime" }
        val updates = obj.optJSONObject("updates")
        val indexUrl = updates?.optString("indexUrl")?.takeIf { it.isNotBlank() }
        val proxies = mutableListOf<String>()
        updates?.optJSONArray("proxies")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optString(i)
                if (p.isNotBlank()) proxies.add(p)
            }
        }
        return Manifest(packageName, obj.optString("abi"), runtime, indexUrl, proxies)
    }

    /** 确保运行时就绪；已安装且关键资产完整时按包形态决定是否复用。
     *  @param onProgress 解压进度 0f..1f（仅解压阶段有值；频繁调用方自行节流）
     *  @param onDetail 解压的文件数/字节数明细（v1.4.8：启动遮罩显示"已加载 N/M 个文件"）
     *
     *  @return true = 复用了已安装的运行时（本次没有解压）；false = 执行了解压/下载 */
    fun ensureInstalled(
        onProgress: (Float) -> Unit = {},
        onDetail: (BootTrace.Install) -> Unit = {},
    ): Boolean = synchronized(INSTALL_LOCK) {
        ensureInstalledLocked(onProgress, onDetail)
    }

    private fun ensureInstalledLocked(
        onProgress: (Float) -> Unit,
        onDetail: (BootTrace.Install) -> Unit,
    ): Boolean {
        // ① 先收拾「上次换装被杀在半路」的现场（v1.4.26）。
        //    必须早于完整性判断：混合态可能通过 `isRootCompleteAt()`，
        //    看着完整但 node 与 .so 错配，那种运行时不能拿去启动。
        recoverInterruptedSwap()
        // ② 再处理「换装成功但引擎始终没进 Healthy」——它依赖 engine.old，
        //    与 ① 是两个独立窗口，判断依据也不同（那里是启动成功与否）。
        recoverPendingHealth()
        // ③ 最后清掉上一次中断留下的临时目录（staging / zip 缓存）。
        cleanupInstallResidue()
        val manifest = readManifest()
        val complete = EngineConfig.nodeBin(ctx).exists() && isRootComplete()
        if (complete) {
            val installed = readInstalled()
            val reuse = when {
                // 简单包：只要运行时完整就复用；运行时更新只能显式触发。
                manifest.packageName == "noruntime" -> true
                // 完整包：包内版本更新时本地重装（无下载）。
                !manifest.runtime.bundled -> true
                // 已通过云端更新到比包内更新的运行时：不要被旧 APK 降级。
                installed != null && installed.builtAt > 0 &&
                    manifest.runtime.builtAt > 0 &&
                    installed.builtAt > manifest.runtime.builtAt -> true
                manifest.runtime.version.isBlank() || manifest.runtime.version == "unknown" -> true
                installed != null -> installed.version == manifest.runtime.version
                else -> runCatching { stampFile.readText().trim() == manifest.runtime.version }
                    .getOrDefault(false)
            }
            if (reuse) {
                if (installed == null) writeStamp(manifest.runtime, bundled = manifest.runtime.bundled)
                Log.i(TAG, "runtime ${manifest.runtime.version} already installed (${manifest.packageName})")
                return true
            }
            Log.i(
                TAG,
                "runtime upgrade in package: installed=${installed?.version ?: "?"} " +
                    "bundled=${manifest.runtime.version}; reinstalling locally",
            )
        } else {
            Log.w(TAG, "runtime install incomplete (missing assets); installing")
        }
        install(manifest, onProgress, onDetail)
        return false
    }

    /**
     * noruntime 包且本机还没有完整运行时 → 首启需要先征得用户同意再下载。
     * 只读探针，不触发安装。
     */
    fun needsRuntimeDownload(): Boolean = runCatching {
        val manifest = readManifest()
        manifest.packageName == "noruntime" &&
            !(EngineConfig.nodeBin(ctx).exists() && isRootComplete())
    }.getOrDefault(false)

    /**
     * 引擎进入 Healthy/SafeMode 后调用：确认新运行时可用，删除 engine.old 与 pending 标记。
     * 幂等；没有待确认安装时是 no-op。
     *
     * ⚠️ v1.4.26：**SafeMode 也算确认**（引擎 HTTP 通、只是插件降级）。这个语义是
     * 有意的 —— 运行时本身没问题就不该回滚，插件故障归 ProfileGuardian 管。
     * 改动之前先读 docs/RUNTIME_SWAP.md。
     */
    fun markHealthy() {
        val marker = pendingMarker()
        val backup = backupDir()
        val log = readSwapLog()
        // 换装日志还停在 swap 相位，说明启动恢复没能把它处理掉（回滚失败）。
        // 这种情况下**什么都不删** —— 备份是下次启动重试回滚的唯一依据，
        // 此刻把它删掉，下次启动就会对着一个没有备份的「换到一半」现场去还原。
        if (log != null && log.phase != PHASE_COMMITTED) {
            Log.w(
                TAG,
                "markHealthy: swap log still in phase=${log.phase}; keeping evidence for next start",
            )
            return
        }
        if (!marker.isFile && !backup.exists() && log == null) return
        runCatching {
            if (backup.exists() && !backup.deleteRecursively()) {
                Log.w(TAG, "could not remove runtime backup ${backup.absolutePath}")
            }
            marker.delete()
            metaBackupFile().delete()
            stampBackupFile().delete()
            // committed 日志正常在提交时已删；这里兜的是「提交后、删日志前被杀，
            // 而启动恢复又没走到」的极端情况。
            if (log?.phase == PHASE_COMMITTED) deleteSwapLog()
        }.onFailure { Log.w(TAG, "markHealthy cleanup failed: ${it.message}") }
        Log.i(TAG, "runtime confirmed healthy; previous runtime backup released")
    }

    /**
     * 启动时处理“上次换运行时后从未确认 Healthy”的情况。
     *
     * 两次启动都未进入 Healthy（或当前 runtimes 不完整）→ 回滚 engine.old；
     * 第一次未确认只累加计数，避免用户强杀 App 造成误回滚。
     */
    private fun recoverPendingHealth() {
        val marker = pendingMarker()
        val backup = backupDir()
        if (!marker.isFile) return
        if (!backup.isDirectory || backup.listFiles().isNullOrEmpty()) {
            marker.delete()
            return
        }
        val attempts = (runCatching { marker.readText().trim().toInt() }.getOrNull() ?: 0) + 1
        val currentOk = isRootComplete()
        if (currentOk && attempts < 2) {
            marker.writeText(attempts.toString())
            Log.w(TAG, "runtime pending-health (attempt $attempts); keeping new runtime for one more boot")
            return
        }
        Log.w(TAG, "runtime pending-health failed (attempts=$attempts, complete=$currentOk); rolling back")
        runCatching {
            backup.listFiles()?.forEach { old ->
                val current = File(root, old.name)
                current.deleteRecursively()
                if (!old.renameTo(current)) old.copyRecursively(current, overwrite = true)
            }
            restoreMetaBackups()
            marker.delete()
            backup.deleteRecursively()
            Log.w(TAG, "runtime rolled back to previous version after failed boot")
        }.onFailure {
            Log.e(TAG, "runtime rollback failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 处理「上次换装被打断」的现场（v1.4.26）。
     *
     * 判定**只看意图日志**，不看 `isRootComplete()` —— 后者对「新 bin + 旧 lib」
     * 这种混合态是失效的（锚点文件全在，node 架构也对得上，只是彼此不配套）。
     *
     * 两个相位：
     *  - `swap`：换装没走完。一律回滚到 `engine.old`，让磁盘回到「旧运行时 + 一致的
     *    元数据」。回滚后交给 `ensureInstalled`：full 包会因版本戳不符**本地重装**
     *    （不多下一次网络），noruntime 包按「已装好就复用」保持旧版本可用 —— 用户
     *    下次点更新再装，而不是在启动遮罩里被动重下 100MB+。
     *  - `committed`：条目已全部换完，只是没来得及收尾（写版本戳 / 清 staging /
     *    写 pending-health）。直接前滚，别把已经换好的运行时白扔掉。
     *
     * @return true = 处理了现场（调用方可以不关心，诊断用）
     */
    private fun recoverInterruptedSwap(): Boolean {
        val log = readSwapLog() ?: return false
        val backup = backupDir()
        val hasBackup = backup.isDirectory && !backup.listFiles().isNullOrEmpty()
        val complete = isRootComplete()
        Log.w(
            TAG,
            "interrupted runtime swap: phase=${log.phase} backup=$hasBackup " +
                "complete=$complete planned=${log.planned.size} old=${log.old.size}",
        )
        if (log.phase == PHASE_COMMITTED && complete) {
            // 前滚：元数据可能还没写（版本戳 / 清理），按日志补齐。
            writeStamp(
                RuntimeInfo(
                    version = log.version,
                    builtAt = log.builtAt,
                    bundled = log.bundled,
                    url = null,
                    urls = emptyList(),
                    sha256 = log.sha256.takeIf { it.isNotBlank() },
                    size = 0L,
                    unpackedBytes = 0L,
                ),
                bundled = log.bundled,
            )
            File(ctx.filesDir, "engine.staging").deleteRecursively()
            deleteSwapLog()
            if (hasBackup) {
                // 与正常提交路径一致：留着 engine.old 走两次启动的健康确认。
                pendingMarker().writeText("0")
                Log.w(TAG, "runtime swap was already committed; kept engine.old for the first healthy boot")
            } else {
                backup.deleteRecursively()
                metaBackupFile().delete()
                stampBackupFile().delete()
            }
            return true
        }
        rollbackFromLog(log)
        return true
    }

    /**
     * 按意图日志回滚。**必须可重入** —— 回滚本身也可能在同一处被杀。
     *
     * 规则只有两条，且都靠 `log.old` / `log.planned` 判定，不做任何「文件不在就删」
     * 之类的猜测：
     *  ① `planned - old`：新运行时独有的顶层条目 → 删掉（删两次无害）；
     *  ② `old`：`engine.old` 里有就还原，没有就保持 root 不动 ——
     *     「没搬走过」和「已经还原过」在磁盘上是同一个样子，而两种情形都该保留 root。
     *
     * 第二节这条正是幂等性的来源：若按备份目录的当前内容反推「缺失即新条目」，
     * 重入时会把上一轮刚还原好的旧运行时删掉。
     */
    private fun rollbackFromLog(log: SwapLog) {
        val backup = backupDir()
        val planned = log.planned.toSet()
        val old = log.old.toSet()
        runCatching {
            for (name in planned - old) {
                File(root, name).deleteRecursively()
            }
            for (name in old) {
                val oldEntry = File(backup, name)
                if (!oldEntry.exists()) continue
                val current = File(root, name)
                current.deleteRecursively()
                if (!oldEntry.renameTo(current)) oldEntry.copyRecursively(current, overwrite = true)
            }
            restoreMetaBackups()
            Log.w(
                TAG,
                "runtime rolled back from swap log (planned=${planned.size}, restored=${old.size})",
            )
        }.onFailure {
            // 回滚失败：**保留日志与 engine.old**，下次启动继续尝试。
            // 这里绝不清证据 —— 清掉之后磁盘就永久停在混合态了。
            Log.e(TAG, "runtime rollback failed: ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        File(ctx.filesDir, "engine.staging").deleteRecursively()
        deleteSwapLog()
        backup.deleteRecursively()
    }

    /**
     * 清理上次安装被打断后残留的临时目录（v1.4.26）。
     *
     * 解压阶段是最长的窗口（240MB zip 在低端机上 30-60s），也最容易撞上低内存
     * 杀进程。被杀之后 `engine/` 往往仍然完整 → 下次启动走「复用」直接返回，
     * 而 `staging.deleteRecursively()` 只存在于 `install()` 内部 —— 于是
     * `engine.staging`（约 580MB）与 `cacheDir/runtime.zip`（约 124MB）会**永久
     * 留在盘上**，用户只看到「空间莫名少了一大截」。这里给它们一个归宿。
     */
    private fun cleanupInstallResidue() {
        // 有换装日志说明本次是「换到一半」，此时 staging 是证据，不能动。
        if (swapLogFile().isFile) return
        val staging = File(ctx.filesDir, "engine.staging")
        if (staging.exists()) {
            val mb = runCatching { sizeOf(staging) / (1L shl 20) }.getOrDefault(0L)
            if (staging.deleteRecursively()) {
                Log.w(TAG, "removed orphaned engine.staging (${mb}MB) left by an interrupted install")
            }
        }
        val zip = File(ctx.cacheDir, "runtime.zip")
        if (zip.isFile) {
            val mb = zip.length() / (1L shl 20)
            if (zip.delete()) Log.w(TAG, "removed orphaned runtime.zip cache (${mb}MB)")
        }
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * 换装中途自杀开关（**仅 debuggable 包生效**）。
     *
     * 这个缺陷没法靠单测覆盖 —— 它只在「进程在两条指令之间被 SIGKILL」时出现。
     * 所以留一个能精确命中窗口的开关，让人能用一条 adb 命令复现：
     *
     * ```
     * adb shell run-as app.dsh.debug sh -c 'echo after_entry:1 > files/.debug-swap-abort'
     * ```
     *
     * 取值：`before_swap` / `after_entry:N` / `after_commit`。
     * 用 [android.os.Process.killProcess] 而不是抛异常 —— 异常会走 finally、
     * 会触发回滚，那就测不到「被杀之后」的恢复了。
     */
    private fun debugAbortAt(point: String) {
        if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val f = File(ctx.filesDir, ".debug-swap-abort")
        if (!f.isFile) return
        val spec = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        if (spec != point) return
        Log.e(TAG, "debug: aborting process at swap point '$point' to simulate a mid-swap kill")
        android.os.Process.killProcess(android.os.Process.myPid())
        Runtime.getRuntime().halt(9)
    }

    private fun pendingMarker() = File(ctx.filesDir, ".pending-health")
    private fun backupDir() = File(ctx.filesDir, "engine.old")
    private fun metaBackupFile() = File(ctx.filesDir, ".runtime.json.old")
    private fun stampBackupFile() = File(ctx.filesDir, ".runtime-version.old")

    // ── 换装意图日志（v1.4.26）───────────────────────────────────────────────
    //
    // 为什么需要它：v1.4.20 的 staging + 逐条换名已经让「解压」和「生效」分离，
    // 但**换装本身**仍是多步破坏性操作，而盘上唯一的进度证据 `.pending-health`
    // 要到换装**全部完成之后**才写（原 :634）、在换装**开始之前**就被删（原 :565）。
    // 进程在两者之间被强杀（低内存 / 用户强杀 / OEM 清后台）→ 没有任何标记，
    // 下次启动 `recoverPendingHealth()` 直接 return，`engine/` 停在新旧混合态。
    //
    // 更糟的是混合态**不一定**被 `isRootCompleteAt()` 判为不完整 —— 它只查锚点
    // 文件是否在、node 架构是否匹配，新 `bin/` 配旧 `lib/` 完全通得过。于是
    // 「靠 complete=false 重装自愈」这条兜底会失效，用户拿到一个错配的运行时。
    //
    // 所以这里把「换装」变成显式的两阶段提交：
    //   swap（已写入意图、尚未动 root）→ committed（全部条目已换完）
    // 恢复判定只看这份日志，不再依赖完整性探针。

    /**
     * 换装意图日志。
     *
     * @param planned 本次要替换的**顶层条目**名（= staging 的顶层条目）
     * @param old     `planned` 中在换装前**确实存在于 root** 的子集 —— 只有这些
     *                才可能被搬进 `engine.old`，回滚也只处理这些
     * @param version 目标运行时版本（committed 相位下用于补写版本戳）
     * @param sha256  包校验和（同上，补写 `.runtime.json` 时保持与正常路径一致）
     */
    private data class SwapLog(
        val phase: String,
        val planned: List<String>,
        val old: List<String>,
        val version: String,
        val builtAt: Long,
        val bundled: Boolean,
        val sha256: String = "",
    ) {
        fun toJson(): String = JSONObject()
            .put("phase", phase)
            .put("planned", org.json.JSONArray(planned))
            .put("old", org.json.JSONArray(old))
            .put("version", version)
            .put("builtAt", builtAt)
            .put("bundled", bundled)
            .put("sha256", sha256)
            .put("at", System.currentTimeMillis())
            .toString()
    }

    private fun swapLogFile() = File(ctx.filesDir, ".runtime-swap")

    /** 写换装日志。必须原子：进程可能在写入过程中被杀。 */
    private fun writeSwapLog(log: SwapLog) {
        atomicWriteText(swapLogFile(), log.toJson())
    }

    /**
     * 读换装日志。损坏/截断时返回 null —— 调用方按「无日志」处理，
     * 那是 v1.4.25 之前的旧行为，不会比现在更差。
     */
    private fun readSwapLog(): SwapLog? = runCatching {
        val f = swapLogFile()
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        fun arr(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        }
        SwapLog(
            phase = o.optString("phase", PHASE_SWAP),
            planned = arr("planned"),
            old = arr("old"),
            version = o.optString("version"),
            builtAt = o.optLong("builtAt", 0L),
            bundled = o.optBoolean("bundled", false),
            sha256 = o.optString("sha256", ""),
        )
    }.onFailure {
        Log.w(TAG, "swap log unreadable, treating as absent: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    private fun deleteSwapLog() {
        swapLogFile().delete()
    }

    /**
     * 原子写文本：`tmp` → `fsync` → `rename`。
     *
     * 直接 `writeText` 不保证落盘顺序，断电/被杀时可能留下半截文件；而这份日志
     * 的全部价值就在于「被杀之后还能读到」。rename(2) 在同一文件系统内是原子的，
     * 所以读到的一定是完整的旧内容或完整的新内容。
     */
    private fun atomicWriteText(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // 个别 ROM/文件系统的 renameTo 不能覆盖已存在文件：先删再试一次。
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw java.io.IOException("cannot commit ${target.name}")
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun backupMeta() {
        runCatching {
            if (metaFile.isFile) metaFile.copyTo(metaBackupFile(), overwrite = true) else metaBackupFile().delete()
            if (stampFile.isFile) stampFile.copyTo(stampBackupFile(), overwrite = true) else stampBackupFile().delete()
        }.onFailure { Log.w(TAG, "runtime metadata backup failed: ${it.message}") }
    }

    private fun restoreMetaBackups() {
        runCatching {
            if (metaBackupFile().isFile) metaBackupFile().copyTo(metaFile, overwrite = true)
            if (stampBackupFile().isFile) stampBackupFile().copyTo(stampFile, overwrite = true)
        }.onFailure { Log.w(TAG, "runtime metadata restore failed: ${it.message}") }
        metaBackupFile().delete()
        stampBackupFile().delete()
    }

    /**
     * 显式安装云端索引里的运行时（设置/关于页“检查运行时更新”）。
     * 与首启安装走同一条 staging 流程；调用方需在后台线程执行。
     *
     * v1.4.21：不再用 `check(!installing)` 抛 “runtime install already in progress”。
     * 那个布尔量是**进程级**的，引擎启动时的自动安装也会置位 —— 用户在启动安装
     * 期间点更新、或连点两次更新，就会直接撞上这条英文异常。现在改为串行等待：
     * 同一时刻只有一个安装真正落盘，后到的请求排队而不是报错。
     */
    fun installFromIndex(
        entry: RuntimeIndexClient.Entry,
        indexUrl: String?,
        proxies: List<String>,
        onProgress: (Float) -> Unit = {},
        onDetail: (BootTrace.Install) -> Unit = {},
        /** 下载阶段可取消（后台服务用）；解压阶段不再理会 —— 半解压的 engine 目录比多等 3 分钟更糟。 */
        shouldCancel: () -> Boolean = { false },
    ): String = synchronized(INSTALL_LOCK) {
        // v1.4.26：先把「上次换装被杀在半路」的现场收拾掉再装。
        // 否则 `install()` 开头的 `backup.deleteRecursively()` 会把回滚数据删掉，
        // 而 root 还是混合态 —— 这次换装会以「混合态」为基线，越修越乱。
        recoverInterruptedSwap()
        val info = RuntimeInfo(
            version = entry.version,
            builtAt = entry.builtAt,
            bundled = false,
            url = entry.urls.firstOrNull(),
            urls = entry.urls,
            sha256 = entry.sha256,
            size = entry.size,
            unpackedBytes = entry.unpackedBytes,
        )
        val manifest = Manifest("noruntime", defaultAbi(), info, indexUrl, proxies)
        install(manifest, onProgress, onDetail, shouldCancel)
        entry.version
    }

    /** 读安装元数据（.runtime.json），旧安装回退到纯文本版戳。 */
    private fun readInstalled(): Installed? = runCatching {
        if (metaFile.isFile) {
            val obj = JSONObject(metaFile.readText())
            return@runCatching Installed(
                version = obj.optString("version"),
                builtAt = obj.optLong("builtAt", 0L),
                source = obj.optString("source"),
            )
        }
        if (stampFile.isFile) {
            return@runCatching Installed(stampFile.readText().trim(), 0L, "legacy")
        }
        null
    }.getOrElse { e ->
        Log.w(TAG, "runtime metadata unreadable (${e.javaClass.simpleName}: ${e.message})")
        null
    }

    private fun hasAsset(path: String): Boolean = runCatching {
        ctx.assets.open(path).close()
        true
    }.getOrDefault(false)

    /** 设备主 ABI（manifest 里通常已有，legacy 包兜底）。 */
    private fun defaultAbi(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64-v8a"
        "x86_64" -> "x86_64"
        else -> Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    }

    private fun currentVersionCode(): Int = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    private fun isRootComplete(): Boolean = isRootCompleteAt(root)

    /**
     * 完整性探针（可对任意目录，用于 staging 校验）。
     * 只查「必须存在」的锚点文件，避免与运行时裁剪的解耦（不校验具体数量）。
     */
    private fun isRootCompleteAt(base: File): Boolean {
        val node = File(base, "bin/node")
        // 1) 引擎入口 bin.js（MODULE_NOT_FOUND 的直接案发现场）
        if (!File(base, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile) return false
        // 2) bash 依赖闭环：readline SONAME 别名（m1.5 事故）
        if (!File(base, "lib/libreadline.so.8").isFile) return false
        // 3) node 本体可执行位（安装器 restoreExecBits 之后应为 true）
        if (!node.canExecute()) return false
        // 4) @deepseek-ai 固定锚点抽样（覆盖入口包、核心框架、内置 profile 层与前端 bundle）
        val dshAi = File(base, "lib/node_modules/@deepseek-ai")
        val anchors = listOf(
            "dsh/package.json",
            "cordis/lib/index.js",
            "dsh-base/cordis.patch.yml",
            "dsh-web-app/cordis.patch.yml",
            "dsh-client-ui-chat/lib/client.js",
        )
        for (rel in anchors) {
            if (!File(dshAi, rel).isFile) {
                Log.w(TAG, "runtime incomplete: missing anchor @deepseek-ai/$rel")
                return false
            }
        }
        // 5) 架构必须匹配（跨架构 ELF 会在 execve 被 linker 拒绝）
        val want = expectedElfMachine()
        if (want > 0) {
            val got = elfMachine(node)
            if (got != want) {
                Log.e(TAG, "runtime node architecture mismatch: e_machine=$got, device expects $want")
                return false
            }
        }
        return true
    }

    /** ELF e_machine 取值（System V ABI） */
    private val EM_ARM = 40
    private val EM_X86_64 = 62
    private val EM_AARCH64 = 183

    /** 本机主 ABI 对应的 e_machine；无法判定返回 -1（此时跳过校验，不误伤） */
    private fun expectedElfMachine(): Int = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> EM_AARCH64
        "x86_64" -> EM_X86_64
        "armeabi-v7a", "armeabi" -> EM_ARM
        else -> -1
    }

    /** 读 ELF 头的 e_machine；读不到 / 不是 ELF 返回 -1 */
    private fun elfMachine(file: File): Int = runCatching {
        file.inputStream().use { elfMachine(it) }
    }.getOrDefault(-1)

    /** 从流里读 ELF e_machine（只读头 20 字节，不整体载入） */
    private fun elfMachine(ins: java.io.InputStream): Int = runCatching {
        val head = ByteArray(20)
        var read = 0
        while (read < head.size) {
            val n = ins.read(head, read, head.size - read)
            if (n <= 0) return -1
            read += n
        }
        // 0x7F 'E' 'L' 'F'
        if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) return -1
        // e_machine @ 18，little-endian（Android 全部小端）
        (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
    }.getOrDefault(-1)

    /** 解压前校验源 runtime.zip 里的 bin/node 架构，不匹配就抛确定性错误 */
    private fun assertAssetArch(zip: File) {
        val want = expectedElfMachine()
        if (want <= 0) return
        val got = runCatching {
            java.util.zip.ZipFile(zip).use { zf ->
                val entry = zf.getEntry("bin/node") ?: return@use -1
                zf.getInputStream(entry).use { elfMachine(it) }
            }
        }.getOrDefault(-1)
        if (got != want) {
            // ⚠️ 不能只是返回 false 让它重装：APK 内的包就是错的，重装 100 次也一样，
            // 反而把用户拖进「重装→启动→再死」的循环。这里抛确定性异常，
            // 由监督器转成明确的 Failed 状态（见 EngineArchMismatchException 的消费点）。
            throw EngineArchMismatchException(
                "runtime node architecture mismatch: bundled e_machine=$got, device expects $want",
            )
        }
    }

    /**
     * 把 APK 内置的 runtime.zip 拷到 cacheDir，并按字节上报进度（v1.4.8）。
     *
     * 拷贝 240MB 在低端设备上可占 30-60 秒，是"解压"阶段里最长的一段。
     * 不报进度的话，遮罩在整段时间里只能显示一句静态文案。
     *
     * 用 [BootTrace.Install] 复用同一个数据模型：文件数填 0（这一步没有文件概念），
     * 字节数是确定值，遮罩按 `totalFiles == 0` 走纯字节展示。
     */
    private fun copyAssetWithProgress(
        dest: File,
        onDetail: (BootTrace.Install) -> Unit,
    ) {
        val total = runCatching {
            ctx.assets.openFd("runtime.zip").use { it.length }
        }.getOrNull() ?: -1L
        var done = 0L
        var lastAt = 0L
        ctx.assets.open("runtime.zip").use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)   // 1MB：240 次回调级别的粒度
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastAt >= 250L) {
                        lastAt = now
                        runCatching {
                            onDetail(
                                BootTrace.Install(
                                    doneFiles = 0,
                                    totalFiles = 0,
                                    doneBytes = done,
                                    totalBytes = if (total > 0) total else 0,
                                ),
                            )
                        }
                    }
                }
            }
        }
        // 收尾：拷贝完成，进度落到 100%
        runCatching {
            onDetail(
                BootTrace.Install(
                    doneFiles = 0,
                    totalFiles = 0,
                    doneBytes = done,
                    totalBytes = if (total > 0) total else done,
                ),
            )
        }
    }

    /**
     * 真正的下载 + 解压 + 换名。
     *
     * ⚠️ 调用方必须持有 [INSTALL_LOCK]（[ensureInstalled] / [installFromIndex]
     * 已经代劳），否则会和另一个安装同时写 `engine/`。
     */
    private fun install(
        manifest: Manifest,
        onProgress: (Float) -> Unit,
        onDetail: (BootTrace.Install) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ) {
        // var：下载分支拿到签名索引后会用**条目事实**覆盖它（见下面 entry != null 处）。
        var info = manifest.runtime
        Log.i(TAG, "installing runtime ${info.version} (${manifest.packageName})")
        installing = true
        try {
            val assetZip = File(ctx.cacheDir, "runtime.zip")
            assetZip.delete()
            // 【v1.2.97】空间预检（用户邮件反馈：覆盖升级后引擎反复崩溃重启）。
            // 覆盖升级 = 旧 runtime 还在盘上 + zip 缓存 + 新解压峰值同卷并存。
            fun mb(v: Long) = v / (1L shl 20)
            fun requireFree(needMb: Long) {
                val usable = root.usableSpace
                check(usable >= needMb * (1L shl 20)) {
                    ctx.getString(R.string.error_runtime_no_space, needMb, mb(usable))
                }
            }

            val bundled = info.bundled && hasAsset("runtime.zip")
            val unpackMb = if (info.unpackedBytes > 0) mb(info.unpackedBytes) else 500
            if (bundled) {
                // v1.4.20 staging：旧 runtime + engine.staging + zip 峰值
                requireFree(unpackMb * 2 + 300)
                copyAssetWithProgress(assetZip, onDetail)
                requireFree(unpackMb * 2 + mb(assetZip.length()) + 200)
            } else {
                val abi = manifest.abi.ifBlank { defaultAbi() }
                // v1.4.21：代理来自用户偏好（默认 gh-proxy.org），不再只信 MANIFEST。
                // MANIFEST 保留直连地址作为兜底，代理地址由这里统一生成并排到最前。
                val proxy = RuntimeDownloadConfig.proxy(ctx)
                var urls = info.urls.ifEmpty { listOfNotNull(info.url) }
                var pinned = info.sha256?.takeIf { it.isNotBlank() }
                var expectedSize = info.size
                if (pinned == null) {
                    // edge/preview 的 manifest 指向滚动 latest URL，构建时没有 sha；
                    // 先从签名索引取 sha，验签失败/不可达则拒绝下载（不降级）。
                    val entry = RuntimeIndexClient.resolve(
                        ctx = ctx,
                        indexUrl = manifest.indexUrl,
                        proxyBase = proxy,
                        abi = abi,
                        exactVersion = info.version,
                        currentVersionCode = currentVersionCode(),
                    )
                    if (entry != null) {
                        pinned = entry.sha256
                        if (expectedSize <= 0) expectedSize = entry.size
                        urls = (urls + entry.urls).distinct()
                        // v1.4.38：戳必须记「实际装的那份」，不能用 MANIFEST 的占位值。
                        // noruntime 包的 MANIFEST 里 runtime.version 恒为 "unknown"、
                        // builtAt=0、sha 为空（构建期没有运行时内容），真实版本只存在于
                        // 签名索引条目里。此前一路沿用 manifest.runtime 写戳 →
                        // `.runtime.json` 永远是 unknown/0/""，而更新侧
                        // `UpdateAssetPicker.runtimeChanged()` 拿它和索引条目比，必然
                        // 判定「运行时变了」：full 包用户每次升级都被劝去多下 130MB，
                        // 且 `ensureInstalled` 里 `installed.builtAt > 包内 builtAt`
                        // 这条「别被旧 APK 降级」的保护也因为 builtAt=0 而失效。
                        info = info.copy(
                            version = entry.version.takeIf { it.isNotBlank() } ?: info.version,
                            builtAt = entry.builtAt.takeIf { it > 0 } ?: info.builtAt,
                            sha256 = entry.sha256,
                            size = entry.size.takeIf { it > 0 } ?: info.size,
                            unpackedBytes = entry.unpackedBytes.takeIf { it > 0 } ?: info.unpackedBytes,
                        )
                    }
                }
                // 用户显式留空（直连）时，把 MANIFEST/索引里预置的镜像地址剔掉；
                // 若剔完没有可用直连地址，则保留原列表兜底（至少还能下载）。
                val blockedProxies = if (proxy.isBlank()) {
                    (manifest.proxies + RuntimeDownloadConfig.DEFAULT_PROXY).distinct()
                } else {
                    emptyList()
                }
                urls = RuntimeDownloadConfig.orderUrls(urls, proxy, blockedProxies)
                if (urls.isEmpty()) {
                    throw IllegalStateException(ctx.getString(R.string.error_runtime_no_source))
                }
                val expected = pinned
                    ?: throw IllegalStateException(
                        "Runtime download has no SHA-256 pin (signed runtime-index unavailable)",
                    )
                requireFree(unpackMb * 2 + mb(expectedSize) + 300)
                Log.i(TAG, "downloading runtime ${info.version} from $urls")
                BootTrace.stage(BootTrace.Stage.DOWNLOADING)
                onProgress(0f)
                HttpDownloader(ctx).download(
                    urls, assetZip, expected, expectedSize,
                    onProgress = { frac ->
                    // 下载占进度条前 45%，解压占后 55%，避免进度条回到 0 再走一遍。
                    onProgress(frac * 0.45f)
                    if (expectedSize > 0) {
                        runCatching {
                            onDetail(
                                BootTrace.Install(
                                    doneFiles = 0,
                                    totalFiles = 0,
                                    doneBytes = (expectedSize * frac).toLong(),
                                    totalBytes = expectedSize,
                                ),
                            )
                        }
                    }
                    },
                    shouldCancel = shouldCancel,
                )
            }

            // v1.3.3（issue #23）：解压前断言架构。必须在**删除旧 runtime 之前**做 ——
            // 否则发现架构不对时旧环境已经被拆了，用户从「启动失败」退化成「什么都没了」。
            assertAssetArch(assetZip)
            BootTrace.stage(BootTrace.Stage.EXTRACTING)

            // v1.2.21 事故修复：绝不 root.deleteRecursively()（会把 extensions/ 一起删）。
            // v1.4.20：先解压到 engine.staging，校验通过后再逐个顶层条目换名；
            // 任何一步失败都从 engine.old 回滚，旧运行时保持可用。
            // v1.4.26：换装改为「意图日志 + 两阶段提交」，恢复不再依赖完整性探针。
            val staging = File(ctx.filesDir, "engine.staging")
            val backup = File(ctx.filesDir, "engine.old")
            staging.deleteRecursively()
            // 旧的 engine.old 连同它对应的 pending 标记一起作废：从这一刻起
            // 「回滚目标」是 root 里当前这套运行时，由下面的 swap 日志重新建立。
            backup.deleteRecursively()
            pendingMarker().delete()
            backupMeta()
            staging.mkdirs()
            runCatching {
                if (Privilege.dshHomeNeedsOwnershipFix(ctx)) {
                    Log.w(TAG, "runtime reinstall: healing file attributes before wipe")
                    Privilege.fixHomeOwnership(ctx)
                }
            }

            unzip(
                assetZip,
                staging,
                onProgress = { p -> onProgress(if (bundled) p else 0.45f + p * 0.55f) },
                onDetail = onDetail,
            )
            restoreExecBits(staging)
            check(isRootCompleteAt(staging)) {
                "staged runtime incomplete (${staging.absolutePath})"
            }

            root.mkdirs()
            val entries = staging.listFiles()?.toList().orEmpty()
            check(entries.isNotEmpty()) { "staging is empty" }

            // ── 意图落盘（v1.4.26）────────────────────────────────────────────
            // 这是整个换装唯一的持久化交接点：
            //  · 写它**之前**被杀 → root 一个字节都没动，走「完整即复用」不会错；
            //  · 写它**之后**被杀 → 下次启动能读到 planned/old，精确回滚。
            // `old` 只记「换装前确实存在」的顶层条目 —— 只会这些才可能被搬进
            // engine.old，回滚时也只能还原这些。新运行时独有的条目（planned - old）
            // 回滚时直接删，避免遗留。
            val planned = entries.map { it.name }
            val oldNames = planned.filter { File(root, it).exists() }
            val pendingLog = SwapLog(
                phase = PHASE_SWAP,
                planned = planned,
                old = oldNames,
                version = info.version,
                builtAt = info.builtAt,
                bundled = bundled,
                sha256 = info.sha256 ?: "",
            )
            writeSwapLog(pendingLog)
            debugAbortAt("before_swap")

            try {
                entries.forEachIndexed { index, entry ->
                    val name = entry.name
                    val current = File(root, name)
                    if (current.exists()) {
                        val old = File(backup, name)
                        old.parentFile?.mkdirs()
                        // ⚠️ v1.4.26（issue #49）：换名失败即中止，**不再退化成
                        // copyRecursively**。staging / root / engine.old 全在
                        // filesDir（同一文件系统），rename(2) 是原子的；退回拷贝
                        // 只会把「原子切换」降级成「拷到一半」，正是恢复窗口被
                        // 拉长的根源。真失败说明文件系统状态异常，回滚比硬撑着
                        // 搬完更安全 —— 半拷贝的文件既不是旧的也不是新的。
                        if (!current.renameTo(old)) {
                            throw java.io.IOException("cannot move ${current.absolutePath}")
                        }
                    }
                    if (!entry.renameTo(current)) {
                        throw java.io.IOException("cannot place ${current.absolutePath}")
                    }
                    debugAbortAt("after_entry:${index + 1}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "runtime swap failed; rolling back per swap log", e)
                rollbackFromLog(pendingLog)
                throw e
            }

            // ── 提交点（v1.4.26）──────────────────────────────────────────────
            // 先写 committed、再写版本戳：万一在两者之间被杀，恢复走「前滚」，
            // 按日志补写戳 —— 不会把一个已经换好的运行时白白回滚掉。
            writeSwapLog(pendingLog.copy(phase = PHASE_COMMITTED))
            debugAbortAt("after_commit")
            writeStamp(info, bundled)
            staging.deleteRecursively()
            deleteSwapLog()
            if (oldNames.isNotEmpty()) {
                // 保留 engine.old 直到引擎首次 Healthy；两次未确认则自动回滚。
                pendingMarker().writeText("0")
                Log.i(TAG, "runtime installed; engine.old kept pending first healthy boot")
            } else {
                backup.deleteRecursively()
                metaBackupFile().delete()
                stampBackupFile().delete()
            }
            assetZip.delete()
            Log.i(TAG, "runtime installed at $root (${entries.size} top-level entries)")
        } finally {
            installing = false
        }
    }

    /** 写安装元数据：`.runtime.json`（新） + `.runtime-version`（旧代码/诊断仍读它）。 */
    private fun writeStamp(info: RuntimeInfo, bundled: Boolean) {
        val obj = JSONObject()
            .put("version", info.version)
            .put("builtAt", info.builtAt)
            .put("sha256", info.sha256 ?: "")
            .put("source", if (bundled) "bundled" else "download")
            .put("installedAt", System.currentTimeMillis())
        runCatching { metaFile.writeText(obj.toString()) }
            .onFailure { Log.w(TAG, "cannot write .runtime.json: ${it.message}") }
        runCatching { stampFile.writeText(info.version) }
            .onFailure { Log.w(TAG, "cannot write .runtime-version: ${it.message}") }
    }

    private fun unzip(
        zip: File,
        target: File,
        onProgress: (Float) -> Unit,
        onDetail: (BootTrace.Install) -> Unit = {},
    ) {
        // 预扫 central directory 拿总未压缩字节（ZipInputStream 流式读时 size 可能为 -1，
        // ZipFile 走 central directory 是精确的）→ 真实确定性进度而非假转圈
        var total = 0L
        // 文件数双口径（v1.4.8）：字节数在解压小文件时几乎不动，用户看到进度条
        // 长时间停在同一个百分比；"已加载 N/M 个文件"更直观且始终在动。
        var totalFiles = 0
        java.util.zip.ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                total += e.size
                if (!e.isDirectory) totalFiles++
            }
        }
        var done = 0L
        var doneFiles = 0
        // 明细节流：每 256 个文件或每 4MB 才回调一次，避免两万次状态更新
        var lastDetailAt = 0L
        fun publishDetail(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastDetailAt < 250L) return
            lastDetailAt = now
            runCatching {
                onDetail(
                    BootTrace.Install(
                        doneFiles = doneFiles,
                        totalFiles = totalFiles,
                        doneBytes = done,
                        totalBytes = total,
                    ),
                )
            }
        }
        // 别名去重（v1.2.98）：Termux 的 SONAME 别名（libicu.so.78.3 → libicu.so.78
        // → libicu.so）在打包时若各自存一份，仅 ICU 一家就浪费 ~95MB（实测全库 89.4MB）。
        // 打包侧改为「只存一份数据，其余别名条目的 comment 写 LINK:<本体路径>」，
        // 这里解压时**复制成实体文件**还原（linker 不认链接，见 materialiseLink 的说明）。
        //
        // ⚠️ 必须**两阶段**：zip 里别名条目可能排在本体之前（实测 libz.so.1 排在
        // libz.so 之前），若边解压边建链，链接时本体还不存在 → 变成 0 字节空文件
        // （正是「CANNOT LINK ... libz.so.1 not found」那类故障的成因）。
        // 所以先落全部本体，再统一建链接 —— 与 ExtensionManager 的 deb 解包同一套路。
        val linkTargets = readLinkTargets(zip)
        if (linkTargets.isNotEmpty()) {
            Log.i(TAG, "unzip: ${linkTargets.size} deduplicated alias entries (copied)")
        }
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(target, entry.name).canonicalFile
                // 防 zip-slip：解压目标必须落在 engineRoot 内
                check(out.path.startsWith(target.canonicalPath)) { "zip-slip: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (linkTargets.containsKey(entry.name)) {
                    // 别名条目：zip 里数据为空，跳过写入，阶段 2 统一建链
                    out.parentFile?.mkdirs()
                    doneFiles++
                    publishDetail()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { done += zis.copyTo(it) }
                    doneFiles++
                    if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    publishDetail()
                }
                zis.closeEntry()
            }
        }
        // 阶段 2：本体已全部落地，建硬链接（失败自动回退复制）
        linkTargets.forEach { (alias, linkTo) ->
            materialiseLink(target, linkTo, File(target, alias).canonicalFile)
        }
        // 收尾强制发一次：进度条要落到 100%、文件数要落到 N/N
        publishDetail(force = true)
    }

    /** 别名条目表：条目 comment 形如 `LINK:lib/libicu.so.78.3` → 该条目只需建硬链接 */
    private fun readLinkTargets(zip: File): Map<String, String> {
        val map = HashMap<String, String>()
        java.util.zip.ZipFile(zip).use { zf ->
            val it = zf.entries()
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                val c = e.comment ?: continue
                if (c.startsWith(LINK_PREFIX)) map[e.name] = c.removePrefix(LINK_PREFIX)
            }
        }
        return map
    }

    /**
     * 还原别名（v1.2.98/v1.2.99 定稿）：**复制成实体文件**。
     *
     * ## 为什么最终只能复制（三轮实测的结论）
     * 打包侧把内容相同的 SONAME 别名去重（zip 里只存一份数据，省 33MB APK），
     * 解压侧必须把别名还原成**真实文件**，因为 Android 的 linker 在 app 域
     * **既不认硬链接也不认符号链接**：
     *
     * - 硬链接：SELinux 直接拒绝
     *   `avc: denied { link } ... scontext=u:r:untrusted_app_27 tclass=file`
     * - 符号链接：能创建，但 **linker 不跟随** →
     *   `CANNOT LINK EXECUTABLE ".../bin/node": library "libz.so.1" not found`
     *   （`libz.so.1 -> libz.so` 明明存在却找不到，引擎完全起不来）
     *
     * 这也解释了 collect-termux-runtime.sh 里那句注释的原意 ——
     * 「Android SELinux 禁 symlink/link()，必须 cp 出普通文件别名」。
     *
     * ## 结果
     * APK 体积省下来了（156→124MB）；解压后磁盘占用与优化前相同（~582MB）。
     * 体积与磁盘不可兼得，优先保 APK（下载/分享成本更直观），磁盘靠用户清理。
     */
    private fun materialiseLink(root: File, linkTo: String, dest: File) {
        val src = File(root, linkTo).canonicalFile
        if (!src.isFile) {
            Log.w(TAG, "alias source missing: $linkTo (for ${dest.name})")
            return
        }
        runCatching {
            if (dest.exists()) dest.delete()
            src.copyTo(dest, overwrite = true)
        }.onFailure { Log.w(TAG, "alias copy failed for ${dest.name}: ${it.message}") }
    }

    /**
     * 恢复可执行位（zip 不保存 Unix 权限，解压后一律是 0644）。
     *
     * ## 为什么不能只扫 bin/ 和 usr/bin/（v1.2.100 修）
     * AI 自查发现 `grep` / `glob` 工具报
     * 「ripgrep provider failure」，但 bash 里 `rg` 正常 —— 因为工具走的**不是**
     * PATH 里的 `engine/bin/rg`，而是 npm 包内的副本：
     *
     *     lib/node_modules/@vscode/ripgrep-android-arm64/bin/rg   ← 权限 0600，spawn 时 EACCES
     *     engine/bin/rg                                           ← 正常（旧逻辑扫到了）
     *
     * `dsh-tool-fs-search` 的 `resolveRgPath()` 从 `@vscode/ripgrep` 解析路径，
     * 拿到的是前者。旧实现只扫两个固定目录 → 漏掉 node_modules 里的可执行文件。
     *
     * ## 现在的做法
     * ① 固定目录（bin / usr/bin）全量置可执行 —— 保持原有语义
     * ② 全树扫描 node_modules 下的 bin 目录与已知可执行名 —— 覆盖包内副本
     * ③ 只对**确实是 ELF 或脚本**的文件动手（读前 4 字节），避免给 .md/.json 加 x 位
     */
    private fun restoreExecBits(root: File) {
        fun grant(f: File) {
            runCatching {
                f.setExecutable(true, false)
                f.setReadable(true, false)
            }
        }
        // ① 固定目录
        listOf("bin", "usr/bin").forEach { dir ->
            File(root, dir).takeIf { it.isDirectory }?.listFiles()?.forEach { grant(it) }
        }
        // ② node_modules 下的 bin/ 目录（ripgrep / esbuild / 各类 CLI 副本都在这）
        val nm = File(root, "lib/node_modules")
        if (!nm.isDirectory) return
        nm.walkTopDown()
            .maxDepth(6)
            .filter { it.isFile }
            .filter { f ->
                val p = f.invariantSeparatorsPath
                // 只处理 bin/ 与 .bin/ 目录内的，或已知需要执行位的名字。
                // ⚠️ v1.4.6（审查）：`/bin/` 匹配不到 `node_modules/.bin/xxx`
                //（那是 `/.bin/`，中间没有 `/bin/` 子串）。当前 runtime 里有 12 个
                // `.bin` 条目（.bin/dsh、.bin/cordis、.bin/semver…），全部是
                // `#!/usr/bin/env node` 脚本 —— 一个都没被置上可执行位，任何直接
                // exec `.bin/xxx` 的路径（npm/pnpm 的 PATH 注入、工具链 shim）
                // 都会 EACCES，表现为「某个工具偶发不可用」。
                p.contains("/bin/") || p.contains("/.bin/") || f.name in EXECUTABLE_NAMES
            }
            .forEach { f ->
                if (isElfOrScript(f)) grant(f)
            }
    }

    /** 前 4 字节是 ELF 魔数，或首行以 #! 开头（脚本） */
    private fun isElfOrScript(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val head = ByteArray(4)
            if (ins.read(head) < 2) return@use false
            val isElf = head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
            isElf || (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte())
        }
    }.getOrDefault(false)

    companion object {
        /** runtime 装配进行中：ExtensionManager 拒绝在此窗口安装扩展（防互删） */
        @Volatile
        var installing: Boolean = false
            private set

        /**
         * 进程级安装锁（v1.4.21）。
         *
         * `installing` 只是「有没有人在装」的标志，不能用来互斥 —— 引擎首启的
         * 自动安装、关于页的显式更新、以及可能被服务重新拉起的启动流程会并发
         * 进入。两个安装同时解压/换名会把 engine 目录拆坏，而此前 About 的
         * `check(!installing)` 只是把后来者挡在门外（并抛出英文异常）。
         *
         * 现在所有入口都在这把锁上排队；`installing` 仍然保留给扩展中心做
         * 「运行时装配中不要装扩展」的判断。
         */
        private val INSTALL_LOCK = Any()

        private const val TAG = "RuntimeInstaller"

        /**
         * 别名条目标记（v1.2.98）：打包侧把重复内容的 so 只存一份，其余条目在 zip
         * comment 里写 `LINK:<本体相对路径>`；解压时据此建硬链接而非写重复数据。
         */
        const val LINK_PREFIX = "LINK:"

        /**
         * 除 bin/ 目录外仍需可执行位的文件名（v1.2.100）。
         * ripgrep 的包内副本在 `@vscode/ripgrep-<arch>/bin/rg`（已被 /bin/ 规则覆盖），
         * 这里列的是可能放在其他位置的常见可执行文件。
         */
        private val EXECUTABLE_NAMES = setOf(
            "rg", "esbuild", "node", "sharp", "swc", "dprint", "biome",
        )

        /** @deepseek-ai 作用域下「完整安装」至少应存在的文件数（m1.12 空壳事故阈值） */
        private const val MIN_DSH_AI_FILES = 100

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 随包 runtime 的 CPU 架构与本机不符（v1.3.3，issue #23）。
 *
 * 这是**确定性**故障：APK 里带的 `bin/node` 就是另一个架构的 ELF，重装多少次都一样。
 * 所以它必须与「解压失败 / 空间不足」这类可重试错误区分开 —— 监督器收到它应直接
 * 进入 Failed 并给出明确文案，而不是继续指数退避重试把用户拖在「装完就是坏的」循环里。
 */
class EngineArchMismatchException(message: String) : Exception(message)

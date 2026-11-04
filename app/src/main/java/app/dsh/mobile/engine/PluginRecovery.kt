package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件启动故障的有限自愈。
 *
 * Cordis 会把插件 import/init 失败留在 Loader 树里而不一定让 HTTP 进程退出，
 * 所以仅看端口或 supervisor 的崩溃计数无法判断工作区能力是否真的存在。本类读取
 * 内置 health probe 的一次性标记，按失败 entry 反查 profile bundle，并只从
 * `dsh.profile.bundles` 摘掉一个第三方 bundle。package.json、lockfile 与插件文件
 * 保留，用户可在插件中心重新启用；修改前另存备份，且连续自愈有硬上限。
 */
object PluginRecovery {

    private const val TAG = "PluginRecovery"
    private const val HEALTH_MARKER = ".android-plugin-health.json"
    private const val STATE_FILE = "plugin-recovery.state"
    private const val BACKUP_DIR = "plugin-recovery"
    private const val MAX_ATTEMPTS = 3
    /** 引擎连续启动失败多少次后启用「快速隔离」（见 isolateRecentBundleOnBootFailure）。
     *  取 2：第一次可能是环境抖动（su 忙、端口刚释放），第二次还失败基本就是插件问题。 */
    const val FAST_ISOLATE_STREAK = 2
    /** 快速隔离的生命周期总上限（v1.4.16 审查 P2）。
     *  防止根因与插件无关时（runtime 损坏等）把所有第三方插件逐个摘光。 */
    const val MAX_FAST_ISOLATIONS = 3

    data class FailedEntry(val id: String, val name: String, val error: String = "")

    data class Health(
        val ok: Boolean,
        val missingServices: List<String>,
        val failedEntries: List<FailedEntry>,
        val bundles: List<String>,
    )

    data class Action(
        val packageName: String,
        val reason: String,
        val attempt: Int,
    )

    fun markerFile(ctx: Context): File = File(EngineConfig.dshHome(ctx), HEALTH_MARKER)

    /**
     * 引擎**压根起不来**时的快速隔离（v1.4.15）。
     *
     * ## 与 [quarantineOne] 的区别
     *
     * [quarantineOne] 处理的是「引擎起来了、HTTP 通了，但插件探针报 degraded」——
     * 那时有 marker 文件可以归因到具体 entry。
     *
     * 用户反馈的另一类更糟：**装完插件后引擎再也起不来**，走不到探针那一步，
     * marker 根本不存在。这时只能靠「node_modules 里哪个第三方包的 package.json
     * 最近被改写过」来判断，也就是最后安装的那个。
     *
     * ## 判定条件（保守，宁可不动手）
     *
     *   ① 引擎已连续 [FAST_ISOLATE_STREAK] 次无法启动（由监督器保证）
     *   ② profile 里存在第三方 bundle
     *   ③ 能唯一确定「最近安装」的那个包（与次新的差 ≥ 60s）
     *
     * 满足就摘掉它并备份清单。**不猜测、不批量摘**：一次只动一个；
     * 同一批装进来的多个包分不清主次时宁可不动，交给 ProfileGuardian 的慢通道。
     *
     * @return 被摘掉的包名；不满足条件时返回 null
     */
    fun isolateRecentBundleOnBootFailure(ctx: Context): Action? {
        val profileDir = File(EngineConfig.dshHome(ctx), "profiles/web")
        val manifestFile = File(profileDir, "package.json")
        if (!manifestFile.isFile) return null
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return null
        val bundles = manifest.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")
            ?: return null
        val names = (0 until bundles.length())
            .mapNotNull { bundles.optString(it).takeIf(String::isNotBlank) }
        val userBundles = names.filter(::isUserBundle)
        if (userBundles.isEmpty()) {
            Log.i(TAG, "boot failure: no third-party bundle to isolate")
            return null
        }

        // 用 node_modules 里该包 package.json 的 mtime 判断「最近安装」——
        // pnpm 装完会重写它，比 profile 清单的 mtime 更贴近真实安装时刻。
        val candidates = userBundles.map { name ->
            val pj = File(File(profileDir, "node_modules"), "$name/package.json")
            name to pj.lastModified()
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (candidates.isEmpty()) return null
        val newest = candidates.first().first
        val newestAt = candidates.first().second

        // 同一批安装（差值 < 60s）分不清主次时不动手
        if (candidates.size > 1 && newestAt - candidates[1].second < 60_000L) {
            Log.i(TAG, "boot failure: multiple bundles installed together; not guessing")
            return null
        }

        return runCatching {
            backupManifest(ctx, manifestFile, newest)
            val next = JSONArray()
            names.filterNot { it == newest }.forEach(next::put)
            val dsh = manifest.optJSONObject("dsh") ?: JSONObject().also { manifest.put("dsh", it) }
            val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            profile.put("bundles", next)
            atomicWrite(manifestFile, manifest.toString(2) + "\n")
            Log.e(
                TAG,
                "boot failure: isolated most recently installed bundle $newest " +
                    "(engine could not start; manifest backed up)",
            )
            Action(newest, "engine failed to start after install", 1)
        }.onFailure {
            Log.e(TAG, "failed to isolate $newest: ${it.message}", it)
        }.getOrNull()
    }

    /** 每次 fork 前清掉旧标记，避免上一次崩溃的诊断结果误伤下一次启动。 */
    fun prepareForBoot(ctx: Context) {
        runCatching { markerFile(ctx).delete() }
            .onFailure { Log.w(TAG, "cannot clear health marker: ${it.message}") }
    }

    /** 本次 boot 已通过健康检查：允许下一次独立故障重新使用有限自愈额度。 */
    fun onHealthy(ctx: Context) {
        clearState(ctx)
    }

    fun readHealth(ctx: Context, expectedBootId: String? = null): Health? = runCatching {
        val file = markerFile(ctx)
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText())
        // 删除 marker 可能因旧 Root 属主失败；bootId 是第二道防线，
        // 防止把上一次引擎的 degraded 结果应用到本次正常启动。
        if (expectedBootId != null && json.optString("bootId") != expectedBootId) {
            // 明确打出两边的值。这里静默返回 null 曾经掩盖过一个真 bug：
            // Linux 容器路径把环境变量写成 DSH_BOOT_ID（插件读的是
            // DSH_ANDROID_BOOT_ID），于是 marker 里 bootId 恒为空串，
            // 校验永远不匹配 → 监督器空等满 20 秒超时才放行引擎，
            // 表现为「Linux 模式冷启动要 22 秒」。有这行日志一眼就能看出来。
            Log.w(
                TAG,
                "health marker bootId mismatch: file='${json.optString("bootId")}' " +
                    "expected='$expectedBootId' — treating as stale",
            )
            return@runCatching null
        }
        val failed = mutableListOf<FailedEntry>()
        val failedArray = json.optJSONArray("failedEntries") ?: JSONArray()
        for (i in 0 until failedArray.length()) {
            val row = failedArray.optJSONObject(i) ?: continue
            failed += FailedEntry(
                id = row.optString("id"),
                name = row.optString("name"),
                error = row.optString("error"),
            )
        }
        Health(
            ok = json.optBoolean("ok", false),
            missingServices = json.optStringArray("missingServices"),
            failedEntries = failed,
            bundles = json.optStringArray("bundles"),
        )
    }.onFailure {
        Log.w(TAG, "cannot read health marker: ${it.message}")
    }.getOrNull()

    /**
     * 对一次 degraded boot 做有限处理；返回 null 表示没有可安全归因的用户 bundle，
     * 此时由监督器继续普通退避并把完整诊断留在 engine.log，不会盲目删除用户配置。
     */
    fun quarantineOne(ctx: Context, health: Health): Action? {
        if (health.ok) {
            clearState(ctx)
            return null
        }
        val state = readState(ctx)
        if (state.attempts >= MAX_ATTEMPTS) {
            Log.e(
                TAG,
                "auto-disable limit reached ($MAX_ATTEMPTS); " +
                    "leaving profile unchanged (missing=${health.missingServices}, failed=${health.failedEntries})",
            )
            return null
        }

        val profileDir = File(EngineConfig.dshHome(ctx), "profiles/web")
        val manifestFile = File(profileDir, "package.json")
        if (!manifestFile.isFile) {
            Log.w(TAG, "web profile manifest is missing; cannot quarantine a plugin")
            return null
        }
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrElse {
            Log.e(TAG, "cannot parse web profile manifest: ${it.message}", it)
            return null
        }
        val bundles = manifest.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")
            ?: JSONArray()
        val names = (0 until bundles.length()).mapNotNull { bundles.optString(it).takeIf(String::isNotBlank) }
        val userBundles = names.filter(::isUserBundle)
        if (userBundles.isEmpty()) {
            Log.e(TAG, "degraded boot has no removable third-party bundle; profile was not changed")
            return null
        }

        val candidates = userBundles.filter { bundle ->
            val rows = readBundleRows(profileDir, bundle)
            health.failedEntries.any { failed ->
                rows.any { row ->
                    (failed.id.isNotBlank() && row.first == failed.id) ||
                        (failed.name.isNotBlank() && row.second == failed.name)
                }
            }
        }
        // 没有失败 entry 与用户 bundle 的精确对应关系时绝不猜测：
        // workspaceRegistry 的持久化故障、内置包损坏等情况不能靠“最后安装的插件”背锅。
        // 这时只保留健康探针和 engine.log 诊断，交给下一层 ProfileGuardian/用户处理。
        val target = candidates.lastOrNull() ?: run {
            Log.e(TAG, "degraded boot has no exact third-party bundle attribution; profile was not changed")
            return null
        }
        val attempt = state.attempts + 1
        val reason = buildReason(health, exact = true)

        return runCatching {
            backupManifest(ctx, manifestFile, target)
            val nextBundles = JSONArray()
            names.filterNot { it == target }.forEach(nextBundles::put)
            val dsh = manifest.optJSONObject("dsh") ?: JSONObject().also { manifest.put("dsh", it) }
            val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            profile.put("bundles", nextBundles)
            atomicWrite(manifestFile, manifest.toString(2) + "\n")
            writeState(ctx, attempt, target)
            Log.e(
                TAG,
                "auto-disabled third-party plugin $target (attempt=$attempt/$MAX_ATTEMPTS, " +
                    "reason=$reason); package and lockfile were kept",
            )
            Action(target, reason, attempt)
        }.onFailure {
            Log.e(TAG, "failed to disable plugin $target: ${it.message}", it)
        }.getOrNull()
    }

    private data class State(val attempts: Int, val lastPackage: String?)

    private fun readState(ctx: Context): State {
        val file = File(EngineConfig.dshHome(ctx), STATE_FILE)
        val parts = runCatching { file.readText().trim().split('|', limit = 2) }.getOrNull()
            ?: return State(0, null)
        return State(parts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0, parts.getOrNull(1))
    }

    private fun writeState(ctx: Context, attempts: Int, packageName: String) {
        val file = File(EngineConfig.dshHome(ctx), STATE_FILE)
        atomicWrite(file, "$attempts|$packageName\n")
    }

    private fun clearState(ctx: Context) {
        runCatching { File(EngineConfig.dshHome(ctx), STATE_FILE).delete() }
    }

    /** 第三方 bundle 判定 —— [PluginKillSwitch] 共用同一套口径，别在两处各写一份 */
    internal fun isUserBundle(name: String): Boolean =
        !name.startsWith("@deepseek-ai/") &&
            name != "dsh-base" &&
            name != "dsh-web-app"

    /** 只读取 bundle patch 的 id/name，不执行其中的 JavaScript 表达式。 */
    private fun readBundleRows(profileDir: File, packageName: String): Set<Pair<String, String>> {
        val packageDir = File(File(profileDir, "node_modules"), packageName)
            .let { runCatching { it.canonicalFile }.getOrDefault(it) }
        val manifestFile = File(packageDir, "package.json")
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return emptySet()
        val bundle = manifest.optJSONObject("dsh")?.optJSONObject("bundle") ?: return emptySet()
        val patch = bundle.opt("patch")
        val patchFiles = when (patch) {
            is String -> listOf(patch)
            is JSONArray -> (0 until patch.length()).mapNotNull { patch.optString(it).takeIf(String::isNotBlank) }
            else -> emptyList()
        }
        return patchFiles.flatMap { relative ->
            val file = File(packageDir, relative)
            parsePatchRows(runCatching { file.readText() }.getOrDefault(""))
        }.toSet()
    }

    private fun parsePatchRows(text: String): Set<Pair<String, String>> {
        val rows = mutableSetOf<Pair<String, String>>()
        var id: String? = null
        var name = ""
        fun flush() {
            val current = id ?: return
            rows += current to name
            id = null
            name = ""
        }
        for (line in text.lineSequence()) {
            val idMatch = Regex("^\\s*-\\s*id:\\s*(.+?)\\s*$").find(line)
            if (idMatch != null) {
                flush()
                id = yamlScalar(idMatch.groupValues[1])
                continue
            }
            if (id != null) {
                val nameMatch = Regex("^\\s*name:\\s*(.+?)\\s*$").find(line)
                if (nameMatch != null) name = yamlScalar(nameMatch.groupValues[1])
            }
        }
        flush()
        return rows
    }

    private fun yamlScalar(value: String): String = value.trim()
        .removeSuffix(",")
        .removeSurrounding("'", "'")
        .removeSurrounding("\"", "\"")
        .substringBefore(" #")
        .trim()

    private fun buildReason(health: Health, exact: Boolean): String = buildString {
        append(if (exact) "failed profile row matched bundle" else "degraded host service with no exact bundle row")
        if (health.missingServices.isNotEmpty()) append("; missing=" + health.missingServices.joinToString(","))
        if (health.failedEntries.isNotEmpty()) {
            append("; failed=")
            append(health.failedEntries.joinToString(",") {
                "${it.id}:${it.name}" + if (it.error.isNotBlank()) " (${it.error.lineSequence().firstOrNull()?.take(240)})" else ""
            })
        }
    }

    private fun backupManifest(ctx: Context, source: File, packageName: String) {
        val dir = File(EngineConfig.dshHome(ctx), BACKUP_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val safeName = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        source.copyTo(File(dir, "$stamp-$safeName.package.json"), overwrite = false)
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        temp.writeText(text, StandardCharsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IllegalStateException("rename failed for ${file.name}")
        }
        file.setReadable(true, false)
    }

    private fun JSONObject.optStringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }
}

package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Profile 配置守护（自愈层）。
 *
 * 背景：dsh 的插件配置是 AI/用户可写的 YAML，任何一处形状错误都会让引擎在
 * 插件树加载阶段 fail-loud 循环崩溃（实测事故：cordis.patch.yml 的 group 条目
 * 缺 config 数组 → "config is not iterable" → 引擎无限退避重启）。监督器的
 * 退避对这类确定性错误毫无意义——重启多少次都在同一处死。
 *
 * 三层自愈策略（只动配置层 dsh-home/profiles/，绝不触碰 workspaces/ 会话等用户资产）：
 *
 * 1. last-good 快照：引擎每次 Healthy 时备份 profiles/ 到 profiles.last-good/
 * 2. 崩溃回滚：同签名连续崩溃 ≥N 次 → 用 last-good 覆盖回当前 profiles/
 * 3. 安全模式：连 last-good 都救不回来（或没有快照）→ 把坏配置整体归档到
 *    profiles.crash-archive-<时间戳>/，用空目录拉起引擎。引擎永远能起来；
 *    用户最多丢插件配置（归档保留，可在文件管理器里找回），会话与工作区无损。
 *
 * 另含启动前 YAML 静态预检（廉价启发式）：把明显不可解析的配置改名隔离，
 * 减少进入运行时崩溃的次数。改名为 <name>.quarantine-<n> 不删除，可人工恢复。
 */

/** 同签名失败多少次后才动手（留出偶发崩溃的空间；配合指数退避总时长可控）。 */
private const val FAILURES_BEFORE_ROLLBACK = 3

private const val TAG = "ProfileGuardian"

class ProfileGuardian(private val ctx: Context) {

    /** 崩溃计数持久化文件名 */
    private var crashMeta: File = File(ctx.filesDir, "profile-guardian.meta")

    fun profilesRoot(): File = File(EngineConfig.dshHome(ctx), "profiles")

    private fun lastGoodDir(): File = File(EngineConfig.dshHome(ctx), "profiles.last-good")

    // ---------- 1. last-good 快照 ----------

    /** 引擎 Healthy 后调用；同步做小型目录拷贝。
     *  刻意排除 node_modules（可重装的派生物、体量大、且可能含指向
     *  引擎目录的符号链接）；只保护配置层。 */
    fun snapshotLastGood() {
        val src = profilesRoot()
        if (!src.isDirectory) return
        val dst = lastGoodDir()
        try {
            dst.deleteRecursively()
            copyTreeSkippingNodeModules(src, dst)
            Log.i(TAG, "last-good snapshot updated (${dst.walkBottomUp().count()} entries)")
        } catch (e: IOException) {
            // 快照失败不影响引擎运行，只影响下次回滚质量
            Log.w(TAG, "last-good snapshot failed: ${e.message}")
        }
    }

    /** 复制目录树但跳过任何名为 node_modules 的子树与符号链接项 */
    private fun copyTreeSkippingNodeModules(src: File, dst: File) {
        if (src.isDirectory) {
            if (src.name == "node_modules") return
            if (java.nio.file.Files.isSymbolicLink(src.toPath())) return
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyTreeSkippingNodeModules(child, File(dst, child.name))
            }
        } else {
            if (java.nio.file.Files.isSymbolicLink(src.toPath())) return
            src.copyTo(dst, overwrite = true)
        }
    }

    fun hasLastGood(): Boolean = lastGoodDir().isDirectory

    // ---------- 2. 确定性崩溃检测与回滚 ----------

    private data class CrashState(val streak: Int, val signature: String?)

    private fun readCrashState(): CrashState {
        if (!crashMeta.isFile) return CrashState(0, null)
        val parts = crashMeta.readText().trim().split('|', limit = 2)
        val n = parts.getOrNull(0)?.toIntOrNull() ?: 0
        return CrashState(n, parts.getOrNull(1))
    }

    private fun writeCrashState(state: CrashState) {
        runCatching { crashMeta.writeText("${state.streak}|${state.signature ?: ""}") }
    }

    /** 引擎 Healthy 时清零崩溃计数 */
    fun resetCrashStreak() {
        runCatching { crashMeta.delete() }
    }

    enum class Action { NONE, ROLLED_BACK, SAFE_MODE }

    /**
     * 引擎启动失败时调用。内部逻辑：
     * - 同签名连续失败达 [failuresBeforeRollback] 次：
     *   a) 有 last-good → 回滚（历史内容先归档）→ ROLLED_BACK
     *   b) 无 last-good 或已回滚过仍失败 → 归档现配置 + 清空 → SAFE_MODE
     * - 签名不同/次数不足 → NONE（正常退避即可）
     *
     * @param failureSignature 本次失败的稳定签名（低频调用，允许 O(log) 拼接）
     */
    fun onFailure(failureSignature: String): Action {
        val st = readCrashState()
        val sameAsBefore = st.signature == failureSignature
        val nextStreak = if (sameAsBefore) st.streak + 1 else 1
        writeCrashState(CrashState(nextStreak, failureSignature))

        if (nextStreak < FAILURES_BEFORE_ROLLBACK) return Action.NONE

        return if (hasLastGood() && !st.signature.isNullOrEmpty() && !rolledBackFor(st.signature)) {
            rollbackToLastGood()
            markRolledBack(st.signature)
            Action.ROLLED_BACK
        } else {
            enterSafeMode(reason = st.signature)
            clearRolledBackMark()
            Action.SAFE_MODE
        }
    }

    private fun rolledBackMarker(signature: String): File =
        File(ctx.filesDir, "guardian.rolledback.${signature.hashCode()}")

    private fun rolledBackFor(signature: String): Boolean = rolledBackMarker(signature).isFile

    private fun markRolledBack(signature: String) {
        runCatching { rolledBackMarker(signature).writeText("pending safe-mode evaluation") }
    }

    private fun clearRolledBackMark() {
        ctx.filesDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("guardian.rolledback.")) f.delete()
        }
    }

    /** 用 last-good 覆盖当前 profiles（当前内容先移入回滚存档，不直接删除） */
    private fun rollbackToLastGood() {
        val cur = profilesRoot()
        val lg = lastGoodDir()
        val backupOfCurrent = File(EngineConfig.dshHome(ctx), "profiles.before-rollback")
        try {
            backupOfCurrent.deleteRecursively()
            if (cur.isDirectory && !cur.renameTo(backupOfCurrent)) throw IOException("rename failed")
            lg.copyRecursively(cur)
            Log.w(TAG, "rolled back profiles to last-good; previous saved at ${backupOfCurrent.name}")
        } catch (e: IOException) {
            Log.e(TAG, "rollback failed: ${e.message}", e)
        }
    }

    // ---------- 3. 安全模式 ----------

    /**
     * 归档当前 profiles 并以空目录拉起。归档命名带时间戳，多次触发互不覆盖。
     * 只动 profiles/ —— workspaces、sessions、凭证等用户资产不在此目录内，天然不受影响。
     *
     * @param reason 触发时的崩溃签名，写进 .safe-mode 标记；用户事后可凭它
     *               在 crash-archive 里对照日志定位是哪个文件把引擎搞死的。
     */
    fun enterSafeMode(reason: String? = null) {
        val cur = profilesRoot()
        try {
            if (cur.isDirectory && cur.list()?.isNotEmpty() == true) {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
                val archive = File(EngineConfig.dshHome(ctx), "profiles.crash-archive-$stamp")
                check(!archive.exists()) { "archive name collision" }
                if (!cur.renameTo(archive)) throw IOException("archive rename failed")
                Log.e(TAG, "SAFE MODE: quarantined bad profiles to ${archive.name}")
            } else {
                cur.deleteRecursively()
            }
            cur.mkdirs()
            File(cur, ".safe-mode").writeText(
                "engine entered safe mode ${System.currentTimeMillis()}\n" +
                    (reason?.let { "failure signature: $it\n" } ?: "") +
                    "your plugin configs were archived alongside this directory's siblings\n" +
                    "recover: copy the needed files back from profiles.crash-archive-* after fixing them\n",
            )
        } catch (e: IOException) {
            // 最坏情况兜底：连归档都失败也必须保证空目录存在
            Log.e(TAG, "safe-mode archive failed hard: ${e.message}; forcing empty profiles", e)
            cur.deleteRecursively()
            cur.mkdirs()
        }
    }

    fun inSafeMode(): Boolean = File(profilesRoot(), ".safe-mode").isFile

    // ---------- 4. 启动前静态预检（廉价启发式） ----------

    /**
     * 启动前静态预检：只检查【用户直接可写】的配置文件——
     * profiles/ 根与一级子目录（如 web/）下的 cordis.patch*.yml|yaml。
     * 铁律（事故 90535 的教训）：
     *   - 深度永远 ≤2，绝不递归进 node_modules（pnpm 符号链接会指向引擎目录，
     *     曾把引擎自带 dsh-base/cordis.patch.yml 改名导致全引擎起不来）；
     *   - 任何符号链接直接跳过；
     *   - 命中即改名 *.quarantine-N，不删除。
     *
     * 假阳性防护（m1.6.4）：启发式只拦「确定性非序列」形态，所有存疑形态一律
     * 放行——宁漏放不错杀。能正常加载的插件文件绝不能被保护层自己搞掉；
     * 预检真的放走了坏文件也没关系，运行时崩溃回滚层兜底。
     */
    fun quarantineMalformedPatches(): List<File> {
        val isolated = mutableListOf<File>()
        val root = profilesRoot()
        if (!root.isDirectory) return isolated

        val scanDirs = sequenceOf(root) + root.listFiles()
            ?.filter { it.isDirectory && it.name != "node_modules" && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            ?.asSequence().orEmpty()

        for (dir in scanDirs) {
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                // 只盯引擎真正读取的 overlay 命名（cordis.patch*.yml|yaml）；
                // 用户的 cordis-xxx.yml 私有文件引擎根本不加载，不碰。
                if (!f.name.startsWith("cordis.patch")) return@forEach
                if (!f.name.endsWith(".yml") && !f.name.endsWith(".yaml")) return@forEach
                val text = runCatching { f.readText().removePrefix("\uFEFF") }.getOrNull() ?: return@forEach
                val meaningful = text.lineSequence()
                    .map { it.trimEnd('\r') }
                    .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && it.trimEnd() != "---" }
                    .toList()
                // 合法形态（满足其一即放行，宁漏放不错杀——真会崩的交给运行时崩溃回滚兜底）：
                // a) 空文档 / "[]"；b) 整体是 JSON 数组（JSON 是 YAML 子集，流式写法也覆盖）；
                // c) 顶层块式数组条目（"- " 或其缩进续行）。
                val looksLikeList = meaningful.isEmpty() ||
                    (meaningful.size == 1 && meaningful[0].trim() == "[]") ||
                    isJsonArray(meaningful) ||
                    meaningful.all { line ->
                        line.startsWith("- ") || line.startsWith("-\t") || line.startsWith(" ") || line.startsWith("\t")
                    }
                if (!looksLikeList) {
                    val target = quarantineName(f)
                    if (f.renameTo(target)) {
                        isolated += target
                        Log.e(TAG, "quarantined malformed patch file: ${f.path} -> ${target.name}")
                    }
                }
            }
        }
        return isolated
    }

    private fun quarantineName(src: File): File {
        var n = 0
        var target: File
        do {
            target = File(src.parentFile, "${src.name}.quarantine-${if (n == 0) "" else "$n-"}${System.currentTimeMillis() % 100000}")
            n++
        } while (target.exists())
        return target
    }

    /** 形态探针：把有意义的行拼回文档，能用 org.json 解析成【数组】即视为合法。
     *  Android 自带 org.json（零依赖）；JSONObject 返回 false——引擎要的是序列，
     *  映射形态真会崩，那种交给运行时回滚兜底而不是预检猜测。 */
    private fun isJsonArray(meaningful: List<String>): Boolean = runCatching {
        org.json.JSONArray(meaningful.joinToString("\n"))
        true
    }.getOrDefault(false)

    /**
     * 内置 overlay 自愈：修复被误隔离的引擎自带 cordis.patch.yml。
     *
     * 事故 90535/90537/90546：早期 `walkTopDown` 扫描器经 pnpm 符号链接钻进
     * engine/lib/node_modules/@deepseek-ai/ 各包，把 dsh-base / dsh-headless /
     * dsh-web-app 的内置 cordis.patch.yml 改名成了 *.quarantine-N。引擎每次
     * 启动都要 loadOverlayPatches 读这些内置 patch，缺失任一即 ENOENT fail-loud
     * → 无限重启。
     *
     * 此方法把「原文件缺失 + 存在同名前缀的 .quarantine-*」恢复回原名。
     * 只对 engine 内置目录操作，且只回滚隔离名，绝不触碰用户配置层。
     */
    fun restoreQuarantinedBuiltinOverlays(): Int {
        var restored = 0
        val dshAi = File(EngineConfig.engineRoot(ctx), "lib/node_modules/@deepseek-ai")
        if (!dshAi.isDirectory) return 0
        dshAi.listFiles()?.forEach { pkg ->
            if (!pkg.isDirectory || java.nio.file.Files.isSymbolicLink(pkg.toPath())) return@forEach
            val originals = pkg.listFiles()
                ?.filter { it.isFile && it.name == "cordis.patch.yml" }
                ?.isNotEmpty() == true
            if (originals) return@forEach // 原文件健在，无需恢复
            val quarantine = pkg.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name.startsWith("cordis.patch.yml.quarantine-") &&
                        !java.nio.file.Files.isSymbolicLink(it.toPath())
                }
                ?.sortedBy { it.lastModified() }
                ?.firstOrNull() ?: return@forEach
            val target = File(pkg, "cordis.patch.yml")
            if (quarantine.renameTo(target)) {
                restored++
                Log.e(TAG, "restored quarantined builtin overlay: ${quarantine.name} -> cordis.patch.yml (pkg=${pkg.name})")
            }
        }
        return restored
    }
}

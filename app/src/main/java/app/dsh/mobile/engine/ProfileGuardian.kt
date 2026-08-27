package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Profile 配置守护（自愈层）· m1.6.5 保守化重构。
 *
 * 背景：dsh 的插件配置是 AI/用户可写的 YAML，形状错误会让引擎在插件树加载阶段
 * fail-loud 循环崩溃。但真机实测证明：任何"主动预防"（启动前静态隔离）的误伤率
 * 都高到不可接受——能正常加载的插件被保护层自己搞掉，等于制造比原故障更严重的
 * 故障。本层从此只做【事后被动响应】，且触发条件极度保守：
 *
 *   只有在「引擎真实死亡」（非超时自杀、非安装异常）且签名连续一致的前提下，
 *   走两阶段升级，全程累计需 ≥10 次连续同签名真死才可能归档用户配置：
 *
 *   阶段0（计数 ≥5 次）→ 有 last-good 则回滚；没有则只观望（绝不直接归档，
 *                          因为 AI 可能正在初始化，崩溃也可能与配置无关）
 *                       → 计数清零、进入阶段1（给回滚效果完整的一轮观察期）
 *   阶段1（再计数 ≥5 次）→ 仍同签名真死 → 才进入安全模式（归档 + 空配置拉起）
 *
 * 引擎 Healthy 时一切计数/阶段归零。 Healthy 后运行中退出不计入（那是资源类
 * 偶发问题，交给监督器普通退避即可，与配置无关）。
 *
 * 只动 dsh-home/profiles/（workspaces/sessions/凭证零接触）；归档不删除可找回。
 */

/** 每个阶段的容忍额度：同签名【引擎真死】连续达到该值才升级动作 */
private const val FAILURES_PER_STAGE = 5

private const val TAG = "ProfileGuardian"

class ProfileGuardian(private val ctx: Context) {

    /** 崩溃计数持久化文件名。内容格式：<streak>|<phase>|<signature> */
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

    // ---------- 2. 两阶段确定性崩溃检测 ----------

    /** phase: 0=初始观察；1=已做过一次处置（回滚或观望），等待再次定罪 */
    private data class CrashState(val streak: Int, val phase: Int, val signature: String?)

    private fun readCrashState(): CrashState {
        if (!crashMeta.isFile) return CrashState(0, 0, null)
        val parts = crashMeta.readText().trim().split('|', limit = 3)
        return CrashState(
            streak = parts.getOrNull(0)?.toIntOrNull() ?: 0,
            phase = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            signature = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }

    private fun writeCrashState(state: CrashState) {
        runCatching {
            crashMeta.writeText("${state.streak}|${state.phase}|${state.signature ?: ""}")
        }
    }

    /** 引擎 Healthy 时归零一切计数与阶段 */
    fun resetCrashStreak() {
        runCatching { crashMeta.delete() }
    }

    enum class Action { NONE, ROLLED_BACK, SAFE_MODE }

    /**
     * 仅当「引擎自行死亡」时调用（监督器保证：健康检查超时自杀、安装异常等
     * 一律不进来）。内部逻辑见类注释的两阶段模型。
     *
     * @param failureSignature 本次失败的稳定签名（低频调用，允许 O(n) 拼接）
     */
    fun onFailure(failureSignature: String): Action {
        val st = readCrashState()
        val sameAsBefore = st.signature == failureSignature
        val nextStreak = if (sameAsBefore) st.streak + 1 else 1

        if (nextStreak < FAILURES_PER_STAGE) {
            writeCrashState(CrashState(nextStreak, st.phase, failureSignature))
            return Action.NONE
        }

        // 同签名连续真死已达阶段阈值
        return when (st.phase) {
            0 -> {
                // 第一次定罪：能回滚就回滚；不能就先观望——绝不立刻归档。
                // 无论哪种都切到阶段1重新计数，给处置效果完整的观察窗口。
                val action = if (hasLastGood()) {
                    rollbackToLastGood()
                    Log.w(TAG, "guardian stage-0: rolled back to last-good; entering verification stage")
                    Action.ROLLED_BACK
                } else {
                    Log.w(TAG, "guardian stage-0: no last-good snapshot; observing only (NO safe-mode yet)")
                    Action.NONE
                }
                writeCrashState(CrashState(0, 1, failureSignature))
                action
            }
            else -> {
                // 回滚/观望之后依然同签名连续真死 5 次 → 最后手段
                enterSafeMode(reason = failureSignature)
                writeCrashState(CrashState(0, 0, null))
                clearRolledBackMark()
                Action.SAFE_MODE
            }
        }
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

    // ---------- 3. 安全模式（最后手段） ----------

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

    // ---------- 4. 历史误隔离残留的自愈（唯一的"预检"类能力） ----------

    /**
     * 修复被历史版本（≤m1.6.4 的启动前预检）误隔离的引擎自带 cordis.patch.yml。
     *
     * 事故 90535/90537/90546：早期扫描器经 pnpm 符号链接钻进 engine 内置包目录，
     * 把 dsh-base / dsh-headless / dsh-web-app 的内置 cordis.patch.yml 改名成了
     * *.quarantine-N。引擎每次启动都要 loadOverlayPatches 读这些内置 patch，
     * 缺失任一即 ENOENT fail-loud → 无限重启。老 APK 升级后磁盘上可能仍有残留，
     * 此方法把「原文件缺失 + 存在 .quarantine-*」恢复回原名。
     * 只对 engine 内置目录操作，绝不触碰用户配置层。
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

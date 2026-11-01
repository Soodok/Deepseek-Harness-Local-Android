package app.dsh.mobile.engine

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * 全权限自动获取（v1.5.0-beta.9）。
 *
 * ## 设计原则（宝宝定的）
 * **无差别尝试**：不从白名单挑，而是把 Manifest 里声明的**每一个**权限都试一遍。
 * 能拿就拿，拿不到就算了 —— 差的那 1000 多个 signature 级权限本来就不属于第三方
 * App，试一次的成本只有一条 `pm grant`，但留给未来系统放开（比如某个版本把某个
 * signature 权限加了 development 标记）的余地。
 *
 * ## 铁律
 * `pm grant` **只对已声明的权限生效**，且返回值 `EXIT=0` 不代表成功 ——
 * 必须 `checkSelfPermission` 回读复核。我们踩过这个坑：早期声明缺失时
 * `pm grant` 静默成功但完全不生效，导致误判「Android 不让给」。
 *
 * ## ⚠️ 绝不批量弹窗（宝宝 2026-11-01 明确要求）
 * 本类**只走 root / Shizuku 静默通道**，一个系统权限框都不弹。原因：
 * - `pm grant` 是 root 身份下的静默授权，没有 UI；
 * - 用户最反感的是「一装完 App 连续弹十几个权限框」，那会把首启体验彻底毁掉。
 *
 * 因此本类**不调用** `Activity.requestPermissions(...)`，也**不引导**用户批量去哪授权。
 * 危险权限的系统弹窗一律由 [app.dsh.mobile.PermissionCenterActivity] 在用户**主动
 * 点某一个开关时**逐个触发 —— 用户点哪个问哪个，不点不问。
 *
 * ## ⚠️ 授权必须跑在 App 进程之外（真机 logcat 铁证）
 * ```
 * I am_kill : [0,20430,app.dsh.mobile,0,permission grant or revoke changed gids,352488]
 * ```
 * `pm grant` 每授一个**带 gid 的**权限，Android 就杀掉 App 进程重载 gid 表。
 * 实测跑到 600/1358 左右线程随进程一起死，`running` 标志永远清不掉 —— 前两版
 * 都是死在这里（一次是残留卡死，一次是永远卡 625/1358）。
 *
 * 所以现在：把整批命令写成 shell 脚本，用 `su` 起**脱离 App 的后台进程**跑。
 * 该进程 PPID=1，不随 App 死亡而终止；它把进度写进状态文件，App 侧只读文件。
 * 读写分离，谁死都不影响。
 */
object PermGrant {

    private const val TAG = "PermGrant"
    private const val PREFS = "perm_grant"
    private const val KEY_LAST_GRANTED = "last_granted"
    private const val KEY_LAST_FAILED = "last_failed"
    private const val KEY_RUNNING = "running"
    private const val KEY_TOTAL = "progress_total"
    private const val KEY_BEFORE = "granted_before"
    private const val KEY_BEAT = "heartbeat"

    /** 状态文件多久没更新就认为后台脚本已死（它每 10 个权限写一次）。 */
    private const val STALE_MS = 120_000L

    /** 换行符（用字符码构造，避免在 Kotlin 字符串里写转义序列）。 */
    private val LF: String = 10.toChar().toString()

    /**
     * 启动全量授权：写脚本 → su 起脱离进程 → 立即返回。
     *
     * 调用方（AI 门脚本 / 维护页）拿到 `started:true` 后应当轮询 [progress]，
     * 直到 `running=false`。
     */
    fun startAsync(ctx: Context, force: Boolean): Result {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_RUNNING, false)) {
            val beat = sp.getLong(KEY_BEAT, 0L)
            if (System.currentTimeMillis() - beat <= STALE_MS) {
                return Result(0, 0, 0, channelMissing = false, skipped = true, alreadyHad = grantedCount(ctx))
                    .also { Log.i(TAG, "startAsync: already running") }
            }
            Log.w(TAG, "startAsync: clearing stale flag")
        }

        val all = declaredPermissions(ctx)
        val todo = all.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        Log.i(TAG, "startAsync: declared=${all.size} todo=${todo.size}")

        if (Privilege.runPrivileged(ctx, "id") == null) {
            return Result(0, 0, all.size, channelMissing = true, skipped = false, alreadyHad = grantedCount(ctx))
        }

        val stateFile = File(ctx.filesDir, "perm-grant.state")
        val scriptFile = File(ctx.filesDir, "perm-grant.sh")
        val script = buildScript(ctx.packageName, todo, stateFile.absolutePath)
        runCatching {
            scriptFile.writeText(script)
            stateFile.writeText(joined("running=1", "done=0", "total=${todo.size}"))
        }.onFailure { Log.w(TAG, "prepare script failed: ${it.message}") }

        // su + nohup：脱离 App 进程，PPID=1，App 被杀也继续跑
        val cmd = "nohup sh " + scriptFile.absolutePath + " >/dev/null 2>&1 &"
        Privilege.runPrivileged(ctx, cmd)
        Log.i(TAG, "detached runner started, todo=${todo.size}")

        sp.edit()
            .putBoolean(KEY_RUNNING, true)
            .putInt(KEY_TOTAL, todo.size)
            .putInt(KEY_BEFORE, grantedCount(ctx))
            .putLong(KEY_BEAT, System.currentTimeMillis())
            .apply()
        return Result(0, 0, todo.size, channelMissing = false, skipped = false, alreadyHad = grantedCount(ctx))
    }

    /**
     * 构造后台授权脚本。
     *
     * 全部用「命令行列表 + 换行拼接」生成，**不在 Kotlin 字符串里写任何转义** ——
     * 之前试过在字符串里塞 `\n`，被 shell 与 Kotlin 两层转义同时吞掉，脚本每次
     * 都断成语法错（状态文件 0 字节），排查掉了很多时间。
     *
     * 状态文件每 10 个权限刷新一次，格式就是 `key=value` 一行一个。
     */
    private fun buildScript(pkg: String, todo: List<String>, statePath: String): String {
        val lines = mutableListOf<String>()
        lines += "#!/system/bin/sh"
        lines += "S=" + statePath
        lines += "T=" + todo.size
        lines += "D=0"
        // 首行用 > 覆盖，其余用 >> 追加 —— 每次刷新都重写成完整快照
        lines += "echo \"running=1\" > \"\$S\""
        lines += "echo \"done=0\" >> \"\$S\""
        lines += "echo \"total=\$T\" >> \"\$S\""
        todo.forEachIndexed { idx, perm ->
            lines += "pm grant " + pkg + " " + perm + " >/dev/null 2>&1"
            lines += "D=\$((D+1))"
            if ((idx + 1) % 10 == 0 || idx == todo.size - 1) {
                lines += "echo \"running=1\" > \"\$S\""
                lines += "echo \"done=\$D\" >> \"\$S\""
                lines += "echo \"total=\$T\" >> \"\$S\""
            }
        }
        lines += "echo \"running=0\" > \"\$S\""
        lines += "echo \"done=\$D\" >> \"\$S\""
        lines += "echo \"total=\$T\" >> \"\$S\""
        return lines.joinToString(LF) + LF
    }

    private fun joined(vararg kv: String): String = kv.joinToString(LF) + LF

    /** 读后台脚本写的状态文件；不存在或格式不对返回 null。 */
    private fun readState(ctx: Context): Map<String, Int>? = runCatching {
        val f = File(ctx.filesDir, "perm-grant.state")
        if (!f.isFile || f.length() == 0L) return null
        val m = HashMap<String, Int>()
        val running = HashMap<String, Boolean>()
        f.readLines().forEach { line ->
            val k = line.substringBefore('=', "").trim()
            val raw = line.substringAfter('=', "").trim()
            if (k.isEmpty()) return@forEach
            raw.toIntOrNull()?.let { m[k] = it } ?: run { if (raw == "true") running[k] = true }
        }
        // running 用 1/0 表示，整数已解析进 m；这里只需判 m 是否有 running
        if (m.isEmpty()) null else m
    }.getOrNull()

    /**
     * 当前进度：**优先信后台脚本写的状态文件**，它才是真正跑完的判据。
     *
     * 文件还没出现（刚启动的几百毫秒）时才回落到 SharedPreferences。
     */
    fun progress(ctx: Context): Progress {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val st = readState(ctx)
        if (st != null && st.containsKey("running")) {
            val running = st["running"] == 1
            val now = grantedCount(ctx)
            val before = sp.getInt(KEY_BEFORE, 0)
            if (!running) {
                sp.edit()
                    .putBoolean(KEY_RUNNING, false)
                    .putInt(KEY_LAST_GRANTED, (now - before).coerceAtLeast(0))
                    .putInt(KEY_LAST_FAILED, (st["total"] ?: 0) - (now - before).coerceAtLeast(0))
                    .apply()
            }
            return Progress(
                running = running,
                done = st["done"] ?: 0,
                total = st["total"] ?: 0,
                newlyGranted = (now - before).coerceAtLeast(0),
                lastGranted = sp.getInt(KEY_LAST_GRANTED, -1),
                lastFailed = sp.getInt(KEY_LAST_FAILED, -1),
            )
        }
        return Progress(
            running = sp.getBoolean(KEY_RUNNING, false),
            done = 0,
            total = sp.getInt(KEY_TOTAL, 0),
            newlyGranted = (grantedCount(ctx) - sp.getInt(KEY_BEFORE, 0)).coerceAtLeast(0),
            lastGranted = sp.getInt(KEY_LAST_GRANTED, -1),
            lastFailed = sp.getInt(KEY_LAST_FAILED, -1),
        )
    }

    /** 从 PackageInfo 读回 Manifest 里声明的全部权限（含未授予的）。 */
    fun declaredPermissions(ctx: Context): List<String> = runCatching {
        val pi = ctx.packageManager.getPackageInfo(
            ctx.packageName, PackageManager.GET_PERMISSIONS,
        )
        (@Suppress("DEPRECATION") pi.requestedPermissions ?: emptyArray()).toList()
    }.getOrDefault(emptyList())

    /** 当前真正拿到的权限数（含 normal 级）。 */
    fun grantedCount(ctx: Context): Int =
        declaredPermissions(ctx).count { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** 上次授权结果的摘要（供 UI 显示）。 */
    fun lastResult(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val g = sp.getInt(KEY_LAST_GRANTED, -1)
        val f = sp.getInt(KEY_LAST_FAILED, -1)
        return if (g < 0) "never" else "granted=" + g + " failed=" + f
    }

    data class Progress(
        val running: Boolean,
        val done: Int,
        val total: Int,
        val newlyGranted: Int,
        val lastGranted: Int,
        val lastFailed: Int,
    )

    data class Result(
        val newlyGranted: Int,
        val failed: Int,
        val total: Int,
        val channelMissing: Boolean,
        val skipped: Boolean,
        val alreadyHad: Int,
    )
}

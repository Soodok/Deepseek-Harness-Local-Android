package app.dsh.mobile.engine

import app.dsh.mobile.R

import android.content.Context
import android.util.Log

/**
 * 运行权限模式与真机能力探测。
 *
 * 私有存储布局：
 *   filesDir/engine/       引擎运行时根（node + node_modules）
 *   filesDir/dsh-home/     用户资产（会话/凭证/配置）—— 提权模式下必须保护
 *
 * 三种模式：
 *  - NORMAL     默认。App 沙箱 uid 运行，引擎 node 以 App 权限执行，AI 命令仅限沙箱内。
 *  - SHIZUKU    AI 额外能以 adb 身份跑 shell（保活/杀进程），引擎本体仍 App 权限。
 *  - ROOT       引擎整体以 su(uid 0) 启动，AI 可全盘操作；必须两次警告 + 预备份 dsh-home。
 *
 * 注意：Shizuku 只能授予应用本体（binder/远程）能力，无法改变 fork 出的子进程 uid，
 *  因此 Shizuku 模式【不把引擎 node 提权】，而是给 AI 的执行工具注入 shizuku/adb 身份。
 */
enum class PrivMode { NORMAL, SHIZUKU, ROOT }

data class PrivCapability(
    val hasRoot: Boolean = false,     // 存在 su 且可执行（Magisk/KernelSU 等）
    val hasShizuku: Boolean = false,  // Shizuku 服务已授权（浅探测）
)

/** 权限模式存取 + 真机能力探测（纯检测，不做任何危险动作） */
object Privilege {

    private const val TAG = "Privilege"
    private const val PREFS = "dsh_priv"
    private const val KEY_MODE = "priv_mode"
    private const val KEY_ONBOARDED = "onboarded"

    // 常见 su 路径（存在其一即视为具备 Root 能力）
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su",
        "/sbin/su", "/vendor/bin/su", "/su/bin/su",
        // ⚠️ v1.2.112：Magisk 25+ 的 su 在 `magisk --path`（默认 /debug_ramdisk/su）——
        // 不在任何 PATH 目录里，裸 `su` 找不到（KernelSU 的 /system/bin/su 在 PATH，
        // 所以此前 KernelSU 正常、Magisk 用户反馈「root 检测不到」）
        "/debug_ramdisk/su",
        "/data/adb/magisk/su", "/data/adb/ksu/bin/su",
        "/system/bin/su.d", "/system/bin/busybox",
    )

    // 常见 Shizuku 服务名（存在授权即视为可用）
    private val SHIZUKU_PACKAGES = listOf(
        "moe.shizuku.privileged.api",          // Shizuku Server
        "moe.shizuku.manager",                 // Shizuku Manager
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): PrivMode = runCatching {
        PrivMode.valueOf(prefs(ctx).getString(KEY_MODE, PrivMode.NORMAL.name)!!)
    }.getOrDefault(PrivMode.NORMAL)

    fun setMode(ctx: Context, mode: PrivMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
        Log.i(TAG, "priv mode -> $mode")
    }

    fun isOnboarded(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONBOARDED, false)

    fun markOnboarded(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /**
     * Root 探测：**以能否真正执行命令为准**，不靠文件存在性。
     *
     * ## 为什么不能只看文件（v1.2.100 修，酷安用户反馈）
     * 旧实现在 stat 到任一常见 su 路径时就 `return true`，从不实际执行：
     *
     *     if (SU_PATHS.any { File(it).exists() }) return true   // ← 短路
     *     return ... "su" -c id ...                             // ← 永不执行
     *
     * 于是「su 文件在、但实际用不了」的设备被误判为可用 —— Android 15 用户反馈
     * 「授予 root 后检测不到 su」正是这一类：UI 说检测到了，随后所有 `su -c` 全失败。
     * 实测（模拟器）两种失败形态：
     *   · `su: invalid uid/gid '-c'` —— 该 su 是 toybox 版，**不认 `-c`**
     *   · `exec failed for su: Permission denied` —— 未授权 / SELinux 拒绝
     *
     * ## 现在的做法
     * 逐个尝试常见 su 调用语法，**必须拿到 uid=0** 才算可用：
     *   ① `su -c id`     —— Magisk / KernelSU 标准写法
     *   ② `su 0 id`      —— toybox su 的位置参数写法（上面那台 Android 15 的形态）
     *   ③ `su root -c id`—— 部分老版 SuperSU
     * 每种 2 秒超时。这会触发 Magisk 授权弹窗（首次属预期）。
     */
    private fun probeRoot(): Boolean {
        // 先看有没有 su 可用（省一次无谓的 fork）——但**不作为判定依据**
        val hasSuBinary = SU_PATHS.any { java.io.File(it).exists() }
        if (!hasSuBinary) {
            // 没有 su 文件：仍试一次 `su`（PATH 里可能有）
            return trySu("su", "-c", "id")
        }
        val candidates = listOf(
            arrayOf("su", "-c", "id"),
            arrayOf("su", "0", "id"),
            arrayOf("su", "root", "-c", "id"),
        )
        for (c in candidates) {
            if (trySu(*c)) {
                Log.i(TAG, "probeRoot: ok via '${c.joinToString(" ")}'")
                return true
            }
        }
        Log.i(TAG, "probeRoot: su binary present but unusable")
        return false
    }

    /**
     * 执行一次 su 命令并检查输出含 `uid=0`。
     * @return true 表示确实拿到 root
     */
    private fun trySu(vararg argv: String): Boolean = runCatching {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val done = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) {
            p.destroy()
            return@runCatching false
        }
        val out = p.inputStream.bufferedReader().readText()
        val ok = out.contains("uid=0")
        if (!ok && out.isNotBlank()) {
            // 记录失败原因（诊断用；不含敏感信息）
            Log.i(TAG, "trySu '${argv.joinToString(" ")}' -> ${out.trim().take(80)}")
        }
        ok
    }.getOrDefault(false)

    /**
     * 用**实测可用的** su 形式执行一条命令（v1.2.100）。
     *
     * 替代各处写死的 `ProcessBuilder("su", "-c", ...)` —— toybox su 不认 `-c`，
     * 那些调用点在同类设备上会静默失败（`invalid uid/gid '-c'`）。
     *
     * @param command 要执行的 shell 命令（调用方自行 shellQuote 拼接）
     * @param timeoutMs 等待超时
     * @return 标准输出+错误（合并）；null 表示无可用 su 或超时
     */
    fun runSu(command: String, timeoutMs: Long = 5_000L): String? = runCatching {
        val prefix = usableSuPrefix() ?: return null
        val suBin = findSu() ?: prefix.first()
        val argv = (listOf(suBin) + prefix.drop(1) + command).toTypedArray()
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroy()
            return null
        }
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (p.exitValue() != 0) {
            Log.i(TAG, "runSu exit=${p.exitValue()}: ${out.trim().take(120)}")
        }
        out
    }.getOrNull()

    /**
     * 本机桥的请求鉴权（v1.2.100）—— **AgentBridge 与 ShizukuHttpBridge 共用**。
     *
     * ## 为什么放在这里
     * 两个桥都监听 127.0.0.1，暴露的都是高权限能力（无障碍操作 / adb 身份执行），
     * 威胁模型相同，必须同一套校验。token 由 AgentBridge 每次启动随机生成、
     * 只经环境变量交给引擎子进程（不落盘），因此同设备第三方 App 拿不到。
     *
     * AgentBridge 内部先落地了这套逻辑；这里抽出来供 ShizukuHttpBridge 复用，
     * 避免"修了一个桥、漏了另一个"（审查发现的正是这个缺口）。
     *
     * ## fail-closed
     * token 未生成（异常状态）时不放行 —— 宁可让调用方报错，也不静默开放能力。
     *
     * @param headerLines 原始请求头行（第 0 行是请求行，会被跳过）
     */
    fun authorisedBridgeRequest(headerLines: List<String>): Boolean {
        val expected = AgentBridge.currentToken() ?: return false
        for (i in 1 until headerLines.size) {
            val h = headerLines[i]
            if (h.startsWith("X-DSH-Token:", ignoreCase = true)) {
                return h.substringAfter(":").trim() == expected
            }
        }
        return false
    }

    /**
     * 供 Root 模式启动前调用：返回**可用的** su 命令形式（含所需参数）。
     *
     * 旧实现只返回路径（`findSu()`），调用方固定拼 `su -c <cmd>`；
     * 而 toybox su 不认 `-c` → 引擎起不来。现在把「路径 + 参数形式」一起给出。
     *
     * @return 可直接用于 ProcessBuilder 的前缀参数，如 ["su","-c"] 或 ["su","0"]；
     *         无可用 su 时返回 null
     */
    fun usableSuPrefix(): List<String>? {
        // ⚠️ v1.2.112：**必须用 findSu() 的绝对路径**，不能用裸 "su"。
        // 裸名走 PATH，而 Magisk 的 su 不在任何 PATH 目录（/sbin/su 或
        // /debug_ramdisk/su）—— 此前 KernelSU 用户正常（其 /system/bin/su 恰在 PATH）、
        // Magisk 用户反馈「root 检测不到」（裸 su → ENOENT → 探针全失败）。
        val suBin = findSu() ?: run {
            Log.w(TAG, "usableSuPrefix: no su binary on SU_PATHS")
            return null
        }
        val forms = listOf(
            listOf(suBin, "-c"),
            listOf(suBin, "0"),
            listOf(suBin, "root", "-c"),
        )
        for (f in forms) {
            // ⚠️ v1.2.109（紧急回归修复）：探针命令必须**同时满足**两个条件：
            //   ① 只有经 shell 解释才能成功 —— 旧旧探针 `id` 是真实二进制，su 无论
            //      经不经 shell 都会成功，会把「不经 shell 直接 exec」的 su 形式误判
            //      可用，而那种形式下整条启动链（`cd … && export … && exec node …`）
            //      会被当成一个文件名 → ENOENT → 引擎起不来。
            //   ② **输出必须包含 `uid=0`** —— `trySu` 的判断逻辑是
            //      `out.contains("uid=0")`（见上）。v1.2.108 把探针改成 `cd / && true`
            //      虽满足 ① 却没有输出，**所有 su 形式都被判失败** → 引擎直接报
            //      `EngineStartException: no usable su`（issue #9：一加 13 /
            //      KernelSU 3.3 实测，Root 模式起不来，切普通模式正常）。
            // `cd / && echo uid=0`：`cd` 是 shell 内建（磁盘上没有这个可执行文件，
            // 直接 exec 必失败），`echo uid=0` 内建输出 `uid=0` —— 两个条件同时满足。
            val probe = f + listOf("cd / && echo uid=0")
            if (trySu(*probe.toTypedArray())) {
                Log.i(TAG, "usableSuPrefix: ${f.joinToString(" ")}")
                return f
            }
        }
        return null
    }

    /** 浅探测 Shizuku：Manager/Server 包是否安装（不代表已授权） */
    private fun probeShizukuInstalled(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return SHIZUKU_PACKAGES.any {
            runCatching { pm.getPackageInfo(it, 0) != null }.getOrDefault(false)
        }
    }

    fun probe(ctx: Context): PrivCapability = PrivCapability(
        hasRoot = probeRoot(),
        hasShizuku = probeShizukuInstalled(ctx),
    )

    /**
     * 轻量 Root **线索**（UI 变灰用）：仅 stat 常见 su 路径。
     *
     * ⚠️ 这只表示「可能有 root」，**不等于可用**（v1.2.100 酷安反馈的教训：
     * su 文件存在但实际不可用，UI 却显示已检测到）。真正判定必须用 [probeRoot]。
     * 保留它是因为每次读 UI 都真跑 su 会反复弹 Magisk 授权框。
     */
    fun rootAvailableMinimal(): Boolean = SU_PATHS.any { java.io.File(it).exists() }

    // ---- Shizuku 真实集成（m1.25）----
    // 官方 API（dev.rikka.shizuku:api 13.1.5）：ShizukuProvider 在 Manifest 声明后，
    // Shizuku.bindProvider 会在进程启动时完成 binder 握手；此后 pingBinder /
    // checkSelfPermission / requestPermission / newProcess 才可用。
    // requestPermission 才会弹授权对话框（用户报障「不能主动弹窗」的根因就是缺这套）。

    /** Shizuku server 是否在运行（用户已通过无线调试/Root 启动过 server） */
    fun shizukuServerRunning(): Boolean = runCatching {
        rikka.shizuku.Shizuku.pingBinder()
    }.getOrDefault(false)

    /** 本 app 是否已获得 Shizuku 授权 */
    fun shizukuGranted(): Boolean = runCatching {
        rikka.shizuku.Shizuku.checkSelfPermission() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku 总可用 = server 在跑 且 本 app 已授权 */
    fun shizukuUsable(): Boolean = shizukuServerRunning() && shizukuGranted()

    /**
     * 请求 Shizuku 授权（弹 Shizuku 的授权对话框）。
     * 调用方需先 Shizuku.addRequestPermissionResultListener 接收结果（见 Onboarding）。
     */
    fun requestShizukuPermission(requestCode: Int) {
        rikka.shizuku.Shizuku.requestPermission(requestCode)
    }

    /**
     * 以 Shizuku（adb uid 2000）身份执行 shell 命令——Shizuku 模式下 AI 的执行通道。
     * 官方链路：Shizuku.getBinder() → IShizukuService.newProcess(cmd, env, dir) →
     * IRemoteProcess 的 ParcelFileDescriptor 读输出（aidl 依赖提供接口）。
     * @return stdout+stderr 合并输出
     */
    fun shizukuExec(ctx: android.content.Context, cmd: String): String {
        val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(
            rikka.shizuku.Shizuku.getBinder()
        ) ?: throw IllegalStateException(ctx.getString(R.string.error_shizuku_binder))
        val rp = svc.newProcess(
            arrayOf("sh", "-c", cmd),
            arrayOf("PATH=/system/bin:/system/xbin"),
            null,
        )
        val out = rp.inputStream.let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }
        val err = rp.errorStream.let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }
        rp.waitFor()
        return out + err
    }

    /**
     * 已入 Root 模式时，对用户资产 dsh-home 做一次轻量备份（tar 到 filesDir/root-backup）。
     * 保护壳：即便 Root 引擎误改 dsh-home，用户仍可回滚。
     * @return 生成的备份文件绝对路径；失败返回 null
     */
    fun backupHome(ctx: Context): String? = runCatching {
        val home = EngineConfig.dshHome(ctx)
        if (!home.exists() || home.listFiles()?.isEmpty() == true) return null
        val outDir = java.io.File(ctx.filesDir, "root-backup").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val out = java.io.File(outDir, "dsh-home.$stamp.tar")
        // 用系统 tar 打包（root 模式下 tar 也在），失败留空返回
        val env = listOf(
            "PATH=${EngineConfig.engineRoot(ctx).absolutePath}/bin:/system/bin:/system/xbin",
            "TMPDIR=${EngineConfig.tmpDir(ctx).absolutePath}",
        )
        val pb = ProcessBuilder(
            listOf("/system/bin/tar", "-cf", out.absolutePath, "-C", home.absolutePath, ".")
        ).also { pb ->
            val e = pb.environment()
            env.forEach { kv -> val idx = kv.indexOf('='); if (idx > 0) e[kv.substring(0, idx)] = kv.substring(idx + 1) }
        }
        pb.start().waitFor()
        out.takeIf { it.isFile && it.length() > 0 }?.absolutePath
        .also {
            // 滚动保留最近 2 份（v1.2.22 事故：无清理 → 204 份 × 295MB = 57.6GB 堆爆存储；
            // 本意「一次轻量备份」，实际每次切 Root 都全量重拍）
            outDir.listFiles()
                ?.filter { it.name.startsWith("dsh-home.") && it.name.endsWith(".tar") }
                ?.sortedBy { it.name }
                ?.dropLast(2)
                ?.forEach { it.delete() }
        }
    }.onFailure { Log.w(TAG, "backupHome failed: ${it.message}") }.getOrNull()

    /** 探测到的 su 可执行路径（首个存在的），供 Root 模式启动引擎用 */
    fun findSu(): String? = SU_PATHS.firstOrNull { java.io.File(it).exists() }

    /**
     * 自愈：把 dsh-home 递归 chown 回 app uid（Root 整体提权会new进程把 dsh-home 里新建/写过的
     * 文件变成 root 属主，之后任何非 Root 启动都会 EACCES 读不了——m1.26 引擎崩溃根因）。
     * 仅当设备有 su 时执行（su -c chown），否则跳过；失败不报错仅告警。
     * @return 是否需要 chown（即有文件非 app uid 属主）
     */
    fun dshHomeNeedsOwnershipFix(ctx: Context): Boolean {
        val home = EngineConfig.dshHome(ctx)
        return runCatching {
            if (findSu() == null) return false
            // 用实测可用的 su 形式（v1.2.100：toybox su 不认 -c）
            val out = runSu(
                "find " + shellQuote(home.absolutePath) +
                    " -not -user " + android.os.Process.myUid() + " -print -quit",
                timeoutMs = 3_000L,
            ) ?: return false
            out.isNotBlank()
        }.getOrDefault(false)
    }

    /** 执行 chown（Root 模式下自愈），成功返回 true */
    fun fixHomeOwnership(ctx: Context): Boolean {
        val home = EngineConfig.dshHome(ctx)
        return runCatching {
            if (findSu() == null) return false
            val uid = android.os.Process.myUid()
            val out = runSu(
                "chown -R $uid:$uid " + shellQuote(home.absolutePath),
                timeoutMs = 5_000L,
            ) ?: return false
            Log.i(TAG, "fixHomeOwnership: chown dsh-home to uid $uid (${out.trim().take(60)})")
            true
        }.getOrDefault(false)
    }

    /** POSIX sh 单引号转义（供 su -c 拼接） */
    private fun shellQuote(s: String): String {
        if (!s.contains(Regex("[\\s\"'\\\\]"))) return "'$s'"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}

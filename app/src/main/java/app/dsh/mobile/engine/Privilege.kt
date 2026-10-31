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
     * 统一提权执行（v1.2.173）：**Shizuku 优先 → su 兜底**。
     *
     * ## 为什么要这个入口（实测踩坑）
     * 本机是 **Shizuku 模式**，`engine/bin/su` 是一个会主动拒绝的 shim
     * （`su gate: priv mode != ROOT, deny su`）→ `runSu()` 永远失败。
     * 而所有依赖提权的自愈逻辑（属主修复、杀残留进程）此前都只走 `runSu`
     * → **在 Shizuku 模式下全部静默失效**（实测症状：重装 APK 后 uid 变化，
     * 插件文件属主过期 → app 读不了 → 引擎报 `failed to import`；局域网代理
     * 同样起不来）。
     *
     * ## 为什么 Shizuku 能修 app 私有目录
     * Shizuku 以 **adb(2000) 身份**执行命令，shell 对 `/data/user/0/<pkg>/` 有
     * 访问权（可 chown/chmod/kill），这正是我们需要的，且**不需要 ROOT**。
     *
     * @return 命令输出；两条通道都不可用时返回 null
     */
    fun runPrivileged(ctx: android.content.Context, command: String): String? {
        // ① Shizuku 通道（本机主路径）
        val viaShizuku = runCatching {
            if (shizukuUsable()) {
                val out = shizukuExec(ctx, command)
                Log.i(TAG, "runPrivileged via shizuku: ${out.trim().take(80)}")
                return out
            }
        }.onFailure { Log.w(TAG, "runPrivileged shizuku failed: ${it.message}") }
        Unit
        // ② su 兜底（ROOT 模式 / 普通设备）
        runSu(command, timeoutMs = 8_000L)?.let {
            Log.i(TAG, "runPrivileged via su: ${it.trim().take(80)}")
            return it
        }
        Log.w(TAG, "runPrivileged: neither shizuku nor su available")
        return null
    }

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
    /**
     * 申请 Shizuku 授权（v1.2.158 隐私修复，issue 反馈「拒绝后仍反复弹窗」）。
     *
     * 根因：设置页/引导页的**状态刷新**路径也在无条件调用本函数，而授权结果回调
     * 又会触发刷新 → 用户每拒绝一次就再弹一次，直到同意为止。
     * 现在：**拒绝即尊重** —— 记录用户已拒绝，之后只有显式操作
     * （用户点授权入口，explicit = true）才会再次弹窗。
     */
    fun requestShizukuPermission(requestCode: Int, ctx: android.content.Context? = null,
                                 explicit: Boolean = false) {
        if (ctx != null) {
            if (!explicit && shizukuDenied(ctx)) return     // 尊重拒绝：不自动弹
            if (explicit) markShizukuDenied(ctx, false)     // 用户主动再要 → 重置标记
        }
        runCatching { rikka.shizuku.Shizuku.requestPermission(requestCode) }
    }

    private const val PREFS_PRIV = "dsh_priv_state"
    private const val KEY_SHIZUKU_DENIED = "shizuku_denied"

    /** 用户是否已明确拒绝过 Shizuku 授权（拒绝即尊重，不再自动弹窗） */
    fun shizukuDenied(ctx: android.content.Context): Boolean =
        ctx.getSharedPreferences(PREFS_PRIV, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_SHIZUKU_DENIED, false)

    fun markShizukuDenied(ctx: android.content.Context, denied: Boolean) {
        ctx.getSharedPreferences(PREFS_PRIV, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHIZUKU_DENIED, denied).apply()
    }

    /**
     * 以 Shizuku（adb uid 2000）身份执行 shell 命令——Shizuku 模式下 AI 的执行通道。
     * 官方链路：Shizuku.getBinder() → IShizukuService.newProcess(cmd, env, dir) →
     * IRemoteProcess 的 ParcelFileDescriptor 读输出（aidl 依赖提供接口）。
     *
     * ## ⚠️ 超时是必须的（v1.4.9，用户反馈「等几十分钟引擎都准备不好」）
     *
     * 旧实现三处都是**无界**的：`readText()` 阻塞到流关闭、`rp.waitFor()` 阻塞到
     * 进程退出。这在启动热路径上是致命的 —— 它被
     * `dshHomeNeedsOwnershipFix` / `fixHomeOwnership` / `reclaimPort` 调用，
     * 而这些都在监督循环里**每轮启动**执行。
     *
     * 一旦被调用的命令卡住（`find` 卡在不可达的挂载点、`chown -R` 碰上正在
     * 被写入的大目录、su/Shizuku 服务端自己卡死），监督循环就永久停在这一步：
     * 引擎不 spawn、状态不前进、退避与 MAX_RESTART 完全不会触发 —— 用户看到的是
     * 「准备阶段无限转圈」，且没有任何日志（连 boot step 都写不出来）。
     *
     * 现在：读流放到后台线程并设读超时，主线程按 deadline 等；超时后返回已读到的
     * 部分输出并尽力结束进程。**超时不当成失败**（返回部分输出），避免把「环境慢」
     * 误判成「命令失败」进而触发更重的自愈分支。
     *
     * @param timeoutMs 整个命令的预算（含读输出）。默认 10s：启动热路径能接受，
     *                  也远大于正常情况的毫秒级。
     * @return stdout+stderr 合并输出；超时返回已读到的部分
     */
    fun shizukuExec(
        ctx: android.content.Context,
        cmd: String,
        timeoutMs: Long = 10_000L,
    ): String {
        val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(
            rikka.shizuku.Shizuku.getBinder()
        ) ?: throw IllegalStateException(ctx.getString(R.string.error_shizuku_binder))
        val rp = svc.newProcess(
            arrayOf("sh", "-c", cmd),
            arrayOf("PATH=/system/bin:/system/xbin"),
            null,
        )
        val deadline = System.currentTimeMillis() + timeoutMs
        // 读流放后台：readText() 会一直阻塞到 EOF，而 EOF 只在进程退出、管道关闭后
        // 才出现。若进程卡住，主线程绝不能跟着卡。
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                for (stream in arrayOf(rp.inputStream, rp.errorStream)) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(stream)
                        .bufferedReader().use { br ->
                            val buf = CharArray(4096)
                            while (true) {
                                val n = br.read(buf)
                                if (n <= 0) break
                                synchronized(sb) { sb.append(buf, 0, n) }
                            }
                        }
                }
            }
        }.apply { isDaemon = true; name = "dsh-shizuku-read"; start() }

        // 等进程退出或超时；两者都不阻塞主监督循环。
        // ⚠️ IRemoteProcess.waitFor() 是**无参数**的阻塞调用（Shizuku AIDL 接口就
        // 这么定义），没有 Java Process 那样的 waitFor(timeout, unit) 重载。
        // 因此这里用「后台线程调用 waitFor + 主线程按 deadline join」来做超时：
        // 后台线程卡住也无所谓（daemon），主线程到点就返回。
        val waiter = Thread {
            runCatching { rp.waitFor() }
        }.apply { isDaemon = true; name = "dsh-shizuku-wait"; start() }
        waiter.join(maxOf(1L, deadline - System.currentTimeMillis()))
        val finished = !waiter.isAlive
        if (!finished) {
            Log.w(TAG, "shizukuExec timed out after ${timeoutMs}ms; cmd=${cmd.take(120)}")
            runCatching { rp.destroy() }
        }
        // 收尾：给读线程一个很短的窗口把缓冲冲出来，然后无论它在不在都返回
        runCatching { reader.join(300L) }
        val out = synchronized(sb) { sb.toString() }
        return if (finished) out else out + "\n[timeout after ${timeoutMs}ms]"
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
            env.forEach { kv ->
                val idx = kv.indexOf('=')
                if (idx > 0) e[kv.substring(0, idx)] = kv.substring(idx + 1)
            }
        }
        // ⚠️ v1.4.9（用户反馈「等几十分钟引擎都准备不好」）：这次 tar 在 Root 模式下
        // **每轮启动都会跑**，旧实现用无参数的 waitFor() 等它结束。dsh-home 逐渐变大
        // （会话、workspaces），或碰上存储慢/被占 I/O，tar 可以跑很久 —— 期间整个
        // 监督循环停在 spawnEngine 里，退避与 MAX_RESTART 都不会触发，界面就是
        // 「无限准备中」。备份是**附加保护**，绝不能拖住启动：给 60s 预算，
        // 超时就放弃本次备份（下次启动再试），并明确写日志。
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val done = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) {
            runCatching { proc.destroy() }
            Log.w(TAG, "backupHome: tar timed out after 60s — skipping this backup (boot continues)")
            runCatching { out.delete() }
            return null
        }
        if (proc.exitValue() != 0) {
            Log.w(TAG, "backupHome: tar exit=${proc.exitValue()} — backup discarded")
            runCatching { out.delete() }
            return null
        }
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
     * 检测 dsh-home / engine 下是否有「App 用不了」的条目（v1.3.1）。
     *
     * ## 三类损坏，检测方式各不相同
     *  ① **权限位被剥**（`chmod 000`）—— 实测最隐蔽：文件属主仍是 app，`File.canRead()`
     *     在 API 上会因为拿不到 stat 而返回 false（看似够用），但 Android 的
     *     `canRead()` 语义包含 root/权限位判断差异，**不能只靠它**。这里改用
     *     「实际尝试打开」判定：能 open 才算可用。
     *  ② **属主变成 root**（Root 引擎以 uid 0 写出的文件）—— App 侧看不出来（stat 可能
     *     也被拒），必须靠特权通道 `find -not -user <uid>` 查。
     *  ③ **SELinux 标签错** —— App 侧无法直接判断（`getfilecon` 不可用），
     *     同样交给特权通道的 restorecon 兜。
     *
     * ⚠️ 旧实现在「没有 shizuku/su」时直接 return false（放弃）——那是错的：
     * ①②里**属于 app 自己的那部分**（绝大多数）不需要任何特权就能修，
     * 放弃等于把用户推向「删掉重装」（用户实测的原话）。
     */
    fun dshHomeNeedsOwnershipFix(ctx: Context): Boolean {
        val home = EngineConfig.dshHome(ctx)
        return runCatching {
            val engine = java.io.File(home.parentFile, "engine")
            // ① 自己就能判定的部分：实际尝试读取，读不了就是要修
            // 只探 home 树：engine 由 RuntimeInstaller 自管，且引擎正在其中运行
            if (hasUnreadableEntries(ctx, listOf(home))) {
                Log.w(TAG, "ownership check: found entries this app cannot read (mode/owner damage)")
                return true
            }
            // ② 需要特权才能判定的部分：属主是否属于别的 uid。
            //    v1.4.45（issue #74）：pnpm 运行时状态（按 uid 加后缀的锁目录、
            //    metadata 缓存）是**自管理**的，不参与 chown——把它们 prune 掉，
            //    否则另一 uid 留下的锁每轮都命中 `-not -user`、每轮检出、修复却
            //    跳过，永不收敛（用户实测「每次重启都复发」）。
            //    注意 prune 必须用**正向** `-path`：`-not -path L -prune -o X` 里
            //    L 命中时左侧为假、直接落到 `-o` 右侧，prune 形同虚设。
            //    它们要不要清理由 ③（pnpmForeignStateProbe）单独判定。
            val uid = android.os.Process.myUid()
            val euid = engineUid(ctx)
            val out = runPrivileged(
                ctx,
                "find " + shellQuote(home.absolutePath) + " -xdev " +
                    pnpmSelfManagedPruneExpr(home) + "-not -user " + uid + " -print -quit 2>/dev/null; " +
                    pnpmForeignStateProbe(home, euid),
            )
            if (out == null) {
                Log.i(TAG, "ownership check: no privileged channel; app-own damage check only")
                // v1.4.70：无特权通道时旧版直接返回 false —— 于是「装插件报
                // EACCES / Failed to remove node_modules/.bin」这类故障在普通
                // 手机上永远检不出，维护页只会说「没有发现异常文件」。
                // find -user 是本 app 权限就能跑的（目录可读就能列出别的属主），
                // 补上这一层判断，至少能如实告诉用户「有坏文件、需要 root 才能修」。
                if (hasForeignOwnedFiles(home)) {
                    Log.w(TAG, "ownership check: found foreign-owned entries under profiles/ (needs privilege to fix)")
                    return true
                }
                return false
            }
            val bad = out.isNotBlank()
            if (bad) Log.w(TAG, "ownership check: found files not owned by $uid → will chown")
            // v1.4.70：特权通道的 find 覆盖整棵 home，但若它被系统策略裁剪、
            // 或 profiles/ 恰好不在其视角内，app 侧这一遍仍能兜住插件目录。
            bad || hasForeignOwnedFiles(home)
        }.getOrDefault(false)
    }

    /** 当前**引擎**的有效 uid：root 模式引擎以 0 跑，其余以 app uid 跑。 */
    private fun engineUid(ctx: Context): Int =
        if (getMode(ctx) == PrivMode.ROOT) 0 else android.os.Process.myUid()

    private val pnpmLockBases = listOf(
        "pnpm-store-operation-locks", "pnpm-dir-locks", "pnpm-modules-swaps",
    )
    private const val PNPM_METADATA = "/.cache/pnpm/v12/metadata"

    /**
     * pnpm 自管理状态（三棵锁树 + metadata）的正向 `-path` 列表，以
     * ` -prune -o ` 结尾——直接拼在 find 的路径参数后面。
     * `-path` 的 glob 里 `*` 可以跨 `/`，所以锁树**内部**文件同样命中、同样被剪。
     */
    private fun pnpmSelfManagedPruneExpr(home: java.io.File): String =
        pnpmLockBases.joinToString(" -o ") {
            "-path " + shellQuote(home.absolutePath + "/.cache/" + it + "-*")
        } + " -o -path " + shellQuote(home.absolutePath + PNPM_METADATA + "*") + " -prune -o "

    /**
     * 探测「别的 uid 留下的」pnpm 运行时状态（issue #74）：
     *   · 锁目录后缀 ≠ 当前引擎 uid → 输出该目录名；
     *   · 非 root 引擎时 metadata 属主是 root → 输出该路径。
     * 命中任何一行都表示启动时要清理。无匹配则无输出。
     */
    private fun pnpmForeignStateProbe(home: java.io.File, euid: Int): String {
        val cacheQ = shellQuote(home.absolutePath + "/.cache/")
        val globs = pnpmLockBases.joinToString(" ") { "$cacheQ$it-*" }
        val metaCheck = if (euid != 0) {
            "find " + shellQuote(home.absolutePath + PNPM_METADATA) + " -user 0 -print -quit"
        } else {
            ":"
        }
        // 未命中 glob 时 $d 是字面量，[ -d ] 挡掉；`*-<euid>` 是自己 uid 的锁，保留
        return "for d in $globs; do [ -d \"\$d\" ] || continue; " +
            "case \"\$d\" in *-$euid) ;; *) echo \"\$d\" ;; esac; done; $metaCheck"
    }

    /**
     * 扫一遍目录，找出「App 读不了」的条目。
     *
     * ⚠️ 必须**实际 open 一次**，不能只看 `canRead()`：权限位是 `000` 时属性检查
     * 的行为在 Android 各版本不一致，实测（模拟器，`.runtime-version` chmod 000）
     * `canRead()` 检出不稳定，而真实影响是 `RuntimeInstaller` 读版本戳直接 EACCES。
     * 用「能否打开」作为判据与真实故障完全对齐。
     *
     * 限深 + 限量：这是启动热路径，不能为了体检把 20 万文件的 runtime 全读一遍。
     * 只查**关键文件与目录本身**（够覆盖真实故障面：版本戳、插件、配置、guardian 元数据）。
     */
    private fun hasUnreadableEntries(ctx: Context, roots: List<java.io.File>): Boolean {
        val probes = mutableListOf<java.io.File>()
        for (root in roots) {
            if (!root.exists()) continue
            probes += root
        }
        // 关键文件（被启动链路直接读取的）
        probes += java.io.File(roots[1], ".runtime-version")          // engine/.runtime-version
        probes += java.io.File(roots[0], "AGENTS.md")                // dsh-home/AGENTS.md
        probes += java.io.File(ctx.filesDir, "profile-guardian.meta") // guardian 元数据
        // v1.4.70：插件安装现场。pnpm 以 root 跑过一次就会被改成 root:600，
        // 而上面几项关键文件可能全都正常 —— 用户看到的是「装插件报 EACCES」
        // 却在维护页查不出任何异常。这几项直接对齐两个真实报错：
        //   package.json.lock        —— EACCES, open '.../package.json.lock'
        //   node_modules/.bin        —— Failed to remove .../node_modules/.bin
        //   node_modules 与 profiles/web 本身 —— 两个报错的直接宿主
        val webProfile = java.io.File(roots[0], "profiles/web")
        probes += webProfile
        probes += java.io.File(webProfile, "package.json")
        probes += java.io.File(webProfile, "node_modules")
        probes += java.io.File(webProfile, "node_modules/.bin")
        for (p in probes) {
            if (!p.exists()) continue
            val ok = runCatching {
                if (p.isDirectory) {
                    p.listFiles() != null      // 目录：能否列出（无 x/r 位会返回 null）
                } else {
                    p.inputStream().use { it.read() }   // 文件：能否真正读
                    true
                }
            }.getOrDefault(false)
            if (!ok) return true
        }
        return false
    }

    /**
     * 快速属主扫描：`profiles/web` 下有没有非本 app uid 的条目（v1.4.70）。
     *
     * ## 为什么必须单独扫，光看关键文件不够
     *
     * [hasUnreadableEntries] 只探测根目录、`.runtime-version`、`AGENTS.md`、
     * guardian 元数据这几项 —— 够覆盖「启动链路读不动」那类故障，但**漏掉**
     * 插件安装现场：pnpm 用 root 身份跑过一次后，`node_modules/.bin`、
     * `node_modules/pdfjs-dist/` 等会被改成 `root:root 600`，而
     * `profiles/web` 这一层目录本身的属主没变、可读性检查也通得过。
     *
     * 真实报错（真机 logcat，宝宝第 9 次反馈）：
     *
     *     Error: EACCES: permission denied, open '.../package.json.lock'
     *     ERR_PNPM_PACKAGE_MANAGER_REMOVE_MODULES_DIR
     *       ╰─▶ Failed to remove .../node_modules/.bin: Permission denied
     *
     * 只查关键文件时这里返回 false → 维护页提示「没有发现异常文件」→ 用户
     * 以为按钮坏了，实际是一堆文件真的坏着。
     *
     * ⚠️ 用 `find -user <uid>` 而不是遍历后再取 `owner`：20 万文件的 runtime
     * 在启动热路径上不能全读 stat；`find` 由 toybox 在 native 层跑，快一个量级。
     * 限定 `profiles/` 子树（插件安装只发生在这里），并排除 pnpm 按 uid 自管理
     * 的锁树（那本来就该是别的 uid）。
     */
    private fun hasForeignOwnedFiles(home: java.io.File): Boolean {
        val uid = android.os.Process.myUid()
        val profiles = java.io.File(home, "profiles")
        if (!profiles.isDirectory) return false
        return runCatching {
            val prune = pnpmSelfManagedPruneExpr(home)
            // `! -user <uid> -print -quit`：命中第一个就退出，热路径上几乎零成本
            val cmd = listOf(
                "/system/bin/sh", "-c",
                "find " + shellQuote(profiles.absolutePath) + " -xdev " + prune +
                    "-xdev ! -user " + uid + " -print -quit 2>/dev/null",
            )
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            Log.w(TAG, "hasForeignOwnedFiles: exit=${p.exitValue()} hit=${out.isNotEmpty()} uid=$uid out=${out.take(200)}")
            out.isNotEmpty()
        }.getOrElse {
            Log.w(TAG, "hasForeignOwnedFiles failed: ${it.message}")
            false
        }
    }

    /**
     * 修复 dsh-home / engine 的属性（v1.3.1 扩展到「所有模式 + SELinux 上下文」）。
     *
     * 做的三件事（都幂等）：
     *  ① `chown -R <appUid>` —— 归还原主（Root 引擎写出的文件属主是 root）；
     *  ② `chmod -R u+rwX` —— 补回读写与目录/可执行位（Root 下 umask 可能剥掉）；
     *  ③ `restorecon -R` —— **重置 SELinux 上下文**。这一条是「删掉重装就恢复」在
     *     系统层面替用户做的事：重装会重新打标签。Root 引擎（非 app 域）创建的文件
     *     可能带错标签，属主修对也依然 open 被拒。
     *
     * ⚠️ **分层修复**（v1.3.1，实测驱动）：
     *   ① 先自己修 —— 对**属于本 app** 的条目（绝大多数故障）不需要任何特权：
     *      实测「chmod 000 的文件，属主仍是 app，直接 chmod 600 即恢复」。
     *      这一步让**无 su / 无 Shizuku 的普通设备也能自愈**（旧实现遇到这种情况
     *      直接放弃，把用户推向「删掉重装」）。
     *   ② 再走特权通道 —— 处理自己改不动的（属主已是 root、跨 uid 的目录）。
     *      用 [runPrivileged]（Shizuku 优先）退回 su，附带 restorecon 修 SELinux 标签。
     * 只要①成功就算修好；②失败不影响返回值（属主问题确实需要特权，但不该阻断）。
     */
    fun fixHomeOwnership(ctx: Context): Boolean {
        val home = EngineConfig.dshHome(ctx)
        return runCatching {
            val uid = android.os.Process.myUid()
            val engine = java.io.File(home.parentFile, "engine")

            // ① 自己修：递归补回 u+rwX（对目录补 x 才能继续往下走）
            val selfFixed = runCatching {
                var touched = 0
                listOf(home, engine).forEach { root -> touched += repairOwnedTree(root) }
                Log.i(TAG, "fixHomeOwnership(self): restored permissions on $touched entr(ies)")
                touched
            }.getOrDefault(-1)

            // ② 特权通道：修属主 + SELinux 标签（自己做不到的部分）
            val homeQ = shellQuote(home.absolutePath)
            val engineQ = shellQuote(engine.absolutePath)
            // ⚠️ v1.2.171 实测补充：必须**同时**修 engine/android-plugins。
            // 每次重装 APK，Android 会分配**新的 app uid**，而 `engine/` 下的插件文件
            // （priv-mode / bridge-token / fast-mode）属主还是旧 uid + 权限 600 →
            // app 自己的 node 进程（新 uid）**读不了插件** → 引擎日志
            // `android-fast-mode: failed to import` / EACCES → 插件功能全哑。
            // 实测症状：AI 找不到 fast_mode 工具、局域网代理进程起不来。
            // ⚠️ v1.4.34（issue #65）：root 模式下**不能**把 `.cache` 下的 pnpm
            // 锁目录 chown 回 app uid。
            //
            // 现象：以 root 运行时（DSH_ANDROID_PRIV_MODE=root），pnpm 把操作锁建在
            // `$HOME/.cache/pnpm-store-operation-locks-<uid>` 这类**按 uid 分后缀**的
            // 目录里。本函数原本对整棵 dsh-home 递归 chown，会连 `...-locks-0`（root
            // 那一套）一起改成 app uid；随后 root 身份的 pnpm 判定「锁目录必须是当前
            // 用户拥有的真实目录」不成立，于是安装/卸载全部失败：
            //   ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK: lock directory must be a real
            //   directory owned by the current user
            // 用户每次重启引擎都会复发（因为启动即 chown）。
            //
            // 修法（v1.4.35，issue #65 + #67）：**chown 时直接排除整棵 pnpm 锁树**。
            //
            // v1.4.34 的做法是「先递归 chown 全树，再把锁目录本身改回原属主」，
            // 漏了一点：`chown "$n:$n" "$d"` 没有 `-R`，目录里的锁文件仍是 app uid。
            // 于是 pnpm 的报错从「lock directory must be a real directory owned by
            // the current user」变成「lock must be a **regular file** owned by the
            // current user」——问题没解决，只是换了一句话（issue #67）。
            //
            // 现在的做法更干净：这些锁目录**本来就不属于 App 数据**（是 pnpm 按运行
            // 身份自己管理的运行时状态），递归遍历时整棵跳过。用 `-path ... -prune -o`
            // 实现：命中锁树就不再下降，其余照常处理。
            //
            // 这样无论 root 还是 app uid 运行，各自那套锁都不被碰，天然正确。
            //
            // v1.4.45（issue #74）补充：`.cache/pnpm/v12/metadata` 同样是 pnpm
            // 自管理的缓存，也**不参与 chown**——否则 root 模式每轮把它 chown 成
            // app uid、root pnpm 又写回 root 属主，翻烧饼不收敛。它的清理在下面
            // 按 engineUid 走删除（非 root 引擎时），与锁树同一策略。
            val prunePatterns = listOf(
                "/.cache/pnpm-store-operation-locks-*",
                "/.cache/pnpm-dir-locks-*",
                "/.cache/pnpm-modules-swaps-*",
                "/.cache/pnpm/v12/metadata*",
            )
            // 不用分组括号：`-path A -o -path B -o -path C` 与 `-prune -o -exec`
            // 的优先级天然正确（-o 左结合，-prune 只作用于紧邻的 -path），
            // 也避免了在 shell 里转义括号带来的可移植性问题（toybox find 差异）。
            val homePlain = home.absolutePath
            val pruneExpr = buildString {
                prunePatterns.forEachIndexed { i, pat ->
                    if (i > 0) append(" -o ")
                    append("-path ").append(shellQuote(homePlain + pat))
                }
                append(" -prune -o ")
            }
            val cmd = buildString {
                // ⚠️ v1.4.46（群友 dsh-root-doctor 实测经验，v0.2.0）：
                // **必须加 -xdev**。susfs / 隐藏挂载环境下，无界遍历会跨进
                // 隐藏挂载点并卡死（对方实测：唯一安全的遍历是 `find $F/dsh-home -xdev`，
                // ~20ms 返回；去掉 -xdev 会挂住）。这里同时挂 -xdev 保命。
                //
                // 另：**不扫 engine 本体** —— 引擎就运行在里面，对它递归 chown
                // 既有风险（正在执行的 .so/二进制被改元数据）又没必要（engine
                // 由 RuntimeInstaller 自己管属主）。只保留 home 树。
                append("find ").append(homeQ).append(' ')
                append("-xdev ")
                append(pruneExpr)
                append("-exec chown ").append(uid).append(':').append(uid)
                append(" {} + 2>/dev/null; ")
                append("chmod -R u+rwX ").append(engineQ).append(" 2>/dev/null; ")
                // ── v1.4.45（issue #74）：按**当前引擎 uid**清理 pnpm 自管理状态 ──
                //
                // 上面 -prune 只保证 chown 不碰锁树；但换模式后**另一 uid 留下的**
                // 状态就是垃圾：
                //   · root 引擎写的 `...-locks-0`，普通模式 pnpm 判「锁目录必须属
                //     当前用户」→ ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK（#74）；
                //   · root 写的 `.cache/pnpm/v12/metadata` 属主 root，普通 uid 写不
                //     进 → EACCES。
                // #65 的教训（不能把 root 的锁 chown 给 app）+ #74 的教训（也不能把
                // root 的锁留在普通模式）合起来：谁的 uid 不是当前引擎 uid 就删。
                // 这些全是可重建缓存（pnpm 按当前身份重建自己的那套）。删除放在
                // chown 之后——此时 `.cache` 父目录已归 app uid。
                //
                // ⚠️ engineUid 只取决于**引擎进程**的运行身份：root 模式=0（保留
                // `-0` 锁，#65 语义），普通/Shizuku=app uid（删 `-0`，#74 语义）。
                val euid = engineUid(ctx)
                append("for d in ")
                pnpmLockBases.forEachIndexed { i, base ->
                    if (i > 0) append(' ')
                    append(shellQuote(home.absolutePath + "/.cache/")).append(base).append("-*")
                }
                append("; do [ -d \"\$d\" ] || continue; case \"\$d\" in *-")
                append(euid)
                append(") ;; *) rm -rf \"\$d\" ;; esac; done; ")
                if (euid != 0) {
                    // metadata 是 uid 无关的可重建缓存：非 root 引擎遇到 root 属主
                    // 只能删（chown 会被下一轮 root 重写，删了让本 uid 重建才收敛）
                    append("rm -rf ").append(shellQuote(home.absolutePath + PNPM_METADATA))
                        .append(" 2>/dev/null; ")
                }
                // SELinux 标签：restorecon 各设备 toybox 差异大（缺失/行为不同），
                // 先跑它；再用**本 app files 目录的真实 context** 做 chcon -R 兜底
                // （类别集 c512,c768 之类因设备而异，不能写死——#74 用户实测）。
                append("restorecon -R ").append(homeQ).append(" 2>/dev/null; ")
                append("restorecon -R ").append(engineQ).append(" 2>/dev/null; ")
                append("CTX=\$(stat -c '%C' ")
                    .append(shellQuote(home.parentFile!!.absolutePath))
                    .append(" 2>/dev/null); ")
                append("[ -n \"\$CTX\" ] && chcon -R \"\$CTX\" ")
                    .append(homeQ).append(' ').append(engineQ).append(" 2>/dev/null; ")
                // 自检（#74 第 4 步）：以 app uid 真实读一次被 root 写过的关键文件，
                // READ-OK / READ-DENIED 写进日志，远程排障时一眼定位是哪层挡路。
                append("S=\$(su ").append(uid).append(" -c cat ")
                    .append(shellQuote(home.absolutePath + "/profiles/web/package.json"))
                    .append(" 2>/dev/null | head -c 1); ")
                append("case \"\$S\" in ?*) echo ISLAND-SELFTEST-READ-OK ;; *) echo ISLAND-SELFTEST-READ-DENIED ;; esac; ")
                // 审计（#74 第 5 步）：清单外 root 残留计数（非 root 引擎时才有意义）。
                // 排除 pnpm 自管理状态（锁树 + metadata），其余 root 属主都算残留。
                if (euid != 0) {
                    val auditExcl = pnpmLockBases.joinToString(" ") {
                        "-not -path " + shellQuote(home.absolutePath + "/.cache/" + it + "-*")
                    } + " -not -path " + shellQuote(home.absolutePath + PNPM_METADATA + "*")
                    append("echo ISLAND-ROOT-LEFTOVER=\$(find ").append(homeQ)
                        .append(" -xdev -user 0 ").append(auditExcl)
                        .append(" -print 2>/dev/null | wc -l); ")
                }
                append("echo ok")
            }
            val out = runPrivileged(ctx, cmd)
            if (out == null) {
                // 无特权通道：只要「自己修」动过手就仍有救（属主类问题才必须特权）
                Log.w(TAG, "fixHomeOwnership: no privileged channel; self-repair only (touched=$selfFixed)")
                return selfFixed > 0
            }
            // 自检与审计结果（issue #74）单独立日志行，远程排障直接 grep：
            //   ISLAND-SELFTEST-READ-OK/DENIED —— app uid 能否真实读到关键文件
            //   ISLAND-ROOT-LEFTOVER=<n>      —— 清单外 root 属主残留计数
            out.lineSequence().filter { it.startsWith("ISLAND-") }.forEach { Log.i(TAG, "fixHomeOwnership: $it") }
            Log.i(TAG, "fixHomeOwnership done via privileged channel")
            true
        }.getOrDefault(false)
    }

    /** POSIX sh 单引号转义（供 su -c 拼接） */
    private fun shellQuote(s: String): String {
        if (!s.contains(Regex("[\\s\"'\\\\]"))) return "'$s'"
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /**
     * 递归修复「属于本 app」的目录树权限（v1.3.1）。
     *
     * ⚠️ 不能直接用 `walkTopDown()`：它在遇到**无权限的目录**时列不出子项
     * （`listFiles()` 返回 null），于是"深层坏文件修不到"——而这正是
     * `chmod 000 <dir>` 之后的现场。这里改成「先修自己、再列子项」的自顶向下递归：
     * 目录补上 u+rx 之后就能继续往里走。
     *
     * @return 实际成功改了权限的条目数（无法修改的跳过，不抛）
     */
    private fun repairOwnedTree(root: java.io.File, depth: Int = 0): Int {
        if (depth > 12) return 0            // 防符号链接成环 / 异常深树
        if (!root.exists()) return 0
        var touched = 0
        val isDir = root.isDirectory
        // ⚠️ 目录必须 **rwx 三者都补**，缺一不可：
        //   · r 才列得出子项、x 才进得去（少了就「目录打不开」）
        //   · **w 必须补**：漏掉这一步会留下 `dr-x------` 这种「能读不能写」的目录，
        //     引擎往里写 session/profile 立刻 EACCES（实测：只补 r+x 后 profiles/
        //     变成 dr-x------，看起来修好了、其实是把故障推迟到写入时）。
        // 文件补 rw（执行位对数据文件无意义，不主动加，避免误放行脚本）。
        val changed = if (isDir) {
            val r = root.setReadable(true, true)
            val w = root.setWritable(true, true)
            val x = root.setExecutable(true, true)
            r || w || x
        } else {
            val r = root.setReadable(true, true)
            val w = root.setWritable(true, true)
            r || w
        }
        if (changed) touched++
        if (!isDir) return touched
        val children = runCatching { root.listFiles() }.getOrNull() ?: return touched
        for (c in children) touched += repairOwnedTree(c, depth + 1)
        return touched
    }
}

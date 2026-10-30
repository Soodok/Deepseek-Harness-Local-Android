package app.dsh.mobile.engine

import app.dsh.mobile.R

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 引擎监督器：冷启动 → 健康检查 → 运行中 → 崩溃退避重启 的完整状态机。
 *
 * 状态流转：
 *   Idle → Installing → Starting → Healthy(port)
 *        ↘ Backoff(delay, attempt) ↘ Failed(reason) → Stopped
 * 连续健康一次即重置退避计数；达到 MAX_RESTART 后进入 Failed 终态。
 *
 * 自愈层（ProfileGuardian）： Healthy 时快照配置；同签名连续失败触发
 * last-good 回滚；仍失败进入安全模式（归档坏配置空跑）。
 * SafeMode 会作为独立状态暴露给 UI 展示「引擎运行于安全模式」。
 */
class EngineSupervisor(private val ctx: Context) {

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object Starting : State

        /** 正常健康；tokenUrl = 0.2.0+ WebUI 认证地址（engine.log 的 dsh web: 输出） */
        data class Healthy(
            val port: Int,
            val tokenUrl: String? = null,
            /** 本次启动前自动摘掉的第三方插件；仅在恢复后的首个 Healthy 状态携带。 */
            val recoveredPlugin: String? = null,
        ) : State

        /** 安全模式：配置被隔离后以空配置拉起，功能受限但可用 */
        data class SafeMode(
            val port: Int,
            val tokenUrl: String? = null,
            val recoveredPlugin: String? = null,
        ) : State
        data class Backoff(val delayMs: Long, val attempt: Int) : State
        data class Failed(val reason: String) : State
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 运行时解压进度 0f..1f；null = 不在解压中（UI 据此切换 indeterminate） */
    private val _installProgress = MutableStateFlow<Float?>(null)
    val installProgress: StateFlow<Float?> = _installProgress

    /** 当前健康端口，UI 层据此加载 WebView */
    val healthyPort: Int get() = when (val s = _state.value) {
        is State.Healthy -> s.port
        is State.SafeMode -> s.port
        else -> EngineConfig.DEFAULT_PORT
    }

    /** 从 engine.log 提取**本次启动**的 `dsh web:` 输出（带 token 完整 URL）。
     *  0.2.0 起 WebUI 强制认证，裸 127.0.0.1:3080 会显示认证提示页。
     *  引擎 stdout 由 EngineProcess 泵入 engine.log（环形 2MB，最新在末尾）。
     *  只搜 [logOffsetAtSpawn] 之后的日志段：健康探测可能早于 stdout 送达，
     *  搜全文会拿到上一次启动的旧 token（已失效 → WebView 401 白页，实测）。
     *  stdout 泵有毫秒级延迟，最多重试 ~600ms 等该行出现。 */
    private fun extractTokenUrl(): String? = runCatching {
        repeat(6) {
            val f = logFile()
            if (f.isFile) {
                // ⚠️ v1.4.6（审查 P0）：logOffsetAtSpawn 是**字节数**（来自
                // File.length()），而 String.substring 的下标是**字符数**。
                // 日志里有中文（AI 命令输出、工作区名）时字节数 > 字符数，
                // 直接拿字节值当字符下标会跳过本段开头 → token 行落在被跳过的
                // 区域 → tokenUrl=null → WebView 加载裸地址 → 401/白屏。
                // 重试 6 次用的还是同一个错误偏移，所以必然每次都失败。
                //
                // 正确做法：按**字节偏移**读取（RandomAccessFile.seek），再整体解码。
                val seg = runCatching {
                    java.io.RandomAccessFile(f, "r").use { raf ->
                        val start = logOffsetAtSpawn.coerceIn(0L, raf.length())
                        raf.seek(start)
                        val buf = ByteArray((raf.length() - start).coerceAtMost(1L shl 20).toInt())
                        var read = 0
                        while (read < buf.size) {
                            val n = raf.read(buf, read, buf.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        String(buf, 0, read, Charsets.UTF_8)
                    }
                }.getOrNull().orEmpty()
                Regex("dsh web: (http://127\\.0\\.0\\.1:\\d+/\\?token=\\S+)")
                    .findAll(seg).lastOrNull()?.groupValues?.get(1)
                    ?.let { hit -> return@runCatching hit }
            }
            Thread.sleep(100)
        }
        null
    }.getOrNull()

    /**
     * WebUI 应加载的地址。
     *
     * ⚠️ v1.4.46 安全审查 H2：旧实现在拿不到 token 时回退到**裸地址**
     * `http://127.0.0.1:3080/`。若设备上别的 App 先占了 3080 并返回 200，
     * 健康检查会判「就绪」，WebView 就直接加载攻击者页面 —— 同源钓鱼拿 API key、
     * 滥用 JS 桥写文件/投毒剪贴板/静默拿麦克风。
     *
     * 现在：Healthy/SafeMode 必须有 tokenUrl，拿不到就返回空串表示「不可加载」，
     * 由调用方显示错误并提示重启引擎。裸地址回退只保留给「尚未启动」的初值场景
     * （此时不会真的被 load，MainActivity 会等 Healthy）。
     */
    fun webUrl(): String = when (val s = _state.value) {
        is State.Healthy -> s.tokenUrl.orEmpty()
        is State.SafeMode -> s.tokenUrl.orEmpty()
        else -> ""
    }

    /** 是否有可加载的 WebUI 地址（含 token）。供 UI 决定是否显示「引擎异常」。 */
    fun hasLoadableUrl(): Boolean = webUrl().isNotEmpty()

    // v1.4.16 审查 P2：以下四个字段被监督协程与主线程/服务线程并发读写，
    // 与 stopping/restarting 同理需要 @Volatile 保证可见性。
    @Volatile private var process: EngineProcess? = null
    /** 启动前摘掉的插件名；恢复成功后的首个 Healthy 状态会带上它，随后清空。 */
    @Volatile private var recoveredPlugin: String? = null
    /** 本次引擎启动时 engine.log 的长度：extractTokenUrl 只搜此偏移之后的日志段 */
    @Volatile private var logOffsetAtSpawn = 0L
    @Volatile private var loopJob: Job? = null
    /**
     * 用户是否**显式**退出过（通知栏「退出」）。
     *
     * 只由 `stop(explicit = true)` 置位、`start()` 清零。用于阻止保活看门狗
     * 与 MainActivity.onResume 在用户主动退出后又把引擎拉起来。
     */
    @Volatile private var userStop = false

    /**
     * 监督循环是否应停止（任何 stop 都置位，start 清零）。
     *
     * 与 [userStop] 分开：服务被动销毁（OEM 清后台、系统回收）也要求循环停下，
     * 但那**不代表用户想退出** —— 之后必须有自动拉起的可能，否则引擎永久不启动。
     * 旧实现把两者混成一个 `userStop`，正是「打开 App 也不转」的根因（审查 P0）。
     */
    @Volatile
    private var stopping = false
    @Volatile private var scopeRef: CoroutineScope? = null

    /**
     * 用户是否显式退出过且尚未重新启动（通知栏「退出」置位）。
     * 期间 MainActivity.onResume 的自动拉起必须跳过，否则「退出」会被生命周期
     * 立刻撤销（用户实测：通知栏点退出 → 引擎又被拉起来 ✗）。
     * 重新启动（重启按钮 / 权限模式切换等显式动作）会清掉该状态。
     */
    fun isUserStopped(): Boolean = userStop && (loopJob?.isActive != true)

    /**
     * 用户**从通知栏点「退出」**时的回调（v1.2.187，v1.2.188 审查后改为回调式）。
     *
     * 由 MainActivity 在 onCreate 注册、onDestroy 注销。收到回调时它负责
     * `finishAndRemoveTask()` —— 销毁 WebView、释放 ~380MB。
     *
     * ## 为什么不用「标记位」而是回调
     * 标记位必须在某个时刻清零，否则用户**下次打开 App 会被立刻关掉**
     * （审查发现 P0：explicitExit 只有 start() 会清零，而 start() 被
     * `isUserStopped()` 拦住永不执行）。回调方式天然满足"只对本次退出生效"：
     * Activity 不在（已被系统回收）时回调为 null → 什么也不做，也不留残留状态。
     */
    @Volatile var onExplicitExit: (() -> Unit)? = null

    /**
     * 派发显式退出：结束 Activity（若在），然后**无条件结束整个 App 进程**。
     *
     * ## 为什么必须兜底杀进程（v1.4.0，用户反馈「通知栏退出后仍持续空转」）
     * 旧实现只调用 `onExplicitExit` 回调，而该回调注册在 MainActivity 上、并在
     * Activity onDestroy 时被置回 null（`MainActivity:1183` 的实例感知清理）。
     * 于是出现这条必然失败的路径：
     *
     *     用户按 Home 让 App 进后台（Activity 可能被回收）
     *       → 从通知栏点「退出」
     *       → supervisor.stop(explicit = true)
     *       → dispatchExplicitExit() 发现回调为 null
     *       → log 一句 "no live Activity" 就返回
     *       → **没有任何东西去结束进程**，App 连同其前台服务/无障碍/悬浮窗继续驻留
     *
     * 用户感知正是「脚本成功把软件退出了（引擎没了），但自己本身不退出」。
     * root 模式下更糟：残留进程会持续空转吃 CPU。
     *
     * 现在：回调有没有都继续走完，最后调用 [killAppProcess] 彻底结束进程。
     * Activity 在时先让它 finishAndRemoveTask（正常释放 WebView），再由兜底
     * 结束进程；Activity 不在时直接兜底 —— 两条路径终点一致。
     */
    private fun dispatchExplicitExit() {
        val cb = onExplicitExit
        if (cb == null) {
            Log.i(TAG, "explicit exit: no live Activity; killing process instead")
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (cb != null) {
                runCatching { cb() }
                    .onFailure { Log.w(TAG, "explicit exit callback failed: ${it.message}") }
            }
            // 给 Activity 的 finishAndRemoveTask 一点时间把 WebView 拆干净；
            // 但无论如何都会结束进程 —— 用户点了「退出」就不该留下任何东西。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                killAppProcess()
            }, if (cb == null) 0L else 600L)
        }
    }

    /**
     * 结束本 App 进程。
     *
     * 用 `am kill`/`force-stop` 针对自己不现实（需要 shell 权限），`Process.killProcess`
     * 对自家 uid 完全合法，且会带走同一进程里的前台服务、无障碍绑定、悬浮窗与所有
     * Kotlin 协程。引擎与它派生的进程树在 [stop] 里已经先 TERM→KILL，孤儿另有
     * [OrphanReaper] 兜底，所以这里只需要处理「本进程」。
     */
    private fun killAppProcess() {
        Log.i(TAG, "explicit exit: finishing app process")
        runCatching {
            // 先移除常驻通知，避免用户看到一条指向已死引擎的通知（部分 ROM 会保留）
            android.app.NotificationManager::class.java
                .let { ctx.getSystemService(it) }
                ?.cancelAll()
        }
        runCatching {
            android.os.Process.killProcess(android.os.Process.myPid())
        }.onFailure {
            // 极端情况下 killProcess 被拦（部分 ROM 的策略），退回 exit(0) —— 同样是
            // 结束本进程，不影响其他应用。
            Log.w(TAG, "killProcess failed (${it.message}); falling back to exit")
            kotlin.system.exitProcess(0)
        }
    }

    /** 重启互斥：重复点击重启按钮是 no-op（并发 stop/start 会互相踩踏，实测事故） */
    /**
     * 凭证/登录类失败的特征串（v1.4.24）。命中即判定「重启无用」，
     * 直接引导用户重新登录，而不是反复重启。
     */
    private val AUTH_FAILURE_MARKERS = listOf(
        "401", "403", "unauthorized", "authentication failed", "authentication required",
        "invalid api key", "invalid_api_key", "incorrect api key",
        "token expired", "invalid token", "token is invalid",
        // 中文特征串用 Unicode 转义：这里是日志匹配模式（不是 UI 文案），
        // i18n 门禁会禁止 Kotlin 里出现 CJK 字面量。
        "token \u8fc7\u671f", "\u4ee4\u724c\u5df2\u8fc7\u671f",
        "login required", "not logged in",
        "\u8bf7\u5148\u767b\u5f55", "\u767b\u5f55\u5df2\u5931\u6548",
        "credentials", "assertowneronly", "eacces",
    )

    @Volatile private var restarting = false

    /**
     * 维护窗口标志（v1.4.56）：清理 dsh-home 期间为 true。
     * [start] 看到它就立即返回，避免监督循环或外部调用把引擎重新拉起来、
     * 在删除过程中写文件。
     */
    @Volatile private var maintenanceStop = false
    /** 用户重启的起点时间戳（healthy 时打印总耗时，用于定位「重启很久」这类反馈） */
    @Volatile private var restartStartedAt: Long? = null
    private val guardian by lazy { ProfileGuardian(ctx) }

    fun start(scope: CoroutineScope) {
        scopeRef = scope
        if (loopJob?.isActive == true) return
        // v1.4.56：维护窗口内拒绝启动 —— 此时 dsh-home 正被删除/重建，
        // 拉起引擎只会让它写到一半的文件上并再次崩溃。
        if (maintenanceStop) {
            Log.i(TAG, "start ignored: maintenance window active (dsh-home being reset)")
            return
        }
        userStop = false
        // ⚠️ v1.4.9（社区贡献 @henryz78，用户实测「点重启后一直卡在等待状态」）：
        // [stopping] 必须在这里复位 —— 这个标志自己的 KDoc 写的就是「任何 stop 都置位、
        // start 清零」，而实现漏了清零。后果是确定性的：
        //   restart() → stop() 置 stopping=true → start() 拉起新监督循环
        //     → 循环条件 while (isActive && !stopping) 首轮即为假，一次迭代都不跑
        //     → 状态永久停在 restart() 设的 State.Starting，UI 一直转圈不动。
        // 影响面比「重启卡住」更大：任何一次 stop()（EngineService.onDestroy 被系统回收、
        // 通知栏退出、权限模式切换）之后，本进程内所有 start() 都再也跑不起来，
        // 只有杀掉 App 进程才恢复 —— 与用户描述完全吻合。
        stopping = false
        loopJob = scope.launch(Dispatchers.Default) { supervisionLoop() }
    }

    /**
     * 停止引擎。
     * @param graceMs 优雅退出等待上限。
     * @param explicit true = 用户在通知栏点了「退出」（会结束主界面并销毁 WebView
     *                 以释放内存）；false = 服务销毁等被动停止。
     * @return v1.4.26：**是否确认已停干净**（进程已退出且 3080 已释放）。
     *         换运行时前必须看这个返回值 —— 引擎还在跑就换 `engine/`，
     *         新进程会继续读旧 inode，等我们把 `engine.old` 删掉它就崩。
     *         其余调用方可以忽略（Kotlin 允许丢弃返回值）。
     */
    fun stop(graceMs: Long = 10_000, explicit: Boolean = false): Boolean {
        // ⚠️ v1.4.6（审查 P0）：只有**用户显式退出**才置 userStop。
        // 旧实现在服务被动销毁（explicit=false，如 OEM 清后台、系统回收前台服务、
        // 划掉任务）时也置 userStop=true —— 而 isUserStopped() 会因此恒为真，
        // EngineService.start() 与保活看门狗都被它拦住，引擎**永久不再启动**，
        // 直到用户手动点「重启」。用户感知是「打开 App 也不转、一直空转」。
        if (explicit) userStop = true
        stopping = true
        loopJob?.cancel()
        val proc = process
        proc?.stop(graceMs)
        process = null
        // v1.2.188（审查 P2）：stop() 只 cancel 不 join 监督循环 —— 若恰好在
        // spawnEngine() 的阻塞段里，已 fork 的 node 可能没有引用、无人 stop。
        // 这里补一次孤儿清理（端口占用检测 + 同 uid pkill），覆盖该窗口。
        runCatching { cleanupOrphanEngine() }
        // ⚠️ v1.4.0 修复（用户反馈「通知栏退出后 root 模式持续空转」）：
        // cleanupOrphanEngine() 以 `portInUse()` 为前提 —— 端口没被占就整体跳过。
        // 而 root 模式下存在问题：主引擎 node 已退出、3080 也随之释放，但它通过
        // su 派生/被 init 收养的后代（AI 起的 bash、工具进程）仍在跑并空转吃 CPU。
        // 端口检查看不到它们，于是这条路径被整个跳过，进程永久残留。
        //
        // 修法：显式退出时**无条件**跑一次全量孤儿回收（含 root 视角的 /proc 扫描）。
        // 只杀 cmdline 里带本 app engine/dsh-home 绝对路径的进程，不会误伤用户
        // 自己在 Termux 里跑的东西（见 OrphanReaper 顶部「绝不误杀」）。
        if (explicit) {
            runCatching {
                val killed = OrphanReaper.reap(ctx)
                if (killed > 0) Log.i(TAG, "explicit exit: reaped $killed orphan process(es)")
            }.onFailure { Log.w(TAG, "explicit exit orphan reap failed: ${it.message}") }
        }
        _state.value = State.Stopped
        if (explicit) dispatchExplicitExit()
        // v1.4.26：给「是否真的停干净」一个确定的答案。上面的 cleanupOrphanEngine
        // 已经是尽力而为，这里再等一小段端口释放 —— 换运行时那种「文件要被整体
        // 替换掉」的操作不能拿「大概停了」当依据。
        // 端口已释放时这一步几乎是零成本（connect 立即 refused）；只有真的还被占
        // 才等到超时，而那正是需要如实报告的场合。
        val portFree = awaitPortFree(800)
        // 等完再判一次进程：force kill 之后 exitFuture 的完成有几百毫秒的延迟。
        val alive = proc != null && !proc.exitFuture.isDone
        if (alive || !portFree) {
            Log.w(TAG, "stop incomplete: alive=$alive portFree=$portFree (engine may still hold files)")
        }
        return !alive && portFree
    }

    /** 轮询等待端口释放；超时返回是否已释放。 */
    private fun awaitPortFree(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!portInUse(200)) return true
            runCatching { Thread.sleep(100) }
        }
        return !portInUse(300)
    }

    /**
     * 热重启：完整走一遍 stop → start（TERM→KILL 优雅停止 + 监督循环重进）。
     * 与进程被杀后的自动退避不同，这是用户显式动作：退避计数天然从零开始，
     * guardian 的 Healthy 快照/计数不受影响。
     *
     * ⚠️ v1.2.37 修复（用户实测「点三下才重启」）：旧实现里
     *   a) `process.stop()` 要等引擎优雅退出**最长 10 秒**，且 Stopped 状态在其之后才置位
     *      → 第一次点击界面毫无反馈（用户以为没点到，继续点）；
     *   b) 调用方（MainActivity/SettingsActivity）每次点击各起一条线程，多点几下就是
     *      多条 restart 线程并发 stop/start 互相踩踏 → 「待启动→进程异常→2 秒后自愈重启」。
     * 现在：立即置 Starting（UI 立刻有反馈）、整个重启跑在监督循环同一个 scope 上、
     * [restarting] 互斥保证重复点击是 no-op（幂等）。本方法立即返回、不阻塞调用线程。
     */
    fun restart() {
        val scope = scopeRef ?: return
        if (restarting) {
            Log.i(TAG, "restart already in progress, ignoring duplicate tap")
            return
        }
        Log.i(TAG, "restart: hot restart requested by user")
        // 维护窗口结束：用户点了重启，说明清理已完成
        maintenanceStop = false
        restarting = true
        val t0 = System.currentTimeMillis()
        restartStartedAt = t0
        scope.launch(Dispatchers.IO) {
            try {
                _state.value = State.Starting
                // 用户重启：3.5s 宽限（足够引擎落盘收尾；TERM 无响应时尽快 KILL，不再干等 10s）
                stop(graceMs = 3_500)
                Log.i(TAG, "restart: previous engine stopped (${System.currentTimeMillis() - t0}ms)")
                _state.value = State.Starting   // stop() 会置 Stopped，重启路径立即回到启动中
                start(scope)
            } catch (e: Exception) {
                Log.w(TAG, "restart failed: ${e.message}")
            } finally {
                restarting = false
            }
        }
    }

    /**
     * 为维护操作（清理 dsh-home）**同步**停掉引擎，并阻止它自动重启。
     *
     * 与 [restart] 的区别：
     * - restart 是「停完立刻起」，异步、幂等；
     * - 本方法**阻塞到进程真正退出**，因为调用方紧接着要删 dsh-home —— 引擎还活着
     *   就会写出新文件、持有 fd、并在删除过程中崩溃，留下半截状态。
     *
     * 停完之后调用方负责调用 [restart] 让引擎重新起来（用户可能想先看看备份）。
     * 期间监督循环不会自动重拉：这里置 maintenanceStop 标志，[start] 入口检查它。
     */
    fun stopForMaintenance(graceMs: Long = 4_000) {
        maintenanceStop = true
        Log.i(TAG, "maintenance stop: halting engine before dsh-home surgery")
        val proc = process
        if (proc == null) {
            _state.value = State.Stopped
            return
        }
        // 直接在当前线程等它退干净（调用方已在后台线程）
        runCatching { proc.stop(graceMs) }
            .onFailure { Log.w(TAG, "maintenance stop: ${it.message}") }
        process = null
        _state.value = State.Stopped
        // 释放端口，避免重启时抢不到
        runCatching { reclaimPort() }
        Log.i(TAG, "maintenance stop: engine is down, safe to modify dsh-home")
    }

    /** 维护完成：允许下一次 [start] 正常拉起引擎。 */
    fun allowStartAfterMaintenance() {
        maintenanceStop = false
    }

    /** 手动导出引擎日志（用户反馈通道） */
    fun logFile(): File = File(EngineConfig.engineRoot(ctx), "engine.log")

    /**
     * 失败签名：用「引擎日志尾部 + 退出码」的哈希近似。
     * 确定性崩溃（坏配置）每次堆栈一致 → 同签名；
     * 偶发崩溃（OOM/被杀）尾部随机 → 不同签名不累计。
     */
    private fun failureSignature(status: Int?): String {
        // 签名必须稳定：崩溃堆栈里的行号/内存地址/时间戳每次都变，原文哈希会让
        // streak 永远重置 → 永远到不了阶段阈值（自愈失效实测根因）。
        // 数字全部归一为 #，保留错误结构与字面（不同异常仍然不同签名）。
        val tail = runCatching {
            logFile().readText().takeLast(4096)
                .lineSequence()
                .filter { it.contains("Error") || it.contains("at ") }
                .toList()
                .takeLast(12)
                .joinToString("\n") { it.replace(Regex("\\d+"), "#") }
        }.getOrDefault("")
        return "$status:${tail.hashCode()}"
    }

    /**
     * 本次启动是否因**凭证/登录态**失败（v1.4.24）。
     *
     * 用户反馈：「token 过期就会显示进程异常退出、反复重启」。这类失败的特征是
     * 重启无法自愈——需要用户重新登录 / 换 API Key / 修正凭据文件权限。如果
     * 交给常规退避重试，就是「重启 14 次全部失败 → Failed」的长时间空转，
     * 用户只看到「进程异常退出」却不知道要重新登录。
     *
     * 判据（任一命中）：
     *  - 401 / 403 / unauthorized / invalid api key / authentication failed
     *  - token expired / invalid token / 登录失效
     *  - credentials 读取失败（权限/格式）
     *
     * 只扫**本次启动**的日志段（[logOffsetAtSpawn] 之后），避免把历史错误当成本次。
     */
    private fun looksLikeAuthFailure(): Boolean = runCatching {
        val f = logFile()
        if (!f.isFile) return@runCatching false
        val len = f.length()
        val from = logOffsetAtSpawn.coerceIn(0L, len)
        if (len <= from) return@runCatching false
        val text = java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray((len - from).toInt().coerceAtMost(16_384))
            val n = raf.read(buf)
            String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
        }.lowercase()
        AUTH_FAILURE_MARKERS.any { text.contains(it) }
    }.getOrElse { false }

    /**
     * **本次启动**的日志尾部是否出现「端口被占」类失败。
     *
     * 只读 [logOffsetAtSpawn] 之后的内容 —— 引擎日志是**追加**的，整份读会把
     * 上一次启动留下的 EADDRINUSE 当成本次失败（那样每次启动都会误触发清场）。
     */
    private fun looksLikePortConflict(): Boolean = runCatching {
        val f = logFile()
        if (!f.isFile) return@runCatching false
        val len = f.length()
        val from = logOffsetAtSpawn.coerceIn(0L, len)
        // 只取本次启动写下的尾部（最多 8KB，足够覆盖一段启动堆栈）
        val tail = java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(maxOf(from, len - 8192))
            val buf = ByteArray((len - maxOf(from, len - 8192)).toInt())
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
        tail.contains("EADDRINUSE") || tail.contains("address already in use", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * 监督循环是否应当退出（v1.4.9，社区贡献 @henryz78，配合 [start] 里的 stopping 复位）。
     *
     * 只看 [stopping] 不够：stop() 的 `loopJob?.cancel()` 是**异步**的，而 start()
     * 把 stopping 复位后，那个已被取消的**旧**循环若正停在 `catch (e: Exception)`
     * ——CancellationException 也是 Exception，会被它吞掉——就判出 stopping == false，
     * 于是不 break、继续走收尾：guardian.onFailure() 可能触发 ROLLED_BACK 静默回滚
     * 用户 profiles，末尾的 State.Backoff 还会覆盖**新**循环刚设的 State.Starting。
     * 所以额外要求协程活性：本协程一旦被取消，一律视为退出信号。
     */
    private suspend fun shouldExitLoop(): Boolean =
        stopping || !kotlinx.coroutines.currentCoroutineContext().isActive

    private suspend fun supervisionLoop() {
        var backoffIndex = 0
        // 端口清场计数（v1.3.1）：防「非引擎进程永久占端口」时绕过退避无限重试
        var portReclaimCount = 0
        // 生命周期内累计清场次数：portReclaimCount 会在「额度用尽」时被归零以允许
        // 下一轮，但**累计值**必须持续增长，否则清场额度永远用不完（审查 P0）。
        var totalPortReclaims = 0
        // 上一轮是否因插件恢复而主动重启（只用来给 BootTrace 标注启动原因）
        var pluginRecoveryRequestedThisLoop = false
        // v1.4.9：插件自动隔离触发的重启次数（生命周期累计，独立于 MAX_RESTART）。
        // 旧实现会让这条路径绕过退避额度，形成「摘插件→重启→再摘」的长时间空转。
        var pluginRecoveryRestarts = 0
        // v1.4.16 审查 P2：快速隔离的独立节流。旧实现隔离后只重置 backoffIndex，
        // 下一轮失败又满足「连续 2 次」——若启动失败根因与插件无关（runtime 损坏等），
        // 会按 mtime 从新到旧把所有第三方插件逐个摘光（实测复现：先摘无辜的
        // whale-widget 再摘真凶 skills-manager，好在那次真凶在第二个）。
        // 现在每次隔离后要求再失败 2 次才允许下一次，并设生命周期总上限。
        var fastIsolations = 0
        // v1.4.9：本次「用户可见启动」的墙钟起点。所有自愈层各自有边界，但组合起来
        // 没有总预算 —— 用户实测能空转 20 分钟以上，界面一直停在准备阶段。
        var userVisibleBootT0 = System.currentTimeMillis()
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !stopping) {
            // 总时限检查：一旦超预算，收敛为 Failed 并说清发生了什么。
            // 预算只约束「从空闲/停止状态到引擎可用」的这一段连续尝试，两个重置点：
            //   ① 引擎进入 Healthy/SafeMode（成功收尾，见下方 ready 分支）
            //   ② 健康运行的引擎退出后决定自动重启时（新一轮启动从死亡时刻起算）
            // 缺了 ② 会出现：健康跑了一天 → 崩溃 → 第一次重试就被误判
            // 「启动超时 N 分钟」直接 Failed（审查 P1，v1.4.16 修复）。
            val bootElapsed = System.currentTimeMillis() - userVisibleBootT0
            if (bootElapsed > EngineConfig.BOOT_WALL_CLOCK_BUDGET_MS && _state.value !is State.Healthy &&
                _state.value !is State.SafeMode
            ) {
                val mins = bootElapsed / 60_000
                Log.e(
                    TAG,
                    "boot exceeded ${EngineConfig.BOOT_WALL_CLOCK_BUDGET_MS}ms budget " +
                        "(elapsed=${bootElapsed}ms, attempts=$backoffIndex, " +
                        "pluginRecoveryRestarts=$pluginRecoveryRestarts) — giving up with a visible error",
                )
                _state.value = State.Failed(
                    ctx.getString(R.string.error_boot_timeout, mins),
                )
                BootTrace.fail(ctx, ctx.getString(R.string.error_boot_timeout, mins))
                return
            }
            // 仅当引擎「自行死亡」（fork 失败 / 启动期退出）才值得让 guardian 定罪；
            // 健康超时自杀、安装异常、Healthy 后运行中退出都不算配置崩溃。
            var deterministicFailure: String? = null
            val bootT0 = System.currentTimeMillis()
            var stepT = bootT0
            // v1.4.8：启动过程可视化。每轮尝试开一笔新记录 —— 插件恢复重启会让同一轮
            // 循环再跑一次，两次各自的阶段耗时都值得保留（能直观看出第二次快多少）。
            BootTrace.begin(
                when {
                    pluginRecoveryRequestedThisLoop -> "plugin recovery restart"
                    backoffIndex > 0 -> "retry #${backoffIndex + 1}"
                    restartStartedAt != null -> "user restart"
                    else -> "cold start"
                },
            )
            pluginRecoveryRequestedThisLoop = false
            fun step(name: String) {
                val now = System.currentTimeMillis()
                Log.i(TAG, "boot step $name: ${now - stepT}ms (total ${now - bootT0}ms)")
                BootTrace.step(name)
                stepT = now
            }
            try {
                // 启动前先把被误隔离的引擎内置 patch 恢复（自愈；防 ENOENT fail-loud）
                val healed = withContext(Dispatchers.IO) { guardian.restoreQuarantinedBuiltinOverlays() }
                step("guardian-restore")
                if (healed > 0) Log.w(TAG, "guardian: restored $healed quarantined builtin overlay(s)")

                // Agent 上下文种子（m1.35）：幂等预写 $HOME/AGENTS.md（dsh 原生 user-global
                // 指令），让 Agent 首轮就带 Android 世界观，省掉环境探索 token
                withContext(Dispatchers.IO) { AgentContextSeed.ensure(ctx) }
                step("agent-seed")

                // 孤儿进程回收（v1.2.104）：**必须早于桥 bind 与引擎 fork** ——
                // 历史孤儿会攥着继承来的 3080/3083 监听 socket 不撒手（无 CLOEXEC，
                // 见 OrphanReaper 顶部），不先清掉，桥会静默 bind 失败、引擎会起不来。
                withContext(Dispatchers.IO) { OrphanReaper.reap(ctx) }
                step("orphan-reap")

                // Agent 能力桥（v1.1.0）：notify/scr 的 HTTP 后端，全模式启动
                withContext(Dispatchers.IO) { AgentBridge.start(ctx) }
                step("bridge")

                // 属性自愈（v1.3.1 扩展到**所有模式**，含 Root）。
                //
                // ⚠️ 旧实现只在非 Root 模式跑（`mode != ROOT`），漏掉了两类真实故障
                // （用户反馈「安卓 11 上异常退出后无法使用，删掉重装又好了」）：
                //   · Root 引擎以 uid 0 写出的文件属主是 root，而 **App 自己**（非 root）
                //     读不了 engine/ 与 dsh-home/：`.runtime-version` 版本戳、
                //     `profile-guardian.meta`、插件、AGENTS.md → 启动链路 EACCES 累积；
                //   · 与模式无关的属性损坏（SELinux 标签错、权限位被剥）同样致命。
                // 用户只能靠「删掉重装」恢复（卸载清空私有目录 = 变相重置属性），
                // 这正是该缺陷最典型的现场指纹。
                // 现在无论什么模式都查一遍，检测到就修（chown + chmod + restorecon）。
                // 日志卫生（v1.4.57，用户反馈「日志累积到 96 万行、不会定时清除」）：
                // engine.log / boot.log 各自有环形上限，但 plugin-manager 的
                // operation-* 目录无上限、且「只读不写」时 2MB 旧内容永不收缩。
                // 这里在每次启动前统一裁剪一次，只删日志不碰用户数据。
                withContext(Dispatchers.IO) { LogJanitor.sweep(ctx) }
                step("log-sweep")

                val needFix = withContext(Dispatchers.IO) { Privilege.dshHomeNeedsOwnershipFix(ctx) }
                if (needFix) {
                    Log.w(TAG, "found files with broken ownership/permissions (root residue or bad labels); healing")
                    val fixed = withContext(Dispatchers.IO) { Privilege.fixHomeOwnership(ctx) }
                    Log.w(TAG, "attribute healing ${if (fixed) "succeeded" else "unavailable (no shizuku/su)"}")
                }
                step("home-owner-check")

                _state.value = State.Installing
                BootTrace.stage(BootTrace.Stage.EXTRACTING)
                withContext(Dispatchers.IO) {
                    var lastPct = -1
                    val reused = RuntimeInstaller(ctx).ensureInstalled(
                        onProgress = { frac ->
                            // 仅整 1% 变化时发布，避免 2 万次无效状态更新
                            val pct = (frac * 100).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _installProgress.value = frac
                            }
                        },
                        onDetail = { detail ->
                            // "已加载 N/M 个文件"的确定性进度（v1.4.8）
                            BootTrace.install(detail)
                        },
                    )
                    if (reused) BootTrace.markRuntimeReused()
                }
                _installProgress.value = null
                step("runtime-install")

                // v1.4.20：内置 SSH 插件与 runtime 解耦。runtime 重装会删掉
                // engine/lib，插件必须在这里补装（失败只降级 SSH，不阻断引擎）。
                runCatching { HardsshInstaller(ctx).ensureInstalled() }
                    .onSuccess { v -> if (v != null) Log.i(TAG, "bundled ssh plugin ready: $v") }
                    .onFailure { Log.w(TAG, "bundled ssh plugin setup failed: ${it.message}") }
                step("hardssh-install")

                // 用户数据外置（v1.2.99）：sessions/workspaces 迁到公开目录（软链），
                // profiles 保持私有（上游强制凭证 0600，共享存储给不了权限位）。
                // 必须在 spawn 前完成 —— 引擎启动即读这些目录。
                withContext(Dispatchers.IO) { EngineConfig.ensureUserDataExternal(ctx) }
                step("user-data")

                _state.value = State.Starting
                BootTrace.stage(BootTrace.Stage.STARTING)
                // 旧 marker 可能来自上一次被强杀的引擎；本次 fork 前先清掉，
                // 否则一个 stale 的 degraded 结果会把正常插件误摘掉。
                withContext(Dispatchers.IO) { PluginRecovery.prepareForBoot(ctx) }
                // 插件应急总开关（v1.4.27）：引擎此刻必定没在跑，是唯一安全的
                // manifest 改写点。按意图日志把 bundle 列表收敛到目标状态——
                // 上一次动作被强杀在中间也能在这里补完（幂等）。
                val killSwitch = withContext(Dispatchers.IO) { PluginKillSwitch.enforceBeforeBoot(ctx) }
                if (killSwitch.changed) {
                    Log.i(
                        TAG,
                        "kill-switch converged plugins: ${killSwitch.before} -> ${killSwitch.after} " +
                            "bundles (phase=${killSwitch.phase?.wire})",
                    )
                }
                val bootId = java.util.UUID.randomUUID().toString()
                val proc = withContext(Dispatchers.IO) { spawnEngine(bootId) }
                process = proc
                step("spawn")

                val healthy = pollHealth(EngineConfig.DEFAULT_PORT, proc)
                step("health-poll")
                if (healthy) {
                    // HTTP 已通 → 进入「加载界面」阶段：等插件探针、再由 UI 拉 WebUI 首屏。
                    BootTrace.stage(BootTrace.Stage.LOADING)
                    var pluginRecoveryRequested = false
                    // HTTP 已监听不代表 Cordis Loader 的关键 entry 已成功激活。
                    // 等待内置 probe 写出本次 boot 的 marker；缺失只记诊断，不阻断慢设备。
                    val health = withContext(Dispatchers.IO) { awaitPluginHealth(proc, bootId) }
                    if (health != null) {
                        val summary =
                            "plugin health: ok=${health.ok} " +
                                "missing=[${health.missingServices.joinToString(",")}] " +
                                "failed=[${health.failedEntries.joinToString(",") { "${it.id}:${it.name}" }}]"
                        Log.i(TAG, summary)
                        // ⚠️ v1.4.7（用户建议）：健康摘要也要进 engine.log。
                        // 此前只写 logcat / marker 文件，而用户反馈问题时导出的是
                        // engine.log —— 里面一个字都没有，导致「目录选择器整条链失效」
                        // 这类问题在日志里完全不可见，只能靠卸载重装碰运气。
                        // 写文件失败不影响启动。
                        runCatching { EngineLog.append(logFile(), "[health] " + summary) }
                    } else {
                        Log.w(TAG, "plugin health marker missing or unreadable; keeping engine available")
                        runCatching {
                            EngineLog.append(
                                logFile(),
                                "[health] marker missing/unreadable (probe did not report)",
                            )
                        }
                    }
                    if (health?.ok == true) {
                        withContext(Dispatchers.IO) { PluginRecovery.onHealthy(ctx) }
                    }
                    if (health != null && !health.ok) {
                        val action = withContext(Dispatchers.IO) { PluginRecovery.quarantineOne(ctx, health) }
                        if (action != null) {
                            recoveredPlugin = action.packageName
                            pluginRecoveryRequested = true
                            pluginRecoveryRequestedThisLoop = true
                            Log.e(
                                TAG,
                                "plugin recovery: disabled ${action.packageName}; reason=${action.reason}; " +
                                    "restart=${action.attempt}/${3}",
                            )
                            // 不把 degraded engine 暴露给 WebView；只重启一次本轮，
                            // PluginRecovery 的持久化上限负责阻止无限重启风暴。
                            proc.stop(graceMs = 1_500)
                            deterministicFailure = null
                            // ⚠️ v1.4.9（用户反馈「等几十分钟引擎都准备不好」）：
                            // 这里**不再重置 backoffIndex**，也**不能**跳过统一退避。
                            // 旧实现重置为 0 并在下面 `if (pluginRecoveryRequested)` 里
                            // 直接进下一轮（无 delay），于是「探针报 degraded → 摘一个插件
                            // → 立刻重启 → 再报 degraded」形成一个**不计入 MAX_RESTART**
                            // 的快速循环；每轮还要跑满 45s 健康检查 + 20s 探针等待，
                            // 14 轮就是 21 分钟，正好是用户描述的「几十分钟准备不好」。
                            // 现在：沿用统一的 backoffIndex 递增与退避，并受
                            // pluginRecoveryRestarts 生命周期总上限约束。
                            pluginRecoveryRestarts++
                        } else {
                            Log.e(TAG, "plugin recovery: no safe candidate; leaving profile unchanged")
                        }
                    }
                    if (pluginRecoveryRequested) {
                        // 这次是有界的插件修复重启，不进入 ProfileGuardian 的配置定罪计数，
                        // 但**仍然走下面的统一退避**。超过生命周期总上限就停手，
                        // 把状态暴露出去而不是继续空转。
                        if (pluginRecoveryRestarts > EngineConfig.MAX_PLUGIN_RECOVERY_RESTARTS) {
                            Log.e(
                                TAG,
                                "plugin recovery restart budget exhausted " +
                                    "(${EngineConfig.MAX_PLUGIN_RECOVERY_RESTARTS} lifetime) — " +
                                    "leaving degraded engine available instead of looping",
                            )
                            // 降级但可用：继续按 degraded 处理（引擎 HTTP 是通的）
                            pluginRecoveryRequested = false
                        }
                    } else {
                        // 端口已监听但健康探针明确报告 degraded 时，不能把坏 profile
                        // 快照成 last-good；否则后续崩溃回滚会再次恢复同一坏插件。
                        if (health?.ok != false) {
                            withContext(Dispatchers.IO) {
                                guardian.resetCrashStreak()
                                guardian.snapshotLastGood()
                            }
                        } else {
                            Log.w(TAG, "plugin health degraded; keeping previous last-good snapshot")
                        }
                        backoffIndex = 0
                        // 引擎 healthy = 待重启标记解除（激活/停用/卸载的扩展自此刻生效）
                        app.dsh.mobile.engine.ExtensionManager.clearPendingRestart()
                        val safe = guardian.inSafeMode()
                        val tokenUrl = extractTokenUrl()
                        val notice = recoveredPlugin
                        recoveredPlugin = null
                        val t1 = System.currentTimeMillis()
                        val restartMs = restartStartedAt?.let { " (total restart took ${t1 - it}ms)" } ?: ""
                        restartStartedAt = null
                        Log.i(TAG, (if (safe) "engine healthy in SAFE MODE on :${EngineConfig.DEFAULT_PORT}" else "engine healthy on :${EngineConfig.DEFAULT_PORT}") + restartMs)
                        _state.value =
                            if (safe) State.SafeMode(EngineConfig.DEFAULT_PORT, tokenUrl, notice)
                            else State.Healthy(EngineConfig.DEFAULT_PORT, tokenUrl, notice)
                        // 新运行时首次 Healthy：确认安装成功，释放 engine.old 回滚备份。
                        runCatching { RuntimeInstaller(ctx).markHealthy() }
                            .onFailure { Log.w(TAG, "runtime markHealthy failed: ${it.message}") }
                        // 插件应急总开关（v1.4.27）：恢复动作已收敛且引擎真起来了，
                        // 相位推进到 enabled。⚠️ SafeMode 不确认：那时 profiles 已被
                        // 归档重建，bundles 根本没写回去，标成 enabled 会让用户失去重试。
                        if (!safe) {
                            withContext(Dispatchers.IO) { PluginKillSwitch.onHealthy(ctx) }
                        }
                        // 启动记录收尾：引擎已可用。WebView 首屏耗时会由 UI 层补记。
                        BootTrace.ready(ctx)
                        // ⚠️ v1.4.9：成功进入 Healthy/SafeMode 后**重置墙钟起点与插件
                        // 恢复额度**。预算约束的是「这一次启动要多久」，不是「进程总寿命」
                        // —— 否则一台跑了一天的设备，引擎偶发崩溃后的正常重启会被
                        // 误判成「启动超时」直接 Failed。
                        userVisibleBootT0 = System.currentTimeMillis()
                        pluginRecoveryRestarts = 0
                        backoffIndex = 0
                        // Shizuku 模式：引擎就绪后启动 ADB 级访问桥（shz 包装器回呼用）；其他模式自动关停
                        withContext(Dispatchers.IO) {
                            ShizukuHttpBridge.start(ctx, EngineConfig.DEFAULT_PORT)
                        }
                        // 阻塞等待进程退出（被杀/崩溃）。Healthy 后退出视为资源类偶发，
                        // 不计入 guardian（那是普通退避该管的事，与配置无关）。
                        val status = proc.exitFuture.get()
                        if (shouldExitLoop()) break
                        Log.w(TAG, "engine exited, raw status=0x${status.toString(16)}")
                        // 把退出原因写进 engine.log（用户分享日志时能看到是信号杀还是退出码），
                        // 否则只能靠 logcat 排查引擎「起来又没了」这种静默重启。
                        runCatching {
                            EngineLog.append(
                                logFile(),
                                "engine exited, raw status=0x${status.toString(16)} " +
                                    "(signal=${status and 0x7f}, core=${(status and 0x80) != 0}, " +
                                    "exitCode=${(status shr 8) and 0xff})",
                            )
                        }
                        // ── v1.4.24（宝宝要求）：异常退出后彻底清场再完整重启 ──────
                        // 这条路径是「引擎健康运行过、然后被杀/OOM/崩溃」——正是用户
                        // 说的「进程异常退出」。旧实现直接进入下一轮 spawn，残留的
                        // 监听 socket、TIME_WAIT、或被 su 拉起的孙进程都可能让新引擎
                        // EADDRINUSE，表现为「反复重启起不来」。
                        //
                        // 现在：先按端口把 3080 上的一切清干净，再完整重新启动一遍，
                        // 效果等同「关掉应用重开」。
                        withContext(Dispatchers.IO) { reclaimPort() }
                        Log.i(TAG, "post-crash: port ${EngineConfig.DEFAULT_PORT} thoroughly reclaimed before restart")
                        // v1.4.16（审查 P1）：健康运行的引擎退出、即将自动重启时，
                        // 重置启动墙钟——新一轮「用户可见启动」从旧引擎死亡时起算。
                        // 旧实现只有 Healthy 分支一个重置点，健康跑超 5 分钟后崩溃，
                        // 第一次重试就会在循环顶部被误判「启动超时」直接 Failed。
                        userVisibleBootT0 = System.currentTimeMillis()
                    }
                } else if (proc.exitFuture.isDone) {
                    // 启动期内进程自己死了——唯一进入自愈判定的信号
                    deterministicFailure = failureSignature(lastExitStatus)
                } else {
                    // 健康检查超时：引擎没死是我们主动停的——很可能是慢启动，
                    // 绝不计入崩溃计数（m1.6.4 真机误伤教训）
                    proc.stop()
                }
            } catch (e: EngineStartException) {
                if (shouldExitLoop()) break
                Log.e(TAG, "supervision failure", e)
                // v1.4.8：失败也收尾，否则启动遮罩与"上次启动"会一直显示进行中
                BootTrace.fail(ctx, e.message ?: e.javaClass.simpleName)
                // ⚠️ v1.3.1：su 探测失败（"no usable su"）是**环境瞬时问题**，不是坏配置。
                // 场景：App 被强杀后立刻重启，magiskd 对该 app 的 su 会话尚未重建、
                // 或设备刚开机 daemon 还没就绪 —— 三个探针各 2s 超时 → usableSuPrefix()
                // 返回 null。旧实现把它当确定性崩溃计入 guardian（签名 "null:<hash>"），
                // 于是「一次异常杀死 + 几轮 su 忙」就足以让 guardian 回滚用户配置、
                // 进而 SAFE MODE（归档配置）——这正是用户报的二次启动起不来的放大机制。
                // 现在明确隔离：这类失败只退避重试，绝不判罪。
                if (e.message?.contains("no usable su") == true) {
                    Log.w(TAG, "su unavailable right now — treating as transient, not a config failure")
                    deterministicFailure = null
                } else {
                    deterministicFailure = failureSignature(null)
                }
            } catch (e: EngineArchMismatchException) {
                // 确定性故障（v1.3.3，issue #23）：APK 里带的 runtime 就是错的架构，
                // 重试多少次都必然失败 —— 直接进 Failed 并说清原因，别让用户看着
                // 「连续 14 次启动失败」的模糊文案反复重装。
                Log.e(TAG, "runtime architecture mismatch — stopping (retrying cannot help)", e)
                _state.value = State.Failed(ctx.getString(R.string.error_runtime_arch_mismatch))
                BootTrace.fail(ctx, ctx.getString(R.string.error_runtime_arch_mismatch))
                return
            } catch (e: Exception) {
                if (shouldExitLoop()) break
                Log.e(TAG, "supervision failure", e)
                BootTrace.fail(ctx, e.message ?: e.javaClass.simpleName)
            }

            // ---- 自愈判定：仅对「引擎真死」且签名连续一致时逐步升级 ----
            // ---- 环境类失败处置（v1.3.1）：与配置无关，绝不交给 guardian 判罪 ----
            // 【历史事故】v1.2.22 / v1.2.32 / v1.2.108 三轮修复都写在这个分支里，
            // 但判断条件是 `deterministicFailure.contains("EADDRINUSE")` —— 而签名是
            // `"<退出码>:<哈希>"`，**永远不可能包含该字面量**，所以这段自愈从未执行过。
            // 后果（用户报的「Root 模式被杀死后二次启动起不来」）：
            //   Root 引擎的 node 是 su 的**孙进程**（PDEATHSIG 只挂到 su 外壳上），
            //   App 被强杀时 node 可能以 uid 0 成为孤儿继续占着 3080 →
            //   新引擎 EADDRINUSE 启动即退 → 本该「清场重试、不计入自愈」，
            //   实际被 guardian 当成「坏配置连续崩溃」→ 回滚 profiles →
            //   SAFE MODE（归档用户配置）→ MAX_RESTART 后 State.Failed。
            // 证据门槛（两道，必须同时成立）：
            //   ① **端口真的被占** —— 硬证据，且清场对空闲端口是无害 no-op；
            //   ② 本次确实处于失败路径（有失败签名，或日志确证端口冲突）。
            // 加②是为了防两类误判：一是日志里**历史**的 EADDRINUSE 被当成本次失败；
            // 二是「健康超时自杀」时我们刚停的进程尚未释放端口（那条路径
            // deterministicFailure 恒为 null，且日志无端口冲突 → 不触发）。
            // ⚠️ 阻塞调用（socket 探测 / Thread.sleep / su）必须切到 IO：
            // 监督循环本身跑在 Dispatchers.Default 上。
            // ── v1.4.24：异常退出 → 无条件彻底清场 ────────────────────────────
            // 宝宝要求：「异常退出时把当前 3080 端口的所有东西全部杀掉，然后重新
            // 完整启动一遍 dsh web，相当于把应用关了重开」。
            //
            // 旧实现只在「端口探测显示被占」时才清场，且只杀 cmdline 匹配
            // files/engine/bin/node 的进程。问题是：进程退出≠端口释放（内核
            // TIME_WAIT / 僵尸孙进程 / su 外壳仍持有 fd），而且包装层会改变
            // cmdline 让匹配失效。结果就是「引擎明明死了，下一轮启动还是
            // EADDRINUSE」的诡异循环。
            //
            // 现在：只要本次是**异常退出**（deterministicFailure != null 说明
            // 启动期自己死了，或日志确证端口冲突），就先做一次彻底清场，
            // 再进入正常退避重试。清场对空闲端口是无害 no-op，代价只是几百毫秒。
            val crashed = deterministicFailure != null || looksLikePortConflict()
            // ── v1.4.24：凭证/token 类失败 → 不无限重启 ──────────────────────
            // 用户反馈「token 过期就显示进程异常退出、反复重启」。这类失败
            // 重启多少次都不会好（需要用户重新登录或换 key），必须收敛成
            // 一个可操作的提示，否则就是永动机式的重启风暴。
            if (crashed && looksLikeAuthFailure()) {
                Log.e(TAG, "auth/credential failure detected — retrying cannot help, asking user to re-authenticate")
                val msg = ctx.getString(R.string.error_auth_expired)
                _state.value = State.Failed(msg)
                BootTrace.fail(ctx, msg)
                return
            }
            if (crashed) {
                withContext(Dispatchers.IO) { reclaimPort() }
                Log.i(TAG, "crash detected — thorough port reclaim done, next attempt starts clean")
            }
            val portEvidence = withContext(Dispatchers.IO) {
                portInUse() && (deterministicFailure != null || looksLikePortConflict())
            }
            if (portEvidence) {
                totalPortReclaims++
                if (totalPortReclaims <= EngineConfig.MAX_PORT_RECLAIMS) {
                    portReclaimCount++
                    withContext(Dispatchers.IO) { reclaimPort() }
                    deterministicFailure = null   // 已处置，不计入 guardian（与配置无关）
                    // ⚠️ v1.4.6（审查 P0）：这里**绝不能**重置 backoffIndex。
                    // 旧实现重置它，且 `portReclaimCount` 满了又归零，于是计数永远在
                    // 0..8 循环、退避永远停在 0~2 秒 —— MAX_RESTART(14) 永远到不了，
                    // 端口被外来进程长期占用时就是「每 2 秒 spawn 一次 node、立刻死」
                    // 的永久循环（耗电、发热、UI 抖动，且永不进 Failed）。
                    delay(1_200)                  // 等端口真正释放再下一轮
                } else {
                    // 累计清场额度用尽：端口被外部进程占着，不是我们能修的问题。
                    // 明确进入 Failed 并给出可读原因，而不是无限重试。
                    Log.e(
                        TAG,
                        "port reclaim exhausted (${EngineConfig.MAX_PORT_RECLAIMS} lifetime attempts) — " +
                            "port ${EngineConfig.DEFAULT_PORT} is held by a foreign process",
                    )
                    _state.value = State.Failed(
                        "port ${EngineConfig.DEFAULT_PORT} is in use by another process",
                    )
                    break
                }
            }
            deterministicFailure?.let { sig ->
                // ⚠️ v1.4.15：插件导致「装完就起不来」的快速通道。
                // ProfileGuardian 要求同签名连续 5 次失败才回滚，而每轮要跑满 45s
                // 健康检查 —— 用户等 4 分钟才恢复正常，观感就是「装了插件 App 废了」。
                // 这里在连续 FAST_ISOLATE_STREAK 次启动失败后，先尝试摘掉最近安装的
                // 第三方 bundle（只动一个、带备份），成功则立刻给它一轮干净的启动额度。
                if (backoffIndex + 1 >= PluginRecovery.FAST_ISOLATE_STREAK &&
                    fastIsolations < PluginRecovery.MAX_FAST_ISOLATIONS
                ) {
                    val isolated = runCatching {
                        PluginRecovery.isolateRecentBundleOnBootFailure(ctx)
                    }.getOrNull()
                    if (isolated != null) {
                        fastIsolations++
                        recoveredPlugin = isolated.packageName
                        Log.e(
                            TAG,
                            "boot failure: isolated recently installed plugin " +
                                "${isolated.packageName} (reason=${isolated.reason})",
                        )
                        runCatching {
                            EngineLog.append(
                                logFile(),
                                "[plugin] isolated ${isolated.packageName} after " +
                                    "${backoffIndex + 1} failed boot(s) — engine could not start",
                            )
                        }
                        // 不重置 backoffIndex（审查 P2）：重置会让下一轮失败立即再次
                        // 满足隔离条件；保留它配合 fastIsolations 上限形成真正的节流。
                        BootTrace.fail(ctx, "isolated plugin ${isolated.packageName}")
                        // 不 return：继续走下面统一的退避，但额度已重置
                    }
                }
                when (
                    runCatching { guardian.onFailure(sig) }
                        .getOrElse { ProfileGuardian.Action.NONE }
                ) {
                    ProfileGuardian.Action.ROLLED_BACK -> {
                        Log.w(TAG, "guardian: rolled back profiles to last-good")
                        backoffIndex = 0 // 给恢复后的启动全新的退避额度
                    }
                    ProfileGuardian.Action.SAFE_MODE -> {
                        Log.e(TAG, "guardian: verified persistent crash, entering SAFE MODE")
                        backoffIndex = 0
                    }
                    ProfileGuardian.Action.NONE -> {}
                }
            }

            // 统一走退避重启
            backoffIndex++
            if (backoffIndex > EngineConfig.MAX_RESTART) {
                _state.value = State.Failed(ctx.getString(R.string.error_restart_failed_streak, EngineConfig.MAX_RESTART))
                return
            }
            val delayMs = EngineConfig.BACKOFF_STEPS[
                (backoffIndex - 1).coerceAtMost(EngineConfig.BACKOFF_STEPS.size - 1)
            ]
            _state.value = State.Backoff(delayMs, backoffIndex)
            delay(delayMs)
        }
    }

    /** 等待本次引擎写出插件健康标记；marker 本身不包含凭证或用户路径。 */
    private suspend fun awaitPluginHealth(
        proc: EngineProcess,
        bootId: String,
    ): PluginRecovery.Health? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline && !proc.exitFuture.isDone) {
            val health = PluginRecovery.readHealth(ctx, bootId)
            if (health != null) {
                Log.i(TAG, "plugin health marker received (ok=${health.ok})")
                return@withContext health
            }
            Thread.sleep(200L)
        }
        null
    }

    /** 最近一次子进程退出码；仅在进程已退出后可读，避免阻塞 */
    private val lastExitStatus: Int?
        get() = process?.exitFuture?.takeIf { it.isDone }?.get()

    private fun spawnEngine(bootId: String): EngineProcess {
        // 【v1.2.32】启动前孤儿清理：App 被强杀/force-stop 后，引擎 node 可能成孤儿
        // 继续占着 3080（普通模式与 App 同 uid；Root 模式需 su）。此时新引擎必然
        // EADDRINUSE 无限重启（用户实测 62 次）。pkill 只匹配我们的引擎路径，
        // 不会误伤其他进程；正常重启（旧引擎已 stop）端口已释放，此处为 no-op。
        cleanupOrphanEngine()
        // 【v1.2.36】记录本次启动的日志起点：token 提取只认这段之后的输出。
        // 否则健康探测先于 stdout 泵送达 "dsh web: ?token=" 行时，会提取到上一次
        // 引擎的旧 token → WebView 加载已失效地址 → "Webpage not available"
        // （模拟器实测：重启后页面 401，engine.log 中新旧 token 并存）。
        logOffsetAtSpawn = runCatching { logFile().length() }.getOrDefault(0L)
        val mode = Privilege.getMode(ctx)
        var suPath: String? = null
        if (mode == PrivMode.ROOT) {
            // Root 保护壳：先备份用户资产 dsh-home，再以 su 整体提权启动引擎。
            // 即便 Root 引擎误改 dsh-home，用户仍可回滚；备份失败不阻断启动，仅告警。
            val backup = Privilege.backupHome(ctx)
            Log.w(TAG, if (backup != null) "ROOT mode: dsh-home backed up to $backup" 
                  else "ROOT mode: WARNING — dsh-home backup failed")
            suPath = Privilege.findSu() ?: throw EngineStartException(
                ctx.getString(R.string.error_root_no_su)
            )
        }
        // v1.2.159：启动前自愈 engine/bin 的 Termux shebang（真机 37 个 wrapper 指向不存在的
        // 路径 → getprop/logcat/node 直接崩）。幂等，仅改第一行。
        runCatching { EngineConfig.healTermuxShebangs(ctx) }
        // v1.4.65：清掉 profile 的 pnpm-workspace.yaml 里 pnpm 11 已删除、且裸写
        // scoped 包名会让整份 YAML 解析失败的历史构建白名单键（`onlyBuiltDependencies:`
        // 等）。不清掉 pnpm 会直接 `× load configuration → unexpected character: '@'`，
        // 于是**所有插件都装不上**。这里在引擎启动前做，老用户升级后第一次开机就自愈，
        // 不必等装插件时才碰运气。
        runCatching {
            if (PluginCenter.healPnpmWorkspace(ctx)) {
                Log.i(TAG, "healed pnpm-workspace.yaml (dropped obsolete build whitelist keys)")
            }
        }
        // 内置 SSH 远程工作区（@xiaopb/dsh-hardssh）：插件随 runtime.zip 发布，
        // 这里补 profile 模块链接并取回它自己的 patch 层。顺序必须保持：
        //   ① 插件层：禁用 fs-sandbox/subprocess、插入 hardssh 接缝行
        //   ② Android 适配层：重复钉住关键 host 行（后应用者生效）
        // 插件缺失/链接失败时 ensureBundledSshPlugin 返回 null —— 引擎照常启动。
        val patchPaths = ArrayList<String>(2)
        runCatching { EngineConfig.ensureBundledSshPlugin(ctx)?.absolutePath }
            .getOrNull()?.let { patchPaths.add(it) }
        runCatching { EngineConfig.ensureAndroidOverlay(ctx).absolutePath }
            .getOrNull()?.let { patchPaths.add(it) }
        return EngineProcess.spawn(
            nodeBin = EngineConfig.nodeBin(ctx),
            entryJs = EngineConfig.dshEntry(ctx),
            cwd = EngineConfig.workspaces(ctx),
            env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT, bootId),
            logFile = logFile(),
            suPath = suPath,
            patchPaths = patchPaths,
        )
    }

    /** 引擎默认端口当前是否真被占用。
     *
     * ⚠️ 旧实现把探测失败（超时/异常）当作「端口空闲」（getOrDefault(false)）——
     * 而 `listen backlog` 打满、引擎事件循环卡住时 connect 恰好会超时，于是
     * **明明占着却被判为空闲，跳过整个清场**，新引擎照样 EADDRINUSE。
     * 现在区分两种结果：连上 = 占用；连不上但内核报 RST/拒绝 = 空闲；
     * 其它（超时/未知）**按占用处理** —— 宁可多清一次（清场对空闲端口是 no-op），
     * 也不能漏掉真正的占用者。 */
    private fun portInUse(timeoutMs: Int = 700): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", EngineConfig.DEFAULT_PORT), timeoutMs)
                true
            }
        } catch (e: java.net.ConnectException) {
            false            // 明确拒绝 = 没人在听 = 空闲
        } catch (e: Exception) {
            true             // 超时/未知一律按占用（保守：清场无害，漏清致命）
        }
    }

    /**
     * 强制回收 3080：清掉所有占着它的引擎 node 残留（v1.3.1）。
     *
     * ⚠️ Root 模式的孤儿为什么清不干净（用户报的二次启动失败）：
     * 引擎以 `su -c "exec node …"` 启动，PTY 的直接子进程只是 **su 外壳** ——
     * `dsh_pty.c` 的 `PR_SET_PDEATHSIG` 挂在外壳上，App 被强杀时信号**传不到 node**，
     * 于是 node 以 uid 0 变孤儿继续监听 3080。而 app uid 的 `pkill` 对 root 进程
     * 必然 EPERM，唯一的指望是 su 那条。
     *
     * 因此这里做三件事：
     *  ① 同 uid pkill（普通模式的孤儿）；
     *  ② su 兜底 pkill（Root 模式的孤儿）—— 用 `findSu` 覆盖 Magisk/KernelSU 路径；
     *  ③ **兜底再确认**：清完仍被占就再补一轮，并把结果写进日志（不再静默）。
     *
     * ⚠️ 必须排除 LAN 反向代理（`lan-web-proxy`）：它的 cmdline 里同样含
     * `files/engine/bin/node`，误杀会让「局域网访问」在每次引擎重启后静默失效。
     */
    private fun reclaimPort() {
        // v1.4.24：默认也改为「按端口找占用者」——旧实现只匹配 cmdline 含
        // files/engine/bin/node 的进程，任何包装层（sh -c、su 外壳、自定义启动器）
        // 都可能漏网，导致「杀了但端口还在占」的假清场。
        reclaimPortByPort()
    }

    /**
     * 彻底清场（v1.4.24，宝宝要求）：
     * 「把当前 3080 端口的所有东西全部杀掉，然后重新完整启动一遍 dsh web，
     *  相当于把应用关了重开」。
     *
     * 与 [reclaimPort] 的区别：**不按 cmdline 匹配，直接按端口找持有者**。
     * 因此无论是引擎 node、su 外壳、僵尸孙进程，还是任何第三方进程占着 3080，
     * 都会被清掉。这是异常退出后的默认处置，不再只在「检测到端口占用」时触发。
     *
     * 安全边界：只针对**监听 3080 的进程**，绝不误伤 LAN 反向代理（它监听别的
     * 端口；即便 cmdline 含 engine node，只要不占 3080 就不动）。
     */
    private fun reclaimPortByPort() {
        val port = EngineConfig.DEFAULT_PORT
        Log.w(TAG, "port $port — thorough reclaim (kill every holder of this port)")
        val d = "$"
        // 用 /proc 找所有监听该端口的 uid（tcp/tcp6 的本地地址:端口列），再杀掉这些 pid。
        // 匹配逻辑：读 /proc/net/tcp{,6}，找 state=0A（LISTEN）且 local_port == 3080，
        // 取其 inode，再扫 /proc/<pid>/fd 找指向 socket:[inode] 的进程。
        val script = """
            PORT_HEX=${'$'}(printf '%04X' $port)
            INODES=''
            for f in /proc/net/tcp /proc/net/tcp6; do
              [ -r "${'$'}f" ] || continue
              INODES="${'$'}INODES ${'$'}(awk -v p="${'$'}PORT_HEX" 'NR>1 && \$4 == "0A" { split(\$2,a,":"); if (a[2]==p) print \$10 }' "${'$'}f" 2>/dev/null)"
            done
            case "${'$'}INODES" in
              *[0-9]*)
                for p in ${'$'}(ls /proc | grep -E '^[0-9]+${'$'}'); do
                  for fd in /proc/${'$'}p/fd/*; do
                    tgt=${'$'}(readlink "${'$'}fd" 2>/dev/null) || continue
                    case "${'$'}tgt" in
                      socket:*)
                        ino=${'$'}{tgt#socket:}
                        ino=${'$'}{ino%]}
                        case " ${'$'}INODES " in
                          *" ${'$'}ino "*) kill -9 ${'$'}p 2>/dev/null; break ;;
                        esac ;;
                    esac
                  done
                done ;;
            esac
            true
        """.trimIndent()
        // ① 同 uid
        runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", script).start()
            if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                runCatching { p.destroy() }
                Log.w(TAG, "thorough reclaim: same-uid sweep timed out (4s)")
            }
        }
        // ② su 兜底（Root 模式孤儿以 uid 0 运行，app uid 杀不动）
        Privilege.runSu(script)
        waitPortFree(2_000)
        if (portInUse(400)) {
            Log.w(TAG, "port still in use after thorough sweep — retrying via su")
            Privilege.runSu(script)
            waitPortFree(2_000)
        }
        if (portInUse(300)) {
            Log.e(TAG, "port $port STILL in use after thorough sweeps — next boot will likely fail")
        } else {
            Log.i(TAG, "port $port is free after thorough reclaim")
        }
    }

    /** 旧口径：只杀 cmdline 含 files/engine/bin/node 的进程（保留给 LAN 代理等场景） */
    private fun reclaimEngineNodesOnly() {
        Log.w(TAG, "port ${EngineConfig.DEFAULT_PORT} in use — reclaiming (orphan engine node)")
        // shell 脚本里的 $ 用 $ 拼接（Kotlin 会把 "$p" 当模板变量解析）
        val d = "$"
        val killCmd =
            "for p in " + d + "(ls /proc | grep -E '^[0-9]+" + d + "'); do " +
                "c=" + d + "(tr '\\0' ' ' < /proc/" + d + "p/cmdline 2>/dev/null) || continue; " +
                "case \"" + d + "c\" in *files/engine/bin/node*) " +
                "case \"" + d + "c\" in *lan-web-proxy*) continue;; esac; " +
                "kill -9 " + d + "p 2>/dev/null;; esac; done; true"
        // ① 同 uid（普通模式孤儿）
        runCatching {
            // v1.4.9：加超时 —— 这个 kill 脚本要遍历 /proc，若某个进程的 cmdline
            // 读取卡住（僵尸/内核态 D 状态），无界 waitFor 会让整个监督循环停住。
            val p = ProcessBuilder("/system/bin/sh", "-c", killCmd).start()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                runCatching { p.destroy() }
                Log.w(TAG, "reclaimPort: same-uid sweep timed out (3s) — continuing")
            }
        }
        // ② su 兜底（Root 模式孤儿；su 不可用时 runSu 自己会静默返回 null）
        Privilege.runSu(killCmd)
        // ③ 等端口真正释放；仍占用则再补一轮 su 清场（可能是刚被拉起的新进程）
        waitPortFree(1_800)
        if (portInUse(400)) {
            Log.w(TAG, "port still in use after first sweep — retrying via su")
            Privilege.runSu(killCmd)
            waitPortFree(1_800)
        }
        if (portInUse(300)) {
            Log.e(TAG, "port ${EngineConfig.DEFAULT_PORT} STILL in use after sweeps — next boot will likely fail")
        }
    }

    /** 轮询等端口释放，返回是否已空闲 */
    private fun waitPortFree(budgetMs: Long): Boolean {
        val until = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < until) {
            if (!portInUse(250)) return true
            Thread.sleep(120)
        }
        return !portInUse(250)
    }

    /** 启动前端口体检：占用则清场（不做静默跳过）。 */
    private fun cleanupOrphanEngine() {
        if (!portInUse()) return
        reclaimPort()
    }

    /** 稳定窗：windowMs 内进程退出返回 false（启动失败），挺过窗口返回 true */
    private suspend fun awaitStable(proc: EngineProcess, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (proc.exitFuture.isDone) return false
            delay(250)
        }
        return !proc.exitFuture.isDone
    }

    /** 轮询 http://127.0.0.1:port 直到响应/超时/子进程提前死亡 */
    private suspend fun pollHealth(port: Int, proc: EngineProcess): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + EngineConfig.HEALTH_TIMEOUT_MS
        val url = "http://127.0.0.1:$port/"
        while (System.currentTimeMillis() < deadline &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
            // 子进程已死就别干等超时——立即返回走快速退避重试
            if (proc.exitFuture.isDone) return@withContext false
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                val code = conn.responseCode
                conn.disconnect()
                // ⚠️ v1.4.46（安全审查 H2）：光看「3080 有响应」不能证明对面是
                // 我们的引擎——任何进程都能在 3080 返回 200。要求引擎自己把带
                // token 的 WebUI 地址写进 engine.log（这是它独有的启动证据），
                // 两者同时成立才算就绪。任一方独占 3080 都无法伪造这个组合。
                if (code in 200..499) {
                    val tokenUrl = extractTokenUrl()
                    if (!tokenUrl.isNullOrBlank()) return@withContext true
                }
            } catch (_: Exception) {
                // 引擎尚未监听，继续等
            }
            delay(EngineConfig.HEALTH_INTERVAL_MS)
        }
        false
    }

    companion object {
        private const val TAG = "EngineSupervisor"
    }
}

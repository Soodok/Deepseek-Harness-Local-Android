package app.dsh.mobile.engine

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.dsh.mobile.A11ySwitch
import app.dsh.mobile.DshAccessibilityService
import app.dsh.mobile.FastMode
import app.dsh.mobile.Handoff
import app.dsh.mobile.NumFmt
import app.dsh.mobile.OverlayState
import app.dsh.mobile.PhoneAsk
import app.dsh.mobile.PhoneTask
import app.dsh.mobile.TaskReport
import app.dsh.mobile.TaskReportNotifier
import app.dsh.mobile.ProtectedPages
import app.dsh.mobile.R
import app.dsh.mobile.ScreenDigest
import app.dsh.mobile.ScreenDoc
import app.dsh.mobile.ScreenVerdict
import app.dsh.mobile.StatusOverlay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Agent 能力桥（m1.37/v1.1.0）：监听 127.0.0.1:3083，把「通知 / 读屏 / 点击」暴露给引擎内 Agent。
 *
 * 与 ShizukuHttpBridge 同构（原生 ServerSocket 环回 HTTP），但【不依赖任何特权模式】——
 * 通知是无障碍是 App 自身能力，普通模式即可用。
 *
 * 路由：
 *   POST /notify        body: {"title":"...", "body":"..."}          → Android 系统通知
 *   GET  /screen        → 当前屏幕可见文本+坐标 JSON（需无障碍服务已开启）
 *   POST /tap           body: {"x":123,"y":456} 或 {"text":「确定」}   → 模拟点击（需无障碍服务）
 *   POST /input         body: {"text":"...", "target":「<输入框提示>」} → 向输入框写文字（v1.2.54）
 *   POST /scroll-find   body: {"text":"...", "tap":true}             → 滚动查找并可选点击（v1.2.54）
 *   POST /idle          body: {"timeoutMs":3000}                     → 等待界面稳定（v1.2.54）
 *   GET  /ext/list      → 扩展清单+三态 JSON（v1.2.1）
 *   POST /ext/install   body: {"id":"python"}                        → 自助安装环境扩展（v1.2.1）
 *
 * 配套注入 engine/bin 的 `notify` 与 `scr` 包装器（EngineConfig.applyAgentGates）。
 */
object AgentBridge {

    private const val TAG = "AgentBridge"
    const val PORT = 3083
    private const val CHANNEL_ID = "agent_notify"

    /** 请求体上限（字节）。超限直接 413，见 handleOnWorker 里的说明。 */
    private const val MAX_BODY_BYTES = 4 shl 20

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    /**
     * 实际绑定到哪个地址（v1.7.1）。
     *
     * 默认 `127.0.0.1`；用户在设置里开启「远程访问手机能力」后变成 `0.0.0.0`。
     * 记下来是因为**日志与 UI 都要报真实值** —— 只写死 127.0.0.1 的话，
     * 开了远程访问后日志仍说只监听环回，排查时会往错方向走。
     */
    @Volatile private var boundAddress: String = "127.0.0.1"

    /** 上一次动作发生时的前台包名（用于回报 foregroundChanged，见 tap 的注释） */
    @Volatile private var lastActionPkg: String? = null

    /** 包名匹配：完全相等，或一方是另一方的前缀（容忍 caller 只写包名前缀） */
    private fun pkgMatches(current: String, expect: String): Boolean =
        current == expect || current.startsWith(expect) || expect.startsWith(current)

    /** 应用上下文（start 时记住，供截图落盘等需要路径的操作使用） */
    @Volatile private var shotCtx: Context? = null

    /**
     * 每个动作入口都调一次：让悬浮窗立刻显示「执行中 · <动作类型>」+ 具体指令。
     *
     * 🔴 v1.2.72 修主人实测「展开悬浮窗也看不到 AI 干活的指令」：
     * 此前**只有 `/tap`** 会通知悬浮窗，滑动 / 按键 / 输入文字 / 滚动查找 / 批量**全都没有**。
     * AI 的实际操作里 tap 只占一部分，所以用户展开也经常一片空白。
     */
    private fun announceAction(actionRes: Int, detail: String) {
        runCatching {
            val c = shotCtx ?: return@runCatching
            StatusOverlay.setStatus(StatusOverlay.labelWorking(c.getString(actionRes)))
            StatusOverlay.setAction(detail)
        }
    }

    /** 单步截图序号 */
    private val shotSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 保留最近几张单步截图（更旧的当场删除 = 主人要的「用不到自动销毁」） */
    private const val KEEP_STEP_SHOTS = 3

    /**
     * 每步自动截图（v1.2.70，主人要求）。
     *
     * 「每做完一个步骤就截一张图，免去 AI 自己截图的过程；假如 AI 用不到图则自动销毁」。
     * 做法：动作派发后截屏落盘到 `files/screens/step-<n>.png`，**路径写进动作响应**；
     * 只保留最近 [KEEP_STEP_SHOTS] 张，更旧的当场删除 —— 这就是「用不到自动销毁」：
     * AI 不读它也不会堆积，要读时它还在。
     *
     * ⚠️ 截图失败绝不影响动作本身（返回 null，响应里 shot 为空串）。
     */
    private fun captureStepShot(): String? = runCatching {
        val c = shotCtx ?: return@runCatching null
        val svc = DshAccessibilityService.instance ?: return@runCatching null
        val shot = svc.screenshotBase64(1500L) ?: return@runCatching null
        val dir = java.io.File(c.filesDir, "screens").apply { mkdirs() }
        val f = java.io.File(dir, "step-${shotSeq.incrementAndGet()}.png")
        f.writeBytes(android.util.Base64.decode(shot.third, android.util.Base64.DEFAULT))
        dir.listFiles { file -> file.name.startsWith("step-") }
            ?.sortedBy { it.lastModified() }
            ?.dropLast(KEEP_STEP_SHOTS)
            ?.forEach { it.delete() }
        f.absolutePath
    }.getOrNull()

    /**
     * 桥访问 token（v1.2.99）。
     *
     * ## 为什么需要
     * 桥监听 `127.0.0.1:3083`，把读屏 / 点击 / 通知 / 手势等能力暴露成 HTTP。
     * 未鉴权时，**同设备上任何 App** 都能直接调用 —— 等于把无障碍能力借给第三方。
     * 加 token 门控后，只有拿到 token 的引擎内 Agent 能调。
     *
     * ## 传递方式
     * token 每次进程启动随机生成（不落盘、不复用），写入引擎环境变量
     * `DSH_BRIDGE_TOKEN`；`_dsh_http.sh` 自动带上 `X-DSH-Token` 头。
     * 因此**只有引擎子进程**（及其派生的 AI 命令）能通过校验。
     *
     * ## 兼容
     * 引擎未重启时（旧进程无 token）→ 会 403。调用方（scr/notify 等闸门脚本）
     * 读的是当前进程环境变量，与引擎同源，故不受影响。
     */
    @Volatile private var token: String? = null

    /** 当前 token（供 EngineConfig 注入环境变量） */
    fun currentToken(): String? = token

    private fun ensureToken(ctx: Context) {
        if (token != null) return
        token = java.util.UUID.randomUUID().toString().replace("-", "")
        Log.i(TAG, "bridge token generated")
    }

    /**
     * 校验请求：`X-DSH-Token` 头或 `?token=` 查询参数须等于当前 token。
     *
     * fail-closed：token 未生成（异常状态）时**拒绝**而非放行 —— 桥暴露的是
     * 高权限设备能力，宁可让 Agent 报错也不要静默开放。
     */
    /**
     * 绑定地址（v1.7.1）。
     *
     * 默认只绑环回 —— 这是历史行为，也是「同设备第三方 App 不能直接调」的前提。
     * 用户在设置里显式开启远程访问、且总开关真的处于开启态时才绑 `0.0.0.0`。
     *
     * 为什么用 `0.0.0.0` 而不是逐个网卡绑：Android 上没有稳定的「监听 WiFi 网卡」
     * 抽象（热点/以太网/多网卡情况各异），逐个绑会在网络切换时掉线。绑全部再靠
     * token 鉴权是这里唯一稳定的做法。
     */
    private fun bindAddress(ctx: Context): String =
        if (RemoteAccess.isEnabled(ctx)) "0.0.0.0" else "127.0.0.1"

    /** 当前实际绑定地址（UI 与日志用）。 */
    fun currentBindAddress(): String = boundAddress

    /**
     * 请求鉴权（v1.7.1 扩展）。
     *
     * 按来源分流，详见 [RemoteAccess.authorise] 的 KDoc：
     * · 环回 → 引擎 token 或配对密钥
     * · 非环回 → **只认配对密钥**
     *
     * 远程访问关闭时行为与旧版完全一致（只认引擎 token），所以默认配置下
     * 既有链路零变化。
     */
    private fun authorised(ctx: Context, client: Socket, headers: List<String>): Boolean {
        val fromLoopback = runCatching {
            val a = client.inetAddress
            a != null && (a.isLoopbackAddress || a.hostAddress == "127.0.0.1")
        }.getOrDefault(true)   // 判不出来时按环回处理：宁可走宽松的旧路径，
                               // 也不要让容器内的既有调用因地址解析失败而全断
        val presented = headers.firstOrNull { it.startsWith("X-DSH-Token:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        if (!RemoteAccess.isEnabled(ctx)) {
            // 远程访问没开 → 与旧版一致（只认引擎 token，且只可能来自环回）
            return Privilege.authorisedBridgeRequest(headers)
        }
        return RemoteAccess.authorise(ctx, fromLoopback, presented)
    }

    /**
     * 在远程访问开关变化后重新绑定。
     *
     * 监听地址是 `ServerSocket` 创建时确定的，改不了 —— 只能关掉重建。
     * 重建期间（毫秒级）容器内的门脚本可能拿到一个连接失败，这是可接受的：
     * 用户点开关时本来就不会同时跑 AI 命令。
     */
    fun rebindForRemoteAccess(ctx: Context) {
        if (server == null) return   // 还没启动过，等 start() 自己读开关
        runCatching { stop() }
        runCatching { start(ctx) }
        Log.i(TAG, "bridge rebound on $boundAddress:$PORT")
    }

    fun start(ctx: Context) {
        if (server != null) return
        shotCtx = ctx.applicationContext
        // v1.2.160：LAN 代理需要 app context 推导引擎目录（部署脚本 / 拉起 node 进程）
        runCatching { LanWebProxy.setContext(ctx) }
        // 桥 token（v1.2.99）：每次启动随机生成，只通过环境变量交给引擎内的 Agent。
        // 同设备第三方 App 拿不到 → 无法调用读屏/点击/通知等能力。
        ensureToken(ctx)
        try {
            val ss = ServerSocket(PORT, 16, java.net.InetAddress.getByName(bindAddress(ctx)))
            server = ss
            boundAddress = ss.inetAddress.hostAddress ?: "127.0.0.1"
            thread = Thread({
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        handle(ctx.applicationContext, client)
                    } catch (e: Exception) {
                        if (!ss.isClosed) Log.w(TAG, "accept: ${e.message}")
                    }
                }
            }, "AgentBridge").apply { isDaemon = true; start() }
            Log.i(TAG, "agent bridge started on $boundAddress:$PORT")
            // 对话完成监听（v1.2.57）：不依赖 AI 主动调 notify —— 只要会话写入
            // turn/end 事件就提示用户（用户反馈「任务完成后通知栏没有任何信息」）。
            TurnWatcher.start(ctx.applicationContext) { session ->
                onTurnEnd(ctx.applicationContext, session)
            }
        } catch (e: Exception) {
            // ⚠️ v1.2.104：**bind 失败绝不能再静默**（主人反馈的起点）。
            // 旧实现只打一行 Log.w，于是「notify / scr / say 全哑」在现场被反复
            // 误判成「无障碍没开 / 桥没就绪」，真因（端口被占）谁也看不见。
            // 现在把占用者一并查出来 —— 典型元凶是继承了桥 listen socket 的历史
            // 孤儿（3083 无 O_CLOEXEC，见 OrphanReaper 顶部说明），并主动告知用户。
            val holder = portHolder()
            Log.e(
                TAG,
                "bridge bind FAILED on 127.0.0.1:$PORT: ${e.message}" +
                    (holder?.let { "; holder=$it" } ?: ""),
            )
            // 也落 engine.log：用户/支持要把这个文件发出来，logcat 抓不到历史
            runCatching {
                java.io.File(EngineConfig.engineRoot(ctx), "engine.log")
                    .appendText("[bridge] bind FAILED on 127.0.0.1:$PORT: ${e.message}; holder=${holder ?: "?"}\n")
            }
            runCatching {
                notify(
                    ctx,
                    JSONObject().apply {
                        put("title", ctx.getString(R.string.bridge_down_title))
                        put(
                            "body",
                            ctx.getString(
                                R.string.bridge_down_body,
                                PORT,
                                holder ?: e.message.orEmpty(),
                            ),
                        )
                    }.toString(),
                )
            }.onFailure { Log.w(TAG, "bridge-down notify failed: ${it.message}") }
        }
    }

    /**
     * 谁占着 [PORT]（v1.2.104）。
     *
     * `/proc/net/tcp` 的 LISTEN 行给出 socket inode 与所有者 uid；再遍历
     * `/proc/<pid>/fd` 反查哪个进程打开着这个 inode（**同 uid 才看得见**，
     * 别的 uid 直接跳过 —— 不做猜测）。返回形如 `uid=10228 inode=39939 pid=6760(node ...)`。
     */
    private fun portHolder(): String? = runCatching {
        val f = java.io.File("/proc/net/tcp").readLines().drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { c -> c.size > 9 && c[1].endsWith("%04X".format(PORT)) && c[3] == "0A" }
            ?: return@runCatching null
        val inode = f[9]
        val uid = f[7]
        val owner = File("/proc").listFiles()?.firstNotNullOfOrNull { p ->
            val n = p.name.toIntOrNull() ?: return@firstNotNullOfOrNull null
            if (!p.isDirectory) return@firstNotNullOfOrNull null
            val hit = runCatching {
                File(p, "fd").list()?.any { fd ->
                    runCatching {
                        java.nio.file.Files.readSymbolicLink(File(p, "fd/$fd").toPath()).toString()
                    }.getOrNull()?.contains("socket:[$inode]") == true
                } == true
            }.getOrDefault(false)
            if (!hit) return@firstNotNullOfOrNull null
            val cmd = runCatching {
                File(p, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim()
            }.getOrNull().orEmpty()
            "pid=$n(${cmd.take(50)})"
        }
        "uid=$uid inode=$inode " + (owner ?: "owner-hidden")
    }.getOrNull()

    /**
     * 一轮对话结束时的处理（v1.2.57）。
     * 发系统通知 + 悬浮窗高亮；若用户开了 TTS 播报则一并朗读。
     *
     * 与 AI 主动调 `notify` 的关系：两者互补 —— AI 的 notify 可带自定义内容
     * （如「构建完成，测试全过」），本监听是**兜底**：AI 忘了调也不会漏提示。
     */
    private fun onTurnEnd(ctx: Context, session: String) {
        runCatching {
            Log.i(TAG, "turn/end detected in $session")
            // v1.7.0：一轮对话结束 ≠ 任务结束。任务还在进行时（模型下一轮还要接着
            // 点屏幕）刷「完成」绿会让用户以为活干完了。只有任务不活跃才提示。
            if (!task().snapshot().active) {
                StatusOverlay.flashComplete(ctx.getString(R.string.overlay_turn_done))
            }
            notify(
                ctx,
                JSONObject().apply {
                    put("title", ctx.getString(R.string.notif_turn_done_title))
                    put("body", ctx.getString(R.string.notif_turn_done_body, session.take(8)))
                }.toString(),
            )
            // TTS 播报（v1.2.57）：用户可在设置里开关；默认关闭避免打扰。
            // 与 say 门脚本共用 TtsManager（系统 TTS，离线免费）。
            if (ttsOnComplete(ctx)) {
                Thread(
                    { TtsManager.speak(ctx, ctx.getString(R.string.overlay_tts_done), false) },
                    "dsh-tts-turn",
                ).apply { isDaemon = true; start() }
            }
        }.onFailure { Log.w(TAG, "turn-end handling failed: ${it.message}") }
    }

    /** 是否开启「完成时语音播报」（设置页开关；缺省关闭） */
    private fun ttsOnComplete(ctx: Context): Boolean =
        runCatching {
            ctx.getSharedPreferences("dsh_ui", Context.MODE_PRIVATE)
                .getBoolean("tts_on_complete", false)
        }.getOrDefault(false)

    fun stop() {
        runCatching { server?.close() }
        server = null; thread = null
        Log.i(TAG, "agent bridge stopped")
    }

    /**
     * 请求处理线程池（**必须有界**）。
     *
     * 旧实现每 accept 一个连接就 `Thread(...).start()` 一个裸线程，而同设备任意 App
     * 都能连 127.0.0.1:3083（鉴权要读完请求头才做）—— 刷连接就能把线程数打到 OOM。
     * 4 线程 + 64 队列：正常用量（Agent 串行调门脚本）远远够；超了直接断开连接，
     * 让刷连接的人拿不到任何东西，也不影响正常请求。
     */
    private val reqExec = java.util.concurrent.ThreadPoolExecutor(
        4,
        4,
        30L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "AgentBridge-req").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )

    private fun handle(ctx: Context, client: Socket) {
        try {
            reqExec.execute { handleOnWorker(ctx, client) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "request rejected: bridge queue full")
            runCatching { client.close() }
        }
    }

    private fun handleOnWorker(ctx: Context, client: Socket) {
        try {
                client.soTimeout = 5_000
                // ⚠️ v1.2.43 修复：必须按**字节**读头与读体。Content-Length 是字节数而
                // 旧实现用 CharArray/Reader 按「字符数」读 → UTF-8 多字节字符（中文每字 3 字节）
                // 永远等不到足量字符 → SocketTimeoutException → 返回
                // {"ok":false,"error":"Read timed out"}。实测：AI 用 `say` 说中文必挂、
                // `/notify` 带中文同挂（App 自身测试不走 HTTP，故看起来「TTS 是好的」）。
                val ins = client.getInputStream()
                val head = java.io.ByteArrayOutputStream()
                var crlf = 0
                while (head.size() < (16 shl 10)) {
                    val b = ins.read()
                    if (b < 0) break
                    head.write(b)
                    crlf = when {
                        crlf == 0 && b == 13 -> 1
                        crlf == 1 && b == 10 -> 2
                        crlf == 2 && b == 13 -> 3
                        crlf == 3 && b == 10 -> 4
                        b == 13 -> 1
                        else -> 0
                    }
                    if (crlf == 4) break
                }
                val lines = String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")
                val parts = (lines.firstOrNull() ?: return).split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')
                val query = rawPath.substringAfter('?', "")
                var contentLength = 0
                lines.drop(1).forEach { l ->
                    if (l.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = l.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                // 超限必须**拒绝**，不能静默截断：截断后的 JSON 解析失败会报 400/500，
                // 看起来像客户端 bug，实际是「桥悄悄丢了半个 body」。4MB 对所有端点
                // 都远超实际需要（最大的 /batch、/vision/crop 也到不了）。
                if (contentLength > MAX_BODY_BYTES || contentLength < 0) {
                    Log.w(TAG, "payload too large: $contentLength bytes on $method $path")
                    runCatching { respond(client, 413, """{"ok":false,"error":"payload too large"}""") }
                    return
                }
                val bodyBytes = ByteArray(contentLength)
                var n = 0
                while (n < bodyBytes.size) {
                    val r = ins.read(bodyBytes, n, bodyBytes.size - n)
                    if (r < 0) break
                    n += r
                }
                val body = if (n > 0) String(bodyBytes, 0, n, StandardCharsets.UTF_8) else ""

                // 桥鉴权（v1.2.99）：只允许携带本机 token 的请求。
                // 为什么需要：桥监听 127.0.0.1:3083，**同设备上任何 App 都能访问** ——
                // 未鉴权时第三方应用可以直接读屏、点击、发通知（等于把无障碍能力
                // 借给任意 App）。token 每次启动随机生成、只写进引擎环境变量，
                // 因此只有引擎内的 Agent 拿得到。
                if (!authorised(ctx, client, lines)) {
                    Log.w(TAG, "unauthorised request: $method $path")
                    respond(client, 403, """{"ok":false,"error":"unauthorised"}""")
                    return
                }

                val (status, json) = route(ctx, method, path, query, body)
                respond(client, status, json)
            } catch (e: Exception) {
                Log.w(TAG, "handle: ${e.message}")
                runCatching { respond(client, 500, """{"ok":false,"error":"${e.message}"}""") }
            } finally {
                runCatching { client.close() }
            }
    }

    /**
     * All bridge endpoints are registered once and dispatched by method + path.
     * Existing handlers remain untouched so this refactor does not alter behavior.
     */
    private val routes: Map<String, Route> = mapOf(
        "POST /notify" to Route("POST") { c, _, b -> notify(c, b) },
        "GET /screen" to Route("GET") { _, q, _ -> screen(q) },
        "GET /ocr" to Route("GET") { _, q, _ -> ocr(q) },
        "GET /screen-changed" to Route("GET") { _, _, _ -> screenChanged() },
        "POST /pick" to Route("POST") { _, _, b -> pick(b) },
        "GET /local/status" to Route("GET") { c, _, _ -> localStatus(c) },
        "GET /frame-diff" to Route("GET") { _, _, _ -> frameDiff() },
        "POST /frame-wait" to Route("POST") { _, _, b -> frameWait(b) },
        "POST /loop/run" to Route("POST") { c, _, b -> loopRun(c, b) },
        "POST /vision/crop" to Route("POST") { c, _, b -> visionCrop(c, b) },
        "POST /vision/track" to Route("POST") { c, _, b -> visionTrack(c, b) },
        "POST /vision/tap" to Route("POST") { c, _, b -> visionTap(c, b) },
        "POST /vision/drag" to Route("POST") { c, _, b -> visionDrag(c, b) },
        "GET /vision/list" to Route("GET") { c, _, _ -> visionList(c) },
        "GET /memory/list" to Route("GET") { c, _, _ -> 200 to SceneMemory.listJson(c) },
        "GET /memory/stats" to Route("GET") { c, _, _ -> 200 to JSONObject().put("ok", true).put("summary", SceneMemory.stats(c)).toString() },
        "POST /memory/lookup" to Route("POST") { c, _, b -> memoryLookup(c, b) },
        "POST /memory/store" to Route("POST") { c, _, b -> memoryStore(c, b) },
        "POST /memory/confirm" to Route("POST") { c, _, b -> memoryConfirm(c, b) },
        "POST /memory/clear" to Route("POST") { c, _, _ ->
            SceneMemory.clear(c)
            200 to """{"ok":true,"cleared":true}"""
        },
        // ---- 无障碍记忆系统 v2（M1：轨迹录制）----
        "GET /trace/list" to Route("GET") { c, q, _ -> MemoryRoutes.traceList(c, q) },
        "GET /trace/get" to Route("GET") { c, q, _ -> MemoryRoutes.traceGet(c, q) },
        "POST /trace/clear" to Route("POST") { c, _, _ -> MemoryRoutes.traceClear(c) },
        "POST /trace/record" to Route("POST") { _, _, b -> MemoryRoutes.traceRecord(b) },
        "GET /memory/v2/list" to Route("GET") { c, q, _ -> MemoryRoutes.memList(c, q) },
        "GET /memory/v2/get" to Route("GET") { c, q, _ -> MemoryRoutes.memGet(c, q) },
        // 用 POST 而不是 DELETE：桥的 HTTP 层只解析 GET/POST，DELETE 请求根本进不了路由表。
        "POST /memory/v2/delete" to Route("POST") { c, q, b -> MemoryRoutes.memDelete(c, q.ifBlank { b }) },
        "POST /memory/v2/state" to Route("POST") { c, _, b -> MemoryRoutes.memState(c, b) },
        "POST /memory/v2/upsert" to Route("POST") { c, _, b -> MemoryRoutes.memUpsert(c, b) },
        "POST /memory/run" to Route("POST") { c, _, b -> MemoryRoutes.memRun(c, b) },
        "POST /memory/v2/learn" to Route("POST") { c, _, b -> MemoryRoutes.memLearn(c, b) },
        "POST /memory/v2/label" to Route("POST") { c, _, b -> MemoryRoutes.memLabel(c, b) },
        "POST /memory/v2/lookup" to Route("POST") { c, _, b -> MemoryRoutes.memLookup(c, b) },
        "GET /memory/v2/stats" to Route("GET") { c, _, _ -> MemoryRoutes.memStats(c) },
        "POST /memory/v2/migrate" to Route("POST") { c, _, _ -> MemoryRoutes.memMigrate(c) },
        "POST /memory/v2/config" to Route("POST") { _, _, b -> MemoryRoutes.memConfig(b) },
        "POST /memory/v2/patch" to Route("POST") { c, _, b -> MemoryRoutes.memPatch(c, b) },
        "POST /memory/v2/extend" to Route("POST") { c, _, b -> MemoryRoutes.memExtend(c, b) },
        "POST /memory/v2/fork" to Route("POST") { c, _, b -> MemoryRoutes.memFork(c, b) },
        "POST /memory/v2/rollback" to Route("POST") { c, _, b -> MemoryRoutes.memRollback(c, b) },
        "POST /local/ensure" to Route("POST") { c, _, _ -> localEnsure(c) },
        "GET /daemon/status" to Route("GET") { _, _, _ -> daemonStatus() },
        "POST /daemon/stop" to Route("POST") { _, _, _ -> daemonStop() },
        "POST /tap" to Route("POST") { _, _, b -> tap(b) },
        "GET /screenshot" to Route("GET") { _, q, _ -> screenshot(q) },
        "POST /gesture" to Route("POST") { _, _, b -> gesture(b) },
        "POST /key" to Route("POST") { _, _, b -> key(b) },
        "POST /wait" to Route("POST") { _, _, b -> wait(b) },
        "POST /input" to Route("POST") { _, _, b -> input(b) },
        "POST /scroll-find" to Route("POST") { _, _, b -> scrollFind(b) },
        "POST /scroll-center" to Route("POST") { _, _, b -> scrollCenter(b) },
        "GET /diff" to Route("GET") { _, _, _ -> diffScreen() },
        "POST /idle" to Route("POST") { _, _, b -> idle(b) },
        "POST /batch" to Route("POST") { _, _, b -> batch(b) },
        "GET /interference" to Route("GET") { _, q, _ -> interference(q) },
        "POST /overlay" to Route("POST") { _, _, b -> overlay(b) },
        "POST /launch" to Route("POST") { c, _, b -> launch(c, b) },
        "POST /launch-intent" to Route("POST") { c, _, b -> launchIntent(c, b) },
        "POST /tap-id" to Route("POST") { _, _, b -> tapId(b) },
        "POST /dev/node" to Route("POST") { c, _, b -> nodeAct(c, b) },
        "GET /dev/windows" to Route("GET") { c, _, _ ->
            // 纯读取、无参数：窗口列表本身就是一次快照。
            if (!CapabilityGate.can(c, CapabilityGate.STATUS)) {
                return@Route 403 to """{"ok":false,"error":"capability disabled","capability":"status"}"""
            }
            val svc = DshAccessibilityService.instance
                ?: return@Route 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            CapabilityGate.audit(c, "dev.windows")
            // runCatching 的两条分支都要是 `Pair<Int,String>`；getOrElse 里写的是
            // 字符串模板，Kotlin 会推断成 Serializable，必须显式给 Pair。
            val out: Pair<Int, String> = runCatching { 200 to svc.windowListJson() }
                .getOrElse { 500 to """{"ok":false,"error":"${it.message}"}""" }
            out
        },
        "GET /dev/watch" to Route("GET") { c, q, _ ->
            // GET 也走同一个 JSON 入口：把 k=v&k2=v2 拼成 JSONObject，
            // 免得 WatchCap 里为 GET/POST 各写一套解析。
            val o = JSONObject()
            parseQuery(q).forEach { (k, v) ->
                // since 必须是数字，否则 optLong 会静默回 0（增量拉取直接失效）
                if (k == "since") o.put(k, v.toLongOrNull() ?: 0L) else o.put(k, v)
            }
            app.dsh.mobile.engine.cap.WatchCap.handle(c, o.toString())
        },
        "POST /dev/watch" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.WatchCap.handle(c, b) },
        "POST /tap-ref" to Route("POST") { _, _, b -> tapRef(b) },
        // 交接给用户（v1.6.21）：登录/验证码/支付这类页面不该由 agent 点。
        // 阻塞等待放在独立请求里（桥每个连接一个线程，不会卡住其他调用）。
        "POST /handoff" to Route("POST") { _, _, b -> handoffBegin(b) },
        "GET /handoff" to Route("GET") { _, q, _ -> handoffStatus(q) },
        "POST /handoff/done" to Route("POST") { _, _, _ -> handoffDone() },
        // 用户随时可以叫停。这是安全入口，不是体验功能。
        "POST /task/stop" to Route("POST") { _, _, b -> taskStop(b) },
        "POST /task/pause" to Route("POST") { _, _, _ -> taskPause() },
        "POST /task/resume" to Route("POST") { _, _, _ -> taskResume() },
        "GET /task" to Route("GET") { _, _, _ -> taskStatus() },
        "POST /task/say" to Route("POST") { _, _, b -> taskSay(b) },
        "POST /ask" to Route("POST") { _, _, b -> askBegin(b) },
        "GET /ask" to Route("GET") { _, _, _ -> askStatus() },
        "POST /ask/answer" to Route("POST") { _, _, b -> askAnswer(b) },
        "POST /ask/cancel" to Route("POST") { _, _, _ -> askCancel() },
        "POST /ask/result" to Route("POST") { _, _, _ -> askResult() },
        "POST /task/report" to Route("POST") { _, _, b -> taskReport(b) },
        "POST /task/finish" to Route("POST") { _, _, b -> taskFinish(b) },
        "POST /task/allow" to Route("POST") { _, _, b -> taskAllow(b) },
        "GET /task/allow" to Route("GET") { _, _, _ -> taskAllowStatus() },
        "POST /task/point" to Route("POST") { _, _, b -> taskPoint(b) },
        "POST /wait-text" to Route("POST") { _, _, b -> waitText(b) },
        "POST /scroll-edge" to Route("POST") { _, _, b -> scrollEdge(b) },
        "GET /notif" to Route("GET") { _, _, _ -> notif() },
        "GET /ext/list" to Route("GET") { c, _, _ -> extList(c) },
        "GET /plugin/list" to Route("GET") { _, _, _ -> pluginList() },
        "POST /plugin/install" to Route("POST") { _, _, b -> pluginInstall(b) },
        "GET /plugin/killswitch" to Route("GET") { c, q, _ -> pluginKillSwitch(c, q, "") },
        "POST /plugin/killswitch" to Route("POST") { c, q, b -> pluginKillSwitch(c, q, b) },
        "GET /ext/check" to Route("GET") { c, _, _ -> extCheck(c) },
        // 卸载（v1.7.10）：扩展中心 UI 早就能卸，桥却只有 install —— AI 装错东西
        // 之后没有任何自助退路，只能让用户去点界面。与 install 对称补齐。
        "POST /ext/uninstall" to Route("POST") { c, _, b -> extUninstall(c, b) },
        "POST /plugin/uninstall" to Route("POST") { c, _, b -> pluginUninstall(b) },
        // 日志尾部（v1.7.10）：LogViewerActivity 一直在读这两个文件，但 AI 只能靠
        // /diag 拿截断的 2500 字符 HTML。排障第一步（看 engine.log / boot.log）
        // 因此对 AI 不可达 —— 它只能去猜。
        "GET /dev/logs" to Route("GET") { c, q, _ -> devLogs(c, q) },
        // 引擎起不来时的结构化诊断与修复（v1.5.3）。与 /diag 的分工：
        // /diag 是给人看的 HTML 页面（截图发反馈用），这两条是给 AI 读的 JSON。
        "GET /repair/diagnose" to Route("GET") { c, _, _ -> repairDiagnose(c) },
        "POST /repair/apply" to Route("POST") { c, _, b -> repairApply(c, b) },
        "GET /diag" to Route("GET") { c, _, _ -> diag(c) },
        "POST /say" to Route("POST") { c, _, b -> say(c, b) },
        "GET /say" to Route("GET") { c, q, _ -> sayGet(c, q) },
        "POST /open-file" to Route("POST") { c, _, b -> openFile(c, b) },
        "POST /ext/install" to Route("POST") { c, _, b -> extInstall(c, b) },
        "GET /dev/ping" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.StatusCap.ping(c) },
        "GET /dev/status" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.StatusCap.status(c) },
        "GET /dev/sensors" to Route("GET") { c, q, _ -> app.dsh.mobile.engine.cap.SensorCap.read(c, q) },
        // 位置查询：与 dev/geofence 的被动触发互补，这里回答「此刻在哪」。
        "GET /dev/location" to Route("GET") { c, q, _ -> app.dsh.mobile.engine.cap.LocationCap.read(c, q) },
        // 包管理：list/info 纯读，install/uninstall 强制走系统确认页（返回 202）。
        // v1.7.10：GET 也要吃 query。此前写死 "{}"，于是目录里声明的
        // limit / include_system / sort / q 全部静默无效 —— 按文档传参永远拿到默认 200 条。
        "GET /dev/packages" to Route("GET") { c, q, _ ->
            app.dsh.mobile.engine.cap.PackageCap.handle(
                c, queryJson(q).put("op", "list").toString(),
            )
        },
        "POST /dev/packages" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.PackageCap.handle(c, b) },
        // 拍照：返回 JPEG 的 base64，由引擎侧决定是否落盘。
        "POST /dev/camera" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.CameraCap.capture(c, b) },
        // Linux 容器（整组受 cap.linux 门控，默认关闭 —— exec 等价于容器内 shell）
        "GET /dev/linux/status" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.LinuxCap.status(c) },
        "GET /dev/linux/distros" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.LinuxCap.distros(c) },
        "POST /dev/linux/switch" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.LinuxCap.switch(c, b) },
        "POST /dev/linux/deploy" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.LinuxCap.deploy(c, b) },
        "POST /dev/linux/exec" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.LinuxCap.exec(c, b) },
        "GET /dev/notifications" to Route("GET") { c, q, _ -> app.dsh.mobile.engine.cap.NotifyCap.read(c, q) },
        "POST /dev/clipboard" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.ControlCap.clipboard(c, b) },
        "POST /dev/control" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.ControlCap.control(c, b) },
        "POST /dev/remind" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.SchedCap.create(c, b) },
        "GET /dev/remind-list" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.SchedCap.list(c) },
        "POST /dev/remind-cancel" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.SchedCap.cancel(c, b) },
        // 底层射频能力：蓝牙报文收发 / WiFi 链路与抓包。能力边界见 RadioCap 的注释。
        "POST /dev/bt" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.RadioCap.bt(c, b) },
        "POST /dev/wifi-radio" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.RadioCap.wifi(c, b) },
        // 按 App（UID）的网络封锁与流量查询。两档：查询 B 档（不需 root），
        // 封锁 C 档（iptables），成功判据是计数器回读而不是 ping（见 NetCap 注释）。
        "GET /dev/net" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.NetCap.net(c, "{}") },
        "POST /dev/net" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.NetCap.net(c, b) },
        // 手机当 BLE 外设（GATT Server）。与 /dev/bt 的方向相反：
        // /dev/bt 是手机去连别人，这里是被别人连。
        "POST /dev/ble" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.BlePeripheralCap.peripheral(c, b) },
        "GET /dev/ble" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.BlePeripheralCap.peripheral(c, "{}") },
        // 屏幕变化检测（不存视频）：只回答「变没变、哪块在变」，用于廉价轮询。
        "POST /dev/screenwatch" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.ScreenWatchCap.control(c, b) },
        "GET /dev/screenwatch" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.ScreenWatchCap.status(c) },
        // 手机冒充蓝牙键盘（HID Device）。与 /dev/uhid（本机虚拟键盘）互补：
        // 那个是「AI 按本机」，这个是「AI 按另一台设备」。
        "POST /dev/bt-hid" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.BtHidCap.hid(c, b) },
        "GET /dev/bt-hid" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.BtHidCap.hid(c, "{}") },
        // 振动摩斯电码 / 传感器场景推断 / Doze 白名单自助（P2 三个小能力）
        "POST /dev/pulse" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.PulseCap.morse(c, runCatching { org.json.JSONObject(b.ifBlank { "{}" }) }.getOrNull() ?: org.json.JSONObject()) },
        "POST /dev/scene" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.PulseCap.scene(c, runCatching { org.json.JSONObject(b.ifBlank { "{}" }) }.getOrNull() ?: org.json.JSONObject()) },
        "POST /dev/doze" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.PulseCap.doze(c, runCatching { org.json.JSONObject(b.ifBlank { "{}" }) }.getOrNull() ?: org.json.JSONObject()) },
        "GET /dev/doze" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.PulseCap.doze(c, org.json.JSONObject().put("op", "status")) },
        // 上下文总线 + 主动提醒闸门（P1-3）与通知摘要（P2-3）。
        // 闸门默认「不打扰」：静音时段/息屏/锁屏/Doze/冷却期内一律拒，并列出拒绝理由。
        "GET /dev/context" to Route("GET") { c, q, _ ->
            app.dsh.mobile.engine.cap.ContextCap.context(c, queryJson(q))
        },
        "POST /dev/context" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.ContextCap.context(c, runCatching { org.json.JSONObject(b.ifBlank { "{}" }) }.getOrNull() ?: org.json.JSONObject()) },
        "POST /dev/nudge" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.ContextCap.gate(c, runCatching { org.json.JSONObject(b.ifBlank { "{}" }) }.getOrNull() ?: org.json.JSONObject()) },
        "GET /dev/notify-digest" to Route("GET") { c, q, _ ->
            // 目录声明 limit（默认 50），此前 GET 丢弃 query → limit 恒为 50。
            app.dsh.mobile.engine.cap.ContextCap.digest(c, queryJson(q))
        },
        // 「AI 的手」可视化（P1-4）：纯视觉层，FLAG_NOT_TOUCHABLE，永不吃事件。
        "POST /dev/hand" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.HandOverlay.control(c, runCatching { org.json.JSONObject(b.ifBlank { "{}" }) }.getOrNull() ?: org.json.JSONObject()) },
        "GET /dev/hand" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.HandOverlay.control(c, org.json.JSONObject()) },
        // 代用户回复通知（RemoteInput）。与「读通知」分开授权，原因见 NotifyCap 注释。
        "POST /dev/notify-reply" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.NotifyCap.reply(c, b) },
        // Shizuku / ADB shell 通道（uid 2000 / u:r:shell:s0）。刻意做成 HTTP 端点而非
        // 门脚本直连：Shizuku 是 App 进程持有的 binder，引擎子进程拿不到，只能经桥中转。
        // 这也是它与 `root` 门脚本最本质的区别（那个靠「引擎本身 uid 0」）。
        "GET /dev/shz" to Route("GET") { c, q, _ ->
            // 目录写 op=status|exec 且 exec 要 cmd / timeout_ms —— GET 必须吃 query，
            // 否则 `dev api GET '/dev/shz?op=exec&cmd=...'` 只会回 status。
            val req = queryJson(q)
            app.dsh.mobile.engine.cap.ShizukuCap.handle(c, req.put("op", req.optString("op", "status")))
        },
        "POST /dev/shz" to Route("POST") { c, _, b ->
            app.dsh.mobile.engine.cap.ShizukuCap.handle(
                c,
                runCatching { org.json.JSONObject(b.ifBlank { "{}" }) }.getOrNull()
                    ?: org.json.JSONObject(),
            )
        },
        // App 自身设置的读取与修改（v1.6.2）。读全部但凭据脱敏；写全部并直接生效。
        // 能力开关 cap.* 刻意不在此列 —— 那是用户对 AI 的授权，见 SettingsCap 注释。
        "GET /dev/settings" to Route("GET") { c, q, _ ->
            // 目录写 GET 默认 list、也可 op=get&key=…；此前 GET 一律 list，
            // 按文档 `?op=get&key=theme_mode` 静默走成全量列表。
            val req = queryJson(q)
            app.dsh.mobile.engine.cap.SettingsCap.handle(
                c, req.put("op", req.optString("op", "list")),
            )
        },
        "POST /dev/settings" to Route("POST") { c, _, b ->
            app.dsh.mobile.engine.cap.SettingsCap.handle(
                c, runCatching { org.json.JSONObject(b.ifBlank { "{}" }) }.getOrNull() ?: org.json.JSONObject(),
            )
        },
        // 媒体控制走 MediaSession（需要通知访问权限，不是 MEDIA_CONTENT_CONTROL）。
        "GET /dev/media" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.MediaCap.list(c) },
        "POST /dev/media" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.MediaCap.control(c, b) },
        // 个人信息：日历 / 联系人 / 短信。三域权限各自独立，缺哪个报哪个。
        "GET /dev/calendar" to Route("GET") { c, q, _ -> app.dsh.mobile.engine.cap.PersonalCap.calendar(c, q) },
        "POST /dev/calendar" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.PersonalCap.calendarAdd(c, b) },
        "GET /dev/contacts" to Route("GET") { c, q, _ -> app.dsh.mobile.engine.cap.PersonalCap.contacts(c, q) },
        "GET /dev/sms" to Route("GET") { c, q, _ -> app.dsh.mobile.engine.cap.PersonalCap.sms(c, q) },
        "POST /dev/sms" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.PersonalCap.smsCompose(c, b) },
        // 通话记录。2026-11-01 审计：READ_CALL_LOG 早就声明并授到了，却没端点用它。
        "GET /dev/calls" to Route("GET") { c, q, _ -> app.dsh.mobile.engine.cap.PersonalCap.calls(c, q) },
        // NFC（读标签）与地理围栏。NFC 无法"主动扫"，只能在前台接收贴近的标签。
        "GET /dev/nfc" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.RadioExtraCap.nfcStatus(c) },
        "POST /dev/nfc" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.RadioExtraCap.nfc(c, b) },
        // GET 只做列表（v1.7.10）：此前只有 POST，`op=list` 走 POST 之外没有只读入口，
        // 而围栏是「持续的被动触发」状态，AI 需要能纯读、不产生副作用地看现状。
        "GET /dev/geofence" to Route("GET") { c, _, _ ->
            app.dsh.mobile.engine.cap.RadioExtraCap.geofence(c, """{"op":"list"}""")
        },
        "POST /dev/geofence" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.RadioExtraCap.geofence(c, b) },
        // 弹窗快路径与动作生效校验：把「点完不知道成不成」变成可判定的结果。
        "POST /dev/dialog" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.DialogCap.scan(c, b) },
        "POST /dev/verify" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.DialogCap.verify(c, b) },
        // 屏幕录制（MediaProjection）。每次开始必须用户在系统弹窗里点头，无法静默。
        "POST /dev/record" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.RecordCap.control(c, b) },
        "GET /dev/record" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.RecordCap.status(c) },
        // 虚拟 HID 设备（v1.5.0-beta.13）：/dev/uhid 注册真实外设键盘/鼠标/媒体键。
        // 与「模拟点击」的本质区别：系统把它当外接硬件，没有注入标记。需要 root。
        "GET /dev/hid" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.HidCap.status(c) },
        "POST /dev/hid" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.HidCap.control(c, b) },
        // 权限自助：查状态 / 全量 pm grant / 列还差哪些。AI 用 getperm、grantperm 门脚本调。
        "GET /dev/perms" to Route("GET") { c, q, _ -> app.dsh.mobile.engine.cap.PermCap.missing(c, q) },
        "POST /dev/perms" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.PermCap.grant(c, b) },
        "POST /dev/perms/revoke" to Route("POST") { c, _, b -> app.dsh.mobile.engine.cap.PermCap.revoke(c, b) },
        "GET /dev/perms/status" to Route("GET") { c, _, _ -> app.dsh.mobile.engine.cap.PermCap.status(c) },
        // 端点自描述目录（v1.5.0-beta.11）：dev 门脚本从这里拉清单自渲染，
        // 不再硬编码。新增端点只要登记一次 EndpointCatalog，dev 立刻能发现。
        "GET /dev/help" to Route("GET") { c, q, _ -> EndpointCatalog.help(c, q) },
        // 真实路由表拍平清单，供 `dev api` 自检与查漏（目录写错能立刻看出来）。
        "GET /dev/routes" to Route("GET") { c, _, _ -> devRoutes(c) },
    )

    /** `GET /dev/routes` —— 真实路由表的拍平清单 + 目录一致性核对。 */
    private fun devRoutes(@Suppress("UNUSED_PARAMETER") ctx: Context): Pair<Int, String> {
        val arr = JSONArray()
        routes.keys.sorted().forEach { arr.put(it) }
        val problems = runCatching { EndpointCatalog.verifyAgainst(routes.keys) }.getOrDefault(emptyList())
        if (problems.isNotEmpty()) {
            Log.w(TAG, "endpoint catalog mismatch: ${problems.joinToString("; ")}")
        }
        return 200 to org.json.JSONObject()
            .put("ok", true)
            .put("count", routes.size)
            .put("routes", arr)
            .put("catalog_problems", org.json.JSONArray(problems))
            .toString()
    }

    private fun route(ctx: Context, method: String, path: String, query: String, body: String): Pair<Int, String> {
        // Preserve the existing action notification side effect before dispatch.
        val acts = method == "POST" && isA11yAction(path, body)
        if (acts) noteA11yOperation(ctx, path)

        // 用户叫停闸门（v1.6.22）：优先级高于保护页闸门。
        // 行为动作是异步派发的，攒在队列里的手势不会因为一个标记而消失 ——
        // 必须在入口就拒掉。放在保护页前面是因为：
        // 用户按了停止，不论当前是什么页，都不该再动。
        if (acts) {
            val stopper = taskGate(path)
            if (stopper != null) return stopper
        }

        // 从这里开始，动作已经进入成功路径（taskGate 已 callStarted），
        // 必须用 try/finally 收尾 —— 中途 return（403 保护页、未知路由）
        // 不减在飞数的话，inFlight 会永远不归零，任务永远不超时。
        var status = 0
        try {
            // 保护页闸门（v1.6.20）：此前只有 /tap-ref 会检查，于是换个端点
            // （/tap、/tap-id、/gesture、/key、/input…）就能绕过安全层。
            // 现在所有动作端点统一过闸。/tap-ref 除外 —— 它自己用反重建后的文档
            // 做更严格的判定，不应在不同口径上先被这个宽松判据拦下。
            if (acts && path != "/tap-ref") {
                // 逃离保护页（v1.7.6）：/key 的 back/home/recents 只做导航，给不了权限、
                // 改不了设置，却是在「设置-搜索」这类保护页上唯一能把设备带回可用状态的动作。
                // 一律拦掉的后果是 AI 被困在保护页里 —— 实测 /key home 也被 403。
                val escape = path == "/key" && app.dsh.mobile.ProtectedPages.isNavigationKey(body)
                if (!escape) {
                    val blocked = protectedPageGuard(path)
                    if (blocked != null) return blocked
                }
            }
            val result = routes["$method $path"]?.handle?.invoke(ctx, query, body)
                ?: (404 to """{"ok":false,"error":"unknown route"}""")
            status = result.first
            return result
        } finally {
            // decision 取自真实 HTTP 状态：200=ok，409=refused（用户叫停），其余=error。
            // 用 finally 而不是直接跟在 return 后面：中途 return（保护页 403、未知路由 404）
            // 不减在飞数的话，inFlight 永远不归零 → 任务永远不超时。
            if (acts) {
                val decision = if (status == 200) "ok" else if (status == 409) "refused" else "error"
                taskDone(path, decision, body)
                // 五态状态得跟着真实结果走（v1.6.27）。
                // 只有 error 才切错误档 —— refused 是用户自己叫停或关了开关，
                // 那不是「出错」，染红会让用户以为系统坏了。
                when (decision) {
                    "error" -> StatusOverlay.noteActionFailed()
                    // v1.7.0：动作成功 ≠ 任务完成。
                    // 旧写法每一步成功都 noteActionDone() → justFinished=true → 状态点
                    // 刷成完成绿，一个 40 步的任务全程「任务已完成」，用户根本看不出
                    // 它还在动。现在只有任务真的结束了才染绿，否则保持「执行中」。
                    "ok" -> when (OverlayState.afterActionSuccess(task().snapshot().active)) {
                        OverlayState.Kind.WORKING -> StatusOverlay.noteWorking()
                        else -> StatusOverlay.noteActionDone()
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * 保护页闸门（v1.6.20）。
     *
     * 命中返回 403 响应，否则 null。
     *
     * 为什么用 foregroundPackage + visibleTextBlob 而不是完整 dump：
     * dump 要几百毫秒且占 IPC 池，而「这是不是权限页」只需包名 + 几个标题词。
     *
     * **读屏失败时放行而非拒绝**：这是低成本的粗筛。一旦把「服务正在忙 / 读不到」
     * 也当成拦截理由，就会出现「什么都点不了」的假故障。真正的严格兜底是 /tap-ref
     * （它用重建后的完整文档判，且判不到就拒绝）。
     */
    private fun protectedPageGuard(path: String): Pair<Int, String>? {
        val svc = DshAccessibilityService.instance ?: return null
        val pkg = try {
            svc.foregroundPackage()
        } catch (t: Throwable) {
            null
        }
        val blob = try {
            svc.titleBlob()
        } catch (t: Throwable) {
            ""
        }
        if (pkg.isNullOrEmpty() && blob.isEmpty()) return null
        val hit = try {
            ProtectedPages.isProtectedFast(pkg, blob)
        } catch (t: Throwable) {
            false
        }
        if (!hit) return null
        return 403 to JSONObject()
            .put("ok", false)
            .put("error", "protected page")
            .put("endpoint", path)
            .put("package", pkg ?: "")
            .put("reason", ScreenVerdict.PROTECTED_REASON)
            .toString()
    }

    /**
     * POST /open-file —— 在本应用内打开一个文件供用户查看/编辑（v1.4.0，issue #25）。
     *
     * ## 为什么由桥来做
     * 上游「打开配置文件」把路径交给桌面文本编辑器（macOS `open -t` /
     * Windows 资源管理器 / Linux `xdg-open`），Android 三者皆无 →
     * 引擎抛 `native path opener is unsupported on android`，前端显示
     * 「无法打开配置文件」。宿主机插件 `android-open-file.mjs` 把这个调用转到
     * 这里，由应用拉起 FileEditorActivity，形成可用的等价能力。
     *
     * ## 路径校验
     * 只接受**本应用私有目录 / 引擎目录 / 工作区**内的绝对路径：
     *   · 拒绝相对路径与 `..`（防目录穿越）；
     *   · 拒绝上述根之外的路径 —— 这个端点由 Agent 的 shell 可达（token 在引擎
     *     环境里），不能让它变成「拉起任意路径」的通用弹窗器；
     *   · 目标是符号链接时按真实路径判定，避免用链接绕过上面的根检查。
     * 注意这与「AI 能读什么文件」是两件事：AI 的沙箱由权限模式决定，
     * 这里限制的是**能弹给用户看的界面**。
     *
     * ## Linux 模式：路径是容器视角，必须先翻译
     *
     * 引擎跑在容器里时 `DSH_HOME=/dsh`，它上报的「配置文件」路径是
     * `/dsh/profiles/web/cordis.patch.yml`（见 `AppearanceActivity`/
     * `SettingsController.openSettingsDocument` 的路径来源：
     * `profileContext.patchPath = <DSH_HOME>/profiles/web/cordis.patch.yml`）。
     * 宿主上没有 `/dsh`，直接 `isFile` 必然 false、`isOpenablePath` 也判越界
     * —— 用户看到的就是「配置文件打不开」。
     *
     * 所以进校验前先按 bind 表做一次 guest→host 翻译
     * （[LinuxEngineLauncher.hostPathOf]，与容器挂载表同一来源）。
     * **只在 Linux 模式生效**：原生模式下 `/dsh` 这类路径不该出现，
     * 若用户真有一个宿主 `/dsh` 目录，无条件翻译会把它错当 guest 路径 ——
     * 所以判据用 [EngineModeSelector.lastMode]，而不是「路径长得像不像」。
     *
     * ## 返回
     * `{"ok":true,"opened":true}` —— 只表示界面已拉起，不代表用户已保存。
     * 调用方（open-file.mjs）据此判断是否要向上游抛错。
     */
    private fun openFile(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val raw = obj.optString("path").trim()
        if (raw.isBlank()) return 400 to """{"ok":false,"error":"field 'path' required"}"""
        if (!raw.startsWith("/")) return 400 to """{"ok":false,"error":"absolute path required"}"""

        // ★容器路径翻译：引擎在 Linux 模式下上报的是 guest 视角。
        val translated = if (EngineModeSelector.lastMode == EngineModeSelector.Mode.LINUX) {
            LinuxEngineLauncher.get(ctx).hostPathOf(raw)
        } else {
            null
        }
        if (translated != null && translated != raw) {
            Log.i(TAG, "open-file: mapped guest $raw -> host $translated")
        } else if (EngineModeSelector.lastMode == EngineModeSelector.Mode.LINUX &&
            translated == null && raw.startsWith("/dsh/")
        ) {
            // Linux 模式下给了 guest 路径却翻译不出来 → 是挂载表缺口，不是用户文件问题。
            // 明确记一笔，免得排查时误判成「文件不存在」。
            Log.w(TAG, "open-file: cannot map guest path to host: $raw")
        }

        // 原路径存在就优先用它 —— 保证「以前能打开的现在仍能打开」。
        // 只有原路径在宿主上不是文件时，才采用翻译结果。这样：
        //   · lastMode 尚未判定（进程刚起、resolve() 还没跑）时不会误判；
        //   · 宿主上若真有同名目录（如用户自建 /dsh），也不会被翻译抢占。
        val rawFile = File(raw)
        val target = if (rawFile.isFile || translated == null) rawFile else File(translated)
        // 真实路径判定：软链接会解析到真实位置，root 检查绕不过去。
        val real = runCatching { target.canonicalFile }.getOrDefault(target)
        if (!isOpenablePath(ctx, real)) {
            return 403 to """{"ok":false,"error":"path is outside the app-managed roots"}"""
        }
        if (!real.isFile) return 404 to """{"ok":false,"error":"not a file"}"""

        return runCatching {
            val intent = android.content.Intent(ctx, app.dsh.mobile.FileEditorActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(app.dsh.mobile.FileEditorActivity.EXTRA_PATH, real.absolutePath)
            ctx.startActivity(intent)
            200 to """{"ok":true,"opened":true}"""
        }.getOrElse {
            Log.w(TAG, "open-file failed: ${it.message}")
            500 to """{"ok":false,"error":"${it.message}"}"""
        }
    }

    /**
     * 该路径是否落在应用管理的根之内（见 [openFile] 的说明）。
     *
     * 根集合 = 应用私有 files/ 与 cache/（引擎、dsh-home、插件都在下面）、
     * 外置工作区（externaliseSubdir 可能把 workspaces/ 放到共享存储）。
     * 用 canonicalPath 前缀比较，避免 `..` 与软链接绕过。
     */
    private fun isOpenablePath(ctx: Context, file: File): Boolean {
        val p = file.absolutePath
        val roots = listOfNotNull(
            runCatching { ctx.filesDir.canonicalPath }.getOrNull(),
            runCatching { ctx.cacheDir.canonicalPath }.getOrNull(),
            runCatching { EngineConfig.engineRoot(ctx).canonicalPath }.getOrNull(),
            runCatching { EngineConfig.dshHome(ctx).canonicalPath }.getOrNull(),
            // 工作区在公开目录时（Documents/dshdata/workspaces）单独放行
            runCatching { EngineConfig.workspaces(ctx).canonicalPath }.getOrNull(),
            // v1.4.26：工作区根迁到 Download/DSH MOBILE 后，旧位置的工作区仍要能打开
            runCatching { EngineConfig.legacyWorkspacesRoot(ctx)?.canonicalPath }.getOrNull(),
        )
        return roots.any { root -> p == root || p.startsWith("$root/") }
    }

    /** GET /ext/list → 扩展清单与三态（red/yellow/green），AI 判断环境是否可用的唯一入口 */
    /**
     * GET /ext/check —— 只读健康检查（用户「一键检测」与 AI 自查共用）：
     * 对每个已装扩展核对 声明的主程序是否存在 / 悬空软链数 / root 权限残留目录。
     * 返回 {"ok":N,"broken":[{id,name,reason,...}], "all":[...]}，不修改任何文件。
     */
    private fun extCheck(ctx: Context): Pair<Int, String> {
        return try {
            val mgr = ExtensionManager(ctx)
            val list = mgr.checkHealth()
            val arr = org.json.JSONArray()
            list.forEach { h ->
                arr.put(
                    JSONObject()
                        .put("id", h.id).put("name", h.name)
                        .put("installed", h.installed).put("active", h.active)
                        .put("version", h.version ?: "")
                        .put("ok", h.ok)
                        .put("reason", h.reason())
                        .put("binsMissing", org.json.JSONArray(h.binsMissing))
                        .put("danglingLinks", h.danglingLinks)
                        .put("blockedDirs", org.json.JSONArray(h.blockedDirs))
                )
            }
            val broken = list.filter { it.installed && !it.ok }
            val body = JSONObject()
                .put("installed", list.count { it.installed })
                .put("healthy", list.count { it.ok })
                .put("broken", org.json.JSONArray(broken.map { it.id }))
                .put("all", arr).toString()
            200 to body
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /ext/uninstall body {"id":"..."} —— 扩展卸载（v1.7.10）。
     *
     * ## 为什么必须补
     * 扩展中心 UI 早就有卸载，桥只有 install。AI 装错一个 200MB 的运行时之后
     * **没有任何自助退路**，只能让用户去点界面 —— 与「AI 能自助装」的能力不对称，
     * 装错的成本被转嫁给用户。
     *
     * ## 两种模式必须分开走
     * - Linux 模式：包装在 rootfs 的全局位置，只能交给容器的包管理器（`apk del`
     *   / `apt remove`），删扩展目录**什么都不会发生**（目录根本不存在）。
     * - 原生模式：装在各扩展独立目录，就是删目录 + 清激活标记。
     * 之前 remove() 只有后者，若照原样接上去，Linux 模式会返回 `ok:true` 而包还在
     * —— 那是比不支持更糟的静默错误（AI 会据 ok 继续下一步）。
     *
     * - APK 型扩展：那是独立的 Android 应用，卸载要走系统安装器（用户在弹窗里
     *   点头）。这里明确拒绝并指路 /dev/packages op=uninstall，不假装成功。
     */
    private fun extUninstall(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"body must be {\"id\":\"<extension id>\"}"}"""
        val id = obj.optString("id").trim()
        if (id.isEmpty()) return 400 to """{"ok":false,"error":"missing id"}"""
        val mgr = (ctx.applicationContext as app.dsh.mobile.DshApp).extensionManager
        val ext = runCatching { mgr.loadCatalog().firstOrNull { it.id == id } }.getOrNull()
            ?: return 404 to """{"ok":false,"error":"unknown extension: $id"}"""

        // APK 型：卸载要用户点系统弹窗，这条链路不在扩展管理器里。
        if (ext.apkUrl.isNotBlank()) {
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "APK-type extension: user must confirm uninstall in the system dialog")
                .put("apkPackage", ext.apkPackage)
                .put("hint", "POST /dev/packages {\"op\":\"uninstall\",\"pkg\":\"${ext.apkPackage}\"}, then ask the user to tap OK in that dialog")
                .toString()
        }

        if (EngineModeSelector.isLinuxEnabled(ctx)) {
            val r = runCatching { LinuxExtensionBridge.remove(ctx, ext) }
                .getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
            if (!r.ok) {
                return 500 to JSONObject()
                    .put("ok", false)
                    .put("mode", "linux")
                    .put("error", "container package manager refused to remove")
                    .put("packages", JSONArray(r.packages))
                    .put("output", r.rawOutput.takeLast(600))
                    .toString()
            }
            return 200 to JSONObject()
                .put("ok", true)
                .put("mode", "linux")
                .put("removed", JSONArray(r.packages))
                .put("needs_restart", false)
                .put("message", "removed from the container; no engine restart needed")
                .toString()
        }

        // 原生模式：目录删掉 + 清激活标记（remove 内部置 pendingRestart）。
        // 判据用 state()（原生模式下它读 marker + 目录是否存在），没有 isInstalled 这个方法。
        val st = runCatching { mgr.state(id) }.getOrDefault(ExtensionManager.ExtState.NOT_DOWNLOADED)
        if (st == ExtensionManager.ExtState.NOT_DOWNLOADED) {
            return 404 to JSONObject()
                .put("ok", false)
                .put("error", "not installed")
                .put("mode", "native")
                .toString()
        }
        runCatching { mgr.remove(id) }.getOrElse {
            return 500 to """{"ok":false,"error":"${it.message}"}"""
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("mode", "native")
            .put("removed", id)
            .put("needs_restart", true)
            .put("message", "files deleted; engine restart clears leftover bin/lib links")
            .toString()
    }

    /**
     * POST /plugin/uninstall body {"package":"..."} —— 卸载社区插件（v1.7.10）。
     *
     * 与 install 对称：此前只有 install，装错插件只能靠用户去插件中心点。
     * 底层 `dsh plugin remove` 是 pnpm 调用，通常 3~20 秒，**必然超过桥的客户端
     * 读超时**，所以本地最多等 3 秒：等到就回真实结果，等不到回 202 并让调用方
     * 轮询 /plugin/list 的 installed 字段 —— 绝不假装成功。
     */
    private fun pluginUninstall(body: String): Pair<Int, String> {
        val pkg = runCatching { JSONObject(body).optString("package") }
            .getOrDefault("").trim()
        if (pkg.isBlank()) return 400 to """{"ok":false,"error":"field 'package' required"}"""
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        var msg = ""
        PluginCenter.uninstall(ctx, pkg) { o, m ->
            ok = o
            msg = m
            latch.countDown()
            runCatching {
                notify(
                    ctx,
                    JSONObject().apply {
                        put("title", if (o) "Plugin removed" else "Plugin remove failed")
                        put("body", "$pkg — ${m.take(120)}")
                    }.toString(),
                )
            }
        }
        // 3 秒是刻意选的：短于客户端 5s 读超时，长到能覆盖「包不存在」这类秒回错误。
        val finished = runCatching { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            return 202 to JSONObject()
                .put("ok", true)
                .put("started", true)
                .put("package", pkg)
                .put("message", "pnpm remove is running; poll GET /plugin/list and check the installed flag")
                .toString()
        }
        return if (ok) {
            200 to JSONObject().put("ok", true).put("package", pkg).put("removed", true).toString()
        } else {
            500 to JSONObject()
                .put("ok", false)
                .put("package", pkg)
                .put("error", msg.takeLast(600))
                .toString()
        }
    }

    /**
     * GET /dev/logs?file=engine|boot&tail=<bytes> —— 日志尾部（v1.7.10）。
     *
     * ## 为什么必须补
     * LogViewerActivity 一直在读 `engine/engine.log` 与 `engine/boot.log`，但这条
     * 读取能力只存在于**界面**里。AI 排障第一步（看日志）拿不到原件，只能靠
     * /diag —— 那是自诊页 HTML 里截断的 2500 字符，连 boot.log 都没有。
     * 于是最常见的场景（引擎反复重启 / 插件激活失败）AI 只能猜。
     *
     * 只允许这两个文件名（白名单，防路径穿越）；尾部字节数有上限，避免把
     * 几百 KB 日志灌进模型上下文把上下文撑爆。
     */
    private fun devLogs(ctx: Context, query: String): Pair<Int, String> {
        val p = parseQuery(query)
        val which = (p["file"] ?: "engine").lowercase()
        val name = when (which) {
            "boot" -> "boot.log"
            "engine", "engine.log" -> "engine.log"
            else -> return 400 to """{"ok":false,"error":"file must be engine|boot"}"""
        }
        val tail = (p["tail"]?.toIntOrNull() ?: 2_500).coerceIn(256, 65_536)
        val f = File(EngineConfig.engineRoot(ctx), name)
        if (!f.isFile) {
            return 200 to JSONObject()
                .put("ok", true).put("file", name).put("exists", false)
                .put("totalBytes", 0).put("tailBytes", 0).put("text", "")
                .toString()
        }
        val total = runCatching { f.length() }.getOrDefault(0L)
        val text = runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val from = maxOf(0L, total - tail)
                raf.seek(from)
                val buf = ByteArray((total - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
        return 200 to JSONObject()
            .put("ok", true).put("file", name).put("exists", true)
            .put("totalBytes", total).put("tailBytes", text.length)
            .put("truncated", total > tail)
            .put("text", text)
            .toString()
    }

    /**
     * 扩展清单（给 AI 读）。
     *
     * v1.6.21：补上 `available` / `mode` / `linuxOnly` / `rootOnly` / `note`。
     *
     * 之前只返回 `state`，而 `state` 在两种引擎模式下来自**完全不同的数据源**：
     * 原生模式读 Termux marker 文件，Linux 模式读容器内 `apk info` 结果。
     * AI 无从得知这件事，于是会出现两种典型误判 ——
     *   ① 用户在 Linux 模式下装好了包，AI 报 `red`（它读的是空的 marker 目录）；
     *   ② AI 直接 `POST /ext/install` 一个只存在于 Alpine 的条目，拿到
     *      「请切到 Linux 模式」这种与用户实际状态相反的错误。
     * 把模式与可用性显式说出来，AI 才不会拿错误的假设去解释失败。
     */
    private fun extList(ctx: Context): Pair<Int, String> {
        return try {
            val mgr = ExtensionManager(ctx)
            val linuxMode = EngineModeSelector.isLinuxEnabled(ctx)
            val family = LinuxExtensionBridge.activeFamily(ctx)
            // Linux 模式下装了什么包只有容器知道，一次取全表（每条都现查会 fork 20+ 次）
            val linuxInstalled = if (linuxMode) {
                runCatching { LinuxPackageManager.get(ctx).installed() }.getOrNull()
            } else null
            val arr = org.json.JSONArray()
            mgr.loadCatalog().forEach { e ->
                val state = if (linuxMode) {
                    LinuxExtensionBridge.state(ctx, e, linuxInstalled, null, family)
                } else {
                    mgr.state(e.id)
                }
                val st = when (state) {
                    ExtensionManager.ExtState.NOT_DOWNLOADED -> "red"
                    ExtensionManager.ExtState.DOWNLOADED -> "yellow"
                    ExtensionManager.ExtState.ACTIVATED -> "green"
                }
                // 可用性：Linux 模式看发行版仓库有没有这些包；原生模式看条目是否 linuxOnly
                val note = if (linuxMode) {
                    LinuxExtensionBridge.unavailableReason(e, family)
                } else if (e.linuxOnly) {
                    "only available in Linux container mode (its packages do not exist in the Termux repository)"
                } else null
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("name", e.name)
                        .put("category", e.category)
                        .put("state", st)
                        .put("version", mgr.installedVersion(e.id) ?: "")
                        .put("installing", mgr.isInstalling(e.id))
                        .put("available", note == null)
                        .put("mode", if (linuxMode) "linux" else "native")
                        .put("linuxOnly", e.linuxOnly)
                        .put("rootOnly", e.rootOnly)
                        .apply { if (note != null) put("note", note) }
                )
            }
            200 to arr.toString()
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /say body {"text":"...", "flush":false} → 系统语音合成朗读。
     * Agent 语音输出的出口（issues #2 语音功能方向的第一块）。
     */
    /** "text=...&flush=1" → Map（query 参数，GET /say 用；UTF-8 URL 解码） */
    private fun urlDecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    /** GET /say?text=...&flush=1 → 构造 JSON body 后走同一 say 流程 */
    private fun sayGet(ctx: Context, rawPath: String): Pair<Int, String> {
        val q = rawPath   // 传入的已是去掉 '?' 的纯 query 串
        val text = urlDecode(q.substringAfter("text=", "").substringBefore("&flush"))
        val flush = q.contains("flush=1")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text is blank"}"""
        val obj = org.json.JSONObject().put("text", text).put("flush", flush)
        return say(ctx, obj.toString())
    }

    private fun say(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"body must be {\"text\":\"...\",\"flush\":false}"}"""
        val text = obj.optString("text")
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text is blank"}"""
        // TTS init/speak 可能阻塞数秒（系统 TTS 服务冷启动），异步执行防 HTTP 超时
        Thread({ TtsManager.speak(ctx, text, obj.optBoolean("flush", false)) }, "dsh-tts").apply { isDaemon = true; start() }
        return 200 to """{"ok":true,"result":"accepted"}"""
    }

    /**
     * GET /diag → HTML 自诊断页（Android 11 等老设备排障）：
     * WebView UA 与 JS API 缺失检测、引擎状态、3080 HTTP/WS 探测、
     * runtime 版本、polyfill 落地、engine.log 尾部。用户在设备浏览器打开截图即可。
     */
    /**
     * GET /repair/diagnose —— 引擎起不来时的**结构化**归因（v1.5.3）。
     *
     * ## 与 GET /diag 的分工
     *
     * `/diag` 返回 HTML 页面，是给人截图发反馈用的；这一条返回 JSON，是给
     * **AI 自己读**的。两者刻意不复用：把 HTML 塞给模型既浪费 token 又难解析。
     *
     * ## 返回字段为什么是这些
     *
     * - `cause`：稳定 id（`port_foreign` / `plugin_inactive` / …），AI 据它决定策略；
     * - `evidence`：给人看的证据行，AI 可以直接复述给用户，不必自己去翻日志；
     * - `fixes[]`：**能做什么**，含 `kind` / `needs_confirm`。AI 只应调用这里
     *   列出的动作 —— 列表之外的动作意味着「归因器认为它不适用」。
     * - `summary`：一行可进日志的摘要。
     *
     * ## 一个刻意的设计
     *
     * 即使引擎当前是 Healthy 也会返回结果（`state` 字段说明现状），而不是报错。
     * 因为 AI 常见的用法是「用户说刚才起不来，现在已经好了」—— 那时它需要的是
     * **事后解释**，不是 4xx。
     */
    private fun repairDiagnose(ctx: Context): Pair<Int, String> {
        return try {
            val app = ctx.applicationContext as app.dsh.mobile.DshApp
            val state = runCatching { app.supervisor.state.value }.getOrNull()
            val existing = (state as? EngineSupervisor.State.Failed)?.diagnosis

            // 已经是 Failed 且当时算过诊断 → 直接复用（那时的日志/端口状态更贴近现场，
            // 现在再算一遍可能因为环境已变而得出不同结论，反而误导）
            val diag = existing ?: runCatching {
                EngineTriage.diagnose(
                    EngineTriage.collectFacts(
                        ctx,
                        runSelfCheck = true,
                        neverBooted = state !is EngineSupervisor.State.Healthy &&
                            state !is EngineSupervisor.State.SafeMode,
                        hasLastGoodProfile = runCatching {
                            ProfileGuardian(ctx).hasLastGood()
                        }.getOrDefault(false),
                    ),
                )
            }.getOrNull()

            if (diag == null) {
                return 500 to """{"ok":false,"error":"diagnosis unavailable"}"""
            }

            val fixes = org.json.JSONArray()
            diag.fixes.forEach { f ->
                fixes.put(
                    JSONObject()
                        .put("kind", f.kind.name.lowercase())
                        .put("needs_confirm", f.needsConfirm),
                )
            }
            val evidence = org.json.JSONArray()
            diag.evidence.forEach { evidence.put(it) }

            val body = JSONObject()
                .put("ok", true)
                .put("state", state?.let { it::class.simpleName }.orEmpty())
                .put("cause", diag.cause.id)
                .put("never_booted", diag.neverBooted)
                .put("summary", diag.summary)
                .put("evidence", evidence)
                .put("fixes", fixes)
                .put("from_live_state", existing != null)
            if (state is EngineSupervisor.State.Failed) body.put("reason", state.reason)
            200 to body.toString()
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /repair/apply —— 执行归因给出的某个修复（v1.5.3）。
     *
     * 请求体：`{"kind":"reclaim_port"}`（可选 `"confirm":true`）。
     *
     * ## 两道门
     *
     * 1. **kind 必须在当前诊断的 fixes 里**。归因器说「这个动作不适用」时
     *    强制调用是危险的（例如架构不匹配时去重装运行时，几十秒后照样起不来）。
     * 2. **needs_confirm 的动作必须显式带 `"confirm":true`**。这条既是给 AI 的
     *    护栏（它不能自作主张回滚用户配置），也是给用户的说明（界面会先弹窗）。
     *
     * ## 谁来挡住滥用
     *
     * 桥整体有 token 鉴权（见 [handle] 里的 authorised 检查），token 每次启动
     * 随机生成、只注入引擎环境，所以同设备的第三方 App 拿不到。这两条端点因此
     * 天然只对**引擎内的 Agent** 开放 —— 那正好是我们要授权的主体。
     * 额外的 needs_confirm 门是防 AI 自作主张，不是防越权。
     *
     * ## 为什么同步执行而不是排到后台
     *
     * 动作本身很快（杀进程 / 重启 / 读标记），慢的那部分（重启后的健康等待）
     * 本来就在 supervisor 自己的协程里。同步返回让 AI 能立刻拿到结果决定下一步，
     * 比「先返回 accepted 再轮询」少一轮往返。
     * 唯一例外是 RUN_SELF_CHECK —— 它要扫目录，几十毫秒到几秒，仍可接受。
     */
    private fun repairApply(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"body must be {\"kind\":\"<fix kind>\",\"confirm\":true?}"}"""
        val kindName = obj.optString("kind").lowercase()
        if (kindName.isEmpty()) return 400 to """{"ok":false,"error":"missing kind"}"""
        val confirmed = obj.optBoolean("confirm", false)

        val kind = EngineTriage.Fix.Kind.entries.firstOrNull { it.name.lowercase() == kindName }
            ?: return 400 to """{"ok":false,"error":"unknown kind: $kindName"}"""

        val app = ctx.applicationContext as app.dsh.mobile.DshApp
        val state = runCatching { app.supervisor.state.value }.getOrNull()
        val diag = (state as? EngineSupervisor.State.Failed)?.diagnosis
            ?: runCatching {
                EngineTriage.diagnose(EngineTriage.collectFacts(ctx))
            }.getOrNull()
        if (diag == null) return 500 to """{"ok":false,"error":"diagnosis unavailable"}"""

        val allowed = diag.fixes.firstOrNull { it.kind == kind }
            ?: return 409 to JSONObject()
                .put("ok", false)
                .put("error", "action not applicable for current cause")
                .put("cause", diag.cause.id)
                .put("allowed", org.json.JSONArray(diag.fixes.map { it.kind.name.lowercase() }))
                .toString()

        if (allowed.needsConfirm && !confirmed) {
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "action requires confirmation")
                .put("kind", kindName)
                .put("hint", "resend with \"confirm\":true after the user agrees")
                .toString()
        }

        val outcome = RepairActions.run(ctx, app.supervisor, allowed)
        val bodyOut = JSONObject()
            .put("ok", outcome.status == RepairActions.Outcome.Status.DONE)
            .put("kind", outcome.kind.name.lowercase())
            .put("status", outcome.status.name.lowercase())
            .put("restart_needed", outcome.restartNeeded)
            .put("cause", diag.cause.id)
        if (outcome.message.isNotEmpty()) bodyOut.put("message", outcome.message)
        if (outcome.detail.isNotEmpty()) bodyOut.put("detail", outcome.detail)
        // 状态码：SKIPPED 用 200（不是错误，只是条件不具备），FAILED 用 500
        val code = when (outcome.status) {
            RepairActions.Outcome.Status.DONE,
            RepairActions.Outcome.Status.SKIPPED -> 200
            RepairActions.Outcome.Status.FAILED -> 500
        }
        return code to bodyOut.toString()
    }

    private fun diag(ctx: Context): Pair<Int, String> {
        val app = ctx.applicationContext as app.dsh.mobile.DshApp
        val engineState = try {
            app.supervisor.state.value.toString()
        } catch (e: Exception) { "unknown: " + e.message }
        val runtimeVer = runCatching {
            java.io.File(EngineConfig.engineRoot(ctx), ".runtime-version").readText().trim()
        }.getOrDefault("(unreadable)")
        val feHtml = runCatching {
            java.io.File(
                EngineConfig.engineRoot(ctx),
                "lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html"
            ).readText()
        }.getOrDefault("")
        val poly = if (feHtml.isEmpty()) "frontend index.html missing!"
            else if (feHtml.contains("legacy-webview polyfill")) "present" else "MISSING"
        val logTail = runCatching {
            val f = java.io.File(ctx.filesDir, "engine.log")
            if (f.isFile) f.readText().takeLast(2500) else "(no engine.log)"
        }.getOrDefault("(log read failed)")

        // 3080 HTTP 探测
        val httpProbe = runCatching {
            val c = (java.net.URL("http://127.0.0.1:3080/").openConnection()
                    as java.net.HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 4000 }
            val code = c.responseCode
            // readNBytes(int) 是 API 33+；Android 11 上会 NoSuchMethod——用 Kotlin readBytes 截断
            val head = c.getInputStream().use { it.readBytes().take(120).toByteArray().toString(Charsets.UTF_8) }
            c.disconnect()
            "HTTP $code | head: ${head.replace(java.lang.System.lineSeparator(), " ")}"
        }.getOrElse { "FAIL: ${it.message}" }

        // WS 握手探测（多候选路径）。CRLF 由常量拼接，避免源码内嵌换行歧义。
        val crlf = "\r\n"
        val wsProbe = listOf("/ws", "/", "/api/ws", "/socket").joinToString("<br>") { p ->
            runCatching {
                java.net.Socket("127.0.0.1", 3080).use { sock ->
                    sock.soTimeout = 4000
                    val req = "GET $p HTTP/1.1" + crlf +
                        "Host: 127.0.0.1:3080" + crlf +
                        "Upgrade: websocket" + crlf +
                        "Connection: Upgrade" + crlf +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" + crlf +
                        "Sec-WebSocket-Version: 13" + crlf + crlf
                    sock.getOutputStream().write(req.toByteArray())
                    sock.getOutputStream().flush()
                    val first = ByteArray(64)
                    val n = sock.getInputStream().read(first)
                    if (n <= 0) "no response" else String(first, 0, n).split(crlf)[0]
                }
            }.getOrElse { "FAIL: ${it.message}" }
        }.let { "WS handshake:<br>" + it }

        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH diag</title><style>body{font-family:monospace;background:#111;color:#eee;padding:12px;font-size:13px;word-break:break-all}pre{white-space:pre-wrap;background:#1c1c1c;padding:8px;border-radius:6px}h3{color:#7DD3FC;margin:14px 0 4px}</style></head><body>
<h3>1. WebView UA</h3><pre id="ua"></pre>
<h3>2. JS API probe (false = missing)</h3><pre id="api"></pre>
<h3>3. Engine state</h3><pre>$engineState</pre>
<h3>4. HTTP probe on 3080</h3><pre>$httpProbe</pre>
<h3>5. WebSocket probe</h3><pre>$wsProbe</pre>
<h3>6. Runtime version</h3><pre>$runtimeVer</pre>
<h3>7. Frontend polyfill</h3><pre>$poly</pre>
<h3>8. engine.log tail</h3><pre>$logTail</pre>
<script>
document.getElementById('ua').textContent = navigator.userAgent;
var checks = [
  ['Object.hasOwn (93+)', function(){ return typeof Object.hasOwn === 'function'; }],
  ['Array.prototype.at (92+)', function(){ return typeof Array.prototype.at === 'function'; }],
  ['String.prototype.at (92+)', function(){ return typeof String.prototype.at === 'function'; }],
  ['Element.replaceChildren (86+)', function(){ return typeof Element.prototype.replaceChildren === 'function'; }],
  ['String.replaceAll (85+)', function(){ return typeof String.prototype.replaceAll === 'function'; }],
  ['crypto.randomUUID (92+)', function(){ return typeof crypto.randomUUID === 'function'; }],
  ['structuredClone (98+)', function(){ return typeof structuredClone === 'function'; }],
  ['Array.findLast (97+)', function(){ return typeof Array.prototype.findLast === 'function'; }]
];
document.getElementById('api').textContent = checks.map(function(c){
  var ok = false; try { ok = c[1](); } catch (e) { ok = 'ERR ' + e.message; }
  return c[0] + ' = ' + ok;
}).join('
');
</script></body></html>"""
        return 200 to html
    }

    /**
     * POST /ext/install body {"id":"python","force":true?} → 202 后台安装（Termux 镜像 → 依赖闭包 →
     * 解包 → 自动激活），完成后系统通知；AI 轮询 /ext/list 等 state=green。
     * force=true 强制重装已激活扩展（目录整体重建，用户手装内容会清掉）——修复旧布局扩展用。
     * 注意：激活后的 PATH 需引擎重启才生效（AI 应提醒用户点设置页「重启引擎」）。
     */
    private fun extInstall(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"body must be {\"id\":\"<extension id>\",\"force\":true?}"}"""
        val id = obj.optString("id").ifEmpty { return 400 to """{"ok":false,"error":"missing id"}""" }
        val force = obj.optBoolean("force", false)
        val mgr = (ctx.applicationContext as app.dsh.mobile.DshApp).extensionManager
        val ext = runCatching { mgr.loadCatalog().firstOrNull { it.id == id } }.getOrNull()
            ?: return 404 to """{"ok":false,"error":"unknown extension: $id"}"""
        // v1.6.21：Linux 模式必须走容器装包路径。
        //
        // 之前不管当前是什么模式都调 `mgr.installTask(ext)` —— 那是 Termux 路径
        // （下 .deb 到 app 私有目录、建 marker）。用户在 Linux 模式下让 AI 装东西
        // 会得到两种错：linuxOnly 条目被守卫挡下（提示「请切到 Linux 模式」，
        // 而用户本来就在），其他条目则装进了 Termux 目录、容器里根本看不到。
        val linuxMode = EngineModeSelector.isLinuxEnabled(ctx)
        if (linuxMode && ext.apkUrl.isBlank()) {
            val family = LinuxExtensionBridge.activeFamily(ctx)
            LinuxExtensionBridge.unavailableReason(ext, family)?.let {
                return 409 to JSONObject()
                    .put("ok", false)
                    .put("error", "unavailable in ${LinuxExtensionBridge.label(family)}: $it")
                    .put("mode", "linux")
                    .put("distro", LinuxExtensionBridge.label(family))
                    .toString()
            }
            val pkgs = runCatching {
                LinuxExtensionBridge.packagesOf(ext.packages, family)
            }.getOrNull().orEmpty()
            if (pkgs.isEmpty()) {
                return 409 to """{"ok":false,"error":"entry maps to no packages in this distro"}"""
            }
            if (mgr.isInstalling(id)) {
                return 409 to """{"ok":false,"error":"already installing, poll /ext/list"}"""
            }
            Thread({
                val r = LinuxExtensionBridge.install(ctx, ext)
                if (r.ok) {
                    notify(ctx, """{"title":"${ctx.getString(R.string.notif_ext_installed_title)}","body":"${ctx.getString(R.string.notif_ext_installed_body, ext.name)}"}""")
                } else {
                    Log.w(TAG, "ext install $id (linux): ${r.rawOutput.take(400)}")
                    notify(ctx, """{"title":"${ctx.getString(R.string.notif_ext_failed_title)}","body":"${ext.name}: ${r.rawOutput.take(200).replace("\"", "'")}"}""")
                }
            }, "ext-install-linux-$id").apply { isDaemon = true; start() }
            return 202 to JSONObject()
                .put("ok", true)
                .put("state", "installing")
                .put("mode", "linux")
                .put("distro", LinuxExtensionBridge.label(family))
                .put("packages", org.json.JSONArray(pkgs))
                .put("message", "installing with the container package manager; poll GET /ext/list until state=green")
                .toString()
        }
        // native 模式下的 linuxOnly 条目：前置拒绝，不要等后台线程抛。
        //
        // 之前直接放行、返回 202「已开始下载」，而真正的守卫在 installTask 里，
        // 抛错的那个线程只发通知、不改 state —— AI 按自己的契约轮询 /ext/list
        // 等 green，永远等不到，只能超时。实测：202 之后 state 一直是 red。
        if (ext.linuxOnly) {
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "this extension only exists in the Linux container repositories")
                .put("mode", "native")
                .put("linuxOnly", true)
                .put("hint", "enable Linux mode (Settings → Engine → Linux) and retry; in Linux mode this endpoint uses apk/apt instead of Termux")
                .toString()
        }
        if (!force && mgr.state(id) == ExtensionManager.ExtState.ACTIVATED) {
            return 200 to """{"ok":true,"state":"green","message":"already installed and activated (pass force:true to reinstall)"}"""
        }
        if (mgr.isInstalling(id)) {
            return 409 to """{"ok":false,"error":"already installing, poll /ext/list"}"""
        }
        Thread({
            try {
                kotlinx.coroutines.runBlocking { mgr.installTask(ext) }   // 与 UI 共享任务队列（并发下载/串行解包）
                mgr.activate(ext.id)
                notify(ctx, """{"title":"${ctx.getString(R.string.notif_ext_installed_title)}","body":"${ctx.getString(R.string.notif_ext_installed_body, ext.name)}"}""")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // 并发重复请求（另一个入口已在装）：这不是失败，别弹「安装失败」误导用户/AI，
                // 让它继续等那边的结果即可。上面的 isInstalling 预检与这里的线程启动之间
                // 本就有窗口，靠 installTask 的原子占位兜住。
                Log.i(TAG, "ext install $id: already in progress, request ignored")
            } catch (e: Exception) {
                Log.w(TAG, "ext install $id: ${e.message}")
                notify(ctx, """{"title":"${ctx.getString(R.string.notif_ext_failed_title)}","body":"${ext.name}: ${e.message}"}""")
            }
        }, "ext-install-$id").apply { isDaemon = true; start() }
        return 202 to """{"ok":true,"state":"installing","message":"download started; poll GET /ext/list until state=green, then remind user to restart engine"}"""
    }

    // ---------- v1.2.182：长串无障碍操作的系统通知 ----------

    /** 计入「AI 在操作设备」的动作类端点（读屏/查询类不计）。 */
    /** 上一次 /screen?format=doc 的签名与屏幕号，供 &diff=1 去重用。
     *
     * 只保一份：模型的下一步总是看完当前屏再决定，历史快照没有意义。
     * 且 @Volatile：桥的请求跨线程。
     */
    @Volatile private var lastDocSig: List<String>? = null
    @Volatile private var lastDocScreenId: String = ""

    /**
     * 这条请求是不是「会动界面」的动作（v1.7.6）。
     *
     * 决定三件事：过不过叫停/保护页闸门、点不点亮悬浮窗、算不算「AI 在操作」。
     * 单独抽出来是因为 /dev/node 是个多态端点：op=read 只读剪贴板（不动界面，
     * 用户叫停后仍应允许读），而 long_click/scroll/dismiss/click/input/back 都会动。
     *
     * 真机教训：/dev/node 之前完全在 A11Y_ACTION_PATHS 之外 → AI 用节点语义动作
     * 操纵时，悬浮窗不出现（用户以为没在动）、叫停按钮拦不住、保护页也能滚。
     */
    private fun isA11yAction(path: String, body: String): Boolean = when (path) {
        "/dev/node" -> runCatching { JSONObject(body).optString("op").lowercase() }
            .getOrDefault("") != "read"
        "/tap-ref" -> true
        else -> path in A11Y_ACTION_PATHS
    }

    private val A11Y_ACTION_PATHS = setOf(
        "/tap", "/tap-id", "/gesture", "/key", "/input",
        "/scroll-find", "/scroll-center", "/scroll-edge",
        "/launch", "/launch-intent", "/vision/tap", "/vision/drag",
        "/loop/run",
        // v1.6.35：/batch 之前漏在闸门外 —— 叫停与保护页闸门都绕过它，
        // 一次批量动作可以在权限页上点下去。
        "/batch",
    )

    /** 连续操作够几步才提示（太少会打扰，太多用户又看不到过程）。 */
    private const val A11Y_NOTIF_THRESHOLD = 3
    /** 序列粘性窗口：超过这么久没动作就视为新序列。 */
    private const val A11Y_NOTIF_WINDOW_MS = 30_000L
    /** 最后一次动作后多久自动撤掉通知。 */
    private const val A11Y_NOTIF_IDLE_MS = 10_000L
    /** 固定的通知 id：每次更新同一条，不刷屏。 */
    private const val A11Y_NOTIF_ID = 0x0A11

    /** /loop/run 单次预算：默认值（实测约 1167ms/步 → 约 257 步）。 */
    private const val LOOP_BUDGET_MS = 300_000L
    /** 下限：比这更短的计划不值得开一次闭环（也防止 1ms 这种明显笔误）。 */
    private const val LOOP_BUDGET_MIN_MS = 10_000L
    /** 上限：再长就该拆成多次调用，否则用户盯着一个不动的屏幕太久。 */
    private const val LOOP_BUDGET_MAX_MS = 600_000L

    @Volatile private var a11ySeqCount = 0
    @Volatile private var a11ySeqLastAt = 0L
    private val a11yNotifHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var a11yNotifIdle: Runnable? = null

    /**
     * 动作标签（通知正文里给人类看的那半句）。
     *
     * 走字符串资源而不是内联中文：项目自带 i18n 门禁（scripts/check-i18n.py）
     * 禁止 Kotlin 字面量里出现 CJK，中英两套 locale 必须同步。
     */
    private fun a11yOpLabel(ctx: Context, path: String): String = when (path) {
        "/tap", "/tap-id", "/vision/tap" -> ctx.getString(R.string.notif_a11y_op_tap)
        "/gesture", "/vision/drag" -> ctx.getString(R.string.notif_a11y_op_swipe)
        "/scroll-find", "/scroll-center", "/scroll-edge" -> ctx.getString(R.string.notif_a11y_op_scroll)
        "/input" -> ctx.getString(R.string.notif_a11y_op_input)
        "/key" -> ctx.getString(R.string.notif_a11y_op_key)
        "/launch", "/launch-intent" -> ctx.getString(R.string.notif_a11y_op_launch)
        "/loop/run" -> ctx.getString(R.string.notif_a11y_op_loop)
        else -> ctx.getString(R.string.notif_a11y_op_other)
    }

    /**
     * 记录一次无障碍动作；达到阈值后显示/更新一条系统通知。
     *
     * 设计取舍：
     *  · **不每条都弹**（会烦）—— 连续第 3 步起才提示，之后原地更新步数；
     *  · **同一条通知**（固定 id + setOnlyAlertOnce）→ 只在第一次提示时响一下；
     *  · 空闲 10s 自动撤掉；任务结束时用户也能手动划走。
     *  · 没有通知权限时静默跳过（不影响操作本身）。
     */
    private fun noteA11yOperation(ctx: Context, path: String) {
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            val count: Int
            synchronized(this) {
                if (a11ySeqLastAt == 0L || now - a11ySeqLastAt > A11Y_NOTIF_WINDOW_MS) {
                    a11ySeqCount = 0
                }
                // v1.2.188（审查 P2）：/loop/run 是"整段连续操作"——它的内部每一步
                // 在进程内执行、不再经过 HTTP，若只计 1 步，daemon 的长任务永远
                // 凑不够阈值 3，通知在最主要场景下不出现。这里按整段计 3 步。
                a11ySeqCount += if (path == "/loop/run") A11Y_NOTIF_THRESHOLD else 1
                a11ySeqLastAt = now
                count = a11ySeqCount
            }
            if (count < A11Y_NOTIF_THRESHOLD) return
            showA11yNotification(ctx, count, a11yOpLabel(ctx, path))
            synchronized(this) {
                a11yNotifIdle?.let { a11yNotifHandler.removeCallbacks(it) }
                val r = Runnable {
                    runCatching {
                        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(A11Y_NOTIF_ID)
                    }
                    synchronized(this) {
                        a11ySeqCount = 0
                        a11ySeqLastAt = 0L
                        a11yNotifIdle = null
                    }
                }
                a11yNotifIdle = r
                a11yNotifHandler.postDelayed(r, A11Y_NOTIF_IDLE_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "a11y notify skipped: ${e.message}")
        }
    }

    /** 构建并更新那条「正在操作」通知。 */
    private fun showA11yNotification(ctx: Context, step: Int, label: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return   // 没授权就不打扰；操作照常进行
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_channel_agent),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(ctx)
        }
        // 点通知回到 App（用户想插手/看进度时一步到位）
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (launch != null) {
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            )
            builder.setContentIntent(pi)
        }
        builder.setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notif_a11y_active_title))
            .setContentText(ctx.getString(R.string.notif_a11y_active_text, step, label))
            .setOnlyAlertOnce(true)      // 更新时不重复响铃
            .setAutoCancel(true)
            .setOngoing(false)
        nm.notify(A11Y_NOTIF_ID, builder.build())
    }

    /** POST /notify → 系统通知（任务完成推送） */
    private fun notify(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val title = obj.optString("title").ifEmpty { ctx.getString(R.string.notif_agent_task) }
            val text = obj.optString("body").ifEmpty { ctx.getString(R.string.notif_task_complete) }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_agent), NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                val routed = app.dsh.mobile.service.EngineService.pushAgentNotice(ctx.getString(R.string.notif_agent_prefix, title, text))
                return if (routed) {
                    200 to """{"ok":true,"result":"shown on engine foreground notice (grant POST_NOTIFICATIONS for heads-up alerts)"}"""
                } else {
                    403 to """{"ok":false,"error":"notification permission not granted and engine service not running"}"""
                }
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(ctx)
            }
            builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
            nm.notify(System.currentTimeMillis().toInt() and 0x7FFFFFFF, builder.build())
            200 to """{"ok":true}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** 上一次 /screen 的节点签名集（diff=1 模式用；进程级缓存） */
    @Volatile
    private var lastScreenSig: Set<String>? = null

    /** 紧凑格式的上一次行集合（diff=1 用；与 lastScreenSig 分开存，两种格式互不干扰） */
    @Volatile
    private var lastCompactSig: Set<String>? = null

    // ================= 无障碍 IPC 有界等待（v1.2.50） =================
    //
    // 问题：dumpScreenJson / screenXml 是对无障碍服务的同步 IPC，节点树僵死时
    // 会无限期阻塞。而 handle() 的 client.soTimeout 只覆盖「读请求头」阶段，管不到
    // 这里；客户端门脚本也没有超时 → 一次卡死会把 /screen 与 /wait 一起挂住
    // （用户实测 scr dump 挂 >60s、notify 挂 >120s）。
    // 解法：把节点树读取丢进单线程 executor，有界等待；超时返回 504 并明确报错，
    // 让上层能区分「服务未开启(503)」与「IPC 卡住(504)」。单线程同时限制了并发，
    // 避免多个僵死任务堆积。
    // v1.2.152 审查修复：原先只有 1 条线程，而 `/loop/run` 会在这个线程上占满
    // 整段执行时间（最长 180s）——期间任何并发读屏（`/screen`、`/pick`、`/memory`）
    // 都排在后面，2s 上限一到全部 504，AI 收到"无障碍坏了"的错误信号。
    // 2 条线程：长任务占一条，快速读屏仍有可用线程（读操作互不冲突）。
    private val screenExec = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "dsh-screen-ipc").apply { isDaemon = true }
    }

    /** 节点树 IPC 的默认上限：稳定后读屏几十毫秒，冷读（见下）约 2.5s */
    private val SCREEN_IPC_TIMEOUT_MS = 2_000L

    /** 冷读重试预算：实测屏幕刚变化后的第一次读树要 2.1-2.5s，稳定后 60-80ms */
    private val SCREEN_IPC_COLD_RETRY_MS = 5_000L

    /**
     * 有界执行无障碍节点树读取。
     * @return 成功返回值；超时或异常返回 null 并记录原因（调用方据此回 504）
     */
    private fun <T> withScreenTimeout(
        what: String, timeoutMs: Long = SCREEN_IPC_TIMEOUT_MS, block: () -> T,
    ): Result<T>?  {
        val first = screenReadOnce(what, timeoutMs, block)
        if (first != null) return first
        // ⚠️ v1.2.152 实测修复（模拟器连续 5 次读屏取证）：同一个静止页面，
        // 前两次各 2075ms 超时→504，随后三次 57-77ms 成功。也就是说 2s 上限
        // 会把「屏幕刚变化、节点树正在重建」的**冷读**误报成「无障碍卡死」，
        // 而 AI 收到 504 只会重试一次——正好撞上重建完成，白白多花一轮。
        // 对上位机来说这是"某些页面莫名停留更久"的直接来源。
        // 只对短超时路径做一次放宽重试（loop/run 那种长时间预算不重试，
        // 重试没有意义且会占用执行线程）。
        if (timeoutMs > 20_000L) return null
        Log.w(TAG, "screen ipc timeout: $what (>${timeoutMs}ms) — retrying once (cold node tree)")
        return screenReadOnce(what, SCREEN_IPC_COLD_RETRY_MS, block)
    }

    /** 单次有界读树：超时返回 null，异常返回 failure */
    private fun <T> screenReadOnce(what: String, timeoutMs: Long, block: () -> T): Result<T>? = try {
        val future = screenExec.submit(java.util.concurrent.Callable { block() })
        Result.success(future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
    } catch (e: java.util.concurrent.TimeoutException) {
        Log.w(TAG, "screen ipc timeout: $what (>${timeoutMs}ms) — node tree stalled")
        null
    } catch (e: java.util.concurrent.ExecutionException) {
        Result.failure(e.cause ?: e)
    }

    /**
     * GET /screen → 无障碍读屏（服务未开启时 503）。
     * query 参数：
     *   compact=1 → **紧凑文本格式（推荐，压缩 6.8 倍）**；缺省也走紧凑
     *   format=json → 强制完整 JSON（需要 cls/rid/精确尺寸时用）
     *   xml=1 → 树形转储（含 viewId/scrollable/editable/层级）
     *   filter=clickable → 只输出可点击节点；diff=1 → 只输出与上次不同的节点（省 token）
     *
     * ⚠️ v1.2.55：默认改为**紧凑格式**。完整 JSON 每节点 11 字段，200 节点约 44KB
     * ≈ 1.5 万 tokens，每一轮对话都要重新过一遍注意力 —— 用户实测「五六秒才动一次」，
     * 主因就是这个（不是动作慢，是喂给模型的上下文太肥）。紧凑格式 44KB → 6.5KB。
     */
    private fun screen(query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled (enable 'DSH Screen Control' in system settings)"}"""
        return try {
            val params = parseQuery(query)
            val filter = when (params["filter"]) {
                null, "", "all" -> "all"
                "clickable", "editable" -> params["filter"]!!
                else -> return 400 to """{"ok":false,"error":"unknown filter '${params["filter"]}'; valid: all|clickable|editable"}"""
            }
            // 紧凑为默认；显式 format=json 才走完整 JSON
            val wantJson = params["format"] == "json"
            // v1.6.19：编号文档格式。独立入口，不影响既有紧凑/JSON/XML 三种格式。
            // 编号只对最新一次构建有效，动作侧用 POST /tap-ref 引用它。
            if (params["format"] == "doc") {
                val r = withScreenTimeout("dumpScreenDoc") { ScreenDoc.build(svc) }
                    ?: return 504 to "{\"ok\":false,\"error\":\"accessibility node tree read timed out (service may be stuck)\"}"
                val doc = r.getOrElse { return 500 to "{\"ok\":false,\"error\":\"${it.message}\"}" }
                    ?: return 503 to "{\"ok\":false,\"error\":\"no active window (screen off, or the service just reconnected)\"}"
                // 屏幕去重（v1.6.21，需显式加 &diff=1）。
                // 默认不开：去重本质上是「省 token」的优化，而它引入了一个隐式状态
                // （上一屏是哪一屏）。默认开会让「第一次调用」的行为变得不可预测；
                // 叫它显式开，调用方就能自己判断什么时候要全量。
                var text = doc.text
                var deduped = false
                if (params["diff"] == "1") {
                    val prev = lastDocSig
                    val nowSig = doc.signature()
                    val reduced = if (prev == null) null else ScreenDigest.reduce(
                        doc.text, lastDocScreenId, prev, nowSig,
                    )
                    if (reduced != null) {
                        text = reduced
                        deduped = true
                    }
                    lastDocSig = nowSig
                    lastDocScreenId = doc.screenId
                } else {
                    // 不请求去重也要记下签名，否则下一次 &diff=1 拿不到基线。
                    lastDocSig = doc.signature()
                    lastDocScreenId = doc.screenId
                }
                return 200 to JSONObject()
                    .put("ok", true)
                    .put("screen", doc.screenId)
                    .put("app", doc.appLabel)
                    .put("package", doc.packageName)
                    .put("count", doc.elements.size)
                    .put("protected", doc.protectedPage)
                    .put("poorTree", doc.poorTree)
                    .put("deduped", deduped)
                    .put("text", text)
                    .toString()
            }
            when {
                params["xml"] == "1" -> {
                    val r = withScreenTimeout("screenXml") { svc.screenXml() }
                        ?: return 504 to """{"ok":false,"error":"accessibility node tree read timed out (service may be stuck)"}"""
                    r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
                    val xml = r.getOrThrow()
                    200 to """{"ok":true,"xml":${JSONObject.quote(xml)}}"""
                }
                !wantJson -> {
                    val r = withScreenTimeout("dumpScreenCompact") {
                        svc.dumpScreenCompact(filter)
                    } ?: return 504 to """{"ok":false,"error":"accessibility node tree read timed out (service may be stuck)"}"""
                    val text = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
                    // ⚡ 紧凑模式也支持 diff（v1.2.58）：只回「与上次不同」的行。
                    // 连续观察同一界面时（等加载、确认状态），重复行是纯 token 浪费。
                    // 按行做集合差；保留首行（屏幕尺寸/节点数）。
                    if (params["diff"] == "1") {
                        val lines = text.lines()
                        val header = lines.firstOrNull().orEmpty()
                        val body = lines.drop(1).filter { it.isNotBlank() }
                        val prev = lastCompactSig
                        val changed = if (prev == null) body else body.filter { it !in prev }
                        lastCompactSig = body.toSet()
                        val out = buildString {
                            append(header)
                            if (prev != null) append(" (diff ${changed.size}/${body.size})")
                            append('\n')
                            changed.forEach { append(it).append('\n') }
                        }
                        return 200 to out
                    }
                    // 紧凑格式直接用 text/plain 返回，省掉 JSON 转义的额外膨胀
                    200 to text
                }
                else -> {
                    val r = withScreenTimeout("dumpScreenJson") { svc.dumpScreenJson(filter) }
                        ?: return 504 to """{"ok":false,"error":"accessibility node tree read timed out (service may be stuck)"}"""
                    r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
                    val json = JSONObject(r.getOrThrow())
                    if (params["diff"] == "1") {
                        val nodes = json.getJSONArray("nodes")
                        val sig = HashSet<String>()
                        val changed = org.json.JSONArray()
                        for (i in 0 until nodes.length()) {
                            val n = nodes.getJSONObject(i)
                            val key = n.optString("text") + "|" + n.optString("desc") + "|" +
                                n.optInt("x") + "," + n.optInt("y")
                            sig.add(key)
                            val prev = lastScreenSig
                            if (prev == null || key !in prev) changed.put(n)   // 新出现/位置变化
                        }
                        lastScreenSig = sig
                        json.put("nodes", changed)
                        json.put("diff", true)
                    }
                    200 to json.toString()
                }
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** query 串（"a=1&b=2"）解析；值做 UTF-8 URL 解码 */
    private fun parseQuery(query: String): Map<String, String> =
        query.split("&").filter { it.contains("=") }.associate {
            val i = it.indexOf('=')
            java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

    /**
     * 把 query string 转成带类型的 JSONObject —— GET 端点复用 POST 的实现（v1.7.10）。
     *
     * ## 为什么需要它
     * 目录为 6 个 GET 端点声明了完整参数（`/dev/context` `/dev/notify-digest`
     * `/dev/packages` `/dev/settings` `/dev/shz` `/dev/windows`），但那些路由写的是
     * `{ c, _, _ -> ...` —— **query 被整个丢掉**，按文档传参静默无效，GET 恒走默认分支。
     * 这不是文档问题而是实现问题：`dev packages limit=20` 一直只返回默认 200 条。
     *
     * ## 类型收敛（与 [parseArgs] 的 MCP 侧、门脚本 k=v 的语义逐条对齐）
     * - `true` / `false` → Boolean（`optBoolean` 认字符串也认布尔，但布尔更省一层猜测）
     * - 纯整数 → Number（`optInt`/`optLong` 对字符串数字其实也能解析，这里显式转
     *   是为了 `optJSONArray` / `optJSONObject` 的兄弟字段不误判）
     * - 以 `[` 或 `{` 开头 → 尝试 JSON.parse（对应 `steps=[{...}]` 这类用法）
     * - 其余保持字符串
     *
     * ⚠️ 解析失败不抛：一个坏参数不该让整个端点 500，交给具体端点的默认值兜。
     */
    private fun queryJson(query: String): JSONObject {
        val o = JSONObject()
        for ((k, raw) in parseQuery(query)) {
            when {
                raw == "true" || raw == "false" -> o.put(k, raw == "true")
                raw.matches(Regex("-?\\d+")) && raw.length <= 18 -> o.put(k, raw.toLong())
                raw.matches(Regex("-?\\d+\\.\\d+")) -> o.put(k, raw.toDouble())
                raw.startsWith("{") ->
                    o.put(k, runCatching { JSONObject(raw) }.getOrNull() ?: raw)
                raw.startsWith("[") ->
                    o.put(k, runCatching { JSONArray(raw) }.getOrNull() ?: raw)
                else -> o.put(k, raw)
            }
        }
        return o
    }

    /** POST /tap → 坐标点击 / 按文本点击（text+desc 打分匹配，trim 忽略大小写）/ 按 desc 点击 */
    private fun tap(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
            // 悬浮窗（v1.2.57）：让用户实时看到 AI 正在执行什么动作
            announceAction(
                R.string.a11y_action_tap,
                when {
                    obj.has("desc") -> "tap-desc \"${obj.getString("desc")}\""
                    obj.has("text") -> "tap-text \"${obj.getString("text")}\""
                    obj.has("x") -> "tap ${obj.getDouble("x").toInt()},${obj.getDouble("y").toInt()}"
                    else -> "tap"
                },
            )
            // v1.2.68「动作前校验前台包名」（内置 AI 建议）——
            // 它上轮误点抖音的直接原因就是「以为还在目标应用里，其实前台已经换了」。
            // 两层：① 调用方显式传 expectPkg 时**硬校验**，不符直接拒绝；
            //      ② 不传也自动对比「上次动作时的前台包名」，在响应里回报 foregroundChanged，
            //         让 AI 一步之内就能发现跑偏（不必等它自己 dump 察觉）。
            val fg = svc.foregroundPackage()
            val expect = obj.optString("expectPkg").takeIf { it.isNotBlank() }
            if (expect != null && fg != null && !pkgMatches(fg, expect)) {
                return 409 to """{"ok":false,"error":"foreground is $fg, expected $expect","foreground":"$fg"}"""
            }
            val fgChanged = lastActionPkg != null && fg != null && fg != lastActionPkg
            lastActionPkg = fg
            // 选择器歧义检测（v1.2.82，无障碍提案 P1-4）：
            // 提案原文「tap-text "7" 在计算器上同时匹配显示区和键盘，当前静默取第一个，
            // 这次恰好对，纯属运气」。现在：匹配到多个**同分**候选时默认拒绝并列出候选，
            // 调用方可传 index 明确选择，或传 allowAmbiguous:true 沿用旧行为。
            val sel = when {
                obj.has("desc") -> obj.getString("desc")
                obj.has("text") -> obj.getString("text")
                else -> null
            }
            if (sel != null) {
                val matches = svc.findMatches(sel, byDesc = obj.has("desc"))
                val index = if (obj.has("index")) obj.getInt("index") else -1
                val allowAmbiguous = obj.optBoolean("allowAmbiguous", false)
                if (matches.size > 1 && matches.first().ties > 1 && index < 0 && !allowAmbiguous) {
                    val cands = matches.take(5).map {
                        val rect = android.graphics.Rect().also { r -> it.node.getBoundsInScreen(r) }
                        JSONObject().apply {
                            put("label", it.label)
                            put("byDesc", it.byDesc)
                            put("clickable", it.clickable)
                            put("x", rect.centerX()); put("y", rect.centerY())
                        }
                    }
                    return 409 to JSONObject().apply {
                        put("ok", false)
                        put("error", "ambiguous selector: ${matches.size} nodes match \"$sel\"")
                        put("candidates", org.json.JSONArray(cands))
                        put("hint", "pass index:N to pick one, or allowAmbiguous:true to take the first")
                    }.toString()
                }
            }

            val index = if (obj.has("index")) obj.getInt("index") else -1
            val ok = when {
                sel != null && index >= 0 -> {
                    // 按 index 选候选（歧义消解）
                    val m = svc.findMatches(sel, byDesc = obj.has("desc"))
                    if (index < m.size) svc.dispatchTapRect(
                        android.graphics.Rect().also { r -> m[index].node.getBoundsInScreen(r) }
                    ) else false
                }
                obj.has("desc") -> svc.tapDesc(obj.getString("desc"))
                obj.has("text") -> svc.tapText(obj.getString("text"))
                obj.has("x") && obj.has("y") -> svc.dispatchTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                else -> false
            }
            // ⚠️ ok:true 只表示**手势已派发**，不代表页面真的响应了（用户实测：
            // 点不可点击节点的祖先中心时返回 ok:true 但毫无变化）。
            // 故在响应里带上 dispatched 语义 + 提示，让调用方知道要自行校验。
            // 每步自动截图（v1.2.70）：路径直接给 AI，省掉它自己再调一次截图接口
            val shot = if (ok) captureStepShot() else null
            if (ok) {
                200 to """{"ok":true,"dispatched":true,"foreground":"${fg ?: ""}","foregroundChanged":$fgChanged,"shot":"${shot ?: ""}","hint":"ok means the gesture was dispatched, not that the UI reacted — dump again to verify"}"""
            } else {
                // 失败时附上"最接近的候选"（提案 P0-2）——
                // 省掉 AI「再花一整轮 dump 去找」的往返；常见真相是目标在 contentDescription 里
                // （计算器的 d:加 / d:等于），候选会带 d: 前缀标出来。
                val nearest = if (sel != null) svc.nearestCandidates(sel, 5) else emptyList()
                JSONObject().apply {
                    put("ok", false)
                    put("error", "tap failed / text not found")
                    put("foreground", fg ?: "")
                    put("foregroundChanged", fgChanged)
                    if (nearest.isNotEmpty()) {
                        put("nearest", org.json.JSONArray(nearest))
                        put("hint", "closest labels on screen; d: prefix means it is a contentDescription (use desc: not text:)")
                    }
                }.let { 500 to it.toString() }
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** GET /screenshot → 截屏 PNG base64（takeScreenshot，API 30+）。
     *  能力缺失时给出准确指引：服务配置 canTakeScreenshot 需用户重新开启服务生效。 */
    /** GET /plugin/list：社区插件清单（远程拉取 + 内置兜底，v1.2.113） */
    private fun pluginList(): Pair<Int, String> {
        val list = runCatching { PluginCenter.list() }.getOrDefault(emptyList())
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id); put("package", p.pkg)
                    put("desc", p.desc); put("repo", p.repo)
                },
            )
        }
        return 200 to arr.toString()
    }

    /** POST /plugin/install：一键安装 dsh 插件（后台执行，202=已开始，v1.2.113） */
    private fun pluginInstall(body: String): Pair<Int, String> {
        val pkg = runCatching { JSONObject(body).optString("package") }
            .getOrDefault("").trim()
        if (pkg.isBlank()) return 400 to """{"ok":false,"error":"field 'package' required"}"""
        val ctx = shotCtx ?: return 503 to """{"ok":false,"error":"app context not ready"}"""
        // v1.4.27：应急总开关期间安装会被 PluginCenter 拒绝，但那是异步的 ——
        // 之前这里照样回 202 started，调用方（curl / AI）以为装上了，实际什么都没发生。
        // v1.4.28：停用期间放开安装（装了不加载，先更新再单独启用），只拦恢复窗口。
        if (PluginKillSwitch.isRestoring(ctx)) {
            return 409 to """{"ok":false,"error":"plugins are being restored; try again in a moment","package":"$pkg"}"""
        }
        PluginCenter.install(ctx, pkg) { ok, msg ->
            runCatching {
                notify(
                    ctx,
                    JSONObject().apply {
                        put("title", if (ok) "Plugin installed" else "Plugin install failed")
                        put("body", "$pkg — ${msg.take(120)}")
                    }.toString(),
                )
            }
        }
        return 202 to """{"ok":true,"started":true,"package":"$pkg"}"""
    }

    /**
     * `/plugin/killswitch` —— 第三方插件应急总开关（v1.4.27）。
     *
     * 这是「WebUI 被插件搞坏、界面点不动」时的无 UI 逃生门：
     * ```
     * adb forward tcp:3083 tcp:3083
     * curl -s 'http://127.0.0.1:3083/plugin/killswitch'                      # 查状态
     * curl -s -X POST -d '{"action":"disable"}' http://127.0.0.1:3083/plugin/killswitch
     * curl -s -X POST -d '{"action":"enable"}'  http://127.0.0.1:3083/plugin/killswitch
     * curl -s -X POST -d '{"action":"enable-one","package":"@x/foo"}' http://127.0.0.1:3083/plugin/killswitch
     * curl -s -X POST -d '{"action":"disable-one","package":"@x/foo"}' http://127.0.0.1:3083/plugin/killswitch
     * ```
     * 只写意图日志并重启引擎；真正的 bundle 列表改写发生在下次 spawn 前
     * （[PluginKillSwitch.enforceBeforeBoot]），所以这里不会和运行中的引擎抢文件。
     */
    private fun pluginKillSwitch(ctx: Context, query: String, body: String): Pair<Int, String> {
        val fromBody = runCatching { JSONObject(body).optString("action") }.getOrDefault("")
        val action = fromBody.ifBlank { parseQuery(query)["action"].orEmpty() }
            .trim().lowercase()
        val state = PluginKillSwitch.read(ctx)
        val status = PluginKillSwitch.status(ctx)

        fun statusJson(): JSONObject = JSONObject().apply {
            put("ok", true)
            put("active", status.active)
            put("phase", state?.phase?.wire ?: "none")
            put("thirdParty", org.json.JSONArray(status.thirdParty))
            put("disabled", org.json.JSONArray(status.disabled))
            put("allow", org.json.JSONArray(status.allow))
            put("total", status.total)
            put("at", status.at)
        }

        if (action.isEmpty() || action == "status") return 200 to statusJson().toString()

        // v1.4.28：单插件白名单动作（仅停用期间有效）
        if (action == "enable-one" || action == "disable-one") {
            val pkg = runCatching { JSONObject(body).optString("package") }.getOrDefault("")
                .ifBlank { parseQuery(query)["package"].orEmpty() }.trim()
            if (pkg.isBlank()) {
                return 400 to """{"ok":false,"error":"field 'package' required"}"""
            }
            val outcome = if (action == "enable-one") PluginKillSwitch.requestEnableOne(ctx, pkg)
            else PluginKillSwitch.requestDisableOne(ctx, pkg)
            if (!outcome.ok) {
                return 409 to JSONObject().put("ok", false).put("error", outcome.message).toString()
            }
            runCatching { (ctx.applicationContext as app.dsh.mobile.DshApp).supervisor.restart() }
                .onFailure { Log.w(TAG, "kill-switch restart failed: ${it.message}") }
            Log.w(TAG, "plugin kill-switch: $action $pkg accepted")
            return 202 to JSONObject().apply {
                put("ok", true)
                put("action", action)
                put("package", pkg)
                put("restarting", true)
            }.toString()
        }

        val outcome = when (action) {
            "disable" -> PluginKillSwitch.requestDisable(ctx, "bridge")
            "enable" -> PluginKillSwitch.requestEnable(ctx)
            else -> return 400 to
                """{"ok":false,"error":"action must be status|disable|enable|enable-one|disable-one"}"""
        }
        if (!outcome.ok) {
            return 409 to JSONObject().put("ok", false).put("error", outcome.message).toString()
        }
        // 意图已落盘 → 异步重启引擎让它生效（restart 自身幂等，重复请求是 no-op）
        runCatching { (ctx.applicationContext as app.dsh.mobile.DshApp).supervisor.restart() }
            .onFailure { Log.w(TAG, "kill-switch restart failed: ${it.message}") }
        Log.w(TAG, "plugin kill-switch: $action accepted (${outcome.message})")
        return 202 to JSONObject().apply {
            put("ok", true)
            put("action", action)
            put("affected", outcome.message)
            put("restarting", true)
        }.toString()
    }

    /**
     * GET /ocr —— 离线 OCR 识别当前屏幕（v1.2.123）。
     * 无障碍节点树拿不到文字的场景（OPPO 浏览器/WebView/自绘界面）用它
     * 作为独立文本来源；`/screen` 在文本稀少时会自动附带 OCR 结果
     * （dumpScreenCompact 兜底），本端点用于强制刷新与诊断。
     * ?force=1 忽略缓存重新识别。
     */
    private fun ocr(query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val params = parseQuery(query)
        val force = params["force"] in listOf("1", "true", "yes")
        val scale = params["scale"]?.toFloatOrNull() ?: 0.5f
        val r = app.dsh.mobile.ScreenOcr.recognize(svc, force, scale)
        val arr = JSONArray()
        for (b in r.blocks) {
            arr.put(JSONObject().apply {
                put("text", b.text)
                put("x", b.cx)
                put("y", b.cy)
                put("left", b.left)
                put("top", b.top)
                put("right", b.right)
                put("bottom", b.bottom)
            })
        }
        return 200 to JSONObject().apply {
            put("ok", !r.unavailable)
            put("unavailable", r.unavailable)
            put("cached", r.cached)
            put("costMs", r.costMs.toInt())
            put("lines", r.blocks.size)
            put("blocks", arr)
        }.toString()
    }

    /** GET /local/status —— 本地快速决策层的就绪状态（v1.2.121） */
    private fun localStatus(ctx: Context): Pair<Int, String> =
        200 to JSONObject().apply {
            put("ok", true)
            put("modelReady", LocalModel.isModelReady(ctx))
            put("serverRunning", LocalModel.isServerRunning())
            put("modelPath", LocalModel.modelFile(ctx).absolutePath)
            put("port", LocalEmbedder.PORT)
            // v1.2.124：本地判决 AI 的实际用量（exact/semantic/none + verify 语义救场）
            put("stats", FastPicker.stats.snapshot())
        }.toString()

    /**
     * POST /local/ensure —— 确保本地模型与服务就绪（后台执行，v1.2.121）。
     * 首次调用会下载 15MB 模型并启动 llama-server；返回 202 立即开始，
     * 结果通过 /local/status 轮询（或等通知）。
     */
    private fun localEnsure(ctx: Context): Pair<Int, String> {
        Thread({
            val ok = LocalModel.ensureReady(ctx) { _, msg ->
                Log.i("LocalModel", "ensure: $msg")
            }
            Log.i("LocalModel", "ensureReady -> $ok")
        }, "local-model-ensure").apply { isDaemon = true }.start()
        return 202 to """{"ok":true,"started":true,"hint":"poll GET /local/status"}"""
    }

    /**
     * POST /pick —— 本地快速决策（v1.2.120）。
     *
     * 主人设计的架构：本地只做「文本 → 选哪个节点」的判断题（毫秒级），
     * 坐标由无障碍层直接取，云端模型负责规划与兜底。
     *
     * body: {"target":"WLAN 开关", "tap":true, "clickableOnly":true}
     * resp: {"ok":true,"via":"exact|semantic|none","text":"...","x":..,"y":..,
     *        "score":0.93,"tapped":true}
     *
     * via=none 表示本地判断不了 → 调用方（AI）应自己 dump 决定（云端兜底）。
     */
    private fun pick(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val target = obj.optString("target").trim()
        if (target.isBlank()) return 400 to """{"ok":false,"error":"field 'target' required"}"""
        val wantTap = obj.optBoolean("tap", false)
        val clickableOnly = obj.optBoolean("clickableOnly", true)

        val dumpRes = withScreenTimeout("pick.dump") { svc.dumpScreenCompact("all") }
            ?: return 504 to """{"ok":false,"error":"screen read timed out"}"""
        val dump = dumpRes.getOrElse {
            return 500 to """{"ok":false,"error":"${it.message}"}"""
        }
        val t0 = System.currentTimeMillis()
        val r = FastPicker.pick(dump, target, clickableOnly)
        val costMs = System.currentTimeMillis() - t0

        val c = r.candidate
        if (c == null) {
            // 本地判断不了：把候选列表一并给出，方便 AI 一轮内决定（减少往返）
            // 候选 = 有文本的节点（可点击容器文本是 "-"，见 FastPicker 的说明）
            val pool = FastPicker.parseCompact(dump)
                .filter { it.text.isNotBlank() && it.text != "-" }
                .take(24)
                .joinToString(",") { """{"i":${it.index},"t":${JSONObject.quote(it.text)},"x":${it.x},"y":${it.y}}""" }
            return 200 to """{"ok":true,"via":"none","costMs":$costMs,"candidates":[$pool]}"""
        }
        val tapped = if (wantTap) {
            svc.dispatchTapRect(android.graphics.Rect(c.x, c.y, c.x, c.y))
        } else false
        return 200 to buildString {
            append("""{"ok":true,"via":"${r.via}","score":${"%.3f".format(r.score)},""")
            append(""""text":${JSONObject.quote(c.text)},"x":${c.x},"y":${c.y},""")
            append(""""costMs":$costMs,"tapped":$tapped}""")
        }
    }

    /**
     * GET /frame-diff —— 帧差：本地 <5ms 判断「画面变了没」（v1.2.122）。
     *
     * 事件流覆盖不了**动态视觉场景**（Canvas 游戏、视频、动画、WebGL）
     * —— 那些没有节点树也没有可用事件。帧差是这类场景唯一可靠的判据。
     */
    private fun frameDiff(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val r = withScreenTimeout("frame-diff", 5_000) {
            app.dsh.mobile.Framer.captureAndDiff(svc)
        }?.getOrNull() ?: return 504 to """{"ok":false,"error":"frame capture timed out"}"""
        if (r.unavailable) {
            return 400 to """{"ok":false,"error":"screenshot unavailable (needs API 30+ and screenshot capability; toggle the service off/on)"}"""
        }
        val regions = r.hotRegions.joinToString(",") {
            """{"nx":${"%.3f".format(it.nx)},"ny":${"%.3f".format(it.ny)},"ratio":${"%.3f".format(it.ratio)}}"""
        }
        return 200 to buildString {
            append("""{"ok":true,"changed":${r.changed},"changedRatio":${"%.4f".format(r.changedRatio)},""")
            append(""""costMs":${r.costMs},"isFirst":${r.isFirst},""")
            append(""""cx":${"%.3f".format(r.cx)},"cy":${"%.3f".format(r.cy)},""")
            append(""""hotRegions":[$regions]}""")
        }
    }

    /**
     * POST /frame-wait —— 等画面变化（超时返回 changed:false，替代固定 sleep）。
     */
    private fun frameWait(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val timeoutMs = obj.optLong("timeoutMs", 5000L).coerceIn(300L, 30_000L)
        val intervalMs = obj.optLong("intervalMs", 250L).coerceIn(100L, 2000L)
        val start = System.currentTimeMillis()
        var polls = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            polls++
            val r = runCatching { app.dsh.mobile.Framer.captureAndDiff(svc) }.getOrNull()
            if (r != null && r.changed) {
                return 200 to """{"ok":true,"changed":true,"costMs":${System.currentTimeMillis() - start},"polls":$polls,"changedRatio":${"%.4f".format(r.changedRatio)}}"""
            }
            Thread.sleep(intervalMs)
        }
        return 200 to """{"ok":true,"changed":false,"timedOut":true,"polls":$polls,"waitedMs":$timeoutMs}"""
    }

    /**
     * POST /loop/run —— 本地闭环执行「决策程序」（v1.2.123）。
     *
     * body: 决策程序 JSON（字段说明见 LocalLoopExecutor.run 的 KDoc）
     * resp: 执行报告 JSON —— 每步 via/score/耗时/尝试记录，escalate 时附候选池证据。
     *
     * 同步执行（一条连接跑到结束，预算见 budgetMs）：调用方一次请求即可拿到完整闭环报告。
     * 与 /pick 的区别：/pick 是单步判别，/loop/run 是**连续多步闭环**（PICK→ACT→VERIFY
     * →RETRY→ESCALATE），即「云端编译一次、本地跑完一串」的那条快路径。
     */
    private fun loopRun(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        // v1.7.2：单次预算默认 180s → 300s，并且可以由调用方在计划里用 budgetMs 指定。
        //
        // 实测（emulator-5556，滚动一步 1167ms）：180s ≈ 154 步，300s ≈ 257 步。
        // 但**真正先撞上的墙不是这里**：模型是通过 bash 工具跑 `loop run -` 的，而
        // bash-local 默认 120s（≈102 步）就把命令转成后台作业。所以长计划要么在 bash
        // 调用里传 timeoutMs，要么接受「结果从 job_output 拿」。这条链路的四个预算：
        //   bash-local timeoutMs 120s（默认，可传，上限 600s）
        //   → 闸门 DSH_HTTP_TIMEOUT 315s（跟随 budgetMs）
        //   → 这里 budgetMs（默认 300s，夹在 10s~600s）
        //   → 引擎 tool-call timeout（由工具自己声明）
        // 客户端必须严格大于服务端，否则结构化超时报告到不了，调用方只能重跑 ——
        // 而重跑是唯一会放大真实副作用（重复点击/重复提交）的路径。
        val budgetMs = runCatching { JSONObject(body).optLong("budgetMs", LOOP_BUDGET_MS) }
            .getOrDefault(LOOP_BUDGET_MS)
            .coerceIn(LOOP_BUDGET_MIN_MS, LOOP_BUDGET_MAX_MS)
        // 同一个单调时钟（System.nanoTime）算截止点：执行器在步与步之间自己收手，
        // 这样即使 HTTP 这边超时返回了，循环也不会继续在后台点。
        val deadlineAt = System.nanoTime() / 1_000_000L + budgetMs
        // HTTP 侧的兜底要**比执行器的截止点晚一点**：执行器在两步之间收手并返回
        // 带 budgetExceeded 的完整报告，调用方拿到的是「做到第几步、为什么停」；
        // 如果这里先超时，调用方只看到一个 504，前面的报告全丢了。
        val r = withScreenTimeout("loop/run", budgetMs + 5_000L) {
            LocalLoopExecutor.run(ctx, svc, body, deadlineAt)
        }
        return when {
            r == null -> 504 to """{"ok":false,"error":"loop timed out (>${budgetMs}ms)","budgetMs":$budgetMs}"""
            r.isSuccess -> 200 to r.getOrThrow()
            else -> 500 to ("""{"ok":false,"error":"loop execution failed: """ +
                (r.exceptionOrNull()?.message ?: "unknown").replace("\"", "'") + """}""")
        }
    }

    // ==================== 视觉定位端点组（v1.2.124） ====================
    //
    // 无节点树场景（游戏 / Canvas / 纯图片）的「目标在哪」。
    // 分工：云端看懂画面并圈出目标（/vision/crop 存模板）→ 本地每帧模板匹配
    // 定位（/vision/track）→ 点击 / 拖动（/vision/tap、/vision/drag）。
    // 手机游戏的操作无非「虚拟按钮点按 + 摇杆/视角拖动」，手势层都能做，
    // 缺的只是视觉定位——这组端点补的就是它，毫秒级、不需要云端往返。

    /**
     * GET /daemon/status —— 悬浮窗用的：daemon 是否在跑任务 / 是否 needsHelp。
     * v1.2.157 用户反馈：daemon 卡住时用户无法主动取消，且一切回应用它又切回浏览器干活。
     * 悬浮窗第三档据此显示/隐藏「退出 daemon」按钮，并可在 needsHelp 时提示用户。
     */
    private fun daemonStatus(): Pair<Int, String> {
        val tasks = java.io.File(dshHome(), "tasks")
        val out = org.json.JSONObject()
        val arr = org.json.JSONArray()
        tasks.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                val o = org.json.JSONObject(f.readText())
                val st = o.optString("status")
                if (st == "running" || st == "queued" || o.optBoolean("needsHelp", false)) {
                    arr.put(org.json.JSONObject().apply {
                        put("id", o.optString("id", f.nameWithoutExtension))
                        put("status", st)
                        put("needsHelp", o.optBoolean("needsHelp", false))
                        put("goal", o.optString("goal", "").take(60))
                        put("rounds", o.optJSONArray("history")?.length() ?: 0)
                    })
                }
            }
        }
        out.put("active", arr.length() > 0)
        out.put("tasks", arr)
        return 200 to out.toString()
    }

    /** POST /daemon/stop —— 立即停止当前 daemon 任务（悬浮窗「退出 daemon」按钮） */
    private fun daemonStop(): Pair<Int, String> {
        val tasks = java.io.File(dshHome(), "tasks")
        var stopped = 0
        tasks.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                val o = org.json.JSONObject(f.readText())
                if (o.optString("status") == "running") {
                    o.put("stopRequested", true)
                    f.writeText(o.toString())
                    java.io.File(tasks, o.optString("id", f.nameWithoutExtension) + ".stop")
                        .writeText("stop")
                    stopped++
                }
            }
        }
        return 200 to """{"ok":true,"stopped":$stopped}"""
    }

    /** $DSH_HOME（引擎子进程的 dsh-home 目录；悬浮窗/daemon 端点共用） */
    private fun dshHome(): java.io.File {
        val c = shotCtx ?: return java.io.File("")
        return java.io.File(EngineConfig.engineRoot(c).parentFile, "dsh-home")
    }

    private fun visionDir(ctx: Context): java.io.File =
        java.io.File(ctx.filesDir, "vision").apply { mkdirs() }

    /**
     * POST /vision/crop —— 从当前屏幕裁剪一块存为模板（云端圈目标用）。
     * body: {"id":"btn_attack", "x":..,"y":..,"w":..,"h":..} 绝对像素
     *   或  {"id":"..", "nx":0.7,"ny":0.8,"nw":0.2,"nh":0.2} 归一化坐标
     * resp: {"ok":true,"id":"..","w":..,"h":..,"pngBytes":N}
     */
    private fun visionCrop(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        // ⚠️ v1.4.46（安全审查 M1）：id 直接拼进文件路径，未校验时可传
        // `../../engine/bin/su` 覆盖任意应用私有文件（含 su 闸门 shim、引擎配置、
        // shared_prefs）。白名单只允许安全字符，且长度受限。
        val rawId = obj.optString("id").ifBlank { "t${System.currentTimeMillis()}" }
        val id = if (rawId.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) rawId else
            return 400 to """{"ok":false,"error":"id must match [A-Za-z0-9_-]{1,64}"}"""
        // 同 trackOnce：直接调用，不经 screenExec（避免被节点树 IPC 拖死）
        val shot = runCatching { svc.screenshotArgb() }.getOrNull()
            ?: return 500 to """{"ok":false,"error":"screenshot unavailable (needs API 30+ capability)"}"""
        val (_, sw, sh) = shot
        // 归一化坐标优先（云端看截图后给的通常是比例）
        val x: Int; val y: Int; val w: Int; val h: Int
        if (obj.has("nx")) {
            val nx = obj.optDouble("nx"); val ny = obj.optDouble("ny")
            val nw = obj.optDouble("nw", 0.2); val nh = obj.optDouble("nh", 0.2)
            x = (nx * sw).toInt(); y = (ny * sh).toInt()
            w = (nw * sw).toInt(); h = (nh * sh).toInt()
        } else {
            x = obj.optInt("x"); y = obj.optInt("y")
            w = obj.optInt("w"); h = obj.optInt("h")
        }
        val cropped = app.dsh.mobile.VisionTracker.crop(shot, x, y, w, h)
            ?: return 400 to """{"ok":false,"error":"crop rect out of bounds ($x,$y,$w,$h) screen=${sw}x$sh"}"""
        val (arr, cw, ch) = cropped
        // 有效性检查（实测教训：裁到纯色背景/黑帧时模板无特征，匹配会随机跳到
        // 任意区域且分数虚高——例如裁到深蓝背景后匹配到屏幕底部、分数 0.956）
        val variance = run {
            var sum = 0.0; var sum2 = 0.0
            val step = maxOf(1, arr.size / 2000)
            var n = 0
            var i = 0
            while (i < arr.size) {
                val c = arr[i]
                val g = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000.0
                sum += g; sum2 += g * g; n++
                i += step
            }
            val mean = sum / n
            sum2 / n - mean * mean
        }
        if (variance < 120.0) {
            return 400 to """{"ok":false,"error":"cropped area is too flat (variance=${"%.0f".format(variance)}) — likely a solid-colour background; verify the coordinates with /screenshot"}"""
        }
        val png = app.dsh.mobile.VisionTracker.encodePng(arr, cw, ch)
            ?: return 500 to """{"ok":false,"error":"png encode failed"}"""
        val f = java.io.File(visionDir(ctx), "$id.png")
        f.writeBytes(png)
        return 200 to """{"ok":true,"id":$id,"w":$cw,"h":$ch,"pngBytes":${png.size},"path":"${f.absolutePath}"}"""
    }

    /** 读模板文件并解码（/vision/track 与 tap/drag 共用） */
    private fun loadTemplate(ctx: Context, id: String): Triple<IntArray, Int, Int>? {
        // ⚠️ v1.4.46（安全审查 M1）：/vision/track、/vision/tap、/vision/drag 共用本函数，
        // 同样要挡 `../` 穿越读任意文件。canonical 前缀校验是最后一道（与 open-file
        // 端点同一模式），即使将来有别的调用方漏了白名单也能兜住。
        if (!id.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) return null
        val dir = visionDir(ctx).canonicalFile
        val f = java.io.File(dir, "$id.png")
        val canon = runCatching { f.canonicalFile }.getOrNull() ?: return null
        if (canon.parentFile != dir) return null
        if (!canon.exists()) return null
        return app.dsh.mobile.VisionTracker.decodePng(canon.readBytes())
    }

    private fun trackOnce(
        svc: DshAccessibilityService,
        ctx: Context,
        id: String,
        region: FloatArray? = null,
    ): app.dsh.mobile.VisionTracker.Match? {
        val tmpl = loadTemplate(ctx, id) ?: return null
        // ⚠️ 截图**直接调用**，不走 withScreenTimeout/screenExec —— 实测该线程池
        // 会被节点树 IPC 挂起拖死，导致截图排队全部超时（/screenshot 端点不受影响
        // 正是因为它直接调用）。screenshotArgb 自带 latch 超时，不会无限阻塞。
        var shot: Triple<IntArray, Int, Int>? = null
        for (i in 0 until 3) {
            shot = runCatching { svc.screenshotArgb() }.getOrNull()
            if (shot != null) break
            Thread.sleep(300)
        }
        val s = shot ?: return null
        return app.dsh.mobile.VisionTracker.locate(s, tmpl.first, tmpl.second, tmpl.third, region)
    }

    /** 解析可选的搜索区域（归一化 nx/ny/nw/nh） */
    private fun parseRegion(obj: JSONObject): FloatArray? {
        val r = obj.optJSONObject("region") ?: return null
        return floatArrayOf(
            r.optDouble("nx", 0.0).toFloat(),
            r.optDouble("ny", 0.0).toFloat(),
            r.optDouble("nw", 1.0).toFloat(),
            r.optDouble("nh", 1.0).toFloat(),
        )
    }

    /**
     * POST /vision/track —— 定位模板在当前屏幕的位置（本地模板匹配，毫秒级）。
     * body: {"id":"btn_attack"}
     * resp: {"ok":true,"x":..,"y":..,"score":0.88,"costMs":12,...}
     */
    private fun visionTrack(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val id = runCatching { JSONObject(body).optString("id") }.getOrDefault("")
        if (id.isBlank()) return 400 to """{"ok":false,"error":"field 'id' required"}"""
        if (loadTemplate(ctx, id) == null) return 404 to """{"ok":false,"error":"template '$id' not found (use /vision/crop first)"}"""
        val region = parseRegion(runCatching { JSONObject(body) }.getOrDefault(JSONObject()))
        val m = trackOnce(svc, ctx, id, region)
            ?: return 500 to """{"ok":false,"error":"match failed (screenshot unavailable?)"}"""
        return 200 to buildString {
            append("""{"ok":true,"id":${JSONObject.quote(id)},"x":${m.x},"y":${m.y},""")
            append(""""score":${"%.3f".format(m.score)},"costMs":${m.costMs},""")
            append(""""proc":{"x":${m.procX},"y":${m.procY},"w":${m.procW},"h":${m.procH},"fw":${m.frameW},"fh":${m.frameH}}}""")
        }
    }

    /**
     * POST /vision/tap —— 定位并点击（虚拟按钮的标准操作）。
     * body: {"id":"btn_attack", "minScore":0.6}
     */
    private fun visionTap(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val id = obj.optString("id")
        if (id.isBlank()) return 400 to """{"ok":false,"error":"field 'id' required"}"""
        val minScore = obj.optDouble("minScore", 0.5)
        val m = trackOnce(svc, ctx, id, parseRegion(obj))
            ?: return 500 to """{"ok":false,"error":"match failed"}"""
        if (m.score < minScore) {
            return 200 to """{"ok":false,"error":"score too low","score":${"%.3f".format(m.score)},"x":${m.x},"y":${m.y},"minScore":$minScore}"""
        }
        val ok = withTimeoutTap(svc, m.x, m.y)
        return 200 to """{"ok":$ok,"tapped":$ok,"x":${m.x},"y":${m.y},"score":${"%.3f".format(m.score)},"costMs":${m.costMs}}"""
    }

    private fun withTimeoutTap(svc: DshAccessibilityService, x: Int, y: Int): Boolean =
        runCatching { svc.dispatchTapRect(android.graphics.Rect(x, y, x, y)) }.getOrDefault(false)

    /**
     * POST /vision/drag —— 定位并拖动（摇杆 / 视角滑动）。
     * body: {"id":"joystick", "dx":80, "dy":0, "ms":600}  从目标中心拖 dx,dy（原图像素）
     *   或  {"id":"joystick", "toNx":0.5,"toNy":0.8}      拖到屏幕归一化位置
     */
    private fun visionDrag(ctx: Context, body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val id = obj.optString("id")
        if (id.isBlank()) return 400 to """{"ok":false,"error":"field 'id' required"}"""
        val m = trackOnce(svc, ctx, id, parseRegion(obj))
            ?: return 500 to """{"ok":false,"error":"match failed"}"""
        val (sw, sh) = svc.screenSize()
        val x2: Float; val y2: Float
        if (obj.has("toNx")) {
            x2 = (obj.optDouble("toNx") * sw).toFloat()
            y2 = (obj.optDouble("toNy") * sh).toFloat()
        } else {
            x2 = (m.x + obj.optDouble("dx", 0.0)).toFloat()
            y2 = (m.y + obj.optDouble("dy", 0.0)).toFloat()
        }
        val ms = obj.optLong("ms", 500L).coerceIn(100L, 3000L)
        // v1.6.32：拖拽类动作带 2.5% 的收手过冲再回弹 —— 真人拉到目标位不会一次到位，
        // 终点仍严格落在 (x2,y2)，不影响定位精度。
        val ok = runCatching {
            svc.dispatchSwipe(m.x.toFloat(), m.y.toFloat(), x2, y2, ms, overshoot = 0.025f)
        }.getOrDefault(false)
        return 200 to """{"ok":$ok,"fromX":${m.x},"fromY":${m.y},"toX":${x2.toInt()},"toY":${y2.toInt()},"ms":$ms,"score":${"%.3f".format(m.score)}}"""
    }

    /** GET /vision/list —— 已存模板列表 */
    private fun visionList(ctx: Context): Pair<Int, String> {
        val files = visionDir(ctx).listFiles { f -> f.name.endsWith(".png") } ?: emptyArray()
        val arr = org.json.JSONArray()
        files.forEach { arr.put(JSONObject().put("id", it.nameWithoutExtension).put("bytes", it.length())) }
        return 200 to JSONObject().put("ok", true).put("templates", arr).toString()
    }

    /**
     * POST /memory/lookup —— 查场景记忆（v1.2.125）。
     * body: {"goal":"...", "dump":"..."(可选，不给则自动读屏)}
     * resp: {"ok":true,"hit":true,"similarity":0.88,"reused":3,"plan":{...}}
     */
    private fun memoryLookup(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        if (goal.isBlank()) return 400 to """{"ok":false,"error":"field 'goal' required"}"""
        var dump = obj.optString("dump")
        if (dump.isBlank()) {
            val svc = DshAccessibilityService.instance
                ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            dump = withScreenTimeout("memory.dump") { svc.dumpScreenCompact("all") }
                ?.getOrNull() ?: return 504 to """{"ok":false,"error":"screen read timed out"}"""
        }
        val fp = SceneMemory.fingerprint(dump, DshAccessibilityService.instance?.foregroundPackage() ?: "")
        val (entry, sim) = SceneMemory.lookup(ctx, goal, fp)
        return if (entry != null) {
            200 to JSONObject().apply {
                put("ok", true).put("hit", true)
                put("similarity", NumFmt.round2(sim))
                put("reused", entry.hits)
                put("plan", entry.plan)
            }.toString()
        } else {
            200 to JSONObject().apply {
                put("ok", true).put("hit", false)
                put("bestSimilarity", NumFmt.round2(sim))
                put("fpSize", fp.size)
            }.toString()
        }
    }

    /**
     * POST /memory/store —— 存一条成功决策（v1.2.125）。
     * body: {"goal":"...", "plan":{...}, "dump":"..."(可选)}
     */
    private fun memoryStore(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        val plan = obj.optJSONObject("plan")
        if (goal.isBlank() || plan == null) return 400 to """{"ok":false,"error":"fields 'goal' and 'plan' required"}"""
        var dump = obj.optString("dump")
        if (dump.isBlank()) {
            val svc = DshAccessibilityService.instance
                ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            dump = withScreenTimeout("memory.dump") { svc.dumpScreenCompact("all") }
                ?.getOrNull() ?: return 504 to """{"ok":false,"error":"screen read timed out"}"""
        }
        val fp = SceneMemory.fingerprint(dump, DshAccessibilityService.instance?.foregroundPackage() ?: "")
        SceneMemory.store(ctx, goal, fp, plan)
        return 200 to JSONObject().put("ok", true).put("stored", true)
            .put("fpSize", fp.size).put("summary", SceneMemory.stats(ctx)).toString()
    }

    /**
     * POST /memory/confirm —— 任务级确认（v1.2.126）：云端判定目标达成后调用，
     * 把本次成功执行产生的 **pending** 记忆转正（pending 不参与命中，防污染）。
     * body: {"goal":"...", "dump":"..."(可选)}
     * resp: {"ok":true,"promoted":N,"pendingLeft":M}
     */
    private fun memoryConfirm(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val goal = obj.optString("goal").trim()
        if (goal.isBlank()) return 400 to """{"ok":false,"error":"field 'goal' required"}"""
        val n = SceneMemory.confirm(ctx, goal)
        return 200 to JSONObject().put("ok", true).put("promoted", n)
            .put("pendingLeft", SceneMemory.pendingCount(ctx)).toString()
    }

    /** /screen-changed：事件驱动变化摘要（v1.2.113）——毫秒级，不 dump 整树 */
    private fun screenChanged(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val text = runCatching { svc.changedSummary() }.getOrDefault("")
        return if (text.isBlank()) {
            200 to """{"ok":true,"changed":false,"events":[]}"""
        } else {
            val arr = text.lineSequence().joinToString(",") { JSONObject.quote(it) }
            200 to """{"ok":true,"changed":true,"events":[$arr]}"""
        }
    }

    private fun screenshot(query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val caps = svc.serviceInfo?.capabilities ?: 0
        if (Build.VERSION.SDK_INT >= 30 &&
            caps and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0
        ) {
            return 400 to """{"ok":false,"error":"accessibility service lacks screenshot capability — toggle the service off/on in system settings to grant it"}"""
        }
        return try {
            val shot = svc.screenshotBase64()
                ?: return 400 to """{"ok":false,"error":"screenshot failed (timeout or unsupported)"}"""
            // ?max=N：把最长边缩到 N 像素（省 base64 体积 + 省上下文）
            val maxDim = parseQuery(query)["max"]?.toIntOrNull()?.coerceIn(256, 4096)
            var w = shot.first; var h = shot.second; var b64 = shot.third
            if (maxDim != null && maxOf(w, h) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(w, h)
                w = (w * scale).toInt(); h = (h * scale).toInt()
                val srcBmp = android.graphics.BitmapFactory.decodeByteArray(
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), 0,
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP).size)
                val bmp = android.graphics.Bitmap.createScaledBitmap(srcBmp, w, h, true)
                srcBmp.recycle()
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, baos)
                b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                bmp.recycle()
            }
            // ?save=1（v1.2.138）：保存到文件**只回路径**——base64 极吃上下文
            // （1080p PNG ≈ 100-200KB），AI 用 read_image 按需读图更省。
            // 顺带清理旧图（保留最近 12 张 shot-*），避免目录无限膨胀。
            if (parseQuery(query)["save"] == "1") {
                val c = shotCtx
                if (c != null) {
                    val dir = java.io.File(c.filesDir, "screens").apply { mkdirs() }
                    val f = java.io.File(dir, "shot-${System.currentTimeMillis()}.png")
                    f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                    dir.listFiles { file -> file.name.startsWith("shot-") }
                        ?.sortedBy { it.lastModified() }?.dropLast(12)?.forEach { it.delete() }
                    return 200 to """{"ok":true,"path":"${f.absolutePath}","width":$w,"height":$h,"hint":"read it with the read_image tool"}"""
                }
            }
            return 200 to """{"ok":true,"format":"png","width":$w,"height":$h,"base64":"$b64"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** POST /gesture → 手势注入（无障碍 dispatchGesture，无需 Shizuku/Root）。
     *  swipe: {"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"durationMs":300}
     *  long_press: {"type":"long_press","x":..,"y":..,"durationMs":600}
     *  tap: {"type":"tap","x":..,"y":..} */
    private fun gesture(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            // 悬浮窗：让用户看到「正在滑动/长按」（此前只有 /tap 会通知，见 announceAction 注释）
            announceAction(
                R.string.a11y_action_swipe,
                runCatching {
                    val o = JSONObject(body)
                    val t = o.optString("type").ifBlank { "gesture" }
                    if (o.has("x1")) "$t ${o.optDouble("x1").toInt()},${o.optDouble("y1").toInt()} -> ${o.optDouble("x2").toInt()},${o.optDouble("y2").toInt()}"
                    else "$t ${o.optDouble("x").toInt()},${o.optDouble("y").toInt()}"
                }.getOrDefault("gesture"),
            )
        return try {
            val obj = JSONObject(body)
            val ok = when (obj.optString("type")) {
                // v1.2.149：默认**拟人化**滑动（弧线+变速+抖动）；human=false 精确直线
                // v1.6.32：durationMs 缺省 = 0（按距离自动），不再写死 300ms ——
                // 固定时长 + 直线是两道最容易被自动化检测识别的特征。
                "swipe" -> if (obj.optBoolean("human", true)) svc.dispatchSwipeHuman(
                    obj.getDouble("x1").toFloat(), obj.getDouble("y1").toFloat(),
                    obj.getDouble("x2").toFloat(), obj.getDouble("y2").toFloat(),
                    obj.optLong("durationMs", 0L),
                    overshoot = obj.optDouble("overshoot", 0.0).toFloat(),
                ) else svc.dispatchSwipe(
                    obj.getDouble("x1").toFloat(), obj.getDouble("y1").toFloat(),
                    obj.getDouble("x2").toFloat(), obj.getDouble("y2").toFloat(),
                    obj.optLong("durationMs", 0L),
                    overshoot = obj.optDouble("overshoot", 0.0).toFloat(),
                )
                "long_press" -> svc.dispatchLongPress(
                    obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat(),
                    obj.optLong("durationMs", 600L),
                )
                "tap" -> svc.dispatchTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                else -> return 400 to """{"ok":false,"error":"type must be swipe|long_press|tap"}"""
            }
            if (ok) 200 to """{"ok":true}""" else 500 to """{"ok":false,"error":"gesture dispatch failed"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** POST /key → 全局动作。
     *  API 26 可用：back / home / recents / notifications / quick_settings。
     *  API 28+ 追加：lock（直接锁屏）/ screenshot（系统截图，落通知栏）/ power_dialog（电源菜单）。
     *  ⚠️ 实测本应用内 back 会把整个 Activity 弹到桌面而非关闭弹层，
     *  调用方应配合 /screen 检查前台是否仍在目标界面。 */
    private fun key(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            // 悬浮窗：导航键也要显示（此前完全没有）
            announceAction(R.string.a11y_action_key, "key " + JSONObject(body).optString("action"))
        return try {
            val action = JSONObject(body).optString("action")
            val ok = svc.performGlobalActionByName(action)
            if (ok) 200 to """{"ok":true,"action":"$action"}"""
            else 400 to """{"ok":false,"error":"unknown action (back|home|recents|notifications|quick_settings|lock|screenshot|power_dialog; lock/screenshot need API 28+)"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** POST /wait → 服务端轮询等待文本出现/消失（省客户端轮询）。
     *  {"text":"..","gone":false,"timeoutMs":5000}；200ms 间隔，上限 15s。
     *  v1.2.31 实测：screenContains 的节点树 IPC 在请求线程上偶发挂起
     *  （found 分支 Read timed out），故改走 dumpScreenJson —— 与 /screen
     *  同一条已验证路径，且整体包 try/catch。 */
    private fun wait(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
            val text = obj.optString("text").ifEmpty {
                return 400 to """{"ok":false,"error":"missing text"}"""
            }
            val gone = obj.optBoolean("gone", false)
            val timeout = obj.optLong("timeoutMs", 5000L).coerceIn(100L, 15000L)
            val deadline = System.currentTimeMillis() + timeout
            while (System.currentTimeMillis() < deadline) {
                // 单次读取同样受超时约束：僵死时不再无限期阻塞轮询循环
                val found = withScreenTimeout("wait/dumpScreenJson") { svc.dumpScreenJson() }
                    ?.getOrNull()
                    ?.let { raw ->
                        val nodes = JSONObject(raw).getJSONArray("nodes")
                        (0 until nodes.length()).any { i ->
                            val n = nodes.getJSONObject(i)
                            n.optString("text").contains(text, true) ||
                                n.optString("desc").contains(text, true)
                        }
                    } ?: false
                if (found != gone) {
                    val waited = timeout - (deadline - System.currentTimeMillis())
                    return 200 to """{"ok":true,"condition":"${if (gone) "disappeared" else "appeared"}","waitedMs":$waited}"""
                }
                Thread.sleep(200)
            }
            200 to """{"ok":false,"error":"timeout: text ${if (gone) "still present" else "not found"}"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * GET /diff → 界面差分（v1.2.82，无障碍提案 P1-3）。
     *
     * 只回**变化**的节点（新增/消失），而不是整屏重 dump。
     * 用途：判断"点完界面到底有没有反应"，以及等待列表加载。
     * 首次调用建立基线（added/removed 为空）。
     * 传 ?reset=1 强制重建基线。
     */
    private fun diffScreen(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val (added, removed) = svc.diffScreen()
        return 200 to JSONObject().apply {
            put("ok", true)
            put("changed", added.isNotEmpty() || removed.isNotEmpty())
            put("added", org.json.JSONArray(added.toList()))
            put("removed", org.json.JSONArray(removed.toList()))
        }.toString()
    }

    /**
     * POST /scroll-center → 滚动查找并让目标居中（v1.2.82，提案 P2-6）。
     * body: {"text":"...","maxSwipes":8,"back":false,"tap":false}
     *
     * 与 /scroll-find 的区别：命中后若目标贴近屏幕边缘，会补一次小幅滑动把它
     * 带到中间区域 —— 避免"点完该项在屏幕边缘，长按/拖拽打偏"。
     */
    private fun scrollCenter(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
            val t = ((obj.optString("text").ifBlank { obj.optString("query") })).trim()
            if (t.isBlank()) return 400 to """{"ok":false,"error":"text is required"}"""
            val maxSwipes = obj.optInt("maxSwipes", 8)
            val forward = !obj.optBoolean("back", false)
            val rect = svc.scrollToCenter(t, maxSwipes, forward)
                ?: return 200 to """{"ok":false,"error":"not found after scrolling"}"""
            val tapped = if (obj.optBoolean("tap", false)) svc.dispatchTapRect(rect) else false
            JSONObject().apply {
                put("ok", true)
                put("x", rect.centerX()); put("y", rect.centerY())
                put("w", rect.width()); put("h", rect.height())
                if (tapped) put("tapped", true)
            }.let { 200 to it.toString() }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /input → 向输入框写文字（v1.2.54）。
     * body: {"text":"...", "append":false, "target":「<输入框提示文字>」}
     *
     * 此前 AI 能点开搜索框却打不了字 —— 搜索/登录/发消息/填表全部卡死在这一步。
     * 走无障碍 ACTION_SET_TEXT（不走 IME，不受输入法语言/联想干扰），失败回退剪贴板粘贴。
     */
    private fun input(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            // 悬浮窗：输入文字也要显示（此前完全没有）
            announceAction(
                R.string.a11y_action_input,
                "input \"" + JSONObject(body).optString("text").take(24) + "\"",
            )
        return try {
            val obj = JSONObject(body)
            val text = obj.optString("text")
            if (text.isEmpty() && !obj.has("text")) {
                return 400 to """{"ok":false,"error":"missing text"}"""
            }
            val append = obj.optBoolean("append", false)
            val target = obj.optString("target").takeIf { it.isNotBlank() }
            val r = withScreenTimeout("inputText", 4_000L) {
                svc.inputText(text, append, target)
            } ?: return 504 to """{"ok":false,"error":"input timed out (node tree stalled)"}"""
            val ok = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
            if (ok) 200 to """{"ok":true,"chars":${text.length}}"""
            else 500 to """{"ok":false,"error":"no editable field found (tap the input box first, or pass target=)"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /scroll-find → 在可滚动容器内滚动查找并可选点击（v1.2.54）。
     * body: {"text":"...", "tap":true, "maxSwipes":8, "back":false}
     *
     * 把「swipe → dump → 没找到 → 再 swipe」的手动循环压到服务端一次调用
     * （此前一次找元素要烧 5-10 个工具往返）。
     */
    private fun scrollFind(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            // 悬浮窗：滚动查找也要显示（此前完全没有）
            announceAction(
                R.string.a11y_action_screen,
                "scroll-find \"" + JSONObject(body).optString("text").take(24) + "\"",
            )
        return try {
            val obj = JSONObject(body)
            val text = obj.optString("text")
            if (text.isBlank()) return 400 to """{"ok":false,"error":"missing text"}"""
            val wantTap = obj.optBoolean("tap", false)
            val maxSwipes = obj.optInt("maxSwipes", 8)
            val forward = !obj.optBoolean("back", false)
            // 滚动 + 多次读屏，整体给足时间（每次滚动 ~260ms + 读屏）
            val timeout = (maxSwipes.coerceIn(1, 30) * 700L) + 3_000L
            val r = withScreenTimeout("scrollToFind", timeout) {
                svc.scrollToFind(text, maxSwipes, forward)
            } ?: return 504 to """{"ok":false,"error":"scroll-find timed out (node tree stalled)"}"""
            val hit = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
                ?: return 200 to """{"ok":false,"error":"not found after scrolling","scrolled":$maxSwipes}"""
            val cx = hit.exactCenterX()
            val cy = hit.exactCenterY()
            if (wantTap) {
                val tapped = svc.dispatchTapRect(hit)
                if (tapped) 200 to """{"ok":true,"x":${cx.toInt()},"y":${cy.toInt()},"tapped":true}"""
                else 500 to """{"ok":false,"error":"found but tap dispatch failed"}"""
            } else {
                200 to """{"ok":true,"x":${cx.toInt()},"y":${cy.toInt()},"tapped":false}"""
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /idle → 等待界面稳定（v1.2.54）。
     * body: {"timeoutMs":3000, "quietMs":120, "stableReads":3}
     *
     * 点击后立刻读屏会拿到旧界面 → AI 以为没生效 → 重复点。
     * 稳定检测把「点完等它安静下来」变成一次调用，替代硬 sleep 或猜文本。
     * 返回里带 pkg（前台包名），便于确认「还在不在目标界面」。
     */
    private fun idle(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            val timeout = obj.optLong("timeoutMs", 3_000L).coerceIn(200L, 20_000L)
            val quietMs = obj.optLong("quietMs", 120L).coerceIn(50L, 1_000L)
            val stableReads = obj.optInt("stableReads", 3).coerceIn(2, 10)
            val r = withScreenTimeout("waitForIdle", timeout + 2_000L) {
                svc.waitForIdle(timeout, quietMs, stableReads)
            } ?: return 504 to """{"ok":false,"error":"idle wait timed out (node tree stalled)"}"""
            val stable = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
            val pkg = svc.foregroundPackage() ?: ""
            200 to """{"ok":true,"stable":$stable,"pkg":${JSONObject.quote(pkg)}}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /batch → 一次执行一串动作（v1.2.54）。**治「慢」的关键。**
     * body: {"steps":[{"type":"tap","text":"WLAN"},{"type":"idle"}],
     *        "settleMs":2000, "stopOnError":true}
     *
     * 为什么需要：单次动作的桥接往返只有几十毫秒，慢的是**每个动作都要 AI 往返
     * 一次**（dump → LLM 思考是秒级 → 点 → dump…）。10 步流程 = 10 次 LLM 调用。
     * 批量把整串动作压到一次调用里，AI 只思考一次。
     */
    private fun batch(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
            // 悬浮窗：批量动作整体状态（每步的明细由 onAfterStep 更新动作行）
            announceAction(
                R.string.a11y_action_screen,
                "batch " + runCatching { JSONObject(body).optJSONArray("steps")?.length() ?: 0 }.getOrDefault(0) + " steps",
            )
        return try {
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("steps")
                ?: return 400 to """{"ok":false,"error":"missing steps array"}"""
            if (arr.length() == 0) return 400 to """{"ok":false,"error":"steps is empty"}"""
            if (arr.length() > 50) return 400 to """{"ok":false,"error":"too many steps (max 50)"}"""

            val steps = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.keys().asSequence().associateWith { k -> o.get(k) }
            }
            val settleMs = obj.optLong("settleMs", 2_000L).coerceIn(200L, 10_000L)
            val stopOnError = obj.optBoolean("stopOnError", true)

            // 整串动作可能跑很久（每步都等稳定），给足超时
            val budget = (steps.size * (settleMs + 1_500L)) + 5_000L
            // ⚠️ 干扰检测要用**真实开始时刻**做起点，不能用「现在 - budget」推算：
            // budget 是按步数估的上限（5 步默认 22.5s），拿它反推会把 batch 开始之前
            // 那段时间里用户的一次真触摸也算成「执行期间被接管」→ 误报 userInterference，
            // AI 据此判定结果不可信而重试，在提交类步骤上就是重复执行的风险。
            val batchT0 = System.currentTimeMillis()
            // 每步自动截图（v1.2.70）：步骤序号 -> 截图路径，随结果一起回给 AI
            val shots = java.util.concurrent.ConcurrentHashMap<Int, String>()
            val r = withScreenTimeout("runBatch", budget) {
                svc.runBatch(steps, settleMs, stopOnError, { i ->
                    captureStepShot()?.let { shots[i] = it }
                    // 动作行跟着批量进度走，用户能实时看到「跑到第几步、在做什么」
                    runCatching {
                        val st = steps.getOrNull(i)
                        val t = (st?.get("type") as? String).orEmpty()
                        val label = (st?.get("text") ?: st?.get("desc"))?.toString().orEmpty()
                        StatusOverlay.setAction("[$i/${steps.size}] $t${if (label.isBlank()) "" else " \"" + label.take(20) + "\""}")
                    }
                }, obj.optBoolean("abortOnInterference", false))
            } ?: return 504 to """{"ok":false,"error":"batch timed out (node tree stalled)"}"""

            val results = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
            val stepsJson = org.json.JSONArray()
            results.forEach { s ->
                stepsJson.put(JSONObject().apply {
                    put("i", s.index)
                    put("type", s.action)
                    put("ok", s.ok)
                    if (s.detail.isNotEmpty()) put("detail", s.detail)
                    if (s.retries > 0) put("retries", s.retries)
                    s.asserted?.let { put("asserted", it) }
                    shots[s.index]?.let { put("shot", it) }
                })
            }
            val allOk = results.isNotEmpty() && results.all { it.ok }
            // 附上外部干预标记：若执行期间用户碰过屏幕，AI 需要知道结果可能不可信
            val snap = svc.interferenceSnapshot()
            val interfered = svc.interferedSince(
                batchT0, svc.lastSelfAction(),
            )
            JSONObject().apply {
                put("ok", allOk)
                put("executed", results.size)
                put("total", steps.size)
                put("steps", stepsJson)
                if (interfered) put("userInterference", true)
                put("pkg", snap.pkg ?: "")
                // v1.2.128 动态引导（真机反馈「AI 还是在自己操作无障碍功能」）：
                // 多步 batch = AI 自己在当 picker（无本地判别/验证/重试）。静态
                // 提示词容易被忽略，在**响应里**给一次明确引导；仅 ≥3 步时出现，
                // 单步/双步不打扰。
                if (steps.size >= 3) {
                    put(
                        "tip",
                        "This multi-step flow was executed by you, not by the local loop — " +
                            "no picking, no target-state verification, no retry was applied " +
                            "(ok=true only means dispatch). Next time express it as a `loop run` " +
                            "plan: the local executor picks anchors (exact/semantic), verifies " +
                            "each step, retries failures, and needs no round trip per action.",
                    )
                }
            }.toString().let { 200 to it }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * GET /interference → 外部干预快照（v1.2.54，v1.2.56 修正判定依据）。
     * query: since=<ms> 时返回「自该时刻起是否有人碰过屏幕」。
     *
     * 用户反馈：「人操作突然接管，AI 也不知道」。AI 可在关键步骤前记下时间戳，
     * 执行后再查一次，据此判断流程是否被打断（结果是否可信）。
     *
     * ⚠️ v1.2.56 修复假阳性：**只有触摸能作为「人接管」的判据**。
     * 应用自己启动/弹联想下拉/切页都会触发 window change，旧实现把它也算作干预，
     * 导致实测「启动 Edge、弹下拉时 interfered:true 但 touchCount:0」（误判）。
     * `lastWindowChangeAt` 仍返回但仅供诊断，不参与 interfered 判定。
     */
    private fun interference(query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val params = parseQuery(query)
            val snap = svc.interferenceSnapshot()
            val since = params["since"]?.toLongOrNull()
            JSONObject().apply {
                put("ok", true)
                put("lastTouchAt", snap.lastTouchAt)
                put("touchCount", snap.touchCount)
                put("lastWindowChangeAt", snap.lastWindowChangeAt)   // 仅诊断，不参与判定
                put("lastSelfActionAt", svc.lastSelfAction())
                put("pendingSelfTouches", svc.pendingSelfTouches())
                put("pkg", snap.pkg ?: "")
                if (since != null) {
                    // 只看触摸；AI 自身手势按计数核销（时间戳会漏掉晚到的事件）
                    put("interfered", svc.interferedSince(since, svc.lastSelfAction()))
                }
            }.toString().let { 200 to it }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /overlay → 控制状态悬浮窗（v1.2.57）。
     * body: {"status":"<short status>","action":"tap-text \"<label>\"","show":true,"hide":false}
     *
     * Lets the agent report what it is doing right now. The overlay itself is shown
     * automatically when the accessibility service connects;
     * 本端点用于更新内容；`hide:true` 尊重用户不想看的选择。
     */
    private fun overlay(body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            if (obj.optBoolean("hide", false)) {
                StatusOverlay.hide(DshAccessibilityService.instance)
                return 200 to """{"ok":true,"visible":false}"""
            }
            obj.optString("status").takeIf { it.isNotBlank() }?.let { StatusOverlay.setStatus(it) }
            obj.optString("action").takeIf { it.isNotBlank() }?.let { StatusOverlay.setAction(it) }
            if (obj.optBoolean("complete", false)) StatusOverlay.flashCompleteDefault()
            200 to """{"ok":true,"available":${StatusOverlay.isAvailable()}}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }


    /**
     * POST /launch → 启动任意 App（v1.2.59）。
     * body: {"package":"com.example.app"} or {"label":"<displayed name>"}
     *
     * 用户实测确认：normal 模式下 `am start` / `monkey` 被系统拒
     * （SecurityException / cannot find libbinder_ndk.so），
     * 但 App 自身的 PackageManager + startActivity 不需要任何特权。
     * This is the only clean way to launch an arbitrary app by name.
     */
    private fun launch(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            // v1.2.131 修：接受 pkg 别名——gates 的 `scr macro open-settings` 一直传
            // {"pkg":...}，端点只认 "package"，于是该 macro 长期 100% 失败
            // （AI 体验反馈实测抓到：报 "need 'package' or 'label' field"）。
            val pkg = obj.optString("package").ifBlank { obj.optString("pkg") }.orEmpty()
            val label = obj.optString("label").orEmpty()
            val pm = ctx.packageManager

            // 按包名直接启动
            if (pkg.isNotBlank()) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    return 200 to """{"ok":true,"launched":"$pkg"}"""
                }
                return 404 to """{"ok":false,"error":"no launchable activity for package '$pkg'"}"""
            }

            // 按应用名模糊匹配（遍历已安装的带 launcher 入口的包）
            if (label.isNotBlank()) {
                val q = label.lowercase()
                val matches = pm.getInstalledApplications(0).filter { appInfo ->
                    pm.getApplicationLabel(appInfo).toString().lowercase().contains(q)
                }
                if (matches.isEmpty()) {
                    return 404 to """{"ok":false,"error":"no app matching label '$label'"}"""
                }
                if (matches.size > 1) {
                    val names = matches.map { pm.getApplicationLabel(it).toString() }
                    return 300 to """{"ok":false,"error":"multiple matches","apps":${names.toString()}}"""
                }
                val intent = pm.getLaunchIntentForPackage(matches[0].packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    return 200 to """{"ok":true,"launched":"${matches[0].packageName}"}"""
                }
            }
            400 to """{"ok":false,"error":"need 'package' or 'label' field"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * POST /launch-intent —— 按 Intent action 直达系统页面（v1.2.137）；
     * v1.6.x 起扩展成「直接调起别的 App 的接口」。原用途（android.settings.* 之类
     * 确定性导航）仍完全兼容，供 scr macro 的设置直达与 loop 的 intent 动作共用。
     *
     *
     * 这是**唯一不需要任何特权**就能操作别的 App 的方式：Deep Link 与显式组件
     * 都走这里，App 进程自己就能发。
     *
     * 字段：
     * · `action`    如 `android.intent.action.VIEW`
     * · `data`      Deep Link，如 `weixin://dl/chat?x=1`
     * · `type`      MIME，如 `image/<星号>`（注意：Kotlin 块注释可以嵌套，
     *   正文里直接写斜杠加星号会提前闭合注释，所以这里不写字面量）
     * · `pkg`      目标包名（配 `cls` 就是显式组件启动）
     * · `cls`      目标 Activity 全名
     * · `extras`    对象，按 JSON 值类型自动选 putExtra 重载
     * · `categories` 字符串数组
     * · `flags`     整数，**额外**叠加在 FLAG_ACTIVITY_NEW_TASK 之上
     * · `dry_run`   true 时只回报将要用的 intent，不真的启动
     *
     * 为什么必须支持 `extras`：绝大多数 App 的深链参数都在 extras 里
     * （`android.intent.extra.TEXT`、`query` 等），只给 action+data 的话
     * 大部分跳转只能落到首页，等于没用。
     */
    private fun launchIntent(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val act = obj.optString("action")
        val pkg = obj.optString("pkg")
        val cls = obj.optString("cls")
        // 显式组件启动可以不给 action；其余必须给 action，否则 Intent 无法解析
        if (act.isBlank() && (pkg.isBlank() || cls.isBlank())) {
            return 400 to """{"ok":false,"error":"action required (or give both pkg+cls)"}"""
        }
        return runCatching {
            val intent = android.content.Intent()
            if (act.isNotBlank()) intent.action = act
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg.isNotBlank() && cls.isNotBlank()) {
                intent.setClassName(pkg, cls)
            } else if (pkg.isNotBlank()) {
                intent.setPackage(pkg)
            }
            obj.optString("data").takeIf { it.isNotBlank() }?.let {
                intent.data = android.net.Uri.parse(it)
            }
            obj.optString("type").takeIf { it.isNotBlank() }?.let { intent.type = it }
            obj.optInt("flags", 0).takeIf { it != 0 }?.let { intent.addFlags(it) }
            obj.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { intent.addCategory(it) }
                }
            }
            obj.optJSONObject("extras")?.let { extras ->
                extras.keys().forEach { k ->
                    when (val v = extras.get(k)) {
                        is String -> intent.putExtra(k, v)
                        is Int -> intent.putExtra(k, v)
                        is Long -> intent.putExtra(k, v)
                        is Double -> intent.putExtra(k, v)
                        is Boolean -> intent.putExtra(k, v)
                        is org.json.JSONArray -> {
                            val list = ArrayList<String>()
                            for (i in 0 until v.length()) list.add(v.optString(i))
                            intent.putExtra(k, list.toTypedArray())
                        }
                        // 对象/数组套对象没有通用映射，宁可报错也不要静默丢参数 ——
                        // 静默丢会让调用方以为「传了没生效」，排查成本远高于直接失败。
                        else -> throw IllegalArgumentException("unsupported extra type for '$k'")
                    }
                }
            }
            if (obj.optBoolean("dry_run", false)) {
                return@runCatching 200 to JSONObject()
                    .put("ok", true)
                    .put("dry_run", true)
                    .put("intent", intent.toString())
                    .put("extras_count", obj.optJSONObject("extras")?.length() ?: 0)
                    .toString()
            }
            ctx.startActivity(intent)
            200 to JSONObject()
                .put("ok", true)
                .put("launched", intent.toString())
                .put("extras_count", obj.optJSONObject("extras")?.length() ?: 0)
                .toString()
        }.getOrElse { 500 to """{"ok":false,"error":"${it.message}"}""" }
    }

    /**
     * POST /dev/node —— 节点语义动作（v1.6.x）。
     *
     * 与 /tap 系列的区别：这里的动作**由节点自己执行**，不算坐标，因此
     * 不受悬浮层遮挡、触摸抖动、命中偏移影响；长按/展开/关闭也比手势可靠。
     *
     * op 一览：
     * · `long_click` {text, by_desc?}   真长按（ACTION_LONG_CLICK，失败回退长按手势）
     * · `expand` / `collapse` {text}     展开/折叠（折叠屏分区、可折叠列表、树形控件）
     * · `dismiss` {text}                 关闭（通知条、对话框、Snackbar）
     * · `read` {text}                    **反向读文本**：选中→COPY→读剪贴板。
     *   用于 WebView / 自绘输入框（它们的 `getText()` 常常是空串）。
     *   密码框会被系统拦住，拿不到明文 —— 这是预期行为。
     * · `scroll` {direction, times?}     方向滚动 up|down|left|right，精确滚一步
     * · `click` {text, by_desc?}        点一下（节点自身 ACTION_CLICK，不吃坐标）
     * · `input` {value, text?, append?} 往输入框写文字（节点 ACTION_SET_TEXT）
     * · `back`                          返回（全局动作，不需要定位文本）
     */
    private fun nodeAct(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        if (!CapabilityGate.can(ctx, CapabilityGate.CONTROL)) {
            return 403 to """{"ok":false,"error":"capability disabled","capability":"control"}"""
        }
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val op = obj.optString("op").lowercase()
        val text = obj.optString("text")
        val byDesc = obj.optBoolean("by_desc", false)
        // clear_selection 不需要 text（它作用于整个窗口的选中状态）
        // 这几个 op 不需要定位文本：scroll 作用于当前可滚动容器、back 是全局动作、
        // input 不指定 text 时写当前焦点输入框、clear_selection 作用于窗口。
        if (op !in setOf("scroll", "clear_selection", "back", "input") && text.isBlank()) {
            return 400 to """{"ok":false,"error":"text required"}"""
        }
        val result = runCatching {
            when (op) {
                "long_click" -> JSONObject()
                    .put("ok", svc.longClickText(text, byDesc))
                    .put("via", "action+gesture-fallback")
                "expand" -> JSONObject().put("ok", svc.expandText(text, true, byDesc))
                "collapse" -> JSONObject().put("ok", svc.expandText(text, false, byDesc))
                // dismiss 的真实性判定（v1.7.6 真机）：ACTION_DISMISS 返回 false 时
                // 菜单**其实已经关掉了**（实测「复制 Session ID」就是这种），只回
                // ok=false 会让调用方重试 → 误触别的元素。改成「关掉了就算成功」：
                // 动作前节点在、动作后节点不在 = 生效，并标明是靠消失判定的。
                "dismiss" -> {
                    val before = svc.nodeExists(text, byDesc)
                    val done = svc.dismissText(text, byDesc)
                    // ★v1.7.8：判定「消失了没有」必须给动画留时间。弹窗/菜单
                    //   的退出动画实测 100~300ms，紧接着读树必然还看得到它 ——
                    //   旧实现的 before/after 紧挨着取，于是恒判 no-effect，
                    //   而 ACTION_DISMISS 本身在不少 ROM 上就返回 false（点了但
                    //   没回报）。两个误报叠在一起：动作其实成功了，AI 却以为失败。
                    //   轮询到消失为止（最多 600ms），命中即返回，正常路径不付
                    //   满额等待。
                    var after = svc.nodeExists(text, byDesc)
                    val deadline = System.currentTimeMillis() + 600L
                    while (after && System.currentTimeMillis() < deadline) {
                        runCatching { Thread.sleep(120L) }
                        after = svc.nodeExists(text, byDesc)
                    }
                    val gone = before && !after
                    JSONObject()
                        .put("ok", done || gone)
                        .put("via", when {
                            done -> "action"
                            gone -> "disappeared"
                            else -> "no-effect"
                        })
                        .put(
                            "error",
                            if (!done && !gone) {
                                "dismiss had no effect: the node is still on screen (no DISMISS support)"
                            } else JSONObject.NULL,
                        )
                }
                "read" -> {
                    val got = svc.readTextViaClipboard(text, byDesc)
                    JSONObject().put("ok", got != null)
                        .put("text", got ?: org.json.JSONObject.NULL)
                        .put("length", got?.length ?: 0)
                        // 密码框被系统拦截时 got==null，把可能原因写进 hint，
                        // 否则调用方很容易误判成「没找到节点」。
                        .put(
                            "hint",
                            if (got == null) "node not found, or COPY not supported / blocked (password field)"
                            else "read via clipboard; original clipboard restored",
                        )
                }
                // scroll 的真实性判定（v1.7.6 真机）：此前无条件 put("ok", true)，
                // 于是「没找到可滚动节点」也回 ok=true + scrolled:0，调用方只能靠
                // scrolled 猜，猜错就会连着做后续动作（真机实测随后界面跳到了设置页）。
                "scroll" -> {
                    val dir = obj.optString("direction", "down")
                    val times = obj.optInt("times", 1)
                    // 边界说明：`scrolled=n>0` 只代表「系统接受了 n 次滚动动作」，
                    // 不代表画面真的动了 —— 到达列表尽头时部分 ROM 仍返回 true。
                    // 因此「到没到头」不能只看这个字段，需要时用 /frame-diff 或
                    // 重新 dump 比文本（ScreenVerdict.SCROLL_END 那条路径）。
                    val n = svc.scrollDirection(dir, times)
                    JSONObject().put("ok", n > 0)
                        .put("direction", dir)
                        .put("scrolled", n)
                        .put(
                            "error",
                            if (n == 0) {
                                "no scrollable container accepted the action; nothing moved"
                            } else JSONObject.NULL,
                        )
                }
                // WebView 结构遍历：文本树里看不出「这是什么元素」，只有这对 action 有
                "html_next", "html_prev" -> {
                    val el = obj.optString("element", "LINK")
                    JSONObject().put("ok", svc.hopHtmlElement(text, op == "html_next", el, byDesc))
                        .put("element", el.uppercase())
                }
                "select", "clear_selection" -> JSONObject()
                    .put("ok", svc.selectNode(text, op == "select", byDesc))
                // 补齐三个最常用原语（v1.7.6）：此前节点动作里只有长按，没有
                // 「点一下」，想点按钮只能长按（弹菜单，语义完全错位）；输入与返回
                // 也只能绕去 /input、/key。这里让它们都走节点自身，不吃坐标。
                "click" -> {
                    // ★v1.7.8 修正：此前这里调 tapText/tapDesc，它们内部是
                    //   tapNode → dispatchTap —— 发的是**注入坐标手势**（还带抖动与
                    //   落点标记），却回 `via:"node-action"`，注释也写「节点自身
                    //   ACTION_CLICK，不吃坐标」。两头都是假的：覆盖层遮挡、落点抖动
                    //   这两个真问题一个都没解决，调用方还以为走的是语义动作。
                    //   真正的节点 action 是 tapNodeDeep：ACTION_CLICK → 可点祖先 →
                    //   坐标兜底，并把实际用了哪一级如实返回（action/ancestor/tap）。
                    val via = svc.tapTextDeep(text, byDesc)
                    if (via == null) {
                        JSONObject().put("ok", false).put("via", "failed")
                            .put("error", "no node matched, or all three strategies failed: " + text)
                    } else {
                        JSONObject().put("ok", true).put("via", via)
                    }
                }
                "input" -> {
                    val value = obj.optString("value")
                    val ok = svc.inputText(
                        value,
                        obj.optBoolean("append", false),
                        text.takeIf { it.isNotBlank() },
                    )
                    JSONObject().put("ok", ok)
                        .put("length", value.length)
                        .put("via", "set-text")
                }
                "back" -> JSONObject()
                    .put("ok", svc.performGlobalActionByName("back"))
                    .put("via", "global-action")
                else -> return 400 to
                    """{"ok":false,"error":"op must be click|input|back|long_click|expand|collapse|dismiss|read|scroll|html_next|html_prev|select|clear_selection"}"""
            }
        }.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
        CapabilityGate.audit(ctx, "dev.node:$op")
        return 200 to result.toString()
    }

    /** POST /tap-id —— 按 resource-id 点击（v1.2.141）。比文本定位稳：不受界面语言、OCR 误差影响 */
    private fun tapId(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val id = runCatching { JSONObject(body).optString("id") }.getOrNull().orEmpty()
        if (id.isBlank()) return 400 to """{"ok":false,"error":"id required"}"""
        val hit = runCatching { svc.tapById(id) }.getOrNull()
            ?: return 404 to """{"ok":false,"error":"no node with id '$id'"}"""
        return 200 to """{"ok":true,"id":"$id","x":${hit.first},"y":${hit.second},"via":"${hit.third}"}"""
    }

    /**
     * POST /tap-ref —— 按编号点击（v1.6.19）。
     *
     * body: {"screen":"s7","id":3}
     *
     * 与 /tap、/tap-id 的区别：这两个是「你说点哪就点哪」，本端点是
     * **「你说的那个还在不在」先问一遍再点**。执行顺序：
     *
     *   1. 取最新文档；没有 → 503，让调用方先 dump
     *   2. 屏幕号对不上 → 409，回最新的编号，别拿旧编号蒙
     *   3. 编号不存在 → 404
     *   4. 重新读树，比对指纹：变了 → 409，说明「[3]『发送』变了」，不点
     *   5. 保护页 → 403，读可以，点不行
     *   6. 三级降级点击（ACTION_CLICK → 最近可点祖先 → 坐标注入）
     *   7. 等界面稳定后重建文档
     *   8. 回新屏幕 + 诚实判定（变了/没变/到头了/无法验证）
     *
     * 第 4 步是全部价值所在：它把「点错」从一个随机故障变成一条明确的拒绝。
     */
    /**
     * POST /handoff —— 把手机交给用户。
     *
     * body: {reason?: string, timeoutMs?: number, wait?: boolean}
     *
     * wait 默认 false：先告知模型「请让用户操作」，它把这句转达给用户、
     * 然后用 GET /handoff 查结果。这样两个请求各自有限，不会因为一个长轮询
     * 被客户端超时砍断。wait=true 时就地阻塞等（上限 30s）。
     */
    private fun handoffBegin(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val reason = o?.optString("reason")?.trim().orEmpty()
        val timeoutMs = o?.optLong("timeoutMs", Handoff.DEFAULT_TIMEOUT_MS) ?: Handoff.DEFAULT_TIMEOUT_MS
        val wait = o?.optBoolean("wait", false) ?: false
        val svc = DshAccessibilityService.instance
        val pkg = try {
            svc?.foregroundPackage().orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val session = Handoff.begin(reason, pkg, timeoutMs)
        if (wait) {
            // 就地等一段，不超过 30s：客户端超时一般在 30s 上下。
            val st = Handoff.await(30_000L)
            return 200 to JSONObject()
                .put("ok", true)
                .put("state", st.name.lowercase())
                .put("message", Handoff.describe(st))
                .put("package", session.packageName)
                .toString()
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("state", "waiting")
            .put("message", Handoff.BEGIN_MESSAGE)
            .put("reason", reason)
            .put("package", session.packageName)
            .put("timeoutMs", timeoutMs)
            .toString()
    }

    /**
     * GET /handoff —— 查交接是否完成。
     *
     * query: waitMs=最多就地等多久（上限 30s）。不给就立即返回。
     */
    private fun handoffStatus(query: String): Pair<Int, String> {
        val params = parseQuery(query)
        val waitMs = (params["waitMs"]?.toLongOrNull() ?: 0L).coerceIn(0L, 30_000L)
        val st = if (waitMs > 0) Handoff.await(waitMs) else Handoff.settle()
        val s = Handoff.current()
        return 200 to JSONObject()
            .put("ok", true)
            .put("state", st.name.lowercase())
            .put("message", Handoff.describe(st))
            .put("package", s?.packageName ?: "")
            .put("reason", s?.reason ?: "")
            .toString()
    }

    // ---------- 用户随时叫停（v1.6.22）----------

    /** 一个进程内共享的任务状态机。 */

    // v1.6.34：改用进程内单例 —— 闭环执行器要在步与步之间自查用户是否叫停，
    // 两边各建一个实例就永远看不到对方的刹车。
    private fun task(): PhoneTask = PhoneTask.shared()

    /**
     * v1.7.0 行动边界：fast_mode 是不是**我们**开的。
     *
     * 只有我们开的才由我们关。模型自己调 `fast_mode on`、或用户手动开着的，
     * 收尾时不能替他关掉 —— 那会把用户明确表达过的意图抹掉。
     */
    @Volatile private var fastOwned = false

    /** v1.7.0：无障碍服务是不是我们为了这次行动打开的（只有我们开的才由我们关）。 */
    @Volatile private var a11yOwned = false

    /**
     * 行动开始：确保无障碍服务在（v1.7.0）。
     *
     * 用户要的边界是「动手时无障碍在，收工就撤」。撤掉之后下一次动手必须能自己
     * 恢复，否则第一次动作会撞上 503 accessibility service not enabled。
     *
     * 没有 WRITE_SECURE_SETTINGS 时什么都不做 —— 那是用户手动开的服务，不归我们管。
     */
    private fun ensureA11y() {
        if (DshAccessibilityService.instance != null) return
        val c = shotCtx ?: return
        if (!A11ySwitch.granted(c)) return
        if (!A11ySwitch.listed(c)) {
            if (!A11ySwitch.add(c)) return
            a11yOwned = true
        }
        // 系统绑定要时间。等最多 2.5 秒；等不到也放行 —— 动作自己会回 503，
        // 比在这里无限挂住请求线程好。
        val deadline = System.currentTimeMillis() + 2_500L
        while (System.currentTimeMillis() < deadline) {
            if (DshAccessibilityService.instance != null) return
            runCatching { Thread.sleep(50L) }
        }
    }

    /** 收行动边界：撤掉无障碍服务（只撤我们开的）并把本应用带回前台。 */
    private fun closeA11y() {
        if (!a11yOwned) return
        a11yOwned = false
        val c = shotCtx ?: return
        A11ySwitch.remove(c)
    }

    /** 主线程定时器：延迟收工用（v1.7.4）。 */
    private val uiHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 待执行的「撤无障碍服务」（v1.7.4，可取消） */
    @Volatile private var a11yCloseTask: Runnable? = null

    /**
     * 延迟撤掉无障碍服务（v1.7.4）。
     *
     * 为什么不能像以前那样在 [endBoundary] 里立刻撤：悬浮窗是
     * `TYPE_ACCESSIBILITY_OVERLAY` 窗口，服务一撤，窗口 token 立刻失效，窗口跟着消失 ——
     * 用户要求「结束后约 1 分钟消失」，所以撤服务必须排在悬浮窗自动收起之后（+1s 余量，
     * 让 hide 先跑完）。
     */
    private fun scheduleA11yClose() {
        a11yCloseTask?.let { runCatching { uiHandler.removeCallbacks(it) } }
        val r = Runnable { a11yCloseTask = null; closeA11y() }
        a11yCloseTask = r
        uiHandler.postDelayed(r, StatusOverlay.AUTO_HIDE_MS + 1_000L)
    }

    /** AI 又开始动了 → 取消待执行的收工（否则它会在 60s 后把正在用的服务撤掉）。 */
    private fun cancelA11yClose() {
        a11yCloseTask?.let { runCatching { uiHandler.removeCallbacks(it) } }
        a11yCloseTask = null
    }

    /**
     * 收行动边界：关掉**我们开的** fast_mode。
     *
     * 幂等：没开过就什么也不做。不在这里动悬浮窗状态 —— 谁结束的谁负责画，
     * 这里只管「思考恢复」这一件事。
     */
    private fun endBoundary() {
        if (fastOwned) {
            fastOwned = false
            FastMode.off(dshHome())
        }
        // v1.7.4：任务收工 → 悬浮窗从**这一刻**再留 60s（用户要求「结束后约 1 分钟消失」）。
        // 撤无障碍服务也一起延后：窗口 token 绑在服务上，先撤服务等于立刻关窗。
        StatusOverlay.scheduleAutoHide()
        scheduleA11yClose()
        // v1.8.0 记忆录制（M1）：行动边界收工 = 轨迹封存 + 过期清理。
        runCatching { MemoryRoutes.onBoundaryEnd(appCtxForBridge()) }
    }

    @Volatile private var phoneAsk: PhoneAsk? = null

    /**
     * 主界面是不是真的在前台（用于判「用户看见了吗」）。
     *
     * ⚠️ 不能用 ActivityManager.appTasks：它只列出**本进程自己的**任务，
     * 只要我们还活着就永远能找到自己，实测在设置页上直接误判成「已看见」。
     * 靠无障碍服务取真正的前台包名。
     */
    private fun isSessionVisible(): Boolean = runCatching {
        val svc = DshAccessibilityService.instance ?: return@runCatching false
        val fg = svc.foregroundPackage() ?: return@runCatching false
        fg == "app.dsh.mobile"
    }.getOrDefault(false)

    /** 请求把 App 带回前台（返回请求是否被接受，不代表用户看见）。 */
    private fun bringAppToFront(): Boolean = runCatching {
        val ctx = appCtxForBridge()
        val i = ctx.packageManager.getLaunchIntentForPackage("app.dsh.mobile") ?: return@runCatching false
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

    private fun appCtxForBridge(): android.content.Context =
        shotCtx ?: throw IllegalStateException("bridge has no context")

    private fun ask(): PhoneAsk = phoneAsk ?: synchronized(this) {
        phoneAsk ?: PhoneAsk().also { phoneAsk = it }
    }

    /**
     * POST /ask —— 向用户提问。
     *
     * 模型卡在需要用户决定的地方时用它：两个同名店选哪个、
     * 要不要提交订单。body: {text, options, freeText, timeoutMs, wait}
     */
    private fun askBegin(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "bad json").toString()
        val text = o.optString("text", "").trim()
        if (text.isEmpty()) {
            return 400 to JSONObject().put("ok", false).put("error", "text is required").toString()
        }
        val opts = ArrayList<String>()
        o.optJSONArray("options")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotEmpty()) opts.add(v)
            }
        }
        val q = ask().ask(
            text = text,
            options = opts,
            allowFreeText = o.optBoolean("freeText", true),
            timeoutMs = (o.optLong("timeoutMs", 0L)).takeIf { it > 0 } ?: PhoneAsk.DEFAULT_TIMEOUT_MS,
        ) ?: return 409 to JSONObject()
            .put("ok", false)
            .put("error", "a question is already pending")
            .put("pending", ask().pending()?.text ?: "")
            .toString()
        val res = JSONObject()
            .put("ok", true)
            .put("id", q.id)
            .put("asked", true)
            .put("message", PhoneAsk.ASK_BEGIN_MESSAGE)
        return 200 to res.toString()
    }

    /** GET /ask —— 当前待回答的问题（悬浮窗与模型都读它）。 */
    private fun askStatus(): Pair<Int, String> {
        val q = ask().pending()
        if (q == null) {
            return 200 to JSONObject().put("ok", true).put("pending", false).toString()
        }
        val res = JSONObject()
            .put("ok", true)
            .put("pending", true)
            .put("id", q.id)
            .put("text", q.text)
            .put("freeText", q.allowFreeText)
            .put("askedAt", q.askedAt)
            .put("deadlineAt", q.deadlineAt)
        if (q.options.isNotEmpty()) {
            val arr = JSONArray()
            q.options.forEach { arr.put(it) }
            res.put("options", arr)
        }
        return 200 to res.toString()
    }

    /**
     * POST /task/allow —— 用户切换「允许 AI 操作」（悬浮窗开关与模型都读它）。
     *
     * body: {allowed: true|false}
     */
    private fun taskAllow(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "bad json").toString()
        if (!o.has("allowed")) {
            return 400 to JSONObject().put("ok", false).put("error", "allowed is required").toString()
        }
        val t = task()
        t.setAllowed(o.optBoolean("allowed", true))
        val on = t.isAllowed()
        return 200 to JSONObject()
            .put("ok", true)
            .put("allowed", on)
            .put("message", if (on) "" else PhoneTask.NOT_ALLOWED_MESSAGE)
            .toString()
    }

    /** GET /task/allow —— 当前是否允许操作。 */
    private fun taskAllowStatus(): Pair<Int, String> =
        200 to JSONObject().put("ok", true).put("allowed", task().isAllowed()).toString()

    /**
     * POST /task/point —— 告诉悬浮窗「刚刚点在哪了」。
     *
     * 画询不断言点中了什么（点没点中由动作自己判），
     * 只负责让用户看见——“它到底点了哪里”。
     * body: {x, y, label}.
     */
    private fun taskPoint(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "bad json").toString()
        val x = o.optDouble("x", Double.NaN)
        val y = o.optDouble("y", Double.NaN)
        if (x.isNaN() || y.isNaN()) {
            return 400 to JSONObject().put("ok", false).put("error", "x and y are required").toString()
        }
        StatusOverlay.showPoint(x.toFloat(), y.toFloat(), o.optString("label", ""))
        return 200 to JSONObject().put("ok", true).toString()
    }

    /**
     * POST /task/report —— 一件事做完了，告诉用户。
     *
     * body: {summary, steps, stopped}
     *
     * 画询不只是发一条通知：先把用户带回会话，回不来才通知。
     * 理由见 [TaskReport]。
     */
    private fun taskReport(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "bad json").toString()
        val snapshot = task().snapshot()
        val outcome = deliverReport(
            summary = o.optString("summary", ""),
            steps = o.optInt("steps", task().log.size()),
            stopped = o.optBoolean("stopped", snapshot.state == PhoneTask.State.STOPPED),
        )
        return 200 to JSONObject()
            .put("ok", true)
            .put("shown", outcome.shown.name)
            .put("opened", outcome.opened)
            .put("launched", outcome.launched)
            .put("notified", outcome.notified)
            .toString()
    }

    /**
     * 投递一份收尾报告（/task/report 与 /task/finish 共用）。
     */
    private fun deliverReport(summary: String, steps: Int, stopped: Boolean): TaskReport.Outcome {
        val owner = task().owner()
        val rep = TaskReport()
        val payload = rep.body(summary = summary, steps = steps, stopped = stopped)
        return rep.deliver(
            body = payload,
            current = { task().isCurrent(owner) },
            visible = { isSessionVisible() },
            launch = { bringAppToFront() },
            notify = { TaskReportNotifier.post(appCtxForBridge(), payload) },
        )
    }

    /**
     * POST /task/finish —— AI 主动收尾（v1.6.35）。
     *
     * body: {summary, steps?}
     *
     * 关掉「允许 AI 操作」之后，模型仍然需要能体面地结束：写一句摘要、把状态区放回绿色。
     * 这条路径**不受允许开关限制**（它不是动作，不碰屏幕），也**不会**替用户把开关打开 ——
     * 开关是用户的，AI 只负责收尾。
     */
    private fun taskFinish(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "bad json").toString()
        val t = task()
        val before = t.snapshot()
        val summary = o.optString("summary", "").trim()
        val steps = o.optInt("steps", t.log.size())
        val stopped = o.optBoolean("stopped", before.state == PhoneTask.State.STOPPED)
        t.finish()
        val outcome = runCatching { deliverReport(summary, steps, stopped) }.getOrNull()
        // v1.7.0 行动边界，顺序是用户定的：先关无障碍、再回前台、最后才提示。
        // 反过来的话，用户会先看到「完成」再去猜屏幕为什么还能被读。
        endBoundary()
        bringAppToFront()
        StatusOverlay.noteFinished()
        return 200 to JSONObject()
            .put("ok", true)
            .put("state", t.snapshot().state.name)
            .put("shown", outcome?.shown?.name ?: "SKIPPED")
            .put("notified", outcome?.notified ?: false)
            .put("allowed", t.isAllowed())
            .toString()
    }

    /**
     * POST /ask/result —— 取用户的回答。
     *
     * 画询为 POST 不是 GET：取走正是一次有副作用的操作，而答案只能消费一次。
     */
    private fun askResult(): Pair<Int, String> {
        val r = ask().take()
        val o = JSONObject()
            .put("ok", true)
            .put("state", r.state.name)
            .put("pending", r.state == PhoneAsk.State.ASKING)
        if (r.answer != null) {
            o.put("answer", r.answer)
            o.put("fromOption", r.fromOption)
        }
        return 200 to o.toString()
    }

    /** POST /ask/cancel —— 用户选择「不答」。 */
    /** POST /ask/cancel —— 用户选择「不答」。 */
    private fun askCancel(): Pair<Int, String> {
        ask().cancel()
        return 200 to JSONObject().put("ok", true).toString()
    }

    /** POST /ask/answer —— 用户回答（悬浮窗按钮或程序调用）。 */
    private fun askAnswer(body: String): Pair<Int, String> {
        val o = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "bad json").toString()
        val text = o.optString("text", "").trim()
        if (text.isEmpty()) {
            return 400 to JSONObject().put("ok", false).put("error", "text is required").toString()
        }
        val ok = ask().answer(text, fromOption = o.optBoolean("fromOption", false))
        return if (ok) {
            200 to JSONObject().put("ok", true).toString()
        } else {
            409 to JSONObject()
                .put("ok", false)
                .put("error", "no pending question, or it already timed out")
                .toString()
        }
    }


    /** POST /task/say —— 用户在任务进行中插话（最多留 5 条）。 */
    private fun taskSay(body: String): Pair<Int, String> {
        val text = runCatching {
            JSONObject(body.ifBlank { "{}" }).optString("text", "")
        }.getOrDefault("")
        if (text.isBlank()) {
            return 400 to JSONObject().put("ok", false).put("error", "text is required").toString()
        }
        task().interject(text)
        return 200 to JSONObject().put("ok", true).toString()
    }

    /** POST /task/stop —— 用户叫停。硬窗口内所有动作都被拒。 */
    private fun taskStop(body: String): Pair<Int, String> {
        val reason = runCatching {
            JSONObject(body.ifBlank { "{}" }).optString("reason", "")
        }.getOrDefault("")
        val t = task()
        t.stop(reason.ifBlank { PhoneTask.USER_STOP_REASON })
        // v1.7.0：用户叫停也意味着这次行动结束了，思考该恢复。
        endBoundary()
        return 200 to JSONObject()
            .put("ok", true)
            .put("state", t.snapshot().state.name)
            .put("message", t.stoppedMessage())
            .toString()
    }

    private fun taskPause(): Pair<Int, String> {
        val t = task()
        t.pause()
        return 200 to JSONObject().put("ok", true).put("state", t.snapshot().state.name).toString()
    }

    private fun taskResume(): Pair<Int, String> {
        val t = task()
        t.resume()
        // v1.6.34：resume() 只对 PAUSED 有效。若用户已经叫停，返回 ok:true 会让模型
        // 以为「已经继续了」而继续发动作、全部 409 —— 这里如实回报，并说明刹车只能由用户解。
        if (t.isStopped()) {
            return 409 to JSONObject()
                .put("ok", false)
                .put("state", t.snapshot().state.name)
                .put("stopped", true)
                .put("error", "still stopped by user")
                .put("hint", PhoneTask.RESUME_HINT)
                .toString()
        }
        return 200 to JSONObject().put("ok", true).put("state", t.snapshot().state.name).toString()
    }

    /** GET /task —— 当前任务状态（悬浮窗与模型都读它）。 */
    private fun taskStatus(): Pair<Int, String> {
        val t = task()
        val s = t.snapshot()
        // v1.7.0：任务因空闲自动结束（3 分钟没动作）时也要收边界。
        // 悬浮窗每 2 秒轮询这个端点，所以空闲结束最多 2 秒内会被看见。
        if (!s.active) endBoundary()
        val o = JSONObject()
            .put("ok", true)
            .put("active", s.active)
            .put("state", s.state.name)
            .put("steps", s.steps)
        if (s.app != null) o.put("app", s.app)
        if (s.lastStep != null) o.put("lastStep", s.lastStep)
        if (s.stopReason != null) o.put("stopReason", s.stopReason)
        val entries = task().log.recent(20)
        if (entries.isNotEmpty()) {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("time", e.time)
                        .put("tool", e.tool)
                        .put("summary", e.summary)
                        .put("decision", e.decision)
                        .also { o2 -> if (e.app != null) o2.put("app", e.app) }
                )
            }
            o.put("trail", arr)
        }
        // 插话只读不清：GET 不应有副作用，清空由动作端点做。
        val said = t.peekInterjections()
        if (said.isNotEmpty()) {
            val arr2 = JSONArray()
            said.forEach { arr2.put(it) }
            o.put("interjections", arr2)
        }
        return 200 to o.toString()
    }

    /**
     * 每个手机动作端点前都过这里。
     *
     * 返回 null 表示放行；非 null 表示被用户叫停了，直接回给模型。
     */
    private fun taskGate(path: String = ""): Pair<Int, String>? {
        val t = task()
        val owner = t.owner()
        val refusal = t.begin(frontAppLabel())
        if (refusal != null) {
            // 被拒也要记：否则用户看到的日志里，模型重试了十次却像什么也没发生。
            t.noteStep(path.removePrefix("/"), "refused", "refused")
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "stopped by user")
                .put("reason", refusal)
                .toString()
        }
        t.callStarted()
        // 动作被放行 → 悬浮窗切「执行中」蓝档。放在这里而不是任务开始处：
        // 一个任务里可能几十个动作，用户想看到的是「此刻正在动」。
        // v1.7.4：同时把窗口「叫回来」并重排 60s 自动收起 —— 空闲时悬浮窗是不显示的，
        // 这一行保证「AI 一动手就出现」。
        cancelA11yClose()
        StatusOverlay.showForWork(DshAccessibilityService.instance)
        StatusOverlay.noteActionStarted()
        // v1.7.0 行动边界：第一个动作被放行 = 行动真的开始了 → 关掉思考。
        //
        // 为什么不让模型自己调 fast_mode：实测它不会。工具在列表里、种子说明里也
        // 写了「做 UI 任务前先打开」，模型仍然每步都付一遍思考成本 —— 这类
        // 「每步都该生效」的开关不能交给模型的记性，必须由动作驱动。
        if (!fastOwned && FastMode.on(dshHome())) fastOwned = true
        // 无障碍服务也要在：上一步收工时可能已经撤掉了（见 closeA11y）。
        ensureA11y()
        // v1.8.0 记忆录制（M1）：动作被放行 = 录制开始/继续。放在最后，
        // 前面的闸门拒绝了就不该开轨迹（被拒的动作不是「经验」）。
        runCatching { MemoryRoutes.onActionBegin(appCtxForBridge()) }
        return null
    }

    /**
     * 动作执行完之后收尾：记一步、减在飞数、回报结果。
     *
     * 在飞计数是为了让长任务（handoff 可以等 10 分钟）
     * 不被 3 分钟空闲判死。
     */
    private fun taskDone(path: String, decision: String, body: String = "") {
        val t = task()
        t.callEnded()
        t.noteStep(path.removePrefix("/"), describeAction(path, body), decision)
        // v1.8.0 记忆录制（M1）：动作结束记一行事件（一次 dump 取后指纹）。
        // 只记 path 与 decision —— body 里有 input 文本，绝不能进轨迹（隐私）。
        runCatching { MemoryRoutes.onActionDone(appCtxForBridge(), path, decision, body) }
    }

    /**
     * 把一个动作变成人能看懂的一句话。
     *
     * 只取坐标与长度这类**不含内容**的字段：若把 input 的 text 或
     * launch 的包名原样记进去，这份给人看的日志就变成了敏感信息泄露渠道。
     * 输入只记「输入了 N 字符」。
     */
    private fun describeAction(path: String, body: String): String {
        val name = path.removePrefix("/")
        val o = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull() ?: return name
        val x = o.optInt("x", Int.MIN_VALUE)
        val y = o.optInt("y", Int.MIN_VALUE)
        if (x != Int.MIN_VALUE && y != Int.MIN_VALUE) return name + " " + x + "," + y
        val len = o.optString("text", "").length
        if (len > 0) return name + " " + len + " chars"
        val steps = o.optJSONArray("steps")
        if (steps != null) return name + " " + steps.length() + " steps"
        val pkg = o.optString("package", "")
        if (pkg.isNotEmpty()) return name + " " + pkg.substringAfterLast('.')
        return name
    }

    /** 当前前台应用名（拿不到就返 null，不影响主流程）。 */
    private fun frontAppLabel(): String? = runCatching {
        DshAccessibilityService.instance?.foregroundPackage()
    }.getOrNull()


    /** POST /handoff/done —— 用户点了「已完成」。 */
    private fun handoffDone(): Pair<Int, String> {
        val ok = Handoff.complete()
        val st = Handoff.settle()
        return 200 to JSONObject()
            .put("ok", ok)
            .put("state", st.name.lowercase())
            .put("message", Handoff.describe(st))
            .toString()
    }

    private fun tapRef(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to "{\"ok\":false,\"error\":\"accessibility service not enabled\"}"
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to "{\"ok\":false,\"error\":\"invalid json\"}"
        val screenId = obj.optString("screen").trim()
        val id = obj.optInt("id", -1)
        if (screenId.isEmpty()) return 400 to "{\"ok\":false,\"error\":\"screen required (e.g. \\\"s7\\\")\"}"
        if (id <= 0) return 400 to "{\"ok\":false,\"error\":\"id required (positive integer)\"}"

        val keysBefore = ScreenVerdict.keyboardVisible(svc)
        val resolved = withScreenTimeout("tapRef.resolve") { ScreenDoc.resolveForTap(svc, screenId, id) }
            ?.getOrElse { return 500 to "{\"ok\":false,\"error\":\"${it.message}\"}" }
            ?: return 504 to "{\"ok\":false,\"error\":\"accessibility node tree read timed out (service may be stuck)\"}"

        val before = ScreenDoc.latestDoc()
        when (resolved) {
            is ScreenDoc.Resolve.NoDoc -> return 503 to
                "{\"ok\":false,\"error\":\"no screen document yet; call GET /screen?format=doc first\"}"
            is ScreenDoc.Resolve.StaleId -> return 409 to JSONObject()
                .put("ok", false)
                .put("error", "screen id changed")
                .put("requested", screenId)
                .put("latest", resolved.latestId)
                .put("hint", "the page has been re-read since; look at the newest screen before choosing")
                .toString()
            is ScreenDoc.Resolve.NoSuchId -> return 404 to JSONObject()
                .put("ok", false)
                .put("error", "no element [$id] on $screenId")
                .put("screen", screenId)
                .toString()
            is ScreenDoc.Resolve.Changed -> {
                val rec = resolved.recorded
                return 409 to JSONObject()
                    .put("ok", false)
                    .put("error", "target changed")
                    // ⚠️ 用 label（已脱敏）而不是 rawLabel（原文）：
                    // 这个响应会回给模型、再发给服务端，原文一旦离开设备就收不回。
                    // 屏幕里显示的是 «身份证 ****1234»，这里也必须是一致的视图。
                    .put("reason", ScreenVerdict.changedReason(id, rec.label, resolved.now?.label))
                    .put("was", rec.label)
                    .put("now", resolved.now?.label ?: "")
                    .put("screen", screenId)
                    .toString()
            }
            is ScreenDoc.Resolve.Ok -> { /* 继续 */ }
        }

        val ok = resolved as ScreenDoc.Resolve.Ok
        val elem = ok.now
        // ⚠️ 保护页判定必须用**重建后**那份（resolveForTap 内部 build 出来的）。
        // 此前用的是 before（= latest 在 resolveForTap 结束后已复原成旧文档），
        // 于是「dump 时是普通页、点击瞬间已切到权限页」这个窗口判不出来 ——
        // 而那正是最危险的一瞬：用户刚点进无障碍设置，agent 的点击在路上。
        val protectedNow = ok.nowDocProtected || (before?.protectedPage == true)
        if (protectedNow) {
            return 403 to JSONObject()
                .put("ok", false)
                .put("error", "protected page")
                .put("reason", ScreenVerdict.PROTECTED_REASON)
                .put("screen", screenId)
                .put("text", before?.text ?: "")
                .toString()
        }

        val via = svc.tapNodeDeep(elem.node, elem.bounds)
            ?: return 500 to JSONObject()
                .put("ok", false)
                .put("error", "tap failed")
                .put("reason", ScreenVerdict.TAP_FAILED_REASON)
                .put("screen", screenId)
                .toString()

        // 等界面稳定（v1.6.20）：原来是写死 Thread.sleep(320) —— 动画慢的页面睡不够，
        // 会把「还没变」误判成「没变」；不变的页面又白等。现在看无障碍事件是否静默下来。
        // settled=false 表示等到超时仍有事件在刷（转圈、视频、持续动画）：
        // 这时仍然给出判定，但要说明结果可能不稳，而不是假装确定。
        val settled = svc.awaitSettle()
        val keysAfter = ScreenVerdict.keyboardVisible(svc)
        val after = withScreenTimeout("tapRef.after") { ScreenDoc.build(svc) }?.getOrNull()

        val verdict = before?.let { ScreenVerdict.of(it, after, false, keysBefore, keysAfter) }
            ?: ScreenVerdict.UNVERIFIABLE
        val changed = if (before != null && after != null) ScreenVerdict.diffCount(before, after) else 0

        return 200 to JSONObject()
            .put("ok", true)
            .put("through", via)
            .put("tapped", "[" + id + "] " + elem.label)
            .put("verdict", verdict)
            .put("changed", changed)
            .put("settled", settled)
            .put("screen", after?.screenId ?: "")
            .put("text", after?.text ?: "")
            .toString()
    }

    /** POST /wait-text —— 等文本出现/消失（v1.2.141）：{text, gone?, timeoutMs?} */
    private fun waitText(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val text = obj.optString("text")
        val gone = obj.optBoolean("gone", false)
        val timeoutMs = obj.optLong("timeoutMs", 8000L).coerceIn(200L, 60_000L)
        if (text.isBlank()) return 400 to """{"ok":false,"error":"text required"}"""
        // ⚠️ 并发闸门：本端点每次请求都会周期性占用全局读屏池（screenExec 仅 2 条线程）。
        // 不加限制时，并发几个 /wait-text 就能把池子占满 → /screen、/pick、/loop/run
        // 全部 2s 超时返回 504，表现为「无障碍坏了」的假警报（Agent 自伤）。
        // 名额被占满时直接快速失败，引导调用方串行等待，而不是排队把池子拖死。
        if (!waitTextPermits.tryAcquire()) {
            return 429 to """{"ok":false,"error":"too many concurrent /wait-text calls (max $WAIT_TEXT_CONCURRENCY); do the waits one at a time"}"""
        }
        try {
            val t0 = System.currentTimeMillis()
            val deadline = t0 + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                // ⚠️ 每次读屏都必须经 withScreenTimeout：直接调 dumpScreenCompact 是同步
                // 无障碍 IPC，节点树巨大时可能长时间不返回 —— 会永久占住这条请求线程，
                // 且本端点的 timeoutMs 上限是 60s、每 200ms 轮询一次。
                // 同类端点（/wait 走 "wait/dumpScreenJson"）都是这么做的，这里此前漏了。
                // 单次读屏超时不终止整个等待：把它当作「这一轮没读到」，继续下一轮。
                // withScreenTimeout 返回 Result<T>?（null = 超时放弃）。若再套一层
                // runCatching，类型会变成 Result<Result<T>?>.getOrNull() = Result<T>?，
                // 与随后的 String.contains 不兼容（编译报 receiver type mismatch）。
                // 直接对返回值取 getOrNull()：超时返回 null，成功则拿到 String。
                val dump = withScreenTimeout("waitText.dump", 2_500L) {
                    svc.dumpScreenCompact("all")
                }?.getOrNull()
                if (dump != null && dump.contains(text, true) != gone) {
                    return 200 to """{"ok":true,"text":"$text","gone":$gone,"waitedMs":${System.currentTimeMillis() - t0}}"""
                }
                Thread.sleep(200)
            }
            return 200 to """{"ok":false,"error":"timeout","text":"$text","gone":$gone,"timeoutMs":$timeoutMs}"""
        } finally {
            waitTextPermits.release()
        }
    }

    /** /wait-text 并发上限与许可池（读屏池仅 2 条线程，留余量给 /screen、/pick 等） */
    private val WAIT_TEXT_CONCURRENCY = 2
    private val waitTextPermits = java.util.concurrent.Semaphore(WAIT_TEXT_CONCURRENCY)

    /** POST /scroll-edge —— 滚到顶/底（v1.2.141）：{direction: top|bottom} */
    private fun scrollEdge(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val dir = runCatching { JSONObject(body).optString("direction", "bottom") }.getOrDefault("bottom")
        val n = runCatching { svc.scrollToEdge(forward = dir != "top") }.getOrDefault(0)
        return 200 to """{"ok":true,"direction":"$dir","swipes":$n}"""
    }

    /** GET /notif —— 最近的通知（v1.2.141，来自无障碍通知事件缓存） */
    private fun notif(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        val arr = JSONArray()
        svc.recentNotifications().forEach { arr.put(it) }
        return 200 to JSONObject().put("ok", true).put("notifications", arr).toString()
    }

    private fun respond(client: Socket, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        // 紧凑读屏返回的是 text/plain 行格式（省掉 JSON 转义膨胀）；
        // 其余端点都是 JSON。按首字符粗判即可，避免为类型再传参。
        val isJson = json.startsWith("{") || json.startsWith("[")
        val ct = if (isJson) "application/json; charset=utf-8" else "text/plain; charset=utf-8"
        val head = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: $ct\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            write(bytes)
            flush()
        }
    }
}

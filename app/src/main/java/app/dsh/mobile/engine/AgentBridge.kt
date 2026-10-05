package app.dsh.mobile.engine

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.dsh.mobile.DshAccessibilityService
import app.dsh.mobile.R
import app.dsh.mobile.StatusOverlay
import org.json.JSONObject
import java.io.BufferedReader
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
 *   POST /tap           body: {"x":123,"y":456} 或 {"text":"确定"}   → 模拟点击（需无障碍服务）
 *   POST /input         body: {"text":"...", "target":"<输入框提示>"} → 向输入框写文字（v1.2.54）
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

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    fun start(ctx: Context) {
        if (server != null) return
        try {
            val ss = ServerSocket(PORT, 16, java.net.InetAddress.getByName("127.0.0.1"))
            server = ss
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
            Log.i(TAG, "agent bridge started on 127.0.0.1:$PORT")
            // 对话完成监听（v1.2.57）：不依赖 AI 主动调 notify —— 只要会话写入
            // turn/end 事件就提示用户（用户反馈"任务完成后通知栏没有任何信息"）。
            TurnWatcher.start(ctx.applicationContext) { session ->
                onTurnEnd(ctx.applicationContext, session)
            }
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    /**
     * 一轮对话结束时的处理（v1.2.57）。
     * 发系统通知 + 悬浮窗高亮；若用户开了 TTS 播报则一并朗读。
     *
     * 与 AI 主动调 `notify` 的关系：两者互补 —— AI 的 notify 可带自定义内容
     * （如"构建完成，测试全过"），本监听是**兜底**：AI 忘了调也不会漏提示。
     */
    private fun onTurnEnd(ctx: Context, session: String) {
        runCatching {
            Log.i(TAG, "turn/end detected in $session")
            StatusOverlay.flashComplete(ctx.getString(R.string.overlay_turn_done))
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

    /** 是否开启"完成时语音播报"（设置页开关；缺省关闭） */
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

    private fun handle(ctx: Context, client: Socket) {
        Thread({
            try {
                client.soTimeout = 5_000
                // ⚠️ v1.2.43 修复：必须按**字节**读头与读体。Content-Length 是字节数而
                // 旧实现用 CharArray/Reader 按"字符数"读 → UTF-8 多字节字符（中文每字 3 字节）
                // 永远等不到足量字符 → SocketTimeoutException → 返回
                // {"ok":false,"error":"Read timed out"}。实测：AI 用 `say` 说中文必挂、
                // `/notify` 带中文同挂（App 自身测试不走 HTTP，故看起来"TTS 是好的"）。
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
                val parts = (lines.firstOrNull() ?: return@Thread).split(" ")
                if (parts.size < 2) return@Thread
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
                val bodyBytes = ByteArray(contentLength.coerceIn(0, 4 shl 20))
                var n = 0
                while (n < bodyBytes.size) {
                    val r = ins.read(bodyBytes, n, bodyBytes.size - n)
                    if (r < 0) break
                    n += r
                }
                val body = if (n > 0) String(bodyBytes, 0, n, StandardCharsets.UTF_8) else ""

                val (status, json) = route(ctx, method, path, query, body)
                respond(client, status, json)
            } catch (e: Exception) {
                Log.w(TAG, "handle: ${e.message}")
                runCatching { respond(client, 500, """{"ok":false,"error":"${e.message}"}""") }
            } finally {
                runCatching { client.close() }
            }
        }, "AgentBridge-req").apply { isDaemon = true; start() }
    }

    private fun route(ctx: Context, method: String, path: String, query: String, body: String): Pair<Int, String> {
        return when {
            method == "POST" && path == "/notify" -> notify(ctx, body)
            method == "GET" && path == "/screen" -> screen(query)
            method == "POST" && path == "/tap" -> tap(body)
            method == "GET" && path == "/screenshot" -> screenshot(query)
            method == "POST" && path == "/gesture" -> gesture(body)
            method == "POST" && path == "/key" -> key(body)
            method == "POST" && path == "/wait" -> wait(body)
            method == "POST" && path == "/input" -> input(body)
            method == "POST" && path == "/scroll-find" -> scrollFind(body)
            method == "POST" && path == "/idle" -> idle(body)
            method == "POST" && path == "/batch" -> batch(body)
            method == "GET" && path == "/interference" -> interference(query)
            method == "POST" && path == "/overlay" -> overlay(body)
            method == "POST" && path == "/launch" -> launch(ctx, body)
            method == "GET" && path == "/ext/list" -> extList(ctx)
            method == "GET" && path == "/ext/check" -> extCheck(ctx)
            method == "GET" && path == "/diag" -> diag(ctx)
            method == "POST" && path == "/say" -> say(ctx, body)
            method == "GET" && path == "/say" -> sayGet(ctx, query)
            method == "POST" && path == "/ext/install" -> extInstall(ctx, body)
            else -> 404 to """{"ok":false,"error":"unknown route"}"""
        }
    }

    /** GET /ext/list → 扩展清单与三态（red/yellow/green），AI 判断环境是否可用的唯一入口 */
    /**
     * GET /ext/check —— 只读健康检查（用户"一键检测"与 AI 自查共用）：
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

    private fun extList(ctx: Context): Pair<Int, String> {
        return try {
            val mgr = ExtensionManager(ctx)
            val arr = org.json.JSONArray()
            mgr.loadCatalog().forEach { e ->
                val st = when (mgr.state(e.id)) {
                    ExtensionManager.ExtState.NOT_DOWNLOADED -> "red"
                    ExtensionManager.ExtState.DOWNLOADED -> "yellow"
                    ExtensionManager.ExtState.ACTIVATED -> "green"
                }
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("name", e.name)
                        .put("category", e.category)
                        .put("state", st)
                        .put("version", mgr.installedVersion(e.id) ?: "")
                        .put("installing", mgr.isInstalling(e.id))
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
            } catch (e: Exception) {
                Log.w(TAG, "ext install $id: ${e.message}")
                notify(ctx, """{"title":"${ctx.getString(R.string.notif_ext_failed_title)}","body":"${ext.name}: ${e.message}"}""")
            }
        }, "ext-install-$id").apply { isDaemon = true; start() }
        return 202 to """{"ok":true,"state":"installing","message":"download started; poll GET /ext/list until state=green, then remind user to restart engine"}"""
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
    // 会无限期阻塞。而 handle() 的 client.soTimeout 只覆盖"读请求头"阶段，管不到
    // 这里；客户端门脚本也没有超时 → 一次卡死会把 /screen 与 /wait 一起挂住
    // （用户实测 scr dump 挂 >60s、notify 挂 >120s）。
    // 解法：把节点树读取丢进单线程 executor，有界等待；超时返回 504 并明确报错，
    // 让上层能区分「服务未开启(503)」与「IPC 卡住(504)」。单线程同时限制了并发，
    // 避免多个僵死任务堆积。
    private val screenExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-screen-ipc").apply { isDaemon = true }
    }

    /** 节点树 IPC 的默认上限：正常读屏几十毫秒，2s 足够；超时即判定僵死 */
    private val SCREEN_IPC_TIMEOUT_MS = 2_000L

    /**
     * 有界执行无障碍节点树读取。
     * @return 成功返回值；超时或异常返回 null 并记录原因（调用方据此回 504）
     */
    private fun <T> withScreenTimeout(
        what: String, timeoutMs: Long = SCREEN_IPC_TIMEOUT_MS, block: () -> T,
    ): Result<T>? = try {
        val future = screenExec.submit(java.util.concurrent.Callable { block() })
        Result.success(future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
    } catch (e: java.util.concurrent.TimeoutException) {
        Log.w(TAG, "screen ipc timeout: $what (>${timeoutMs}ms) — node tree stalled, giving up this read")
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
     * ≈ 1.5 万 tokens，每一轮对话都要重新过一遍注意力 —— 用户实测"五六秒才动一次"，
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
                    // ⚡ 紧凑模式也支持 diff（v1.2.58）：只回"与上次不同"的行。
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

    /** POST /tap → 坐标点击 / 按文本点击（text+desc 打分匹配，trim 忽略大小写）/ 按 desc 点击 */
    private fun tap(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
            // 悬浮窗（v1.2.57）：让用户实时看到 AI 正在执行什么动作
            StatusOverlay.setStatus(app.dsh.mobile.StatusOverlay.labelWorkingTap())
            StatusOverlay.setAction(
                when {
                    obj.has("desc") -> "tap-desc \"${obj.getString("desc")}\""
                    obj.has("text") -> "tap-text \"${obj.getString("text")}\""
                    obj.has("x") -> "tap ${obj.getDouble("x").toInt()},${obj.getDouble("y").toInt()}"
                    else -> "tap"
                },
            )
            val ok = when {
                obj.has("desc") -> svc.tapDesc(obj.getString("desc"))
                obj.has("text") -> svc.tapText(obj.getString("text"))
                obj.has("x") && obj.has("y") -> svc.dispatchTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                else -> false
            }
            // ⚠️ ok:true 只表示**手势已派发**，不代表页面真的响应了（用户实测：
            // 点不可点击节点的祖先中心时返回 ok:true 但毫无变化）。
            // 故在响应里带上 dispatched 语义 + 提示，让调用方知道要自行校验。
            if (ok) {
                200 to """{"ok":true,"dispatched":true,"hint":"ok means the gesture was dispatched, not that the UI reacted — dump again to verify"}"""
            } else {
                500 to """{"ok":false,"error":"tap failed / text not found"}"""
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** GET /screenshot → 截屏 PNG base64（takeScreenshot，API 30+）。
     *  能力缺失时给出准确指引：服务配置 canTakeScreenshot 需用户重新开启服务生效。 */
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
        return try {
            val obj = JSONObject(body)
            val ok = when (obj.optString("type")) {
                "swipe" -> svc.dispatchSwipe(
                    obj.getDouble("x1").toFloat(), obj.getDouble("y1").toFloat(),
                    obj.getDouble("x2").toFloat(), obj.getDouble("y2").toFloat(),
                    obj.optLong("durationMs", 300L),
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

    /** POST /key → 全局动作（back/home/recents/notifications/quick_settings）。
     *  ⚠️ 实测本应用内 back 会把整个 Activity 弹到桌面而非关闭弹层，
     *  调用方应配合 /screen 检查前台是否仍在目标界面。 */
    private fun key(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val action = JSONObject(body).optString("action")
            val ok = svc.performGlobalActionByName(action)
            if (ok) 200 to """{"ok":true,"action":"$action"}"""
            else 400 to """{"ok":false,"error":"unknown action (back|home|recents|notifications|quick_settings)"}"""
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
     * POST /input → 向输入框写文字（v1.2.54）。
     * body: {"text":"...", "append":false, "target":"<输入框提示文字>"}
     *
     * 此前 AI 能点开搜索框却打不了字 —— 搜索/登录/发消息/填表全部卡死在这一步。
     * 走无障碍 ACTION_SET_TEXT（不走 IME，不受输入法语言/联想干扰），失败回退剪贴板粘贴。
     */
    private fun input(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
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
     * 稳定检测把"点完等它安静下来"变成一次调用，替代硬 sleep 或猜文本。
     * 返回里带 pkg（前台包名），便于确认"还在不在目标界面"。
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
     * POST /batch → 一次执行一串动作（v1.2.54）。**治"慢"的关键。**
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
            val r = withScreenTimeout("runBatch", budget) {
                svc.runBatch(steps, settleMs, stopOnError)
            } ?: return 504 to """{"ok":false,"error":"batch timed out (node tree stalled)"}"""

            val results = r.getOrElse { return 500 to """{"ok":false,"error":"${it.message}"}""" }
            val stepsJson = org.json.JSONArray()
            results.forEach { s ->
                stepsJson.put(JSONObject().apply {
                    put("i", s.index)
                    put("type", s.action)
                    put("ok", s.ok)
                    if (s.detail.isNotEmpty()) put("detail", s.detail)
                })
            }
            val allOk = results.isNotEmpty() && results.all { it.ok }
            // 附上外部干预标记：若执行期间用户碰过屏幕，AI 需要知道结果可能不可信
            val snap = svc.interferenceSnapshot()
            val interfered = svc.interferedSince(
                System.currentTimeMillis() - budget, svc.lastSelfAction(),
            )
            JSONObject().apply {
                put("ok", allOk)
                put("executed", results.size)
                put("total", steps.size)
                put("steps", stepsJson)
                if (interfered) put("userInterference", true)
                put("pkg", snap.pkg ?: "")
            }.toString().let { 200 to it }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /**
     * GET /interference → 外部干预快照（v1.2.54，v1.2.56 修正判定依据）。
     * query: since=<ms> 时返回"自该时刻起是否有人碰过屏幕"。
     *
     * 用户反馈："人操作突然接管，AI 也不知道"。AI 可在关键步骤前记下时间戳，
     * 执行后再查一次，据此判断流程是否被打断（结果是否可信）。
     *
     * ⚠️ v1.2.56 修复假阳性：**只有触摸能作为"人接管"的判据**。
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
     * body: {"status":"执行中 · 搜索","action":"tap-text \"设置\"","show":true,"hide":false}
     *
     * 让 AI 主动告知用户"我在干什么"。悬浮窗本身在无障碍服务连上时自动显示，
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
     * body: {"package":"com.example.app"} 或 {"label":"设置"}
     *
     * 用户实测确认：normal 模式下 `am start` / `monkey` 被系统拒
     * （SecurityException / cannot find libbinder_ndk.so），
     * 但 App 自身的 PackageManager + startActivity 不需要任何特权。
     * 这是"一句话开任意 App"的唯一干净解法。
     */
    private fun launch(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val pkg = obj.optString("package").orEmpty()
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

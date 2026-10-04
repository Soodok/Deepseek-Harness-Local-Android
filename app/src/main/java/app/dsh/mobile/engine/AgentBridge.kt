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
import org.json.JSONArray
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
    @Volatile private var authToken: String? = null

    fun start(ctx: Context) {
        if (server != null) return
        val token = AgentBridgeSecurity.newToken()
        authToken = token
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
        } catch (e: Exception) {
            authToken = null
            Log.e(TAG, "start failed", e)
            throw IllegalStateException("Unable to start local AgentBridge on port $PORT", e)
        }
    }

    fun tokenForEngine(): String =
        checkNotNull(authToken) { "AgentBridge must be started before launching the engine" }

    fun rotateTokenForEngine(): String {
        checkNotNull(server) { "AgentBridge must be started before launching the engine" }
        return AgentBridgeSecurity.newToken().also { authToken = it }
    }

    fun stop() {
        authToken = null
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
                val expectedToken = authToken
                val authorizationHeaders = lines.drop(1)
                    .filter { it.startsWith("Authorization:", ignoreCase = true) }
                if (expectedToken == null ||
                    !AgentBridgeSecurity.isAuthorized(expectedToken, authorizationHeaders)
                ) {
                    respond(client, 401, """{"ok":false,"error":"unauthorized"}""")
                    return@Thread
                }
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
            method == "GET" && path == "/screenshot" -> screenshot()
            method == "POST" && path == "/gesture" -> gesture(body)
            method == "POST" && path == "/key" -> key(body)
            method == "POST" && path == "/wait" -> wait(body)
            // Phase 2: Task progress
            method == "POST" && path == "/task/progress" -> taskProgress(ctx, body)
            // Phase 3: Task management
            method == "GET" && path == "/agent/tasks" -> listTasks(ctx)
            method == "POST" && path == "/agent/tasks" -> createTask(ctx, body)
            method == "GET" && path.startsWith("/agent/tasks/") && path.endsWith("/feedback") -> getFeedback(ctx, path)
            method == "GET" && path.startsWith("/agent/tasks/") -> getTask(ctx, path)
            method == "POST" && path == "/agent/task/cancel" -> cancelTask(ctx, body)
            method == "POST" && path == "/agent/task/feedback" -> addFeedback(ctx, body)
            method == "GET" && path == "/agent/next" -> nextTask(ctx)
            method == "POST" && path == "/agent/task/complete" -> completeTask(ctx, body)
            method == "POST" && path == "/agent/task/fail" -> failTask(ctx, body)
            // Phase 1: Connect Phone pairing
            method == "POST" && path == "/pair/verify" -> verifyPairing(ctx, body)
            method == "GET" && path == "/pair" -> getPairingInfo(ctx)
            // Phase 4: Audit
            method == "GET" && path == "/audit" -> listAudit(ctx)
            method == "POST" && path == "/audit/clear" -> clearAudit(ctx)
            // Existing routes
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
                notify(ctx, """{"title":"Extension installed","body":"${ext.name} is activated — restart the engine to use it"}""")
            } catch (e: Exception) {
                Log.w(TAG, "ext install $id: ${e.message}")
                notify(ctx, """{"title":"Extension install failed","body":"${ext.name}: ${e.message}"}""")
            }
        }, "ext-install-$id").apply { isDaemon = true; start() }
        return 202 to """{"ok":true,"state":"installing","message":"download started; poll GET /ext/list until state=green, then remind user to restart engine"}"""
    }

    /** POST /notify → 系统通知（任务完成推送） */
    private fun notify(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val title = obj.optString("title").ifEmpty { "Agent Task" }
            val text = obj.optString("body").ifEmpty { "Task complete" }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Agent task notifications", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                val routed = app.dsh.mobile.service.EngineService.pushAgentNotice("Agent · $title: $text")
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

    /**
     * GET /screen → 无障碍读屏（服务未开启时 503）。
     * query 参数：xml=1 → 树形转储（含 viewId/scrollable/editable/层级）；
     * filter=clickable → 只输出可点击节点；diff=1 → 只输出与上次不同的节点（省 token）。
     */
    private fun screen(query: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled (enable 'DSH Screen Control' in system settings)"}"""
        return try {
            val params = parseQuery(query)
            when {
                params["xml"] == "1" ->
                    200 to """{"ok":true,"xml":${JSONObject.quote(svc.screenXml())}}"""
                else -> {
                    val clickableOnly = params["filter"] == "clickable"
                    val json = JSONObject(svc.dumpScreenJson(clickableOnly))
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
            val ok = when {
                obj.has("desc") -> svc.tapDesc(obj.getString("desc"))
                obj.has("text") -> svc.tapText(obj.getString("text"))
                obj.has("x") && obj.has("y") -> svc.dispatchTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                else -> false
            }
            if (ok) 200 to """{"ok":true}""" else 500 to """{"ok":false,"error":"tap failed / text not found"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** GET /screenshot → 截屏 PNG base64（takeScreenshot，API 30+）。
     *  能力缺失时给出准确指引：服务配置 canTakeScreenshot 需用户重新开启服务生效。 */
    private fun screenshot(): Pair<Int, String> {
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
            return 200 to """{"ok":true,"format":"png","width":${shot.first},"height":${shot.second},"base64":"${shot.third}"}"""
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
                val found = runCatching {
                    val nodes = JSONObject(svc.dumpScreenJson()).getJSONArray("nodes")
                    (0 until nodes.length()).any { i ->
                        val n = nodes.getJSONObject(i)
                        n.optString("text").contains(text, true) ||
                            n.optString("desc").contains(text, true)
                    }
                }.getOrDefault(false)
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

    /** Phase 2: POST /task/progress — update long-running task progress */
    private fun taskProgress(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val taskId = obj.optString("taskId", "")
            val title = obj.optString("title", "")
            val progress = obj.optInt("progress", 0)
            val max = obj.optInt("max", 100)
            val message = obj.optString("message", "")
            if (taskId.isBlank() || title.isBlank()) {
                return 400 to """{"ok":false,"error":"taskId and title required"}"""
            }
            app.dsh.mobile.service.EngineService.updateTaskProgress(taskId, title, progress, max, message)
            200 to """{"ok":true,"message":"progress updated"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 3: GET /agent/tasks — list all tasks sorted by priority */
    private fun listTasks(ctx: Context): Pair<Int, String> {
        return try {
            val tasks = TaskManager.taskList.value
            val arr = JSONArray()
            tasks.forEach { t ->
                arr.put(JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("description", t.description)
                    .put("priority", t.priority.name)
                    .put("status", t.status.name)
                    .put("createdAt", t.createdAt)
                    .put("startedAt", t.startedAt ?: 0)
                    .put("completedAt", t.completedAt ?: 0)
                    .put("result", t.result ?: "")
                    .put("error", t.error ?: "")
                )
            }
            200 to """{"ok":true,"tasks":${arr.toString()}}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 3: POST /agent/tasks — create a new task */
    private fun createTask(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val title = obj.optString("title", "").trim()
            val description = obj.optString("description", "").trim()
            val priorityStr = obj.optString("priority", "NORMAL").uppercase()
            if (title.isEmpty() || description.isEmpty()) {
                return 400 to """{"ok":false,"error":"title and description required"}"""
            }
            if (title.length > 512 || description.length > 16_384) {
                return 400 to """{"ok":false,"error":"title or description exceeds the allowed length"}"""
            }
            val priority = runCatching { TaskManager.Priority.valueOf(priorityStr) }.getOrNull()
                ?: return 400 to """{"ok":false,"error":"priority must be HIGH, NORMAL or LOW"}"""
            val task = kotlinx.coroutines.runBlocking { TaskManager.create(title, description, priority) }
            if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                android.os.Build.VERSION.SDK_INT < 33) {
                notify(ctx, JSONObject().put("title", "Task Created: $title").put("body", "Priority: ${priority.name}").toString())
            }
            201 to """{"ok":true,"id":"${task.id}","priority":"$priority"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 3: GET /agent/tasks/:id — get a specific task */
    private fun getTask(ctx: Context, path: String): Pair<Int, String> {
        val id = path.removePrefix("/agent/tasks/").substringBefore("/feedback")
        val task = kotlinx.coroutines.runBlocking { TaskManager.claim(id) } ?: TaskManager.get(id)
        return if (task != null) {
            200 to JSONObject()
                .put("ok", true)
                .put("id", task.id)
                .put("title", task.title)
                .put("description", task.description)
                .put("priority", task.priority.name)
                .put("status", task.status.name)
                .put("createdAt", task.createdAt)
                .put("startedAt", task.startedAt ?: 0)
                .put("completedAt", task.completedAt ?: 0)
                .put("result", task.result ?: "")
                .put("error", task.error ?: "")
                .toString()
        } else {
            404 to """{"ok":false,"error":"task not found: $id"}"""
        }
    }

    /** Phase 3: POST /agent/task/cancel — cancel a task */
    private fun cancelTask(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val id = obj.optString("id", "")
            if (id.isBlank()) return 400 to """{"ok":false,"error":"missing task id"}"""
            val cancelled = kotlinx.coroutines.runBlocking { TaskManager.cancel(id) }
            if (cancelled) {
                200 to """{"ok":true,"message":"task canceled"}"""
            } else {
                404 to """{"ok":false,"error":"task not found or already completed"}"""
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 3: GET /agent/next — agent picks next pending task by priority */
    private fun nextTask(ctx: Context): Pair<Int, String> {
        return try {
            val task = kotlinx.coroutines.runBlocking { TaskManager.nextTask() }
            if (task != null) {
                200 to JSONObject()
                    .put("ok", true)
                    .put("task", JSONObject()
                        .put("id", task.id)
                        .put("title", task.title)
                        .put("description", task.description)
                        .put("priority", task.priority.name)
                        .put("status", task.status.name)
                        .put("createdAt", task.createdAt)
                        .put("startedAt", task.startedAt ?: 0)
                        .put("result", task.result ?: "")
                    )
                    .toString()
            } else {
                200 to """{"ok":true,"task":null}"""
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 3: POST /agent/task/complete — mark a task as completed */
    private fun completeTask(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val id = obj.optString("id", "")
            val result = obj.optString("result", "")
            if (id.isBlank()) return 400 to """{"ok":false,"error":"missing task id"}"""
            val completed = kotlinx.coroutines.runBlocking { TaskManager.complete(id, result) }
            if (!completed) return 404 to """{"ok":false,"error":"task not found or already finished"}"""
            // Notify task completion with sound
            val task = TaskManager.get(id)
            if (task != null) {
                app.dsh.mobile.service.EngineService.pushAgentNotice("Task complete: ${task.title}")
            }
            200 to """{"ok":true,"message":"task marked complete"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 3: POST /agent/task/fail — mark a task as failed */
    private fun failTask(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val id = obj.optString("id", "")
            val error = obj.optString("error", "")
            if (id.isBlank()) return 400 to """{"ok":false,"error":"missing task id"}"""
            val failed = kotlinx.coroutines.runBlocking { TaskManager.fail(id, error) }
            if (!failed) return 404 to """{"ok":false,"error":"task not found or already finished"}"""
            val task = TaskManager.get(id)
            if (task != null) {
                app.dsh.mobile.service.EngineService.pushAgentNotice("Task failed: ${task.title}")
            }
            200 to """{"ok":true,"message":"task marked failed"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 3: POST /agent/task/feedback — add feedback to a task */
    private fun addFeedback(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val taskId = obj.optString("taskId", "")
            val text = obj.optString("text", "").trim()
            if (taskId.isBlank() || text.isBlank()) {
                return 400 to """{"ok":false,"error":"taskId and text required"}"""
            }
            if (kotlinx.coroutines.runBlocking { TaskManager.addFeedback(taskId, text) } == null) {
                return 404 to """{"ok":false,"error":"task not found"}"""
            }
            200 to """{"ok":true,"message":"feedback added"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 3: GET /agent/tasks/:id/feedback — list feedback for a task */
    private fun getFeedback(ctx: Context, path: String): Pair<Int, String> {
        val id = path.removePrefix("/agent/tasks/").removeSuffix("/feedback")
        val feedback = TaskManager.getFeedback(id)
        val arr = JSONArray()
        feedback.forEach { f ->
            arr.put(JSONObject()
                .put("id", f.id)
                .put("taskId", f.taskId)
                .put("text", f.text)
                .put("timestamp", f.timestamp)
            )
        }
        return 200 to """{"ok":true,"feedback":${arr.toString()}}"""
    }

    /** Phase 1: GET /pair — get current pairing info */
    private fun getPairingInfo(ctx: Context): Pair<Int, String> {
        val app = ctx.applicationContext as app.dsh.mobile.DshApp
        val pairing = ConnectPhoneManager.currentPairingCode()
                ?: ConnectPhoneManager.generatePairingCode(app.supervisor.healthyPort)
        return 200 to JSONObject()
            .put("code", pairing.code)
            .put("expiresIn", pairing.remainingSec)
            .put("isExpired", pairing.isExpired)
            .toString()
    }

    /** Phase 1: POST /pair/verify — verify a pairing code */
    private fun verifyPairing(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val code = obj.optString("code", "")
            if (code.isBlank()) return 400 to """{"ok":false,"error":"missing code"}"""
            val valid = ConnectPhoneManager.verifyPairingCode(code)
            if (valid) {
                200 to """{"ok":true,"message":"pairing verified"}"""
            } else {
                403 to """{"ok":false,"error":"invalid or expired pairing code"}"""
            }
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** Phase 4: GET /audit — list audit log entries */
    private fun listAudit(ctx: Context): Pair<Int, String> {
        val entries = AuditLogger.entries.value
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject()
                .put("timestamp", e.timestamp)
                .put("eventType", e.eventType)
                .put("privMode", e.privMode)
                .put("message", e.message)
            )
        }
        return 200 to """{"ok":true,"entries":${arr.toString()}}"""
    }

    /** Phase 4: POST /audit/clear — clear audit log */
    private fun clearAudit(ctx: Context): Pair<Int, String> {
        AuditLogger.clear(ctx)
        return 200 to """{"ok":true,"message":"audit log cleared"}"""
    }

    private fun respond(client: Socket, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            write(bytes)
            flush()
        }
    }
}

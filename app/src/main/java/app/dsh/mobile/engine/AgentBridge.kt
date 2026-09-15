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
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null; thread = null
        Log.i(TAG, "agent bridge stopped")
    }

    private fun handle(ctx: Context, client: Socket) {
        Thread({
            try {
                client.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@Thread
                val method = parts[0]
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')
                val query = rawPath.substringAfter('?', "")
                // headers 读完
                var line = reader.readLine()
                var contentLength = 0
                while (line != null && line.isNotEmpty()) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var n = 0
                    while (n < contentLength) {
                        val r = reader.read(buf, n, contentLength - n)
                        if (r < 0) break
                        n += r
                    }
                    String(buf, 0, n)
                } else ""

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
            method == "GET" && path == "/screen" -> screen()
            method == "POST" && path == "/tap" -> tap(body)
            method == "GET" && path == "/ext/list" -> extList(ctx)
            method == "GET" && path == "/diag" -> diag(ctx)
            method == "POST" && path == "/say" -> say(ctx, body)
            method == "GET" && path == "/say" -> sayGet(ctx, query)
            method == "POST" && path == "/ext/install" -> extInstall(ctx, body)
            else -> 404 to """{"ok":false,"error":"unknown route"}"""
        }
    }

    /** GET /ext/list → 扩展清单与三态（red/yellow/green），AI 判断环境是否可用的唯一入口 */
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
<h3>2. JS API 检测（false=缺失）</h3><pre id="api"></pre>
<h3>3. 引擎状态</h3><pre>$engineState</pre>
<h3>4. 3080 HTTP 探测</h3><pre>$httpProbe</pre>
<h3>5. WebSocket 探测</h3><pre>$wsProbe</pre>
<h3>6. runtime 版本</h3><pre>$runtimeVer</pre>
<h3>7. 前端 polyfill</h3><pre>$poly</pre>
<h3>8. engine.log 尾部</h3><pre>$logTail</pre>
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
        val mgr = ExtensionManager(ctx)
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
                mgr.download(ext) { }   // 无进度消费方：AI 侧靠轮询
                mgr.activate(ext.id)
                notify(ctx, """{"title":"扩展安装完成","body":"${ext.name} 已激活，重启引擎后可用"}""")
            } catch (e: Exception) {
                Log.w(TAG, "ext install $id: ${e.message}")
                notify(ctx, """{"title":"扩展安装失败","body":"${ext.name}: ${e.message}"}""")
            }
        }, "ext-install-$id").apply { isDaemon = true; start() }
        return 202 to """{"ok":true,"state":"installing","message":"download started; poll GET /ext/list until state=green, then remind user to restart engine"}"""
    }

    /** POST /notify → 系统通知（任务完成推送） */
    private fun notify(ctx: Context, body: String): Pair<Int, String> {
        return try {
            val obj = JSONObject(body)
            val title = obj.optString("title").ifEmpty { "Agent 任务" }
            val text = obj.optString("body").ifEmpty { "任务已完成" }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Agent 任务通知", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                return 403 to """{"ok":false,"error":"notification permission not granted"}"""
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

    /** GET /screen → 无障碍读屏（服务未开启时 503） */
    private fun screen(): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled (enable 'DSH Screen Control' in system settings)"}"""
        return try {
            200 to svc.dumpScreenJson()
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
    }

    /** POST /tap → 坐标点击或按文本点击 */
    private fun tap(body: String): Pair<Int, String> {
        val svc = DshAccessibilityService.instance
            ?: return 503 to """{"ok":false,"error":"accessibility service not enabled"}"""
        return try {
            val obj = JSONObject(body)
            val ok = when {
                obj.has("text") -> svc.tapText(obj.getString("text"))
                obj.has("x") && obj.has("y") -> svc.dispatchTap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                else -> false
            }
            if (ok) 200 to """{"ok":true}""" else 500 to """{"ok":false,"error":"tap failed / text not found"}"""
        } catch (e: Exception) {
            500 to """{"ok":false,"error":"${e.message}"}"""
        }
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

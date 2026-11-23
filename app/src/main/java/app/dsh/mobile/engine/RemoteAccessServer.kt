package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

/**
 * 局域网接入服务（3091，v1.7.1）。
 *
 * ## 它和桥（3083）的分工
 *
 * | 端口 | 做什么 | 谁能连 |
 * |---|---|---|
 * | 3083 桥 | 真正的设备能力（全部 /dev 与 /plugin 端点） | 环回 + 已配对密钥 |
 * | 3091 **本服务** | 只做「发现 + 配对 + 发脚本」，不碰设备能力 | 局域网任何人（但要配对才能拿到密钥） |
 *
 * 分开的理由：把「让人找到我」和「让人用我的能力」拆开。3091 是低权限的
 * 引导面板，即使被同网段扫描到也无所谓（它只会告诉你「配对码请在手机上打开」）；
 * 而 3083 一旦暴露就必须有凭据。
 *
 * ## 端点
 *
 * | 方法 | 路径 | 作用 |
 * |---|---|---|
 * | GET | `/ping` | 发现：返回设备名、版本、是否需要配对（**无鉴权**，手机热点里扫一下就知道连没连对） |
 * | POST | `/pair` | 用 6 位配对码换长期密钥，body `{"code":"123456","name":"my-pc"}` |
 * | GET | `/setup.sh` | 一键引导脚本（PC 上 `curl -s http://ip:3091/setup.sh \| sh`） |
 * | GET | `/gates.tgz` | 全部门脚本打包（引导脚本会拉它） |
 *
 * ## 安全边界（这些都是硬要求，不是建议）
 *
 * 1. **只接受私网来源**。公网地址的连接一律 403 —— 这条在手机偶尔被分配到
 *    公网 IP（某些运营商 CGNAT 之外的情况）时是最后一道门。
 * 2. 配对码只在窗口内有效（[RemoteAccess.PAIR_WINDOW_MS]），**一次性**。
 * 3. `/pair` 每来源 IP 有尝试次数限制（[MAX_PAIR_ATTEMPTS]），防在线爆破。
 * 4. `/gates.tgz` 与 `/setup.sh` 需要**已配对密钥**（`X-DSH-Token` 头或
 *    `?key=` 参数），否则一个陌生设备就能把我们的脚本全下载走做分析。
 *    只有 `/ping` 与 `/pair` 是开放的 —— 那正是配对流程本身需要的。
 * 5. 总开关关闭时本服务不监听。
 */
object RemoteAccessServer {

    private const val TAG = "RemoteAccessSrv"

    /** 独立端口（3080 引擎 / 3083 桥 / 3090 LAN 网页代理都已占用）。 */
    const val PORT = 3091

    /** `/pair` 每来源 IP 的最大尝试次数（窗口期内）。 */
    private const val MAX_PAIR_ATTEMPTS = 10

    /** 单次请求读头上限。 */
    private const val MAX_HEAD = 8 shl 10

    /** 请求体上限（配对请求只有几十字节，8KB 足够）。 */
    private const val MAX_BODY = 8 shl 10

    private val CRLF = Character.toString(13) + Character.toString(10)

    private val running = AtomicBoolean(false)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var appCtx: Context? = null

    /** 每 IP 的配对尝试计数（窗口期内有效；窗口关闭时清空）。 */
    private val pairAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 请求处理池：有界，防刷连接。与桥同款理由。 */
    private val exec = java.util.concurrent.ThreadPoolExecutor(
        2, 4, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(16),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RemoteAccess-req").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )

    // ------------------------------------------------------------ 生命周期

    /** 启动（幂等）。总开关没开时直接返回 —— 调用方不需要再判一次。 */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!RemoteAccess.isEnabled(ctx)) {
            Log.i(TAG, "not starting: remote access disabled")
            return
        }
        if (server != null) return
        runCatching {
            val ss = ServerSocket()
            // 绑 0.0.0.0 而不是 127.0.0.1：这个端口存在的意义就是被局域网发现。
            // 真正的安全靠「只私网来源 + 已配对密钥」，不靠绑定位置。
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", PORT), 16)
            server = ss
            running.set(true)
            thread = Thread({
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        runCatching { exec.execute { handle(ctx.applicationContext, client) } }
                            .onFailure {
                                Log.w(TAG, "request rejected: pool full")
                                runCatching { client.close() }
                            }
                    } catch (e: Exception) {
                        if (!ss.isClosed) Log.w(TAG, "accept: ${e.message}")
                    }
                }
            }, "RemoteAccessServer").apply { isDaemon = true; start() }
            Log.i(TAG, "listening on 0.0.0.0:$PORT")
        }.onFailure {
            Log.w(TAG, "bind failed on $PORT: ${it.message}")
            running.set(false)
        }
    }

    /** 停止（幂等）。 */
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        thread = null
        pairAttempts.clear()
        Log.i(TAG, "stopped")
    }

    fun isRunning(): Boolean = running.get() && server?.isClosed == false

    // ------------------------------------------------------------ 请求处理

    private fun handle(ctx: Context, client: Socket) {
        try {
            client.soTimeout = 8000
            val ins = BufferedInputStream(client.getInputStream())

            // 读头（按字节，理由同桥：Content-Length 是字节数，用 Reader 等不到中文）
            val head = ByteArrayOutputStream()
            var crlf = 0
            while (head.size() < MAX_HEAD) {
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
            val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("$CRLF")
            val parts = (lines.firstOrNull() ?: return).split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")

            var contentLength = 0
            lines.drop(1).forEach { l ->
                if (l.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = l.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY) {
                respond(client, 413, jsonErr("payload too large"))
                return
            }
            val bodyBytes = ByteArray(contentLength)
            var n = 0
            while (n < bodyBytes.size) {
                val r = ins.read(bodyBytes, n, bodyBytes.size - n)
                if (r < 0) break
                n += r
            }
            val body = if (n > 0) String(bodyBytes, 0, n, Charsets.UTF_8) else ""

            val peer = client.inetAddress

            // ★第一道门：只允许私网来源。放在所有分支之前 —— 公网地址连 /ping
            // 也不该得到回应（否则等于把「这里有台手机」广播到互联网）。
            if (!isPrivatePeer(peer)) {
                Log.w(TAG, "reject non-private peer: ${peer?.hostAddress}")
                respond(client, 403, jsonErr("private network only"))
                return
            }

            when {
                method == "GET" && path == "/ping" -> respond(client, 200, ping(ctx, peer))

                method == "POST" && path == "/pair" -> respond(client, 200, pair(ctx, body, peer))

                method == "GET" && path == "/setup.sh" -> {
                    if (!authorised(ctx, lines, query)) { respond(client, 403, jsonErr("unauthorised")); return }
                    respondText(client, 200, "text/x-shellscript", setupScript(presentedKey(lines, query) ?: ""))
                }

                method == "GET" && path == "/gates.tgz" -> {
                    if (!authorised(ctx, lines, query)) { respond(client, 403, jsonErr("unauthorised")); return }
                    respondBytes(client, 200, "application/gzip", gatesArchive(ctx))
                }

                else -> respond(client, 404, jsonErr("not found"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle: ${e.message}")
            runCatching { respond(client, 500, jsonErr(e.message ?: "error")) }
        } finally {
            runCatching { client.close() }
        }
    }

    // ------------------------------------------------------------ 端点实现

    /**
     * 发现端点（无鉴权）。
     *
     * 只吐「这里是什么、要不要配对」，**不吐任何设备信息** —— 无鉴权端点
     * 不能成为指纹来源。设备型号、已配对数量都不在这里。
     */
    private fun ping(ctx: Context, peer: InetAddress?): String {
        val remote = RemoteAccess.isEnabled(ctx)
        return org.json.JSONObject().apply {
            put("ok", true)
            put("service", "dsh-mobile-remote-access")
            put("version", 1)
            put("bridge_port", AgentBridge.PORT)
            put("setup_port", PORT)
            // 只告诉「现在能不能直接配对」，不告诉「有几个已配对」
            put("pair_window_open", RemoteAccess.pairWindowOpen(ctx))
            put("needs_pairing", true)
            put("peer", peer?.hostAddress ?: "")
        }.toString()
    }

    /**
     * 配对端点。
     *
     * body：`{"code":"123456","name":"windows-pc"}`
     *
     * 成功返回该设备的专属密钥。这个密钥从此可以调 3083 的全部能力。
     * 失败原因**不细分**（不说「码错了」还是「窗口关了」）—— 减少信息泄露，
     * 但日志里记清楚，方便用户自己排查。
     */
    private fun pair(ctx: Context, body: String, peer: InetAddress?): String {
        val ip = peer?.hostAddress ?: "?"
        if (!RemoteAccess.pairWindowOpen(ctx)) {
            Log.w(TAG, "pair from $ip rejected: window closed")
            return jsonErr("pairing window closed; open it in the app")
        }
        // 每 IP 尝试次数限制（窗口期内）
        val tried = pairAttempts.merge(ip, 1) { a, b -> a + b } ?: 1
        if (tried > MAX_PAIR_ATTEMPTS) {
            Log.w(TAG, "pair from $ip rejected: too many attempts ($tried)")
            return jsonErr("too many attempts; reopen the pairing window in the app")
        }
        if (!RemoteAccess.isEnabled(ctx)) return jsonErr("remote access disabled")
        // 名额已满时给出**明确原因**，而不是让它落到「配对失败」——
        // 用户看到「码错了」会反复试，实际是自己满了 8 台得先撤销一个。
        if (RemoteAccess.pairs(ctx).size >= RemoteAccess.MAX_PAIRS) {
            Log.w(TAG, "pair from $ip rejected: device limit reached (${RemoteAccess.MAX_PAIRS})")
            return jsonErr("device limit reached; revoke one in the app first")
        }

        val obj = runCatching { org.json.JSONObject(body) }.getOrElse { return jsonErr("invalid json") }
        val code = obj.optString("code").trim()
        val name = obj.optString("name").trim()
        if (code.isEmpty()) return jsonErr("missing code")

        val got = RemoteAccess.redeemPairCode(ctx, code, name)
            ?: run {
                Log.w(TAG, "pair from $ip failed: wrong code")
                return jsonErr("pairing failed")
            }
        Log.i(TAG, "pair from $ip succeeded as '${got.name}'")
        // 配对成功后该 IP 的计数已无意义（窗口也关了），顺手清掉
        pairAttempts.remove(ip)
        return org.json.JSONObject().apply {
            put("ok", true)
            put("key", got.key)
            put("name", got.name)
            put("bridge_port", AgentBridge.PORT)
            put("usage", "export DSH_BRIDGE_PORT=${AgentBridge.PORT} DSH_BRIDGE_TOKEN=<key>")
        }.toString()
    }

    /**
     * 一键引导脚本（已配对才给）。
     *
     * 目标是「PC 上一条命令就开始用」：
     * ```
     * curl -s "http://192.168.43.1:3091/setup.sh?key=<KEY>" | sh
     * ```
     * 它做三件事：写配置到 `~/.dsh-phone/env`、下载门脚本、打印用法。
     * 幂等 —— 重复跑只是覆盖同一份文件。
     */
    private fun setupScript(key: String): String = """
        #!/bin/sh
        # dsh-android device bridge setup (generated by the phone on port 3091)
        #
        # Usage (on the PC that will use this phone):
        #   curl -s "http://<phone-ip>:3091/setup.sh?key=<your-key>" | sh
        #
        # It will:
        #   1. write the key and port into ~/.dsh-phone/env
        #   2. download the gate scripts into ~/.dsh-phone/gates/
        #   3. print how to use them
        #
        # Plain ASCII on purpose: the target is an arbitrary PC terminal whose
        # locale we do not control, and a broken UTF-8 locale turns progress
        # messages into mojibake. Use English here, not resource strings.
        set -e

        BASE="${'$'}{DSH_PHONE_BASE:-http://__PHONE_IP__:3091}"
        # The key is already inlined below (it came from the ?key= of this request).
        # DSH_PHONE_KEY can still override it, e.g. after re-pairing with a new device.
        KEY="${'$'}{DSH_PHONE_KEY:-__KEY_PLACEHOLDER__}"
        DEST="${'$'}{DSH_PHONE_DIR:-${'$'}HOME/.dsh-phone}"

        if [ -z "${'$'}KEY" ]; then
          echo "missing key; rerun with the key you got while pairing:" >&2
          echo "  curl -s \"${'$'}BASE/setup.sh?key=<KEY>\" | sh" >&2
          exit 1
        fi

        mkdir -p "${'$'}DEST/gates"
        cat > "${'$'}DEST/env" <<ENV
        # dsh-android device bridge wiring (generated by the phone)
        export DSH_BRIDGE_HOST="__PHONE_IP__"
        export DSH_BRIDGE_PORT="${AgentBridge.PORT}"
        export DSH_BRIDGE_TOKEN="${'$'}KEY"
        export PATH="${'$'}DEST/gates:${'$'}PATH"
        ENV
        chmod 600 "${'$'}DEST/env"

        echo "downloading gate scripts..."
        if command -v curl >/dev/null 2>&1; then
          curl -fsSL -H "X-DSH-Token: ${'$'}KEY" "${'$'}BASE/gates.tgz" | tar xz -C "${'$'}DEST/gates" || {
            echo "download failed (is the phone still online, is the key valid?)" >&2; exit 1; }
        else
          echo "curl is required" >&2; exit 1
        fi
        chmod +x "${'$'}DEST/gates/"* 2>/dev/null || true

        echo ""
        echo "ready. usage:"
        echo "  . ${'$'}DEST/env        # source once per new terminal"
        echo "  dev                  # list what this phone can do"
        echo "  dev status           # phone status"
        echo "  dev api POST /dev/tap '{\"text\":\"OK\"}'"
    """.trimIndent()
        .replace("__PHONE_IP__", LanAddress.best() ?: "127.0.0.1")
        // Keys are 64-char hex; anything else means the caller did not really pass one.
        .replace("__KEY_PLACEHOLDER__", key.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,256}")) } ?: "PASTE_YOUR_KEY_HERE")

    /**
     * 门脚本包（已配对才给）。
     *
     * 打包 [EngineConfig] 部署的那套脚本，但**把占位符换成 PC 侧能用的值**：
     * · `@DSH_BASH@` → `/usr/bin/env bash`（PC 上 bash 在 PATH 里，不需要绝对路径）
     * · `@DSH_LIBDIR@` → 空（PC 侧不设 LD_LIBRARY_PATH，用了会污染）
     *
     * 这一步是必须的：原样发出去，PC 上会因为 `#!/system/bin/sh` 与
     * 引擎 bionic 的绝对路径直接跑不起来（实测过）。
     */
    private fun gatesArchive(ctx: Context): ByteArray {
        val names = runCatching {
            ctx.assets.list("gates")?.toList().orEmpty()
        }.getOrElse { emptyList() }
            // 全量打包，**不能**排除 `_dsh_http.sh`：34 个门脚本都 `. "$DIR/_dsh_http.sh"`
            // 依赖它（纯 bash 的 HTTP 客户端）。旧实现写的是
            // `.filter { it != "_dsh_http.sh" || true }` —— 恒真，等于没过滤，注释与实际不符；
            // 真要按那条注释排除，反而会把 34 个脚本一起打瘫。这里改成显式不过滤。

        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            val tar = java.io.ByteArrayOutputStream()
            fun writeTarEntry(name: String, data: ByteArray) {
                val header = ByteArray(512)
                fun put(s: String, off: Int, len: Int) {
                    val b = s.toByteArray(Charsets.UTF_8)
                    System.arraycopy(b, 0, header, off, minOf(b.size, len))
                }
                put(name, 0, 100)
                put("000755", 100, 8)
                put("0000000", 108, 8)
                put("0000000", 116, 8)
                put("%011o".format(data.size), 124, 12)
                put("%011o".format(0), 136, 12)
                put("        ", 148, 8)
                header[156] = '0'.code.toByte()
                put("ustar", 257, 5)
                put("00", 263, 2)
                // 校验和：先填空格，再算总和
                java.util.Arrays.fill(header, 148, 156, ' '.code.toByte())
                var sum = 0
                header.forEach { sum += (it.toInt() and 0xFF) }
                put("%06o\u0000 ".format(sum), 148, 8)
                tar.write(header)
                tar.write(data)
                val pad = (512 - data.size % 512) % 512
                if (pad > 0) tar.write(ByteArray(pad))
            }

            names.forEach { name ->
                runCatching {
                    val raw = ctx.assets.open("gates/$name").use { it.readBytes() }
                        .toString(Charsets.UTF_8)
                        .replace("\r\n", "\n").replace("\r", "\n")
                        // PC 侧有三处必须改，少一处脚本就跑不起来（实测过）：
                        //  ① shebang `#!/system/bin/sh` 在 PC 上不存在 → 换 /bin/sh
                        //  ② `exec "@DSH_BASH@"` 带引号，占位符被整串当路径
                        //     → 去掉引号并指向 /bin/bash（PC 上一定有）
                        //  ③ LD_LIBRARY_PATH 指向引擎的 bionic 目录，PC 上用会污染
                        .replaceFirst("#!/system/bin/sh", "#!/bin/sh")
                        .replace("\"@DSH_BASH@\"", "/bin/bash")
                        .replace("@DSH_BASH@", "/bin/bash")
                        .replace("@DSH_LIBDIR@", "")
                    writeTarEntry(name, raw.toByteArray(Charsets.UTF_8))
                }.onFailure { Log.w(TAG, "pack $name failed: ${it.message}") }
            }
            tar.write(ByteArray(1024))   // tar 结束标记
            gz.write(tar.toByteArray())
        }
        return bos.toByteArray()
    }

    // ------------------------------------------------------------ 工具

    /**
     * 来源是否私网。
     *
     * 判断依据是**对端地址本身**而不是接口名：热点场景下连进来的设备可能是
     * 192.168.43.x，而手机自己是 192.168.43.1，两者都是私网。
     * 若手机被分配公网 IP（罕见），这条会拒掉一切外部连接 —— 这是刻意的保守选择。
     */
    private fun isPrivatePeer(addr: InetAddress?): Boolean {
        if (addr == null) return false
        if (addr.isLoopbackAddress) return true   // 手机自己/容器内访问
        if (addr.isSiteLocalAddress) return true  // 10/8、172.16/12、192.168/16
        // Android 热点常见网段（部分机型用 192.168.x 之外的 CGNAT 段）
        val h = addr.hostAddress ?: return false
        return h.startsWith("100.")   // 运营商级 NAT，热点里也常见
    }

    /** 请求里带的密钥（`X-DSH-Token` 头优先，其次 `?key=`）。没带就回 null。
     *
     * 单独拆出来是因为 `/setup.sh` 不只要「验过」，还要**把同一个密钥写进脚本**：
     * 用户拿到的是 `curl ...?key=<KEY> | sh` 这一条命令，密钥只出现在 URL 里。
     * 旧实现只校验、不回填（脚本里留 `PASTE_YOUR_KEY_HERE` 占位符），于是按文档
     * 照着敲的用户会拿占位符当密钥去下载 gates.tgz → 必然 403（真机实测）。
     */
    private fun presentedKey(lines: List<String>, query: String): String? {
        val fromHeader = lines.firstOrNull { it.startsWith("X-DSH-Token:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val fromQuery = runCatching {
            query.split("&").firstOrNull { it.startsWith("key=") }?.substringAfter("=")
                ?.let { URLDecoder.decode(it, "UTF-8") }
        }.getOrNull()
        return fromHeader?.takeIf { it.isNotBlank() } ?: fromQuery?.takeIf { it.isNotBlank() }
    }

    /** 从请求头或 query 里取密钥并校验。 */
    private fun authorised(ctx: Context, lines: List<String>, query: String): Boolean =
        RemoteAccess.authorise(ctx, fromLoopback = false, presented = presentedKey(lines, query))

    private fun jsonErr(msg: String): String =
        org.json.JSONObject().apply { put("ok", false); put("error", msg) }.toString()

    private fun respond(client: Socket, status: Int, json: String) =
        respondBytes(client, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

    private fun respondText(client: Socket, status: Int, type: String, text: String) =
        respondBytes(client, status, "$type; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun respondBytes(client: Socket, status: Int, type: String, payload: ByteArray) {
        val reason = when (status) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"
            413 -> "Payload Too Large"; else -> "Error"
        }
        val out: OutputStream = client.getOutputStream()
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason).append(CRLF)
            .append("Content-Type: ").append(type).append(CRLF)
            .append("Content-Length: ").append(payload.size).append(CRLF)
            .append("Cache-Control: no-store").append(CRLF)
            .append("Connection: close").append(CRLF)
            .append(CRLF)
            .toString()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        out.flush()
    }
}

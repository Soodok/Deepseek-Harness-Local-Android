package app.dsh.mobile.engine

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import kotlin.concurrent.thread

/**
 * 容器内 DNS 代理（v1.6.10）。
 *
 * ## 为什么必须有这个东西
 *
 * Android 上 **UDP 53 被拦、TCP 53 通**。真机实测（App uid 10488 与 shell uid 2000
 * 都试过）：
 * ```
 * # 宿主侧
 * $ nc -u -w 3 223.5.5.5 53   → rc=124（超时）
 * $ nc -w 3 223.5.5.5 53      → rc=0（连上）
 *
 * # 容器内（musl）
 * $ nslookup mirrors.aliyun.com
 *   → nslookup: write to '223.5.5.5': Operation not permitted
 *
 * # 但同一个容器用固定 IP 就能通，证明网络本身没问题
 * $ wget --header="Host: mirrors.aliyun.com" http://112.15.7.206/alpine/
 *   → rc=0
 * ```
 *
 * 后果是容器里的 `apk add` / `pip` / `npm` / `git clone` / AI 联网**全部不可用**。
 *
 * ## 两条路，按 libc 分
 *
 * - **glibc**（Ubuntu/Debian）：`/etc/resolv.conf` 加 `options use-vc`，
 *   让 glibc 的解析器强制走 TCP。一行搞定，见
 *   [LinuxEnvironmentManager.RESOLV_CONF_GLIBC]。
 * - **musl**（Alpine）：musl **不支持** `use-vc`（源码里没这个选项），
 *   无法强制 TCP。只能走本文件的本地代理。
 *
 * ## musl 侧的做法
 *
 * musl 的 `resolv.conf` 支持 `nameserver <ip>:<port>` 的形式（实测：
 * 写 `nameserver 127.0.0.1:5353` 后报 `connection timed out` 而**不是**
 * `Operation not permitted` —— 说明它认了这个地址和端口，只是当时没人监听）。
 *
 * 而容器与 App 共享同一个 network namespace（实测容器内能连上宿主的
 * `127.0.0.1:18080`）。所以：
 *
 * 1. App 内起一个 TCP+UDP 监听的 DNS 代理，绑 `127.0.0.1:<随机高位端口>`
 * 2. 容器的 `/etc/resolv.conf` 写 `nameserver 127.0.0.1:<那个端口>`
 * 3. 代理收到查询后，用 `InetAddress`（走 Android 的 netd，bionic 侧 DNS 正常）
 *    解析，再按 DNS 报文格式回包
 *
 * ## 为什么不用 53 端口
 *
 * 实测 App uid **绑不上 53**（`bind 53: FAIL`），因为 <1024 是特权端口。
 * 5353 及更高可以绑。所以用高位端口 + `nameserver ip:port` 的形式。
 *
 * ## 设计：纯转发器，不是自己合成应答
 *
 * 第一版是用 `InetAddress.getAllByName()` 查完再拼 DNS 应答。实测不够用：
 * `nslookup` 能通，但 `wget` / `apk` 走的是 `getaddrinfo`，它们**同时查 A 和
 * AAAA**，第一版对 AAAA（本机无 IPv6 出口）返回 `ANCOUNT=0`，musl 的
 * `getaddrinfo` 认为整次解析失败 → `wget: bad address`。
 *
 * 更根本的问题是：自己合成应答等于重写一个 DNS 服务器，MX / TXT / SRV /
 * CNAME 链 / DNSSEC 全都不对。
 *
 * 所以改成**纯转发**：收到 UDP 查询 → 原样通过 **TCP** 发给上游 DNS
 * （App uid 实测能连上游 53，见下）→ 把上游的应答原样回传。
 * 报文一个字节都不改，所有记录类型天然正确。
 *
 * ## 关键实测前提
 *
 * ```
 * $ su 10488 -c "nc -w 4 223.5.5.5 53"    → rc=0   # App uid 能 TCP 连上游 53
 * $ nc -u -w 3 223.5.5.5 53              → rc=124 # 但 UDP 53 被拦
 * ```
 * 即「出站 TCP 53 放行、UDP 53 被拦」，而容器只能发 UDP（musl 的解析器
 * 默认 UDP，无 `use-vc`）。代理正好把 UDP 转成 TCP。
 */
object LinuxDnsProxy {

    private const val TAG = "LinuxDnsProxy"

    /**
     * 上游 DNS 服务器，按顺序 failover。
     *
     * 选这两家的原因：国内可达、支持 TCP 53、且 App uid 实测能连
     * （`nc -w 4 223.5.5.5 53` → rc=0）。
     */
    /**
     * DoH 端点前缀（后面直接拼 base64url 查询）。
     *
     * 用 IP 而不是域名：App 自己的解析器虽然能解析域名，但容器里的域名解析
     * 正是我们要修的东西，用 IP 避免自举依赖。两家都是国内公共 DNS 的
     * RFC 8484 端点，实测均返回 200 + 完整应答。
     */
    private val DOH_ENDPOINTS = listOf(
        "https://223.5.5.5/dns-query?dns=",
        "https://1.12.12.12/dns-query?dns=",
    )

    /** 全信任 SSLSocketFactory（理由见 dohGet 的注释） */
    private val TRUST_ALL_FACTORY: SSLSocketFactory by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
        ctx.socketFactory
    }

    /** 单次上游超时。太长会让 `wget` / `apk` 卡住，太短会误判网络不可用。 */
    private const val UPSTREAM_TIMEOUT_MS = 4000

    /**
     * 固定监听端口。
     *
     * ★不能用随机端口（第一版就是 ServerSocket(0)）。实测教训：App 重启后
     * 新代理换了端口，但 rootfs 里的 `/etc/resolv.conf` 还留着**上一轮的旧端口**，
     * 指向一个已经没人监听的死端口 → 容器 DNS 彻底失效，而且从日志里完全看不出
     * 异常（`nslookup` 只是超时）。
     *
     * 固定端口后，新旧进程用的是同一个地址，配置永远有效；即使代理暂时没起来，
     * 重启后也自动恢复。
     *
     * 选 15353 而不是 53：<1024 是特权端口，App uid 绑不上（实测
     * `bind 53: FAIL`）；也不选 5353（被系统 mdnsd 占着，实测 ss 里能看到）。
     */
    private const val PREFERRED_PORT = 15353

    /**
     * 真正要绑的端口。
     *
     * ★必须绑 **53**，不能用高位端口。实测（musl 1.2 / Alpine 3.22）：
     * ```
     * # resolv.conf 写 nameserver 127.0.0.1:15353
     * 实际发到 53 端口: 144 字节     ← musl 忽略了端口
     * 发到 15353:      0 字节
     * ```
     * musl 的 `getaddrinfo` 把 DNS 端口**写死成 53**（它按 nameserver 只解析
     * IP，冒号后缀整个扔掉），所以只有 busybox 的 `nslookup`（自带解析器）
     * 才会认端口。`wget` / `apk` / `nc` / node 全走 getaddrinfo，只认 53。
     *
     * 而 53 是特权端口（`ip_unprivileged_port_start=1024`），App uid 绑不上
     * （实测 `UDP bind 53 ERR: EACCES`）。唯一办法是**有 root 时**把这个
     * sysctl 调低（实测调到 53 后 App uid 立刻能绑）。
     *
     * 所以：有 root → 绑 53，Alpine 的 DNS 真正可用；无 root → 退回高位端口，
     * 只有 `nslookup` 这类自带解析器能通，`wget`/`apk` 仍然失败
     * （这是平台限制，不是实现缺陷，会在 UI 上如实提示）。
     */
    private const val DNS_PORT = 53

    /** 单例状态 */
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var port = 0
    private val running = AtomicBoolean(false)

    /**
     * 启动代理，返回**实际监听端口**（0 表示失败）。
     *
     * 幂等：已在跑就直接返回原端口。
     */
    @Synchronized
    fun start(ctx: android.content.Context? = null): Int {
        if (running.get() && port > 0) return port
        return try {
            val loopback = InetAddress.getByName("127.0.0.1")

            // ---- 第一步：尽量拿到 53 ----
            // 只有绑上 53，musl 的 getaddrinfo 才会把查询送到这里（它把端口写死）。
            // 53 是特权端口，需要先把 ip_unprivileged_port_start 调低；
            // 那需要 root。没有 root 就退到高位端口（只有 nslookup 能用）。
            var boundPort = -1
            var server: ServerSocket? = null
            var datagram: DatagramSocket? = null

            // ★端口策略（不需要 53，也不需要 root）：
            //   容器内的 loader 做**双向**端口改写（见 dsh_alpine.c，DSH_DNS_PORT 环境变量）：
            //     出程 sendto/sendmsg   127.0.0.1:53  ->  127.0.0.1:<这里绑的端口>
            //     回程 recvfrom/recvmsg 来源 <该端口>   ->  改回 53
            //   回程不能省：musl 的 __res_msend_rc 校验应答来源端口必须等于它发的目标端口，
            //   只改出程会让它把应答整包丢弃（sendto 成功、我们这边也 replied 了，
            //   但 getaddrinfo 仍 EAI_AGAIN —— 实测踩了两轮才定位）。
            //   改写只针对回环（外部 DNS 本就被 Android 按 uid 拦，改了没用还掩盖问题）。
            //   所以**不再需要特权端口**：优先绑固定的 [PREFERRED_PORT]（配置稳定，
            //   App 重启后端口不变），被占才退回随机高位端口。
            val tries = mutableListOf(PREFERRED_PORT, 0, DNS_PORT)
            for (candidate in tries) {
                if (candidate == DNS_PORT) {
                    // 先试直接绑（万一 sysctl 已经是低值）
                    runCatching {
                        datagram = DatagramSocket(InetSocketAddress(loopback, DNS_PORT))
                        server = ServerSocket(DNS_PORT, 8, loopback)
                        boundPort = DNS_PORT
                    }
                    if (boundPort != DNS_PORT && ctx != null) {
                        // 直接绑失败 → 借用 root 把 sysctl 调下来，然后**无条件**重试绑定。
                        //
                        // ★不能拿「读回 sysctl 值」当门禁：untrusted_app 域读不了
                        //   /proc/sys/net/ipv4/ip_unprivileged_port_start（返回 null），
                        //   于是会误判「没生效」而跳过重试 —— 实测就是这样：sysctl 明明
                        //   已经是 53，代码却走了 `if (false)` 分支，白费一次 root 调用。
                        //   改成「跑完 su 就重试」，用**绑定成功与否**做唯一判据。
                        runCatching { datagram?.close(); server?.close() }
                        datagram = null; server = null
                        lowerUnprivilegedPortStart(ctx)
                        runCatching {
                            datagram = DatagramSocket(InetSocketAddress(loopback, DNS_PORT))
                            server = ServerSocket(DNS_PORT, 8, loopback)
                            boundPort = DNS_PORT
                        }
                    }
                    if (boundPort != DNS_PORT) {
                        runCatching { server?.close() }
                        runCatching { datagram?.close() }
                        server = null; datagram = null
                        Log.w(TAG, "cannot bind 53 (no root?); musl getaddrinfo will not reach us")
                    }
                    continue
                }
                if (boundPort > 0) break
                runCatching {
                    val ss = ServerSocket(candidate, 8, loopback)
                    val us = DatagramSocket(InetSocketAddress(loopback, ss.localPort))
                    server = ss; datagram = us; boundPort = ss.localPort
                }
                if (boundPort <= 0) {
                    Log.w(TAG, "bind $candidate failed, trying next")
                }
            }

            if (boundPort <= 0 || server == null || datagram == null) {
                Log.w(TAG, "DNS proxy could not bind any port")
                stop()
                return 0
            }

            this.server = server
            this.udp = datagram
            this.port = boundPort
            running.set(true)

            val ssFinal = server!!
            val usFinal = datagram!!
            thread(name = "dns-tcp", isDaemon = true) { acceptLoop(ssFinal) }
            thread(name = "dns-udp", isDaemon = true) { udpLoop(usFinal) }

            Log.i(TAG, "DNS proxy listening on 127.0.0.1:$port (tcp+udp, muslUsable=${port == DNS_PORT})")
            port
        } catch (e: Exception) {
            Log.w(TAG, "DNS proxy start failed: ${e.message}")
            stop()
            0
        }
    }

    /**
     * 借 root 把 `ip_unprivileged_port_start` 调低到 [DNS_PORT]，让 App uid
     * 能绑 53。成功返回 true。
     *
     * 只在**有 root**（KernelSU / Magisk）的设备上生效；无 root 直接返回 false，
     * 让调用方退回高位端口。
     *
     * 注意这是**全局内核参数**，会影响整机。但它的语义只是「允许非特权进程绑定
     * 低端口」，不改任何行为；且 DNS 代理是常驻的，不存在改完就没人负责的情况。
     * 设备重启后自动恢复默认值，不需要额外清理。
     */
    private fun lowerUnprivilegedPortStart(ctx: android.content.Context): Boolean {
        val su = runCatching { Privilege.findSu() }.getOrNull() ?: return false
        val script = "echo $DNS_PORT > /proc/sys/net/ipv4/ip_unprivileged_port_start"
        return runCatching {
            val p = ProcessBuilder(su, "-c", script).redirectErrorStream(true).start()
            val ok = p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) p.destroyForcibly()
            // 尽力读回来核对。注意 untrusted_app 域**读不到**这个文件（返回 null），
            // 所以 readback 只能作为「附加信息」记录，绝不能当成功判据 ——
            // 真正判据是调用方随后能否 bind(53) 成功。
            val v = runCatching {
                java.io.File("/proc/sys/net/ipv4/ip_unprivileged_port_start")
                    .readText().trim().toIntOrNull()
            }.getOrNull()
            Log.i(TAG, "lowerUnprivilegedPortStart: exitOk=$ok readback=$v (readback null = no read permission, normal)")
            ok
        }.getOrElse {
            Log.w(TAG, "lowerUnprivilegedPortStart failed: ${it.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        runCatching { udp?.close() }
        server = null
        udp = null
        port = 0
    }

    /** 当前监听端口；0 表示未启动 */
    fun currentPort(): Int = if (running.get()) port else 0

    // ==================== 监听循环 ====================

    private fun acceptLoop(ss: ServerSocket) {
        Log.i(TAG, "tcp accept loop started on ${ss.localPort}")
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "tcp accept: ${e.message}")
                break
            }
            Log.d(TAG, "tcp accepted from ${sock.remoteSocketAddress}")
            thread(isDaemon = true) {
                try {
                    handleTcp(sock)
                } catch (e: Throwable) {
                    Log.w(TAG, "tcp handle failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    runCatching { sock.close() }
                }
            }
        }
        Log.i(TAG, "tcp accept loop exited")
    }

    private fun udpLoop(us: DatagramSocket) {
        Log.i(TAG, "udp loop started on ${us.localPort}")
        val buf = ByteArray(1500)
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                us.receive(pkt)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "udp recv: ${e.message}")
                break
            }
            Log.d(TAG, "udp got ${pkt.length}B from ${pkt.address}:${pkt.port}")
            val query = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            thread(isDaemon = true) {
                try {
                    val answer = forward(query)
                    if (answer == null) {
                        Log.w(TAG, "udp respond returned null for ${query.size}B query")
                        return@thread
                    }
                    us.send(DatagramPacket(answer, answer.size, pkt.address, pkt.port))
                    Log.d(TAG, "udp replied ${answer.size}B")
                } catch (e: Throwable) {
                    Log.w(TAG, "udp respond failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        Log.i(TAG, "udp loop exited")
    }

    // ==================== DNS 协议 ====================

    /**
     * TCP 与 UDP 共用：输入查询报文，输出回答报文。
     *
     * DNS over TCP 在报文前多一个 2 字节长度前缀，这里各自处理。
     */
    private fun handleTcp(sock: Socket) {
        sock.soTimeout = 5000
        val din = DataInputStream(sock.getInputStream().buffered())
        val len = din.readUnsignedShort()
        if (len <= 0 || len > 4096) return
        val query = ByteArray(len).also { din.readFully(it) }
        val answer = forward(query) ?: return
        val dout = DataOutputStream(sock.getOutputStream())
        dout.writeShort(answer.size)
        dout.write(answer)
        dout.flush()
    }

    /**
     * 解析查询并用 [InetAddress] 查地址，构造回答。
     *
     * 只支持单问题、A/AAAA/CNAME 类型 —— 见类注释的「局限」段。
     */
    /**
     * 把一条 DNS 查询转发给上游（TCP），返回上游的应答。
     *
     * 报文完全原样转发 —— 不解析、不改写、不重新合成。这样 MX / TXT / SRV /
     * CNAME 链 / DNSSEC 全都天然正确，不需要在这里维护任何 DNS 记录类型的知识。
     *
     * 走 TCP 的原因见类注释：容器只能发 UDP，而 Android 只放行出站 TCP 53。
     */
    private fun forward(query: ByteArray): ByteArray? {
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
        for (base in DOH_ENDPOINTS) {
            try {
                val got = dohGet(base + b64)
                if (got != null && got.size >= 12) return got
                Log.d(TAG, "doh $base returned short/empty")
            } catch (e: Throwable) {
                Log.w(TAG, "doh $base failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    /**
     * 一次 DoH GET（RFC 8484 的 GET 形式：`?dns=<base64url>`）。
     *
     * ## 为什么是 DoH 而不是普通 TCP 53
     *
     * 实测（App uid 10488）：
     * ```
     * TCP 连 223.5.5.5:53   → SocketException: Connection reset   ← 被拦
     * TCP 连 119.29.29.29:53→ SocketTimeoutException (4000ms)     ← 被拦
     * 同一个 uid 用 shell 身份测则全部 rc=0                       ← 说明是按 uid 拦的
     * HTTPS GET https://223.5.5.5/dns-query → 200, ancount=9      ← 通
     * ```
     * 即 Android 对**应用的出站 53 端口**做了重置，而 443 完全放行
     * （引擎自己就能正常联网，用的也是 443）。
     *
     * ## 证书
     *
     * 用 IP 直连 DoH 必然过不了证书校验（证书是签给域名的），所以这里显式
     * 全信任 + 跳过 hostname 校验。风险可控：只用于把用户自己的 DNS 查询发给
     * 固定的两家国内公共 DNS，不传输任何凭据；且报文本身就是明文 DNS 的内容，
     * 没有额外机密可泄露。
     */
    private fun dohGet(urlStr: String): ByteArray? {
        val u = URL(urlStr)
        val conn = u.openConnection() as HttpsURLConnection
        conn.connectTimeout = UPSTREAM_TIMEOUT_MS
        conn.readTimeout = UPSTREAM_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("accept", "application/dns-message")
        conn.sslSocketFactory = TRUST_ALL_FACTORY
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        try {
            if (conn.responseCode != 200) return null
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 把当前代理的地址写进容器的 `/etc/resolv.conf`。
     *
     * 只对 musl 用 —— glibc 走 `options use-vc`，不需要代理。
     * 返回 true 表示已写入。
     */
    fun writeMuslResolvConf(rootfs: File): Boolean {
        val p = start()
        if (p <= 0) return false
        val f = File(rootfs, "etc/resolv.conf")
        val want = "nameserver 127.0.0.1:$p\n"
        return runCatching {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) f.delete()
            if (!f.isFile || f.readText() != want) {
                f.parentFile?.mkdirs()
                f.writeText(want)
                Log.i(TAG, "wrote musl resolv.conf -> $want")
            }
            true
        }.getOrElse {
            Log.w(TAG, "write resolv.conf failed: ${it.message}")
            false
        }
    }
}

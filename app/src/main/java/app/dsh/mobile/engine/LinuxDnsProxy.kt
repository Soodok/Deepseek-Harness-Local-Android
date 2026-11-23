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
import java.util.concurrent.atomic.AtomicInteger
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
     * 诊断日志文件（`files/engine/dns.log`）。
     *
     * ★为什么必须有：这台设备上 `logcat` **取不到本 App 的任何一行日志**
     * （`logcat -b all -d | grep -c app.dsh.mobile` = 0，而代理自己的
     * `dns-tcp`/`dns-udp` 线程明明在跑）。代理的全部诊断都走 `android.util.Log`，
     * 于是「代理收到包了吗、上游 forward 成功了吗」这类关键事实完全不可见，
     * 只能在外围猜 —— 排查 DNS 问题时这是致命的。
     *
     * 落盘后才可在设备上直接 `cat files/engine/dns.log` 看到完整链路。
     * 只记**结论性事实**（绑定端口、每次 forward 的成败与耗时），不记查询内容
     * 之外的任何东西；文件按行追加、超过 64KB 自动截半，不会无限增长。
     */
    @Volatile
    private var logFile: java.io.File? = null

    /** 同时写 logcat 与文件。文件写失败不影响代理本身（诊断不能成为故障源）。 */
    private fun log(level: Char, msg: String) {
        when (level) {
            'i' -> Log.i(TAG, msg)
            'd' -> Log.d(TAG, msg)
            else -> Log.w(TAG, msg)
        }
        val f = logFile ?: return
        runCatching {
            synchronized(this) {
                if (f.length() > 64 * 1024) {
                    // 截半：保留最近一半，避免长期运行把用户磁盘占满
                    val keep = f.readLines().takeLast(200)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
                f.appendText("$level ${System.currentTimeMillis()} $msg\n")
            }
        }
    }

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
     * 看门狗探测用的保留域名。
     *
     * ★这个域名**永远不会出网**：udpLoop 认出它就直接回一个空应答（RCODE=0、
     * ANCOUNT=0）。这样探针只验证「监听循环还在收包」，与上游是否可达完全解耦
     * —— 断网时若拿真实域名探测，会把「没网」误判成「代理死了」并触发无意义的重绑。
     */
    private const val PROBE_NAME = "dsh-probe.invalid"

    /** 看门狗扫描间隔。20s 足够早地发现问题，又不会频繁占用一次 socket 往返。 */
    private const val WATCHDOG_INTERVAL_MS = 20_000L

    /** 探针单次等待上限。走过回环 + 一次本地应答，1s 都嫌多；给 3s 容忍调度抖动。 */
    private const val PROBE_TIMEOUT_MS = 3_000

    /**
     * 连续失败几次才判定死亡。
     *
     * 不取 1：系统在深度休眠/进程冻结边缘会让线程短暂不被调度，单次超时是噪声。
     * 不取 3+：用户点「安装」时链路已经是坏的了，多等一轮只让失败更难解释。
     */
    private const val PROBE_FAILS_TO_REBIND = 2

    /** 转发池的核心/最大线程数。见 forwardPool 的注释。 */
    private const val FORWARD_THREADS = 8

    /** 转发池排队上限。超出即丢弃（上游已经卡住，排队只会把故障放大）。 */
    private const val FORWARD_QUEUE = 32

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
     * 监听 socket 是否还活着。
     *
     * v1.7.13：真机发现进程 29384 一直报 running=true，但 15353 早已没人监听
     * （/proc/net 里 3BF9 计数 0、容器内 connect ECONNREFUSED）。旧判据
     * running.get() && port > 0 是**静态意图**，不是**当前事实**，socket 一旦被
     * 系统层悄无声息地关掉（引擎窗口切换、网络配置变更、进程冻结回收 fd），判据
     * 永远绿 → start() 幂等短路 → 装包/解析全部失败，而日志看不出任何异常。
     *
     * 现在由各监听循环的 catch 分支把它清掉：socket 死了 → alive=false →
     * 下一次 start() 重绑。
     */
    private val socketsAlive = AtomicBoolean(true)

    /**
     * 监听循环（dns-udp 线程）是否**实际在收包**。
     *
     * ★为什么 socketsAlive 不够：那个标志只在 receive/accept 抛异常时才被清掉。
     * 真机实测出现过「socket 保持 LISTEN、Recv-Q 涨到 2048、dns.log 一条新记录
     * 都没有」的状态 —— 收包线程已经不在了，但没有任何异常路径被走到，
     * socketsAlive 永远 true，currentPort() 不断把 15353 交给容器，于是
     * `apk update` / `wget` 全部报 temporary error，日志里毫无线索。
     *
     * 这里改成**主动探测**：看门狗周期性往自己的监听端口发一个保留域名的查询，
     * 只要 udpLoop 还在跑就必然会回一个空应答（见 PROBE_NAME）。收到应答才算
     * 「循环活着」——这是事实判据，不是意图判据。
     */
    private val loopAlive = AtomicBoolean(true)

    /** 上一次探测成功的时间戳（ms，0 = 还没探过）。用于让 [currentPort] 判断判据是否新鲜。 */
    private val lastProbeOkAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 连续探测失败次数，达到 [PROBE_FAILS_TO_REBIND] 才判定死亡并重绑。 */
    private val probeFails = AtomicInteger(0)

    /** 看门狗线程是否已启动，避免重复起线程。 */
    private val watchdogRunning = AtomicBoolean(false)

    /**
     * 最近一次 start() 拿到的 Context，供看门狗自愈重绑时使用。
     *
     * 只用于 `filesDir` 拼 dns.log 路径（[start] 内部），不持有任何 Activity
     * ——调用方传的都是 application context（见 DshApp / LinuxContainerRunner）。
     */
    @Volatile
    private var appCtx: android.content.Context? = null

    /**
     * 监听循环代际。
     *
     * ★每 start() 一次 +1，循环只在「自己仍是当前代」时才允许改动共享状态。
     *
     * 踩过的坑（真机日志实录）：看门狗自愈时 stop() → start() 是两步，中间旧线程
     * 还阻塞在 `us.receive()`。stop() 关掉 socket 后旧 receive 抛异常，而
     * `running` 此时已经被新的 start() 置回 true —— 旧线程的 catch 分支便会
     * `socketsAlive.set(false)`，把**刚建立好的新代理**判成死的：端口明明在听、
     * 日志刚写过 listening，currentPort() 却返回 0，容器拿不到 DSH_DNS_PORT，
     * 解析全挂。这种「自杀式污染」比原始 bug 更难查，因为它只在重绑瞬间出现。
     */
    private val generation = AtomicInteger(0)

    /**
     * 转发线程池。
     *
     * ★这是「代理跑几小时后哑掉」的直接根因，必须是有界池，不能每个查询起线程。
     *
     * 旧实现每条查询都 `thread(isDaemon = true) { forward(query) ... }`。上游 DoH
     * 不通时 `forward` 要串行走两个端点、每端点 connect/read 各 4s，单条最长 8s。
     * `apk update` / `wget` 这类工具一次就发几十条并发查询，几轮下来线程数可以
     * 堆到几百。Android 对单进程线程数有限制，一旦 `thread { }` 抛
     * OutOfMemoryError，异常是从 **udpLoop 的 while 里逃出去的**（当时的 try 只包住
     * receive），收包线程就此静默消失：
     *   - socket 仍开着 → netstat 看着还在 LISTEN
     *   - Recv-Q 一路涨到 2048（实测值）
     *   - dns.log 再无新行（日志写在 receive 之后）
     *   - socketsAlive 仍是 true → currentPort() 继续把 15353 交给容器
     *   - 容器内所有解析超时，报的却是 "temporary error (try again later)"
     * 只有重启 App 才恢复。这与真机现象逐条吻合。
     *
     * 池上限取 [FORWARD_THREADS]：DoH 单端点 4s、双端点 8s，8 并发足以覆盖
     * 正常安装的查询密度，又保证线程数恒定。队列满时**丢弃**而不是阻塞
     * —— 阻塞会让 udpLoop 不再 receive，症状与不修一样。
     */
    private val forwardPool: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            FORWARD_THREADS, FORWARD_THREADS,
            60L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(FORWARD_QUEUE),
            { r ->
                Thread(r, "dns-fwd").apply { isDaemon = true }
            },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    /**
     * 启动代理，返回**实际监听端口**（0 表示失败）。
     *
     * 幂等：已在跑就直接返回原端口。
     */
    @Synchronized
    fun start(ctx: android.content.Context? = null): Int {
        if (ctx != null) appCtx = ctx.applicationContext ?: ctx
        // 先挂上诊断文件（幂等；ctx 为 null 时保持 null，只走 logcat）
        if (ctx != null && logFile == null) {
            runCatching {
                val dir = java.io.File(ctx.filesDir, "engine").apply { mkdirs() }
                logFile = java.io.File(dir, "dns.log")
            }
        }
        // v1.7.13：幂等短路前先验 socket 是否真的活着（socketsAlive）。
        // 只看 running==true 会让「socket 已死、状态没死」的进程永远不再重绑
        // ——真机实测：dns.log 明明记录过 listening 15353，可 15353 早已 ECONNREFUSED，
        // start() 还在直接返回 port。healthCheck() 无副作用（只是 state 查询），
        // 放在幂等判据里代价可以忽略。
        if (running.get() && port > 0) {
            if (socketsAlive.get() && loopAlive.get()) return port
            log(
                'w',
                "listener is dead (running=$running, port=$port, socketsAlive=${socketsAlive.get()}, " +
                    "loopAlive=${loopAlive.get()}) — rebinding",
            )
            stop()
        }
        log('i', "start() called; ctx=${ctx != null} preferPort=$PREFERRED_PORT")
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
                        log('w', "cannot bind 53 (no root?); musl getaddrinfo will not reach us")
                    }
                    continue
                }
                if (boundPort > 0) break
                // v1.7.13：拆开 TCP/UDP 两次绑定并**记下具体异常**。
                // 旧代码两条 bind 放同一个 runCatching，任何一条抛错整组静默吞掉，
                // 日志里只看到 start() called 然后直接跳到 cannot bind 53 ——
                // 15353 到底为什么绑不上完全不可见（真机本轮实测就是这种哑巴失败）。
                // 拆开后单独失败也能记录原因；TCP 失败但 UDP 成功时仍用 UDP 端口
                // （musl 走 UDP 改写，TCP 只是 curl 备路）。
                var udpPort = -1
                var tcpPort = -1
                try {
                    // ★固定端口抢不到时要**重试同一个端口**，不能立刻换下一个候选。
                    //
                    // 真机实测：看门狗自愈时刚 stop() 关掉旧 socket，紧接着重绑 15353
                    // 会拿到 EADDRINUSE（内核还没完全释放），于是循环落到候选 53 ——
                    // 端口从 15353 变成 53。容器 env（DSH_DNS_PORT）与 rootfs 的
                    // resolv.conf 都是按「固定端口」写死的，端口漂移等于把这条契约
                    // 悄悄作废，排查时完全看不出。这里对固定端口重试几次，
                    // 让它有机会等内核释放。
                    if (candidate == PREFERRED_PORT) {
                        var attempt = 0
                        while (attempt < 5) {
                            attempt++
                            val us = DatagramSocket(null as java.net.SocketAddress?)
                            runCatching { us.reuseAddress = true }
                            try {
                                us.bind(InetSocketAddress(loopback, PREFERRED_PORT))
                                udpPort = us.localPort
                                try {
                                    val ss = ServerSocket()
                                    runCatching { ss.reuseAddress = true }
                                    ss.bind(InetSocketAddress(loopback, PREFERRED_PORT), 8)
                                    tcpPort = ss.localPort
                                    server = ss
                                } catch (te: Exception) {
                                    log('w', "tcp bind $PREFERRED_PORT failed: " + te.javaClass.simpleName + ": " + te.message)
                                    us.close()
                                    udpPort = -1
                                }
                                if (tcpPort > 0) {
                                    datagram = us
                                    boundPort = tcpPort
                                } else if (udpPort > 0) {
                                    datagram = us
                                    boundPort = udpPort
                                    log('w', "udp-only bind on $PREFERRED_PORT (tcp refused; musl still works)")
                                }
                                if (boundPort > 0) break
                            } catch (ue: Exception) {
                                runCatching { us.close() }
                                if (attempt == 5) {
                                    log('w', "udp bind $PREFERRED_PORT failed after $attempt tries: " + ue.javaClass.simpleName + ": " + ue.message)
                                } else {
                                    Thread.sleep(150)
                                }
                            }
                        }
                        if (boundPort <= 0) {
                            Log.w(TAG, "bind $candidate failed, trying next")
                        }
                        continue
                    }
                    val us = DatagramSocket(InetSocketAddress(loopback, candidate))
                    udpPort = us.localPort
                    try {
                        val ss = ServerSocket(candidate, 8, loopback)
                        tcpPort = ss.localPort
                        server = ss
                    } catch (te: Exception) {
                        log('w', "tcp bind " + candidate + " failed: " + te.javaClass.simpleName + ": " + te.message)
                        us.close()
                    }
                    if (tcpPort > 0) {
                        datagram = us
                        boundPort = tcpPort
                    } else if (candidate == 0) {
                        us.close()
                    } else if (udpPort > 0) {
                        datagram = us
                        boundPort = udpPort
                        log('w', "udp-only bind on " + candidate + " (tcp refused; musl still works)")
                    }
                } catch (ue: Exception) {
                    log('w', "udp bind " + candidate + " failed: " + ue.javaClass.simpleName + ": " + ue.message)
                }
                if (boundPort <= 0) {
                    Log.w(TAG, "bind " + candidate + " failed, trying next")
                }
            }

            if (boundPort <= 0 || server == null || datagram == null) {
                log('w', "could not bind any port")
                stop()
                return 0
            }

            this.server = server
            this.udp = datagram
            this.port = boundPort
            running.set(true)
            socketsAlive.set(true)
            loopAlive.set(true)
            probeFails.set(0)
            lastProbeOkAt.set(0)

            val ssFinal = server!!
            val usFinal = datagram!!
            val myGen = generation.incrementAndGet()
            thread(name = "dns-tcp", isDaemon = true) { acceptLoop(ssFinal, myGen) }
            thread(name = "dns-udp", isDaemon = true) { udpLoop(usFinal, myGen) }

            log('i', "listening 127.0.0.1:$port (tcp+udp, muslUsable=${port == DNS_PORT})")
            startWatchdog()
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
        socketsAlive.set(false)
        loopAlive.set(false)
        runCatching { server?.close() }
        runCatching { udp?.close() }
        server = null
        udp = null
        port = 0
    }

    /**
     * 当前**可用**的监听端口；0 表示没有可用代理。
     *
     * v1.7.13：判据从 running 改成 running && socketsAlive。
     * 只判 running 会让调用方（[LinuxContainerRunner] 注入 DSH_DNS_PORT、
     * [LinuxEngineLauncher] 拼容器 env）把一个**已经死掉的端口**交给容器，
     * 于是 loader 把 53 改写到那个死端口、容器内所有解析一律超时，
     * 而所有日志都显示代理「在跑」。返回 0 会让调用方不带该变量、
     * 或触发一次 [start] 重绑。
     */
    fun currentPort(): Int = if (running.get() && socketsAlive.get() && loopAlive.get()) port else 0

    // ==================== 看门狗 ====================

    /**
     * 周期性自检：往自己的监听端口发一个保留域名查询，确认 udpLoop 真的在收包。
     *
     * 一旦连续 PROBE_FAILS_TO_REBIND 次拿不到应答，就把 loopAlive 置 false
     * 并主动 stop()，让下一次 start()（LinuxContainerRunner 每次执行前都会调
     * 一次）重新绑定。这样即便收包线程在完全没有异常的情况下消失，链路也能在
     * 20~40 秒内自愈，而不是等用户杀掉 App。
     *
     * 线程本身是 daemon 且全局只起一份（watchdogRunning 抱着），
     * 随进程存活，不额外持有 Context。
     */
    private fun startWatchdog() {
        if (!watchdogRunning.compareAndSet(false, true)) return
        thread(name = "dns-watchdog", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!running.get()) {
                    // 已经停了：让位给下一次 start() 重新拉起点火。
                    watchdogRunning.set(false)
                    return@thread
                }
                if (probeOnce()) {
                    probeFails.set(0)
                    lastProbeOkAt.set(System.currentTimeMillis())
                    if (!loopAlive.get()) {
                        // 之前误判过（比如系统冻结后又恢复），把标志扶正。
                        loopAlive.set(true)
                        log('i', "watchdog: loop recovered")
                    }
                } else {
                    val n = probeFails.incrementAndGet()
                    log('w', "watchdog: probe failed ($n/$PROBE_FAILS_TO_REBIND)")
                    if (n >= PROBE_FAILS_TO_REBIND) {
                        log('w', "watchdog: udp loop is unresponsive - rebinding listener now")
                        probeFails.set(0)
                        // 自己就地重绑，而不是只把 socket 丢掉等下一次外部调用。
                        //
                        // ★为什么不能只 stop()：真机实测过 —— stop() 之后端口确实没了，
                        // 但**没有任何东西会主动把它拉起来**。LinuxContainerRunner /
                        // LinuxPackageManager 只在「用户发起容器操作」时才调 start()，
                        // 于是代理一直缺席，容器内所有解析照旧超时，用户看到的仍然是
                        // 那个 "temporary error (try again later)"。自愈必须是自驱的。
                        //
                        // stop() 清掉 running/socketsAlive/port 并关闭两个 socket，
                        // 紧接着 start() 会走完整绑定流程并拉起新的两条监听循环。
                        loopAlive.set(false)
                        stop()
                        val fresh = runCatching { start(appCtx) }.getOrDefault(0)
                        // 判据用 >0 而不是 >1023：53 是本代理的**合法**绑定结果
                        // （有 root 时能绑，muslUsable=true，比 15353 更好）。
                        if (fresh > 0) {
                            log('i', "watchdog: rebind OK on port $fresh")
                        } else {
                            log('w', "watchdog: rebind failed (start() returned $fresh); will retry next tick")
                        }
                    }
                }
            }
        }
    }

    /**
     * 发一个探针查询并等应答。true = 监听循环确实在收包。
     *
     * 只走 UDP 回环，不碰上游；共用 PROBE_TIMEOUT_MS 作为总预算。
     */
    private fun probeOnce(): Boolean {
        val p = port
        if (p <= 0) return false
        return runCatching {
            java.net.DatagramSocket().use { s ->
                s.soTimeout = PROBE_TIMEOUT_MS
                val q = buildProbeQuery()
                s.send(java.net.DatagramPacket(q, q.size, java.net.InetAddress.getByName("127.0.0.1"), p))
                val rbuf = ByteArray(512)
                val rp = java.net.DatagramPacket(rbuf, rbuf.size)
                s.receive(rp)
                // 只要收到任何应答就说明循环活着；内容正确性由 isProbeQuery 的
                // 回显逻辑保证，这里不必再解析（避免把解析 bug 变成「代理假死」）。
                rp.length >= 12
            }
        }.getOrDefault(false)
    }

    /** 构造一次 PROBE_NAME 的 A 记录查询（标准 12 字节头 + QNAME + QTYPE/QCLASS）。 */
    private fun buildProbeQuery(): ByteArray {
        // ID 用时间低 16 位，避免与真实查询混在一起（回环内不影响正确性，只便于抓包对照）。
        val id = (System.currentTimeMillis() and 0xFFFF).toInt()
        val out = java.io.ByteArrayOutputStream()
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // RD = 1
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        out.write(0x00); out.write(0x00) // ANCOUNT
        out.write(0x00); out.write(0x00) // NSCOUNT
        out.write(0x00); out.write(0x00) // ARCOUNT
        for (label in PROBE_NAME.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00)                   // QNAME 结束
        out.write(0x00); out.write(0x01)  // QTYPE = A
        out.write(0x00); out.write(0x01)  // QCLASS = IN
        return out.toByteArray()
    }

    /**
     * 若是看门狗探针，返回可直接发出的应答；否则返回 null（继续走正常转发）。
     *
     * ★应答必须**回显 question 段**（12 字节头 + 原样 QNAME/QTYPE/QCLASS）。
     * 第一版只回 12 字节头的空应答，nslookup 直接报
     * ```
     * *** Can't find dsh-probe.invalid: Parse error
     * ```
     * 因为 RFC 1035 要求应答的 QDCOUNT 与实际携带的 question 段一致，只留包头
     * 会被解析器判为畸形报文。探针本身只关心「收到任何 12 字节以上的应答」，
     * 但客户端（或将来调试时用 nslookup 手测）不该看到 Parse error。
     */
    private fun buildProbeReply(q: ByteArray): ByteArray? {
        if (q.size < 13) return null
        val (qEnd, labels) = parseQname(q, 12) ?: return null
        if (labels.size < 2) return null
        if (!labels.joinToString(".").equals(PROBE_NAME, ignoreCase = true)) return null
        // question 段 = QNAME + 4 字节（QTYPE/QCLASS），应答原样带回去。
        val qEndFull = minOf(q.size, qEnd + 4)
        val out = q.copyOfRange(0, qEndFull)
        out[2] = (out[2].toInt() or 0x80).toByte()  // QR = response
        out[3] = (out[3].toInt() and 0x0F).toByte() // RCODE = NOERROR
        out[6] = 0; out[7] = 0                      // ANCOUNT = 0
        out[8] = 0; out[9] = 0                      // NSCOUNT = 0
        out[10] = 0; out[11] = 0                    // ARCOUNT = 0
        return out
    }

    /** 解析 QNAME，返回 (下一段起始偏移, 标签列表)；畸形报文返回 null。 */
    private fun parseQname(q: ByteArray, start: Int): Pair<Int, List<String>>? {
        var i = start
        val labels = ArrayList<String>(4)
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) return (i + 1) to labels
            if (len > 63 || i + 1 + len > q.size) return null
            labels.add(String(q, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return null
    }

    // ==================== 监听循环 ====================

    private fun acceptLoop(ss: ServerSocket, myGen: Int) {
        log('i', "tcp accept loop started on ${ss.localPort} (gen=$myGen)")
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (e: Throwable) {
                // 同 udpLoop：必须捕 Throwable，否则 Error 会静默杀死监听线程。
                if (running.get()) {
                    Log.w(TAG, "tcp accept: ${e.message}")
                    log('w', "tcp accept loop died: ${e.javaClass.simpleName}: ${e.message}")
                    // v1.7.13：accept 抛错意味着 server socket 已死（正常停机是
                    // running=false，会走 stop() 并把 socketsAlive 清掉；这里
                    // running 还是 true 却异常，只能说明 socket 被系统层关了）。
                    // 不标记的话 currentPort() 会继续谎报端口在听。
                    //
                    // ★代际保护：只有「自己还是当前代」才允许改共享标志。
                    // 否则 stop()→start() 期间被关闭的旧线程会污染新代理（见 generation 注释）。
                    if (generation.get() == myGen) socketsAlive.set(false)
                }
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

    private fun udpLoop(us: DatagramSocket, myGen: Int) {
        log('i', "udp loop started on ${us.localPort} (gen=$myGen)")
        val buf = ByteArray(1500)
        var exitReason = "running=false"
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                us.receive(pkt)
            } catch (e: Throwable) {
                // ★这里必须捕 Throwable，不能只捕 Exception。
                //
                // 真机实测过「socket 一直在 LISTEN、Recv-Q 涨到 2048、代理日志
                // 一条新记录都没有」的状态：若此处只捕 Exception，任何 Throwable
                // （线程被中断后 receive 抛的异常链、底层 OOM 的 OutOfMemoryError、
                // 甚至 JNI 层的 Error）都会**直接逃出 while**，线程静默消失，
                // 而 socket 仍开着 —— netstat 看着正常，socketsAlive 仍是 true，
                // currentPort() 继续谎报 15353，loader 照旧把 DNS 改写到这个
                // 没人收包的端口，容器里所有解析一律超时，且日志零线索。
                exitReason = if (running.get()) {
                    "${e.javaClass.simpleName}: ${e.message}"
                } else {
                    "running=false"
                }
                if (running.get()) {
                    Log.w(TAG, "udp recv: ${e.message}")
                    // v1.7.13：同 acceptLoop —— running=true 却 receive 抛错，
                    // 说明 UDP socket 已被系统层关掉；不标记的话 start() 会
                    // 永远幂等短路，musl 侧表现为 nslookup 超时/ECONNREFUSED。
                    //
                    // ★代际保护：只有「自己还是当前代」才允许改共享标志。
                    if (generation.get() == myGen) socketsAlive.set(false)
                    log('w', "udp loop died: $exitReason — listener will be rebuilt on next start()")
                }
                break
            }
            val query = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            // 看门狗探针：本地直接回空应答，绝不转发，也不写进 udp got 统计。
            // 见 PROBE_NAME 注释（只在回环内往返，与上游可达性完全解耦）。
            val probeReply = buildProbeReply(query)
            if (probeReply != null) {
                runCatching {
                    us.send(DatagramPacket(probeReply, probeReply.size, pkt.address, pkt.port))
                }
                continue
            }
            log('d', "udp got ${pkt.length}B from ${pkt.address}:${pkt.port}")
            // ★必须走有界池，不能每条查询起一个线程。理由见 forwardPool 的注释：
            // 线程爆炸 → thread{} 抛 OutOfMemoryError → 逃出 udpLoop 的 while →
            // 收包线程静默消失（socket 还在、Recv-Q 堆积、日志停摆）。
            runCatching {
                forwardPool.execute {
                    try {
                        val answer = forward(query)
                        if (answer == null) {
                            log('w', "udp forward returned null for ${query.size}B query")
                            return@execute
                        }
                        us.send(DatagramPacket(answer, answer.size, pkt.address, pkt.port))
                        log('d', "udp replied ${answer.size}B")
                    } catch (e: Throwable) {
                        log('w', "udp respond failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }.onFailure {
                // 提交本身失败（池已 shutdown）——不能让它逃出去杀死收包循环。
                log('w', "udp forward submit failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        // 日志必须带代际号：stop()→start() 时旧循环与新循环的日志会交错，
        // 没有 gen 就分不清「旧代正常退出」和「新循环秒退」——前者无害、
        // 后者是致命故障，看日志的人无法判断，排查方向会被彻底带偏。
        val genTag = if (generation.get() == myGen) "current" else "stale"
        Log.i(TAG, "udp loop exited (gen=$myGen $genTag, $exitReason)")
        log('w', "udp loop exited (gen=$myGen $genTag, $exitReason)")
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
        val t0 = System.currentTimeMillis()
        for (base in DOH_ENDPOINTS) {
            try {
                val got = dohGet(base + b64)
                val ms = System.currentTimeMillis() - t0
                if (got != null && got.size >= 12) {
                    log('d', "forward OK via ${base.substringBefore("/dns")} ${got.size}B in ${ms}ms")
                    return got
                }
                log('w', "doh ${base.substringBefore("/dns")} short/empty in ${ms}ms")
            } catch (e: Throwable) {
                log('w', "doh ${base.substringBefore("/dns")} failed in " +
                    "${System.currentTimeMillis() - t0}ms: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        log('w', "forward FAILED both endpoints, total ${System.currentTimeMillis() - t0}ms")
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

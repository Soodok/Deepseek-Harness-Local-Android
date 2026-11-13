package app.dsh.mobile.engine

/**
 * 挑「别的设备能连进来的那个」本机 IPv4 地址（v1.7.1，v1.7.12 修虚拟网卡抢占）。
 *
 * ## 为什么需要单独一个工具
 *
 * 旧实现（[LanWebProxy.lanAddress]）是一行 `firstOrNull { !isLoopbackAddress }` ——
 * 取系统枚举到的第一个非环回 IPv4。`NetworkInterface.getNetworkInterfaces()`
 * 的枚举顺序**不保证**，所以取第一个就是在赌运气。
 *
 * 两个用户实测过的错法：
 *
 * ```
 * # 错法一（v1.7.1 之前）：开热点时取到移动网络
 * rmnet_data0   10.123.45.67     ← 移动网络，别人连不到
 * wlan1         192.168.43.1     ← 热点 AP，别人要连的是这个
 *
 * # 错法二（v1.7.12，按 score 排序后仍有并列）：取到虚拟网关
 * vgate0        172.30.250.61/32 ← 虚拟网关，别人连不到
 * wlan0         192.168.101.21/24 ← 真正的局域网入口
 * ```
 *
 * 错法二的根因不是「没排序」，而是**并列时按接口名字典序**决定：两个地址都落在
 * 172.16/12 与 192.168/16 这两个私有网段里，都拿 50 分，而 `v` < `w`，
 * 虚拟网关就赢了。所以 v1.7.12 除了补排斥名单，还补了两条**结构性判据**：
 *
 * · **广播地址**：真局域网口有广播地址（`/24`），`/32` 的隧道口没有。
 * · **前缀长度**：`/31`、`/32` 是点对点/主机路由，不可能是「别人连进来」的入口。
 *
 * 这两条跟接口名叫什么无关，所以厂商把虚拟网卡改名也挡得住。
 *
 * ## 判定策略（按 score 降序，score 相同再按有广播、接口名）
 *
 * 1. **排除**移动数据与虚拟/隧道接口（见 [REJECT_PREFIXES]）—— 名字命中直接丢弃。
 * 2. **优先热点/有线类**（`ap*` / `swlan*` / `wlan1` / `eth*`）—— 热点 AP 在这些上。
 * 3. `wlan0` 单独给高分：热点关闭时它就是唯一的局域网入口。
 * 4. 其余按「私有网段 + 有广播地址」判，隧道口（无广播 / `/32`）沉到最低档。
 *
 * ## 为什么不直接用 ConnectivityManager
 *
 * `ConnectivityManager.getLinkProperties()` 能给到更权威的接口归属，但：
 * · 热点 AP 在 `TetheringManager`（API 30+ 且需要 `TETHER_PRIVILEGED` 权限）才看得到；
 * · 拿不到时还要回落，等于两套逻辑。
 * 而接口名的规律在 Android 各版本上足够稳定，[scoreOf] 又是纯函数（可在 JVM 上断言），
 * 比引权限更实用。
 */
object LanAddress {

    /**
     * 移动网络与虚拟/隧道接口前缀。出现在这些接口上的地址**外部设备一定连不到**。
     *
     * · `rmnet*` / `ccmni*` / `pdp*` —— Qualcomm / 联发科 / 三星系数据网络
     * · `clat` —— IPv4-over-IPv6 兼容层
     * · `p2p*` —— WiFi Direct，不是普通局域网
     * · `dummy` —— 系统占位口
     */
    private val REJECT_MOBILE = listOf("rmnet", "ccmni", "pdp", "p2p", "clat", "dummy")

    /**
     * 虚拟 / 隧道 / 容器接口（v1.7.12 新增）。
     *
     * `vgate` 是这次用户报的 bug 的直接来源：它拿 172.30.250.61/32，落在
     * 172.16/12 私有网段里，旧评分给 50 分，与 `wlan0` 并列后靠字典序胜出。
     * `tun` / `tap` / `ppp` / `vti` 是各类 VPN，`ovnet` 是厂商虚拟网，
     * `ifb` 是流量整形用的中间口，`gre` / `erspan` / `sit` 是隧道，
     * `wifi-aware` 是 NAN 通道，`r_rmnet` 是双卡的数据网络别名。
     */
    private val REJECT_VIRTUAL = listOf(
        "vgate", "tun", "tap", "ppp", "vti", "veth", "ovnet", "ifb",
        "gre", "erspan", "sit", "ip6", "wifi-aware", "r_rmnet", "br-", "docker",
    )

    /** 热点 AP / 有线：最可能是「别人连进来」的入口。数字越小越优先。 */
    private val PREFER_PREFIXES = listOf("ap", "swlan", "wlan1", "eth")

    /** 私有网段（只有这些才可能被局域网设备访问到）。 */
    private fun isPrivateIPv4(host: String): Boolean {
        val p = host.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return when (a) {
            10 -> true
            192 -> b == 168
            172 -> b in 16..31
            else -> false
        }
    }

    /**
     * 一个候选地址及其所属接口名。
     *
     * [score] 越高越该被显示；[hasBroadcast] 与 [prefixLen] 是 v1.7.12 补的结构判据
     * （隧道口没有广播地址、前缀是 /31 或 /32）。
     */
    data class Candidate(
        val address: String,
        val iface: String,
        val score: Int,
        val hasBroadcast: Boolean = false,
        val prefixLen: Int = 24,
    )

    /**
     * 给单个接口地址打分。**纯函数、不做 IO**，因此可以在宿主 JVM 上直接断言
     * （真机上 `vgate0` 与 `wlan0` 并存的那种组合，本机重现不出来）。
     *
     * @return `null` 表示这个接口根本不该作为候选（名字命中排斥名单）
     */
    fun scoreOf(name: String, host: String, hasBroadcast: Boolean, prefixLen: Int): Int? {
        if (REJECT_MOBILE.any { name.startsWith(it) }) return null
        if (REJECT_VIRTUAL.any { name.startsWith(it) }) return null

        // 点对点 / 主机路由不可能是「别人连进来」的入口：/31、/32 一律沉底。
        val pointToPoint = prefixLen >= 31 && !hasBroadcast
        val isPrivate = isPrivateIPv4(host)

        // 局域网入口必须是私有网段：公网地址（连了公司网/VPN 出口之类）不该被当成
        // 「同网段设备用这个 IP 连进来」。这条先于接口名判，所以名字再好听也不行。
        if (!isPrivate) return if (pointToPoint) 5 else 10

        if (pointToPoint) return 20

        val prefer = PREFER_PREFIXES.indexOfFirst { name.startsWith(it) }
        if (prefer >= 0) return 100 - prefer
        // 热点关闭时 wlan0 就是局域网入口：给高分，但仍低于真正的热点 AP 口。
        if (name.startsWith("wlan")) return 90
        return 60
    }

    /**
     * 候选排序（**纯函数**，同样可在 JVM 上断言）。
     *
     * 顺序：score 降序 → 有广播地址的在前 → 接口名字典序（保证结果稳定）。
     * 第二级是 v1.7.12 补的：万一两个接口拿到同一个 score，有广播的那个才是真局域网口。
     */
    fun order(candidates: List<Candidate>): List<Candidate> =
        candidates.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenByDescending { it.hasBroadcast }
                .thenBy { it.iface },
        )

    /** 全部可用候选，已按「越该被显示」排好序。 */
    fun candidates(): List<Candidate> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                val name = nif.name ?: ""
                nif.interfaceAddresses.mapNotNull { ia ->
                    val addr = ia.address as? java.net.Inet4Address ?: return@mapNotNull null
                    if (addr.isLoopbackAddress) return@mapNotNull null
                    val host = addr.hostAddress ?: return@mapNotNull null
                    // 用 interfaceAddresses 而不是 inetAddresses：只有前者能拿到广播地址与
                    // 前缀长度，这两个是 v1.7.12 区分真局域网口与 /32 隧道口的关键。
                    val prefix = ia.networkPrefixLength.toInt()
                    val broadcast = ia.broadcast != null
                    val score = scoreOf(name, host, broadcast, prefix) ?: return@mapNotNull null
                    Candidate(host, name, score, broadcast, prefix)
                }
            }
            .let { order(it) }
    }.getOrElse { emptyList() }

    /**
     * 最该被显示给用户的那一个地址。
     *
     * @return 没有可用地址时返回 `null`（未联网 / 只有移动网络）
     */
    fun best(): String? = candidates().firstOrNull()?.address

    /** 从一组候选里挑最好的一个（纯函数，供断言与多网卡 UI 复用）。 */
    fun bestOf(candidates: List<Candidate>): String? = order(candidates).firstOrNull()?.address

    /** 全部候选地址（去重，保持优先级顺序）。 */
    fun all(): List<String> = candidates().map { it.address }.distinct()

    /**
     * 给用户看的「地址 → 接口名」清单，用于 UI 在多网卡时让用户自己选。
     */
    fun describe(): List<String> = candidates().map { "${it.address} (${it.iface})" }
}
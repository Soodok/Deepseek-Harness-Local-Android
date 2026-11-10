package app.dsh.mobile.engine

/**
 * 挑「别的设备能连进来的那个」本机 IPv4 地址（v1.7.1）。
 *
 * ## 为什么需要单独一个工具
 *
 * 旧实现（[LanWebProxy.lanAddress]）是一行 `firstOrNull { !isLoopbackAddress }` ——
 * 取系统枚举到的第一个非环回 IPv4。单网卡下没问题，**开热点时必错**：
 *
 * ```
 * rmnet_data0   10.123.45.67     ← 移动网络，用户报的「网址是移动网络网址」
 * wlan1         192.168.43.1     ← 热点 AP，别人要连的是这个
 * ```
 *
 * `NetworkInterface.getNetworkInterfaces()` 的枚举顺序**不保证**，所以取第一个
 * 就是在赌运气。用户实测：WebUI 显示的地址是移动网络 IP，热点里其它设备连不上。
 *
 * ## 判定策略（按顺序，先强后弱）
 *
 * 1. **排除移动网络接口**（`rmnet*` / `ccmni*` / `pdp*` / `p2p*`）—— 这些地址
 *    外部设备永远连不到，是纯粹的坏候选。
 * 2. **优先热点/以太网类接口**（`ap*` / `swlan*` / `wlan1` / `eth*`）——
 *    热点 AP 在这些接口上。
 * 3. 再看是否**私有网段**（192.168/16、10/8、172.16/12）—— 公网地址不可能是
 *    局域网访问入口。
 * 4. 最后按「越像 WiFi 越靠前」排（`wlan0` 在热点关闭时就是局域网的入口）。
 *
 * ## 为什么不直接用 ConnectivityManager
 *
 * `ConnectivityManager.getLinkProperties()` 能给到更权威的接口归属，但：
 * · 热点 AP 在 `TetheringManager`（API 30+ 且需要 `TETHER_PRIVILEGED` 权限）才看得到；
 * · 拿不到时还要回落，等于两套逻辑。
 * 而接口名的规律在 Android 各版本上足够稳定（厂商定制会改，但都是「wlan/ap/swlan」
 * 这一族），所以先用名字 + 网段筛，比引权限更实用。
 */
object LanAddress {

    /**
     * 移动网络接口前缀。出现在这些接口上的地址**外部设备一定连不到**。
     *
     * · `rmnet*` —— Qualcomm 系数据网络（最常见）
     * · `ccmni*` —— 联发科系
     * · `pdp*`   —— 三星/老机型
     * · `p2p*`   —— WiFi Direct，不是普通局域网
     */
    private val REJECT_PREFIXES = listOf("rmnet", "ccmni", "pdp", "p2p", "clat", "dummy")

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

    /** 一个候选地址及其所属接口名。 */
    data class Candidate(val address: String, val iface: String, val score: Int)

    /**
     * 全部可用候选，按「越该被显示」排前面。
     *
     * 排序键（依次比较，全相等时保持枚举顺序以保证结果稳定）：
     * 1. 是否命中热点/有线前缀（命中得分更高）
     * 2. 是否私有网段
     * 3. 接口名（`wlan0` 优先于 `wlan9` 这种，纯字典序，够用）
     */
    fun candidates(): List<Candidate> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                val name = nif.name ?: ""
                nif.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .filterNot { it.isLoopbackAddress }
                    .mapNotNull { addr ->
                        val host = addr.hostAddress ?: return@mapNotNull null
                        // 排除移动网络等外网不可达接口
                        if (REJECT_PREFIXES.any { name.startsWith(it) }) return@mapNotNull null
                        val prefer = PREFER_PREFIXES.indexOfFirst { name.startsWith(it) }
                        val score = when {
                            prefer >= 0 -> 100 - prefer
                            isPrivateIPv4(host) -> 50
                            else -> 10
                        }
                        Candidate(host, name, score)
                    }
            }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.iface })
    }.getOrElse { emptyList() }

    /**
     * 最该被显示给用户的那一个地址。
     *
     * @return 没有可用地址时返回 `null`（未联网 / 只有移动网络）
     */
    fun best(): String? = candidates().firstOrNull()?.address

    /** 全部候选地址（去重，保持优先级顺序）。 */
    fun all(): List<String> = candidates().map { it.address }.distinct()

    /**
     * 给用户看的「地址 → 接口名」清单，用于 UI 在多网卡时让用户自己选。
     */
    fun describe(): List<String> = candidates().map { "${it.address} (${it.iface})" }
}

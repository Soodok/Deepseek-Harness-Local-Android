package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 局域网远程访问手机能力的配对与鉴权（v1.7.1）。
 *
 * ## 为什么需要这一层，而不是直接用 [AgentBridge] 现有的 token
 *
 * 桥现有的 token（`AgentBridge.currentToken()`）是**每次进程启动随机生成、只写进
 * 引擎子进程环境变量**的一次性凭据。它解决的威胁是「同设备上其它 App 偷调无障碍
 * 能力」，所以设计上**刻意无法从外部取得** —— 这正是局域网访问做不到的原因：
 * PC 上的调用方既不是引擎子进程，也没有 root 去读 `/proc/<pid>/environ`。
 *
 * 所以「远程访问」需要一套不同性质的凭据：**可持久、可配对、可撤销、每人一份**。
 *
 * ## 两套凭据并存，各管各的（这一点很关键）
 *
 * | 来源 | 认什么 | 理由 |
 * |---|---|---|
 * | 环回（127.0.0.1） | 引擎 token **或** 配对密钥 | 容器与宿主共用网络命名空间，容器内程序走的也是环回；引擎 token 继续有效保证既有链路零破坏 |
 * | 非环回（局域网） | **只认配对密钥**，引擎 token 一律拒 | 引擎 token 会随环境变量泄露给容器内任意进程；它不该成为「从 LAN 进入」的通行证 |
 *
 * 如果两个方向都只认配对密钥，那么容器里跑的任何东西都能拿到它（环境变量里就有一份），
 * 局域网防线就白设了。反过来如果 LAN 也认引擎 token，那任何能看到进程环境的本地
 * 恶意程序就能从外部进来。所以必须按来源分开。
 *
 * ## 密钥形态
 *
 * · 设备级密钥：32 字节随机 → 64 位十六进制。一次性生成，落盘 0600，用户可重置。
 * · 配对码：6 位数字，**只在「配对窗口」内有效**（默认 5 分钟）。用户点开配对界面
 *   才生成，离开即失效。用它来换取设备级密钥 —— 这样把「看图输码」的弱凭据和
 *   「长期可用」的强凭据分开，码泄露的影响面被时间窗口限制住。
 *
 * 为什么配对码只有 6 位还敢用：它只在同网段可见（3091 只接受私网来源），
 * 且有 5 分钟窗口 + 每 IP 尝试次数限制（见 [RemoteAccessServer]）。真要暴力破解
 * 需要先能连上端口、再在窗口内试 100 万次，代价远超收益。
 *
 * ## 为什么不用固定的长期明文密码
 *
 * LAN 代理（[LanWebProxy]）用的是用户自设密码，因为那是**人肉在浏览器里敲**。
 * 而这里调用方是脚本/AI，密码会明文写进配置文件四处流动，一旦泄露无法察觉。
 * 配对密钥 + 可撤销列表至少能回答「谁在用我的手机」这个问题。
 */
object RemoteAccess {

    private const val TAG = "RemoteAccess"

    private const val PREFS = "remote_access"

    /** 总开关（默认关）。关闭时 [AgentBridge] 不绑 LAN、[RemoteAccessServer] 不监听。 */
    private const val KEY_ENABLED = "enabled"

    /** 设备级密钥（六十四位十六进制）。null 表示还没生成。 */
    private const val KEY_SECRET = "secret"

    /** 已配对设备的记录（JSON 数组的字符串形式，见 [pairs]）。 */
    private const val KEY_PAIRS = "pairs"

    /** 配对窗口的截止时刻（`SystemClock.elapsedRealtime()`；0 = 窗口关闭）。 */
    private const val KEY_WINDOW_UNTIL = "window_until"

    /** 当前配对码（仅在窗口开启期间有值）。 */
    private const val KEY_PAIR_CODE = "pair_code"

    /** 配对窗口时长：5 分钟。够用户走到电脑前输码，又不至于长期敞着。 */
    const val PAIR_WINDOW_MS = 5 * 60 * 1000L

    /** 已配对设备上限。超出后必须先在 App 里撤销一个 —— 不做静默淘汰。 */
    const val MAX_PAIRS = 8

    /** 一次配对记录。 */
    data class Pair(
        /** 该设备专属的密钥（与设备级密钥同强度，泄露时只影响这一台）。 */
        val key: String,
        /** 用户可读的标签，如「Windows-PC」，来自配对时的 `name` 参数。 */
        val name: String,
        /** 配对时刻（`System.currentTimeMillis()`），用于列表排序与显示。 */
        val at: Long,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 开关

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    /**
     * 开关。**关闭时必须连带停掉两个监听**，否则会出现「UI 显示已关但端口还在收包」——
     * 这类不一致比不开更危险，因为用户以为自己安全了。
     *
     * @return 关闭后是否真的处于关闭态（监听已停）
     */
    fun setEnabled(ctx: Context, on: Boolean): Boolean {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) {
            ensureSecret(ctx)
            RemoteAccessServer.start(ctx)
            AgentBridge.rebindForRemoteAccess(ctx)
        } else {
            // 关开关顺手关窗口：不然关掉后配对码仍有效，用户看不到任何提示
            closePairWindow(ctx)
            RemoteAccessServer.stop()
            // ⚠️ 还要把已配对的设备凭据一并清掉。
            // 只停监听是不够的：配对照存在 prefs 里，下次再打开远程访问时那些凭据
            // 立刻重新有效，而用户以为「关掉 = 断开」，中间没有任何提示。
            // 关闭是用户表达「不再信任这些设备」的动作，凭据必须跟着作废。
            revokeAll(ctx)
            AgentBridge.rebindForRemoteAccess(ctx)
        }
        Log.i(TAG, "remote access enabled=$on")
        return isEnabled(ctx) == on
    }

    // ---------------------------------------------------------------- 设备密钥

    /** 取设备级密钥（不存在就生成）。 */
    fun ensureSecret(ctx: Context): String {
        val have = prefs(ctx).getString(KEY_SECRET, null)
        if (have != null && have.length == 64) return have
        val fresh = randomHex(32)
        prefs(ctx).edit().putString(KEY_SECRET, fresh).apply()
        // 重置密钥意味着所有已配对设备立刻失效 —— 这是刻意的，密钥就是根凭据
        prefs(ctx).edit().remove(KEY_PAIRS).apply()
        Log.i(TAG, "device secret generated; existing pairs cleared")
        return fresh
    }

    /** 重置：换新密钥并清空配对列表。用于「怀疑泄露」场景。 */
    fun resetSecret(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SECRET).remove(KEY_PAIRS).apply()
        ensureSecret(ctx)
        Log.w(TAG, "device secret reset; all paired devices revoked")
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- 配对窗口

    /** 配对窗口是否开着（且未过期）。 */
    fun pairWindowOpen(ctx: Context): Boolean {
        val until = prefs(ctx).getLong(KEY_WINDOW_UNTIL, 0L)
        if (until <= 0L) return false
        return android.os.SystemClock.elapsedRealtime() < until
    }

    /**
     * 打开配对窗口并生成一次性配对码。
     *
     * @return 6 位配对码；`null` 表示总开关没开（先开开关才能配对）
     */
    fun openPairWindow(ctx: Context): String? {
        if (!isEnabled(ctx)) return null
        val code = "%06d".format(SecureRandom().nextInt(1_000_000))
        prefs(ctx).edit()
            .putString(KEY_PAIR_CODE, code)
            .putLong(KEY_WINDOW_UNTIL, android.os.SystemClock.elapsedRealtime() + PAIR_WINDOW_MS)
            .apply()
        Log.i(TAG, "pair window opened for ${PAIR_WINDOW_MS / 1000}s")
        return code
    }

    fun currentPairCode(ctx: Context): String? =
        if (pairWindowOpen(ctx)) prefs(ctx).getString(KEY_PAIR_CODE, null) else null

    /** 剩余秒数（给 UI 倒计时用；窗口关闭时返回 0）。 */
    fun pairWindowRemainingSeconds(ctx: Context): Int {
        val until = prefs(ctx).getLong(KEY_WINDOW_UNTIL, 0L)
        if (until <= 0L) return 0
        val left = until - android.os.SystemClock.elapsedRealtime()
        return if (left <= 0L) 0 else (left / 1000).toInt()
    }

    fun closePairWindow(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PAIR_CODE).putLong(KEY_WINDOW_UNTIL, 0L).apply()
    }

    /**
     * 用配对码换取一个设备专属密钥。
     *
     * 成功即**关闭窗口** —— 一次性使用，避免同一码被同网段第二台机器复用。
     * 用户要给第二台配对就再点一次（成本很低，收益是不留可重放的口子）。
     *
     * @param code 用户输入的 6 位码
     * @param name 该设备的标签，空则自动生成
     * @return 新密钥；失败返回 null
     */
    fun redeemPairCode(ctx: Context, code: String, name: String): Pair? {
        if (!pairWindowOpen(ctx)) return null
        val expect = prefs(ctx).getString(KEY_PAIR_CODE, null) ?: return null
        // 常量时间比较：这里虽然只有 6 位，但没有理由给它留计时侧信道
        val same = MessageDigest.isEqual(
            code.trim().toByteArray(Charsets.UTF_8),
            expect.toByteArray(Charsets.UTF_8),
        )
        if (!same) return null

        val existing = pairs(ctx)
        if (existing.size >= MAX_PAIRS) {
            Log.w(TAG, "pair rejected: already $MAX_PAIRS devices")
            return null
        }
        val label = name.trim().ifBlank { "device-${existing.size + 1}" }.take(40)
        val pair = Pair(key = randomHex(32), name = label, at = System.currentTimeMillis())
        savePairs(ctx, existing + pair)
        // 一次性：换完即关窗
        closePairWindow(ctx)
        Log.i(TAG, "device paired as '$label' (${existing.size + 1} total)")
        return pair
    }

    // ---------------------------------------------------------------- 配对列表

    fun pairs(ctx: Context): List<Pair> {
        val raw = prefs(ctx).getString(KEY_PAIRS, null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("key")
                if (k.length != 64) return@mapNotNull null
                Pair(k, o.optString("name", "device"), o.optLong("at", 0L))
            }
        }.getOrElse {
            Log.w(TAG, "pairs parse failed: ${it.message}")
            emptyList()
        }
    }

    private fun savePairs(ctx: Context, list: List<Pair>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(
                org.json.JSONObject()
                    .put("key", p.key)
                    .put("name", p.name)
                    .put("at", p.at),
            )
        }
        prefs(ctx).edit().putString(KEY_PAIRS, arr.toString()).apply()
    }

    /** 撤销一台已配对设备。撤销后它的密钥立刻失效。 */
    fun revokePair(ctx: Context, key: String) {
        val left = pairs(ctx).filterNot { it.key == key }
        savePairs(ctx, left)
        Log.i(TAG, "pair revoked; ${left.size} remaining")
    }

    /** 撤销全部。 */
    fun revokeAll(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PAIRS).apply()
        Log.i(TAG, "all pairs revoked")
    }

    // ---------------------------------------------------------------- 鉴权

    /**
     * 本次请求是否被允许。
     *
     * @param fromLoopback 连接来源是否环回（`127.0.0.1` / `::1`）
     * @param presented 调用方出示的凭据（`X-DSH-Token` 头的值）
     *
     * ## 判定顺序（顺序本身就是安全策略，别调换）
     *
     * 1. 总开关没开 → **一律拒**。用户关掉的端口不该因为某个旧密钥仍有效而放行。
     * 2. 环回：引擎 token 或任一配对密钥都行（容器内程序靠这条继续工作）。
     * 3. 非环回：**只认配对密钥**。引擎 token 在非环回来源一律不作数 ——
     *    它会随环境变量泄露给容器内任意进程，不能当远程通行证。
     */
    fun authorise(ctx: Context, fromLoopback: Boolean, presented: String?): Boolean {
        if (presented.isNullOrBlank()) return false
        if (!isEnabled(ctx)) return false

        // 环回：先试引擎 token（既有链路，零成本），再试配对密钥
        if (fromLoopback) {
            val engine = AgentBridge.currentToken()
            if (engine != null && constantTimeEquals(presented, engine)) return true
        }
        return pairs(ctx).any { constantTimeEquals(presented, it.key) }
    }

    /**
     * 常量时间比较。
     *
     * 用 `MessageDigest.isEqual` 而不是 `==`：字符串相等在第一个不同字节就返回，
     * 理论上给了逐字节计时探测的可能。这里的威胁模型很弱（要能在同网段连续发
     * 数十万次请求），但改这个零成本。
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- 展示

    /**
     * 本机在局域网里的 IPv4 地址（给 UI 显示「让 PC 连这个」）。
     *
     * 委托 [LanAddress]，**不要**在这里再写一遍「取第一个非环回」——开热点时那样
     * 会拿到移动网络地址，用户实测过（热点里别的设备连不上）。理由见 [LanAddress]。
     */
    fun lanAddresses(): List<String> = LanAddress.all()

    /**
     * 给用户看的状态摘要。
     *
     * ⚠️ 这里**没有**对应的 HTTP 路由：旧的 KDoc 写「UI 与 `/remote/status` 共用一份」，
     * 但那个端点从未实现过（summary() 在桥里零调用方）。保留这个函数是给未来接一个
     * 只读状态端点用的；要接就两边一起改，不要再单方面在注释里宣布它存在。
     */
    fun summary(ctx: Context): org.json.JSONObject = org.json.JSONObject().apply {
        put("enabled", isEnabled(ctx))
        put("bridge_port", AgentBridge.PORT)
        put("setup_port", RemoteAccessServer.PORT)
        put("addresses", org.json.JSONArray(lanAddresses()))
        put("paired", pairs(ctx).size)
        put("max_pairs", MAX_PAIRS)
        put("pair_window_open", pairWindowOpen(ctx))
        put("pair_code", currentPairCode(ctx) ?: org.json.JSONObject.NULL)
        put("pair_window_left_s", pairWindowRemainingSeconds(ctx))
    }
}

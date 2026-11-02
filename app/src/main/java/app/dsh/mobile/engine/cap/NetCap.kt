package app.dsh.mobile.engine.cap

import android.annotation.SuppressLint
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.Privilege
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按 App（UID）的网络控制与流量查询。
 *
 * ## 两档能力，差别很大
 * - **查询流量（B 档，不需要 root）**：走 `NetworkStatsManager`。
 * - **封锁网络（C 档，必须 root）**：走 `iptables -m owner --uid-owner <uid> -j DROP`。
 *
 * ## 为什么「成功」的判据是 iptables 计数器，而不是 ping 通不通
 * 真机实测（2026-11-02）踩过这个坑：我用 `su <uid> -c ping` 验证封锁，
 * 插规则后 100% 丢包、删规则后**也** 100% 丢包 —— 因为这个方式本身就拿不到
 * ICMP raw socket（`su` 切了 uid 但 SELinux 域还是 `u:r:ksu:s0`）。
 * 于是「丢包」根本不能证明是规则拦的，那是**假阳性**。
 *
 * 可靠判据是 `iptables -vL OUTPUT` 的 pkts 计数器：插规则时是 0，
 * 让该 uid 发流量后变成 5 pkts / 300 bytes —— 规则确实命中过。
 * 所以 [block] / [unblock] 都以「读回计数器/规则是否存在」为准，
 * 并且把 `verified` 字段明确告诉调用方。
 *
 * ## 链位置很关键
 * ColorOS（OPPO）的 OUTPUT 链前面挂了一堆定制链：`customize_OUTPUT`、
 * `RESTRICTION_SSDP_CHAIN`、`oem_out`、`fw_OUTPUT`、`st_OUTPUT`、`bw_OUTPUT`、
 * BPF skfilter……我们的规则必须 `-I OUTPUT 1`（插到**最前**），
 * 追加到末尾会被这些链提前 ACCEPT 掉。
 */
object NetCap {

    /** 我们自己的规则都带这个注释标记，便于无歧义地列出与删除。 */
    private const val RULE_TAG = "dsh-netblock"

    /**
     * 列出/封锁/解封/流量。
     *
     * op:
     * - `status`  当前由我们添加的封锁规则
     * - `list`    本次设备上可查流量的 App 及其用量
     * - `block`   按包名或 uid 封锁
     * - `unblock` 解除
     */
    fun net(ctx: Context, rawBody: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.NET)) return denied(CapabilityGate.NET)
        val body = runCatching { JSONObject(rawBody.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to error("invalid json")
        return when (val op = body.optString("op").ifBlank { "status" }) {
            "status" -> status(ctx)
            "list" -> list(ctx, body)
            "block" -> block(ctx, body)
            "unblock" -> unblock(ctx, body)
            else -> 400 to error("unknown op '$op' (status|list|block|unblock)")
        }
    }

    // ─────────────────────────── 封锁（C 档） ───────────────────────────

    private fun block(ctx: Context, body: JSONObject): Pair<Int, String> {
        val target = resolveTarget(ctx, body) ?: return 400 to error("need package=<name> or uid=<n>")
        if (target.uid <= 0) return 400 to error("refusing to block uid ${target.uid} (system/root)")
        // 自己封自己会让 AI 从此无法联网，必须显式允许（默认拒绝）
        val selfUid = ctx.applicationInfo?.uid ?: -1
        if (target.uid == selfUid && !body.optBoolean("allowSelf", false)) {
            return 400 to error(
                "refusing to block myself (uid $selfUid); pass allowSelf=true only if you really " +
                    "want the AI to lose network access",
            )
        }

        val before = countRulePackets(ctx, target.uid)
        if (before != null) {
            // 已存在：幂等返回，并说明当前命中情况
            return 200 to JSONObject()
                .put("ok", true)
                .put("already_blocked", true)
                .put("uid", target.uid)
                .put("package", target.pkg)
                .put("packets_dropped", before)
                .put("verified", true)
                .put("note", "rule already present; packets_dropped is the live counter")
                .toString()
        }

        val ins = Privilege.runPrivileged(
            ctx,
            "iptables -I OUTPUT 1 -m owner --uid-owner ${target.uid} -j DROP " +
                "-m comment --comment $RULE_TAG && echo OK",
        )
        if (ins == null || !ins.contains("OK")) {
            return 403 to error("iptables insert failed (root required?): ${ins?.trim()?.take(160)}")
        }

        // 读回计数器：规则真在链上才算成功
        val dropped = countRulePackets(ctx, target.uid)
        if (dropped == null) {
            return 500 to error("rule inserted but could not be read back; state unknown")
        }
        CapabilityGate.audit(ctx, "net.block:${target.pkg ?: target.uid}")
        return 200 to JSONObject()
            .put("ok", true)
            .put("uid", target.uid)
            .put("package", target.pkg)
            .put("packets_dropped", dropped)
            .put("verified", true)
            .put("scope", "IPv4 OUTPUT only; IPv6 needs ip6tables (not covered yet)")
            .put(
                "note",
                "counters prove the rule sits on the chain. Do NOT verify by ping: " +
                    "su <uid> -c ping loses raw-socket access, so it fails with or without the rule.",
            )
            .toString()
    }

    private fun unblock(ctx: Context, body: JSONObject): Pair<Int, String> {
        val target = resolveTarget(ctx, body) ?: return 400 to error("need package=<name> or uid=<n>")
        val before = countRulePackets(ctx, target.uid)
        if (before == null) {
            return 200 to JSONObject()
                .put("ok", true)
                .put("was_blocked", false)
                .put("uid", target.uid)
                .put("package", target.pkg)
                .toString()
        }
        val del = Privilege.runPrivileged(
            ctx,
            "iptables -D OUTPUT -m owner --uid-owner ${target.uid} -j DROP " +
                "-m comment --comment $RULE_TAG && echo OK",
        )
        if (del == null || !del.contains("OK")) {
            return 500 to error("iptables delete failed: ${del?.trim()?.take(160)}")
        }
        val still = countRulePackets(ctx, target.uid)
        CapabilityGate.audit(ctx, "net.unblock:${target.pkg ?: target.uid}")
        return 200 to JSONObject()
            .put("ok", still == null)
            .put("was_blocked", true)
            .put("uid", target.uid)
            .put("package", target.pkg)
            .put("packets_dropped_before", before)
            .also { if (still != null) it.put("error", "rule still present after delete") }
            .toString()
    }

    /**
     * 当前被我们封着的 uid 及其命中计数。
     *
     * ⚠️ 解析要贴 `iptables -vL` 的**真实输出**：它写的是
     * `owner UID match 10360`，不是命令里那种 `uid-owner 10360` 写法。
     * 第一版按后者 grep，结果规则明明在链上也读回 null → 报
     * "rule inserted but could not be read back"（真机踩过）。
     *
     * 另：不带 `--line-numbers` 时第 0 列是 pkts、第 1 列是 bytes（不是第 1、2 列）。
     */
    private fun status(ctx: Context): Pair<Int, String> {
        val out = Privilege.runPrivileged(ctx, "iptables -vL OUTPUT -n 2>&1")
            ?: return 403 to error("cannot read iptables (root required)")
        val arr = JSONArray()
        var total = 0
        out.lineSequence().forEach { line ->
            val uid = parseRuleUid(line) ?: return@forEach
            val cols = line.trim().split(Regex("""\s+"""))
            val pkts = cols.getOrNull(0)?.toIntOrNull() ?: 0
            val bytes = cols.getOrNull(1)?.toLongOrNull() ?: 0L
            total++
            arr.put(
                JSONObject()
                    .put("uid", uid)
                    .put("package", packageOfUid(ctx, uid))
                    .put("packets_dropped", pkts)
                    .put("bytes_dropped", bytes),
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("blocked_count", total)
            .put("blocked", arr)
            .put("scope", "IPv4 OUTPUT only")
            .toString()
    }

    /** 从一行 `iptables -vL` 输出里取 owner 规则的 uid；不是这类行返回 null。 */
    private fun parseRuleUid(line: String): Int? {
        if (!line.contains("DROP")) return null
        if (!line.contains("owner UID match") && !line.contains("owner GID match")) return null
        return Regex("""owner UID match\s+(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 该 uid 是否已有我们的 DROP 规则；有则返回当前命中包数，没有返回 null。
     *
     * 用 `iptables -S`（把规则还原成命令行语法）来判存在性更稳，
     * 因为那里写的就是 `--uid-owner 10360`，和插入时同一套拼法。
     * 计数则回 `-vL` 取。
     */
    private fun countRulePackets(ctx: Context, uid: Int): Int? {
        val exists = Privilege.runPrivileged(
            ctx,
            "iptables -S OUTPUT 2>/dev/null | grep -F -- '--uid-owner $uid' " +
                "| grep -F -- '$RULE_TAG' | head -1",
        ) ?: return null
        if (!exists.contains("--uid-owner $uid")) return null
        val verbose = Privilege.runPrivileged(ctx, "iptables -vL OUTPUT -n 2>&1") ?: return 0
        val line = verbose.lineSequence().firstOrNull { parseRuleUid(it) == uid } ?: return 0
        return line.trim().split(Regex("""\s+""")).getOrNull(0)?.toIntOrNull() ?: 0
    }

    // ─────────────────────────── 流量（B 档） ───────────────────────────

    @SuppressLint("MissingPermission")
    private fun list(ctx: Context, body: JSONObject): Pair<Int, String> {
        val limit = body.optInt("limit", 25).coerceIn(1, 200)
        val windowMs = body.optLong(
            "hours",
            24L,
        ).coerceIn(1L, 24L * 30) * 3_600_000L
        val pkgFilter = body.optString("package").ifBlank { "" }
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
            ?: return 503 to error("NetworkStatsManager unavailable")
        val end = System.currentTimeMillis()
        val start = end - windowMs

        // 先汇总每个 uid 的「哪些包在跑网络」——NetworkStats 按 uid 出，包名要自己反查
        val perUid = LinkedHashMap<Int, LongArray>() // [rx, tx]
        var any = false
        // querySummary 的 networkType 直接收 ConnectivityManager.TYPE_*，
        // 传 -1（TYPE_NONE）在多数机型上一律返回空，所以两种分别查再合并。
        for (type in intArrayOf(
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_WIFI,
        )) {
            runCatching {
                @Suppress("DEPRECATION")
                nsm.querySummary(type, null, start, end).use { stats ->
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        val cur = perUid.getOrPut(bucket.uid) { LongArray(2) }
                        cur[0] += bucket.rxBytes
                        cur[1] += bucket.txBytes
                        any = true
                    }
                }
            }
        }
        if (!any) {
            return 403 to error(
                "no stats returned — need Usage Access (PACKAGE_USAGE_STATS) granted in system settings",
            )
        }

        val rows = perUid.entries
            .map { (uid, v) ->
                Triple(uid, v[0], v[1])
            }
            .filter { pkgFilter.isBlank() || packageOfUid(ctx, it.first)?.contains(pkgFilter) == true }
            .sortedByDescending { it.second + it.third }
            .take(limit)

        val arr = JSONArray()
        rows.forEach { (uid, rx, tx) ->
            arr.put(
                JSONObject()
                    .put("uid", uid)
                    .put("package", packageOfUid(ctx, uid))
                    .put("rx_bytes", rx)
                    .put("tx_bytes", tx)
                    .put("total_bytes", rx + tx),
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("window_hours", windowMs / 3_600_000L)
            .put("uids_total", perUid.size)
            .put("returned", rows.size)
            .put("rows", arr)
            .put("note", "aggregate over mobile + wifi counters maintained by the system")
            .toString()
    }

    // ───────────────────────────── 公共 ─────────────────────────────

    private data class Target(val uid: Int, val pkg: String?)

    private fun resolveTarget(ctx: Context, body: JSONObject): Target? {
        val pkg = body.optString("package").ifBlank { "" }
        if (pkg.isNotBlank()) {
            val uid = runCatching {
                ctx.packageManager.getApplicationInfo(pkg, 0).uid
            }.getOrNull() ?: return null
            return Target(uid, pkg)
        }
        val uid = body.optInt("uid", -1)
        if (uid > 0) return Target(uid, packageOfUid(ctx, uid))
        return null
    }

    private fun packageOfUid(ctx: Context, uid: Int): String? = runCatching {
        ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()
    }.getOrNull()

    private fun denied(capability: String): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false)
        .put("error", "capability disabled")
        .put("capability", capability)
        .toString()

    private fun error(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}

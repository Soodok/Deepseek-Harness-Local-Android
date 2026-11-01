package app.dsh.mobile.engine.cap

import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.PermGrant
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 权限自助授权端点（v1.5.0-beta.9）。
 *
 * ## 为什么是端点而不是 App 内按钮
 * 宝宝的要求是「AI 想要啥权限就直接拿」。所以这件事必须能被 **AI 通过命令行** 触发，
 * 而不是只有用户点按钮才能跑 —— 门脚本 `getperm` / `grantperm` 打的就是这里。
 *
 * ## 能力门
 * 走 [CapabilityGate.CONTROL]（与设备控制同一档），因为全量 `pm grant` 实际上
 * 是在改系统授权状态，危险程度等同甚至高于点屏。用户在权限中心关掉「设备控制」
 * 就该连这个也一起关。
 *
 * ## 端点
 * - `GET  /dev/perms`  当前状态：声明数 / 已授予数 / 特权通道是否可用
 * - `POST /dev/perms`  执行全量授权，body 可带 `{"force":true}`
 */
object PermCap {

    /** GET /dev/perms —— 只读状态，不需要授权就能查。 */
    fun status(ctx: Context): Pair<Int, String> {
        val p = PermGrant.progress(ctx)
        val declared = PermGrant.declaredPermissions(ctx)
        val granted = declared.count {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val out = JSONObject()
            .put("ok", true)
            .put("declared", declared.size)
            .put("granted", granted)
            .put("missing", declared.size - granted)
            .put("last", PermGrant.lastResult(ctx))
            .put("enabled", CapabilityGate.enabled(ctx, CapabilityGate.CONTROL))
            .put("running", p.running)
            .put("progress_done", p.done)
            .put("progress_total", p.total)
            .put("progress_new", p.newlyGranted)
        return 200 to out.toString()
    }

    /**
     * POST /dev/perms —— **异步**启动全量授权，立即返回。
     *
     * ⚠️ 必须异步：桥的 HTTP 超时是 10 秒，全量 1358 个权限实测要一两分钟。
     * 同步实现会让调用方拿到 `rc=3 读取响应超时` 并误判成「桥挂了」。
     * 调用后请用 `GET /dev/perms/status` 轮询 `running` 字段。
     */
    fun grant(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CONTROL)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability_disabled")
                .put("capability", CapabilityGate.CONTROL)
                .put("hint", "enable it in Permission Center > AI device capabilities")
                .toString()
        }
        val force = runCatching {
            if (body.isBlank()) false else JSONObject(body).optBoolean("force", true)
        }.getOrDefault(true)
        val r = PermGrant.startAsync(ctx, force = force)
        val p = PermGrant.progress(ctx)
        return 200 to JSONObject()
            .put("ok", true)
            .put("started", true)
            .put("already_running", r.skipped)
            .put("total_declared", r.total)
            .put("running", p.running)
            .put("hint", "poll GET /dev/perms/status until running=false")
            .toString()
    }

    /**
     * POST /dev/perms/revoke —— 撤销指定权限（v1.5.0-beta.10）。
     *
     * body: `{"name":"android.permission.CAMERA"}` 或 `{"names":["..."]}`
     *
     * 走 root/Shizuku 通道，与授权对称。**注意 `pm revoke` 会杀 App 进程**
     * 重载 gid 表 —— 这是系统行为，调用方拿到响应后马上会看到进程消失，
     * 下游应把它当「已送达」而不是「失败」。
     */
    fun revoke(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CONTROL)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability_disabled")
                .put("capability", CapabilityGate.CONTROL).toString()
        }
        val req = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val names = mutableListOf<String>()
        req.optString("name").takeIf { it.isNotBlank() }?.let { names += it }
        req.optJSONArray("names")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { names += it }
        }
        if (names.isEmpty()) {
            return 400 to JSONObject().put("ok", false).put("error", "name required").toString()
        }
        val declared = PermGrant.declaredPermissions(ctx).toSet()
        val bad = names.filter { it !in declared }
        if (bad.isNotEmpty()) {
            return 400 to JSONObject().put("ok", false)
                .put("error", "not declared").put("names", JSONArray(bad)).toString()
        }
        val done = JSONArray()
        val failed = JSONArray()
        names.forEach { n ->
            val out = app.dsh.mobile.engine.Privilege.runPrivileged(
                ctx, "pm revoke " + ctx.packageName + " " + n,
            )
            if (out != null) done.put(n) else failed.put(n)
        }
        CapabilityGate.audit(ctx, "dev.perms.revoke")
        return 200 to JSONObject().put("ok", failed.length() == 0)
            .put("revoked", done).put("failed", failed).toString()
    }

    /** 列出还没拿到的权限（诊断用，可按前缀过滤）。 */
    fun missing(ctx: Context, query: String): Pair<Int, String> {
        val prefix = Regex("(?:^|&)q=([^&]*)").find(query)?.groupValues?.get(1) ?: ""
        val arr = JSONArray()
        PermGrant.declaredPermissions(ctx).forEach {
            if (ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED &&
                (prefix.isEmpty() || it.contains(prefix, ignoreCase = true))
            ) arr.put(it)
        }
        return 200 to JSONObject().put("ok", true).put("count", arr.length())
            .put("missing", arr).toString()
    }
}

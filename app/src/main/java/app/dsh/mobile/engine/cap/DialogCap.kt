package app.dsh.mobile.engine.cap

import app.dsh.mobile.R
import app.dsh.mobile.engine.CapabilityGate
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 弹窗识别与自动消解。
 *
 * ## 为什么需要它
 * 手机 GUI Agent 落地失败的最大单一原因不是模型不够强，而是**弹窗**：
 * 权限框、青少年模式、更新提示、广告浮层、活动弹窗、网络异常提示 —— 它们不请自来，
 * 挡住按钮，且文案随厂商随版本变化。每次都让大模型看一遍图去判断，既慢又不稳。
 *
 * ## 本模块的定位：便宜的快路径，不是决策
 * 这里只做「已知弹窗 → 已知按钮」的确定性匹配：命中就给坐标，没命中就返回 miss，
 * 由上层决定要不要上视觉模型。规则库按「危险 / 安全」分级（[Risk]），
 * 因为**误点「同意授权」或「立即购买」比不点更糟**。
 */
object DialogCap {

    /**
     * 按钮风险分级。
     *
     * - [SAFE]：关闭弹窗、维持现状，点了不会改变任何授权或产生消费。
     * - [NEUTRAL]：跳转类（"知道了""稍后""下次再说"），可点。
     * - [DANGER]：授予权限、同意条款、购买、支付、删除、注销。
     *   **默认绝不自动点**，只报告出来让上层或用户决定。
     */
    enum class Risk { SAFE, NEUTRAL, DANGER }

    data class Rule(val id: String, val texts: List<String>, val risk: Risk)

    /**
     * 关闭类按钮词库。
     *
     * 排序有讲究：越"无害"的越靠前，命中即用。这样同一个弹窗上同时出现
     * 「取消」和「立即更新」时，永远选中「取消」。
     *
     * 只放**明确表示"不"**的词，不放"确定""好的"这类模棱两可的 ——
     * 后者在权限弹窗里等于同意。
     */
    private val DISMISS_RULES: List<Rule> = listOf(
        Rule("dismiss.cancel", listOf("取消", "Cancel", "Abbrechen", "Cancelar"), Risk.SAFE),
        Rule("dismiss.not_now", listOf("以后再说", "暂不", "稍后再说", "下次再说", "以后", "Not now", "Later", "Später", "Mais tarde"), Risk.SAFE),
        Rule("dismiss.skip", listOf("跳过", "跳过广告", "Skip", "Überspringen", "Pular"), Risk.SAFE),
        Rule("dismiss.close", listOf("关闭", "关闭广告", "Close", "Schließen", "Fechar", "×"), Risk.SAFE),
        Rule("dismiss.deny", listOf("拒绝", "不允许", "禁止", "Deny", "Don't allow", "Ablehnen", "Recusar"), Risk.SAFE),
        Rule("dismiss.notice", listOf("我知道了", "知道了", "了解", "Got it", "Verstanden", "Entendi"), Risk.NEUTRAL),
    )

    /** 危险按钮：只报告，绝不自动点。 */
    private val DANGER_RULES: List<Rule> = listOf(
        Rule("danger.allow", listOf("允许", "始终允许", "允许所有", "Allow", "Always allow", "Zulassen", "Permitir"), Risk.DANGER),
        Rule("danger.agree", listOf("同意", "同意并继续", "我同意", "同意授权", "Agree", "Accept", "Zustimmen", "Aceitar"), Risk.DANGER),
        Rule("danger.buy", listOf("立即购买", "立即支付", "确认支付", "开通", "订阅", "Buy now", "Pay", "Kaufen", "Comprar"), Risk.DANGER),
        Rule("danger.update", listOf("立即更新", "立即升级", "马上安装", "Update now", "Jetzt aktualisieren", "Atualizar"), Risk.DANGER),
        Rule("danger.delete", listOf("删除", "清空", "注销", "退出登录", "Delete", "Löschen", "Excluir"), Risk.DANGER),
    )

    /** 常见弹窗容器特征词：节点文本里出现这些，基本可以断定"当前在一个弹窗里"。 */
    private val DIALOG_HINTS = listOf(
        "权限", "授权", "允许", "隐私政策", "用户协议", "青少年模式", "温馨提示",
        "发现新版本", "立即更新", "评分", "反馈", "广告", "活动", "恭喜", "领取",
        "permission", "privacy", "update", "rate us", "advertisement",
    )

    /**
     * POST /dev/dialog —— 扫描当前屏幕，判断是否有可消解的弹窗。
     *
     * body: `{"auto":false,"includeDanger":false}`
     *
     * 返回 `dialog`（是否判定为弹窗）、`hits[]`（命中的按钮及风险）、`suggested`（建议点哪个）。
     * `auto=true` 时**只自动点 SAFE/NEUTRAL**，DANGER 永远只报告。
     *
     * ## 为什么 auto 只点 SAFE
     * 「允许」「立即购买」这类误点的代价不可逆（授权、扣费），而漏点一个弹窗
     * 最多是这一步失败、下一轮再试。因此策略必须偏保守：宁可不动，绝不错点。
     */
    fun scan(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CONTROL)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled").put("capability", CapabilityGate.CONTROL).toString()
        }
        val req = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
        val auto = req.optBoolean("auto", false)
        val includeDanger = req.optBoolean("includeDanger", true)

        val svc = app.dsh.mobile.DshAccessibilityService.instance
            ?: return 503 to JSONObject().put("ok", false)
                .put("error", "accessibility service not enabled").toString()

        // ⚠️ dumpScreenJson() 返回的是**一个 JSON 对象**（含 "nodes" 数组与尺寸字段），
        // 不是裸数组 —— /screen?format=json 的解析方式同样是先 JSONObject 再取 "nodes"。
        // 早期版本按裸数组解析，每次都抛异常又被 getOrDefault 吞掉，真机表现是
        // 「永远 node_count=0、永远判无弹窗」，看起来像「没弹窗」而不是「读屏坏了」。
        // 现在解析失败要显式报错，把这类静默失败挡在源头。
        val nodes = runCatching {
            JSONObject(svc.dumpScreenJson("all", false)).getJSONArray("nodes")
        }.getOrElse { e ->
            return 500 to JSONObject().put("ok", false)
                .put("error", "screen_dump_failed")
                .put("detail", e.message.orEmpty().take(200)).toString()
        }

        val hits = JSONArray()
        // 两个信号分开收集：提示词（页面像弹窗）+ 按钮命中（确实有可点的关闭按钮）。
        // 只有两者同时成立才算弹窗 —— 见下方 dtgish 的判据说明。
        var hintSeen = false
        var best: JSONObject? = null
        var bestRank = Int.MAX_VALUE

        fun consider(label: String, risk: Risk, node: JSONObject): Boolean {
            val x = node.optInt("x", -1)
            val y = node.optInt("y", -1)
            if (x < 0 || y < 0) return false
            val hit = JSONObject()
                .put("label", label)
                .put("risk", risk.name.lowercase())
                .put("x", x).put("y", y)
                .put("node_text", node.optString("text"))
                .put("node_desc", node.optString("desc"))
                .put("rid", node.optString("rid"))
            if (risk != Risk.DANGER || includeDanger) hits.put(hit)
            if (risk == Risk.DANGER) return false // 危险项永不成为建议
            val rank = when (risk) { Risk.SAFE -> 0; Risk.NEUTRAL -> 1; Risk.DANGER -> 2 }
            if (rank < bestRank) {
                bestRank = rank
                best = hit
            }
            return true
        }

        // 按钮候选：所有「可点 + 文案很短」的节点。
        // ⚠️ 关键：几何判据必须看**全部**按钮，不能只看关闭类词命中的那些 ——
        // 实测系统录屏弹窗的第二个按钮是「下一步」，它不属于任何关闭词库，
        // 只看 hits 就永远只有「取消」一个，按钮栏判据随之失效。
        val buttonYs = ArrayList<Int>()

        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            val text = n.optString("text").ifBlank { n.optString("desc") }
            if (text.isBlank()) continue
            // 按钮文案一般 ≤8 个字符；长文本是说明文字，不算按钮。
            if (n.optBoolean("clickable", false) && text.length <= 8) {
                val by = n.optInt("y", -1)
                if (by >= 0) buttonYs.add(by)
            }
            if (!hintSeen && DIALOG_HINTS.any { text.contains(it, ignoreCase = true) }) hintSeen = true
            DISMISS_RULES.firstOrNull { r -> r.texts.any { text.equals(it, ignoreCase = true) } }
                ?.let { consider(text, it.risk, n) }
                ?: DANGER_RULES.firstOrNull { r -> r.texts.any { text.equals(it, ignoreCase = true) } }
                    ?.let { consider(text, it.risk, n) }
        }

        // ⚠️ 判据演进史（三次真机实测，别退回任何一种）：
        //   ① 只看提示词 → 设置页因含「更新」二字被判弹窗（hits 却是空的）；
        //   ② 只看「命中一个可点按钮」→ 设置页的普通「关闭」卡片按钮被判弹窗；
        //   ③ 只看「提示词 + 按钮」→ 真实的系统录屏授权弹窗被判 **false**
        //      （其文案「要开始使用…录制或投放吗」不在词库，且只有「取消」命中）。
        // 前两种都是「单点特征」思维，注定两头漏。真正稳定的判据是**几何**：
        // 系统弹窗的按钮永远排在底部同一水平线上、成对出现（取消/确定、取消/下一步）；
        // 页面里的普通卡片按钮则是单点、孤立、位置随内容走。
        // 因此主判据 = 按钮同行（≥2 个），文案与危险词只作兜底。
        val hasDanger = (0 until hits.length()).any {
            hits.optJSONObject(it)?.optString("risk") == "danger"
        }
        // 按 y 分桶（24px 容差 ≈ 半行高），同一桶 ≥2 个按钮即认为处在弹窗按钮栏。
        // 用全部按钮候选（buttonYs），不是关闭词命中项 —— 原因见上方收集处注释。
        val rows = HashMap<Int, Int>()
        for (y in buttonYs) {
            val bucket = y / 24
            rows[bucket] = (rows[bucket] ?: 0) + 1
        }
        val buttonRow = rows.values.any { it >= 2 }
        val dialogish = buttonRow || (hintSeen && hits.length() > 0) || hasDanger
        // 单点命中（一个安全按钮、无提示词、无同行伙伴）不足以断定弹窗，
        // 但也不该完全吞掉 —— 标记出来让上层自己决定。
        val weakHit = !dialogish && hits.length() >= 1

        val suggested = best
        var clicked: JSONObject? = null
        if (auto && dialogish && suggested != null) {
            val x = suggested.optInt("x")
            val y = suggested.optInt("y")
            // 走无障碍的坐标点击（含防抖 jitter 与浮层动作播报），而不是 adb input tap：
            // 后者要 shell 权限，且不会触发 interference 计数，会让「AI 自己点」被误判成用户干扰。
            val ok = runCatching { svc.dispatchTap(x.toFloat(), y.toFloat()) }.getOrDefault(false)
            if (ok) {
                clicked = suggested
                CapabilityGate.audit(ctx, "dev.dialog.auto:${suggested.optString("label")}")
            }
        }

        CapabilityGate.audit(ctx, "dev.dialog.scan")
        return 200 to JSONObject()
            .put("ok", true)
            .put("dialog", dialogish)
            .put("hint_seen", hintSeen)
            .put("weak_hit", weakHit)
            .put("button_candidates", buttonYs.size)
            .put("max_buttons_in_row", rows.values.maxOrNull() ?: 0)
            .put("node_count", nodes.length())
            .put("hits", hits)
            .put("suggested", suggested ?: JSONObject.NULL)
            .put("clicked", clicked ?: JSONObject.NULL)
            .put("auto", auto)
            .toString()
    }

    /**
     * POST /dev/verify —— 动作生效校验。
     *
     * body: `{"expect":"文本","gone":false,"timeout_ms":4000}`
     *
     * 这是把「点完不知道成不成」变成「点完知道成不成」的最小工具。
     * 上层每做一个动作就该跟一次 verify：命中 = 这一步真的生效了；
     * 超时 = 该换定位方式或换思路，而不是盲目继续点。
     */
    fun verify(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CONTROL)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled").put("capability", CapabilityGate.CONTROL).toString()
        }
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val expect = req.optString("expect")
        val gone = req.optBoolean("gone", false)
        if (expect.isBlank()) return 400 to JSONObject().put("ok", false).put("error", "expect required").toString()
        val timeout = req.optLong("timeout_ms", 4000L).coerceIn(200L, 30_000L)

        val svc = app.dsh.mobile.DshAccessibilityService.instance
            ?: return 503 to JSONObject().put("ok", false)
                .put("error", "accessibility service not enabled").toString()

        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeout
        while (System.currentTimeMillis() < deadline) {
            val raw = runCatching { svc.dumpScreenJson("all", false) }.getOrNull()
            val present = raw != null && raw.contains(expect)
            if (present != gone) {
                return 200 to JSONObject().put("ok", true)
                    .put("expect", expect).put("gone", gone)
                    .put("waited_ms", System.currentTimeMillis() - t0).toString()
            }
            Thread.sleep(150)
        }
        return 200 to JSONObject().put("ok", false)
            .put("error", "timeout")
            .put("expect", expect).put("gone", gone)
            .put("timeout_ms", timeout)
            .put("hint", "the action may have missed; re-dump and retry with a different locator")
            .toString()
    }
}

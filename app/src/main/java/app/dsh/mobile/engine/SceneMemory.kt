package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 场景记忆（v1.2.125）—— 「越玩越聪明」的端侧实现。
 *
 * ## 机制
 * 不做模型训练，做**记忆**（非参数化学习）：
 * 1. 云端规划的决策执行**成功** → 存 (场景指纹, 目标, 决策程序)
 * 2. 下次遇到**相似场景**（屏幕文本集合 Jaccard 相似度 ≥ 阈值）→ 直接复用，
 *    **跳过云端**（云端 0.6-1.6s/次 → 查表 <1ms）
 * 3. 复用后失败 → misses+1，超阈值淘汰（防错记忆固化）
 *
 * ## 数据从哪来（关键洞察）
 * **每次云端的纠正都是标注样本**：本地选错 A → 云端改选 B → (屏幕, 目标, 正确答案=B)
 * 就是一条天然的标注对。记忆攒够之后可导出训练判别头（泛化），但记忆本身零成本。
 *
 * ## 实测（2026-10-11，模拟器）
 * 同任务第 2 次执行：云端调用 3→2 次、记忆命中 2 次。
 * 陷阱（已修）：动作改变的状态若不在屏幕文本里（开关 checked 不输出）→ 指纹不变
 * → 记忆反复命中同一动作（反复点开关）。调用方应对「同指纹+同动作」做任务内去重。
 */
object SceneMemory {

    private const val TAG = "SceneMemory"
    private const val FILE = "scene_memory.json"
    private const val MAX_ENTRIES = 200

    /** 相似度阈值：屏幕文本集合的 Jaccard 相似度 */
    const val SIM_THRESHOLD = 0.7

    /** 连续失败几次后淘汰 */
    private const val MAX_MISSES = 2

    data class Entry(
        val fp: Set<String>,
        val goal: String,
        var plan: JSONObject,
        var hits: Int,
        var misses: Int,
        val created: Long,
        var updated: Long,
        /** 待确认（v1.2.126）：**步骤级成功但任务级未验证**。
         *  实测教训（极复杂场景）：步骤全过但目标未达成的计划被存为"成功记忆" →
         *  复用后任务失败（记忆污染）。故成功只存 pending，待云端确认达成后转正。 */
        var pending: Boolean = false,
        /** 同一轨迹的连续成功次数（v1.2.139 自动沉淀）：待确认条目的计划
         *  连续成功 2 次即**自动转正**——重复验证过的一致性比单次结果可靠，
         *  不必等云端确认（AppAgentX/PalmClaw 的"技能沉淀"机制，业界实测
         *  用结构化捷径替代推理循环可砍 -94.9% 完成时间）。失败会清零并淘汰。 */
        var pendingHits: Int = 0,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fp", JSONArray(fp.toList()))
            put("goal", goal)
            put("plan", plan)
            put("hits", hits)
            put("misses", misses)
            put("created", created)
            put("updated", updated)
            put("pending", pending)
            put("pendingHits", pendingHits)
        }
    }

    private fun file(ctx: Context): File =
        File(ctx.filesDir, FILE)

    private fun load(ctx: Context): MutableList<Entry> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val fpArr = o.optJSONArray("fp") ?: JSONArray()
                val fp = HashSet<String>(fpArr.length())
                for (k in 0 until fpArr.length()) fp.add(fpArr.optString(k))
                out.add(
                    Entry(
                        fp = fp,
                        goal = o.optString("goal"),
                        plan = o.optJSONObject("plan") ?: JSONObject(),
                        hits = o.optInt("hits", 0),
                        misses = o.optInt("misses", 0),
                        created = o.optLong("created"),
                        updated = o.optLong("updated"),
                        pending = o.optBoolean("pending", false),
                        pendingHits = o.optInt("pendingHits", 0),
                    )
                )
            }
            out
        }.getOrElse {
            Log.w(TAG, "load failed: ${it.message}")
            mutableListOf()
        }
    }

    private fun save(ctx: Context, entries: List<Entry>) {
        runCatching {
            val arr = JSONArray()
            // 容量控制：超限时淘汰"最少命中 + 最旧"的
            val sorted = entries.sortedWith(compareByDescending<Entry> { it.hits }
                .thenByDescending { it.updated })
            sorted.take(MAX_ENTRIES).forEach { arr.put(it.toJson()) }
            file(ctx).writeText(arr.toString())
        }.onFailure { Log.w(TAG, "save failed: ${it.message}") }
    }

    /** 场景指纹 = 屏幕文本集合（复用 FastPicker 的紧凑 dump 解析）
     *  v1.2.126：**勾选状态纳入指纹**（v:/o: 前缀）——开关开/关是不同指纹，
     *  修复"点击开关后指纹不变 → 记忆反复命中同一动作（反复点开关）"的根因。 */
    fun fingerprint(dump: String, foregroundPkg: String = ""): Set<String> {
        val base = FastPicker.parseCompact(dump)
            .filter { it.text.isNotBlank() && it.text != "-" }
            .filterNot { isVolatileText(it.text) }
            .map {
                when (it.checked) {
                    true -> "v:${it.text}"
                    false -> "o:${it.text}"
                    null -> it.text
                }
            }
            .toMutableSet()
        // v1.2.139：前台包名并入指纹——同一文本在不同 App 下不是同一场景
        // （实测教训：不同 App 的"设置"页文本高度相似，仅靠文本集合会误命中）
        if (foregroundPkg.isNotBlank()) base.add("pkg:$foregroundPkg")
        return base
    }

    /**
     * 这条文本是否属于「同一页面每次都不一样」的易变内容（v1.7.x）。
     *
     * 为什么必须滤掉：指纹是用来回答「这还是同一个场景吗」的，而
     *
     * · 模型/版本标识（`DeepSeek-V4.1-FlashHigh`、`预览版`、`标准模式`）
     * · 会话标题、时间戳、未读数、进度百分比、随机 id
     *
     * 每次进页面都可能变。它们混进指纹会有两个后果，且方向相反：
     *   ① 让「同一场景」的相似度被无谓拉低（用户多发一条消息，指纹就掉一大截）；
     *   ② 让「不同场景」因为共用这些噪声文本而虚高，产生假匹配。
     *
     * 判据刻意保守：只滤**明显的**动态文本，拿不准的一律保留 —— 误滤掉一个真正的
     * 结构性锚点（比如某个按钮文案）比多留一条噪声更伤，因为锚点是回放的定位依据。
     */
    fun isVolatileText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // 纯数字 / 纯标点 / 单字符：计数、页码、进度
        if (t.length <= 1) return false
        if (VOLATILE_NUMERIC.matches(t)) return true
        // 时间与日期
        if (VOLATILE_TIME.containsMatchIn(t)) return true
        // 百分比 / 进度
        if (VOLATILE_PERCENT.containsMatchIn(t)) return true
        // 版本号与模型标识
        if (VOLATILE_VERSION.containsMatchIn(t)) return true
        // 形如 v1.2.3 / 4.1-FlashHigh 的模型名
        for (m in VOLATILE_MODEL_HINTS) if (t.contains(m, ignoreCase = true)) return true
        return false
    }

    /** 纯数字（可带千分位/小数点/单位后缀） */
    private val VOLATILE_NUMERIC = Regex("""^[0-9][0-9,.]*\s*(ms|s|m|h|MB|GB|KB|B|%|个|条|项|次|字)?$""")

    /** 时间：12:34、12:34:56、2026-11-12、11月12日 */
    private val VOLATILE_TIME = Regex(
        """(^|\s)([01]?[0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?($|\s)""" +
            """|[0-9]{4}[-/年][0-9]{1,2}[-/月][0-9]{1,2}|[0-9]{1,2}月[0-9]{1,2}日""",
    )

    /** 百分比与进度 */
    private val VOLATILE_PERCENT = Regex("""[0-9]+\s*%""")

    /** 版本号：v1.2.3 / 1.6.0-beta.1 / 0.2.2-rc.1 */
    private val VOLATILE_VERSION = Regex(
        """(^|\s)v?[0-9]+\.[0-9]+(\.[0-9]+)?(-[a-z0-9.]+)?($|\s)""",
    )

    private val VOLATILE_MODEL_HINTS = listOf(
        "DeepSeek-V", "FlashHigh", "Flash-Lite", "预览版", "标准模式", "探索未至之境",
        "beta", "rc.", "loading", "…",
    )


    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var inter = 0
        for (x in a) if (b.contains(x)) inter++
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** 查记忆：返回 (entry, 相似度)；无匹配返回 (null, 最高相似度) */
    fun lookup(ctx: Context, goal: String, fp: Set<String>): Pair<Entry?, Double> {
        var best: Entry? = null
        var bestSim = 0.0
        for (e in load(ctx)) {
            if (e.goal != goal) continue
            if (e.pending) continue   // 待确认记忆不参与命中（防污染）
            val sim = jaccard(fp, e.fp)
            if (sim > bestSim) {
                best = e
                bestSim = sim
            }
        }
        return if (best != null && bestSim >= SIM_THRESHOLD) best to bestSim else null to bestSim
    }

    /** 存成功决策（同场景同目标则更新） */
    fun store(ctx: Context, goal: String, fp: Set<String>, plan: JSONObject,
              pending: Boolean = false) {
        val entries = load(ctx)
        val now = System.currentTimeMillis()
        for (e in entries) {
            if (e.goal == goal && jaccard(fp, e.fp) >= 0.9) {
                e.plan = plan
                e.misses = 0
                e.updated = now
                // v1.2.139 自动沉淀：同一轨迹**再次成功** → 计数；连续 2 次即转正。
                // 设计取舍：单次成功可能有偶然性（故先 pending），但同一指纹下
                // 重复成功说明计划稳定——此时等云端确认纯属浪费往返。
                if (e.pending && pending) {
                    e.pendingHits++
                    if (e.pendingHits >= 2) {
                        e.pending = false
                        Log.i(TAG, "auto-promoted goal=$goal (pendingHits=${e.pendingHits})")
                    }
                }
                save(ctx, entries)
                Log.i(TAG, "updated entry for goal=$goal (hits=${e.hits} pending=${e.pending} pendingHits=${e.pendingHits})")
                return
            }
        }
        entries.add(
            Entry(fp = fp, goal = goal, plan = plan, hits = 0, misses = 0,
                created = now, updated = now, pending = pending,
                // 首次成功本身即计 1（自动沉淀）：下一次同轨迹成功就转正，
                // 共 2 次验证即生效——尽快把稳定路径沉淀为零推理回放。
                pendingHits = if (pending) 1 else 0)
        )
        save(ctx, entries)
        Log.i(TAG, "stored new entry for goal=$goal (total=${entries.size})")
    }

    /**
     * 任务级确认（v1.2.126）：云端判定目标达成后调用 → pending 记忆转正。
     * @return 转正条数
     */
    fun confirm(ctx: Context, goal: String, fp: Set<String>? = null): Int {
        val entries = load(ctx)
        var n = 0
        val now = System.currentTimeMillis()
        for (e in entries) {
            if (e.goal != goal || !e.pending) continue
            if (fp != null && jaccard(fp, e.fp) < SIM_THRESHOLD) continue
            e.pending = false
            e.updated = now
            n++
        }
        if (n > 0) save(ctx, entries)
        Log.i(TAG, "confirm: $n pending entries promoted for goal=$goal")
        return n
    }

    /** 诊断：pending 条数 */
    fun pendingCount(ctx: Context): Int = load(ctx).count { it.pending }

    fun markHit(ctx: Context, entry: Entry) {
        val entries = load(ctx)
        entries.find { it.goal == entry.goal && it.created == entry.created }?.let {
            it.hits++
            it.misses = 0
            it.updated = System.currentTimeMillis()
            save(ctx, entries)
        }
    }

    /** 复用失败：计数，超阈值淘汰。返回 true = 已淘汰 */
    fun markMiss(ctx: Context, entry: Entry): Boolean {
        val entries = load(ctx)
        val target = entries.find { it.goal == entry.goal && it.created == entry.created }
            ?: return false
        target.misses++
        if (target.misses >= MAX_MISSES) {
            entries.remove(target)
            save(ctx, entries)
            Log.i(TAG, "evicted entry for goal=${entry.goal} (misses=$MAX_MISSES)")
            return true
        }
        save(ctx, entries)
        return false
    }

    /**
     * 统计摘要（含待确认条数：pending 不参与命中，诊断时必须可见）。（`GET /memory/stats`）。**legacy**，新写入全走 v2。
     *
     * 文案刻意点明「v1」，并把 v2 条数并列 —— 用户实测就是被
     * 「v1 说 0 条、v2 说有 1 条」这组矛盾卡住的，不写清归属只能靠猜。
     */
    fun stats(ctx: Context): String {
        val entries = load(ctx)
        val hits = entries.sumOf { it.hits }
        val pending = entries.count { it.pending }
        val v2 = runCatching { MemoryStore.list(ctx).size }.getOrDefault(-1)
        val v2Part = if (v2 >= 0) "; v2 (current store) has $v2" else ""
        return "v1 legacy store: ${entries.size} memories ($pending pending), " +
            "reused $hits times$v2Part"
    }

    /**
     * v1 记忆清单（`GET /memory/list`）。
     *
     * ⚠️ 这**已经是 legacy**：v2（`/memory/v2/`）才是现行存储，两者是**两套独立
     * 数据集**，不是同一份数据的两个视图。用户实测踩到过：`/memory/stats` 回
     * `记忆条目 0 条`，而 `/memory/v2/stats` 回 `memories=1` —— 看着像矛盾，
     * 其实是左眼看 A 库右眼看 B 库。
     *
     * 这里显式标注 `legacy:true` 与 `hint`，并（尽力）报出 v2 侧条数，
     * 让调用方一眼看出「该看哪个」，不必自己去猜。
     */
    fun listJson(ctx: Context): String {
        val arr = JSONArray()
        for (e in load(ctx)) {
            arr.put(JSONObject().apply {
                put("goal", e.goal)
                put("pending", e.pending)
                put("pendingHits", e.pendingHits)
                put("hits", e.hits)
                put("misses", e.misses)
                put("fpSize", e.fp.size)
                put("fpSample", JSONArray(e.fp.take(6).toList()))
                put("steps", e.plan.optJSONArray("steps")?.length() ?: 0)
                put("updated", e.updated)
            })
        }
        val legacyCount = arr.length()
        return JSONObject()
            .put("ok", true)
            .put("legacy", true)
            .put("entries", arr)
            .put("count", legacyCount)
            // v1 已停用（新写入全走 v2）。条数为 0 是**正常**的，不是记忆丢了。
            .put(
                "hint",
                if (legacyCount == 0) {
                    "v1 storage is legacy and no longer written; 0 here is expected. " +
                        "Use /memory/v2/list with /memory/v2/stats for the current store."
                } else {
                    "v1 storage is legacy (read-only). /memory/v2 is the current store."
                },
            )
            .toString()
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
        Log.i(TAG, "memory cleared")
    }
}

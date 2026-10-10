package app.dsh.mobile.engine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地快速决策层（v1.2.120）—— 主人设计的「非黑即白判断题」架构。
 *
 * ## 架构意图（主人原话）
 * 本地跑一个**极小**模型，**只做判断题**：给定屏幕文本 + 目标描述，
 * 立刻回答「该点哪个节点」。**不输出坐标**（坐标由无障碍层从节点直接取），
 * **不看图片**（不做视觉推理）—— 因此模型可以极小、延迟可以毫秒级。
 * 云端模型负责高层规划与异常兜底。
 *
 * ## 为什么这样能快
 * 传统路径：截图 → 上传 → 大模型视觉推理 → 返回坐标 → 点击，一次 2-5 秒。
 * 本路径：屏幕文本（本地已有）→ 极小模型选节点（**毫秒级**）→ 无障碍层取坐标
 * → 点击。**省掉了图片编码、上传、大模型推理三段时间**。
 *
 * ## 三层回退（保证永不失败）
 * ① **精确匹配**（零延迟）：目标文本与节点文本完全相同/包含 → 直接用
 * ② **语义匹配**（毫秒级，需 llama-server + 嵌入模型）：余弦相似度选最相关节点
 * ③ **云端兜底**：本地判断不了 → 交回给 AI（AI 用 scr dump 自己决定）
 *
 * 前三层都在本地，只有 ③ 才走模型往返 —— 绝大多数常见操作会命中 ①②。
 */
object FastPicker {

    private const val TAG = "FastPicker"

    /** 候选节点（来自无障碍层的紧凑 dump） */
    data class Candidate(
        val index: Int,
        val text: String,
        val x: Int,
        val y: Int,
        val clickable: Boolean,
    )

    /** 选择结果 */
    data class Pick(
        val candidate: Candidate?,
        /** 命中方式：exact | semantic | none */
        val via: String,
        /** 置信度 0..1（semantic 时为余弦相似度） */
        val score: Double,
    )

    /**
     * 从紧凑 dump 文本解析候选节点。
     * 格式（见 DshAccessibilityService.dumpScreenCompact）：
     * `序号 文本 @x,y [标志]`，标志含 c=clickable
     */
    fun parseCompact(dump: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        dump.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.isEmpty()) return@forEach
            // 形如: `3 设置 @540,1232 c`  或  `0 - @540,243 c`
            val at = l.lastIndexOf('@')
            if (at < 0) return@forEach
            val head = l.substring(0, at).trim()
            val tail = l.substring(at + 1).trim()
            val sp = head.indexOf(' ')
            if (sp < 0) return@forEach
            val idx = head.substring(0, sp).trim().toIntOrNull() ?: return@forEach
            val text = head.substring(sp + 1).trim()
            val comma = tail.indexOf(',')
            if (comma < 0) return@forEach
            val x = tail.substring(0, comma).trim().split(' ')[0].toIntOrNull() ?: return@forEach
            val y = tail.substring(comma + 1).trim().split(' ')[0].toIntOrNull() ?: return@forEach
            val clickable = tail.contains(" c") || tail.endsWith("c")
            out.add(Candidate(idx, text, x, y, clickable))
        }
        return out
    }

    /**
     * 挑一个最该点的节点（本地、无网络）。
     *
     * @param dump 紧凑 dump 文本
     * @param target 目标描述（用户/模型给的，如「WLAN 开关」）
     * @param preferClickable 是否只考虑可点击节点（默认 true —— 自动化点击的目标
     *   绝大多数是 clickable，过滤能显著降噪）
     * @return 选择结果；candidate 为 null 表示本地判断不了（应交云端兜底）
     */
    fun pick(dump: String, target: String, preferClickable: Boolean = true): Pick {
        val all = parseCompact(dump)
        if (all.isEmpty()) return Pick(null, "none", 0.0)
        // ⚠️ v1.2.120 实测修正：紧凑 dump 里**可点击的是容器节点、其文本是 `-`**，
        // 真正的文本在子节点上（如 `6 网络和互联网`，无 c 标记）。旧实现只挑
        // clickable → 候选全是 "-"，永远选不中（实测 pick 全部 via=none）。
        // 正确语义：**文本节点是目标**，点它的坐标（事件冒泡到可点击祖先 ——
        // 与 tap-text 同一原理）。故这里优先用「有文本」的节点，
        // preferClickable 仅用于**优先排序**（可点击的排前面）而非过滤。
        val candidates = all.filter { it.text.isNotBlank() && it.text != "-" }
        if (candidates.isEmpty()) return Pick(null, "none", 0.0)
        val want = target.trim()
        if (want.isEmpty()) return Pick(null, "none", 0.0)

        // 可点击的节点排前面（同分优先），但不排除文本节点
        val ordered = if (preferClickable) {
            candidates.sortedByDescending { it.clickable }
        } else candidates

        // ① 精确匹配（零延迟）：完全相同 → 包含 → 被包含
        ordered.firstOrNull { it.text == want }?.let {
            return Pick(it, "exact", 1.0)
        }
        ordered.firstOrNull { it.text.contains(want, ignoreCase = true) }?.let {
            return Pick(it, "exact", 0.95)
        }
        ordered.firstOrNull {
            it.text.isNotBlank() && want.contains(it.text, ignoreCase = true) && it.text.length >= 2
        }?.let {
            return Pick(it, "exact", 0.9)
        }

        // ② 语义匹配（需本地嵌入服务；不可用时返回 none → 云端兜底）
        val emb = LocalEmbedder.bestMatch(ordered.map { it.text }, want)
        if (emb != null && emb.score >= SEMANTIC_MIN_SCORE) {
            return Pick(ordered[emb.index], "semantic", emb.score)
        }
        return Pick(null, "none", emb?.score ?: 0.0)
    }

    /** 语义命中阈值：低于此值宁可交云端，也不要瞎点（误点比慢更糟） */
    private const val SEMANTIC_MIN_SCORE = 0.62
}

/**
 * 本地嵌入服务客户端（v1.2.120）。
 *
 * 与 `llama-server`（扩展 llama-cpp 提供）通信：它在本地起一个 OpenAI 兼容
 * HTTP 服务，`POST /v1/embeddings` 返回向量。我们用最小模型
 * （bge-small-zh-v1.5-q4，**15MB**，24M 参数）算「目标描述 vs 各节点文本」的
 * 余弦相似度，取最高者 —— 这是**毫秒级**的判断，无网络往返。
 *
 * ## 为什么用嵌入而不是生成
 * 生成式模型要逐 token 解码（几百毫秒到秒级）；嵌入是一次前向传播，
 * 15MB 模型在手机上**几毫秒**就能算完。而且我们的任务本质是「排序选优」，
 * 嵌入天然适合。
 *
 * ## 不可用时的行为
 * 服务没起 / 模型没下 → 返回 null，FastPicker 回退到云端兜底（不阻塞、不报错）。
 */
object LocalEmbedder {

    private const val TAG = "LocalEmbedder"

    /** llama-server 默认端口（避开引擎 3080 / 桥 3083） */
    const val PORT = 3085

    data class Match(val index: Int, val score: Double)

    /** 服务是否就绪（缓存，避免每次都探测） */
    @Volatile private var ready: Boolean? = null
    @Volatile private var lastProbe = 0L

    /** 探测本地嵌入服务（带 30 秒缓存，避免高频轮询） */
    fun isReady(): Boolean {
        val now = System.currentTimeMillis()
        val cached = ready
        if (cached != null && now - lastProbe < 30_000) return cached
        val ok = runCatching {
            val conn = java.net.URL("http://127.0.0.1:$PORT/health").openConnection()
            conn.connectTimeout = 400
            conn.readTimeout = 400
            conn.getInputStream().close()
            true
        }.getOrDefault(false)
        ready = ok
        lastProbe = now
        return ok
    }

    /**
     * 在候选文本里找与目标最相关的一个。
     * @return null = 服务不可用（调用方回退云端）
     */
    fun bestMatch(candidates: List<String>, target: String): Match? {
        if (candidates.isEmpty() || target.isBlank()) return null
        if (!isReady()) return null
        return runCatching {
            val inputs = (listOf(target) + candidates)
            val body = JSONObject().apply {
                put("model", "local")
                put("input", JSONArray(inputs))
            }.toString()
            val conn = java.net.URL("http://127.0.0.1:$PORT/v1/embeddings").openConnection()
            conn.doOutput = true
            conn.connectTimeout = 2000
            conn.readTimeout = 4000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val resp = conn.getInputStream().bufferedReader().use { it.readText() }
            val vecs = parseEmbeddings(resp, inputs.size) ?: return@runCatching null
            if (vecs.size < 2) return@runCatching null
            val targetVec = vecs[0]
            var bestIdx = -1
            var bestScore = -1.0
            for (i in 1 until vecs.size) {
                val s = cosine(targetVec, vecs[i])
                if (s > bestScore) { bestScore = s; bestIdx = i - 1 }
            }
            if (bestIdx < 0) null else Match(bestIdx, bestScore)
        }.getOrElse {
            Log.w(TAG, "embedding failed: ${it.message}")
            null
        }
    }

    private fun parseEmbeddings(json: String, expect: Int): List<DoubleArray>? = runCatching {
        val arr = JSONObject(json).getJSONArray("data")
        val out = ArrayList<DoubleArray>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i).getJSONArray("embedding")
            val v = DoubleArray(e.length()) { e.getDouble(it) }
            out.add(v)
        }
        out
    }.getOrNull()

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val d = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (d == 0.0) 0.0 else dot / d
    }
}

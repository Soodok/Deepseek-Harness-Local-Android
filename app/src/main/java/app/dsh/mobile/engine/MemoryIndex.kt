package app.dsh.mobile.engine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * 一条被打分后的召回结果（设计文档 §3C 的「top-3 + 分数 + 分档」）。
 *
 * @param cos 语义相似度：bge 向量 / 字符 bigram 哈希 / 关键词包含 三级里能拿到的最高的那个
 * @param fp 结构与记忆起点屏的 Jaccard（屏幕文本集合）
 * @param anchor 记忆首步的锚点文本能否在当前可见文本里找到（找不到不代表不能用，只降权）
 * @param score 0.6*cos + 0.3*fp + 0.1*anchor
 * @param tier high / mid / low —— high 直接重放，mid 交给模型确认一次，low 不召回
 */
data class Scored(
    val entry: MemoryEntry,
    val cos: Double,
    val fp: Double,
    val anchor: Boolean,
    val score: Double,
    val tier: String,
)

/**
 * 记忆检索（M4）：三级降级 + 结构打分 + 分档（设计文档 §3C）。
 *
 * ## 三级降级（不阻塞主链路）
 * 1. **bge 嵌入**：`LocalModel` 起的 llama-server（127.0.0.1:3085）算 512 维向量，毫秒级；
 * 2. **字符 bigram 哈希**：纯 Kotlin 的 hashing trick（[embedFallback]），无网络、无模型，
 *    宿主机 JVM 上也能跑 —— 这是本文件能被单测的关键；
 * 3. **关键词包含**：记忆自带的 keywords 是否出现在意图里。
 *
 * 为什么非要降级链：模型是用户可选下载的（15MB + 扩展 llama-cpp）。模型没下/服务没起就
 * 让「记忆复用」整体失效，等于把最贵的那个功能绑在最脆的依赖上。降级后最差退化成关键词，
 * 仍然比 v1 的「goal 必须全等」强。
 *
 * ## 为什么打分是纯函数
 * [rank] 不碰 Context、不碰网络、不读文件：向量由调用方（录像/入库时）先算好塞进
 * [MemoryEntry.vector]，意图向量也可以显式传进来（[rank] 的 queryVector）。
 * 于是整套排序、阈值、分档都能在 `scripts/jvm/BehaviourTest.kt` 里逐条断言，
 * 而不是上线后靠「感觉命中率不高」盲猜。
 *
 * ## 注意：rank 里不打日志
 * android.util.Log 在宿主机 JVM 上是 android.jar 的空壳（一调就抛 "Stub!"），
 * 纯函数里调它会让整套 JVM 断言直接崩。所以级别日志只放在 [embed]（真机才会调），
 * 纯逻辑用 [engineOf] 把「这条走的哪一级」算出来交给调用方记录。
 */
object MemoryIndex {

    private const val TAG = "MemoryIndex"

    /** 权重（设计文档 §3C 第 4 步）：余弦占大头，结构次之，锚点只是加分项。 */
    const val W_COS = 0.6
    const val W_FP = 0.3
    const val W_ANCHOR = 0.1

    /** 分档阈值：>= HIGH 直接重放；>= MID 让模型确认一次；否则不召回。 */
    const val TIER_HIGH = 0.85
    const val TIER_MID = 0.65

    const val TIER_NAME_HIGH = "high"
    const val TIER_NAME_MID = "mid"
    const val TIER_NAME_LOW = "low"

    /** 哈希兜底向量的维度：256 维在中文短句上够用，且比 512 省一半内存与比较时间。 */
    const val FALLBACK_DIM = 256

    const val ENGINE_BGE = "bge"
    const val ENGINE_HASH = "hash"
    const val ENGINE_KEYWORD = "keyword"
    const val ENGINE_NONE = "none"

    private const val PKG_PREFIX = "pkg:"

    // ==================== 嵌入（一级：需要服务） ====================

    /**
     * 用本地 bge 算意图向量（512 维）。**服务不可用返回 null**，绝不抛。
     *
     * 调用方式与 FastPicker 的 [LocalEmbedder] 完全一致（同一个 llama-server、同一组超时），
     * 但那边只暴露「批量里挑最像的一个」，拿不到原始向量，所以这里单独发一次单条请求。
     * 就绪探测**复用** [LocalEmbedder.isReady]：它带 30 秒缓存，不必每步都去敲 /health。
     */
    fun embed(text: String): List<Float>? {
        if (text.isBlank()) return null
        if (!LocalEmbedder.isReady()) {
            Log.i(TAG, "embed via none (llama-server not ready on :" + LocalEmbedder.PORT + ")")
            return null
        }
        return runCatching {
            val body = JSONObject().apply {
                put("model", "local")
                put("input", JSONArray().put(text))
            }.toString()
            val conn = java.net.URL("http://127.0.0.1:" + LocalEmbedder.PORT + "/v1/embeddings").openConnection()
            conn.doOutput = true
            conn.connectTimeout = 2000
            // 与 LocalEmbedder 同口径：热态毫秒级，冷加载不该由这里买单（实测一次冷调用能吃 8s）
            conn.readTimeout = 2500
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val vec = parseVector(resp)
            if (vec.isEmpty()) {
                Log.w(TAG, "embed via none (empty vector from llama-server)")
                null
            } else {
                Log.i(TAG, "embed via bge dim=" + vec.size)
                vec
            }
        }.getOrElse {
            Log.w(TAG, "embed via none (bge failed: " + it.message + ")")
            null
        }
    }

    private fun parseVector(json: String): List<Float> = runCatching {
        val data = JSONObject(json).optJSONArray("data") ?: return@runCatching emptyList()
        val first = data.optJSONObject(0) ?: return@runCatching emptyList()
        val e = first.optJSONArray("embedding") ?: return@runCatching emptyList()
        val out = ArrayList<Float>(e.length())
        for (i in 0 until e.length()) out.add(e.optDouble(i, 0.0).toFloat())
        out
    }.getOrDefault(emptyList())

    // ==================== 哈希兜底（二级：纯 Kotlin） ====================

    /**
     * 字符 bigram 哈希向量（hashing trick）。无网络、无模型、结果确定。
     *
     * 做法：把文本压成去掉空白的小写串，取**单字**（权重 0.5）与**相邻二字**（权重 1.0）
     * 两类特征，各自哈希到 [dim] 个桶里累加（符号取哈希值正负，让碰撞互相抵消而不是单边堆高），
     * 最后 L2 归一化 —— 这样 [cosine] 就等价于「共享了多少字/二元组」。
     *
     * 为什么用 bigram 而不是分词：中文没有空格，「打开深色主题」与「深色主题打开」的
     * 单字集合完全相同；二元组才有语序感。为什么不用词表：嵌不进 APK，也没必要 ——
     * 这一级只求「比关键词包含略强」的兜底，语义交给 bge。
     *
     * @return 长度恒为 dim 的归一化向量；空文本返回全 0（[cosine] 会给 0，不抛）。
     */
    fun embedFallback(text: String, dim: Int = FALLBACK_DIM): List<Float> {
        val n = if (dim <= 0) FALLBACK_DIM else dim
        val buf = StringBuilder(text.length)
        for (c in text.trim().lowercase()) if (!c.isWhitespace()) buf.append(c)
        val s = buf.toString()
        if (s.isEmpty()) return MutableList(n) { 0f }

        val vec = FloatArray(n)
        for (i in s.indices) {
            addFeature(vec, "1:" + s[i], 0.5f)
            if (i + 1 < s.length) addFeature(vec, "2:" + s[i] + s[i + 1], 1.0f)
        }

        var norm = 0.0
        for (x in vec) norm += x.toDouble() * x
        norm = sqrt(norm)
        if (norm == 0.0) return MutableList(n) { 0f }
        val out = ArrayList<Float>(n)
        for (x in vec) out.add((x / norm).toFloat())
        return out
    }

    private fun addFeature(vec: FloatArray, feature: String, weight: Float) {
        val h = feature.hashCode()
        val idx = (h and 0x7fffffff) % vec.size
        val sign = if (h < 0) -1f else 1f
        vec[idx] += sign * weight
    }

    // ==================== 相似度与打分 ====================

    /**
     * 余弦相似度，范围 [-1, 1]。
     *
     * 尺寸不等 / 有零向量 → 0.0（「同长度才能比」不是洁癖：bge 512 维与哈希 256 维直接比
     * 会把下标当同义词，得到看似合理实则无意义的分数）。结果再夹到 [-1,1]：浮点误差
     * 会让同向向量算出 1.0000000002，放任它会让加权总分越界、阈值判断飘。
     */
    fun cosine(a: List<Float>, b: List<Float>): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        val d = sqrt(na) * sqrt(nb)
        if (d == 0.0 || d.isNaN()) return 0.0
        val c = dot / d
        return if (c > 1.0) 1.0 else if (c < -1.0) -1.0 else c
    }

    /**
     * 加权总分（设计文档 §3C 第 4 步）。
     *
     * 不做四舍五入：阈值就该作用在真实数值上，提前取整会把 0.8496 抬成 0.85 ——
     * 那是「中置信」被当成「高置信」直接重放，属于安全侧的错。
     */
    fun score(cos: Double, fpJaccard: Double, anchorFound: Boolean): Double {
        val c = if (cos.isNaN()) 0.0 else if (cos > 1.0) 1.0 else if (cos < -1.0) -1.0 else cos
        val f = if (fpJaccard.isNaN()) 0.0 else if (fpJaccard < 0.0) 0.0 else if (fpJaccard > 1.0) 1.0 else fpJaccard
        return W_COS * c + W_FP * f + (if (anchorFound) W_ANCHOR else 0.0)
    }

    /** 分档：`>= 0.85` high、`>= 0.65` mid、其余 low；NaN 当 low（宁可少召回，不要瞎召回）。 */
    fun tier(score: Double): String = when {
        score.isNaN() -> TIER_NAME_LOW
        score >= TIER_HIGH -> TIER_NAME_HIGH
        score >= TIER_MID -> TIER_NAME_MID
        else -> TIER_NAME_LOW
    }

    // ==================== 召回排序（纯函数） ====================

    /**
     * 打分并排序，返回 top-[limit]。
     *
     * @param entries 候选记忆。**调用方应先用 pkg 取**（`MemoryStore.list(ctx, pkg)`）；
     *                这里还会用 [fp] 里的 `pkg:` 元素再拦一道（见下）。
     * @param intent 当前任务的归一化意图（不在记忆里存的原文，AI 每次给的都可能有出入）。
     * @param fp 当前屏幕指纹（[SceneMemory.fingerprint] 的产物，**含 `pkg:` 元素**）。
     * @param anchorTexts 当前屏幕上可见的文本集合（用来判断记忆首步的锚点还在不在）。
     * @param limit 最多返回几条；<=0 返回空。
     * @param queryVector 意图的 bge 向量。**用 bge 时必须传**：不然 512 维的 entry.vector
     *                    只能跟 256 维的哈希向量比，尺寸不等一律 0 分（见 [cosine]）。
     *
     * 三条规则（都在断言里钉住）：
     * 1. **disabled 永不返回**（负样本，只用来告诉 AI「别再这么干」）；
     * 2. **跨 App 绝不召回**：fp 里带 `pkg:X` 时，pkg 不等于 X 的条目直接跳过 ——
     *    不同应用的同名页面文本高度相似，v1.2.139 就是在这上面踩过误命中。
     *    fp 里没有 `pkg:` 时无法判断归属，只能不过滤（调用方必须保证 dump 带前台包名）；
     * 3. **suspect 最高只给 mid**：连续失败过的记忆不该再「直接重放、不惊动模型」，
     *    让它先过一次模型确认（这正是 state=suspect 的语义）。
     */
    fun rank(
        entries: List<MemoryEntry>,
        intent: String,
        fp: Set<String>,
        anchorTexts: Set<String>,
        limit: Int = 3,
        queryVector: List<Float>? = null,
    ): List<Scored> {
        if (entries.isEmpty() || limit <= 0) return emptyList()

        val wantPkg = pkgOfFp(fp)
        val qHash = embedFallback(intent)
        val qVec = queryVector ?: qHash
        val out = ArrayList<Scored>(entries.size)

        for (e in entries) {
            if (e.state == MemoryContract.STATE_DISABLED) continue
            if (wantPkg != null && e.pkg != wantPkg) continue

            val semantic = semanticOf(e, qHash, qVec)
            val kw = keywordScore(intent, e.keywords)
            val cos = if (kw > semantic) kw else semantic

            val fpScore = jaccard(fp, e.fingerprint())
            val anchor = anchorHit(e.steps.firstOrNull(), anchorTexts)
            val s = score(cos, fpScore, anchor)
            var t = tier(s)
            if (e.state == MemoryContract.STATE_SUSPECT && t == TIER_NAME_HIGH) t = TIER_NAME_MID
            out.add(Scored(e, cos, fpScore, anchor, s, t))
        }

        // 同分稳定性：分数 → 命中次数 → id。只按分数排的话，两条同分记忆的先后
        // 会随入参顺序变化，重放结果就成了掷骰子。
        out.sortWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { it.entry.hits }
                .thenBy { it.entry.id },
        )
        return if (out.size <= limit) out else ArrayList(out.subList(0, limit))
    }

    /**
     * 这条记忆是靠哪一级算出来的（给日志与诊断用；纯函数，可断言）。
     *
     * 线上排查「为什么没命中」时，第一件事就是分清「向量算了但不像」还是
     * 「模型没起、整条链路降到了关键词」。没有这个函数，只能靠猜。
     */
    fun engineOf(e: MemoryEntry, intent: String): String {
        if (e.vector.isNotEmpty()) return ENGINE_BGE
        val kw = keywordScore(intent, e.keywords)
        if (intent.isBlank() || e.intent.isBlank()) {
            return if (kw > 0.0) ENGINE_KEYWORD else ENGINE_NONE
        }
        val sem = cosine(embedFallback(e.intent), embedFallback(intent))
        return when {
            kw > sem -> ENGINE_KEYWORD
            sem > 0.0 -> ENGINE_HASH
            else -> ENGINE_NONE
        }
    }

    // ==================== 内部 ====================

    /**
     * 语义分：优先用条目自带的向量（bge 或已存好的哈希），都没有就现算哈希。
     *
     * 尺寸对不上时退到「同为哈希维」的那一档比较 —— 调用方可能给 queryVector（bge 512），
     * 而这条记忆只有哈希 256 维：直接比会得 0 分（尺寸不等），但两者其实可以各自哈希后比。
     */
    private fun semanticOf(e: MemoryEntry, qHash: List<Float>, qVec: List<Float>): Double {
        if (e.vector.isEmpty()) return cosine(embedFallback(e.intent), qHash)
        if (e.vector.size == qVec.size) return cosine(e.vector, qVec)
        if (e.vector.size == qHash.size) return cosine(e.vector, qHash)
        return 0.0
    }

    /** 关键词包含率：命中的关键词数 / 关键词总数，[0,1]。
     *  只做「意图里是否出现该关键词」的单向包含 —— 反向包含会把「设置」这种短词变成万能钥匙。 */
    private fun keywordScore(intent: String, keywords: List<String>): Double {
        if (keywords.isEmpty()) return 0.0
        val q = intent.lowercase()
        if (q.isBlank()) return 0.0
        var hit = 0
        for (k in keywords) {
            val t = k.trim().lowercase()
            if (t.isNotEmpty() && q.contains(t)) hit++
        }
        return hit.toDouble() / keywords.size
    }

    /** 结构相似度：屏幕文本集合的 Jaccard。空集合一律 0（彼此都不像，别拿 0/0 当 1）。 */
    private fun jaccard(a: Set<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val bs = HashSet<String>(b.size)
        for (t in b) bs.add(t)
        var inter = 0
        for (t in a) if (bs.contains(t)) inter++
        val union = a.size + bs.size - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    /**
     * 首步锚点还在不在当前屏幕上。
     *
     * 只做**文本层**判定：viewId/bounds 能不能真的点到，要靠 M5 的 FastPicker 拿真 dump 试，
     * 本函数在纯函数里回答不了。文本都没有的锚点（只有坐标）一律 false —— 宁可少给 0.1 分，
     * 也不要凭坐标猜「大概在」。
     */
    private fun anchorHit(step: MemoryStep?, visible: Set<String>): Boolean {
        if (step == null || !step.hasAnchor()) return false
        val want = step.targetText.trim()
        if (want.isEmpty()) return false
        for (v in visible) {
            val t = v.trim()
            if (t.isEmpty()) continue
            if (t.equals(want, ignoreCase = true)) return true
            if (want.length >= 2 && t.contains(want, ignoreCase = true)) return true
        }
        return false
    }

    /** fp 里的前台包名（[SceneMemory.fingerprint] 会加 `pkg:` 前缀）。没有返回 null。 */
    private fun pkgOfFp(fp: Set<String>): String? {
        for (t in fp) {
            if (!t.startsWith(PKG_PREFIX)) continue
            val name = t.substring(PKG_PREFIX.length).trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }
}

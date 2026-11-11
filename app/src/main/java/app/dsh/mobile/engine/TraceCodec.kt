package app.dsh.mobile.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 头行（契约 §2.1）的 Kotlin 投影。
 *
 * 单独一个类而不是塞进 [TraceDoc]：头行是**写盘格式**，[TraceDoc] 是**内存视图**，
 * 两者字段并不完全一样（头行带 device 分辨率，TraceDoc 不带）。硬合成一个类的话，
 * 每次 list() 只读头行也要伪造一堆空的 steps 字段，读的人分不清「空」和「没有」。
 */
data class TraceHead(
    val traceId: String,
    val startedAt: Long,
    val pkg: String = "",
    val goal: String = "",
    val w: Int = 0,
    val h: Int = 0,
    val density: Int = 0,
    val schema: Int = TraceContract.SCHEMA,
)

/** 尾行（契约 §2.3）。[ok]=false 表示任务没成功；[reason] 是给人看的短句。 */
data class TraceEnd(
    val ts: Long,
    val ok: Boolean,
    val reason: String = "",
)

/**
 * 轨迹行 ↔ JSON（契约 docs/A11Y_TRACE_CONTRACT.md §2）。
 *
 * ## 为什么单独一层
 * 本文件依赖 org.json，而 org.json 在宿主机 JVM 上是 android.jar 的空壳
 * （调用即 "Stub!"），所以它**不进** scripts/run-a11y-jvm-test.sh：
 * 纯逻辑留在 TraceModel / TraceRetention 单测，格式转换只在这里。
 *
 * ## 三条硬约定
 * 1. **一行一对象**：encode* 的返回值不含裸换行（org.json 会把换行转义成两个字符，
 *    这里再兜一层 oneLine，防止某个字段带进真换行、把整个文件的行结构打散）。
 * 2. **解码永不抛**：契约 §6 要求读取方容忍未知字段、缺字段用默认值；
 *    这里连「字段类型不对」也一并吞掉（坏值退化成默认），因为一条坏行不该让
 *    /trace/get 整体 500 —— 原始轨迹是剪枝的唯一退路，读不出来比读错更糟。
 * 3. **args 一律字符串**：TraceStep.args 的类型就是 Map<String, String>，
 *    这里不做「数字就写数字」的猜测："007" 该是字符串还是 7？猜错就改写了参数语义。
 *    解码时 fixture 里的裸数字会转成字符串（800 -> "800"），剪枝只做字符串比较，没有损失。
 */
object TraceCodec {

    // ===== 编码 =====

    /** 头行。device 分辨率不在 [TraceDoc] 里，由调用方（Context.resources.displayMetrics）给。 */
    fun encodeHead(doc: TraceDoc, w: Int, h: Int, density: Int): String {
        val o = JSONObject()
        o.put("type", "trace")
        o.put("traceId", doc.traceId)
        o.put("startedAt", doc.startedAt)
        o.put("pkg", doc.pkg)
        o.put("goal", doc.goal)
        o.put("device", JSONObject().apply {
            put("w", w)
            put("h", h)
            put("density", density)
        })
        o.put("schema", doc.schema)
        return oneLine(o)
    }

    /**
     * 事件行。
     *
     * 比契约 §2.2 多写一个 index：契约允许「只加不删」（§6），而原始顺序是剪枝
     * 回溯「第几步被删掉」的唯一依据 —— 光靠行号，剪枝器一旦重排就再也对不上。
     */
    fun encodeStep(step: TraceStep): String {
        val o = JSONObject()
        o.put("type", "step")
        o.put("index", step.index)
        o.put("ts", step.ts)
        o.put("endpoint", step.endpoint)
        o.put("args", JSONObject().apply {
            // 排序后再写：同一份轨迹两次录制出来字节可比对，排障时 diff 才有意义
            for (k in step.args.keys.sorted()) put(k, step.args.getValue(k))
        })
        o.put("beforeFp", JSONArray().apply { for (t in step.beforeFp) put(t) })
        o.put("afterFp", JSONArray().apply { for (t in step.afterFp) put(t) })
        o.put("anchor", step.anchor?.let { anchorJson(it) } ?: JSONObject.NULL)
        o.put("verify", JSONObject().apply {
            put("status", step.verify.status)
            put("via", step.verify.via)
            put("ms", step.verify.ms)
            put("retries", step.verify.retries)
        })
        o.put("caller", step.caller)
        o.put("protected", step.protected)
        return oneLine(o)
    }

    /** 尾行。任务正常结束、被下一个任务顶掉、用户 clear，都要留一条，否则轨迹永远「无尾」。 */
    fun encodeEnd(ts: Long, ok: Boolean, reason: String): String {
        val o = JSONObject()
        o.put("type", "end")
        o.put("ts", ts)
        o.put("ok", ok)
        o.put("reason", reason)
        return oneLine(o)
    }

    // ===== 解码 =====

    /**
     * 解一行：头行 -> [TraceHead]，事件行 -> [TraceStep]，尾行 -> [TraceEnd]。
     * 未知 type、空行、坏 JSON、以及任何解析异常一律返回 null（契约 §6：读方必须容错）。
     *
     * 注意 [TraceStep.index]：文件里有 index 就用它，没有（老的 fixture）返回 -1，
     * 由调用方按行序补齐 —— 这里不猜，因为「第几行」是调用方比本函数更清楚的事。
     */
    fun decodeLine(line: String): Any? {
        val raw = strip(line)
        if (raw.isEmpty()) return null
        return runCatching {
            val o = JSONObject(raw)
            when (str(o, "type")) {
                "trace" -> headOf(o)
                "step" -> stepOf(o)
                "end" -> TraceEnd(
                    ts = o.optLong("ts", 0L),
                    ok = o.optBoolean("ok", false),
                    reason = str(o, "reason"),
                )
                else -> null
            }
        }.getOrNull()
    }

    // ===== 内部 =====

    /**
     * 单行化兜底。
     *
     * org.json 本就会把换行转义成两个字符，理论上这里是空操作；
     * 但「理论上」一旦被某条编码路径打破，代价是整条轨迹的行结构错位
     * （坏行之后每一行都被当成同一条），所以宁可多这一步。
     */
    private fun oneLine(o: JSONObject): String =
        o.toString().replace('\n', ' ').replace('\r', ' ')

    private fun strip(line: String): String {
        val t = line.trim()
        // Windows 上手工编辑过的 fixture 可能带 BOM，JSONObject 不认它
        return if (t.startsWith("\uFEFF")) t.substring(1).trim() else t
    }

    /** 取字符串字段；显式 null 与缺失都退化成空串（org.json 的 optString 会给 "null"）。 */
    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")

    private fun headOf(o: JSONObject): TraceHead {
        val d = o.optJSONObject("device")
        return TraceHead(
            traceId = str(o, "traceId"),
            startedAt = o.optLong("startedAt", 0L),
            pkg = str(o, "pkg"),
            goal = str(o, "goal"),
            w = d?.optInt("w", 0) ?: 0,
            h = d?.optInt("h", 0) ?: 0,
            density = d?.optInt("density", 0) ?: 0,
            schema = o.optInt("schema", TraceContract.SCHEMA),
        )
    }

    private fun stepOf(o: JSONObject): TraceStep {
        val a = o.optJSONObject("anchor")
        val v = o.optJSONObject("verify")
        return TraceStep(
            index = o.optInt("index", -1),
            ts = o.optLong("ts", 0L),
            endpoint = str(o, "endpoint"),
            args = argsOf(o.optJSONObject("args")),
            beforeFp = fpOf(o.optJSONArray("beforeFp")),
            afterFp = fpOf(o.optJSONArray("afterFp")),
            anchor = a?.let { anchorOf(it) },
            verify = TraceVerify(
                status = if (v == null) TraceStatus.OK else str(v, "status").ifEmpty { TraceStatus.OK },
                via = if (v == null) TraceVia.NONE else str(v, "via").ifEmpty { TraceVia.NONE },
                ms = v?.optLong("ms", 0L) ?: 0L,
                retries = v?.optInt("retries", 0) ?: 0,
            ),
            caller = str(o, "caller").ifEmpty { "loop" },
            protected = o.optBoolean("protected", false),
        )
    }

    private fun anchorJson(a: TraceAnchor): JSONObject = JSONObject().apply {
        put("text", a.text)
        put("role", a.role)
        put("viewId", a.viewId)
        put("bounds", JSONArray().apply { for (f in a.bounds) put(f.toDouble()) })
    }

    private fun anchorOf(o: JSONObject): TraceAnchor = TraceAnchor(
        text = str(o, "text"),
        role = str(o, "role"),
        viewId = str(o, "viewId"),
        bounds = boundsOf(o.optJSONArray("bounds")),
    )

    /**
     * 归一化坐标 [l, t, r, b]。
     *
     * 只要有一个元素不是数字、或者长度不是 4，就整体作废（返回空 list），
     * 不做「跳过坏值」：少一个元素会让 TraceAnchor.isValid() 静默变成 false，
     * 还可能在复用时算出错误的中心点。宁可让上层看到「这条锚点没有 bounds」，
     * 也不能给它一个错位的矩形。
     */
    private fun boundsOf(a: JSONArray?): List<Float> {
        if (a == null || a.length() != 4) return emptyList()
        val out = ArrayList<Float>(4)
        for (i in 0 until a.length()) {
            val d = a.optDouble(i, Double.NaN)
            if (d.isNaN() || d.isInfinite()) return emptyList()
            out.add(d.toFloat())
        }
        return out
    }

    /** 端点参数。数字统一转成字符串（800 -> "800"，800.0 -> "800"）。 */
    private fun argsOf(o: JSONObject?): Map<String, String> {
        if (o == null || o.length() == 0) return emptyMap()
        val out = LinkedHashMap<String, String>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (o.isNull(k)) continue
            when (val v = o.opt(k)) {
                is String -> out[k] = v
                // 整数形态的 Double 去掉尾巴，免得同一个参数在 fixture 与真机轨迹里长得不一样
                is Double ->
                    out[k] = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
                null -> Unit
                else -> out[k] = v.toString()
            }
        }
        return out
    }

    private fun fpOf(a: JSONArray?): Set<String> {
        if (a == null || a.length() == 0) return emptySet()
        val out = LinkedHashSet<String>(a.length())
        for (i in 0 until a.length()) {
            if (a.isNull(i)) continue
            val v = a.opt(i) ?: continue
            val s = if (v is String) v else v.toString()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}

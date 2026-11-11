package app.dsh.mobile.engine

/**
 * 轨迹数据模型（M0 契约 [docs/A11Y_TRACE_CONTRACT.md] 的 Kotlin 投影）。
 *
 * ## 为什么单独抽一层
 * `TaskRecorder`（写）与 `TracePruner`（读）必须能各自**独立单测**，而 `org.json`
 * 在宿主机 JVM 上没有实现（它来自 android.jar，运行时是 "Stub!"）。所以：
 *
 * - 本文件：**纯 Kotlin**，无 android / org.json 依赖 → 直接进 `scripts/run-a11y-jvm-test.sh`
 * - `TraceCodec`：JSON ↔ 本模型（依赖 org.json，只在 App 内跑）
 *
 * 谁要加字段，三处一起改：本文件、`TraceCodec`、契约文档。
 */
object TraceContract {
    const val SCHEMA = 1
    const val DIR = "traces"
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_FILES = 200
    const val MAX_LINES_PER_FILE = 4000
}

/** 动作目标元素。复用时靠它定位，所以 bounds 用归一化 0..1（跨分辨率可用）。 */
data class TraceAnchor(
    val text: String = "",
    val role: String = "",
    val viewId: String = "",
    val bounds: List<Float> = emptyList(),   // [l, t, r, b]，0..1；用 List 而非 FloatArray 才有值相等
) {
    fun isValid(): Boolean = text.isNotBlank() || viewId.isNotBlank() || bounds.size == 4
    fun center(): Pair<Float, Float>? =
        if (bounds.size == 4) (bounds[0] + bounds[2]) / 2f to (bounds[1] + bounds[3]) / 2f else null
}

/** 单步判定结果。 */
data class TraceVerify(
    val status: String = TraceStatus.OK,
    val via: String = TraceVia.NONE,
    val ms: Long = 0,
    val retries: Int = 0,
)

/** 一步动作。index 是原始顺序（剪枝后仍保留原 index，便于回溯）。 */
data class TraceStep(
    val index: Int,
    val ts: Long,
    val endpoint: String,
    val args: Map<String, String> = emptyMap(),
    val beforeFp: Set<String> = emptySet(),
    val afterFp: Set<String> = emptySet(),
    val anchor: TraceAnchor? = null,
    val verify: TraceVerify = TraceVerify(),
    val caller: String = "loop",
    val protected: Boolean = false,
) {
    /** 这一步有没有真的改变屏幕（供剪枝判断「无效动作」）。 */
    fun changedScreen(): Boolean = beforeFp != afterFp

    /** 观测类动作：不改变世界，只获取信息。 */
    fun isObservation(): Boolean = endpoint in TraceEndpoint.OBSERVE

    fun anchorText(): String = anchor?.text?.trim().orEmpty()
}

/** 一次完整任务。 */
data class TraceDoc(
    val traceId: String,
    val startedAt: Long,
    val pkg: String,
    val goal: String,
    val steps: List<TraceStep> = emptyList(),
    val endOk: Boolean? = null,
    val endReason: String = "",
    val schema: Int = TraceContract.SCHEMA,
)

object TraceStatus {
    const val OK = "ok"
    const val UNCHANGED = "unchanged"
    const val FAILED = "failed"
    const val TIMEOUT = "timeout"
    const val SKIPPED = "skipped"
}

object TraceVia {
    const val ANY_TEXT = "anyText"
    const val FRAME = "frameChanged"
    const val PKG = "foregroundPkg"
    const val NONE = "none"
}

object TraceEndpoint {
    const val TAP = "tap"
    const val TAP_ID = "tap_id"
    const val GESTURE = "gesture"
    const val INPUT = "input"
    const val KEY = "key"
    const val SCROLL = "scroll"
    const val LAUNCH = "launch"
    const val LOOP_STEP = "loop_step"
    const val NOOP = "noop"

    /** 观测类：剪枝直接丢。 */
    val OBSERVE = setOf(NOOP, "screen", "dump", "screenshot")

    /** 会产生真实世界副作用的动作。 */
    val ACTIONS = setOf(TAP, TAP_ID, GESTURE, INPUT, KEY, SCROLL, LAUNCH)
}

/**
 * 桥端点的请求字段 → 重放侧认的参数名（纯函数，宿主机 JVM 可断言）。
 *
 * ## 为什么必须有这一层
 * 桥的 `/key` 用 `"action"`，重放读的是 `args["name"]`；`/launch` 允许 `"pkg"` 别名。
 * 不翻译的话轨迹里 `args` 恒为空，于是重放时 `key` 永远按 **back** 执行、
 * `launch` 永远报 "launch needs package or data"、`scroll` 永远按 down ——
 * 实测就是这么踩到的：录下来的 launch 步骤 `args={}`，重放必失败。
 *
 * ## 红线：只记**不含内容**的参数
 * 输入文本（`/input` 的 text）、剪贴板内容、搜索关键词一律不落盘 —— 轨迹文件是明文 JSONL，
 * 写进去等于把用户输入的密码/地址存到磁盘上。所以 `input` 只留**长度**，
 * 重放时明确报「这一步没有可重放的内容」，回落云端重新规划，而不是盲打一段空文本。
 */
/**
 * 一步动作的「后置条件」该怎么取（纯函数，JVM 可断言）。
 *
 * 剪枝把 `afterFp`（整屏指纹）直接当成期望，对 tap/scroll 是够用的弱校验；
 * 但对 `launch` 是**错的**：那一步的作用是把前台换成目标 App，重放后屏幕上理应是
 * **目标 App 的内容**，而录制时的 afterFp 常常还是换包前的桌面/上一个 App
 * （dump 在应用起来之前就拍了）。实测：一条「从桌面打开设置 → 点显示」的记忆，
 * 重放时 launch 步被判 verify-failed，detail 里全是桌面的图标文本。
 *
 * 所以 launch 不设文本后置条件 —— 它换没换包由下一步的前台包守卫（PKG_CHANGED）硬判，
 * 那比「屏幕上出现某段文字」更准。
 */
object TraceExpect {
    fun after(action: String, texts: List<String>): List<String> =
        if (action.equals(TraceEndpoint.LAUNCH, ignoreCase = true)) emptyList() else texts
}

object TraceArgs {

    fun of(endpoint: String, body: Map<String, String>): Map<String, String> {
        fun pick(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> body[k]?.takeIf { it.isNotBlank() } }.orEmpty()

        val out = LinkedHashMap<String, String>()
        when (endpoint.lowercase()) {
            TraceEndpoint.LAUNCH -> {
                val pkg = pick("package", "pkg")
                if (pkg.isNotEmpty()) out["package"] = pkg
                // data/uri 是链接内容，按红线不记；只留「这次是开链接不是开 App」这个事实
                val hasData = !(body["data"].isNullOrBlank() && body["uri"].isNullOrBlank())
                if (pkg.isEmpty() && hasData) out["kind"] = "uri"
            }
            TraceEndpoint.KEY -> {
                val name = pick("name", "action", "code")
                if (name.isNotEmpty()) out["name"] = name
            }
            TraceEndpoint.INPUT -> {
                // 只记长度，不记内容（见上方红线）
                val text = pick("text", "value")
                if (text.isNotEmpty()) out["len"] = text.length.toString()
                val append = pick("append")
                if (append.isNotEmpty()) out["append"] = append
            }
            TraceEndpoint.SCROLL -> {
                val dir = pick("direction")
                if (dir.isNotEmpty()) out["direction"] = dir
                val dist = pick("distance", "px", "times")
                if (dist.isNotEmpty()) out["distance"] = dist
            }
            else -> Unit
        }
        return out
    }
}

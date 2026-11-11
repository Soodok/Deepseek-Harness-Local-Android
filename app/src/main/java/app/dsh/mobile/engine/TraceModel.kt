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

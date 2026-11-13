package app.dsh.mobile

/**
 * Linux 工作台（方案 E）的**纯展示事实 → 界面状态**归约器。
 *
 * 为什么单独一层：原页面的三态判定散在 refreshGuide / refreshStatus /
 * refreshModeRow 三处，各自查一部分（一处查 bash、一处查 env.state、一处查
 * launcher.readiness），结果是同一个页面能同时显示「未部署」和「bash 已装」。
 * 这里把判定收成一个**纯函数**：输入一次 IO 快照，输出各区块该显示什么。
 *
 * 这一层不引用任何 Android 类，所以能在宿主机 JVM 上直接断言（见
 * scripts/run-a11y-jvm-test.sh 的 assertWorkbenchReduce）。
 *
 * 不是状态机：页面没有「安装流程」这种需要记住的历史，只有此刻的事实。
 */

/** 环境层状态（与 LinuxEnvironmentManager.State 一一对应，独立枚举以便 JVM 单测） */
enum class EnvState { NOT_DEPLOYED, DEPLOYING, READY, BROKEN }

/** 可用性判定（与 LinuxEngineLauncher.Readiness 一一对应） */
enum class ReadinessKind { READY, NEEDS_DEPLOY, NEEDS_PACKAGES, UNAVAILABLE }

/** 引擎实际跑在哪 */
enum class EngineMode { LINUX, NATIVE }

/** 页面阶段：决定主卡、引导、画面区显示什么 */
enum class Phase { SETUP, PACKAGES, READY }

/** 主卡色调：绿（就绪）/ 黄（缺依赖）/ 红（故障）/ 中性（未部署） */
enum class HeroTone { ACTIVE, MISSING, FAULT, IDLE, NATIVE }

/** 横幅语气 */
enum class BannerKind { CHOOSE_IMAGE, FIX_DEPENDENCIES, READY, FAULT, DEPLOYING, INSTALLING, REMOVING }

/** 页面自己发起、此刻还在跑的操作种类 */
enum class BusyKind { DEPLOY, INSTALL, REMOVE }

/**
 * 一次快照的全部输入。
 *
 * 所有字段都能在 IO 线程上算出来；调用方负责在切镜像后丢弃旧代次的结果
 * （见 LinuxActivity.loadWorkbenchFacts 的代次号）。
 */
data class WorkbenchFacts(
    val optionKey: String = "",
    val optionName: String = "",
    val libc: String = "",
    val abi: String = "",
    val versionLabel: String? = null,
    val envState: EnvState = EnvState.NOT_DEPLOYED,
    val readiness: ReadinessKind = ReadinessKind.NEEDS_DEPLOY,
    val readinessReason: String? = null,
    val flavorReady: Boolean = false,
    /** 缺哪个前置二进制（当前只有 Alpine 系的 bin/bash）；null = 不缺 */
    val missingDependency: String? = null,
    val requestedLinux: Boolean = false,
    val actualMode: EngineMode = EngineMode.NATIVE,
    val engineRunning: Boolean = false,
    val fallbackReason: String? = null,
    /** null = 还没算出来（体积/包清单要遍历目录），不是 0 */
    val toolCount: Int? = null,
    val diskBytes: Long? = null,
    val fileCount: Int? = null,
    val freeBytes: Long? = null,
    /** 读取失败时保留原始报错，页面照原样显示 */
    val error: String? = null,
    /**
     * 页面发起、还在跑的部署 / 装包 / 删除。
     *
     * 它不是从磁盘读出来的事实，而是 Activity 叠上去的：环境层没有「正在装包」这个状态，
     * 不叠的话装包的那几分钟里，页面与空闲时一模一样 —— 用户只能猜它是在跑还是卡死了。
     */
    val busyKind: BusyKind? = null,
    /** 忙碌对象（包名列表 / 镜像名），横幅原样显示 */
    val busyDetail: String? = null,
)

/**
 * 渲染所需的全部结论。
 *
 * 只放**布尔/枚举/原始数值**，不放文案：文案在视图层按 locale 取资源，
 * 这样同一个状态在各语言下都能正确显示。
 */
data class WorkbenchUiState(
    val phase: Phase,
    val tone: HeroTone,
    /** 主卡第二行：musl · arm64 / Bionic · arm64；未部署时为破折号 */
    val heroRuntime: String,
    /** 主卡第三行：rootfs 版本；未部署时为 null */
    val heroVersion: String?,
    val toolCount: Int?,
    val diskBytes: Long?,
    val banner: BannerKind,
    /** 横幅里要填的缺项（缺依赖时是 bash / node 等） */
    val bannerArg: String?,
    /** 1..3 = 当前步骤；0 = 不显示引导（已就绪） */
    val currentStep: Int,
    /** 引擎信息卡里「运行时」的值 */
    val infoRuntime: String,
    /** 故障原因（BROKEN / Unavailable / 读取失败），非空时页面必须显示它 */
    val fault: String?,
    /** 故障种类：fault 为空但种类非空时，视图层用它取兜底文案（不能当成正常页） */
    val faultKind: FaultKind? = null,
    val screenVisible: Boolean,
    val consoleVisible: Boolean,
    /** 允许发起安装/执行类操作（故障与部署中禁止） */
    val actionsAllowed: Boolean,
    /** 已选 Linux 但实际跑在原生：需要重启才生效 */
    val modeRestartPending: Boolean,
    val actualMode: EngineMode,
    /** 有操作在跑：横幅显示进度，发起类入口全部收起 */
    val busy: Boolean = false,
    /**
     * 「重装」能不能点。与 [actionsAllowed] 分开：故障时安装类操作要禁，
     * 但重装恰恰是故障时唯一该点的，只有忙碌时才禁。
     */
    val repairAllowed: Boolean = true,
    /** 容器终端此刻进得去（与画面 / 命令区同一个判据） */
    val terminalReady: Boolean = false,
)

/**
 * 唯一判定入口。
 *
 * 优先级（顺序即语义，改顺序前先读注释）：
 *   1. 故障（BROKEN / Unavailable / 读取失败）压过一切 —— 不能伪装成缺包，
 *      否则用户会去「补依赖」，越补越错。
 *   2. 未部署 → SETUP。
 *   3. 就绪判定不通过（缺 bash / 缺包 / flavor 未齐）→ PACKAGES。
 *   4. 其余 → READY。
 *
 * 「运行中」与「就绪」是两件事：Readiness.Ready 只表示**可以启动**，
 * 主卡文案由 engineRunning 决定，这里只暴露 actualMode 供视图层取词。
 */
fun reduceWorkbench(facts: WorkbenchFacts): WorkbenchUiState {
    val fault: String? = when {
        facts.error != null -> facts.error
        facts.envState == EnvState.BROKEN -> facts.readinessReason
        facts.readiness == ReadinessKind.UNAVAILABLE -> facts.readinessReason
        else -> null
    }

    // ★故障原因可能为空串/空值（比如 BROKEN 但没带 reason）：
    //   只看 fault 是否为 null 会把「环境坏了」渲染成正常页面，操作也照常放行。
    //   这里单独记下故障种类，视图层在 fault 为空时用它取兜底文案。
    val faultKind: FaultKind? = when {
        facts.envState == EnvState.BROKEN -> FaultKind.BROKEN
        facts.readiness == ReadinessKind.UNAVAILABLE -> FaultKind.UNAVAILABLE
        else -> null
    }

    // 故障按「原因或种类任一非空」算：只看原因的话，没带原因的损坏会被主卡和横幅当成正常
    val inFault = fault != null || faultKind != null

    val phase: Phase = when {
        facts.envState == EnvState.NOT_DEPLOYED -> Phase.SETUP
        facts.readiness == ReadinessKind.NEEDS_DEPLOY -> Phase.SETUP
        facts.envState == EnvState.DEPLOYING -> Phase.SETUP
        facts.missingDependency != null -> Phase.PACKAGES
        facts.readiness == ReadinessKind.NEEDS_PACKAGES -> Phase.PACKAGES
        !facts.flavorReady -> Phase.PACKAGES
        else -> Phase.READY
    }

    val tone: HeroTone = when {
        inFault -> HeroTone.FAULT
        phase == Phase.SETUP -> HeroTone.IDLE
        phase == Phase.PACKAGES -> HeroTone.MISSING
        // 环境就绪但引擎跑在 Bionic：用原生黄，别让人以为已经在容器里
        facts.actualMode == EngineMode.NATIVE -> HeroTone.NATIVE
        else -> HeroTone.ACTIVE
    }

    val runtime: String = when {
        phase == Phase.SETUP -> "\u2014"
        facts.actualMode == EngineMode.LINUX ->
            listOf(facts.libc, facts.abi).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
        else -> ("Bionic \u00b7 " + facts.abi).trim()
    }

    val busy = facts.envState == EnvState.DEPLOYING || facts.busyKind != null

    val banner: BannerKind = when {
        // 忙碌排在故障前面：正在删一个损坏的镜像时，横幅该说「正在删除」，
        // 而不是继续报它坏了
        facts.busyKind == BusyKind.DEPLOY -> BannerKind.DEPLOYING
        facts.busyKind == BusyKind.INSTALL -> BannerKind.INSTALLING
        facts.busyKind == BusyKind.REMOVE -> BannerKind.REMOVING
        inFault -> BannerKind.FAULT
        facts.envState == EnvState.DEPLOYING -> BannerKind.DEPLOYING
        phase == Phase.SETUP -> BannerKind.CHOOSE_IMAGE
        phase == Phase.PACKAGES -> BannerKind.FIX_DEPENDENCIES
        else -> BannerKind.READY
    }

    val currentStep: Int = when {
        // 部署进行中就是第 2 步「解压到私有目录」——之前固定 1，用户会以为还在选镜像
        facts.envState == EnvState.DEPLOYING -> 2
        // 页面发起的部署同理。环境层的 status() 实际上从不报 DEPLOYING，真正会走到的是这一条
        facts.busyKind == BusyKind.DEPLOY && phase == Phase.SETUP -> 2
        phase == Phase.SETUP -> 1
        phase == Phase.PACKAGES -> 3
        else -> 0
    }

    val canRun = phase == Phase.READY && fault == null && faultKind == null

    return WorkbenchUiState(
        phase = phase,
        tone = tone,
        heroRuntime = runtime,
        heroVersion = if (phase == Phase.SETUP) null else facts.versionLabel,
        toolCount = if (phase == Phase.SETUP) 0 else facts.toolCount,
        diskBytes = if (phase == Phase.SETUP) null else facts.diskBytes,
        banner = banner,
        bannerArg = when (banner) {
            BannerKind.FIX_DEPENDENCIES -> facts.missingDependency ?: facts.readinessReason
            BannerKind.FAULT -> fault
            BannerKind.INSTALLING, BannerKind.REMOVING -> facts.busyDetail
            else -> null
        },
        currentStep = currentStep,
        faultKind = faultKind,
        infoRuntime = runtime,
        fault = fault,
        screenVisible = canRun,
        consoleVisible = canRun,
        actionsAllowed = fault == null && faultKind == null && !busy,
        modeRestartPending = facts.requestedLinux != (facts.actualMode == EngineMode.LINUX),
        actualMode = facts.actualMode,
        busy = busy,
        repairAllowed = !busy,
        terminalReady = canRun,
    )
}

/** 已装工具数是「已知的 0」还是「还不知道」——视图层据此决定显示 0 还是破折号 */
fun WorkbenchUiState.toolCountKnown(): Boolean = toolCount != null || phase == Phase.SETUP

/** 引导三步的完成态：索引 0..2 对应 选镜像 / 解压 / 补依赖 */
fun WorkbenchUiState.stepState(index: Int): StepMark = when {
    currentStep == 0 -> StepMark.DONE
    index + 1 < currentStep -> StepMark.DONE
    index + 1 == currentStep -> StepMark.CURRENT
    else -> StepMark.TODO
}

enum class StepMark { DONE, CURRENT, TODO }

/** 故障种类：决定兜底文案，也决定是否放行操作（与 fault 文案分开，文案可能缺） */
enum class FaultKind { BROKEN, UNAVAILABLE }

// ---------- 忙碌进度（纯计算，界面只管显示读数） ----------

/** 一次操作里的阶段。部署依次经过 下载 → 解包 → 装包；单独的装包、删除各只有一个阶段 */
enum class BusyStage { DOWNLOAD, UNPACK, PACKAGES, REMOVE }

/**
 * 给界面的读数。
 *
 * fraction 为 null 表示这一刻量不出完成度（进度条退回来回滚动的样式）；
 * etaSeconds 为 null 表示还估不出剩余时间。量不出来就如实报未知，不编一个数。
 */
data class BusyReading(val fraction: Float?, val etaSeconds: Long?)

/** 包管理器的一行计数输出：共 total 个包，正在装第 index 个，名字是 name */
data class ApkStep(val index: Int, val total: Int, val name: String)

// apk 2.x 输出「(5/14) Installing nodejs (22.16.0-r2)」，apk 3.x 的序号前会补空格「( 5/14)」
private val APK_STEP = Regex(
    """\(\s*(\d+)\s*/\s*(\d+)\s*\)\s+(?:Installing|Upgrading|Downgrading|Reinstalling|Replacing)\s+(\S+)"""
)

/** 从包管理器的一行输出里取出「第几个 / 共几个 / 包名」；不是计数行就返回 null */
fun parseApkStep(line: String): ApkStep? {
    val m = APK_STEP.find(line) ?: return null
    val index = m.groupValues[1].toIntOrNull() ?: return null
    val total = m.groupValues[2].toIntOrNull() ?: return null
    if (total <= 0 || index <= 0 || index > total) return null
    return ApkStep(index, total, m.groupValues[3])
}

/**
 * 把按块到达的输出拼回成行。
 *
 * 容器运行器的输出回调给的是「读到多少算多少」的字节块，不是切好的行：
 * 一行可能被切成两块，一块里也可能有好几行。直接把整块当一行用，计数行就认不出来。
 * 标准输出与标准错误各有一个读取线程，所以 feed 要加锁。
 */
class LineAssembler {
    private val pending = StringBuilder()

    @Synchronized
    fun feed(chunk: String): List<String> {
        val out = ArrayList<String>()
        for (c in chunk) {
            if (c == '\n' || c == '\r') {
                if (pending.isNotEmpty()) {
                    out.add(pending.toString())
                    pending.setLength(0)
                }
            } else {
                pending.append(c)
                // 一直不换行的输出（进度条刷屏之类）不能无限攒着
                if (pending.length >= MAX_LINE) {
                    out.add(pending.toString())
                    pending.setLength(0)
                }
            }
        }
        return out
    }

    private companion object {
        const val MAX_LINE = 4096
    }
}

/**
 * 装包阶段的完成度（0..1；量不出来返回 null）。
 *
 * 计数行给的是硬边界：正在装第 n 个（共 N 个）时，完成度一定在 (n-1)/N 与 n/N 之间。
 * 包的大小差得很远（nodejs 六十多 MB，多数依赖库只有几百 KB），只按个数算会一顿一顿地跳，
 * 所以用「目录体积涨了多少 ÷ 预计要写入多少」在这个区间里插值；体积估计有偏差时，
 * 误差也被计数行夹在一个包的范围内。
 *
 * 没有计数行时（环境层自己装基础软件，不透出输出；或者包管理器的输出格式认不出来）只看体积，
 * 并且最多报到 95%：体积是估的，不能靠它宣布装完。
 *
 * [expectedBytes] 是预计写入量，[fullCount] 是这个预计量对应的包数，不知道就传 0。
 * 实际要装的个数 N 和它不一样时（有一部分早就装过了），预计写入量按比例缩放。
 */
fun packageFraction(step: ApkStep?, grownBytes: Long, expectedBytes: Long, fullCount: Int): Float? {
    if (step == null) {
        if (expectedBytes <= 0L) return null
        return (grownBytes.toFloat() / expectedBytes).coerceIn(0f, 0.95f)
    }
    val lo = (step.index - 1).toFloat() / step.total
    val hi = step.index.toFloat() / step.total
    if (expectedBytes <= 0L) return lo
    val scaled = if (fullCount > 0) {
        expectedBytes.toDouble() * step.total / fullCount
    } else {
        expectedBytes.toDouble()
    }
    return (grownBytes / scaled).toFloat().coerceIn(lo, hi)
}

/**
 * 把「当前阶段 + 阶段内完成度」换算成整个操作的完成度与剩余时间。
 *
 * [plan] 是这次操作按顺序要经过的阶段和各自的权重。权重用同一个单位（要从网络取回的字节数；
 * 解包不走网络，按折算后的值给），这样完成度大致随时间匀速前进，剩余时间才估得准。
 * 被跳过的阶段（镜像已有缓存、不用下载）直接算作已完成。
 *
 * 完成度只增不减，并且在操作真正结束前不会到 100%。
 * 剩余时间按最近十几秒的实际速度估，再和上一次的估计做平滑，避免每半秒跳一个数；
 * 开头几秒和进度停住不动的时候不给数。
 */
class ProgressMeter(private val plan: List<Pair<BusyStage, Float>>) {
    private val total = plan.sumOf { it.second.toDouble() }.toFloat()
    private var best = 0f
    private val window = ArrayDeque<Pair<Long, Float>>()
    private var eta: Double? = null
    private var etaAt = 0L

    fun update(nowMs: Long, stage: BusyStage, within: Float?): BusyReading {
        val i = plan.indexOfFirst { it.first == stage }
        if (i < 0 || total <= 0f) return BusyReading(null, null)
        var before = 0f
        for (k in 0 until i) before += plan[k].second
        if (within == null) {
            // 这个阶段量不出完成度（例如没量过体量的发行版在装软件）。
            // 前面的阶段已经走完的部分是确定的：停在那里，不把百分比收回去，也不往前编；
            // 一个阶段都没走完时才报「未知」。剩余时间这时估不出来。
            eta = null
            window.clear()
            val floor = (before / total).coerceIn(0f, CEILING)
            if (floor > best) best = floor
            return if (best > 0f) BusyReading(best, null) else BusyReading(null, null)
        }
        val raw = ((before + plan[i].second * within.coerceIn(0f, 1f)) / total).coerceIn(0f, CEILING)
        if (raw > best) best = raw

        window.addLast(nowMs to best)
        while (window.size > 2 && nowMs - window.first().first > WINDOW_MS) window.removeFirst()
        val oldest = window.first()
        val span = nowMs - oldest.first
        val gained = best - oldest.second

        if (best >= MIN_FRACTION && span >= MIN_SPAN_MS && gained > 0f) {
            val fresh = (1f - best) / (gained / span) / 1000.0
            val prev = eta
            val next = if (prev == null) {
                fresh
            } else {
                // 上一次的估计先扣掉这段时间里已经过去的秒数，再和新估计加权
                maxOf(0.0, prev - (nowMs - etaAt) / 1000.0) * 0.7 + fresh * 0.3
            }
            eta = next
            etaAt = nowMs
            return BusyReading(best, Math.ceil(next).toLong())
        }
        // 整个窗口里一点没动：卡住了（断网、等锁），旧的估计不再可信
        if (gained <= 0f && span >= STALL_MS) eta = null
        val carried = eta?.let { Math.ceil(maxOf(0.0, it - (nowMs - etaAt) / 1000.0)).toLong() }
        return BusyReading(best, carried)
    }

    private companion object {
        /** 操作结束前最多显示到 99%：最后一步收尾（写标记、核对）量不到 */
        const val CEILING = 0.99f
        const val WINDOW_MS = 12_000L
        const val MIN_SPAN_MS = 3_000L
        const val STALL_MS = 10_000L
        const val MIN_FRACTION = 0.02f
    }
}

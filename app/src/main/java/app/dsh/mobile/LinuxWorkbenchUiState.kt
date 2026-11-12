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
enum class BannerKind { CHOOSE_IMAGE, FIX_DEPENDENCIES, READY, FAULT, DEPLOYING }

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
        fault != null -> HeroTone.FAULT
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

    val banner: BannerKind = when {
        fault != null -> BannerKind.FAULT
        facts.envState == EnvState.DEPLOYING -> BannerKind.DEPLOYING
        phase == Phase.SETUP -> BannerKind.CHOOSE_IMAGE
        phase == Phase.PACKAGES -> BannerKind.FIX_DEPENDENCIES
        else -> BannerKind.READY
    }

    val currentStep: Int = when {
        // 部署进行中就是第 2 步「解压到私有目录」——之前固定 1，用户会以为还在选镜像
        facts.envState == EnvState.DEPLOYING -> 2
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
            else -> null
        },
        currentStep = currentStep,
        faultKind = faultKind,
        infoRuntime = runtime,
        fault = fault,
        screenVisible = canRun,
        consoleVisible = canRun,
        actionsAllowed = fault == null && faultKind == null &&
            facts.envState != EnvState.DEPLOYING,
        modeRestartPending = facts.requestedLinux != (facts.actualMode == EngineMode.LINUX),
        actualMode = facts.actualMode,
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

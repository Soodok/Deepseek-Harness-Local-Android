package app.dsh.mobile.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** 后台资源包任务的阶段。 */
enum class PetPackPhase {
    /** 没有任务（或上次任务的结果已被清掉）。 */
    IDLE,
    DOWNLOADING,
    INSTALLING,

    /** 下载 + 安装都成功，包已成当前包。 */
    DONE,
    FAILED,

    /** 用户主动取消；残件保留，下次点下载可续传。 */
    CANCELLED,
}

/**
 * 一次资源包后台任务的可观测状态。
 *
 * 只放 UI 需要的事实，不持有 Context / View：Service 写、Activity 读，两边互不引用。
 */
data class PetPackTask(
    val phase: PetPackPhase = PetPackPhase.IDLE,
    val packId: String = "",
    val packName: String = "",
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val error: String? = null,
    /**
     * 当前实际在用的下载源；null = 还没开始连。
     *
     * 会随代理失败回落直连而变化，所以展示的是「此刻」的线路，不是启动时的判断。
     */
    val source: PackSource? = null,
) {
    /** 任务是否还在跑（Service 活着、进度条该转）。 */
    val running: Boolean
        get() = phase == PetPackPhase.DOWNLOADING || phase == PetPackPhase.INSTALLING

    val percent: Int
        get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
}

/**
 * 资源包「后台下载」状态总线。
 *
 * 下载和安装都搬进 [PetPackDownloadService] 后，设置页只是订阅者：
 * 用户随时可以退出去忙别的，回来时按当前阶段续上进度显示。
 */
object PetPackDownloadBus {
    private val _state = MutableStateFlow(PetPackTask())
    val state: StateFlow<PetPackTask> = _state

    /** 取消请求：UI / 通知按钮置位，下载线程每读一块检查一次。 */
    private val cancelRequested = AtomicBoolean(false)

    /** 置位取消。线程会在下一个 256KB 块边界退出，残件留着供续传。 */
    fun requestCancel() {
        cancelRequested.set(true)
    }

    fun isCancelled(): Boolean = cancelRequested.get()

    /** 每个新任务开始前清一次，别让上一次的取消串到下一次。 */
    internal fun resetCancel() {
        cancelRequested.set(false)
    }

    internal fun update(block: (PetPackTask) -> PetPackTask) {
        _state.value = block(_state.value)
    }

    /** 任务结束时把阶段落回 IDLE，但保留结果供 UI 提示一次。 */
    internal fun clear() {
        _state.value = PetPackTask()
        cancelRequested.set(false)
    }
}

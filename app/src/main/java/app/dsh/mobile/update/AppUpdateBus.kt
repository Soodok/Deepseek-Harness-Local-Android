package app.dsh.mobile.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 更新状态总线（进程内单例）。
 *
 * UI 只订阅、不驱动：下载/安装发生在**前台服务**里，Activity 随时可能被销毁，
 * 状态必须活得比页面久。这与 pet 资源下载的 `PetPackDownloadBus` 是同一套思路。
 */
object AppUpdateBus {

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun set(next: UpdateState) {
        _state.value = next
    }

    fun update(block: (UpdateState) -> UpdateState) {
        _state.value = block(_state.value)
    }

    fun phase(phase: Phase, message: String? = null) {
        update { it.copy(phase = phase, message = message, updatedAt = System.currentTimeMillis()) }
    }

    fun progress(pct: Int, viaProxy: Boolean?) {
        update {
            it.copy(
                phase = Phase.DOWNLOADING,
                progress = pct.coerceIn(0, 100),
                viaProxy = viaProxy ?: it.viaProxy,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }
}

/**
 * 三个后台更新任务的互斥闸门。
 *
 * 为什么必须互斥：
 *  - APK 安装前要 `supervisor.stop()`，运行时换装也要停引擎 —— 两者同时跑，
 *    后一个拿到的是"已经停了的引擎"，`stop()` 返回 false 直接失败；
 *  - 同时下 200MB+ 的包会互相抢带宽，还可能与 pet 资源下载叠加。
 *
 * 第二个请求进来时 UI 提示「另一项更新任务进行中」，不排队（排队会让用户困惑
 * 为什么点了没反应）。
 */
object UpdateTasks {

    enum class Task { APK_DOWNLOAD, APK_INSTALL, RUNTIME }

    @Volatile
    private var current: Task? = null

    /** 拿到闸门返回 true；已有任务在跑返回 false。 */
    @Synchronized
    fun acquire(task: Task): Boolean {
        if (current != null) return false
        current = task
        return true
    }

    @Synchronized
    fun release(task: Task) {
        if (current == task) current = null
    }

    @Synchronized
    fun isBusy(): Boolean = current != null

    @Synchronized
    fun running(): Task? = current
}

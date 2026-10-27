package app.dsh.mobile.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 桌宠运行状态（进程内）。
 *
 * 只承载 UI 需要的事实：是否在跑、是否在等插件、端口有没有冲突、最后一次事件。
 * 不持有 Context / View，避免设置页与 Service 互相泄漏。
 */
data class PetStatus(
    val running: Boolean = false,
    val listening: Boolean = false,
    val connected: Boolean = false,
    val lastKind: String? = null,
    val lastEventAt: Long = 0L,
    val packLabel: String? = null,
    val placeholder: Boolean = true,
    val lastError: String? = null,
    val port: Int = PetProtocol.PORT,
    /**
     * 服务在跑、但桥插件没就绪，所以角色没被画出来。
     *
     * 和 running 不互斥：前台服务照跑（端口、状态查询都在），只是不 attachPet。
     * 设置页据此显示「桌宠已暂停：桥插件未就绪」的提示行。
     */
    val bridgeMissing: Boolean = false,
)

object PetStatusBus {
    private val _state = MutableStateFlow(PetStatus())
    val state: StateFlow<PetStatus> = _state

    internal fun update(block: (PetStatus) -> PetStatus) {
        _state.value = block(_state.value)
    }

    internal fun reset() {
        _state.value = PetStatus()
    }
}

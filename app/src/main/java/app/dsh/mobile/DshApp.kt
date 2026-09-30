package app.dsh.mobile

import android.app.Application
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.ExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DshApp : Application() {

    /** 全局协程域：监督器生命周期独立于任何 Activity/Service */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 全局唯一监督器单例，Activity 与 Service 共享状态 */
    lateinit var supervisor: EngineSupervisor
        private set

    /** 全局唯一扩展管理器单例：扩展中心 UI 与 AI 通道（AgentBridge）共享下载任务状态 */
    val extensionManager: ExtensionManager by lazy { ExtensionManager(this) }

    override fun onCreate() {
        super.onCreate()
        supervisor = EngineSupervisor(this)
    }
}

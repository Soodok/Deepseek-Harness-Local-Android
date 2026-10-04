package app.dsh.mobile

import android.app.Application
import app.dsh.mobile.engine.AuditLogger
import app.dsh.mobile.engine.CapabilityManager
import app.dsh.mobile.engine.DshAppProvider
import app.dsh.mobile.engine.EngineStatusProvider
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.ExtensionManager
import app.dsh.mobile.engine.TaskManager
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

    /** App 启动时间戳（用于仪表盘显示运行时长） */
    val startupTimestamp: Long = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        DshAppProvider.init(this)
        supervisor = EngineSupervisor(this)
        AuditLogger.init(this)
        CapabilityManager.init(this)
        TaskManager.init(this)
        EngineStatusProvider.start(appScope)
    }

    override fun onTerminate() {
        super.onTerminate()
        EngineStatusProvider.stop()
    }
}

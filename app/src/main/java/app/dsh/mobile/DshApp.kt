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

    companion object {
        /** 设置页 UI 偏好（页面缩放/横竖屏/试验性功能），与 SettingsActivity 共用 */
        const val PREFS_UI = "dsh_ui"

        /** 试验性功能开关（v1.2.95）：默认关闭，关闭时悬浮窗与相关设置入口都不出现 */
        const val KEY_EXPERIMENTAL = "experimental"

        /** 读取试验性开关（Activity/Service 通用；无 Context 依赖的静态读法） */
        fun experimentalOn(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_EXPERIMENTAL, false)
    }
}

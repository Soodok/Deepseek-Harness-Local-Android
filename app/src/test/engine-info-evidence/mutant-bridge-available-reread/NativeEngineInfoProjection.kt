package app.dsh.mobile.engine
class NativeEngineInfoProjection(private val supervisor: EngineSupervisor?) {
 var pendingRuntimeTarget: String? = null
 var pendingRuntimeUrl = ""
 private fun supervisorOrNull() = supervisor
 private fun currentRuntimeWire() = "fixture-runtime"
 fun read(): Map<String, Any> = engineInfo()
    private fun engineInfo(): Map<String, Any> {
        val supervisor = supervisorOrNull()
        val hostSnapshot = supervisor?.nativeHostSnapshot()
        val webUrl = hostSnapshot?.webUrl.orEmpty()
        val distro = app.dsh.mobile.engine.EngineModeSelector.lastDistro
        val workspaceScope = hostSnapshot?.workspaceScope
        // 「切了但引擎还没重启」的判定：webUrl 里带每次启动才有的 token，所以
        // 换了一代（含引擎停掉后变空）就说明已经重新拉起过、新偏好已被采用，
        // 待生效标记可以收掉；没变则继续挂着。
        if (pendingRuntimeTarget != null && webUrl != pendingRuntimeUrl) {
            pendingRuntimeTarget = null
            pendingRuntimeUrl = ""
        }
        return mapOf(
            "webUrl" to webUrl,
            "available" to (supervisor?.hasLoadableUrl() == true),
            "state" to (hostSnapshot?.state?.let { it::class.simpleName ?: "unknown" } ?: "uninitialized"),
            "runtimeMode" to currentRuntimeWire(),
            "runtimeResolved" to app.dsh.mobile.engine.EngineModeSelector.resolved,
            "runtimeReason" to app.dsh.mobile.engine.EngineModeSelector.lastReason,
            "distro" to distro,
            "runtimeRestartPending" to (pendingRuntimeTarget != null),
            "workspaceDomain" to workspaceScope?.domain.orEmpty(),
            "workspaceGeneration" to workspaceScope?.generation.orEmpty(),
        )
    }

    private fun engineState(supervisor: app.dsh.mobile.engine.EngineSupervisor? = supervisorOrNull()): String =
        supervisor?.state?.value?.let { it::class.simpleName ?: "unknown" } ?: "uninitialized"

}

package app.dsh.mobile.workspace

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceBridgeContractTest {
    private val activity = File("src/main/java/app/dsh/mobile/NativeChatActivity.kt").readText()
    private val bridge = File("src/main/java/app/dsh/mobile/workspace/NativeWorkspaceBridge.kt").readText()

    @Test fun nativeBridgeExposesPrivateCreateAndSystemSharedPicker() {
        assertTrue(bridge.contains("\"listPrivateWorkspaces\""))
        assertTrue(bridge.contains("\"createPrivateWorkspace\""))
        assertTrue(bridge.contains("\"pickSharedWorkspace\""))
        assertTrue(bridge.contains("ACTION_OPEN_DOCUMENT_TREE"))
        assertTrue(bridge.contains("takePersistableUriPermission"))
        assertTrue(bridge.contains("NativeWorkspacePaths.sharedDirectory"))
    }

    @Test fun bridgeNeverReturnsAContentUriAsTheEngineCwd() {
        assertTrue(bridge.contains("\"path\" to mapped.absolutePath"))
        assertTrue(bridge.contains("mapOf(\"uri\" to it.toString())"))
        assertFalse(bridge.contains("\"path\" to uri.toString()"))
        assertTrue(bridge.contains("workspace/unreachable"))
    }

    @Test fun systemPickerIsNotAPathPermissionEscalation() {
        assertFalse(bridge.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(bridge.contains("requestPermissions"))
        assertFalse(bridge.contains("su "))
        assertTrue(bridge.contains("Environment.isExternalStorageManager()"))
        assertTrue(activity.contains("workspaceBridge?.onActivityResult(requestCode, resultCode, data)"))
        assertTrue(activity.contains("app.dsh.mobile.workspace.NativeWorkspaceBridge("))
    }
    @Test fun pickerDoesNotPersistBeforeHostRegistration() {
        val callback = bridge.substringAfter("fun onActivityResult(").substringBefore("private fun sharedDirectory(")
        assertFalse("Picking alone must not persist a grant", callback.contains("takePersistableUriPermission"))
        assertTrue("Registration must explicitly finalize the choice", bridge.contains("rememberWorkspaceDirectory"))
    }

    @Test fun registeredWorkspaceValidationUsesAppRecordsAndManualPermissionGuidance() {
        assertTrue(bridge.contains("validateRegisteredWorkspaceDirectory"))
        assertTrue(bridge.contains("getSharedPreferences"))
        assertTrue(bridge.contains("checkUriPermission"))
        assertTrue(bridge.contains("权限中心手动配置"))
    }

    @Test fun mainThreadDeliveryRejectsStaleScopeBeforeCompletingASelection() {
        val delivery = bridge.substringAfter("main.post {").substringBefore("}.isSuccess")
        assertTrue("A generation change after IO must not deliver an obsolete selection",
            delivery.contains("currentScope() == scope"))
    }
    @Test fun rememberCapturesChoiceIdentityBeforeQueuingIo() {
        val handler = bridge.substringAfter("rememberWorkspaceDirectory").substringBefore("validateRegisteredWorkspaceDirectory")
        assertTrue("The choice must be captured at MethodChannel receipt, not after a later re-selection",
            handler.indexOf("selections.matching") >= 0 &&
                handler.indexOf("selections.matching") < handler.indexOf("run(scope, result"))
    }
}



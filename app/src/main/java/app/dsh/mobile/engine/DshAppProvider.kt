package app.dsh.mobile.engine

import app.dsh.mobile.DshApp

/**
 * DshApp provider — lightweight accessor to get the DshApp instance
 * from EngineStatusProvider / other objects that may not have direct
 * context access. This avoids passing Context around in every method.
 */
object DshAppProvider {
    @Volatile private var app: DshApp? = null

    fun init(app: DshApp) {
        this.app = app
    }

    fun get(): DshApp = app ?: throw IllegalStateException(
        "DshApp not initialized — call DshAppProvider.init() in DshApp.onCreate()"
    )
}

package app.dsh.mobile.coldentry

/**
 * UI-thread confined, process-owned routes. Codes are never reused in this process/task lineage.
 * Android supplies the code even for RESULT_CANCELED + null data. No child extra is required.
 * Persist nextCode/launchedCode, never paths. After process death restore only a routing tombstone:
 * cache orphans deliberately remain unowned, rather than deleting a file based on saved input.
 */
class EntryResultRoutes(private val deliveries: EntryDeliveries) {
    private data class Route(val code: Int, val attempt: Long?)
    private var route: Route? = null
    private var restored = false
    var nextCode: Int = FIRST_CODE
        private set
    val launchedCode: Int? get() = route?.code

    fun restore(savedNextCode: Int, savedLaunchedCode: Int?) {
        nextCode = maxOf(nextCode, savedNextCode.coerceIn(FIRST_CODE, 65536))
        if (restored) return
        restored = true
        if (route == null && savedLaunchedCode != null && recognizes(savedLaunchedCode) && savedLaunchedCode < nextCode) {
            route = Route(savedLaunchedCode, null)
        }
    }

    fun launched(entry: NativeEntry): Int {
        check(route == null) { "Native child already owns a result route" }
        check(nextCode <= 65535) { "Native result code space exhausted; restart task" }
        val code = nextCode++
        route = Route(code, entry.attemptId)
        return code
    }

    fun complete(requestCode: Int): Boolean {
        val owned = route ?: return false
        if (owned.code != requestCode) return false
        route = null
        owned.attempt?.let { deliveries.complete(it) }
        return true
    }

    fun abandon(requestCode: Int) {
        if (route?.code == requestCode) route = null
    }

    fun recognizes(code: Int): Boolean = code in FIRST_CODE..65535

    companion object { const val FIRST_CODE = 24576 }
}

/** No Activity references survive here. Main recreation must reuse these capabilities. */
object NativeEntryProcess {
    val deliveries = EntryDeliveries()
    val ids = AttemptIds()
    val results = EntryResultRoutes(deliveries)
}

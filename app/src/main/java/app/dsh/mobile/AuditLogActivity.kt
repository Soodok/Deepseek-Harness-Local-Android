package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.AuditLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Audit log viewer (Phase 4): displays security-relevant events
 * in chronological order. Allows the user to clear the log.
 */
class AuditLogActivity : Activity() {

    private lateinit var logContainer: LinearLayout
    private lateinit var logEmpty: TextView

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audit_log)

        logContainer = findViewById(R.id.logContainer)
        logEmpty = findViewById(R.id.logEmpty)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.rowClearLog).setOnClickListener {
            AuditLogger.clear(this)
            Toast.makeText(this, getString(R.string.audit_cleared), Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.Main + Job()).launch {
            AuditLogger.entries.collectLatest { entries ->
                renderEntries(entries)
            }
        }
    }

    private fun renderEntries(entries: List<AuditLogger.AuditEntry>) {
        logContainer.removeAllViews()
        if (entries.isEmpty()) {
            logEmpty.visibility = android.view.View.VISIBLE
            return
        }
        logEmpty.visibility = android.view.View.GONE
        for (entry in entries) {
            val item = layoutInflater.inflate(R.layout.item_audit_entry, logContainer, false) as LinearLayout
            item.findViewById<TextView>(R.id.entryTimestamp).text = entry.timestamp
            item.findViewById<TextView>(R.id.entryEventType).text = entry.eventType
            item.findViewById<TextView>(R.id.entryPrivMode).text = entry.privMode
            item.findViewById<TextView>(R.id.entryMessage).text = entry.message
            logContainer.addView(item)
        }
    }
}

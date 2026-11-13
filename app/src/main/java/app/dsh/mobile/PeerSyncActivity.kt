package app.dsh.mobile

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import app.dsh.mobile.engine.PeerSync
import app.dsh.mobile.engine.PeerSyncPrefs

/**
 * 双端同步（v1.6.0-beta.3）—— 设置 → 数据与会话 → 双端同步。
 *
 * ## 为什么单开一页
 * 要填的东西（电脑地址 / 端口 / 密码 / 方向 / 冲突策略）比一行开关多得多，
 * 塞进设置主页会把它变成第二个设置页。
 *
 * ## 交互约定
 * · 三行配置点开就是输入框（地址 / 端口 / 密码），改完立刻落盘（加密）。
 * · 「立即同步」在后台线程跑，按钮转圈并禁用，返回后弹结果；全程不阻塞 UI。
 * · 状态文案全部走 strings.xml（六语言）—— 不在代码里写中文字面量，
 *   否则 check-i18n.py 之外的用户（de/ru/th/pt）会看到中文。
 */
class PeerSyncActivity : ThemedActivity() {

    /** 属于设置体系：跟随「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    @Volatile
    private var syncing = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_peersync)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.rowHost).setOnClickListener {
            editText(
                value = PeerSyncPrefs.load(this).host,
                hint = getString(R.string.peersync_enter_host),
                numeric = false,
            ) { v ->
                if (v.isNotBlank() && !PeerSync.isPrivateHost(v)) {
                    // 公网地址一律拒绝：密码是明文请求头，发出去等于交钥匙。
                    // 在输入处就拦，比等到同步失败再解释要清楚。
                    Toast.makeText(this, R.string.peersync_public_host, Toast.LENGTH_LONG).show()
                    return@editText
                }
                save(host = v)
            }
        }
        findViewById<LinearLayout>(R.id.rowPort).setOnClickListener {
            val cur = PeerSyncPrefs.load(this).port
            editText(value = cur.toString(), hint = getString(R.string.peersync_enter_port), numeric = true) { v ->
                val p = v.toIntOrNull()
                if (p == null || p <= 0 || p > 65535) {
                    Toast.makeText(this, R.string.peersync_invalid_port, Toast.LENGTH_SHORT).show()
                    return@editText
                }
                save(port = p)
            }
        }
        findViewById<LinearLayout>(R.id.rowPassword).setOnClickListener {
            editText(value = "", hint = getString(R.string.peersync_enter_password), numeric = false) { v ->
                if (v.isBlank()) return@editText
                save(password = v)
                Toast.makeText(this, R.string.peersync_saved, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<LinearLayout>(R.id.rowDirection).setOnClickListener {
            val cur = PeerSyncPrefs.load(this).direction
            val labels = arrayOf(
                getString(R.string.peersync_direction_both),
                getString(R.string.peersync_direction_pull),
                getString(R.string.peersync_direction_push),
            )
            val values = arrayOf("both", "pull", "push")
            choice(getString(R.string.peersync_direction), labels, values.indexOf(cur).coerceAtLeast(0)) { i ->
                save(direction = values[i])
            }
        }
        findViewById<LinearLayout>(R.id.rowConflict).setOnClickListener {
            val cur = PeerSyncPrefs.load(this).conflict
            val labels = arrayOf(
                getString(R.string.peersync_conflict_backup_newer),
                getString(R.string.peersync_conflict_prefer_local),
                getString(R.string.peersync_conflict_prefer_remote),
                getString(R.string.peersync_conflict_skip),
            )
            val values = arrayOf("backup-newer", "prefer-local", "prefer-remote", "skip")
            choice(getString(R.string.peersync_conflict), labels, values.indexOf(cur).coerceAtLeast(0)) { i ->
                save(conflict = values[i])
            }
        }
        findViewById<MaterialButton>(R.id.btnSync).setOnClickListener { runSync() }

        refresh()
        loadLocalStats()
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        refresh()
    }

    // ── 状态刷新 ──────────────────────────────────────────────

    private fun refresh() {
        val cfg = PeerSyncPrefs.load(this)
        findViewById<TextView>(R.id.txtHost).text =
            cfg.host.ifBlank { getString(R.string.peersync_not_set) }
        findViewById<TextView>(R.id.txtPort).text = cfg.port.toString()
        findViewById<TextView>(R.id.txtPassword).text =
            if (cfg.password.isNotBlank()) getString(R.string.peersync_password_set)
            else getString(R.string.peersync_not_set)
        findViewById<TextView>(R.id.txtDirection).text = when (cfg.direction) {
            "pull" -> getString(R.string.peersync_direction_pull)
            "push" -> getString(R.string.peersync_direction_push)
            else -> getString(R.string.peersync_direction_both)
        }
        findViewById<TextView>(R.id.txtConflict).text = when (cfg.conflict) {
            "prefer-local" -> getString(R.string.peersync_conflict_prefer_local)
            "prefer-remote" -> getString(R.string.peersync_conflict_prefer_remote)
            "skip" -> getString(R.string.peersync_conflict_skip)
            else -> getString(R.string.peersync_conflict_backup_newer)
        }
        // 上次同步结果（基线文件里带着 lastResult；没有就显示「还没同步过」）
        val last = PeerSync.lastResult(this).optJSONObject("lastResult")
        val lastText = findViewById<TextView>(R.id.txtLast)
        if (last == null) {
            lastText.text = getString(R.string.peersync_never)
        } else {
            val when_ = android.text.format.DateFormat.format("MM-dd HH:mm", last.optLong("at", 0L))
            lastText.text = getString(
                R.string.peersync_last_detail_fmt,
                when_, last.optInt("pushed", 0), last.optInt("pulled", 0),
                last.optInt("conflicts", 0), last.optInt("failed", 0),
            )
        }
    }

    /** 本机会话规模要在后台算（几百个文件 stat 一次，主线程做会卡一下）。 */
    private fun loadLocalStats() {
        val tv = findViewById<TextView>(R.id.txtLocal)
        Thread {
            val (count, bytes) = PeerSync.localStats(this)
            val human = android.text.format.Formatter.formatShortFileSize(this, bytes)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tv.text = getString(R.string.peersync_local_fmt, count, human)
            }
        }.start()
    }

    private fun save(
        host: String? = null,
        port: Int? = null,
        password: String? = null,
        direction: String? = null,
        conflict: String? = null,
    ) {
        val cur = PeerSyncPrefs.load(this)
        PeerSyncPrefs.save(
            this,
            host = host ?: cur.host,
            port = port ?: cur.port,
            password = password ?: cur.password,
            direction = direction ?: cur.direction,
            conflict = conflict ?: cur.conflict,
            scope = cur.scope,
            maxBytes = cur.maxBytes,
        )
        refresh()
    }

    // ── 同步 ──────────────────────────────────────────────────

    private fun runSync() {
        if (syncing) return
        val cfg = PeerSyncPrefs.load(this)
        if (cfg.host.isBlank() || cfg.password.isBlank()) {
            Toast.makeText(this, R.string.peersync_need_config, Toast.LENGTH_LONG).show()
            return
        }
        val btn = findViewById<MaterialButton>(R.id.btnSync)
        syncing = true
        btn.isEnabled = false
        btn.text = getString(R.string.peersync_running)
        Thread {
            val res = PeerSync.sync(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                syncing = false
                btn.isEnabled = true
                btn.text = getString(R.string.peersync_run)
                refresh()
                loadLocalStats()
                if (res.ok) {
                    val c = res.json.optJSONObject("counts")
                    Toast.makeText(
                        this,
                        getString(
                            R.string.peersync_ok_fmt,
                            c?.optInt("pushed", 0) ?: 0,
                            c?.optInt("pulled", 0) ?: 0,
                            c?.optInt("conflicts", 0) ?: 0,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    // 失败原因原样给用户看：unreachable / unauthorized / rate-limited 的
                    // 处置动作完全不同，翻译成一句「同步失败」等于让用户去猜。
                    Toast.makeText(
                        this,
                        getString(R.string.peersync_fail_fmt, res.summary()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    // ── 小工具 ────────────────────────────────────────────────

    private fun editText(value: String, hint: String, numeric: Boolean, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(hint)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun choice(title: String, labels: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

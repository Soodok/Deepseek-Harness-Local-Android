package app.dsh.mobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 记忆详情（M8）：逐步列出 action / targetText，底部四个动作键。
 *
 * ## 四个键各自打哪个端点
 * - 标注 → POST /memory/v2/label {id,intent,title}：把派生签名换成一句人话，
 *   换完才能被语义检索到（自动学习只能给 `pkg: 首步 -> 末步` 这种替身）。
 * - 删除 → POST /memory/v2/delete?id=
 * - 禁用/启用 → POST /memory/v2/state {id,state}：disabled 是**负样本**，
 *   重放永远拒绝它（MemoryReplay.Reason.DISABLED），不是"暂时不看"。
 * - 试跑 → POST /memory/run {id}：真在屏幕上点一遍，逐条回 status
 *   （ok / anchor-mismatch / verify-failed / skipped / blocked）。成功 hits++、
 *   真失效 misses++，所以这一键同时是「校正统计口径」的手动入口。
 *
 * ## 为什么详情是独立 Activity
 * 与列表页同样的理由（见 MemoryPanelActivity 头注释）：项目内页面一律 Activity；
 * 且试跑要占用整屏几十秒并会真的操作界面，塞进对话框很难看清每步 status。
 */
class MemoryDetailActivity : ThemedActivity() {

    /** 属于设置体系：启用「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    private lateinit var txtTitle: TextView
    private lateinit var txtSub: TextView
    private lateinit var txtState: TextView
    private lateinit var boxSteps: LinearLayout
    private lateinit var txtRun: TextView
    private lateinit var btnToggle: TextView
    private lateinit var btnTry: TextView

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mem-detail-io").apply { isDaemon = true }
    }
    private val ui = Handler(Looper.getMainLooper())

    private var memId = ""
    private var state = MemoryEntryAdapter.STATE_ACTIVE
    private var pending = false
    private var pkg = ""
    private var steps = 0
    private var hits = 0
    private var misses = 0
    private var intentText = ""

    /** 试跑正在跑：防止连点把同一条记忆并行点两遍。 */
    private var running = false

    /**
     * 跟随 App 内的语言设置。
     *
     * 为什么必须重写：ThemedActivity 只包了主题，语言包装在 SettingsActivity 里是**单独**做的。
     * 新页面不重写就会退回系统语言 —— 实测：设备系统语言英文、App 内设成葡萄牙语时，
     * 设置页是葡语、这个面板整页英文，看起来像没翻译。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_detail)

        memId = intent?.getStringExtra(EXTRA_ID).orEmpty()

        txtTitle = findViewById(R.id.txtMemDetailTitle)
        txtSub = findViewById(R.id.txtMemDetailSub)
        txtState = findViewById(R.id.txtMemDetailState)
        boxSteps = findViewById(R.id.boxMemSteps)
        txtRun = findViewById(R.id.txtMemRunResult)
        btnToggle = findViewById(R.id.btnMemToggle)
        btnTry = findViewById(R.id.btnMemTry)

        findViewById<View>(R.id.btnMemDetailBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMemLabel).setOnClickListener { showLabelDialog() }
        findViewById<View>(R.id.btnMemDelete).setOnClickListener { confirmDelete() }
        btnToggle.setOnClickListener { toggleState() }
        btnTry.setOnClickListener { tryRun() }

        if (memId.isBlank()) {
            // 正常入口不会走到这里；留个明确的出口而不是白屏。
            toast(getString(R.string.mem_load_failed))
            finish()
            return
        }
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        // shutdown() 而不是 shutdownNow()：试跑可能正在桥上逐步点击，
        // 打断它只会让这次重放的结果丢失，而服务端照样会跑完。
        io.shutdown()
    }

    // ==================== 读取 ====================

    private fun load() {
        submit {
            val r = MemoryBridge.get("/memory/v2/get?id=" + enc(memId))
            ui.post { render(r) }
        }
    }

    private fun render(reply: MemoryBridge.Reply) {
        val o = reply.json
        if (o == null) {
            txtTitle.text = getString(R.string.mem_load_failed)
            toast(getString(R.string.mem_offline))
            return
        }
        intentText = o.optString("intent")
        pkg = o.optString("pkg")
        state = o.optString("state", MemoryEntryAdapter.STATE_ACTIVE)
        pending = o.optBoolean("pending", false)
        hits = o.optInt("hits", 0)
        misses = o.optInt("misses", 0)

        val arr = o.optJSONArray("steps")
        steps = arr?.length() ?: 0
        txtTitle.text = o.optString("title").ifBlank { intentText }
        txtSub.text = getString(R.string.mem_item_sub, pkg, steps, hits, misses)
        MemoryEntryAdapter.applyBadge(this, txtState, state, pending)
        btnToggle.text = getString(
            if (state == MemoryEntryAdapter.STATE_DISABLED) R.string.mem_btn_enable
            else R.string.mem_btn_disable,
        )

        boxSteps.removeAllViews()
        if (arr == null || arr.length() == 0) {
            boxSteps.addView(stepLine(getString(R.string.mem_steps_empty), false))
            return
        }
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val action = s.optString("action")
            val target = s.optString("targetText").ifBlank {
                getString(R.string.mem_step_no_target)
            }
            val line = action + " · " + target
            boxSteps.addView(stepLine(getString(R.string.mem_step_line, i + 1, line), true))
        }
    }

    private fun stepLine(text: String, primary: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(if (primary) R.color.text_primary else R.color.text_secondary))
        setPadding(0, dp(4), 0, dp(4))
    }

    // ==================== 标注 ====================

    private fun showLabelDialog() {
        val input = EditText(this).apply {
            setText(intentText)
            hint = getString(R.string.mem_label_title)
            setSingleLine(true)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
            addView(input)
        }
        md3Dialog()
            .setTitle(R.string.mem_btn_label)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val next = input.text.toString().trim()
                if (next.isBlank()) {
                    toast(getString(R.string.mem_label_empty))
                } else {
                    commitLabel(next)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun commitLabel(nextIntent: String) {
        submit {
            val body = JSONObject()
                .put("id", memId)
                .put("intent", nextIntent)
                // title 跟 intent 同步更新：列表标题优先取 title，留着旧标题等于没改。
                .put("title", nextIntent)
                .toString()
            val r = MemoryBridge.post("/memory/v2/label", body)
            ui.post {
                toast(
                    if (r.ok) getString(R.string.mem_label_saved)
                    else getString(R.string.mem_offline)
                )
                if (r.ok) load()
            }
        }
    }

    // ==================== 删除 ====================

    private fun confirmDelete() {
        val name = txtTitle.text.toString()
        md3Dialog()
            .setTitle(R.string.mem_delete_title)
            .setMessage(getString(R.string.mem_delete_msg, name))
            .setPositiveButton(R.string.mem_btn_delete) { _, _ -> deleteNow() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteNow() {
        submit {
            val r = MemoryBridge.post("/memory/v2/delete?id=" + enc(memId), "")
            ui.post {
                if (r.ok) {
                    toast(getString(R.string.mem_delete_ok))
                    finish()
                } else {
                    toast(
                        if (r.unreachable) getString(R.string.mem_offline)
                        else getString(R.string.mem_delete_failed)
                    )
                }
            }
        }
    }

    // ==================== 禁用 / 启用 ====================

    private fun toggleState() {
        val next = if (state == MemoryEntryAdapter.STATE_DISABLED) {
            MemoryEntryAdapter.STATE_ACTIVE
        } else {
            MemoryEntryAdapter.STATE_DISABLED
        }
        submit {
            val body = JSONObject().put("id", memId).put("state", next).toString()
            val r = MemoryBridge.post("/memory/v2/state", body)
            ui.post {
                if (r.ok) {
                    toast(
                        getString(
                            R.string.mem_state_saved,
                            getString(
                                if (next == MemoryEntryAdapter.STATE_DISABLED) {
                                    R.string.mem_badge_disabled
                                } else {
                                    R.string.mem_badge_active
                                },
                            ),
                        ),
                    )
                    load()
                } else {
                    toast(
                        if (r.unreachable) getString(R.string.mem_offline)
                        else getString(R.string.mem_state_failed)
                    )
                }
            }
        }
    }

    // ==================== 试跑（重放） ====================

    private fun tryRun() {
        if (running) return
        running = true
        txtRun.visibility = View.VISIBLE
        txtRun.text = getString(R.string.mem_btn_try)
        submit {
            // 重放要逐步点击 + 每步后置校验，几十秒是正常量级，读超时给足。
            val body = JSONObject().put("id", memId).toString()
            val r = MemoryBridge.post("/memory/run", body, 120_000)
            ui.post {
                running = false
                txtRun.text = describeRun(r)
                txtRun.visibility = View.VISIBLE
                // 命中/失败计数被重放改写过，重新读一次才与统计口径一致。
                load()
            }
        }
    }

    private fun describeRun(r: MemoryBridge.Reply): String {
        if (r.unreachable) return getString(R.string.mem_offline)
        if (r.code == 503) return getString(R.string.mem_run_no_service)
        val o = r.json ?: return getString(
            R.string.mem_run_failed,
            r.body.take(120).ifBlank { r.code.toString() },
        )
        // 不给 id 之外的判定：桥回 fallback=true 表示档位不够，拒绝执行是有意为之。
        if (!o.optBoolean("ok", false) && o.optBoolean("fallback", false)) {
            return getString(R.string.mem_run_failed, o.optString("reason"))
        }
        val arr = o.optJSONArray("steps")
        val n = arr?.length() ?: 0
        val sb = StringBuilder(getString(R.string.mem_run_title))
        var okCount = 0
        for (i in 0 until n) {
            val s = arr?.optJSONObject(i) ?: continue
            val target = s.optString("target").ifBlank {
                getString(R.string.mem_step_no_target)
            }
            val status = s.optString("status")
            if (status == "ok") okCount++
            sb.append('\n')
            sb.append(getString(R.string.mem_run_line, i + 1, target, status))
        }
        sb.append('\n')
        sb.append(getString(R.string.mem_run_summary, okCount, n))
        return sb.toString()
    }

    // ==================== 小工具 ====================

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 提交后台任务；Activity 已销毁（executor 关停）时静默丢弃，不抛 RejectedExecutionException。 */
    private fun submit(task: () -> Unit) {
        if (io.isShutdown) return
        runCatching { io.execute(task) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** 记忆 id（GET /memory/v2/list 的 entries[].id）。 */
        const val EXTRA_ID = "id"
    }
}

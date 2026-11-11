package app.dsh.mobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONArray
import java.util.concurrent.Executors

/** 桥不可达时统计数值位的占位符：只有符号，不含任何需要翻译的词。 */
private const val NO_VALUE = "\u2014"

/**
 * 记忆面板（M8，无障碍记忆系统 v2）。
 *
 * ## 这一页解决什么
 * 记忆存在 `files/memory/v2/<pkg>.json` 里，命中率、suspect、待标注这些状态
 * 之前只有桥的 JSON 能看到 —— 用户没法确认「这套东西到底学到了什么」。
 * 本页把 GET /memory/v2/stats + /memory/v2/list 摊开：统计条给总量/本应用命中率/
 * 待确认，列表给每条记忆的标题、来源包、步数、命中失败与状态徽标。
 *
 * ## 为什么是 Activity 而不是 Fragment
 * 与项目既有约定一致：全仓 30+ 个页面都是 `ThemedActivity` 子类（Fragment 只在
 * 终端/插件两处用到），并且本页需要自己的顶栏、返回键语义与「从详情页返回即刷新」。
 * 用 Activity 还能直接吃到 ThemedActivity 的 MD3 主题分派与设置页背景图。
 *
 * ## 数据来源与失败态
 * 三个来源都在本机桥上：GET /memory/v2/stats、GET /memory/v2/list、
 * POST /memory/v2/config（录制与自动学习两个开关的写入口）。
 * 桥不可达（引擎没起 / 拿不到 token）时**不做假数据兜底**：列表清空、空态换成
 * mem_offline，避免把「引擎没起」误读成「一条记忆都没有」。
 *
 * ## 统计条为什么是「数字在上、标签在下」
 * 360dp 手机的内容宽约 300dp，三列各约 100dp。把「12 memories」这种整句塞进一列，
 * de / th 的长标签必然换行或截断；拆成 17sp 的大数字 + 11sp 的静态标签后，数字永远
 * 单行，标签最多两行，五种语言都不会把卡片撑破。
 */
class MemoryPanelActivity : ThemedActivity() {

    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var pullHint: TextView
    private lateinit var txtTotal: TextView
    private lateinit var txtHitRate: TextView
    private lateinit var txtPending: TextView
    private lateinit var swRecord: MaterialSwitch
    private lateinit var swAutoLearn: MaterialSwitch
    private lateinit var adapter: MemoryEntryAdapter

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mem-panel-io").apply { isDaemon = true }
    }
    private val ui = Handler(Looper.getMainLooper())

    /** 一次只允许一轮加载：下拉 + onResume 可能同时触发。 */
    private var loading = false

    /** 程序性写法（回填开关状态）不触发写请求。 */
    private var bindingSwitches = false

    /** 桥上次报告的自动学习状态：写失败时用它回弹。 */
    private var autoLearnFromBridge = true

    /** 桥上次报告的录制开关状态：写失败时用它回弹。 */
    private var recordEnabledFromBridge = true

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
        setContentView(R.layout.activity_memory_panel)

        list = findViewById(R.id.listMem)
        empty = findViewById(R.id.txtMemEmpty)
        pullHint = findViewById(R.id.txtMemPullHint)
        txtTotal = findViewById(R.id.txtMemTotal)
        txtHitRate = findViewById(R.id.txtMemHitRate)
        txtPending = findViewById(R.id.txtMemPending)
        swRecord = findViewById(R.id.swMemRecord)
        swAutoLearn = findViewById(R.id.swMemAutoLearn)

        adapter = MemoryEntryAdapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.rows.getOrNull(position)?.let { openDetail(it.id) }
        }

        findViewById<View>(R.id.btnMemBack).setOnClickListener { finish() }

        // 两个开关都走同一个端点：POST /memory/v2/config 支持只带其中一个字段，
        // 回包会把两个真实值都带上，所以拿它回写 Switch 比各自记账更不容易不一致。
        swRecord.setOnCheckedChangeListener { _, on ->
            if (!bindingSwitches) writeConfig(recordEnabled = on)
        }
        swAutoLearn.setOnCheckedChangeListener { _, on ->
            if (!bindingSwitches) writeConfig(autoLearn = on)
        }

        installPullToRefresh()
    }

    override fun onResume() {
        super.onResume()
        // 从详情页删/标注/改状态回来，列表与统计都得重新拉一次。
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        // shutdown() 而不是 shutdownNow()：试跑可能正在桥上逐步点击，
        // 打断它只会让这次重放的结果丢失，而服务端照样会跑完。
        io.shutdown()
    }

    private fun openDetail(id: String) {
        startActivity(
            Intent(this, MemoryDetailActivity::class.java)
                .putExtra(MemoryDetailActivity.EXTRA_ID, id),
        )
    }

    // ==================== 加载 ====================

    private fun reload() {
        if (loading) return
        loading = true
        submit {
            val stats = MemoryBridge.get("/memory/v2/stats")
            val listReply = MemoryBridge.get("/memory/v2/list")
            ui.post {
                loading = false
                applyStats(stats)
                applyList(listReply)
            }
        }
    }

    private fun applyStats(reply: MemoryBridge.Reply) {
        val o = reply.json
        if (o == null) {
            // 桥不可达：数值位统一给破折号（标签仍在，不然整块卡片像坏了）。
            txtTotal.text = NO_VALUE
            txtHitRate.text = NO_VALUE
            txtPending.text = NO_VALUE
            return
        }
        val total = o.optJSONObject("byPkg")?.let { byPkg ->
            byPkg.keys().asSequence().sumOf { byPkg.optJSONObject(it)?.optInt("count", 0) ?: 0 }
        } ?: 0
        // 数值位只放数字，名词交给布局里的静态标签 —— 窄屏三列才不会被长句撑爆。
        txtTotal.text = total.toString()

        // 命中率只挑「本应用」这一桶（用户在这个页面上关心的是自己正在用的 App）。
        val rate = o.optJSONObject("byPkg")
            ?.optJSONObject(packageName)
            ?.optDouble("hitRate", 0.0) ?: 0.0
        // 走算术取整而不是 String.format：pt-BR 等逗号小数点地区会把 "0.67" 变 "0,67"。
        // 「%」的写法交给资源（德语习惯留空格：%1$d %%）—— 这里不让代码拼符号。
        txtHitRate.text = getString(R.string.mem_stat_pct, Math.round(rate * 100).toInt())
        txtPending.text = o.optInt("pending", 0).toString()

        autoLearnFromBridge = o.optBoolean("autoLearn", true)
        recordEnabledFromBridge = o.optBoolean("recordEnabled", true)
        bindingSwitches = true
        swRecord.isChecked = recordEnabledFromBridge
        swAutoLearn.isChecked = autoLearnFromBridge
        bindingSwitches = false
    }

    private fun applyList(reply: MemoryBridge.Reply) {
        val o = reply.json
        if (o == null) {
            adapter.rows = emptyList()
            empty.text = getString(R.string.mem_offline)
            empty.visibility = View.VISIBLE
            pullHint.visibility = View.GONE
            return
        }
        val arr: JSONArray = o.optJSONArray("entries") ?: JSONArray()
        val rows = ArrayList<MemoryEntryAdapter.Row>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            rows.add(
                MemoryEntryAdapter.Row(
                    id = e.optString("id"),
                    pkg = e.optString("pkg"),
                    title = e.optString("title"),
                    intent = e.optString("intent"),
                    state = e.optString("state", MemoryEntryAdapter.STATE_ACTIVE),
                    pending = e.optBoolean("pending", false),
                    hits = e.optInt("hits", 0),
                    misses = e.optInt("misses", 0),
                    steps = e.optInt("steps", 0),
                    lastUsed = e.optLong("lastUsed", 0L),
                ),
            )
        }
        adapter.rows = rows
        empty.text = getString(R.string.mem_empty)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        pullHint.visibility = View.VISIBLE
    }

    // ==================== 录制 / 自动学习开关 ====================

    /**
     * 写开关：POST /memory/v2/config {autoLearn?, recordEnabled?}
     *
     * 只传被点的那一个字段（端点支持部分更新），但**回写两个** —— 回包同时带
     * autoLearn 与 recordEnabled，用桥的真实状态覆盖 UI，就不存在「界面显示开、
     * 引擎其实是关」这种漂移。失败（连不上 / 非 ok 回包）回弹到上次已知值并提示，
     * 不做乐观保留：用户看到的开关状态必须在一次往返后等于引擎状态。
     */
    private fun writeConfig(autoLearn: Boolean? = null, recordEnabled: Boolean? = null) {
        submit {
            val body = org.json.JSONObject()
            if (autoLearn != null) body.put("autoLearn", autoLearn)
            if (recordEnabled != null) body.put("recordEnabled", recordEnabled)
            val r = MemoryBridge.post("/memory/v2/config", body.toString())
            ui.post {
                val o = r.json
                if (r.unreachable || o == null || !o.optBoolean("ok", false)) {
                    toast(
                        getString(
                            if (r.unreachable) R.string.mem_offline
                            else R.string.mem_config_failed,
                        ),
                    )
                    bindingSwitches = true
                    swRecord.isChecked = recordEnabledFromBridge
                    swAutoLearn.isChecked = autoLearnFromBridge
                    bindingSwitches = false
                    return@post
                }
                autoLearnFromBridge = o.optBoolean("autoLearn", autoLearnFromBridge)
                recordEnabledFromBridge = o.optBoolean("recordEnabled", recordEnabledFromBridge)
                bindingSwitches = true
                swRecord.isChecked = recordEnabledFromBridge
                swAutoLearn.isChecked = autoLearnFromBridge
                bindingSwitches = false
            }
        }
    }

    // ==================== 下拉刷新 ====================
    //
    // 不引入 androidx.swiperefreshlayout：build.gradle.kts 不在本卡的写范围，且
    // 依赖里确实没有它。这里用 ListView 的触摸事件做最小实现 —— 列表已在顶部时
    // 往下拖超过阈值就算一次刷新，配合顶部的 txtMemPullHint 做进度提示。

    private fun installPullToRefresh() {
        val threshold = 72f * resources.displayMetrics.density
        var downY = 0f
        var tracking = false
        var armed = false
        list.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = !list.canScrollVertically(-1)
                    downY = ev.y
                    armed = false
                }
                MotionEvent.ACTION_MOVE -> if (tracking) {
                    val dy = ev.y - downY
                    if (dy > 0f) {
                        pullHint.alpha = (dy / threshold).coerceIn(0f, 1f)
                        if (dy > threshold) armed = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tracking && armed) reload()
                    tracking = false
                    armed = false
                    pullHint.animate().alpha(1f).setDuration(160L).start()
                }
            }
            false
        }
    }

    /** 提交后台任务；Activity 已销毁（executor 关停）时静默丢弃，不抛 RejectedExecutionException。 */
    private fun submit(task: () -> Unit) {
        if (io.isShutdown) return
        runCatching { io.execute(task) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

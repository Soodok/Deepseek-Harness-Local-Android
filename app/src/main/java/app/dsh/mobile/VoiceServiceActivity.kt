package app.dsh.mobile

import android.app.Activity
import android.content.Intent
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.CloudAsr
import app.dsh.mobile.engine.EdgeTts

/**
 * 语音服务（v1.2.79）—— 识别 + 合成一页，入口在「扩展中心」。
 *
 * ## 识别（主人要求）
 * 只有两条路：**自用 API**（用户自己填地址/Key）或**系统识别**。
 * 不再内置任何"免费 API 预设"—— 服务商由用户自己选（点问号看说明）。
 *
 * ## 合成
 * 微软 Edge 神经网络音色（App 直连，无中转）/ 系统引擎；**音色从下拉里选**，
 * 不让用户手填（主人："有的时候用户都不知道可以填什么"）。
 */
class VoiceServiceActivity : ThemedActivity() {

    // 识别
    private lateinit var asrEngineSpinner: Spinner
    private lateinit var asrKey: EditText
    private lateinit var asrEndpoint: EditText
    private lateinit var asrModel: EditText
    // 合成
    private lateinit var ttsEnabled: RadioButton
    private lateinit var ttsDisabled: RadioButton
    private lateinit var ttsVoiceSpinner: Spinner
    private lateinit var ttsRateSpinner: Spinner

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.4.18：与其他原生设置页一致，跟随应用朝向偏好
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_voice_service)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        // 问号：说明为什么需要、可选哪些服务商
        findViewById<View>(R.id.btnAsrHelp).setOnClickListener { showAsrHelp() }

        // v1.5.9（宝宝要求）：进来先二选一 —— 本地语音识别 / API（云端），
        // 选完才展开各自的详细选项。默认按「有没有装本地识别应用」自动选。
        val prefs = getSharedPreferences("dsh_voice_mode", MODE_PRIVATE)
        val localPanel = findViewById<LinearLayout>(R.id.localPanel)
        val apiPanel = findViewById<LinearLayout>(R.id.apiPanel)
        val segLocal = findViewById<TextView>(R.id.segLocal)
        val segApi = findViewById<TextView>(R.id.segApi)

        fun paint(seg: TextView, on: Boolean) {
            seg.setBackgroundResource(if (on) R.drawable.bg_btn_accent else 0)
            seg.setTextColor(getColor(if (on) R.color.on_accent else R.color.accent))
        }
        fun render(local: Boolean) {
            localPanel.visibility = if (local) View.VISIBLE else View.GONE
            apiPanel.visibility = if (local) View.GONE else View.VISIBLE
            paint(segLocal, local)
            paint(segApi, !local)
        }
        fun choose(local: Boolean) {
            prefs.edit().putBoolean("local_first", local).apply()
            render(local)
        }
        val asrReady = runCatching {
            packageManager.getPackageInfo("app.dsh.asr", 0) != null
        }.getOrDefault(false)
        choose(prefs.getBoolean("local_first", asrReady))
        segLocal.setOnClickListener { choose(true) }
        segApi.setOnClickListener { choose(false) }

        // 仓库入口：本地识别应用不在商店里，只能从 GitHub 下载
        findViewById<View>(R.id.btnAsrRepo).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/Soodok/dsh-asr-service/releases")))
            }.onFailure { Toast.makeText(this, R.string.asr_repo_open, Toast.LENGTH_SHORT).show() }
        }

        bindLocalAsrCard()

        asrEngineSpinner = findViewById(R.id.asrEngineSpinner)
        asrKey = findViewById(R.id.asrKey)
        asrEndpoint = findViewById(R.id.asrEndpoint)
        asrModel = findViewById(R.id.asrModel)
        ttsEnabled = findViewById(R.id.ttsOn)
        ttsDisabled = findViewById(R.id.ttsOff)
        ttsVoiceSpinner = findViewById(R.id.ttsVoiceSpinner)
        ttsRateSpinner = findViewById(R.id.ttsRateSpinner)

        load()

        findViewById<LinearLayout>(R.id.rowSave).setOnClickListener { save() }
        findViewById<LinearLayout>(R.id.rowTestTts).setOnClickListener { testTts() }
    }

    /** 回到本页时重新对齐朝向：外观页可能刚改过开关/偏好。 */
    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
    }

    private fun load() {
        // 识别引擎（v1.2.87）：系统默认 / 已安装的 DSH 插件
        // —— 很多 ROM 把系统「语音输入」设置页重定向了，用户没地方选，只能在这里选
        engineOptions = buildList {
            add(null to getString(R.string.asr_engine_system))
            if (AsrManager.isPluginInstalled(this@VoiceServiceActivity)) {
                add(
                    "${AsrManager.PLUGIN_PKG}/${AsrManager.PLUGIN_SERVICE}" to
                        getString(R.string.asr_engine_plugin)
                )
            }
        }
        asrEngineSpinner.setPopupBackgroundDrawable(getDrawable(R.drawable.bg_spinner_popup))
        asrEngineSpinner.adapter = darkAdapter(engineOptions.map { it.second })
        val curEngine = AsrManager.preferredComponent(this)
        asrEngineSpinner.setSelection(
            engineOptions.indexOfFirst { it.first == curEngine }.coerceAtLeast(0)
        )

        // 识别：地址/模型默认留空（用户自己填）
        asrKey.setText(CloudAsr.apiKey(this))
        asrEndpoint.setText(CloudAsr.endpoint(this))
        asrModel.setText(CloudAsr.model(this))

        // 合成：音色下拉（选项来自 EdgeTts.VOICES，不让手填）
        val labels = EdgeTts.VOICES.map { getString(it.second) }
        // 弹框背景（正规 API）：圆角由它负责，条目只留水波纹
        ttsVoiceSpinner.setPopupBackgroundDrawable(getDrawable(R.drawable.bg_spinner_popup))
        ttsVoiceSpinner.adapter = darkAdapter(labels)
        val cur = EdgeTts.voice(this)
        val idx = EdgeTts.VOICES.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        ttsVoiceSpinner.setSelection(idx)

        // 语速下拉（固定几档，避免用户不知道填什么格式）
        val rates = listOf("-20%", "-10%", "+0%", "+10%", "+20%", "+30%")
        ttsRateSpinner.setPopupBackgroundDrawable(getDrawable(R.drawable.bg_spinner_popup))
        ttsRateSpinner.adapter = darkAdapter(rates.map { getString(R.string.voice_rate_label, it) })
        val curRate = EdgeTts.rate(this)
        ttsRateSpinner.setSelection(rates.indexOf(curRate).coerceAtLeast(0))

        ttsEnabled.isChecked = EdgeTts.enabled(this)
        ttsDisabled.isChecked = !EdgeTts.enabled(this)
    }

    /** 识别引擎选项：组件 spec（null=系统默认）→ 显示名 */
    private var engineOptions: List<Pair<String?, String>> = emptyList()

    private fun save() {
        // 识别引擎
        AsrManager.setPreferredComponent(
            this, engineOptions.getOrNull(asrEngineSpinner.selectedItemPosition)?.first
        )
        CloudAsr.save(
            this,
            asrEndpoint.text?.toString().orEmpty(),
            asrModel.text?.toString().orEmpty(),
            asrKey.text?.toString().orEmpty(),
        )
        val voice = EdgeTts.VOICES.getOrNull(ttsVoiceSpinner.selectedItemPosition)?.first
            ?: EdgeTts.DEFAULT_VOICE
        val rate = listOf("-20%", "-10%", "+0%", "+10%", "+20%", "+30%")
            .getOrNull(ttsRateSpinner.selectedItemPosition) ?: "+0%"
        EdgeTts.save(this, ttsEnabled.isChecked, voice, rate)
        toast(getString(R.string.cloud_asr_saved))
    }

    /** 问号说明：为什么要配、能选什么 */
    private fun showAsrHelp() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.asr_help_title))
            // ⚠️ aapt 会把 XML 字符串里的换行压成空格（实测 setMessage 拿到的是一整行），
            // 所以正文拆成多个 string，这里用换行拼装 —— 段落结构才保得住。
            .setMessage(
                listOf(
                    R.string.asr_help_body_1, R.string.asr_help_body_2,
                    R.string.asr_help_body_3, R.string.asr_help_body_4,
                    R.string.asr_help_body_5, R.string.asr_help_body_6,
                    R.string.asr_help_body_7, R.string.asr_help_body_8,
                    R.string.asr_help_body_9, R.string.asr_help_body_10,
                ).joinToString("\n") { getString(it) }
            )
            // 「安装本地识别应用」→ 打开下载列表页（一键跳浏览器，不申请安装权限）
            .setPositiveButton(R.string.asrapp_entry) { _, _ ->
                startActivity(Intent(this, AsrAppActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }


    // ================= 本地识别状态卡（v1.5.7） =================
    //
    // 为什么要把状态与动作搬到这一页：原来「本地识别」只在帮助弹窗里有个入口，
    // 用户得 帮助 → 识别应用页 → 跳 ASR App → 下模型 → 设为默认 → 返回本页 →
    // 再把引擎改成「系统识别」，跨两个 App 三次跳转、全程看不到进度。
    // 现在状态一眼可见，四个动作就地可点。
    private val ASR_PKG = "app.dsh.asr"

    /**
     * 状态卡所需的最低识别应用版本（v0.8.0 / versionCode 32）。
     *
     * ## 为什么必须比版本（2026-11-14 真机事故）
     * 状态卡是照着 v0.8.0 的接口写的：`content://app.dsh.asr.status/status`
     * 取模型/默认态，`SET_DEFAULT` / `CHECK` 一键设默认与自检。
     * 但设备上装的是 **0.7.4（versionCode 31）** —— 那版三个能力全没有：
     *
     *   content query → Could not find provider: app.dsh.asr.status
     *   query-activities -a app.dsh.asr.SET_DEFAULT → No activities found
     *   query-activities -a app.dsh.asr.CHECK       → No activities found
     *
     * 于是 `startActivity` 抛 ActivityNotFoundException → 走兜底 → 弹出
     * 「请先安装语音识别服务」。**用户明明是装了的**（模型也下了），
     * 看到的却是"没装" —— 这就是那条「真实测试根本用不了」的根因。
     *
     * 只判"装没装"不够：装了旧版 = 装了但接口不存在，必须单独一态。
     */
    private val ASR_MIN_VERSION_CODE = 32

    private fun asrInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(ASR_PKG, 0)
        true
    }.getOrDefault(false)

    /** 已装版本的 versionCode；没装返回 0。 */
    private fun asrVersionCode(): Long = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(ASR_PKG, 0).versionCode.toLong()
    }.getOrDefault(0L)

    /** 装了但版本低于 [ASR_MIN_VERSION_CODE]：接口对不上，需要更新。 */
    private fun asrTooOld(): Boolean {
        val v = asrVersionCode()
        return v in 1 until ASR_MIN_VERSION_CODE
    }

    /** 一条能真正解释现状的提示：没装 / 版本过旧 / 动作暂不可用，分开说。 */
    private fun asrTroubleText(): Int = when {
        !asrInstalled() -> R.string.asr_local_need_install
        asrTooOld() -> R.string.asr_local_need_update
        else -> R.string.asr_local_action_failed
    }

    /**
     * 读 ASR 侧的状态（v0.8.0 起的 ContentProvider）。
     * 只判断"装没装"是不够的 —— 用户下完模型、设完默认，这里还是「已安装」，
     * 那一步进度就白做了。读不到（老版本/未实现）时返回 null，退回只显示安装态。
     */
    private fun asrStatus(): org.json.JSONObject? = runCatching {
        val uri = android.net.Uri.parse("content://app.dsh.asr.status/status")
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            org.json.JSONObject(c.getString(0))
        }
    }.getOrNull()

    private fun bindLocalAsrCard() {
        val installed = asrInstalled()
        val tooOld = installed && asrTooOld()
        val state = findViewById<TextView>(R.id.asrLocalState)
        val desc = findViewById<TextView>(R.id.asrLocalDesc)
        val st = if (installed && !tooOld) asrStatus() else null
        val modelReady = st?.optBoolean("modelReady", false) ?: false
        val isDefault = if (st != null && !st.isNull("isDefault")) st.optBoolean("isDefault") else null
        val fullyReady = installed && !tooOld && modelReady && isDefault == true
        state.setText(
            when {
                fullyReady -> R.string.asr_local_state_ready
                // 旧版必须与"已安装"分开 —— 否则用户看到绿的「已安装」，点任何按钮都失败
                tooOld -> R.string.asr_local_state_outdated
                installed -> R.string.asr_local_state_installed
                else -> R.string.asr_local_state_missing
            }
        )
        state.setTextColor(
            getColor(
                when {
                    fullyReady -> R.color.success
                    tooOld -> R.color.danger
                    installed -> R.color.warning
                    else -> R.color.danger
                }
            )
        )
        desc.setText(
            when {
                tooOld -> R.string.asr_local_desc_outdated
                installed -> R.string.asr_local_desc
                else -> R.string.asr_local_need_install
            }
        )

        // 安装 / 更新：进识别应用页（那页有 GitHub Releases 下载与安装引导）
        findViewById<View>(R.id.btnAsrInstall).setOnClickListener {
            startActivity(Intent(this, AsrAppActivity::class.java))
        }
        // 三个「需要已安装且版本够新」的动作：不满足时置灰并说明具体原因，不做假按钮
        listOf(
            R.id.btnAsrOpenConfig to "app.dsh.asr.OPEN",
            R.id.btnAsrSetDefault to "app.dsh.asr.SET_DEFAULT",
            R.id.btnAsrSelfCheck to "app.dsh.asr.CHECK",
        ).forEach { (id, action) ->
            findViewById<View>(id).apply {
                alpha = if (installed && !tooOld) 1f else 0.45f
                setOnClickListener {
                    when {
                        !asrInstalled() -> {
                            toast(getString(R.string.asr_local_need_install))
                            return@setOnClickListener
                        }
                        asrTooOld() -> {
                            toast(getString(R.string.asr_local_need_update))
                            return@setOnClickListener
                        }
                    }
                    val ok = runCatching {
                        startActivity(Intent(action).setPackage(ASR_PKG))
                        true
                    }.getOrDefault(false)
                    // SET_DEFAULT / CHECK 只有 0.8.0+ 才声明。走到这里说明版本够新
                    // 但解析失败（被 ROM 拦、或那版确实没实现）→ 退回「打开配置」，
                    // 并且说"动作不可用"而不是误导性的"请先安装"。
                    if (!ok) {
                        runCatching { startActivity(Intent("app.dsh.asr.OPEN").setPackage(ASR_PKG)) }
                        toast(getString(asrTroubleText()))
                    }
                }
            }
        }
    }

    private fun testTts() {
        save()   // 先存再试，避免"测的是旧配置"
        Thread({
            val r = EdgeTts.speak(this, getString(R.string.tts_test_text), true)
            runOnUiThread { toast(r) }
        }, "edge-tts-test").apply { isDaemon = true; start() }
    }

    /**
     * 暗色 Spinner 适配器。
     *
     * ⚠️ 为什么不用 `android:popupTheme`：实测在 Spinner 上不生效
     * （弹出层仍是框架默认的白底灰字，主人反馈"还是原版样式"）。
     * 可靠做法是在适配器里**直接给下拉条目设背景色与文字色** ——
     * 下拉弹框的背景由条目自己铺满，所以设条目背景就等于设弹框背景。
     */
    private fun darkAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, R.layout.item_spinner_dark, items) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.apply {
                    // 同样不设 setBackgroundColor（Spinner 收起态的背景由外层 bg_voice_input 提供）
                    setTextColor(dshColor(R.attr.dshTextPrimary, R.color.text_primary))
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply {
                    // ⚠️ 不要用 setBackgroundColor：那会**冲掉 drawable 的圆角**，
                    // 条目变成直角方块（主人反馈"边缘还是不圆润"）。
                    // 背景交给 item_spinner_dark 的 drawable（含圆角 + 水波纹），这里只设文字色。
                    setTextColor(dshColor(R.attr.dshTextPrimary, R.color.text_primary))
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                return v
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

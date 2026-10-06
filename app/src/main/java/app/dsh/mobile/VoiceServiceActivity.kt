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
class VoiceServiceActivity : Activity() {

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
        setContentView(R.layout.activity_voice_service)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        // 问号：说明为什么需要、可选哪些服务商
        findViewById<View>(R.id.btnAsrHelp).setOnClickListener { showAsrHelp() }

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
                    setTextColor(0xFFE8ECF2.toInt())
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply {
                    // ⚠️ 不要用 setBackgroundColor：那会**冲掉 drawable 的圆角**，
                    // 条目变成直角方块（主人反馈"边缘还是不圆润"）。
                    // 背景交给 item_spinner_dark 的 drawable（含圆角 + 水波纹），这里只设文字色。
                    setTextColor(0xFFE8ECF2.toInt())
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                return v
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

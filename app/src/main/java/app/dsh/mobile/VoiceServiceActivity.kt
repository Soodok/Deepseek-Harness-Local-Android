package app.dsh.mobile

import android.app.Activity
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
        // 识别：地址/模型默认留空（用户自己填）
        asrKey.setText(CloudAsr.apiKey(this))
        asrEndpoint.setText(CloudAsr.endpoint(this))
        asrModel.setText(CloudAsr.model(this))

        // 合成：音色下拉（选项来自 EdgeTts.VOICES，不让手填）
        val labels = EdgeTts.VOICES.map { getString(it.second) }
        ttsVoiceSpinner.adapter = darkAdapter(labels)
        val cur = EdgeTts.voice(this)
        val idx = EdgeTts.VOICES.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        ttsVoiceSpinner.setSelection(idx)

        // 语速下拉（固定几档，避免用户不知道填什么格式）
        val rates = listOf("-20%", "-10%", "+0%", "+10%", "+20%", "+30%")
        ttsRateSpinner.adapter = darkAdapter(rates.map { getString(R.string.voice_rate_label, it) })
        val curRate = EdgeTts.rate(this)
        ttsRateSpinner.setSelection(rates.indexOf(curRate).coerceAtLeast(0))

        ttsEnabled.isChecked = EdgeTts.enabled(this)
        ttsDisabled.isChecked = !EdgeTts.enabled(this)
    }

    private fun save() {
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
            .setMessage(getString(R.string.asr_help_body))
            .setPositiveButton(android.R.string.ok, null)
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
                    setBackgroundColor(0xFF1B222C.toInt())   // 收起态：与输入框同色
                    setTextColor(0xFFE8ECF2.toInt())
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply {
                    // 下拉条目：铺满整行，深色底 + 亮色字（弹框背景由此而来）
                    setBackgroundColor(0xFF1B222C.toInt())
                    setTextColor(0xFFE8ECF2.toInt())
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                return v
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

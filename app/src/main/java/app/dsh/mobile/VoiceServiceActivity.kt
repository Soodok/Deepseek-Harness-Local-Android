package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.CloudAsr
import app.dsh.mobile.engine.EdgeTts

/**
 * 语音服务（v1.2.78）—— **识别 + 合成合并成一页**，入口在「扩展中心」。
 *
 * ## 为什么合并
 * 主人反馈「云端语音识别和云端语音合成重复了」。两者都是"语音能力配置"，
 * 拆成两个入口既重复又难找；现在一页两段：语音识别 / 语音合成。
 *
 * ## 设计（按主人要求）
 *  · **语音识别**：默认走**免费 API**（硅基流动，用户只需填 Key）；也可切到**自用 API**
 *    （自定义地址，兼容任何 OpenAI 格式的转写接口）
 *  · **语音合成**：**客户端直连微软 edge-tts**（不需要任何服务器中转，见 EdgeTts 注释），
 *    可开关、选音色、调语速；关闭则用系统 TTS
 */
class VoiceServiceActivity : Activity() {

    // —— 识别 ——
    private lateinit var asrFreeRadio: RadioButton
    private lateinit var asrCustomRadio: RadioButton
    private lateinit var asrKey: EditText
    private lateinit var asrEndpoint: EditText
    private lateinit var asrModel: EditText

    // —— 合成 ——
    private lateinit var ttsEnabled: RadioButton
    private lateinit var ttsDisabled: RadioButton
    private lateinit var ttsVoice: EditText
    private lateinit var ttsRate: EditText

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_service)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        asrFreeRadio = findViewById(R.id.asrFree)
        asrCustomRadio = findViewById(R.id.asrCustom)
        asrKey = findViewById(R.id.asrKey)
        asrEndpoint = findViewById(R.id.asrEndpoint)
        asrModel = findViewById(R.id.asrModel)

        ttsEnabled = findViewById(R.id.ttsOn)
        ttsDisabled = findViewById(R.id.ttsOff)
        ttsVoice = findViewById(R.id.ttsVoice)
        ttsRate = findViewById(R.id.ttsRate)

        load()

        // 识别方式切换：免费 ↔ 自用（自用才显示地址/模型输入框）
        asrFreeRadio.setOnClickListener { applyAsrMode() }
        asrCustomRadio.setOnClickListener { applyAsrMode() }

        findViewById<LinearLayout>(R.id.rowSave).setOnClickListener { save() }
        findViewById<LinearLayout>(R.id.rowTestTts).setOnClickListener { testTts() }
    }

    private fun load() {
        val custom = CloudAsr.isCustomEndpoint(this)
        asrFreeRadio.isChecked = !custom
        asrCustomRadio.isChecked = custom
        asrKey.setText(CloudAsr.apiKey(this))
        asrEndpoint.setText(CloudAsr.endpoint(this))
        asrModel.setText(CloudAsr.model(this))
        applyAsrMode()

        ttsEnabled.isChecked = EdgeTts.enabled(this)
        ttsDisabled.isChecked = !EdgeTts.enabled(this)
        ttsVoice.setText(EdgeTts.voice(this))
        ttsRate.setText(EdgeTts.rate(this))
    }

    /** 免费模式：地址/模型用固定默认值且不可改；自用模式：可改 */
    private fun applyAsrMode() {
        val custom = asrCustomRadio.isChecked
        if (!custom) {
            // 免费 API：写回官方默认值（用户只需填 Key）
            asrEndpoint.setText(CloudAsr.FREE_ENDPOINT)
            asrModel.setText(CloudAsr.FREE_MODEL)
        }
        asrEndpoint.isEnabled = custom
        asrModel.isEnabled = custom
        asrEndpoint.alpha = if (custom) 1f else 0.5f
        asrModel.alpha = if (custom) 1f else 0.5f
    }

    private fun save() {
        // 识别：免费模式强制用默认端点
        val custom = asrCustomRadio.isChecked
        CloudAsr.save(
            this,
            if (custom) asrEndpoint.text?.toString().orEmpty() else CloudAsr.FREE_ENDPOINT,
            if (custom) asrModel.text?.toString().orEmpty() else CloudAsr.FREE_MODEL,
            asrKey.text?.toString().orEmpty(),
            custom,
        )
        // 合成
        EdgeTts.save(
            this,
            ttsEnabled.isChecked,
            ttsVoice.text?.toString().orEmpty(),
            ttsRate.text?.toString().orEmpty(),
        )
        toast(getString(R.string.cloud_asr_saved))
    }

    /** 试听：走 EdgeTts（含网络，必须后台线程） */
    private fun testTts() {
        // 先存，避免"测的是旧配置"
        EdgeTts.save(this, true, ttsVoice.text.toString(), ttsRate.text.toString())
        Thread({
            val r = EdgeTts.speak(this, getString(R.string.tts_test_text), true)
            runOnUiThread { toast(r) }
        }, "edge-tts-test").apply { isDaemon = true; start() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

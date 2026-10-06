package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.CloudTts

/**
 * 云端语音合成设置（v1.2.77）。
 *
 * ## 为什么有这个页面
 * 系统 TTS 的中文音色机械感强。服务端跑 **edge-tts**（微软 Edge 神经网络语音，
 * 免费无需 Key）后，音色质量高一个档次。用户可填自己的服务地址（自建/共享皆可）。
 *
 * ## 通道优先级
 *   ① 云端（本页配置了地址）→ ② 系统 TTS（失败自动回落）
 */
class CloudTtsActivity : Activity() {

    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var voiceInput: EditText
    private lateinit var rateInput: EditText

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_tts)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        endpointInput = findViewById(R.id.ttsEndpoint)
        tokenInput = findViewById(R.id.ttsToken)
        voiceInput = findViewById(R.id.ttsVoice)
        rateInput = findViewById(R.id.ttsRate)

        endpointInput.setText(CloudTts.endpoint(this))
        tokenInput.setText(CloudTts.token(this))
        voiceInput.setText(CloudTts.voice(this))
        rateInput.setText(CloudTts.rate(this))

        findViewById<LinearLayout>(R.id.rowSave).setOnClickListener { save() }
        findViewById<LinearLayout>(R.id.rowTest).setOnClickListener { test() }
        findViewById<LinearLayout>(R.id.rowClear).setOnClickListener {
            endpointInput.setText("")
            CloudTts.save(this, "", "", voiceInput.text.toString(), rateInput.text.toString())
            CloudTts.clearCache(this)
            toast(getString(R.string.cloud_tts_cleared))
            refreshStatus()
        }
        refreshStatus()
    }

    private fun save() {
        CloudTts.save(
            this,
            endpointInput.text?.toString().orEmpty(),
            tokenInput.text?.toString().orEmpty(),
            voiceInput.text?.toString().orEmpty(),
            rateInput.text?.toString().orEmpty(),
        )
        toast(getString(R.string.cloud_asr_saved))
        refreshStatus()
    }

    /** 实测合成一段（后台线程：含网络请求） */
    private fun test() {
        val ep = endpointInput.text?.toString()?.trim().orEmpty()
        if (ep.isBlank()) {
            toast(getString(R.string.cloud_tts_need_endpoint))
            return
        }
        // 先存再测，避免"测的是旧配置"
        CloudTts.save(this, ep, tokenInput.text.toString(), voiceInput.text.toString(), rateInput.text.toString())
        refreshStatus()
        Thread({
            val r = CloudTts.speak(this, getString(R.string.tts_test_text), true)
            runOnUiThread { toast(r) }
        }, "cloud-tts-test").apply { isDaemon = true; start() }
    }

    private fun refreshStatus() {
        val st = findViewById<TextView>(R.id.ttsState)
        st.text = if (CloudTts.isConfigured(this)) getString(R.string.asr_state_ready)
        else getString(R.string.asr_state_none)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

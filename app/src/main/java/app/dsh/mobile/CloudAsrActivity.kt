package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.CloudAsr

/**
 * 云端语音识别设置（v1.2.74）。
 *
 * ## 为什么要有这个页面
 * 主人实测反馈「语音识别服务有点太垃圾了」—— 设备没有系统识别服务时，
 * 原来的兜底是离线 Vosk small（2019 年 Kaldi 时代的小模型），中文很差；
 * v1.2.75 起本地模型整体移除（主人决策：追求功能而非体积），只剩两条通道。
 * 这里让用户填一个免费的云端 ASR（默认硅基流动 SenseVoiceSmall，免费）作为替代。
 *
 * ## 通道优先级（在 DshAccessibilityService 里实现）
 *   ① 系统自带识别（零配置、离线、质量好）—— 有就优先用
 *   ② 系统没有 → 云端 API（本页配置）
 *   （本地模型已移除）
 */
class CloudAsrActivity : Activity() {

    private lateinit var keyInput: EditText
    private lateinit var endpointInput: EditText
    private lateinit var modelInput: EditText

    /** 与全项目一致：按应用内语言设置包装 Context */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_asr)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        keyInput = findViewById(R.id.cloudKey)
        endpointInput = findViewById(R.id.cloudEndpoint)
        modelInput = findViewById(R.id.cloudModel)

        // 载入现有配置（endpoint/model 为空时显示默认值，便于用户知道格式）
        keyInput.setText(CloudAsr.apiKey(this))
        endpointInput.setText(CloudAsr.endpoint(this))
        modelInput.setText(CloudAsr.model(this))

        findViewById<LinearLayout>(R.id.rowSave).setOnClickListener { save() }
        findViewById<LinearLayout>(R.id.rowClear).setOnClickListener {
            keyInput.setText("")
            CloudAsr.save(this, endpointInput.text.toString(), modelInput.text.toString(), "")
            toast(getString(R.string.cloud_asr_clear))
        }
        refreshStatus()
    }

    private fun save() {
        val key = keyInput.text?.toString()?.trim().orEmpty()
        if (key.isBlank()) {
            toast(getString(R.string.cloud_asr_need_key))
            return
        }
        CloudAsr.save(
            this,
            endpointInput.text?.toString().orEmpty(),
            modelInput.text?.toString().orEmpty(),
            key,
        )
        toast(getString(R.string.cloud_asr_saved))
        refreshStatus()
    }

    private fun refreshStatus() {
        val st = findViewById<TextView>(R.id.cloudState)
        st.text = if (CloudAsr.isConfigured(this)) getString(R.string.asr_state_ready)
        else getString(R.string.asr_state_none)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

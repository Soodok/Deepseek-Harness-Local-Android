package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.TtsManager
import app.dsh.mobile.pet.PetOverlayService
import app.dsh.mobile.pet.PetPluginDetector
import app.dsh.mobile.pet.PetSettingsActivity

/**
 * 扩展与语音（v1.4.56）：从设置主页收拢的扩展/语音能力子页。
 *
 * 六项：扩展中心、插件中心、ASR 应用、语音服务、TTS 试听、桌面宠物。
 * 语音区撤销后其两行（语音服务 / TTS）归入本页；设置主页只留一个入口行。
 */
class ExtensionVoiceActivity : ThemedActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.forcePortrait(this)
        setContentView(R.layout.activity_extension)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 社区插件（v1.2.113）：一键安装 dsh 生态插件 ——
        findViewById<LinearLayout>(R.id.rowPlugin).setOnClickListener {
            startActivity(android.content.Intent(this, PluginStoreActivity::class.java))
        }

        // 本地识别应用（v1.2.85）：**直接可见的一行**，不用钻进语音服务页再点问号
        findViewById<LinearLayout>(R.id.rowAsrApps).setOnClickListener {
            startActivity(android.content.Intent(this, app.dsh.mobile.AsrAppActivity::class.java))
        }

        // 语音服务（v1.2.78）：识别 + 合成合并入口
        findViewById<LinearLayout>(R.id.rowVoiceService).setOnClickListener {
            startActivity(android.content.Intent(this, VoiceServiceActivity::class.java))
        }

        // —— 语音合成（TTS）—— 点击播报测试 + 显示引擎状态
        // speak 含 TTS 引擎绑定等待（最长 20s），必须离开主线程（ANR 防护）
        findViewById<LinearLayout>(R.id.rowTts).setOnClickListener {
            Thread({
                val res = TtsManager.speak(this, getString(R.string.tts_test_text), true)
                runOnUiThread {
                    Toast.makeText(this, res, Toast.LENGTH_LONG).show()
                    refreshTtsRow()
                }
            }, "tts-test").apply { isDaemon = true; start() }
        }

        // —— 桌宠悬浮窗（预留，插件驱动）：独立设置页负责权限/包/开关 ——
        findViewById<LinearLayout>(R.id.rowPet).setOnClickListener {
            startActivity(android.content.Intent(this, PetSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTtsRow()
        refreshPetRow()
    }

    /** TTS 引擎可用性（列出 TextToSpeechService 提供方，不触发绑定） */
    private fun refreshTtsRow() {
        val n = TtsManager.engineCount(this)
        val v = findViewById<TextView>(R.id.valTts)
        v.text = if (n == 0) getString(R.string.tts_missing) else getString(R.string.tts_engines, n)
        v.setTextColor(if (n > 0) getColor(R.color.success) else getColor(R.color.text_secondary))
    }

    /** 桌宠入口状态（未装桥插件 / 已就绪 / 运行中 / 缺权限）。 */
    private fun refreshPetRow() {
        val v = findViewById<TextView>(R.id.valPet)
        val plugin = PetPluginDetector.detect(this)
        val running = PetOverlayService.isRunning()
        val canDraw = android.provider.Settings.canDrawOverlays(this)
        val status = app.dsh.mobile.pet.PetStatusBus.state.value
        val text = when {
            !canDraw -> getString(R.string.pet_permission_denied)
            running && status.connected -> getString(R.string.pet_status_connected)
            running -> getString(R.string.pet_status_running)
            !plugin.profileExists -> getString(R.string.pet_status_engine_down)
            // 装了但低于 0.3.1：不能报「未安装」，否则用户会重复装同一个旧版
            plugin.bridgeInstalled && !plugin.bridgeVersionOk ->
                getString(R.string.pet_status_plugin_outdated)
            !plugin.bridgeReady -> getString(R.string.pet_plugin_missing)
            else -> getString(R.string.pet_status_stopped)
        }
        v.text = text
        v.setTextColor(
            when {
                running -> getColor(R.color.success)
                !canDraw || !plugin.bridgeReady -> getColor(R.color.warning)
                else -> getColor(R.color.text_secondary)
            },
        )
    }
}

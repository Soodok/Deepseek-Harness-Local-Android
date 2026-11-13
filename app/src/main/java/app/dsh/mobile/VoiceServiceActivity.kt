package app.dsh.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.CloudAsr
import app.dsh.mobile.engine.EdgeTts
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView

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
    private lateinit var asrEngineSpinner: MaterialAutoCompleteTextView
    private lateinit var asrKey: EditText
    private lateinit var asrEndpoint: EditText
    private lateinit var asrModel: EditText
    // 合成
    private lateinit var ttsEnabled: RadioButton
    private lateinit var ttsDisabled: RadioButton
    private lateinit var ttsVoiceSpinner: MaterialAutoCompleteTextView
    private lateinit var ttsRateSpinner: MaterialAutoCompleteTextView

    private var selectedEngineIndex = 0
    private var selectedVoiceIndex = 0
    private var selectedRateIndex = 0
    private val rates = listOf("-20%", "-10%", "+0%", "+10%", "+20%", "+30%")

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.4.18：与其他原生设置页一致，跟随应用朝向偏好
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_voice_service)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        // 问号：说明为什么需要、可选哪些服务商
        findViewById<View>(R.id.btnAsrHelp).setOnClickListener { showAsrHelp() }

        // v1.5.9（宝宝要求）：进来先二选一 —— 本地语音识别 / API（云端），
        // 选完才展开各自的详细选项。默认按「有没有装本地识别应用」自动选。
        val prefs = getSharedPreferences("dsh_voice_mode", MODE_PRIVATE)
        val localPanel = findViewById<LinearLayout>(R.id.localPanel)
        val apiPanel = findViewById<LinearLayout>(R.id.apiPanel)
        val modeSwitch = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.asrModeSwitch)

        fun render(local: Boolean) {
            localPanel.visibility = if (local) View.VISIBLE else View.GONE
            apiPanel.visibility = if (local) View.GONE else View.VISIBLE
        }
        // 历史未设置用户沿用原有系统优先行为；仅用户主动选择时写入偏好。
        val local = prefs.getBoolean("local_first", true)
        modeSwitch.check(if (local) R.id.segLocal else R.id.segApi)
        render(local)
        modeSwitch.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                val selectedLocal = checkedId == R.id.segLocal
                prefs.edit().putBoolean("local_first", selectedLocal).apply()
                render(selectedLocal)
            }
        }

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

        findViewById<View>(R.id.rowSave).setOnClickListener { save() }
        findViewById<View>(R.id.rowTestTts).setOnClickListener { testTts() }
    }

    /** 回到本页时重新对齐朝向：外观页可能刚改过开关/偏好。 */
    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
        bindLocalAsrCard()
        refreshAsrEngines(engineOptions.getOrNull(selectedEngineIndex)?.first)
    }

    private fun load() {
        refreshAsrEngines(AsrManager.preferredComponent(this))

        // 识别：地址/模型默认留空（用户自己填）
        asrKey.setText(CloudAsr.apiKey(this))
        asrEndpoint.setText(CloudAsr.endpoint(this))
        asrModel.setText(CloudAsr.model(this))

        // 不从本地化显示文字反推选项：重复翻译也保持与原 Spinner 相同的索引语义。
        val labels = EdgeTts.VOICES.map { getString(it.second) }
        ttsVoiceSpinner.setSimpleItems(labels.toTypedArray())
        selectedVoiceIndex = EdgeTts.VOICES.indexOfFirst { it.first == EdgeTts.voice(this) }.coerceAtLeast(0)
        ttsVoiceSpinner.setText(labels.getOrNull(selectedVoiceIndex).orEmpty(), false)
        ttsVoiceSpinner.setOnItemClickListener { _, _, position, _ -> selectedVoiceIndex = position }

        val rateLabels = rates.map { getString(R.string.voice_rate_label, it) }
        ttsRateSpinner.setSimpleItems(rateLabels.toTypedArray())
        selectedRateIndex = rates.indexOf(EdgeTts.rate(this)).coerceAtLeast(0)
        ttsRateSpinner.setText(rateLabels[selectedRateIndex], false)
        ttsRateSpinner.setOnItemClickListener { _, _, position, _ -> selectedRateIndex = position }

        ttsEnabled.isChecked = EdgeTts.enabled(this)
        ttsDisabled.isChecked = !EdgeTts.enabled(this)
    }

    private fun refreshAsrEngines(selected: String?) {
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
        val labels = engineOptions.map { it.second }
        asrEngineSpinner.setSimpleItems(labels.toTypedArray())
        selectedEngineIndex = engineOptions.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        asrEngineSpinner.setText(labels[selectedEngineIndex], false)
        asrEngineSpinner.setOnItemClickListener { _, _, position, _ -> selectedEngineIndex = position }
    }

    /** 识别引擎选项：组件 spec（null=系统默认）→ 显示名 */
    private var engineOptions: List<Pair<String?, String>> = emptyList()

    private fun save() {
        // 识别引擎
        AsrManager.setPreferredComponent(
            this, engineOptions.getOrNull(selectedEngineIndex)?.first
        )
        CloudAsr.save(
            this,
            asrEndpoint.text?.toString().orEmpty(),
            asrModel.text?.toString().orEmpty(),
            asrKey.text?.toString().orEmpty(),
        )
        val voice = EdgeTts.VOICES.getOrNull(selectedVoiceIndex)?.first
            ?: EdgeTts.DEFAULT_VOICE
        val rate = rates.getOrNull(selectedRateIndex) ?: "+0%"
        EdgeTts.save(this, ttsEnabled.isChecked, voice, rate)
        toast(getString(R.string.cloud_asr_saved))
    }

    /** 问号说明：为什么要配、能选什么 */
    private fun showAsrHelp() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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

    private fun asrInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(ASR_PKG, 0)
        true
    }.getOrDefault(false)

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
        val state = findViewById<TextView>(R.id.asrLocalState)
        val desc = findViewById<TextView>(R.id.asrLocalDesc)
        val st = if (installed) asrStatus() else null
        val modelReady = st?.optBoolean("modelReady", false) ?: false
        val isDefault = if (st != null && !st.isNull("isDefault")) st.optBoolean("isDefault") else null
        val fullyReady = installed && modelReady && isDefault == true
        state.setText(
            when {
                fullyReady -> R.string.asr_local_state_ready
                installed -> R.string.asr_local_state_installed
                else -> R.string.asr_local_state_missing
            }
        )
        val stateColor = when {
            fullyReady -> androidx.appcompat.R.attr.colorPrimary
            installed -> com.google.android.material.R.attr.colorOnSurfaceVariant
            else -> androidx.appcompat.R.attr.colorError
        }
        state.setTextColor(MaterialColors.getColor(state, stateColor))
        desc.setText(if (installed) R.string.asr_local_desc else R.string.asr_local_need_install)

        // 安装 / 更新：进识别应用页（那页有 GitHub Releases 下载与安装引导）
        findViewById<View>(R.id.btnAsrInstall).setOnClickListener {
            startActivity(Intent(this, AsrAppActivity::class.java))
        }
        // 三个「需要已安装」的动作：未装时置灰并提示，不做假按钮
        listOf(
            R.id.btnAsrOpenConfig to "app.dsh.asr.OPEN",
            R.id.btnAsrSetDefault to "app.dsh.asr.SET_DEFAULT",
            R.id.btnAsrSelfCheck to "app.dsh.asr.CHECK",
        ).forEach { (id, action) ->
            findViewById<View>(id).apply {
                alpha = if (installed) 1f else 0.45f
                setOnClickListener {
                    if (!asrInstalled()) {
                        toast(getString(R.string.asr_local_need_install))
                        return@setOnClickListener
                    }
                    val ok = runCatching {
                        startActivity(Intent(action).setPackage(ASR_PKG))
                        true
                    }.getOrDefault(false)
                    // SET_DEFAULT / CHECK 要等 ASR 侧补上对应 intent（P3）；
                    // 现在还没实现时退回「打开配置」，不假装成功。
                    if (!ok) {
                        runCatching { startActivity(Intent("app.dsh.asr.OPEN").setPackage(ASR_PKG)) }
                        toast(getString(R.string.asr_local_need_install))
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

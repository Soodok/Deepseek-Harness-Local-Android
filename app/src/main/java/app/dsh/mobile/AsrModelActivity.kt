package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.AsrModelCatalog
import app.dsh.mobile.engine.AsrModelManager
import app.dsh.mobile.engine.VoskRecognizer

/**
 * ASR 模型管理页（v1.2.62）。
 *
 * 入口：设置页「扩展中心」下方的「语音识别服务」。
 * 功能：按语言分组展示可下载的离线模型 → 注明适用场景 → 下载（带进度/双源镜像）→
 *       下载完成后自动初始化 VoskRecognizer（后续语音输入优先走离线识别）。
 *
 * 模型状态三态：未下载 / 下载中(进度条) / 已就绪（可点击删除）。
 */
class AsrModelActivity : Activity() {

    companion object {
        private const val TAG = "AsrModelActivity"
    }

    private lateinit var listHost: LinearLayout

    /** 每个模型对应的行视图（下载完成后更新状态文字） */
    private val rows = HashMap<String, Pair<TextView, ProgressBar>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_models)
        listHost = findViewById(R.id.asrList)
        findViewById<TextView>(R.id.asrBack).setOnClickListener { finish() }
        buildList()
    }

    /** 构建模型列表（每次进入重建，状态最新） */
    private fun buildList() {
        listHost.removeAllViews()
        val inflater = LayoutInflater.from(this)

        AsrModelCatalog.models.forEach { model ->
            val group = TextView(this).apply {
                text = when (model.language) {
                    "zh-CN" -> getString(R.string.asr_group_chinese)
                    "en-IN" -> getString(R.string.asr_group_indian)
                    else -> getString(R.string.asr_group_english)
                }
                textSize = 14f
                setTextColor(0xFF7DD3FC.toInt())
                setPadding(dp(8), dp(14), dp(8), dp(6))
            }
            listHost.addView(group)

            val row = inflater.inflate(R.layout.item_asr_model, listHost, false)
            val name = row.findViewById<TextView>(R.id.asrModelName)
            val desc = row.findViewById<TextView>(R.id.asrModelDesc)
            val action = row.findViewById<TextView>(R.id.asrModelAction)
            val progress = row.findViewById<ProgressBar>(R.id.asrModelProgress)

            name.text = model.label
            desc.text = when (model.language) {
                "zh-CN" -> getString(R.string.asr_cn_desc)
                "en-IN" -> getString(R.string.asr_enin_desc)
                else -> getString(R.string.asr_en_desc)
            }
            rows[model.id] = action to progress

            refreshRow(model.id)
            row.setOnClickListener {
                val downloading = progress.visibility == View.VISIBLE
                if (downloading) {
                    return@setOnClickListener
                }
                if (AsrModelManager.isDownloaded(this, model)) {
                    // 已就绪 → 初始化（或提示已就绪）
                    initModel(model.id)
                    return@setOnClickListener
                }
                download(model.id)
            }
            listHost.addView(row)
        }
    }

    /** 单行刷新：按当前状态显示 未下载/下载中/已就绪 */
    private fun refreshRow(id: String) {
        val model = AsrModelCatalog.models.firstOrNull { it.id == id } ?: return
        val (action, progress) = rows[id] ?: return
        if (AsrModelManager.isDownloaded(this, model)) {
            action.text = getString(R.string.asr_state_ready)
            action.setTextColor(0xFF81C995.toInt())
            progress.visibility = View.GONE
        } else {
            action.text = getString(R.string.asr_state_none)
            action.setTextColor(0xFF9AA0A6.toInt())
            progress.visibility = View.GONE
        }
    }

    /** 开始下载（后台线程，进度回主线程） */
    private fun download(id: String) {
        val model = AsrModelCatalog.models.firstOrNull { it.id == id } ?: return
        val (action, progress) = rows[id] ?: return
        progress.visibility = View.VISIBLE
        progress.progress = 0
        action.text = getString(R.string.asr_state_downloading)
        action.setTextColor(0xFF7DD3FC.toInt())

        AsrModelManager.download(
            this, model,
            onProgress = { p -> runOnUiThread { progress.progress = (p * 100).toInt() } },
            onDone = {
                runOnUiThread {
                    toast(getString(R.string.voice_sent).let { getMessageDownloaded(id) })
                    initModel(id)
                    refreshRow(id)
                }
            },
            onError = { msg ->
                runOnUiThread {
                    toast(getString(R.string.asr_download_failed, msg))
                    refreshRow(id)
                }
            },
        )
    }

    /**
     * 下载完成后初始化识别器（后台 load ~40MB，成功后写状态）。
     * 加载在应用线程做，不阻塞 UI。
     */
    private fun initModel(id: String) {
        val model = AsrModelCatalog.models.firstOrNull { it.id == id } ?: return
        val path = AsrModelManager.modelPath(this, model) ?: return
        Thread({
            val ok = VoskRecognizer.init(this, path)
            runOnUiThread {
                if (ok) {
                    toast(getString(R.string.asr_state_ready))
                    refreshRow(id)
                } else {
                    toast(getString(R.string.asr_model_load_failed))
                }
            }
        }, "asr-init").apply { isDaemon = true; start() }
    }

    /** 下载成功后的提示文案（中英随资源走） */
    private fun getMessageDownloaded(id: String): String =
        getString(R.string.asr_state_ready)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}

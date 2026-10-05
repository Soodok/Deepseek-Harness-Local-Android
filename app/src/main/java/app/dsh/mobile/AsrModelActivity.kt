package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.AsrModelCatalog
import app.dsh.mobile.engine.AsrModelManager
import app.dsh.mobile.engine.VoskRecognizer

/**
 * ASR 模型管理页（v1.2.62 初版，v1.2.64 重做视觉）。
 *
 * 入口：设置页「扩展中心」下方的「语音识别」行。
 * 功能：按语言分组展示可下载的离线模型 → 下载（进度/双源镜像）→
 *       完成后自动加载进 VoskRecognizer（语音输入即走离线识别）。
 *
 * ## 视觉语言（v1.2.64 对齐扩展中心）
 * 初版是「白字 + 灰字 + 圆角卡 + 纯文字状态」的自创样式，与扩展中心割裂。
 * 现在完全复用扩展中心那一套：语言分组头（#7DD3FC / 13sp / BOLD）、
 * 行内 = 图标胶囊 + 名称/说明 + 状态点 + 胶囊按钮，进度条内联在行下方。
 * 两个页面同属「扩展能力」，视觉必须同源。
 */
class AsrModelActivity : Activity() {

    private lateinit var listHost: LinearLayout

    /** 每行的视图引用（下载/状态变化时更新） */
    private class RowRefs(
        val dot: View,
        val stateText: TextView,
        val action: TextView,
        val progress: ProgressBar,
    )

    private val rows = HashMap<String, RowRefs>()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_models)
        listHost = findViewById(R.id.asrList)
        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }
        buildList()
    }

    override fun onResume() {
        super.onResume()
        // 回到本页时刷新状态（下载可能已在后台完成）
        AsrModelCatalog.models.forEach { refreshRow(it.id) }
    }

    /** 构建模型列表（每次进入重建，状态最新） */
    private fun buildList() {
        listHost.removeAllViews()
        rows.clear()

        // 按语言分组（同一语言的模型归到一个组头下）
        var lastGroup: String? = null
        AsrModelCatalog.models.forEach { model ->
            val groupName = groupTitle(model.language)
            if (groupName != lastGroup) {
                lastGroup = groupName
                listHost.addView(sectionHeader(groupName))
            }
            listHost.addView(buildRow(model))
        }
    }

    private fun groupTitle(language: String): String = when (language) {
        "zh-CN" -> getString(R.string.asr_group_chinese)
        else -> getString(R.string.asr_group_english)
    }

    /** 分组标题：与扩展中心 sectionHeader 同款（强调色 / 13sp / 粗体） */
    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(0xFF7DD3FC.toInt())
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(8), dp(18), dp(8), dp(8))
    }

    /** 单行：图标胶囊 + 名称/说明 + 状态点 + 胶囊按钮（+ 内联进度条） */
    private fun buildRow(model: app.dsh.mobile.engine.AsrModel): View {
        val dot = View(this).apply {
            setBackgroundResource(R.drawable.bg_status_dot)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        }
        val stateText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8A94A3.toInt())
        }
        val action = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(64)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10) }
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF7DD3FC.toInt())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { topMargin = dp(6) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = getDrawable(R.drawable.bg_card)

            val main = LinearLayout(this@AsrModelActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                // 图标胶囊（与扩展中心一致的 40dp + bg_icon_chip_soft）
                addView(android.widget.ImageView(this@AsrModelActivity).apply {
                    setImageResource(R.drawable.ic_mic)
                    setColorFilter(0xFF7DD3FC.toInt())
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = getDrawable(R.drawable.bg_icon_chip_soft)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })

                addView(LinearLayout(this@AsrModelActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = dp(12); marginEnd = dp(6) }

                    addView(TextView(this@AsrModelActivity).apply {
                        text = localizedName(model)
                        textSize = 16f
                        setTextColor(0xFFFFFFFF.toInt())
                    })
                    addView(TextView(this@AsrModelActivity).apply {
                        text = localizedDesc(model)
                        textSize = 12f
                        setTextColor(0xFF9AA5B4.toInt())
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    })
                    addView(stateText.apply { setPadding(0, dp(3), 0, 0) })
                })

                addView(dot)
                addView(action)
            }
            addView(main, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(progress)
        }

        rows[model.id] = RowRefs(dot, stateText, action, progress)
        action.setOnClickListener { onAction(model) }
        row.setOnClickListener { onAction(model) }
        refreshRow(model.id)
        return row
    }

    private fun localizedName(model: app.dsh.mobile.engine.AsrModel): String =
        when (model.id) {
            "small-cn" -> getString(R.string.asr_name_cn)
            else -> getString(R.string.asr_name_en)
        }

    private fun localizedDesc(model: app.dsh.mobile.engine.AsrModel): String =
        when (model.language) {
            "zh-CN" -> getString(R.string.asr_cn_desc)
            else -> getString(R.string.asr_en_desc)
        }

    /** 行点击 / 按钮点击的统一入口 */
    private fun onAction(model: app.dsh.mobile.engine.AsrModel) {
        val refs = rows[model.id] ?: return
        if (refs.progress.visibility == View.VISIBLE) return   // 下载中：忽略
        if (AsrModelManager.isDownloaded(this, model)) {
            initModel(model.id)                                 // 已就绪 → 加载/重新加载
            return
        }
        download(model.id)
    }

    /** 单行刷新：按当前状态显示 未下载/已就绪 */
    /**
     * 单行刷新。
     *
     * 三态：
     *  · 未下载   → 红点 +「未下载」+ Download 按钮
     *  · 已下载且**正被加载** → 绿点 +「使用中」（无按钮，避免误导）
     *  · 已下载但未加载     → 绿点 +「已就绪」+ Reload 按钮（点了切到它）
     *
     * 旧版对「已下载但未加载」也显示「Ready」，与真正生效的那个无法区分 ——
     * 用户看不出到底哪个模型在起作用（两个模型时尤其迷惑）。
     */
    private fun refreshRow(id: String) {
        val model = AsrModelCatalog.models.firstOrNull { it.id == id } ?: return
        val refs = rows[id] ?: return
        if (refs.progress.visibility == View.VISIBLE) return     // 下载中不覆盖
        if (AsrModelManager.isDownloaded(this, model)) {
            val loaded = VoskRecognizer.loadedPath() ==
                AsrModelManager.modelPath(this, model)
            refs.dot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(COLOR_GREEN)
            if (loaded) {
                refs.stateText.text = getString(R.string.asr_state_in_use)
                refs.action.visibility = View.GONE
            } else {
                refs.stateText.text = getString(R.string.asr_state_ready)
                styleAction(refs.action, getString(R.string.asr_action_reload), COLOR_BLUE)
            }
        } else {
            refs.dot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(COLOR_RED)
            refs.stateText.text = getString(R.string.asr_state_none)
            refs.action.visibility = View.VISIBLE
            styleAction(refs.action, getString(R.string.asr_action_download), COLOR_BLUE)
        }
    }

    /** 胶囊按钮（与扩展中心 styleAction 同款） */
    private fun styleAction(btn: TextView, label: String, bgColor: Int) {
        btn.text = label
        btn.visibility = View.VISIBLE
        btn.isClickable = true
        btn.setBackgroundResource(R.drawable.bg_btn_accent)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
        btn.setTextColor(0xFF101418.toInt())
    }

    /** 开始下载（后台线程，进度回主线程） */
    private fun download(id: String) {
        val model = AsrModelCatalog.models.firstOrNull { it.id == id } ?: return
        val refs = rows[id] ?: return
        refs.progress.visibility = View.VISIBLE
        refs.progress.isIndeterminate = false
        refs.progress.progress = 0
        refs.action.visibility = View.GONE
        refs.stateText.text = getString(R.string.asr_state_downloading)
        refs.dot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(COLOR_YELLOW)

        AsrModelManager.download(
            this, model,
            onProgress = { p ->
                runOnUiThread {
                    refs.progress.progress = (p * 100).toInt()
                    refs.stateText.text = getString(
                        R.string.ext_downloading_pct, localizedName(model), (p * 100).toInt()
                    )
                }
            },
            onDone = {
                runOnUiThread {
                    refs.progress.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.asr_download_done), Toast.LENGTH_SHORT).show()
                    refreshRow(id)
                    initModel(id)
                }
            },
            onError = { msg ->
                runOnUiThread {
                    refs.progress.visibility = View.GONE
                    Toast.makeText(
                        this, getString(R.string.asr_download_failed, msg), Toast.LENGTH_LONG
                    ).show()
                    refreshRow(id)
                }
            },
        )
    }

    /**
     * 加载识别器（后台线程，~40MB 文件读入）。
     * 成功后 `VoskRecognizer.loadedPath()` 即指向它，语音输入自动走离线识别。
     */
    private fun initModel(id: String) {
        val model = AsrModelCatalog.models.firstOrNull { it.id == id } ?: return
        val path = AsrModelManager.modelPath(this, model) ?: return
        val refs = rows[id] ?: return
        if (VoskRecognizer.loadedPath() == path) {
            Toast.makeText(this, getString(R.string.asr_state_ready), Toast.LENGTH_SHORT).show()
            return
        }
        refs.stateText.text = getString(R.string.asr_state_downloading)
        refs.dot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(COLOR_YELLOW)

        val appCtx = applicationContext
        Thread({
            val ok = VoskRecognizer.init(path)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(
                        this, getString(R.string.asr_model_loaded), Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.asr_model_load_failed), Toast.LENGTH_LONG
                    ).show()
                }
                refreshRow(id)
            }
        }, "asr-init").apply { isDaemon = true; start() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "AsrModelActivity"
        val COLOR_RED = 0xFFFF5A5F.toInt()
        val COLOR_YELLOW = 0xFFFFB74D.toInt()
        val COLOR_GREEN = 0xFF81C995.toInt()
        val COLOR_BLUE = 0xFF7DD3FC.toInt()
    }
}

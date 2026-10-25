package app.dsh.mobile.pet

import android.app.Activity
import app.dsh.mobile.ThemedActivity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.DshApp
import app.dsh.mobile.LocaleHelper
import app.dsh.mobile.MaxWidthLinearLayout
import app.dsh.mobile.OrientationPrefs
import app.dsh.mobile.PluginStoreActivity
import app.dsh.mobile.R
import app.dsh.mobile.service.EngineService

/**
 * 桌宠独立设置页：权限、桥插件、动画包、大小、测试气泡、总开关。
 *
 * 所有状态都从文件/StateFlow 现读，不缓存“已安装”之类的内存标记。
 */
@Suppress("DEPRECATION")
class PetSettingsActivity : ThemedActivity() {

    private lateinit var content: LinearLayout
    private var enableSwitch: Switch? = null
    private var importing: ProgressDialog? = null
    private var updatingSwitch = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_pet)
        findViewById<MaxWidthLinearLayout>(R.id.petContentWidth).maxWidthDp = MAX_WIDTH_DP
        content = findViewById(R.id.petContent)
        findViewById<View>(R.id.btnPetBack).setOnClickListener { finish() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PACK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            render()
            return
        }
        startImport(uri)
    }

    // ------------------------------------------------------------ UI

    private fun render() {
        if (content.childCount > 0) content.removeAllViews()
        val status = PetStatusBus.state.value
        val running = PetOverlayService.isRunning()
        val plugin = PetPluginDetector.detect(this)
        val canDraw = Settings.canDrawOverlays(this)
        val engineRunning = EngineService.isRunning()

        enableSwitch = makeSwitchRow(
            title = getString(R.string.pet_enable),
            subtitle = getString(R.string.pet_enable_sub),
            checked = running,
        ) { checked ->
            if (!updatingSwitch) {
                if (checked) enablePet(canDraw, plugin) else disablePet()
            }
        }

        section(getString(R.string.pet_title))

        // 状态/错误
        status.lastError?.let { error ->
            card().also { c ->
                c.addView(rowText(error, getColor(R.color.warning)))
                content.addView(c)
            }
        }

        card().also { c ->
            c.addView(switchRowView(enableSwitch))
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_permission),
                    getString(R.string.pet_permission_sub),
                    getString(if (canDraw) R.string.pet_permission_granted else R.string.pet_permission_denied),
                    if (canDraw) getColor(R.color.success) else getColor(R.color.warning),
                ) {
                    openOverlaySettings()
                },
            )
            c.addView(divider())
            val pluginValue = when {
                !plugin.profileExists -> getString(R.string.pet_status_engine_down)
                // 装了但版本不达标（含上游 0.1.0）：先于「已就绪 / 请重启」判断，
                // 否则会误导用户去重启一个永远不通的旧版插件。
                plugin.bridgeInstalled && !plugin.bridgeVersionOk ->
                    getString(R.string.pet_status_plugin_outdated)
                plugin.bridgeReady -> getString(R.string.pet_plugin_installed)
                plugin.bridgeInstalled -> getString(R.string.pet_status_plugin_disabled)
                plugin.webPetInstalled -> getString(R.string.pet_plugin_web_only)
                else -> getString(R.string.pet_plugin_missing)
            }
            val pluginColor = if (plugin.bridgeReady) getColor(R.color.success) else getColor(R.color.warning)
            c.addView(
                row(
                    getString(R.string.pet_plugin),
                    getString(R.string.pet_plugin_hint),
                    pluginValue,
                    pluginColor,
                ) {
                    when {
                        plugin.bridgeInstalled && !plugin.bridgeVersionOk -> openPluginStore()
                        plugin.bridgeInstalled && !plugin.bridgeEnabled -> restartEngine()
                        else -> openPluginStore()
                    }
                },
            )
            content.addView(c)
        }

        if (plugin.bridgeInstalled) {
            card().also { c ->
                c.addView(
                    row(
                        getString(R.string.pet_plugin_restart),
                        getString(R.string.pet_plugin_restart_hint),
                        "",
                        getColor(R.color.text_secondary),
                    ) { restartEngine() },
                )
                content.addView(c)
            }
        }

        section(getString(R.string.pet_pack))
        card().also { c ->
            val count = countAnimations()
            val value = if (count > 0) getString(R.string.pet_pack_count, count)
            else getString(R.string.pet_pack_none)
            c.addView(
                row(
                    getString(R.string.pet_pack_import),
                    getString(R.string.pet_pack_keep_open),
                    value,
                    if (count > 0) getColor(R.color.success) else getColor(R.color.text_secondary),
                ) { pickPack() },
            )
            if (count > 0) {
                c.addView(divider())
                c.addView(
                    row(
                        getString(R.string.pet_pack_clear),
                        "",
                        "",
                        getColor(R.color.text_secondary),
                    ) { confirmClearPack() },
                )
            }
            content.addView(c)
        }

        section(getString(R.string.pet_size))
        card().also { c ->
            c.addView(
                row(
                    getString(R.string.pet_size),
                    getString(R.string.pet_size_hint),
                    getString(R.string.pet_size_value, PetPrefs.sizeDp(this)),
                    getColor(R.color.text_secondary),
                ) { chooseSize() },
            )
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_test_bubble),
                    getString(R.string.pet_test_bubble_text),
                    "",
                    getColor(R.color.text_secondary),
                ) {
                    if (running) {
                        PetOverlayService.showTestBubble(getString(R.string.pet_test_bubble_text))
                    } else {
                        toast(getString(R.string.pet_status_stopped))
                    }
                },
            )
            content.addView(c)
        }

        section(getString(R.string.pet_sound_section))
        card().also { c ->
            c.addView(switchRowFor(
                title = getString(R.string.pet_sound),
                subtitle = getString(R.string.pet_sound_sub),
                checked = PetPrefs.soundEnabled(this),
            ) { checked ->
                PetPrefs.setSoundEnabled(this, checked)
            })
            c.addView(divider())
            c.addView(
                row(
                    getString(R.string.pet_lines),
                    getString(R.string.pet_lines_sub),
                    getString(R.string.pet_lines_count, PetLines.totalCount(this)),
                    0xFF8A94A3.toInt(),
                ) { startActivity(Intent(this, PetLinesActivity::class.java)) },
            )
            content.addView(c)
        }

        if (!engineRunning) {
            content.addView(rowText(getString(R.string.pet_status_engine_down), getColor(R.color.text_secondary)))
        }

        // 状态副标题（运行中/已连接/等待）
        if (running) {
            val st = when {
                status.connected -> getString(R.string.pet_status_connected)
                status.listening -> getString(R.string.pet_status_waiting)
                else -> getString(R.string.pet_status_running)
            }
            content.addView(rowText(st, getColor(R.color.success)))
        }
    }

    private fun enablePet(canDraw: Boolean, plugin: PetPluginDetector.Snapshot) {
        if (!canDraw) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pet_permission)
                .setMessage(R.string.pet_permission_rationale)
                .setPositiveButton(R.string.pet_permission) { _, _ -> openOverlaySettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            render()
            return
        }
        val doStart = {
            runCatching {
                PetPrefs.setEnabled(this, true)
                PetOverlayService.start(this)
            }.onFailure {
                PetPrefs.setEnabled(this, false)
                toast(it.message ?: getString(R.string.pet_error_no_permission))
            }
            content.postDelayed({ render() }, 400L)
        }
        val outdated = plugin.bridgeInstalled && !plugin.bridgeVersionOk
        if (outdated) {
            // 硬门禁：< 0.3.1 的桥插件不带 token，协议 v2 下必然 403，
            // 开了也是「连接着但永远没气泡」，所以这里不给「仍然开启」的旁路。
            AlertDialog.Builder(this)
                .setTitle(R.string.pet_plugin)
                .setMessage(R.string.pet_plugin_outdated_msg)
                .setPositiveButton(R.string.pet_plugin_upgrade) { _, _ ->
                    render()
                    openPluginStore()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> render() }
                .setOnCancelListener { render() }
                .show()
            render()
            return
        }
        if (!plugin.bridgeReady) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pet_plugin)
                .setMessage(R.string.pet_enable_without_plugin_msg)
                .setPositiveButton(R.string.pet_enable_without_plugin) { _, _ -> doStart() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> render() }
                .setOnCancelListener { render() }
                .show()
        } else {
            doStart()
        }
    }

    private fun disablePet() {
        PetPrefs.setEnabled(this, false)
        PetOverlayService.stop(this)
        content.postDelayed({ render() }, 250L)
    }

    private fun openOverlaySettings() {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(i) }.onFailure {
            toast(it.message ?: getString(R.string.pet_error_no_permission))
        }
    }

    private fun openPluginStore() {
        startActivity(
            Intent(this, PluginStoreActivity::class.java)
                // 搜索用短词：npm 搜索不认 scoped 全名，靠短词 + 目录项合并命中官方包。
                .putExtra(PluginStoreActivity.EXTRA_QUERY, PetPluginDetector.BRIDGE_SEARCH_QUERY),
        )
    }

    private fun restartEngine() {
        runCatching { (application as DshApp).supervisor.restart() }
        toast(getString(R.string.pet_plugin_restart_hint))
        content.postDelayed({ render() }, 600L)
    }

    private fun pickPack() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        runCatching { startActivityForResult(i, REQ_PACK) }.onFailure {
            toast(it.message ?: getString(R.string.pet_pack_failed, "?"))
        }
    }

    private fun startImport(uri: Uri) {
        val d = ProgressDialog(this).apply {
            setTitle(R.string.pet_pack)
            setMessage(getString(R.string.pet_pack_keep_open))
            isIndeterminate = false
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }
        importing = d
        PetPackInstaller.install(
            this,
            uri,
            onProgress = { p -> runOnUiThread { runCatching { d.progress = p.coerceIn(0, 100) } } },
            onDone = { result ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    runCatching { d.dismiss() }
                    importing = null
                    if (result.ok) {
                        PetPrefs.setPackLabel(this, getString(R.string.pet_pack_count, result.animationCount))
                        PetOverlayService.reload(this)
                        toast(getString(R.string.pet_pack_imported, result.animationCount))
                    } else {
                        toast(getString(R.string.pet_pack_failed, result.message ?: "?"))
                    }
                    render()
                }
            },
        )
    }

    private fun confirmClearPack() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_pack_clear)
            .setMessage(R.string.pet_pack_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PetPrefs.clearPack(this)
                PetPrefs.setPackLabel(this, null)
                PetOverlayService.reload(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseSize() {
        val values = PetPrefs.SIZE_LEVELS
        val labels = values.map { getString(R.string.pet_size_value, it) }.toTypedArray()
        val current = values.indexOfFirst { it == PetPrefs.sizeDp(this) }
        AlertDialog.Builder(this)
            .setTitle(R.string.pet_size)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                PetPrefs.setSizeDp(this, values[which])
                dlg.dismiss()
                PetOverlayService.reload(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun countAnimations(): Int {
        val dir = PetPrefs.packWebpDir(this)
        if (!dir.isDirectory) return 0
        return dir.listFiles()?.count { it.isFile && it.name.endsWith(".webp", true) } ?: 0
    }

    // ------------------------------------------------------------ 视图小工具

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(18), dp(8), dp(8))
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1).coerceAtLeast(1),
        ).apply { marginStart = dp(52) }
        setBackgroundColor(0x22FFFFFF)
    }

    private fun switchRowView(sw: Switch?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.pet_enable)
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.pet_enable_sub)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        if (sw != null) row.addView(sw)
        return row
    }

    private fun makeSwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): Switch {
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value ->
                if (!updatingSwitch) onChange(value)
            }
        }
        return sw
    }

    /** 卡片内直接可用的开关行（标题/副标题/开关状态/回调）。 */
    private fun switchRowFor(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val sw = makeSwitchRow(title, subtitle, checked, onChange)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
        })
        if (subtitle.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = subtitle
                setTextColor(0xFF8A94A3.toInt())
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(col)
        row.addView(sw)
        return row
    }

    private fun row(
        title: String,
        subtitle: String,
        value: String,
        valueColor: Int,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
        })
        if (subtitle.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = subtitle
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(col)
        if (value.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = value
                setTextColor(valueColor)
                textSize = 13f
                setPadding(dp(10), 0, 0, 0)
            })
        }
        return row
    }

    private fun rowText(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 12f
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PACK = 4301
        private const val MAX_WIDTH_DP = 680
    }
}

package app.dsh.mobile

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.ExtensionManager
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.engine.RuntimeDownloadConfig
import app.dsh.mobile.engine.LanWebProxy
import app.dsh.mobile.update.AppUpdateBus
import app.dsh.mobile.update.Phase

/**
 * 独立设置页（MIUI 分组卡片风格，替代原先的悬浮菜单）。
 *
 * 三个分组：
 *  - 显示：屏幕朝向（竖屏/横屏/自动，v1.2.120）、页面缩放
 *  - 权限中心（统一管理运行权限与无障碍）：运行权限模式、Root 能力状态、Shizuku 状态、屏幕点击（无障碍）
 *  - 其他：重启引擎、关于
 *
 * 权限切换与缩放均复用 MainActivity 的既有决策（Root 双警告、缩放 −/＋ 步进、
 * 无障碍跳系统设置），落库到同一组 SharedPreferences，MainActivity 在 onResume 重新读取生效。
 */
class SettingsActivity : ThemedActivity() {

    /** 上次应用的主题/强调色/布局快照：子页改动后回到本页检测重建（v1.4.56） */
    private var appliedTheme: ThemeMode = ThemeMode.DARK
    private var appliedAccent: Int = 0
    private var appliedLayout: SettingsLayoutMode = SettingsLayoutMode.LIST

    /** 当前打开的权限选择对话框（选完/切换中转时关闭） */
    private var privPickDialog: AlertDialog? = null

    /** Shizuku 授权结果监听（requestPermission 异步回调后刷新状态行） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            // v1.2.158：记住用户的选择——拒绝后不再自动弹窗（隐私）
            if (requestCode == SHIZUKU_REQ) {
                Privilege.markShizukuDenied(this, grantResult != android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
            refreshShizuku()
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.4.18：不再硬锁竖屏 —— 跟随应用的三态偏好（平板无偏好时默认 auto）
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_settings)
        applyResponsiveLayout()


        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // —— 扩展：局域网访问 WebUI（v1.2.160/v1.2.161）——
        // 独立端口 3090 的反向代理，让同网段设备用原生 WebUI 连上本机引擎。
        // v1.2.161（用户要求）：验证码改为 **6 位数字 + 每次开启重新随机**，
        // 生成后**自动复制到剪贴板**，且**只在应用内显示一次**（退出再进设置不显示）。
        val swLan = findViewById<android.widget.Switch>(R.id.swLanWeb)
        val lanSub = findViewById<android.widget.TextView>(R.id.lanWebSub)
        // ⚠️ v1.2.162 实测修复（用户反馈"关闭时显示网址、开启时反而不显示"）：
        // 旧实现用 LanWebProxy.isRunning() 判断 —— 但代理的启停都是**异步**的
        // （拉起要 1-3s，停止要等进程回收）→ 点开关的瞬间读到的还是旧状态，
        // 表现正好相反。改为**以开关自身的状态为准**（用户看到的就是他点的那个状态），
        // 地址只在该状态为"开"时展示。
        // v1.2.162（用户要求）：**行内不再保留验证码** —— 验证码只在开启时的
        // 提示框里显示一次，避免长期驻留。行内只显示说明与访问地址。
        fun lanSubText() {
            val on = swLan.isChecked
            val addr = "http://" + (LanWebProxy.lanAddress() ?: "?") + ":" + LanWebProxy.PORT
            lanSub.text = if (!on) getString(R.string.setting_lan_web_sub)
            else getString(R.string.setting_lan_web_sub) + "\n\n" + addr
        }
        /** 复制到剪贴板（6 位码，方便直接粘到电脑上） */
        fun copyCode(code: String) {
            runCatching {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH LAN code", code))
            }
        }
        swLan.isChecked = DshApp.lanWebEnabled(this)
        // v1.2.162：验证码只在**刚生成时**用提示框展示一次（行内不再保留）。
        val pendingShown = DshApp.lanWebPwPending(this)
        if (pendingShown && DshApp.lanWebEnabled(this)) {
            val c = DshApp.lanWebPassword(this)
            runCatching {
                LanAccessDialog.show(
                    this, c,
                    "http://" + (LanWebProxy.lanAddress() ?: "?") + ":" + LanWebProxy.PORT,
                )
            }
            DshApp.markLanWebPwShown(this)   // 只显示一次
        }
        lanSubText()
        swLan.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.setting_lan_web)
                    .setMessage(R.string.lan_web_consent)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DshApp.setLanWebEnabled(this, true)
                        // 每次开启都重新生成 6 位验证码
                        val code = DshApp.regenerateLanWebPassword(this)
                        // v1.2.162（用户要求）：改用「提示框」展示 —— 验证码显著、可点复制；
                        // 访问地址紧贴其下（小字、也可复制）；行内不再保留验证码。
                        runCatching {
                            LanAccessDialog.show(
                                this, code,
                                "http://" + (LanWebProxy.lanAddress() ?: "?") + ":" + LanWebProxy.PORT,
                            )
                        }
                        lanSubText()
                        DshApp.markLanWebPwShown(this)
                        Thread({
                            runCatching { LanWebProxy.start(code) }
                                .onFailure { android.util.Log.w("Settings", "lan proxy: ${it.message}") }
                        }, "lan-start").apply { isDaemon = true }.start()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> swLan.isChecked = false }
                    .setOnCancelListener { swLan.isChecked = false }
                    .show()
            } else {
                DshApp.setLanWebEnabled(this, false)
                Thread({ runCatching { LanWebProxy.stop() } }, "lan-stop").apply { isDaemon = true }.start()
                lanSubText()
            }
        }

        // —— 扩展：运行时下载代理（v1.4.21）——
        // 运行时 zip / 签名索引都从 GitHub Releases 取；国内直连经常超时。
        // 默认 gh-proxy.org，可换自建前缀，留空则直连。
        refreshRuntimeProxyRow()
        findViewById<LinearLayout>(R.id.rowRuntimeProxy).setOnClickListener {
            showRuntimeProxyDialog()
        }


        // —— 顶部返回 ——
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 个性化入口（v1.4.56）：外观/扩展整组收进子页，主页只留入口 ——
        val savedLayout = SettingsLayoutMode.current(this)
        appliedTheme = ThemePref.get(this)
        appliedAccent = Accent.current(this).argb
        appliedLayout = savedLayout
        findViewById<LinearLayout>(R.id.rowAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowExtensionVoice).setOnClickListener {
            startActivity(Intent(this, ExtensionVoiceActivity::class.java))
        }
        if (savedLayout != SettingsLayoutMode.LIST) {
            // 布局参数改动需要在视图完成布局后进行
            findViewById<View>(R.id.columnPrimary)?.post { applyLayoutMode(savedLayout) }
        }

        // —— 权限中心：运行权限模式 ——
        findViewById<LinearLayout>(R.id.rowPriv).setOnClickListener {
            showPrivDialog()
        }

        // —— 权限中心：屏幕点击（无障碍） ——
        findViewById<LinearLayout>(R.id.rowAccess).setOnClickListener {
            handleAccessibility()
        }

        // —— 权限中心：所有文件访问（AI 在共享存储建/读写文件） ——
        findViewById<LinearLayout>(R.id.rowStorage).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= 30)
                Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            else Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(intent) }
                .onFailure {
                    Toast.makeText(this, getString(R.string.setting_access_open_failed), Toast.LENGTH_SHORT).show()
                }
        }
        refreshStorageRow()

        // —— 权限中心：通知权限（Android 13+ 运行时授权，Agent 推送需要） ——

        findViewById<LinearLayout>(R.id.rowNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
        refreshNotifRow()

        // —— 权限中心：麦克风（语音输入需要 RECORD_AUDIO） ——
        // 已授权时点击提示无需重复授权；未授权时弹系统授权框（本 Activity 有权请求）。
        findViewById<LinearLayout>(R.id.rowMic).setOnClickListener {
            if (hasMicPermission()) {
                Toast.makeText(this, getString(R.string.mic_granted), Toast.LENGTH_SHORT).show()
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }
        refreshMicRow()


        // 自定义指令（v1.3.2，issue #19）：在 App 的环境说明之上补写用户自己的长期指令。
        // 落点是 AGENTS.md 的用户段 —— 引擎只加载这一个 user-global 指令文件，
        // 分段管理后升级 App 不会丢用户内容，用户改指令也不会让环境说明变旧。
        findViewById<LinearLayout>(R.id.rowUserPrompt).setOnClickListener {
            startActivity(Intent(this, UserPromptActivity::class.java))
        }

        // —— 数据：备份与恢复子页（v1.4.46：导出/导入/恢复对话/配置文件收拢）——
        findViewById<LinearLayout>(R.id.rowBackup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        // —— 系统：升级子页（v1.4.58：应用更新+运行时+偏好统一入口，文案改「升级」）——
        // 副标题 = 当前版本 +（bus 已有可用更新时追加提示）。只读 AppUpdateBus 现成
        // 状态，**不额外发起网络检查**——检查节奏仍由调度器管（冷启动/前台>24h）。
        // onResume 里刷新：从升级子页回来（可能已忽略/已下载）副标题能跟上。
        refreshUpdateEntrySub()
        findViewById<LinearLayout>(R.id.rowUpdate).setOnClickListener {
            startActivity(Intent(this, UpdateSettingsActivity::class.java))
        }

        // —— 维护与高级（v1.4.45）：诊断与高风险操作收进子页，主页只留入口 ——
        findViewById<LinearLayout>(R.id.rowMaintenance).setOnClickListener {
            startActivity(Intent(this, MaintenanceActivity::class.java))
        }

        // —— 其他：语言 Language（v1.2.120：en/de/zh 三选一，默认 en）——
        val langCodes = listOf("en", "de", "zh")
        fun langLabel(code: String): String = when (code) {
            "zh" -> getString(R.string.lang_zh)
            "de" -> getString(R.string.lang_de)
            else -> getString(R.string.lang_en)
        }
        val langSub = findViewById<TextView>(R.id.appLanguageSub)
        fun refreshLangSub() {
            langSub.text = getString(
                R.string.app_language_sub_current,
                langLabel(LocaleHelper.get(this)),
            )
        }
        refreshLangSub()
        findViewById<LinearLayout>(R.id.rowLanguage).setOnClickListener {
            val choices = arrayOf(
                getString(R.string.lang_en),
                getString(R.string.lang_de),
                getString(R.string.lang_zh),
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_language))
                .setSingleChoiceItems(choices, langCodes.indexOf(LocaleHelper.get(this))) { dlg, which ->
                    val code = langCodes[which]
                    if (code == LocaleHelper.get(this)) { dlg.dismiss(); return@setSingleChoiceItems }
                    LocaleHelper.set(this, code)
                    dlg.dismiss()
                    // 立即生效：本页原地重建（recreate 会重走 attachBaseContext →
                    // 按新语言重新 inflate 布局），用户马上看到文案变化。
                    //
                    // 旧实现是「清任务栈 + 重新拉起 Launcher」——那是跨进程重建，
                    // 用户实测「切换后 UI 不能及时响应」（要等整个应用重开）。
                    // 现在只重建当前页；MainActivity 在 onResume 会检测到语言变化
                    // 并自行重建，返回主界面时也是新语言。
                    recreate()
                }
                .showStyled()
        }

        // —— 其他：关于 ——
        findViewById<TextView>(R.id.subAbout).text =
            getString(R.string.setting_about_sub, versionName())
        findViewById<LinearLayout>(R.id.rowAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    /** 从包管理器读取 versionName（AGP 8+ 默认关闭 BuildConfig，避免依赖它） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

    /**
     * 升级入口副标题：当前版本 +（bus 已有可用更新时追加「有新版本 x.x.x」）。
     * 只读内存态不发起网络；onCreate 与 onResume 各刷一次，从升级子页回来也能跟上。
     */
    private fun refreshUpdateEntrySub() {
        val sub = findViewById<TextView>(R.id.subUpdateEntry) ?: return
        val base = getString(R.string.setting_update_entry_sub, versionName())
        val state = AppUpdateBus.state.value
        val info = state.info
        val hasNew = info != null && state.phase in
            setOf(Phase.AVAILABLE, Phase.DOWNLOADING, Phase.DOWNLOADED, Phase.NEEDS_CHOICE)
        sub.text = if (hasNew && info != null) {
            base + " · " + getString(R.string.setting_update_entry_has_new, info.versionName)
        } else {
            base
        }
    }

    /**
     * 转屏不重建（Manifest 已声明 configChanges）：只切换宽/窄布局，
     * 保留滚动位置、已打开的对话框和进行中的数据导出/扩展安装任务。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout()
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateEntrySub()
        // 子页里改过主题/强调色/布局：回到本页立即重建，保证视觉与偏好一致（v1.4.56）
        if (appliedTheme != ThemePref.get(this) ||
            appliedAccent != Accent.current(this).argb ||
            appliedLayout != SettingsLayoutMode.current(this)
        ) {
            recreate()
            return
        }
        // 从系统无障碍设置返回后刷新状态；Shizuku 授权结果回调也会刷新
        refreshAll()
    }

    /** 个性化入口副标题：当前主题 · 强调色（v1.4.56） */
    private fun refreshAppearanceEntry() {
        findViewById<TextView>(R.id.subAppearanceEntry)?.text = getString(
            R.string.setting_appearance_sub,
            getString(ThemePref.get(this).labelRes),
            getString(Accent.current(this).labelRes),
        )
    }

    /** 扩展与语音入口副标题：已激活扩展数（v1.4.56） */
    private fun refreshExtensionEntry() {
        val count = runCatching { ExtensionManager(this).activeCount() }.getOrDefault(0)
        findViewById<TextView>(R.id.subExtensionEntry)?.text =
            getString(R.string.setting_extension_entry_sub, count)
    }

    /**
     * 平板/横屏响应式布局（v1.4.18，v1.4.27 改为按高度均衡分列）：
     * - 宽窗（>= 840dp）：两栏，**按分区行数把 8 个分区就近对半分**；
     * - 窄窗：单列，顺序不变（显示 → 权限中心 → 语音 → 扩展 → 数据 → 网络 → 维护 → 关于）。
     *
     * ## 为什么不再写死「左显示+权限 / 右其余」
     * 旧实现把列归属硬编码在 XML 里（columnPrimary 放前两个分区，其余全塞
     * columnSecondary），行数一多就歪：v1.4.24 扩到 8 个分区后是左 12 行 / 右 18 行，
     * 右边要滚很久、左边一屏未完就空了。而且每加一行设置项都会继续加剧。
     *
     * 现在：把 XML 里的原始阅读顺序读出来（先 primary 再 secondary），按行数找
     * 一个**连续切分点**让两列高度最接近（保持"从上往下读"的语义，不做瀑布流乱序），
     * 再重新挂到两列上。以后加行/加分区自动重新均衡，不用再手工调 XML。
     *
     * 只改容器方向/权重与子视图归属，不重建页面。限宽交给
     * [MaxWidthLinearLayout]，避免平板横屏下单列内容被拉成超宽行。
     */
    private fun applyResponsiveLayout() {
        val wide = resources.configuration.screenWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP
        val columns = findViewById<LinearLayout>(R.id.settingsColumns)
        val primary = findViewById<LinearLayout>(R.id.columnPrimary)
        val secondary = findViewById<LinearLayout>(R.id.columnSecondary)
        findViewById<MaxWidthLinearLayout>(R.id.settingsContentWidth).maxWidthDp =
            if (wide) WIDE_CONTENT_MAX_WIDTH_DP else NARROW_CONTENT_MAX_WIDTH_DP

        val sections = sections()
        if (wide) {
            columns.orientation = LinearLayout.HORIZONTAL
            primary.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginEnd = dp(16) }
            secondary.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
            distributeSections(sections, primary, secondary)
        } else {
            columns.orientation = LinearLayout.VERTICAL
            primary.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            secondary.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            // 单列时必须把分区全部收回 primary，否则右列内容会跑到屏幕外
            detachSections(sections)
            sections.forEach { addSection(primary, it) }
        }
    }

    /**
     * 一个「分区单元」：标题 + 卡片 + 行数权重。
     *
     * 权重用**卡片内的行数**而不是实测像素：两列等宽、行高一致，行数是最稳的
     * 代理；实测像素要求先布局，而本方法在 onCreate 阶段就会被调用。
     */
    private class Section(val header: TextView?, val card: LinearLayout, val weight: Int)

    /** XML 原始阅读顺序（先 columnPrimary 再 columnSecondary），只在首次调用时收集 */
    private var cachedSections: List<Section>? = null

    private fun sections(): List<Section> = cachedSections ?: run {
        val out = ArrayList<Section>()
        out += collectSections(findViewById(R.id.columnPrimary))
        out += collectSections(findViewById(R.id.columnSecondary))
        out.also { cachedSections = it }
    }

    /** 形如「标题 TextView + 卡片 LinearLayout」交替出现；别的节点忽略 */
    private fun collectSections(column: LinearLayout): List<Section> {
        val out = ArrayList<Section>()
        var header: TextView? = null
        for (i in 0 until column.childCount) {
            when (val child = column.getChildAt(i)) {
                is TextView -> header = child
                is LinearLayout -> {
                    val rows = (0 until child.childCount).count {
                        child.getChildAt(it) is LinearLayout
                    }
                    out += Section(header, child, rows.coerceAtLeast(1))
                    header = null
                }
            }
        }
        return out
    }

    /**
     * 连续切分：找一个 k，让前 k 个分区与其余分区的行数差最小。
     * 保持阅读顺序（不做「往短的那列随手塞」的瀑布流），分列结果可预测。
     */
    private fun distributeSections(
        sections: List<Section>,
        primary: LinearLayout,
        secondary: LinearLayout,
    ) {
        if (sections.isEmpty()) return
        val total = sections.sumOf { it.weight }
        var best = 1
        var bestDiff = Int.MAX_VALUE
        var acc = 0
        for (k in 1 until sections.size) {
            acc += sections[k - 1].weight
            val diff = kotlin.math.abs(total - 2 * acc)
            if (diff < bestDiff) {
                bestDiff = diff
                best = k
            }
        }
        detachSections(sections)
        sections.forEachIndexed { index, section ->
            addSection(if (index < best) primary else secondary, section)
        }
    }

    private fun detachSections(sections: List<Section>) {
        sections.forEach { section ->
            (section.header?.parent as? ViewGroup)?.removeView(section.header)
            (section.card.parent as? ViewGroup)?.removeView(section.card)
        }
    }

    private fun addSection(column: LinearLayout, section: Section) {
        section.header?.let { column.addView(it) }
        column.addView(section.card)
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    /** 全量刷新：朝向值 + 缩放值 + 权限状态行 + 无障碍 */
    private fun refreshAll() {
        findViewById<TextView>(R.id.valPriv).text = privLabel(Privilege.getMode(this))
        findViewById<TextView>(R.id.valRootStatus).let {
            val rootOk = Privilege.rootAvailableMinimal()
            it.text = getString(
                if (rootOk) R.string.setting_root_status_yes else R.string.setting_root_status_no
            )
            it.asStatusChip(
                if (rootOk) R.color.success else R.color.text_secondary,
                if (rootOk) R.color.success_soft else R.color.surface_alt,
            )
        }
        refreshShizuku()
        refreshAccess()
        refreshStorageRow()
        refreshNotifRow()
        refreshMicRow()
        refreshAppearanceEntry()
        refreshExtensionEntry()
        // 缩放副标题文案无需变；图标着色按打开时状态由静态 XML 决定
    }

    /** 权限中心：所有文件访问状态（MANAGE_EXTERNAL_STORAGE，Android 11+ 系统设置页授予；
     *  引擎进程与 App 同 uid，授权后引擎即可读写共享存储） */
    private fun refreshStorageRow() {
        val granted = if (Build.VERSION.SDK_INT >= 30)
            android.os.Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val v = findViewById<TextView>(R.id.valStorage)
        v.text = getString(if (granted) R.string.storage_granted else R.string.storage_denied)
        v.asStatusChip(
            if (granted) R.color.success else R.color.warning,
            if (granted) R.color.success_soft else R.color.warning_soft,
        )
    }

    /** 权限中心：通知权限（Android 13+ 运行时授权；低版本默认持有） */

    private fun refreshNotifRow() {
        val granted = if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        val v = findViewById<TextView>(R.id.valNotif)
        v.text = getString(if (granted) R.string.notif_granted else R.string.notif_denied)
        v.asStatusChip(
            if (granted) R.color.success else R.color.text_secondary,
            if (granted) R.color.success_soft else R.color.surface_alt,
        )
    }

    /** 麦克风权限是否已授予（语音输入的前提） */
    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 麦克风权限状态行：已授权（绿）/ 未授权（灰，点击授权） */
    private fun refreshMicRow() {
        val granted = hasMicPermission()
        val v = findViewById<TextView>(R.id.valMic)
        v.text = getString(if (granted) R.string.mic_granted else R.string.mic_denied)
        v.asStatusChip(
            if (granted) R.color.success else R.color.text_secondary,
            if (granted) R.color.success_soft else R.color.surface_alt,
        )
    }

    /** 权限回调：麦克风授权结果 → 刷新状态行 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == REQ_MIC) {
            refreshMicRow()
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                getString(if (ok) R.string.mic_granted else R.string.mic_denied),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /** Shizuku 三态刷新（已授权绿 / 等待授权黄 / 未运行灰） */
    private fun refreshShizuku() {
        val v = findViewById<TextView>(R.id.valShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.setting_shizuku_granted)
                v.asStatusChip(R.color.success, R.color.success_soft)
            }
            Privilege.shizukuServerRunning() -> {
                // v1.2.158 隐私修复：这里**不再**自动请求（拒绝后回调又会回到此处 →
                // 死循环弹窗）。改为展示状态 + 允许点击该行**显式**重新申请。
                v.text = getString(R.string.setting_shizuku_request)
                v.asStatusChip(R.color.warning, R.color.warning_soft)
                v.isClickable = true
                v.setOnClickListener {
                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this@SettingsActivity, explicit = true)
                }
            }
            else -> {
                v.text = getString(R.string.setting_shizuku_absent)
                v.asStatusChip(R.color.text_secondary, R.color.surface_alt)
            }
        }
    }

    /** 无障碍状态刷新 */
    private fun refreshAccess() {
        val on = DshAccessibilityService.isEnabled()
        findViewById<TextView>(R.id.subAccess).text = getString(
            if (on) R.string.setting_access_sub_on else R.string.setting_access_sub_off
        )
        val v = findViewById<TextView>(R.id.valAccess)
        v.text = getString(if (on) R.string.state_on else R.string.state_off)
        v.asStatusChip(
            if (on) R.color.success else R.color.text_secondary,
            if (on) R.color.success_soft else R.color.surface_alt,
        )
        // v1.4.58：独立「去开启」按钮已删——整行即入口（点击行为与按钮本就一致），
        // 未授权的行动指引由 subAccess 文案承担；chevron 恒显作为可点提示。
    }

    private fun privLabel(mode: PrivMode): String = when (mode) {
        PrivMode.ROOT -> getString(R.string.priv_root)
        PrivMode.SHIZUKU -> getString(R.string.priv_shizuku)
        PrivMode.NORMAL -> getString(R.string.priv_normal)
    }

    // ================= 扩展：运行时下载代理 =================

    /** 刷新「运行时下载代理」行的说明文字（默认 / 自定义 / 直连）。 */
    private fun refreshRuntimeProxyRow() {
        val sub = findViewById<TextView>(R.id.subRuntimeProxy)
        val current = RuntimeDownloadConfig.proxy(this)
        sub.text = when {
            current.isBlank() -> getString(R.string.setting_runtime_proxy_value_direct)
            RuntimeDownloadConfig.isDefault(this) ||
                current.equals(RuntimeDownloadConfig.DEFAULT_PROXY.trimEnd('/'), ignoreCase = true) ->
                getString(R.string.setting_runtime_proxy_value_default)
            else -> getString(R.string.setting_runtime_proxy_value_custom, current)
        }
    }

    /**
     * 运行时下载代理编辑框（v1.4.21）。
     *
     * 运行时 zip 与签名索引都从 GitHub Releases 取；默认 gh-proxy.org，
     * 国内网络不用先等一轮直连超时。用户可换自建镜像，或留空直连。
     */
    private fun showRuntimeProxyDialog() {
        val current = RuntimeDownloadConfig.proxy(this)
        val input = EditText(this).apply {
            setText(current)
            hint = RuntimeDownloadConfig.DEFAULT_PROXY
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setSelection(text.length)
        }
        val tip = TextView(this).apply {
            text = getString(
                R.string.setting_runtime_proxy_dialog_hint,
                RuntimeDownloadConfig.DEFAULT_PROXY,
            )
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
            addView(input)
            addView(tip)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.setting_runtime_proxy)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.setting_runtime_proxy_reset, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString()
                if (!RuntimeDownloadConfig.isValid(raw)) {
                    input.error = getString(R.string.setting_runtime_proxy_invalid)
                    return@setOnClickListener
                }
                RuntimeDownloadConfig.setProxy(this, raw)
                refreshRuntimeProxyRow()
                Toast.makeText(
                    this, getString(R.string.setting_runtime_proxy_saved), Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                RuntimeDownloadConfig.setProxy(this, null)
                refreshRuntimeProxyRow()
                Toast.makeText(
                    this, getString(R.string.setting_runtime_proxy_reset_done), Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
    /** dp → px 小工具（对话框内边距用） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ================= 权限中心：运行权限模式 =================

    /**
     * 运行权限模式选择：自定义单选列表（MIUI 行式）。
     * 能力未就绪的选项直接置灰（alpha 0.35）且不可点击：
     *  - Shizuku：server 未运行 → 置灰（无从授权）
     *  - Root：未检测到 su → 置灰
     * Root 仍保留双警告；切换后自动重启引擎。
     */
    private fun showPrivDialog() {
        val current = Privilege.getMode(this)
        val shizukuOk = Privilege.shizukuServerRunning()
        val rootOk = Privilege.rootAvailableMinimal()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        fun makeRow(
            label: String, sub: String, mode: PrivMode,
            enabled: Boolean, checked: Boolean,
        ): LinearLayout {
            val radio = android.widget.RadioButton(this).apply {
                isChecked = checked
                isEnabled = enabled
                isClickable = false   // 由整行接管点击
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(getColor(R.color.text_primary))
                })
                if (sub.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                })
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                if (enabled) {
                    // 解析主题的 ripple 背景为真实 resId 再取 drawable（attr 不能直接 getDrawable）
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    background = getDrawable(tv.resourceId)
                }
                addView(radio)
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                })
                alpha = if (enabled) 1f else 0.35f
                isEnabled = enabled
                if (enabled) {
                    setOnClickListener {
                        // 互斥：重画全部 radio
                        for (i in 0 until box.childCount) {
                            val row = box.getChildAt(i) as LinearLayout
                            (row.getChildAt(0) as android.widget.RadioButton).isChecked = row === this
                        }
                        when (mode) {
                            PrivMode.ROOT -> warnRootSwitch {
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.ROOT)
                            }
                            PrivMode.SHIZUKU -> {
                                // server 在跑但未授权 → 先请求授权（弹 Shizuku 框）
                                if (!Privilege.shizukuGranted()) {
                                    // 用户主动选了 Shizuku 模式 = 显式请求（会重置"已拒绝"标记）
                                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this@SettingsActivity, explicit = true)
                                }
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.SHIZUKU)
                            }
                            PrivMode.NORMAL -> {
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.NORMAL)
                            }
                        }
                    }
                }
            }
        }

        box.addView(makeRow(
            getString(R.string.priv_normal), "", PrivMode.NORMAL, true, current == PrivMode.NORMAL))
        box.addView(makeRow(
            getString(R.string.priv_shizuku),
            if (shizukuOk) "" else getString(R.string.setting_shizuku_absent),
            PrivMode.SHIZUKU, shizukuOk, current == PrivMode.SHIZUKU))
        box.addView(makeRow(
            getString(R.string.priv_root),
            if (rootOk) "" else getString(R.string.setting_root_status_no),
            PrivMode.ROOT, rootOk, current == PrivMode.ROOT))

        privPickDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.priv_pick_title))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        privPickDialog?.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        privPickDialog?.show()
    }

    /** 关闭权限选择对话框（选完/警告链中转用；null 安全） */
    private fun dismissPrivPick() {
        privPickDialog?.dismiss()
        privPickDialog = null
    }

    /** 切换运行权限模式并自动重启引擎（模式未变则不重启） */
    private fun applyModeAndRestart(target: PrivMode) {
        if (Privilege.getMode(this) == target) return
        Privilege.setMode(this, target)
        findViewById<TextView>(R.id.valPriv).text = privLabel(target)
        (application as DshApp).supervisor.restart()
        Toast.makeText(this, getString(R.string.setting_priv_mode), Toast.LENGTH_SHORT).show()
    }

    private fun warnRootSwitch(next: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn_title))
            .setMessage(getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ob_priv_root_warn2_title))
                    .setMessage(getString(R.string.ob_priv_root_warn2))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
                    .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
                    .showStyled()
            }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
            .showStyled()
    }

    // ================= 权限中心：无障碍 =================

    /** 无障碍：未开启则跳系统设置引导开启；已开启则提示已可用 */
    private fun handleAccessibility() {
        if (DshAccessibilityService.isEnabled()) {
            Toast.makeText(
                this, getString(R.string.access_enabled_toast),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, getString(R.string.setting_access_open_failed), Toast.LENGTH_SHORT).show() }
    }

    /** 统一弹窗样式：覆盖自绘圆角背景，贴近原生安卓对话框的圆润观感 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }



    private companion object {
        const val SHIZUKU_REQ = 4202

        /** 麦克风权限请求码（权限中心「麦克风」行） */
        const val REQ_MIC = 4203
        /** 双栏断点：Material 3 的 expanded 宽度（840dp） */
        const val WIDE_LAYOUT_MIN_WIDTH_DP = 840

        /** 宽窗内容最大宽度：两栏各约 516dp，平板横屏不拉满 */
        const val WIDE_CONTENT_MAX_WIDTH_DP = 1080

        /** 窄窗内容最大宽度：手机/平板竖屏保持易读行宽 */
        const val NARROW_CONTENT_MAX_WIDTH_DP = 680
    }

    /**
     * 状态值徽章化（v1.4.45）：软底 chip + 状态色文字。
     * 软底/文字色由调用方按状态给（success/success_soft、warning/warning_soft、
     * text_secondary/surface_alt），明暗主题都由 values(-night) 保证可读。
     */
    private fun TextView.asStatusChip(textColorRes: Int, bgTintRes: Int) {
        setBackgroundResource(R.drawable.bg_status_chip)
        backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(bgTintRes))
        setTextColor(getColor(textColorRes))
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    /**
     * 「去开启」按钮（v1.4.45~v1.4.57）已整体移除（v1.4.58）：按钮点击行为与
     * 整行点击本就一致（跳系统设置），纯视觉冗余且在窄屏上挤压标题/说明文字。
     * 现在整行即入口，未授权指引由各行的 sub 文案承担（"点击去系统设置开启"）。
     */

    /** 把当前 Activity 的视图按模式重排（不改 XML，运行时改布局参数） */
    private fun applyLayoutMode(mode: SettingsLayoutMode) {
        // 分区横跨两列：Display/Permissions 在 columnPrimary，Extensions/Other 在
        // columnSecondary（v1.2.79 双栏改造的产物）。只重排第一列会漏掉后两个分区
        // ——实测网格模式下 Extensions 仍是一列。
        val columns = listOfNotNull(
            findViewById<LinearLayout>(R.id.columnPrimary),
            findViewById<LinearLayout>(R.id.columnSecondary),
        )
        if (columns.isEmpty()) return
        val sections = columns.flatMap { sectionCards(it) }
        when (mode) {
            SettingsLayoutMode.LIST -> {
                sections.forEach { card ->
                    (card.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.bottomMargin = dp(4); card.layoutParams = it
                    }
                    card.orientation = LinearLayout.VERTICAL
                    rowsOf(card).forEach { row ->
                        row.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        setDescriptionsVisible(row, true)
                    }
                }
            }
            SettingsLayoutMode.CARD -> {
                // 分区之间拉开距离，卡内保持单列（读起来还是"每区一卡"）
                sections.forEach { card ->
                    (card.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.bottomMargin = dp(18); card.layoutParams = it
                    }
                    card.orientation = LinearLayout.VERTICAL
                    rowsOf(card).forEach { row ->
                        row.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        setDescriptionsVisible(row, true)
                    }
                }
            }
            SettingsLayoutMode.GRID -> {
                // 真正两列：每两行包进一个横向容器（卡片的父方向是 vertical，
                // 直接把行设 weight=1/width=0 会得到宽度 0 —— 实测踩过）。
                val gap = dp(6)
                sections.forEach { card ->
                    (card.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.bottomMargin = dp(18); card.layoutParams = it
                    }
                    val rows = rowsOf(card)
                    card.removeAllViews()
                    rows.chunked(2).forEach { pair ->
                        val wrap = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        }
                        pair.forEachIndexed { idx, row ->
                            row.layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                            ).apply { if (idx == 0 && pair.size == 2) marginEnd = gap }
                            setDescriptionsVisible(row, false)
                            // 网格里每行只剩图标 + 标题 + 当前值：文字必须单行省略，
                            // 否则会被逐字换行（实测「Landscape mode」竖排成一列）
                            compactRowForGrid(row)
                            wrap.addView(row)
                        }
                        if (pair.size == 1) {
                            // 奇数行：补占位保持左对齐，避免最后一项被拉满整行
                            wrap.addView(View(this).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                            })
                        }
                        card.addView(wrap)
                    }
                }
            }
        }
        findViewById<TextView>(R.id.valLayoutMode)?.text = getString(mode.labelRes)
    }

    /**
     * 找 columnPrimary 下的分区卡片（v1.4.23）。
     *
     * ⚠️ 不能只认「有 background 的 LinearLayout」：Extensions / Other 两个分区
     * 的卡片没有 bg_card 背景（实测漏识 → 网格模式下它们不参与重排）。
     * 这里改为「分区标题 TextView 之后、且包含至少一个可点击行的容器」。
     */
    private fun sectionCards(primary: LinearLayout): List<LinearLayout> {
        val out = mutableListOf<LinearLayout>()
        var afterHeader = false
        for (i in 0 until primary.childCount) {
            val v = primary.getChildAt(i)
            if (v is TextView && !afterHeader) {
                afterHeader = true      // 分区标题（大写字重仅用于视觉，无需判文案）
                continue
            }
            if (afterHeader && v is LinearLayout) {
                out.add(v)
                afterHeader = false
            }
        }
        return out
    }

    /** 卡片内的行容器（直接子 View 里可点击的 LinearLayout） */
    private fun rowsOf(card: LinearLayout): List<LinearLayout> =
        (0 until card.childCount).mapNotNull { card.getChildAt(it) as? LinearLayout }

    /**
     * 网格模式下隐藏「说明文字」（v1.4.23）。
     *
     * v1.4.58 起行结构是「标题行 + 说明行」两段式（任意深度嵌套），
     * 按直接子节点索引的老判据失效 → 改为**递归遍历**整棵行子树。
     * 判据：**小字号（≤12sp）且无 id 的 TextView** 才是说明文字——
     * 有 id 的都是有身份的控件（标题 16sp / 状态徽章 13-14sp / 箭头）；
     * 唯一例外 subAccess（带 id 的说明文字，照藏）。
     */
    private fun setDescriptionsVisible(row: LinearLayout, visible: Boolean) {
        walkRowViews(row) { v ->
            if (v is TextView) {
                val sp = v.textSize / resources.displayMetrics.scaledDensity
                val isDesc = sp <= 12.5f &&
                    (v.id == View.NO_ID || v.id == R.id.subAccess)
                if (isDesc) v.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
    }

    /** 深度优先遍历行内全部 View（含嵌套的标题行/说明行）。 */
    private fun walkRowViews(root: View, fn: (View) -> Unit) {
        fn(root)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) walkRowViews(root.getChildAt(i), fn)
        }
    }

    /**
     * 网格模式下行内压缩（v1.4.23；v1.4.58 改递归）：图标缩到 28dp、
     * 全部文字单行省略，保证 2 列宽度下仍可读（实测不处理会逐字竖排）。
     * 说明文字由 [setDescriptionsVisible] 先行隐藏，说明行随之塌缩，
     * 网格行只剩「图标 + 标题 + 状态徽章」单行。
     */
    private fun compactRowForGrid(row: LinearLayout) {
        row.setPadding(dp(8), dp(10), dp(8), dp(10))
        walkRowViews(row) { v ->
            when (v) {
                is android.widget.ImageView -> {
                    (v.layoutParams as? LinearLayout.LayoutParams)?.let {
                        if (it.width > dp(28)) {
                            it.width = dp(28); it.height = dp(28)
                            v.layoutParams = it
                        }
                    }
                    if (v.id == View.NO_ID || v.id == R.id.iconPriv || v.id == R.id.iconAccess) {
                        v.setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                }
                is TextView -> {
                    v.maxLines = 1
                    v.ellipsize = android.text.TextUtils.TruncateAt.END
                    v.setSingleLine(true)
                }
            }
        }
    }

}

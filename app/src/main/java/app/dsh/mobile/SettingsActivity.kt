package app.dsh.mobile

import android.app.AlertDialog
import android.Manifest
import android.content.Context
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
import app.dsh.mobile.engine.LanAddress
import app.dsh.mobile.engine.RemoteAccess
import app.dsh.mobile.engine.RemoteAccessServer
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
    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true


    /** 上次应用的主题/强调色/布局快照：子页改动后回到本页检测重建（v1.4.56） */
    private var appliedTheme: ThemeMode = ThemeMode.DARK
    private var appliedAccent: Int = 0
    private var appliedLayout: SettingsLayoutMode = SettingsLayoutMode.LIST

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.5.x：大屏跟随偏好、手机锁竖屏；用户可在外观页打开「设置页跟随屏幕方向」
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_settings)

        // MD3 进场：分组卡片按顺序错峰淡入上移，第一屏就有层次感。
        runCatching {
            val columns = findViewById<LinearLayout>(R.id.settingsColumns)
            val cards = mutableListOf<View>()
            fun collectCards(v: ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c is com.google.android.material.card.MaterialCardView) cards.add(c)
                    else if (c is ViewGroup) collectCards(c)
                }
            }
            collectCards(columns)
            cards.forEachIndexed { i, c ->
                c.alpha = 0f
                c.translationY = 12f
                c.animate().alpha(1f).translationY(0f)
                    .setStartDelay(40L + i * 45L).setDuration(220L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
        applyResponsiveLayout()


        // —— 扩展：局域网访问 WebUI（v1.2.160/v1.2.161）——
        // 独立端口 3090 的反向代理，让同网段设备用原生 WebUI 连上本机引擎。
        // v1.2.161（用户要求）：验证码改为 **6 位数字 + 每次开启重新随机**，
        // 生成后**自动复制到剪贴板**，且**只在应用内显示一次**（退出再进设置不显示）。
        val swLan = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swLanWeb)
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

        // AI 触摸点开关 v1.7.9 起搬到「权限与能力 → 无障碍」子页（A11ySettingsActivity），
        // 主页个性化分组只剩「外观与显示」一行。

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
        // —— 远程访问手机能力（v1.7.1）——
        // 与上面 LAN Web 的区别：那个是「人在浏览器里操作手机界面」，
        // 这个是「PC 上的脚本/AI 直接调用手机的 144 个设备端点」。
        // 两者独立开关：有人只要远程看界面、不想要脚本权限。
        val swRemote = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swRemoteAccess)
        val remoteSub = findViewById<android.widget.TextView>(R.id.remoteAccessSub)

        fun remoteSubText() {
            val on = swRemote.isChecked
            if (!on) {
                remoteSub.text = getString(R.string.setting_remote_access_sub)
                return
            }
            val best = LanAddress.best()
            val addr = if (best != null) "http://$best:${RemoteAccessServer.PORT}" else "?"
            val pairs = RemoteAccess.pairs(this).size
            remoteSub.text = buildString {
                append(getString(R.string.setting_remote_access_sub))
                append("\n\n")
                append(addr)
                append("\n")
                append(getString(R.string.remote_access_paired_count, pairs, RemoteAccess.MAX_PAIRS))
                if (RemoteAccess.pairWindowOpen(this@SettingsActivity)) {
                    append("\n")
                    append(
                        getString(
                            R.string.remote_access_window_open,
                            RemoteAccess.currentPairCode(this@SettingsActivity) ?: "------",
                            RemoteAccess.pairWindowRemainingSeconds(this@SettingsActivity),
                        ),
                    )
                }
            }
        }

        swRemote.isChecked = RemoteAccess.isEnabled(this)
        remoteSubText()

        // 行点击 = 打开详情（配对码 / 已配对设备 / 重置密钥）。开关本身只管总闸。
        findViewById<android.view.View>(R.id.rowRemoteAccess).setOnClickListener {
            RemoteAccessSheet.show(this) { remoteSubText() }
        }
        swRemote.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                RemoteAccess.setEnabled(this, false)
                remoteSubText()
                return@setOnCheckedChangeListener
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_remote_access)
                .setMessage(R.string.remote_access_consent)
                .setNegativeButton(R.string.action_cancel) { _, _ ->
                    swRemote.isChecked = false   // 拒绝就把开关弹回去
                }
                .setPositiveButton(R.string.remote_access_allow) { _, _ ->
                    RemoteAccess.setEnabled(this, true)
                    remoteSubText()
                    // 开启后立刻打开配对（用户开这个开关的唯一目的就是配对）
                    RemoteAccessSheet.show(this) { remoteSubText() }
                }
                .setOnCancelListener { swRemote.isChecked = false }
                .show()
        }

        lanSubText()
        swLan.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
        // 原生 MD3 顶栏：返回由 MaterialToolbar 的导航图标承担
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        // —— 个性化入口（v1.4.56）：外观/扩展整组收进子页，主页只留入口 ——
        val savedLayout = SettingsLayoutMode.current(this)
        appliedTheme = ThemePref.get(this)
        appliedAccent = Accent.current(this).argb
        appliedLayout = savedLayout
        findViewById<LinearLayout>(R.id.rowAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        // v1.4.58（宝宝要求）：扩展中心不再分组，直接显示在设置第一屏
        findViewById<LinearLayout>(R.id.rowExt).setOnClickListener {
            startActivity(Intent(this, ExtensionStoreActivity::class.java))
        }
        // v1.5.8：按宝宝要求把「语音和插件」拆成两个独立入口 ——
        // 「语音服务」进 VoiceServiceActivity（TTS + 本地识别合一），
        // 「社区插件」直接进插件市场。桌面宠物挪到「外观与显示」里。
        findViewById<LinearLayout>(R.id.rowPlugins).setOnClickListener {
            startActivity(Intent(this, PluginStoreActivity::class.java))
        }
        // Linux 工作区：完整 Alpine 用户态（免 root）。副标题右侧显示就绪状态，
        // 让用户一眼看到「要不要先部署」，不必点进去才发现。
        findViewById<LinearLayout>(R.id.rowLinux).setOnClickListener {
            startActivity(Intent(this, LinuxActivity::class.java))
        }
        // —— 数据：文件保存目录（v1.5.x 从「外观与显示」归到数据分类）——
        // 点一下把路径复制到剪贴板，方便粘到文件管理器/终端。
        runCatching {
            val filesDir = app.dsh.mobile.engine.EngineConfig.workspaces(this)
            findViewById<TextView>(R.id.valFilesDir)?.text = filesDir.absolutePath
            findViewById<LinearLayout>(R.id.rowFilesDir)?.setOnClickListener {
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("files dir", filesDir.absolutePath),
                    )
                }
                Toast.makeText(this, R.string.files_dir_copied, Toast.LENGTH_SHORT).show()
            }
        }
        runCatching {
            val st = app.dsh.mobile.engine.LinuxEnvironmentManager.get(this).status()
            findViewById<android.widget.TextView>(R.id.valLinux)?.text = when (st.state) {
                app.dsh.mobile.engine.LinuxEnvironmentManager.State.READY -> getString(R.string.linux_state_ready)
                app.dsh.mobile.engine.LinuxEnvironmentManager.State.NOT_DEPLOYED -> getString(R.string.linux_state_not_deployed)
                app.dsh.mobile.engine.LinuxEnvironmentManager.State.DEPLOYING -> getString(R.string.linux_state_deploying)
                app.dsh.mobile.engine.LinuxEnvironmentManager.State.BROKEN -> getString(R.string.linux_state_broken)
            }
        }
        if (savedLayout != SettingsLayoutMode.LIST) {
            // 布局参数改动需要在视图完成布局后进行
            findViewById<View>(R.id.columnPrimary)?.post { applyLayoutMode(savedLayout) }
        }

        // —— 权限与能力（v1.4.58）：收纳为两个分类入口（层级 1），
        //    具体设置项在 PermissionCenterActivity（层级 2，按 extra 显示对应分组）
        findViewById<LinearLayout>(R.id.rowPermEngine).setOnClickListener {
            startActivity(
                Intent(this, PermissionCenterActivity::class.java)
                    .putExtra(PermissionCenterActivity.EXTRA_GROUP, PermissionCenterActivity.GROUP_ENGINE),
            )
        }
        // 无障碍（v1.7.9）：服务开关 / 任务记忆 / 悬浮窗 / 语音 / 安全边界收在一个子页。
        findViewById<LinearLayout>(R.id.rowA11y).setOnClickListener {
            startActivity(Intent(this, A11ySettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowPermCaps).setOnClickListener {
            startActivity(
                Intent(this, PermissionCenterActivity::class.java)
                    .putExtra(PermissionCenterActivity.EXTRA_GROUP, PermissionCenterActivity.GROUP_CAPS),
            )
        }


        // 自定义指令（v1.3.2，issue #19）：在 App 的环境说明之上补写用户自己的长期指令。
        // 落点是 AGENTS.md 的用户段 —— 引擎只加载这一个 user-global 指令文件，
        // 分段管理后升级 App 不会丢用户内容，用户改指令也不会让环境说明变旧。
        findViewById<LinearLayout>(R.id.rowUserPrompt).setOnClickListener {
            startActivity(Intent(this, UserPromptActivity::class.java))
        }

        // —— 数据：备份与恢复子页（v1.4.46：导出/导入/恢复对话/配置文件收拢）——
        // —— 数据：双端同步子页（v1.6.0-beta.3）：与电脑上的 DSH 对齐会话历史 ——
        findViewById<LinearLayout>(R.id.rowPeerSync).setOnClickListener {
            startActivity(Intent(this, PeerSyncActivity::class.java))
        }

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
        // 语言名一律用该语言自身的写法（endonym）：中文恒为「中文」、德语恒为
        // 「Deutsch」、葡语恒为「Português」，泰语恒为「ไทย」。这样无论当前界面是哪种语言，用户在
        // 列表里都能靠母语认出自己要选的那一项（旧实现按当前 locale 翻译成
        // 「Chinese / Chinesisch / Chinês」，中文用户看到的反而是外语）。
        val langCodes = listOf("en", "de", "zh", "pt", "th", "ru")
        fun langLabel(code: String): String = when (code) {
            "zh" -> getString(R.string.lang_zh)
            "de" -> getString(R.string.lang_de)
            "pt" -> getString(R.string.lang_pt)
            "th" -> getString(R.string.lang_th)
            "ru" -> getString(R.string.lang_ru)
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
                getString(R.string.lang_pt),
                getString(R.string.lang_th),
                getString(R.string.lang_ru),
            )
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
                .show()
        }

        // —— 其他：关于 ——
        findViewById<TextView>(R.id.subAbout).text =
            getString(R.string.setting_about_sub, versionName())
        findViewById<LinearLayout>(R.id.rowAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 记忆面板（M8）的入口 v1.7.9 起收进「权限与能力 → 无障碍 → 任务记忆」，
        // 主页不再单列一个「记忆」分组（同一页两个入口只会让人犹豫点哪个）。
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
     *
     * ⚠️ 必须把 [newConfig] 传下去：此刻 `resources.configuration` 仍是**转屏前**的
     * 那一份（实测 newCfg 800dp/竖屏、res 1280dp/横屏），照着 resources 算会把
     * 横屏双栏硬塞进竖屏里。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout(newConfig)
    }

    /** 无障碍行的副标题：把「服务开没开」直接写在入口上，用户不用点进去才知道。 */
    private fun refreshA11ySub() {
        val tv = findViewById<android.widget.TextView?>(R.id.txtRowA11y) ?: return
        tv.text = if (DshAccessibilityService.instance != null) {
            getString(R.string.a11y_service_on)
        } else {
            getString(R.string.settings_row_a11y_sub)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
        refreshUpdateEntrySub()
        refreshA11ySub()
        // 子页里改过主题/强调色/布局：回到本页立即重建，保证视觉与偏好一致（v1.4.56）
        if (appliedTheme != ThemePref.get(this) ||
            appliedAccent != Accent.current(this).argb ||
            appliedLayout != SettingsLayoutMode.current(this)
        ) {
            recreate()
            return
        }
        refreshAppearanceEntry()
        refreshExtEntry()
    }

    /** 个性化入口副标题：当前主题 · 强调色（v1.4.56） */
    private fun refreshAppearanceEntry() {
        findViewById<TextView>(R.id.subAppearanceEntry)?.text = getString(
            R.string.setting_appearance_sub,
            getString(ThemePref.get(this).labelRes),
            getString(Accent.current(this).labelRes),
        )
    }

    /** 扩展中心行副标题：已激活扩展数（v1.4.58 起该行回到主页第一屏） */
    private fun refreshExtEntry() {
        val count = runCatching { ExtensionManager(this).activeCount() }.getOrDefault(0)
        findViewById<TextView>(R.id.valExt)?.text =
            getString(R.string.setting_ext_active_count, count)
    }

    /**
     * 平板/横屏响应式布局（v1.4.18，v1.4.27 改为按高度均衡分列）：
     * - 宽窗：两栏，**按分区行数把 8 个分区就近对半分**（断点见 [useTwoColumns]）；
     * - 窄窗：单列，顺序不变（显示 → 权限中心 → 语音 → 扩展 → 数据 → 网络 → 维护 → 关于）。
     *
     * v1.5.x：断点不再只看「横屏 840dp 一个值」，改为**按当前配置的宽度 + 朝向**判定，
     * 所以平板竖屏（800dp）也能双栏、手机竖屏仍是单列，转屏时实时切换。
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
     *
     * @param cfg **当前**配置。转屏路径必须传入 `onConfigurationChanged` 的 newConfig，
     *   默认值只适用于 onCreate（那时 resources 与实屏一致）。
     */
    private fun applyResponsiveLayout(cfg: Configuration = resources.configuration) {
        val wide = useTwoColumns(cfg)
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
     * 单列 / 双栏判定（v1.5.x）：只看**传入配置**的可用宽度与朝向。
     *
     *  · 横屏：>= 840dp（Material 3 的 expanded 断点）—— 手机横屏 780~800dp
     *    仍是单列，避免两栏各 380dp 把说明文字挤成三行；
     *  · 竖屏：>= 600dp —— 平板竖屏（实测 1600x2560 @320dpi = 800dp）双栏，
     *    手机竖屏（360~430dp）单列。
     *
     * 这两个值都是「内容区宽度」的代理：判的是 screenWidthDp（已扣除系统栏），
     * 不依赖实测像素，所以在 onCreate（尚未布局）时也能算出正确结果。
     */
    private fun useTwoColumns(cfg: Configuration): Boolean {
        val min = if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            TWO_COLUMN_MIN_WIDTH_LANDSCAPE_DP
        } else {
            TWO_COLUMN_MIN_WIDTH_PORTRAIT_DP
        }
        return cfg.screenWidthDp >= min
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
                    val rows = rowsOf(child).size
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
        super.onDestroy()
    }

    // 权限中心代码（v1.4.58）已整体迁入 PermissionCenterActivity —— 含 Shizuku
    // 监听、麦克风权限回调、运行权限模式对话框（双警告 + 引擎重启）。
    // 本页只保留两个分类入口行（rowPermEngine / rowPermCaps）。

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

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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

    /** 统一弹窗样式：覆盖自绘圆角背景，贴近原生安卓对话框的圆润观感 */
private companion object {
        /** 折叠模式展开/收起的过渡时长（毫秒） */
        const val COLLAPSE_ANIM_MS = 220L

        /** 网格格子固定高度（dp）：容纳两行 16sp 标题 + 上下 14dp 内边距。 */
        const val GRID_CELL_HEIGHT_DP = 64

        /**
         * 含「行内控件」（开关 / 按钮）的格子高度（dp）。
         *
         * 为什么必须比 [GRID_CELL_HEIGHT_DP] 高：格子宽度只有约 176dp，扣掉
         * 左右 20dp 内边距 + 42dp 图标后，横向再塞一个约 52dp 的 MaterialSwitch，
         * 标题连一个字都放不下，而标题容器的 weight=1 会把剩余宽度全吃掉 ——
         * 开关被压成 0 宽直接从界面上消失（宝宝反馈「按钮被隐藏，无法选择」）。
         * 所以这类格子改为竖排：上行图标+标题，控件放底部，高度给到能容纳两者的值。
         */
        const val GRID_CELL_CONTROL_HEIGHT_DP = 104

        /** 双栏断点（横屏）：Material 3 的 expanded 宽度（840dp） */
        const val TWO_COLUMN_MIN_WIDTH_LANDSCAPE_DP = 840

        /** 双栏断点（竖屏）：平板竖屏 800dp 也应双栏，手机竖屏远达不到 */
        const val TWO_COLUMN_MIN_WIDTH_PORTRAIT_DP = 600

        /** 宽窗内容最大宽度：两栏各约 516dp，平板横屏不拉满 */
        const val WIDE_CONTENT_MAX_WIDTH_DP = 1080

        /** 窄窗内容最大宽度：手机/平板竖屏保持易读行宽 */
        const val NARROW_CONTENT_MAX_WIDTH_DP = 680
    }

    /**
     * 「去开启」按钮（v1.4.45~v1.4.57）已整体移除（v1.4.58）：按钮点击行为与
     * 整行点击本就一致（跳系统设置），纯视觉冗余且在窄屏上挤压标题/说明文字。
     * 现在整行即入口，未授权指引由各行的 sub 文案承担（"点击去系统设置开启"）。
     */


    /**
     * 折叠模式下分区标题的箭头：收起朝下、展开朝上。
     *
     * 图标本身由 ic_expand_* 提供，这里只管方向与间距 —— 集中一处，
     * 初始渲染和点击切换用的是同一段逻辑，不会出现两处不一致。
     */
    private fun applyCollapseArrow(header: TextView, open: Boolean) {
        header.setCompoundDrawablesWithIntrinsicBounds(
            0, 0,
            if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
            0,
        )
        header.compoundDrawablePadding = dp(8)
    }


    /**
     * 展开/收起一张分区卡片（高度动画）。
     *
     * 为什么不用 TransitionManager：它对 GONE 的可见性变化只做淡出，
     * 布局高度是立即塌缩的，收起看起来像被「截断」。
     * 这里直接把 layoutParams.height 从 0 动画到实测内容高度（反之亦然），
     * 高度与透明度同步过渡，收起和展开对称。
     */
    private fun animateCollapse(card: LinearLayout, parent: ViewGroup, expand: Boolean) {
        val lp = card.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        // 展开前先量出内容高度：此时卡片还是 GONE，height 不可用。
        val targetHeight: Int
        if (expand) {
            card.visibility = View.VISIBLE
            card.measure(
                View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            targetHeight = card.measuredHeight
            lp.height = 0
            card.layoutParams = lp
            card.alpha = 0f
        } else {
            targetHeight = 0
        }

        val startHeight = if (expand) 0 else card.height
        val animator = android.animation.ValueAnimator.ofInt(startHeight, targetHeight)
        animator.duration = COLLAPSE_ANIM_MS
        animator.interpolator = if (expand) {
            android.view.animation.DecelerateInterpolator()
        } else {
            android.view.animation.AccelerateInterpolator()
        }
        animator.addUpdateListener { a ->
            val v = a.animatedValue as Int
            lp.height = v
            card.layoutParams = lp
            card.alpha = if (expand) a.animatedFraction else 1f - a.animatedFraction
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // 动画结束回到自适应高度，否则旋转/改字号后内容会被裁掉
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                card.layoutParams = lp
                card.alpha = 1f
                if (!expand) card.visibility = View.GONE
            }
        })
        animator.start()
    }

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
            SettingsLayoutMode.GRID -> {
                // v1.6.1 重写。旧实现有三个真缺陷（宝宝真机反馈「轮廓异常 + 大量字符
                // 被隐藏」）：
                //  1. 格子直接沿用列表的 bg_md3_seg_top/mid/bottom —— 那套是「整宽卡片
                //     里的一行」，首行只有上圆角、中间行四角全平、末行只有下圆角。
                //     放进两列网格后每格都是残角，上下相邻拼出锯齿轮廓、左右两列圆角
                //     方向还不一致。改成每格独立的 bg_md3_grid_cell（四角同圆 + 描边）。
                //  2. 行内「值/状态」文字（valExt、subAbout…）仍占宽，标题 TextView 是
                //     weight=1 被压到只剩几个字 → 「外观与…」。网格里格子只该有
                //     图标 + 标题，值/说明一律藏掉。
                //  3. 行仍带列表的内边距与 72dp minHeight，两列下格子高度虚高。
                val gap = dp(8)
                sections.forEach { card ->
                    // 网格里卡片底距收紧到 0：后面还有分区标题的 24dp marginTop，
                    // 再叠 18dp 就成了 42dp 的空白（一屏少一排格子）。
                    (card.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.bottomMargin = 0; card.layoutParams = it
                    }
                    // MaterialCardView 仍作为分组外壳，网格重排其内部的行容器；
                    // 直接清空外层会把整张卡片当作一行，分隔线也会残留。
                    val content = rowContainerOf(card)
                    val rows = rowsOf(card)
                    if (rows.isEmpty()) return@forEach
                    val materialCard = content.parent as? com.google.android.material.card.MaterialCardView
                    if (materialCard != null) {
                        materialCard.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                        materialCard.foreground = null
                        materialCard.strokeWidth = 0
                        materialCard.cardElevation = 0f
                        materialCard.radius = 0f
                        materialCard.clipToOutline = false
                    } else {
                        card.setBackgroundResource(0)
                        card.clipToOutline = false
                    }
                    content.setPadding(0, 0, 0, 0)
                    content.removeAllViews()
                    rows.chunked(2).forEach { pair ->
                        val wrap = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = gap }
                        }
                        // 同一行两格高度必须一致：只要有一格带行内控件（开关/按钮），
                        // 就以「带控件」的高度给整行，否则开关那格变高、邻格仍是 64dp，
                        // 上下拼出的轮廓会参差（v1.6.1 已被投诉过锯齿）。
                        val rowHasControl = pair.any { rowHasInlineControl(it) }
                        pair.forEachIndexed { idx, row ->
                            row.layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                            ).apply {
                                // 首格永远留 gap：奇数行（最后一格独占）若不加，
                                // 它的宽度会比偶数行的格子宽 gap/2 —— 实测
                                // 552px 对 538px，右侧边缘对不齐。
                                if (idx == 0) marginEnd = gap
                            }
                            gridCell(row, rowHasControl)
                            val cell = com.google.android.material.card.MaterialCardView(this).apply {
                                layoutParams = row.layoutParams
                                setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                                    this, com.google.android.material.R.attr.colorSurfaceContainer,
                                ))
                                radius = resources.getDimension(R.dimen.md3_card_radius)
                                cardElevation = 0f
                                strokeWidth = 0
                            }
                            row.layoutParams = android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(if (rowHasControl) GRID_CELL_CONTROL_HEIGHT_DP else GRID_CELL_HEIGHT_DP),
                            )
                            cell.addView(row)
                            wrap.addView(cell)
                        }
                        if (pair.size == 1) {
                            // 奇数行：补占位保持左对齐，避免最后一项被拉满整行
                            wrap.addView(View(this).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                            })
                        }
                        content.addView(wrap)
                    }
                }
            }
            SettingsLayoutMode.COLLAPSE -> {
                // 分区默认收起，只留标题；点标题展开该区全部条目。
                // 行内排布沿用 LIST 的基础样式，只是把卡片整体藏起来。
                // 注意：切换模式会走 onResume 的 recreate()，所以这里拿到的
                // 一定是 XML 刚 inflate 的干净视图，不用考虑 GRID 的 wrap 残留。
                val expanded = SettingsLayoutMode.expandedSections(this)
                // 顶部分区标题原本直接贴住内容区顶部，太挤（真机反馈）。
                // 折叠模式给两列各留 10dp 上边距。
                listOf(R.id.columnPrimary, R.id.columnSecondary).forEach { id ->
                    findViewById<LinearLayout>(id)?.setPadding(0, dp(10), 0, 0)
                }
                sections().forEachIndexed { idx, section ->
                    section.card.orientation = LinearLayout.VERTICAL
                    (section.card.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.bottomMargin = dp(4); section.card.layoutParams = it
                    }
                    rowsOf(section.card).forEach { row ->
                        row.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        setDescriptionsVisible(row, true)
                    }
                    val open = idx in expanded
                    section.card.visibility = if (open) View.VISIBLE else View.GONE
                    section.header?.let { header ->
                        // 折叠模式下标题就是「分区卡片」本身：给它卡片底色 + 内边距，
                        // 收起时是一摞可点的卡片，比裸文字好认（真机反馈「太小、没背景」）。
                        // 卡片 + 圆角涟漪：折叠标题是主要操作入口，必须有按压反馈。
                        // 用 ripple 包住 bg_card，mask 把涟漪裁在圆角内。
                        header.setBackgroundResource(R.drawable.bg_card_ripple)
                        header.setPadding(dp(20), dp(18), dp(20), dp(18))
                        header.textSize = 17f
                        header.minimumHeight = dp(64)
                        header.isClickable = true
                        header.isFocusable = true
                        // 标题原本是 wrap_content，点击区只有文字那么宽；
                        // 撑满整行后整条都能点，手感更符合"点这行展开"的预期。
                        // 注意类型：header.layoutParams 的静态类型是 ViewGroup.LayoutParams，
                        // 直接 .apply { bottomMargin = ... } 会编译报错（它只定义在
                        // LinearLayout.LayoutParams 上），所以先显式转一次。
                        val headerLp = header.layoutParams as? LinearLayout.LayoutParams
                            ?: LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        headerLp.width = LinearLayout.LayoutParams.MATCH_PARENT
                        headerLp.bottomMargin = dp(8)
                        header.layoutParams = headerLp
                        applyCollapseArrow(header, open)
                        header.setOnClickListener {
                            SettingsLayoutMode.toggleSection(this, idx)
                            val nowOpen = idx in SettingsLayoutMode.expandedSections(this)
                            // 手动做**高度动画**，而不是 visibility 切换：
                            // AutoTransition 遇到 GONE 只做淡出，高度是瞬间塌缩的，
                            // 收起那一刻会「啪」地截断（真机反馈）。
                            val parent = section.card.parent as? android.view.ViewGroup
                            if (parent != null) {
                                animateCollapse(section.card, parent, nowOpen)
                            } else {
                                section.card.visibility =
                                    if (nowOpen) View.VISIBLE else View.GONE
                            }
                            applyCollapseArrow(header, nowOpen)
                        }
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

    /** 兼容「外层 LinearLayout → MaterialCardView → 行容器」及旧版直接行容器。 */
    private fun rowContainerOf(card: LinearLayout): LinearLayout {
        val materialCard = (0 until card.childCount)
            .mapNotNull { card.getChildAt(it) as? com.google.android.material.card.MaterialCardView }
            .firstOrNull() ?: return card
        return (0 until materialCard.childCount)
            .mapNotNull { materialCard.getChildAt(it) as? LinearLayout }
            .firstOrNull() ?: card
    }

    /** 分隔线不是行；不向下递归，以免把行内标题容器误认为设置入口。 */
    private fun rowsOf(card: LinearLayout): List<LinearLayout> {
        val content = rowContainerOf(card)
        return (0 until content.childCount).mapNotNull { content.getChildAt(it) as? LinearLayout }
    }

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
                val isDesc = sp <= 12.5f && v.id == View.NO_ID
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
     * 把一个列表行改造成网格格子（v1.6.1 重写，旧版叫 compactRowForGrid）。
     *
     * 旧版只做「图标缩到 28dp + 全部文字单行」，但保留了两条会把标题挤没的
     * 结构：① 行内「值/状态」TextView（valExt / valLinux / valFilesDir /
     * subAbout…）仍占宽；② 说明行只是 GONE，行内两段式容器仍按列表内边距
     * 排布。两列下标题（weight=1）被压成「外观与…」——宝宝原话「大量字符
     * 被隐藏」。
     *
     * 现在的做法：格子只留「图标 + 标题」，其余文字一律 GONE；背景换成
     * 独立的 bg_md3_grid_cell（四角同圆 + 1dp 描边），不再复用分段背景的
     * 残角形状。
     */
    private fun gridCell(row: LinearLayout, hasControl: Boolean = false) {
        val selectable = android.util.TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
            row.setBackgroundResource(selectable.resourceId)
        }
        row.setPadding(dp(10), dp(10), dp(10), dp(10))
        // 格子高度：无控件 64dp（v1.6.2 从 88 收紧，一屏多两排）；带控件的格子
        // 必须更高，见 GRID_CELL_CONTROL_HEIGHT_DP 的说明。
        val cellHeightDp = if (hasControl) GRID_CELL_CONTROL_HEIGHT_DP else GRID_CELL_HEIGHT_DP
        row.minimumHeight = dp(cellHeightDp)
        // 行自身的外边距（列表里用来做 1dp 缝）在网格里会撕开格子，清掉
        (row.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = 0
            it.bottomMargin = 0
            it.height = dp(cellHeightDp)
            row.layoutParams = it
        }
        // ★ 行内控件（开关/按钮）必须先摘出来，否则下面的文字裁剪会把它一起藏掉：
        // MaterialSwitch 的继承链是 SwitchCompat → CompoundButton → Button →
        // TextView，落在「is TextView」分支里，字号 14sp 小于标题 16sp 就被判成
        // 说明文字 → GONE。这正是宝宝说的「网格模式下按钮被隐藏、点不到」。
        val control = if (hasControl) inlineControlOf(row) else null
        // 行内最大字号的 TextView 就是标题（BodyLarge 16sp），其余是说明/值。
        var titleSp = 0f
        walkRowViews(row) { v ->
            if (v is TextView && v !== control) {
                val sp = v.textSize / resources.displayMetrics.scaledDensity
                if (sp > titleSp) titleSp = sp
            }
        }
        walkRowViews(row) { v ->
            when {
                v === control -> Unit          // 稍后单独摆到格子底部
                v is android.widget.ImageView -> {
                    val isChevron = (v.layoutParams as? LinearLayout.LayoutParams)
                        ?.let { it.width <= dp(24) } ?: false
                    if (isChevron) {
                        // 网格格子里那个「>」纯属噪音，占掉标题的位置
                        v.visibility = View.GONE
                    } else {
                        (v.layoutParams as? LinearLayout.LayoutParams)?.let {
                            it.width = dp(28); it.height = dp(28)
                            it.marginStart = 0
                            v.layoutParams = it
                        }
                        v.setPadding(dp(7), dp(7), dp(7), dp(7))
                        v.visibility = View.VISIBLE
                    }
                }
                v is TextView -> {
                    // 只留标题（BodyLarge 16sp 那一支）。说明行是 BodyMedium 14sp、
                    // 值/状态是 BodySmall 12sp —— 实测用「≤13.5sp」会把 14sp 的说明
                    // 漏下来（Linux 工作区那格因此比邻居高一截）。改成「除最大字号
                    // 外一律藏」：先收集全部 TextView，字号最大的那个才是标题。
                    val sp = v.textSize / resources.displayMetrics.scaledDensity
                    if (sp < titleSp - 0.1f) {
                        v.visibility = View.GONE
                    } else {
                        v.maxLines = 2
                        v.ellipsize = android.text.TextUtils.TruncateAt.END
                        v.setSingleLine(false)
                        v.visibility = View.VISIBLE
                        (v.layoutParams as? LinearLayout.LayoutParams)?.let {
                            it.width = ViewGroup.LayoutParams.MATCH_PARENT
                            it.weight = 0f
                            v.layoutParams = it
                        }
                    }
                }
            }
        }
        if (control != null) {
            // 竖排：上行「图标 + 标题」，下行控件。横排做不到 —— 176dp 宽的格子里
            // 图标 28dp + 开关约 52dp，留给标题的宽度不足一个字，而标题容器 weight=1
            // 会把剩余宽度全吃掉，开关被压成 0 宽。
            val children = (0 until row.childCount).map { row.getChildAt(it) }
            row.removeAllViews()
            row.orientation = LinearLayout.VERTICAL
            // 上行高度用 wrap_content 而不是 weight=1：weight 分配时 LinearLayout
            // 按「剩余空间 ÷ 权重」给高度，实测两行标题只分到 48dp 被切掉一半
            // （「LAN access to WebUI」的 WebUI 看不见）。wrap_content 让它先量足
            // 两行文字，剩余空间留给中间的空白。
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            children.filter { it !== control }.forEach { v ->
                (v.parent as? ViewGroup)?.removeView(v)
                // 标题在竖排上行里是唯一的伸缩项：宽度给 0 + weight=1。
                // 直接沿用原来的 MATCH_PARENT 会跟 28dp 图标抢整格宽度，
                // 图标被压扁。
                if (v is TextView) {
                    v.layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { marginStart = dp(8) }
                }
                top.addView(v)
            }
            row.addView(top)
            (control.parent as? ViewGroup)?.removeView(control)
            control.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4); gravity = android.view.Gravity.END }
            control.visibility = View.VISIBLE
            row.addView(control)
        }
    }

    /** 行内真正可操作的控件（开关 / 按钮），网格模式要给它留位置。 */
    private fun inlineControlOf(row: LinearLayout): View? {
        var found: View? = null
        walkRowViews(row) { v ->
            if (found == null && (v is android.widget.CompoundButton || v is android.widget.Button)) {
                found = v
            }
        }
        return found
    }

    /** 该行是否带行内控件 —— 决定这一格（以及同排邻格）用哪种高度。 */
    private fun rowHasInlineControl(row: LinearLayout): Boolean = inlineControlOf(row) != null

}

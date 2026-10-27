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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.ChatRecovery
import app.dsh.mobile.engine.ExtensionManager
import app.dsh.mobile.engine.PluginKillSwitch
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.engine.RuntimeDownloadConfig
import app.dsh.mobile.engine.SelfCheck
import app.dsh.mobile.engine.TtsManager
import app.dsh.mobile.engine.LanWebProxy
import app.dsh.mobile.pet.PetOverlayService
import app.dsh.mobile.pet.PetPluginDetector
import app.dsh.mobile.pet.PetSettingsActivity

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

    /** 屏幕朝向偏好（v1.2.120 三态） */
    private var orientMode: OrientationMode = OrientationMode.PORTRAIT
    private var pageScale = DEFAULT_PAGE_SCALE

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

        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        orientMode = OrientationPrefs.get(this)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

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

        // —— 显示：屏幕朝向（v1.2.120：竖屏/横屏/自动 三选一）——
        findViewById<LinearLayout>(R.id.rowLandscape).setOnClickListener {
            showOrientDialog()
        }

        // —— 显示：页面缩放 ——
        findViewById<LinearLayout>(R.id.rowScale).setOnClickListener {
            showScaleDialog()
        }

        // —— 显示：沉浸式状态栏（横竖屏都隐藏，可自行开关）——
        val swImmersive = findViewById<android.widget.Switch>(R.id.swImmersive)
        swImmersive.isChecked = MainActivity.immersiveEnabled(this)
        swImmersive.setOnCheckedChangeListener { _, checked ->
            MainActivity.setImmersiveEnabled(this, checked)
        }

        // —— 显示：文件保存目录（v1.4.26，DSHA 风格 Download/DSH MOBILE）——
        val filesDir = app.dsh.mobile.engine.EngineConfig.workspaces(this)
        findViewById<TextView>(R.id.valFilesDir)?.text = filesDir.absolutePath
        findViewById<LinearLayout>(R.id.rowFilesDir)?.setOnClickListener {
            runCatching {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("files dir", filesDir.absolutePath),
                )
            }
            Toast.makeText(this, R.string.files_dir_copied, Toast.LENGTH_SHORT).show()
        }

        // —— 显示：外观主题（深色/浅色/跟随系统）——
        // 与引导页写同一份偏好（ThemePref）；改动后本页 recreate 立即生效，
        // 返回主界面时 MainActivity.onResume 检测到偏好变化会自行重建。
        findViewById<LinearLayout>(R.id.rowTheme)?.setOnClickListener {
            showThemeDialog()
        }
        findViewById<TextView>(R.id.valTheme)?.text = getString(ThemePref.get(this).labelRes)

        // —— 显示：配色方案（v1.4.23）——
        // v1.4.22 把 DSHA 靛蓝写死了，宝宝反馈"跟主题不搭"。配色不再拍板，
        // 交给用户选，默认回到项目原本的天蓝。
        findViewById<LinearLayout>(R.id.rowAccent)?.setOnClickListener {
            showAccentDialog()
        }

        // —— 显示：设置页布局（v1.4.23）——
        findViewById<LinearLayout>(R.id.rowLayoutMode)?.setOnClickListener {
            showLayoutModeDialog()
        }

        // 应用已保存的布局模式与当前值显示（v1.4.23）
        val savedLayout = SettingsLayoutMode.current(this)
        findViewById<TextView>(R.id.valLayoutMode)?.text = getString(savedLayout.labelRes)
        findViewById<TextView>(R.id.valAccent)?.text = getString(Accent.current(this).labelRes)
        findViewById<TextView>(R.id.valLayoutMode)?.deprecatedAccent()
        findViewById<TextView>(R.id.valAccent)?.deprecatedAccent()
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

        // —— 权限中心：语音合成（TTS）—— 点击播报测试 + 显示引擎状态
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
        refreshTtsRow()

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


        // —— 扩展中心 ——
        findViewById<LinearLayout>(R.id.rowExt).setOnClickListener {
            startActivity(Intent(this, ExtensionStoreActivity::class.java))
        }

        // —— 社区插件（v1.2.113）：一键安装 dsh 生态插件 ——
        findViewById<LinearLayout>(R.id.rowPlugin).setOnClickListener {
            startActivity(Intent(this, PluginStoreActivity::class.java))
        }

        // —— 桌宠悬浮窗（预留，插件驱动）：独立设置页负责权限/包/开关 ——
        findViewById<LinearLayout>(R.id.rowPet).setOnClickListener {
            startActivity(Intent(this, PetSettingsActivity::class.java))
        }

        // 自定义指令（v1.3.2，issue #19）：在 App 的环境说明之上补写用户自己的长期指令。
        // 落点是 AGENTS.md 的用户段 —— 引擎只加载这一个 user-global 指令文件，
        // 分段管理后升级 App 不会丢用户内容，用户改指令也不会让环境说明变旧。
        findViewById<LinearLayout>(R.id.rowUserPrompt).setOnClickListener {
            startActivity(Intent(this, UserPromptActivity::class.java))
        }

        // 语音服务（v1.2.78）：识别 + 合成合并入口，归入扩展中心分组
        findViewById<LinearLayout>(R.id.rowVoiceService).setOnClickListener {
            startActivity(Intent(this, app.dsh.mobile.VoiceServiceActivity::class.java))
        }

        // 本地识别应用（v1.2.85）：**直接可见的一行**，不用钻进语音服务页再点问号
        // （主人反馈：「设置里面好像没有下载入口」—— 原来藏在 语音服务 → ? → 弹窗 三层里）
        findViewById<LinearLayout>(R.id.rowAsrApps).setOnClickListener {
            startActivity(Intent(this, app.dsh.mobile.AsrAppActivity::class.java))
        }

        // —— 其他：数据导出（issue #31 第一步）——
        // 系统文件选择器让用户选保存位置（无需额外存储权限），结果在 onActivityResult 里处理。
        exportSub = findViewById(R.id.txtExportSub)
        findViewById<LinearLayout>(R.id.rowExportData).setOnClickListener {
            if (exporting) return@setOnClickListener
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val pick = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "dsh-backup-$stamp.zip")
            runCatching { startActivityForResult(pick, REQ_EXPORT) }.onFailure {
                exportSub?.text = getString(R.string.export_failed, it.message ?: it.javaClass.simpleName)
            }
        }

        // —— 其他：配置文件（v1.4.29，issue #25）——
        // 用户原话「无法打开配置文件」：FileEditorActivity 一直存在，但只有 AI 桥能拉起，
        // 用户自己没有入口。这里列出常用配置，点哪个开哪个。
        findViewById<LinearLayout>(R.id.rowConfigFiles).setOnClickListener {
            showConfigFilePicker()
        }

        // —— 其他：数据导入（v1.4.29，issue #31 第二步）——
        // 合并式恢复：只写目标不存在的文件，不覆盖现有数据。
        importSub = findViewById(R.id.txtImportSub)
        findViewById<LinearLayout>(R.id.rowImportData).setOnClickListener {
            if (importing) return@setOnClickListener
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
            runCatching { startActivityForResult(pick, REQ_IMPORT) }.onFailure {
                importSub?.text = getString(R.string.import_failed, it.message ?: it.javaClass.simpleName)
            }
        }

        // —— 其他：对话恢复（v1.4.4）——
        // 扫描 sessions/ 下未登记进工作区的会话文件，用户确认后登记回去。
        // 全程后台线程：扫几百个会话目录 + 读写注册表，不能在主线程做。
        val recoverSub = findViewById<TextView>(R.id.txtRecoverSub)
        findViewById<LinearLayout>(R.id.rowRecoverChats).setOnClickListener {
            recoverSub.text = getString(R.string.recover_scanning)
            Thread({
                val result = runCatching { ChatRecovery.scan(this) }.getOrNull()
                runOnUiThread {
                    if (result == null) {
                        recoverSub.text = getString(
                            R.string.recover_failed, getString(R.string.file_editor_read_failed),
                        )
                        return@runOnUiThread
                    }
                    if (result.orphans.isEmpty()) {
                        recoverSub.text = getString(R.string.recover_none)
                        return@runOnUiThread
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.setting_recover_chats)
                        .setMessage(getString(R.string.recover_confirm, result.orphans.size))
                        .setPositiveButton(R.string.recover_confirm_yes) { _, _ ->
                            recoverSub.text = getString(R.string.recover_scanning)
                            Thread({
                                // ⚠️ v1.4.7（用户实测反馈，关键）：**必须先把引擎停掉**。
                                // 引擎把 workspace.json 当独占文件：启动时读一次进内存，
                                // 之后每次变更整份写回，既不监视文件也不比对时间戳。
                                // 在引擎运行期间改这个文件，改动会被它的旧内存状态抹掉 ——
                                // 用户实测：写进 7 个工作区，3 分钟后回落到 1 个，日志无记录。
                                // 于是「恢复成功 → 重启 → 什么都没有 → 卸载」，
                                // 私有目录被清空，历史真的丢了。
                                val sup = (application as DshApp).supervisor
                                runCatching { sup.stop(graceMs = 3_000, explicit = false) }
                                // 给引擎一点时间真正退出并落盘
                                Thread.sleep(800)
                                val n = runCatching {
                                    ChatRecovery.recover(this, result.orphans)
                                }.getOrElse { -1 }
                                // 恢复完再启动：引擎这次启动会重新从磁盘读 registry
                                runCatching { sup.start((application as DshApp).appScope) }
                                runOnUiThread {
                                    recoverSub.text = if (n >= 0) {
                                        getString(R.string.recover_done, n) + " · " +
                                            getString(R.string.recover_restart_hint)
                                    } else {
                                        getString(
                                            R.string.recover_failed,
                                            getString(R.string.file_editor_read_failed),
                                        )
                                    }
                                }
                            }, "chat-recover-write").start()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }, "chat-recover-scan").start()
        }

        // —— 维护：插件应急总开关（v1.4.27）——
        // 插件把 WebUI 弄坏但引擎还活着时，自动自救层全部不触发（它们只认崩溃与
        // 探针 degraded）。这里是唯一的逃生门：停用全部第三方插件，只留引擎自带。
        findViewById<LinearLayout>(R.id.rowPluginKill).setOnClickListener {
            confirmKillSwitch()
        }
        refreshPluginKill()

        // —— 其他：全面体检与修复（v1.4.4，用户要的「强检模式」）——
        // 与插件无关：插件坏了、配置乱了也能跑；结果逐条列出，异常项带具体数值。
        val selfCheckSub = findViewById<TextView>(R.id.txtSelfCheckSub)
        findViewById<LinearLayout>(R.id.rowSelfCheck).setOnClickListener {
            selfCheckSub.text = getString(R.string.self_check_running)
            Thread({
                val report = runCatching { SelfCheck.run(this) }.getOrNull()
                runOnUiThread {
                    if (report == null) {
                        selfCheckSub.text = getString(
                            R.string.recover_failed, getString(R.string.file_editor_read_failed),
                        )
                        return@runOnUiThread
                    }
                    selfCheckSub.text = if (report.issues == 0) {
                        getString(R.string.self_check_ok)
                    } else {
                        getString(R.string.self_check_issues, report.issues, report.fixed)
                    }
                    // 逐条展示：用户能直接看到哪一项异常、具体是什么
                    val lines = report.items.map { item ->
                        val mark = when (item.status) {
                            SelfCheck.Item.Status.OK -> "✓"
                            SelfCheck.Item.Status.FIXED -> "✓*"
                            SelfCheck.Item.Status.WARN -> "!"
                            SelfCheck.Item.Status.FAIL -> "✗"
                        }
                        "$mark ${item.title}" + if (item.detail.isNotBlank()) "\n    ${item.detail}" else ""
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.setting_self_check)
                        .setMessage(lines.joinToString("\n\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.file_editor_copy_path) { _, _ ->
                            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "dsh-selfcheck", SelfCheck.summarize(report),
                                ),
                            )
                            Toast.makeText(this, R.string.file_editor_path_copied, Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
            }, "self-check").start()
        }

        // —— 其他：上次启动详情（v1.4.8）——
        // 用户报「启动慢」时，这里能直接看到每个阶段各花了多久、解压了多少文件，
        // 不用再去翻 engine.log。数据来自 BootTrace（引擎启动时实时写入 boot.log）。
        findViewById<LinearLayout>(R.id.rowBootTrace).setOnClickListener {
            val snap = BootTrace.snapshot.value
            val body = StringBuilder()
            if (snap.startedAt <= 0) {
                body.append(getString(R.string.boot_trace_none))
            } else {
                body.append(getString(R.string.boot_step_runtime)).append(": ")
                body.append(String.format(java.util.Locale.US, "%.1fs", snap.elapsedMs() / 1000.0))
                body.append(if (snap.running) {
                    " (" + getString(R.string.boot_trace_running) + ")"
                } else {
                    ""
                })
                body.append("\n\n")
                val ins = snap.install
                if (ins.totalFiles > 0) {
                    body.append(getString(R.string.splash_files, ins.doneFiles, ins.totalFiles))
                    body.append("\n\n")
                }
                snap.steps.forEach { s ->
                    body.append(String.format(java.util.Locale.US, "%6dms  %-24s %s%n",
                        s.durationMs, s.key,
                        String.format(java.util.Locale.US, "(%dms)", s.atMs)))
                }
                if (snap.failure != null) {
                    body.append("\n").append(snap.failure)
                }
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.boot_trace_title)
                .setMessage(body.toString().trimEnd())
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.file_editor_copy_path) { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "dsh-boot",
                            BootTrace.readLogTail(this, 32 * 1024),
                        ),
                    )
                    Toast.makeText(this, R.string.file_editor_path_copied, Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // —— 其他：重启引擎 ——
        // restart() 自身立即返回（内部串行 + 先置「启动中」），不需要再套线程；
        // 状态栏/主界面会随 state 流转自动刷新
        findViewById<LinearLayout>(R.id.rowRestart).setOnClickListener {
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(android.R.string.ok), Toast.LENGTH_SHORT).show()
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

    /** 导出行的副标题（进度 / 结果都写在这里，与「对话恢复」行同一交互） */
    private var exportSub: TextView? = null
    /**
     * 常用配置文件选择器（v1.4.29，issue #25）。
     *
     * 只列「改坏了能恢复 / 有意义」的文件 —— 全部在应用私有目录内，
     * FileEditorActivity 的路径检查天然放行：
     *  · AGENTS.md        用户给 AI 的长期指令（自定义指令页也写这个）
     *  · 工作区注册表      workspace.json（列表里看不到会话时排查）
     *  · 插件 profile      profiles/web/package.json（加错插件起不来时手改）
     */
    private fun showConfigFilePicker() {
        val home = app.dsh.mobile.engine.EngineConfig.dshHome(this)
        val candidates: List<Triple<String, java.io.File, String>> = listOf(
            Triple("AGENTS.md", java.io.File(home, "AGENTS.md"), "Instructions given to the AI"),
            Triple("workspace.json", java.io.File(home, "storages/workspace.json"), "Workspace registry"),
            Triple("profile package.json", java.io.File(home, "profiles/web/package.json"), "Installed plugin list"),
        ).filter { it.second.isFile }

        if (candidates.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.config_files_none, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { it.first + "\n" + it.third }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setting_config_files)
            .setItems(labels) { d, which ->
                d.dismiss()
                val f = candidates[which].second
                runCatching {
                    startActivity(
                        android.content.Intent(this, FileEditorActivity::class.java)
                            .putExtra(FileEditorActivity.EXTRA_PATH, f.absolutePath),
                    )
                }.onFailure {
                    android.widget.Toast.makeText(
                        this, getString(R.string.config_files_failed, it.message ?: "?"),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 导入行的副标题（v1.4.29） */
    private var importSub: TextView? = null
    @Volatile private var importing = false
    @Volatile private var exporting = false

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) runImport(uri)
            return
        }
        if (requestCode != REQ_EXPORT) return
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) runExport(uri)
    }

    /**
     * 后台导入（v1.4.29，issue #31）。合并式：不覆盖现有文件，只补缺失的。
     *
     * 先把 URI 复制到私有缓存再解包 —— ContentResolver 的流不可 seek，
     * 而 ZipFile 需要随机访问。
     */
    private fun runImport(uri: Uri) {
        importing = true
        importSub?.text = getString(R.string.import_preparing)
        Thread({
            val result = runCatching {
                val cache = java.io.File(cacheDir, "restore-${System.currentTimeMillis()}.zip")
                try {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        cache.outputStream().use { outs -> ins.copyTo(outs, 128 * 1024) }
                    } ?: throw java.io.IOException("cannot open $uri")
                    var lastShown = 0L
                    app.dsh.mobile.engine.DataExport.import(this, cache) { done, total ->
                        val now = System.currentTimeMillis()
                        if (done == total || now - lastShown >= 200) {
                            lastShown = now
                            runOnUiThread {
                                importSub?.text = getString(R.string.import_progress, done, total)
                            }
                        }
                    }
                } finally {
                    runCatching { cache.delete() }
                }
            }
            runOnUiThread {
                importing = false
                result.onSuccess { s ->
                    importSub?.text = getString(
                        R.string.import_done, s.sessionFiles, s.workspaceFiles, s.overwritten,
                    )
                    if (s.sessionFiles > 0 || s.workspaceFiles > 0) {
                        android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.import_done_title)
                            .setMessage(getString(R.string.import_done_body, s.sessionFiles + s.workspaceFiles))
                            .setPositiveButton(R.string.import_done_restart) { _, _ ->
                                (application as DshApp).supervisor.restart()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }.onFailure { e ->
                    importSub?.text = getString(
                        R.string.import_failed, e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }, "data-import").apply { isDaemon = true }.start()
    }

    /**
     * 后台导出到 [uri]。失败时删掉已创建的半成品文件，避免用户误以为备份可用。
     * 详细过程与失败原因见 engine.log 的 `[export]` 行（DataExport 负责写）。
     */
    private fun runExport(uri: Uri) {
        exporting = true
        exportSub?.text = getString(R.string.export_preparing)
        Thread({
            var lastShown = 0L
            val result = runCatching {
                val out = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("cannot open $uri")
                out.use { stream ->
                    app.dsh.mobile.engine.DataExport.export(this, stream) { done, total ->
                        // 节流：最多每 200ms 刷新一次文字，最后一个文件必刷
                        val now = System.currentTimeMillis()
                        if (done == total || now - lastShown >= 200) {
                            lastShown = now
                            runOnUiThread { exportSub?.text = getString(R.string.export_progress, done, total) }
                        }
                    }
                }
            }
            if (result.isFailure) {
                runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            }
            runOnUiThread {
                exporting = false
                result.onSuccess { s ->
                    val done = getString(R.string.export_done, s.sessionFiles, s.workspaceFiles)
                    exportSub?.text = done
                    val msg = buildString {
                        append(done).append("\n\n").append(getString(R.string.export_no_creds))
                        if (!s.hasRegistry) append("\n\n").append(getString(R.string.export_no_registry))
                    }
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.setting_export_data)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }.onFailure { e ->
                    val failed = getString(R.string.export_failed, e.message ?: e.javaClass.simpleName)
                    exportSub?.text = failed
                    // 失败也弹窗：只改行内小字很容易看漏（真机实测反馈）
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.setting_export_data)
                            .setMessage(failed + "\n\n" + getString(R.string.export_log_hint))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }, "data-export").start()
    }

    /** 从包管理器读取 versionName（AGP 8+ 默认关闭 BuildConfig，避免依赖它） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

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
        // 从系统无障碍设置返回后刷新状态；Shizuku 授权结果回调也会刷新
        refreshAll()
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
        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        orientMode = OrientationPrefs.get(this)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        findViewById<TextView>(R.id.valLandscape).text = getString(orientLabel(orientMode))
        findViewById<TextView>(R.id.valScale).text = "$pageScale%"
        findViewById<TextView>(R.id.valPriv).text = privLabel(Privilege.getMode(this))
        findViewById<TextView>(R.id.valRootStatus).let {
            val rootOk = Privilege.rootAvailableMinimal()
            it.text = getString(
                if (rootOk) R.string.setting_root_status_yes else R.string.setting_root_status_no
            )
            it.setTextColor(if (rootOk) getColor(R.color.success) else getColor(R.color.text_secondary))
        }
        refreshShizuku()
        refreshAccess()
        refreshStorageRow()
        refreshTtsRow()
        refreshNotifRow()
        refreshMicRow()
        refreshExt()
        refreshPetRow()
        refreshPluginKill()
        // 缩放副标题文案无需变；图标着色按打开时状态由静态 XML 决定
    }

    /** 扩展分组：桌宠入口状态（未装桥插件 / 已就绪 / 运行中 / 缺权限）。 */
    private fun refreshPetRow() {
        val v = findViewById<TextView>(R.id.valPet)
        val plugin = PetPluginDetector.detect(this)
        val running = PetOverlayService.isRunning()
        val canDraw = Settings.canDrawOverlays(this)
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

    /**
     * 插件应急总开关的状态刷新（v1.4.27）。
     *
     * 标题随状态变：正常时是「停用全部第三方插件」，停用后变成「恢复第三方插件」。
     * 状态本身由意图日志决定（[PluginKillSwitch.status]），不是引擎上报 ——
     * 这样即使引擎正在重启、甚至起不来，这一行也永远显示真实状态。
     */
    private fun refreshPluginKill() {
        val title = findViewById<TextView>(R.id.valPluginKill) ?: return
        val sub = findViewById<TextView>(R.id.txtPluginKillSub) ?: return
        val st = runCatching { PluginKillSwitch.status(this) }.getOrNull() ?: return
        if (st.active) {
            title.text = getString(R.string.setting_plugin_kill_restore)
            // v1.4.28：有单独恢复的白名单时显示进度，引导去插件中心逐个启用
            sub.text = if (st.allow.isNotEmpty()) {
                getString(
                    R.string.setting_plugin_kill_restore_sub_partial,
                    st.allow.size, st.disabled.size,
                )
            } else {
                getString(R.string.setting_plugin_kill_restore_sub, st.disabled.size)
            }
            sub.setTextColor(getColor(R.color.warning))
        } else {
            title.text = getString(R.string.setting_plugin_kill)
            sub.text = getString(R.string.setting_plugin_kill_sub, st.thirdParty.size)
            sub.setTextColor(getColor(R.color.text_secondary))
        }
    }

    /** 确认框：把将被停用/恢复的包名列出来，别让用户盲点 */
    private fun confirmKillSwitch() {
        val st = runCatching { PluginKillSwitch.status(this) }.getOrNull() ?: return
        val restore = st.active
        val list = if (restore) st.disabled else st.thirdParty
        if (list.isEmpty()) {
            Toast.makeText(this, getString(R.string.plugin_kill_none), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (restore) R.string.plugin_kill_restore_title
                else R.string.plugin_kill_disable_title,
            )
            .setMessage(
                getString(
                    if (restore) R.string.plugin_kill_restore_body
                    else R.string.plugin_kill_disable_body,
                    list.size,
                    summarizePackages(list),
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> applyKillSwitch(restore) }
            .show()
    }

    /** 包名列表：超过 8 个只列前 8 个 + 「等 N 个」，避免对话框撑满屏幕 */
    private fun summarizePackages(list: List<String>): String {
        val head = list.take(8).joinToString("\n") { "· $it" }
        return if (list.size <= 8) head
        else head + "\n" + getString(R.string.plugin_kill_more, list.size - 8)
    }

    /**
     * 落意图 + 重启引擎。
     *
     * ⚠️ 这里**只写意图日志**，不直接改 `profiles/web/package.json` —— 引擎运行中
     * 会把自己内存里的 profile 状态写回去，覆盖我们的改动；真正的数组改写由
     * 监督器在下次 spawn 前完成（[PluginKillSwitch.enforceBeforeBoot]）。
     */
    private fun applyKillSwitch(restore: Boolean) {
        val outcome =
            if (restore) PluginKillSwitch.requestEnable(this)
            else PluginKillSwitch.requestDisable(this, "settings")
        if (!outcome.ok) {
            Toast.makeText(
                this,
                getString(R.string.plugin_kill_failed, outcome.message),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        Toast.makeText(this, getString(R.string.plugin_kill_restarting), Toast.LENGTH_SHORT).show()
        (application as DshApp).supervisor.restart()
        refreshPluginKill()
    }

    /** 扩展中心入口：显示已激活扩展数（>0 变绿提示已并入引擎） */
    private fun refreshExt() {
        val count = ExtensionManager(this).activeCount()
        val v = findViewById<TextView>(R.id.valExt)
        v.text = getString(R.string.setting_ext_active_count, count)
        v.setTextColor(if (count > 0) getColor(R.color.success) else getColor(R.color.text_secondary))
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
        v.setTextColor(if (granted) getColor(R.color.success) else getColor(R.color.warning))
    }

    /** 权限中心：TTS 引擎可用性（列出 TextToSpeechService 提供方，不触发绑定） */
    private fun refreshTtsRow() {
        val n = TtsManager.engineCount(this)
        val v = findViewById<TextView>(R.id.valTts)
        v.text = if (n == 0) getString(R.string.tts_missing) else getString(R.string.tts_engines, n)
        v.setTextColor(if (n > 0) getColor(R.color.success) else getColor(R.color.text_secondary))
    }

    /** 权限中心：通知权限（Android 13+ 运行时授权；低版本默认持有） */

    private fun refreshNotifRow() {
        val granted = if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        val v = findViewById<TextView>(R.id.valNotif)
        v.text = getString(if (granted) R.string.notif_granted else R.string.notif_denied)
        v.setTextColor(if (granted) getColor(R.color.success) else getColor(R.color.text_secondary))
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
        v.setTextColor(if (granted) getColor(R.color.success) else getColor(R.color.text_secondary))
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
                v.setTextColor(getColor(R.color.success))
            }
            Privilege.shizukuServerRunning() -> {
                // v1.2.158 隐私修复：这里**不再**自动请求（拒绝后回调又会回到此处 →
                // 死循环弹窗）。改为展示状态 + 允许点击该行**显式**重新申请。
                v.text = getString(R.string.setting_shizuku_request)
                v.setTextColor(getColor(R.color.warning))
                v.isClickable = true
                v.setOnClickListener {
                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this@SettingsActivity, explicit = true)
                }
            }
            else -> {
                v.text = getString(R.string.setting_shizuku_absent)
                v.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    /** 无障碍状态刷新 */
    private fun refreshAccess() {
        val on = DshAccessibilityService.isEnabled()
        findViewById<TextView>(R.id.subAccess).text = getString(
            if (on) R.string.setting_access_sub_on else R.string.setting_access_sub_off
        )
        findViewById<TextView>(R.id.valAccess).text =
            getString(if (on) R.string.state_on else R.string.state_off)
        findViewById<TextView>(R.id.valAccess).setTextColor(
            if (on) getColor(R.color.success) else getColor(R.color.text_secondary)
        )
    }

    private fun privLabel(mode: PrivMode): String = when (mode) {
        PrivMode.ROOT -> getString(R.string.priv_root)
        PrivMode.SHIZUKU -> getString(R.string.priv_shizuku)
        PrivMode.NORMAL -> getString(R.string.priv_normal)
    }

    // ================= 显示：屏幕朝向（v1.2.120 三态） =================

    private fun orientLabel(mode: OrientationMode): Int = when (mode) {
        OrientationMode.PORTRAIT -> R.string.setting_orient_portrait
        OrientationMode.LANDSCAPE -> R.string.setting_orient_landscape
        OrientationMode.AUTO -> R.string.setting_orient_auto
    }

    /**
     * 屏幕朝向选择：竖屏 / 横屏 / 自动 单选列表。
     *
     * 横屏 = 锁横屏 + 桌面布局（UA/视口/缩放手势）；自动 = 跟随设备旋转，
     * 桌面布局仅在实际横屏时生效。落库由 MainActivity.onResume 检测后统一应用。
     */
    private fun showOrientDialog() {
        val modes = listOf(
            OrientationMode.PORTRAIT,
            OrientationMode.LANDSCAPE,
            OrientationMode.AUTO,
        )
        val labels = modes.map { getString(orientLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_landscape))
            .setSingleChoiceItems(labels, modes.indexOf(orientMode)) { dlg, which ->
                val picked = modes[which]
                dlg.dismiss()
                if (picked == orientMode) return@setSingleChoiceItems
                orientMode = picked
                OrientationPrefs.set(this, picked)
                // v1.4.18：立即生效（平板用户当场就能看到双栏/单列切换）
                OrientationPrefs.applyTo(this)
                findViewById<TextView>(R.id.valLandscape).text = getString(orientLabel(picked))
                Toast.makeText(
                    this,
                    getString(orientLabel(picked)) + " · " + getString(R.string.setting_applied_now),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .showStyled()
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
    // ================= 显示：页面缩放 =================

    /**
     * 页面缩放对话框：SeekBar 滑条连续拖动（范围 50–150，步长 5），实时显示百分比。
     * 落库后 MainActivity onResume 读取并 reload 生效。
     */
    private fun showScaleDialog() {
        val value = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            text = "$pageScale%"
            setPadding(0, dp(8), 0, dp(4))
        }
        val bar = SeekBar(this).apply {
            max = (MAX_PAGE_SCALE - MIN_PAGE_SCALE) / SCALE_STEP   // 索引 0..20 → 50..150 步长5
            progress = (pageScale - MIN_PAGE_SCALE) / SCALE_STEP
            progressTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@SettingsActivity).argb)
            thumbTintList = android.content.res.ColorStateList.valueOf(Accent.current(this@SettingsActivity).argb)
            setPadding(dp(24), 0, dp(24), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "${MIN_PAGE_SCALE + progress * SCALE_STEP}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(value)
            addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scale_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = MIN_PAGE_SCALE + bar.progress * SCALE_STEP
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                findViewById<TextView>(R.id.valScale).text = "$pageScale%"
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
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
        const val REQ_EXPORT = 4204
        const val REQ_IMPORT = 4205
        const val PREFS_UI = DshApp.PREFS_UI
        const val KEY_PAGE_SCALE = "page_scale"
        const val KEY_LANDSCAPE = "landscape"
        const val KEY_LAN_WEB = DshApp.KEY_LAN_WEB

        const val DEFAULT_PAGE_SCALE = 90
        const val MIN_PAGE_SCALE = 50
        const val MAX_PAGE_SCALE = 150
        const val SCALE_STEP = 5

        /** 双栏断点：Material 3 的 expanded 宽度（840dp） */
        const val WIDE_LAYOUT_MIN_WIDTH_DP = 840

        /** 宽窗内容最大宽度：两栏各约 516dp，平板横屏不拉满 */
        const val WIDE_CONTENT_MAX_WIDTH_DP = 1080

        /** 窄窗内容最大宽度：手机/平板竖屏保持易读行宽 */
        const val NARROW_CONTENT_MAX_WIDTH_DP = 680
    }

    /** 给设置项的当前值文字上强调色（v1.4.23，配色可切换后动态取色） */
    private fun TextView.deprecatedAccent() {
        setTextColor(Accent.current(this.context).argb)
    }

    // ───────────────────────────────────────────────────────────────────
    // v1.4.23：配色方案 / 布局样式 / 快捷栏样式
    // ───────────────────────────────────────────────────────────────────

    /**
     * 外观主题：深色 / 浅色 / 跟随系统。
     *
     * 落库到 `ThemePref`（SharedPreferences `dsh_ui.theme_mode`），与引导页共用。
     * 选完必须 `recreate()`：`ThemeManager.wrap()` 在 `attachBaseContext` 阶段
     * 改 `uiMode`，只有重建 Activity 才会重新取 values / values-night 资源。
     * 主界面不用管——`MainActivity.onResume` 会比对 `appliedTheme` 并自行重建。
     */
    private fun showThemeDialog() {
        val modes = ThemeMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(ThemePref.get(this))
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setting_theme)
            .setSingleChoiceItems(labels, checked) { d, which ->
                ThemePref.set(this, modes[which])
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 配色方案选择：5 种强调色，默认项目原本的天蓝 */
    private fun showAccentDialog() {
        val modes = Accent.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(Accent.current(this))
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setting_accent)
            .setSingleChoiceItems(labels, checked) { d, which ->
                Accent.set(this, modes[which])
                d.dismiss()
                // 配色影响整个原生壳（含本页所有强调色），重建本页最省事且无状态丢失风险
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 设置页布局选择。三种模式的实现差异：
     *  - LIST：现状（单列分组卡片，`columnPrimary` 内按原顺序排）
     *  - CARD：每个分区一张独立卡片，分区之间加大间距（视觉分块更强）
     *  - GRID：每个分区转成 2 列图标网格，只留图标 + 标题，一屏看全部
     *
     * 实现上不复制任何条目：三种模式都来自同一份 XML 视图。CARD 通过调整
     * 分区卡片之间的外边距实现；GRID 通过把每个分区卡片的行改为
     * `weight=1` 的两列流式布局实现（行内左图标+标题保留，描述在网格下隐藏）。
     */
    private fun showLayoutModeDialog() {
        val modes = SettingsLayoutMode.entries
        val labels = modes.map { getString(it.labelRes) }.toTypedArray()
        val checked = modes.indexOf(SettingsLayoutMode.current(this))
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setting_layout_mode)
            .setSingleChoiceItems(labels, checked) { d, which ->
                SettingsLayoutMode.set(this, modes[which])
                d.dismiss()
                // 重建本页：布局改动涉及视图层级重排，重新 inflate 最可靠
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
     * ⚠️ 不能一律隐藏文本列的第 2 个子节点：部分行（如「所有文件访问」）把当前值
     * `valStorage` 放在文本列内，直接按索引隐藏会把值也藏掉，标题被挤成省略号。
     * 判据：**小字号（≤12sp）的那一个**才是说明文字；当前值通常 14sp 且有 id。
     */
    private fun setDescriptionsVisible(row: LinearLayout, visible: Boolean) {
        for (i in 0 until row.childCount) {
            val col = row.getChildAt(i) as? LinearLayout ?: continue
            if (col.childCount < 2) continue
            for (k in 0 until col.childCount) {
                val tv = col.getChildAt(k) as? TextView ?: continue
                // 说明文字 = 小字号（≤12sp）**且**不是第一个子节点（第一个是标题）。
                // 不再用 id 判断"是否当前值"：部分描述 TextView 也有 id。
                val small = tv.textSize / resources.displayMetrics.scaledDensity <= 12.5f
                val notTitle = k > 0
                if (notTitle && small) {
                    tv.visibility = if (visible) View.VISIBLE else View.GONE
                }
            }
        }
    }


    /**
     * 网格模式下行内文字压缩（v1.4.23）：标题单行省略、当前值不换行、
     * 图标缩小，保证 2 列宽度下仍可读（实测不处理会逐字竖排）。
     */
    private fun compactRowForGrid(row: LinearLayout) {
        row.setPadding(dp(8), dp(10), dp(8), dp(10))
        for (i in 0 until row.childCount) {
            when (val c = row.getChildAt(i)) {
                is android.widget.ImageView -> {
                    (c.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.width = dp(28); it.height = dp(28); it.marginStart = 0
                        c.layoutParams = it
                    }
                    c.setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                is LinearLayout -> {
                    // 文本列：占满剩余宽度
                    (c.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.width = 0; it.weight = 1f; it.marginStart = dp(8)
                        c.layoutParams = it
                    }
                    for (k in 0 until c.childCount) {
                        (c.getChildAt(k) as? TextView)?.apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setSingleLine(true)
                        }
                    }
                }
                is TextView -> {
                    c.maxLines = 1
                    c.ellipsize = android.text.TextUtils.TruncateAt.END
                    c.setSingleLine(true)
                    (c.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.width = LinearLayout.LayoutParams.WRAP_CONTENT
                        it.marginStart = dp(4)
                        c.layoutParams = it
                    }
                }
                else -> Unit
            }
        }
    }

}

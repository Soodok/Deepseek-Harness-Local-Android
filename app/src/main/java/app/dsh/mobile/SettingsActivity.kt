package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.ChatRecovery
import app.dsh.mobile.engine.ExtensionManager
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.engine.SelfCheck
import app.dsh.mobile.engine.TtsManager
import app.dsh.mobile.engine.LanWebProxy

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
class SettingsActivity : Activity() {

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
    @Volatile private var exporting = false

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT) return
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) runExport(uri)
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
     * 平板/横屏响应式布局（v1.4.18）：
     * - 宽窗（>= 840dp）：两栏 —— 左「显示 + 权限中心」，右「扩展 + 其他」；
     * - 窄窗：单列，顺序不变（显示 → 权限中心 → 扩展 → 其他）。
     *
     * 只改容器方向/权重，不重建页面。限宽交给 [MaxWidthLinearLayout]，
     * 避免平板横屏下单列内容被拉成超宽行。
     */
    private fun applyResponsiveLayout() {
        val wide = resources.configuration.screenWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP
        val columns = findViewById<LinearLayout>(R.id.settingsColumns)
        val primary = findViewById<LinearLayout>(R.id.columnPrimary)
        val secondary = findViewById<LinearLayout>(R.id.columnSecondary)
        findViewById<MaxWidthLinearLayout>(R.id.settingsContentWidth).maxWidthDp =
            if (wide) WIDE_CONTENT_MAX_WIDTH_DP else NARROW_CONTENT_MAX_WIDTH_DP

        if (wide) {
            columns.orientation = LinearLayout.HORIZONTAL
            primary.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginEnd = dp(16) }
            secondary.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
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
        }
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
            it.setTextColor(if (rootOk) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
        }
        refreshShizuku()
        refreshAccess()
        refreshStorageRow()
        refreshTtsRow()
        refreshNotifRow()
        refreshMicRow()
        refreshExt()
        // 缩放副标题文案无需变；图标着色按打开时状态由静态 XML 决定
    }

    /** 扩展中心入口：显示已激活扩展数（>0 变绿提示已并入引擎） */
    private fun refreshExt() {
        val count = ExtensionManager(this).activeCount()
        val v = findViewById<TextView>(R.id.valExt)
        v.text = getString(R.string.setting_ext_active_count, count)
        v.setTextColor(if (count > 0) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
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
        v.setTextColor(if (granted) 0xFF6EE7B7.toInt() else 0xFFFFB74D.toInt())
    }

    /** 权限中心：TTS 引擎可用性（列出 TextToSpeechService 提供方，不触发绑定） */
    private fun refreshTtsRow() {
        val n = TtsManager.engineCount(this)
        val v = findViewById<TextView>(R.id.valTts)
        v.text = if (n == 0) getString(R.string.tts_missing) else getString(R.string.tts_engines, n)
        v.setTextColor(if (n > 0) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
    }

    /** 权限中心：通知权限（Android 13+ 运行时授权；低版本默认持有） */

    private fun refreshNotifRow() {
        val granted = if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        val v = findViewById<TextView>(R.id.valNotif)
        v.text = getString(if (granted) R.string.notif_granted else R.string.notif_denied)
        v.setTextColor(if (granted) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
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
        v.setTextColor(if (granted) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
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
                v.setTextColor(0xFF6EE7B7.toInt())
            }
            Privilege.shizukuServerRunning() -> {
                // v1.2.158 隐私修复：这里**不再**自动请求（拒绝后回调又会回到此处 →
                // 死循环弹窗）。改为展示状态 + 允许点击该行**显式**重新申请。
                v.text = getString(R.string.setting_shizuku_request)
                v.setTextColor(0xFFFFB74D.toInt())
                v.isClickable = true
                v.setOnClickListener {
                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this@SettingsActivity, explicit = true)
                }
            }
            else -> {
                v.text = getString(R.string.setting_shizuku_absent)
                v.setTextColor(0xFF8A94A3.toInt())
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
            if (on) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt()
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
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF7DD3FC.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF7DD3FC.toInt())
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
                    setTextColor(0xFFFFFFFF.toInt())
                })
                if (sub.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(0xFF8A94A3.toInt())
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

}

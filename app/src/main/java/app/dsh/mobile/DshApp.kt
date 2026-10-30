package app.dsh.mobile

import android.app.Application
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.ExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DshApp : Application() {

    /** 全局协程域：监督器生命周期独立于任何 Activity/Service */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 全局唯一监督器单例，Activity 与 Service 共享状态 */
    lateinit var supervisor: EngineSupervisor
        private set

    /** 全局唯一扩展管理器单例：扩展中心 UI 与 AI 通道（AgentBridge）共享下载任务状态 */
    val extensionManager: ExtensionManager by lazy { ExtensionManager(this) }

    override fun onCreate() {
        super.onCreate()
        // 让插件兼容性检查能读到内置引擎版本（issue #66：判定插件要求的引擎
        // 版本是否高于我们内置的版本）。用 applicationContext，不泄漏 Activity。
        app.dsh.mobile.engine.NativePluginCheck.attachContext(this)
        // ⚠️ v1.2.110：全局崩溃捕获 —— 用户反馈「进程异常退出」（1.2.97 升级后 ×2 人，
        // Android 16 未复现，疑为 Android 17 兼容问题）。没有现场日志无法定位，
        // 现在任何 Java 崩溃都会落盘 `filesDir/crash-last.txt`（时间、线程、堆栈），
        // 用户或支持把该文件发回来即可定位。若该文件为空，说明是 native 崩溃
        // （tombstone 在系统日志里，需 logcat 提供）。
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val f = java.io.File(filesDir, "crash-last.txt")
                f.writeText(
                    buildString {
                        appendLine("time: ${System.currentTimeMillis()}")
                        appendLine("thread: ${thread.name}")
                        appendLine(android.util.Log.getStackTraceString(throwable))
                    },
                )
                android.util.Log.e("DshApp", "crash captured -> ${f.absolutePath}")
            }
            prev?.uncaughtException(thread, throwable)
        }
        supervisor = EngineSupervisor(this)
    }

    companion object {
        /** 设置页 UI 偏好（页面缩放/横竖屏），与 SettingsActivity 共用 */
        const val PREFS_UI = "dsh_ui"

        /**
         * max+ 的模型来源（v1.2.127）：选中的凭证记录键名（如 DEEPSEEK_API_KEY）。
         * 空串 = 未显式选择（按 CredentialProbe.defaultRef 兜底：优先 DeepSeek 官方）。
         */
        const val KEY_A11Y_MAX_PROVIDER = "a11y_max_provider"

        fun a11yMaxProvider(context: android.content.Context): String =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getString(KEY_A11Y_MAX_PROVIDER, "") ?: ""

        /** 局域网访问 WebUI（v1.2.160）：让同网段设备用原生界面连上本机引擎。默认关闭。 */
        const val KEY_LAN_WEB = "lan_web_access"
        private const val KEY_LAN_WEB_PW = "lan_web_password"
        /** 验证码是否处于"待展示"状态（只显示一次，再进设置不再显示） */
        private const val KEY_LAN_WEB_PW_PENDING = "lan_web_pw_pending"

        /** 用户显式退出标记（跨进程存活；见 [userExitedExplicitly] 的说明） */
        private const val KEY_USER_EXITED = "user_exited_explicitly"

        fun lanWebEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAN_WEB, false)

        fun setLanWebEnabled(context: android.content.Context, on: Boolean) {
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LAN_WEB, on).apply()
        }

        /**
         * 用户是否在通知栏点过「退出」，且此后没有主动打开过 App。
         *
         * ## 为什么必须持久化（v1.4.0，用户反馈「退出后 root 模式持续空转」）
         * `EngineSupervisor.userStop` 只是内存标志，进程一死就没了。而退出流程
         * 恰恰会结束进程（见 `killAppProcess`）—— 于是：
         *
         *   退出 → 进程结束 → 无障碍服务仍被系统拉起（它是独立的系统级组件）
         *        → 看门狗读到 userStop = false（新进程、内存标志已重置）
         *        → **把引擎重新拉起来** → 空转
         *
         * 落盘之后，新进程也能知道「用户是主动退出的，别自作主张复活」。
         * 用户下一次主动打开 App（MainActivity.onCreate）会清掉它。
         */
        fun userExitedExplicitly(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_EXITED, false)

        fun setUserExitedExplicitly(context: android.content.Context, exited: Boolean) {
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USER_EXITED, exited).apply()
        }

        /** 访问密码：首次生成后固定（8 位十六进制） */
        fun lanWebPassword(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
            val cur = prefs.getString(KEY_LAN_WEB_PW, "") ?: ""
            if (cur.length >= 6) return cur
            return regenerateLanWebPassword(context)
        }

        /**
         * 生成新的 6 位数字验证码（v1.2.161，用户要求）。
         * 每次「开启局域网访问」都重新生成 —— 不复用旧码，避免长期有效的固定口令。
         */
        fun regenerateLanWebPassword(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
            // ⚠️ v1.4.46 安全审查 H1：6 位纯数字只有 20 bit 熵，且代理端曾无速率
            // 限制（任何同 WiFi 设备 / 同机任意 App 走 127.0.0.1:3090 都能爆破，
            // 破译即拿到完整引擎会话：历史对话、profiles 里的 API key、工作区文件、
            // Agent 的 shell/读屏/点击能力）。改为 8 位、去掉易混淆字符、混合字母数字
            // （约 40 bit），配合代理端失败退避与锁定。
            //
            // 仍保持「一屏可读、手输不痛苦」：分组显示由 UI 负责，这里是连写。
            val alphabet = "23456789abcdefghijkmnpqrstuvwxyz"
            val pw = (1..8).map { alphabet.random() }.joinToString("")
            // 同时标记"待展示"：新码生成后应在设置页显示一次（并自动复制）
            prefs.edit().putString(KEY_LAN_WEB_PW, pw)
                .putBoolean(KEY_LAN_WEB_PW_PENDING, true).apply()
            return pw
        }

        /** 是否还需要在设置页显示验证码：只在"刚生成、还没展示过"时为真（只显示一次） */
        fun lanWebPwPending(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAN_WEB_PW_PENDING, false)

        fun markLanWebPwShown(context: android.content.Context) {
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LAN_WEB_PW_PENDING, false).apply()
        }


    }
}

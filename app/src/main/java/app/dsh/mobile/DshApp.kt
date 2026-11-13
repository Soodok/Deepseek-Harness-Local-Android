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
        // v1.6.11：容器内 DNS 代理必须随进程启动，不能只挂在 readiness()。
        //
        // 为什么：readiness() 只在 `EngineModeSelector.resolve()` 判定要走 Linux
        // 模式、且引擎**尚未运行**时才被调用。实测遇到的坑：App 被系统/省电策略
        // 杀掉后重启，引擎窗口还在（引擎进程独立存活），readiness 不再执行 →
        // 代理没起、53 端口没人监听 → 容器的 `/etc/resolv.conf` 指向 127.0.0.1:53
        // 却无人应答，DNS 全废，而日志里只看到 `nslookup` 超时。
        //
        // 放在 Application.onCreate 的好处：进程一起就有代理；幂等（start() 内部
        // 判 running），重复调用无副作用。用后台线程是因为可能要走一次 su 调
        // ip_unprivileged_port_start，不能阻塞主线程。
        Thread({
            runCatching {
                // v1.7.13：不再按 pkgManager 分流。代理是给**容器内一切解析**用的
                // （Alpine 的 musl 走 loader 改写，Ubuntu 的 glibc 走 use-vc 不依赖
                // 它，但 LinuxPackageManager 的 apt 流程仍可能要查 DNS——上游源是
                // 域名不是 IP），而且 start() 幂等且失败无害，无条件起成本更低、
                // 分支更少。旧判据会让装了 Ubuntu 的设备进程一启动就没有代理，
                // 一旦引擎窗口竞争把 socket 关掉，连兜底都没有。
                app.dsh.mobile.engine.LinuxDnsProxy.start(this)
            }.onFailure {
                android.util.Log.w("DshApp", "dns proxy prestart failed: ${it.message}")
            }
        }, "dsh-dns-prestart").apply { isDaemon = true }.start()
        // v1.6.2：/dev/settings 改完设置要让界面立刻生效（用户选了「直接生效」）。
        // 注册当前 Activity 追踪，供设置端点调 recreate()。
        CurrentActivity.install(this)
        // 进程一起来就把夜间模式与偏好对齐：md3Dialog() 造的是 AppCompat 的
        // AlertDialog，它的 delegate 会按「自己知道的」夜间模式重写进程内共享的
        // Resources.uiMode（见 ThemeManager.syncAppCompatNight 的说明）。
        // 放在这里而不是只放 ThemedActivity.onCreate：本仓还有几个直接继承
        // Activity 的页面（LogViewerActivity / ShareInboxActivity / TerminalActivity）
        // 也会弹 AlertDialog，同样需要这份对齐。
        ThemeManager.syncAppCompatNight(this)
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

        /** AI 触摸点开关（v1.7.3）；缺省 true，见 [touchMarkersEnabled]。 */
        private const val KEY_TOUCH_MARKERS = "a11y_touch_markers"

        fun lanWebEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LAN_WEB, false)

        fun setLanWebEnabled(context: android.content.Context, on: Boolean) {
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LAN_WEB, on).apply()
        }

        /**
         * AI 触摸点/滑动轨迹是否显示（v1.7.3，用户要求能在设置里关掉）。
         *
         * 默认**开**：这是用户看见「AI 到底点了哪儿」的唯一途径，默认关掉等于把
         * 透明度藏起来。但录屏、演示、或者单纯嫌它挡视线时，需要一个开关 ——
         * 关掉只是不画点，手势本身照常注入（不影响任何动作）。
         */
        fun touchMarkersEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_TOUCH_MARKERS, true)

        fun setTouchMarkersEnabled(context: android.content.Context, on: Boolean) {
            context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TOUCH_MARKERS, on).apply()
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

        /**
         * 访问密码：首次生成后固定（6 位纯数字验证码）。
         *
         * 历史：v1.2.161 起是 6 位数字；v1.4.46 安全审查 H1 因「代理端曾无速率限制，
         * 同 WiFi 或同机任意 App 走 127.0.0.1:3090 可爆破」改成 8 位混合字母数字（~40 bit）。
         * v1.5.0（宝宝要求）改回 6 位数字 —— 手输体验优先。
         *
         * ⚠️ 熵只有 ~20 bit，抗爆破**完全依赖** `lan-web-proxy.mjs` 的失败退避：
         * 同源连续 5 次失败即锁 10 分钟，且成功后清空计数。改回 6 位的前提是那段
         * 逻辑必须存在且生效，删掉它等于把口令裸奔在局域网上。
         */
        fun lanWebPassword(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
            val cur = prefs.getString(KEY_LAN_WEB_PW, "") ?: ""
            // 旧版本可能存着 8 位字母数字码：长度够但不是 6 位数字，得换掉，
            // 否则「6 位验证码」这个承诺对老用户不成立。
            if (cur.length == LAN_CODE_LEN && cur.all { it.isDigit() }) return cur
            return regenerateLanWebPassword(context)
        }

        /** 验证码长度：6 位数字（4 位太容易被同网段随手试出来）。 */
        const val LAN_CODE_LEN = 6

        /**
         * 生成新的 6 位数字验证码。
         * 每次「开启局域网访问」都重新生成 —— 不复用旧码，避免长期有效的固定口令。
         */
        fun regenerateLanWebPassword(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE)
            // SecureRandom 而非 Random：后者种子可预测，攻击者能从历史验证码反推下一个。
            val rnd = java.security.SecureRandom()
            val pw = (1..LAN_CODE_LEN).map { rnd.nextInt(10) }.joinToString("")
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

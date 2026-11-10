package app.dsh.mobile.engine

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网访问 WebUI 的反向代理（v1.2.160）：让同网段设备用**原生 WebUI 界面**连上手机里的引擎。
 *
 * ## 为什么需要它
 * 引擎只绑 127.0.0.1（实测 PC 连手机 3080 不可达），而改成绑 0.0.0.0 也不行——
 * 浏览器访问 http://192.168.x.x 属于**非安全上下文**，会连撞四道门：
 *   ① crypto.randomUUID 不存在（仅安全上下文可用）；
 *   ② 前端 isLoopbackHostname(pageLocation.hostname) 判假 → connection.isLoopback=false
 *      → 设置页持久化降级为 memory、设置文档编辑不可用；
 *   ③ /api 的 Origin/Host 同源检查；
 *   ④ WebSocket 升级必须被转发（对话流靠它推送）。
 * 正解：app 内起反向代理，浏览器访问 http://<手机IP>:3090，代理打补丁后转发到 127.0.0.1:3080。
 * 界面**就是引擎原生的那份前端**，只是被打了补丁。
 *
 * ## 硬约束（不得破坏原有结构、不得导致异常重启）
 *  · 绝不监听 3080/3083（引擎 WebUI / 能力桥），只用独立端口 [PORT]；
 *  · 起停完全独立于引擎生命周期；任何异常只影响本代理（catch 住、不抛出）；
 *  · 只做转发，**不修改磁盘上的任何引擎文件**（补丁只在内存里改响应体）；
 *  · 默认关闭，用户显式开启才监听；accept 循环必须在**独立线程**（见 start 注释）。
 */
object LanWebProxy {

    private const val TAG = "LanWebProxy"

    /** 独立端口（禁止占用 3080/3083） */
    const val PORT = 3090

    /** 「已开启」常驻通知的 channel / id（v1.2.188） */
    private const val NOTIF_CHANNEL = "lan_web"
    private const val NOTIF_ID = 0x0A12

    /** 通知里「关闭」按钮的广播 action（由 [app.dsh.mobile.LanWebOffReceiver] 处理） */
    const val ACTION_LAN_OFF = "app.dsh.mobile.action.LAN_WEB_OFF"

    private const val UPSTREAM_HOST = "127.0.0.1"
    private const val UPSTREAM_PORT = 3080
    private const val COOKIE = "dsh_lan_pw"

    /** HTTP 换行（源码里不写转义，避免多层转义失真） */
    private val CRLF = Character.toString(13) + Character.toString(10)

    private val running = AtomicBoolean(false)

    /** app context（部署路径/引擎目录都用它推导；setContext 由 AgentBridge 在启动时调用） */
    @Volatile
    private var appCtx: android.content.Context? = null

    /** 由本进程拉起的 node 子进程（stop 时直接 destroy —— app 对自己的子进程有权限，
     *  不依赖 su/pkill：实测 Shizuku 模式下 su 闸门拒绝，pkill 杀不掉进程）。 */
    @Volatile
    private var child: Process? = null

    private fun dshHomeDir(ctx: android.content.Context): java.io.File {
        val engine = EngineConfig.engineRoot(ctx)
        val parent: java.io.File? = engine.parentFile
        return java.io.File(parent ?: engine, "dsh-home")
    }

    fun setContext(ctx: android.content.Context) { appCtx = ctx.applicationContext }

    @Volatile
    private var server: ServerSocket? = null

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "lan-proxy-conn").apply { isDaemon = true }
    }

    @Volatile
    private var password: String = ""

    fun isRunning(): Boolean = running.get()

    /**
     * 读取引擎 WebUI 的访问 token（v1.2.160）。
     *
     * 引擎自己对 WebUI 也做 token 鉴权（`http://127.0.0.1:3080/?token=…`），
     * 每次启动生成新 token 并写进 engine/engine.log —— 实测带 token 访问会返回
     * `303 + set-cookie: dsh-auth-…`（引擎签发会话 Cookie）。所以代理必须：
     *   ① 首访时自动带上 token（用户不必手输），拿到引擎的会话 Cookie 并透传给浏览器；
     *   ② 仅此一次，之后浏览器带着 dsh-auth-* Cookie 就能正常用原生 UI。
     * 读的是**本机文件**（同 app uid 私有目录），无网络、无外部依赖。
     */
    private fun readEngineToken(): String? = runCatching {
        val log = java.io.File(ENV_ENGINE_LOG)
        if (!log.isFile) return@runCatching null
        var last: String? = null
        log.forEachLine { line ->
            val m = Regex("token=([A-Za-z0-9_-]{16,})").find(line)
            if (m != null) last = m.groupValues[1]
        }
        last
    }.getOrNull()

    private const val ENV_ENGINE_LOG = "/data/user/0/app.dsh.mobile/files/engine/engine.log"

    /**
     * 本机局域网 IPv4（供 UI 显示访问地址）。
     *
     * v1.7.1：改为委托 [LanAddress]。旧实现取「第一个非环回 IPv4」，
     * **开热点时必错** —— 系统里同时有移动网络（`rmnet_data0` 10.x）与热点 AP
     * （`wlan1` 192.168.x.x），枚举顺序不保证，用户实测看到的是移动网络 IP，
     * 热点里其它设备根本连不上。判据与排序理由见 [LanAddress] 的 KDoc。
     */
    fun lanAddress(): String? = LanAddress.best()

    /** 多网卡时的全部候选，UI 可让用户自己挑。 */
    fun lanAddresses(): List<String> = LanAddress.all()

    /**
     * 启动代理（幂等，失败只记日志不抛出）。
     *
     * ⚠️ accept 循环**必须**跑在独立线程：start() 由设置页对话框回调调用（主线程），
     * 直接在调用线程上 accept() 会把 UI 线程永久阻塞（实测症状：点确定后应用 ANR 卡死）。
     */
    fun start(pw: String) {
        if (pw.isBlank()) {
            Log.w(TAG, "refusing to start without a password")
            return
        }
        if (isRunning()) {
            Log.i(TAG, "already running")
            return
        }
        password = pw
        running.set(true)
        // v1.2.160 架构修正：**不再由 app 自己 bind 端口**，改为拉起 node 代理脚本
        // （与 planner-daemon 同一套：nohup node xxx.mjs &）。
        // 原因：手写 Kotlin HTTP 代理要自己处理 chunked / 压缩 / Cookie / 特殊 URL
        // （plugins/??a.js,b.js）/ WebSocket —— 实测反复出问题（白屏、0 字节资源、乱码）。
        // node 的 http 模块天然正确处理这些，且与 daemon 共用同一部署通道。
        Thread({
            runCatching {
                val ctx = appCtx ?: return@runCatching
                val tools = java.io.File(EngineConfig.engineRoot(ctx), "../dsh-home/tools").canonicalFile
                val script = java.io.File(tools, "lan-web-proxy.mjs")
                if (!script.isFile) {
                    Log.w(TAG, "script missing: ${script.absolutePath}")
                    running.set(false)
                    return@runCatching
                }
                val node = EngineConfig.nodeBin(ctx)
                val workDir: java.io.File = EngineConfig.engineRoot(ctx)
                // 复用引擎的环境构造（正确处理 Termux 硬编码 OpenSSL 路径 / LD_LIBRARY_PATH /
                // PREFIX / TMPDIR 等；手写会漏，实测漏 OPENSSL_CONF 导致 node exit=13）
                val engineEnv: Array<String> = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT)
                // 清掉上一次可能残留的代理进程：否则新进程会撞 EADDRINUSE。
                // 自己拉起的子进程优先用 destroy（不需要提权），su 兜底覆盖"换进程后残留"。
                killStaleProxy(ctx)
                Thread.sleep(600L)   // 给端口释放一点时间（TIME_WAIT）

                // ⚠️ v1.2.160 实测修复（进程"起来了又消失"）：
                // 起初用 ProcessBuilder 起 node —— 那是**强绑定的子进程**，app 被系统
                // 回收/重启时它会一起死（实测：日志停在 listening，随后进程消失，
                // 手机内部访问 3090 也失败）。改为与 planner-daemon **同一套做法**：
                // 经 shell `nohup ... &` 脱离父进程，生命周期独立于 app。
                val envPrefix = engineEnv.joinToString(" ") { kv ->
                    val i = kv.indexOf('=')
                    if (i > 0) kv.substring(0, i) + "=" + "'" + kv.substring(i + 1).replace("'", "'\''") + "'" else kv
                }
                val logPath = java.io.File(dshHomeDir(ctx), "lan-proxy.log").absolutePath
                val scriptPath = script.absolutePath
                val sh = "cd " + workDir.absolutePath + " && " + envPrefix +
                    " DSH_LAN_PW='" + pw + "'" +
                    " nohup " + node.absolutePath + " " + scriptPath +
                    " --port " + PORT + " --pw '" + pw + "'" +
                    " > " + logPath + " 2>&1 &"
                val proc = ProcessBuilder("sh", "-c", sh).start()
                val exit = proc.waitFor()
                Log.i(TAG, "node proxy launched via nohup (sh exit=$exit, log=$logPath)")
                Thread.sleep(1500L)
                // 自检：确认端口真的在监听
                val ok = runCatching {
                    java.net.Socket().use { sk ->
                        sk.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 1200)
                        true
                    }
                }.getOrDefault(false)
                Log.i(TAG, if (ok) "proxy verified listening on $PORT" else "proxy NOT listening on $PORT (see $logPath)")
                // v1.2.188：确认端口真的在监听后再弹「已开启」常驻通知 ——
                // 否则启动失败也弹，用户会被误导（以为电脑能连上）。
                // 复查：自检这 1.5s + 600ms 里用户可能已经点了「关闭」/关了开关
                //（审查 P2：否则陈旧通知会在关闭后又弹出来）
                if (ok && app.dsh.mobile.DshApp.lanWebEnabled(ctx)) showLanNotice(ctx)
                // v1.2.188（审查）：旧实现无条件 running.set(false) —— 启动成功后
                // isRunning() 也为 false，语义错乱。按自检结果落位。
                // 复查偏好：自检成功但用户已关闭（或 stop() 恰好落在自检后、
                // 本句前）→ 不置 running=true，避免"已关闭却挡住下次启动"。
                running.set(ok && app.dsh.mobile.DshApp.lanWebEnabled(ctx))
            }.onFailure { e ->
                Log.w(TAG, "start failed: ${e.message}")
                running.set(false)
            }
        }, "lan-proxy-spawn").apply { isDaemon = true }.start()
    }

    /**
     * 杀掉遗留的代理进程（v1.2.167 实测修复）。
     *
     * ⚠️ 根因：旧实现只有 `Privilege.runSu("pkill …")` —— 但本机是 **Shizuku 模式**，
     * su 闸门会拒绝（gate: mode=SHIZUKU, su denied），于是 pkill 从未生效 →
     * 旧代理进程一直占着 3090、用**旧的验证码**服务 → 用户输新码永远打不开
     * （实测症状：日志刷 "port 3090 busy, retrying"）。
     * 现在：**Shizuku 通道优先**（adb 身份，能杀 app 自己的进程），su 兜底。
     */
    private fun killStaleProxy(ctx: android.content.Context) {
        val cmd = "pkill -f lan-web-proxy 2>/dev/null; echo done"
        // ⓪ v1.2.188（审查 P1）：**同 uid 直杀**——app 对自己 fork 的 node 进程
        // 天然有 kill 权限，不需要 Shizuku/su。旧实现只有 shizuku/su 两条通道，
        // 普通模式（无 Shizuku、无 root）下**两条都失效** → 用户点「关闭」后
        // node 仍监听 3090，而通知已经消失（更危险：用户以为关了）。
        runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", cmd).start()
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            Log.i(TAG, "same-uid pkill done (exit=${runCatching { p.exitValue() }.getOrDefault(-1)})")
        }.onFailure { Log.w(TAG, "same-uid pkill failed: ${it.message}") }
        // ① 自己持有的子进程引用（若 start 路径记录过）
        val c = child
        if (c != null) runCatching { c.destroyForcibly() }
        // ② Shizuku（换进程后旧代理可能不属于本进程树；adb 身份可能无权限，仅兜底）
        runCatching {
            if (Privilege.shizukuUsable()) Privilege.shizukuExec(ctx, cmd)
        }
        // ③ su（root 模式）
        runCatching { Privilege.runSu(cmd) }
        child = null
    }

    /**
     * 显示「局域网 WebUI 已开启」常驻通知（v1.2.188，用户需求）。
     *
     * ## 为什么需要
     * 局域网访问一旦开启就会一直监听，用户很容易忘记关（开着 = 同网段任何人都能
     * 访问 WebUI）。用户要求「在通知栏加一条常驻通知提醒已开启，并提供关闭按钮」。
     *
     * 通知用独立 channel + IMPORTANCE_LOW（不响不震、不打断），`setOngoing(true)`
     * 让它常驻；`setOnlyAlertOnce` 避免重复提醒；点「关闭」走
     * [LanWebOffReceiver]（停止代理 + 清偏好 + 撤通知）。
     *
     * @param ctx app context（用 applicationContext 也可）。
     */
    fun showLanNotice(ctx: android.content.Context) {
        runCatching {
            val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        NOTIF_CHANNEL,
                        ctx.getString(app.dsh.mobile.R.string.notif_lan_channel),
                        android.app.NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
            // 用户没给通知权限（Android 13+）→ 静默跳过，不影响开关
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val addr = lanAddress()?.let { "http://$it:$PORT" } ?: "http://<IP>:$PORT"
            // 点通知 → 打开设置页（可以去关掉）
            val open = android.app.PendingIntent.getActivity(
                ctx, 0,
                android.content.Intent(ctx, app.dsh.mobile.SettingsActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // 「关闭」→ 广播给 App 自己处理
            val off = android.app.PendingIntent.getBroadcast(
                ctx, 1,
                android.content.Intent(ACTION_LAN_OFF).setPackage(ctx.packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(ctx, NOTIF_CHANNEL)
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            }
            builder.setSmallIcon(app.dsh.mobile.R.drawable.ic_launcher_foreground)
                .setContentTitle(ctx.getString(app.dsh.mobile.R.string.notif_lan_title))
                .setContentText(ctx.getString(app.dsh.mobile.R.string.notif_lan_text, addr))
                .setContentIntent(open)
                .setOngoing(true)          // 常驻，避免被随手划掉后忘记还开着
                .setOnlyAlertOnce(true)
                .addAction(
                    android.app.Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            ctx, android.R.drawable.ic_menu_close_clear_cancel
                        ),
                        ctx.getString(app.dsh.mobile.R.string.notif_lan_action_off),
                        off,
                    ).build()
                )
            nm.notify(NOTIF_ID, builder.build())
            Log.i(TAG, "LAN notice shown ($addr)")
        }.onFailure { Log.w(TAG, "show LAN notice failed: ${it.message}") }
    }

    /** 撤掉「局域网 WebUI 已开启」通知（幂等）。 */
    fun hideLanNotice(ctx: android.content.Context) {
        runCatching {
            val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.cancel(NOTIF_ID)
        }
    }

    fun stop() {
        running.set(false)
        // v1.2.188：关掉代理时同步撤掉「已开启」常驻通知
        //（覆盖三种入口：设置页开关、通知里的「关闭」按钮、引擎退出一并停代理）
        appCtx?.let { hideLanNotice(it) }
        // ⚠️ v1.2.160 实测修复（用户反馈"关掉开关后电脑仍能访问"）：
        // 原实现用 `su pkill`，但本机是 **Shizuku 模式**，su 闸门拒绝 → 杀不掉 →
        // 端口继续监听。改为**优先直接 destroy 自己拉起的子进程**（app 对子进程
        // 天然有权，不需要任何提权），su 仅作兜底（normal/root 模式下也能清干净）。
        val p = child
        if (p != null) {
            runCatching {
                p.destroy()
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly()   // 脚本忽略 TERM 时强杀
                }
                Log.i(TAG, "node proxy destroyed (exit=${runCatching { p.exitValue() }.getOrDefault(-1)})")
            }.onFailure { Log.w(TAG, "destroy failed: ${it.message}") }
            child = null
        } else {
            Log.w(TAG, "no child reference (proxy may have been started by a previous process)")
        }
        // 兜底：换进程/换包后 child 引用可能失效 → 走 Shizuku/su 通道强制清掉
        appCtx?.let { killStaleProxy(it) }
        Log.i(TAG, "stopped")
    }

}

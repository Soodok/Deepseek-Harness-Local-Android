package app.dsh.mobile.engine.vscreen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟屏常驻核心 —— 以 uid 0 在独立的 app_process 进程里跑。
 *
 * ## 为什么必须是独立的 app_process 进程
 * `DisplayManager.createVirtualDisplay` 需要 Android Context；门脚本是 bash，拿不到。
 * App 进程能拿到 Context，但：建受信任显示要签名级 `ADD_TRUSTED_DISPLAY`，
 * 注入事件要 `INJECT_EVENTS` —— 两者普通 App 都没有。
 * 而引擎本身就是 uid 0（u:r:ksu:s0），所以用 `app_process` 加载**本 APK** 的
 * 这个类，一次拿到 Context + root 身份。这是唯一自洽的通道。
 *
 * ## 为什么画面不出现在物理屏上
 * `settings put global overlay_display_devices` 建出来的屏会在物理屏左上角叠一块
 * 画中画（实测确认），那是给开发者的叠加视图。本类走 `createVirtualDisplay`，
 * 内容只进 ImageReader，物理屏上什么都不显示。
 *
 * ## 与门脚本的分工
 * 本进程只做「平台侧那件事」：建屏、取帧、投事件。所有命令行语义、参数校验、
 * JSON 输出都在 `gates/vscreen` 门脚本里 —— 和 `hid` 同一分工。这样核心可以
 * 只有几百行，不用重复实现一套 CLI。
 *
 * ## 鉴权
 * 核心自己生成 token 并写到**仅属主可读**的文件（路径由 argv 给出，argv 里没有
 * token 本身）。门脚本从同一文件读。放 App 私有目录下，同机第三方 App 读不到，
 * 也不落进任何 argv/cmdline。
 */
class VScreenCore {

    private companion object {
        /** 帧循环间隔，约 30fps。再快无意义：ImageReader 3 缓冲会被自己吃满。 */
        const val FRAME_INTERVAL_MS = 33L

        /** 观察窗口：超过它就必须重新截图才能注入，避免拿很久以前的画面做决策。 */
        const val OBSERVATION_WINDOW_MS = 30_000L

        // @hide / @SystemApi 的 flag 名字。编译期不可见，只能按名字反射取。
        const val F_SUPPORTS_TOUCH = "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH"
        const val F_DESTROY_ON_REMOVAL = "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL"
        const val F_TRUSTED = "VIRTUAL_DISPLAY_FLAG_TRUSTED"
        const val F_OWN_FOCUS = "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS"

        /**
         * 进程级单例强引用。
         *
         * ⚠️ 没有它，核心会在几十秒后「静默失去监听」而进程还活着 —— 一个极隐蔽的坑：
         * `main` 里 `val core = VScreenCore()` 只是**局部变量**，随后进入
         * `Looper.loop()`（无限循环，再也碰不到 core），JIT 就把这个局部引用判定为
         * 死亡，GC 回收 core 实例；而 `ServerSocket` 有 Cleaner，被回收即 close()。
         * 外部表现：`ps` 能看到 app_process 还在 `do_epoll_wait`，但端口已不在监听，
         * 连接直接 refused，日志里一个字都没有。
         * 实测踩过，排查了整轮才定位。
         */
        @JvmStatic
        val INSTANCE: VScreenCore by lazy { VScreenCore() }

        @JvmStatic
        fun main(argv: Array<String>) {
            // token 绝不走 argv：/proc/<pid>/cmdline 的可见性比文件更难控。
            if (argv.any { it == "--token" } || argv.any { it.startsWith("--token=") }) {
                System.err.println("VS_ERROR=TOKEN_MUST_NOT_BE_IN_ARGV")
                return
            }
            // 用静态单例而非局部变量：理由见 INSTANCE 的注释。
            val core = INSTANCE
            try {
                core.readyFile = File(argValue(argv, "--ready") ?: return run {
                    System.err.println("VS_ERROR=READY_FILE_REQUIRED")
                })
                // 注意：File(null) 直接 NPE（String 构造重载不接受 null），必须先取值再判空。
                argValue(argv, "--journal")?.let { core.journalFile = File(it) }
                core.context = core.systemContext()
                val port = core.serve()
                core.writeReady(port)
                // 不用 Looper.loop()：它一旦返回（quit 或内部异常）主线程就结束、
                // 进程静默退出，而我们的服务全在独立线程上，根本不依赖消息循环。
                // 用 latch 永久阻塞主线程，语义明确也不会因 Looper 状态而意外退出。
                java.util.concurrent.CountDownLatch(1).await()
            } catch (t: Throwable) {
                System.err.println("VS_ERROR=" + t.javaClass.simpleName + ":" + t.message)
                t.stackTrace.take(4).forEach { System.err.println("  at " + it) }
                System.err.flush()
            }
        }

        /** `--key value` 取值。 */
        fun argValue(argv: Array<String>, name: String): String? {
            val i = argv.indexOf(name)
            return if (i >= 0 && i + 1 < argv.size) argv[i + 1] else null
        }
    }

    // ---- 会话状态：单实例，一次只服务一块屏 ----
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameThread: Thread? = null
    private val active = AtomicBoolean(false)

    private val frameLock = Any()
    private var frame: Bitmap? = null
    private var frameSeq = 0L

    /**
     * 待执行的退出原因（/stop 用）。serveOne 把响应 flush 出去之后再调 shutdown()。
     * 直接在处理线程里 shutdown 会 System.exit，响应发不回去。
     */
    @Volatile private var pendingShutdown: String? = null
    private var frameAt = 0L
    private var observedSeq = -1L
    private var observedAt = 0L

    private var width = 0
    private var height = 0
    private var generation = ""
    private var lastRequest = SystemClock.elapsedRealtime()
    private val startedAt = SystemClock.elapsedRealtime()

    // ---- 进程级 ----
    private lateinit var context: Context
    private lateinit var readyFile: File
    private var journalFile: File? = null
    private var token = ""
    private lateinit var server: ServerSocket

    @Volatile private var running = true

    /**
     * 取得系统级 Context。
     *
     * `app_process` 起来时没有 main Looper，而 `ActivityThread.systemMain()`
     * 在 attach 期间要构造 Handler —— 顺序错了直接 NPE。必须先 prepareMainLooper。
     */
    private fun systemContext(): Context {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val thread = runCatching { at.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: at.getMethod("systemMain").invoke(null)
        return thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
    }

    // ======================= 生命周期 =======================

    private fun serve(): Int {
        // 只绑环回地址；端口交给内核挑（0），避免和引擎/桥抢固定端口。
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        token = randomToken()
        active.set(true)
        Thread({ acceptLoop() }, "vscreen-accept").apply { isDaemon = true }.start()
        Thread({ watchdog() }, "vscreen-watchdog").apply { isDaemon = true }.start()
        return server.localPort
    }

    /**
     * 把 port 与 token 交给调用方。
     *
     * 先写临时文件再 rename —— 门脚本可能正好在这之间读，读到一个只写了一半的
     * JSON 会 parse 失败，然后误判成"核心没起来"。rename 是原子的。
     * 权限压到 600：文件在 App 私有目录下，但多一层属主限制没有坏处。
     */
    private fun writeReady(port: Int) {
        val json = JSONObject()
            .put("ok", true)
            .put("port", port)
            .put("token", token)
            .put("pid", Process.myPid())
            .put("uid", Process.myUid())
            .put("started_at", System.currentTimeMillis())
        val tmp = File(readyFile.absolutePath + ".tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(json.toString())
        // FileUtils.setPermissions 是 @hide，用公开的 Os.chmod（0600）。
        runCatching { android.system.Os.chmod(tmp.absolutePath, 0x180) }
        if (!tmp.renameTo(readyFile)) {
            // rename 失败（跨文件系统/已存在覆盖不了）时退回直接写。
            readyFile.writeText(json.toString())
            tmp.delete()
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            Thread({ handle(socket) }, "vscreen-client").apply { isDaemon = true }.start()
        }
    }

    /**
     * 看门狗。
     *
     * 两层职责：①连接级空闲超时（10 分钟没人来就自己退，虚拟屏占着一整块
     * SurfaceFlinger 显示与一路帧缓冲，不该永久常驻）；②父进程消失检测 ——
     * 核心是门脚本 setsid 起的，父进程退出后它就成了孤儿。引擎重启/App 被杀
     * 都会留下这种孤儿，占着显示不放，下次建屏直接失败。
     */
    private fun watchdog() {
        // ⚠️ ppid 不能用"变了就是父进程死了"来判。核心是用 `( ... & )` + nohup 起在
        // su 会话里的，启动它的中间 shell 几十毫秒后就退出，核心随即被 init 收养，
        // ppid 从「某个 sh 的 pid」变成 1 —— **这是预期的正常流程**。
        // 早期版本一发现 ppid 变化就自杀，表现为「ready 文件刚写出来，下一秒就
        // Connection refused」，排查了整整一轮。
        //
        // 现在的判据：只认「被 init 收养之后又换了父进程」这一种异常，
        // 而且收养状态一旦成立就永久锁定，不再重复判断。
        var adopted = false
        var beat = 0L
        while (running) {
            Thread.sleep(1000)
            // 每 5 秒一条心跳：核心若"静默消失"，journal 里最后一条心跳的时间
            // 就是死亡时刻，比猜有用得多。
            beat++
            if (beat % 5L == 0L) {
                journal("alive:up=" + (SystemClock.elapsedRealtime() - startedAt) / 1000 + "s" +
                    ":ppid=" + runCatching { android.system.Os.getppid() }.getOrDefault(-1) +
                    ":idle=" + (SystemClock.elapsedRealtime() - lastRequest) / 1000 + "s")
            }
            if (SystemClock.elapsedRealtime() - lastRequest > IDLE_TIMEOUT_MS) {
                shutdown("idle timeout")
                return
            }
            val now = runCatching { android.system.Os.getppid() }.getOrDefault(-1)
            when {
                // 已被 init 收养：正常常驻形态。
                now == 1 -> adopted = true
                // 还没被收养：中间 shell 还活着，继续等。
                !adopted -> Unit
                // 收养过了又换父进程：只能是被重新绑到别的会话，链条断了。
                else -> {
                    shutdown("parent rebound")
                    return
                }
            }
        }
    }

    private fun shutdown(reason: String) {
        journal("shutdown:$reason")
        runCatching { release() }
        readyFile.delete()
        running = false
        runCatching { server.close() }
        runCatching { Thread.sleep(100) }
        System.exit(0)
    }

    /** 关键事件留一条流水，排查"孤儿怎么来的"时唯一线索。 */
    private fun journal(event: String) {
        val f = journalFile ?: return
        runCatching {
            f.appendText(
                JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("pid", Process.myPid())
                    .put("event", event)
                    .toString() + "\n",
            )
        }
    }

    // ======================= HTTP =======================

    private fun handle(socket: Socket) {
        // ⚠️ 整个连接处理必须包 try/catch。这是**独立线程**上的异常 ——
        // 非主线程抛未捕获异常会走 ThreadGroup.uncaughtException，
        // 直接把整个进程杀掉（不是"这条连接断了"，是"核心没了"）。
        // 实测踩过：门脚本发一个不带 body 的请求，服务端等 Content-Length 超时抛
        // SocketTimeoutException，核心当场死掉，而客户端只看到 Connection refused，
        // 完全看不出是服务端崩了。
        runCatching { serveOne(socket) }
            .onFailure { journal("conn-error:" + it.javaClass.simpleName + ":" + it.message) }
        runCatching { socket.close() }
    }

    private fun serveOne(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        // 只认恰好一个 X-Token：重复头会让不同解析器取到不同值，是典型的头走私面。
        val supplied = headers["x-token"]
        val authorised = supplied != null && constantTimeEquals(supplied, token)
        val parts = requestLine.split(' ')
        val target = parts.getOrNull(1) ?: "/"
        val body = if (authorised) route(target)
        else JSONObject().put("ok", false).put("error", "UNAUTHORISED")
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        out.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray())
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
        // flush 之后才允许真正退出：退出原因由 /stop 分支登记。
        pendingShutdown?.let { shutdown(it) }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun route(target: String): JSONObject {
        lastRequest = SystemClock.elapsedRealtime()
        val path = target.substringBefore('?')
        val params = parseQuery(target.substringAfter('?', ""))
        return try {
            when (path) {
                "/ping" -> ok()
                "/status" -> status()
                "/start" -> start(params["w"]?.toIntOrNull() ?: 0, params["h"]?.toIntOrNull() ?: 0)
                // ★ 不能在这里直接 shutdown：shutdown() 末尾 System.exit(0)，
                //   那会让本连接的响应**永远发不回去**（HTTP 客户端只看到连接被重置）。
                //   以前被门脚本的 >/dev/null 掩盖了 —— 客户端其实一直拿不到 JSON。
                //   改为登记一个待退出标记，由 serveOne 在 flush 之后再真正退。
                "/stop" -> { pendingShutdown = "requested"; ok() }
                "/shot" -> shot()
                "/launch" -> launch(params["pkg"] ?: return err("pkg required"))
                "/tap" -> tap(params)
                "/swipe" -> swipe(params)
                "/key" -> key(params["code"]?.toIntOrNull() ?: return err("code required"))
                "/type" -> type(params["text"].orEmpty())
                else -> err("unknown route")
            }
        } catch (t: Throwable) {
            err(t.javaClass.simpleName + ":" + t.message)
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        if (q.isEmpty()) emptyMap()
        else q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null
            else java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()

    private fun ok() = JSONObject().put("ok", true)
    private fun err(message: String?) = JSONObject().put("ok", false).put("error", message ?: "unknown")

    // ======================= 建屏 / 销毁 =======================

    /**
     * 屏幕尺寸规整。
     *
     * 直接给 1080x1920 会让部分 ROM 的 SurfaceFlinger 在缩放到物理屏时取整出
     * 亚像素缝。把短边对齐到 144 的倍数再按 16:9 推长边（144 是常见 tile 尺寸的
     * 公约数）。这是踩过的经验值，不是猜的。
     */
    private fun phoneSize(reqW: Int, reqH: Int): Pair<Int, Int> {
        val landscape = reqW > reqH
        val source = minOf(maxOf(reqW, 1), maxOf(reqH, 1))
        val edge = maxOf(144, minOf(1440, Math.round(source / 144f) * 144))
        val long = edge * 16 / 9
        return if (landscape) long to edge else edge to long
    }

    private fun start(reqW: Int, reqH: Int): JSONObject {
        if (display != null) release()
        val (w, h) = phoneSize(reqW, reqH)
        width = w
        height = h
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)

        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val missing = mutableListOf<String>()
        flags = flags or sysFlag(F_SUPPORTS_TOUCH, missing)
        flags = flags or sysFlag(F_DESTROY_ON_REMOVAL, missing)
        flags = flags or sysFlag(F_OWN_FOCUS, missing)
        // TRUSTED 在 API 31+ 要签名级 ADD_TRUSTED_DISPLAY。uid 0 天然持有；
        // 非 root 请求它会被 DisplayManagerService 直接抛 SecurityException，
        // 所以必须按「实际是否 uid 0」决定，不能无条件加。
        if (Process.myUid() == 0) flags = flags or sysFlag(F_TRUSTED, missing)

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val created = dm.createVirtualDisplay("dsh-vscreen", w, h, 320, reader!!.surface, flags)
            ?: return err("DISPLAY_CREATE_FAILED")
        display = created
        // ⚠️ active 必须在每次 start 时重新点亮。它唯一的另一次 set(true) 在 main
        // 入口，而 release() 会把它置 false —— 于是「stop 再 start」之后帧循环
        // 立刻退出，frame 永远是 null，shot 恒报 FRAME_NOT_READY、frame_seq 恒 0。
        // 实测踩过：第一次 start 截图有画面，之后无论怎么 start 都拍不到，
        // 而且所有端点都回报 ok:true，看起来像「截图偶尔失败」。
        active.set(true)
        generation = randomToken().take(8)
        frameSeq = 0
        observedSeq = -1
        synchronized(frameLock) {
            frame?.recycle()
            frame = null
        }
        startFrameLoop()
        journal("start:display=${created.display.displayId}:${w}x$h")
        return status().put("missing_flags", missing.joinToString(","))
    }

    /** 反射读 DisplayManager 的 @hide/@SystemApi flag；取不到返回 0 并记下名字。 */
    private fun sysFlag(name: String, missing: MutableList<String>): Int = try {
        DisplayManager::class.java.getField(name).getInt(null)
    } catch (t: Throwable) {
        missing.add(name)
        0
    }

    private fun startFrameLoop() {
        val session = display ?: return
        val r = reader ?: return
        frameThread?.let { runCatching { it.interrupt() } }
        frameThread = Thread({
            while (active.get() && display === session) {
                var image: Image? = null
                try {
                    image = r.acquireLatestImage()
                    if (image != null) {
                        val next = toBitmap(image, width, height)
                        synchronized(frameLock) {
                            if (frame?.sameAs(next) == true) {
                                next.recycle()
                            } else {
                                frame?.recycle()
                                frame = next
                                frameSeq++
                                frameAt = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // 帧暂时取不到不重建显示，也不伪造帧号。
                } finally {
                    runCatching { image?.close() }
                }
                try {
                    Thread.sleep(FRAME_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "vscreen-frames").apply { isDaemon = true }
        frameThread!!.start()
    }

    private fun release() {
        active.set(false)
        runCatching { frameThread?.interrupt() }
        frameThread = null
        runCatching { reader?.close() }
        reader = null
        runCatching { display?.release() }
        display = null
        synchronized(frameLock) {
            frame?.recycle()
            frame = null
        }
    }

    // ======================= 截图 =======================

    // 等第一帧到位。刚 launch 完的那一瞬间 ImageReader 还没回调过
    // onImageAvailable，直接查 frame 会误报 FRAME_NOT_READY。
    // 返回 true 表示已经拿到帧（或本来就有）。
    private fun awaitFirstFrame(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            synchronized(frameLock) { if (frame != null) return true }
            try { Thread.sleep(100) } catch (_: InterruptedException) { return false }
        }
        synchronized(frameLock) { return frame != null }
    }

    /**
     * 截图落盘目录：**与 ready/journal 同一个目录**（门脚本传进来的 DSH_HOME/vscreen）。
     *
     * 为什么不能指望 context.filesDir（v1.7.12 真机实测）：核心是 app_process 以
     * uid 0 起的，拿到的是 ActivityThread 的 **system** context，
     * `filesDir` = `/data/user/0/android/files` —— 那个目录在真机上**根本不存在**，
     * mkdirs 也建不出来（父目录 `/data/user/0/android` 是 system:system 0700，
     * 核心所在的 SELinux 域写不进去）。于是每一张截图都静默退回 /data/local/tmp，
     * 「落在私有目录」这层防护等于没生效 —— 现场抓 shot 返回的 path 一眼可见。
     *
     * 改用 readyFile 的父目录：那是 App 私有数据区（`/data/user/0/<pkg>/files/vscreen`，
     * 上层目录 0700 属主是 App uid），adb shell 与其它应用都写不进去，正是要的效果。
     * 仍然保留两级兜底，且文件名始终带 generation+seq，不可预测、不互相覆盖。
     */
    private fun shotDir(): File {
        val candidates = listOf(
            readyFile.absoluteFile.parentFile,
            runCatching { File(context.filesDir, "vscreen") }.getOrNull(),
        )
        for (d in candidates) {
            if (d == null) continue
            runCatching { d.mkdirs() }
            if (d.isDirectory && d.canWrite()) return d
        }
        return File("/data/local/tmp")
    }

    private fun shot(): JSONObject {
        val snapshot: Bitmap
        val seq: Long
        // 等首帧：刚 launch 完的那一瞬间 ImageReader 还没回调过 onImageAvailable，
        // 直接查 frame 会误报 FRAME_NOT_READY。等最多 3 秒（每 100ms 一次）。
        awaitFirstFrame(3_000)
        synchronized(frameLock) {
            val f = frame ?: return err("FRAME_NOT_READY")
            snapshot = f.copy(Bitmap.Config.ARGB_8888, false) ?: return err("FRAME_COPY_FAILED")
            seq = frameSeq
            // 记下"调用方看过这一帧" —— 随后的 tap/swipe 必须带同一个 frame_seq。
            observedSeq = seq
            observedAt = SystemClock.elapsedRealtime()
        }
        // ★ 固定路径有两个问题（v1.7.12 改）：
        //   ① 并发：两次 shot 会互相覆盖，调用方读到的可能是别人那一帧；
        //   ② 攻击面：/data/local/tmp 对 adb shell（uid 2000）可写，
        //      预先在那里放一个同名 symlink 就能让 uid 0 的核心以 root 覆盖任意文件。
        //   改为按 generation（每次 build 自增）+ 帧号命名，落在核心自己的私有目录。
        val file = File(shotDir(), "shot-g" + generation + "-s" + seq + ".png")
        try {
            file.outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG, 92, it) }
        } finally {
            snapshot.recycle()
        }
        return JSONObject()
            .put("ok", true)
            .put("path", file.absolutePath)
            .put("bytes", file.length())
            .put("width", width)
            .put("height", height)
            .put("frame_seq", seq)
            .put("display_id", display?.display?.displayId ?: -1)
            .put("generation", generation)
    }

    /**
     * 把 ImageReader 的 RGBA 平面重排成紧凑位图。
     *
     * ImageReader 的行跨距 rowStride 通常大于 width*4（硬件对齐填充），
     * 直接 copyPixelsFromBuffer 会让每行错位几个像素 —— 画面呈斜条纹。
     * 这个失败很迷惑：帧"取到了"、字节数也正常，只有真的看一眼才发现是花的。
     */
    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val packed = packPlane(plane.buffer, w, h, plane.pixelStride, plane.rowStride)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(packed)
        return bitmap
    }

    private fun packPlane(
        source: java.nio.ByteBuffer, w: Int, h: Int, pixelStride: Int, rowStride: Int,
    ): java.nio.ByteBuffer {
        require(w > 0 && h > 0 && pixelStride >= 4 && rowStride > 0)
        val rowBytes = w.toLong() * 4
        val rowSpan = (w - 1).toLong() * pixelStride + 4
        val packedBytes = rowBytes * h
        val required = (h - 1).toLong() * rowStride + rowSpan
        require(rowSpan <= rowStride && packedBytes <= Int.MAX_VALUE && required <= source.limit())
        val input = source.duplicate()
        input.rewind()
        if (pixelStride == 4 && rowStride.toLong() == rowBytes) {
            input.limit(packedBytes.toInt())
            return input
        }
        val output = java.nio.ByteBuffer.allocate(packedBytes.toInt())
        for (y in 0 until h) {
            val row = (y.toLong() * rowStride).toInt()
            if (pixelStride == 4) {
                input.limit(row + rowBytes.toInt())
                input.position(row)
                output.put(input)
                input.limit(source.limit())
            } else {
                for (x in 0 until w) {
                    val pixel = row + x * pixelStride
                    for (c in 0 until 4) output.put(input.get(pixel + c))
                }
            }
        }
        output.flip()
        return output
    }

    // ======================= 跨屏启动 =======================

    private fun launch(pkg: String): JSONObject {
        val id = display?.display?.displayId ?: return err("NO_DISPLAY")
        // ActivityOptions.setLaunchDisplayId 是 @hide；am 自带 --display，
        // 且它跑在 uid 0 下能启未导出的 Activity —— 比反射稳。
        // 允许两种写法：纯包名（"com.android.settings"）用 -p，
        // 带组件（"com.android.settings/.Settings"）必须用 -n。
        // 用 -p 传带斜杠的组件会被 am 当成包名，报
        // "unable to resolve Intent ... pkg=com.android.settings/.Settings"。
        val args = mutableListOf("/system/bin/am", "start", "--display", id.toString())
        if (pkg.contains('/')) {
            args += listOf("-n", pkg)
        } else {
            args += listOf("-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-p", pkg)
        }
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().take(600)
        proc.waitFor()
        val started = !out.contains("Error") && !out.contains("Exception")
        journal("launch:$pkg:$started")
        return JSONObject()
            .put("ok", started)
            .put("display_id", id)
            .put("pkg", pkg)
            .apply { if (!started) put("error", out.trim()) }
    }

    // ======================= 输入注入 =======================

    private fun status(): JSONObject {
        val seq: Long
        synchronized(frameLock) { seq = frameSeq }
        return JSONObject()
            .put("ok", true)
            .put("active", display != null)
            .put("display_id", display?.display?.displayId ?: -1)
            .put("width", width)
            .put("height", height)
            .put("frame_seq", seq)
            .put("generation", generation)
            .put("uid", Process.myUid())
            .put("input_backend", inputBackend())
            .put("pid", Process.myPid())
    }

    /** 输入后端：优先反射直接注入（不 fork），被 hidden-API 拦掉则退到 `input -d`。 */
    private val reflectInject by lazy {
        runCatching {
            val cls = runCatching { Class.forName("android.hardware.input.InputManager") }
                .getOrElse { Class.forName("android.hardware.input.InputManagerGlobal") }
            val instance = cls.getMethod("getInstance").invoke(null)
            val inject = cls.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
            )
            instance to inject
        }.getOrNull()
    }

    private val setDisplayId by lazy {
        runCatching { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }
            .getOrNull()
    }

    private fun inputBackend(): String =
        if (reflectInject != null && setDisplayId != null) "reflection" else "command"

    private fun inject(event: InputEvent): Boolean {
        val id = display?.display?.displayId ?: return false
        runCatching { setDisplayId?.invoke(event, id) }
            .onFailure { journal("inject:setDisplayId:" + it.javaClass.simpleName + ":" + it.message) }
        val pair = reflectInject ?: return false
        // 注入失败**必须记 journal**：反射拿得到方法不等于能调通（缺 INJECT_EVENTS、
        // 目标 display 不是 trusted 都会抛），而调用方只能看到 via=command 的降级，
        // 完全不知道为什么降级。实测踩过：一路 ok:true，画面一帧没变。
        return runCatching {
            pair.second.invoke(pair.first, event, 2 /* WAIT_FOR_FINISH */) as Boolean
        }.onFailure {
            journal("inject:" + it.javaClass.simpleName + ":" + (it.cause?.message ?: it.message))
        }.getOrDefault(false)
    }

    private fun tap(params: Map<String, String>): JSONObject {
        val x = params["x"]?.toFloatOrNull() ?: return err("x required")
        val y = params["y"]?.toFloatOrNull() ?: return err("y required")
        val stale = frameGuard(params)
        if (stale != null) return stale
        val now = SystemClock.uptimeMillis()
        val down = touch(now, now, MotionEvent.ACTION_DOWN, x, y)
        val up = touch(now, now + 60, MotionEvent.ACTION_UP, x, y)
        val viaReflect = inject(down) && inject(up)
        down.recycle()
        up.recycle()
        if (viaReflect) {
            observedSeq = -1
            return ok().put("via", "input_event").put("x", x).put("y", y)
        }
        return commandInput("tap", x.toInt().toString(), y.toInt().toString()).put("x", x).put("y", y)
    }

    private fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

    private fun swipe(params: Map<String, String>): JSONObject {
        val x1 = params["x1"]?.toFloatOrNull() ?: return err("x1 required")
        val y1 = params["y1"]?.toFloatOrNull() ?: return err("y1 required")
        val x2 = params["x2"]?.toFloatOrNull() ?: return err("x2 required")
        val y2 = params["y2"]?.toFloatOrNull() ?: return err("y2 required")
        val ms = (params["ms"]?.toLongOrNull() ?: 300L).coerceIn(50L, 3000L)
        val stale = frameGuard(params)
        if (stale != null) return stale
        val t0 = SystemClock.uptimeMillis()
        val down = touch(t0, t0, MotionEvent.ACTION_DOWN, x1, y1)
        var okAll = inject(down)
        down.recycle()
        val steps = (ms / 16).toInt().coerceAtLeast(2)
        for (i in 1..steps) {
            val k = i.toFloat() / steps
            val e = touch(
                t0, t0 + (ms * k).toLong(), MotionEvent.ACTION_MOVE,
                x1 + (x2 - x1) * k, y1 + (y2 - y1) * k,
            )
            okAll = okAll && inject(e)
            e.recycle()
            Thread.sleep(16)
        }
        val upTime = t0 + ms
        val up = touch(upTime, upTime, MotionEvent.ACTION_UP, x2, y2)
        okAll = okAll && inject(up)
        up.recycle()
        if (okAll) {
            observedSeq = -1
            return ok().put("via", "input_event").put("ms", ms)
        }
        return commandInput(
            "swipe", x1.toInt().toString(), y1.toInt().toString(),
            x2.toInt().toString(), y2.toInt().toString(), ms.toString(),
        ).put("ms", ms)
    }

    private fun key(code: Int): JSONObject {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)
        val up = KeyEvent(now, now + 40, KeyEvent.ACTION_UP, code, 0)
        val viaReflect = inject(down) && inject(up)
        if (viaReflect) return ok().put("via", "input_event").put("code", code)
        return commandInput("keyevent", code.toString()).put("code", code)
    }

    private fun type(text: String): JSONObject {
        if (text.isEmpty()) return err("text required")
        // 文本输入要过 IME，无法用 KeyEvent/MotionEvent 表达 —— 只能走 input text。
        return commandInput("text", text.replace(" ", "%s"))
    }

    /**
     * 命令后端：`input -d <displayId> ...`。
     * 反射被 hidden-API 策略拦掉时用它兜底，代价是每次 fork 一个进程（约 60~120ms）。
     */
    private fun commandInput(vararg args: String): JSONObject {
        val id = display?.display?.displayId ?: return err("NO_DISPLAY")
        val cmd = mutableListOf("/system/bin/input", "-d", id.toString())
        cmd.addAll(args)
        return runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val rc = p.waitFor()
            JSONObject().put("ok", rc == 0).put("via", "command").put("exit", rc)
                .apply { if (out.isNotEmpty()) put("output", out.take(400)) }
        }.getOrElse { err(it.message) }
    }

    /**
     * 帧闸门：注入前必须能证明"调用方看过当前这一帧"。
     *
     * 不带它，AI 会拿几分钟前的截图坐标去点现在的界面 —— 点错地方比不点更糟，
     * 而且事后看不出错。调用方先 /shot 拿 frame_seq，再把它带上。
     * 不传 frame_seq 则跳过（留给 `input -d` 手工调试）。
     */
    private fun frameGuard(params: Map<String, String>): JSONObject? {
        val requested = params["frame_seq"]?.toLongOrNull() ?: return null
        synchronized(frameLock) {
            if (requested > 0 && requested != observedSeq) {
                return JSONObject()
                    .put("ok", false).put("error", "STALE_FRAME")
                    .put("requested", requested).put("current", frameSeq)
                    .put("observed", observedSeq)
                    .put("hint", "take a fresh screenshot first")
            }
            if (requested > 0 && SystemClock.elapsedRealtime() - observedAt > OBSERVATION_WINDOW_MS) {
                return JSONObject().put("ok", false).put("error", "OBSERVATION_EXPIRED")
            }
        }
        return null
    }
}

/** 空闲多久自动退出。没有常驻需求时不该占着一块显示与帧缓冲。 */
private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L


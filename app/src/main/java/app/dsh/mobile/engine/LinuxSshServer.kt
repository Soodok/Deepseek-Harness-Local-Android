package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 远程 SSH 开发环境（v1.7.2）—— 让 PC 用 `ssh -p 2222` 直接进手机容器。
 *
 * ## 为什么不是容器里的 dropbear
 *
 * 这是本轮实测出来的结论，写在最前面免得后人重走：
 *
 * dropbear 在容器里**装得上、也能监听**（`/proc/net/tcp` 里能看到
 * `00000000:08AE 0A` = `0.0.0.0:2222` LISTEN，因为容器与宿主共用网络命名空间，
 * 所以那是真局域网可达的）。但从 PC 连上去时，它的登录子进程在 exec 登录 shell
 * 那一刻打印 `REEXEC: preload FAIL` 并断开。
 *
 * 我逐层排除了所有「容器有问题」的可能：
 *  · `apk add dropbear` → `OK: 416 MiB in 102 packages`，正常；
 *  · 容器内 `id` → `uid=0(root)`；
 *  · 容器内普通 `fork+exec`、两层三层嵌套 exec → 全正常；
 *  · 容器内 `exec -a -sh /bin/sh -c`（dropbear 用的登录 shell 调用形式）→ 正常；
 *  · **自己写的 pty 探测程序**（`posix_openpt` + `grantpt` + `unlockpt` + fork +
 *    setsid + `TIOCSCTTY` + dup2 + execv）：`RESULT: PTY_CHAIN_OK`，子进程 `id -un`
 *    返回 `root`。
 *
 * 也就是说：pty 能用、exec 能用、容器身份是 root，但 dropbear 自己那套启动序列
 * （fork 两次 + 会话化 + 在信号/会话状态上做手脚后再 exec）踩到了 loader 自我
 * 重执行路径（`app/src/main/cpp/dsh_alpine.c` 里 `REEXEC: preload FAIL`）的边角。
 * 追下去要改 loader，风险远高于收益 —— 那条路径是全仓最敏感的地方（GPF/寄存
 * 器回写、栈和 TLS 都在里面）。
 *
 * ## 所以改在 App 进程内跑服务端
 *
 * ```
 * PC: ssh -p 2222 dsh@<phone-ip>
 *        │
 *        ├─ MINA SSHD（本文件，App 进程内）
 *        │
 *        └─ LinuxContainerRunner.run("/bin/sh", ["-c", cmd])
 *              └─ seccomp 容器（产线已用 40+ 轮的稳定路径）
 * ```
 *
 * 好处不只是「避开 loader 边角」：
 *  1. 所有命令走 `LinuxContainerRunner`，那是**已经被上千次调用验证过**的路径；
 *  2. 能用 `CapabilityGate` 管权限（新增 `cap.ssh`），和相机/定位/容器一个模型；
 *  3. 不污染 rootfs（不用装 dropbear，省 8MB，也不受 Alpine 升级影响）；
 *  4. 密钥与配对复用 [RemoteAccess] 的机制，用户不用记两套凭据。
 *
 * ## 明确不支持的东西（诚实分列，别指望）
 *
 * · **sftp / scp** —— 没引 `sshd-sftp`。VS Code Remote-SSH 不用它；需要传文件
 *   时在命令里跑 `cat > /path <<'EOF'` 或 `base64 -d` 就行。
 * · **端口转发**（`-L`/`-R`/`-D`）—— 没接 forwarding filter。需要访问容器内
 *   服务时用 `ssh -p 2222 ... "curl localhost:8080"` 或走 [RemoteAccessServer]。
 * · **真正的交互式 shell（PTY）** —— 见下。
 *
 * ## ★ 为什么 shell 通道只做了「行缓冲直通」而不是完整 PTY
 *
 * 容器内的 `runner` 支持真 PTY（`dsh_pty.c`），但那条路是给**本地终端控件**
 * 用的（`TerminalSession`），它直接持有 fd 并做字节流转发。通过 SSH 走它需要
 * 把 fd 生命周期跨 JNI 交给 MINA 的 channel —— 两边清理时序很难对齐，一旦
 * 泄漏就是「容器进程残留」，比没有交互 shell 更糟。
 *
 * 当前实现：非交互 `exec` 请求（`ssh host "cmd"`）完整可用，这是
 * VS Code Remote-SSH、`scp` 替代、脚本自动化的全部所需；纯交互 `ssh host`
 * 给一个**行缓冲**的最小 shell（能跑命令、有提示符，但没有行编辑与 Ctrl-C 传递）。
 * 用户如果要在手机容器里长时间手敲命令，用 App 内的终端控件体验更好。
 */
object LinuxSshServer {

    private const val TAG = "LinuxSsh"

    /** 独立端口。避开 3080 引擎 / 3083 桥 / 3090 LAN Web / 3091 配对面板。 */
    const val PORT = 2222

    /**
     * SSH 用户名。固定 `dsh` —— 不暴露容器内的 root 用户名，也不允许用户改。
     *
     * 为什么不直接用 `root`：SSH 用户名会出现在 `ps`、日志、连接串里。叫 `dsh`
     * 能让人一眼看出这是「手机里的那个容器」，而实际进容器后仍是 root。
     */
    const val USER = "dsh"

    private const val PREFS = "linux_ssh"

    private const val KEY_ENABLED = "enabled"

    /** 密码（用户自设）。空 = 未配置，服务不启动。 */
    private const val KEY_PASSWORD = "password"

    @Volatile private var server: SshServer? = null

    @Volatile private var lastError: String? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 开关与凭据

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun hasPassword(ctx: Context): Boolean =
        runCatching { prefs(ctx).getString(KEY_PASSWORD, null) }.getOrNull()?.isNotBlank() == true

    fun lastError(): String? = lastError

    /**
     * 设置或重置密码。
     *
     * 密码用 SHA-256 + 随机盐存，不存明文 —— 这个文件在 App 私有目录里，但
     * 手机 root 后被读走的成本应该更高一点。SSH 认证时按同样方式算一遍比对。
     */
    fun setPassword(ctx: Context, plain: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = hashPassword(plain, salt)
        prefs(ctx).edit()
            .putString(KEY_PASSWORD, "%s:%s".format(hex(salt), hex(hash)))
            .apply()
        Log.i(TAG, "password set")
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun hashPassword(plain: String, salt: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(plain.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /** 校验密码（常量时间比较）。 */
    private fun verifyPassword(ctx: Context, plain: String): Boolean {
        val stored = prefs(ctx).getString(KEY_PASSWORD, null) ?: return false
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = runCatching { parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
            .getOrNull() ?: return false
        val want = runCatching { parts[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
            .getOrNull() ?: return false
        val got = hashPassword(plain, salt)
        return java.security.MessageDigest.isEqual(got, want)
    }

    /**
     * 开关。**打开前必须已设密码** —— 没有密码的 SSH 端口是纯风险。
     *
     * @return 实际状态（失败时返回 false，原因见 [lastError]）
     */
    fun setEnabled(ctx: Context, on: Boolean): Boolean {
        if (!on) {
            stop()
            prefs(ctx).edit().putBoolean(KEY_ENABLED, false).apply()
            Log.i(TAG, "disabled")
            return false
        }
        if (!hasPassword(ctx)) {
            lastError = "set a password first"
            Log.w(TAG, "refusing to start without a password")
            return false
        }
        val ok = start(ctx)
        prefs(ctx).edit().putBoolean(KEY_ENABLED, ok).apply()
        return ok
    }

    // ------------------------------------------------------------ 生命周期

    /** 启动（幂等）。 */
    fun start(ctx: Context): Boolean {
        if (server != null) return true
        if (!hasPassword(ctx)) {
            lastError = "no password"
            return false
        }
        return runCatching {
            // ★★ 必须在 setUpDefaultServer() **之前**把 user.home 设上。
            //
            // 否则会抛 ExceptionInInitializerError，且 message 为 null（本轮实测）：
            //   java.lang.ExceptionInInitializerError
            //     at org.apache.sshd.common.util.io.PathUtils.getUserHomeFolder(PathUtils.java:144)
            //     at org.apache.sshd.common.config.keys.PublicKeyEntry$LazyDefaultKeysFolderHolder.<clinit>
            //     at org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator$LazyDefaultAuthorizedKeysFileHolder.<clinit>
            //     at org.apache.sshd.server.config.keys.DefaultAuthorizedKeysAuthenticator.<clinit>
            //     at org.apache.sshd.server.ServerBuilder.<clinit>(ServerBuilder.java:81)
            //     at org.apache.sshd.server.SshServer.setUpDefaultServer(SshServer.java:441)
            //
            // 根因：MINA 的静态初始化器要拿「用户主目录」来算 ~/.ssh/authorized_keys 的默认路径，
            // 而 Android **从不设置 `user.home` 系统属性**（桌面 JVM 才有）。于是
            // PathUtils.getUserHomeFolder → System.getProperty("user.home") 为 null → 抛异常，
            // 且因为这个异常发生在 <clinit>，整个 ServerBuilder 类永久 unusable。
            //
            // 为什么不用 `DefaultAuthorizedKeysAuthenticator` 的带参构造绕开：那个类的
            // <clinit> 同样会碰 LazyDefaultAuthorizedKeysFileHolder，绕不掉。必须在类加载
            // 之前把属性补上 —— 这也是唯一的时机。
            if (System.getProperty("user.home").isNullOrBlank()) {
                System.setProperty("user.home", ctx.filesDir.absolutePath)
            }
            val hostKey = File(ctx.filesDir, "ssh/hostkey.ser").apply {
                parentFile?.mkdirs()
            }
            val sshd = SshServer.setUpDefaultServer()
            sshd.port = PORT
            sshd.host = "0.0.0.0"
            // host key：SimpleGeneratorHostKeyProvider 会在文件不存在时生成；
            // 指定 ed25519，Android 上靠 net.i2p.crypto:eddsa 提供实现。
            sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath()).apply {
                algorithm = "EC"
            }
            sshd.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, pass, _ ->
                // 诊断：把「用户名对不对」「密码长度」「哈希对不对」三件事分开打，
                // 否则 auth failed 这一行无法区分是名字错还是密码错（本轮实测踩过）。
                val nameOk = user == USER
                val passOk = verifyPassword(ctx, pass)
                if (!nameOk || !passOk) {
                    Log.w(
                        TAG,
                        "auth failed: nameOk=$nameOk user='$user' passLen=${pass?.length ?: -1} passOk=$passOk " +
                            "storedLen=${ctx.getSharedPreferences("linux_ssh", Context.MODE_PRIVATE).getString("password", null)?.length ?: -1}",
                    )
                }
                nameOk && passOk
            }
            // 公钥认证：读 files/ssh/authorized_keys 逐行比对。
            //
            // 不用 SSHD 自带的 AuthorizedKeysLookupFactory —— 它的构造签名在
            // 2.14.0 里需要 FilePasswordProvider，而我们要的是「无加密的裸公钥
            // 文件」，自己读二十行更清楚，也少一个版本敏感点。
            val authKeys = File(ctx.filesDir, "ssh/authorized_keys")
            sshd.publickeyAuthenticator =
                org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ ->
                    user == USER && authorizedKeysMatch(authKeys, key)
                }
            sshd.commandFactory = ContainerCommandFactory(ctx.applicationContext)
            sshd.shellFactory = ContainerShellFactory(ctx.applicationContext)
            sshd.start()
            server = sshd
            lastError = null
            Log.i(TAG, "ssh server listening on 0.0.0.0:$PORT (user=$USER)")
            true
        }.getOrElse {
            // ★ 必须打完整堆栈：ExceptionInInitializerError 的 message 恒为 null，
            // 只打 message 得到的就是 "ExceptionInInitializerError: null" 这种
            // 零信息量的日志（本轮实测踩过）。真正的根因在 cause 里。
            lastError = it.message ?: it::class.simpleName
            Log.w(TAG, "start failed: ${it::class.simpleName}: ${it.message}", it)
            it.cause?.let { c ->
                Log.w(TAG, "  caused by: ${c::class.java.name}: ${c.message}", c)
            }
            runCatching { server?.stop(true) }
            server = null
            false
        }
    }

    fun stop() {
        runCatching { server?.stop(true) }
        server = null
        Log.i(TAG, "stopped")
    }

    fun isRunning(): Boolean = server?.isStarted == true

    // ------------------------------------------------------------ 公钥

    /**
     * `authorized_keys` 是否包含给定公钥。
     *
     * 只做最基本的「逐行比较 base64 部分」，不解析 options 前缀（`command=`、
     * `from=` 之类）。理由：本机只服务一个人，要精细控制在容器里用
     * `CapabilityGate` 管，而不是在 SSH 层做限制 —— 后者容易被绕过（用户进容器
     * 后还能跑别的）。
     */
    private fun authorizedKeysMatch(file: File, key: java.security.PublicKey): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        // ★ 关键：不能拿 `key.encoded`（那是 X.509 SubjectPublicKeyInfo 的 DER）去和
        //   authorized_keys 里的 base64 比 —— 后者是 OpenSSH 自己的 wire format
        //   （算法名长度 + 算法名 + 密钥长度 + 密钥），两者 base64 **完全不同**。
        //
        //   本轮实测：密钥内容已正确写入、SELinux 上下文也对，但比对恒 false，
        //   症状是 ssh 报 "Permission denied (password,keyboard-interactive,publickey)"
        //   而服务端日志里连一行 auth failed 都没有（因为 PublickeyAuthenticator
        //   返回 false 时不打日志）。就是这里格式用错了。
        //
        //   正解：用 MINA 的 `PublicKeyEntry.toString(PublicKey)` —— 它直接产出
        //   "ssh-ed25519 AAAA..." 这种 authorized_keys 行格式（实测确认：
        //   javap 看到 `public static String toString(java.security.PublicKey)`）。
        //
        //   备选路径 PublicKeyEntry#appendPublicKey(SessionContext, Appendable, ...)
        //   也能做同样的事，但它要 SessionContext，这里没有。
        val want = org.apache.sshd.common.config.keys.PublicKeyEntry
            .toString(key)
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?: return@runCatching false
        file.readLines().any { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@any false
            // 一行是 "ssh-ed25519 AAAA... [comment]"，只比 base64 那一列
            t.split(Regex("\\s+")).any { it == want }
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------ 命令工厂

    /**
     * 非交互 `exec`（`ssh host "cmd"`）—— 走这条。
     *
     * 直接把整条命令丢给 `LinuxContainerRunner.shell()`，它内部已处理：
     * 信号量并发限制（最多 3 个容器进程）、超时、编码、输出收集。
     */
    private class ContainerCommandFactory(private val ctx: Context) : CommandFactory {
        override fun createCommand(channel: ChannelSession, command: String): Command =
            ContainerCommand(ctx, command)
    }

    /**
     * 交互式 shell（`ssh host`，没有命令）—— 走这条。
     *
     * 实现是**行缓冲**：读一行、交容器执行、把输出写回，再打提示符。
     * 不是完整 PTY（没有行编辑、Ctrl-C 传不到容器内），但能跑命令、能看输出。
     * 为什么不做完整 PTY：见类注释「不支持的东西」一节。
     */
    private class ContainerShellFactory(private val ctx: Context) : ShellFactory {
        override fun createShell(channel: ChannelSession): Command = ContainerShell(ctx)
    }

    /** 把 `runner.shell()` 的结果写回 SSH 流。 */
    private open class Sink(
        private val ctx: Context,
        private val command: String,
    ) : Command {
        private var out: OutputStream? = null
        private var err: OutputStream? = null
        private var input: InputStream? = null
        private var exitCallback: org.apache.sshd.server.ExitCallback? = null
        @Volatile private var cancelled = false

        override fun setInputStream(inp: InputStream) { input = inp }
        override fun setOutputStream(o: OutputStream) { out = o }
        override fun setErrorStream(e: OutputStream) { err = e }
        override fun setExitCallback(cb: org.apache.sshd.server.ExitCallback) { exitCallback = cb }

        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            Thread({
                var code = 0
                try {
                    val r = LinuxContainerRunner.get(ctx).shell(command, timeoutMs = 300_000L)
                    out?.let {
                        if (r.stdout.isNotEmpty()) it.write(r.stdout.toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                    if (r.stderr.isNotEmpty()) {
                        err?.let {
                            it.write(r.stderr.toByteArray(Charsets.UTF_8))
                            it.flush()
                        }
                    }
                    code = r.exitCode.coerceIn(0, 255)
                } catch (e: Exception) {
                    runCatching {
                        err?.write(("ssh: ${e.message}\n").toByteArray(Charsets.UTF_8))
                        err?.flush()
                    }
                    code = 1
                } finally {
                    if (!cancelled) runCatching { exitCallback?.onExit(code) }
                }
            }, "SshCmd").apply { isDaemon = true; start() }
        }

        override fun destroy(channel: ChannelSession) {
            cancelled = true
            // 容器命令无法从外部中途杀掉（runner 内部按超时收尾），
            // 所以这里只标记取消，真正的进程会在超时或自然结束时退出。
        }
    }

    /** `exec` 命令。 */
    private class ContainerCommand(ctx: Context, command: String) : Sink(ctx, command)

    /**
     * 交互 shell：行缓冲循环。
     *
     * 提示符用 `dsh:<cwd>$ ` 形式 —— 不假装是容器里的 `~ #`，免得用户以为
     * 这是完整终端（它不是：没有作业控制、没有 Ctrl-C）。
     */
    private class ContainerShell(private val ctx: Context) : Command {
        private var out: OutputStream? = null
        private var err: OutputStream? = null
        private var input: InputStream? = null
        private var exitCallback: org.apache.sshd.server.ExitCallback? = null
        @Volatile private var cancelled = false

        override fun setInputStream(inp: InputStream) { input = inp }
        override fun setOutputStream(o: OutputStream) { out = o }
        override fun setErrorStream(e: OutputStream) { err = e }
        override fun setExitCallback(cb: org.apache.sshd.server.ExitCallback) { exitCallback = cb }

        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            Thread({
                val runner = LinuxContainerRunner.get(ctx)
                try {
                    out?.write(
                        ("ds h-android container shell (line-buffered, no job control)\n" +
                            "type 'exit' to quit\n\n").toByteArray(Charsets.UTF_8),
                    )
                    out?.flush()
                    val reader = input?.bufferedReader(Charsets.UTF_8)
                    while (!cancelled) {
                        out?.write("dsh$ ".toByteArray(Charsets.UTF_8))
                        out?.flush()
                        val line = reader?.readLine() ?: break
                        val cmd = line.trim()
                        if (cmd.isEmpty()) continue
                        if (cmd == "exit" || cmd == "logout") break
                        val r = runner.shell(cmd, timeoutMs = 300_000L)
                        if (r.stdout.isNotEmpty()) {
                            out?.write(r.stdout.toByteArray(Charsets.UTF_8))
                            out?.flush()
                        }
                        if (r.stderr.isNotEmpty()) {
                            err?.write(r.stderr.toByteArray(Charsets.UTF_8))
                            err?.flush()
                        }
                    }
                    runCatching { exitCallback?.onExit(0) }
                } catch (e: Exception) {
                    runCatching {
                        err?.write(("ssh: ${e.message}\n").toByteArray(Charsets.UTF_8))
                        err?.flush()
                    }
                    runCatching { exitCallback?.onExit(1) }
                }
            }, "SshShell").apply { isDaemon = true; start() }
        }

        override fun destroy(channel: ChannelSession) { cancelled = true }
    }
}

package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Linux 容器执行器 —— 在 rootfs 内跑命令的唯一入口。
 *
 * ## 职责边界
 *
 * 本类**不**负责部署（那是 [LinuxEnvironmentManager] 的事），只负责：
 * 1. 拼出正确的 argv（`dsh_alpine ROOTFS INTERP GUEST ...`）
 * 2. 注入容器内必需的环境变量（`PATH` / `HOME` / `TMPDIR` / apk 镜像配置）
 * 3. 处理退出码与超时，把 stderr 收进结果对象
 *
 * ## 两条容易踩的规则
 *
 * **一、绝不能把宿主路径传给 guest。** 容器内的 `/tmp` 是 rootfs 里的 `/tmp`，
 * 宿主的 `/data/local/tmp` 在 guest 里**不存在**。历史上我把启动脚本写到宿主
 * `/tmp` 再让容器 `sh /tmp/x.sh`，报 `can't open '/tmp/x.sh': No such file`，
 * 排查了很久。所有要传给 guest 的文件路径都必须是 **rootfs 内的路径**。
 *
 * **二、`TMPDIR` 必须显式设成 `/tmp`。** 容器进程默认继承宿主的
 * `TMPDIR=/data/local/tmp`，而这个路径在 guest 里不存在，会让 DSH 引擎的
 * spill-local 插件直接失败（实测报 `ENOENT: mkdtemp '/data/local/tmp/dsh-spill-XXXXXX'`）。
 *
 * ## 关于 rootfs 内没有 DNS
 *
 * 容器内 musl 直连 UDP 53 被 Android netd/SELinux 拦截（实测 `nslookup` 报
 * `write to '223.5.5.5': Operation not permitted`），所以 `apk` 靠静态
 * `/etc/hosts` 解析。本类在首次执行前确保这两个文件存在且内容正确。
 */
class LinuxContainerRunner(private val ctx: Context) {

    /** 结果对象 */
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val durationMs: Long = 0L,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
        /** 合并输出，便于把原始报错贴给用户（纪律：跑不通必须贴原始报错） */
        fun combined(): String =
            buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(stderr.trim())
                }
            }.trim()
    }

    private val env = LinuxEnvironmentManager.get(ctx)

    /**
     * 在 rootfs 内执行一条命令。
     *
     * @param guestPath rootfs 内的程序路径，如 `/bin/busybox` 或 `/usr/bin/node`
     * @param args      传给该程序的参数（rootfs 内的路径语义）
     * @param timeoutMs 超时；超时后强杀进程树
     * @param workdir   rootfs 内的工作目录（null = 不切换）
     * @param extraEnv  额外环境变量（形如 `KEY=VALUE`）
     */
    fun run(
        guestPath: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 60_000L,
        workdir: String? = null,
        extraEnv: List<String> = emptyList(),
        stdin: String? = null,
        /**
         * 跳过 [ensureReady]。
         *
         * ⚠️ 这是给**基础包安装自己**用的逃生门。ensureReady() 会触发
         * ensureBootstrapPackages()，而后者又要调 run() 去跑 apk ——
         * 不加这个开关就是 run → ensureReady → bootstrap → run 的无限递归，
         * 表现为启动卡住、后台线程打转、.dsh-bootstrap 永远写不出来。
         * 那条路径上的 rootfs / 运行器 / 配置文件都已由外层 ensureReady 检查过。
         */
        skipReady: Boolean = false,
        /**
         * 逐行回调（可选）。非 null 时每读到一行就回调一次，用于「装包/下载要给
         * 用户看进度」的长任务 —— 这些命令可能跑几分钟，只在结束时返回结果的话
         * 界面上只能干转圈，用户既不知道还在动、也不知道卡在哪一步。
         *
         * 回调在读取线程上执行，**不得做 UI 操作**（调用方自行 runOnUiThread）。
         * 抛异常会被吞掉并继续读流，不会中断子进程。
         */
        onLine: ((String) -> Unit)? = null,
    ): Result {
        if (!skipReady && !ensureReady()) {
            return Result(-2, "", "Linux environment not ready (rootfs deploy failed)")
        }

        // 动态链接器必须跟随**当前发行版**：Alpine 用 musl 的 ld-musl-*，
        // glibc 用 ld-linux-*。传错的话容器连第一个程序都起不来
        // （musl interp 加载 glibc 二进制报 "LOADER: preload FAIL"）。
        val option = env.activeOption()
        val argv = buildList {
            addAll(EngineConfig.linuxContainerArgv(ctx, env.rootfsDir(option), option.interpPath))
            add(guestPath)
            addAll(args)
        }
        val t0 = System.currentTimeMillis()
        return exec(argv, timeoutMs, workdir, extraEnv, stdin, onLine)
            .copy(durationMs = System.currentTimeMillis() - t0)
    }

    /**
     * 便利方法：在 rootfs 内跑 shell 片段。
     *
     * shell 路径按发行版选：Alpine 只有 busybox 的 `sh`（没有 /bin/sh 实体，
     * 但 /bin/sh -> busybox 的链接存在）；glibc 发行版有 dash 的 `/bin/sh`，
     * 且**没有 busybox**。用 `/bin/sh` 两边都能跑。
     */
    fun shell(
        script: String,
        timeoutMs: Long = 60_000L,
        workdir: String? = null,
        extraEnv: List<String> = emptyList(),
    ): Result = run("/bin/sh", listOf("-c", script), timeoutMs, workdir, extraEnv)

    /**
     * 在容器内执行 argv 并收集输出。
     *
     * ⚠️ 并发控制：[SEM] 限制同时最多 3 个容器进程。不是性能考虑，而是
     * **稳定性**：Android 12+ 的 phantom process killer 在进程数超过约 32 时
     * 会杀 rootfs 进程；而且实测同时跑 3 个满负载引擎会把设备内存压到接近
     * OOM（15016MB 里用掉 14547MB，设备直接重启）。
     */
    private fun exec(
        argv: List<String>,
        timeoutMs: Long,
        workdir: String?,
        extraEnv: List<String>,
        stdin: String?,
        onLine: ((String) -> Unit)? = null,
    ): Result {
        var permit = false
        return try {
            // ★等待许可的最长时间 = min(timeoutMs, SEM_WAIT_MAX_MS)。
            //
            // 原来直接用 timeoutMs（默认 60 秒），后果是：只要许可被占满，
            // 这次调用就白等 60 秒才开始干活。而这个 Semaphore 会被**冻死**的
            // 子进程长期占住 —— Android 的 Cgroup Freezer 在息屏/后台会把整个
            // 进程组冻在 do_freezer_trap，容器子进程跟着冻住、永不退出。
            // 实测：Ubuntu 模式 spawn 固定卡 60195ms，就是在这里等满了一整个
            // timeoutMs（当时有 4 个 quirks 容器残留，wchan 全是 do_freezer_trap）。
            //
            // 抢不到许可说明有别的容器在跑，等 8 秒已经足够让正常的短命调用让出；
            // 再久就是「冻死的残留」，等下去毫无意义 —— 直接如实报错。
            val waitMs = minOf(timeoutMs, SEM_WAIT_MAX_MS)
            permit = SEM.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
            if (!permit) {
                return Result(
                    -3,
                    "",
                    "concurrency limit reached (waited ${waitMs}ms, >${SEM.availablePermits()} container processes)",
                )
            }
            doExec(argv, timeoutMs, workdir, extraEnv, stdin, onLine)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result(-4, "", "interrupted: ${e.message}")
        } finally {
            if (permit) SEM.release()
        }
    }

    private fun doExec(
        argv: List<String>,
        timeoutMs: Long,
        workdir: String?,
        extraEnv: List<String>,
        stdin: String?,
        onLine: ((String) -> Unit)? = null,
    ): Result {
        val pb = ProcessBuilder(argv)
        pb.redirectErrorStream(false)

        // 容器内环境：显式覆盖宿主的路径类变量（见类注释第二条）
        val e = pb.environment()
        e["PATH"] = CONTAINER_PATH
        e["HOME"] = "/root"
        e["TMPDIR"] = "/tmp"
        e.remove("TMP")
        e.remove("TEMP")
        e.remove("ANDROID_DATA")
        e.remove("ANDROID_ROOT")
        e.remove("LD_PRELOAD")
        e.remove("LD_LIBRARY_PATH")
        // ★DNS 免 root 桥接：容器内 musl 把 DNS 端口硬编码成 53，而 53 是特权
        //   端口（App uid 绑不上），所以由 loader 双向改写端口（见 dsh_alpine.c
        //   的 sendto/recvfrom 分支），端口号从这里读。
        //
        //   ★这段以前只在 LinuxEngineLauncher 里注入，于是「引擎自己」能联网，
        //   而所有走本执行器的路径（扩展安装 `apk add`、终端里 `wget`/`curl`、
        //   包管理器 `apk update`）通通解析失败，报的是
        //       WARNING: updating and opening http://mirrors.aliyun.com/...:
        //       temporary error (try again later)
        //   —— 看起来像镜像站挂了，实际是 DNS 包发到 53 无人监听。实测补上这
        //   一个变量后 `apk update` 立刻成功（26337 个包可达）。
        //
        //   extraEnv 里的同名值优先（调用方显式指定时以它为准）。
        LinuxDnsProxy.currentPort().takeIf { it > 1023 }?.let {
            e["DSH_DNS_PORT"] = it.toString()
        }
        extraEnv.forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) e[kv.substring(0, i)] = kv.substring(i + 1)
        }
        if (workdir != null) {
            // 宿主要能看到这个目录才能真正设 cwd：rootfs 内路径 → 宿主路径
            val hostDir = File(env.rootfsDir(), workdir.trimStart('/'))
            if (hostDir.isDirectory) pb.directory(hostDir)
        }

        val proc = try {
            pb.start()
        } catch (ex: Exception) {
            return Result(-5, "", "failed to start container: ${ex.message}")
        }

        if (stdin != null) {
            runCatching {
                proc.outputStream.use { it.write(stdin.toByteArray()) }
            }
        } else {
            runCatching { proc.outputStream.close() }
        }

        val outSb = StringBuilder()
        val errSb = StringBuilder()
        val tOut = Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(outSb) {
                            // 上限保护：容器内程序若刷屏（实测 apk 在失败重试时会
                            // 无限输出），无上限累积会 OOM（实测要 76MB 分配）。
                            // 只保留前 FINAL 字节，超出丢弃但继续读（不能停读，
                            // 否则管道写满会把子进程卡死）。
                            if (outSb.length < OUT_CAP) outSb.append(buf, 0, n)
                        }
                        // ★流式回调：装包/下载这类长任务要靠它给用户看进度。
                        //   回调异常必须吞掉 —— 界面侧一个小 bug 不该让整条
                        //   装包命令失败。
                        if (onLine != null) {
                            runCatching { onLine(String(buf, 0, n).trimEnd()) }
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val tErr = Thread {
            runCatching {
                proc.errorStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(errSb) {
                            if (errSb.length < OUT_CAP) errSb.append(buf, 0, n)
                        }
                        // stderr 也走同一条回调：apk/dpkg 的失败原因全在 stderr，
                        // 只回 stdout 的话用户在进度窗里看不到任何报错线索。
                        if (onLine != null) {
                            runCatching { onLine(String(buf, 0, n).trimEnd()) }
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            // 强杀整棵进程树：容器的 execve 会 fork 子进程（自我重执行 + guest 子进程），
            // 只 kill 父进程会留下孤儿抱住 PTY/端口（实测 proot 残留占端口导致
            // EADDRINUSE，下一轮直接失败）
            killTree(proc)
            tOut.join(1000)
            tErr.join(1000)
            return Result(-1, outSb.toString(), errSb.toString(), timedOut = true)
        }

        // 等输出线程收尾：进程退出后管道会 EOF，但线程可能尚未读完缓冲区
        tOut.join(2000)
        tErr.join(2000)
        return Result(proc.exitValue(), outSb.toString(), errSb.toString())
    }

    /** 递归杀进程树（先 TERM 后 KILL） */
    private fun killTree(proc: Process) {
        val pid = pidOf(proc)
        runCatching { proc.destroy() }
        if (pid != null) {
            runCatching {
                File("/proc/$pid/task/$pid/children").takeIf { it.isFile }?.readText()?.split(' ')?.forEach { c ->
                    c.trim().toIntOrNull()?.let { cp ->
                        runCatching { android.os.Process.killProcess(cp) }
                    }
                }
            }
        }
        runCatching { proc.destroyForcibly() }
    }

    private fun pidOf(proc: Process): Int? = runCatching {
        val f = Process::class.java.getDeclaredField("pid").apply { isAccessible = true }
        f.getInt(proc)
    }.getOrNull()

    // ==================== 就绪保障 ====================

    /**
     * 确保 ①rootfs 已部署 ②运行器已落地且是固定基址 ET_EXEC ③apk 配置就位。
     *
     * 每次调用都会做（都是廉价的文件判断，不是全量扫描）：
     * 真正的解包只发生一次（[LinuxEnvironmentManager.ensureDeployed] 幂等）。
     */
    fun ensureReady(): Boolean {
        // ① 运行器
        val runner = EngineConfig.deployLinuxRunner(ctx)
        if (runner == null) {
            Log.e(TAG, "runner not in APK")
            return false
        }
        if (!EngineConfig.isFixedBaseLinuxRunner(runner)) {
            Log.e(TAG, "runner is not a fixed-base ET_EXEC — refusing to run (ASLR would break the BPF allowlist)")
            return false
        }

        // ② rootfs
        val st = env.ensureDeployed(env.activeOption())
        if (st.state != LinuxEnvironmentManager.State.READY) {
            Log.e(TAG, "rootfs not ready: ${st.state} ${st.detail}")
            return false
        }

        // ③ 发行版配置（hosts + resolv.conf + 镜像源）：幂等，内容不一致才写。
        //    配置本身在 ensureDeployed 里写过；这里重复一次是为了覆盖
        //    「rootfs 早就部署好、但之后 resolv.conf 被别的程序覆盖」的情况。
        runCatching { env.writeDistroConfigPublic(env.activeOption()) }
            .onFailure { Log.w(TAG, "distro config failed: ${it.message}") }

        // ★基础包（bash / curl / ca-certificates）**不在这里装**。
        //
        // 它们是部署阶段的产物：ensureDeployed() 会在解包后同步装完并写
        // `.dsh-bootstrap` 标记。启动路径只做纯本地文件判断，一次联网都不做。
        //
        // 早先这里挂了个后台线程每次启动都调 ensureBootstrapPackages()，
        // 看似「幂等所以没代价」，实际代价是：一旦上次没装成（Ubuntu 侧
        // apt-key 失败 ⇒ 标记永不出现），每次冷启就重跑整套联网流程，
        // Ubuntu 因此要 63 秒、Alpine 只要 0.1 秒。
        // **启动路径上不许有任何可能联网的动作。**
        return true
    }

    companion object {
        private const val TAG = "LinuxRunner"

        /** 换行符常量，避免字符串转义被改代码时误伤。 */
        private const val NL = "\n"

        /**
         * 输出累积上限（字符数，约 256K）。
         *
         * 容器内程序失败重试时可能无限刷屏 —— 实测 `apk update` 在 App 进程里
         * 遇到网络错误后持续重试，Java 侧累积到 76MB 直接 OOM。这里只保留前
         * [OUT_CAP] 字符，超出丢弃但**继续读**（不能停读，否则管道写满会把子进程
         * 卡死）。诊断信息都在开头，截断尾部无损失。
         */
        private const val OUT_CAP = 256 * 1024

        /** 容器内 PATH。与 Alpine 默认一致，前置 sbin 便于用系统工具。 */
        private const val CONTAINER_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        private val SEM = Semaphore(3)

        /**
         * 等容器许可的上限。见 [exec] 里的说明：死亡许可会永久占位，
         * 等满 timeoutMs（60s）只会把启动拖成 60 秒。
         */
        private const val SEM_WAIT_MAX_MS = 8_000L

        @Volatile
        private var instance: LinuxContainerRunner? = null

        fun get(ctx: Context): LinuxContainerRunner =
            instance ?: synchronized(this) {
                instance ?: LinuxContainerRunner(ctx.applicationContext).also { instance = it }
            }
    }
}

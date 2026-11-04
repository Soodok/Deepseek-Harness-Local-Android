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
    ): Result {
        if (!ensureReady()) {
            return Result(-2, "", "Linux environment not ready (rootfs deploy failed)")
        }

        val argv = buildList {
            addAll(EngineConfig.linuxContainerArgv(ctx, env.rootfsDir(), EngineConfig.linuxMuslInterp(ctx)))
            add(guestPath)
            addAll(args)
        }
        val t0 = System.currentTimeMillis()
        return exec(argv, timeoutMs, workdir, extraEnv, stdin).copy(durationMs = System.currentTimeMillis() - t0)
    }

    /** 便利方法：在 rootfs 内跑 shell 片段 */
    fun shell(
        script: String,
        timeoutMs: Long = 60_000L,
        workdir: String? = null,
        extraEnv: List<String> = emptyList(),
    ): Result = run("/bin/busybox", listOf("sh", "-c", script), timeoutMs, workdir, extraEnv)

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
    ): Result {
        var permit = false
        return try {
            permit = SEM.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
            if (!permit) {
                return Result(-3, "", "concurrency limit reached (>${SEM.availablePermits()} container processes)")
            }
            doExec(argv, timeoutMs, workdir, extraEnv, stdin)
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
                        synchronized(outSb) { outSb.append(buf, 0, n) }
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
                        synchronized(errSb) { errSb.append(buf, 0, n) }
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
        val st = env.ensureDeployed()
        if (st.state != LinuxEnvironmentManager.State.READY) {
            Log.e(TAG, "rootfs not ready: ${st.state} ${st.detail}")
            return false
        }

        // ③ apk 配置（hosts + resolv.conf + repositories）：幂等，内容不一致才写
        runCatching { writeApkConfig() }
            .onFailure { Log.w(TAG, "apk config failed: ${it.message}") }

        // ④ 基础包（bash / curl / ca-certificates）：幂等，装过就跳过。
        //
        // ⚠️ **必须异步**，绝不能挡在启动路径上。
        //    曾经是同步调用，结果首次启动要现场 `apk add`（联网下这三个包
        //    合计约 2MB，实测多花 4~8 秒），冷启从 1.45 秒退到 6 秒以上。
        //    基础包是**附加能力**（让 AI 的 bash 工具能用），不是启动前提：
        //    引擎跑的是 node，不需要 bash 就能起来。
        //    丢到独立线程后，装完写标记，下次启动就是 O(1) 的文件判断。
        //
        //    时序上够用：引擎起来后还要加载插件（约 1.2 秒）才可能调工具，
        //    那时 apk 早已装完（联网顺利时 2~3 秒）。
        Thread({
            runCatching { env.ensureBootstrapPackages() }
                .onFailure { Log.w(TAG, "bootstrap failed: " + it.message) }
        }, "dsh-linux-bootstrap").apply { isDaemon = true }.start()
        return true
    }

    /**
     * 写 `/etc/hosts` 与 `/etc/apk/repositories`。
     *
     * 只在内容不一致时落盘：这两个文件用户可能改过（换成公司内网镜像），
     * 每次启动无条件覆盖会把用户的改动抹掉。
     */
    private fun writeApkConfig() {
        val root = env.rootfsDir()

        fun writeIfChanged(rel: String, content: List<String>) {
            val want = content.joinToString(NL) + NL
            val f = File(root, rel)
            if (!f.isFile || f.readText() != want) {
                f.parentFile?.mkdirs()
                f.writeText(want)
                Log.i(TAG, "wrote /$rel")
            }
        }

        writeIfChanged("etc/hosts", LinuxEnvironmentManager.HOSTS)
        writeIfChanged("etc/resolv.conf", LinuxEnvironmentManager.RESOLV_CONF)
        writeIfChanged("etc/apk/repositories", LinuxEnvironmentManager.APK_REPOSITORIES)
    }

    companion object {
        private const val TAG = "LinuxRunner"

        /** 换行符常量，避免字符串转义被改代码时误伤。 */
        private const val NL = "\n"

        /** 容器内 PATH。与 Alpine 默认一致，前置 sbin 便于用系统工具。 */
        private const val CONTAINER_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        private val SEM = Semaphore(3)

        @Volatile
        private var instance: LinuxContainerRunner? = null

        fun get(ctx: Context): LinuxContainerRunner =
            instance ?: synchronized(this) {
                instance ?: LinuxContainerRunner(ctx.applicationContext).also { instance = it }
            }
    }
}

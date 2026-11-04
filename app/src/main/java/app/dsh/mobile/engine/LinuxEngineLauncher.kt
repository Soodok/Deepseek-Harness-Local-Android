package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.R
import java.io.File

/**
 * Linux 模式下的 DSH 引擎启动器。
 *
 * ## 与原生模式的根本差异
 *
 * | | 原生模式（现状） | Linux 模式（本类） |
 * |---|---|---|
 * | node | `filesDir/engine/bin/node`（bionic 动态） | rootfs 里的 musl node（`apk add nodejs`） |
 * | dsh 代码 | `filesDir/engine/lib/node_modules/...` | **同一份**，通过 bind 挂进容器 |
 * | 进程 | 直接 execve | `dsh_alpine` 容器内 execve |
 * | 工具链 | 无（要装扩展） | 完整 Linux（apk / gcc / python / git / ffmpeg） |
 *
 * ## 为什么引擎代码不复制进 rootfs
 *
 * 引擎的 `node_modules` 有 **355MB**。复制进 rootfs 意味着：
 * - 磁盘翻倍（355MB × 2）
 * - 升级引擎时要同步两边，容易不一致
 * - 首装时间从 1 秒涨到十几秒
 *
 * 改用 `-m /eng:<宿主路径>` bind 映射（实测已验证：容器内 `ls /eng/node_modules/
 * @deepseek-ai/dsh/lib/bin.js` 可见、`node --version` 可跑）。代价是 bind 映射
 * 是**字符串前缀替换**而非真挂载，所以 engine 路径下不能再有指向别处的绝对软链
 * —— 引擎目录里没有软链（`lib/` 是实体目录），已验证。
 *
 * ## 回退策略
 *
 * 本类只负责「构造 Linux 模式的启动参数」。失败时由调用方决定是否回退到原生
 * 模式 —— 我们的立场是**绝不静默降级**：回退必须在 UI 上明确标记，否则用户会
 * 以为自己正在用完整 Linux 环境。
 */
class LinuxEngineLauncher(private val ctx: Context) {

    private val env = LinuxEnvironmentManager.get(ctx)

    /** Linux 模式下要 bind 进容器的宿主目录（guest 路径 -> 宿主绝对路径） */
    private data class Bind(val guest: String, val host: File)

    /**
     * 检查 Linux 模式是否可用（不启动任何东西）。
     *
     * 逐项检查，每项失败都给出**可操作的原因**，而不是笼统的「不可用」。
     */
    fun readiness(): Readiness {
        val runner = EngineConfig.deployLinuxRunner(ctx)
            ?: return Readiness.Unavailable("Linux runner missing from APK (build configuration issue)")
        if (!EngineConfig.isFixedBaseLinuxRunner(runner)) {
            return Readiness.Unavailable("Linux runner is not a fixed-base ET_EXEC; refusing to run (would crash randomly)")
        }

        val st = env.status()
        when (st.state) {
            LinuxEnvironmentManager.State.NOT_DEPLOYED ->
                return Readiness.NeedsDeploy(st)
            LinuxEnvironmentManager.State.BROKEN ->
                return Readiness.Unavailable("Linux environment broken: ${st.detail}")
            LinuxEnvironmentManager.State.DEPLOYING ->
                return Readiness.Unavailable("Linux environment is being deployed")
            LinuxEnvironmentManager.State.READY -> Unit
        }

        // rootfs 里必须有 node（DSH 引擎依赖）。没装时给出可操作的提示，
        // 而不是等到 spawn 后才报 "node: not found"。
        val nodeOk = LinuxContainerRunner.get(ctx)
            .run("/bin/busybox", listOf("sh", "-c", "command -v node >/dev/null 2>&1"), 30_000)
        if (!nodeOk.ok) {
            return Readiness.NeedsPackages(
                ctx.getString(R.string.linux_err_need_node)
            )
        }
        return Readiness.Ready(st)
    }

    sealed class Readiness {
        /** 可以直接启动引擎 */
        data class Ready(val status: LinuxEnvironmentManager.Status) : Readiness()

        /** rootfs 尚未部署，需要先调 ensureDeployed */
        data class NeedsDeploy(val status: LinuxEnvironmentManager.Status) : Readiness()

        /** rootfs 就绪但缺少运行时（如 node） */
        data class NeedsPackages(val reason: String) : Readiness()

        /** 环境层面不可用，只能回退原生模式 */
        data class Unavailable(val reason: String) : Readiness()
    }

    /**
     * 构造在 Linux 容器内启动 DSH 引擎的完整 argv 与环境。
     *
     * 产物可直接交给 [EngineProcess] 的同类逻辑（但这里返回裸数据，便于测试与
     * 日志打印，不直接耦合进程管理）。
     *
     * ## argv 结构
     *
     * ```
     * dsh_alpine -m /eng:<engineRoot> -m /sdcard:<externalDir>
     *            <rootfs 宿主路径> <musl interp> /usr/bin/node
     *            --expose-internals /eng/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
     *            web --patch /eng/... --no-open
     * ```
     *
     * ## 几个必须保留的原生侧行为
     *
     * - `--expose-internals`：引擎依赖 `internal/modules/esm/loader`，缺了直接
     *   `Cannot find module`（node-addon-require-builtin 的硬需求）。
     * - patch 顺序：SSH 插件层在前，Android 适配层在后（后应用者生效）。
     * - `cwd` 用 workspaces 目录（同样 bind 进容器）。
     */
    fun buildEngineArgv(
        port: Int,
        bootId: String,
        patchPaths: List<String>,
    ): LaunchSpec {
        val engineRoot = EngineConfig.engineRoot(ctx)
        val workspaces = EngineConfig.workspaces(ctx)
        val rootfs = env.rootfsDir()
        val runner = File(File(ctx.filesDir, "linux"), "runner")

        // bind 表：guest 路径 -> 宿主目录
        val binds = buildList {
            add(Bind("/eng", engineRoot))
            add(Bind("/ws", workspaces))
            // dsh-home（profile / 插件 / 会话）也要能让引擎读写
            EngineConfig.dshHome(ctx).takeIf { it.isDirectory }?.let { add(Bind("/dsh", it)) }
        }

        val argv = buildList {
            add(File(runner, RUNNER_NAME).absolutePath)
            binds.forEach { b ->
                add("-m")
                add("${b.guest}:${b.host.absolutePath}")
            }
            add(rootfs.absolutePath)
            add(EngineConfig.linuxMuslInterp(ctx))
            add(NODE_IN_CONTAINER)
            add("--expose-internals")
            // 入口 JS 走 bind 后的 guest 路径
            add("/eng/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
            add("web")
            // patch 参数用 guest 路径：patch 文件在 engine 目录下（已被 /eng bind）
            patchPaths.forEach { p ->
                val rel = runCatching { p.removePrefix(engineRoot.absolutePath).trimStart('/') }
                    .getOrDefault("")
                if (rel.isNotEmpty()) {
                    add("--patch")
                    add("/eng/$rel")
                }
            }
            add("--no-open")
        }

        val envVars = buildList {
            add("PATH=$CONTAINER_PATH")
            add("HOME=/dsh")
            add("TMPDIR=/tmp")
            add("TERM=xterm-256color")
            add("DSH_HOME=/dsh")
            add("DSH_PROFILE=${runCatching { "web" }.getOrDefault("web")}")
            add("DSH_PROFILE_DIR=/dsh/profiles/web")
            add("PORT=$port")
            add("NODE_ENV=production")
            add("DSH_BOOT_ID=$bootId")
            // ⚠️ 不设 LD_LIBRARY_PATH / LD_PRELOAD：容器内是 musl 运行时，
            // 宿主的 bionic 库路径会让 musl 的 dlopen 走错路径。
        }

        // 宿主侧工作目录：容器进程的 cwd 用 workspaces（guest 也是 /ws）
        return LaunchSpec(
            argv = argv,
            env = envVars,
            cwd = workspaces,
            guestCwd = "/ws",
        )
    }

    /** 启动规格：可直接交给进程管理器 */
    data class LaunchSpec(
        val argv: List<String>,
        val env: List<String>,
        val cwd: File,
        val guestCwd: String,
    )

    /**
     * 在容器内装一个软件包（供插件中心 / 包管理 UI 复用）。
     *
     * 失败时把 `apk` 的原始 stderr 带回去 —— 纪律：跑不通必须贴原始报错，
     * 不能只回「安装失败」。
     */
    fun apkAdd(packages: List<String>, timeoutMs: Long = 300_000L): LinuxContainerRunner.Result {
        if (packages.isEmpty()) return LinuxContainerRunner.Result(0, "", "")
        val runner = LinuxContainerRunner.get(ctx)
        return runner.run(
            guestPath = "/sbin/apk",
            args = listOf("add", "--no-progress") + packages,
            timeoutMs = timeoutMs,
        )
    }

    /** 已装包列表（`apk info` 输出，每行一个包名） */
    fun apkList(): List<String> {
        val r = LinuxContainerRunner.get(ctx)
            .run("/sbin/apk", listOf("info"), 60_000)
        return if (!r.ok) emptyList() else r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "LinuxEngine"

        /** 容器内 node 的路径（`apk add nodejs` 装到这里） */
        const val NODE_IN_CONTAINER = "/usr/bin/node"

        /** 运行器文件名，与 EngineConfig 保持一致 */
        private const val RUNNER_NAME = "dsh_alpine"

        /** 容器内 PATH，与 LinuxContainerRunner 保持一致 */
        private const val CONTAINER_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        @Volatile
        private var instance: LinuxEngineLauncher? = null

        fun get(ctx: Context): LinuxEngineLauncher =
            instance ?: synchronized(this) {
                instance ?: LinuxEngineLauncher(ctx.applicationContext).also { instance = it }
            }
    }
}

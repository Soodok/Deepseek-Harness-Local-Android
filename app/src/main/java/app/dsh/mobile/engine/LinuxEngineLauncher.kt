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
        //
        // ⚠️ 这一项**必须用静态检查**，不能起容器进程去跑 `command -v node`。
        //
        // 原来确实是这么干的（LinuxContainerRunner.run("/bin/busybox",
        // ["sh","-c","command -v node"], 30_000)），后果是**每次冷启都多一次
        // 完整的容器 fork**：fork dsh_alpine → 打开 rootfs → 载入 musl interp
        // （723KB）→ 载入 busybox → 跑 shell → 退出 → wait4。
        // 实测这段稳定吃 200~280ms，而它要回答的问题只是「node 在不在」。
        //
        // 现在直接看文件：rootfs 宿主路径下有没有 usr/bin/node（或 bin/node）。
        // 这与 `command -v node` 在实践上等价 —— rootfs 是确定性布局
        // （apk add nodejs 只会落在 usr/bin/node），PATH 也是固定的容器默认值。
        // 真正的「能不能跑」由 spawn 后引擎自己的输出证明，不需要前置探测。
        val rootfs = env.rootfsDir()
        val hasNode = File(rootfs, "usr/bin/node").isFile || File(rootfs, "bin/node").isFile
        if (!hasNode) {
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
            // ★变量名必须是 DSH_ANDROID_BOOT_ID，与原生路径
            //   （EngineConfig.buildEnv）以及插件侧读的名字完全一致。
            //
            // 踩过的坑：这里曾写成 DSH_BOOT_ID，而 android-plugins/plugin-health.mjs:316
            // 读的是 `process.env.DSH_ANDROID_BOOT_ID`。名字对不上 → 插件写出的
            // marker 里 bootId 恒为空串 → PluginRecovery.readHealth() 的
            // `json.optString("bootId") != expectedBootId` 判定不匹配 → 返回 null
            // → 监督器认为「marker missing or unreadable」，然后**干等满 20 秒**
            // awaitPluginHealth 超时才放行。这就是 Linux 模式冷启多出 20 秒的全部原因
            // （实测：健康检查 1.9 秒就出了结果，却到 20 秒后才 healthy）。
            add("DSH_ANDROID_BOOT_ID=$bootId")
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

    /**
     * 构造在容器内开一个**交互式登录 shell** 的 argv（供 PTY 会话用）。
     *
     * 与 [buildEngineArgv] 的区别只在命令部分：引擎跑 `node ...`，
     * 终端跑 `/bin/sh -l`。容器前缀（runner + bind 表 + rootfs + interp）完全复用，
     * 所以终端里看到的路径、HOME、工作目录与引擎进程内一致 ——
     * 用户在终端里 `apk add` 装的东西，引擎里的插件也能立刻用到。
     *
     * ## 为什么用 `sh -l` 而不是直接 `/bin/sh`
     *
     * Alpine 的登录 shell 会读 `/etc/profile`，而 `prepare-rootfs.sh` 往
     * `/etc/profile.d/ccache.sh` 写了 ccache 的 PATH 与 CCACHE_DIR。
     * 不带 `-l` 就没有这份 PATH，`ccache` 永远 miss（实测过：`command -v gcc`
     * 返回 `/usr/bin/gcc` 而不是 `/usr/lib/ccache/bin/gcc`）。
     *
     * ## 为什么用 busybox 的 `sh` 而不是 `bash`
     *
     * 极简 rootfs 里**没有 bash**（`/bin/bash` 不存在，busybox 的 sh 才是 ash）。
     * 想要 bash 得用户自己 `apk add bash`；那时 `sh -l` 仍能正常工作，
     * 用户可在终端里 `exec bash -l` 自己切。
     */
    fun buildTerminalArgv(): LaunchSpec {
        val engineRoot = EngineConfig.engineRoot(ctx)
        val workspaces = EngineConfig.workspaces(ctx)
        val rootfs = env.rootfsDir()
        val runner = File(File(ctx.filesDir, "linux"), "runner")

        val binds = buildList {
            add(Bind("/eng", engineRoot))
            add(Bind("/ws", workspaces))
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
            add("/bin/sh")
            // ★必须显式 cd 到 guest 侧的 /ws，不能只靠宿主 cwd。
            //
            // 原因：Termux 的 TerminalSession 把子进程的 cwd 设成**宿主路径**
            // （`EngineConfig.workspaces(ctx)`）。getcwd 虽然有 bind 反向映射，
            // 但那份映射要求宿主路径与 bind 表里的 host 前缀逐字节相同 ——
            // workspaces 在部分设备上落在 `/storage/emulated/0/Documents/dsh data/...`
            // （带空格的分区路径），与 bind 记录的路径出现差异时映射不中，
            // 提示符就原样显示宿主路径（实测：
            //   `localhost:/storage/emulated/0/Documents/dsh data/workspaces$`）。
            // 显式 chdir 一次即可彻底绕开这个问题，且不依赖宿主路径长什么样。
            add("-c")
            add("cd /ws 2>/dev/null || cd /dsh 2>/dev/null || true; exec /bin/sh -l")
        }

        val envVars = buildList {
            add("PATH=$CONTAINER_PATH")
            add("HOME=/dsh")
            add("TMPDIR=/tmp")
            add("TERM=xterm-256color")
            add("DSH_HOME=/dsh")
            // 关掉宿主 bionic 的库路径：容器内是 musl
        }

        return LaunchSpec(
            argv = argv,
            env = envVars,
            cwd = workspaces,
            guestCwd = "/ws",
        )
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

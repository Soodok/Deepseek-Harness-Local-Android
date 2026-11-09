package app.dsh.mobile.engine.cap

import android.content.Context
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.EngineModeSelector
import app.dsh.mobile.engine.LinuxContainerRunner
import app.dsh.mobile.engine.LinuxEnvironmentManager
import app.dsh.mobile.engine.LinuxPackageManager
import app.dsh.mobile.engine.LinuxRootfsCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Linux 容器的 AI 能力面（`/dev/linux/…`）。
 *
 * ## 为什么需要它
 *
 * 此前 Linux 工作台的能力**完全没有暴露**：`EndpointCatalog` 里 `grep linux` 是空的、
 * `AgentBridge` 路由表里没有任何 `/dev/linux*`。后果是 AI 明明知道用户有容器，
 * 却无法回答「装了什么」「能切到哪个发行版」「我能不能在里面跑命令」——
 * 只能让用户自己去点界面，或者盲目 `exec` 一条命令然后从报错里猜。
 *
 * ## 授权模型
 *
 * 整组挂 [CapabilityGate.LINUX]，默认关闭。理由写在那个常量上：
 * `exec` 等价于给 AI 一个容器内 shell（能装包、联网、编东西、长期驻留进程），
 * `switch`/`deploy` 会改变用户环境；而 `status`/`distros` 虽然是只读，
 * 也放同一开关下 —— 让用户只理解「Linux 能力开/关」一件事。
 *
 * ## 五个端点
 *
 * | 端点 | 语义 |
 * |---|---|
 * | `GET  /dev/linux/status`   | 当前发行版 / 是否部署 / 引擎模式 / 磁盘 / 已装包数 |
 * | `GET  /dev/linux/distros`  | 全部可选项（含 flavor）+ 每项体积与状态 |
 * | `POST /dev/linux/switch`   | 切换激活项 `{"family":"alpine","flavor":"gui"}` |
 * | `POST /dev/linux/deploy`   | 部署/重部署 `{"force":false}` |
 * | `POST /dev/linux/exec`     | 容器内执行 `{"cmd":"...","timeout_ms":30000}` |
 *
 * ## exec 的边界（都在响应里如实回报，不假装成功）
 *
 * - **超时硬上限 [EXEC_TIMEOUT_MAX_MS]**：容器里一条命令卡死会占住执行器信号量
 *   （上限 3 个并发），超时必须杀进程并回报 `timed_out=true`
 * - **不是 pty**：`read`/`vi` 这类要交互的命令会立刻 EOF。写进 `limits`，
 *   免得 AI 以为是自己命令写错了
 * - **不注入宿主环境**：`DSH_BRIDGE_TOKEN` 之类绝不进容器（`LinuxContainerRunner`
 *   已有实现保证），所以容器里也调不了 bridge 端点 —— 要在 `limits` 里说明
 */
object LinuxCap {

    /** exec 的默认超时 */
    private const val EXEC_TIMEOUT_DEFAULT_MS = 30_000L

    /**
     * exec 的硬上限。
     *
     * 为什么不给更大：容器执行器是**有界信号量**（实测上限 3 个并发容器进程）。
     * 一条挂住 10 分钟的命令会占掉三分之一，用户其他操作跟着排队。
     * 真要跑长任务，正确做法是 `nohup ... &` 起后台再用 `exec` 查结果。
     */
    private const val EXEC_TIMEOUT_MAX_MS = 120_000L

    private fun deny(): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false)
        .put("error", "capability disabled")
        .put("cap", CapabilityGate.LINUX)
        .put(
            "hint",
            "Enable it in Settings > Permission center > Linux container. " +
                "This capability allows running arbitrary commands inside the container."
        )
        .toString()

    private fun err(code: String, hint: String? = null): String = JSONObject()
        .put("ok", false)
        .put("error", code)
        .apply { if (hint != null) put("hint", hint) }
        .toString()

    // ==================== GET /dev/linux/status ====================

    /**
     * 容器总览。
     *
     * 字段设计原则：**每个字段都要能回答 AI 的下一个问题**。
     * - `engine_mode` → 现在引擎跑在原生还是容器？（决定"我说的路径在哪一侧"）
     * - `deployed` → 能不能直接用
     * - `node_present` → 引擎能不能在这个容器里跑（缺 node 时引擎根本起不来）
     * - `free_bytes` → 装包前先看够不够
     * - `packages_installed` → 已装了哪些（AI 不必自己去 `apk info`）
     */
    fun status(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val env = LinuxEnvironmentManager.get(ctx)
        val opt = env.activeOption()
        val root = env.rootfsDir(opt)

        val nodeRel = when (opt.nodeStrategy) {
            LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE -> listOf("usr/bin/node", "bin/node")
            LinuxRootfsCatalog.NodeStrategy.OFFICIAL_TARBALL ->
                listOf(LinuxRootfsCatalog.GLIBC_NODE_PATH.trimStart('/'))
        }
        val nodePresent = nodeRel.any { java.io.File(root, it).isFile }

        /*
         * 已装包数：静态数数据库文件，比起容器进程快得多（起一次容器 200ms+）。
         *
         * ★必须按包管理器分流：apk 与 dpkg 的数据库格式完全不同。
         *   原实现只读 `lib/apk/db/installed`，在 Ubuntu 上恒返回 0 ——
         *   AI 看到 `packages_installed: 0` 会误判成「空容器什么都没装」，
         *   然后去重复安装一堆已经存在的包。
         */
        val pkgCount = runCatching {
            when (opt.pkgManager) {
                // Alpine：installed 每行一个包，首字段是 P:<name>
                LinuxRootfsCatalog.PackageManager.APK -> {
                    val db = java.io.File(root, "lib/apk/db/installed")
                    if (!db.isFile) 0 else db.readLines().count { it.startsWith("P:") }
                }
                // Debian 系：dpkg 的状态文件每段以 `Package:` 开头
                LinuxRootfsCatalog.PackageManager.APT -> {
                    val db = java.io.File(root, "var/lib/dpkg/status")
                    if (!db.isFile) 0 else db.readLines().count { it.startsWith("Package:") }
                }
            }
        }.getOrDefault(0)

        val body = JSONObject()
            .put("ok", true)
            .put("engine_mode", if (EngineModeSelector.isLinuxEnabled(ctx)) "linux" else "native")
            .put("sandbox", if (app.dsh.mobile.engine.Sandbox.isChrootPreferred(ctx)) "chroot" else "seccomp")
            .put("family", opt.family.id)
            .put("flavor", opt.flavor.id)
            .put("display_name", "${opt.displayName} ${opt.versionLabel}")
            .put("libc", opt.libc)
            .put("pkg_manager", if (opt.pkgManager == LinuxRootfsCatalog.PackageManager.APK) "apk" else "apt")
            .put("deployed", env.isDeployed(opt))
            .put("node_present", nodePresent)
            .put("packages_installed", pkgCount)
            .put("rootfs_host_path", root.absolutePath)
            .put("rootfs_guest_path", "/")
            .put("free_bytes", root.absoluteFile.parentFile?.usableSpace ?: 0L)
            .put(
                "limits",
                JSONArray()
                    .put("exec is not a pty: commands needing stdin (read/vi) get EOF immediately")
                    .put("the container cannot call bridge endpoints (no DSH_BRIDGE_TOKEN inside)")
                    .put("long tasks: use `nohup ... &` then poll, instead of blocking exec"),
            )
        return 200 to body.toString()
    }

    // ==================== GET /dev/linux/distros ====================

    /**
     * 全部可选容器（含 flavor 展开）。
     *
     * `deployed` 按 **family** 判（flavor 共享同一 rootfs 目录），
     * 但 `active` 要同时判 family + flavor —— 否则同一 family 下三行会同时亮。
     */
    fun distros(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val env = LinuxEnvironmentManager.get(ctx)
        val active = env.activeOption()

        val arr = JSONArray()
        LinuxRootfsCatalog.catalog.forEach { opt ->
            val bytes = when (val src = opt.source) {
                is LinuxRootfsCatalog.RootfsSource.Bundled -> src.extractedBytes
                is LinuxRootfsCatalog.RootfsSource.Remote -> src.extractedBytes
            }
            arr.put(
                JSONObject()
                    .put("family", opt.family.id)
                    .put("flavor", opt.flavor.id)
                    .put("id", "${opt.family.id}:${opt.flavor.id}")
                    .put("display_name", "${opt.displayName} ${opt.versionLabel}" +
                        if (opt.flavor == LinuxRootfsCatalog.Flavor.BASE) "" else " · ${opt.flavor.label}")
                    .put("libc", opt.libc)
                    .put("pkg_manager", if (opt.pkgManager == LinuxRootfsCatalog.PackageManager.APK) "apk" else "apt")
                    .put("deployed", env.isDeployed(opt))
                    .put("active", opt.family == active.family && opt.flavor == active.flavor)
                    .put("extracted_bytes", bytes)
                    .put("extra_download_bytes", opt.flavor.extraDownloadBytes)
                    .put("extra_packages", JSONArray(opt.flavor.extraPackages))
                    .put(
                        "shared_rootfs",
                        opt.family.id,
                    )
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("count", arr.length())
            .put(
                "note",
                "Entries with the same family share one rootfs directory; " +
                    "switching flavor only changes which extra packages get installed on next boot. " +
                    "Installed extras are NOT removed when switching back."
            )
            .put("distros", arr)
            .toString()
    }

    // ==================== POST /dev/linux/switch ====================

    /** 切换激活项。只改选择，不动文件（与 UI 的 switchDistro 同语义）。 */
    fun switch(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to err("invalid json")
        val family = obj.optString("family").ifBlank { null }
            ?: return 400 to err("missing family", "e.g. {\"family\":\"alpine\"}")
        val flavorId = obj.optString("flavor").ifBlank { LinuxRootfsCatalog.Flavor.BASE.id }

        val target = LinuxRootfsCatalog.findExact(family, flavorId)
            ?: return 404 to err(
                "unknown distro",
                "GET /dev/linux/distros for valid ids",
            )

        val env = LinuxEnvironmentManager.get(ctx)
        // 磁盘检查只对**未部署**的目标做：已部署的切换不动文件，不需要空间
        if (!env.isDeployed(target)) {
            val need = when (val src = target.source) {
                is LinuxRootfsCatalog.RootfsSource.Bundled -> src.extractedBytes
                is LinuxRootfsCatalog.RootfsSource.Remote -> src.extractedBytes
            }
            val free = env.rootfsDir(target).absoluteFile.parentFile?.usableSpace ?: 0L
            if (free in 1 until need) {
                return 507 to JSONObject()
                    .put("ok", false)
                    .put("error", "insufficient disk space")
                    .put("need_bytes", need)
                    .put("free_bytes", free)
                    .toString()
            }
        }

        /*
         * flavor 不是 family 的一部分，`setActive` 只记 family。
         * 这里额外把 flavor 也记下来 —— 否则切到 "alpine:gui" 之后再读
         * `activeOption()` 会退回 BASE，用户看到「切了但没生效」。
         *
         * 存储上分成两个 key 而不是拼成一个串：family 是长期身份（目录名），
         * flavor 是可变形态，分开存才能在只有 family 的旧版本上优雅降级。
         */
        LinuxRootfsCatalog.setActive(ctx, target.family)
        LinuxRootfsCatalog.setActiveFlavor(ctx, target.flavor)

        CapabilityGate.audit(ctx, "dev.linux.switch:${target.family.id}:${target.flavor.id}")
        return 200 to JSONObject()
            .put("ok", true)
            .put("family", target.family.id)
            .put("flavor", target.flavor.id)
            .put("deployed", env.isDeployed(target))
            .put(
                "next",
                if (env.isDeployed(target)) "restart the engine to use it"
                else "call POST /dev/linux/deploy to install it"
            )
            .toString()
    }

    // ==================== POST /dev/linux/deploy ====================

    /**
     * 部署/重部署当前激活的容器。
     *
     * `force=true` 会 **purge 整个 rootfs 重新解包** —— 用户装的包全丢。
     * 所以默认 false，且响应里明确写出「这一步做了什么」。
     */
    fun deploy(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
        val force = obj.optBoolean("force", false)
        val env = LinuxEnvironmentManager.get(ctx)
        val opt = env.activeOption()

        if (env.isDeployed(opt) && !force) {
            return 200 to JSONObject()
                .put("ok", true)
                .put("already_deployed", true)
                .put("family", opt.family.id)
                .put("flavor", opt.flavor.id)
                .put("hint", "pass {\"force\":true} to reinstall (this DELETES the current rootfs)")
                .toString()
        }

        CapabilityGate.audit(ctx, "dev.linux.deploy:${opt.family.id}:${opt.flavor.id}:force=$force")
        val st = env.ensureDeployed(opt, force = force)
        return if (st.state == LinuxEnvironmentManager.State.READY) {
            200 to JSONObject()
                .put("ok", true)
                .put("family", opt.family.id)
                .put("flavor", opt.flavor.id)
                .put("detail", st.detail)
                .toString()
        } else {
            500 to JSONObject()
                .put("ok", false)
                .put("error", "deploy failed")
                .put("state", st.state.name)
                .put("detail", st.detail)
                .toString()
        }
    }

    // ==================== POST /dev/linux/exec ====================

    /**
     * 在容器里执行一条命令。
     *
     * 这是整组能力里**唯一真正有权力**的一个：AI 可以借它验证环境、装包、
     * 编译东西。安全边界写在类注释与响应的 `limits` 里。
     *
     * 返回 `stdout` / `stderr` / `exit_code` / `timed_out` 四个字段 ——
     * **不合并成 combined**：AI 需要能区分「命令没输出」与「命令报错到 stderr」，
     * 合并了就分不清（这是 `Result.combined()` 在排查时坑过的地方）。
     */
    fun exec(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to err("invalid json")
        val cmd = obj.optString("cmd").ifBlank { null }
            ?: return 400 to err("missing cmd", "e.g. {\"cmd\":\"uname -m\"}")
        val timeout = obj.optLong("timeout_ms", EXEC_TIMEOUT_DEFAULT_MS)
            .coerceIn(1_000L, EXEC_TIMEOUT_MAX_MS)

        val env = LinuxEnvironmentManager.get(ctx)
        val opt = env.activeOption()
        if (!env.isDeployed(opt)) {
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "container not deployed")
                .put("family", opt.family.id)
                .put("hint", "call POST /dev/linux/deploy first")
                .toString()
        }

        CapabilityGate.audit(ctx, "dev.linux.exec:" + cmd.take(120))
        val started = System.currentTimeMillis()
        val r = LinuxContainerRunner.get(ctx).run(
            guestPath = "/bin/sh",
            args = listOf("-c", cmd),
            timeoutMs = timeout,
        )
        val ms = System.currentTimeMillis() - started

        /*
         * 超时判据看**两个**信号：执行器自己的 exitCode 约定，与耗时是否撞到上限。
         * 只判耗时会把「刚好跑满的正常命令」误报成超时；只判 exitCode 又不知道
         * 它到底是被杀的还是命令自己返回的非零。
         */
        val timedOut = r.exitCode == null && ms >= timeout - 500L
        return 200 to JSONObject()
            .put("ok", r.ok)
            .put("cmd", cmd)
            .put("family", opt.family.id)
            .put("flavor", opt.flavor.id)
            .put("exit_code", r.exitCode ?: -1)
            .put("timed_out", timedOut)
            .put("duration_ms", ms)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put(
                "limits",
                JSONArray()
                    .put("not a pty: interactive commands get EOF")
                    .put("hard timeout is $EXEC_TIMEOUT_MAX_MS ms")
                    .put("for long work use `nohup ... &` then poll"),
            )
            .toString()
    }
}

package app.dsh.mobile.engine

import java.io.File

/**
 * 引擎静态配置与目录拓扑。
 *
 * 目录约定（全部位于 app 私有存储内，targetSdk 28 下可执行）：
 *   filesDir/
 *     engine/          运行时根（node + node_modules，升级时整体替换）
 *       bin/node       bionic 编译的 Node 二进制（Termux 构建产物）
 *       lib/           动态库（libuv/openssl 等）
 *     dsh-home/        $DSH_HOME（凭证、会话、配置）
 *     workspaces/      默认工作区根
 *     tmp/             TMPDIR
 *
 * 升级引擎时绝不能触碰 dsh-home 与 workspaces —— 会话数据是用户资产。
 */
object EngineConfig {

    const val DEFAULT_PORT = 3080
    const val HEALTH_TIMEOUT_MS = 45_000L
    const val HEALTH_INTERVAL_MS = 500L

    /** 崩溃退避序列（毫秒），连续健康后重置 */
    val BACKOFF_STEPS = longArrayOf(2_000, 4_000, 8_000, 16_000, 32_000)
    const val MAX_RESTART = 8

    fun engineRoot(ctx: android.content.Context): File =
        File(ctx.filesDir, "engine").apply { mkdirs() }

    fun nodeBin(ctx: android.content.Context): File =
        File(engineRoot(ctx), "bin/node")

    fun dshEntry(ctx: android.content.Context): File =
        File(engineRoot(ctx), "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    fun dshHome(ctx: android.content.Context): File =
        File(ctx.filesDir, "dsh-home").apply { mkdirs() }

    fun workspaces(ctx: android.content.Context): File =
        File(ctx.filesDir, "workspaces").apply { mkdirs() }

    fun tmpDir(ctx: android.content.Context): File =
        File(ctx.filesDir, "tmp").apply { mkdirs() }

    /**
     * 组装子进程环境变量。
     * PATH/LD_LIBRARY_PATH/PREFIX 对齐 Termux 布局，保证 bionic 二进制能找到依赖库。
     */
    fun buildEnv(ctx: android.content.Context, port: Int): Array<String> {
        val root = engineRoot(ctx)
        return arrayOf(
            "PATH=${File(root, "bin")}:${File(root, "usr/bin")}:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=${File(root, "lib")}:${File(root, "usr/lib")}",
            "PREFIX=$root",
            "HOME=${dshHome(ctx)}",
            "DSH_HOME=${dshHome(ctx)}",
            "TMPDIR=${tmpDir(ctx)}",
            "PORT=$port",
            "NODE_ENV=production",
        )
    }
}

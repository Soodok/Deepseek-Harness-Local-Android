package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Linux 包管理器 —— 用 `apk` 在 rootfs 内装/删软件。
 *
 * ## 为什么单独一个类而不是复用 ExtensionManager
 *
 * 两者管理的是**完全不同的包体系**，强行统一会产生隐蔽错误：
 *
 * | | ExtensionManager（现状） | 本类（Linux 模式） |
 * |---|---|---|
 * | 包格式 | Termux 的 `.deb`（ar + data.tar.xz） | Alpine 的 `.apk`（拼接 gzip） |
 * | 落点 | `engine/extensions/<id>/`，拍平 usr/ | rootfs 的标准 FHS 布局 |
 * | 数据库 | 无（靠目录存在性判断） | `/lib/apk/db/installed` |
 * | 依赖解析 | 自己实现的 BFS 闭包 | apk 自己 |
 * | 作用目标 | 只给 DSH 引擎用 | 整个 Linux 用户态 |
 *
 * 复用它们的公共基础设施（下载、SHA-256 校验、原子发布）是合理的，但**安装器
 * 本体必须分开** —— Termux 的路径布局和 Alpine 的包数据库语义不同，混在一起
 * 只会两套都做不对。
 *
 * ## 与 LinuxContainerRunner 的关系
 *
 * 本类只做「装什么、装完了吗、装了多少」这类包级语义；真正执行 `apk` 命令的
 * 是 [LinuxContainerRunner]（它负责容器 argv、环境变量、超时与进程树回收）。
 *
 * ## 索引刷新
 *
 * `apk update` 拉的是 `APKINDEX.tar.gz`（阿里云源实测 26178 个包）。首次用
 * 之前必须刷新一次，否则 `apk add` 报 `no such package`。本类记录刷新时间，
 * 超过 [INDEX_TTL_MS] 才重刷，避免每次进页面都拉 700KB。
 */
class LinuxPackageManager(private val ctx: Context) {

    private val runner = LinuxContainerRunner.get(ctx)
    private val env = LinuxEnvironmentManager.get(ctx)

    /** 已装包缓存（包名 -> 版本），避免每次遍历 `apk info -v` 的输出 */
    private val installedCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var lastIndexRefresh = 0L

    /** 安装/卸载结果 */
    data class OpResult(
        val ok: Boolean,
        val packages: List<String>,
        val rawOutput: String,
    )

    /**
     * 刷新包索引（`apk update`）。
     *
     * @param force 为 true 时忽略 TTL
     */
    fun refreshIndex(force: Boolean = false): OpResult {
        if (!force && System.currentTimeMillis() - lastIndexRefresh < INDEX_TTL_MS) {
            return OpResult(true, emptyList(), "index fresh (skipped)")
        }
        val r = runner.run("/sbin/apk", listOf("update", "--no-progress"), 180_000)
        if (r.ok) lastIndexRefresh = System.currentTimeMillis()
        // 失败时把 exitCode 与 stderr 一起打出来。原来只打 combined()，而
        // exitCode != 0 且两路输出都为空时（进程根本没起来、或 exec 被拒），
        // 日志只剩一条空尾巴 `apk update: ok=false` —— 看不出任何线索。
        Log.i(
            TAG,
            "apk update: ok=${r.ok} rc=${r.exitCode} ms=${r.durationMs} " +
                "out=${r.stdout.take(160)} err=${r.stderr.take(240)}",
        )
        return OpResult(r.ok, emptyList(), r.combined())
    }

    /**
     * 安装软件包。
     *
     * 会自动先刷新索引（过期时）。失败时把 apk 的原始 stderr 原样带回。
     */
    fun install(packages: List<String>, timeoutMs: Long = 600_000L): OpResult {
        if (packages.isEmpty()) return OpResult(true, emptyList(), "")
        if (!ensureIndex()) {
            return OpResult(false, packages, "package index refresh failed; cannot install")
        }
        val r = runner.run(
            "/sbin/apk",
            listOf("add", "--no-progress") + packages,
            timeoutMs,
        )
        if (r.ok) installedCache.clear()
        Log.i(TAG, "apk add ${packages.joinToString(",")}: ok=${r.ok}")
        return OpResult(r.ok, packages, r.combined())
    }

    /** 卸载（`apk del`；只删指定包，不动依赖） */
    fun remove(packages: List<String>, timeoutMs: Long = 180_000L): OpResult {
        if (packages.isEmpty()) return OpResult(true, emptyList(), "")
        val r = runner.run("/sbin/apk", listOf("del") + packages, timeoutMs)
        if (r.ok) installedCache.clear()
        return OpResult(r.ok, packages, r.combined())
    }

    /**
     * 已安装的包（包名 -> 版本）。
     *
     * 用 `apk info -v` 而不是 `apk info`：后者只给包名，前者形如
     * `nodejs-22.23.4-r0`，需要切掉末尾两段（`-<ver>-r<n>`）才能得到包名。
     */
    fun installed(): Map<String, String> {
        if (installedCache.isNotEmpty()) return installedCache
        val r = runner.run("/sbin/apk", listOf("info", "-v"), 60_000)
        if (!r.ok) return emptyMap()
        r.stdout.lines().forEach { line ->
            val s = line.trim()
            if (s.isEmpty()) return@forEach
            // nodejs-22.23.4-r0 -> name=nodejs, ver=22.23.4
            val lastDash = s.lastIndexOf('-')
            if (lastDash <= 0) return@forEach
            val beforeRev = s.substring(0, lastDash)
            val secondDash = beforeRev.lastIndexOf('-')
            if (secondDash <= 0) return@forEach
            val name = beforeRev.substring(0, secondDash)
            val ver = beforeRev.substring(secondDash + 1)
            installedCache[name] = ver
        }
        Log.i(TAG, "apk installed packages: ${installedCache.size}")
        return installedCache
    }

    /** 搜索可用包（`apk search`，按名字匹配） */
    fun search(keyword: String, limit: Int = 30): List<String> {
        if (keyword.isBlank()) return emptyList()
        val r = runner.run("/sbin/apk", listOf("search", "-q", keyword), 60_000)
        if (!r.ok) return emptyList()
        return r.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(limit)
    }

    /** 某个包是否已装 */
    fun isInstalled(pkg: String): Boolean = installed().containsKey(pkg)

    /** 包索引是否可用（过期则刷新） */
    private fun ensureIndex(): Boolean {
        if (lastIndexRefresh > 0 || installed().isNotEmpty()) {
            // 已经有索引痕迹（装过东西）通常说明索引用过；TTL 到了再刷
            if (System.currentTimeMillis() - lastIndexRefresh < INDEX_TTL_MS) return true
        }
        return refreshIndex(force = lastIndexRefresh == 0L).ok
    }

    /**
     * 一键安装「开发工具箱」（用户最常需要的一组）。
     *
     * 体积参考（实测压缩后可下载量）：
     * - nodejs        约 30MB
     * - python3+py3-pip 约 25MB
     * - gcc/g++/make   约 74MB
     * - git            约 15MB
     */
    fun installDevToolbox(timeoutMs: Long = 900_000L): OpResult =
        install(listOf("nodejs", "git", "make"), timeoutMs)

    companion object {
        private const val TAG = "LinuxPkg"

        /** 包索引有效期：6 小时。apk 索引约 700KB，不必每次进页面都拉。 */
        private const val INDEX_TTL_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var instance: LinuxPackageManager? = null

        fun get(ctx: Context): LinuxPackageManager =
            instance ?: synchronized(this) {
                instance ?: LinuxPackageManager(ctx.applicationContext).also { instance = it }
            }
    }
}

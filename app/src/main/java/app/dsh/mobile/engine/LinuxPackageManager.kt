package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.R
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
 *
 * ## 必须跟随发行版（v1.6.20 修正）
 *
 * 原来本类把 `/sbin/apk` **写死**，于是用户把发行版切成 Ubuntu 后，点「安装
 * node」仍在找 `apk` —— Ubuntu 里根本没有这个二进制，返回
 * `exit 127 / not found`，UI 只显示「apk add failed」，看不出是装错了包管理器。
 *
 * 现在按 `option.pkgManager` 分派：
 * - `APK`  → 走本类的 apk 路径（一次 `apk add` 就够）
 * - `APT`  → 委托给 [LinuxEnvironmentManager.installPackagesApt]，那是**两步法**
 *   （`apt-get -d install` 只下载 → 对 cache 里的 deb 跑 `dpkg -i` → `dpkg
 *   --configure -a`）。为什么不能一步：实测 Ubuntu 24.04 上
 *   `apt-get install -y foo` 报 `dpkg: error: cannot stat pathname
 *   '/tmp/apt-dpkg-install-XXXX/00-foo.deb'`，apt 把 deb 硬链进 /tmp 后 dpkg
 *   在容器的路径重写层上看不见它；同一份 deb 留在 `/var/cache/apt/archives/`
 *   里 dpkg 就能正常读。
 */
class LinuxPackageManager(private val ctx: Context) {

    private val runner = LinuxContainerRunner.get(ctx)
    private val env = LinuxEnvironmentManager.get(ctx)

    /** 当前发行版（决定走 apk 还是 apt） */
    private fun option(): LinuxRootfsCatalog.Option = env.activeOption()

    /** 是否 glibc/apt 系。每次现取，切发行版后立刻生效，不缓存。 */
    private fun isApt(): Boolean =
        option().pkgManager == LinuxRootfsCatalog.PackageManager.APT

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
     * 确保容器 DNS 代理已经起来了。
     *
     * ★为什么必须在**每次执行前**都保一次：`DshApp.onCreate` 里的预启动跑在
     * 后台线程（因为它可能要过一次 `su` 调 `ip_unprivileged_port_start`，不能
     * 阻塞主线程）。用户完全可能在代理还没绑好端口时就点「安装 node」，那一刻
     * `LinuxDnsProxy.currentPort()` 还是 0，`LinuxContainerRunner` 就不会注入
     * `DSH_DNS_PORT`，容器的 musl resolver 于是把查询发到 53（没人监听），
     * `apk update` 报
     *     WARNING: updating and opening http://mirrors.aliyun.com/...:
     *              temporary error (try again later)
     * 最后 UI 显示 `apk update failed`。
     *
     * 这个时序竞态在慢启动机型/刚装完就点的情况下必现，而且日志里只看得到
     * 「临时错误」，会把人引去换镜像源。
     *
     * `start()` 本身幂等且 `@Synchronized`（已在跑就直接返回原端口），所以
     * 这里无条件调一次的代价可以忽略；返回 0 表示确实起不来，交给上层报错。
     */
    private fun ensureDnsProxy(): Int {
        val p = LinuxDnsProxy.currentPort()
        if (p > 1023) return p
        return runCatching { LinuxDnsProxy.start(ctx) }.getOrDefault(0)
    }

    /**
     * 刷新包索引（`apk update`）。
     *
     * @param force 为 true 时忽略 TTL
     */
    fun refreshIndex(
        force: Boolean = false,
        onLine: ((String) -> Unit)? = null,
    ): OpResult {
        // apt 系没有「apk 索引」这回事：`apt-get update` 由
        // installPackagesApt() 内部按需调用（它还会做「失败清 partial 再试一次」）。
        // 这里直接报成功，避免上层把「没有 apk」误当成索引刷新失败。
        if (isApt()) return OpResult(true, emptyList(), "apt: index handled by installPackagesApt")
        if (!force && System.currentTimeMillis() - lastIndexRefresh < INDEX_TTL_MS) {
            return OpResult(true, emptyList(), "index fresh (skipped)")
        }
        // ★执行前保一次代理：见 ensureDnsProxy() 的注释（启动竞态）。
        ensureDnsProxy()
        val r = runner.run("/sbin/apk", listOf("update", "--no-progress"), 180_000, onLine = onLine)
        if (r.ok) lastIndexRefresh = System.currentTimeMillis()
        // 失败时把 exitCode 与 stderr 一起打出来。原来只打 combined()，而
        // exitCode != 0 且两路输出都为空时（进程根本没起来、或 exec 被拒），
        // 日志只剩一条空尾巴 `apk update: ok=false` —— 看不出任何线索。
        Log.i(
            TAG,
            "apk update: ok=${r.ok} rc=${r.exitCode} ms=${r.durationMs} " +
                "out=${r.stdout.take(160)} err=${r.stderr.take(240)}",
        )
        return OpResult(r.ok, emptyList(), if (r.ok) r.combined() else diagnose(r.combined()))
    }

    /**
     * 安装软件包。
     *
     * 会自动先刷新索引（过期时）。失败时把 apk 的原始 stderr 原样带回。
     */
    fun install(
        packages: List<String>,
        timeoutMs: Long = 600_000L,
        onLine: ((String) -> Unit)? = null,
    ): OpResult {
        if (packages.isEmpty()) return OpResult(true, emptyList(), "")
        // ★apt 系（Ubuntu）不能走 apk：见类注释「必须跟随发行版」。
        //   委托给 installPackagesApt 的两步法，并把它的 raw 原样带回给 UI
        //   —— 那里面有 apt/dpkg 的真实报错，比一句 "apk add failed" 有用得多。
        if (isApt()) {
            val r = env.installPackagesApt(option(), packages, timeoutMs, onLine)
            if (r.ok) installedCache.clear()
            Log.i(TAG, "apt install ${packages.joinToString(",")}: ok=${r.ok}")
            return OpResult(r.ok, packages, r.raw)
        }
        // ★执行前保一次代理：见 ensureDnsProxy() 的注释。
        ensureDnsProxy()
        if (!ensureIndex(onLine)) {
            // ensureIndex 内部已经把 apk 的真实输出打进了 refreshIndex 的日志；
            // 这里给用户的话必须带上「可能是 DNS」的方向，否则他只会去换镜像源。
            return OpResult(
                false,
                packages,
                "package index refresh failed; cannot install" +
                    System.lineSeparator() + System.lineSeparator() +
                    ctx.getString(R.string.linux_pkg_index_dns_hint),
            )
        }
        val r = runner.run(
            "/sbin/apk",
            listOf("add", "--no-progress") + packages,
            timeoutMs,
            onLine = onLine,
        )
        if (r.ok) installedCache.clear()
        Log.i(TAG, "apk add ${packages.joinToString(",")}: ok=${r.ok}")
        return OpResult(r.ok, packages, if (r.ok) r.combined() else diagnose(r.combined()))
    }

    /** 卸载（`apk del` / `apt-get remove`；只删指定包，不动依赖） */
    fun remove(
        packages: List<String>,
        timeoutMs: Long = 180_000L,
        onLine: ((String) -> Unit)? = null,
    ): OpResult {
        if (packages.isEmpty()) return OpResult(true, emptyList(), "")
        if (isApt()) {
            val r = runner.run(
                "/usr/bin/apt-get",
                listOf("remove", "-y") + packages,
                timeoutMs,
                skipReady = true,
                onLine = onLine,
            )
            if (r.ok) installedCache.clear()
            return OpResult(r.ok, packages, r.combined())
        }
        val r = runner.run("/sbin/apk", listOf("del") + packages, timeoutMs, onLine = onLine)
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
        // ★apt 系必须换查询工具：Ubuntu 里没有 apk，`apk info -v` 恒失败，
        //   于是「已装了什么」永远是空 —— 扩展中心会一直显示未安装，用户以为
        //   装失败了。dpkg-query 的输出格式是
        //       nodejs	18.19.1+dfsg-6ubuntu5
        //   （包名与版本用 TAB 分隔），和 apk 的 `nodejs-22.23.4-r0` 完全不同，
        //   所以解析必须分开写，不能共用一套切横线的逻辑。
        if (isApt()) {
            val rq = runner.run(
                "/usr/bin/dpkg-query",
                listOf("-W", "-f=\${Package}\t\${Version}\n"),
                60_000,
                skipReady = true,
            )
            if (rq.ok) {
                rq.stdout.lines().forEach { line ->
                    val t = line.trim()
                    if (t.isEmpty()) return@forEach
                    val tab = t.indexOf('	')
                    if (tab <= 0) return@forEach
                    installedCache[t.substring(0, tab)] = t.substring(tab + 1)
                }
            }
            Log.i(TAG, "dpkg installed packages: ${installedCache.size}")
            return installedCache
        }
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
        if (isApt()) {
            val ra = runner.run(
                "/usr/bin/apt-cache",
                listOf("search", keyword),
                60_000,
                skipReady = true,
            )
            if (!ra.ok) return emptyList()
            // apt-cache 输出 `name - description`，只取包名
            return ra.stdout.lines()
                .map { it.substringBefore(" - ").trim() }
                .filter { it.isNotEmpty() }
                .take(limit)
        }
        val r = runner.run("/sbin/apk", listOf("search", "-q", keyword), 60_000)
        if (!r.ok) return emptyList()
        return r.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(limit)
    }

    /** 某个包是否已装 */
    fun isInstalled(pkg: String): Boolean = installed().containsKey(pkg)

    /**
     * 给失败输出加一段**可操作**的诊断。
     *
     * 为什么需要它：`apk` 的网络失败文案是
     * ```
     * WARNING: updating and opening http://mirrors.aliyun.com/alpine/v3.22/main:
     *          temporary error (try again later)
     * ```
     * 看起来像镜像站挂了，实际绝大多数情况是**容器内 DNS 不可用**：musl 把 DNS
     * 端口写死成 53，而 App uid 绑不上 53（特权端口），要靠 loader 的端口双向
     * 改写出程/回程才行，端口号从环境变量 `DSH_DNS_PORT` 读。
     *
     * 只要这条链路任一环断了（代理没起、端口没注入、rootfs 的 resolv.conf 指向
     * 死端口），用户看到的就是「temporary error」，而日志里没有任何线索。
     * 之前真机上有人报「装 node 失败」，根因就是这一条。
     *
     * 这里把「输出里出现了网络类关键词」与「代理实际状态」一起报给用户，
     * 让下一句提示能直接告诉他该怎么办，而不是只回一句 apk add failed。
     */
    private fun diagnose(raw: String): String {
        val netFail = listOf("temporary error", "try again later", "unable to select",
                             "network is unreachable", "connection refused",
                             "could not connect", "timed out", "DNS")
            .any { raw.contains(it, ignoreCase = true) }
        if (!netFail) return raw
        val port = LinuxDnsProxy.currentPort()
        // 文案必须走资源（i18n 门禁禁止 Kotlin 字面量含 CJK，见 check-i18n.py 的 D 段）
        val msg = if (port <= 1023) {
            ctx.getString(R.string.linux_pkg_dns_proxy_down, port)
        } else {
            ctx.getString(R.string.linux_pkg_dns_proxy_up, port)
        }
        return raw + System.lineSeparator() + System.lineSeparator() + msg
    }

    /** 包索引是否可用（过期则刷新） */
    private fun ensureIndex(onLine: ((String) -> Unit)? = null): Boolean {
        if (lastIndexRefresh > 0 || installed().isNotEmpty()) {
            // 已经有索引痕迹（装过东西）通常说明索引用过；TTL 到了再刷
            if (System.currentTimeMillis() - lastIndexRefresh < INDEX_TTL_MS) return true
        }
        // onLine 必须透传：索引刷新在装包之前，`apk update` 的下载进度
        // 恰恰是最长的一段等待（实测首次 27MB / 8s，慢网更久）。漏传的话
        // 用户在进度窗里只能看到空白，直到真正开始装包才突然有输出。
        return refreshIndex(force = lastIndexRefresh == 0L, onLine = onLine).ok
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

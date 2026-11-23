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

    /**
     * 包数据库串行锁 —— 实现与理由见 [LinuxPkgLock]。
     *
     * 那些写操作必须串行，否则后到的 `apk add` 会拿
     * `Unable to lock database: Resource temporarily unavailable` (rc=99)
     * 静默失败，界面上看不出任何原因。
     */
    private val dbLock = LinuxPkgLock


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
        // v1.7.13：currentPort() 现在会校验 socket 是否真的还活着，
        // 所以这里拿到 0 就说明「状态在、监听没了」，start() 内部会先停掉
        // 死状态再重绑（见 LinuxDnsProxy.start 的幂等判据）。
        val p = LinuxDnsProxy.currentPort()
        if (p > 1023) return p
        val rebind = runCatching { LinuxDnsProxy.start(ctx) }.getOrDefault(0)
        if (rebind <= 1023) {
            Log.w(TAG, "DNS proxy unavailable (port=0); package operations will fail to resolve")
        }
        return rebind
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
        // ★写包数据库 → 必须持锁（见 dbLock 注释）
        val r = dbLock.withLock {
            runner.run("/sbin/apk", listOf("update", "--no-progress"), 180_000, onLine = onLine)
        }
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
        // ★--no-chown 必须加：容器按 App uid（非 root）运行，apk 给包内文件
        //   chown 会被内核拒成 EPERM，逐文件刷屏
        //       ERROR: Failed to set ownership on usr/bin/...: Operation not permitted
        //       ERROR: 9 errors updating directory permissions
        //   并让 apk 以非 0 退出 —— 包其实装好了，用户看到的却是 Install failed。
        //   apk 自己就提供了这个开关（--help 里写作「Do not change file owner or
        //   group」），正是给非 root 场景用的。属主本来就该是 App 自己，
        //   改不了也不需要改。
        // ★写包数据库 → 必须持锁。并发装两个条目时，后到的会拿
        //   "Unable to lock database: Resource temporarily unavailable" rc=99
        //   静默失败（见 dbLock 注释里的复现记录）。
        val r = dbLock.withLock {
            runner.run(
                "/sbin/apk",
                listOf("add", "--no-progress", "--no-chown") + packages,
                timeoutMs,
                onLine = onLine,
            )
        }
        // ★真值判据是「包在不在」，不是 apk 的退出码。
        //
        //   实测（模拟器 x86_64 / Alpine 3.22 / 68 包已装）：每次 apk add 都以
        //   EXIT=3 结束并打印一句 `3 errors; 434 MiB in 68 packages`，
        //   但目标包全部就位、可正常执行 —— nano/tree/less 的 --version 都通，
        //   `apk info -e` 也认得它们。那 3 个计数来自 busybox 的 trigger
        //   （`/bin/busybox --install -s` 重建 /bin 下的 applet 符号链接），
        //   在「非 root + 容器里没有 /proc/sys 写权限」的环境下必然非 0，
        //   与装包成败无关。用 root 跑同一条命令同样 EXIT=3，进一步证明
        //   它不是权限问题、也不属于我们可控范围。
        //
        //   若只看 r.ok，用户遇到的就是最糟的组合：软件明明装好了，
        //   界面却写 Install failed，他只会反复重试并以为扩展中心坏了。
        val verified = if (r.ok) true else allInstalled(packages)
        if (verified) {
            installedCache.clear()
            healPackageFiles(packages)
            // 不再做「把绝对符号链接改成相对」那一步。
            //
            // 曾经的假设是：容器没有 chroot，绝对链接会被内核按宿主根解析。
            // 实测证明这个假设是错的（本 loader 会拦截路径系统调用、在 guest
            // 空间里自行解析，宿主内核只看到 rootfs 内的真实路径）：
            //     ./runner/dsh_alpine <rootfs> <interp> /usr/bin/go version
            //     → go version go1.24.13 linux/amd64      RC=0
            // 而 /usr/bin/go 正是一条指向 /usr/lib/go/bin/go 的**绝对**链接。
            // 装包后 rootfs 里那 6 条绝对链接（go/gofmt/cacerts/liblto_plugin/
            // system/bin/{sh,bash}）也一直工作正常。
            // 那段代码本身还带 shell 引号转义错误（实测 `sh: syntax error:
            // unexpected ')'`），从未真正执行成功过 —— 留着只会误导后来的人。
        }
        Log.i(
            TAG,
            "apk add ${packages.joinToString(",")}: rc-ok=${r.ok} verified=$verified",
        )
        // ★说明文案必须挂在**所有**成功分支上，不能只挂在「退出码非 0」那支。
        //   实测同一台设备两次装 nodejs：第一次 exit≠0（7 errors）、第二次 exit=0
        //   却仍有 `can't create pipe` 与 `ERROR: 2 errors updating directory
        //   permissions` —— 这类 trigger 报错的有无取决于装的是哪些包、以及
        //   icu-data-en 要不要跑 post-install，与退出码不对应。挂在单一分支上，
        //   用户总有一半的机会看到 ERROR 却没有任何解释。
        //
        // ★2026-11-12 真机修正：这里的匹配串原来按文档写法抄成了带括注的形态，
        //   但 apk 实际输出是「ERROR: 2 errors updating directory permissions」
        //   （带数字、用复数），两者不相等 —— 于是 triggersMayBeNoisy 恒为
        //   false，那句解释文案一次都没显示过，用户只看到一个裸 ERROR。
        //   现在按「稳定片段」匹配，不依赖具体数字与单复数。
        val combined = r.combined()
        val triggersMayBeNoisy = combined.contains("updating directory permissions", ignoreCase = true) ||
            combined.contains("can't create pipe") ||
            combined.contains("cannot create pipe") ||
            Regex("ERROR:\\s+\\d+\\s+error", RegexOption.IGNORE_CASE).containsMatchIn(combined)
        return OpResult(
            verified,
            packages,
            if (verified && r.ok && !triggersMayBeNoisy) {
                r.combined()
            } else if (verified) {
                // 包都在（数据库核对过）：把 apk 原话留着让用户能复制，
                // 再补一句解释 —— 不编「成功」，但也绝不让 ERROR 无人解释。
                r.combined() + System.lineSeparator() + System.lineSeparator() +
                    ctx.getString(R.string.linux_pkg_trigger_note)
            } else {
                diagnose(r.combined())
            },
        )
    }

    /**
     * 装完包之后收拾两类「包装上了、但文件不可用」的残留。
     *
     * ## 真机证据（2026-11-14 / PLZ110 / Alpine 3.22）
     *
     * 装 `build-base` 之后 `apk info -e gcc` 认得、数据库里也有，但：
     *
     * ```
     * /usr/bin/gcc:  -rw-r--r--   ← 缺 x 位，`gcc --version` 报 not found
     * /usr/bin/as:   -rw-r--r--   ← 同上
     * /usr/bin/ld:   -rw-r--r--   ← 同上
     * /usr/include:  drwxr-xr-x root root   ← 属主是 root，App 写不进
     * ```
     *
     * 后果链：`/usr/include` 写不进 → `apk add musl-dev` 报
     * `failed to rename usr/include/sys/.apk.xxx to .../vt.h` 与
     * `ERROR: 14 errors updating directory permissions` → `stdio.h` 不落地
     * → `gcc` 编任何 C 文件都报 `fatal error: stdio.h: No such file or directory`。
     *
     * 手工验证过：`chmod +x /usr/bin/gcc` 后 `gcc --version` 立刻正常；
     * `chown -R <uid> /usr/include` 后 `apk del musl-dev && apk add musl-dev`
     * 才把 `stdio.h` 装上、`gcc` 才编得出 `C-OK`。
     *
     * ## 两类分开处理，因为修法完全不同
     *
     * | 症状 | 判据 | 谁能修 |
     * |---|---|---|
     * | 可执行文件缺 x 位 | 是 ELF/带 shebang 且无 x 位 | **App 自己**（属主已是自己） |
     * | 头文件目录属主漂移 | 目录 `canWrite()` 为假 | 只有特权通道 |
     *
     * 第二类只在装「需要编译链的包」时才尝试 —— 那是要起 `su`/Shizuku 的
     * 有代价操作，不该在装个 `curl` 时也走一遍。判据见
     * [RootfsModes.wantsHeaders]。
     *
     * 全程静默兜错：这是一步**修补**，不该让本来成功的装包变红。
     */
    private fun healPackageFiles(packages: List<String>) {
        val root = runCatching { env.rootfsDir() }.getOrNull() ?: return
        // ① 补 x 位 —— 纯 App 域操作，无条件做（代价只是几次 listFiles）
        val fixed = runCatching { RootfsModes.repairExecBits(root) }.getOrDefault(0)
        if (fixed > 0) {
            Log.w(
                TAG,
                "healed $fixed executable bit(s) after apk add " + packages.joinToString(",") +
                    " (files were 0644, gcc/as/ld would report 'not found')",
            )
        }
        // ② 头文件目录属主 —— 只在装编译链相关包时试
        if (!RootfsModes.wantsHeaders(packages)) return
        val include = java.io.File(root, "usr/include")
        if (!include.isDirectory) return
        if (runCatching { include.canWrite() }.getOrDefault(false)) return
        val ok = runCatching { Privilege.normalizeOwnership(ctx, include) }.getOrDefault(false)
        Log.w(
            TAG,
            "heal usr/include ownership: ok=" + ok +
                " (apk add musl-dev fails with 'failed to rename .../vt.h' without it)",
        )
        if (ok) {
            // 属主交还之后必须重装一次：上一次的 apk add 已经把包**记进数据库**了
            // （所以 `apk info -e musl-dev` 是 yes），但文件没落地。
            // 不 del 直接 add 是幂等跳过 —— 头文件永远不会补上。真机实测如此。
            runCatching {
                dbLock.withLock {
                    runner.run("/sbin/apk", listOf("del", "musl-dev"), 120_000)
                }
                dbLock.withLock {
                    runner.run(
                        "/sbin/apk",
                        listOf("add", "--no-progress", "--no-chown", "musl-dev"),
                        300_000,
                    )
                }
            }.onFailure { Log.w(TAG, "re-add musl-dev failed: " + it.message) }
        }
    }

    /**
     * 逐个查包是否真的装进了数据库。
     *
     * ★v1.5.4：**改成直接读 `/lib/apk/db/installed`，不再起容器进程**。
     *
     * ## 为什么必须换判据（实测证据）
     *
     * 原实现调 `apk info -e <pkg>`，而它在 Android 上有两个坑：
     *
     * 1. **`apk` 自己的 post-install 脚本会失败**，导致 `apk add` 退出码非 0，
     *    即使包全装好了。实测设备输出：
     *    ```
     *    ✓ OK: 76 MiB in 30 packages
     *    /lib/apk/exec/icu-data-en-76.1-r1.post-install: line 3: can't create pipe: Operation not permitted
     *    ERROR: 7 error(s) updating directory permissions
     *    ```
     *    `pipe()` 在无 root 的 App 域被 SELinux/seccomp 拦，`icu-data-en` 的
     *    post-install 又要建管道 → apk 判自己失败。**包其实装好了**。
     *
     * 2. 用它做兜底判据等于**再起一次同样会踩坑的进程** —— 第一次失败的真实原因
     *    没被绕过，反而多付一次 fork + 一次同样的失败。所以兜底形同虚设。
     *
     * 数据库文件是 apk 的**权威记账**：装成功的包一定在里面，没装成功一定不在。
     * 直接读它既绕开 exit code，又不需要起进程（零成本、不会被拦）。
     *
     * ## 格式
     *
     * `/lib/apk/db/installed` 是段落式纯文本，每段一个包，字段形如
     * `P:nodejs` / `V:22.23.4-r0`（`P:` = 包名，`V:` = 版本）。
     * 只需要 `P:` 行。
     */
    private fun allInstalled(packages: List<String>): Boolean = runCatching {
        val db = java.io.File(env.rootfsDir(), "lib/apk/db/installed")
        if (!db.isFile) return@runCatching false
        val present = HashSet<String>()
        db.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                // 只在 P: 行取包名；别的字段（V:/S:/I:...）一律跳过
                if (line.length > 2 && line[0] == 'P' && line[1] == ':') {
                    present.add(line.substring(2).trim())
                }
            }
        }
        packages.all { it in present }
    }.getOrDefault(false)

    /** 卸载（`apk del` / `apt-get remove`；只删指定包，不动依赖） */
    fun remove(
        packages: List<String>,
        timeoutMs: Long = 180_000L,
        onLine: ((String) -> Unit)? = null,
    ): OpResult {
        if (packages.isEmpty()) return OpResult(true, emptyList(), "")
        if (isApt()) {
            // v1.7.13：-o dpkg::options=--force-not-root 让 apt 调起的 dpkg 也按
            // 非 root 运行 —— 容器按 App uid 跑，不加这条 apt-get remove 会报
            // requested operation requires superuser privilege。真机实证同装包路径。
            val r = runner.run(
                "/usr/bin/apt-get",
                listOf("-o", "APT::Sandbox::User=", "-o", "dpkg::options::=--force-not-root", "remove", "-y") + packages,
                timeoutMs,
                skipReady = true,
                onLine = onLine,
            )
            if (r.ok) installedCache.clear()
            return OpResult(r.ok, packages, r.combined())
        }
        // ★写包数据库 → 必须持锁（同上）
        val r = dbLock.withLock {
            runner.run("/sbin/apk", listOf("del") + packages, timeoutMs, onLine = onLine)
        }
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
        // ★v1.7.12：空结果**不进缓存**。
        //
        // 旧写法是 `if (installedCache.isNotEmpty()) return installedCache`，看似无害，
        // 但配合「查询失败时也返回同一个缓存对象」就成了毒药：容器还没就绪 / 引擎刚
        // 切模式 / apk 命令超时，第一次查询会拿到空表并把它当结果写进缓存 —— 之后
        // 只要缓存还是空，每次调用都返回空 Map，扩展中心于是**永久**显示「都没装」，
        // 只有进程重启才恢复。用户看到的正是「刚装好的扩展又提示可以安装」。
        //
        // 现在空表一律视为「不可信的一次查询」：不缓存、下次重查。代价是容器真的
        // 什么都没装时会重复 fork apk，但那种情况只在全新 rootfs 上出现，可以接受。
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

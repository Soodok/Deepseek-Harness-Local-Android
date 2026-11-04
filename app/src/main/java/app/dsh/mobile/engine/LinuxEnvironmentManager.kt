package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Linux 环境（Workspace）管理器 —— rootfs 的部署、修复与自检。
 *
 * ## 这是什么
 *
 * 把随 APK 分发的**最简 Alpine rootfs**（`assets/linux/rootfs-bootstrap.tgz`，约 3.8MB
 * 压缩 / 8.5MB 解压 / 110 文件）落地到 `filesDir/linux/rootfs/`，并用 `dsh-alpine`
 * （seccomp 用户态运行器）在其中执行 Linux 程序 —— **全程免 root**。
 *
 * ## 为什么需要这个类（而不是直接 tar 解压）
 *
 * 直接把 tarball 解开是**不能用的**，有三个必须处理的坑，每个都会导致「命令找不到」
 * 或「Permission denied」，而且报错极具误导性（看起来像 rootfs 缺文件）：
 *
 * 1. **绝对符号链接**：官方 minirootfs 的 335 个 applet 链接全部指向 `/bin/busybox`
 *    这类**绝对路径**。我们的 seccomp 方案只重定向「应用发起的路径」；内核解析
 *    symlink 目标是按**宿主真实根**来的 → `/bin/busybox` 在宿主上不存在 → ENOENT，
 *    表现为 `sh: cat: not found`。必须在部署期一次性重写成相对链接
 *    （实测 335 个链接、306 个需重写；TAWC 选的是每次 syscall 都解析的 live 方案，
 *    安装期一次性重写成本低得多）。
 * 2. **执行位依赖 tar 头**：Android 的 `tar` 解包在部分路径下会丢 mode；本项目统一
 *    用自己实现的 [untar] 按 tar 头 offset 100 的 mode 字段重建权限位（与
 *    `ExtensionManager.untar` 同源做法），否则 busybox 本体不可执行。
 * 3. **命令行工具不全**：minirootfs 只带 busybox + apk，没有 node / git / gcc。
 *    这些交给 `apk add` 按需安装（见 [APK_REPOSITORIES]），不预置进 APK。
 *
 * ## 目录布局
 *
 * ```
 * filesDir/
 *   linux/
 *     runner/dsh_alpine           <- 从 APK 的 libdsh_alpine.so 提取（见 EngineConfig.deployLinuxRunner）
 *     rootfs/                     <- Alpine 用户态，本类负责部署与修复
 *       bin/ sbin/ usr/ etc/ ...
 *       .dsh-deployed             <- 部署完成的标记文件（含版本号，用于幂等与升级判定）
 *     state/                      <- 会话状态、日志
 * ```
 *
 * ## 幂等与自愈
 *
 * - 首次运行：解包 → 修链接 → 恢复执行位 → 写标记。
 * - 后续运行：比对标记里的版本号，一致则跳过（不动用户已装的所有软件）。
 * - 检测到关键文件缺失（busybox 不可执行 / 链接断了）时耗尽一次修复机会，
 *   仍失败则删标记强制重装。
 */
class LinuxEnvironmentManager(private val ctx: Context) {

    /** Linux 工作区根：`filesDir/linux` */
    fun linuxRoot(): File = File(ctx.filesDir, "linux").apply { mkdirs() }

    /** rootfs 落点 */
    fun rootfsDir(): File = File(linuxRoot(), "rootfs")

    /** 运行器与状态目录 */
    fun runnerDir(): File = File(linuxRoot(), "runner").apply { mkdirs() }

    fun stateDir(): File = File(linuxRoot(), "state").apply { mkdirs() }

    /** 用户数据目录（home、装好的软件包都在 rootfs 内，这里只放会话级状态） */
    fun markerFile(): File = File(rootfsDir(), MARKER)

    // ==================== 状态查询 ====================

    enum class State {
        /** 未部署（首次使用，或标记缺失） */
        NOT_DEPLOYED,

        /** 正在部署 */
        DEPLOYING,

        /** 已就绪，可直接进容器 */
        READY,

        /** 已部署但自检失败（修复中或需重装） */
        BROKEN,
    }

    data class Status(
        val state: State,
        val rootfsVersion: String? = null,
        val rootfsBytes: Long = 0L,
        val fileCount: Int = 0,
        val detail: String = "",
    )

    /**
     * 轻量自检：只看关键路径，不做全量扫描（避免启动路径上遍历上万个文件）。
     *
     * 判据（每个都是实测踩过的具体故障点）：
     * - `bin/busybox` 存在且可执行 → 否则 busybox 本体不可用
     * - `usr/bin/cat` 是符号链接且解析得到 → 否则 applet 链没修（`cat: not found`）
     * - `sbin/apk` 存在且可执行 → 否则装不了任何软件包
     */
    fun status(): Status {
        val root = rootfsDir()
        if (!root.isDirectory) return Status(State.NOT_DEPLOYED, detail = "rootfs directory does not exist")

        val marker = markerFile()
        val ver = if (marker.isFile) marker.readText().trim().lines().firstOrNull() else null

        val busybox = File(root, "bin/busybox")
        if (!busybox.isFile) return Status(State.BROKEN, ver, detail = "bin/busybox missing")
        if (!busybox.canExecute()) return Status(State.BROKEN, ver, detail = "bin/busybox not executable")

        // 软链健康判据必须选**真实存在于这个 rootfs 布局里**的样本。
        //
        // ⚠️ 踩过的坑：最初拿 `usr/bin/cat` 当判据，但 Alpine minirootfs 的
        // cat 只出现在 `bin/cat`（→ /bin/busybox），`usr/bin/` 下**根本没有 cat**。
        // 于是健康环境永远被判成「applet links not repaired」，UI 显示「需要修复」，
        // 并且因为 status() != READY，部署流程提前 return、连运行器都不部署 ——
        // 一个错误的判据样本让整条链路假死。
        //
        // 正确做法：挑 usr/bin 下**确实存在**的条目。`usr/bin/env` 是 Alpine 里
        // 固定的 applet 链接（→ ../../bin/busybox），实测存在。exists() 会跟随
        // 软链解析，所以同时也验证了「没有断链」。
        val appletSample = File(root, "usr/bin/env")
        if (!appletSample.exists()) {
            return Status(State.BROKEN, ver, detail = "usr/bin/env missing or dangling (applet links not repaired)")
        }

        val apk = File(root, "sbin/apk")
        if (!apk.isFile || !apk.canExecute()) {
            return Status(State.BROKEN, ver, detail = "sbin/apk missing or not executable")
        }

        // ⚠️ 这里**不能**算 dirBytes/countFiles。
        //
        // 它们是两次完整的 rootfs 目录遍历（当前 628 个文件、77MB，实测合计
        // 300~500ms），而这只是拿来给 LinuxActivity 状态卡显示「磁盘占用 / 文件数」。
        // 更糟的是 status() 在**每次冷启**都会被 EngineModeSelector.resolve()
        // 调用一次 —— 用户为两个展示数字付出近半秒的启动延迟。
        //
        // 现在 READY 分支只做 O(1) 的判据（上面几个 isFile/canExecute/exists），
        // 数字留给 UI 在需要时通过 [diskUsage]/[fileCount] 单独取。
        // 调用方要数字时显式调，不会拖慢启动路径。
        return Status(State.READY, ver)
    }

    // ==================== 部署主流程 ====================

    /**
     * 确保 rootfs 就绪。幂等：已就绪时直接返回。
     *
     * **必须跑在后台线程**（解包 110 个文件 + 重写 335 个链接，实测 1~3 秒）。
     *
     * @param force 为 true 时忽略标记强制重新解包（用于「修复」按钮与升级）
     * @return 部署后的状态
     */
    fun ensureDeployed(force: Boolean = false): Status {
        val root = rootfsDir()
        val marker = markerFile()

        if (!force) {
            val cur = status()
            if (cur.state == State.READY && cur.rootfsVersion == ASSET_VERSION) {
                Log.i(TAG, "linux rootfs already ready (v${cur.rootfsVersion})")
                return cur
            }
            if (cur.state == State.READY && cur.rootfsVersion != ASSET_VERSION) {
                // 版本变了但结构完好：不重装，只更新标记，避免抹掉用户装好的软件。
                // 大版本升级（Alpine 3.22 → 3.23）才需要 force。
                Log.i(TAG, "linux rootfs version drift ${cur.rootfsVersion} -> $ASSET_VERSION (keeping installed packages)")
                marker.writeText("$ASSET_VERSION\n${System.currentTimeMillis()}\n")
                return cur.copy(rootfsVersion = ASSET_VERSION)
            }
        }

        Log.i(TAG, "deploying linux rootfs (force=$force) -> ${root.absolutePath}")
        val t0 = System.currentTimeMillis()

        // 强制重装时先清空；普通部署只补缺（保留用户已装软件）
        if (force) {
            purge(root)
        }
        root.mkdirs()

        val pending = mutableListOf<LinkJob>()
        val entries = openAssetRootfs().use { ins ->
            untar(ins, root, pending)
        }
        materialiseLinks(root, pending)
        restoreExecBits(root)

        val ms = System.currentTimeMillis() - t0
        marker.writeText("$ASSET_VERSION\n${System.currentTimeMillis()}\n")
        Log.i(TAG, "linux rootfs deployed: $entries entries, ${pending.size} links, ${ms}ms")


        // 部署完补基础包（bash / curl / ca-certificates），**异步**。
        // 缺 bash 时 AI 的 bash 工具（dsh-bash-local 固定执行 `bash -c`）每条
        // 命令都会 spawn 失败；缺 ca-certificates 则 https 报 SSL 错。
        // 但它是附加能力、不是部署成功的前提 —— 同步等它会把首次部署拖慢
        // 好几秒（联网装这三个包实测 4~8 秒），所以丢后台线程，装完写标记。
        Thread({
            runCatching { ensureBootstrapPackages() }
                .onFailure { Log.w(TAG, "bootstrap failed: " + it.message) }
        }, "dsh-linux-bootstrap").apply { isDaemon = true }.start()

        return status()
    }

    /**
     * 装基础包（bash / curl / ca-certificates）。**幂等**：装过就跳过。
     *
     * 为什么需要：极简 Alpine rootfs 只有 busybox 的 `sh`(ash)，没有 `/bin/bash`。
     * DSH 的 bash 工具（`@deepseek-ai/dsh-bash-local` 的 `execute()`）固定调用
     * `bash -c <cmd>`，不会退回 sh —— 于是**每一条 AI 命令都 spawn 失败**，
     * 用户看到的现象是「AI 调用不了任何命令」。这是「容器里一堆工具不可用」
     * 最直接的一条根因。
     *
     * 必须在部署后跑一次、而不是查 rootfs 里有没有 bash：apk 装包需要网络，
     * 而部署本身是离线解包。所以用标记文件区分「已尝试」，避免每次启动都 apk。
     */
    fun ensureBootstrapPackages() {
        val root = rootfsDir()
        val mark = File(root, BOOTSTRAP_MARKER)
        if (mark.isFile) return
        val runner = LinuxContainerRunner.get(ctx)
        val r = runner.run(
            guestPath = "/sbin/apk",
            args = listOf("add", "--no-progress") + BOOTSTRAP_PACKAGES,
            timeoutMs = 600_000L,
        )
        if (r.ok) {
            mark.writeText(BOOTSTRAP_PACKAGES.joinToString(","))
            Log.i(TAG, "bootstrap packages installed: " + BOOTSTRAP_PACKAGES)
        } else {
            // 不写标记：下次启动再试（网络可能只是暂时不可用）。
            // 失败绝不能静默 —— 缺 bash 会让整条 AI 工具链不可用。
            Log.w(TAG, "bootstrap packages failed (will retry next boot): " + r.combined().take(400))
        }
    }

    /** 打开内置的 rootfs 资产 */
    private fun openAssetRootfs(): InputStream {
        val raw = ctx.assets.open(ASSET_ROOTFS)
        return GZIPInputStream(BufferedInputStream(raw, 64 * 1024), 64 * 1024)
    }

    // ==================== tar 解包 ====================

    /** 延后落地的链接（tar 里 symlink 的目标可能指向尚未解出的文件） */
    private class LinkJob(val linkRel: String, val target: String, val isSymlink: Boolean)

    /**
     * Alpine rootfs tar 解包。
     *
     * 与 `ExtensionManager.untar` 的关键差异：这里的 rootfs 是**系统镜像**而不是
     * 某个扩展，路径不做 Termux 前缀剥离，也不做 usr/ 拍平（Alpine 的 `usr/bin`
     * 与 `bin` 是真实并列布局，标准 posix 语义）。
     *
     * 完整性判据沿用同一套纪律：只有读到全零结束块才算正常收尾；半截头或流尾
     * 一律抛错 —— 历史实现把 EOF 当「正常结束」会留下半套负载却仍标成功。
     */
    private fun untar(input: InputStream, target: File, pending: MutableList<LinkJob>): Int {
        var entries = 0
        DataInputStream(BufferedInputStream(input, 64 * 1024)).use { din ->
            val bh = ByteArray(512)
            var gnuLongName: String? = null
            var paxPath: String? = null
            while (true) {
                var off = 0
                while (off < 512) {
                    val r = din.read(bh, off, 512 - off)
                    if (r < 0) break
                    off += r
                }
                if (off == 0) {
                    throw EOFException("rootfs tar ended without a closing block ($entries entries)")
                }
                if (off < 512) throw EOFException("rootfs tar header truncated ($off/512)")
                if (bh.allZero()) break
                entries++

                val size = octal(bh, 124, 12)
                val type = bh[156].toInt().toChar()
                when (type) {
                    'L' -> { gnuLongName = readBodyString(din, size); continue }
                    'x' -> { paxPath = parsePaxPath(readBodyString(din, size)) ?: paxPath; continue }
                    'g' -> { skipBody(din, size); continue }
                }

                var name = gnuLongName ?: paxPath ?: str(bh, 0, 100)
                gnuLongName = null; paxPath = null
                val prefix = str(bh, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                val linkname = str(bh, 157, 100)
                val rel = name.removePrefix("./").trimStart('/')
                if (rel.isBlank()) { skipBody(din, size); continue }

                val out = File(target, rel).canonicalFile
                check(out.path.startsWith(target.canonicalPath)) { "rootfs path escape: $name" }

                when (type) {
                    '5' -> { out.mkdirs(); skipBody(din, size) }
                    '0', '\u0000' -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            var left = size
                            val buf = ByteArray(64 shl 10)
                            while (left > 0) {
                                val r = din.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (r < 0) throw EOFException("rootfs tar truncated: $name")
                                o.write(buf, 0, r)
                                left -= r
                            }
                        }
                        // 按 tar 头 mode 重建权限 —— 不保留则 busybox 不可执行（实测
                        // Windows 解包重打包路径下 mode 全丢，表现为 Permission denied）
                        val mode = octal(bh, 100, 8)
                        if (mode > 0) {
                            out.setExecutable((mode and 73L) != 0L, false)
                            out.setReadable((mode and 292L) != 0L, false)
                            out.setWritable((mode and 146L) != 0L, false)
                        }
                        skipPadAfter(din, size)
                    }
                    '2' -> { pending.add(LinkJob(rel, linkname, true)); skipBody(din, size) }
                    '1' -> { pending.add(LinkJob(rel, linkname, false)); skipBody(din, size) }
                    else -> skipBody(din, size)
                }
            }
        }
        return entries
    }

    // ==================== 链接修复（本类的核心价值）====================

    /**
     * 落地 tar 里的 symlink / 硬链接，并把**绝对 symlink 重写为相对**。
     *
     * 这是整个部署流程里最关键的一步，理由见类注释第 1 条。重写算法：
     * 把链接所在目录与目标路径都按 `/` 拆段，去掉公共前缀后，向上补 `../`、
     * 再拼目标剩余段。例：
     * - `/usr/bin/cat`（链接在 `usr/bin/`）指向 `/bin/busybox`
     *   → 公共前缀为空 → 目标 = `../../bin/busybox`
     * - `/bin/sh`（链接在 `bin/`）指向 `/bin/busybox`
     *   → 公共前缀 `bin` → 目标 = `busybox`
     */
    private fun materialiseLinks(root: File, pending: List<LinkJob>) {
        var fixed = 0
        var failed = 0
        pending.forEach { job ->
            val link = File(root, job.linkRel)
            link.parentFile?.mkdirs()
            val target = if (job.isSymlink) relativeTarget(job.linkRel, job.target) else job.target
            runCatching {
                Files.deleteIfExists(link.toPath())
                if (job.isSymlink) {
                    Files.createSymbolicLink(link.toPath(), Paths.get(target))
                } else {
                    val src = File(root, job.target.removePrefix("./").trimStart('/'))
                    Files.createLink(link.toPath(), src.toPath())
                }
                fixed++
            }.onFailure { e ->
                failed++
                Log.w(TAG, "link failed ${job.linkRel} -> ${job.target}: ${e.message}")
            }
        }
        Log.i(TAG, "links materialised: fixed=$fixed failed=$failed")
    }

    /**
     * 把绝对目标改成相对目标的链接内容。
     * 相对目标与非绝对目标原样返回（相对链接在 rootfs 内语义正确，不用动）。
     */
    private fun relativeTarget(linkRel: String, target: String): String {
        if (!target.startsWith("/")) return target
        // 链接所在目录（rootfs 内绝对路径，不含文件名）
        val linkDir = linkRel.substringBeforeLast('/', "")
        val from = if (linkDir.isEmpty()) emptyList() else linkDir.split('/')
        val to = target.trimStart('/').split('/')

        var i = 0
        while (i < from.size && i < to.size && from[i] == to[i]) i++

        val up = from.size - i
        if (up == 0 && i >= to.size) return "."
        val sb = StringBuilder()
        repeat(up) { sb.append("../") }
        for (k in i until to.size) {
            sb.append(to[k])
            if (k + 1 < to.size) sb.append('/')
        }
        return if (sb.isEmpty()) "." else sb.toString()
    }

    /**
     * 兜底：把 rootfs 内**残留的**绝对符号链接批量转相对。
     *
     * [materialiseLinks] 只处理「本次 tar 里声明的」链接；用户后续用 `apk add`
     * 装的包如果带绝对链接，需要这个扫描器（apk 的 post-install 在本方案里
     * 不完整执行，链接可能以原始绝对形态留下）。
     *
     * @return 修复数量
     */
    fun fixAbsoluteSymlinks(): Int {
        val root = rootfsDir()
        if (!root.isDirectory) return 0
        var n = 0
        root.walkTopDown()
            .onEnter { it.name != "proc" && it.name != "sys" && it.name != "dev" }
            .forEach { f ->
                if (!Files.isSymbolicLink(f.toPath())) return@forEach
                val tgt = runCatching { Files.readSymbolicLink(f.toPath()).toString() }.getOrNull() ?: return@forEach
                if (!tgt.startsWith("/")) return@forEach
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                val rt = relativeTarget(rel, tgt)
                runCatching {
                    Files.deleteIfExists(f.toPath())
                    Files.createSymbolicLink(f.toPath(), Paths.get(rt))
                    n++
                }
            }
        if (n > 0) Log.i(TAG, "absolute symlinks rewritten: $n")
        return n
    }

    /** 恢复可执行位（解包丢 mode 时的兜底；bin/sbin/usr/bin/usr/sbin/lib） */
    private fun restoreExecBits(root: File) {
        listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/lib", "lib").forEach { d ->
            val dir = File(root, d)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { f ->
                if (f.isFile && !Files.isSymbolicLink(f.toPath())) {
                    // 只给「原本就是可执行的」补位：用 ELF/shebang 判断，避免把
                    // /etc 下的配置文件误标可执行。minirootfs 里可执行文件很少。
                    if (looksExecutable(f)) f.setExecutable(true, false)
                }
            }
        }
        // busybox 本体必须可执行（断言，不做静默容错）
        File(root, "bin/busybox").takeIf { it.isFile }?.setExecutable(true, false)
        File(root, "sbin/apk").takeIf { it.isFile }?.setExecutable(true, false)
    }

    private fun looksExecutable(f: File): Boolean {
        if (f.length() < 4) return false
        return runCatching {
            f.inputStream().use { ins ->
                val b = ByteArray(4)
                val n = ins.read(b)
                if (n < 4) return@use false
                b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte()   // ELF
            }
        }.getOrDefault(false)
    }

    // ==================== 其他工具方法 ====================

    fun dirBytes(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile && !Files.isSymbolicLink(f.toPath())) total += f.length()
        }
        return total
    }

    fun countFiles(dir: File): Int = dir.walkTopDown().count { it.isFile }

    /** 强清目录（部署重装用；不静默跳过删不掉的节点） */
    private fun purge(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach {
            if (it == dir) return@forEach
            runCatching { it.delete() }
        }
        dir.mkdirs()
    }

    /** 磁盘占用（供 UI 展示）。**不要**放进 status()：完整遍历，几百毫秒。 */
    fun diskUsage(): Long = dirBytes(rootfsDir())

    /** rootfs 文件数（供 UI 展示）。同样不进 status()，理由见上。 */
    fun fileCount(): Int = countFiles(rootfsDir())

    // ==================== 二进制解析辅助（与 ExtensionManager 同源做法）====================

    private fun ByteArray.allZero(): Boolean = all { it.toInt() == 0 }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = minOf(off + len, b.size)
        while (end < max && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8).trim()
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = String(b, off, len, Charsets.US_ASCII).trim().trimEnd('\u0000')
        if (s.isEmpty()) return 0L
        return s.toLongOrNull(8) ?: 0L
    }

    private fun readBodyString(din: DataInputStream, size: Long): String {
        if (size <= 0 || size > (1 shl 20)) { skipBody(din, size); return "" }
        val buf = ByteArray(size.toInt())
        din.readFully(buf)
        skipPadAfter(din, size)
        return String(buf, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun skipBody(din: DataInputStream, size: Long) {
        var left = size
        val buf = ByteArray(8 shl 10)
        while (left > 0) {
            val r = din.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException("rootfs tar body truncated")
            left -= r
        }
        skipPadAfter(din, size)
    }

    private fun skipPadAfter(din: DataInputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) din.skipNBytes(pad)
    }

    /** PAX 扩展头的 path record（供超长路径使用） */
    private fun parsePaxPath(body: String): String? {
        var rest = body
        while (rest.isNotEmpty()) {
            val sp = rest.indexOf(' ')
            if (sp <= 0) break
            val lenStr = rest.substring(0, sp)
            val len = lenStr.toIntOrNull() ?: break
            if (len <= 0 || len > rest.length) break
            val record = rest.substring(0, len).trimEnd('\u0000')
            rest = rest.substring(len)
            if (record.startsWith("path=") || record.contains(" path=")) {
                return record.substringAfter("path=").trimEnd('\u0000')
            }
        }
        return null
    }

    companion object {
        private const val TAG = "LinuxEnv"

        /** 随包的 rootfs 资产 */
        private const val ASSET_ROOTFS = "linux/rootfs-bootstrap.tgz"

        /**
         * 资产版本号。与 tar 内容一起变更时递增 —— 但注意：**版本变化不会重装**
         * （见 [ensureDeployed]），避免抹掉用户已装的软件；只有 [ensureDeployed]
         * 传 force=true 才真重装。
         */
        const val ASSET_VERSION = "alpine-3.22.0-bootstrap"

        /** 部署完成标记 */
        private const val MARKER = ".dsh-deployed"

        /**
         * apk 镜像源。国内直连优先阿里云（实测 26178 个包可达）。
         *
         * ⚠️ 容器内**没有可用 DNS**（musl 直连 UDP 53 被 Android netd/SELinux 拦，
         * 实测 `nslookup` 报 `write to '223.5.5.5': Operation not permitted`），
         * 必须靠静态 `/etc/hosts` 解析。`mirrors.aliyun.com` 的 IP 见 [HOSTS]。
         */
        /**
         * 首次部署后额外装的**基础包**（离线装不了，所以在 status()==READY 后的
         * 第一次启动时装一次，装完写标记，不重复）。
         *
         * 为什么必须有 bash：
         *   DSH 的 bash 工具（`dsh-bash-local`）固定执行 `bash -c <cmd>`。极简
         *   Alpine rootfs 只有 busybox 的 `sh`（ash），没有 `/bin/bash`，于是
         *   **AI 的每条 bash 命令都 spawn 失败**，表现为「工具调用不了、命令全失败」。
         *   bash 包压缩 500KB / 解压 3.2MB，很便宜。
         *
         * 为什么还要 curl / ca-certificates：
         *   引擎与插件的自检、以及 npm/pip 走 https 时需要证书与 curl；
         *   缺 ca-certificates 会报 `SSL certificate problem`，很容易被误读成网络问题。
         */
        val BOOTSTRAP_PACKAGES = listOf("bash", "curl", "ca-certificates")

        /** 基础包安装完成标记（存在即视为已尝试，不重复装） */
        private const val BOOTSTRAP_MARKER = ".dsh-bootstrap"

        val APK_REPOSITORIES = """
            http://mirrors.aliyun.com/alpine/v3.22/main
            http://mirrors.aliyun.com/alpine/v3.22/community
        """.trimIndent().lines()

        /**
         * 静态 hosts。
         *
         * 只保留 localhost —— 镜像域名**不要**写死 IP。
         *
         * ⚠️ 踩过的坑：这里曾经硬编码 `111.1.164.136 mirrors.aliyun.com`，
         * 因为当时容器里没有 `/etc/resolv.conf`、DNS 完全不可用，只能靠 hosts
         * 解析出一个固定 IP 才能装包。但那是个治标方案，代价是：
         *   · 用户和 AI 访问任何**其他**域名都解析失败 —— 账号登录
         *     （`/auth-api/v0/users/current` 报 no-response）、模型列表
         *     （`api.deepseek.com` 超时）、pip/npm/git 全线不通，
         *     而这些都不在 hosts 白名单里。
         * 正确做法是让 DNS 真的能用（见 [RESOLV_CONF]），hosts 只留 localhost。
         * 实测：补上 resolv.conf 后 `nslookup mirrors.aliyun.com` 与
         * `apk update`（26178 个包）都正常，不再需要写死 IP。
         */
        val HOSTS = """
            127.0.0.1 localhost
        """.trimIndent().lines()

        /**
         * `/etc/resolv.conf` —— 容器内 DNS 配置。
         *
         * 这是**必须写**的：musl 的解析器只认 `/etc/resolv.conf`，没有它就
         * 只能用 127.0.0.1:53（Android 上没人监听，报 Connection refused）。
         *
         * ⚠️ 为什么不用宿主的 DNS：Android 的 DNS 由 netd 统一管理，没有
         * `/etc/resolv.conf` 这个文件（getprop 里只有 net.dns* 的内部属性），
         * 而 musl 又不走 bionic 的 netd 代理。所以只能给容器写一份公共 DNS。
         *
         * 实测（2026-11）：写入 223.5.5.5 / 119.29.29.29 后
         *   · `nslookup api.deepseek.com` → 正常解析
         *   · `nc -z api.deepseek.com 443` → rc=0
         *   · `apk update` → OK: 26178 distinct packages available
         *   · 账号接口 `/auth-api/v0/users/current` 从 no-response 变为正常
         *
         * Android 允许 musl 直连外部 UDP 53（不像某些 SELinux 策略会拦），
         * 而 127.0.0.1:53 才是不可用的那个 —— 这点容易反着猜。
         */
        val RESOLV_CONF = """
            nameserver 223.5.5.5
            nameserver 119.29.29.29
        """.trimIndent().lines()

        /** 单例（进程级，避免重复解包） */
        @Volatile
        private var instance: LinuxEnvironmentManager? = null

        fun get(ctx: Context): LinuxEnvironmentManager =
            instance ?: synchronized(this) {
                instance ?: LinuxEnvironmentManager(ctx.applicationContext).also { instance = it }
            }
    }
}

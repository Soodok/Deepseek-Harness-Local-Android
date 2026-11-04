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

        return Status(State.READY, ver, rootfsBytes = dirBytes(root), fileCount = countFiles(root))
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

        return status()
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

    /** 磁盘占用（供 UI 展示） */
    fun diskUsage(): Long = dirBytes(rootfsDir())

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
        val APK_REPOSITORIES = """
            http://mirrors.aliyun.com/alpine/v3.22/main
            http://mirrors.aliyun.com/alpine/v3.22/community
        """.trimIndent().lines()

        /**
         * 静态 hosts。IP 取自实测（2026-11）。
         *
         * 只有阿里云对纯 http + 非浏览器 UA 放行；TUNA/USTC 对 IP 直连返回 403，
         * 所以这里只列阿里云。镜像失效时的兜底策略见 [resolveMirror]。
         */
        val HOSTS = """
            127.0.0.1 localhost
            111.1.164.136 mirrors.aliyun.com
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

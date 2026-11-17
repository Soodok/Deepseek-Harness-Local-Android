package app.dsh.mobile.engine

import java.io.File

/**
 * rootfs 里「包装了但文件不可用」的两类损坏，以及它们的判据。
 *
 * ## 为什么需要这个（真机证据，2026-11-14 / PLZ110 / Alpine 3.22）
 *
 * 用户在扩展中心装 `build-base`，`apk add` 报
 * `6 errors; 576 MiB in 114 packages`，而**包确实全在** ——
 * `apk info -e gcc` 认得它、`/lib/apk/db/installed` 里也有它。
 * 但接下来 `gcc --version` 报 `/bin/sh: gcc: not found`，`which gcc` 是空的。
 * 直接看文件才露馅：
 *
 * ```
 * -rw-r--r--  1 10499 10499  1905896  /usr/bin/gcc      ← 缺 x 位
 * -rw-r--r--  1 10499 10499   529800  /usr/bin/as       ← 同上
 * -rw-r--r--  1 10499 10499  2431592  /usr/bin/ld       ← 同上
 * drwxr-xr-x  3 root  root      3452  /usr/include      ← 属主是 root
 * ```
 *
 * 手工 `chmod +x /usr/bin/gcc` 后 `gcc --version` 立刻正常；
 * 手工 `chown -R 10499:10499 /usr/include` 再 `apk del musl-dev && apk add
 * musl-dev` 后 `stdio.h` 才落地、`gcc` 才编得出 `C-OK`。
 *
 * ## 两类损坏，两个不同判据
 *
 * | 症状 | 根因 | 判据 | 谁能修 |
 * |---|---|---|---|
 * | 可执行文件缺 x 位 | `apk` 的 chmod 阶段失败（目录属主不对） | 是 ELF / 带 shebang，却没有 x 位 | **App 自己**（属主已是自己） |
 * | 头文件装不进去 | 目标目录属主是 root，App 写不进 | 目录 `canWrite()` 为假 | 只有特权通道 |
 *
 * 这两件事**必须分开判**：第一类 App 域自己能修（就是补个权限位），
 * 第二类必须走 `Privilege.normalizeOwnership`。混成一个「修复」会让人以为
 * 没有 root 就什么都修不了 —— 而实际上第一类不需要任何特权。
 *
 * ## 为什么不能「bin 目录里的都 chmod +x」
 *
 * `/usr/bin` 下不全是可执行文件。判据必须窄，否则会把数据文件改成可执行，
 * 那是一种新的损坏。这里用**内容**判：ELF 头（`\x7fELF`）或 `#!` shebang。
 * 内容与「该不该可执行」的关系是确定的，权限位本身反而是不可信的（它正是
 * 被弄坏的那个）。
 */
internal object RootfsModes {

    /** ELF 魔数。 */
    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /**
     * 应该可执行、但在这台机器上**不该**指望 x 位的目录。
     *
     * 只列传统 bin 目录：`/usr/lib` 下的 `.so` 不需要 x 位（`ld.so` 会
     * mmap 它），`/usr/share` 更是纯数据。把范围收窄既是性能也是安全 ——
     * 这条修复会递归遍历，范围越窄越不容易误伤。
     */
    val BIN_DIRS = listOf(
        "bin",
        "sbin",
        "usr/bin",
        "usr/sbin",
        "usr/libexec",
        "usr/local/bin",
        "usr/local/sbin",
    )

    /**
     * 判断一个文件「该不该有 x 位」。
     *
     * @param head 文件**前几个字节**（至少 4 个；调用方读不到就给空数组）
     * @param hasExecBit 当前是否已有任一 x 位（`mode and 0b001_001_001 != 0`）
     * @return true 表示该补 x 位
     */
    fun needsExecBit(head: ByteArray, hasExecBit: Boolean): Boolean {
        if (hasExecBit) return false
        return isElf(head) || isShebang(head)
    }

    /** 是否是 ELF（`\x7fELF`）。 */
    fun isElf(head: ByteArray): Boolean {
        if (head.size < ELF_MAGIC.size) return false
        for (i in ELF_MAGIC.indices) {
            if (head[i] != ELF_MAGIC[i]) return false
        }
        return true
    }

    /**
     * 是否是脚本（`#!`）。
     *
     * Alpine 的 bin 目录里脚本不少（`/usr/bin/apk` 自己不是，但 `ldconfig`
     * 一类有）。判据只要前两字节，不需要读整行。
     */
    fun isShebang(head: ByteArray): Boolean =
        head.size >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()

    /**
     * 扫描一个 rootfs 下的 bin 目录，返回**需要补 x 位**的文件。
     *
     * 只读前 4 字节判类型，不做内容哈希 —— 这个函数会在每次装包后跑一遍，
     * 遍历量可达几千个文件，读整文件不可接受。
     *
     * 不跟随符号链接：链接自身的权限位没有意义（0644 的软链照样能执行），
     * 把它算进来只会刷出一堆永远修不完的条目。
     *
     * @param hasExec 判定「这个文件现在有没有 x 位」。默认 `File.canExecute()`，
     *   也就是 Android/Linux 上的真实答案。
     *
     *   ★为什么要做成参数：**Windows 上 `canExecute()` 恒为 true**（NTFS 的
     *   ACL 模型里没有 POSIX x 位，JDK 一律报可执行）。于是单测在 Windows
     *   开发机上会把所有文件都判成「已有 x 位」，扫描逻辑一行都测不到 ——
     *   13 条测试里 4 条假绿/假红全出在这里。把平台原语注入进来，
     *   被测的是扫描逻辑本身，不受宿主文件系统模型影响。
     */
    fun findMissingExecBit(
        rootfs: File,
        limit: Int = 200,
        hasExec: (File) -> Boolean = { it.canExecute() },
    ): List<File> {
        val out = ArrayList<File>()
        for (rel in BIN_DIRS) {
            val dir = File(rootfs, rel)
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (out.size >= limit) return out
                if (f.isDirectory) continue
                if (isSymlink(f)) continue
                val readable = runCatching { f.canRead() }.getOrDefault(false)
                if (!readable) continue
                if (runCatching { hasExec(f) }.getOrDefault(false)) continue
                val head = readHead(f, 4)
                if (needsExecBit(head, hasExecBit = false)) out.add(f)
            }
        }
        return out
    }

    /**
     * 读文件头。
     *
     * 读不到（EPERM/EACCES）就返回空数组 —— 判据会因此变假，**这是刻意的**：
     * 读不出内容时不该猜它可执行，宁可漏修也不能把数据文件改成可执行。
     */
    fun readHead(f: File, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        return runCatching {
            f.inputStream().use { ins ->
                val buf = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = ins.read(buf, off, n - off)
                    if (r <= 0) break
                    off += r
                }
                if (off == n) buf else buf.copyOf(off)
            }
        }.getOrDefault(ByteArray(0))
    }

    /**
     * Android 的 `File.isSymlink` 不可用（API 26 没有），用 canonical 对比。
     *
     * 这里不复用 `SelinuxContext` 的实现：那个是给「读 SELinux 标签」用的，
     * 依赖特权通道；这里的判据必须在**纯 JVM 单测**里也能跑。
     */
    fun isSymlink(f: File): Boolean = runCatching {
        f.canonicalPath != f.absolutePath
    }.getOrDefault(false)

    /**
     * 就地修掉「该可执行却没有 x 位」的文件，返回修了几个。
     *
     * ## 为什么 App 自己就能修，不需要特权
     *
     * 真机上这些文件的**属主已经是 App**（`10499 10499`），只是权限位是
     * `0644`。`chmod` 只有属主或 root 能做 —— 属主是我们就够了。
     * 所以这条修复在普通模式（无 Shizuku、无 root）下也能生效，
     * 不要因为「它属于装包链路」就条件反射地走特权通道：
     * 那会让没有 root 的用户以为这个故障修不了。
     *
     * ## 空操作是常态
     *
     * 每次装包后都会跑一遍。绝大多数包的权限是对的，此时扫一圈什么都
     * 不改（`hasExec` 为真的直接跳过，连文件头都不读）。代价是几次
     * `listFiles()`，对手机来说可以忽略。
     *
     * @return 实际补了 x 位的文件数；0 表示没有需要修的
     */
    fun repairExecBits(rootfs: File, limit: Int = 200): Int {
        var fixed = 0
        for (f in findMissingExecBit(rootfs, limit)) {
            val ok = runCatching { f.setExecutable(true, false) }.getOrDefault(false)
            if (ok) fixed += 1
        }
        return fixed
    }

    /**
     * 该包名是否「需要编译链」—— 用来决定要不要顺手修头文件目录属主。
     *
     * 判据是包名里出现这些词。**不是**精确的包依赖分析（那要解析
     * `lib/apk/db/installed` 的 `D:` 依赖行），只是一个便宜的启发式：
     * 装编译器时才值得多走一次特权通道。
     */
    fun wantsHeaders(packages: List<String>): Boolean {
        val markers = listOf("gcc", "g++", "build-base", "musl-dev", "clang", "cmake", "make", "linux-headers")
        return packages.any { p -> markers.any { m -> p.contains(m) } }
    }
}

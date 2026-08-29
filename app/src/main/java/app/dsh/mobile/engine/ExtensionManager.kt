package app.dsh.mobile.engine

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 环境扩展管理器（v1.2.1 重构：Termux 仓库实时安装）。
 *
 * 下载源：Termux 官方仓库的国内镜像（TUNA/USTC/BFSU，官方兜底）——解决用户真机
 * GitHub Releases 全超时的问题。安装链路：
 *   Packages.gz 索引 → 依赖闭包解析 → 逐包 .deb（SHA256 强校验）→
 *   ar 归档 → data.tar.xz → tar 解包（GNU longname / PAX / symlink / 硬链接）→
 *   usr/ 前缀拍平 → 恢复可执行位 → rename 原子发布。
 * 相比预打包 zip：版本永远最新、国内直连快、无需人工维护包仓库。
 *
 * 三态判定（UI 红黄绿）：
 *  - 红  NOT_DOWNLOADED：extensions/<id>/ 无 .ext-version 标记
 *  - 黄  DOWNLOADED    ：已安装但未激活（并入引擎 PATH 需激活+重启引擎）
 *  - 绿  ACTIVATED     ：已激活（下次引擎重启后二进制进入 PATH/LD_LIBRARY_PATH）
 */
class ExtensionManager(private val ctx: Context) {

    // ================= 数据模型 =================

    data class Extension(
        val id: String,
        val name: String,
        val category: String,
        val desc: String,
        val bins: List<String>,
        val packages: List<String>,
    )

    enum class ExtState { NOT_DOWNLOADED, DOWNLOADED, ACTIVATED }

    /** Packages 索引中的一个包 */
    private data class RepoPkg(
        val name: String,
        val version: String,
        val filename: String,
        val sha256: String,
        val size: Long,
        val depends: List<String>,
    )

    /** 延后落地的链接（symlink/硬链接），rename 发布后在最终目录创建 */
    private data class LinkJob(val linkRel: String, val target: String, val isSymlink: Boolean)

    // ================= 存储 =================

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val extRoot get() = File(EngineConfig.engineRoot(ctx), "extensions")

    private fun dirOf(id: String) = File(extRoot, id)
    private fun markerOf(id: String) = File(dirOf(id), MARKER)

    // ================= 清单 =================

    fun loadCatalog(): List<Extension> {
        val raw = ctx.assets.open("extensions/catalog.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val items = root.getJSONArray("items")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Extension(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.optString("category", "扩展"),
                desc = o.optString("desc", ""),
                bins = o.optJSONArray("bins")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                packages = o.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }
    }

    private fun mirrors(): List<String> {
        val raw = ctx.assets.open("extensions/catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(raw).optJSONArray("mirrors") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** 设备 ABI → Termux 仓库架构键（binary-aarch64 / binary-x86_64） */
    fun deviceAbiKey(): String =
        when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a", "aarch64" -> "aarch64"
            else -> "x86_64"
        }

    // ================= 三态 =================

    fun state(id: String): ExtState {
        val installed = markerOf(id).isFile
        if (!installed) {
            if (prefs.getBoolean(keyActivated(id), false)) {
                // 目录被手动清掉：惰性修正标记，避免幽灵激活
                prefs.edit().putBoolean(keyActivated(id), false).apply()
            }
            return ExtState.NOT_DOWNLOADED
        }
        return if (prefs.getBoolean(keyActivated(id), false)) ExtState.ACTIVATED else ExtState.DOWNLOADED
    }

    /** 已安装扩展的实际版本号（安装时从仓库索引记录），未安装返回 null */
    fun installedVersion(id: String): String? =
        markerOf(id).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun isInstalling(id: String): Boolean = installing.contains(id)

    // ================= 安装主流程（阻塞，须在 IO/后台线程调用） =================

    /**
     * 从 Termux 镜像安装扩展（依赖闭包全自动）。装完【不自动激活】——
     * 激活由调用方决定（App UI 保持黄色态等用户确认；AI 通道 /ext/install 会自动激活）。
     *
     * @param onProgress 0f..1f（下载段 0~0.95 按字节，解包/发布 0.95~1）
     * @param onStage 人类可读阶段文案（"解析依赖闭包… / python 3/17 包 / 源被拒切换…"），
     *                UI 应实时上屏——failover 期间给用户"活着"的证据，避免看起来像卡死
     */
    fun download(
        ext: Extension,
        onProgress: (Float) -> Unit = {},
        onStage: (String) -> Unit = {},
    ) {
        check(installing.add(ext.id)) { "扩展 ${ext.id} 正在安装中" }
        try {
            installFromRepo(ext, onProgress, onStage)
        } finally {
            installing.remove(ext.id)
        }
    }

    private fun installFromRepo(ext: Extension, onProgress: (Float) -> Unit, onStage: (String) -> Unit) {
        val finalDir = dirOf(ext.id)
        val tmpDir = File(extRoot, "${ext.id}.tmp-install")
        val cacheDir = File(ctx.cacheDir, "ext-${ext.id}").apply { mkdirs() }
        try {
            extRoot.mkdirs()
            // 半截安装先清场重装；顺手清全部历史 tmp 残留（安装线程被杀/中断留下的孤儿目录）
            if (finalDir.exists() && !markerOf(ext.id).isFile) finalDir.deleteRecursively()
            extRoot.listFiles { f -> f.name.endsWith(".tmp-install") }?.forEach { it.deleteRecursively() }

            // 1. 仓库索引 + 依赖闭包（镜像列表全程复用，deb 下载同样做 failover）
            val allMirrors = mirrors().ifEmpty {
                listOf("https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main")
            }
            onStage("解析依赖闭包…")
            val index = fetchPackagesIndex(allMirrors.first(), onStage)
            val closure = resolveClosure(ext.packages, index)
            val mainPkg = index[ext.packages.first()]
                ?: throw IllegalStateException("包 ${ext.packages.first()} 不在仓库索引中")
            Log.i(TAG, "install ${ext.id}: ${closure.size} pkgs, ${closure.sumOf { it.size } / 1048576}MB from ${allMirrors.first()}")

            // 2. 逐包下载 + SHA256 强校验（进度按字节累计，占 0~0.95）
            val totalBytes = closure.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            val debs = mutableListOf<File>()
            closure.forEachIndexed { idx, p ->
                val f = File(cacheDir, "${p.name}_${p.version}.deb")
                val label = "${ext.name} ${idx + 1}/${closure.size} 包"
                downloadDebWithFailover(allMirrors, p.filename, f, label, onStage) { frac ->
                    onProgress(((done + p.size * frac).toDouble() / totalBytes).toFloat() * 0.95f)
                }
                check(p.sha256.isEmpty() || RuntimeInstaller.sha256(f) == p.sha256.lowercase()) {
                    "SHA-256 校验失败: ${p.name}（镜像源数据异常？）"
                }
                done += p.size
                debs.add(f)
            }

            // 3. 解包（symlink/硬链接延后到 rename 之后创建——避免绝对链接指向临时目录）
            val pendingLinks = mutableListOf<LinkJob>()
            debs.forEach { deb -> extractDeb(deb, tmpDir, pendingLinks) }
            onProgress(0.96f)

            // 4. 拍平 usr/ 布局 → 可执行位 → 版本标记 → 原子发布
            flattenUsrLayout(tmpDir)
            restoreExecBits(tmpDir)
            File(tmpDir, MARKER).writeText(mainPkg.version)
            check(tmpDir.renameTo(finalDir)) { "扩展目录发布失败（rename）: ${tmpDir.path}" }
            onProgress(0.99f)

            createLinks(finalDir, pendingLinks)
            onProgress(1f)
            Log.i(TAG, "extension ${ext.id} installed v${mainPkg.version} (${closure.size} pkgs)")
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    // ================= 镜像与仓库索引 =================

    /** 单包 .deb 下载：按镜像顺序 failover，每次切换都上报阶段（用户可见），全部失败抛聚合错误 */
    private fun downloadDebWithFailover(
        mirrors: List<String>, filename: String, dest: File,
        label: String, onStage: (String) -> Unit, onProgress: (Float) -> Unit
    ) {
        var lastErr: Exception? = null
        for ((i, m) in mirrors.withIndex()) {
            try {
                onStage("$label · ${URL(m).host}")
                downloadTo("$m/$filename", dest, onProgress)
                return
            } catch (e: Exception) {
                lastErr = e
                Log.w(TAG, "deb fail: $m/$filename (${e.message})")
                dest.delete()
                if (i < mirrors.lastIndex) onStage("$label · ${URL(m).host} 失败，切换源 ${i + 2}/${mirrors.size}…")
            }
        }
        throw IllegalStateException("$label：${mirrors.size} 个镜像源均下载失败（${lastErr?.message}）")
    }

    /** 拉取并解析 Packages.gz（按镜像顺序自动 failover，选第一个成功的；切换时上报阶段） */
    private fun fetchPackagesIndex(preferred: String, onStage: (String) -> Unit = {}): Map<String, RepoPkg> {
        val abiPath = "binary-${deviceAbiKey()}"
        var lastErr: Exception? = null
        // 优先用户目录排前的镜像，失败顺延
        val ordered = listOf(preferred) + mirrors().filter { it != preferred }
        for ((i, m) in ordered.withIndex()) {
            try {
                onStage("拉取仓库索引 · ${URL(m).host}")
                val url = "$m/dists/stable/main/$abiPath/Packages.gz"
                val gz = GZIPInputStream(ByteArrayInputStream(downloadBytes(url)))
                val index = parsePackages(gz)
                Log.i(TAG, "packages index from $m: ${index.size} pkgs")
                return index
            } catch (e: Exception) {
                lastErr = e
                Log.w(TAG, "mirror fail: $m (${e.message})")
                if (i < ordered.lastIndex) onStage("源 ${URL(m).host} 不可达，切换源 ${i + 2}/${ordered.size}…")
            }
        }
        throw IllegalStateException("所有 Termux 镜像源均不可达，请检查网络：${lastErr?.message}")
    }

    /** 解析 apt Packages 文本索引（含续行过滤：缩进行属于上一键，直接忽略） */
    private fun parsePackages(gz: InputStream): Map<String, RepoPkg> {
        val text = gz.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val out = HashMap<String, RepoPkg>(2048)
        text.split("\n\n").forEach { block ->
            var name = ""; var ver = ""; var fn = ""; var sha = ""
            var size = 0L; var deps = emptyList<String>()
            block.lineSequence().forEach { line ->
                if (line.isEmpty() || line[0] == ' ' || line[0] == '\t') return@forEach
                val idx = line.indexOf(": ")
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx)
                val v = line.substring(idx + 2).trim()
                when (key) {
                    "Package" -> name = v
                    "Version" -> ver = v
                    "Filename" -> fn = v
                    "SHA256" -> if (v.length == 64) sha = v
                    "Size" -> size = v.toLongOrNull() ?: 0L
                    "Depends" -> deps = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            if (name.isNotEmpty() && fn.isNotEmpty()) {
                out[name] = RepoPkg(name, ver, fn, sha, size, deps)
            }
        }
        check(out.isNotEmpty()) { "Packages 索引解析为空" }
        return out
    }

    /** 依赖闭包（BFS）：剥版本约束，| 备选项依序取第一个索引中存在的 */
    private fun resolveClosure(wants: List<String>, index: Map<String, RepoPkg>): List<RepoPkg> {
        val out = LinkedHashMap<String, RepoPkg>()
        val queue = ArrayDeque(wants)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (out.containsKey(name)) continue
            val p = index[name] ?: throw IllegalStateException("包 $name 不在 Termux 仓库索引中")
            out[name] = p
            p.depends.forEach { dep ->
                val candidates = dep.split("|").map { it.trim().substringBefore(' ').trim() }
                val hit = candidates.firstOrNull { index.containsKey(it) }
                    ?: candidates.firstOrNull { it.isNotEmpty() }
                if (!hit.isNullOrEmpty() && !out.containsKey(hit)) queue.addLast(hit)
            }
        }
        return out.values.toList()
    }

    // ================= .deb / tar 解包 =================

    /** ar 归档定位 data.tar.* 成员并解 tar（Termux .deb 为 data.tar.xz） */
    private fun extractDeb(deb: File, target: File, pending: MutableList<LinkJob>) {
        DataInputStream(BufferedInputStream(FileInputStream(deb))).use { din ->
            val magic = ByteArray(8)
            din.readFully(magic)
            check(String(magic, 0, 8, StandardCharsets.US_ASCII) == "!<arch>\n") {
                "不是有效的 .deb: ${deb.name}"
            }
            while (true) {
                val h = ByteArray(60)
                try {
                    din.readFully(h)
                } catch (e: EOFException) {
                    break
                }
                val name = String(h, 0, 16, StandardCharsets.US_ASCII).trim().trimEnd('/')
                val size = String(h, 48, 10, StandardCharsets.US_ASCII).trim().toLongOrNull() ?: break
                if (name.startsWith("data.tar.")) {
                    val limited = LimitInputStream(din, size)
                    val tar: InputStream = when {
                        name.endsWith(".xz") -> XZInputStream(limited)
                        name.endsWith(".gz") -> GZIPInputStream(limited)
                        name.endsWith(".tar") -> limited
                        else -> throw IllegalStateException("不支持的 data.tar 格式: $name")
                    }
                    untar(tar, target, pending)
                    return
                }
                skipFully(din, size + (size and 1))   // ar 成员 2 字节对齐
            }
            throw IllegalStateException(".deb 中未找到 data.tar 成员: ${deb.name}")
        }
    }

    /**
     * tar 流解包。完整支持：目录/普通文件/symlink('2')/硬链接('1')、
     * GNU longname('L')、PAX 扩展头('x' 的 path record)。
     * 设备特殊文件与 mtime 一律忽略；symlink/硬链接延后到 createLinks() 落地。
     */
    private fun untar(input: InputStream, target: File, pending: MutableList<LinkJob>) {
        DataInputStream(BufferedInputStream(input)).use { din ->
            val bh = ByteArray(512)
            var gnuLongName: String? = null
            var paxPath: String? = null
            while (true) {
                try {
                    din.readFully(bh)
                } catch (e: EOFException) {
                    break
                }
                if (bh.allZero()) break   // 结束块
                val size = octal(bh, 124, 12)
                val type = bh[156].toInt().toChar()
                when (type) {
                    'L' -> {   // GNU longname：内容是下一个 entry 的真实名字
                        gnuLongName = readBodyString(din, size)
                        continue
                    }
                    'x' -> {   // PAX 扩展头：path record 覆盖下一个 entry 名
                        paxPath = parsePaxPath(readBodyString(din, size)) ?: paxPath
                        continue
                    }
                    'g' -> { skipBody(din, size); continue }   // PAX 全局头，忽略
                }
                var name = gnuLongName ?: paxPath ?: str(bh, 0, 100)
                gnuLongName = null; paxPath = null
                val prefix = str(bh, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                val linkname = str(bh, 157, 100)

                // 前导 '/' 的条目（vim 等包混用 './data/...' 与 '/data/...' 两种形态）
                // 必须先 trimStart('/') 再剥 Termux data 前缀，否则残留 data/data/... 深嵌套
                val rel = name.removePrefix("./").trimStart('/').removePrefix(TERMUX_DATA_PREFIX).removePrefix("/")
                if (rel.isBlank()) { skipBody(din, size); continue }
                val out = File(target, rel).canonicalFile
                check(out.path.startsWith(target.canonicalPath)) { "tar 路径逃逸: $name" }

                when (type) {
                    '5' -> {   // 目录
                        out.mkdirs()
                        skipBody(din, size)
                    }
                    '0', '\u0000' -> {   // 普通文件（手写循环，防 FilterInputStream 连带关闭上游）
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            var left = size
                            val buf = ByteArray(64 shl 10)
                            while (left > 0) {
                                val r = din.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (r < 0) throw EOFException("tar 流被截断: $name")
                                o.write(buf, 0, r)
                                left -= r
                            }
                        }
                        skipPadAfter(din, size)
                    }
                    '2' -> {   // symlink
                        pending.add(LinkJob(rel, linkname, isSymlink = true))
                        skipBody(din, size)
                    }
                    '1' -> {   // 硬链接
                        pending.add(LinkJob(rel, linkname, isSymlink = false))
                        skipBody(din, size)
                    }
                    else -> skipBody(din, size)   // char/block/fifo 等设备文件，忽略
                }
            }
        }
    }

    /** 512 对齐 padding 计算与跳读见 skipPadAfter；GNU/PAX 头内容读取由 readBodyString 完成 */

    /** symlink/硬链接落地：rename 发布后创建，绝对 target 从 Termux 前缀重写到扩展根 */
    private fun createLinks(finalDir: File, pending: List<LinkJob>) {
        pending.forEach { job ->
            // linkRel 带 usr/ 前缀（解包期原始布局）；flattenUsrLayout 已把 usr/* 提升到根，
            // 必须同步剥前缀，否则 symlink 落进重建的 usr/ 残留目录、bin 下缺链接
            // （golang bin/go、vim bin/vim 缺失实测事故）。相对 target 因 usr/* 同级化平移，语义不变。
            val link = File(finalDir, job.linkRel.removePrefix("usr/"))
            link.parentFile?.mkdirs()
            runCatching {
                if (job.isSymlink) {
                    val t = when {
                        job.target.startsWith(TERMUX_PREFIX) ->
                            File(finalDir, job.target.removePrefix("$TERMUX_PREFIX/")).absolutePath
                        else -> job.target
                    }
                    Files.deleteIfExists(link.toPath())
                    Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(t))
                } else {
                    val srcRel = job.target.removePrefix("./").removePrefix(TERMUX_DATA_PREFIX).removePrefix("/")
                    val src = File(finalDir, srcRel)
                    if (link.exists()) link.delete()
                    Files.createLink(link.toPath(), src.toPath())
                }
            }.onFailure { e ->
                // symlink 创建失败（个别 ROM 限制）→ 复制目标内容兜底
                val src = resolveLinkSource(finalDir, job)
                if (src?.isFile == true) {
                    if (link.exists()) link.delete()
                    src.copyTo(link, overwrite = true)
                } else {
                    Log.w(TAG, "link 落地失败（忽略）: ${job.linkRel} -> ${job.target}: ${e.message}")
                }
            }
            // restoreExecBits 只扫 bin/ 且在 rename 前执行，追不到后建的链接目标本体
            // （go -> ../lib/go/bin/go 实测 Permission denied）；setExecutable 沿 symlink 落到本体
            link.setExecutable(true, false)
            link.setReadable(true, false)
        }
    }

    /** 兜底复制时解析链接目标（相对 target 以链接父目录为基准） */
    private fun resolveLinkSource(finalDir: File, job: LinkJob): File? = when {
        job.target.startsWith(TERMUX_PREFIX) ->
            File(finalDir, job.target.removePrefix("$TERMUX_PREFIX/"))
        job.target.startsWith("/") -> null
        else -> File(File(finalDir, job.linkRel).parent, job.target)
    }.takeIf { it?.isFile == true }

    // ================= Termux 布局 =================

    /** usr/ 前缀布局：usr 存在且根下无 bin 时，把 usr 内条目提升到根（bin/lib 平级，相对链接仍成立） */
    private fun flattenUsrLayout(root: File) {
        val usr = File(root, "usr")
        // 条件不能含「根下已有 bin 则跳过」：部分包条目缺 usr 中缀（如 clang wrapper 直落根 bin），
        // 会令整个提升被跳过、usr 全体残留（golang 的 go 本体困在 usr/lib 实测事故）
        if (!usr.isDirectory) return
        usr.listFiles()?.forEach { child ->
            val dest = File(root, child.name)
            if (dest.exists()) dest.deleteRecursively()
            if (!child.renameTo(dest)) {
                child.copyRecursively(dest, overwrite = true)
                child.deleteRecursively()
            }
        }
        usr.delete()
    }

    private fun restoreExecBits(root: File) {
        listOf("bin", "usr/bin").forEach { rel ->
            File(root, rel).takeIf { it.isDirectory }?.listFiles()?.forEach {
                it.setExecutable(true, false)
                it.setReadable(true, false)
            }
        }
    }

    // ================= 激活 / 停用 / 卸载 =================

    /** 激活：并入引擎 PATH/LD_LIBRARY_PATH（需重启引擎生效） */
    fun activate(id: String) {
        check(markerOf(id).isFile) { "扩展未安装，无法激活" }
        prefs.edit().putBoolean(keyActivated(id), true).apply()
    }

    /** 停用：从引擎环境中摘除（保留文件，可随时再激活） */
    fun deactivate(id: String) = prefs.edit().putBoolean(keyActivated(id), false).apply()

    /** 卸载：删除文件与全部状态标记 */
    fun remove(id: String) {
        dirOf(id).deleteRecursively()
        prefs.edit().remove(keyActivated(id)).apply()
    }

    /** 已激活且目录健在的扩展数（UI 副标题计数用） */
    fun activeCount(): Int = activeRoots(ctx).size

    // ================= 网络 =================

    /** 403/4xx 的可读化：403 高概率是手机侧加速器/VPN 劫持了国内镜像流量 */
    private fun httpFail(code: Int, url: String): String = when (code) {
        403 -> "HTTP 403 被拒绝: $url —— 若开启了加速器/VPN 请关闭后重试"
        404 -> "HTTP 404 资源不存在: $url（镜像同步缺失？）"
        else -> "下载失败 HTTP $code: $url"
    }

    /**
     * 统一连接工厂。关键：connectTimeout 不覆盖 DNS 解析阶段——
     * DNS 黑洞（被劫持/防火墙吞包）会让请求挂 30s+ 且任何超时参数都管不到，
     * 表现为用户侧"永远没有反馈"。因此连接前先做 5s 超时的 DNS 预检，
     * 解析不出来立刻抛错触发镜像 failover。
     */
    private fun openConn(url: String, readTimeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        val addrs = dnsResolve(u.host, timeoutMs = 5_000)
            ?: throw IllegalStateException("DNS 解析超时: ${u.host}（网络受限或被加速器/VPN 劫持？）")
        check(addrs.isNotEmpty()) { "DNS 解析失败: ${u.host}" }
        val conn = u.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", HTTP_UA)
        conn.setRequestProperty("Connection", "Close")
        return conn
    }

    /** 带超时的 DNS 解析；null = 超时或错误（调用方快速 failover） */
    private fun dnsResolve(host: String, timeoutMs: Long): Array<InetAddress>? = try {
        DNS_POOL.submit(Callable<Array<InetAddress>> { InetAddress.getAllByName(host) }).get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "dns resolve fail/timeout: $host (${e.message})")
        null
    }

    private fun downloadBytes(url: String): ByteArray {
        val conn = openConn(url, readTimeoutMs = 45_000)
        val code = conn.responseCode
        check(code in 200..299) { httpFail(code, url) }
        return conn.inputStream.use { it.readBytes() }
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = openConn(url, readTimeoutMs = 120_000)
        val code = conn.responseCode
        check(code in 200..299) { httpFail(code, url) }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 shl 10)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
            }
        }
    }

    // ================= 流小工具 =================

    /** 限量输入流（ar 成员按 header size 精确截断，不关闭上游） */
    private class LimitInputStream(src: InputStream, private val limit: Long) : FilterInputStream(src) {
        private var remaining = limit
        override fun read(): Int {
            if (remaining <= 0) return -1
            val r = super.read()
            if (r >= 0) remaining--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (r > 0) remaining -= r
            return r
        }
    }

    private fun readBodyString(din: DataInputStream, size: Long): String {
        val buf = ByteArray(size.toInt())
        din.readFully(buf)
        skipPadAfter(din, size)
        // GNU longname body 以 NUL 结尾（tar 规范填充）；不去掉会带 \0 建路径，
        // 底层 canonicalize 抛 "Invalid file path"（python 等 74 个 L 头条目实测炸点）
        return String(buf, StandardCharsets.UTF_8).trimEnd('\u0000')
    }

    private fun parsePaxPath(content: String): String? =
        content.lineSequence().firstNotNullOfOrNull { rec ->
            val i = rec.indexOf(" path=")
            if (i >= 0) rec.substring(i + 6) else null
        }

    /** 跳过整个未读 body 及其 512 对齐 padding（目录/链接/设备等不落地条目） */
    private fun skipBody(din: DataInputStream, size: Long) {
        skipFully(din, size)
        skipPadAfter(din, size)
    }

    /** body 已精确读完时，仅跳过其 512 对齐 padding */
    private fun skipPadAfter(din: DataInputStream, size: Long) {
        var left = ((size + 511) / 512 * 512) - size
        while (left > 0) {
            val s = din.skip(left)
            if (s <= 0) throw EOFException("tar 流对齐跳读被截断")
            left -= s
        }
    }

    private fun skipFully(din: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = din.skip(left)
            if (s <= 0) throw EOFException("ar 流跳读被截断")
            left -= s
        }
    }

    private fun str(bh: ByteArray, off: Int, len: Int): String =
        String(bh, off, len, StandardCharsets.UTF_8).substringBefore('\u0000').trim()

    private fun octal(bh: ByteArray, off: Int, len: Int): Long {
        val s = String(bh, off, len, StandardCharsets.US_ASCII).trim('\u0000', ' ')
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun ByteArray.allZero(): Boolean = all { it == 0.toByte() }

    // ================= 引擎环境注入（供 EngineConfig 调用） =================

    companion object {
        private const val TAG = "ExtensionManager"
        // UA 必须是包管理器身份：TUNA/USTC/BFSU 的 WAF 会 403 自创 UA
        // （v1.2.2 的 Mozilla+自定义后缀实测被 TUNA 反爬拦截，apt 身份三源全 200）
        private const val HTTP_UA = "APT/2.12.10 (aarch64; android; termux)"
        private const val PREFS = "dsh_extensions"
        private const val MARKER = ".ext-version"
        private const val KEY_PREFIX = "activated_"
        private const val TERMUX_PREFIX = "/data/data/com.termux/files"

        /**
         * 正在安装中的扩展 id —— 进程级单例（companion）：
         * App UI 与 AI 通道（/ext/install）各自 new 的 ExtensionManager 实例
         * 必须共享同一份状态，否则 UI 看不见 AI 触发的安装、且会双装冲突。
         */
        private val installing = Collections.synchronizedSet(mutableSetOf<String>())

        /** DNS 预检线程池（daemon，防进程悬挂）；同一时刻只有一次解析在跑，单线程足够 */
        private val DNS_POOL = Executors.newSingleThreadExecutor { r ->
            Thread(r).apply { isDaemon = true; name = "dsh-dns-resolve" }
        }
        private const val TERMUX_DATA_PREFIX = "data/data/com.termux/files/"

        private fun keyActivated(id: String) = "$KEY_PREFIX$id"

        /** 已激活且目录健在的扩展根目录列表（EngineConfig.buildEnv 拼 PATH 用） */
        fun activeRoots(ctx: Context): List<File> {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val extRoot = File(EngineConfig.engineRoot(ctx), "extensions")
            return prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
                .filter { prefs.getBoolean(it, false) }
                .mapNotNull { key ->
                    val dir = File(extRoot, key.removePrefix(KEY_PREFIX))
                    dir.takeIf { File(it, MARKER).isFile }
                }
                .sortedBy { it.name }
        }
    }
}

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
import java.util.concurrent.locks.ReentrantLock
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

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
        val iconRes: String = "",
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

    // ============ 下载任务（进程级，独立于扩展中心 Activity 生命周期）============

    enum class TaskState { QUEUED, RUNNING, DONE, FAILED }

    /** 一个扩展下载任务的实时快照：UI 观察 [tasks] 流渲染进度 */
    data class ExtTask(
        val id: String,
        val name: String,
        val progress: Float,
        val stage: String,
        val state: TaskState,
        val error: String? = null,
    )

    /** 进程级 scope：任务不随扩展中心 Activity 的销毁/重建而取消（后台下载） */
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<Map<String, ExtTask>>(emptyMap())

    /** 扩展下载任务表（id → 快照）。UI 收集此流渲染进度；Activity 重建后自动恢复显示 */
    val tasks: StateFlow<Map<String, ExtTask>> = _tasks

    /** 并发上限：网络段允许 3 个扩展同时下载；解包/发布按扩展分锁（v1.2.41 起可并行） */
    private val dlSemaphore = Semaphore(3)

    private fun updateTask(id: String, f: (ExtTask) -> ExtTask) {
        _tasks.update { cur -> cur[id]?.let { cur + (id to f(it)) } ?: cur }
    }

    /**
     * 入队一个扩展安装任务并立即返回。任务在进程级 scope 上执行：
     * 网络下载段最多 3 个并发（[dlSemaphore]）；解包/发布段按扩展 id 分锁（不同扩展可并行，
     * 见 [INSTALL_LOCKS]，历史上"并发解包互删 tmp"的根因已随清理逻辑收窄而消除）。
     * 进度经 [tasks] 流广播，UI 收集渲染。
     * 退出扩展中心、销毁 Activity 均不影响任务执行（后台下载）。
     */
    fun enqueue(ext: Extension) {
        check(!RuntimeInstaller.installing) { "runtime 正在装配，请等引擎启动完成后再试" }
        if (installing.contains(ext.id)) return
        _tasks.update { cur ->
            val existing = cur[ext.id]
            if (existing != null &&
                (existing.state == TaskState.QUEUED || existing.state == TaskState.RUNNING)
            ) cur // 已在队列/执行中，去重
            else cur + (ext.id to ExtTask(ext.id, ext.name, 0f, "排队中…", TaskState.QUEUED))
        }
        taskScope.launch { installTask(ext) }
    }

    /**
     * 执行一个完整的扩展安装任务（挂起直至完成/失败）。
     * UI 入口用 [enqueue]（异步 + [tasks] 状态流），AI 通道可挂起直调。
     */
    suspend fun installTask(ext: Extension, report: (Float?, String) -> Unit = { _, _ -> }) {
        if (installing.contains(ext.id)) {
            throw IllegalStateException("扩展 ${ext.id} 正在安装中")
        }
        installing.add(ext.id)
        try {
            updateTask(ext.id) { it.copy(state = TaskState.RUNNING, stage = "准备…") }
            fun rep(p: Float?, s: String) {
                report(p, s)
                updateTask(ext.id) { it.copy(progress = p ?: it.progress, stage = s.ifEmpty { it.stage }) }
            }
            // 阶段 1：网络下载（可并发，每扩展独立 cacheDir）—— 0~0.95
            val permit = dlSemaphore.acquire()
            val downloaded = try {
                try {
                    downloadPhase(ext) { p, s -> rep(p, s) }
                } catch (e: Exception) {
                    // 自愈重试（v1.2.36 实测缺口）：镜像索引与 .deb 存在同步窗口，
                    // CDN 边缘可能先给出新版索引（文件名带新版本）而 .deb 尚未同步 →
                    // 四个镜像全 404（实测 ca-certificates_1:2026.08.13 全 404）。
                    // 换一个镜像作索引源重来一次即可拿到一致的那份索引。
                    Log.w(TAG, "download ${ext.id} 失败，换索引源重试一次: ${e.message}")
                    rep(0f, "重试中（换源）…")
                    downloadPhase(ext, rotatePreferred = true) { p, s -> rep(p, s) }
                }
            } finally {
                dlSemaphore.release()
            }
            // 阶段 2：解包/发布（按扩展 id 加锁：同扩展互斥、不同扩展可并行；见 INSTALL_LOCKS）
            val installT0 = System.currentTimeMillis()
            val lock = installLockFor(ext.id)
            lock.lock()
            try {
                installPhase(ext, downloaded) { p, s -> rep(p, s) }
            } finally {
                lock.unlock()
                Log.i(TAG, "install ${ext.id}: 解包+发布耗时 ${System.currentTimeMillis() - installT0}ms")
            }
            updateTask(ext.id) { it.copy(state = TaskState.DONE, progress = 1f, stage = "完成") }
            // 30s 后从任务表移除：防 UI 重建时把历史完成重放成 Toast，也防 map 无限增长
            taskScope.launch {
                kotlinx.coroutines.delay(30_000)
                _tasks.update { it - ext.id }
            }
        } catch (e: CancellationException) {
            updateTask(ext.id) { it.copy(state = TaskState.FAILED, stage = "已取消") }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "install task ${ext.id}: ${e.message}")
            updateTask(ext.id) {
                it.copy(state = TaskState.FAILED, stage = e.message ?: "安装失败", error = e.message)
            }
            // 失败任务同样 30s 后移除（防重放/防增长）
            taskScope.launch {
                kotlinx.coroutines.delay(30_000)
                _tasks.update { it - ext.id }
            }
        } finally {
            installing.remove(ext.id)
        }
    }

    // ================= 存储 =================

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val extRoot get() = File(EngineConfig.engineRoot(ctx), "extensions")

    init {
        // 启动清理：上次会话中断遗留的 .tmp-install（ANR/崩溃时发布中断的孤儿目录，
        // 实测 ffmpeg 遗留 136MB）。安装按扩展分锁，进程启动时刻
        // 无并发安装，此时清理安全。
        runCatching {
            extRoot.listFiles()
                ?.filter { it.isDirectory && it.name.endsWith(".tmp-install") }
                ?.forEach { it.deleteRecursively() }
        }
        // 历史扩展补链迁移（v1.2.32）：旧版本（≤v1.2.30）安装的扩展没有 sh/env
        // 解释器补链（v1.2.31 新增），对已装扩展补齐 —— 否则 166 个 shebang 脚本
        // 仍报 bad interpreter（Agent 实测）。
        // v1.2.35：同时做声明 bins 核对 + 跨扩展解释器 shebang 升级（PATH 依赖 → 绝对直指）
        runCatching {
            val engine = EngineConfig.engineRoot(ctx)
            val binsOf = loadCatalog().associate { it.id to it.bins }
            extRoot.listFiles()
                ?.filter { it.isDirectory && File(it, MARKER).isFile }
                ?.forEach { dir ->
                    ensureShimInterpreters(dir, dir, engine, otherExtBins(dir))
                    // 声明的 bins 全缺 = 负载没落地（历史残缺安装），启动日志留证据
                    val declared = binsOf[dir.name].orEmpty()
                    val missing = declared.filterNot { File(dir, "bin/$it").exists() }
                    if (missing.isNotEmpty()) {
                        Log.w(TAG, "ext ${dir.name}: bins 缺失 $missing（重装该扩展可修复）")
                    }
                }
        }
    }

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
                iconRes = o.optString("iconRes", ""),
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
     * @param report 进度（null = 仅更新阶段文案；0f = indeterminate）与阶段文案。
     *               failover 期间持续上屏，给用户"活着"的证据
     */
    /** 下载阶段产物：解包所需全部 .deb（cacheDir 内）+ 主包版本 + cacheDir 句柄 */
    private data class DownloadedDebs(
        val debs: List<File>,
        val mainVersion: String,
        val cacheDir: File,
    )

    /** 阶段 1：仓库索引 → 依赖闭包 → 逐包 .deb 下载（SHA256 强校验）。
     *  纯网络段，可多扩展并发（每扩展独立 cacheDir，互不干扰）；
     *  解包/发布在 [installPhase]。
     *  @param rotatePreferred 索引源改用 mirrors[1]（自愈重试：规避 CDN 陈旧索引） */
    private fun downloadPhase(
        ext: Extension, rotatePreferred: Boolean = false, report: (Float?, String) -> Unit,
    ): DownloadedDebs {
        val cacheDir = File(ctx.cacheDir, "ext-${ext.id}").apply { mkdirs() }
        val allMirrors = mirrors().ifEmpty {
            listOf("https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main")
        }
        report(0f, "解析依赖闭包…")   // 0 = indeterminate：解析/索引阶段无确定字节量，UI 转旋转动画
        val preferred = if (rotatePreferred) allMirrors.getOrElse(1) { allMirrors.first() } else allMirrors.first()
        val index = fetchPackagesIndex(preferred) { s -> report(null, s) }
        val closure = resolveClosure(ext.packages, index)
        val mainPkg = index[ext.packages.first()]
            ?: throw IllegalStateException("包 ${ext.packages.first()} 不在仓库索引中")
        Log.i(TAG, "download ${ext.id}: ${closure.size} pkgs, ${closure.sumOf { it.size } / 1048576}MB index=${preferred}")
        // 包名清单：诊断"载荷错位"类问题（如 ffmpeg 目录出现 python3.14 —— Agent 实测）
        // 时用来核对闭包内容 —— 若是闭包污染此处直接可见
        Log.i(TAG, "download ${ext.id} closure: " + closure.joinToString(",") { it.name })

        // 逐包下载 + SHA256 强校验（进度按字节累计，占 0~0.95）
        val totalBytes = closure.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        val debs = mutableListOf<File>()
        closure.forEachIndexed { idx, p ->
            val f = File(cacheDir, "${p.name}_${p.version}.deb")
            val label = "${ext.name} ${idx + 1}/${closure.size} 包"
            downloadDebWithFailover(allMirrors, p.filename, f, label, { s -> report(null, s) }) { frac ->
                report(((done + p.size * frac).toDouble() / totalBytes).toFloat() * 0.95f, "")
            }
            check(p.sha256.isEmpty() || RuntimeInstaller.sha256(f) == p.sha256.lowercase()) {
                "SHA-256 校验失败: ${p.name}（镜像源数据异常？）"
            }
            done += p.size
            debs.add(f)
        }
        return DownloadedDebs(debs, mainPkg.version, cacheDir)
    }

    /** 阶段 2：解包 → 拍平 usr/ → 可执行位 → 版本标记 → rename 原子发布 → 链接落地。
     *  只操作本扩展自己的 tmp/final 目录；调用方（installTask）需持该扩展的分锁。 */
    private fun installPhase(ext: Extension, dl: DownloadedDebs, report: (Float?, String) -> Unit) {
        val finalDir = dirOf(ext.id)
        val tmpDir = File(extRoot, "${ext.id}.tmp-install")
        try {
            extRoot.mkdirs()
            // 清场：半截安装（目录无 marker）与重装（目录完整）统一删除旧目录——
            // 否则 tmp→finalDir 的 rename 对非空目标必失败；重装会清扩展目录（含用户手装内容）。
            // ⚠️ 只清自己的 tmp：历史上"清全部 .tmp-install"在并发安装时会删掉
            // 别的线程正在解包的目录（perl 实体被清光实锤）——串行锁已根治并发
            // v1.2.39：删除不可信（deleteRecursively 会静默跳过删不掉的节点）——
            // 残留（典型：Root 模式引擎以 root 属主写进扩展目录的文件）会让随后的
            // rename 撞 ENOTEMPTY，用户只看到笼统的"扩展目录发布失败"。此处核验 +
            // Root 模式自动强清 + 明确的残留清单（写进日志与异常文案）
            val staleDirs = purgeDir(finalDir) + purgeDir(tmpDir)
            if (staleDirs.isNotEmpty()) {
                Log.w(TAG, "install ${ext.id}: 残留无法删除 ${staleDirs}")
            }

            // 解包（symlink/硬链接延后到 rename 之后创建——避免绝对链接指向临时目录）
            report(0.95f, "解包安装…")
            val pendingLinks = mutableListOf<LinkJob>()
            var totalEntries = 0
            dl.debs.forEach { deb ->
                val n = extractDeb(deb, tmpDir, pendingLinks)
                totalEntries += n
                // 逐包条目数入库日志：残缺安装（负载半套）时一眼可见是哪个包对不上
                Log.i(TAG, "extract ${ext.id}: ${deb.name} -> $n entries")
            }
            Log.i(TAG, "extract ${ext.id}: ${dl.debs.size} pkgs, $totalEntries entries total")
            report(0.96f, "")

            // 拍平 usr/ 布局 → 可执行位 → 版本标记 → 原子发布
            flattenUsrLayout(tmpDir)
            restoreExecBits(tmpDir)
            rewriteTermuxShebangs(tmpDir, finalDir)
            rewriteTermuxPaths(tmpDir, finalDir)
            // 传 tmpDir：文件此刻还在临时目录，bin 存在性检查必须看 tmpDir；
            // shebang 文本里的路径由 finalDir 生成（rename 后才是真实路径）
            ensureShimInterpreters(tmpDir, finalDir, EngineConfig.engineRoot(ctx), otherExtBins(finalDir))
            File(tmpDir, MARKER).writeText(dl.mainVersion)
            if (!tmpDir.renameTo(finalDir)) {
                // 失败原因二选一：源缺失（解包被并发清场等）或目标残留非空（删不掉的文件）
                val srcOk = tmpDir.isDirectory
                val leftover = purgeDir(finalDir)
                if (!srcOk && leftover.isEmpty()) {
                    throw IllegalStateException("扩展目录发布失败：临时目录在解包后被清掉（${tmpDir.name}）")
                }
                // 兜底（放在抛错之前！）：残留清不掉（通常是 root 属主目录）时改「合并发布」——
                // 把新内容逐项搬进旧目录，能覆盖就覆盖、同名目录递归合并；扩展整体照常可用，
                // 只有真正写不进去的条目会列为 failed。⚠️ v1.2.39 曾把这条分支写成"先抛错"，
                // 导致兜底永不执行（v1.2.40 修正顺序）。
                val failed = mergeMove(tmpDir, finalDir)
                if (failed.isNotEmpty() || !File(finalDir, MARKER).isFile) {
                    // ⚠️ v1.2.40 曾把这里做成"部分落地也算成功"→ 用户实测 19/19 green 但
                    // 主程序缺失（残留占位处写入被拒，新 payload 没落地、旧文件又没了）
                    // → 级联损坏更多扩展。v1.2.42 起：发布不完整一律判失败并**删掉版本标记**，
                    // 宁可显式报错，也不留"绿了但不能用"的假状态。
                    runCatching { File(finalDir, MARKER).delete() }
                    throw IllegalStateException(
                        "扩展目录发布不完整：${failed.size} 项新内容未能落地" +
                            (if (leftover.isNotEmpty()) "，且有 ${leftover.size} 项残留无法删除（${leftover.take(3).joinToString("、")}${if (leftover.size > 3) "…" else ""}）" else "") +
                            "。多为 Root 模式引擎写入的 root 属主内容占位；给 su 授权后重试（应用会自动强清），" +
                            "或用支持 Root 的文件管理器删掉该扩展目录后重试"
                    )
                }
            }
            report(0.99f, "")

            createLinks(finalDir, pendingLinks)
            report(1f, "")
            if (ext.id == "rust") ensureRustUnwindStub(finalDir)
            if (ext.id == "vim") patchVimLinks(finalDir)
            if (ext.id == "lua") patchLuaLinks(finalDir)
            // 发布后主程序存在性校验（v1.2.42 收严，Agent 实测"19/19 green 但主程序没落地"）：
            // 声明 bins **全部**缺失 = 依赖装上了、主程序没落地 → 判安装失败（删标记），
            // 避免假绿；部分缺失仅告警（个别包声明与产物不完全一致）。
            // 放在 createLinks 之后：deb 自带的软链（python3 → python3.14 等）此时已落地。
            val missingBins = ext.bins.filterNot { File(finalDir, "bin/$it").exists() }
            if (ext.bins.isNotEmpty() && missingBins.size == ext.bins.size) {
                runCatching { File(finalDir, MARKER).delete() }
                throw IllegalStateException(
                    "扩展安装校验失败：${ext.name} 声明的可执行文件（${ext.bins.joinToString("、")}）均未落地，" +
                        "目录可能被权限残留占用。给 su 授权后重装（应用会自动强清），或用支持 Root 的文件管理器删除该扩展目录"
                )
            } else if (missingBins.isNotEmpty()) {
                Log.w(TAG, "extension ${ext.id}: bins 缺失 $missingBins")
            }
            Log.i(TAG, "extension ${ext.id} installed v${dl.mainVersion} (${dl.debs.size} pkgs, $totalEntries entries)")
        } finally {
            dl.cacheDir.deleteRecursively()
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

    /** ar 归档定位 data.tar.* 成员并解 tar（Termux .deb 为 data.tar.xz）；返回解出的条目数 */
    private fun extractDeb(deb: File, target: File, pending: MutableList<LinkJob>): Int {
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
                    return untar(tar, target, pending)
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
     *
     * 完整性：只有读到全零结束块才算正常收尾；半截头/无结束块一律抛错 ——
     * 历史实现把 EOF 当"正常结束"静默 break，deb 不完整时会留下半套负载
     * 且安装仍标成功（Agent 实测"主包缺失、依赖在位"一类残缺安装的温床）。
     * @return 实际解出的条目数（调用方记日志）
     */
    private fun untar(input: InputStream, target: File, pending: MutableList<LinkJob>): Int {
        var entries = 0
        DataInputStream(BufferedInputStream(input)).use { din ->
            val bh = ByteArray(512)
            var gnuLongName: String? = null
            var paxPath: String? = null
            while (true) {
                // 手工填满 512B 头：区分"流尾"（0 字节）与"流中途截断"（1..511 字节），
                // readFully 的 EOFException 不携带已读字节数，无法区分二者
                var off = 0
                while (off < 512) {
                    val r = din.read(bh, off, 512 - off)
                    if (r < 0) break
                    off += r
                }
                if (off == 0) {
                    // dpkg-deb 产物必有零块结束标记；无标记即流尾 = 文件不完整
                    throw EOFException("tar 流无结束块即中断（已解 $entries 条）——deb 可能不完整")
                }
                if (off < 512) throw EOFException("tar 头被截断（$off/512，已解 $entries 条）")
                if (bh.allZero()) break   // 结束块
                entries++
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
                        // 按 tar 头 mode（offset 100, 8B octal）重建权限——Termux 包内
                        // 755/644 语义真实，不保留则全部 664 → go pkg/tool 等大量
                        // 可执行缺 +x（171 个 Permission denied 实测清单的根因）
                        val mode = octal(bh, 100, 8)
                        if (mode > 0) {
                            // 0o111=73(rwx) 0o444=292(r) 0o222=146(w)——Kotlin 1.9 无 0o 字面量
                            out.setExecutable((mode and 73L) != 0L, false)
                            out.setReadable((mode and 292L) != 0L, false)
                            out.setWritable((mode and 146L) != 0L, false)
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
        return entries
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
    /**
     * 强清目录并核验：返回**未能删除**的条目（相对路径，最多 8 条，便于报错/日志）。
     * 刻意不用 File.deleteRecursively 的返回值（它对删不掉的节点静默跳过，调用方
     * 无从得知 → 历史上就因此把"rename 失败"报成了笼统的发布失败）。
     * Root 模式（DSH_ANDROID_PRIV_MODE=ROOT）下额外用 su 强清一次：root 属主残留
     * 普通应用身份删不掉，但 Root 模式已取得 su，可清理。
     */
    private fun purgeDir(dir: File): List<String> {
        if (!dir.exists()) return emptyList()
        runCatching { dir.deleteRecursively() }
        if (!dir.exists()) return emptyList()
        // 正常删除失败（典型：Root 模式引擎留下的 root 属主目录/文件）→ 用 su 强清。
        // v1.2.42：不再限于 Root 模式——这是**用户主动安装动作**的一部分，且只清理该扩展
        // 自己的目录；su 管理器（Magisk 等）仍会逐次授权把关，AI 侧的提权闸门不受影响。
        // 没有这一步，残留会让发布静默残缺（Agent 实测：19/19 green 但主程序没落地）。
        runCatching {
            val su = Privilege.findSu() ?: return@runCatching
            ProcessBuilder(su, "-c", "rm -rf " + shellQuotePath(dir.absolutePath))
                .start().waitFor()
        }
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().take(9).map { it.relativeTo(dir).path.ifEmpty { "." } }.toList()
    }

    /**
     * 合并发布：把 src 的内容逐项搬进已存在的 dst（能覆盖覆盖、同名目录递归合并）。
     * 返回**未能落地**的相对路径（≤ 若干条；调用方记日志）。
     * 场景：旧目录里有 root 属主残留，应用身份删不掉 → rename 必失败；改为逐项合并，
     * 让扩展先可用，而不是整次安装失败。
     */
    private fun mergeMove(src: File, dst: File): List<String> {
        val failed = mutableListOf<String>()
        fun recurse(s: File, d: File) {
            d.mkdirs()
            s.listFiles()?.forEach { child ->
                val target = File(d, child.name)
                if (target.isDirectory && child.isDirectory) {
                    recurse(child, target)
                    child.delete()
                    return@forEach
                }
                if (target.exists()) target.deleteRecursively()   // 尽力清掉旧同名项
                if (child.renameTo(target)) return@forEach
                val copied = runCatching { child.copyRecursively(target, overwrite = true) }.isSuccess
                if (copied && target.exists() && (target.length() == child.length() || child.isDirectory)) {
                    child.deleteRecursively()
                } else {
                    failed.add(child.relativeTo(src).path)
                }
            }
        }
        runCatching { recurse(src, dst) }
        runCatching { src.deleteRecursively() }
        return failed
    }

    /** 供 su -c 使用的单引号路径转义 */
    private fun shellQuotePath(p: String): String = "'" + p.replace("'", "'\''") + "'"

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

    /**
     * Rust aarch64-android target 的动态链接段带 -lunwind，但 Termux rust 的 rustlib
     * 不含 libunwind（ld.lld: unable to find library 实测）。Android 的 unwind 符号
     * 由 bionic/libc++abi 运行时提供，放一个空 ar 归档满足链接器查找即可（社区标准做法）。
     */
    private fun ensureRustUnwindStub(finalDir: File) {
        val rustlib = File(finalDir, "lib/rustlib/aarch64-linux-android/lib")
        if (!rustlib.isDirectory) return
        val stub = File(rustlib, "libunwind.a")
        if (!stub.isFile) {
            stub.writeBytes("!<arch>\n".toByteArray(StandardCharsets.US_ASCII))
            stub.setReadable(true, false)
        }
    }

    /**
     * 【社区审计 P0-1 修复】Termux 包内容物的编译期路径重定位：
     * Termux 包把 PREFIX=/data/data/com.termux/files/usr 编译/写死进内容
     * （sshd 21 处、git 12 处、*-config 脚本 119 个）。设备装着 Termux 时该路径
     * 恰好有效会掩盖缺陷，干净设备上集中爆雷。
     * 修复：遍历扩展目录全部文本文件，把该前缀替换为扩展根（幂等：替换后
     * 不再含 termux 前缀）。ELF 二进制（首 4 字节 \x7fELF）与超 256KB 文件跳过
     * ——ELF 内嵌路径等长替换在本项目不可行（我们路径更长），深度依赖
     * （sshd hostkeys 等）为已知限制，长期由 PRoot 活环境系统性解决。
     */
    private fun rewriteTermuxPaths(root: File, finalDir: File) {
        val termuxPrefix = "$TERMUX_PREFIX/usr"
        val extRoot = finalDir.absolutePath
        root.walkTopDown()
            .filter { it.isFile && it.length() in 1..(256 shl 10) }
            .forEach { f ->
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
                if (bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte()) return@forEach
                val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
                if (!text.contains(termuxPrefix)) return@forEach
                runCatching {
                    f.writeText(text.replace(termuxPrefix, extRoot), StandardCharsets.UTF_8)
                }
            }
    }

    private fun restoreExecBits(root: File) {
        listOf("bin", "usr/bin", "libexec").forEach { rel ->
            File(root, rel).takeIf { it.isDirectory }?.listFiles()?.forEach {
                it.setExecutable(true, false)
                it.setReadable(true, false)
            }
        }
    }

    /**
     * Termux 包的 bin wrapper（pip/pydoc 等）shebang 硬编码 /data/data/com.termux/files/usr，
     * 拍平后指向本扩展根 —— 不重写则执行报 "No such file or directory"（pip 实测炸点）。
     * 只处理 bin/ 下小于 1MB 且首行为 #! 的文件；usr/ 前缀映射为扩展根（拍平后 bin 平级）。
     */
    private fun rewriteTermuxShebangs(root: File, finalDir: File) {
        val bin = File(root, "bin").takeIf { it.isDirectory } ?: return
        val badPrefix = "$TERMUX_PREFIX/usr/bin/"
        bin.listFiles()?.forEach { f ->
            if (!f.isFile || f.length() > 1 shl 20) return@forEach
            val first = try {
                f.inputStream().use { ins ->
                    val buf = ByteArray(256)
                    val n = ins.read(buf)
                    if (n <= 0 || buf[0] != '#'.code.toByte() || buf[1] != '!'.code.toByte()) return@forEach
                    String(buf, 0, n, StandardCharsets.UTF_8).lineSequence().first()
                }
            } catch (_: Exception) {
                return@forEach
            }
            if (!first.startsWith("#!$badPrefix")) return@forEach
            val fixed = first.replaceFirst("#!$badPrefix", "#!${finalDir.absolutePath}/bin/")
            val body = f.readText(StandardCharsets.UTF_8).substringAfter('\n')
            f.writeText("$fixed\n$body", StandardCharsets.UTF_8)
        }
    }

    /**
     * shebang 重写后的解释器补链（系统性 bug #1：183 个脚本指向不存在的解释器）：
     *  1) `<finalDir>/bin/sh` → `engine/bin/bash` 软链（重写目标 sh 在扩展包里不存在；
     *     engine/bin 也只有 bash，软链用相对路径 `../../bin/bash`，runtime 重装不悬空）
     *  2) `#!…/bin/env <prog>` 形式：扩展包无 env → 直接解析 prog 并重写为
     *     `#!<finalDir>/bin/<prog>`（prog 在包内 bin/ 时成立，python/node 等均如此）
     */
    /**
     * shebang 解释器补链（Agent 扩展实测四类根因，共 64 个文件）：
     *  A 缺 bin/bash（32：xzgrep 系列 / LLVM 交叉包装 / pa-info…）→ 软链 ../../../bin/bash
     *  B 缺 bin/env（24：scan-build 系列…）→ 软链 /system/bin/env（Android 无 /usr/bin）
     *  C 绝对 /usr/bin/env 的 shebang（6：glib-* 系列）从未被重写 → 归一为 <bin>/env
     *  D 缺跨扩展解释器（2：git-cvsserver / bdftogd 需 perl）→ 其他扩展 bin 里找同名软链
     *  另：env <prog> 若 prog 在包内 bin 可直接解析 → 直指（不依赖 PATH）
     *
     * @param root    文件当前所在根（安装期=tmpDir，启动修复期=已发布目录）。
     *                bin 存在性检查必须基于它，否则安装期（finalDir 尚未 rename 出来）
     *                整个函数被 `bin.isDirectory` 提前 return，shebang 修复推迟到重启才生效。
     * @param finalDir 发布后的真实根：shebang 文本写绝对路径时必须用它。
     */
    private fun ensureShimInterpreters(
        root: File, finalDir: File, engineRoot: File, otherBins: List<File> = emptyList(),
    ) {
        val bin = File(root, "bin")
        val pubBin = File(finalDir, "bin")
        if (!bin.isDirectory) return

        // A：sh/bash → engine/bin/bash（每次覆盖重建：坏链 exists()=false 但占位，
        //    必须先 deleteIfExists；基准为软链所在目录 <ext>/bin/，三级到 engineRoot）
        val engineBash = File(engineRoot, "bin/bash")
        if (engineBash.isFile) {
            listOf("sh", "bash").forEach { name ->
                val link = File(bin, name)
                runCatching {
                    java.nio.file.Files.deleteIfExists(link.toPath())
                    java.nio.file.Files.createSymbolicLink(
                        link.toPath(), java.nio.file.Paths.get("../../../bin/bash"),
                    )
                }
            }
        }

        // B：env → /system/bin/env（绝对路径；Android 没有 /usr/bin/env）
        val systemEnv = File("/system/bin/env")
        if (systemEnv.isFile) {
            val link = File(bin, "env")
            runCatching {
                java.nio.file.Files.deleteIfExists(link.toPath())
                java.nio.file.Files.createSymbolicLink(link.toPath(), systemEnv.toPath())
            }
        }

        // ⚠️ v1.2.42 移除「D：跨扩展解释器软链」。用户实测它制造**级联故障**：
        // 某扩展载荷受损后，别的扩展的修复期扫描会把链指过去（如 lua/bin/lua →
        // ../../android-tools/bin/lua），一个扩展坏了连坐一片；同时把大量无关名字
        // 灌进各扩展 bin，干扰用户与 Agent 的判断。
        // 真正的需求（`env <prog>` 脚本要在无 PATH 时也能跑）由下面 C 段的
        // 「跨扩展绝对解析」覆盖：直接写解释器绝对路径，可靠且无链式耦合。

        // C + env 直指：shebang 归一（termux 前缀 env / 绝对 /usr/bin/env / 本包 bin/env 三种形态）
        val envPrefixes = listOf("#!$TERMUX_PREFIX/usr/bin/env ", "#!/usr/bin/env ")
        val localEnvPrefix = "#!${pubBin.absolutePath}/env "
        bin.listFiles()?.forEach { f ->
            if (!f.isFile || f.length() > 1 shl 20) return@forEach
            val first = runCatching {
                f.inputStream().use { ins ->
                    val buf = ByteArray(256)
                    val n = ins.read(buf)
                    if (n <= 0 || buf[0] != '#'.code.toByte() || buf[1] != '!'.code.toByte()) return@forEach
                    String(buf, 0, n, StandardCharsets.UTF_8).lineSequence().first()
                }
            }.getOrNull() ?: return@forEach

            val prefix = envPrefixes.firstOrNull { first.startsWith(it) } ?: localEnvPrefix
            if (!first.startsWith(prefix)) return@forEach
            val prog = first.removePrefix(prefix).trim().substringBefore(' ')
            if (prog.isEmpty()) return@forEach
            val resolved = File(bin, prog)
            // 跨扩展绝对解析（v1.2.35）：glib-genmarshal 等 `env python3` 脚本原先写成
            // <glib>/bin/env python3 → 依赖 PATH 里有 python3（python 扩展激活才成立，
            // Agent 直调脚本时全部失败）。这里在本包 bin 找不到时扫其他已装扩展 bin，
            // 找到即写绝对路径，执行不再依赖 PATH 与被测进程环境。
            val otherHit = if (resolved.isFile) null else otherBins.firstNotNullOfOrNull {
                File(it, prog).takeIf { c -> c.exists() || java.nio.file.Files.isSymbolicLink(c.toPath()) }
            }
            val newShebang = when {
                resolved.isFile -> "#!${File(pubBin, prog).absolutePath}"   // 包内直指
                otherHit != null -> "#!${otherHit.absolutePath}"            // 跨扩展绝对直指
                else -> localEnvPrefix + prog                               // 走 <bin>/env（PATH 查找兜底）
            }
            if (newShebang != first) {
                val body = f.readText(StandardCharsets.UTF_8).substringAfter('\n')
                f.writeText("$newShebang\n$body", StandardCharsets.UTF_8)
            }
        }
    }

    /** 其他已装扩展的 bin（跨扩展解释器补链用；排除 exclude 自身） */
    private fun otherExtBins(exclude: File): List<File> =
        extRoot.listFiles()
            ?.filter {
                it.isDirectory && it.absolutePath != exclude.absolutePath && File(it, MARKER).isFile
            }
            ?.map { File(it, "bin") }
            ?.filter { it.isDirectory }
            ?: emptyList()

    /** vim：Termux 把本体装在 libexec/vim/vim 而 bin/vim 缺失（rview/rvim 悬空）→ 补 bin 软链 */
    private fun patchVimLinks(finalDir: File) {
        val bin = File(finalDir, "bin")
        val real = File(finalDir, "libexec/vim/vim")
        if (!real.isFile || !bin.isDirectory) return
        listOf("vim", "vi", "view", "ex", "rview", "rvim").forEach { name ->
            val f = File(bin, name)
            if (!f.exists()) runCatching {
                java.nio.file.Files.createSymbolicLink(
                    f.toPath(), java.nio.file.Paths.get("../libexec/vim/vim"),
                )
            }
        }
    }

    /** lua：Termux 只装 lua5.4/luac5.4 → 补 lua/luac 别名（用户敲 lua 即可用） */
    private fun patchLuaLinks(finalDir: File) {
        val bin = File(finalDir, "bin")
        listOf("lua" to "lua5.4", "luac" to "luac5.4").forEach { (alias, real) ->
            val src = File(bin, alias)
            if (!src.exists() && File(bin, real).isFile) runCatching {
                java.nio.file.Files.createSymbolicLink(src.toPath(), java.nio.file.Paths.get(real))
            }
        }
    }

    // ================= 激活 / 停用 / 卸载 =================

    /** 激活：并入引擎 PATH/LD_LIBRARY_PATH。**不自动重启引擎** —— 主线程等待
     *  引擎退出会阻塞 5s+ 导致 ANR（用户实测事故），改为置标记 + UI 警告，
     *  用户手动重启（healthy 后标记自动解除）。
     */
    fun activate(id: String) {
        check(markerOf(id).isFile) { "扩展未安装，无法激活" }
        prefs.edit().putBoolean(keyActivated(id), true).apply()
        pendingRestart.add(id)
    }

    /** 停用：从引擎环境中摘除（保留文件，可随时再激活）。同样需重启生效。 */
    fun deactivate(id: String) {
        prefs.edit().putBoolean(keyActivated(id), false).apply()
        pendingRestart.add(id)
    }

    /** 卸载：删除文件与全部状态标记（引擎内的 bin/lib 残留需重启清除） */
    fun remove(id: String) {
        dirOf(id).deleteRecursively()
        prefs.edit().remove(keyActivated(id)).apply()
        pendingRestart.add(id)
    }

    /** 已激活且目录健在的扩展数（UI 副标题计数用） */
    fun activeCount(): Int = activeRoots(ctx).size

    /** 该扩展是否等待「重启引擎生效」（激活/停用/卸载后置位，引擎 healthy 后清除） */
    fun needsRestart(id: String): Boolean = pendingRestart.contains(id)

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
        skipFully(din, ((size + 511) / 512 * 512) - size)
    }

    /**
     * 跳读 n 字节（read 循环实现，不经 InputStream.skip）。
     *
     * 本地复现（python deb 全 1042 条目解包）已证 skip 路径不产生静默截断，
     * 但 skip 的"单次可短返回/返回 0"语义依上层流实现而异（Buffered/XZ/Limit
     * 各层行为不同），read 循环在所有流上语义恒定：短读即继续、EOF 即抛错。
     */
    private fun skipFully(din: DataInputStream, n: Long) {
        var left = n
        val buf = ByteArray(8 shl 10)
        while (left > 0) {
            val r = din.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException("tar/ar 流跳读被截断")
            left -= r
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
         * 解包/发布锁（v1.2.41 起**按扩展 id 分锁**，不再全局串行）。
         *
         * 历史：全局串行是为兜住"并发解包互删 tmp 目录"的竞态——但那次事故的根因是清理逻辑
         * 删了**别人的** `.tmp-install`（已改为只清自己的），且临时目录/发布目录都按扩展 id
         * 隔离（`<id>.tmp-install` → `<id>`），不同扩展之间没有共享写入面；跨扩展只读
         * （otherExtBins 的 MARKER 过滤）在 rename 原子发布下是安全的。
         * 故改为分扩展互斥：同一扩展不会并发（installing 集合去重 + 本锁双保险），
         * 不同扩展解包/发布可并行（XZ 解压是 CPU 大头，多扩展同时装时明显更快）。
         * 并发度天然受下载段 Semaphore(3) 约束，不会无限膨胀。
         */
        private val INSTALL_LOCKS = java.util.concurrent.ConcurrentHashMap<String, ReentrantLock>()
        private fun installLockFor(id: String): ReentrantLock =
            INSTALL_LOCKS.computeIfAbsent(id) { ReentrantLock() }

        /**
         * 正在安装中的扩展 id —— 进程级单例（companion）：
         * App UI 与 AI 通道（/ext/install）各自 new 的 ExtensionManager 实例
         * 必须共享同一份状态，否则 UI 看不见 AI 触发的安装、且会双装冲突。
         */
        private val installing = Collections.synchronizedSet(mutableSetOf<String>())

        /** 激活/停用/卸载后待重启标记：引擎手动重启并 healthy 后由 Supervisor 清除 */
        private val pendingRestart = Collections.synchronizedSet(mutableSetOf<String>())

        /** 引擎 healthy 后由 Supervisor 调用：全部待重启标记解除（扩展自此生效） */
        fun clearPendingRestart() {
            pendingRestart.clear()
        }

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

package app.dsh.mobile.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.RepoConfig
import app.dsh.mobile.engine.RuntimeDownloadConfig
import app.dsh.mobile.engine.RuntimeInstaller
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 自动更新的编排层：取索引 → 校验 → 选包 → 产出 [Result]。
 *
 * 纯逻辑，不做下载、不弹窗、不改 UI —— 这些由 service / dialog / about page 各自负责。
 * 调用必须离开主线程（阻塞网络 IO）。
 */
object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"

    /** 索引地址里的文件名（三种发布渠道同名）。 */
    private const val INDEX_NAME = "update-index.json"

    sealed interface Result {
        /** 已是最新（或无更高 versionCode）。 */
        data class UpToDate(val latestVersionName: String, val latestVersionCode: Int) : Result

        /** 有更新，且已确定包。 */
        data class Available(val info: UpdateInfo, val viaProxy: Boolean) : Result

        /** 运行时变更 → 让用户二选一（F2）。 */
        data class Choice(
            val appOnly: UpdateInfo,
            val withRuntime: UpdateInfo,
            val installedRuntime: String,
            val remoteRuntime: String,
            val viaProxy: Boolean,
        ) : Result

        /** 拿不到可用信息（网络 / 验签 / 无匹配资产）。 */
        data class Failed(
            val reason: String,
            val failure: UpdateFailure = UpdateFailure.Unknown,
        ) : Result
    }

    /**
     * 索引候选地址（按顺序尝试）。
     *
     * - release 构建：`releases/latest/download/update-index.json`（正式渠道）
     * - debug 构建：先试 `edge` 标签（edge/preview 是 prerelease，
     *   `/releases/latest/` 永远解析到正式的 stable 版本，debug 用户永远看不到自己的渠道），
     *   再到 latest 兜底
     *
     * 与 `runtime-index.json` 不同的是：这里**不做代理优先**，见 [SignedIndex]。
     */
    fun indexCandidates(debuggable: Boolean): List<String> {
        val stable = RepoConfig.RELEASES_LATEST + "/" + INDEX_NAME
        if (!debuggable) return listOf(stable)
        return listOf(
            RepoConfig.releaseDownload("edge") + "/" + INDEX_NAME,
            RepoConfig.releaseDownload("preview") + "/" + INDEX_NAME,
            stable,
        ).distinct()
    }

    /** debug 构建可接受的索引渠道（stable 兜底：正式版也发 debug 包）。 */
    private fun allowedChannels(debuggable: Boolean): Set<String> =
        if (debuggable) setOf("edge", "preview", "stable") else setOf("stable")

    /**
     * 检查更新（阻塞）。
     *
     * @param force 手动检查时为 true（本函数不含节流，节流在 [AppUpdateScheduler]）
     */
    fun check(ctx: Context, force: Boolean = false): Result {
        if (force) Log.i(TAG, "manual check requested")
        val env = runtimeEnv(ctx)
        val allowProxy = AppUpdatePrefs.proxyFallback(ctx)
        val proxy = RuntimeDownloadConfig.proxy(ctx)
        val strategy = AppUpdatePrefs.packageStrategy(ctx)

        var lastError: String? = null
        var failure = UpdateFailure.Unknown
        for (url in indexCandidates(env.debuggable)) {
            val loaded = try {
                loadIndex(ctx, url, allowProxy, proxy) ?: continue
            } catch (e: SignedIndex.Unavailable) {
                lastError = e.message ?: "unavailable"
                failure = classify(e.cause ?: e)
                Log.w(TAG, "index unavailable: $url: $lastError (${failure.name})")
                continue
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                failure = classify(e)
                Log.w(TAG, "index error: $url: $lastError (${failure.name})")
                continue
            }

            val index = loaded.index
            if (index.schema != UpdateIndex.SUPPORTED_SCHEMA) {
                lastError = "unsupported index schema (${index.schema})"
                failure = UpdateFailure.Unknown
                continue
            }
            if (index.channel.isNotBlank() && index.channel !in allowedChannels(env.debuggable)) {
                // 防止 edge 用户被 stable 索引覆盖（反之亦然）
                lastError = "index channel mismatch (${index.channel})"
                failure = UpdateFailure.Channel
                continue
            }
            if (index.minAppVersionCode > 0 && env.versionCode < index.minAppVersionCode) {
                lastError = "app too old: needs versionCode >= ${index.minAppVersionCode}"
                failure = UpdateFailure.TooOld
                continue
            }

            val result = evaluate(index, env, strategy, loaded.viaProxy)
            noteResult(ctx, result)
            return result
        }

        val msg = lastError ?: "cannot fetch update info"
        AppUpdatePrefs.setLastCheckAt(ctx, System.currentTimeMillis())
        AppUpdatePrefs.setLastResult(ctx, msg)
        return Result.Failed(msg, failure)
    }

    /**
     * 异常 → 失败分类（按**类型**判定，不靠字符串）。
     *
     * 注意 [SignedIndex.Unavailable] 会把最后一次尝试的异常挂在 `cause` 上，
     * 所以要传 `e.cause ?: e`；三种渠道全失败时保留**最后一跳**的分类：
     * 直连超时 + 代理 404 → 报 404（更能说明"没发布索引"），反之亦然。
     */
    private fun classify(e: Throwable): UpdateFailure {
        for (t in generateSequence(e) { it.cause }.take(6)) {
            when {
                t is SecurityException -> return UpdateFailure.Signature
                t is java.io.IOException -> return UpdateFailure.Network
                t is IllegalStateException && t.message?.contains("HTTP 404") == true ->
                    return UpdateFailure.NoIndex
            }
        }
        return UpdateFailure.Unknown
    }

    /** 解析索引：命中 304 用缓存，否则写缓存（缓存内容均经过验签）。 */
    private class Loaded(val index: UpdateIndex, val viaProxy: Boolean)

    private fun loadIndex(
        ctx: Context,
        url: String,
        allowProxy: Boolean,
        proxy: String,
    ): Loaded? {
        val cache = Cache(ctx, url)
        val fetched = SignedIndex.fetch(ctx, url, allowProxy, proxy, cache.etag())
        val bytes: ByteArray
        var viaProxy = false
        when (fetched) {
            is SignedIndex.Result.NotModified -> {
                bytes = cache.readIndex() ?: run {
                    // 缓存被清了但服务端还回 304（极端情况）→ 下次强制重取
                    cache.clear()
                    return null
                }
            }
            is SignedIndex.Result.Ok -> {
                bytes = fetched.bytes
                viaProxy = fetched.viaProxy
                cache.write(bytes, fetched.signature, fetched.etag)
            }
        }
        return Loaded(UpdateIndex.parse(JSONObject(String(bytes, Charsets.UTF_8))), viaProxy)
    }

    /** 由索引 + 运行环境得出结果（不含网络）。 */
    fun evaluate(
        index: UpdateIndex,
        env: RuntimeEnv,
        strategy: String,
        viaProxy: Boolean = false,
    ): Result {
        if (index.versionCode <= env.versionCode) {
            return Result.UpToDate(index.versionName, index.versionCode)
        }
        return when (val pick = UpdateAssetPicker.pick(index, env, strategy)) {
            is PickResult.Single -> Result.Available(
                info(index, pick.asset, pick.mode, pick.runtimePrepare),
                viaProxy = viaProxy,
            )
            is PickResult.AskUser -> Result.Choice(
                appOnly = info(index, pick.appOnly, ModeNote.FULL_TO_NORUNTIME, false),
                withRuntime = info(index, pick.withRuntime, ModeNote.ADDED_RUNTIME, true),
                installedRuntime = env.installedRuntimeVersion ?: "—",
                remoteRuntime = pick.withRuntime.runtimeVersion ?: "—",
                viaProxy = viaProxy,
            )
            PickResult.NoAsset -> Result.Failed(
                "no package matches this device/build",
                UpdateFailure.NoAsset,
            )
        }
    }

    private fun info(index: UpdateIndex, asset: IndexAsset, mode: ModeNote, prepare: Boolean) =
        UpdateInfo(
            versionCode = index.versionCode,
            versionName = index.versionName.ifBlank { index.tag },
            tag = index.tag,
            notesUrl = index.notesUrl.ifBlank { RepoConfig.RELEASES_PAGE },
            notes = index.notes,
            asset = asset,
            mode = mode,
            runtimePrepare = prepare,
        )

    private fun noteResult(ctx: Context, result: Result) {
        AppUpdatePrefs.setLastCheckAt(ctx, System.currentTimeMillis())
        val text = when (result) {
            is Result.UpToDate -> "up to date (${result.latestVersionName})"
            is Result.Available -> "found ${result.info.versionName}"
            is Result.Choice -> "found ${result.withRuntime.versionName} (runtime choice)"
            is Result.Failed -> result.reason
        }
        AppUpdatePrefs.setLastResult(ctx, text)
    }

    // ── 运行环境 ──────────────────────────────────────────────────────────

    /** 读本机事实：ABI / 包形态 / 签名类型 / 版本 / 已装运行时。 */
    fun runtimeEnv(ctx: Context): RuntimeEnv {
        val pm = ctx.packageManager
        val pkgInfo = runCatching { pm.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val versionCode = when {
            pkgInfo == null -> 0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> pkgInfo.longVersionCode.toInt()
            else -> @Suppress("DEPRECATION") pkgInfo.versionCode
        }
        val versionName = pkgInfo?.versionName ?: "?"
        val debuggable = runCatching {
            (pm.getApplicationInfo(ctx.packageName, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false)

        val manifest = runCatching { RuntimeInstaller(ctx).readManifest() }.getOrNull()
        val abi = manifest?.abi?.takeIf { it.isNotBlank() }
            ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val packageMode = manifest?.packageName?.takeIf { it.isNotBlank() }
            ?: IndexAsset.FULL

        val installed = installedRuntime(ctx)
        return RuntimeEnv(
            abi = abi,
            packageMode = packageMode,
            debuggable = debuggable,
            versionCode = versionCode,
            versionName = versionName,
            installedRuntimeVersion = installed?.first,
            installedRuntimeBuiltAt = installed?.second ?: 0L,
        )
    }

    /** 已装运行时 (version, builtAt)；读 `.runtime.json`，老包回退 `.runtime-version`。 */
    fun installedRuntime(ctx: Context): Pair<String, Long>? = runCatching {
        val root = EngineConfig.engineRoot(ctx)
        val meta = File(root, ".runtime.json")
        if (meta.isFile) {
            val o = JSONObject(meta.readText())
            val version = o.optString("version")
            val builtAt = o.optLong("builtAt", 0L)
            if (version.isBlank() && builtAt <= 0) null else version to builtAt
        } else {
            File(root, ".runtime-version").takeIf { it.isFile }
                ?.readText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { it to 0L }
        }
    }.getOrNull()

    // ── 索引缓存 ──────────────────────────────────────────────────────────

    /**
     * 每个源 URL 一份缓存（`index-<hash>.json/.sig/.etag`）。
     *
     * 为什么按 URL 分：debug 构建会依次试 edge / preview / stable 三个索引，
     * 共用一个缓存文件会让 304 命中错误来源的内容。
     */
    private class Cache(private val ctx: Context, url: String) {
        private val base = File(AppUpdatePrefs.dir(ctx), "index-" + hash(url))
        private val jsonFile = File(base.parentFile, base.name + ".json")
        private val sigFile = File(base.parentFile, base.name + ".sig")
        private val etagFile = File(base.parentFile, base.name + ".etag")

        fun etag(): String? = runCatching {
            etagFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()

        fun readIndex(): ByteArray? = runCatching {
            if (!jsonFile.isFile) return null
            val bytes = jsonFile.readBytes()
            val sig = sigFile.takeIf { it.isFile }?.readBytes() ?: return null
            // 缓存内容也再验一次：缓存文件在 filesDir 里，理论上可被 root/备份工具改动。
            if (!SignedIndex.verify(ctx, bytes, sig)) {
                Log.w(TAG, "cached index signature invalid, dropping")
                clear()
                return null
            }
            bytes
        }.getOrNull()

        fun write(bytes: ByteArray, sig: ByteArray, etag: String?) {
            runCatching {
                base.parentFile?.mkdirs()
                jsonFile.writeBytes(bytes)
                sigFile.writeBytes(sig)
                if (etag.isNullOrBlank()) etagFile.delete() else etagFile.writeText(etag)
            }
        }

        fun clear() {
            runCatching {
                jsonFile.delete()
                sigFile.delete()
                etagFile.delete()
            }
        }

        private fun hash(s: String): String {
            val md = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            return md.joinToString("") { "%02x".format(it) }.take(12)
        }
    }
}

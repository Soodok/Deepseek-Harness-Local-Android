package app.dsh.mobile.update

import org.json.JSONObject

/**
 * 自动更新的数据模型。
 *
 * 设计原则：**选包只认结构化字段，不认文件名**。
 * 旧 `UpdateChecker` 用 `name.contains("arm64")` 取第一个匹配资产，导致
 * 两种事故（见 docs/AUTO_UPDATE_PLAN.md §2.2 D1）：
 *  - x86_64 设备拿到 arm64 包；
 *  - tag Release 里 `-debug-noruntime.apk` 排在前 → 正式用户被引到 debug 包。
 *
 * 现在资产元数据来自**验签过的** `update-index.json`，匹配三元组
 * `(variant, abi, package)`，文件名只进日志。
 */

/** `update-index.json` 里的一个安装包。 */
data class IndexAsset(
    val abi: String,
    /** `full`（内含 runtime.zip）| `noruntime`（首启/按需下载运行时） */
    val packageMode: String,
    /** `release`（可自更新）| `debug`（仅 edge/preview） */
    val variant: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val runtimeVersion: String?,
    /** 构建期 `.runtime.json` 的 builtAt；noruntime 为 0。见 F2 判据。 */
    val runtimeBuiltAt: Long,
) {
    val isFull: Boolean get() = packageMode == FULL
    val isNoRuntime: Boolean get() = packageMode == NORUNTIME

    companion object {
        const val FULL = "full"
        const val NORUNTIME = "noruntime"
        const val VARIANT_RELEASE = "release"
        const val VARIANT_DEBUG = "debug"

        fun fromJson(o: JSONObject): IndexAsset? {
            val name = o.optString("name")
            val sha = o.optString("sha256")
            // 没有 sha 的资产一律不认：宁可"没有更新"，也不能下没钉住的包。
            if (name.isBlank() || sha.isBlank()) return null
            return IndexAsset(
                abi = o.optString("abi"),
                packageMode = o.optString("package", FULL).ifBlank { FULL },
                variant = o.optString("variant", VARIANT_RELEASE).ifBlank { VARIANT_RELEASE },
                name = name,
                size = o.optLong("size", 0L),
                sha256 = sha.lowercase(),
                runtimeVersion = o.optStringOrNull("runtimeVersion"),
                runtimeBuiltAt = o.optLong("runtimeBuiltAt", 0L),
            )
        }

        /**
         * 读可选字符串，**把 JSON null 当成"没有"**。
         *
         * org.json 的 `optString(key)` 对 JSON null 返回字面量 `"null"`（不是空串，
         * 也不是 null）—— v1.4.37 的发布索引里 full 包的 `runtimeVersion` 就是 null，
         * 于是 App 拿到字符串 `"null"`，`isNullOrBlank()` 判不过，`runtimeChanged()`
         * 就认定"运行时变了"：full 用户每次更新都会被问要不要下 150MB 大包。
         * 这里统一归一化，别再让句柄级差异漏到业务判定里。
         */
        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        }
    }
}

/** 验签后的 `update-index.json`。 */
data class UpdateIndex(
    val schema: Int,
    val channel: String,
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val minAppVersionCode: Int,
    val notesUrl: String,
    val notes: String,
    val assets: List<IndexAsset>,
) {
    companion object {
        const val SUPPORTED_SCHEMA = 1

        fun parse(o: JSONObject): UpdateIndex {
            val latest = o.optJSONObject("latest")
                ?: throw IllegalArgumentException("update-index missing latest")
            val assets = ArrayList<IndexAsset>()
            o.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { IndexAsset.fromJson(it)?.let(assets::add) }
                }
            }
            return UpdateIndex(
                schema = o.optInt("schema", 0),
                channel = latest.optString("channel"),
                versionCode = latest.optInt("versionCode", 0),
                versionName = latest.optString("versionName"),
                tag = latest.optString("tag"),
                minAppVersionCode = latest.optInt("minAppVersionCode", 0),
                notesUrl = latest.optString("notesUrl"),
                notes = latest.optString("notes"),
                assets = assets,
            )
        }
    }
}

/** 本机运行环境事实（选包输入；全部来自运行时 manifest，不靠猜）。 */
data class RuntimeEnv(
    val abi: String,
    /** 当前安装包形态：`full` | `noruntime` */
    val packageMode: String,
    /** `ApplicationInfo.FLAG_DEBUGGABLE`：贴"实际装上来的是哪个"，比 BuildConfig 稳。 */
    val debuggable: Boolean,
    val versionCode: Int,
    val versionName: String,
    val installedRuntimeVersion: String?,
    val installedRuntimeBuiltAt: Long,
) {
    val variant: String
        get() = if (debuggable) IndexAsset.VARIANT_DEBUG else IndexAsset.VARIANT_RELEASE
}

/** 选包时的形态说明（决定弹窗文案要不要提示"首次启动慢"）。 */
enum class ModeNote {
    /** 形态未变，且不涉及运行时变更 */
    KEPT_RUNTIME,

    /** 本次会带上/更新运行时：首启需重解压，1–3 分钟 */
    ADDED_RUNTIME,

    /** full 用户改拿 noruntime：省 ~250MB，不重解压 */
    FULL_TO_NORUNTIME,
}

/** 一次"可选更新"的完整信息。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val notesUrl: String,
    val notes: String,
    val asset: IndexAsset,
    val mode: ModeNote,
    /** 装完首启是否需要重解压运行时（用于预报耗时）。 */
    val runtimePrepare: Boolean,
) {
    val sizeMb: Long
        get() = if (asset.size <= 0) 0L else (asset.size + (1L shl 20) - 1) / (1L shl 20)

    fun toJson(): JSONObject = JSONObject().apply {
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("tag", tag)
        put("notesUrl", notesUrl)
        put("notes", notes)
        put("mode", mode.name)
        put("runtimePrepare", runtimePrepare)
        put(
            "asset",
            JSONObject().apply {
                put("abi", asset.abi)
                put("package", asset.packageMode)
                put("variant", asset.variant)
                put("name", asset.name)
                put("size", asset.size)
                put("sha256", asset.sha256)
                put("runtimeVersion", asset.runtimeVersion ?: "")
                put("runtimeBuiltAt", asset.runtimeBuiltAt)
            },
        )
    }

    companion object {
        fun fromJson(raw: String): UpdateInfo? = runCatching {
            val o = JSONObject(raw)
            val a = IndexAsset.fromJson(o.getJSONObject("asset")) ?: return null
            UpdateInfo(
                versionCode = o.optInt("versionCode"),
                versionName = o.optString("versionName"),
                tag = o.optString("tag"),
                notesUrl = o.optString("notesUrl"),
                notes = o.optString("notes"),
                asset = a,
                mode = runCatching { ModeNote.valueOf(o.optString("mode")) }
                    .getOrDefault(ModeNote.KEPT_RUNTIME),
                runtimePrepare = o.optBoolean("runtimePrepare"),
            )
        }.getOrNull()
    }
}

/** 状态机阶段（§7.4）。 */
enum class Phase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    /** 运行时变更 → 等用户二选一（F2） */
    NEEDS_CHOICE,
    DOWNLOADING,
    VERIFYING,
    DOWNLOADED,
    /** 用户取消（`.part` 保留，可续传） */
    CANCELLED,
    INSTALLING,
    FAILED,
    /** 索引不可得（直连失败且关了降级 / 验签失败） */
    UNAVAILABLE,
    ;

    val busy: Boolean get() = this == CHECKING || this == DOWNLOADING || this == VERIFYING || this == INSTALLING
}

/**
 * 检查失败的**可分类原因**。
 *
 * 为什么要分类：`message` 里装的是异常原文（含完整 URL、HTTP 码），只适合进日志。
 * UI 必须给人话，所以这里保留一个与语言无关的分类，由 `UpdateFailureText` 翻成三种语言。
 * 分类靠异常**类型**而不是字符串匹配 —— 换文案/换 URL 时不会静默失效。
 */
enum class UpdateFailure {
    /** 还没失败（正常状态 / 用户取消）。 */
    None,

    /** 该渠道没有发布 `update-index.json`（HTTP 404 或资产缺失）。 */
    NoIndex,

    /** 连不上、超时、DNS、TLS 等网络层问题。 */
    Network,

    /** 索引验签失败（代理投毒或 key 不匹配）—— 属于安全事件，不能当普通错误吞掉。 */
    Signature,

    /** 索引里的渠道与当前构建不匹配（edge 客户端读到 stable 索引等）。 */
    Channel,

    /** 当前 App 版本低于索引要求的 `minAppVersionCode`。 */
    TooOld,

    /** 索引里没有匹配本机 ABI / 形态 / 变体的包。 */
    NoAsset,

    /** 其余（schema 不支持、解析异常等）。 */
    Unknown,
}

/** UI 订阅的单一状态。 */
data class UpdateState(
    val phase: Phase = Phase.IDLE,
    val info: UpdateInfo? = null,
    /** 二选一时的两个候选（appOnly 在前） */
    val choices: List<UpdateInfo> = emptyList(),
    val progress: Int = 0,
    /** 异常原文，**只用于日志/诊断**；UI 请用 [failure]。 */
    val message: String? = null,
    /** 失败分类；`None` 表示当前不是失败态。 */
    val failure: UpdateFailure = UpdateFailure.None,
    /** 当前下载线路是否经代理；null = 未知/未开始 */
    val viaProxy: Boolean? = null,
    val updatedAt: Long = 0L,
) {
    companion object {
        fun of(
            phase: Phase,
            message: String? = null,
            info: UpdateInfo? = null,
            failure: UpdateFailure = UpdateFailure.None,
        ): UpdateState =
            UpdateState(
                phase = phase,
                info = info,
                message = message,
                failure = failure,
                progress = if (phase == Phase.DOWNLOADED) 100 else 0,
                updatedAt = System.currentTimeMillis(),
            )
    }
}

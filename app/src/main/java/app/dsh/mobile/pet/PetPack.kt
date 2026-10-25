package app.dsh.mobile.pet

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PetSpec(
    val id: String,
    val name: String,
    val sizeDp: Int,
    val corner: String,
    val marginX: Int,
    val marginY: Int,
)

data class PetAnimCategory(
    val id: String,
    val weight: Int,
    val actions: List<String>,
    val noMirror: Boolean,
)

data class PetMoveAction(
    val slug: String,
    val minDist: Double,
    val maxDist: Double,
)

/**
 * 动画包配置。
 *
 * 兼容 dsh-pet-android 的 assets/pet-config.json：
 *   pets / names / durations / animations{idle,turn,drag,clicks,moves,categories,events.workStatus}
 *
 * 不依赖上游 dsh-pet 的完整 config.jsonc（那是 web 端专用 schema）。
 */
data class PetPackConfig(
    val spec: PetSpec,
    val names: Map<String, String>,
    val durations: Map<String, Long>,
    val idle: List<String>,
    val turn: List<String>,
    val drag: List<String>,
    val clicks: List<String>,
    val moves: List<PetMoveAction>,
    val categories: List<PetAnimCategory>,
    val workStatus: List<String>,
    val available: Set<String>,
    /** 包自带气泡字体文件名（相对 font/ 目录），没有字体时为 null。 */
    val font: String? = null,
) {
    fun nameOf(slug: String): String = names[slug] ?: slug

    fun durationOf(slug: String): Long =
        (durations[slug] ?: DEFAULT_DURATION_MS).coerceIn(500L, 30_000L)

    fun isAvailable(slug: String): Boolean = available.contains(slug)

    companion object {
        const val DEFAULT_DURATION_MS = 10_041L
        val DEFAULT_WORK_STATUS = listOf("a055", "a054", "a056", "a052", "a057", "a053")
    }
}

/** 设置页列表用的一条资源包摘要。 */
data class PetPackSummary(
    val id: String,
    val name: String,
    val animations: Int,
    val hasFont: Boolean,
    val bytes: Long,
    val current: Boolean,
)

object PetPack {
    private const val TAG = "DshPetPack"

    /**
     * 首次进入：把 v1 的单包 files/pet/pack 迁移到 files/pet/packs/<id>，
     * 并把当前包 id 补齐（旧重装/被删的情形自动落到剩下的第一个包）。
     */
    fun ensure(ctx: Context) {
        migrateLegacy(ctx)
        if (PetPrefs.packId(ctx) == null) {
            val first = PetPrefs.installedPackIds(ctx).firstOrNull()
            if (first != null) PetPrefs.setPackId(ctx, first)
        }
    }

    private fun migrateLegacy(ctx: Context) {
        val legacy = PetPrefs.legacyPackDir(ctx)
        if (!legacy.isDirectory) return
        val cfg = runCatching { parseConfig(File(legacy, "pet-config.json").readText()) }.getOrNull()
        val id = PetPrefs.safeId(cfg?.spec?.id?.takeIf { it != "main" } ?: "imported", "imported")
            .let { uniqueId(ctx, it) }
        val target = PetPrefs.packDir(ctx, id)
        PetPrefs.packsRoot(ctx).mkdirs()
        val moved = legacy.renameTo(target) || runCatching { legacy.copyRecursively(target, true) }.getOrDefault(false)
        if (moved) {
            runCatching { legacy.deleteRecursively() }
            PetPrefs.setPackId(ctx, id)
            Log.i(TAG, "migrated legacy pack to $id")
        } else {
            Log.e(TAG, "legacy pack migration failed -> ${target.absolutePath}")
        }
    }

    /** id 冲突时追加 -2 / -3 …，绝不覆盖用户已有的包。 */
    fun uniqueId(ctx: Context, desired: String): String {
        val base = PetPrefs.safeId(desired)
        if (!PetPrefs.packDir(ctx, base).exists()) return base
        var n = 2
        while (PetPrefs.packDir(ctx, "$base-$n").exists()) n++
        return "$base-$n"
    }

    /** 当前包的配置；没有可用包返回 null（调用方应据此不显示桌宠）。 */
    fun load(ctx: Context): PetPackConfig? {
        ensure(ctx)
        val id = PetPrefs.packId(ctx) ?: return null
        return load(ctx, id)
    }

    fun load(ctx: Context, id: String): PetPackConfig? = runCatching {
        val configFile = PetPrefs.packConfigFile(ctx, id)
        if (!configFile.isFile) return null
        val dir = PetPrefs.packWebpDir(ctx, id)
        val available = webpNames(dir)
        val parsed = parseConfig(configFile.readText()) ?: return null
        val enriched = parsed.copy(available = available)
        if (enriched.idle.none { it in available } && enriched.clicks.none { it in available }) {
            return null
        }
        enriched
    }.getOrNull()

    /** 已安装的全部包摘要（当前包标记 current）。 */
    fun listInstalled(ctx: Context): List<PetPackSummary> {
        ensure(ctx)
        val current = PetPrefs.packId(ctx)
        return PetPrefs.installedPackIds(ctx).map { id ->
            val cfg = load(ctx, id)
            val dir = PetPrefs.packDir(ctx, id)
            PetPackSummary(
                id = id,
                name = cfg?.spec?.name?.takeIf { it.isNotBlank() && it != "Pet" } ?: id,
                animations = webpNames(PetPrefs.packWebpDir(ctx, id)).size,
                hasFont = PetPrefs.packFontDir(ctx, id).listFiles()
                    ?.any { it.isFile && it.length() > 0 } ?: false,
                bytes = dirSize(dir),
                current = id == current,
            )
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /** 供导入器在落地前校验配置文本（不依赖已存在的目录）。 */
    fun parseConfig(text: String): PetPackConfig? = runCatching {
        val root = JSONObject(text)

        val pets = root.optJSONArray("pets")
        val petObj = pets?.optJSONObject(0)
        val spec = PetSpec(
            id = petObj?.optString("id", "main")?.takeIf { it.isNotBlank() } ?: "main",
            name = petObj?.optString("name", "Pet")?.takeIf { it.isNotBlank() } ?: "Pet",
            sizeDp = (petObj?.optInt("size", PetPrefs.DEFAULT_SIZE_DP) ?: PetPrefs.DEFAULT_SIZE_DP)
                .coerceIn(PetPrefs.MIN_SIZE_DP, PetPrefs.MAX_SIZE_DP),
            corner = petObj?.optString("corner", "top-right")?.takeIf { it.isNotBlank() } ?: "top-right",
            marginX = petObj?.optInt("marginX", 24) ?: 24,
            marginY = petObj?.optInt("marginY", 100) ?: 100,
        )

        val names = mutableMapOf<String, String>()
        root.optJSONObject("names")?.let { obj ->
            obj.keys().asSequence().forEach { key ->
                val v = obj.optString(key, "")
                if (v.isNotBlank()) names[key] = v
            }
        }

        val durations = mutableMapOf<String, Long>()
        root.optJSONObject("durations")?.let { obj ->
            obj.keys().asSequence().forEach { key ->
                val ms = obj.optLong(key, PetPackConfig.DEFAULT_DURATION_MS)
                if (ms > 0) durations[key] = ms
            }
        }

        val animations = root.optJSONObject("animations") ?: return null
        val idle = stringList(animations.optJSONArray("idle"))
        val click = stringList(animations.optJSONArray("clicks"))
        if (idle.isEmpty() || click.isEmpty()) return null

        val moves = mutableListOf<PetMoveAction>()
        animations.optJSONObject("moves")?.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slug = o.optString("name", o.optString("slug", "")).takeIf { it.isNotBlank() }
                    ?: continue
                moves += PetMoveAction(
                    slug = slug,
                    minDist = o.optDouble("minDist", 60.0),
                    maxDist = o.optDouble("maxDist", 240.0),
                )
            }
        }

        val categories = mutableListOf<PetAnimCategory>()
        animations.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "actions").takeIf { it.isNotBlank() } ?: "actions"
                val actions = stringList(o.optJSONArray("actions"))
                if (actions.isEmpty()) continue
                categories += PetAnimCategory(
                    id = id,
                    weight = o.optInt("weight", 10).coerceAtLeast(0),
                    actions = actions,
                    noMirror = o.optBoolean("noMirror", false),
                )
            }
        }

        val workStatus = resolveWorkStatus(
            animations.optJSONObject("events")?.optJSONArray("workStatus"),
            names,
        )

        PetPackConfig(
            spec = spec,
            names = names,
            durations = durations,
            idle = idle,
            turn = stringList(animations.optJSONArray("turn")),
            drag = stringList(animations.optJSONArray("drag")),
            clicks = click,
            moves = moves,
            categories = categories,
            workStatus = workStatus,
            available = emptySet(),
            font = root.optString("font", "").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /**
     * 导入前检查：至少 idle/clicks 各有一个可用的 webp。
     * 返回缺失的必需 slug 列表（空 = 通过）。
     */
    fun missingRequired(config: PetPackConfig, available: Set<String>): List<String> {
        val missing = mutableListOf<String>()
        if (config.idle.none { it in available }) missing += config.idle
        if (config.clicks.none { it in available }) missing += config.clicks
        return missing
    }

    private fun resolveWorkStatus(arr: JSONArray?, names: Map<String, String>): List<String> {
        val parsed = mutableListOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i, "").takeIf { it.isNotBlank() } ?: continue
                // 包内可能写段名（工作状态-思考冒泡），也可能写 slug（a055）。
                val slug = when {
                    names.containsKey(raw) -> raw
                    else -> names.entries.firstOrNull { it.value == raw }?.key ?: raw
                }
                parsed += slug
            }
        }
        val defaults = PetPackConfig.DEFAULT_WORK_STATUS
        return (0 until 6).map { i -> parsed.getOrNull(i) ?: defaults[i] }
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").takeIf { it.isNotBlank() } ?: continue
            out += s
        }
        return out
    }

    private fun webpNames(dir: File): Set<String> {
        if (!dir.isDirectory) return emptySet()
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".webp", ignoreCase = true) }
            ?.map { it.name.removeSuffix(".webp") }
            ?.toSet()
            ?: emptySet()
    }
}

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
    /** 动画开头原地不动的秒数（其间不位移，让"起步"动作先站稳）。 */
    val leadSec: Double = 2.0,
    /** 动画结尾原地不动的秒数（其间保持在终点，动作收尾）。 */
    val tailSec: Double = 2.0,
    /** 身体到屏幕边缘的安全距（px，不随 size 缩放）。 */
    val margin: Double = 20.0,
) {
    /** 真正产生位移的时间窗口（秒）；下限 0.1s 防除零，与桌面版 travelWindow 一致。 */
    fun travelWindow(durationSec: Double): Double = (durationSec - leadSec - tailSec).coerceAtLeast(0.1)
}

/**
 * 动画链顶层权重。归一化口径：idle + turn + move + Σ(category.weight) = 100，
 * 余下概率归入"随机动作"分支（见 [PetAnimPicker]）。
 */
data class PetAnimWeights(
    val idle: Int = 10,
    val turn: Int = 5,
    val move: Int = 5,
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
    /**
     * 工作状态六个槽位（0思考 1工作 2整理 3等待 4成功 5出错）。
     * 每个槽位是候选列表：多候选时轮流播避免长时间单段重复，单候选即常驻。
     */
    val workStatus: List<List<String>>,
    val available: Set<String>,
    val animationWeights: PetAnimWeights = PetAnimWeights(),
    /** 包自带气泡字体文件名（相对 font/ 目录），没有字体时为 null。 */
    val font: String? = null,
) {
    fun nameOf(slug: String): String = names[slug] ?: slug

    fun durationOf(slug: String): Long =
        (durations[slug] ?: DEFAULT_DURATION_MS).coerceIn(500L, 30_000L)

    fun isAvailable(slug: String): Boolean = available.contains(slug)

    /** 槽位内所有可用的候选；越界或无可用返回空。 */
    fun workSlotCandidates(index: Int): List<String> =
        workStatus.getOrNull(index)?.filter { isAvailable(it) } ?: emptyList()

    /** 槽位的代表性动画（给菜单预览等只需要一个的场合）。 */
    fun workSlotPrimary(index: Int): String? = workSlotCandidates(index).firstOrNull()

    fun isNoMirror(slug: String): Boolean =
        categories.any { it.noMirror && slug in it.actions }

    companion object {
        const val DEFAULT_DURATION_MS = 10_041L
        /** 缺素材时的兜底：六个槽位各一个 slug。 */
        private val DEFAULT_WORK_STATUS = listOf("a055", "a054", "a056", "a052", "a057", "a053")
        /** 包里没配 workStatus 时的兜底槽位（每槽一个候选）。 */
        val FALLBACK_WORK_STATUS: List<List<String>> = DEFAULT_WORK_STATUS.map { listOf(it) }
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

        // 移动参数：default 兜底 + 每项覆盖（与桌面版 Object.assign({}, default, params) 同口径）
        val movesDefault = animations.optJSONObject("moves")?.optJSONObject("default")
        val defMin = movesDefault?.optDouble("minDist", 60.0) ?: 60.0
        val defMax = movesDefault?.optDouble("maxDist", 240.0) ?: 240.0
        val defLead = movesDefault?.optDouble("leadSec", 2.0) ?: 2.0
        val defTail = movesDefault?.optDouble("tailSec", 2.0) ?: 2.0
        val defMargin = movesDefault?.optDouble("margin", 20.0) ?: 20.0

        val moves = mutableListOf<PetMoveAction>()
        animations.optJSONObject("moves")?.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slug = o.optString("name", o.optString("slug", "")).takeIf { it.isNotBlank() }
                    ?: continue
                moves += PetMoveAction(
                    slug = slug,
                    minDist = o.optDouble("minDist", defMin),
                    maxDist = o.optDouble("maxDist", defMax),
                    leadSec = o.optDouble("leadSec", defLead),
                    tailSec = o.optDouble("tailSec", defTail),
                    margin = o.optDouble("margin", defMargin),
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

        val weightsObj = root.optJSONObject("animationWeights")
        val weights = PetAnimWeights(
            idle = weightsObj?.optInt("idle", 10)?.coerceAtLeast(0) ?: 10,
            turn = weightsObj?.optInt("turn", 5)?.coerceAtLeast(0) ?: 5,
            move = weightsObj?.optInt("move", 5)?.coerceAtLeast(0) ?: 5,
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
            workStatus = resolveWorkStatus(
                animations.optJSONObject("events")?.optJSONArray("workStatus"),
                names,
            ),
            available = emptySet(),
            animationWeights = weights,
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

    /**
     * 解析 events.workStatus：六个槽位，每个槽位可以是单段（字符串）或多候选（数组）。
     * 包内可以写 slug（a055）也可以写段名（工作状态-思考冒泡），两种都要认。
     */
    private fun resolveWorkStatus(arr: JSONArray?, names: Map<String, String>): List<List<String>> {
        val parsed = mutableListOf<List<String>>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val slot = mutableListOf<String>()
                val raws: List<String> = when (val v = arr.opt(i)) {
                    is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it, "").takeIf { s -> s.isNotBlank() } }
                    else -> v?.toString()?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                }
                for (raw in raws) slot += resolveSlug(raw, names)
                if (slot.isEmpty()) slot += ""
                parsed += slot
            }
        }
        return (0 until 6).map { i ->
            val slot = parsed.getOrNull(i)?.filter { it.isNotBlank() } ?: emptyList()
            slot.ifEmpty { PetPackConfig.FALLBACK_WORK_STATUS[i] }
        }
    }

    /** 段名 → slug；本身就是 slug 或查不到时原样返回（交给 available 兜底过滤）。 */
    private fun resolveSlug(raw: String, names: Map<String, String>): String = when {
        names.containsKey(raw) -> raw
        else -> names.entries.firstOrNull { it.value == raw }?.key ?: raw
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

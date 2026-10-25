package app.dsh.mobile.pet

import android.content.Context
import app.dsh.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 桌宠台词库：内置 320 句（assets/pet/lines-default.txt），按情境分 11 个分类。
 *
 * 存储规则：
 *  - 内置台词编在 assets 的 lines-default.txt（`[分类id]` 分节，一行一句），永不修改；
 *  - 用户改动落在 filesDir/pet/lines/<cat>.txt，一行一句；
 *    某个分类存在覆盖文件就用覆盖，否则回落内置；
 *  - 导出/导入用 JSON（见 [exportJson] / [importJson]），改坏了可分类恢复默认或全部回内置。
 */
object PetLines {

    data class Category(val id: String, val titleRes: Int)

    /** 分类固定顺序，编辑器与触发器共用。 */
    val CATEGORIES: List<Category> = listOf(
        Category("boot", R.string.pet_lines_cat_boot),
        Category("tap", R.string.pet_lines_cat_tap),
        Category("tap_many", R.string.pet_lines_cat_tap_many),
        Category("fling", R.string.pet_lines_cat_fling),
        Category("edge", R.string.pet_lines_cat_edge),
        Category("morning", R.string.pet_lines_cat_morning),
        Category("evening", R.string.pet_lines_cat_evening),
        Category("late_night", R.string.pet_lines_cat_late_night),
        Category("idle_long", R.string.pet_lines_cat_idle_long),
        Category("random", R.string.pet_lines_cat_random),
        Category("working", R.string.pet_lines_cat_working),
    )

    private const val ASSET_DEFAULT = "pet/lines-default.txt"

    @Volatile private var cachedDefaults: Map<String, List<String>>? = null

    // ------------------------------------------------------------- 内置台词（assets）

    private fun defaults(ctx: Context): Map<String, List<String>> {
        cachedDefaults?.let { return it }
        val parsed = runCatching {
            val text = ctx.assets.open(ASSET_DEFAULT).bufferedReader().use { it.readText() }
            parseSections(text)
        }.getOrDefault(emptyMap())
        cachedDefaults = parsed
        return parsed
    }

    /** 解析 `[分类id]` 分节、一行一句的台词文本。 */
    fun parseSections(text: String): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        var current: String? = null
        val buf = mutableListOf<String>()
        fun flush() {
            val id = current
            if (id != null && buf.isNotEmpty()) out[id] = buf.toList()
            buf.clear()
        }
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                flush()
                current = line.substring(1, line.length - 1).trim()
            } else if (line.isNotEmpty()) {
                buf += line
            }
        }
        flush()
        return out
    }

    // ------------------------------------------------------------- 读取

    /** 目录：filesDir/pet/lines，分类 id 对应一个 txt，一行一句。 */
    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "pet/lines")

    private fun overrideFile(ctx: Context, id: String): File = File(dir(ctx), "$id.txt")

    /** 取某个分类的当前台词：有用户覆盖用覆盖，否则用内置。 */
    fun lines(ctx: Context, id: String): List<String> {
        val f = overrideFile(ctx, id)
        if (f.isFile) {
            val user = runCatching {
                f.readText().split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
            }.getOrNull()
            if (!user.isNullOrEmpty()) return user
        }
        return defaults(ctx)[id] ?: emptyList()
    }

    fun defaultLines(ctx: Context, id: String): List<String> = defaults(ctx)[id] ?: emptyList()

    fun totalCount(ctx: Context): Int =
        CATEGORIES.sumOf { lines(ctx, it.id).size }

    /** 保存某个分类的用户台词（空列表 = 删除覆盖，恢复内置）。 */
    fun save(ctx: Context, id: String, lines: List<String>) {
        val cleaned = lines.map { it.trimEnd() }.filter { it.isNotBlank() }
        val dir = dir(ctx)
        if (cleaned.isEmpty()) {
            overrideFile(ctx, id).delete()
            return
        }
        dir.mkdirs()
        overrideFile(ctx, id).writeText(cleaned.joinToString("\n", postfix = "\n"))
    }

    fun resetCategory(ctx: Context, id: String): Boolean = overrideFile(ctx, id).delete()

    fun resetAll(ctx: Context) {
        runCatching { dir(ctx).deleteRecursively() }
    }

    /** 是否有任一分类被用户改过。 */
    fun hasOverrides(ctx: Context): Boolean =
        dir(ctx).listFiles()?.any { it.isFile && it.extension == "txt" } == true

    // ------------------------------------------------------------- 导入导出

    /** 导出成 JSON：{"version":1,"categories":{"boot":["...","..."]}}。 */
    fun exportJson(ctx: Context): String {
        val root = JSONObject()
        root.put("version", 1)
        val cats = JSONObject()
        for (c in CATEGORIES) {
            val arr = JSONArray()
            for (line in lines(ctx, c.id)) arr.put(line)
            cats.put(c.id, arr)
        }
        root.put("categories", cats)
        return root.toString(2)
    }

    /**
     * 导入：返回成功写入的分类数。
     * 允许只带部分分类；分类值必须是字符串数组；非法条目跳过该分类。
     */
    fun importJson(ctx: Context, text: String): Int {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return 0
        val cats = root.optJSONObject("categories") ?: return 0
        var saved = 0
        for (c in CATEGORIES) {
            val arr = cats.optJSONArray(c.id) ?: continue
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out += s
            }
            if (out.isEmpty()) continue
            save(ctx, c.id, out)
            saved++
        }
        return saved
    }
}

package app.dsh.mobile.pet

import android.content.Context
import app.dsh.mobile.DshApp
import java.io.File

/** 桌宠的持久化偏好。全部落在现有的 dsh_ui SharedPreferences 里，不新建文件。 */
object PetPrefs {
    private const val KEY_ENABLED = "pet_enabled"
    private const val KEY_SIZE_DP = "pet_size_dp"
    private const val KEY_X = "pet_x"
    private const val KEY_Y = "pet_y"
    private const val KEY_PACK_LABEL = "pet_pack_label"
    private const val KEY_PACK_ID = "pet_pack_id"
    private const val KEY_PACK_INDEX_URL = "pet_pack_index_url"
    private const val KEY_SOUND = "pet_sound_enabled"

    const val DEFAULT_SIZE_DP = 150
    const val MIN_SIZE_DP = 80
    const val MAX_SIZE_DP = 1024

    /** 资源包索引的默认来源：资源仓库 Release 上由 CI 发布的 index.json。 */
    const val DEFAULT_PACK_INDEX_URL =
        "https://github.com/XiaoPb/dsh-pet-mobile-assets/releases/latest/download/index.json"

    /** 设置页的尺寸档位：从「小巧」到「贴满大屏」，实际窗口还会按屏幕再收缩。 */
    val SIZE_LEVELS = intArrayOf(120, 150, 180, 240, 320, 400, 480, 600, 720, 1024)

    /** 允许的 pack id 字符：字母数字与 - _ .，其余替换为 - ，避免路径穿越。 */
    private val ID_SAFE = Regex("[^A-Za-z0-9._-]")

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun sizeDp(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

    fun setSizeDp(ctx: Context, sizeDp: Int) {
        prefs(ctx).edit().putInt(KEY_SIZE_DP, sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    /** -1 表示还没有保存过位置，由 Service 放到默认右上角。 */
    fun x(ctx: Context): Int = prefs(ctx).getInt(KEY_X, -1)

    fun y(ctx: Context): Int = prefs(ctx).getInt(KEY_Y, -1)

    fun setPosition(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    fun packLabel(ctx: Context): String? =
        prefs(ctx).getString(KEY_PACK_LABEL, null)?.takeIf { it.isNotBlank() }

    fun setPackLabel(ctx: Context, label: String?) {
        prefs(ctx).edit().putString(KEY_PACK_LABEL, label).apply()
    }

    /** 点击音效（拍一拍）：默认开。 */
    fun soundEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    // ---------------------------------------------------------------- 资源包（多包）

    /** 归一化 pack id：掉非法字符，空则回落到 fallback。 */
    fun safeId(raw: String?, fallback: String = "pack"): String {
        val clean = ID_SAFE.replace(raw?.trim().orEmpty(), "-").trim('-', '.')
        return clean.takeIf { it.isNotBlank() } ?: fallback
    }

    /** 所有已安装资源包的根目录：filesDir/pet/packs/<id>/，与 dsh-home / profiles 完全隔离。 */
    fun packsRoot(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "pet/packs")

    /** v1 时代的单包目录，仅用于一次性迁移。 */
    fun legacyPackDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "pet/pack")

    fun packDir(ctx: Context, id: String): File = File(packsRoot(ctx), safeId(id))

    fun packConfigFile(ctx: Context, id: String): File = File(packDir(ctx, id), "pet-config.json")

    fun packWebpDir(ctx: Context, id: String): File = File(packDir(ctx, id), "webp")

    /** 包自带的字体（可选），由 installer 原样解出 fonts/ 目录。 */
    fun packFontDir(ctx: Context, id: String): File =
        File(packDir(ctx, id), "fonts").takeIf { it.isDirectory }
            ?: File(packDir(ctx, id), "font")

    fun hasPack(ctx: Context, id: String): Boolean =
        id.isNotBlank() && packConfigFile(ctx, id).isFile && packWebpDir(ctx, id).isDirectory

    /** 已安装的包列表，按目录名字母序。 */
    fun installedPackIds(ctx: Context): List<String> =
        packsRoot(ctx).listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && hasPack(ctx, it.name) }
            ?.map { it.name }
            ?.sorted()
            ?.toList()
            ?: emptyList()

    /** 当前使用的包 id；为空表示还没装过任何包。 */
    fun packId(ctx: Context): String? =
        prefs(ctx).getString(KEY_PACK_ID, null)?.takeIf { it.isNotBlank() && hasPack(ctx, it) }

    fun setPackId(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(KEY_PACK_ID, id?.takeIf { it.isNotBlank() }).apply()
    }

    /** 删除某个包；删掉的是当前包时自动切到剩下的第一个，没有则清空。 */
    fun removePack(ctx: Context, id: String): Boolean {
        val ok = runCatching { packDir(ctx, id).deleteRecursively() }.getOrDefault(false)
        if (ok && packId(ctx) == null) {
            setPackId(ctx, installedPackIds(ctx).firstOrNull())
        }
        return ok
    }

    /** 资源包索引地址：可被设置页改动，便于自托管。 */
    fun packIndexUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_PACK_INDEX_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PACK_INDEX_URL

    fun setPackIndexUrl(ctx: Context, url: String?) {
        prefs(ctx).edit().putString(KEY_PACK_INDEX_URL, url?.takeIf { it.isNotBlank() }).apply()
    }
}

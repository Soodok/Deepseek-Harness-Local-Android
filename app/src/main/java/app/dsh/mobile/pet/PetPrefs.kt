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
    private const val KEY_SOUND = "pet_sound_enabled"

    const val DEFAULT_SIZE_DP = 150
    const val MIN_SIZE_DP = 80
    const val MAX_SIZE_DP = 1024

    /** 设置页的尺寸档位：从「小巧」到「贴满大屏」，实际窗口还会按屏幕再收缩。 */
    val SIZE_LEVELS = intArrayOf(120, 150, 180, 240, 320, 400, 480, 600, 720, 1024)

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

    /** 动画包目录：filesDir/pet/pack，与 dsh-home / profiles 完全隔离。 */
    fun packDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "pet/pack")

    fun packConfigFile(ctx: Context): File = File(packDir(ctx), "pet-config.json")

    fun packWebpDir(ctx: Context): File = File(packDir(ctx), "webp")

    fun hasPack(ctx: Context): Boolean = packConfigFile(ctx).isFile && packWebpDir(ctx).isDirectory

    fun clearPack(ctx: Context): Boolean = runCatching {
        val dir = packDir(ctx)
        if (dir.exists()) dir.deleteRecursively() else true
    }.getOrDefault(false)
}

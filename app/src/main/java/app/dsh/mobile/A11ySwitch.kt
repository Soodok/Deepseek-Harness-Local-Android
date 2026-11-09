package app.dsh.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 把本应用的无障碍服务**按需**放进系统的启用列表（v1.7.0）。
 *
 * 背景：用户要的「AI 行动边界」是 —— 开始动手时无障碍在，动作结束就撤掉。
 * 应用自己不能直接开关无障碍服务，唯一合法的口子是 WRITE_SECURE_SETTINGS
 * （`pm grant` 或 root 一次性授予）。没有这个权限时这里全部返回 false，
 * 调用方按「做不到」处理，**不能**假装成功。
 *
 * 只碰自己那一条：TalkBack 之类的其他条目原样保留（见 [A11yEntry]）。
 */
object A11ySwitch {
    private const val TAG = "DshA11ySwitch"

    /** 与 AndroidManifest 里 android:name=".DshAccessibilityService" 一致的全名。 */
    const val SERVICE_CLASS = "app.dsh.mobile.DshAccessibilityService"

    /** 有没有拿到写系统设置的权限。没有就只能由用户手动开关。 */
    fun granted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun read(ctx: Context): String? =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    /** 现在列表里有没有我们（不看服务是否真的绑定成功）。 */
    fun listed(ctx: Context): Boolean =
        A11yEntry.listed(read(ctx), ctx.packageName, SERVICE_CLASS)

    /** 服务是否真的连上了（系统绑定完成的判据）。 */
    fun bound(): Boolean = DshAccessibilityService.instance != null

    /** 把我们加进列表并打开无障碍总开关。 */
    fun add(ctx: Context): Boolean =
        write(ctx, A11yEntry.withMe(read(ctx), ctx.packageName, SERVICE_CLASS), enable = true)

    /** 把我们移出列表。总开关不动 —— 别人可能还开着别的无障碍服务。 */
    fun remove(ctx: Context): Boolean =
        write(ctx, A11yEntry.withoutMe(read(ctx), ctx.packageName, SERVICE_CLASS), enable = false)

    /**
     * 写列表。只有拿到权限才写；任何异常都吞掉并返回 false（这是尽力而为的能力，
     * 不是主流程 —— 写不进去不该让一次点击失败）。
     */
    private fun write(ctx: Context, list: String, enable: Boolean): Boolean {
        if (!granted(ctx)) return false
        return runCatching {
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
            if (enable) Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "accessibility list updated (enable=" + enable + ")")
            true
        }.getOrElse {
            Log.w(TAG, "cannot write accessibility settings", it)
            false
        }
    }
}
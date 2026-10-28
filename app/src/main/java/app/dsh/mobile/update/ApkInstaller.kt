package app.dsh.mobile.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * APK 安装（系统安装器）封装。
 *
 * ## 相比 `ExtensionManager.installApkTask` 修掉的两个问题
 * 1. **没有回执**：旧实现拿 `getLaunchIntentForPackage()` 当 `commit()` 的
 *    IntentSender，等于"点完安装什么都不回来" —— 签名冲突、空间不足、用户取消
 *    全部无从得知，UI 只能傻等或误报成功。现在改用真正的
 *    `InstallResultReceiver`（`STATUS_PENDING_USER_ACTION` / `SUCCESS` / `FAILURE_*`）。
 * 2. **重复实现**：装包前不校验签名证书，被代理投毒的包会走到系统安装器才失败。
 *    这里先用 `getPackageArchiveInfo` 比对签名证书指纹，不一致直接不进安装器。
 *
 * 系统安装确认框无法跳过（无系统签名做不到静默安装），这是刻意保留的。
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    fun canRequestInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 引导用户打开「安装未知应用」开关（带包名，直达本应用条目）。 */
    fun openInstallPermission(ctx: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    /** 读 APK 的 versionCode（0 = 读不出来，说明文件坏了）。 */
    @Suppress("DEPRECATION")
    fun archiveVersionCode(ctx: Context, apk: File): Int = runCatching {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }.getOrDefault(0)

    /**
     * 安装包签名是否与**已装应用**一致。
     *
     * 这是"能不能覆盖安装"的硬前提，也是防投毒的第二道闸（第一道是索引里的 sha256）：
     * 代理即使换了个同版本号的包，签名对不上就装不进去。
     */
    fun sameSignature(ctx: Context, apk: File): Boolean = runCatching {
        val archive = certDigestsOfArchive(ctx, apk)
        val installed = certDigestsOfInstalled(ctx)
        if (archive.isEmpty() || installed.isEmpty()) {
            Log.w(TAG, "cannot read certificates (archive=${archive.size}, installed=${installed.size})")
            return false
        }
        archive.intersect(installed).isNotEmpty()
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun certDigestsOfArchive(ctx: Context, apk: File): Set<String> {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return sigs.mapNotNull { sha256(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun certDigestsOfInstalled(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageInfo(ctx.packageName, flags) ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return sigs.mapNotNull { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String? = runCatching {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * 提交安装会话。
     *
     * @return false 表示会话创建/写入失败（磁盘满、安装器忙）；true 只代表
     *         "请求已提交"，最终结果由 [InstallResultReceiver] 异步回执。
     */
    fun install(ctx: Context, apk: File, versionCode: Int): Boolean = runCatching {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                .putExtra(InstallResultReceiver.EXTRA_VERSION_CODE, versionCode)
            // ⚠️ 必须是 MUTABLE：系统要往这个 Intent 里塞 EXTRA_STATUS / EXTRA_INTENT，
            // 用 IMMUTABLE 会让 commit 直接抛异常（"点了安装没反应"的经典原因）。
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "install session committed: versionCode=$versionCode")
        true
    }.onFailure {
        Log.w(TAG, "install failed to commit: ${it.message}", it)
    }.getOrDefault(false)

    // 兼容旧调用点（如需从别处发起安装）：这里只暴露"是否已在跑"的判断入口
    fun isInstalling(): Boolean = UpdateTasks.running() == UpdateTasks.Task.APK_INSTALL
}

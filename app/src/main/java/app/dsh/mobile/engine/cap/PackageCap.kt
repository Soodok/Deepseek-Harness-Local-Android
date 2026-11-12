package app.dsh.mobile.engine.cap

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App 包管理（`/dev/packages`）。
 *
 * 为什么需要它：项目里原本没有任何包管理端点。AI 要「看看装了什么」「装一个
 * APK」「卸掉某个 App」都只能靠 shell 里的 `pm` 命令，而那条路需要 root 或
 * shell 身份 —— 在非 root 设备上等于完全不可用。包管理是「自主配置环境」的
 * 基础能力，不该要求 root。
 *
 * 三档操作，风险递增，授权模型刻意不同：
 *   · `list` / `info` —— 纯读，只要 [CapabilityGate.PACKAGES] 开关。
 *   · `install` / `uninstall` —— **改变系统状态**，除开关外还必须走系统确认
 *     弹窗（`ACTION_INSTALL_PACKAGE` / `ACTION_UNINSTALL_PACKAGE`）。
 *     这是刻意的：让 AI 静默装包等于给它一个持久的后门入口，用户在装的那一刻
 *     必须看见包名和权限。`auto=true` 也**不会**跳过这一步。
 *
 * ⚠️ 平台限制（实测口径）：
 *   1. Android 11+ 的包可见性：没有 `QUERY_ALL_PACKAGES` 时 `getInstalledPackages`
 *      只返回「与本应用有交互」的子集。本项目 manifest 已声明该权限
 *      （`app/src/main/AndroidManifest.xml:742`），所以能拿到全量；但若未来
 *      被商店政策否掉，`list` 会静默变少而不是报错 —— 所以返回值带 `total`
 *      让调用方能发现「怎么只有 3 个」。
 *   2. `ACTION_UNINSTALL_PACKAGE` 自 API 29 起被标记 deprecated，
 *      官方建议 `ACTION_DELETE`。两者在 Android 14 上实测都还能拉起确认页，
 *      这里优先用 `ACTION_DELETE`（不传包名时行为一致，但语义更明确）。
 *   3. `REQUEST_INSTALL_PACKAGES` 已声明（manifest:860），但**仍需用户**在
 *      「安装未知应用」里对本 App 授权，否则 Intent 直接被系统拒绝。
 *      所以 `install` 先 `canRequestPackageInstalls()` 检查，缺权时给明确指引，
 *      而不是抛一个 ActivityNotFoundException 让人猜。
 */
object PackageCap {

    /**
     * 一条应用信息。文件级而非局部：Kotlin 局部类不能是 data class
     * （同 `LocationCap.Fix` 的理由）。
     */
    private data class AppRow(
        val pkg: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val system: Boolean,
        val enabled: Boolean,
        val targetSdk: Int,
        val firstInstall: Long,
        val lastUpdate: Long,
        val apkBytes: Long,
    )

    fun handle(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to err("invalid json")
        val op = obj.optString("op", "list").lowercase()
        if (!CapabilityGate.can(ctx, CapabilityGate.PACKAGES)) {
            return 403 to err("capability disabled", "packages")
        }
        return when (op) {
            "list" -> list(ctx, obj)
            "info" -> info(ctx, obj)
            "install" -> install(ctx, obj)
            "uninstall" -> uninstall(ctx, obj)
            else -> 400 to err("op must be list|info|install|uninstall")
        }
    }

    private fun list(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val pm = ctx.packageManager
        val q = obj.optString("q").lowercase().trim()
        // 默认排除系统应用：AI 通常关心「用户装了什么」。想全看就传 system=true。
        //
        // ⚠️ v1.7.10：目录里这条写的是 `include_system=false`，实现却只读 `system`，
        // 于是**照文档传参静默无效**（传 include_system=true 拿到的仍是默认子集）。
        // 两个名字都认，两种写法都能用；同时给了就以后者（规范化名）为准。
        val includeSystem = if (obj.has("include_system")) {
            obj.optBoolean("include_system", false)
        } else {
            obj.optBoolean("system", false)
        }
        val limit = obj.optInt("limit", 200).coerceIn(1, 1000)

        val flags = PackageManager.GET_META_DATA
        val packages = runCatching { pm.getInstalledPackages(flags) }.getOrDefault(emptyList())
        val rows = ArrayList<AppRow>()
        for (pi in packages) {
            val app = pi.applicationInfo ?: continue
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) continue
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pi.packageName)
            if (q.isNotEmpty() &&
                !pi.packageName.lowercase().contains(q) &&
                !label.lowercase().contains(q)
            ) continue
            rows.add(rowOf(pi, label, isSystem))
        }
        // 按最后更新时间倒序：AI 想知道「最近装了什么」时这一步就够，不用自己排。
        rows.sortByDescending { it.lastUpdate }

        CapabilityGate.audit(ctx, "dev.packages.list:${rows.size}")
        val arr = JSONArray()
        for (r in rows.take(limit)) arr.put(toJson(r))
        val doc = JSONObject()
            .put("ok", true)
            .put("total", rows.size)
            .put("returned", minOf(rows.size, limit))
            // installed_packages 的总量（含系统），用来发现包可见性被限制
            .put("visible_total", packages.size)
            .put("include_system", includeSystem)
            .put("apps", arr)

        // 包可见性被 OEM 锁掉时**必须自证**（v1.7.10 真机实测）：
        // OnePlus PLZ110 / ColorOS 上 QUERY_ALL_PACKAGES 已授予、pm 里 555 个包，
        // 但 `com.android.permission.GET_INSTALLED_APPS` 的 appop 是 ignore，
        // 于是 getInstalledPackages 只回 1 条（只看到自己）。此前返回值只有
        // total:1 —— AI 会当成「这台设备只装了 1 个应用」，而真相是「系统不让看」。
        // 这两种情况的处置完全不同（后者要用户去系统设置里放开），所以这里显式区分。
        if (packages.size <= 3) {
            val opState = runCatching {
                val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    "android:get_installed_apps",
                    android.os.Process.myUid(),
                    ctx.packageName,
                )
            }.getOrNull()
            val ignored = opState == android.app.AppOpsManager.MODE_IGNORED
            doc.put("visibility_restricted", true)
            doc.put("visibility_op", if (ignored) "ignored" else "unknown")
            doc.put(
                "hint",
                if (ignored) {
                    "the system denies package visibility to this app " +
                        "(appop android:get_installed_apps = ignore); only ${packages.size}" +
                        " package(s) are visible. Ask the user to allow the \"installed apps\"" +
                        " permission for DSH Mobile in the system settings (common on ColorOS/OriginOS)."
                } else {
                    "only ${packages.size} package(s) visible; if the device clearly has more apps, " +
                        "package visibility is being restricted by the ROM"
                },
            )
        } else {
            doc.put("visibility_restricted", false)
        }
        return 200 to doc.toString()
    }

    private fun info(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val pkg = obj.optString("pkg").trim()
        if (pkg.isEmpty()) return 400 to err("field 'pkg' required")
        val pm = ctx.packageManager
        val pi = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_META_DATA) }.getOrNull()
            ?: return 404 to err("package not installed: $pkg")
        val app = pi.applicationInfo
        val isSystem = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = if (app != null) {
            runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg)
        } else pkg
        CapabilityGate.audit(ctx, "dev.packages.info:$pkg")
        val out = toJson(rowOf(pi, label, isSystem))
        // info 比 list 多给两项：声明的权限、以及是不是 debuggable。
        // 后者对「这个包装的能不能调试」很关键，而 list 里放它会显著变大。
        val perms = runCatching { pi.requestedPermissions?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val permArr = JSONArray()
        perms.forEach { permArr.put(it) }
        out.put("requested_permissions", permArr)
        out.put("debuggable", app?.let { (it.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: false)
        return 200 to out.toString()
    }

    private fun install(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val path = obj.optString("path").trim()
        if (path.isEmpty()) return 400 to err("field 'path' required")
        val f = File(path)
        if (!f.isFile) return 404 to err("apk not found: $path")
        if (!f.canRead()) return 403 to err("apk not readable: $path")
        // 扩展名检查不是洁癖：把非 apk 丢给系统安装器会得到含义模糊的
        // INSTALL_FAILED_INVALID_APK，先在这里挡掉能省一轮排查。
        if (!path.endsWith(".apk", ignoreCase = true)) {
            return 400 to err("not an .apk file: $path")
        }

        // 权限门槛：API 26+ 需要用户对「本 App 装未知应用」单独授权。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            return 403 to JSONObject()
                .put("ok", false)
                .put("error", "install permission not granted")
                .put("hint", "enable Settings > Apps > DSH > Install unknown apps")
                .put("op", "install")
                .toString()
        }

        // 先复制到 FileProvider 暴露的 cache/apk/ 下：manifest 的 file_paths 只
        // 声明了 cache/shared、files/dsh-home/workspaces、cache/apk 三段，
        // 直接把任意路径交给 provider 会抛 IllegalArgumentException。
        // 复制还有一个好处：源 APK 被删/被改不影响正在进行的那次安装。
        val staged = runCatching {
            val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
            val dst = File(dir, f.name)
            f.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
            dst
        }.getOrNull()
            ?: return 507 to JSONObject()
                .put("ok", false).put("error", "cannot stage apk (cache full?)").put("path", path)
                .toString()
        val apkUri = uriOf(ctx, staged)
            ?: return 409 to JSONObject()
                .put("ok", false)
                .put("error", "fileprovider rejected staged apk")
                .put("path", staged.absolutePath)
                .toString()
        val it = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchConfirm(ctx, it, "install", path, obj)
    }

    private fun uninstall(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val pkg = obj.optString("pkg").trim()
        if (pkg.isEmpty()) return 400 to err("field 'pkg' required")
        // 系统应用卸不掉，提前挡掉比让系统弹一个误导性的确认页好。
        val pi = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            ?: return 404 to err("package not installed: $pkg")
        val isSystem = pi.applicationInfo?.let {
            (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } ?: false
        if (isSystem && !obj.optBoolean("force", false)) {
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "refusing to uninstall system app")
                .put("pkg", pkg)
                .put("hint", "pass force=true only if you really mean it")
                .toString()
        }
        // ACTION_DELETE 自 API 14 起就是标准路径；ACTION_UNINSTALL_PACKAGE 在 29 起
        // 标记 deprecated。两者都要经过系统确认页 —— 这正是我们要的。
        val it = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchConfirm(ctx, it, "uninstall", pkg, obj)
    }

    /**
     * 拉起系统确认页。
     *
     * ⚠️ 这个函数**永远返回 202**，它不能告诉调用方「装成功了」——
     * 因为真正的安装/卸载发生在用户点确认之后，而本进程拿不到那个结果
     * （没有 ActivityResult 回调挂在常驻服务上）。返回 ok=true 会变成假绿：
     * 调用方以为装好了，实际用户可能点了取消。
     *
     * 正确做法是让调用方随后自己查 `op=info` 确认落地。
     */
    private fun launchConfirm(
        ctx: Context,
        intent: Intent,
        action: String,
        target: String,
        obj: JSONObject,
    ): Pair<Int, String> {
        if (obj.optBoolean("dry_run", false)) {
            return 200 to JSONObject()
                .put("ok", true).put("dry_run", true)
                .put("would", action).put("target", target)
                .toString()
        }
        val started = runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
        if (!started) {
            return 503 to JSONObject()
                .put("ok", false)
                .put("error", "no activity handled $action confirmation")
                .put("target", target)
                .toString()
        }
        CapabilityGate.audit(ctx, "dev.packages.$action:$target")
        return 202 to JSONObject()
            .put("ok", true)
            .put("pending", true)
            .put("op", action)
            .put("target", target)
            // 明确写出来，避免调用方把 202 当成「已完成」
            .put("note", "system confirmation shown; verify with op=info afterwards")
            .toString()
    }

    /**
     * 把文件变成可交给系统安装器的 URI。
     *
     * 优先 FileProvider（`content://`）：targetSdk 24+ 直接传 `file://` 会触发
     * FileUriExposedException。authority 与 `OpenBridge.kt:40` 用的是同一个
     * （`$packageName.fileprovider`），manifest 里已注册。
     *
     * 退路是 `Uri.fromFile`：只有当 FileProvider 没配 PATH 时才会走到
     * （比如 APK 在 filesDir 之外，而 provider 的 paths 只声明了有限几段）。
     * 这时安装会因 FileUriExposedException 失败 —— 所以要如实把失败报给调用方，
     * 不能静默降级。
     */
    private fun uriOf(ctx: Context, f: File): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", f,
        )
    }.getOrNull()

    private fun rowOf(pi: android.content.pm.PackageInfo, label: String, isSystem: Boolean): AppRow {
        val info = pi.applicationInfo
        return AppRow(
            pkg = pi.packageName,
            label = label,
            versionName = pi.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            },
            system = isSystem,
            enabled = info?.enabled ?: true,
            targetSdk = info?.targetSdkVersion ?: 0,
            firstInstall = pi.firstInstallTime,
            lastUpdate = pi.lastUpdateTime,
            apkBytes = info?.sourceDir?.let { File(it).length() } ?: 0L,
        )
    }

    private fun toJson(r: AppRow): JSONObject = JSONObject()
        .put("pkg", r.pkg)
        .put("label", r.label)
        .put("version", r.versionName)
        .put("version_code", r.versionCode)
        .put("system", r.system)
        .put("enabled", r.enabled)
        .put("target_sdk", r.targetSdk)
        .put("first_install", r.firstInstall)
        .put("last_update", r.lastUpdate)
        .put("apk_bytes", r.apkBytes)

    private fun err(message: String, capability: String? = null): String {
        val o = JSONObject().put("ok", false).put("error", message)
        if (capability != null) o.put("capability", capability)
        return o.toString()
    }
}

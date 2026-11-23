package app.dsh.mobile

import android.content.Context
import android.os.Build
import app.dsh.mobile.engine.LinuxRootfsCatalog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 问题报告的「内容打包」层：负责**取到什么**和**脱敏**，不负责界面。
 *
 * ## 为什么单独一层
 *
 * 用户愿不愿意反馈，几乎全看他**能不能看见要发出去的东西**。所以这里产出一个
 * 纯数据的 [Draft]，界面层先原样展示，用户划掉不要的段再发 —— 展示与发送用同一份
 * 数据，避免「预览说一套、上传另一套」。
 *
 * ## 红线（改代码前先读这段）
 *
 * - **不采集**：手机号、IMEI/序列号、精确位置、通讯录、剪贴板、已安装应用列表。
 * - 设备型号是**可关**的：关掉后只报 `abi + Android 版本`，仍足够复现架构相关问题。
 * - 所有文本出网前都过 [redact]：token / 密钥 / 手机号 / 身份证号一律替换。
 * - install_id 只是本机随机 UUID，用来做「同一台设备重复提交」的去重与限流，
 *   不关联任何账号；设置页可重置。
 */
object ReportBundle {

    /** 上报端点（美国 VPS，无备案负担；只收这一条 POST）。 */
    const val ENDPOINT = "https://soodok.online/bug/report.php"

    /**
     * 上报钥匙。它是**公开的**（打进 APK 就能被扒出来），作用只是挡住无脑扫描，
     * 真正的防线是服务端的限流与体积上限 —— 别把它当秘密来设计。
     */
    const val REPORT_KEY = "67de90b1044679a4223e2f3d3ed7843d"

    private const val PREFS = "dsh_report"
    private const val KEY_INSTALL_ID = "install_id"

    private const val LOG_TAIL_BYTES = 64 * 1024
    private const val LOGCAT_LINES = 300

    data class Draft(
        val installId: String,
        val title: String,
        val body: String,
        val contact: String,
        val appVersion: String,
        val versionCode: Int,
        val abi: String,
        val android: String,
        val device: String,
        val mode: String,
        val logs: String,
    ) {
        /** 发出去之前给用户看的全文；与 [ReportBundle.markdown] 同一来源。 */
        fun preview(): String = ReportBundle.markdown(this)
    }

    /** 本机匿名安装 ID：首次调用生成并持久化。 */
    fun installId(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY_INSTALL_ID, null)
        if (existing != null && existing.length >= 8) return existing
        val id = UUID.randomUUID().toString().replace("-", "").take(32)
        sp.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    /** 换一个安装 ID（设置页给「重置」用），旧的报告从此不再算「同一台设备」。 */
    fun resetInstallId(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_INSTALL_ID).apply()
    }

    /**
     * 收集一份草稿。
     *
     * @param attachLogs 是否附日志（关掉时 logs 为空串，不是「读过但没发」）
     * @param attachDevice 是否附具体型号；关掉只留 abi + Android 版本
     */
    fun collect(
        ctx: Context,
        title: String,
        body: String,
        contact: String,
        attachLogs: Boolean,
        attachDevice: Boolean,
    ): Draft {
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val versionName = pkg?.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            (pkg?.longVersionCode ?: 0L).toInt()
        } else {
            @Suppress("DEPRECATION")
            pkg?.versionCode ?: 0
        }

        val logs = if (attachLogs) redact(collectLogs(ctx)) else ""

        return Draft(
            installId = installId(ctx),
            title = title.trim().take(200),
            body = body.trim().take(8000),
            contact = contact.trim().take(200),
            appVersion = versionName,
            versionCode = versionCode,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = if (attachDevice) shortDevice() else "(hidden)",
            mode = describeMode(ctx),
            logs = logs,
        )
    }

    /** `Pixel 7 / google` 这种能复现问题、又不像唯一标识的短描述。 */
    private fun shortDevice(): String {
        val brand = Build.BRAND?.replaceFirstChar { it.uppercase() } ?: "?"
        val model = Build.MODEL ?: "?"
        return "$brand $model"
    }

    /**
     * 运行模式：容器（含发行版）还是原生。
     *
     * 只读偏好与目录状态，**不探测 su** —— 反馈页不该因为一次无关的 root 探测卡住。
     */
    private fun describeMode(ctx: Context): String = runCatching {
        val rootfs = LinuxRootfsCatalog.active(ctx)
        if (rootfs == null) {
            "native"
        } else {
            val family = runCatching { rootfs.family.id }.getOrDefault("?")
            val flavor = runCatching { rootfs.flavor.id }.getOrDefault("?")
            "container:$family/$flavor"
        }
    }.getOrDefault("unknown")

    // ---------------------------------------------------------------- 脱敏

    private class Rule(val regex: Regex, val replace: String)

    /**
     * 脱敏规则表。顺序有意义：先吃长 token，再吃 `key=value` 这种通用形状。
     *
     * 判据偏保守：宁可多抹一点（用户看得到预览，抹多了会自己补回来），
     * 也不要漏发一个 token —— 漏一次就可能要让用户重新登录或换 key。
     */
    private val RULES: List<Rule> = listOf(
        Rule(Regex("""(?i)(X-DSH-Token\s*[:=]\s*)[A-Za-z0-9_\-]{8,}"""), "$1[redacted]"),
        Rule(
            Regex("""(?i)\b(DSH_BRIDGE_TOKEN|DSH_PET_BRIDGE_TOKEN|DSH_REPORT_KEY)(\s*=\s*)[^\s"']+"""),
            "$1$2[redacted]"
        ),
        Rule(Regex("""(?i)\bBearer\s+[A-Za-z0-9_\-.]{10,}"""), "Bearer [redacted]"),
        Rule(Regex("""\bsk-[A-Za-z0-9_\-]{16,}"""), "[redacted]"),
        Rule(Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""), "[redacted]"),
        Rule(Regex("""\bAKIA[0-9A-Z]{16}\b"""), "[redacted]"),
        Rule(
            Regex(
                """(?i)\b(_authToken|authToken|npmToken|api[_-]?key|access[_-]?key|secret|password|passwd)""" +
                    """(\s*[:=]\s*)[^\s"',]{6,}"""
            ),
            "$1$2[redacted]"
        ),
        Rule(Regex("""(?i)\b(token|key)(\s*[:=]\s*)[A-Za-z0-9_\-]{16,}"""), "$1$2[redacted]"),
        Rule(Regex("""\b1[3-9]\d{9}\b"""), "[phone]"),
        Rule(Regex("""\b\d{17}[\dXx]\b"""), "[id]"),
    )

    /** 抹掉 token / 密钥 / 手机号 / 身份证号。纯文本进，纯文本出。 */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (rule in RULES) {
            out = out.replace(rule.regex, rule.replace)
        }
        return out
    }

    // ---------------------------------------------------------------- 日志

    /**
     * 引擎日志尾部 + 最近 logcat。
     *
     * 两者都可能很大，所以先按字节截尾再脱敏：截断发生在**读取阶段**，
     * 不是发出去之前才发现太大。
     */
    fun collectLogs(ctx: Context): String {
        val sb = StringBuilder()

        sb.append("### engine.log (tail)\n")
        sb.append(engineLogTail(ctx))

        sb.append("\n### logcat (last ").append(LOGCAT_LINES).append(" lines, filtered)\n")
        sb.append(logcatTail())
        return sb.toString()
    }

    private fun engineLogTail(ctx: Context): String = runCatching {
        val candidates = listOf(
            File(ctx.filesDir, "engine.log"),
            File(app.dsh.mobile.engine.EngineConfig.engineRoot(ctx), "engine.log"),
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: return@runCatching "(no engine.log)"
        // 按字节截尾：日志可能有上百 MB，全读进来会直接把 App 拖死。
        val len = f.length()
        val from = if (len > LOG_TAIL_BYTES) len - LOG_TAIL_BYTES else 0L
        f.inputStream().use { ins ->
            ins.skip(from)
            val buf = ByteArray((len - from).toInt().coerceAtMost(LOG_TAIL_BYTES))
            var read = 0
            while (read < buf.size) {
                val n = ins.read(buf, read, buf.size - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        }
    }.getOrDefault("(engine.log read failed)")

    /**
     * logcat 尾部。设备上没有 [READ_LOGS] 时 exec 会失败，这里静默降级 ——
     * 反馈功能不该因为拿不到 logcat 就整体不可用。
     *
     * 3 秒硬超时：`logcat -d` 在个别机型上会卡住，卡住就等于用户点「发送」没反应。
     */
    private fun logcatTail(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "$LOGCAT_LINES", "-v", "time")
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroy()
            return@runCatching "(logcat timeout)"
        }
        val keep = text.lineSequence()
            .filter { line ->
                val l = line.lowercase()
                l.contains("app.dsh") || l.contains("engine") || l.contains("fatal") ||
                    l.contains("androidruntime")
            }
            .joinToString("\n")
        if (keep.isEmpty()) "(logcat: no matching lines)" else keep.take(LOG_TAIL_BYTES)
    }.getOrDefault("(logcat unavailable)")

    // ---------------------------------------------------------------- 渲染

    /**
     * 转成 Markdown：预览、剪贴板、邮件、GitHub Issue 四处共用同一份文本。
     *
     * 小节标题刻意用英文：这份文本是给开发者读的技术产物（像 commit message），
     * 不是界面文案 —— 走 i18n 资源反而会让同一份 bug 在不同语言下长得不一样，
     * 排查时还得先翻译。界面上的按钮与标签仍然是本地化的。
     */
    fun markdown(d: Draft): String = buildString {
        append("## What happened\n\n")
        append(if (d.title.isEmpty()) "(no title)" else d.title)
        append("\n\n")
        append(if (d.body.isEmpty()) "(no description)" else d.body)
        append("\n\n## Environment\n\n")
        append("| field | value |\n|---|---|\n")
        append("| app | ${d.appVersion} (${d.versionCode}) |\n")
        append("| abi | ${d.abi} |\n")
        append("| system | ${d.android} |\n")
        append("| device | ${d.device} |\n")
        append("| mode | ${d.mode} |\n")
        append("| install | ${d.installId.take(8)}... |\n")
        if (d.contact.isNotEmpty()) {
            append("\ncontact: ${d.contact}\n")
        }
        if (d.logs.isNotEmpty()) {
            append("\n## Logs\n\n```\n")
            append(d.logs)
            append("\n```\n")
        }
    }

    fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    // ---------------------------------------------------------------- 上报

    /**
     * 真正发出。**阻塞**，调用方必须在后台线程跑。
     *
     * @return 服务端受理后返回的报告简短 id（例如 `5b816558581b`）
     * @throws IllegalStateException 服务端拒绝或网络失败；消息可直接给用户看
     */
    fun post(d: Draft): String {
        val body = org.json.JSONObject().apply {
            put("install_id", d.installId)
            put("app_version", d.appVersion)
            put("version_code", d.versionCode)
            put("abi", d.abi)
            put("android", d.android)
            put("device", d.device)
            put("mode", d.mode)
            put("title", d.title)
            put("body", d.body)
            put("logs", d.logs)
            put("contact", d.contact)
        }.toString().toByteArray(Charsets.UTF_8)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-DSH-Report-Key", REPORT_KEY)
            setRequestProperty("User-Agent", "dsh-android/${d.appVersion}")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val json = runCatching { org.json.JSONObject(text) }.getOrNull()
            if (code in 200..299 && json?.optBoolean("ok") == true) {
                return json.optString("id", "")
            }
            val err = json?.optString("error").orEmpty().ifEmpty { text.take(200) }
            throw IllegalStateException("HTTP $code ${err.ifEmpty { "(空响应)" }}")
        } finally {
            conn.disconnect()
        }
    }
}

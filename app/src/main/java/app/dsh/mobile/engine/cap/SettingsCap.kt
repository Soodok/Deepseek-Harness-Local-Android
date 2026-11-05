package app.dsh.mobile.engine.cap

import android.content.Context
import android.content.SharedPreferences
import app.dsh.mobile.Accent
import app.dsh.mobile.CurrentActivity
import app.dsh.mobile.CustomVisualPref
import app.dsh.mobile.DshApp
import app.dsh.mobile.OrientationPrefs
import app.dsh.mobile.SettingsLayoutMode
import app.dsh.mobile.ThemePref
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 自身设置的读取与修改（v1.6.2）。
 *
 * ## 为什么做这个
 * 之前 AI 能控制系统（音量、通知、网络、HID…），却看不到它自己跑在什么配置里 ——
 * 用户问「为什么界面是英文」或「帮我把主题换成浅色」，AI 只能让用户自己去翻设置。
 * 这个端点把 20+ 个 SharedPreferences 文件里的设置统一成一个可读写面。
 *
 * ## 三个设计决定
 *
 * ### 1. 读全部，但**密钥一律打码**
 * 全部读得到（用户选了「全部设置开放」），但 `lan_web_password` 这类凭据
 * **只回报长度与是否已设置**，不回报值。理由：AI 读到的内容会进入会话记录、
 * 可能被日志/同步带走，而凭据不该离开它原本的位置。这不是限制 AI 的能力，
 * 是避免把用户的密码复制到别的地方 —— 写它是允许的（AI 可以设新密码），
 * 读它没有正当用途。
 *
 * ### 2. 写全部，白名单只用来标注「会有什么后果」
 * 用户明确选了「全部设置开放 + 直接生效」。所以这里**不做权限拦截**，
 * 而是给每个键标注副作用等级，让 AI 自己知道改了会发生什么：
 *   · SAFE      立即生效，可随时改回（主题、配色、缩放…）
 *   · REFRESH   会触发当前页面重建（页面会闪一下，但状态不丢）
 *   · ENGINE    会重启引擎或换运行模式（正在跑的任务会中断）
 *   · SENSITIVE 涉及凭据或安全边界（能改，但读回来是脱敏的）
 *
 * ### 3. 改完直接生效
 * 用户选了「直接生效，不询问」。写偏好后按副作用等级决定要不要 `recreate()`
 * 当前页面。引擎级改动标记出来，但**不在本端点里自动重启引擎** ——
 * 那会让「改个设置」变成「掐掉正在跑的会话」，太野蛮。改为在返回里明确
 * 告诉调用方「需要重启才生效」，由 AI 决定是否调 /dev/engine 的 restart。
 *
 * ## 与 CapabilityGate 的关系
 * 能力开关（`cap.*`）**不在这里**。那些是用户对 AI 的授权，属于
 * `PermissionCenter` 的领域，已在 `GET /dev/ping` 的 `capabilities` 里暴露。
 * 让 AI 能自己打开 `cap.notify_action`（代用户回微信）就绕过了「逐步授权」的
 * 整个设计意图 —— 这条线不越。
 */
object SettingsCap {

    /** 脱敏键：只回报是否设置与长度，不回报值。 */
    private val SECRET_KEYS = setOf(
        "lan_web_password",
        "pet_pack_index_url",   // 可能是私有源地址，带 token 的也不少
    )

    /**
     * 已知设置项 → 副作用等级 + 人类可读说明。
     *
     * 不在表里的键**依然可读写**（用户选了全部开放），只是标记为 `unknown`，
     * 让 AI 知道它没有可靠的作用说明。
     */
    private enum class Effect { SAFE, REFRESH, ENGINE, SENSITIVE }

    private data class Spec(val effect: Effect, val note: String)

    private val SPECS: Map<String, Spec> = mapOf(
        // —— 外观：改完要重建当前页才看得到 ——
        "theme_mode" to Spec(Effect.REFRESH, "dark|light|system; rebuilds the current page"),
        "accent_scheme" to Spec(Effect.REFRESH, "sky|indigo|teal|violet|amber"),
        "settings_layout" to Spec(Effect.REFRESH, "list|card|grid|collapse"),
        "settings_layout_expanded" to Spec(Effect.REFRESH, "set of expanded section indices in collapse layout mode"),
        "page_scale" to Spec(Effect.REFRESH, "settings page zoom percentage"),
        "landscape" to Spec(Effect.SAFE, "landscape orientation preference"),
        "settings_follow_orientation" to Spec(Effect.SAFE, "whether settings pages follow screen orientation"),
        "immersive_statusbar" to Spec(Effect.SAFE, "immersive mode: hide the status bar"),
        "official_mobile_ui" to Spec(Effect.ENGINE, "use the community mobile-UI plugin; requires engine restart"),
        "settings_bg_file" to Spec(Effect.REFRESH, "settings background image filename"),
        "settings_bg_alpha" to Spec(Effect.REFRESH, "background image opacity 0-100"),
        "settings_bg_blur" to Spec(Effect.REFRESH, "background blur preset 0|25|50|75"),
        "settings_bg_control_alpha" to Spec(Effect.REFRESH, "card background opacity 0-100"),
        "island_dot_file" to Spec(Effect.SAFE, "custom dynamic-island dot image filename"),
        "island_dot_shape" to Spec(Effect.SAFE, "circle|square"),
        "island_dot_size_dp" to Spec(Effect.SAFE, "dynamic-island dot size in dp"),
        "expanded_bg_file" to Spec(Effect.SAFE, "expanded status bar background filename"),
        // —— 宠物 ——
        "pet_enabled" to Spec(Effect.SAFE, "desktop pet on/off"),
        "pet_size_dp" to Spec(Effect.SAFE, "pet size in dp"),
        "pet_x" to Spec(Effect.SAFE, "pet x position"),
        "pet_y" to Spec(Effect.SAFE, "pet y position"),
        "pet_sound_enabled" to Spec(Effect.SAFE, "pet sound effects"),
        "pet_pack_id" to Spec(Effect.SAFE, "pet pack id"),
        "pet_pack_label" to Spec(Effect.SAFE, "pet pack display name"),
        "pet_pack_index_url" to Spec(Effect.SENSITIVE, "pet pack index URL (may contain a private token; redacted on read)"),
        // —— 术语/终端 ——
        "term_font_px" to Spec(Effect.SAFE, "terminal font size in px"),
        // —— 更新 ——
        "update_auto_check" to Spec(Effect.SAFE, "auto-check for updates"),
        "update_wifi_only" to Spec(Effect.SAFE, "download updates on Wi-Fi only"),
        "update_index_proxy_fallback" to Spec(Effect.SAFE, "fall back to a proxy for the update index"),
        "update_package_mode_strategy" to Spec(Effect.SAFE, "update package mode strategy"),
        "update_ignored_version_code" to Spec(Effect.SAFE, "ignored version code"),
        "runtime_download_proxy" to Spec(Effect.SAFE, "runtime download proxy"),
        // —— 引擎 / 权限模式：改完必须重启引擎 ——
        "engine_linux_mode" to Spec(Effect.ENGINE, "Linux container mode toggle; requires engine restart"),
        "priv_mode" to Spec(Effect.ENGINE, "root/shizuku mode; requires engine restart"),
        // —— 凭据 / 安全边界 ——
        "lan_web_access" to Spec(Effect.SAFE, "LAN access toggle"),
        "lan_web_password" to Spec(Effect.SENSITIVE, "LAN access password; writable, but reads return only its length"),
        "a11y_max_provider" to Spec(Effect.SAFE, "credential key name used by max+ model source"),
        "onboarded" to Spec(Effect.SAFE, "whether onboarding completed"),
        "user_exited_explicitly" to Spec(Effect.SAFE, "user explicitly exited flag"),
        // —— 引导页六个状态 ——
        "ob_lang" to Spec(Effect.SAFE, "onboarding language choice"),
        "ob_theme" to Spec(Effect.SAFE, "onboarding theme choice"),
        "ob_accent" to Spec(Effect.SAFE, "onboarding accent choice"),
        "ob_orientation" to Spec(Effect.SAFE, "onboarding orientation choice"),
        "ob_priv" to Spec(Effect.SAFE, "onboarding privilege-mode choice"),
        "ob_page" to Spec(Effect.SAFE, "onboarding current page index"),
        // —— Linux 容器 ——
        "active_family" to Spec(Effect.ENGINE, "Linux rootfs distribution family; requires engine restart"),
    )

    /** 可读写的偏好文件名（不含 capabilities —— 那是权限，不归本端点管）。 */
    private val PREFS_FILES = listOf(
        "dsh_ui",           // 主 UI 偏好（主题/缩放/朝向/更新/局域网…）
        "dsh_ui_prefs",     // 配色、设置页布局与其展开状态
        "dsh_priv",         // 权限模式、引导完成标记
        "dsh_linux_rootfs", // Linux rootfs 族
        "context_cap",      // 提醒闸门的状态
        "sensitive_audit",  // 敏感访问审计
        "app_locale",       // 语言
        "reminders",        // 定时提醒
        "dsh_extensions",   // 扩展激活状态
        "asr_service",      // ASR 服务
        "cloud_asr",        // 云 ASR
        "edge_tts",         // 云 TTS
        "pet",              // 宠物
        "perm_grant",       // 权限授予记录
        "dsh_profile_plugins",
    )

    // ================= 入口 =================

    fun handle(ctx: Context, body: JSONObject): Pair<Int, String> {
        val op = body.optString("op", "get").ifBlank { "get" }
        return when (op) {
            "get", "" -> get(ctx, body)
            "list" -> listAll(ctx, body)
            "set" -> set(ctx, body)
            "reset" -> reset(ctx, body)
            "reload" -> reload(ctx)
            else -> err("unknown op: $op (get|list|set|reset|reload)")
        }
    }

    // ================= get：读单个键 =================

    private fun get(ctx: Context, body: JSONObject): Pair<Int, String> {
        val key = body.optString("key", "")
        if (key.isBlank()) return err("missing key")
        val file = body.optString("file", "").ifBlank { null } ?: findFile(ctx, key)
            ?: return err("key not found in any prefs file: $key")
        val prefs = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
        if (!prefs.contains(key)) return err("key not present: $key (in $file)")
        val spec = SPECS[key]
        return ok(
            JSONObject()
                .put("key", key)
                .put("file", file)
                .put("value", readValue(prefs, key, spec))
                .put("effect", spec?.effect?.name?.lowercase() ?: "unknown")
                .put("note", spec?.note ?: "not documented; treat as unknown effect"),
        )
    }

    // ================= list：列出全部（可按 file 过滤）=================

    private fun listAll(ctx: Context, body: JSONObject): Pair<Int, String> {
        val onlyFile = body.optString("file", "").ifBlank { null }
        val out = JSONObject()
        var total = 0
        for (file in PREFS_FILES) {
            if (onlyFile != null && file != onlyFile) continue
            val prefs = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
            val all = prefs.all
            if (all.isEmpty()) continue
            val obj = JSONObject()
            for ((k, v) in all) {
                val spec = SPECS[k]
                obj.put(
                    k,
                    JSONObject()
                        .put("value", readValue(prefs, k, spec))
                        .put("type", v?.javaClass?.simpleName ?: "null")
                        .put("effect", spec?.effect?.name?.lowercase() ?: "unknown")
                        .put("note", spec?.note ?: ""),
                )
                total++
            }
            out.put(file, obj)
        }
        return ok(
            JSONObject()
                .put("files", out)
                .put("keys_total", total)
                .put("note", "cap.* (capabilities) is not listed here - those are the user's grants to the AI; read-only via /dev/ping"),
        )
    }

    // ================= set：写 =================

    private fun set(ctx: Context, body: JSONObject): Pair<Int, String> {
        val key = body.optString("key", "")
        if (key.isBlank()) return err("missing key")
        if (!body.has("value")) return err("missing value")

        val file = body.optString("file", "").ifBlank { null }
            ?: findFile(ctx, key)
            ?: body.optString("create_in", "").ifBlank { null }
            ?: "dsh_ui"   // 新键默认落在主 UI 偏好

        if (file !in PREFS_FILES && file != "dsh_ui") {
            return err("refusing unknown prefs file: $file (allowed: ${PREFS_FILES.joinToString(",")})")
        }

        val prefs = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
        val old = if (prefs.contains(key)) readValue(prefs, key, SPECS[key]) else JSONObject.NULL
        val spec = SPECS[key]

        val (written, typeError) = writeValue(prefs, key, body.get("value"))
        if (typeError != null) return err(typeError)

        // 副作用：按等级决定要不要重建当前页面。用户选了「直接生效」。
        val recreated = when (spec?.effect) {
            Effect.REFRESH, Effect.SAFE -> CurrentActivity.recreateIfAny()
            // ENGINE / SENSITIVE 也重建：视觉类改动照样要立刻看到；
            // 引擎级改动本身需要 restart 才生效，这里不替调用方重启。
            Effect.ENGINE, Effect.SENSITIVE -> CurrentActivity.recreateIfAny()
            null -> CurrentActivity.recreateIfAny()
        }

        val needsEngineRestart = spec?.effect == Effect.ENGINE
        return ok(
            JSONObject()
                .put("key", key)
                .put("file", file)
                .put("old", old)
                .put("new", readValue(prefs, key, spec))
                .put("written", written)
                .put("effect", spec?.effect?.name?.lowercase() ?: "unknown")
                .put("page_rebuilt", recreated)
                .put(
                    "applied",
                    if (needsEngineRestart) {
                        "written; requires an engine restart to take effect (this endpoint does not restart it - that would kill running sessions)"
                    } else {
                        "written and applied"
                    },
                ),
        )
    }

    // ================= reset：删键回到默认 =================

    private fun reset(ctx: Context, body: JSONObject): Pair<Int, String> {
        val key = body.optString("key", "")
        if (key.isBlank()) return err("missing key")
        val file = body.optString("file", "").ifBlank { null } ?: findFile(ctx, key)
            ?: return err("key not found: $key")
        val prefs = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
        val old = if (prefs.contains(key)) readValue(prefs, key, SPECS[key]) else JSONObject.NULL
        prefs.edit().remove(key).apply()
        val recreated = CurrentActivity.recreateIfAny()
        return ok(
            JSONObject()
                .put("key", key)
                .put("file", file)
                .put("old", old)
                .put("removed", true)
                .put("page_rebuilt", recreated),
        )
    }

    // ================= reload：只重建页面，不改任何值 =================

    private fun reload(ctx: Context): Pair<Int, String> {
        val done = CurrentActivity.recreateIfAny()
        return ok(
            JSONObject()
                .put("page_rebuilt", done)
                .put(
                    "note",
                    if (done) "current page rebuilt" else "no foreground page (app is in background); change applies next time a page opens",
                ),
        )
    }

    // ================= 工具 =================

    private fun findFile(ctx: Context, key: String): String? {
        for (file in PREFS_FILES) {
            if (ctx.getSharedPreferences(file, Context.MODE_PRIVATE).contains(key)) return file
        }
        return null
    }

    /**
     * 读值。**密钥一律脱敏**：只回报是否已设置 + 长度，绝不回报原文。
     *
     * 这不是为了限制 AI 的能力边界（写入是允许的），而是避免把用户凭据
     * 复制进会话记录、日志或同步链路 —— 凭据该留在它原本待的地方。
     */
    private fun readValue(prefs: SharedPreferences, key: String, spec: Spec?): Any {
        if (key in SECRET_KEYS || spec?.effect == Effect.SENSITIVE && key in SECRET_KEYS) {
            val raw = prefs.all[key]
            return if (raw is String) {
                JSONObject()
                    .put("redacted", true)
                    .put("configured", raw.isNotEmpty())
                    .put("length", raw.length)
            } else {
                JSONObject().put("redacted", true).put("configured", raw != null)
            }
        }
        return when (val v = prefs.all[key]) {
            null -> JSONObject.NULL
            is Set<*> -> JSONArray(v.toList())
            else -> v
        }
    }

    /**
     * 写值，按 JSON 值类型推断偏好类型。
     *
     * 返回 `(写入成功?, 错误信息?)`。类型不支持时明确报错，不静默忽略 ——
     * 「改了没生效」比「报错」难查得多。
     */
    private fun writeValue(prefs: SharedPreferences, key: String, v: Any): Pair<Boolean, String?> {
        val e = prefs.edit()
        return when (v) {
            is Boolean -> { e.putBoolean(key, v); e.apply(); true to null }
            is Int -> { e.putInt(key, v); e.apply(); true to null }
            is Long -> { e.putLong(key, v); e.apply(); true to null }
            is Float -> { e.putFloat(key, v); e.apply(); true to null }
            is Double -> { e.putFloat(key, v.toFloat()); e.apply(); true to null }
            is String -> { e.putString(key, v); e.apply(); true to null }
            is JSONArray -> {
                val set = v.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
                e.putStringSet(key, set); e.apply(); true to null
            }
            JSONObject.NULL -> {
                // 显式传 null = 删掉这个键（回到默认）
                e.remove(key); e.apply(); true to null
            }
            is JSONObject -> false to "cannot write a JSON object as a pref value; use a string or array"
            else -> false to "unsupported value type: ${v.javaClass.simpleName}"
        }
    }

    private fun ok(o: JSONObject): Pair<Int, String> =
        (200 to o.put("ok", true).toString())

    private fun err(msg: String): Pair<Int, String> =
        (400 to JSONObject().put("ok", false).put("error", msg).toString())
}

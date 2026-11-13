package app.dsh.mobile.engine.cap

import android.content.ClipData
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.net.wifi.WifiManager
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.SensitiveAccess
import org.json.JSONObject

/** Controlled hardware operations and clipboard access. */
object ControlCap {
    fun clipboard(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.CLIPBOARD)) return denied(CapabilityGate.CLIPBOARD)
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to error("invalid json")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return 503 to error("clipboard unavailable")
        return when (obj.optString("op", "read").lowercase()) {
            "read" -> {
                CapabilityGate.audit(ctx, "dev.clipboard.read")
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                // 剪贴板里常有验证码、密码、地址 —— 事后告警只报长度不报内容。
                SensitiveAccess.record(ctx, SensitiveAccess.Kind.CLIPBOARD, text.length)
                200 to JSONObject().put("ok", true).put("text", text).toString()
            }
            "write" -> {
                if (!obj.has("text")) return 400 to error("field 'text' required")
                cm.setPrimaryClip(ClipData.newPlainText("DSH", obj.optString("text")))
                CapabilityGate.audit(ctx, "dev.clipboard.write")
                200 to JSONObject().put("ok", true).toString()
            }
            else -> 400 to error("op must be read|write")
        }
    }

    fun control(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return 400 to error("invalid json")
        val op = obj.optString("op").lowercase()
        // wifi / bt 各有独立开关：用户点名要的这两项能力必须单独可关。
        // 其余广播类操作（音量/亮度/手电/振动）统一受 control 开关约束。
        val gate = if (op == "wifi") CapabilityGate.WIFI else if (op == "bt") CapabilityGate.BT else CapabilityGate.CONTROL
        if (!CapabilityGate.can(ctx, gate)) return denied(gate)
        val result = when (op) {
            "wifi" -> wifi(ctx, obj)
            "bt" -> bluetooth(obj)
            "volume" -> volume(ctx, obj)
            "brightness" -> brightness(ctx, obj)
            "torch" -> torch(ctx, obj)
            "vibrate" -> vibrate(ctx, obj)
            "system" -> system(ctx, obj)
            else -> return 400 to error("op must be wifi|bt|volume|brightness|torch|vibrate|system")
        }
        CapabilityGate.audit(ctx, "dev.control:$op")
        return result
    }

    private fun wifi(ctx: Context, obj: JSONObject): Pair<Int, String> {
        if (obj.has("connect")) return 409 to JSONObject().put("ok", false).put("reason", "user_confirmation_required").put("hint", "Android 10+ requires WifiNetworkSpecifier confirmation").toString()
        if (!obj.has("enabled")) return 400 to error("wifi requires enabled or connect")
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            ?: return 503 to error("wifi unavailable")
        @Suppress("DEPRECATION") wm.isWifiEnabled = obj.optBoolean("enabled")
        return 200 to JSONObject().put("ok", true).put("enabled", wm.isWifiEnabled).toString()
    }

    private fun bluetooth(obj: JSONObject): Pair<Int, String> {
        if (!obj.has("enabled")) return 400 to error("bt requires enabled")
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return 404 to error("bluetooth unavailable")
        @Suppress("DEPRECATION")
        val ok = if (obj.optBoolean("enabled")) adapter.enable() else adapter.disable()
        // ⚠️ 回读真实状态而不是把请求值原样返回：adapter.enable() 只是「已提交」，
        // 厂商实现里可能返回 false，或异步生效（飞行模式、权限被拒时尤其常见）。
        // 旧实现把 obj.optBoolean("enabled") 当结果回给 AI —— 关不掉也报"已关闭"，
        // AI 据此认为操作成功，后续判断全错。
        val actual = runCatching { adapter.isEnabled }.getOrDefault(false)
        return 200 to JSONObject().put("ok", ok).put("enabled", actual).toString()
    }

    private fun volume(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val stream = when (obj.optString("stream", "music").lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return 503 to error("audio unavailable")
        val value = obj.optInt("value", -1)
        if (value < 0) return 400 to error("volume value required")
        audio.setStreamVolume(stream, value.coerceIn(0, audio.getStreamMaxVolume(stream)), 0)
        return 200 to JSONObject().put("ok", true).put("value", audio.getStreamVolume(stream)).toString()
    }

    private fun brightness(ctx: Context, obj: JSONObject): Pair<Int, String> {
        if (!Settings.System.canWrite(ctx)) return 409 to JSONObject().put("ok", false).put("reason", "write_settings_required").put("settings_action", Settings.ACTION_MANAGE_WRITE_SETTINGS).toString()
        if (!obj.has("value")) return 400 to error("brightness value required")
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, obj.optInt("value").coerceIn(1, 255))
        return 200 to JSONObject().put("ok", true).toString()
    }

    /**
     * 手电筒。
     *
     * ★此处曾把**请求值当结果**返回（obj.optBoolean("enabled") 原样回 200 ok=true）。
     * 相机被其它 App 占用时 setTorchMode 会静默失败（或抛 CameraAccessException 被吞），
     * AI 于是以为灯已经亮了。现在改成：注册一次 TorchCallback 拿到真实回读，
     * 800ms 内没等到回调就按失败报。
     */
    private fun torch(ctx: Context, obj: JSONObject): Pair<Int, String> {
        if (Build.VERSION.SDK_INT < 23) return 400 to error("torch unsupported")
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return 503 to error("camera unavailable")
        val id = cm.cameraIdList.firstOrNull {
            runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)
        } ?: return 404 to error("torch unavailable")
        if (!obj.has("enabled")) return 400 to error("torch requires enabled")
        val want = obj.optBoolean("enabled")

        // 回调只写最新状态，轮询由下面的循环做。
        // ★不能用 CountDownLatch 等「一次回调」：registerTorchCallback **注册时会立刻
        //   用当前状态回调一次**（实测：首次 on=true 读到的是变化前的 false，
        //   第二次调用才成功）。用 latch 会被这个初始回调提前释放，读到旧值。
        //   改成「回调只更新 ref + 主流程轮询到 want 或超时」。
        val actual = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) actual.set(enabled)
            }
            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == id) actual.set(null)
            }
        }
        // ★必须给一个带 Looper 的 Handler：传 null 会用"当前线程的 Looper"，
        //   而桥的请求处理线程没有 Looper，会抛
        //   "No handler given, and current thread has no looper!"（实测踩到）。
        //   用独立 HandlerThread 而不是主线程 Looper：主线程被占用时回调会延迟，
        //   而我们在这边轮询等待，用主线程只会互相拖住。
        val ht = android.os.HandlerThread("dsh-torch-cb").apply { start() }
        val handler = android.os.Handler(ht.looper)
        cm.registerTorchCallback(cb, handler)
        try {
            // 注册时的初始回调可能已经把「变化前的状态」写进 ref，先显式清掉。
            actual.set(null)
            val threw = runCatching { cm.setTorchMode(id, want) }.exceptionOrNull()
            val deadline = System.currentTimeMillis() + 1_500L
            while (actual.get() != want && System.currentTimeMillis() < deadline) {
                Thread.sleep(60L)
            }
            val got = actual.get()
            val body = JSONObject()
                .put("target", "torch")
                .put("requested", want)
                .put("enabled", got ?: false)
                .put("verified", got != null)
            return when {
                got == want -> 200 to body.put("ok", true).toString()
                // 相机被占用时系统报 unavailable：这才是真实原因，别报"已开启"
                got == null -> 409 to body.put("ok", false).put("mismatch", true)
                    .put("error", threw?.message ?: "torch unavailable (camera busy?)").toString()
                else -> 409 to body.put("ok", false).put("mismatch", true)
                    .put("error", "torch state did not change").toString()
            }
        } finally {
            runCatching { cm.unregisterTorchCallback(cb) }
            runCatching { ht.quitSafely() }
        }
    }

    private fun vibrate(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val ms = obj.optLong("ms", 200L).coerceIn(1L, 10_000L)
        val v = ctx.getSystemService(Vibrator::class.java) ?: return 503 to error("vibrator unavailable")
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(ms)
        return 200 to JSONObject().put("ok", true).put("ms", ms).toString()
    }


    // ======================= 系统开关（v1.6.x） =======================
    //
    // ## 为什么不走普通 API
    // 这六项都**没有**普通 App 能用的公开 API：
    //  · 飞行模式：Settings.Global.AIRPLANE_MODE_ON 只读；改要走系统服务的 shell 命令
    //  · 省电模式：PowerManager.setPowerSaveMode 是 @SystemApi（需要 DEVICE_POWER）
    //  · 勿扰：NotificationManager.setInterruptionFilter 要 ACCESS_NOTIFICATION_POLICY，
    //    且系统提示语是「允许此应用访问免打扰」——语义是授权，不是帮忙开关
    //  · 自动旋转 / 护眼 / 热点：同样是 Secure/System 设置或系统服务命令
    //
    // 所以统一走 settings put + cmd <service>，经 Privilege.runPrivileged
    // （Shizuku shell 身份优先，su 兜底）。没有提权时明确报 privileged_required，
    // 不假装成功。
    //
    // ## ★ 每一项都必须回读
    // 这套命令的共同毛病是「命令返回成功 ≠ 状态真的变了」：厂商 ROM 会静默忽略
    // 部分 settings put，热点 start-softap 在 shell 身份下直接 SecurityException。
    // 因此写完一律重新读一遍，把 requested 与 actual 分开回给调用方；两者不一致时
    // ok=false + mismatch=true，让 AI 知道「没生效」而不是「已完成」。
    // 这也顺带修掉了 torch / wifi-radio 把请求值当结果的旧毛病。
    //
    // ## 后端探测
    // 护眼是厂商私有的：AOSP 用 secure.night_display_activated，ColorOS/OPPO 用
    // system.oplus_customize_custom_eye_protect_enable。两个都试，回报实际命中的那个，
    // 都没命中就明确说 unsupported（不猜、不假装关成功了）。

    /** 系统开关清单：target → 写命令 / 读命令 / 解析。 */
    private fun system(ctx: Context, obj: JSONObject): Pair<Int, String> {
        val target = obj.optString("target").lowercase()
        val action = obj.optString("action", "status").lowercase()
        if (target.isBlank()) return 400 to error("system requires target")
        if (action !in setOf("status", "get", "on", "off", "toggle", "enable", "disable")) {
            return 400 to error("action must be status|on|off|toggle")
        }
        return when (target) {
            "airplane", "airplane_mode" -> airplaneMode(ctx, action)
            "battery_saver", "powersaver" -> batterySaver(ctx, action)
            "dnd", "zen", "zen_mode" -> zenMode(ctx, action)
            "rotation", "auto_rotate", "autorotate" -> autoRotate(ctx, action)
            "eye_comfort", "eyeprotect", "night_light", "reading_mode" -> eyeComfort(ctx, action)
            "hotspot", "softap", "tether" -> hotspot(ctx, action, obj)
            else -> 400 to error("target must be airplane|battery_saver|dnd|rotation|eye_comfort|hotspot")
        }
    }

    /** 提权执行；两条通道都不可用时返回 null（调用方据此报 privileged_required）。 */
    private fun privileged(ctx: Context, command: String): String? =
        app.dsh.mobile.engine.Privilege.runPrivileged(ctx, command)

    private fun noPrivilege(): Pair<Int, String> = 403 to JSONObject().put("ok", false)
        .put("error", "privileged_required")
        .put("hint", "these toggles have no public API; enable Root or Shizuku in Permission Center")
        .toString()

    /** 只读一次系统设置键；空 / 字面 null / 异常都归一成 null。 */
    private fun readSetting(ctx: Context, ns: String, key: String): String? {
        val raw = privileged(ctx, "settings get $ns $key")?.trim().orEmpty()
        if (raw.isBlank() || raw == "null") return null
        if (raw.contains("Exception", true) || raw.contains("denial", true)) return null
        return raw
    }

    private fun writeSetting(ctx: Context, ns: String, key: String, value: String): Boolean {
        val out = privileged(ctx, "settings put $ns $key $value") ?: return false
        // settings put 成功时无输出；失败会打 Exception / Permission denial
        return !out.contains("Exception", true) && !out.contains("denial", true)
    }

    /**
     * 统一的「写 → 回读 → 比对」骨架，六个开关共用。
     *
     * @param readNow 读出当前真实状态；null 表示这条通道读不出来
     * @param write 写入期望状态，返回命令本身是否成功（真值交给回读）
     */
    private fun toggle(
        target: String,
        action: String,
        readNow: () -> Boolean?,
        write: (Boolean) -> Boolean,
        // 回读预算：settings 类改动 1s 内落地，但热点启停要走 SoftAP 状态机，
        // 实测停一次超过 1.1s 才从 dumpsys 里消失（先报 mismatch 假失败，实际已关掉）。
        budgetMs: Long = 1_200L,
        extra: JSONObject.() -> Unit = {},
    ): Pair<Int, String> {
        val before = readNow()
        val want: Boolean = when (action) {
            "on", "enable" -> true
            "off", "disable" -> false
            "toggle" -> {
                val cur = before ?: return 409 to JSONObject().put("ok", false)
                    .put("error", "cannot toggle while unreadable").put("target", target).toString()
                !cur
            }
            else -> {
                if (before == null) {
                    return 503 to JSONObject().put("ok", false)
                        .put("error", "cannot read current state").put("target", target).toString()
                }
                return 200 to JSONObject().put("ok", true).put("target", target)
                    .put("enabled", before).apply(extra).toString()
            }
        }
        val wrote = write(want)
        // ★必须等一会儿再读：cmd / settings 都是异步生效，立刻读会读到旧值，
        //   然后我们就会把「刚刚改成功的」误判成 mismatch。最多等约 1.1s。
        var actual: Boolean? = null
        val deadline = System.currentTimeMillis() + budgetMs
        var wait = 120L
        while (true) {
            Thread.sleep(wait)
            actual = readNow()
            if (actual == want) break
            if (System.currentTimeMillis() >= deadline) break
            wait = 250L
        }
        val matched = (actual == want)
        val body = JSONObject()
            .put("ok", wrote && matched)
            .put("target", target)
            .put("requested", want)
            .put("enabled", actual ?: before ?: false)
            .put("verified", actual != null)
            .put("mismatch", !matched)
        if (actual == null) body.put("before", before ?: JSONObject.NULL)
        else body.put("before", before ?: JSONObject.NULL)
        if (!wrote) body.put("error", "write command failed")
        body.apply(extra)
        return (if (wrote && matched) 200 else 409) to body.toString()
    }

    private fun airplaneMode(ctx: Context, action: String): Pair<Int, String> {
        if (privileged(ctx, "cmd connectivity airplane-mode") == null) return noPrivilege()
        return toggle(
            "airplane", action,
            readNow = {
                val viaCmd = privileged(ctx, "cmd connectivity airplane-mode")?.trim()?.lowercase()
                when {
                    viaCmd == null -> null
                    viaCmd == "enabled" -> true
                    viaCmd == "disabled" -> false
                    else -> readSetting(ctx, "global", "airplane_mode_on")?.let { it == "1" }
                }
            },
            write = { want ->
                val out = privileged(ctx, "cmd connectivity airplane-mode " + (if (want) "enable" else "disable"))
                out != null && !out.contains("Exception", true)
            },
        ) { put("backend", "cmd connectivity airplane-mode") }
    }

    private fun batterySaver(ctx: Context, action: String): Pair<Int, String> {
        if (privileged(ctx, "settings get global low_power") == null) return noPrivilege()
        return toggle(
            "battery_saver", action,
            readNow = { readSetting(ctx, "global", "low_power")?.let { it == "1" } },
            // cmd power set-mode 1|0 才是真正的省电开关；settings put low_power 只是影子键，
            // 单独改它在硬件层面不生效（真机实测过）。所以写优先走 cmd，失败再退回影子键。
            write = { want ->
                val out = privileged(ctx, "cmd power set-mode " + (if (want) "1" else "0"))
                val ok = out != null && !out.contains("Exception", true)
                if (ok) true else writeSetting(ctx, "global", "low_power", if (want) "1" else "0")
            },
        ) { put("backend", "cmd power set-mode") }
    }

    private fun zenMode(ctx: Context, action: String): Pair<Int, String> {
        if (privileged(ctx, "settings get global zen_mode") == null) return noPrivilege()
        return toggle(
            "dnd", action,
            // ★别用 ringerMode 推：静音铃音 ≠ 勿扰。StatusCap 旧实现就是这么判的，
            //   用户手动调成静音时会谎报 dnd=true。这里读真正的 zen_mode。
            readNow = { readSetting(ctx, "global", "zen_mode")?.toIntOrNull()?.let { it != 0 } },
            write = { want ->
                val out = privileged(ctx, "cmd notification set_dnd " + (if (want) "on" else "off"))
                out != null && !out.contains("Exception", true)
            },
        ) {
            put("backend", "cmd notification set_dnd")
            readSetting(ctx, "global", "zen_mode")?.toIntOrNull()?.let { put("zen_mode", it) }
        }
    }

    private fun autoRotate(ctx: Context, action: String): Pair<Int, String> {
        if (privileged(ctx, "settings get system accelerometer_rotation") == null) return noPrivilege()
        return toggle(
            "rotation", action,
            readNow = { readSetting(ctx, "system", "accelerometer_rotation")?.let { it == "1" } },
            write = { want -> writeSetting(ctx, "system", "accelerometer_rotation", if (want) "1" else "0") },
        ) { put("backend", "settings system accelerometer_rotation") }
    }

    /**
     * 护眼模式：厂商私有键，两族都试。
     *
     * · AOSP / 原生：secure.night_display_activated（0/1）
     * · ColorOS / OPPO：system.oplus_customize_custom_eye_protect_enable（0/1）
     * 其他 ROM 可能两个都没有 —— 这时如实报 unsupported，不要写一个不存在的键当成功。
     */
    private fun eyeComfort(ctx: Context, action: String): Pair<Int, String> {
        val oplusKey = "oplus_customize_custom_eye_protect_enable"
        val aospKey = "night_display_activated"
        val hasOplus = readSetting(ctx, "system", oplusKey) != null
        val hasAosp = readSetting(ctx, "secure", aospKey) != null
        if (!hasOplus && !hasAosp) {
            if (privileged(ctx, "settings get system $oplusKey") == null) return noPrivilege()
            return 501 to JSONObject().put("ok", false).put("target", "eye_comfort")
                .put("error", "unsupported")
                .put("hint", "this ROM exposes neither night_display_activated nor the OPPO eye-protect key")
                .toString()
        }
        val ns = if (hasOplus) "system" else "secure"
        val key = if (hasOplus) oplusKey else aospKey
        return toggle(
            "eye_comfort", action,
            readNow = { readSetting(ctx, ns, key)?.let { it == "1" } },
            write = { want -> writeSetting(ctx, ns, key, if (want) "1" else "0") },
        ) {
            put("backend", "$ns.$key")
            // 色温也回一份：护眼「开着但色温还是默认值」时用户看不出效果
            if (hasOplus) readSetting(ctx, "system", "eyeprotect_display_cct")?.let { put("color_temp_k", it) }
        }
    }

    /**
     * 热点（SoftAP）。
     *
     * ★真机实测：cmd wifi start-softap 在 **shell 身份（uid 2000）下被拒** ——
     *   SecurityException: Uid 2000 does not have access to start-softap。
     *   所以这一项实际要求 root（runPrivileged 先试 Shizuku，失败再 su，回读会揭穿）。
     *
     * 回读用 dumpsys wifi 里 SoftApManager{ 的行数：开热点从 0 变 1，是所有候选信号里
     * 唯一跟开关同步变化的（settings 的 softap_enabled、接口表、tethering 统计都不跟着变，
     * 逐项实测确认过）。
     */
    private fun hotspot(ctx: Context, action: String, obj: JSONObject): Pair<Int, String> {
        if (privileged(ctx, "dumpsys wifi | grep -cF 'SoftApManager{'") == null) return noPrivilege()
        val ssid = obj.optString("ssid", "DSH").ifBlank { "DSH" }
        val pass = obj.optString("password").ifBlank { "" }
        val security = obj.optString("security", if (pass.length >= 8) "wpa2" else "open")
        return toggle(
            "hotspot", action,
            // ★必须带花括号 'SoftApManager{'：裸 SoftApManager 在 dumpsys 里恒有约 117 行
            //   （mAmmToReadyForChangeMap、日志记录等都含这个词），写成裸词会把"已开"读成常量真值，
            //   于是 on 永远报 before=true、off 永远报 mismatch。实测踩过一次。
            readNow = {
                privileged(ctx, "dumpsys wifi | grep -cF 'SoftApManager{'")?.trim()?.toIntOrNull()?.let { it > 0 }
            },
            write = { want ->
                val cmd = if (want) {
                    if (security == "open") "cmd wifi start-softap $ssid open"
                    else "cmd wifi start-softap $ssid $security $pass"
                } else {
                    "cmd wifi stop-softap"
                }
                val out = privileged(ctx, cmd)
                // shell 身份会被 SecurityException 挡住；这里不吞掉，交给回读判定
                out != null && !out.contains("SecurityException", true) &&
                    !out.contains("Exception occurred", true)
            },
            // SoftAP 启停是系统状态机，实测 stop 后要 1.5s+ 才从 dumpsys 消失
            budgetMs = 6_000L,
        ) {
            put("backend", "cmd wifi start-softap")
            put("ssid", ssid)
            put("security", security)
            put("needs_root", true)
        }
    }
    private fun denied(capability: String): Pair<Int, String> = 403 to JSONObject().put("ok", false).put("error", "capability disabled").put("capability", capability).toString()
    private fun error(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
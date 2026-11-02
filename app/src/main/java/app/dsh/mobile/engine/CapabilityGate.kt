package app.dsh.mobile.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * Single source of truth for device capabilities.
 * A capability is usable only when its explicit user switch is on and the
 * platform permission/special access it needs is currently available.
 */
object CapabilityGate {
    private const val PREFS = "capabilities"
    private const val KEY_PREFIX = "cap."

    const val STATUS = "status"
    const val SENSOR = "sensor"
    const val NOTIFY = "notify"
    const val NOTIFY_READ = "notify_read"

    /**
     * 代用户回复他人通知。
     *
     * 与 [NOTIFY_READ] 分开是刻意的：读通知只是「看」，回复是**以用户身份对外发言**，
     * 后果不可撤回。愿意让 AI 读消息的用户未必愿意让它替自己回微信，所以这是第三个开关。
     */
    const val NOTIFY_ACTION = "notify_action"
    const val CLIPBOARD = "clipboard"
    const val CONTROL = "control"
    const val WIFI = "wifi"
    const val BT = "bt"
    const val SCHED = "sched"

    /** 媒体播放控制（MediaSession：播放/暂停/上一首/下一首/进度）。 */
    const val MEDIA = "media"

    /**
     * 个人信息 —— 曾经是**一个**开关（v1.4.78 的设计），现已拆成四项。
     *
     * 旧设计把日历、联系人、短信、通话捆绑在一起：用户想让它读日程，就得连带
     * 把短信和通话记录一起打开。按 v1.5.0-beta.12 的要求改成逐项授权 ——
     * 「愿意让 AI 看日程」和「愿意让 AI 看短信」是两件不同的事，捆绑等于逼用户
     * 为了一件事卖掉全部。
     *
     * ⚠️ 旧的 `cap.personal` 值**不迁移**：历史上打开过总开关的用户必须重新
     * 逐项确认。这是刻意的 —— 敏感数据的授权应当由人重新做出，而不是被旧状态
     * 批量代劳。
     */
    const val PERSONAL_CALENDAR = "personal_calendar"
    const val PERSONAL_CONTACTS = "personal_contacts"
    const val PERSONAL_SMS = "personal_sms"
    const val PERSONAL_CALLS = "personal_calls"

    /** 四项个人数据开关的完整清单（顺序即 UI 顺序）。 */
    val ALL_PERSONAL = listOf(PERSONAL_CALENDAR, PERSONAL_CONTACTS, PERSONAL_SMS, PERSONAL_CALLS)

    /**
     * @deprecated 已被四项细开关取代。仅保留常量供旧代码编译，运行时不再读取。
     */
    @Deprecated("Split into PERSONAL_CALENDAR / CONTACTS / SMS / CALLS")
    const val PERSONAL = "personal"

    /**
     * NFC 与地理围栏。
     *
     * 与 [BT] 分开：蓝牙是「和附近设备通信」，NFC 是「贴一下」的身份/门禁域，
     * 地理围栏则是位置相关的持续后台动作 —— 用户对这三者的心理预期完全不同。
     */
    const val RF = "rf"

    /** 屏幕录制（MediaProjection，每次启动必须用户点头）。 */
    const val SCREEN_CAPTURE = "screen_capture"

    /**
     * 虚拟 HID 设备（`/dev/uhid`）。
     *
     * 为什么单独一个开关：这条能力不是「模拟点击」，而是**在系统里注册一个
     * 真实的外部输入设备**（`Classes: KEYBOARD | EXTERNAL`）。被注册的设备
     * 会长期存在于 `dumpsys input` 里，AI 可以往它灌任意键值 —— 系统和其他
     * App 无法把它与真外接键盘区分开。
     *
     * 正因为它「太像真的」，默认关闭、必须显式打开：打开后 AI 能发出的按键
     * 与用户亲手按的等价（包括音量键、媒体键、甚至组合键）。
     *
     * ⚠️ 需要 root（`/dev/uhid` 权限位 `crw-rw---- uhid uhid`，普通 App 与
     * shell 都打不开，实测 shell 组里虽有 `3011(uhid)` 仍被 SELinux 拒绝）。
     */
    const val HID = "hid"

    /**
     * 按 App（UID）的网络控制与流量查询。
     *
     * 为什么要单独一个开关：这是唯一一个能**把别的 App 的网彻底切断**的能力
     * （`iptables -m owner --uid-owner`）。它既可能用来「帮我把某 App 断网」，
     * 也可能被用来做隐蔽的阻断 —— 所以与 WiFi/蓝牙分开授权，
     * 且默认关闭；封锁成功还要靠 iptables 计数器回读验证。
     *
     * 查询流量（B 档）不需要 root；封锁（C 档）需要。
     */
    const val NET = "net"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Explicit user opt-in. Every switch defaults to false. */
    fun enabled(ctx: Context, name: String): Boolean =
        prefs(ctx).getBoolean(KEY_PREFIX + name, false)

    fun setEnabled(ctx: Context, name: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PREFIX + name, enabled).apply()
        audit(ctx, "cap.$name=${if (enabled) "enabled" else "disabled"}")
    }

    /** Record an invocation using the existing LastAction persistence pattern. */
    fun audit(ctx: Context, action: String) = LastAction.record(ctx, "capability:$action")

    /** True only when the switch and platform requirement both pass. */
    fun can(ctx: Context, name: String): Boolean =
        enabled(ctx, name) && permissionAvailable(ctx, name)

    /**
     * 能力开关总览。
     *
     * ⚠️ 必须返回 JSONObject 而不是 Map：Android 的 org.json 在序列化未知类型时
     * 抛 `Illegal object in JSONStringer`，直接 put(Map) 会让 /dev/ping 变成 500，
     * 三层链路「看起来通、实际全废」。
     */
    fun snapshot(ctx: Context): JSONObject = JSONObject()
        .put(STATUS, can(ctx, STATUS))
        .put(SENSOR, can(ctx, SENSOR))
        .put(NOTIFY, can(ctx, NOTIFY))
        .put(NOTIFY_READ, can(ctx, NOTIFY_READ))
        .put(NOTIFY_ACTION, can(ctx, NOTIFY_ACTION))
        .put(CLIPBOARD, can(ctx, CLIPBOARD))
        .put(CONTROL, can(ctx, CONTROL))
        .put(WIFI, can(ctx, WIFI))
        .put(BT, can(ctx, BT))
        .put(SCHED, can(ctx, SCHED))
        .put(MEDIA, can(ctx, MEDIA))
        .put(PERSONAL_CALENDAR, can(ctx, PERSONAL_CALENDAR))
        .put(PERSONAL_CONTACTS, can(ctx, PERSONAL_CONTACTS))
        .put(PERSONAL_SMS, can(ctx, PERSONAL_SMS))
        .put(PERSONAL_CALLS, can(ctx, PERSONAL_CALLS))
        .put(RF, can(ctx, RF))
        .put(SCREEN_CAPTURE, can(ctx, SCREEN_CAPTURE))
        .put(HID, can(ctx, HID))
        .put(NET, can(ctx, NET))

    /** Platform truth, independent from the user switch. */
    fun permissionAvailable(ctx: Context, name: String): Boolean = when (name) {
        NOTIFY -> Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        WIFI -> ctx.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        BT -> Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        NOTIFY_READ, NOTIFY_ACTION -> notificationListenerEnabled(ctx)
        // MediaSession.getActiveSessions 要 MEDIA_CONTENT_CONTROL（系统权限，拿不到），
        // 实际路径是「通知监听权限 → 从通知里取 MediaController」。所以判定同上。
        MEDIA -> notificationListenerEnabled(ctx)
        // 个人数据的四项各自独立判定：用户只授了日历就只开放日历，
        // 未授的项在打开对应开关时会被拒绝（而不是整体置灰）。
        PERSONAL_CALENDAR -> granted(ctx, Manifest.permission.READ_CALENDAR)
        PERSONAL_CONTACTS -> granted(ctx, Manifest.permission.READ_CONTACTS)
        PERSONAL_SMS -> granted(ctx, Manifest.permission.READ_SMS)
        PERSONAL_CALLS -> granted(ctx, Manifest.permission.READ_CALL_LOG)
        // NFC 硬件存在 + 系统里 NFC 已打开，两者都成立才算可用：
        // 应用无法在 Android 10+ 静默打开 NFC，所以硬件和开关要分别判，缺哪个就报哪个。
        RF -> nfcUsable(ctx) || locationUsable(ctx)
        SCREEN_CAPTURE -> Build.VERSION.SDK_INT >= 21
        // ⚠️ 这里**不能**判 uhidWritable()：本类跑在 App 进程（u0_a488），
        // 而 /dev/uhid 是 crw-rw---- uhid uhid —— App 进程永远打不开，
        // 判了会让开关永久置灰。真正的写入由引擎侧门脚本 `hid` 完成
        // （引擎进程 uid 0 + u:r:ksu:s0，实测可写）。
        // 所以这里只判「系统是否有这个设备节点」，写权限交给执行侧报错。
        HID -> Build.VERSION.SDK_INT >= 26 && java.io.File("/dev/uhid").exists()
        // 这里一律返回 true，理由：本能力有两档，硬判任一项都会误置灰另一边。
        //   · list/status 要 Usage Access —— 没有时 NetCap.list 会明确报
        //     "need Usage Access (PACKAGE_USAGE_STATS)"，不是静默失败；
        //   · block/unblock 要 root —— 没有时 iptables 调用直接报 403。
        // 若在这里判 root，没 root 的设备连「看流量」（B 档）都会被一起关掉。
        NET -> true
        CONTROL, STATUS, SENSOR, CLIPBOARD, SCHED -> true
        else -> false
    }

    private fun nfcUsable(ctx: Context): Boolean = runCatching {
        android.nfc.NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true
    }.getOrDefault(false)

    /**
     * `/dev/uhid` 是否当前可写。
     *
     * 不能只看「文件存不存在」——普通 App 也能 stat 到它，但 open() 会
     * `EACCES`。这里做一次真实的 open，能打开就关掉，用副作用当证据
     * （而不是靠 uid 猜）。
     */

    private fun locationUsable(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun granted(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun notificationListenerEnabled(ctx: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        // 每一项是完整组件名（`pkg/pkg.engine.cap.DeviceNotificationListener`），
        // 只比 `/` 之前的包名：直接跟 packageName 做 equals 永远不成立，
        // 会让「读取通知」开关永久置灰、用户永远点不动。
        enabled.split(':').any {
            it.substringBefore('/').trim().equals(ctx.packageName, ignoreCase = true)
        }
    }.getOrDefault(false)
}

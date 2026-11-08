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

    /**
     * 系统级操作（进程 / 文件 / 属性 / 服务 / 包管理 / 任意 shell）。
     *
     * 为什么单独一个开关且默认关闭：这是本项目里**权限最大的一档** —— 它能
     * 杀任意进程、读写任意文件、改系统属性、卸载任何 App、以 uid 0 执行任意
     * 命令。它不局限于「某个功能」，而是把整台机器的控制权交出去。
     *
     * 与 HID 的区别：HID 是「AI 假装成一只键盘」，本开关是「AI 拿到 root shell」。
     *
     * ⚠️ 需要 ROOT 权限模式。判定读 PrivMode（`Privilege.getMode(ctx) == ROOT`），
     * 不探测 /proc 或 su —— 门脚本 `root` 最终会在引擎进程里自查 uid，
     * 这里判模式是为了让开关在非 root 时如实置灰。
     */
    const val ROOT = "root"

    /**
     * 读取**其他 App 的私有数据**（`root appdata`）。
     *
     * 与 [ROOT] 分开授权，理由：`root` 是「能操作系统」，`appdata` 是「能看别人
     * 存在磁盘里的东西」—— 前者是控制权，后者是隐私边界，量级完全不同。用户
     * 可能愿意让 AI 帮他还原系统设置，但不愿意让它翻聊天数据库。
     *
     * 因此本开关：
     * · 独立于 [ROOT]，两者都开才真的能读（门脚本里 appdata 先 need_root）；
     * · 默认关闭（不在默认表里 → 读不到即为关）；
     * · 每次成功读取都经 [SensitiveAccess] 留通知告警。
     */
    const val APPDATA = "appdata"

    /**
     * 输入框文本变化事件流（`TYPE_VIEW_TEXT_CHANGED`）。
     *
     * ⚠️ 这是本项目里**最接近键盘记录**的一项：它能看到用户在任何一个 App 的
     * 输入框里敲了什么（密码框被系统屏蔽，`event.isPassword` 为真时我们不记）。
     *
     * 因此三件事必须同时成立：独立开关（本常量）· 默认关闭 · 每次读取经
     * [SensitiveAccess] 留通知告警。缺任何一条都不该开这个能力。
     */
    const val INPUTWATCH = "inputwatch"

    /**
     * 物理按键事件流（`FLAG_REQUEST_FILTER_KEY_EVENTS` + `onKeyEvent`）。
     *
     * 与 [INPUTWATCH] 分开授权：按键流只知道「按了音量下」，不知道输入内容；
     * 两者隐私量级差一个数量级，合成一个开关会让用户为了快捷键被迫交出输入内容。
     *
     * 注意 `onKeyEvent` 一律 `return false`（不消费），所以即使开启也不影响
     * 用户正常按键 —— 它只观察，不拦截。
     */
    const val KEYWATCH = "keywatch"

    /**
     * Shizuku / ADB shell 通道（`shz` 门脚本 → `/dev/shz`）。
     *
     * 与 [ROOT] 的关系：两者**互斥但不等价**。ROOT 模式下引擎自己就是 uid 0，
     * 走门脚本直连；Shizuku 模式下引擎还是 app uid，必须经桥把命令转给 App
     * 进程的 binder，再以 adb shell（uid 2000 / `u:r:shell:s0`）执行。
     *
     * 权限档位也不同（2026-11-08 实测）：shell 能读别的 App 的 databases 与
     * shared_prefs、能 uiautomator dump、能 input，但**改不了 iptables**、
     * 写不了 SELinux。所以它是「半只脚在系统里」，不是 root 的替代品。
     */
    const val SHIZUKU = "shizuku"

    /**
     * 当前地理位置查询（`/dev/location`）。
     *
     * 与 [RF] 分开：`RF` 覆盖的是 NFC 与地理围栏，那两个都是**被动触发**
     * （贴一下 / 进出一个圈），不会在任意时刻回报「你在哪」。而精确位置能
     * 推断出住址、工作地、就医记录 —— 用户愿意让 AI「到公司提醒我」，
     * 不代表愿意让它随时知道自己在哪。所以这是独立开关。
     */
    const val LOCATION = "location"

    /**
     * App 包管理（`/dev/packages`）：列出 / 查询 / **安装 / 卸载**。
     *
     * 为何需要开关：`list`/`info` 只是读，但 `install` 是给这台机器新增一个
     * 长期运行的组件 —— 等于给 AI 一个持久的落脚点。所以整组受一个开关，
     * 且 install/uninstall 内部**仍然强制走系统确认页**（不提供静默安装）。
     */
    const val PACKAGES = "packages"

    /**
     * 拍照（`/dev/camera`）。
     *
     * 与 [SENSOR] 分开：传感器读到的是「环境光 320 lux」，相机拍到的是
     * 房间、屏幕、他人。量级完全不同。与录音能力（`dev/record`）也分开 ——
     * 愿意让 AI 听和愿意让 AI 看是两件事。
     */
    const val CAMERA = "camera"


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
        .put(ROOT, can(ctx, ROOT))
        .put(APPDATA, can(ctx, APPDATA))
        .put(INPUTWATCH, can(ctx, INPUTWATCH))
        .put(KEYWATCH, can(ctx, KEYWATCH))
        .put(SHIZUKU, can(ctx, SHIZUKU))
        .put(LOCATION, can(ctx, LOCATION))
        .put(PACKAGES, can(ctx, PACKAGES))
        .put(CAMERA, can(ctx, CAMERA))

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
        // 这里判的是「当前是否处于 ROOT 权限模式」，不是「能不能拿到 root」：
        // 非 ROOT 模式下 engine/bin/su 被拒绝遮罩挡住，门脚本 `root`
        // 里的 need_root 也会以真实 uid 报错，两层一致。
        ROOT -> Privilege.getMode(ctx) == PrivMode.ROOT
        // 与 [ROOT] 同一档：没有 root 就根本没有读别的 App 私有数据的通道。
        // 但**有 root 不等于允许读** —— 这是独立的隐私开关，用户要单独打勾。
        APPDATA -> Privilege.getMode(ctx) == PrivMode.ROOT
        // Shizuku 只看「server 在跑 + 本 app 已授权」，与 ROOT 模式互不依赖：
        // 有 root 的用户也可能选择用 Shizuku（不想让引擎以 uid 0 常驻）。
        SHIZUKU -> Privilege.shizukuUsable()
        // 位置：硬件/系统开关即便没开也允许读取（返回 location_enabled=false 让
        // 调用方知道原因），只有**权限**缺失才置灰 —— 否则用户关了系统定位就
        // 再也点不亮这个开关，而他要的可能只是「先授权，等我开了再用」。
        LOCATION -> granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        // 包管理一律 true，与 [NET] 同理：这一组里 list/info 只要开关就能用，
        // 而 install 额外需要「安装未知应用」授权 —— 缺权时 PackageCap.install
        // 会明确返回 403 并给出开关指引，不是静默失败。
        // 若在这里判 canRequestPackageInstalls()，没授权的设备连「看看装了什么」
        // 都会被一起关掉，属于误置灰。
        PACKAGES -> true
        // 相机：只判运行时权限。硬件是否存在留给 CameraCap 报（它要列出真实 id 列表）。
        CAMERA -> granted(ctx, Manifest.permission.CAMERA)
        // 事件流两项一律 true：它们的真正前置条件是「无障碍服务在跑」与
        // 「该 ROM 是否支持 FILTER_KEY_EVENTS」，硬判任一项都会把开关误置灰
        // （用户可能只是想先把开关打开，等无障碍连上就用）。
        // 缺条件时 /dev/watch 会明确回报 "accessibility service is not running"，
        // 不是静默失败。
        INPUTWATCH, KEYWATCH -> true
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

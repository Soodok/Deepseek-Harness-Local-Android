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

    /**
     * `linuxReady` 的缓存有效期。
     *
     * 30s 是折中：部署/切换会显式 [invalidateLinuxReady]（立即生效），
     * 而 `/dev/ping` 这类每次请求都调 [snapshot] 的路径不会每轮都去 stat 5 个 rootfs。
     * 与 publicFilesRoot 用的是同一个量级。
     */
    private const val LINUX_READY_TTL_MS = 30_000L

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
     * Android 12+ 拆分出来的三个蓝牙运行时权限。
     *
     * 为什么必须显式列出来：API 31 起 `BLUETOOTH`/`BLUETOOTH_ADMIN` 变成「装了就有」
     * 的旧权限，真正的门是这三个 —— 只判旧权限会让 [BT] 在真机上恒亮，而
     * `/dev/bt {"op":"status"}` 直接 500 `Need android.permission.BLUETOOTH_CONNECT`。
     * 判定顺序即权限中心申请顺序（CONNECT 是基础，SCAN/ADVERTISE 各自管扫描与广播）。
     */
    val BT_RUNTIME_PERMS = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )

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
     * 虚拟屏（`vscreen` 门脚本 → `$DSH_HOME/vscreen/` 核心进程）。
     *
     * 用途：给 AI 开一块**不显示在物理屏上**的第二块屏，把任意 App 启到那里
     * 去操作 —— AI 能看能点，而用户手上的真实屏幕照常用，互不打扰。
     *
     * 为何单独一个开关：它比 [SCREEN_CAPTURE] 强得多。录屏只是「看」，虚拟屏是
     * 「另开一台手机」—— 可以在后台登录账号、走完注册流程、点掉别人的确认框，
     * 全程用户看不见。所以必须由用户显式授权，且默认关闭。
     *
     * 前置：只有 ROOT 模式可用。建屏要 `createVirtualDisplay`，跨屏启非导出
     * Activity 要 uid 0（Shizuku 的 shell uid 也能建屏，但拿不到 TRUSTED flag，
     * 且 `am start --display` 起非导出组件会被拒）。核心进程由 `app_process`
     * 以 uid 0 拉起（实测 `RuntimeInit uid 0`）。
     */
    const val VSCREEN = "vscreen"

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

    /**
     * Linux 容器（`/dev/linux/…`）：状态 / 发行版列表 / 切换 / 部署 / **执行命令**。
     *
     * 为什么整组受一个开关、且默认关闭：
     * - `exec` 是**在容器里跑任意命令**，能力上等价于给 AI 一个 shell。
     *   虽然容器本身受 seccomp 沙箱约束（不能碰宿主文件），但它能装包、
     *   能联网、能编东西、能长期驻留进程 —— 与「读一下电量」不是一个量级。
     * - `switch`/`deploy` 会**改变用户环境**：切发行版之后用户下一次打开
     *   Linux 工作台看到的是另一个系统，装过的东西「不见了」（其实在另一个
     *   目录里）。这属于可逆但会让人困惑的操作。
     *
     * 与之相对：`status`/`distros` 只是读。**但它们也挂在同一个开关下** ——
     * 理由是一致性：让用户只需要理解「Linux 能力开/关」一件事，而不是
     * 「读可以写不行」两套规则。关闭时 AI 完全看不到容器，也不会有
     * 「为什么我只能看到状态不能操作」的半残状态。
     */
    const val LINUX = "linux"


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
        .put(VSCREEN, can(ctx, VSCREEN))
        .put(LOCATION, can(ctx, LOCATION))
        .put(PACKAGES, can(ctx, PACKAGES))
        .put(CAMERA, can(ctx, CAMERA))
        .put(LINUX, can(ctx, LINUX))
        // 与 LINUX 并列的**可用性**信号（v1.7.8）：授权归授权，部署归部署。
        // 权限中心的开关只认 LINUX；这个字段供 UI/AI 区分「没授权」与「还没下载」
        // —— 这两种情况此前的界面文案完全一样（都是「待授权」），用户照着提示去
        // 系统设置里翻了一圈也找不到东西，因为那里根本没有这项。
        .put("linux_ready", linuxReady(ctx))

    /** Platform truth, independent from the user switch. */
    fun permissionAvailable(ctx: Context, name: String): Boolean = when (name) {
        NOTIFY -> Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        WIFI -> ctx.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        // v1.7.10 修正（真机实测：开关亮、调啥都 500）：
        // 旧判据是 SDK_INT<31 || granted(android.permission.BLUETOOTH)。API 31+ 的
        // `BLUETOOTH` 是**自动授予**的旧权限，所以这个表达式在 31+ 恒为 true ——
        // 而 /dev/bt 真正要的是 CONNECT / SCAN / ADVERTISE 三个运行时权限
        // （实测 grep: "Need android.permission.BLUETOOTH_CONNECT ... getName"）。
        // 判据改成三者中至少有一个真实授予：一个都没给时开关必须置灰，
        // 用户在权限中心点它就能当场弹系统授权框（见 PermissionCenterActivity.requestCapPerm）。
        BT -> Build.VERSION.SDK_INT < 31 || BT_RUNTIME_PERMS.any { granted(ctx, it) }
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
        // NFC 能用 或 位置权限已给（地理围栏只依赖后者）。
        // ⚠️ 这个开关管着**两类**端点：/dev/nfc（要 NFC 硬件开着）与 /dev/geofence
        // （要 ACCESS_FINE_LOCATION）。两者任一可用就允许打开开关；具体那一类没条件时
        // 由各自端点返回 403 并说明缺什么 —— 总开关不代它们做更严的判定。
        RF -> nfcUsable(ctx) || locationUsable(ctx)
        SCREEN_CAPTURE -> Build.VERSION.SDK_INT >= 21
        // ⚠️ 这里**不能**判 uhidWritable()：本类跑在 App 进程（u0_a488），
        // 而 /dev/uhid 是 crw-rw---- uhid uhid —— App 进程永远打不开，
        // 判了会让开关永久置灰。真正的写入由引擎侧门脚本 `hid` 完成
        // （引擎进程 uid 0 + u:r:ksu:s0，实测可写）。
        // 所以这里只判「系统是否有这个设备节点」，写权限交给执行侧报错。
        // v1.7.10 修正：除了「节点存在」，还要求**真有 root 通道**。
        // 上一版只判节点存在，于是在 Shizuku 模式下开关是亮的、一调就 503
        // （/dev/uhid 是 crw-rw---- uhid uhid，shell uid 2000 也打不开；实测走
        //  Shizuku 必然 uhid_open_failed）。这里判「有 root 线索」而不是「当前是
        // ROOT 模式」：HidCap 现在优先用 su（见 HidCap.runAsRoot），所以只要有 su
        // 就能跑；真跑不起来时端点会如实报 uhid_open_failed，不是静默失败。
        HID -> Build.VERSION.SDK_INT >= 26 &&
            java.io.File("/dev/uhid").exists() &&
            (Privilege.getMode(ctx) == PrivMode.ROOT || Privilege.rootAvailableMinimal())
        // 两档前置：list/status 要 Usage Access，block/unblock 要 root。
        // v1.7.6：任一满足即视为可用 —— 只判 root 会把「只想看流量」的用户一起关掉，
        // 只判 Usage Access 会让有 root 但没给使用情况访问的用户用不了封锁。
        // 两者都缺才置灰（此时确实两档都做不了，标「待授权」是诚实的）。
        NET -> usageAccessAllowed(ctx) || Privilege.getMode(ctx) == PrivMode.ROOT
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
        // 虚拟屏只有 ROOT 挡可用：要 createVirtualDisplay 带 TRUSTED flag，
        // 还要以 uid 0 跑 app_process 起核心。非 ROOT 模式下门脚本 vscreen
        // 会如实报 VS_SCREEN_NEED_ROOT，不是静默失败。
        VSCREEN -> Privilege.getMode(ctx) == PrivMode.ROOT
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
        // 事件流的真实前置是**无障碍服务在跑**（v1.7.6，用户报「很多选项即使权限没授予
        // 或现在不可用仍然能被允许，这会误导」）：这两项读的是系统派发给无障碍服务的
        // 事件流，服务没连上就一条数据都不会有，开关点亮也是空的 → 按真实前置置灰，
        // 服务连上后 onResume 重绘，开关自动恢复可点。
        // （KEYWATCH 还另有「该 ROM 是否支持 FILTER_KEY_EVENTS」这一层，但它不可探知，
        // 硬判只会把开关永久误置灰；真不支持时 /dev/watch 会回报空流。）
        INPUTWATCH, KEYWATCH -> accessibilityReady()
        // CONTROL **不**判无障碍（v1.7.8 复查修正）。
        //
        // v1.7.6 为了「不可用的不该能点」把 CONTROL 也挂到 accessibilityReady() 上，
        // 但它底下的端点绝大多数与无障碍无关：音量/亮度/手电/振动（ControlCap）、
        // 摩斯振动与 Doze 白名单（PulseCap）、pm grant/revoke（PermCap）。
        // 真需要无障碍的只有 /dev/dialog 与 /dev/hand，而这两个**自己**会在服务
        // 未连接时返回 503「accessibility service not enabled」—— 前置条件由它们
        // 各自声明，不该由 CONTROL 这个总开关代为把关。
        //
        // 挂在 CONTROL 上造成了真自锁：无障碍没开 → CONTROL 不可用 → POST /dev/perms
        // 403 → AI 拿不到 WRITE_SECURE_SETTINGS → 也就无法自己把无障碍加进启用列表。
        // 唯一的出路是用户亲手去系统设置点，而 AI 却宣称「我能帮你配权限」。
        CONTROL -> true
        // STATUS 只有前台应用那一项依赖 Usage Access，其余（电量/内存/网络）不需要 ——
        // 所以它不作为开关的硬门槛，否则「看电量」也会被一起关掉。
        STATUS -> true
        // 传感器与剪贴板没有系统权限门槛（前者是硬件，后者是 API 30+ 的系统复制提示），
        // 判了只会误置灰。
        SENSOR, CLIPBOARD -> true
        // 调度分两档：普通闹钟人人可用，**精确**闹钟在 Android 12+ 需要单独授权。
        // 这里只判「精确闹钟能不能用」——没有它 setExactAndAllowWhileIdle 会退化成
        // 不精确触发（可能晚几十分钟），对「提醒我十分钟后」这种用法等于不可用。
        //
        // ⚠️ 现状（v1.7.8 真机核过 OnePlus PLZ110 / Android 16）：本包 targetSdk=28
        // 且清单里同时声明了 SCHEDULE_EXACT_ALARM 与 USE_EXACT_ALARM，实测
        // `dumpsys package` 显示 USE_EXACT_ALARM granted=true、SCHEDULE_EXACT_ALARM
        // 的 appop 是 default（targetSdk<31 时系统按「已授权」处理），
        // 于是 canScheduleExactAlarms() 恒为 true —— **这个门在当前 targetSdk 下
        // 不会真的拦住任何请求**，/dev/ping 的 sched 也一直是 true。
        // 保留它不是为了现在生效，而是：一旦为了别的目标抬 targetSdk，系统会开始
        // 要求用户单独授权，那时这条门才第一次有意义（届时若误判成 403 会把
        // 普通闹钟一起关掉，所以要按「两档任一」的方式改，与 [NET] 同型）。
        // 与 [exactAlarmUsable] 同义：拿不到精确闹钟授权时 setExactAndAllowWhileIdle 会
        // 抛 SecurityException（不是「退化」），所以缺权时必须置灰并引导授权。
        // VERSION.SDK_INT < 31 时恒 true（旧系统没有这个概念）。
        SCHED -> exactAlarmUsable(ctx)
        // Linux 容器**没有系统权限门槛** —— 容器靠 seccomp，不需要任何平台授权。
        // v1.7.6 曾改成判「至少有一个 rootfs 已部署」，本意是「不可用的不该能选」，
        // 但那一版把「可用性」塞进了「授权」的语义里，造成两个真问题（v1.7.8 复查）：
        //   ① 没部署时开关被置灰 → 用户打不开 → AI 拿到 enabled=false → 连
        //      POST /dev/linux/deploy 都 403，于是**部署这件事只能靠用户手点 UI**，
        //      AI 被永久锁在门外（自锁：要能部署得先已部署）；
        //   ② 权限中心把「还没下载」显示成「待授权」，用户照提示去系统设置里找，
        //      永远找不到 —— 那是两个完全不同的失败原因。
        // 现在：授权只看用户开关；部署状态由 [linuxReady] 单独暴露给 UI 作**提示**，
        // 不参与 403 判定。exec 走到未部署的容器时自己会返回 409 + 「先 deploy」。
        LINUX -> true
        else -> false
    }

    /** 无障碍服务是否真的连上了（不是「在系统设置里勾了」）。 */
    private fun accessibilityReady(): Boolean = runCatching {
        app.dsh.mobile.DshAccessibilityService.isEnabled()
    }.getOrDefault(false)

    /** 是否已授予「使用情况访问」（读别的 App 流量统计的唯一合法途径）。 */
    private fun usageAccessAllowed(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ctx.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ctx.packageName,
            )
        }
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** 精确闹钟是否可用（Android 12+ 需要 SCHEDULE_EXACT_ALARM 特批）。 */
    private fun exactAlarmUsable(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 31) true
        else ctx.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }.getOrDefault(false)

    /**
     * 是否至少有一个 Linux rootfs 已部署完成（有 distro 才能真正起容器）。
     *
     * 这是**可用性**，不是权限 —— 因此不再参与 `permissionAvailable` 的授权判定，
     * 只作为独立信号供权限中心显示「还没下载」与 AI 判断该不该先 deploy。
     *
     * 成本：5 个候选各一次 stat + 一次 16 字节读（`flavorPackagesReady` 里的 mark 文件），
     * 约 10 次文件操作。`/dev/ping` 每次都会走 [snapshot]，所以带 30s TTL ——
     * 与 issue #58（插件中心反复解析目录导致 ANR）同一类问题。
     */
    @Volatile private var linuxReadyCache: Pair<Long, Boolean>? = null

    fun linuxReady(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        linuxReadyCache?.let { (at, v) -> if (now - at < LINUX_READY_TTL_MS) return v }
        val v = linuxRootfsDeployed(ctx)
        linuxReadyCache = now to v
        return v
    }

    /** 部署/切换后主动失效，别让 UI 等满一个 TTL 才亮起来。 */
    fun invalidateLinuxReady() {
        linuxReadyCache = null
    }

    private fun linuxRootfsDeployed(ctx: Context): Boolean = runCatching {
        val env = LinuxEnvironmentManager.get(ctx)
        // 直接调 env 的判定（不走 linuxReady），避免在计算过程中递归命中缓存。
        LinuxRootfsCatalog.options.any { env.isDeployed(it) && env.flavorPackagesReady(it) }
    }.getOrDefault(false)

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

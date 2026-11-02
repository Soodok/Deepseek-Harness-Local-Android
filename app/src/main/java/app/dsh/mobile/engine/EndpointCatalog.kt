package app.dsh.mobile.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桥端点的**自描述目录**（v1.5.0-beta.11）。
 *
 * ## 为什么要有它
 * 2026-11-02 用户提出：「直接做通用入口，有通用入口之后啥都能执行」。
 *
 * 此前的问题是三处重复：Kotlin 里一个端点、`assets/gates/` 里一个门脚本、
 * `AgentContextSeed.kt` 里一段提示词。漏掉任何一处，能力就等于不存在
 * （实测：29 个 /dev 端点里 10 个根本没门脚本，AI 不知道它们存在）。
 *
 * 现在改成**单一数据源**：端点、参数、示例、限制都写在这里，
 * `GET /dev/help` 把它吐成 JSON，门脚本 `dev` 拉下来自己渲染。
 * 新增端点只要在 [ENTRIES] 里登记一次，`dev` 立刻能发现、能调、能显示用法。
 *
 * ## 与 Route 表的关系
 * 这里是**元数据**，不是实现。真正的 handler 仍在 `AgentBridge.routes`。
 * [verifyAgainst] 会在桥启动时逐条核对：目录里写的路径必须在路由表里真的存在，
 * 反过来路由表里的 /dev 端点若没登记会打日志 —— 防止两边漂移。
 */
object EndpointCatalog {

    /** 一条端点的描述。 */
    private data class Entry(
        val id: String,
        val method: String,
        val path: String,
        val cap: String?,        // 需要开启的能力开关（CapabilityGate 常量名），null = 无门
        val perm: String?,       // 需要授予的系统权限（人话说明），null = 无
        val summary: String,
        val params: String?,     // 参数说明（多行）
        val body: String?,       // 请求体示例（原样 JSON）
        val examples: List<String>,
        val limits: String?,     // 已知限制 / 做不到的事
    )

    private fun e(
        id: String,
        method: String,
        path: String,
        summary: String,
        cap: String? = null,
        perm: String? = null,
        params: String? = null,
        body: String? = null,
        examples: List<String> = emptyList(),
        limits: String? = null,
    ) = Entry(id, method, path, cap, perm, summary, params, body, examples, limits)

    /**
     * 全部端点。分组顺序即 `dev` 的展示顺序。
     *
     * ⚠️ 维护约定：改端点实现时必须同步改这里；`verifyAgainst()` 会在启动时兜底。
     */
    private val GROUPS: List<Triple<String, String, List<Entry>>> = listOf(
        Triple(
            "感知", "看设备现在是什么状态", listOf(
                e("status", "GET", "/dev/status", "设备总览：电量/温度/音量/屏幕/内存/网络/前台应用"),
                e("ping", "GET", "/dev/ping", "桥与无障碍是否在线（最快的一次健康检查）"),
                e(
                    "sensors", "GET", "/dev/sensors", "传感器读数",
                    cap = "sensor",
                    params = "type=accel|gyro|light|proximity|step|magnet|pressure（不给则读全部）",
                    examples = listOf("dev sensors", "dev sensors type=accel"),
                ),
                e(
                    "notifications", "GET", "/dev/notifications", "读当前通知列表（需通知使用权）",
                    cap = "notify_read", perm = "通知使用权（在权限中心开）",
                    params = "limit=20",
                ),
                e(
                    "media", "GET", "/dev/media", "当前媒体会话：标题/艺术家/进度/播放状态",
                    cap = "media", perm = "通知使用权（MediaSession 走通知访问）",
                ),
            )
        ),
        Triple(
            "个人数据", "日历 / 联系人 / 短信 / 通话记录", listOf(
                e(
                    "calendar", "GET", "/dev/calendar", "查日程",
                    cap = "personal_calendar", perm = "READ_CALENDAR",
                    params = "from=<毫秒> to=<毫秒>（默认今天起 7 天）",
                    examples = listOf("dev calendar"),
                ),
                e(
                    "contacts", "GET", "/dev/contacts", "搜通讯录",
                    cap = "personal_contacts", perm = "READ_CONTACTS",
                    params = "q=<关键词> limit=20",
                    examples = listOf("dev contacts", "dev contacts q=张三"),
                ),
                e(
                    "sms", "GET", "/dev/sms", "读短信",
                    cap = "personal_sms", perm = "READ_SMS",
                    params = "q=<关键词> limit=20",
                    examples = listOf("dev sms", "dev sms q=验证码"),
                ),
                e(
                    "calls", "GET", "/dev/calls", "通话记录（v1.5.0-beta.10 新增：READ_CALL_LOG 早就授到却没端点用它）",
                    cap = "personal_calls", perm = "READ_CALL_LOG",
                    params = "limit=20",
                ),
            )
        ),
        Triple(
            "控制", "让设备做动作", listOf(
                e(
                    "control", "POST", "/dev/control", "开硬件：WiFi/蓝牙/音量/亮度/手电/震动",
                    cap = "control",
                    params = "op=wifi|bt|volume|brightness|torch|vibrate（只有这六个）\n" +
                        "  op=wifi/bt   → enabled=true|false（Android 10+ 改不动，返回 user_confirmation_required）\n" +
                        "  op=volume    → value=0..max, stream=music|ring|alarm|notification|voice\n" +
                        "  op=brightness→ value=1..255\n" +
                        "  op=torch     → enabled=true|false\n" +
                        "  op=vibrate   → ms=1..10000",
                    body = "{\"op\":\"torch\",\"enabled\":true}",
                    examples = listOf(
                        "dev control op=torch enabled=true",
                        "dev control op=brightness value=128",
                        "dev control op=volume value=8 stream=music",
                    ),
                    limits = "① 没有 ringer / dnd / settings_action 这三个 op（曾写错，实测报 op must be wifi|bt|volume|brightness|torch|vibrate）。" +
                        "② torch 要字段名 enabled 不是 on。③ wifi/bt 的开关在 Android 13+ 被系统拦，返回 user_confirmation_required。" +
                        "④ brightness 需要 WRITE_SETTINGS（可 grant），否则 409 write_settings_required。",
                ),
                e(
                    "clipboard", "POST", "/dev/clipboard", "剪贴板读写",
                    cap = "clipboard",
                    params = "{\"op\":\"read\"} 或 {\"op\":\"write\",\"text\":\"...\"}",
                    body = "{\"op\":\"read\"}",
                    examples = listOf("dev clipboard op=read"),
                    limits = "后台读写受系统前台限制；Android 10+ 只有前台/输入法能读",
                ),
                e(
                    "media", "POST", "/dev/media", "媒体控制：播放/暂停/切歌/进度/音量",
                    cap = "media", perm = "通知使用权",
                    params = "op=play|pause|toggle|next|previous|stop|seek|volume|stream-volume\n" +
                        "  op=seek         → position_ms=<毫秒>\n" +
                        "  op=volume       → volume=0..1（比例）或 0..max（绝对值，按会话 max 换算）\n" +
                        "  op=stream-volume→ stream=music|ring|alarm|notification|voice, value=<档位>",
                    body = "{\"op\":\"toggle\"}",
                    examples = listOf("dev media op=toggle", "dev media op=seek position_ms=30000"),
                ),
                // ⚠️ 没有 POST /dev/notify 这个端点！发通知只走 App 内部 NotifyCap.send()。
                // 曾经在这里登记过一条不存在的 /dev/notify，实测 404。删掉以免误导。
                e(
                    "sms", "POST", "/dev/sms", "打开发短信界面（不直接发，由用户确认）",
                    cap = "personal_sms", perm = "无（拉起系统短信 App，由用户点发送）",
                    params = "to=<号码> text=<内容>",
                    body = "{\"to\":\"10086\",\"text\":\"你好\"}",
                    examples = listOf("dev sms to=10086 text=你好"),
                    limits = "字段名是 to 不是 number。只打开界面不代发，要真发需 SEND_SMS（未实现）。",
                ),
                e(
                    "notify-reply", "POST", "/dev/notify-reply", "代用户回复可回复的通知（微信/QQ 等 RemoteInput）",
                    cap = "notify_action", perm = "通知使用权",
                    params = "{\"key\":\"<通知 key>\",\"text\":\"<回复内容>\",\"action_index\":0}\n" +
                        "  key 从 GET /dev/notifications 的 items[].key 拿，且必须**刚查过**（通知可能已被滑掉）。\n" +
                        "  action_index 可选，多按钮通知里选第几个（默认 -1 = 自动找 RemoteInput 那个）。\n" +
                        "  注意：没有 op=list —— 列通知是 GET /dev/notifications。",
                    body = "{\"key\":\"0|com.tencent.mm|...\",\"text\":\"收到\"}",
                    examples = listOf("dev notifications", "dev api POST /dev/notify-reply '{\"key\":\"...\",\"text\":\"收到\"}'"),
                    limits = "必须先 GET /dev/notifications 拿 key；通知被划掉后 key 失效返回 notification_not_found。",
                ),
                e(
                    "calendar", "POST", "/dev/calendar", "加日程",
                    cap = "personal_calendar", perm = "WRITE_CALENDAR",
                    params = "title=<必填> begin_ms=<毫秒> end_ms=<毫秒> all_day=false location=... notes=... calendar_id=<可选>",
                    body = "{\"title\":\"交作业\",\"begin_ms\":1793500000000,\"end_ms\":1793503600000}",
                    examples = listOf("dev api POST /dev/calendar '{\"title\":\"开会\",\"begin_ms\":1793500000000}'"),
                ),
            )
        ),
        Triple(
            "调度", "定时与提醒", listOf(
                e(
                    "remind", "POST", "/dev/remind", "建定时提醒（不依赖网络，重启后仍在）",
                    cap = "sched", perm = "SCHEDULE_EXACT_ALARM（可选，缺则降级为非精确）",
                    params = "title=<必填> body=<必填> 时间二选一：\n" +
                        "  in_seconds=<秒>                     从现在起 N 秒\n" +
                        "  at=<ISO8601>                       如 2026-11-03T08:00:00+08:00\n" +
                        "  repeat={\"kind\":\"once|daily|weekday\"}  默认 once\n" +
                        "  priority=default|high  sound=true|false  vibrate=true|false",
                    body = "{\"title\":\"喝水\",\"body\":\"该喝水了\",\"in_seconds\":600}",
                    examples = listOf(
                        "dev remind title=喝水 body=该喝水了 in_seconds=600",
                        "dev api POST /dev/remind '{\"title\":\"早\",\"body\":\"起床\",\"in_seconds\":60,\"repeat\":{\"kind\":\"daily\"}}'",
                    ),
                    limits = "title 与 body 都必填（空则 400 title and body are required）。" +
                        "没有 at_ms 这个字段，绝对时间是 at=<ISO8601 字符串>。时间必须晚于现在，否则 400。",
                ),
                e("remind-list", "GET", "/dev/remind-list", "列出全部提醒", cap = "sched"),
                e(
                    "remind-cancel", "POST", "/dev/remind-cancel", "取消提醒",
                    cap = "sched", params = "{\"id\":\"...\"}", body = "{\"id\":\"1\"}",
                ),
            )
        ),
        Triple(
            "底层射频", "蓝牙帧 / WiFi 链路 / NFC / 地理围栏", listOf(
                e(
                    "bt", "POST", "/dev/bt", "蓝牙底层：扫描 / 广播 / GATT / RFCOMM / HCI 日志",
                    cap = "bt", perm = "BLUETOOTH_SCAN/CONNECT/ADVERTISE",
                    params = "op=status|enable|disable|scan|advertise|connect|services|read|write|rfcomm|log\n" +
                        "  scan      → ms=<扫描时长>  filter=<名字过滤>\n" +
                        "  advertise → manufacturerId=<厂商id>  data=<纯十六进制>  service_uuid=...  connectable=...\n" +
                        "  read      → address=<MAC>  char_uuid=<UUID>\n" +
                        "  write     → address=<MAC>  char_uuid=<UUID>  hex=<十六进制>  no_response=false\n" +
                        "  rfcomm    → address=<MAC>  hex=<十六进制>\n" +
                        "  log       → lines=<行数>  filter=<关键词>  format=text|binary\n" +
                        "              format=binary 额外支持：limit=<包数>  hex=1  full=true  path=<.cfa 路径>",
                    body = "{\"op\":\"scan\",\"ms\":5000}",
                    examples = listOf(
                        "dev bt op=scan ms=5000",
                        "dev bt op=advertise manufacturerId=76 data=0102030405",
                        "dev bt op=log lines=50",
                        "dev bt op=log format=binary limit=60",
                        "dev bt op=log format=binary filter=Create_Connection",
                    ),
                    limits = "只能发应用层报文（GATT 特征值 / RFCOMM / BLE 广播）。" +
                        "伪造 HCI 帧做不到：设备无 hcitool/hciconfig/btmon，普通 App 通路拿不到。" +
                        "没有 classic/send/capture 三个 op（曾写错）。" +
                        "format=binary 默认只读文件末尾 256KB 再按记录边界重对齐（整份常 1MB+ 太重），" +
                        "要全量加 full=true；它出的是 HCI 帧（cmd/acl/sco/event），" +
                        "要看 L2CAP/RFCOMM 语义事件用默认 format=text。",
                ),
                e(
                    "wifi-radio", "POST", "/dev/wifi-radio", "WiFi 底层：扫描（含隐藏 SSID）/ 链路 / 抓包",
                    cap = "wifi", perm = "ACCESS_WIFI_STATE + CHANGE_WIFI_STATE",
                    params = "op=status|scan|enable|disable|link|capture\n" +
                        "  scan    → ms=<时长>  iface=<网卡，默认 wlan0>\n" +
                        "  link    → iface=wlan0\n" +
                        "  capture → seconds=<秒>  path=<保存路径>",
                    body = "{\"op\":\"scan\"}",
                    examples = listOf("dev wifi-radio op=scan", "dev wifi-radio op=link"),
                    limits = "scan 走 iw 命令，比 Android API 多出隐藏 SSID；capture 需 root。" +
                        "没有 info/log 两个 op（曾写错）。",
                ),
                e(
                    "net", "POST", "/dev/net",
                    "按 App（UID）断网 / 查各 App 流量（两档：查询不需 root，封锁要）",
                    cap = "net", perm = "PACKAGE_USAGE_STATS（流量）+ root（封锁）",
                    params = "op=status|list|block|unblock\n" +
                        "  status  → 列出当前被我们封着的 uid 及命中计数\n" +
                        "  list    → hours=<窗口小时，默认24>  limit=<条数，默认25>  package=<子串过滤>\n" +
                        "  block   → package=<包名> 或 uid=<数字>  allowSelf=true（默认拒封自己）\n" +
                        "  unblock → package=<包名> 或 uid=<数字>",
                    body = "{\"op\":\"block\",\"package\":\"com.ss.android.ugc.aweme\"}",
                    examples = listOf(
                        "dev net op=status",
                        "dev net op=list hours=6 limit=10",
                        "dev net op=block package=com.ss.android.ugc.aweme",
                        "dev net op=unblock uid=10360",
                    ),
                    limits = "① 只封 IPv4 OUTPUT（ip6tables 还没接）；" +
                        "② 规则插在 OUTPUT 链**最前**（-I OUTPUT 1），因为 ColorOS 前面挂了一串定制链，" +
                        "追加到末尾会被提前 ACCEPT 掉；" +
                        "③ **不要用 ping 验证成败**：su <uid> -c ping 拿不到 raw socket，" +
                        "有没有规则都 100% 丢包。成功判据是返回里的 packets_dropped 计数器回读；" +
                        "④ 拒绝封 uid 0 / 自己（除非显式 allowSelf=true）；" +
                        "⑤ list 需要系统设置里授予「使用情况访问」权限。",
                ),
                e(
                    "ble", "POST", "/dev/ble",
                    "手机当 BLE 外设（GATT Server）：别的设备连上来读写你开的值",
                    cap = "bt", perm = "BLUETOOTH_CONNECT + BLUETOOTH_ADVERTISE",
                    params = "op=status|start|stop|events|notify|read|write\n" +
                        "  start   → service=<UUID>  char=<UUID>  name=<广播名，默认 DSH>\n" +
                        "  events  → limit=<条数>  drain=true 消费掉\n" +
                        "  notify  → text=<要推给客户端的字符串>\n" +
                        "  write   → value=<本地改特征值内容，供客户端 read 到>",
                    body = "{\"op\":\"start\",\"name\":\"DSH\"}",
                    examples = listOf(
                        "dev ble op=start name=DSH",
                        "dev ble op=status",
                        "dev ble op=events drain=true",
                        "dev ble notify text=hello",
                    ),
                    limits = "① 与 /dev/bt 方向相反：/dev/bt 是手机去连别人，这里是被别人连；" +
                        "② 广播载荷上限 31 字节，名字走 scan response；" +
                        "③ 客户端写进来的内容要自己 op=events 轮询，Android 不会主动通知 AI；" +
                        "④ 必须 op=stop 再 op=start 才能换 UUID（运行中改配置会返回 409）；" +
                        "⑤ 熄屏被 Cgroup Freezer 冻结时连接会掉。",
                ),
                e(
                    "bt-hid", "POST", "/dev/bt-hid",
                    "手机冒充蓝牙键盘控制别的设备（HID Device，OEM 常拦）",
                    cap = "bt", perm = "BLUETOOTH_CONNECT（多数 OEM 另需 BLUETOOTH_PRIVILEGED）",
                    params = "op=status|probe|register|unregister|connect|type|key|mouse\n" +
                        "  probe    → 先试能不能当 HID 设备，失败会给具体原因\n" +
                        "  register → name=<显示名>  description=<描述>\n" +
                        "  connect  → address=<MAC>；不给就返回已配对设备列表\n" +
                        "  type     → text=<字符串，仅 US 布局 ASCII>\n" +
                        "  key      → code=<HID usage 0-255>  modifier=<0-255>\n" +
                        "  mouse    → dx dy buttons（本 App 只注册键盘，鼠标报告会被拒）",
                    body = "{\"op\":\"probe\"}",
                    examples = listOf(
                        "dev bt-hid op=probe",
                        "dev bt-hid op=register name=DSHKeyboard",
                        "dev bt-hid op=type text=hello",
                    ),
                    limits = "① **先跑 op=probe**：多数 OEM 用 BLUETOOTH_PRIVILEGED 把 HID_DEVICE " +
                        "profile 保护起来，普通 App 拿不到 proxy；probe 会把原因原样告诉你；" +
                        "② 只注册键盘（SUBCLASS1_KEYBOARD），op=mouse 会被系统拒；" +
                        "③ type 仅 US 布局 ASCII，非 ASCII 字符跳过而不是猜成别的键；" +
                        "④ 需要先在对方设备的蓝牙设置里配对；" +
                        "⑤ 与 /dev/uhid 互补：那个是「AI 按本机」，这个是「AI 按另一台设备」。",
                ),
                e(
                    "pulse", "POST", "/dev/pulse",
                    "振动摩斯电码：不用亮屏、不出声就知道结果",
                    cap = "control", perm = "VIBRATE",
                    params = "text=<A-Z 0-9>  或短码 ok|fail|wait|done|ask|sos\n" +
                        "unitMs=<基本时间单位，默认 60ms>",
                    body = "{\"text\":\"ok\"}",
                    examples = listOf("dev pulse text=ok", "dev pulse text=done unitMs=80"),
                    limits = "只支持 A-Z / 0-9 与六个短码；其他字符会被跳过并如实报告。" +
                        "点=1 单位、划=3 单位、符号间=1、词间=7。",
                ),
                e(
                    "context", "GET", "/dev/context",
                    "上下文总线：时间/屏幕/锁屏/Doze/电池/网络/前台应用/通知概况 + 该不该打扰",
                    cap = "status", perm = "Usage Access（前台应用，可选）",
                    params = "无参数；POST 也接受 {}（留给将来加过滤）",
                    examples = listOf("dev context", "dev api GET /dev/context"),
                    limits = "① battery.percent=-1 表示取不到粘性 ACTION_BATTERY_CHANGED；" +
                        "② foreground.available=false 表示没授 Usage Access；" +
                        "③ notifications 段只给数量不给正文（要正文用 dev notifications）；" +
                        "④ should_nudge 只是建议，真正要发提醒走 dev nudge。",
                ),
                e(
                    "nudge", "POST", "/dev/nudge",
                    "主动提醒闸门：默认不打扰，通过才放行（唯一目的就是不烦人）",
                    cap = "notify", perm = "POST_NOTIFICATIONS（API 33+）",
                    params = "text=<要告诉用户的话>  key=<去重键，默认取 text 前 48 字>\n" +
                        "importance=<0-10，>=8 可穿过静音与锁屏>  force=true 强制放行",
                    body = "{\"text\":\"任务跑完了\",\"importance\":5}",
                    examples = listOf(
                        "dev nudge text=Build finished",
                        "dev nudge text=Alert importance=9",
                    ),
                    limits = "默认拒绝的五种情况：静音时段（23:00-07:00）、息屏、锁屏、Doze、同 key 冷却 30 分钟。\n" +
                        "allow=true 只表示「没有规则拦住它」，内容值不值得说仍由调用方判断；" +
                        "拒绝时 reasons 会列出具体哪条拦的，方便调参。",
                ),
                e(
                    "hand", "POST", "/dev/hand",
                    "「AI 的手」可视化：在屏幕上画出要点哪里、往哪滑",
                    cap = "control", perm = "无障碍服务运行中（TYPE_ACCESSIBILITY_OVERLAY）",
                    params = "op=status|tap|path|clear|hide\n" +
                        "  tap  -> x=<px>  y=<px>  kind=tap|long|swipe（颜色区分：绿/橙/蓝）\n" +
                        "  path -> x1= y1= x2= y2=（画箭头与起终点）",
                    body = "{\"op\":\"tap\",\"x\":540,\"y\":1200}",
                    examples = listOf(
                        "dev hand op=tap x=540 y=1200",
                        "dev hand op=path x1=540 y1=1800 x2=540 y2=600",
                        "dev hand op=clear",
                    ),
                    limits = "① 坐标是**屏幕绝对像素**，与无障碍 boundsInScreen 同一坐标系，" +
                        "直接把节点 bounds 中心丢进来即可；" +
                        "② 纯视觉层（FLAG_NOT_TOUCHABLE），永不吃事件，不改变任何行为；" +
                        "③ 2.5 秒无操作自动淡出（只清标记，窗口复用）；" +
                        "④ 需要无障碍服务在跑，否则 addView 会失败并如实报错；" +
                        "⑤ 与 StatusOverlay 的状态球互不影响（各自独立窗口）。",
                ),
                e(
                    "notify-digest", "GET", "/dev/notify-digest",
                    "通知摘要：按 App 聚合计数 + 最近一条时间（不返回正文）",
                    cap = "notify_read", perm = "通知使用权",
                    params = "limit=<最多几个 App，默认 50>",
                    examples = listOf("dev notify-digest", "dev notify-digest limit=10"),
                    limits = "与 dev notifications 的区别：那个是原始列表，这个是统计视图（每个 App 几条、最近何时），" +
                        "让 AI 不必自己数。数据源与读通知相同。",
                ),
                e(
                    "scene", "POST", "/dev/scene",
                    "传感器场景推断：口袋/户外/静止/在走/在跑/升降楼层",
                    cap = "sensor", perm = "无（气压/光感/距离/加速度/计步都是普通传感器）",
                    params = "windowMs=<采样窗口，默认 1500ms，范围 200-10000>",
                    body = "{\"windowMs\":1500}",
                    examples = listOf("dev scene", "dev scene windowMs=3000"),
                    limits = "**规则推断，不是训练好的模型** —— 每个标签都附带它依据的具体数字" +
                        "（facts 里写明「light 3lux → 很暗」）。" +
                        "气压变化要和上一次调用比，第一次调用没有 delta 是正常的。",
                ),
                e(
                    "screenwatch", "POST", "/dev/screenwatch",
                    "屏幕变化检测：只回「变没变、哪块在变」，不存视频也不存图",
                    cap = "screen_capture", perm = "MediaProjection（用户每次授权）",
                    params = "op=status|request|start|stop|diff\n" +
                        "  start -> downscale=<缩几倍，默认8>  threshold=<差分阈值，默认12>  grid=<网格数，默认8>\n" +
                        "  diff  -> waitMs=<等新帧的毫秒，默认800>",
                    body = "{\"op\":\"start\",\"downscale\":8}",
                    examples = listOf(
                        "dev screenwatch op=request",
                        "dev screenwatch op=start",
                        "dev screenwatch op=diff",
                    ),
                    limits = "① 必须先 op=request 让用户点系统授权弹窗；" +
                        "② 与录屏共用 MediaProjection，Android 14+ 一次授权只能起一个投影；" +
                        "③ 返回的是 changed_ratio / mean_delta / hot_blocks，**没有像素** —— " +
                        "要看画面请用截图；④ 静态页面 VirtualDisplay 仍出帧但 diff 为 0，这是正常的。",
                ),
                e(
                    "doze", "POST", "/dev/doze",
                    "电池优化白名单自助：让自己活过息屏（长驻能力的前提）",
                    cap = "control", perm = "root（dumpsys deviceidle 需要提权）",
                    params = "op=status|add|remove",
                    body = "{\"op\":\"add\"}",
                    examples = listOf("dev doze op=status", "dev doze op=add"),
                    limits = "`dumpsys deviceidle whitelist` 在多数机型上要 root 或 shell；" +
                        "没提权时返回 403。加白名单后 HID / GATT / 录音才不会在息屏时被冻结。",
                ),
                e(
                    "nfc-status", "GET", "/dev/nfc", "NFC 硬件状态：在不在 / 系统开没开 / 前台读标签有没有起",
                    cap = "rf", perm = "NFC",
                    examples = listOf("dev nfc-status", "dev api GET /dev/nfc"),
                    limits = "只读状态，不会开 NFC。要开本 App 的前台读标签模式用 dev nfc op=enable。",
                ),
                e(
                    "nfc", "POST", "/dev/nfc", "NFC：软件开关前台读标签（NDEF 文本与原始字节）",
                    cap = "rf", perm = "NFC",
                    params = "op=read|enable|disable|clear（只有这四个；默认 read）\n" +
                        "  enable  只是「开启本 App 的前台读标签模式」，不是开系统 NFC\n" +
                        "  clear   清掉已读到的标签缓存",
                    body = "{\"op\":\"read\"}",
                    examples = listOf("dev nfc op=read", "dev nfc op=enable"),
                    limits = "① **没有 op=status** —— 查状态用 GET /dev/nfc（dev api GET /dev/nfc）。" +
                        "② enable 不改系统 NFC 开关：Android 10+ 应用做不到，系统 NFC 关着时返回 nfc_disabled_in_settings。" +
                        "③ 需要 App 在前台（Activity 存活），否则 409 activity_unavailable。" +
                        "④ **写卡（transceive / Ndef.write / 卡模拟）没有实现**，只能读。",
                ),
                e(
                    "geofence", "POST", "/dev/geofence", "地理围栏：进入/离开触发",
                    cap = "rf", perm = "ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION",
                    params = "op=list|add|remove|clear（默认 list）\n" +
                        "  add    → id=<必填> lat=<必填> lon=<必填> radius_m=200 label=... enter=true exit=true\n" +
                        "  remove → id=<必填>\n" +
                        "  clear  → 清空全部",
                    body = "{\"op\":\"add\",\"id\":\"home\",\"lat\":31.2,\"lon\":121.5,\"radius_m\":200}",
                    examples = listOf("dev api POST /dev/geofence '{\"op\":\"list\"}'"),
                    limits = "add/remove 都要 id，缺则 400 missing id。需要 ACCESS_FINE_LOCATION 已授予，否则 403。",
                ),
            )
        ),
        Triple(
            "屏幕与交互", "读屏、点按、弹窗、录屏", listOf(
                e(
                    "dialog", "POST", "/dev/dialog", "弹窗检测：几何判据找出系统弹窗与安全按钮",
                    cap = "control",
                    params = "auto=true|false（是否自动点安全按钮，默认 false）\n" +
                        "includeDanger=true|false（是否把危险按钮也算进来，默认 true）\n" +
                        "注意：没有 op 字段。",
                    body = "{\"auto\":false}",
                    examples = listOf("dev dialog", "dev dialog auto=true"),
                    limits = "判据是几何的：系统弹窗按钮在底部同一水平线成对出现；页面卡片按钮是孤立单点。" +
                        "实测设置页 max_buttons_in_row=1（不误判）、录屏授权弹窗=2（正确命中）。",
                ),
                e(
                    "verify", "POST", "/dev/verify", "校验某个动作是否真的生效（前后屏幕对比）",
                    cap = "control",
                    params = "expect=<必填，要查找的文字>  gone=true|false（true=断言消失，false=断言出现）\n" +
                        "timeout_ms=<默认 4000，范围 200..30000>",
                    body = "{\"expect\":\"确定\",\"gone\":true}",
                    examples = listOf("dev api POST /dev/verify '{\"expect\":\"保存成功\",\"gone\":false}'"),
                    limits = "expect 必填（空则 400 expect required）。没有 text 字段。",
                ),
                e(
                    "record-status", "GET", "/dev/record", "录屏状态：授权拿到没 / 是否正在录 / 已录多久",
                    examples = listOf("dev record-status", "dev api GET /dev/record"),
                    limits = "只读。要开始录必须走 dev record op=request，且用户在系统弹窗点「整个屏幕」。",
                ),
                e(
                    "record", "POST", "/dev/record", "屏幕录制",
                    perm = "每次开始必须用户在系统弹窗点「整个屏幕」，无法静默",
                    params = "op=request|start|stop|status（默认 status）",
                    body = "{\"op\":\"status\"}",
                    examples = listOf("dev record op=status"),
                    limits = "选「单个应用」会拿到 data=false（等于取消），必须选「整个屏幕」",
                ),
                e(
                    "hid-status", "GET", "/dev/hid", "虚拟 HID 设备状态：创建了没 / 什么类型",
                    cap = "hid", perm = "root（/dev/uhid 权限位 crw-rw---- uhid uhid）",
                    examples = listOf("dev hid-status", "dev api GET /dev/hid"),
                    limits = "只读。不创建任何设备。",
                ),
                e(
                    "hid", "POST", "/dev/hid", "虚拟 HID 设备：注册真实外设键盘/鼠标/媒体键并注入按键",
                    cap = "hid", perm = "root",
                    params = "op=status|create|key|tap|text|mouse|destroy（默认 status）\n" +
                        "  create → kind=keyboard|consumer|mouse（默认 keyboard）、name=<可选设备名>\n" +
                        "  tap    → code=<HID usage 十进制>，按下后自动抬起\n" +
                        "  key    → code=<usage> 或 keys=[..]；**只按下不抬起**，须再发 code=0\n" +
                        "  text   → text=<仅 US 布局可打印 ASCII>\n" +
                        "  mouse  → dx= dy= wheel= button=0|1|2（需先 create kind=mouse）",
                    body = "{\"op\":\"create\",\"kind\":\"keyboard\"}",
                    examples = listOf(
                        "dev hid op=create kind=consumer",
                        "dev hid op=tap code=233   （Consumer 页 0xE9 = 音量+，实测 60 → 70）",
                        "dev hid op=tap code=128 kind=keyboard  （Keyboard 页 0x80 = KEY_VOLUMEUP）",
                        "dev hid op=destroy",
                    ),
                    limits = "① 必须 root：shell（即使组里有 3011(uhid)）也被 SELinux 拒。\n" +
                        "② 与「模拟点击」的本质区别：系统当它是外接硬件（Classes: KEYBOARD | EXTERNAL），没有注入标记，部分风控 App 无法区分。\n" +
                        "③ ★ 两个页的 usage 号不通用（真机实测 2026-11-02）：Consumer 页音量+ = 233(0xE9)、音量- = 234(0xEA)、静音 = 226(0xE2)；Keyboard 页 128(0x80) = KEY_VOLUMEUP、129 = KEY_VOLUMEDOWN、127(0x7F) = KEY_MUTE（不是音量小）。在 Consumer 页发 0x80 无任何反应。\n" +
                        "④ ★ 屏幕必须 Awake：Dozing 下事件能到内核（getevent 抓得到）但 InputDispatcher 不派发给窗口，表现为「全部成功但没反应」。先 input keyevent KEYCODE_WAKEUP。\n" +
                        "⑤ text 只支持 US 布局 ASCII，中文必须走无障碍 ACTION_SET_TEXT。\n" +
                        "⑥ 创建返回成功 ≠ 设备已注册：必须回读 dumpsys input 确认设备名出现。\n" +
                        "⑦ 建议优先用门脚本 `hid`（root 直连 /dev/uhid）：本端点要经 su 在 App 进程里等授权，无 UI 时会挂住（实测 curl 60s 超时）。",
                ),
            )
        ),
        Triple(
            "权限自助", "查 / 授 / 撤", listOf(
                e("perms", "GET", "/dev/perms", "权限总览：声明数 / 已拿到 / 还差多少"),
                e("perms/status", "GET", "/dev/perms/status", "权限详情（含后台授权进度）"),
                e(
                    "perms/grant", "POST", "/dev/perms", "无差别尝试授予全部已声明权限（需 root 或 Shizuku）",
                    cap = "control",
                    params = "{\"force\":true}",
                    body = "{\"force\":true}",
                    limits = "跑在脱离 App 的后台进程里（pm grant 会因 gid 变更杀 App 进程）。" +
                        "立刻返回 started，要回读 granted 数确认，别只看 started。",
                ),
                e(
                    "perms/revoke", "POST", "/dev/perms/revoke", "撤销指定权限",
                    cap = "control",
                    params = "{\"perms\":[\"android.permission.CAMERA\"]}",
                    body = "{\"perms\":[\"android.permission.CAMERA\"]}",
                    limits = "pm revoke 会杀本 App 进程重载权限组表 —— 系统行为，不是崩溃。等 2-3 秒再继续。",
                ),
            )
        ),
        Triple(
            "扩展与诊断", "环境自检", listOf(
                e("ext/list", "GET", "/ext/list", "已安装的扩展（android-buildtools / rust / vim 等）"),
                e("plugin/list", "GET", "/plugin/list", "已安装的插件"),
                e("diag", "GET", "/diag", "桥自诊断：端口 / token / 无障碍 / 各能力开关"),
            )
        ),
    )

    /** 全部条目拍平。 */
    private val ENTRIES: List<Entry> = GROUPS.flatMap { it.third }

    /** 按 id 或 path 查找（内部用；Entry 是私有类型，不对外暴露）。 */
    private fun find(query: String): Entry? {
        val q = query.trim().removePrefix("/").removePrefix("dev/")
        return ENTRIES.firstOrNull { it.id == q }
            ?: ENTRIES.firstOrNull { it.path.trimStart('/').removePrefix("dev/") == q }
            ?: ENTRIES.firstOrNull { it.path == query }
    }

    /**
     * `GET /dev/help` —— 把目录吐成 JSON，供门脚本 `dev` 自渲染。
     *
     * 带 `q=<名字>` 时只返回那一条（含完整参数/示例/限制）。
     * 不带 q 时返回分组列表（只要 id + summary，省 token —— 完整目录约 8KB，
     * 而 AI 大多数时候只需要「有哪些」）。
     */
    fun help(ctx: Context, query: String): Pair<Int, String> {
        val q = parseQueryValue(query, "q")
        val detail = parseQueryValue(query, "detail") == "1"

        if (!q.isNullOrBlank()) {
            val hit = find(q)
                ?: return 404 to JSONObject()
                    .put("ok", false)
                    .put("error", "unknown_endpoint")
                    .put("query", q)
                    .put("hint", "用 `dev` 看全部可用的名字")
                    .toString()
            return 200 to JSONObject()
                .put("ok", true)
                .put("id", hit.id)
                .put("method", hit.method)
                .put("path", hit.path)
                .put("cap", hit.cap ?: JSONObject.NULL)
                .put("cap_enabled", hit.cap?.let { CapabilityGate.enabled(ctx, it) } ?: JSONObject.NULL)
                .put("perm", hit.perm ?: JSONObject.NULL)
                .put("summary", hit.summary)
                .put("params", hit.params ?: "")
                .put("body", hit.body ?: "")
                .put("examples", JSONArray(hit.examples))
                .put("limits", hit.limits ?: "")
                .toString()
        }

        val groups = JSONArray()
        for ((gname, gdesc, items) in GROUPS) {
            val arr = JSONArray()
            for (it in items) {
                val o = JSONObject()
                    .put("id", it.id)
                    .put("method", it.method)
                    .put("path", it.path)
                    .put("summary", it.summary)
                if (it.cap != null) {
                    val on = CapabilityGate.enabled(ctx, it.cap)
                    o.put("cap", it.cap).put("cap_enabled", on)
                }
                if (detail) {
                    o.put("params", it.params ?: "")
                    o.put("body", it.body ?: "")
                    o.put("examples", JSONArray(it.examples))
                    o.put("limits", it.limits ?: "")
                    o.put("perm", it.perm ?: "")
                }
                arr.put(o)
            }
            groups.put(JSONObject().put("group", gname).put("desc", gdesc).put("items", arr))
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("count", ENTRIES.size)
            .put("detail", detail)
            .put("groups", groups)
            .toString()
    }

    /**
     * 启动时核对：目录里的路径必须在真实路由表里存在。
     *
     * 返回不一致项（空 = 一致）。`AgentBridge` 启动时调用并打日志 ——
     * 这是防止「目录说能用、实际 404」的兜底，比注释和文档可靠。
     */
    /** 元端点：给 dev 自己用的，不属于「设备能力」，不进能力目录也不算漏登记。 */
    private val META_ROUTES = setOf("GET /dev/help", "GET /dev/routes")

    fun verifyAgainst(routes: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        for (e in ENTRIES) {
            val key = "${e.method} ${e.path}"
            if (key !in routes) problems += "catalog lists $key but no such route"
        }
        // 反向：路由表里的 /dev 端点若没登记，也算漏
        val known = ENTRIES.map { "${it.method} ${it.path}" }.toSet()
        for (r in routes) {
            if (r.contains(" /dev/") && r !in known && r !in META_ROUTES) {
                problems += "route $r is not in the catalog (dev won't discover it)"
            }
        }
        return problems
    }

    private fun parseQueryValue(query: String, key: String): String? {
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            if (pair.substring(0, i) == key) {
                return runCatching {
                    java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                }.getOrDefault(pair.substring(i + 1))
            }
        }
        return null
    }
}

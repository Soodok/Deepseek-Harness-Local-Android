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
                    cap = "personal", perm = "READ_CALENDAR",
                    params = "from=<毫秒> to=<毫秒>（默认今天起 7 天）",
                    examples = listOf("dev calendar"),
                ),
                e(
                    "contacts", "GET", "/dev/contacts", "搜通讯录",
                    cap = "personal", perm = "READ_CONTACTS",
                    params = "q=<关键词> limit=20",
                    examples = listOf("dev contacts", "dev contacts q=张三"),
                ),
                e(
                    "sms", "GET", "/dev/sms", "读短信",
                    cap = "personal", perm = "READ_SMS",
                    params = "q=<关键词> limit=20",
                    examples = listOf("dev sms", "dev sms q=验证码"),
                ),
                e(
                    "calls", "GET", "/dev/calls", "通话记录（v1.5.0-beta.10 新增：READ_CALL_LOG 早就授到却没端点用它）",
                    cap = "personal", perm = "READ_CALL_LOG",
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
                    cap = "personal", perm = "无（拉起系统短信 App，由用户点发送）",
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
                    "sms", "POST", "/dev/sms", "打开发短信界面（不直接发，由用户确认）",
                    cap = "personal", perm = "无（用系统短信 App）",
                    params = "{\"number\":\"10086\",\"text\":\"...\"}",
                    body = "{\"number\":\"10086\",\"text\":\"你好\"}",
                ),
                e(
                    "calendar", "POST", "/dev/calendar", "加日程",
                    cap = "personal", perm = "WRITE_CALENDAR",
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
                        "  log       → lines=<行数>",
                    body = "{\"op\":\"scan\",\"ms\":5000}",
                    examples = listOf(
                        "dev bt op=scan ms=5000",
                        "dev bt op=advertise manufacturerId=76 data=0102030405",
                        "dev bt op=log lines=50",
                    ),
                    limits = "只能发应用层报文（GATT 特征值 / RFCOMM / BLE 广播）。" +
                        "伪造 HCI 帧做不到：设备无 hcitool/hciconfig/btmon，普通 App 通路拿不到。" +
                        "没有 classic/send/capture 三个 op（曾写错）。",
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

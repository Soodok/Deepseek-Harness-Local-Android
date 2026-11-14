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
    internal data class Entry(
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
                e(
                    "watch", "GET|POST", "/dev/watch", "事件流：输入内容 / 物理按键 / 窗口切换（支持 since 增量拉取）",
                    cap = "inputwatch | keywatch | status",
                    params = "op=list|clear；kind=input|key|window|all（默认 all）；" +
                        "since=<epoch ms，只返回 at 大于它的条目>；clear=1（清空后再列，用于「从现在开始记」）",
                    body = "{\"kind\":\"window\",\"since\":0}",
                    examples = listOf(
                        "dev watch kind=window",
                        "dev watch kind=input since=1730000000000",
                        "dev watch op=clear",
                    ),
                    limits = "① 三个缓冲都**只在内存**：进程一死就没。这是有意的 —— 落盘会把「能读当前输入」变成「能翻历史输入」。" +
                        "\n② 容量 input 200 / key 200 / window 120，超出淘汰最旧。" +
                        "\n③ 分档授权：input 要 cap.inputwatch，key 要 cap.keywatch，window 归 cap.status；" +
                        "kind=all 时缺哪项就只挡哪项，不会因为没开输入开关就连窗口也看不了。" +
                        "\n④ 密码框（event.isPassword）整条丢弃，**连长度都不记** —— 否则能从长度反推。" +
                        "\n⑤ 每次读到 input 都会发一条系统通知告警，只报条数不报内容（锁屏可见）。" +
                        "\n⑥ 同一窗口连续变化只更新一条并累加 repeat，避免一个界面瞬间灌满缓冲。" +
                        "\n⑦ 增量用法：把上次结果里最大的 at 当 since 传回来。" +
                        "\n⑧ 按键流只观察不拦截（onKeyEvent 恒 false），开了也不影响音量键等正常行为。",
                ),
                e(
                    "status", "GET", "/dev/status", "设备总览：电量/温度/音量/屏幕/内存/网络/前台应用",
                    limits = "① 只反映**这一瞬间**，且 foreground 需要 Usage Access —— 没授权时该字段为空，这**不是**桥坏了。\n② temperature 在部分机型恒为 0（OEM 不暴露热区），不要据此判断设备凉热。\n③ 要连续变化请轮询 dev watch，不要靠反复调这个端点。",
                ),
                e(
                    "ping", "GET", "/dev/ping", "桥与无障碍是否在线（最快的一次健康检查）",
                    limits = "① 只证明桥在监听（App 进程活着）。它**不**证明引擎活着，也不证明无障碍服务可用 —— " +"那两项看 dev diag 与 dev status 的 accessibility 字段。\n② 返回值快（毫秒级），适合做重试前的探活，不适合当健康度总览。",
                ),
                e(
                    
                    "sensors", "GET", "/dev/sensors", "传感器读数",
                    cap = "sensor",
                    params = "type=accel|gyro|light|proximity|step|magnet|pressure（不给则读全部）",
                    examples = listOf("dev sensors", "dev sensors type=accel"),
                    limits = "① 一次性采样，不是流；要连续变化请自己轮询或改用 dev scene 做场景推断。\n② 机型缺的传感器返回 null 而**不是**报错 —— null 表示这台机器没有该硬件。\n③ step 依赖硬件计步模块，不少中端机没有。",
                ),
                e(
                    
                    "notifications", "GET", "/dev/notifications", "读当前通知列表（需通知使用权）",
                    cap = "notify_read", perm = "通知使用权（在权限中心开）",
                    params = "limit=20",
                    limits = "① 只返回**当前挂在通知栏**的；被用户划掉的读不到，系统不保留历史。\n② 正文可能被 App 隐藏（部分 IM 只放「有新消息」），此时 text 为空不是 bug。\n③ 只想判断「有没有必要读」时先用 dev notify-digest（只给计数不带正文）。",
                ),
                e(
                    
                    "media", "GET", "/dev/media", "当前媒体会话：标题/艺术家/进度/播放状态",
                    cap = "media", perm = "通知使用权（MediaSession 走通知访问）",
                    limits = "① 只能拿到正在投放通知栏的会话；后台无通知的播放器读不到。\n② position 是查询瞬间的快照，不是实时进度。\n③ 控制播放用 POST /dev/media，不是这个端点。",
                ),
                // 位置与拍照：2026-11-09 补登记。两者的能力代码与 CapabilityGate 常量
                // 早在 3080c31e 就写好了，但目录里一直没登记 —— 后果是 /dev/routes 报
                // 3 条漂移、dev 里查不到、MCP 工具清单里也没有，AI 根本不知道它们存在。
                e(
                    "location", "GET", "/dev/location",
                    "当前地理位置（一次快照，带 age_ms 与 stale 标记）",
                    cap = "location", perm = "ACCESS_FINE_LOCATION 或 ACCESS_COARSE_LOCATION",
                    params = "coarse=true（只要约 100 米精度时给，默认 5 位小数约 1 米）",
                    examples = listOf(
                        "dev location",
                        "dev location --coarse",
                        "dev api GET '/dev/location?coarse=true'",
                    ),
                    limits = "① 是 getLastKnownLocation 的**缓存**，不是实时定位：fix=false 表示还没有" +
                        "任何缓存（GPS 需要户外几秒），stale=true 表示缓存超过 5 分钟 —— " +
                        "两者都不能当成「用户现在就在这里」，调用方必须读 age_ms 自判。\n" +
                        "② 系统定位总开关关着时 location_enabled=false，但仍可能返回旧缓存；" +
                        "这是「设备能算但用户关了」，与权限缺失（403）不是一回事。\n" +
                        "③ providers 如实列出 gps/network/passive，缺哪个说明这台设备定不了哪种精度；" +
                        "模拟器通常只有 passive。\n" +
                        "④ 与 dev geofence 分开：围栏是**被动触发**（进/出某个圈才报），" +
                        "本端点才回答「我现在在哪」，两者受不同开关（location vs rf）。",
                ),
                e(
                    "camera", "POST", "/dev/camera",
                    "拍一张照片，返回 JPEG 的 base64、尺寸与镜头朝向",
                    cap = "camera", perm = "CAMERA（运行时权限，需用户授予）",
                    params = "facing=back|front（默认 back）；id=<相机 id>（多摄设备指定具体那颗）；" +
                        "quality=40~95（默认 85，太高会让 base64 体积与传输时间翻倍）；" +
                        "save=<路径>（给了就把 JPEG 也写到该路径，省一次 base64 解码）",
                    body = "{\"facing\":\"back\",\"quality\":85}",
                    examples = listOf(
                        "dev camera",
                        "dev api POST /dev/camera '{\"facing\":\"front\",\"quality\":70}'",
                        "dev api POST /dev/camera '{\"facing\":\"back\",\"save\":\"/sdcard/dsh/shot.jpg\"}'",
                    ),
                    limits = "① 走 camera2 直接出图，**不弹**系统相机界面，也不需要用户按快门 —— " +
                        "这是它与 am start 唤起相机 App 的本质区别（后者 AI 拿不到画面）。\n" +
                        "② 指定 facing/id 不存在时返回 404 并附 cameras 列表，**不会**静默退回后置：" +
                        "否则 AI 以为拍的是前置，实际拿到后置画面。\n" +
                        "③ 模拟器通常只有一个虚拟摄像头（后置返回一张测试图案），返回值里的 " +
                        "lens/facing 如实反映，别据此判断真实场景。\n" +
                        "④ 一次调用最多几秒（打开超时 5s / 拍摄超时 8s）；失败时相机也已被关闭。" +
                        "若进程被强杀导致相机没释放，用户手动开相机会黑屏，重启 App 即恢复。\n" +
                        "⑤ 与 dev record（录音）分开授权：愿意让 AI 听和愿意让 AI 看是两件事。",
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
                    limits = "① 同时存在 GET（读）与 POST（写）两条同 id 端点，method 决定行为 —— 别看错。\n② 时间参数是**毫秒时间戳**（begin_ms/end_ms），不是 ISO 字符串。\n③ 写入需要 WRITE_CALENDAR，未授权时 POST 会 403 而不是静默失败。",
                ),
                e(
                    
                    "contacts", "GET", "/dev/contacts", "搜通讯录",
                    cap = "personal_contacts", perm = "READ_CONTACTS",
                    params = "q=<关键词> limit=20",
                    examples = listOf("dev contacts", "dev contacts q=张三"),
                    limits = "① q 是**包含匹配**（不是前缀），结果按系统默认排序；limit 上限由系统 provider 决定。\n② 返回的是通讯录条目，不是通话记录 —— 后者用 dev phone。\n③ 同名联系人会重复出现（不同账号来源），去重请自己按号码做。",
                ),
                e(
                    
                    "sms", "GET", "/dev/sms", "读短信",
                    cap = "personal_sms", perm = "READ_SMS",
                    params = "q=<关键词> limit=20",
                    examples = listOf("dev sms", "dev sms q=验证码"),
                    limits = "① 同时存在 GET（读）与 POST（发）两条同 id 端点。\n② 只读**本机默认 SIM 收件箱**，不含其他 SIM / 加密短信。\n③ 发送走 POST /dev/sms，字段名是 to 不是 number（目录里只登记 to 那一条）。",
                ),
                e(
                    
                    "calls", "GET", "/dev/calls", "通话记录（v1.5.0-beta.10 新增：READ_CALL_LOG 早就授到却没端点用它）",
                    cap = "personal_calls", perm = "READ_CALL_LOG",
                    params = "limit=20",
                    limits = "① 需要 READ_CALL_LOG —— 不给权限时**整个端点不可用**，不是返回空列表。\n② 只含运营商通话记录，不含 VoIP（微信/QQ 语音）通话。\n③ limit 默认 20，取更多会被系统 provider 截断。",
                ),
            )
        ),
        Triple(
            "控制", "让设备做动作", listOf(
                e(
                    "control", "POST", "/dev/control", "开关：WiFi/蓝牙/音量/亮度/手电/震动/飞行/省电/勿扰/旋转/护眼/热点",
                    cap = "control",
                    params = "op=wifi|bt|volume|brightness|torch|vibrate（App 自身能做的）\n" +
                        "  op=wifi/bt   → enabled=true|false（Android 10+ 改不动，返回 user_confirmation_required）\n" +
                        "  op=volume    → value=0..max, stream=music|ring|alarm|notification|voice\n" +
                        "  op=brightness→ value=1..255\n" +
                        "  op=torch     → enabled=true|false\n" +
                        "  op=vibrate   → ms=1..10000\n" +
                        "op=system（系统级开关，需提权：Root 或 Shizuku）\n" +
                        "  target=airplane       → 飞行模式\n" +
                        "  target=battery_saver  → 省电模式\n" +
                        "  target=dnd            → 勿扰（真 zen_mode，不是靠铃音猜）\n" +
                        "  target=rotation       → 自动旋转\n" +
                        "  target=eye_comfort    → 护眼（ColorOS 私有键 / AOSP 夜灯双后端）\n" +
                        "  target=hotspot        → 热点（ssid / password / security=open|wpa2，**必须 root**）\n" +
                        "  action=status|on|off|toggle 缺省 status",
                    body = "{\"op\":\"system\",\"target\":\"airplane\",\"action\":\"on\"}",
                    examples = listOf(
                        "dev control op=torch enabled=true",
                        "dev control op=brightness value=128",
                        "dev control op=volume value=8 stream=music",
                        "dev control op=system target=airplane action=on",
                        "dev control op=system target=dnd action=toggle",
                        "dev control op=system target=hotspot action=on ssid=DSH password=12345678",
                    ),
                    limits = "① 没有 ringer / settings_action 这些 op（实测报 op must be wifi|bt|volume|brightness|torch|vibrate|system）。" +
                        "② torch 要字段名 enabled 不是 on。③ wifi/bt 的开关在 Android 13+ 被系统拦，返回 user_confirmation_required。" +
                        "④ brightness 需要 WRITE_SETTINGS（可 grant），否则 409 write_settings_required。" +
                        "⑤ op=system 六项都**没有公开 API**，一律走 settings/cmd 命令 + Privilege.runPrivileged；" +
                        "没 Root 也没 Shizuku 时返回 403 privileged_required。" +
                        "⑥ 每一项写完都会回读核对：requested 与 enabled 不一致时回 409 + mismatch=true，不要当成功。" +
                        "⑦ 热点在 Shizuku（shell uid 2000）下会被 SecurityException 拒绝，实际只有 root 能开。" +
                        "⑧ 护眼后端厂商相关：ColorOS 用 system.oplus_customize_custom_eye_protect_enable，" +
                        "AOSP 用 secure.night_display_activated；两族都没有时回 501 unsupported。" +
                        "⑨ 飞行模式会同时断 WiFi 与热点，这是系统行为不是 bug。",
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
                    limits = "① 与 GET /dev/media 同 id、靠 method 区分：这个负责**控制**，读状态看 GET。" +
                        "\n② volume 的两种单位容易混：0..1 是比例，0..max 是绝对值（按会话 max 换算）—— 传 1 想调" +
                        "「一格」会变成拉到满。" +
                        "\n③ stream-volume 改的是**系统音量档位**（music/ring/alarm/notification/voice），" +
                        "不是某个播放器的音量；两者独立。" +
                        "\n④ 只能控制正在投放通知栏的会话，后台无通知的播放器动不了。" +
                        "\n⑤ 每个 op 返回的是「请求已发出」，不保证播放器真的响应 —— 要确认结果再 GET 一次对比。",
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
                    limits = "① 与 GET /dev/calendar 同 id、靠 method 区分：这个负责**写**，读用 GET。" +
                        "\n② 时间参数是**毫秒时间戳**（begin_ms/end_ms），不是 ISO 字符串 —— 传字符串会被当成 0。" +
                        "\n③ 需要 WRITE_CALENDAR；只有 READ_CALENDAR 时这里会 403，而 GET 仍然可用。" +
                        "\n④ 不传 calendar_id 时写入系统默认日历；有多个日历时想写特定那个必须显式指定。" +
                        "\n⑤ 这是**真实写入用户日历**，会在对方的日历 App 里可见，没有撤销接口 —— 写错了只能让用户自己删。",
                ),
            )
        ),
        /*
         * ★应用管理（v1.6.x，2026-11-09 补登记）。
         *
         * 这一组只有 /dev/packages 一个端点，但它同时承担「看」与「改」，所以
         * 与「系统级（root）」分开：list/info 只要开了 cap.packages 就能用，
         * install/uninstall 额外走系统确认页 —— 不需要 root，但需要用户点头。
         */
        Triple(
            "应用管理", "装了什么 / 装 / 卸", listOf(
                e(
                    "packages", "GET", "/dev/packages",
                    "列出已安装应用（含包名/版本/label/是否系统应用），或查单个包的详情",
                    cap = "packages",
                    params = "op=list（默认）；limit=100；include_system=false；sort=name|size；" +
                        "op=info 时 pkg=<包名>（必填）",
                    examples = listOf(
                        "dev packages",
                        "dev packages limit=20 include_system=true",
                        "dev packages op=info pkg=com.tencent.mm",
                    ),
                    limits = "① total 是**可见**的包数，不是设备上的全部包 —— Android 11+ 默认只让" +
                        "第三方应用看到一部分包，visible_total 与 total 的差就是「被系统藏起来的」。\n" +
                        "② 没有 QUERY_ALL_PACKAGES 时，某些系统/别的用户下的包永远列不出来，" +
                        "这不是 bug，是包可见性限制。\n" +
                        "③ label 来自 PackageManager，可能是本地化名，不要用它做逻辑判断（用 pkg）。\n" +
                        "④ 只读：装/卸走 POST /dev/packages，且都要用户在系统页点头。",
                ),
                e(
                    "packages", "POST", "/dev/packages",
                    "安装 / 卸载应用（走系统确认页，返回 202 pending 而不是假成功）",
                    cap = "packages", perm = "安装还需「安装未知应用」授权（REQUEST_INSTALL_PACKAGES）",
                    params = "op=install 时 path=<APK 路径> 或 url=<下载地址>；op=uninstall 时 pkg=<包名>；" +
                        "dry_run=true 只验通路不弹窗；force=true 才允许卸系统应用",
                    body = "{\"op\":\"install\",\"path\":\"/sdcard/Download/app.apk\"}",
                    examples = listOf(
                        "dev packages op=install path=/sdcard/Download/app.apk",
                        "dev packages op=uninstall pkg=com.example.demo",
                        "dev api POST /dev/packages '{\"op\":\"install\",\"path\":\"/sdcard/a.apk\",\"dry_run\":true}'",
                    ),
                    limits = "① 返回 **202 + pending=true**，不是 200：系统确认页弹出后用户可能点取消，" +
                        "此时若回 200 就是谎报成功。装完必须用 op=info 复核是否真的在。\n" +
                        "② 不提供静默安装：install 一定弹系统确认页，这是 Android 的硬限制，不是没实现。\n" +
                        "③ APK 会先 stage 到 cacheDir/apk 再交给 FileProvider —— 直接给 FileProvider" +
                        "看不见的路径会失败。\n" +
                        "④ 默认拒绝卸载系统应用；force=true 是逃生阀，卸载系统应用可能让设备无法开机。\n" +
                        "⑤ 非 .apk 文件在进入系统流程前就被挡掉，不会白白弹一次确认页。",
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
                e(
                    "remind-list", "GET", "/dev/remind-list", "列出全部提醒", cap = "sched",
                    limits = "① 只列**本应用登记**的提醒，不是系统闹钟，也不是日历事件。\n② 已触发过的条目仍会列出（带 fired 标记），需要清理请用 remind-cancel。",
                ),
                e(
                    
                    "remind-cancel", "POST", "/dev/remind-cancel", "取消提醒",
                    cap = "sched", params = "{\"id\":\"...\"}", body = "{\"id\":\"1\"}",
                    limits = "① 必须传 id；不知道 id 就先 dev remind-list 查。\n② 取消不存在的 id 返回 ok=false 而不是报错，属于正常情况。",
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
                    "settings", "GET/POST", "/dev/settings",
                    "App 自身设置的读取与修改：主题/配色/布局/缩放/宠物/更新/引擎模式…",
                    params = "op=get|list|set|reset|reload（默认 GET 走 list）\n" +
                        "  get   -> key=<键名>  file=<可省略，自动定位>\n" +
                        "  list  -> file=<可省略，只看某个偏好文件>\n" +
                        "  set   -> key=<键名>  value=<值>  file=<可省略>\n" +
                        "  reset -> key=<键名>（删键回到默认）\n" +
                        "  reload -> 只重建当前页面，不改任何值",
                    body = "{\"op\":\"set\",\"key\":\"theme_mode\",\"value\":\"light\"}",
                    examples = listOf(
                        "dev settings op=list",
                        "dev settings op=get key=theme_mode",
                        "dev settings op=set key=theme_mode value=light",
                        "dev settings op=set key=settings_bg_blur value=50",
                        "dev settings op=reset key=theme_mode",
                        "dev settings op=reload",
                    ),
                    limits = "① **凭据读取一律脱敏**：lan_web_password / pet_pack_index_url 只回 " +
                        "{redacted,configured,length}，不回报原文（写入仍可设新值）；\n" +
                        "② **能力开关 cap.* 不在此列** —— 那是用户对 AI 的授权，只能由用户在权限中心" +
                        "手动打开，见 GET /dev/ping 的 capabilities 字段；\n" +
                        "③ effect 标注后果：safe=立即生效 / refresh=页面会重建 / engine=**需重启引擎才生效**" +
                        "（本端点不会替你重启，避免中断正在跑的会话）/ sensitive=涉及凭据；\n" +
                        "④ 未在说明表里的键仍可读写，但 effect=unknown，别假设它安全；\n" +
                        "⑤ 值类型按 JSON 推断：布尔/整数/小数/字符串/字符串数组；传 null 等于删键；\n" +
                        "⑥ 改完会调 recreate() 重建当前页面 —— 若当前有前台页面，用户会看到界面闪一下。",
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
                    "screenshot", "GET", "/screenshot", "截一张屏（PNG）——MCP 里会作为图片直接进对话",
                    perm = "需要无障碍服务在运行，且服务具备 canTakeScreenshot（开关一次服务即可授予）",
                    params = "max=<最长边像素，256..4096，默认原图>；save=1（保存到 files/screens 只回路径，自动清理到最近 12 张）",
                    examples = listOf(
                        "scr shot",
                        "dev api GET /screenshot?max=1024",
                        "dev api GET /screenshot?save=1",
                    ),
                    limits = "① 走 AccessibilityService.takeScreenshot，**不弹系统授权框**；代价是 API 30+ 才支持。" +
                        "\n② 默认返回 base64（一屏 PNG ≈ 100~200KB）——当文本读极浪费上下文。" +
                        "要看画面请用 MCP 的 screenshot 工具（image 块直接进对话），要落盘留证加 save=1。" +
                        "\n③ 读屏被敏感视图策略挡住时，截出来的是遮挡后的画面，不是黑屏 —— 这是系统行为。" +
                        "\n④ 悬浮窗（包括本应用自己的）会被一起截进去。" +
                        "\n⑤ 失败最常见的原因是服务缺少截图能力：到系统设置里把无障碍服务关掉再打开即可。",
                ),
                e(
                    "windows", "GET", "/dev/windows", "枚举当前所有可交互窗口（包名/类型/层级/bounds）",
                    cap = "status",
                    perm = "需要无障碍服务在运行",
                    params = "无参数（一次快照）",
                    limits = "① 只回答「有哪些窗口、谁在前台、各自多大」，不含窗口里的文本 —— " +
                        "要文本用 scr dump（无障碍树）或 root uidump（uiautomator，更完整但慢 300~900ms）。" +
                        "\n② 窗口本身没有包名，包名取自它的根节点；系统窗口（状态栏/导航栏）根节点常为 null，" +
                        "此时 pkg 是空串且 root_available=false，**不要**因此判定「读不到窗口」。" +
                        "\n③ 依赖 FLAG_RETRIEVE_INTERACTIVE_WINDOWS；没有它 getWindows() 只返回当前活动窗口。" +
                        "\n④ type 取值：application / input_method / system / accessibility_overlay / split_screen_divider。" +
                        "\n⑤ 耗时 1~5ms（无障碍内存态），可以放心高频调用。",
                ),
                e(
                    "node", "POST", "/dev/node", "节点语义动作：长按 / 展开折叠 / 关闭 / 反向读文本 / 方向滚动",
                    cap = "control",
                    params = "op=click|long_click|expand|collapse|dismiss|read|scroll|html_next|html_prev|select|clear_selection|input|back；" +
                        "除 scroll/back/clear_selection 外都要 text=<节点文字>，可选 by_desc=true 改按 contentDescription 找；" +
                        "scroll 用 direction=up|down|left|right 与 times（默认 1，上限 50）；" +
                        "**expect_pkg=<包名>** 传了就硬校验当前前台包，不符直接 409 不派发",
                    body = "{\"op\":\"click\",\"text\":\"蓝牙\",\"expect_pkg\":\"com.android.settings\"}",
                    examples = listOf(
                        "dev api POST /dev/node '{\"op\":\"long_click\",\"text\":\"聊天记录\"}'",
                        "dev api POST /dev/node '{\"op\":\"scroll\",\"direction\":\"down\",\"times\":3}'",
                        "dev api POST /dev/node '{\"op\":\"click\",\"text\":\"蓝牙\",\"expect_pkg\":\"com.android.settings\"}'",
                    ),
                    limits = "① 动作由**节点自己执行**，不算坐标 —— 不受悬浮层遮挡、触摸抖动、命中偏移影响。" +
                        "\n② read 是「选中 → ACTION_COPY → 读剪贴板」，专门解决 WebView / 自绘输入框 " +
                        "getText() 返回空串的问题。**密码框会被系统拦住拿不到明文**，这是预期行为，返回 ok=false。" +
                        "\n③ read 会临时改动剪贴板，成功读到后会自动还原原内容。" +
                        "\n④ performAction 返回 true 只代表系统接受了请求，不代表界面真变了 —— 需要确定性时用 /dev/verify 复核。" +
                        "\n⑤ scroll 用 API 23 的方向 action，比 SCROLL_FORWARD 精确；但节点不支持该方向时返回 scrolled=0。" +
                        "\n⑥ html_next / html_prev 按 HTML 元素粒度跳（element=LINK|CONTROL|BUTTON|LIST|TABLE|SECTION），" +
                        "是 WebView 里唯一能区分「这是链接、那是按钮」的通道 —— 扁平文本树里没有这个结构信息。" +
                        "\n⑦ select / clear_selection 改的是无障碍选中态，不是视觉高亮；某些列表的批量操作按钮" +
                        "要「有选中项」才生效，只能靠它建立。clear_selection 不需要 text。" +
                        "\n⑧ ★ 想操作**虚拟屏**上的控件必须传 display_id（值取 /dev/vscreen 的 display_id 或 /dev/windows 每项的 display_id）：虚拟屏的窗口不是 rootInActiveWindow（焦点在物理屏），不传这个参数时节点动作只在物理屏上找，会一律 not found。" +
                        "节点动作只看得到窗口树；虚拟屏若没有活动的无障碍窗口（getRoot() 返回 null），只能用 vscreen 的 tap 坐标操作。" +
                        "\n⑨ ★ **点击是动作的起点，屏幕状态却是上一轮留下的** —— 在旧快照上照着坐标/文字继续点，会点到上一个动作把屏幕切走之后的界面（实测：点「轨迹」切到 QQ，下一次仍按旧快照找「时长」，结果点开了 QQ 聊天）。" +
                        "要防这个，把快照里记下的 pkg 作为 expect_pkg 传进来：不符时**直接 409 不派发**（reason:\"foreground-changed\"），而不是等你 verify 超时。" +
                        "\n⑩ 不给 expect_pkg 时，动作会与「上次动作时的前台包」比对；不一致就在响应里带 foregroundChanged:true + reason:\"foreground-changed\"（**不拒绝**，跨 App 连续操作是正常需求）。" +
                        "这个信号只说明「前台换了」，不说明是谁换的 —— 你上一步自己的操作、用户、第三方都可能。" +
                        "\n⑪ click 的响应带 hit:{pkg,text,role,bounds}，是**动作前**命中的节点快照。动作成功但结果不对时先看它：" +
                        "点到了别的包/别的控件，问题在定位而不是在动作。密码节点的 text 一律是 [PASSWORD]。",
                ),
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
                        "timeout_ms=<默认 4000，范围 200..30000>\n" +
                        "expect_pkg=<可选，你认为现在应该在前台的包名>",
                    body = "{\"expect\":\"确定\",\"gone\":true}",
                    examples = listOf(
                        "dev api POST /dev/verify '{\"expect\":\"保存成功\",\"gone\":false}'",
                        "dev api POST /dev/verify '{\"expect\":\"已开启\",\"expect_pkg\":\"com.android.settings\"}'",
                    ),
                    limits = "expect 必填（空则 400 expect required）。没有 text 字段。" +
                        "\n★ 失败时必看 reason，它区分两种完全不同的下一步：" +
                        "\n  · reason=\"text-not-found\" —— 全程停在同一个前台应用，expect 就是没出现。" +
                        "这一步没生效，换定位方式重试是对的。" +
                        "\n  · reason=\"foreground-changed\" —— 等待期前台应用换了（foreground_history 给出变化序列）。" +
                        "此时「没找到 expect」是当然的，它不在这一页上；**别再顺着旧快照点**，先 dump 找当前位置。" +
                        "\n  · reason=\"wrong-screen\" —— 你传的 expect_pkg 一开始就不符，立刻返回不浪费 timeout。" +
                        "\n背景：实测过一次「点轨迹切到 QQ，下一次仍按旧快照找时长，结果点开 QQ 聊天」——" +
                        "没等到和交流对象换了是两件事，混成一个 timeout 就只能瞎猜。" +
                        "\n反过来说：`reason:\"foreground-changed\"` 只说明前台变了，不说明是谁改的（你上一步、用户、第三方都可能）。",
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
                e(
                    "vscreen-status", "GET", "/dev/vscreen", "虚拟屏状态：建没建 / 多大 / 已取多少帧",
                    cap = "vscreen", perm = "root",
                    examples = listOf("dev vscreen-status", "dev api GET /dev/vscreen"),
                    limits = "只读，不建屏。active=false 表示当前没有虚拟屏在跑。\n" +
                        "frame_seq 是累计帧计数 —— 它一直不涨说明那块屏上没有任何东西在重绘。",
                ),
                e(
                    "vscreen", "POST", "/dev/vscreen",
                    "虚拟屏：给 AI 开一块**不显示在物理屏上**的第二块屏，把 App 启过去操作",
                    cap = "vscreen", perm = "root（createVirtualDisplay 的 TRUSTED flag 要签名级 ADD_TRUSTED_DISPLAY）",
                    params = "op=start|status|stop|shot|launch|tap|swipe|key|type|journal\n" +
                        "  start  → 建屏（默认 720x1280，随物理屏方向）\n" +
                        "  launch → pkg=<包名 或 包名/类名>，把 App 启到虚拟屏\n" +
                        "  shot   → 截当前帧，返回 path 与 frame_seq\n" +
                        "  tap    → x= y= [frame_seq=]（虚拟屏坐标，不是物理屏）\n" +
                        "  swipe  → x1= y1= x2= y2= [ms=] [frame_seq=]\n" +
                        "  key    → code=<Android KeyEvent keycode，如 3=HOME、4=BACK>\n" +
                        "  type   → text=<ASCII>（中文要用 key/无障碍）\n" +
                        "  stop   → 销毁屏（不调用的话核心进程会一直留着）",
                    body = "{\"op\":\"launch\",\"pkg\":\"com.android.settings/.Settings\"}",
                    examples = listOf(
                        "dev vscreen op=start",
                        "dev vscreen op=launch pkg=com.android.settings/.Settings",
                        "dev vscreen op=shot            （拿 frame_seq）",
                        "dev vscreen op=tap x=360 y=640 frame_seq=<shot 返回的那个>",
                        "dev vscreen op=stop",
                    ),
                    limits = "★ 这是本项目最强的能力之一，请按它的实际后果来用：\n" +
                        "① 只有 ROOT 权限模式可用；Shizuku 能建屏但拿不到 TRUSTED flag，且 am start --display 起不了非导出组件。\n" +
                        "② 虚拟屏上的操作**用户完全看不见**，物理屏照常可用 —— 别用它做用户不知情的事。\n" +
                        "③ 坐标是虚拟屏的（默认 720x1280），不是物理屏的 1216x2640，别混用。\n" +
                        "④ 刚 launch 完要等首帧（内部最多等 3 秒），否则会报 FRAME_NOT_READY。\n" +
                        "④b **tap/swipe 必须带 frame_seq**（取自 op=shot）：核心会校验你看到的就是当前帧，" +
                        "不对就返回 STALE_FRAME。不带则不校验 —— 那是留给 input -d 手工调试的，AI 不要省这一步。\n" +
                        "⑤ uiautomator dump 不支持 --display，只看得到物理屏的树；要看虚拟屏的树用 /dev/windows 或让核心返回截图自己看图。\n" +
                        "⑥ 用完后请 stop。核心是 app_process 起的独立进程，ppid 会被 init 收养，App 被杀带不走它。",
                ),
            )
        ),
        Triple(
            "任务记忆", "录制过的任务步骤 —— 查、改、纠错、删", listOf(
                e(
                    "memory_lookup", "POST", "/memory/v2/lookup",
                    "按意图检索历史任务记忆（向量 + 屏幕指纹），命中可直接重放",
                    params = "intent=<要做什么>  fp=<当前屏幕指纹，逗号分隔，可选>  limit=<条数，默认 3>",
                    examples = listOf("dev memory_lookup intent=打开显示设置"),
                    limits = "① 返回字段是 ranked（不是 items/results），每项带 cos/fp/anchor/score/tier；\n" +
                        "② tier=high 才适合直接重放，mid/low 只作参考；\n" +
                        "③ engine=bge 才是真语义；engine=hash 表示模型没起、只是字符哈希兜底，" +
                        "同义改写（「打开侧栏」vs「展开右边」）几乎命中不了。看 " +
                        "/memory/v2/stats 的 semantic.ready 确认，没就绪先 POST /local/ensure；" +
                        "\n④ fp 是「记忆侧覆盖率」，不是对称 Jaccard：当前屏多出内容不扣分，" +
                        "只有记忆里的要素消失才掉分。fp=1 只说明「记的那些都还在」；" +
                        "\n⑤ 这是 **POST**。用 GET 会 404（路由按方法精确匹配）。v1 的 " +
                        "/memory/lookup 已停用，别混用。",
                ),
                e(
                    "memory_list", "GET", "/memory/v2/list",
                    "列出已存的记忆（可按包名/状态过滤）",
                    params = "pkg=<包名，可选>  state=<active|disabled|pending，可选>",
                    examples = listOf("dev memory_list", "dev memory_list pkg=com.android.settings"),
                    limits = "只读。要看单条完整步骤用 memory_get。",
                ),
                e(
                    "memory_get", "GET", "/memory/v2/get",
                    "取一条记忆的完整内容（步骤、校验条件、修订历史）",
                    params = "id=<记忆 id>",
                    examples = listOf("dev memory_get id=m_1234"),
                    limits = "id 不存在返回 no such memory（不是空对象）。",
                ),
                e(
                    "memory_save", "POST", "/memory/v2/upsert",
                    "新建或整体覆盖一条任务记忆（AI 干完一件事后自己存）",
                    params = "pkg=<包名>  intent=<一句话目标>  title=<短标题，可选>  steps=<步骤数组，用 dev api 发 JSON>",
                    examples = listOf("dev api POST /memory/v2/upsert '{\"pkg\":\"com.android.settings\",\"intent\":\"打开显示设置\"}'"),
                    limits = "① pkg 与 intent 必填；\n" +
                        "② 带 id 是覆盖同一条（rev++，旧版进 history）；\n" +
                        "③ 步骤里 input 的文本不会被存（只记长度），存了也重放不出来。",
                ),
                e(
                    "memory_patch", "POST", "/memory/v2/patch",
                    "修某一步的目标文本 / 动作 / 校验条件（改错就是改这里）",
                    params = "id=<记忆 id>  step=<从 0 开始的步号>  targetText=<新目标文本，可选>  action=<新动作，可选>",
                    examples = listOf("dev api POST /memory/v2/patch '{\"id\":\"m_1234\",\"step\":0,\"targetText\":\"显示\"}'"),
                    limits = "每次成功 rev++ 并把旧版写进 history，可用 memory_rollback 回退。",
                ),
                e(
                    "memory_label", "POST", "/memory/v2/label",
                    "纠正一条记忆的意图/标题（人话说法不对时用它）",
                    params = "id=<记忆 id>  intent=<新的目标描述>  title=<新标题，可选>",
                    examples = listOf("dev api POST /memory/v2/label '{\"id\":\"m_1234\",\"intent\":\"把亮度调低\"}'"),
                    limits = "只改意图与标题，不动步骤。检索靠 intent，改完立即影响召回。",
                ),
                e(
                    "memory_state", "POST", "/memory/v2/state",
                    "启用 / 停用 / 标记可疑（不想删但要它别被自动用）",
                    params = "id=<记忆 id>  state=<active|disabled|pending|suspect>",
                    examples = listOf("dev api POST /memory/v2/state '{\"id\":\"m_1234\",\"state\":\"disabled\"}'"),
                    limits = "disabled 的记忆不会进检索结果，随时可以再置回 active。",
                ),
                e(
                    "memory_delete", "POST", "/memory/v2/delete",
                    "删除一条记忆（真删，不进回收站）",
                    params = "id=<记忆 id>（query 或 JSON body 都认）",
                    examples = listOf("dev memory_delete id=m_1234"),
                    limits = "删除不可撤销；只想停用请用 memory_state 置 disabled。",
                ),
            ),
        ),
        Triple(
            "权限自助", "查 / 授 / 撤", listOf(
                e(
                    "perms", "GET", "/dev/perms", "权限总览：声明数 / 已拿到 / 还差多少",
                    limits = "① **异步**：立刻返回 started，后台慢慢跑（上千条 pm grant 要一两分钟）。\n② 要等结果用 dev perms wait，或轮询 perms_status 直到 running=false。\n③ pm grant 会因 gid 变更**杀掉本 App 进程**，引擎会重启 —— 这是系统行为，不是崩溃。",
                ),
                e(
                    "perms/status", "GET", "/dev/perms/status", "权限详情（含后台授权进度）",
                    limits = "① 只反映本应用自己已声明的权限，不是系统全局权限表。\n② 大批量授权期间 running=true，此时读到的计数是**中间态**。",
                ),
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
            "Shizuku（shell）", "ADB shell 身份（uid 2000）—— 不需要 root，但能力有边界", listOf(
                e(
                    "shz", "GET|POST", "/dev/shz", "Shizuku / ADB shell 通道：状态查询与命令执行",
                    cap = "shizuku",
                    perm = "Shizuku 服务在运行 + 本应用已授权",
                    params = "op=status|exec；exec 需 cmd=<命令>，可选 timeout_ms（1000~120000，默认 10000）",
                    body = "{\"op\":\"exec\",\"cmd\":\"pm list packages | wc -l\"}",
                    examples = listOf(
                        "shz status",
                        "shz exec \"settings get secure android_id\"",
                        "shz uidump",
                        "shz appdocs com.android.providers.telephony db mmssms.db",
                    ),
                    limits = "① **是 HTTP 端点，不是门脚本直连**：Shizuku 是 App 进程持有的 binder，" +
                        "引擎子进程拿不到，只能经桥中转 —— 这是它与 root 门脚本最本质的区别。" +
                        "\n② 身份是 uid 2000 / u:r:shell:s0。能：读别的 App 的 databases 与 shared_prefs、" +
                        "sqlite3 查询、uiautomator dump、input 事件、pm/am/cmd、settings put、" +
                        "读 /data/misc/bluetooth、kill 进程。不能：iptables（需 root）、写 SELinux 策略、" +
                        "读属主为具体 uid 且非 world-readable 的文件。" +
                        "\n③ 不需要 confirm：shell 权限本身有边界，破坏面远小于 uid 0，" +
                        "再加 dry-run 只会让每次调用多一轮往返。" +
                        "\n④ 超时硬性（默认 10s）：Shizuku binder 卡住不会自己回来，" +
                        "实测会把桥请求线程一起挂住。" +
                        "\n⑤ 与 cap.root 独立授权：有 root 的用户也可能选 Shizuku（不想让引擎以 uid 0 常驻）。" +
                        "\n⚠️ 陷阱：`su 2000 -c cmd` **不等于** shell —— uid 切到 2000 但 SELinux 域仍是 " +
                        "u:r:su:s0，权限看起来大得多。验证 shell 边界必须用真实 adb shell。",
                ),
            )
        ),
        Triple(
            "系统级（root）", "进程 / 文件 / 属性 / 服务 / 包 —— 需要 ROOT 模式", listOf(
                e(
                    "root", "CLI", "gates/root", "系统级操作门脚本（不是 HTTP 端点，直接命令行调用）",
                    cap = "root",
                    perm = "必须 ROOT 权限模式（引擎以 uid 0 / u:r:ksu:s0 运行）",
                    params = "info | ps | kill | cat | ls | rm | undo | cp | mv | chmod | chown | " +
                        "prop | svc | pm | settings | net | mount | journal | uidump | appdata | content | appops | " +
                        "binder | sendevent | exec | help",
                    examples = listOf(
                        "root info",
                        "root ps QQ",
                        "root settings get secure android_id",
                        "root prop get ro.product.model",
                        "root net iptables",
                        "root journal 200 filter=AndroidRuntime",
                        "root uidump",
                        "root appdata com.tencent.mm db EnMicroMsg.db",
                        "root content query content://settings/system projection=name:value",
                        "root appops get com.tencent.mm",
                        "root binder list filter=clipboard",
                        "root binder call clipboard 1 confirm=1",
                        "root sendevent /dev/input/event0 1 116 1",
                    ),
                    limits = "① 不是桥端点：和 hid 同源，由门脚本在引擎进程（root）里直接执行，不经 App 进程的 su。" +
                        "\n② 危险操作默认 dry-run：rm / exec / prop set / pm uninstall 必须显式 confirm=1。" +
                        "\n③ rm 不是真删除：移到 /data/local/tmp/dsh-root-trash/<batch>/，root undo <batch> 可恢复。" +
                        "\n④ kill 拒绝 init(1) / zygote / system_server / surfaceflinger / servicemanager / logd / vold / netd，以及自己。" +
                        "\n⑤ 绝不改 SELinux 策略（不碰 setenforce / supolicy）：改了整机失去保护，且大概率开机失败。" +
                        "\n⑥ 全部输出是单行 JSON，方便直接解析。" +
                        "\n⑦ content / appops 写操作默认 dry-run：insert/update/delete 与 appops set/reset 都要 confirm=1。" +
                        "\n⑧ content 与 appdata 共用同一档隐私开关（cap.appdata）；appops 受 cap.root 约束。" +
                        "\n⑨ content 没有 --limit（命令本身不支持），要少取行只能靠 where 收窄或 sort 定序后自己截断。" +
                        "\n⑩ appops set 是**以系统身份替别的 App 授权**，用户不会收到任何提示 —— 只在明确需要时用。" +
                        "\n⑦ uidump 走 uiautomator，是无障碍之外的第二条读屏通道（WebView/Flutter/游戏/自绘界面" +
                        "常无无障碍节点）。开销 300~900ms，是兜底不是默认；scr dump 在无障碍树为空时已自动回落，" +
                        "只有要原始 XML 才直接调。" +
                        "\n⑧ appdata 要**另一个独立开关** cap.appdata：root 打开了也不等于允许读 —— 那是隐私边界不是控制权。" +
                        "只读：SELECT/WITH 之外全部拒绝，二进制文件拒绝返回，路径遍历与非法包名拒绝；" +
                        "不给 SQL 时只回表名/行数/列名，不泄数据。每次成功读取都会发用户可见通知。",
                ),
            )
        ),
        Triple(
            "Linux 容器", "手机里那个完整 Linux —— 状态、发行版、执行命令", listOf(
                e(
                    "linux/status", "GET", "/dev/linux/status",
                    "容器总览：当前发行版/是否部署/引擎模式/磁盘/已装包数",
                    cap = "linux",
                    limits = "① 整组 Linux 能力受一个开关（cap.linux），默认关闭 —— " +
                        "因为同组里的 exec 等价于给一个容器内 shell。\n" +
                        "② `deployed=false` 表示 rootfs 还没解包，此时 exec 会返回 409。\n" +
                        "③ `engine_mode=native` 说明现在引擎跑在原生侧，容器只是躺着没被使用。",
                    examples = listOf("dev linux/status"),
                ),
                e(
                    "linux/distros", "GET", "/dev/linux/distros",
                    "全部可选容器（含 flavor）：体积、是否已部署、是否激活",
                    cap = "linux",
                    limits = "① 同一 family 的多条**共享一个 rootfs 目录**（见 shared_rootfs 字段），" +
                        "切换 flavor 只改下次启动装哪些额外包，不复制文件。\n" +
                        "② 已装的额外包**不会**因为切回 BASE 而被删掉。\n" +
                        "③ extracted_bytes 是解压后占用，拿它跟 free_bytes 比再决定要不要装。",
                    examples = listOf("dev linux/distros"),
                ),
                e(
                    "linux/switch", "POST", "/dev/linux/switch",
                    "切换激活的容器形态",
                    cap = "linux",
                    params = "family=<必填，如 alpine|alpine323|ubuntu>；flavor=<可选，base|dev|gui>",
                    body = "{\"family\":\"alpine\",\"flavor\":\"gui\"}",
                    limits = "① 只改选择，**不动任何文件** —— 已部署的别的发行版仍在磁盘上。\n" +
                        "② 切完要重启引擎才生效。\n" +
                        "③ 目标未部署时会先做磁盘检查，不够返回 507。",
                    examples = listOf(
                        "dev linux/switch family=alpine flavor=gui",
                        "dev linux/switch family=alpine323",
                    ),
                ),
                e(
                    "linux/deploy", "POST", "/dev/linux/deploy",
                    "部署/重新部署当前容器",
                    cap = "linux",
                    params = "force=false（默认）。force=true 会**删掉整个 rootfs 重装**",
                    body = "{\"force\":false}",
                    limits = "① 已部署且 force=false 时直接返回 already_deployed，不做任何事。\n" +
                        "② force=true 会 purge 用户装过的所有包 —— 只在容器彻底坏掉时用。\n" +
                        "③ 首次部署 Alpine 约 1~3 秒（本地资产）；Ubuntu/3.23 要联网下载，30 秒量级。",
                    examples = listOf("dev linux/deploy", "dev linux/deploy force=true"),
                ),
                e(
                    "linux/exec", "POST", "/dev/linux/exec",
                    "在容器里跑一条命令，返回 stdout/stderr/exit_code",
                    cap = "linux",
                    params = "cmd=<必填>；timeout_ms=<可选，默认 30000，上限 120000>",
                    body = "{\"cmd\":\"uname -m && cat /etc/alpine-release\"}",
                    limits = "① **不是 pty**：read/vi 这类要交互的命令会立刻拿到 EOF。\n" +
                        "② 容器里拿不到 DSH_BRIDGE_TOKEN，所以**不能在容器里再调 bridge 端点**。\n" +
                        "③ 硬超时 120 秒；长任务用 `nohup ... &` 起后台再轮询，别阻塞等。\n" +
                        "④ stdout 与 stderr 分开返回，不合并 —— 便于区分「没输出」与「报错了」。",
                    examples = listOf(
                        "dev linux/exec cmd='uname -m'",
                        "dev linux/exec cmd='apk add --no-progress curl' timeout_ms=60000",
                    ),
                ),
            )
        ),
        Triple(
            "任务化执行", "把编译这类长活离机跑：提交 → 轮询 → 取产物", listOf(
                e(
                    "jobs/submit", "POST", "/dev/jobs",
                    "提交一个容器内任务，立即返回 jobId；执行在后台，最多 2 个并发",
                    cap = "linux",
                    body = "{\"cmd\":\"make -j2\",\"workdir\":\"/ws\",\"timeout_ms\":600000,\"label\":\"build\"}",
                    limits = "① 整组受 cap.linux 门控，与 /dev/linux/exec 同一个开关。\n" +
                        "② 返回 **202** 不是 200 —— 意思是「已受理」，不是「已完成」。\n" +
                        "③ 同时最多 2 个任务在跑（WORKERS=2），超出的排队等；队列里的状态是 queued。\n" +
                        "④ 单任务硬超时默认 5 分钟、上限 20 分钟，到点标记 state=timeout。\n" +
                        "⑤ 容器没部署时直接 409，先调 /dev/linux/deploy。",
                    examples = listOf(
                        "dev api POST /dev/jobs '{\"cmd\":\"make -j2\",\"label\":\"build\"}'",
                    ),
                ),
                e(
                    "jobs/list", "GET", "/dev/jobs",
                    "最近的任务（默认 20 条，含 state / exit_code / 耗时 / 输出字节数）",
                    cap = "linux",
                    limits = "① 按 jobId 倒序（jobId 前缀是时间戳），新的在前。\n" +
                        "② 只保留最近 50 个任务目录，多余的按开始时间删最老的**已终态**任务。\n" +
                        "③ state 取值：queued / running / success / failed / timeout / cancelled / error。\n" +
                        "④ `error` 是任务体自身抛异常（不是命令退出码非零），message 在 error 字段。",
                    examples = listOf("dev jobs", "dev api GET '/dev/jobs?limit=5'"),
                ),
                e(
                    "jobs/get", "GET", "/dev/jobs/get",
                    "单个任务的详情（必须带 id）",
                    cap = "linux",
                    limits = "① id 必填，缺了返 400；不存在的 id 返 404。\n" +
                        "② 详情里没有日志正文 —— 日志走 /dev/jobs/log，避免每次轮询搬几百 KB。",
                    examples = listOf("dev api GET '/dev/jobs/get?id=job-20261114-...'"),
                ),
                e(
                    "jobs/log", "GET", "/dev/jobs/log",
                    "读任务日志尾部（默认 stdout 最后 200 行）",
                    cap = "linux",
                    limits = "① 默认读 stdout，`stream=stderr` 读错误流。\n" +
                        "② 只读尾部，最多往回扫 512 KB；`lines` 夹在 1..2000。\n" +
                        "③ 单条日志文件上限 8 MiB，超了截断并追加 `[truncated: ...]` 标记 —— " +
                        "一条 `yes` 不该有写满磁盘的权力。\n" +
                        "④ 任务还没产生输出时返回空字符串，不是 404。",
                    examples = listOf(
                        "dev api GET '/dev/jobs/log?id=job-...&lines=50'",
                        "dev api GET '/dev/jobs/log?id=job-...&stream=stderr'",
                    ),
                ),
                e(
                    "jobs/cancel", "POST", "/dev/jobs/cancel",
                    "请求取消任务（协作式：当前命令返回后标记 cancelled）",
                    cap = "linux",
                    limits = "① **不承诺立刻终止**：置标志位，命令返回后才标终态。\n" +
                        "② 已终态的任务返 409，并带上真实 state（不要在 UI 里假装成功）。\n" +
                        "③ 取消后即使命令成功了，state 也是 cancelled —— 谁下的令谁负责。",
                    examples = listOf("dev api POST /dev/jobs/cancel '{\"id\":\"job-...\"}'"),
                ),
                e(
                    "jobs/artifact", "GET", "/dev/jobs/artifact",
                    "取任务产物（编译出的二进制等），返回 base64 + sha256",
                    cap = "linux",
                    limits = "① path 相对任务目录；`..` 穿出目录会被拒（规范比对，不是字符串找 `..`）。\n" +
                        "② 单文件上限 **8 MiB** —— bridge 响应只能是字符串，base64 会膨胀 33%。" +
                        "更大的产物走 /dev/open-file 或 peersync。\n" +
                        "③ 必须用返回的 sha256 校验：JSON 里塞 base64 一旦被中间层改动，" +
                        "拿到手的二进制会静默损坏。",
                    examples = listOf(
                        "dev api GET '/dev/jobs/artifact?id=job-...&path=out/app'",
                    ),
                ),
            )
        ),
        Triple(
            "扩展与诊断", "环境自检", listOf(
                e(
                    "ext/list", "GET", "/ext/list", "已安装的扩展（android-buildtools / rust / vim 等）",
                    limits = "① 只列环境扩展（android-buildtools / rust / vim 这类），不含插件 —— 插件看 plugin_list。\n② 列表来自已落盘的扩展目录，正在安装中的项可能还没出现。\n③ 每项都带 mode（native / linux）与 available。两种模式的状态**来自不同数据源**（Termux marker 文件 / 容器包数据库），所以看到 red 不等于设备上没有 —— 先读 mode 再下结论。\n④ available=false 时看 note 给的原因（该发行版仓库里没有这些包，或条目只在 Linux 模式可用）；rootOnly=true 表示能装但没 root 用不了。",
                ),
                e(
                    "plugin/list", "GET", "/plugin/list", "已安装的插件",
                    limits = "① 只列已安装插件，不含插件市场里可装但未装的项（那是 /market/catalog.json）。\n② 插件启停状态要看对应 profile 的配置，不只看这里。",
                ),
                e(
                    "diag", "GET", "/diag", "桥自诊断：端口 / token / 无障碍 / 各能力开关",
                    limits = "① 这是排查用的总览：端口 / token 状态 / 数据目录 / 运行时信息。\n② token 字段只报**是否已生成**，绝不返回明文 —— 不要试图从这里拿 token。\n③ 引擎没起来时它仍能返回（桥在 App 进程），所以「diag 通」不等于「引擎就绪」。",
                ),
            )
        ),
        Triple(
            "屏幕动作", "点按划转读屏：AI 的第二只手", listOf(
                e(
                    "tap", "POST", "/tap",
                    "点击：按坐标 / 按文本 / 按 desc 点击，文本打分匹配（trim + 忽略大小写）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "x=<坐标> y=<坐标>；或 text=<可见文本>；或 desc=<contentDescription>；index=<多个候选时取第几个，默认 0>；allowAmbiguous=true 时同分候选也点（默认拒绝）",
                    body = "{\"text\":\"发送\"}",
                    examples = listOf(
                        "dev api POST /tap '{\"text\":\"发送\"}'",
                        "dev api POST /tap '{\"x\":540,\"y\":2100}'",
                    ),
                    limits = "① 文本匹配按包含关系打分，同分不会瞎猜：返回 409 ambiguous 并列出候选，需自己指定 index 或坐标。\n② 无障碍点击是异步派发，返回 ok 只代表动作已投递，界面反应要再读一次 /screen 确认。\n③ 保护页（权限/安装/支付弹窗）会被 403 拦下，用 /handoff 交给人。",
                ),
                e(
                    "tap-id", "POST", "/tap-id",
                    "按 viewId（resource-id）点击，最稳定的定位方式",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "id=<必填，viewId 全名或尾段>",
                    body = "{\"id\":\"com.tencent.mm:id/send_btn\"}",
                    examples = listOf(
                        "dev api POST /tap-id '{\"id\":\"send_btn\"}'",
                    ),
                    limits = "① 纯 Compose / Flutter 界面没有 viewId，会 404；改用 /tap text 或 /tap-ref。\n② 同名 id 在列表里出现多次时点第一个，需要精确控制就先 /screen 拿坐标再 /tap。",
                ),
                e(
                    "tap-ref", "POST", "/tap-ref",
                    "按读屏文档里的序号点击（先 /screen?format=doc 拿 screenId，再引用序号）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "screen=<必填，读屏返回的 screenId，如 s7>；id=<必填，文档里的节点序号>",
                    body = "{\"screen\":\"s7\",\"id\":12}",
                    examples = listOf(
                        "dev api POST /tap-ref '{\"screen\":\"s7\",\"id\":12}'",
                    ),
                    limits = "① 这是最严格的点击方式：文档过期、节点消失、界面已换屏都会拒绝而不是点到别处 —— 宁可失败也不误点。\n② screenId 与序号都来自上一次读屏，隔太久必然对不上。\n③ 它自己做过期校验，不靠保护页的宽松判据，所以别的端点被 403 时它也可能直接拒绝。",
                ),
                e(
                    "gesture", "POST", "/gesture",
                    "手势：滑动 / 长按 / 拖拽（支持人味轨迹与过冲回弹）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "type=swipe|long_press|drag（默认 swipe）；x1,y1,x2,y2 起止坐标；durationMs=<默认 300>；human=true 走拟人轨迹；overshoot=<0~1 过冲比例>；长按用 x,y",
                    body = "{\"type\":\"swipe\",\"x1\":540,\"y1\":1800,\"x2\":540,\"y2\":600,\"durationMs\":400}",
                    examples = listOf(
                        "dev api POST /gesture '{\"x1\":540,\"y1\":1800,\"x2\":540,\"y2\":600}'",
                    ),
                    limits = "① 距离过短的滑动会被系统判成点击，下拉刷新这类动作请给足 300px 以上。\n② 长按与拖拽的时长下限受系统 LONG_PRESS_TIMEOUT 影响，低于 500ms 基本无效。\n③ human=true 会加 15~30% 时长，更慢但更不容易被风控识别，批量动作别默认开。",
                ),
                e(
                    "input", "POST", "/input",
                    "向当前焦点输入框写入文本（可追加）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "text=<必填>；target=<可选，先点这个文本再输入>；append=true 时追加而非替换",
                    body = "{\"text\":\"你好\",\"append\":false}",
                    examples = listOf(
                        "dev api POST /input '{\"text\":\"你好\"}'",
                    ),
                    limits = "① 依赖当前有焦点输入框；没有焦点时返回 409 并提示先 /tap 输入区。\n② 输入内容不会进入自动录制的轨迹（隐私，明确排除）。\n③ 部分金融类 App 的自绘键盘接受不了无障碍粘贴，只能逐字模拟。",
                ),
                e(
                    "key", "POST", "/key",
                    "按物理/系统按键：back / home / recents / enter / delete 等",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "action=<必填，back|home|recents|enter|delete|tab|escape|up|down|left|right>",
                    body = "{\"action\":\"back\"}",
                    examples = listOf(
                        "dev api POST /key '{\"action\":\"home\"}'",
                    ),
                    limits = "① back/home/recents 是导航键，在保护页上也放行（否则 AI 会被困在设置页里）。\n② 其余按键仍受保护页闸门约束。\n③ 音量/电源这类系统键不在这里，用 /dev/control 或 /dev/hid。",
                ),
                e(
                    "wait", "POST", "/wait",
                    "等界面稳定或某个文本出现/消失",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "text=<等待出现的文本>；desc=<等待出现的描述>；gone=true 改为等它消失；timeoutMs=<默认 5000>",
                    body = "{\"text\":\"加载完成\",\"timeoutMs\":8000}",
                    examples = listOf(
                        "dev api POST /wait '{\"text\":\"首页\"}'",
                    ),
                    limits = "① 超时返回 408 而不是 ok —— 别把超时当成功继续下一步。\n② 判据是读屏文本，纯图形界面（图片按钮）等不到，改用 /frame-wait。\n③ 默认 5 秒；网络慢的页面要显式调大，否则会在加载中途放行。",
                ),
                e(
                    "wait-text", "POST", "/wait-text",
                    "wait 的纯文本特化版（参数更少，语义更明确）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "text=<必填>；gone=true 等它消失；timeoutMs=<默认 5000>",
                    body = "{\"text\":\"支付成功\"}",
                    examples = listOf(
                        "dev api POST /wait-text '{\"text\":\"支付成功\"}'",
                    ),
                    limits = "① 与 /wait 同源，超时同样返回 408。\n② 只认可见文本，不含 contentDescription。",
                ),
                e(
                    "batch", "POST", "/batch",
                    "在一个请求里连续执行多步动作（省往返、省超时）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "steps=<必填数组，每步 {action,args}>；stopOnError=true 遇错即停；settleMs=<每步之间等多久，默认 250>",
                    body = "{\"steps\":[{\"action\":\"tap\",\"text\":\"搜索\"},{\"action\":\"input\",\"text\":\"天气\"}],\"settleMs\":300}",
                    examples = listOf(
                        "dev api POST /batch '{\"steps\":[{\"action\":\"key\",\"action\":\"back\"}]}'",
                    ),
                    limits = "① 上限 50 步，超过直接 400。\n② 单步失败默认继续（stopOnError=false），逐步结果要看 decisions 字段而不是只看 ok。\n③ 整批共用一次 HTTP 超时，步骤多时容易整批超时；长流程请拆成多次 /batch。",
                ),
                e(
                    "screen", "GET", "/screen",
                    "读屏：紧凑文本 / JSON / 可点击过滤，是动作前的定位依据",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "filter=all|clickable|editable（默认 all）；format=json（结构化）|doc（带 screenId 供 /tap-ref 用）；text=<只保留含该文本的节点>；x,y=<只看该点附近>",
                    examples = listOf(
                        "dev api GET /screen",
                        "dev api GET '/screen?filter=clickable'",
                        "dev api GET '/screen?format=doc'",
                    ),
                    limits = "① 默认输出是给人看的紧凑文本：序号 [o?] 文本 @x,y 起点/终点 [role] ~desc。\n② 同一屏反复读会有重复节点（父子同文本），已去重但仍可能出现同名不同序号的元素。\n③ 读屏不触发渲染，冻结的界面（视频/游戏）读不到内容，用 /screenshot 或 /ocr。",
                ),
                e(
                    "screen-changed", "GET", "/screen-changed",
                    "事件驱动的变化摘要（毫秒级，不 dump 整树）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    examples = listOf(
                        "dev api GET /screen-changed",
                    ),
                    limits = "① 只回上次读取以来的窗口/内容变化，连续调用第二次通常是空的 —— 空不等于没变，是已经消费掉了。\n② 用于廉价轮询；要完整现状还是得 /screen。",
                ),
                e(
                    "diff", "GET", "/diff",
                    "节点级差异：与上一次读屏相比新增/消失了哪些文本",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    examples = listOf(
                        "dev api GET /diff",
                    ),
                    limits = "① 比的是节点集合，同文本换了位置不算变化。\n② 第一次调用没有基线，返回空差异。",
                ),
                e(
                    "frame-diff", "GET", "/frame-diff",
                    "像素级帧差：变没变、变了多少比例、热点区块（不存视频）",
                    perm = "无障碍服务（DSH Screen Control）已启用，API 30+ 需截图能力",
                    examples = listOf(
                        "dev api GET /frame-diff",
                    ),
                    limits = "① 视频播放/动画会一直判「在变」，此时它没有信息量。\n② 需要系统截图能力，首次可能要重启无障碍服务才授予（返回里会说明）。\n③ 不能替代 /screenshot：它只回差异统计，不回图。",
                ),
                e(
                    "frame-wait", "POST", "/frame-wait",
                    "等画面真正变化（像素级），用于等动画/加载结束",
                    perm = "无障碍服务（DSH Screen Control）已启用，API 30+ 需截图能力",
                    params = "timeoutMs=<默认 5000，上限 30000>；intervalMs=<采样间隔，默认 250，下限 100>",
                    body = "{\"timeoutMs\":8000,\"intervalMs\":200}",
                    examples = listOf(
                        "dev api POST /frame-wait '{\"timeoutMs\":8000}'",
                    ),
                    limits = "① 超时返回 changed=false，不报错 —— 判断逻辑要自己看 changed。\n② 它等的是「发生变化」不是「变化结束」，出发时画面已在变就立刻返回。\n③ 采样间隔太小会拖慢整机（每帧都要截屏），不要低于 100ms。",
                ),
                e(
                    "ocr", "GET", "/ocr",
                    "对当前屏幕做文字识别（本地 OCR，返回每个文本块的坐标）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "force=1 跳过缓存重新识别；scale=<缩放，默认 0.5，越大越准也越慢>",
                    examples = listOf(
                        "dev api GET /ocr",
                        "dev api GET '/ocr?force=1&scale=0.7'",
                    ),
                    limits = "① 识别结果不带角色信息，只回文本与矩形 —— 判断「能不能点」还得靠 /screen。\n② 结果有缓存，同一屏第二次是毫秒级；界面动了要传 force=1。\n③ 手写体/艺术字识别率低，不要把低置信结果当事实。",
                ),
                e(
                    "pick", "POST", "/pick",
                    "按目标描述挑选最可能的可点元素（打分 + 候选列表）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "target=<必填，要点的东西>；tap=true 直接点中选中的；clickableOnly=<默认 true>",
                    body = "{\"target\":\"登录\",\"tap\":false}",
                    examples = listOf(
                        "dev api POST /pick '{\"target\":\"登录\"}'",
                    ),
                    limits = "① 只回候选与分数，默认不点；确认候选对再传 tap=true（或改用 /tap-ref 走严格路径）。\n② 目标描述越接近界面原文越准；同义改写会拉低分数。\n③ clickableOnly=false 会把纯文本节点也纳入（用于判断「这里有没有这个词」）。",
                ),
                e(
                    "overlay", "POST", "/overlay",
                    "控制悬浮状态窗：改状态文案 / 当前动作 / 完成闪烁 / 隐藏",
                    perm = "悬浮窗权限（SYSTEM_ALERT_WINDOW）",
                    params = "status=<状态文案>；action=<当前动作>；complete=true 闪一下完成；hide=true 隐藏",
                    body = "{\"status\":\"正在读消息\",\"action\":\"scroll\"}",
                    examples = listOf(
                        "dev api POST /overlay '{\"status\":\"正在执行\"}'",
                    ),
                    limits = "① 纯展示层，FLAG_NOT_TOUCHABLE，永不吃触摸事件。\n② 用户可能在设置里关掉悬浮窗，此时 available=false。",
                ),
                e(
                    "notify", "POST", "/notify",
                    "发一条系统通知（AI 主动汇报进度的主要方式）",
                    params = "title=<必填>；body=<必填>；channel=<可选渠道 id>；id=<可选，同 id 覆盖否则新建>",
                    body = "{\"title\":\"任务完成\",\"body\":\"已发送 3 条消息\"}",
                    examples = listOf(
                        "dev api POST /notify '{\"title\":\"完成\",\"body\":\"已处理\"}'",
                    ),
                    limits = "① 中文必须用 UTF-8 JSON body —— shell 直接 -d 传中文会因本地编码变乱码（真机踩过）。\n② Android 13+ 需要 POST_NOTIFICATIONS 运行时权限，没给时静默丢弃（返回值仍是 ok）。\n③ 频繁发送会被系统折叠，长任务请更新同一个 id 而不是每次新建。",
                ),
                e(
                    "notif", "GET", "/notif",
                    "当前缓存的最近通知（来自无障碍事件流，不需要通知使用权）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    examples = listOf(
                        "dev api GET /notif",
                    ),
                    limits = "① 只有无障碍服务活着期间收到的通知，进程重启后为空。\n② 要完整历史/摘要用 /dev/notifications 与 /dev/notify-digest（需通知使用权）。\n③ 不含通知里的 RemoteInput 内容（回复框文本）。",
                ),
                e(
                    "say", "POST", "/say",
                    "本地 TTS 朗读（AI 出声说话）",
                    params = "text=<必填>；flush=true 打断当前朗读",
                    body = "{\"text\":\"已完成\",\"flush\":true}",
                    examples = listOf(
                        "dev api POST /say '{\"text\":\"已完成\"}'",
                    ),
                    limits = "① GET 版本也支持（/say?text=…&flush=1），但中文务必用 POST + UTF-8 body。\n② 只支持文本转语音；语音识别（ASR）没有对外端点，属于 App 内 UI 能力。\n③ 静音/勿扰模式下照样调 TTS，是否出声由系统决定。",
                ),
                e(
                    "launch", "POST", "/launch",
                    "按包名或应用名启动 App",
                    params = "package=<必填包名>；或 label=<桌面显示名，模糊匹配>",
                    body = "{\"package\":\"com.tencent.mm\"}",
                    examples = listOf(
                        "dev api POST /launch '{\"label\":\"微信\"}'",
                    ),
                    limits = "① label 是模糊匹配，重名时会挑第一个 —— 生产脚本请用包名。\n② 启动成功只代表 Intent 已投递，界面出来还要 /wait。\n③ 被系统冻结/停用的包会失败，返回里带包名便于核对。",
                ),
                e(
                    "launch-intent", "POST", "/launch-intent",
                    "用任意 Intent 启动：action / data / extras / categories / flags",
                    params = "action=<action> 或 pkg+cls；data=<Uri>；type=<MIME>；extras={...}；categories=[...]；flags=<int>；dry_run=true 只回将要发的 Intent 不真发",
                    body = "{\"action\":\"android.intent.action.VIEW\",\"data\":\"https://example.com\"}",
                    examples = listOf(
                        "dev api POST /launch-intent '{\"action\":\"android.intent.action.VIEW\",\"data\":\"tel:10086\"}'",
                    ),
                    limits = "① 这是通用出口：能干的事远超「打开 App」，用它之前想清楚目标 App 会怎么处理。\n② dry_run=true 只做解析与校验，用来确认参数拼对了没有。\n③ 部分 action 需要权限或签名，系统会直接拒绝（返回带异常信息）。",
                ),
                e(
                    "open-file", "POST", "/open-file",
                    "用系统默认应用打开一个文件（可指定 MIME）",
                    params = "path=<必填，绝对路径>；mime=<可选>",
                    body = "{\"path\":\"/sdcard/Download/a.pdf\"}",
                    examples = listOf(
                        "dev api POST /open-file '{\"path\":\"/sdcard/a.png\"}'",
                    ),
                    limits = "① Linux 模式下会先把容器内路径翻译成宿主路径（/dsh/… → 真实目录）。\n② 只负责「打开」，不提供读写/编辑 —— 编辑是 App 内 FileEditorActivity 的事。",
                ),
                e(
                    "ext/install", "POST", "/ext/install",
                    "安装/重装一个环境扩展（python / openjdk / rust / vim 等）",
                    params = "id=<必填扩展 id>；force=true 覆盖重装",
                    body = "{\"id\":\"python\",\"force\":false}",
                    examples = listOf(
                        "dev api POST /ext/install '{\"id\":\"python\"}'",
                    ),
                    limits = "① Linux 模式走容器包管理器，原生模式装到 App 私有目录；两种路径状态来源不同。\n② 安装是长动作，同步等待可能超过桥的 10 秒超时，失败时先看 /ext/check 的真实状态。\n③ 卸载另见 /ext/uninstall。",
                ),
                e(
                    "ext/uninstall", "POST", "/ext/uninstall",
                    "卸载环境扩展（删除文件与激活标记）",
                    params = "id=<必填扩展 id>",
                    body = "{\"id\":\"rust\"}",
                    examples = listOf(
                        "dev api POST /ext/uninstall '{\"id\":\"rust\"}'",
                    ),
                    limits = "① 引擎内的 bin/lib 软链要重启引擎才彻底消失，返回值带 needs_restart。\n② 已装扩展目录被删后，正在跑的进程仍持有文件句柄，属正常。",
                ),
                e(
                    "ext/check", "GET", "/ext/check",
                    "扩展健康检查：缺哪些可执行文件、哪些软链悬空、哪些目录不可读",
                    examples = listOf(
                        "dev api GET /ext/check",
                    ),
                    limits = "① 只检查已安装的扩展，未装的不在列表里。\n② broken 非空说明环境已坏（常见于用户手动删过目录），按返回的 binsMissing 重装对应扩展。",
                ),
                e(
                    "plugin/install", "POST", "/plugin/install",
                    "安装一个社区插件（pnpm 装到 profile）",
                    params = "package=<必填，npm 包名>",
                    body = "{\"package\":\"@zzerx/dsh-plugin-memory\"}",
                    examples = listOf(
                        "dev api POST /plugin/install '{\"package\":\"@zzerx/dsh-plugin-memory\"}'",
                    ),
                    limits = "① 异步执行，202 不代表装好；用 /plugin/killswitch 的状态确认。\n② 插件恢复/暂停期间会 409。\n③ 需要引擎可用；引擎没起来时返回 503。",
                ),
                e(
                    "plugin/uninstall", "POST", "/plugin/uninstall",
                    "卸载一个已装插件",
                    params = "package=<必填，npm 包名>",
                    body = "{\"package\":\"@zzerx/dsh-plugin-memory\"}",
                    examples = listOf(
                        "dev api POST /plugin/uninstall '{\"package\":\"@zzerx/dsh-plugin-memory\"}'",
                    ),
                    limits = "① pnpm store 锁坏掉时会自动 heal 并重试一次，仍失败会在返回里带原始输出。\n② 卸载后 profile 里的配置项不会自动清理。",
                ),
                e(
                    "plugin/killswitch", "GET|POST", "/plugin/killswitch",
                    "插件熔断开关：出问题时可一键停用全部第三方插件",
                    params = "GET 时 action=status；POST 时 action=status|enable|disable|enable-one|disable-one，enable-one/disable-one 需 package=<包名>",
                    body = "{\"action\":\"disable\"}",
                    examples = listOf(
                        "dev api GET '/plugin/killswitch?action=status'",
                    ),
                    limits = "① 停用期间插件安装会被拒绝（409），恢复中同样拒绝。\n② allow 列表用于豁免特定插件，不随 disable 清空。",
                ),
                e(
                    "repair/diagnose", "GET", "/repair/diagnose",
                    "引擎起不来时的结构化诊断（给 AI 读的 JSON，不是给用户看的页面）",
                    examples = listOf(
                        "dev api GET /repair/diagnose",
                    ),
                    limits = "① 与 /diag 分工：/diag 是给人看的自诊页，这个给机器读。\n② 它自己不修东西，修复动作在 /repair/apply。",
                ),
                e(
                    "repair/apply", "POST", "/repair/apply",
                    "按诊断结论执行修复（会改动 dsh-home / 引擎目录，需要 confirm）",
                    params = "kind=<必填修复项>；confirm=true（缺了会拒绝）",
                    body = "{\"kind\":\"reset-engine\",\"confirm\":true}",
                    examples = listOf(
                        "dev api POST /repair/apply '{\"kind\":\"…\",\"confirm\":true}'",
                    ),
                    limits = "① 破坏性：部分 kind 会重置引擎依赖或 profile，先跑 diagnose 看清原因。\n② 没有 confirm 一律 400，这是刻意的二次确认。",
                ),
                e(
                    "local/status", "GET", "/local/status",
                    "本地小模型（嵌入/快速选择器）的就绪状态与统计",
                    examples = listOf(
                        "dev api GET /local/status",
                    ),
                    limits = "① modelReady=false 时语义检索会退到字符哈希，结果质量下降但不会报错。\n② stats 里的 exact/semantic/none 是命中来源计数，用来判断模型到底有没有被用上。",
                ),
                e(
                    "local/ensure", "POST", "/local/ensure",
                    "异步下载并启动本地模型（202 立即返回）",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /local/ensure",
                        "轮询 dev api GET /local/status",
                    ),
                    limits = "① 立即返回 202，不代表下载完；必须轮询 /local/status 的 modelReady。\n② 首次下载几十 MB，移动网络下要用户同意。",
                ),
                e(
                    "daemon/status", "GET", "/daemon/status",
                    "后台常驻任务（daemon）的当前状态：运行中 / 排队 / 需要人工介入",
                    examples = listOf(
                        "dev api GET /daemon/status",
                    ),
                    limits = "① 数据源是 \$DSH_HOME/tasks/*.json，文件被外部改动时会不一致。\n② needsHelp=true 表示任务卡住等人，此时不会自己继续。",
                ),
                e(
                    "daemon/stop", "POST", "/daemon/stop",
                    "请求停止所有运行中的后台任务（写 stop 标记，不是强杀）",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /daemon/stop",
                    ),
                    limits = "① 只是写停止标记，任务自己检查后才退出 —— 卡死的任务不会因此结束。\n② 与 /task/stop 不同：那个是叫停当前这次 AI 操作，这个是停后台任务队列。",
                ),
                e(
                    "loop/run", "POST", "/loop/run",
                    "本地循环执行器：按预算跑一轮「读屏→决策→动作」（不经过模型）",
                    params = "budgetMs=<总预算，默认 300000，范围 10000~600000>",
                    body = "{\"budgetMs\":60000}",
                    examples = listOf(
                        "dev api POST /loop/run '{\"budgetMs\":60000}'",
                    ),
                    limits = "① 引擎侧还有更短的 HTTP 超时，budgetMs 别超过它。\n② 这是给离线复刻/压测用的，正常任务请走 AI 决策。",
                ),
                e(
                    "interference", "GET", "/interference",
                    "用户干扰检测：AI 动作期间用户有没有动过屏幕",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "since=<epoch ms，只报这之后是否被干扰>",
                    examples = listOf(
                        "dev api GET '/interference?since=1700000000000'",
                    ),
                    limits = "① 判据是「用户触摸 / 窗口变化 与 AI 自身上报的动作时间差」，边界情况可能误判。\n② 不带 since 只回快照，不回「是否被干扰」。",
                ),
            )
        ),
        Triple(
            "任务与交接", "任务状态 / 用户叫停 / 提问 / 交给人", listOf(
                e(
                    "task", "GET", "/task",
                    "当前任务状态：运行中 / 步数 / 最近动作轨迹 / 是否被叫停",
                    examples = listOf(
                        "dev api GET /task",
                    ),
                    limits = "① trail 只保留最近 20 条，细节要看 /trace/list。\n② active=false 但 state=RUNNING 的组合不会出现，state 以 active 为准。\n③ 被叫停时 allowed=false，所有动作端点会回 409，解除用 POST /task/allow。",
                ),
                e(
                    "task/stop", "POST", "/task/stop",
                    "用户叫停：立刻把动作闸门置为 STOPPED，后续动作端点返回 409",
                    params = "reason=<可选，记录叫停原因>",
                    body = "{\"reason\":\"用户手动停止\"}",
                    examples = listOf(
                        "dev api POST /task/stop '{\"reason\":\"stop\"}'",
                    ),
                    limits = "① 全局副作用：置位后所有动作端点（/tap、/vision/tap…）都回 409 stopped by user，直到 POST /task/allow {\"allowed\":true}。\n② 闸门挂在路由入口，已派发出去的手势不会被撤回。\n③ 停止同时会结束行动边界（关掉本次为行动打开的无障碍与 fast_mode）。",
                ),
                e(
                    "task/allow", "GET|POST", "/task/allow",
                    "恢复/查询动作闸门（叫停之后必须显式恢复）",
                    params = "POST 必填 allowed=true|false",
                    body = "{\"allowed\":true}",
                    examples = listOf(
                        "dev api POST /task/allow '{\"allowed\":true}'",
                        "dev api GET /task/allow",
                    ),
                    limits = "① POST 必须带 allowed 字段，缺了 400（不会默认放行）。\n② 这是解除叫停，不代表用户同意新的危险动作。\n③ GET 只回当前 allowed 值。",
                ),
                e(
                    "task/pause", "POST", "/task/pause",
                    "暂停任务计时（读屏等长动作前用，避免被判定超时）",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /task/pause",
                    ),
                    limits = "① 暂停期间不会再累计 doze/超时判定，但不阻止动作执行。\n② 记得 /task/resume；被用户叫停时 pause 不能绕过刹车。",
                ),
                e(
                    "task/resume", "POST", "/task/resume",
                    "恢复任务计时",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /task/resume",
                    ),
                    limits = "① 只对 PAUSED 有效；若用户已叫停，返回 409 stopped=true 并说明刹车只能由用户解除。\n② 未暂停时调用是 no-op。",
                ),
                e(
                    "task/say", "POST", "/task/say",
                    "用户在任务进行中插话（最多留 5 条），AI 下一步能读到",
                    params = "text=<必填>",
                    body = "{\"text\":\"第 3 步慢一点\"}",
                    examples = listOf(
                        "dev api POST /task/say '{\"text\":\"先别点\"}'",
                    ),
                    limits = "① 这是「用户对任务说的话」，不是 TTS 播报 —— 要出声用 /say 或 /task/say 由 UI 决定。\n② 只保留最近 5 条，多了覆盖最旧。",
                ),
                e(
                    "task/report", "POST", "/task/report",
                    "汇报任务进展（步数 / 是否卡住 / 摘要），会更新悬浮窗与通知",
                    params = "summary=<必填>；steps=<已完成步数>；stopped=<是否已停止>",
                    body = "{\"summary\":\"已发送 3 条\",\"steps\":3,\"stopped\":false}",
                    examples = listOf(
                        "dev api POST /task/report '{\"summary\":\"已完成\",\"steps\":5,\"stopped\":true}'",
                    ),
                    limits = "① 只是汇报，不改变任务真实状态 —— 状态仍由动作端点驱动。\n② 长任务建议每 3~5 步报一次，太频繁会刷屏。",
                ),
                e(
                    "task/finish", "POST", "/task/finish",
                    "结束当前任务（成功收尾，状态点转绿）",
                    params = "summary=<必填>；steps=<总步数>；stopped=<是否被中断>",
                    body = "{\"summary\":\"已完成\",\"steps\":8,\"stopped\":false}",
                    examples = listOf(
                        "dev api POST /task/finish '{\"summary\":\"完成\",\"steps\":8}'",
                    ),
                    limits = "① 与 /task/stop 语义不同：finish 是「做完了」，stop 是「用户不让做了」。\n② 会收尾行动边界（把本次为行动打开的无障碍/fast_mode 关回去）。\n③ 调用后 active=false，下一次动作会重新开一个任务。",
                ),
                e(
                    "task/point", "POST", "/task/point",
                    "在屏幕上标一个点（可视化「AI 正在看这里」）",
                    params = "x=<必填>；y=<必填>；label=<可选文案>",
                    body = "{\"x\":540,\"y\":1200,\"label\":\"目标\"}",
                    examples = listOf(
                        "dev api POST /task/point '{\"x\":540,\"y\":1200}'",
                    ),
                    limits = "① 纯视觉，不影响点击，也不拦截触摸。\n② 标点不跟随节点，界面滚动后会错位。",
                ),
                e(
                    "ask", "GET|POST", "/ask",
                    "向用户提问并等待回答：POST 发起，GET 查当前待答",
                    params = "POST：text=<必填>；options=[...]；freeText=<默认 true>；timeoutMs=<默认由 PhoneAsk 定>",
                    body = "{\"text\":\"要用哪个账号登录？\",\"options\":[\"工作号\",\"私人号\"]}",
                    examples = listOf(
                        "dev api POST /ask '{\"text\":\"继续吗？\",\"options\":[\"继续\",\"取消\"]}'",
                        "dev api GET /ask",
                    ),
                    limits = "① 提问会弹悬浮层，用户不答就一直等（到 timeoutMs 为止）。\n② 一次只能有一个待答问题，重复 POST 返回 409 并带上待答文本。\n③ 答案要用 /ask/result 取（读取即消费）。",
                ),
                e(
                    "ask/answer", "POST", "/ask/answer",
                    "提交答案（悬浮窗按钮或程序调用）",
                    params = "text=<必填>；fromOption=<bool，是否来自选项按钮>",
                    body = "{\"text\":\"工作号\",\"fromOption\":true}",
                    examples = listOf(
                        "dev api POST /ask/answer '{\"text\":\"继续\"}'",
                    ),
                    limits = "① 没有待答问题或已超时返回 409。\n② 答案一旦提交会唤醒等待方。\n③ 参数名是 fromOption（bool），不是选项序号。",
                ),
                e(
                    "ask/cancel", "POST", "/ask/cancel",
                    "取消当前待答问题（用户不答又不想继续等时）",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /ask/cancel",
                    ),
                    limits = "① 取消后等待方拿到的是 cancel 而不是超时，两者处置不同。\n② 没有待答问题时是 no-op。",
                ),
                e(
                    "ask/result", "POST", "/ask/result",
                    "取走答案结果（取走后清空）",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /ask/result",
                    ),
                    limits = "① 读取即消费：第二次调用拿不到同一次答案。\n② 还没答完时返回 pending=true 与 state=ASKING。",
                ),
                e(
                    "handoff", "GET|POST", "/handoff",
                    "把设备交给人：登录/验证码/支付这类页面不该由 AI 点",
                    params = "POST：reason=<交办理由>；wait=<是否就地等，最长 30s>；timeoutMs=<会话超时>；GET：waitMs=<最多等多久，上限 30000>",
                    body = "{\"reason\":\"需要输入验证码\",\"wait\":false}",
                    examples = listOf(
                        "dev api POST /handoff '{\"reason\":\"需要人工验证\"}'",
                        "dev api GET /handoff",
                    ),
                    limits = "① wait=true 会阻塞当前请求（最长 30 秒），桥每连接一线程，不会拖住其它调用。\n② 用户完成后必须调 /handoff/done，否则状态一直是 waiting。\n③ 这是安全边界：交出去之后不要再偷偷做动作。",
                ),
                e(
                    "handoff/done", "POST", "/handoff/done",
                    "标记人工环节已完成（AI 可以继续）",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /handoff/done",
                    ),
                    limits = "① 只是状态切换，不校验用户是否真的操作过。\n② 与 /handoff 成对使用。",
                ),
            )
        ),
        Triple(
            "视觉模板", "截图存模板 → 定位 → 点击/拖拽", listOf(
                e(
                    "vision/list", "GET", "/vision/list",
                    "已保存的视觉模板列表（模板匹配用的图）",
                    examples = listOf(
                        "dev api GET /vision/list",
                    ),
                    limits = "① 只列模板文件，不含截图；模板用 /vision/crop 从当前屏幕截取。\n② 空列表表示还没截过模板。",
                ),
                e(
                    "vision/crop", "POST", "/vision/crop",
                    "从当前屏幕截一块存成模板（给 /vision/track|tap|drag 用）",
                    perm = "无障碍服务（DSH Screen Control）已启用，需要截图能力",
                    params = "id=<模板 id，[A-Za-z0-9_-]{1,64}>；x,y,w,h=<像素区域>；或 region={nx,ny,nw,nh} 归一化",
                    body = "{\"id\":\"btn_send\",\"x\":900,\"y\":2100,\"w\":160,\"h\":80}",
                    examples = listOf(
                        "dev api POST /vision/crop '{\"id\":\"btn\",\"x\":900,\"y\":2100,\"w\":160,\"h\":80}'",
                    ),
                    limits = "① id 会拼进文件名，非法字符直接 400（防路径穿越）。\n② 截太小（<24px）匹配不稳定，建议至少 48px 见方。\n③ 模板与分辨率绑定：换分辨率/缩放后旧模板会失配。",
                ),
                e(
                    "vision/track", "POST", "/vision/track",
                    "在当前屏幕上定位模板，返回坐标与置信度",
                    perm = "无障碍服务（DSH Screen Control）已启用，需要截图能力",
                    params = "id=<必填模板 id>；region={nx,ny,nw,nh} 限定搜索区域（更快更准）",
                    body = "{\"id\":\"btn_send\"}",
                    examples = listOf(
                        "dev api POST /vision/track '{\"id\":\"btn_send\"}'",
                    ),
                    limits = "① 是匹配不是识别：界面改版、换主题色都会失配。\n② 需要先 /vision/crop 建模板，否则 404。\n③ 动态内容（验证码、时钟）不要做模板。",
                ),
                e(
                    "vision/tap", "POST", "/vision/tap",
                    "定位模板并点击（带最低分数保护）",
                    perm = "无障碍服务（DSH Screen Control）已启用，需要截图能力",
                    params = "id=<必填>；minScore=<默认 0.5，低于此分不点>；region={...}",
                    body = "{\"id\":\"btn_send\",\"minScore\":0.6}",
                    examples = listOf(
                        "dev api POST /vision/tap '{\"id\":\"btn_send\"}'",
                    ),
                    limits = "① 分数不够时返回 ok=false 并附分数，不会点偏 —— 别把 ok=false 当网络错误。\n② 受 /task/stop 闸门约束（叫停后 409）。\n③ 点的是匹配中心点，元素被遮挡时会点到遮挡物。",
                ),
                e(
                    "vision/drag", "POST", "/vision/drag",
                    "定位模板并拖动（可给归一化目标或像素偏移）",
                    perm = "无障碍服务（DSH Screen Control）已启用，需要截图能力",
                    params = "id=<必填>；toNx,toNy=<归一化目标 0~1>；或 dx,dy=<像素偏移>；ms=<时长>；region={...}",
                    body = "{\"id\":\"thumb\",\"toNx\":0.8,\"toNy\":0.5}",
                    examples = listOf(
                        "dev api POST /vision/drag '{\"id\":\"thumb\",\"toNx\":0.8,\"toNy\":0.5}'",
                    ),
                    limits = "① 起始点来自模板匹配中心，滑块类建议模板取滑块本体而不是轨道。\n② 目标用归一化坐标可跨分辨率复用。\n③ 受叫停闸门约束。",
                ),
            )
        ),
        Triple(
            "轨迹录制", "自动录下的动作序列，可回放", listOf(
                e(
                    "trace/list", "GET", "/trace/list",
                    "任务轨迹列表（自动录制的动作序列，可回放）",
                    params = "limit=<默认 50>",
                    examples = listOf(
                        "dev api GET /trace/list",
                        "dev api GET '/trace/list?limit=10'",
                    ),
                    limits = "① 只回摘要（步数/目标/结束原因），完整步骤用 /trace/get。\n② recordEnabled=false 时新轨迹不会产生，列表里的是历史。\n③ 同时回 recording 与 current，可判断现在是否正在录。",
                ),
                e(
                    "trace/get", "GET", "/trace/get",
                    "取一条轨迹的完整步骤（含锚点、前后指纹）",
                    params = "id=<必填，trace id>",
                    examples = listOf(
                        "dev api GET '/trace/get?id=tr_123'",
                    ),
                    limits = "① 步骤里带 beforeFp/afterFp 指纹，是回放时校验「界面还对得上」的依据。\n② 输入类动作不记文本（隐私），回放时要重新提供。",
                ),
                e(
                    "trace/record", "POST", "/trace/record",
                    "开关轨迹自动录制",
                    params = "enabled=<true|false>；不给则翻转当前值",
                    body = "{\"enabled\":true}",
                    examples = listOf(
                        "dev api POST /trace/record '{\"enabled\":true}'",
                    ),
                    limits = "① 只是开关，不启动任务；录制跟着真实动作走。\n② 关掉不会删除已有轨迹。",
                ),
                e(
                    "trace/clear", "POST", "/trace/clear",
                    "清空全部轨迹（不可恢复）",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /trace/clear",
                    ),
                    limits = "① 不可恢复，且会一起清掉从轨迹学来的记忆来源。\n② 清空后 /trace/list 返回空。",
                ),
            )
        ),
        Triple(
            "场景记忆（v1）", "旧版记忆接口：存/查/转正/清空", listOf(
                e(
                    "memory/store", "POST", "/memory/store",
                    "存一条场景记忆（目标 + 计划 + 指纹，旧版 v1 接口）",
                    perm = "无障碍服务（DSH Screen Control）已启用（不给 dump 时需要读屏）",
                    params = "goal=<必填>；plan=<必填对象>；dump=<可选，不给则自动读屏>",
                    body = "{\"goal\":\"发消息\",\"plan\":{\"steps\":[]}}",
                    examples = listOf(
                        "dev api POST /memory/store '{\"goal\":\"发消息\",\"plan\":{}}'",
                    ),
                    limits = "① 这是 v1 接口，新代码请用 /memory/v2/upsert。\n② 存进去默认 pending，要 /memory/confirm 才转正。",
                ),
                e(
                    "memory/lookup", "POST", "/memory/lookup",
                    "按目标查场景记忆（旧版 v1）",
                    perm = "无障碍服务（DSH Screen Control）已启用（不给 dump 时需要读屏）",
                    params = "goal=<必填>；dump=<可选>",
                    body = "{\"goal\":\"发消息\"}",
                    examples = listOf(
                        "dev api POST /memory/lookup '{\"goal\":\"发消息\"}'",
                    ),
                    limits = "① 只返回相似度最高的一条，不返回候选列表（v2 的 /memory/v2/lookup 才有 limit）。\n② 相似度低时 hit=false，别把空当「没有记忆」—— 可能是阈值问题。",
                ),
                e(
                    "memory/confirm", "POST", "/memory/confirm",
                    "把 pending 的场景记忆转正（旧版 v1）",
                    params = "goal=<必填>",
                    body = "{\"goal\":\"发消息\"}",
                    examples = listOf(
                        "dev api POST /memory/confirm '{\"goal\":\"发消息\"}'",
                    ),
                    limits = "① 只按 goal 匹配，同名多个会一起转正。\n② 返回 promoted 数量与剩余 pending。",
                ),
                e(
                    "memory/list", "GET", "/memory/list",
                    "列出场景记忆（旧版 v1，含 pending 标记）",
                    examples = listOf(
                        "dev api GET /memory/list",
                    ),
                    limits = "① v1 与 v2 是两套存储，互不可见 —— 查不到时先确认用哪一套。\n② 新代码请用 /memory/v2/list。",
                ),
                e(
                    "memory/stats", "GET", "/memory/stats",
                    "场景记忆统计（旧版 v1）",
                    examples = listOf(
                        "dev api GET /memory/stats",
                    ),
                    limits = "① 只统计 v1 库；v2 的统计在 /memory/v2/stats。\n② 数字为 0 不代表坏，可能只是没学过。",
                ),
                e(
                    "memory/clear", "POST", "/memory/clear",
                    "清空场景记忆（旧版 v1，不可恢复）",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /memory/clear",
                    ),
                    limits = "① 不可恢复。\n② 只清 v1，不影响 v2 与轨迹。",
                ),
                e(
                    "memory/run", "POST", "/memory/run",
                    "按记忆执行一次回放（v1 入口，按 id 或按意图排序取第一条）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "id=<记忆 id> 或 intent=<目标>；fp=[...]；auto=<默认 true>",
                    body = "{\"intent\":\"发消息\",\"auto\":true}",
                    examples = listOf(
                        "dev api POST /memory/run '{\"intent\":\"发消息\"}'",
                    ),
                    limits = "① 回放有安全规则：锚点对不上就拒绝，不会盲点坐标。\n② 不带 id 时按意图排序取第一条，可能不是你想要的，务必先 /memory/v2/list 确认。\n③ 新代码请走 /memory/v2/learn + /memory/v2/upsert 那条链。",
                ),
            )
        ),
        Triple(
            "记忆 v2 补充", "统计 / 开关 / 编辑 / 学习 / 迁移", listOf(
                e(
                    "memory/v2/stats", "GET", "/memory/v2/stats",
                    "记忆 v2 统计：条数 / 命中情况 / 录制与自动学习开关",
                    examples = listOf(
                        "dev api GET /memory/v2/stats",
                    ),
                    limits = "① memories=0 是正常初始状态（还没学过任何操作）。\n② recordEnabled / autoLearn 两个开关可用 /memory/v2/config 改。",
                ),
                e(
                    "memory/v2/config", "POST", "/memory/v2/config",
                    "写记忆 v2 的两个开关（自动学习 / 轨迹录制）",
                    params = "autoLearn=true|false；recordEnabled=true|false",
                    body = "{\"autoLearn\":false}",
                    examples = listOf(
                        "dev api POST /memory/v2/config '{\"recordEnabled\":false}'",
                    ),
                    limits = "① 两个开关都能写也能回读 —— 只读会让设置页 Switch 弹回去。\n② 关掉 autoLearn 后已学到的条目不删除。",
                ),
                e(
                    "memory/v2/extend", "POST", "/memory/v2/extend",
                    "给已有记忆追加步骤（不新建条目）",
                    params = "id=<必填>；steps=[{action,targetText,expectAfter,bounds}]；note=<可选说明>",
                    body = "{\"id\":\"m_1\",\"steps\":[{\"action\":\"tap\",\"targetText\":\"发送\"}],\"note\":\"补一步\"}",
                    examples = listOf(
                        "dev api POST /memory/v2/extend '{\"id\":\"m_1\",\"steps\":[]}'",
                    ),
                    limits = "① steps 为空会 400（no steps to extend）。\n② 追加的步骤插在末尾，顺序错了要用 /memory/v2/patch 修单步。\n③ 会展版本号（rev），回滚用 /memory/v2/rollback。",
                ),
                e(
                    "memory/v2/fork", "POST", "/memory/v2/fork",
                    "从某条记忆分叉出新条目（改目标但复用前面步骤）",
                    params = "id=<必填>；intent=<必填，新目标>；fromStep=<分叉点，默认 -1 全部>；title=<新标题>",
                    body = "{\"id\":\"m_1\",\"fromStep\":3,\"intent\":\"发图片\"}",
                    examples = listOf(
                        "dev api POST /memory/v2/fork '{\"id\":\"m_1\",\"intent\":\"发图片\"}'",
                    ),
                    limits = "① id 与 intent 都必填，缺一即 400。\n② 分叉后两条记忆互不影响。\n③ 指纹不继承，分叉项第一次用必然要重新校核。",
                ),
                e(
                    "memory/v2/rollback", "POST", "/memory/v2/rollback",
                    "回滚到某个历史版本",
                    params = "id=<必填>；rev=<目标版本，默认 -1 即上一版>",
                    body = "{\"id\":\"m_1\",\"rev\":2}",
                    examples = listOf(
                        "dev api POST /memory/v2/rollback '{\"id\":\"m_1\"}'",
                    ),
                    limits = "① 回滚是新建一个版本而不是删除历史，rev 会继续增长。\n② id 或 rev 不存在时 404。",
                ),
                e(
                    "memory/v2/learn", "POST", "/memory/v2/learn",
                    "从一条轨迹学成一条记忆（轨迹 → 可复用步骤）",
                    params = "traceId=<必填>；intent=<可选，默认用轨迹 goal>；title=<可选>",
                    body = "{\"traceId\":\"tr_123\"}",
                    examples = listOf(
                        "dev api POST /memory/v2/learn '{\"traceId\":\"tr_123\"}'",
                    ),
                    limits = "① 剪枝后无步骤或含不可回放步骤时返回 409 rejected 并带原因 —— 这是质量门，不是网络错误。\n② 输入类步骤不会带文本。\n③ 学到的是 pending 条目，转正看 /memory/v2/state。",
                ),
                e(
                    "memory/v2/migrate", "POST", "/memory/v2/migrate",
                    "把 v1 场景记忆迁移成 v2 条目",
                    body = "{}",
                    examples = listOf(
                        "dev api POST /memory/v2/migrate",
                    ),
                    limits = "① 幂等：已迁过的不会重复，返回 migrated 条数。\n② v1 原库不删除，迁移后可继续读。",
                ),
            )
        ),
        Triple(
            "端点自省与排障", "目录对账、围栏只读、空闲等待、滚动查找、日志尾部", listOf(
                e(
                    "dev/help", "GET", "/dev/help",
                    "能力目录本身：不带 q 回全部分组，带 q 按名字/人话检索",
                    params = "q=<名字或人话，如「剪贴板」>；detail=1 带上参数/示例/限制",
                    examples = listOf(
                        "dev api GET '/dev/help?detail=1'",
                        "dev api GET '/dev/help?q=剪贴板'",
                    ),
                    limits = "① 不带 q 时返回分组列表（groups → items），不是扁平数组。\n② q 精确没命中时退化成人话检索，返回 ambiguous=true 与候选，要挑一个再查。\n③ 目录与真实路由的一致性看 /dev/routes 的 catalog_problems。",
                ),
                e(
                    "dev/routes", "GET", "/dev/routes",
                    "真实路由表拍平清单 + 目录一致性核对（catalog_problems）",
                    examples = listOf(
                        "dev api GET /dev/routes",
                    ),
                    limits = "① catalog_problems 两个方向都会报：目录写了但路由不存在，路由存在但目录没登记。\n② 它只做对账，不改任何东西。",
                ),
                e(
                    "geofence-list", "GET", "/dev/geofence",
                    "地理围栏列表（只读，不触发任何注册动作）",
                    cap = "rf",
                    perm = "NFC 已开启 或 ACCESS_FINE_LOCATION",
                    examples = listOf(
                        "dev api GET /dev/geofence",
                    ),
                    limits = "① 只读：新增/删除围栏要用 POST /dev/geofence。\n② 空列表不代表围栏失效，可能只是没建过。",
                ),
                e(
                    "idle", "POST", "/idle",
                    "等界面空闲（连续多次读屏结果一致才放行）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "timeoutMs=<默认 3000，范围 200~20000>；quietMs=<默认 120>；stableReads=<默认 3>",
                    body = "{\"timeoutMs\":3000}",
                    examples = listOf(
                        "dev api POST /idle '{\"timeoutMs\":3000}'",
                    ),
                    limits = "① 超时返回 504（节点树卡住），不是 ok。\n② 动画/视频类界面永远不会稳定，会一直超时。\n③ stableReads 越大越稳但越慢。",
                ),
                e(
                    "scroll-find", "POST", "/scroll-find",
                    "滚动查找文本（滚到屏幕上出现目标为止）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "text=<必填>；maxSwipes=<默认 8>；back=true 反向滚；tap=true 找到就点",
                    body = "{\"text\":\"设置\",\"maxSwipes\":5}",
                    examples = listOf(
                        "dev api POST /scroll-find '{\"text\":\"设置\"}'",
                    ),
                    limits = "① 找不到返回 ok=false（滚到边界也没出现），不报错。\n② 每次滑动后要等稳定，maxSwipes 大时整请求会久，注意桥超时。\n③ 只认文本，纯图标按钮找不到。",
                ),
                e(
                    "scroll-center", "POST", "/scroll-center",
                    "滚动把目标滚到屏幕中间（避免边缘误点）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "text=<必填> 或 query=；maxSwipes=<默认 8>；back=true 反向；tap=true 居中后点",
                    body = "{\"text\":\"确定\",\"tap\":true}",
                    examples = listOf(
                        "dev api POST /scroll-center '{\"text\":\"确定\",\"tap\":true}'",
                    ),
                    limits = "① 与 /scroll-find 的区别是它会补一次小幅调整把目标居中，滚动类误点显著更少。\n② 目标本来就在中间时不滚动，直接返回坐标。\n③ 找不到返回 ok=false。",
                ),
                e(
                    "scroll-edge", "POST", "/scroll-edge",
                    "滚到列表顶/底（一次到底，不查找）",
                    perm = "无障碍服务（DSH Screen Control）已启用",
                    params = "direction=top|bottom（默认 bottom）",
                    body = "{\"direction\":\"bottom\"}",
                    examples = listOf(
                        "dev api POST /scroll-edge '{\"direction\":\"top\"}'",
                    ),
                    limits = "① 一直滑到「再滑也没变化」为止，返回 swipes 次数。\n② 长列表可能滑很多次，整请求耗时长。\n③ 不保证停在精确位置，只为「到头了」。",
                ),
                e(
                    "dev/logs", "GET", "/dev/logs",
                    "读引擎日志尾部（engine.log / boot.log），排障第一步",
                    params = "file=engine|boot（默认 engine）；tail=<字节数，默认 2500，上限 64KB>",
                    examples = listOf(
                        "dev api GET '/dev/logs?tail=4000'",
                        "dev api GET '/dev/logs?file=boot'",
                    ),
                    limits = "① 只回尾部，不回全文（日志可能有几百 KB，全量会把上下文撑爆）。\n② 与 /diag 不同：这个回纯文本，/diag 回拼好 HTML 的自诊页。\n③ 日志里可能含用户路径与会话 id，外发前先想清楚。",
                ),
            )
        ),
        // ── 用户数据（v1.7.10）──
        //
        // 这一组不是「设备能力」，而是**用户自己的数据**：会话、分享进来的文件、
        // 用户写的长期指令、备份产物。此前只有界面能看，AI 一条路由都没有。
        // 与设备能力的纪律不同：只读优先，写操作必须显式确认（见各条 limits）。
        Triple(
            "用户数据", "会话清单、分享收件箱、用户提示词、数据备份", listOf(
                e(
                    "chats", "GET", "/dev/chats",
                    "会话清单（标题/cwd/大小/归档态），只读 —— 删除仍在界面里做",
                    examples = listOf(
                        "dev api GET /dev/chats",
                    ),
                    limits = "① 最多回 500 条，按 updatedAt 倒序。\n② has_file=false 是「幽灵会话」：注册表登记了但磁盘文件已丢，只能清不能读。\n③ 刻意没有删除端点：删会话要停引擎，还可能删掉正在对话的这一个 —— 那是用户的动作。",
                ),
                e(
                    "chats-trash-purge", "POST", "/dev/chats/trash-purge",
                    "清掉过期的会话回收站（只删 7 天前的 .trash-* 目录）",
                    examples = listOf(
                        "dev api POST /dev/chats/trash-purge",
                    ),
                    limits = "① 只动回收站，活跃会话一个字节都不碰。\n② 回收站是安全网：用户误删 7 天内还能捞回来，别把它当成「删除」用。",
                ),
                e(
                    "inbox", "GET", "/dev/inbox",
                    "分享收件箱里还没被取走的文件（用户从别的 App 分享进来的）",
                    examples = listOf(
                        "dev api GET /dev/inbox",
                    ),
                    limits = "① 语义是「至少投递一次」：被取走后 count 归零，但文件仍在磁盘上，不丢。\n② files_on_disk 才是目录里的真实文件数，count=0 ≠ 目录空。",
                ),
                e(
                    "prompt", "GET", "/dev/prompt",
                    "读用户提示词（AGENTS.md 的用户段，App 永不覆盖的那一部分）",
                    examples = listOf(
                        "dev api GET /dev/prompt",
                    ),
                    limits = "① 只回用户段，App 自动生成的那一大段不在里面。\n② 这是**用户自己**写给你的长期指令，读它是为了遵守，不是为了改写。",
                ),
                e(
                    "prompt-write", "POST", "/dev/prompt",
                    "覆写用户提示词（必须 confirm=write，回读自证）",
                    body = "{\"text\":\"…\",\"confirm\":\"write\"}",
                    examples = listOf(
                        "dev api POST /dev/prompt '{\"text\":\"- 回答用中文\",\"confirm\":\"write\"}'",
                    ),
                    limits = "① 少了 confirm=write 一律 409 —— 这是「AI 给自己改长期规则」的通道，必须显式确认。\n② 只影响**新会话**，正在跑的引擎仍用旧提示词。\n③ text=\"\" 表示清空用户段。\n④ 除非用户明确要求，不要自己往这里写东西。",
                ),
                e(
                    "backup", "POST", "/dev/backup",
                    "导出用户数据 zip（sessions + workspaces + 注册表），只读导出不停引擎",
                    params = "path=<目标 zip 绝对路径，可选>；overwrite=true（覆盖已有文件时才需要）",
                    body = "{\"path\":\"/sdcard/Download/dsh-backup.zip\"}",
                    examples = listOf(
                        "dev api POST /dev/backup '{}'",
                        "dev api POST /dev/backup '{\"path\":\"/data/local/tmp/b.zip\"}'",
                    ),
                    limits = "① 不给 path 就落到 \$DSH_HOME/backups/dsh-backup-<时间戳>.zip。\n② 目标已存在且没给 overwrite=true 时回 409，不覆盖。\n③ 父目录必须已存在且可写（不会替你造目录）。\n④ 不含凭据：凭据从来不进备份包，这是有意的。\n⑤ 引擎运行中导出的是**当时的快照**，导出期间新写入的会话不在包里。",
                ),
            )
        ),
        // ── 双端同步（v1.6.0-beta.3）──
        //
        // 手机 ↔ 电脑对齐 sessions 里的会话历史。手机永远是 HTTP 客户端，
        // 电脑端跑仓库里的 tools/peersync/pc-sync-server.mjs。
        //
        // 与其它组的关键差别：这组**会真的动文件**（写 sessions/ 与会话基线），
        // 而且是唯一会持续几分钟的组 —— 所以 POST 一律异步起任务、立刻回 202。
        Triple(
            "双端同步", "与电脑上的 DSH 对齐会话历史（局域网）", listOf(
                e(
                    "peersync", "GET", "/dev/peersync",
                    "同步状态总览：配置、基线、上次结果、本机会话规模（不发起网络请求）",
                    examples = listOf(
                        "dev api GET /dev/peersync",
                    ),
                    limits = "① 纯本地读，**不探测电脑是否在线** —— 想验通不通用 POST 起一次同步。" +
                        "\n② 密码只回 exists 与长度，明文永不出进程。" +
                        "\n③ sessions 与 bytes 是遍历 DSH_HOME/sessions 得到的真实规模（只数 session.* 白名单文件）。",
                ),
                e(
                    "peersync-run", "POST", "/dev/peersync",
                    "起一次同步任务（异步，立刻回 202 + job_id）",
                    params = "可选覆盖项：host / port / password / direction=both|pull|push / conflict=backup-newer|prefer-local|prefer-remote|skip / scope=<子目录前缀> / max_file_bytes",
                    body = "{\"direction\":\"both\"}",
                    examples = listOf(
                        "dev api POST /dev/peersync '{}'",
                        "dev api POST /dev/peersync '{\"direction\":\"pull\"}'",
                    ),
                    limits = "① **同一时刻只允许一个同步**：两个并发会互相覆盖同一批文件，已在跑时回 409 already-running。" +
                        "\n② 异步是硬要求：桥的请求线程只有 4 条，同步占住一条几分钟会让后续命令排队。" +
                        "\n③ 方向 both 是三方比较（本地改动 / 远端改动 / 上次同步基线），不是「谁新用谁」——" +
                        "mtime 会被解压重写、备份还原打乱，只看时间会静默丢改动。" +
                        "\n④ 冲突默认 backup-newer：新的赢，旧的在 sessions 目录里留 <名>.peersync-bak-<时间戳>，只留最近 3 份。" +
                        "\n⑤ 只传白名单文件（session[.vN].jsonl[.zstd]），不传 lock / 注册表 / 凭据；" +
                        "**刻意没有删除能力** —— 同步只增不减，删会话永远是用户的动作。" +
                        "\n⑥ 超过 max_file_bytes 的单文件跳过并计入 skipped，不会截断。" +
                        "\n⑦ 只接受局域网/本机地址，公网 IP 与域名在入口就拒（密码是明文请求头）。",
                ),
                e(
                    "peersync-job", "GET", "/dev/peersync/job",
                    "查同步任务进度与结果（不给 id 就回最近那个）",
                    params = "id=<job_id，可省略>",
                    examples = listOf(
                        "dev api GET '/dev/peersync/job'",
                        "dev api GET '/dev/peersync/job?id=<job_id>'",
                    ),
                    limits = "① 只保留最近 20 个任务，旧的滚掉。" +
                        "\n② counts 里 pushed / pulled / conflicts / skipped / failed 分列；failed 非空时 errors 里有逐文件原因。" +
                        "\n③ 任务在内存里，进程重启就没了；落盘的只有基线文件。",
                ),
                e(
                    "peersync-config", "GET", "/dev/peersync/config",
                    "读同步配置（密码只回是否已设置 + 长度）",
                    examples = listOf(
                        "dev api GET /dev/peersync/config",
                    ),
                    limits = "① 明文密码永不返回，长度也只是为了让调用方判断「是不是空着」。" +
                        "\n② baseline_files 是已建立同步基线的会话数，0 表示还没同步过。",
                ),
                e(
                    "peersync-config-write", "POST", "/dev/peersync/config",
                    "保存同步配置（必须 confirm=save，回读自证）",
                    body = "{\"host\":\"192.168.1.9\",\"port\":3091,\"password\":\"...\",\"confirm\":\"save\"}",
                    examples = listOf(
                        "dev api POST /dev/peersync/config '{\"host\":\"192.168.1.9\",\"port\":3091,\"password\":\"123456\",\"confirm\":\"save\"}'",
                    ),
                    limits = "① 少了 confirm=save 一律 409 —— 这条会改掉后续所有同步的目标，必须显式确认。" +
                        "\n② 密码用 AndroidKeyStore 的 AES/GCM 加密后落盘；Keystore 不可用时退回明文并记 WARN。" +
                        "\n③ 公网地址直接 400 拒绝。" +
                        "\n④ 只跑一次不改配置，用 POST /dev/peersync 的覆盖参数。",
                ),
            )
        ),
    )

    /** 全部条目拍平。 */
    private val ENTRIES: List<Entry> = GROUPS.flatMap { it.third }

    /** 精确未命中时按意图返回的候选上限。3 条以内通常就能让 AI 选对。 */
    private const val SEARCH_FALLBACK_LIMIT = 5

    /** 查询词与能力 id 完全相同时的加成：确保「知道名字」永远排第一。 */
    private const val EXACT_ID_BONUS = 100_000

    /**
     * CJK 码位区间，按 **(lo, hi) 成对**存放。
     *
     * ⚠️ 这里曾经写成 `it.code in CJK_RANGES` —— 那是 IntArray 的**成员判断**，
     * 只认「编码刚好等于某个边界值」，`读`(35835) 当然不等 0x4E00(19968)，
     * 于是每个汉字都被判成非 CJK，整句中文被当成一个必须精确匹配的硬词，
     * 中文意图检索**全部返回空**。必须用 [isCjk] 做区间比较。
     */
    private val CJK_RANGES = intArrayOf(
        0x4E00, 0x9FFF, 0x3400, 0x4DBF, 0xF900, 0xFAFF, 0x3040, 0x30FF, 0xAC00, 0xD7AF,
    )

    /** 码位是否落在任一 CJK 区间。 */
    private fun isCjk(cp: Int): Boolean {
        var i = 0
        while (i + 1 < CJK_RANGES.size) {
            if (cp >= CJK_RANGES[i] && cp <= CJK_RANGES[i + 1]) return true
            i += 2
        }
        return false
    }

    /**
     * 按 id 或 path 精确查找（内部用；Entry 是私有类型，不对外暴露）。
     */
    private fun find(query: String): Entry? {
        val q = query.trim().removePrefix("/").removePrefix("dev/")
        return ENTRIES.firstOrNull { it.id == q }
            ?: ENTRIES.firstOrNull { it.path.trimStart('/').removePrefix("dev/") == q }
            ?: ENTRIES.firstOrNull { it.path == query }
    }

    /**
     * 按**人话关键词**检索能力 —— v1.6 新增。
     *
     * ## 为什么需要它
     * 精确匹配要求调用方先知道能力的名字（`notify` 还是 `notifications`？
     * `cal` 还是 `calendar`？`reply` 还是 `notify-reply`？）。AI 只会描述意图
     * （「我想读未读消息」），不知道我们内部叫什么。此前只能靠
     * `AgentContextSeed` 里手工抄一份别名表，抄漏一个能力就等于不存在。
     *
     * ## 打分规则
     * - id / path 完全相等 → 1000（精确命中永远排第一）
     * - id / path 前缀命中 → 500
     * - id / path 包含 → 300
     * - summary 包含 → 200
     * - params / limits / examples 包含 → 60
     * - 同义词表命中（中英）→ 250
     *
     * 多词查询按「全部命中才计分」处理（AND 语义），避免 `net block` 匹配到
     * 只含 `net` 的一大堆条目。空查询返回空列表，由调用方决定怎么提示。
     */
    internal fun search(query: String, limit: Int = 8): List<Entry> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        // 中文没有词间空格：整句「读未读消息」按空白切分只会得到一个长词，
        // 拿去和 id/summary 做 contains 必然落空。所以 CJK 片段要额外拆成
        // 二元组（bigram），让「未读」「消息」这类词粒能命中同义词表。
        val words = ArrayList<String>()
        for (chunk in raw.lowercase().split(Regex("\\s+"))) {
            if (chunk.isEmpty()) continue
            val cjkOnly = chunk.all { isCjk(it.code) }
            if (!cjkOnly) {
                // 英文/混合词按硬词处理（必须命中）
                words.add(chunk)
            }
            if (chunk.any { isCjk(it.code) }) {
                if (cjkOnly && chunk.length == 1) {
                    // 单字查询没有二元组可拆，只能当硬词
                    words.add(chunk)
                } else {
                    // 纯中文整句/短语**不**作为硬词：它几乎必然与我们的 id、
                    // summary 字面不等（「读未读消息」vs `notify`），当作硬词
                    // 会把整句判死。改为只拆二元组，由软命中累积分。
                    for (k in 0 until chunk.length - 1) {
                        val bg = chunk.substring(k, k + 2)
                        if (bg.all { c -> isCjk(c.code) }) words.add(bg)
                    }
                }
            }
        }
        if (words.isEmpty()) return emptyList()

        data class Scored(val entry: Entry, val score: Int)
        val scored = ArrayList<Scored>(ENTRIES.size)

        for (entry in ENTRIES) {
            val id = entry.id.lowercase()
            val path = entry.path.lowercase()
            val summary = entry.summary.lowercase()
            val extras = (
                (entry.params ?: "") + " " + (entry.limits ?: "") + " " +
                    entry.examples.joinToString(" ")
                ).lowercase()
            var total = 0
            var allHit = true
            var softHit = 0
            for (w in words) {
                val isSoft = w.length == 2 && w.all { isCjk(it.code) }
                var s = 0
                if (id == w || path == w || path.endsWith("/$w")) {
                    s = 1000
                } else if (id.startsWith(w) || path.contains("/$w")) {
                    s = 500
                } else if (id.contains(w) || path.contains(w)) {
                    s = 300
                } else if (summary.contains(w)) {
                    s = 200
                } else if (SYNONYMS.any { (canon, aliases) ->
                        (canon == id || path.endsWith("/$canon")) && aliases.any { it.contains(w) }
                    }) {
                    s = 250
                } else if (extras.contains(w)) {
                    s = 60
                }
                if (s == 0) {
                    // 二元组是「软词」：中文整句里必有大量无意义二字组合
                    // （「什么」「么状」），任一不中不代表不相关。
                    if (isSoft) continue
                    allHit = false
                    break
                }
                if (isSoft) softHit++ else total += s
            }
            if (!allHit || (total == 0 && softHit == 0)) continue
            // 精确命中必须**显著**压过模糊命中。`sms` 查询曾把 `sensors`/`root`
            // 排在前面，因为它们靠 summary/path 的 contains 拿到了 300 分，
            // 而精确命中只有一条且被软命中权重稀释 —— 精确命中当加千分量级，
            // 保证「知道名字」时永远拿第一。
            val exactBoost = if (words.any { it == id }) EXACT_ID_BONUS else 0
            scored.add(Scored(entry, total + softHit * 150 + exactBoost))
        }
        return scored.sortedByDescending { it.score }.take(limit.coerceIn(1, 40)).map { it.entry }
    }

    /**
     * 意图同义词表：把用户/AI 的说法映射到我们的能力 id。
     *
     * 只放**真实存在的 id**，且每条别名都应是用户可能脱口而出的词。
     * 这张表是 [search] 的补充而非替代 —— 词面匹配已经能覆盖大部分情况，
     * 这里只补「说法完全不同」的那些（例如把 `net` 说成「断网」）。
     */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "notifications" to listOf("notification", "notify", "message", "通知", "消息", "未读", "unread", "来信", "红点"),
        "sms" to listOf("message", "短信", "text", "发短信", "发消息"),
        "clipboard" to listOf("clip", "copy", "paste", "粘贴", "复制", "剪贴板"),
        "context" to listOf("situation", "state", "现在", "状态", "上下文", "环境"),
        "status" to listOf("battery", "电量", "状态", "总览", "overview"),
        "net" to listOf("network", "firewall", "traffic", "data", "网络", "断网", "流量", "防火墙"),
        "control" to listOf("torch", "flashlight", "wifi", "bt", "volume", "brightness",
            "手电", "音量", "亮度", "开关"),
        "media" to listOf("music", "play", "pause", "音乐", "播放", "暂停"),
        "record" to listOf("screenrecord", "capture", "录制", "录屏"),
        "hid" to listOf("keyboard", "mouse", "input", "键盘", "鼠标", "输入设备"),
        "hand" to listOf("gesture", "tap", "swipe", "手势", "点击", "滑动"),
        "sensors" to listOf("sensor", "accelerometer", "gyro", "传感器", "陀螺仪", "加速度"),
        "dialog" to listOf("popup", "permission", "consent", "弹窗", "授权", "对话框"),
        "perms" to listOf("permission", "grant", "权限", "授权"),
        "diag" to listOf("diagnostic", "health", "log", "诊断", "日志", "自检"),
        "remind" to listOf("reminder", "alarm", "todo", "提醒", "闹钟", "待办"),
        "bt" to listOf("bluetooth", "蓝牙"),
        "wifi-radio" to listOf("wifi", "wireless", "无线", "ssid"),
        "nfc" to listOf("tag", "近场", "刷卡"),
        "geofence" to listOf("location", "gps", "位置", "围栏", "定位"),
        "nudge" to listOf("alert", "notify", "提醒", "打扰", "通知"),
        "pulse" to listOf("vibrate", "morse", "震动", "振动", "摩斯"),
        "scene" to listOf("scene", "environment", "场景", "环境推断"),
        "doze" to listOf("battery", "optimization", "省电", "电池优化", "白名单"),
        "calls" to listOf("call", "dial", "phone", "通话", "电话", "拨号", "未接"),
        "contacts" to listOf("contact", "通讯录", "联系人"),
        "calendar" to listOf("cal", "schedule", "日历", "日程"),
        "ble" to listOf("bluetooth", "peripheral", "gatt", "蓝牙外设", "广播"),
        "bt-hid" to listOf("keyboard", "bluetooth", "蓝牙键盘"),
        "screenwatch" to listOf("screenshot", "screen", "屏幕", "变化", "帧差"),
    )

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
            if (hit == null) {
                // v1.6：精确没命中就按意图搜。AI 说「读未读消息」时并不知道
                // 我们内部叫 `notify`；此前只能靠说明书里手抄的别名表。
                val hits = search(q, limit = SEARCH_FALLBACK_LIMIT)
                return if (hits.isEmpty()) {
                    404 to JSONObject()
                        .put("ok", false)
                        .put("error", "unknown_endpoint")
                        .put("query", q)
                        .put("hint", "用 `dev` 看全部可用的名字")
                        .toString()
                } else {
                    val arr = JSONArray()
                    for (h in hits) {
                        arr.put(
                            JSONObject()
                                .put("id", h.id)
                                .put("method", h.method)
                                .put("path", h.path)
                                .put("summary", h.summary)
                                .put("cap", h.cap ?: JSONObject.NULL)
                                .put("cap_enabled", h.cap?.let { CapabilityGate.enabled(ctx, it) }
                                    ?: JSONObject.NULL)
                        )
                    }
                    200 to JSONObject()
                        .put("ok", true)
                        .put("ambiguous", true)
                        .put("query", q)
                        .put("count", hits.size)
                        .put("candidates", arr)
                        .put("hint", "多个能力匹配，用 `dev help <id>` 看某一条的完整用法")
                        .toString()
                }
            }
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

    /**
     * 把目录渲染成**给 AI 看的一行式清单** —— v1.6。
     *
     * ## 为什么需要它
     * `AgentContextSeed.kt` 里原本手抄了一份能力列表（111 行）。新增端点时
     * 必须人工同步两处，漏掉就等于「AI 不知道有这个能力」（实测 42 条目录
     * 与说明书长期漂移）。现在说明书只保留**策略**（什么时候用哪个入口、
     * 有哪些坑），能力名册改为由本函数在写 AGENTS.md 时动态生成。
     *
     * ## 格式
     * 每个分组一行：`组名: id(method) · id(method) · ...`
     * 只给 id —— 一句话摘要太长会把说明书撑爆，AI 想要细节时用
     * `dev help <id>` 或 `dev` 查。
     *
     * @param capEnabled 能力开关查询（null 表示不查，全部照列）
     */
    fun renderRoster(capEnabled: ((String) -> Boolean)? = null): String {
        val sb = StringBuilder()
        for ((gname, _, items) in GROUPS) {
            if (items.isEmpty()) continue
            sb.append("- ").append(gname).append(": ")
            var first = true
            for (it in items) {
                val off = it.cap != null && capEnabled?.invoke(it.cap) == false
                if (!first) sb.append(" · ")
                first = false
                sb.append(it.id)
                if (it.method == "POST") sb.append("(POST)")
                if (off) sb.append("[off]")
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * 检查 [renderRoster] 与说明书正文是否漂移 —— v1.6。
     *
     * 判据：目录里每个 id 都必须在 `AgentContextSeed` 的**能力名册段**里出现。
     * 只检查 id 字面，不检查顺序或措辞（名册是自动生成的，措辞由模板决定）。
     *
     * @return 缺失的 id 列表；空表示一致
     */
    fun rosterDrift(seedText: String): List<String> {
        val roster = renderRoster(null)
        return ENTRIES.map { it.id }.distinct().filter { id ->
            // 名册里的写法可能是 `id` 或 `id(POST)`，也可能出现在 dev 用法示例里
            !rosterContains(seedText, id)
        }
    }

    /** 说明书里是否提到了这个 id（容忍 `(POST)` 后缀与反引号包裹）。 */
    private fun rosterContains(seedText: String, id: String): Boolean {
        val needle = id
        var from = 0
        while (true) {
            val i = seedText.indexOf(needle, from)
            if (i < 0) return false
            val before = seedText.getOrNull(i - 1)
            val after = seedText.getOrNull(i + needle.length)
            // 前后不能是标识符字符，避免 `net` 命中 `network`、`sms` 命中 `sms_post`
            val okBefore = before == null || !(before.isLetterOrDigit() || before == '_' || before == '-')
            val okAfter = after == null || !(after.isLetterOrDigit() || after == '_' || after == '-')
            if (okBefore && okAfter) return true
            from = i + 1
        }
    }

    fun verifyAgainst(routes: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        for (e in ENTRIES) {
            // 非 HTTP 条目（`gates/root` 这种门脚本）不参与路由对账 —— 它本来就不是
            // 桥端点。以前没排除，于是每次启动都报一条「目录写了但不存在的路由」，
            // 长期噪声会把真正的漂移淹掉。
            if (!e.path.startsWith("/")) continue
            // 一个条目可以同时支持 GET 与 POST，method 字段里两种写法并存：
            // `"GET|POST"`（watch / shz）与 `"GET/POST"`（settings）。以前拿整串去比对，
            // `GET|POST /dev/watch` 永远不等于路由表里的 `GET /dev/watch`，于是这三条
            // **每次启动都报漂移**，而它们其实完全正常。两种分隔符都要拆。
            for (m in e.method.split('|', '/')) {
                val key = "$m ${e.path}"
                if (key !in routes) problems += "catalog lists $key but no such route"
            }
        }
        // 反向：路由表里的端点若没登记，也算漏。
        //
        // ★ v1.7.10 修（真机审计）：这里原本是 `if (!r.contains(" /dev/") || ...) continue` ——
        //   只有 /dev/ 前缀参与反向对账。后果是 2026-11 新增的 /memory/*、/trace/*、
        //   /task/*、/vision/*、/ask/*、/handoff/* 整块能力加入时：目录不用改、
        //   启动不报警、MCP 工具清单（由目录生成）里也没有 —— 79 个路径对 AI
        //   完全隐身，而「对账」这个兜底机制恰好一次都没报。前缀不是契约的一部分，
        //   判据必须只看「路径在不在目录里」。
        //   元端点（/dev/help、/dev/routes）不参与：它们就是目录本身，登记自己无意义。
        //
        // 判据取**路径**而不是 method+path：一个端点常是「GET 只读 + POST 动作」，
        // 目录登记其中一条就足以让 dev 发现这个路径；另一条方法报「未登记」是误报。
        // （方法缺失另有正向检查：目录声明了但路由没有，会在上面那条循环里报。）
        val knownPaths = ENTRIES.filter { it.path.startsWith("/") }.map { it.path }.toSet()
        for (r in routes) {
            if (r in META_ROUTES) continue
            val path = r.substringAfter(" ")
            if (path !in knownPaths) {
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

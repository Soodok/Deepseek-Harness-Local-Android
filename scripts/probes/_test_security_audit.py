#!/usr/bin/env python3
"""安全审查回归护栏（v1.4.46，群友安全审查报告）。

## 背景

社区提交了一份源码级安全审查报告，列出 2 高危 + 6 中危 + 13 低危。本探针
把已修的部分锁住，防止后续重构悄悄退回：

| 编号 | 问题 | 修法 |
|---|---|---|
| H1 | LAN WebUI 6 位纯数字验证码、登录无速率限制 → 爆破即接管引擎会话 | 8 位字母数字口令 + 失败指数退避 + 锁定 + 防开放重定向 |
| H2 | 健康检查只认「3080 有 200」，端口被抢占则 WebView 加载攻击者页面 | 必须同时能从 engine.log 取到带 token 的 URL；拿不到不加载 |
| M1 | /vision/crop 的 id 直接拼路径 → 路径穿越写/读任意文件 | id 白名单 + canonical 前缀校验 |
| M2 | JS 桥/麦克风不校验来源，任意回环页面可静默拿麦克风 | onPermissionRequest 只放行 loopback origin |
| M3 | .npmrc 的 onlyBuiltDependencies 可被包名换行注入 | npm 包名正则校验 + lambda 形式 replace |

未修（记为 backlog，不在本探针断言）：M4 桥接并发上限、M5 通知缓存脱敏、
M6 插件清单签名、L1-L13。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def read(*parts):
    return open(os.path.join(ROOT, *parts), encoding="utf-8").read()


passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


# ══════════════════════════════════════════════════════════════════
# H1：LAN 验证码强度 + 登录限速
# ══════════════════════════════════════════════════════════════════
app = read("app", "src", "main", "java", "app", "dsh", "mobile", "DshApp.kt")
proxy = read("app", "src", "main", "assets", "tools", "lan-web-proxy.mjs")

# v1.5.0 用户决策：验证码改回 6 位数字（手输体验优先，宝宝 2026-10-31）。
# 熵只有 ~20 bit，因此**抗爆破责任全部落在代理端退避上** —— 下面那组
# failState/LOCK_AFTER/lockedUntil/429/Retry-After 断言从"补充项"升为"前提项"，
# 任何一条不成立，6 位码就等于裸奔。这里不再要求长度/字符集，改为要求
# ① 明确是 6 位数字；② 用密码学随机源；③ 退避参数确实存在且必须随时间递减。
_m = re.search(r'val pw = \(1\.\.LAN_CODE_LEN\)\.map \{ rnd\.nextInt\(10\) \}', app)
check("H1: 验证码为 6 位数字（用户决策，熵 ~20 bit）",
      _m is not None and 'const val LAN_CODE_LEN = 6' in app,
      "设计与注释不符：应为 LAN_CODE_LEN=6 的数字码")
check("H1: 验证码用 SecureRandom（非可预测 Random）",
      "java.security.SecureRandom()" in app
      and not re.search(r'val rnd = (kotlin\.random\.)?Random(?!\()', app),
      "种子可预测时能从历史验证码反推下一个")
check("H1: 注释明确「抗爆破依赖退避」不可删",
      "抗爆破" in app and "退避" in app,
      "6 位码的前提是退避生效，必须在源码里写死这条约束，防后人误删")
check("H1: 登录失败有退避/锁定状态机",
      "failState" in proxy and "LOCK_AFTER" in proxy and "lockedUntil" in proxy,
      "无退避时攻击者可无限并发爆破")
check("H1: 失败响应延迟（防耗时侧信道）",
      "setTimeout" in proxy and proxy.count("LOGIN_PAGE") >= 3,
      "成功/失败耗时差异可被用来加速猜测")
check("H1: 锁定期间返回 429 + Retry-After",
      "'Retry-After'" in proxy and "429" in proxy,
      "给客户端明确的退避信号，也便于排障")
check("H1: next 参数防开放重定向",
      "safeNext" in proxy and "//" in proxy and "startsWith" in proxy,
      "next=//evil.com 会把用户带去钓鱼站")

# ══════════════════════════════════════════════════════════════════
# H2：健康检查必须验证引擎身份
# ══════════════════════════════════════════════════════════════════
sup = read("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineSupervisor.kt")

check("H2: 健康判定要求同时拿到带 token 的 URL",
      re.search(r"code in 200\.\.499[\s\S]{0,400}?extractTokenUrl\(\)", sup) is not None,
      "只认证端口有响应 = 任何进程都能冒充引擎（同源钓鱼拿 API key）")
check("H2: 拿不到 tokenUrl 时 webUrl() 返回空而不是裸地址",
      'is State.Healthy -> s.tokenUrl.orEmpty()' in sup
      and 'is State.SafeMode -> s.tokenUrl.orEmpty()' in sup,
      "回退裸 http://127.0.0.1:3080/ 会加载攻击者页面")
check("H2: 提供 hasLoadableUrl() 供 UI 判断",
      "fun hasLoadableUrl()" in sup)

main_act = read("app", "src", "main", "java", "app", "dsh", "mobile", "MainActivity.kt")
check("H2: UI 加载前判空（不把空 URL 交给 WebView）",
      main_act.count("if (u.isNotEmpty()) loadLocalUrl(u)") >= 2,
      "加载空 URL 会让 WebView 停在 about:blank 且 urlLoaded 卡死")

# ══════════════════════════════════════════════════════════════════
# M1：/vision/crop 路径穿越
# ══════════════════════════════════════════════════════════════════
bridge = read("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentBridge.kt")

check("M1: crop 的 id 做了白名单校验",
      r'rawId.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))' in bridge,
      'id="../../engine/bin/su" 可覆盖 su 闸门 shim')
check("M1: loadTemplate 也校验 id + canonical 前缀",
      "canon.parentFile != dir" in bridge,
      "track/tap/drag 共用 loadTemplate，漏掉同样能读任意文件")

# ══════════════════════════════════════════════════════════════════
# M2：媒体权限来源校验
# ══════════════════════════════════════════════════════════════════
check("M2: onPermissionRequest 校验 origin 是回环",
      '"127.0.0.1"' in main_act and 'host != "localhost"' in main_act,
      "任意回环页面（含被注入的预览页）否则都能静默拿麦克风")

# ══════════════════════════════════════════════════════════════════
# M3：写 pnpm 策略前的包名注入
# v1.4.67：策略位从 .npmrc 的 onlyBuiltDependencies 换成
# pnpm-workspace.yaml 的 allowBuilds（pnpm 11 删除了旧键）。
# ══════════════════════════════════════════════════════════════════
plugin = read("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "PluginCenter.kt")

check("M3: 写 pnpm 策略前校验 npm 包名",
      "NPM_PACKAGE_NAME" in plugin and "matches(NPM_PACKAGE_NAME)" in plugin,
      "包名含换行/YAML 指示符可注入别的策略，绕过 pnpm 供应链防线")
check("M3: 包名正则覆盖 scoped",
      r"(@[a-z0-9-~][a-z0-9-._~]*/)?" in plugin)
check("M3: 键一律写成双引号标量",
      "'\"' + k + \"'\"" in plugin or '"\\"" + k + "\\": "' in plugin,
      "YAML 裸标量不允许以 @ 起始，scoped 包名不加引号会写坏文件")
check("M3: 不直接覆盖原文件（临时文件 + rename）",
      'f.name + ".tmp"' in plugin,
      "写一半失败会留下坏配置，插件全部装不了")

# ══════════════════════════════════════════════════════════════════
# 群友 dsh-root-doctor 的实测经验（susfs 隐藏挂载）
# ══════════════════════════════════════════════════════════════════
priv = read("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "Privilege.kt")

check("susfs: 属主修复的 find 带 -xdev",
      "append(\"-xdev \")" in priv,
      "无界遍历在 susfs/隐藏挂载环境会挂死启动链路")

# ══════════════════════════════════════════════════════════════════
# v1.4.51 全屏三件套
# 用户报：「部分手机屏幕下面横条会遮挡部分页面」「有些系统不会自动隐藏
# 状态栏，导致状态栏会遮挡页面」。两条边各修一处 + 一条兜底重断言。
# ══════════════════════════════════════════════════════════════════
check("全屏: WebView 底部 padding 取 max(IME, 导航区)",
      "maxOf(imeBottom, navBottom)" in main_act,
      "只算 IME 时键盘不在 → padding=0，页面底部被手势条压住")
check("全屏: 导航区 insets 覆盖 navigationBars 与 systemGestures",
      "Type.navigationBars()" in main_act and "Type.systemGestures()" in main_act,
      "只读 navigationBars 会漏掉手势导航机型")
check("全屏: 非全屏时底部 padding 归零（避免重复内缩）",
      "if (immersive) maxOf(imeBottom, navBottom) else 0" in main_act,
      "decorFits=true 时系统已让开导航区，再加 padding 会多出空白")
check("全屏: 导航条背景透明（去掉浅色带）",
      "w.navigationBarColor = android.graphics.Color.TRANSPARENT" in main_act,
      "实测深色页面上一条 rgb(245,247,250) 非常扎眼")
check("全屏: 关闭对比度强制（否则透明会被系统补成半透明灰）",
      "isNavigationBarContrastEnforced = false" in main_act)
check("全屏: 关闭全屏时恢复对比度强制",
      main_act.count("isNavigationBarContrastEnforced") >= 2)
check("全屏: 系统重置后有兜底重断言（OEM 不自动隐藏状态栏）",
      "scheduleImmersiveReassert" in main_act,
      "部分 OEM 在页面加载/切回前台时重置 systemUiVisibility")
check("全屏: 重断言有次数上限（不常驻轮询）",
      "immersiveReassertToken" in main_act and "longArrayOf(0L, 120L, 400L, 1000L)" in main_act,
      "常驻轮询会白耗电")
check("全屏: 页面加载完成与焦点变化后都重断言",
      main_act.count("scheduleImmersiveReassert()") >= 3,
      "要覆盖 onPageFinished + onWindowFocusChanged + applyStatusBarImmersive")
check("全屏: 重断言前检查用户是否还开着全屏",
      "if (!immersiveEnabled(this)) return" in main_act,
      "用户关了开关就该停，不能强行再隐藏")

# ══════════════════════════════════════════════════════════════════
# v1.4.54 灵动岛横向对齐挖孔
# 用户报：「全屏模式，灵动岛收起来有的时候根本不会对准摄像头位置，
# 不同手机摄像头位置不同」。根因：布局写死 center_horizontal，只按屏幕
# 居中，而挖孔可能在左 / 右 / 偏一侧。
# ══════════════════════════════════════════════════════════════════
check("灵动岛: 读 DisplayCutout.boundingRects 取真实孔位",
      "insets.displayCutout" in main_act and "boundingRects" in main_act,
      "insets 的 cutout top 只说遮挡深度，不说孔在哪个 x")
check("灵动岛: 多孔取并集中心（药丸+副孔也对）",
      "minOf { it.left }" in main_act and "maxOf { it.right }" in main_act,
      "只取第一个 boundingRect 会漏掉第二个孔")
check("灵动岛: 收起态按孔中心定位（纯 TOP 重力 + marginStart）",
      "android.view.Gravity.START" in main_act and "cutCenterX - handleW / 2" in main_act,
      "center_horizontal 会覆盖 marginStart，必须换重力")
check("灵动岛: 偏移夹在屏幕内（孔极靠边时不越界）",
      "coerceIn(0, (screenW - handleW)" in main_act,
      "左上角挖孔的机型，圆的左沿会跑出屏幕")
check("灵动岛: 无挖孔或非全屏时保持居中",
      "cutCenterX == null -> null" in main_act and "!immersive -> null" in main_act)
check("灵动岛: 收起时清零拖拽位移（否则被上次拖拽推偏）",
      "handle.translationX = 0f" in main_act and "handle.translationY = 0f" in main_act,
      "拖拽是临时手感，不该影响与摄像头的对齐")
check("灵动岛: GONE→VISIBLE 后补一次 insets 应用",
      "handle.requestApplyInsets()" in main_act,
      "GONE 时设的 layoutParams 不参与布局，可见后要重算")

# ══════════════════════════════════════════════════════════════════
# v1.4.58 灵动岛贴合摄像头（真机反馈「看着不跟摄像头」）
# 根因：boundingRect 是「被遮挡区域的外接矩形」，不等于摄像头形状。
# 真机 Rect(568,0-648,135)：宽 80 高 135 —— 摄像头是 80 直径的圆，
# 135 是纵向安全深度。按高度做直径会把圆做得又大又偏下。
# ══════════════════════════════════════════════════════════════════
check("灵动岛: 圆直径同时受孔尺寸与「不溢出」双重约束",
      "maxOf(cutHoleW, cutHoleH)" in main_act and "maxDotNoOverflow" in main_act,
      "只用孔尺寸会太小点不中；只用孔高会做成大黑饼")
check("灵动岛: 圆心对齐孔中心（不是永远贴 y=0）",
      "cutCenterY - wantDot / 2" in main_act,
      "摄像头靠边缘时圆要跟着上移")
check("灵动岛: 工具栏下移到孔下方（不贴屏幕顶边）",
      "cutTop + rowGap" in main_act,
      "贴 top=0 会让工具栏卡在屏幕边缘、圆角被裁，视觉上就是溢出")
check("灵动岛: 工具栏高度回归标准 rowH",
      "val wantRowH = rowH" in main_act,
      "已经下移就不需要为避开孔而额外拉高")
check("灵动岛: 圆尺寸受「不溢出屏幕」约束",
      "maxDotNoOverflow" in main_act,
      "直径超过圆心到顶距离的两倍就会跑到屏幕外")
check("灵动岛: 小圆用 TouchDelegate 扩到 48dp 触控区",
      "applyHandleTouchTarget" in main_act and "TouchDelegate" in main_act,
      "视觉尺寸只有 38dp 时手指点不中（用户实测「我都点不到」）")
check("灵动岛: 工具栏与圆用各自的 top（wantRowTop / wantTop）",
      "m.topMargin != wantRowTop" in main_act,
      "混用会让工具栏跟着圆心下移")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)

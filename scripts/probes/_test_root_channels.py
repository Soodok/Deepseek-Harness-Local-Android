#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""契约锁定：root 通道新增的三件能力（v1.6.0）。

本轮的出发点是「以往的优化都在服务无 root 用户」。加上这三件之后，
root / Shizuku 用户的体验差异集中在三处，每处都必须有断言钉住：

  1. `root uidump` —— 无障碍之外的第二条读屏通道（WebView/Flutter/游戏）
  2. `root appdata` —— 读其他 App 私有数据，**独立开关 + 只读 + 四道护栏**
  3. `scr --via root` —— 屏幕动作的 root 通道，不依赖无障碍服务存活

其中第 2 项是本项目新增的隐私边界，探针重点不是「能不能读」，而是
「不该读的时候一定读不到」：开关未开、包名非法、路径逃逸、非 SELECT，
四条任一失效都意味着 AI 拿到了超出用户授权的数据。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)


def p(*parts):
    return os.path.join(REPO, *parts)


GATE = p("app", "src", "main", "assets", "gates", "root")
SCR = p("app", "src", "main", "assets", "gates", "scr")
CONFIG = p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineConfig.kt")
SEED = p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentContextSeed.kt")
CATALOG = p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EndpointCatalog.kt")
GATEKEEPER = p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "CapabilityGate.kt")
SENSITIVE = p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "SensitiveAccess.kt")
CENTER = p("app", "src", "main", "java", "app", "dsh", "mobile", "PermissionCenterActivity.kt")
LAYOUT = p("app", "src", "main", "res", "layout", "activity_permission_center.xml")
ICON = p("app", "src", "main", "res", "drawable", "ic_appdata.xml")
SHZ = p("app", "src", "main", "assets", "gates", "shz")

checks = []
failed = []


def check(name, cond, detail=""):
    checks.append(name)
    if not cond:
        failed.append((name, detail))


def read(path):
    return io.open(path, encoding="utf-8").read()


def code_only(text):
    """剥掉注释行。

    这个项目里已经因为「注释里写了 setenforce 就以为实现了拦截」踩过两次，
    凡是禁/必需类断言一律跑在剥注释后的代码上。
    """
    return "\n".join(
        ln for ln in text.split("\n") if not ln.lstrip().startswith("#")
    )


gate = read(GATE)
gate_code = code_only(gate)
scr = read(SCR)
scr_code = code_only(scr)
gk = read(GATEKEEPER)
seed = read(SEED)
catalog = read(CATALOG)
sensitive = read(SENSITIVE)
center = read(CENTER)
layout = read(LAYOUT)

# ── 1. root uidump：第二条读屏通道 ────────────────────────────────
check("uidump subcommand exists", re.search(r"^\s{2}uidump\)", gate, re.M) is not None)
check(
    "uidump actually runs uiautomator",
    "uiautomator dump" in gate_code,
    "必须是真调用，不是只打印用法",
)
check(
    "uidump requires root",
    re.search(r"uidump\)(?:.|\n){0,400}?need_root", gate) is not None,
    "uiautomator dump 对 /data/local/tmp 写入需要 root 域",
)
check(
    "uidump detects empty dump instead of trusting exit code",
    re.search(r"!\s*-s\s+\"?\$?tmp", gate) is not None,
    "uiautomator 自身可能 segfault；判据必须是产物存在且有内容",
)
check(
    "uidump reports node count for cheap triage",
    '"nodes"' in gate,
    "先给节点数，AI 才好判断值不值得读全量 XML",
)
check(
    "uidump truncates raw xml",
    "head -c 200000" in gate_code,
    "整棵树 100KB+，不截断会直接撑爆上下文",
)

# ── 2. root appdata：隐私边界 ──────────────────────────────────────
check("appdata subcommand exists", re.search(r"^\s{2}appdata\)", gate, re.M) is not None)
for sub in ("sp", "ls", "cat", "db", "files"):
    check(f"appdata supports {sub}", re.search(rf"^\s{{6}}{sub}\)", gate, re.M) is not None)

check(
    "appdata requires root",
    re.search(r"appdata\)(?:.|\n){0,900}?need_root", gate) is not None,
)
check(
    "appdata checks its own capability switch",
    '"appdata":true' in gate and "capability disabled" in gate,
    "root 开关不等于允许读私有数据，必须查独立开关",
)
check(
    "appdata refuses when switch check is impossible",
    "cannot verify capability switch" in gate,
    "查不到开关状态时必须拒绝，不能默认放行",
)
check(
    "appdata validates package name",
    re.search(r"\*\[!A-Za-z0-9\._\]\*", gate) is not None,
    "包名是拼进路径的，不校验就能注入",
)
check(
    "appdata rejects path traversal",
    gate_code.count("path traversal not allowed") >= 3,
    "ls / cat / db 三处都要拦，漏一处就绕过",
)
check(
    "appdata db allows only SELECT/WITH",
    re.search(r"SELECT\*\|WITH\*", gate_code) is not None,
    "只读是对别的 App 数据的基本尊重",
)
check(
    "appdata db schema mode returns no rows",
    "pragma_table_info" in gate_code and "COUNT(*)" in gate_code,
    "不给 SQL 时只回表名/行数/列名，不泄数据",
)
check(
    "appdata refuses binary files",
    "*ELF*" in gate_code,
    "二进制当文本返回会污染上下文且毫无意义",
)
check(
    "appdata truncates text reads",
    "262144" in gate_code,
    "大文件要截断并标注",
)
check(
    "appdata never writes",
    not re.search(r"sqlite3[^\n]*\b(UPDATE|INSERT|DELETE|DROP)\b", gate_code, re.I),
    "任何写操作都不能出现在别的 App 数据上",
)

# ── 3. scr --via root：不依赖无障碍的动作通道 ─────────────────────
for verb in ("tap", "swipe", "key"):
    check(
        f"scr {verb} accepts --via",
        re.search(rf"^\s{{2}}{verb}\)(?:.|\n){{0,400}}?--via", scr, re.M) is not None,
    )
check(
    "scr tap root path uses input tap",
    "input tap" in scr_code,
)
check(
    "scr swipe root path uses input swipe",
    "input swipe" in scr_code,
)
check(
    "scr key root path uses input keyevent",
    "input keyevent" in scr_code,
)
# 逐条动作分别判 root 权限。
# 曾经写成 `count(...) >= 3` —— 把 tap 的那处判断删掉后，剩下 swipe/key/dump
# 三处仍满足「>= 3」，断言照样通过。数量门槛抓不住「某一处被单独移除」，
# 必须按动作分块锚定。
for verb in ("tap", "swipe", "key"):
    # 分支体内部本身就有 `\n  ` 缩进（注释、if 块），所以边界不能取「下一个
    # 两空格缩进」，否则几乎立刻就切断。取「下一个 case 分支定义」才可靠。
    start = scr.find("\n  " + verb + ")")
    nxt = scr.find("\n  ", start + len("\n  " + verb + ")")) if start >= 0 else -1
    while nxt > start and not scr[nxt + 3:nxt + 4].isalpha():
        nxt = scr.find("\n  ", nxt + 1)
    body = scr[start:nxt] if start >= 0 and nxt > start else ""
    check(
        f"scr {verb} root path requires root",
        'id -u 2>/dev/null)" = "0"' in body,
        "每条动作各自都要判，漏一条就是绕过",
    )
check(
    "scr default stays on accessibility",
    'via="a11y"' in scr_code,
    "默认必须还是无障碍（毫秒级），root 只是可选通道",
)
check(
    "scr key maps back/home/recents to real keycodes",
    "code=4" in scr_code and "code=3" in scr_code,
    "input 要的是 keycode，不是动作名",
)

# ── 4. scr dump 三层读屏回落 ──────────────────────────────────────
check(
    "scr dump detects empty accessibility tree",
    '" 0nodes"' in scr_code,
    "只有真为空才回落，否则每次读屏都多花 300~900ms",
)
check(
    "scr dump falls back to uiautomator",
    "uiautomator dump" in scr_code,
)
check(
    "scr dump fallback marks itself in output",
    "fallback=uiautomator" in scr_code,
    "必须让 AI 知道这棵树来自另一条链路，判读方式不同",
)
check(
    "scr dump fallback is root-only",
    re.search(r'id -u 2>/dev/null\)" = "0"', scr_code) is not None,
)

# ── 4b. LD_LIBRARY_PATH 污染（两个 uidump 都踩过）──────────────────
# 门脚本自举把 LD_LIBRARY_PATH 指向引擎 lib，而 uiautomator 是 Android 原生
# 程序，会先按这个路径找自己的库 → `Runtime library not loaded` 并 abort。
# 实测：带 LD_LIBRARY_PATH 必崩，env -u 就正常。两个 uidump 都必须清掉。
check(
    "root uidump clears LD_LIBRARY_PATH",
    "env -u LD_LIBRARY_PATH uiautomator dump" in gate_code,
    "不清就会报 Runtime library not loaded，且极像环境问题",
)
check(
    "shz uidump clears LD_LIBRARY_PATH",
    "env -u LD_LIBRARY_PATH uiautomator dump" in code_only(read(SHZ)),
)
# 只认「真正会执行」的形态：命令替换 $(uiautomator dump 或行首调用。
# 错误文案 "uiautomator dump produced no file" 也含该子串，直接查子串
# 会把断言骗成永远变红（本条第一次就是这么写错的）。
_bare_calls = [
    ln.strip() for ln in gate_code.split("\n")
    if ("$(uiautomator dump" in ln) or ln.strip().startswith("uiautomator dump")
]
check(
    "no bare uiautomator call in root gate",
    not _bare_calls,
    f"裸调用会被 LD_LIBRARY_PATH 打死：{_bare_calls[:1]}",
)

# ── 4c. shz 门脚本（Shizuku / ADB shell）──────────────────────────
shz = read(SHZ)
shz_code = code_only(shz)
check("shz gate exists", os.path.isfile(SHZ))
check("shz has shebang", shz.startswith("#!/system/bin/sh"))
check(
    "shz bootstraps to engine bash",
    "@DSH_BASH@" in shz and "_DSH_REEXEC" in shz,
    "要 /dev/tcp 才能访问桥",
)
for sub in ("status", "exec", "uidump", "appdocs"):
    # 分支写法是 `  status|"")` 或 `  exec)`，所以只要求「行首两空格 +
    # 子命令名 + 紧随 `)` 或 `|`」，不能要求一定是 `)`。
    check(
        f"shz supports {sub}",
        re.search(rf'^\s{{2}}{sub}[)|]', shz, re.M) is not None,
    )
check(
    "shz validates package name",
    "invalid package name" in shz_code,
    "appdocs 的 pkg 是拼进路径的",
)
check(
    "shz refuses db name with traversal",
    "invalid db name" in shz_code,
)
check(
    "shz appdocs returns structure not rows",
    "sqlite_master" in shz_code and "SELECT * FROM" not in shz_code,
    "只回表名，不回数据",
)
check(
    "shz needs the bridge helper",
    "_dsh_http.sh missing" in shz,
    "拿不到 binder 必须明确报错，不能静默失败",
)
check(
    "shz is registered in deploy list",
    '"shz"' in read(CONFIG),
    "不注册就永远部署不到设备",
)
check(
    "capability SHIZUKU declared",
    'const val SHIZUKU = "shizuku"' in gk,
)
check(
    "SHIZUKU in snapshot",
    ".put(SHIZUKU, can(ctx, SHIZUKU))" in gk,
)
check(
    "SHIZUKU gated on shizukuUsable",
    "SHIZUKU -> Privilege.shizukuUsable()" in gk,
    "判据是 server 在跑 + 已授权，与 ROOT 模式无关",
)
check(
    "ShizukuCap endpoint exists",
    os.path.isfile(p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "cap", "ShizukuCap.kt")),
)
bridge = read(p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentBridge.kt"))
check(
    "bridge routes /dev/shz for both GET and POST",
    # 实际写法是 `"GET /dev/shz" to Route(...)`，带引号的纯路径不存在
    bridge.count("/dev/shz") >= 2,
    "GET 与 POST 各要一条，否则 shz 只能读不能写",
)
check("permission center binds shizuku tile", "CapabilityGate.SHIZUKU" in center)
check("layout has shizuku switch", 'android:id="@+id/swCapShizuku"' in layout)
check(
    "shizuku icon exists",
    os.path.isfile(p("app", "src", "main", "res", "drawable", "ic_shizuku.xml")),
)
check("seed documents shz", "shz" in seed and "/dev/shz" in seed)
check("catalog lists shz", '"shz"' in catalog)
for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
    txt = read(p("app", "src", "main", "res", loc, "strings.xml"))
    check(f"{loc} has shizuku strings", "setting_cap_shizuku" in txt)

# ── 4d. shz 单一实现（两份实现抢同一路径的坑）────────────────────
# applyShzGate 早先自己写一份 shz 并在非 SHIZUKU 模式 delete() 它，
# 与 assets/gates/shz 抢同一个文件：ROOT 模式下资产版被删，
# `shz uidump` / `shz appdocs` 直接消失，而 grep 注册清单又看起来正常。
_cfg_code = code_only(read(CONFIG))
_shz_fn = _cfg_code[_cfg_code.find("private fun applyShzGate("):]
_shz_fn = _shz_fn[: _shz_fn.find("\n    }") + 6] if "\n    }" in _shz_fn else _shz_fn
check(
    "applyShzGate does not write its own shz",
    "writeText" not in _shz_fn,
    "两份实现抢同一路径，部署顺序决定谁活下来",
)
check(
    "applyShzGate only clears shz in NORMAL mode",
    "mode != PrivMode.NORMAL" in _shz_fn,
    "Shizuku / ROOT 都必须保留资产版脚本",
)
check(
    "shz keeps legacy `shz <cmd>` fallback",
    "向后兼容" in shz and 'json_escape "$CMD $*"' in shz,
    "升级成子命令式后旧提示词必须仍然有效",
)

# Kotlin 侧的 "只看代码"：剥掉 // 行注释与 * 开头的 KDoc 续行。
# 不这么做的话，文档里提到的 flag / action 名字会让断言假绿
# （这条教训在本仓库反复出现：注释能骗过子串断言）。
def kt_code(text):
    out = []
    for ln in text.splitlines():
        t = ln.strip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        out.append(ln)
    return "\n".join(out)


# ── 4e. v1.6.x 新增：/dev/node 节点语义动作 ──────────────────────
bridge_all = read(p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentBridge.kt"))
check(
    # 实际写法是 `"POST /dev/node" to Route(...)`，带引号的纯路径不存在
    "bridge routes POST /dev/node",
    "dev/node" in bridge_all,
    "节点动作没有入口就等于没做",
)
for op in ("long_click", "expand", "collapse", "dismiss", "read", "scroll"):
    check(
        f"node op {op} handled",
        f'"{op}" ->' in bridge_all,
        "六个 op 缺一个就会落到 else 分支报 400",
    )
check(
    "node action gated by CONTROL",
    "CapabilityGate.can(ctx, CapabilityGate.CONTROL)" in bridge_all,
    "节点动作属于屏幕控制，必须过 control 开关",
)
check(
    "node read documents password block",
    "password field" in bridge_all,
    "不说清楚，调用方会把 ok=false 误判成没找到节点",
)

# ── 4f. launch-intent 扩展（直接调起别的 App）────────────────────
check(
    "launch-intent supports explicit component",
    "setClassName(" in bridge_all,
    "没有 pkg+cls 就只能发隐式 Intent，跳不到具体页面",
)
check(
    "launch-intent supports extras",
    # 锚定 "取值后立刻遍历" 这一形态：只匹配 optJSONObject("extras") 会被
    # 后面统计 extras_count 的那几行满足，等于没验到真逻辑。
    'optJSONObject("extras")?.let {' in bridge_all,
    "深链参数几乎都在 extras 里，不给就只能落到首页",
)
check(
    "launch-intent rejects unknown extra types loudly",
    "unsupported extra type" in bridge_all,
    "静默丢参数会让调用方以为传了没生效",
)
check(
    "launch-intent supports dry_run",
    'optBoolean("dry_run"' in bridge_all,
)

# ── 4g. 无障碍新增 flag 与动作 ───────────────────────────────────
a11y = read(p("app", "src", "main", "java", "app", "dsh", "mobile", "DshAccessibilityService.kt"))
# 用 kt_code：这些 flag 名字在注释里也出现（解释为什么加），
# 只查全文会假绿。必须锚定真正拼进 info.flags 的那段代码。
a11y_code = kt_code(a11y)
for flag in (
    "FLAG_INCLUDE_NOT_IMPORTANT_VIEWS",
    "FLAG_REPORT_VIEW_IDS",
    "FLAG_REQUEST_MULTI_FINGER_GESTURES",
    "FLAG_RETRIEVE_INTERACTIVE_WINDOWS",
):
    check(
        f"a11y flag {flag} requested",
        f"AccessibilityServiceInfo.{flag}" in a11y_code,
        "必须真的拼进 info.flags，不能只写在注释里",
    )
for act in (
    "ACTION_LONG_CLICK",
    "ACTION_EXPAND",
    "ACTION_COLLAPSE",
    "ACTION_DISMISS",
    "ACTION_SET_SELECTION",
    "ACTION_COPY",
):
    check(f"a11y action {act} used", act in a11y)
check(
    "a11y directional scroll uses AccessibilityAction.id",
    "AccessibilityAction.ACTION_SCROLL_UP.id" in a11y,
    "ACTION_SCROLL_UP 是 AccessibilityAction 对象，不是 int 常量，直接写编译不过",
)
check(
    "a11y read restores clipboard",
    "dsh-restore" in a11y,
    "不还原会把用户刚复制的东西弄丢",
)

# ── 4h. root 门脚本新增 content / appops ─────────────────────────
for sub in ("content", "appops"):
    check(
        f"root gate has {sub} branch",
        re.search(rf"^  {sub}\)", gate, re.M) is not None,
    )
check(
    "content refuses non-content:// uri",
    # 锚定 case 分支 `content://*) ;;`，而不是只找那句错误文案 ——
    # 文案在 `*)` 兜底分支里也有，删掉校验也依然能命中（本条第一版就假绿了）。
    "content://*) ;;" in gate,
    "不限 scheme 就变成任意文件访问了",
)
check(
    "content uri uses whitelist not blacklist",
    "outside the allowed set" in gate,
    "黑名单容易漏，白名单才可靠",
)
check(
    "content insert/update/delete need confirm",
    'dry_run":true,"would_run":"content %s %s' in gate,
    "写别人的数据必须默认 dry-run",
)
check(
    "content shares cap.appdata",
    "content shares that switch" in gate,
    "拆成两个开关会让用户不知道该关哪个",
)
# 只看可执行代码：我在注释里写了「content 没有 --limit」来解释原因，
# 直接查全文会把这条注释当成违规（这条断言第一版就是这么假红的）。
_gate_bare = code_only(gate)
check(
    "content does not pass unsupported --limit",
    "--limit " not in _gate_bare,
    "content 命令实测报 Unsupported argument: --limit",
)
check(
    "appops set/reset need confirm",
    # 不能用「全文 dry_run 出现次数 >= N」这种计数断言：rm / content / exec
    # 的 dry_run 会把计数撑住，改坏 appops 也依然绿。锚定 appops 自己那条。
    '"dry_run":true,"would_run":"appops set %s %s %s"' in gate
    and '"dry_run":true,"would_run":"appops reset %s %s"' in gate,
    "appops set 是替别的 App 授权，必须默认 dry-run",
)
check(
    "appops rejects non allow/deny/default",
    "value must be allow|deny|default" in gate,
)
check(
    # "for p in" 在 rm 分支里本来就有（遍历待删路径），所以不能全文否定；
    # 这里只断言 appops 分支**没有**对包名做循环。
    "appops does no batch over packages",
    'appops reset "$pkg" 2>&1' in gate
    and 'for p in "$pkg"' not in gate
    and 'for pkg in' not in gate,
    "一次只改一个 App，批量会把整机权限改乱，无法审计",
)

# ── 4i. 目录与种子对口 ────────────────────────────────────────────
cat_all = read(CATALOG)
check("catalog lists node endpoint", '"/dev/node"' in cat_all)
check("catalog root params include content", "content | appops" in cat_all)
seed_all = read(SEED)
check("seed documents dev node", "`dev node`" in seed_all)
check("seed documents root content", "root content <query|insert|update|delete>" in seed_all)
check("seed documents root appops", "root appops <get|set|reset>" in seed_all)
check(
    "seed warns appops user is not notified",
    "never notified" in seed_all,
    "不说清楚会让人误以为用户能看到授权提示",
)

# ── 4j. 批次1：/dev/watch 事件流（输入 / 按键 / 窗口）─────────────
WATCH = p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "cap", "WatchCap.kt")
watch = read(WATCH)
watch_code = kt_code(watch)
check("WatchCap exists", os.path.isfile(WATCH))
for kind in ("input", "key", "window"):
    check(
        f"watch has {kind} buffer",
        f"{kind}Buf" in watch_code,
        "三类事件各自一个环形缓冲",
    )
for cap_name in ("MAX_INPUT", "MAX_KEY", "MAX_WINDOW"):
    check(f"watch caps {cap_name}", cap_name in watch_code, "无上限会撑爆内存")
check(
    "watch buffers are in-memory only",
    "SharedPreferences" not in watch_code and "FileOutputStream" not in watch_code,
    "落盘会把「能读当前输入」变成「能翻历史输入」",
)
check(
    # 只找 "repeat" 会被字段读取那一侧满足；锚定自增表达式（本条第一版假绿）。
    "watch dedups consecutive windows",
    'last.put("repeat", last.optInt("repeat", 1) + 1)' in watch_code,
    "同一界面反复上报会瞬间灌满窗口缓冲",
)
check(
    "watch records input via SensitiveAccess",
    "SensitiveAccess.record(" in watch_code,
    "读输入必须留痕，否则用户不知道被读了多少次",
)
check(
    "watch gates input on INPUTWATCH",
    "CapabilityGate.INPUTWATCH" in watch_code,
)
check(
    "watch gates key on KEYWATCH",
    "CapabilityGate.KEYWATCH" in watch_code,
)

# 无障碍侧：采集入口与隐私闸
a11y2 = read(p("app", "src", "main", "java", "app", "dsh", "mobile", "DshAccessibilityService.kt"))
a11y2_code = kt_code(a11y2)
check(
    "a11y handles TYPE_VIEW_TEXT_CHANGED",
    "TYPE_VIEW_TEXT_CHANGED ->" in a11y2_code,
)
check(
    "a11y drops password fields in text events",
    "e.isPassword" in a11y2_code,
    "密码框必须整条丢弃，连长度都不能记",
)
check(
    "a11y requests FILTER_KEY_EVENTS",
    "FLAG_REQUEST_FILTER_KEY_EVENTS" in a11y2_code,
)
# 全文找 "return false" 毫无意义：这个文件里到处都是。必须切出 onKeyEvent 的
# 函数体，断言它**最后**是 return false（本条第一版就是假绿的）。
_oke = a11y2_code[a11y2_code.find("override fun onKeyEvent"):]
_oke = _oke[: _oke.find("override fun onInterrupt")]
check(
    "a11y onKeyEvent never consumes",
    "return false" in _oke and "return true" not in _oke,
    "只观察不拦截 —— 否则开了开关音量键就不起作用了",
)
check(
    "a11y records window switches",
    "WatchCap.recordWindow(" in a11y2_code,
)

# 开关与敏感档
gk2 = gk
check("capability INPUTWATCH declared", 'const val INPUTWATCH = "inputwatch"' in gk2)
check("capability KEYWATCH declared", 'const val KEYWATCH = "keywatch"' in gk2)
check(
    "INPUTWATCH/KEYWATCH in snapshot",
    ".put(INPUTWATCH, can(ctx, INPUTWATCH))" in gk2
    and ".put(KEYWATCH, can(ctx, KEYWATCH))" in gk2,
)
sens = read(p("app", "src", "main", "java", "app", "dsh", "mobile", "engine", "SensitiveAccess.kt"))
check(
    "SensitiveAccess has INPUT_TEXT kind",
    "INPUT_TEXT(" in sens,
    "输入告警要单独一档，不能混在剪贴板里",
)

# 桥路由与 GET 参数归一化
check(
    "bridge routes /dev/watch for GET and POST",
    bridge_all.count("dev/watch") >= 2,
)
check(
    "watch GET coerces since to a number",
    'if (k == "since") o.put(k, v.toLongOrNull() ?: 0L)' in bridge_all,
    "since 传字符串会让 optLong 静默回 0，增量拉取直接失效",
)

# 目录 / 种子 / UI / 文案
check("catalog lists watch endpoint", '"/dev/watch"' in cat_all)
check("seed documents dev watch", "`dev watch`" in seed_all)
check(
    "seed states key events are observed not intercepted",
    "observed, never intercepted" in seed_all,
)
center2 = read(p("app", "src", "main", "res", "layout", "activity_permission_center.xml"))
check("layout has inputwatch switch", 'android:id="@+id/swCapInputwatch"' in center2)
check("layout has keywatch switch", 'android:id="@+id/swCapKeywatch"' in center2)
pca = read(p("app", "src", "main", "java", "app", "dsh", "mobile", "PermissionCenterActivity.kt"))
check(
    # 必须带尾随逗号：不带的话改成 swCapInputwatchX 依然命中前缀（本条第一版假绿）。
    "permission center binds INPUTWATCH tile",
    "CapabilityGate.INPUTWATCH, R.id.swCapInputwatch," in pca,
    "只加布局不接线，开关点了没有任何效果",
)
check(
    "permission center binds KEYWATCH tile",
    "CapabilityGate.KEYWATCH, R.id.swCapKeywatch," in pca,
)
for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
    txt = read(p("app", "src", "main", "res", loc, "strings.xml"))
    check(
        f"{loc} has watch strings",
        "setting_cap_inputwatch" in txt
        and "setting_cap_keywatch" in txt
        and "sensitive_kind_input_text" in txt,
    )

# ── 4k. 批次2：root binder / sendevent ───────────────────────────
for sub in ("binder", "sendevent"):
    check(
        f"root gate has {sub} branch",
        re.search(rf"^  {sub}\)", gate, re.M) is not None,
    )

# binder：list 只读、call 必须 confirm
check(
    "binder call is dry-run by default",
    '"would_run":"service call %s %s' in gate,
    "txn 是盲调，调错码会触发不相干的操作，没有回滚",
)
check(
    "binder validates service name",
    "invalid service name" in gate,
)
check(
    "binder validates txn is an integer",
    "txn must be an integer" in gate,
)
# list 是只读枚举，不该被 confirm 挡住。切出 list 分支单独判 ——
# 全文查 "binder list" 会落空（脚本里没有这个字面量，只有 usage 文案）。
# 先切出 binder 分支本身：全文找 "      list)" 会命中 pm 分支那一个（首个匹配），
# 而 "      call)" 在它后面根本不存在，find 返回 -1 就会把整份脚本切进来。
_gb = gate[gate.find("  binder)"):]
_gb = _gb[: _gb.find(chr(10) + "  sendevent)")]
_bl = _gb[_gb.find("      list)"):]
_bl = _bl[: _bl.find("      call)")]
check(
    "binder list needs no confirm",
    'services":%s' in _bl and "confirm" not in _bl,
    "枚举服务是只读操作，加 confirm 只会让每次调用多一轮往返",
)
check(
    "binder count uses POSIX class not backslash-s",
    "grep -cE '^[0-9]+[[:space:]]'" in gate and "grep -c '^[0-9]*" not in gate,
    "toybox 的 grep 不认反斜杠加 s，实测报 trailing backslash",
)

# sendevent：只放行 SYN/KEY
check(
    "sendevent is dry-run by default",
    '"would_run":"sendevent %s %s %s %s"' in gate,
)
# 锚定 case 分支本身，而不是那句错误文案 —— 文案在拒绝分支里，
# 把 2|3 放行以后文案照旧存在，断言会假绿（本条第一版就是这样）。
check(
    "sendevent only accepts type 0 and 1",
    "      0|1) ;;" in gate and "0|1|2|3) ;;" not in gate,
    "坐标事件写错会把触摸屏钉死，且没有复位手段",
)
check(
    "sendevent validates device path",
    "device must be /dev/input/eventN" in gate,
    "不限路径就等于让调用方往任意设备节点写",
)
check(
    "sendevent checks device exists",
    '[ -e "$dev" ]' in gate,
)

# 目录与种子
check("catalog root params include binder", "binder | sendevent" in cat_all)
check("seed documents root binder", "root binder <list|call>" in seed_all)
check("seed documents root sendevent", "root sendevent <device> <type> <code> <value>" in seed_all)
check(
    "seed warns txn calls are blind",
    "blind" in seed_all,
    "不说明白会让人以为 txn 码是稳定的",
)

# ── 4l. 批次3：HTML 元素遍历 / 选择动作 / 窗口枚举 ───────────────
for op in ("html_next", "html_prev", "select", "clear_selection"):
    check(
        f"node op {op} handled",
        f'"{op}"' in bridge_all,
        "新 op 没接进 nodeAct 就会落到 else 报 400",
    )
# 必须锚定完整调用：只查常量名的话，把它改成空串也照样命中（本条第一版假绿，
# 反向验证时是「变红了但不是预期那条」—— 红来自相邻的 uppercase 断言）。
check(
    "html hop passes the element argument",
    "putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, element.uppercase())"
    in a11y2_code,
    "不给 element 参数时系统按默认行为跳，等于没用",
)
# 切出 hopHtmlElement 函数体再断言：全文找 ".uppercase()" 在这文件里到处都是，
# 改坏了也不会红（反向验证时证实过）。切片的边界用「下一个 fun 」。
_hh = a11y2_code[a11y2_code.find("fun hopHtmlElement("):]
_hh = _hh[: _hh.find("fun ", 10)]
check(
    "html hop uppercases the element",
    "element.uppercase()" in _hh,
    "系统按字面匹配 LINK/CONTROL 等大写值，小写会被忽略",
)
check(
    "select clears via root node",
    "ACTION_CLEAR_SELECTION" in a11y2_code,
    "清选中作用于整个窗口，不是某个节点",
)
check(
    "clear_selection needs no text",
    'op !in setOf("scroll", "clear_selection")' in bridge_all,
    "它作用于整窗选中态，强制要 text 会让调用方以为必须指定节点",
)

# /dev/windows
check("bridge routes GET /dev/windows", "dev/windows" in bridge_all)
check(
    "windows endpoint gated by STATUS",
    "CapabilityGate.can(c, CapabilityGate.STATUS)" in bridge_all,
)
check(
    "window list reports root availability",
    "root_available" in a11y2_code,
    "系统窗口根节点为 null 是常态，不标出来会被误判成读失败",
)
check(
    "window list names window types",
    "split_screen_divider" in a11y2_code and "input_method" in a11y2_code,
)
check(
    "window list exposes bounds",
    '"bounds"' in a11y2_code,
)
check(
    "window enumeration result is typed Pair",
    "val out: Pair<Int, String> = runCatching" in bridge_all,
    "runCatching 里写字符串模板会被推断成 Serializable，编译不过",
)

check("catalog lists windows endpoint", '"/dev/windows"' in cat_all)
check("catalog node params include html_next", "html_next|html_prev" in cat_all)
check("seed documents dev windows", "`dev windows`" in seed_all)
check(
    "seed explains null root is not a failure",
    "not a read failure" in seed_all,
)

# ── 4m. 目录完整性：每个端点都必须写 limits ──────────────────────
# limits 是这个目录最有价值的部分：它写的是「什么时候会骗你」。
# 没有 limits 的端点，AI 只能看到「能干什么」，看不到坑。
# 用括号配对逐个条目检查（不能用正则切分：条目内部有嵌套括号与多行拼接）。
def _catalog_entries(text):
    out = []
    for m in re.finditer(r"\n {16}e\(", text):
        i = m.start() + 1
        depth = 0
        j = i + 1
        instr = False
        while j < len(text):
            c = text[j]
            if instr:
                if c == "\\":
                    j += 2
                    continue
                if c == '"':
                    instr = False
            else:
                if c == '"':
                    instr = True
                elif c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                    if depth == 0:
                        break
            j += 1
        out.append(text[i:j])
    return out


_entries = _catalog_entries(cat_all)
_no_limits = []
for _seg in _entries:
    if "limits =" in _seg:
        continue
    _m = re.search(r'"([^"]+)"', _seg)
    _no_limits.append(_m.group(1) if _m else "?")
check(
    "every catalog entry declares limits",
    len(_entries) > 40 and not _no_limits,
    f"缺 limits 的端点：{_no_limits}",
)

# ── 4n. i18n 检查器必须剥 Kotlin 注释再找 R.string 引用 ──────────
# 曾经的真实假阳性：注释里写 `getString(R.string.ext_cat_*)`，
# 正则把它当成引用，报「referenced but not defined」，而 ext_cat_lang 明明已定义。
import importlib.util as _ilu

# 先清掉可能的 __pycache__：importlib 用 mtime+size 判失效，同秒改写会命中旧
# 字节码，导致探针实际测的是**上一版**函数（本次就踩到了，现象是「源码看着对、
# 执行结果却是旧的」）。
import shutil as _sh
_sh.rmtree(p("scripts", "__pycache__"), ignore_errors=True)
_spec = _ilu.spec_from_file_location("_i18nchk", p("scripts", "check-i18n.py"))
_i18n = _ilu.module_from_spec(_spec)
_spec.loader.exec_module(_i18n)
_strip = getattr(_i18n, "strip_kotlin_comments", None)
check(
    "i18n checker has strip_kotlin_comments",
    callable(_strip),
    "没有剥离函数，注释里的引用又会变假阳性",
)
if callable(_strip):
    _cases = [
        # (源码, 必须保留, 必须剥掉)
        ('val u = "http://x" + g(R.string.keep1)', ["keep1"], []),
        ('/* g(R.string.drop1) */ g(R.string.keep2)', ["keep2"], ["drop1"]),
        ("// g(R.string.drop2)\ng(R.string.keep3)", ["keep3"], ["drop2"]),
        ("/** KDoc R.string.drop3 */ g(R.string.keep4)", ["keep4"], ["drop3"]),
        # Kotlin 块注释可嵌套 —— 只按第一个 */ 截断会漏掉后半段
        ("/* a /* nested R.string.drop4 */ b R.string.drop5 */ g(R.string.keep5)",
         ["keep5"], ["drop4", "drop5"]),
        # 字符串里的 // 不是注释，不能把后半行一起吃掉
        ('val q = "a\\"//b"; g(R.string.keep6)', ["keep6"], []),
    ]
    for _src, _must, _mustnot in _cases:
        _out = _strip(_src)
        for _k in _must:
            check(
                f"strip keeps {_k}",
                ("R.string." + _k) in _out,
                f"字符串内容必须原样保留：{_src[:40]}",
            )
        for _k in _mustnot:
            check(
                f"strip drops {_k}",
                ("R.string." + _k) not in _out,
                f"注释里的引用必须剥掉：{_src[:40]}",
            )
    check(
        "i18n resolves refs from comment-stripped source",
        "strip_kotlin_comments(read(p))" in read(p("scripts", "check-i18n.py")),
        "算出剥离函数却没在 check_string_refs 里用，等于没修",
    )

# ── 4o. 缺 runtime.zip 时必须显式 SKIP，不能伪装成通过 ──────────
# 这是「跳过当通过」的反面：以前它报的是**失败**，每次全量都要人工判读
# 「这条是已知基线」。现在必须自己announce SKIP、单独计数、退出码 0。
import subprocess as _sp

_r = _sp.run(
    [sys.executable, p("scripts", "probes", "_test_corepack_hardlink.py")],
    capture_output=True,
    text=True,
    encoding="utf-8",
    errors="replace",
)
_out = (_r.stdout or "") + (_r.stderr or "")
check(
    "corepack probe exits 0 without runtime.zip",
    _r.returncode == 0,
    f"rc={_r.returncode}",
)
check(
    "corepack probe announces SKIP instead of silently passing",
    "SKIP" in _out and "跳过" in _out,
    "缺 zip 时必须显式说明哪些组本轮没验证",
)

# ── 5. 能力开关与权限中心 ─────────────────────────────────────────
check("capability APPDATA declared", 'const val APPDATA = "appdata"' in gk)
check(
    "APPDATA is separate from ROOT",
    'const val ROOT = "root"' in gk and 'const val APPDATA = "appdata"' in gk,
    "必须两个独立常量，不能复用 root",
)
check(
    "APPDATA appears in snapshot",
    ".put(APPDATA, can(ctx, APPDATA))" in gk,
    "能力总览里没有它就等于不可见",
)
check(
    "APPDATA gated on root privilege mode",
    re.search(r"APPDATA -> Privilege\.getMode\(ctx\) == PrivMode\.ROOT", gk) is not None,
)
check(
    "permission center binds the appdata tile",
    "CapabilityGate.APPDATA" in center,
)
check(
    "layout has appdata switch",
    'android:id="@+id/swCapAppdata"' in layout,
    "没有磁贴用户就无处授权",
)
check("appdata icon exists", os.path.isfile(ICON))

# ── 6. 敏感读取告警 ───────────────────────────────────────────────
check(
    "SensitiveAccess knows APPDATA",
    re.search(r'APPDATA\("appdata"', sensitive) is not None,
    "读了别人的私有数据必须有用户可见告警",
)
for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
    sp = p("app", "src", "main", "res", loc, "strings.xml")
    txt = read(sp)
    check(
        f"{loc} has appdata strings",
        "setting_cap_appdata" in txt and "sensitive_kind_appdata" in txt,
        "五语种都要有，否则切语言就露英文",
    )

# ── 7. AI 可见性：不写进说明书的能力等于不存在 ────────────────────
check("seed documents root uidump", "root uidump" in seed)
check("seed documents root appdata", "root appdata" in seed)
check("seed documents --via root", "--via root" in seed)
check(
    "seed warns appdata has its own switch",
    "cap.appdata" in seed or "separate switch" in seed,
    "AI 必须知道 root 打开了也不等于能读私有数据",
)
check("catalog lists uidump", "uidump" in catalog)
check("catalog lists appdata", "appdata" in catalog)
check("catalog documents appdata guardrails", "cap.appdata" in catalog)
check(
    "root help mentions uidump",
    "#   root uidump" in gate,
    "usage() 从文件头注释抽取，不加就查不到",
)
check("root help mentions appdata", "#   root appdata" in gate)
check("scr help mentions --via root", "[--via root]" in scr)

# ── 8. 可选依赖不能拖垮整个工具 ───────────────────────────────────
check(
    "root sources _dsh_http.sh conditionally",
    re.search(r'if \[ -f "\$DIR/_dsh_http\.sh" \]; then \. "\$DIR/_dsh_http\.sh"; fi', gate) is not None,
    "缺 _dsh_http.sh 时 ps/kill/cat 仍要能用，只有 appdata 报错",
)

# ── 输出 ───────────────────────────────────────────────────────────
print(f"root channels contract: {len(checks)} checks")
if failed:
    print(f"\nFAILED ({len(failed)}):")
    for name, detail in failed:
        print(f"  x {name}" + (f" — {detail}" if detail else ""))
    sys.exit(1)
print("All root channel checks passed.")

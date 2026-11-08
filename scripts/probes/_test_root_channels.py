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

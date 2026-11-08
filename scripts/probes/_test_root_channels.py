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

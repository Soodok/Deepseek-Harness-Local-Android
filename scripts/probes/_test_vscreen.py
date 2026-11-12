#!/usr/bin/env python3
"""虚拟屏（vscreen）链路的静态门禁。

覆盖四层：
1. 门脚本本体 —— 子命令齐、终止空行单独发、app_process 用 env -u 清库路径、
   后台拉起的进程脱离父会话。
2. 核心 —— 连接处理必须整体被 runCatching 包住（独立线程的未捕获异常会杀进程）、
   launch 区分包名与组件、shot 等首帧。
3. 接线 —— 路由、能力开关、权限中心磁贴、端点目录、门脚本三处清单。
4. 文案 —— 六语种齐全。

判据一律基于**剥掉注释后的可执行代码**：注释里写 "env -u LD_LIBRARY_PATH"
是说明，不是实现。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
fails = []
passes = 0


def check(name, ok, detail=""):
    global passes
    if ok:
        passes += 1
    else:
        fails.append(name + ((" — " + detail) if detail else ""))


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        return ""
    return io.open(p, encoding="utf-8").read()


def code_only(text, marker="#"):
    """剥掉行注释。门脚本/core 的注释里会写实现细节，不能当实现判据。"""
    out = []
    for line in text.split("\n"):
        s = line.lstrip()
        if s.startswith(marker):
            continue
        out.append(line)
    return "\n".join(out)


gate = read("app/src/main/assets/gates/vscreen")
core = read("app/src/main/java/app/dsh/mobile/engine/vscreen/VScreenCore.kt")
cap = read("app/src/main/java/app/dsh/mobile/engine/cap/VScreenCap.kt")
bridge = read("app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt")
gates_kt = read("app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
perm_center = read("app/src/main/java/app/dsh/mobile/PermissionCenterActivity.kt")
perm_layout = read("app/src/main/res/layout/activity_permission_center.xml")
catalog = read("app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt")
gate_kt = read("app/src/main/java/app/dsh/mobile/engine/CapabilityGate.kt")

# ============ 1. 门脚本 ============
check("gate script exists", bool(gate))
gcode = code_only(gate)

# 分支形态有两种：`  status)` 与 `  start|"")`（后者是无参默认走 start）。
for sub in ("start", "status", "stop", "shot", "launch", "tap", "swipe", "key", "type", "journal"):
    check(
        f"gate handles {sub}",
        re.search(r"^\s*%s[^\n)]*\)" % sub, gcode, re.M) is not None,
    )

# ★ 终止空行必须在 _dsh_raw_http 里单独 printf —— 写进变量会被 $( ) 剥掉。
check(
    "gate sends terminating blank line separately",
    "printf '%s\\x0d\\x0a\\x0d\\x0a' \"$_REQ\"" in gcode,
    "空行必须由 _dsh_raw_http 补发，否则 $( ) 剥掉尾部换行 → 服务端读超时",
)
check(
    "no request variable ends with a blank line",
    not re.search(r'_REQ=\$\(printf[^\n]*\\x0d\\x0a\\x0d\\x0a[^\n]*\)', gcode),
    "变量里带 \\r\\n\\r\\n 会被命令替换吃掉",
)

# ★ app_process 必须用 env -u 清掉 LD_LIBRARY_PATH。
check(
    "app_process launched with LD_LIBRARY_PATH unset",
    "env -u LD_LIBRARY_PATH" in gcode,
    "否则 app_process 会去引擎 lib 找 libz.so 并 abort",
)
# ★ 后台存活：必须有子 shell 包住，让 sh 自己 re-parent。
check(
    "core detached via subshell + nohup",
    re.search(r"\(\s*env -u LD_LIBRARY_PATH nohup", gcode) is not None,
    "只写 nohup/setsid 不够，su 会话结束会带走整棵树",
)
# 不能靠 ps 抓进程名（app_process 的 comm 是 app_process，不是类名）。
check(
    "gate does not rely on ps comm to find core",
    "ps -A" not in gcode or "VScreenCore" not in re.findall(r"ps[^\n]*", gcode)[0]
    if re.findall(r"ps[^\n]*", gcode)
    else True,
)
# 连不上时 bash 会往 stderr 打 Connection refused，必须吞掉。
check(
    "gate swallows /dev/tcp stderr",
    re.search(r'exec 3<>"/dev/tcp/[^"]*"\s*2>/dev/null', gcode) is not None,
)

# ============ 2. 核心 ============
check("core source exists", bool(core))
ccode = code_only(core, "//")

# ★ 整个连接处理必须被 runCatching 包住 —— 独立线程的未捕获异常会杀进程。
check(
    "core wraps connection handling in runCatching",
    re.search(r"runCatching\s*\{\s*serveOne\(", ccode) is not None,
    "非主线程抛未捕获异常会走 ThreadGroup.uncaughtException 直接杀进程",
)
check(
    "core has serveOne extracted",
    "private fun serveOne(" in ccode,
)
check(
    "core records connection error to journal",
    re.search(r'\.onFailure\s*\{\s*journal\(', ccode) is not None,
)

# ★ launch 必须区分「包名」与「包名/类名」：用 -p 传组件会被 am 当成包名。
check(
    "launch distinguishes package from component",
    'pkg.contains' in ccode and '"-n"' in ccode and '"-p"' in ccode,
    "组件形态必须用 -n，否则报 unable to resolve Intent",
)
# ⚠️ 不能只测 "awaitFirstFrame(" —— 函数**定义**本身就含这个子串，
# 把调用点删掉后断言照样绿（反向验证抓到的假绿）。必须锚定调用点。
check(
    "shot waits for the first frame",
    re.search(r"^\s*awaitFirstFrame\(\s*[0-9_]+\s*\)\s*$", ccode, re.M) is not None,
    "刚 launch 完 ImageReader 还没回调过，直接查 frame 会误报 FRAME_NOT_READY",
)
check(
    "core writes ready.json atomically",
    ".tmp" in ccode and "renameTo" in ccode,
)
check(
    "core chmods ready.json to 0600",
    re.search(r"Os\.chmod\([^)]*0x180|Os\.chmod\([^)]*0600", ccode) is not None,
)
# 只认恰好一个 X-Token（防头走私）
# 同样不能只测函数名 —— 定义本身含该子串。锚定**调用点**。
check(
    "core compares token in constant time",
    re.search(r"&&\s*constantTimeEquals\(\s*supplied\s*,\s*token\s*\)", ccode) is not None,
    "普通 == 会在第一个不同字节处提前返回，留时序侧信道",
)
# ★ start() 必须重新点亮 active。它唯一的另一次 set(true) 在 main 入口，
# 而 release() 会置 false —— 少了这一句，「stop 再 start」之后帧循环立刻退出，
# shot 恒 FRAME_NOT_READY 而所有端点仍报 ok:true（实测踩过）。
check(
    "start re-arms active flag after release",
    re.search(r"active\.set\(true\)", ccode) is not None
    and len(re.findall(r"active\.set\(true\)", ccode)) >= 2,
    "只在 main 里 set(true) 不够：release() 会清掉它，第二次 start 就不再取帧",
)
check(
    "inject failure is journalled",
    re.search(r'journal\("inject:" \+', ccode) is not None,
    "注入异常被静默吞掉时，调用方只看到 via=command 的降级，查不出原因",
)
check(
    "core watchdog adopts ppid==1 instead of dying",
    "adopted" in ccode,
    "被 init 收养后 ppid 变 1 是正常流程，早期版本写反导致秒退",
)

# ============ 3. 接线 ============
check("VScreenCap exists", bool(cap))
check(
    "cap exposes GET and POST routes",
    '"GET /dev/vscreen"' in bridge and '"POST /dev/vscreen"' in bridge,
)
check(
    "cap op whitelist present",
    "OPS = setOf(" in cap and '"shot"' in cap,
    "白名单而非透传：避免拼出任意参数",
)
check(
    "cap validates launch pkg",
    re.search(r'pkg\.matches\(Regex\(', cap) is not None,
)
check(
    "capability VSCREEN registered",
    'const val VSCREEN = "vscreen"' in gate_kt,
)
check(
    "capability self-report includes VSCREEN",
    ".put(VSCREEN, can(ctx, VSCREEN))" in gate_kt,
)
check(
    "VSCREEN gated on ROOT mode",
    re.search(r"VSCREEN ->\s*Privilege\.getMode\(ctx\) == PrivMode\.ROOT", gate_kt) is not None,
    "Shizuku 能建屏但拿不到 TRUSTED flag，起不了非导出组件",
)
# 门脚本三处清单（部署 / chmod / 自检）都要有
check(
    "gate registered in three EngineConfig lists",
    len(re.findall(r'"root", "shz", "vscreen"', gates_kt)) == 3,
    "实得 %d" % len(re.findall(r'"root", "shz", "vscreen"', gates_kt)),
)
check(
    "permission center binds vscreen switch",
    "CapabilityGate.VSCREEN" in perm_center and "swCapVscreen" in perm_center,
)
check(
    "permission center layout has vscreen tile",
    'android:id="@+id/rowCapVscreen"' in perm_layout
    and 'android:id="@+id/swCapVscreen"' in perm_layout
    and 'android:id="@+id/stCapVscreen"' in perm_layout,
)
check(
    "endpoint catalog lists vscreen",
    '"/dev/vscreen"' in catalog and '"vscreen-status"' in catalog,
)

# ============ 4. 文案 ============
locales = ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th", "values-ru"]
for loc in locales:
    s = read("app/src/main/res/%s/strings.xml" % loc)
    ok = all(('name="setting_cap_vscreen%s"' % suf) in s for suf in ("", "_sub", "_need"))
    check("strings in %s" % loc, ok)

# 泰语里不许混中文（我加文案时真踩过一次）
th = read("app/src/main/res/values-th/strings.xml")
m = re.search(r'<string name="setting_cap_vscreen_sub">([^<]*)</string>', th)
check(
    "thai vscreen copy has no CJK",
    m is not None and not re.search(r"[\u4e00-\u9fff]", m.group(1)),
    "泰语串里混进了中文",
)

print(f"passes={passes} fails={len(fails)}")
for f in fails:
    print("  FAIL:", f)
sys.exit(1 if fails else 0)

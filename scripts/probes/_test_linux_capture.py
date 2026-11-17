#!/usr/bin/env python3
"""_test_linux_capture.py —— 「抓 compositor 合成画面」接线的静态守护

这条链路是「容器 GUI 画面进 App」的第二条路（第一条是 framesrv 自己渲染）：
  sway(wlroots) → wlr-screencopy → wlshot → dshframe.bin → LinuxFrameSurface

为什么不能用 weston：weston_capture_v1 有授权模型，只授予 screen-share
拉起的 RDP 客户端，grim 一律 unauthorized（pitfalls #36）。
"""
import io
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
fails = 0


def ck(name, ok, hint=""):
    global fails
    if ok:
        print(f"  \u2713 {name}")
    else:
        fails += 1
        print(f"  \u2717 {name}" + (f" — {hint}" if hint else ""))


ACT = os.path.join(REPO, "app", "src", "main", "java", "app", "dsh", "mobile",
                   "LinuxActivity.kt")
act = io.open(ACT, encoding="utf-8").read()

print("[A] 方法存在且被按钮接到")
ck("A1 定义了 captureCompositor", "captureCompositor" in act)
ck("A2 按钮调用了它",
   re.search(r"\)\s*\{\s*captureCompositor\(\)\s*\}", act) is not None)

print("[B] 用 sway + wlr-screencopy，不是 weston")
ck("B1 用 sway", re.search(r"sway\s+--unsupported-gpu", act) is not None)
ck("B2 明确不用 weston 抓帧",
   re.search(r"/wlsrc/wlshot", act) is not None)
ck("B3 headless 后端 + pixman 渲染器",
   "WLR_BACKENDS=headless" in act and "WLR_RENDERER=pixman" in act)

print("[C] XDG_RUNTIME_DIR 与 socket 发现")
ck("C1 设了 XDG_RUNTIME_DIR",
   re.search(r"XDG_RUNTIME_DIR=/tmp/wl", act) is not None)
ck("C2 从 /tmp/wl 找 wayland-N socket",
   re.search(r"wayland-", act) is not None)

print("[D] 共享文件路径与 LinuxFrameSurface 一致")
ck("D1 用 defaultFrameFile 取路径",
   "LinuxFrameSurface.defaultFrameFile" in act)
# 必须传宿主可见路径：容器里 /data/data/<pkg>/files 由 buildBinds 映射
ck("D2 传给容器的是 /data/data/<pkg>/files 形式",
   re.search(r'"/data/data/\$\{?packageName\}?/files/', act) is not None)

print("[E] 三种失败态分别给可读反馈")
ck("E1 没有 sway 时明确提示", "NO_SWAY" in act)
ck("E2 没有 wlshot 时明确提示", "NO_WLSHOT" in act)
ck("E3 成功时认 WLSHOT_STARTED（loop 模式的完成标记）",
   "WLSHOT_STARTED" in act)
ck("E4 四个文案资源都用了",
   all(f"R.string.{k}" in act for k in
       ["linux_capture_no_sway", "linux_capture_no_wlshot",
        "linux_capture_ok", "linux_capture_failed"]))

print("[F] 五语文案齐全且占位符一致")
RES = os.path.join(REPO, "app", "src", "main", "res")
keys = ["linux_capture_action", "linux_capture_action_sub", "linux_capture_no_sway",
        "linux_capture_no_wlshot", "linux_capture_ok", "linux_capture_failed"]
for d in ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"]:
    p = os.path.join(RES, d, "strings.xml")
    s = io.open(p, encoding="utf-8").read()
    miss = [k for k in keys if f'name="{k}"' not in s]
    ck(f"F {d} 6 条齐全", not miss, f"缺 {miss}")
# failed 那条五语都要带 %1$s，否则格式化会崩
s = io.open(os.path.join(RES, "values", "strings.xml"), encoding="utf-8").read()
m = re.search(r'<string name="linux_capture_failed">(.*?)</string>', s, re.S)
ck("F6 failed 带 %1$s 占位符", m is not None and "%1$s" in m.group(1))

print("[G] 反向断言：不许硬编码 ahb 或 weston capture")
ck("G1 没硬编码 weston-screenshooter", "weston-screenshooter" not in act)
ck("G2 抓帧命令里没出现 weston_capture", "weston_capture" not in act)

print("[H] 持续抓帧模式（--loop）：画面是活镜像而不是快照")
ck("H1 captureCompositor 的 wlshot 调用行真的带 --loop",
   re.search(r"/wlsrc/wlshot\s+\S{0,40}shared\s+\d+\s+--loop", act) is not None,
   "只看全文有没有 --loop 会被 pkill 那行蒙对")
ck("H2 有帧率参数（--loop 后面跟数字）",
   re.search(r"--loop\s+\d+", act) is not None)
# H3/H6 只验「有没有清」，不锁写法。
# 必须先剥注释：代码注释里有 `pkill -f wlshot` 这样的反面教材示范，直接对全文
# 做正则会被它蒙对（实测：把 captureCompositor 里那行 pkill 整条删掉，探针仍
# 报通过，命中内容是注释里的「裸写 pkill -f wlshot」）。
# 写法在 2026-11-14 又变过一次：`[w]lshot` → `^/wlsrc/wlshot`。原因是方括号
# 技巧只在「同一段命令里不含去掉方括号的真实路径」时成立；captureCompositor
# 里既有模式文本又有 `nohup /wlsrc/wlshot …`，正则照样命中，真机 rc=143 自杀。
# 锁写法会让每次加固都要改探针，而探针该守的是「起之前先清」这个行为。
# 自匹配本身由 _test_pkill_selfmatch.py 的 A 段统一守（语义判定，不锁写法）。
def _strip_kt_comments(text):
    """剥掉 // 行注释与 /* */ 块注释，但保留字符串内容（含三引号 raw string）。"""
    out = []
    i, n = 0, len(text)
    in_str = in_raw = False
    while i < n:
        c = text[i]
        if in_raw:
            if text.startswith('"""', i):
                in_raw = False
                out.append('"""')
                i += 3
            else:
                out.append(c)
                i += 1
            continue
        if in_str:
            if c == "\\":
                out.append(text[i:i + 2])
                i += 2
                continue
            if c == '"':
                in_str = False
            out.append(c)
            i += 1
            continue
        if text.startswith('"""', i):
            in_raw = True
            out.append('"""')
            i += 3
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find(chr(10), i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


act_code = _strip_kt_comments(act)


def _wlshot_kill_patterns(text):
    """取出所有 pkill -f 里含 wlshot 的模式，无论写成什么形态。"""
    out = []
    for m in re.finditer(r"pkill\s+-f\s+(?:'([^']*)'|\"([^\"]*)\"|([^\s;]+))", text):
        pat = m.group(1) or m.group(2) or m.group(3) or ""
        if "wlshot" in pat:
            out.append(pat)
    return out


_up_to_stop = act_code.split("private fun stopFrameServer")[0]
ck("H3 起 wlshot 之前先清遗留抓帧进程",
   len(_wlshot_kill_patterns(_up_to_stop)) >= 1,
   "两个 wlshot 抢同一文件会把 seq 搅乱")
ck("H4 用 nohup 后台起（不能阻塞 shell 调用）",
   re.search(r"nohup\s+env\s+XDG_RUNTIME_DIR", act) is not None)
ck("H5 等第一帧真落盘再报成功（不能只说已启动）",
   re.search(r"if \[ -s .{0,24}shared \]", act) is not None,
   "只报启动不验落盘，UI 会说成功但画面空白")
ck("H6 stopFrameServer 同时杀 wlshot",
   len(_wlshot_kill_patterns(act_code.split("private fun stopFrameServer")[-1])) >= 1)
ck("H7 没有 Wayland socket 时单独报错",
   "NO_SOCKET" in act and "linux_capture_no_socket" in act)

print()

print("[I] 多个残留 socket 时必须逐个试连，不能只取第一个")
ck("I1 有逐候选试连循环（for c in $CAND）",
   re.search(r"for c in \$\{?'\$'?\}?CAND", act) is not None
   or re.search(r"for c in .{0,12}CAND", act) is not None,
   "head -1 会选中「socket 在但进程已死」的残留，永远抓不到帧")
ck("I2 试连前不直接用 ls 第一个",
   not re.search(r"DISP=.*\|\s*head -1", act),
   "直接 head -1 是上一版的写法，已证明会选错")
ck("I3 试连失败时不报成功（DISP 为空要走 NO_SOCKET）",
   re.search(r"\[ -z \"\$\{?'\$'?\}?\{?\$?'?\}?DISP\" \].{0,20}NO_SOCKET", act) is not None)
ck("I4 用 ls -t 优先试最新的 socket",
   "ls -t /tmp/wl/" in act,
   "旧的残留 socket 命中率低，先试最新的能少绕一圈")

if fails == 0:
    print("All linux capture checks passed.")
    sys.exit(0)
print(f"{fails} linux capture check(s) FAILED.")
sys.exit(1)

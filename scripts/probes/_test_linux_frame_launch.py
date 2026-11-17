#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Linux 工作台「容器画面 / 触摸回传」启动脚本探针

锁的是 2026-11-14 真机定位到的三个静默失败，它们的共同特征是
「点了按钮什么也没发生、也不报错」，静态看逻辑全都对：

  1. pkill 自杀。startFrameServer 的 X11 分支是一条 sh -c，里面既有
     pkill 的模式文本 [x]11frsrv，又有 nohup /x11frsrv 的裸路径。
     pkill -f 匹配整条 cmdline，「[x]yz 防自杀」那条注释的推理是错的：
     正则 [x]11frsrv 命中的正是同一条命令里 /x11frsrv 那段字面量，
     于是 sh 把自己 SIGTERM 掉（实测 rc=143 Terminated），后面的
     Xvfb / xterm / 帧服务一行都没执行。

  2. Xvfb 写死 -nolock 与 :99。残留的 /tmp/.X11-unix/X99 若属主是
     root，sticky 目录里 app uid 删不掉 → 新 Xvfb 报
     "Cannot establish any listening sockets" 直接退出；-nolock 本身
     也只有 root 能用（非 root 只打一行 Warning）。表现就是
     「以前能跑，重启之后再起不来」。

  3. fire-and-forget。命令一发就报「已启动」，哪怕一帧都没出。
     真实失败（cleaner_exec FAIL / nohup: not found）全被吞掉。

★判据必须先剥注释再去断言。这个仓库的注释里就写着 [x]11frsrv、
  pkill -f、:99、-nolock 这些字面量，直接搜原文会「因为注释还在所以
  永远绿」，改坏了也发现不了。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ACT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/LinuxActivity.kt")

checks = []


def ck(name, cond, detail=""):
    checks.append((name, bool(cond), detail))


def read(p):
    with io.open(p, encoding="utf-8", errors="replace") as f:
        return f.read()


# ---------------------------------------------------------------- 取函数体
def unwrap_shell_escape(text):
    """把 Kotlin 里为了原样输出 $ 而写的 ${'$'} 还原成 $。

    Kotlin 的原始字符串（三引号）不做插值，但 ${'$'} 是**普通字符串**里的
    写法；这两个分支里脚本都写在普通字符串 / trimIndent() 里，所以源码中
    的真实形态是 ${'$'}d，而不是 $d。断言必须在这层还原之后做，否则
    看到的是 Kotlin 的转义壳子，不是真正会传给 sh 的内容。
    """
    return text.replace("${'$'}", "$")


def strip_comments(src):
    """去掉 Kotlin 注释行，只留可执行代码。

    只处理整行注释与行尾 //，不处理字符串里的 //（本项目脚本里的
    /dev/null 之类都不含 //，够用且不会误伤）。
    """
    out = []
    for line in src.split("\n"):
        s = line.strip()
        if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            continue
        i = line.find(" // ")
        if i >= 0:
            line = line[:i]
        out.append(line)
    return "\n".join(out)


def func_body(src, name):
    """抓 private fun <name>(...) = ... 到下一个同缩进的 '}' 或下一个 private fun。

    不用正则整段匹配：脚本里有多层三引号字符串与嵌套括号，正则必然截断。
    按「函数起始行 + 缩进 = 4」找收尾即可。
    """
    lines = src.split("\n")
    start = None
    for i, line in enumerate(lines):
        if re.match(r"\s*private fun %s\b" % re.escape(name), line):
            start = i
            break
    if start is None:
        return ""
    depth = 0
    body = []
    for line in lines[start:]:
        body.append(line)
        depth += line.count("{") - line.count("}")
        if len(body) > 1 and depth <= 0:
            break
    return "\n".join(body)


src = read(ACT)
code = strip_comments(src)

start_fs = func_body(src, "startFrameServer")
stop_fs = func_body(src, "stopFrameServer")
set_in = func_body(src, "setInputEnabled")

ck("E1 startFrameServer/stopFrameServer/setInputEnabled 三个函数都在",
   bool(start_fs) and bool(stop_fs) and bool(set_in),
   "start=%d stop=%d input=%d chars" % (len(start_fs), len(stop_fs), len(set_in)))

# 这三块去注释、还原 Kotlin 转义后再断言：注释里的字面量会把断言喂饱，
# 而源码里的 ${'$'} 只是转义壳子，不是真会传给 sh 的内容。
code_start = strip_comments(unwrap_shell_escape(start_fs))
code_stop = strip_comments(unwrap_shell_escape(stop_fs))
code_in = strip_comments(unwrap_shell_escape(set_in))

# ---------------------------------------------------------------- F. pkill 自杀
# 只看这三块 + captureCompositor，因为只有它们把 pkill 与「裸路径」放同一条命令
cap = strip_comments(unwrap_shell_escape(func_body(src, "captureCompositor")))

bracket_pkill = re.findall(r"pkill -f '?\[[a-zA-Z]\]", code_start + code_stop + code_in + cap)
ck("F1 画面/触摸/抓合成四条路径都不再用 [x]yz 形式的 pkill",
   not bracket_pkill,
   "命中 %r —— 这个写法防不住同条命令里的裸路径，会把自己杀掉" % (bracket_pkill[:3],))

# 行首锚定：^/x11frsrv 这种，匹配不到以运行器路径开头的 sh 自身
anchored = re.findall(r"pkill -f '\^/[A-Za-z0-9_./]+", code_start + code_stop + code_in + cap)
ck("F2 停进程改用行首锚定模式（pkill -f '^/xxx'）",
   len(anchored) >= 6,
   "找到 %d 处：%r" % (len(anchored), anchored[:8]))

ck("F3 Xvfb 也按行首锚定收（'^Xvfb '）",
   "pkill -f '^Xvfb '" in code_start and "pkill -f '^Xvfb '" in code_stop,
   "")

# ---------------------------------------------------------------- G. display 号
ck("G1 不再把 :99 写死在 Xvfb 启动行",
   "Xvfb :99" not in code_start,
   "写死 :99 会被 root 属主的残留 socket 卡死")

ck("G2 display 号是循环挑出来的（99..119）",
   "for d in 99" in code_start and "DISP=$d" in code_start,
   "")

ck("G3 socket 与 lock 文件都不存在才算空闲",
   ".X11-unix/X$d" in code_start and ".X$d-lock" in code_start,
   "只查 socket 不查 lock 会挑到别人刚锁上的号")

ck("G4 挑中的 display 号落盘，供 setInputEnabled 复用",
   "frameDisplayFile" in code_start and "frameDisplayFile" in code_in,
   "两次 runner.shell() 之间只能靠文件传递")

ck("G5 -nolock 已去掉（只有 root 能用）",
   "-nolock" not in code_start,
   "")

# ---------------------------------------------------------------- H. 不静默降级
ck("H1 启动成功判据是脚本打印 DSHFRAME_OK，不是 res.ok",
   'out.contains("DSHFRAME_OK")' in code_start,
   "脚本为了带回日志会自己 exit 0，只看 res.ok 等于永远成功")

ck("H2 等帧循环要求 seq 变化 + 至少一帧像素",
   'NOW" != "$BASE' in code_start and "-ge 3000000" in code_start,
   "只等文件存在会在「写了头还没写像素」时误判")

ck("H3 失败时把 xvfb / 帧服务日志带回界面",
   'head -6 /tmp/xvfb.log' in code_start and 'head -6 /tmp/x11frsrv.log' in code_start,
   "")

ck("H4 缺 Xvfb / x11frsrv 时明确报缺包，而不是等超时",
   "DSHFRAME_FAIL missing-xvfb" in code_start and "DSHFRAME_FAIL missing-x11frsrv" in code_start,
   "")

ck("H5 缺包有独立的用户文案（linux_screen_missing_gui）",
   'contains("DSHFRAME_FAIL missing-")' in code_start
   and "R.string.linux_screen_missing_gui" in code_start,
   "")

ck("H6 inputd 起来后校活，不看到 INPUTD_OK 就报失败",
   'out.contains("INPUTD_OK")' in code_in and "kill -0" in code_in,
   "nohup 起完立刻报成功，进程秒退也看不出来")

ck("H7 inputd 用 display 号文件，不猜 :0/:99",
   "frameDisplayFile" in code_in and "DISPLAY=:99" not in code_in,
   "")

ck("H8 状态文件在 App 私有 files（bind 进容器），不放容器 /tmp",
   "frameStateDir" in code_start and "/tmp/dsh-" not in code_start and "/tmp/dsh-" not in code_stop,
   "容器 /tmp 有时是 tmpfs，跨 runner.shell() 不保证存活")

# ---------------------------------------------------------------- 行数上限
# 这四条路径的脚本已经很长，再翻倍说明又在一层 shell 里堆逻辑。
ck("I1 单函数体不超过 260 行（脚本膨胀是危险的信号）",
   len(start_fs.split("\n")) <= 260,
   "startFrameServer = %d 行" % len(start_fs.split("\n")))

# ---------------------------------------------------------------- 输出
failed = 0
for name, ok, detail in checks:
    if ok:
        print("PASS  " + name)
    else:
        failed += 1
        print("FAIL  " + name + (("  [" + detail + "]") if detail else ""))
print("")
print("%d checks / %d failed" % (len(checks), failed))
sys.exit(1 if failed else 0)

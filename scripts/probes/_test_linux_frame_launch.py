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
ENVM = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt")

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

# ---------------------------------------------------------------- J. Xvfb 的 XKB 输出目录
# 2026-11-16 真机定位的第四个静默失败，与前三条同一症状（点了没反应）：
# Xvfb 把 xkbcomp 的输出写死到 XKB_OUTPUT_DIR=/var/lib/xkb/，而 Alpine
# rootfs 里没有这个目录 → xkbcomp 返回 1 → Xvfb 报
#   (EE) Fatal server error: (EE) Failed to activate virtual core keyboard: 2
# 直接退出，一个 socket 都不建。判据必须锁住「建这个目录」这一步本身，
# 否则以后有人把 mkdir 删掉，探针仍然全绿（前三条断言它都还满足）。
ck("J1 启动前建出 /var/lib/xkb（Xvfb 写死的 XKB_OUTPUT_DIR）",
   "mkdir -p /var/lib/xkb" in code_start,
   "缺这一步 Xvfb 必挂在 Failed to activate virtual core keyboard")

ck("J2 目录不可写时兜底 chmod，并记录实际可写性",
   "chmod 777 /var/lib/xkb" in code_start and "XKBDIR=" in code_start,
   "上一轮换过 uid 会留下不可写目录，静默失败最难查")

ck("J3 失败回显带 xkb 目录状态，不只堆三份日志",
   "xkbdirectory:" in code_start,
   "")

envm = read(ENVM)
code_envm = strip_comments(envm)
ck("J4 部署期也建一次（已 READY 的 rootfs 不会重走运行期）",
   "fun ensureXkbOutputDir" in code_envm
   and 'File(root, "var/lib/xkb")' in code_envm
   and "ensureXkbOutputDir(root)" in code_envm,
   "只在运行期 mkdir 会漏掉「rootfs 已部署」这条路径")

# ---------------------------------------------------------------- K. Xvfb / xterm 的 setuid 兼容层
# 2026-11-21 真机定位的第五个静默失败，与前面四条同一症状（点了没反应）：
# Xvfb 初始化键盘时调 xkbcomp，走 xserver 自己封装的 safer popen（os/utils.c: Popen），
# 子进程头两行是 setgid(getgid()) 与 setuid(getuid())，任一失败就 _exit(127)。
# Android App 域（untrusted_app_27，CapEff=0）内核一律拒绝 setuid/setgid —— 即使
# 传的就是进程自己的 uid —— 于是子进程 127 秒退，父进程 Pclose 拿 127，Xvfb 打
# "Failed to compile keymap" 并当场退出，一个 listening socket 都不建。
#
# 装 shim 后真机复验：xvfb.log 里变成正常的 xkbcomp keysym 警告 + "Errors from
# xkbcomp are not fatal"，/tmp/.X11-unix/X99 socket 建立 -> 假设成立。
#
# 同一轮又暴露出 xterm 用同一套写法：无 shim 时只打一行
#   xterm: setegid(10501): Operation not permitted
# 随即退出，屏幕区空白。所以两个程序都要挂，而且**不再全局 export**。
ck("K1 Xvfb 启动行挂 LD_PRELOAD（只给 Xvfb，不做全局 export）",
   "LD_PRELOAD=$KBSHIM nohup Xvfb" in code_start
   and "export LD_PRELOAD" not in code_start,
   "缺它 Xvfb 必挂在 Failed to activate virtual core keyboard")

ck("K1b xterm 启动行同样挂 shim",
   "LD_PRELOAD=$KBSHIM nohup xterm" in code_start,
   "无 shim 时 xterm 报 setegid(...): Operation not permitted 直接退出，屏幕区空白")

# ★本轮「no-frame」的真因，2026-11-21 真机实测：脚本里把 Kotlin 插值写成了转义形态，
#   于是 shell 里那个变量根本没定义、展开成空串 —— x11frsrv 实际收到 "1280 720 30"
#   （sharepath=1280，而 usage 是 x11frsrv <sharepath> [w] [h] [fps]），于是一直等
#   一个不存在的共享文件，永不产帧；od / stat 也全在查空路径。
# 判据必须在**未还原**的原文里查（unwrap_shell_escape 之后两种写法都长成 $shared，
# 就区分不出来了），所以这一条直接读 start_fs 的原始文本。
ESCAPED_DOLLAR = chr(36) + "{" + chr(39) + chr(36) + chr(39) + "}"
ck("K1c 共享文件路径用 Kotlin 插值，而不是转义成字面量",
   (chr(36) + "{shared}") in start_fs
   and (ESCAPED_DOLLAR + "shared") not in start_fs,
   "转义形态下 shell 变量为空，x11frsrv 会一直等不存在的文件")

ck("K2 shim 路径是 /usr/lib/libdsh-setuid-shim.so，且先判可读再用",
   "/usr/lib/libdsh-setuid-shim.so" in code_start and '[ -r "$KBSHIM" ]' in code_start,
   "不判可读就会出现 LD_PRELOAD 指向不存在的库，动态链接器只打警告")

ck("K3 失败回显带 shim 状态，不只堆三份日志",
   "xkb setuid shim:" in code_start,
   "")

# 判据必须锁**调用点**，不能靠「函数定义里有这些字符串」—— 定义还在但没人调用，
# 真机上照样没有那个库，断言却是绿的（本组第一版就犯了这个错，变异验证抓出来的）。
shims_body = strip_comments(unwrap_shell_escape(func_body(envm, "installLinuxCompatShims")))
ck("K4 部署期真的调用 installSetuidShim（不只是定义了函数）",
   "installSetuidShim(root)" in shims_body,
   "定义在而调用点丢了，真机 rootfs 里永远没有这个库")

ck("K4b shim 真的落到 rootfs 的 usr/lib（写盘语句与文件名都在）",
   'File(root, "usr/lib")' in code_envm and "SETUID_SHIM_FILE" in code_envm,
   "")

# 同理：CMakeLists 里那段注释本身写着 -nostdlib，直接搜全文会让这条永远绿。
# 只看「去注释后的可执行行」。
cm_lines = []
for line in read(os.path.join(ROOT, "app/src/main/cpp/CMakeLists.txt")).split("\n"):
    if line.strip().startswith("#"):
        continue
    cm_lines.append(line)
cm_code = "\n".join(cm_lines)
ck("K5 CMake 有 dsh_setuid_shim 目标",
   "add_library(dsh_setuid_shim SHARED dsh_setuid_shim.c)" in cm_code,
   "没有这个 target，APK 里就不会有 libdsh_setuid_shim.so")

ck("K5b 该目标的 link 选项里有 -nostdlib（否则产物 NEEDED libc）",
   "\"-nostdlib\"" in cm_code,
   "musl 与 glibc 两边都可能解析失败，宁可当场构建失败也别发出坏库")

# OUTPUT_NAME 只写裸名字：AGP 会给 SHARED 库再套一层 lib 前缀与 .so 后缀，写全称
# 会得到 liblibdsh_setuid_shim.so.so —— 大小对、名字没人认得，App 永远 warn。
# 这条上一轮真机踩过：装机后 App 打 warn 找不到库，进 APK 一看条目名多套一层。
ck("K5c CMake 的 OUTPUT_NAME 是裸名（AGP 会自己套 lib 前缀与 .so 后缀）",
   "OUTPUT_NAME \"dsh_setuid_shim\"" in cm_code
   and "OUTPUT_NAME \"libdsh_setuid_shim" not in cm_code,
   "写全称会被 AGP 套成 liblib...so.so，装机后 App 找不到")

# ---------------------------------------------------------------- L. accept -> accept4 兜底（shim 第二阶段）
# 2026-11-21 真机第二轮定位：Xvfb 起来之后死循环打
#   _XSERVTransSocketUNIXAccept: accept() failed
# 一个客户端都接不进来 —— x11frsrv 连不上、dshframe.bin 永不落盘、UI 侧
# DSHFRAME_FAIL no-frame。替身 .so 在 App 域直发裸 syscall 拿到决定性对照：
#   [A] accept(202) = -1 (EPERM)      ← 被 zygote 那层 seccomp 拒
#   [A] accept4(242) = 16             ← 同条件同 fd，成功
# run-as 域 Seccomp 是 0/1 层，accept 不被拦 —— 所以手跑永远复现不出来。
#
# 判据全部在**剥掉注释后的 C 源码**上看：本文件的注释里就写着 202/242 与
# accept4，搜原文会「因为注释在所以永远绿」。
SHIM_C = os.path.join(ROOT, "app/src/main/cpp/dsh_setuid_shim.c")
shim_src = read(SHIM_C)


def strip_c_comments(src):
    """去掉 C 注释（/* */ 与 //），只留可执行代码。

    ⚠️ 必须用状态机，不能用两条 re.sub。
      正则版会把**字符串字面量里的** // 和 /* */ 当成注释剥掉，而且从那里
      一直吃到后文：实测 "char *u = \"http://x/y\"; int a = 1;" 会被剥成
      "char *u = \"http:"，后面的 int a = 1; 整段消失 —— 之后所有断言都在
      残缺文本上跑，等于静默失明（假绿）。
      状态机版跳过双引号与单引号字面量（含反斜杠转义），注释体只删不留。
      与 scripts/probes/_test_dsh_alpine_source_sync.py 的同名函数同一实现。
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != chr(10):
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            out.append(c)
            i += 1
            while i < n and src[i] != '"':
                if src[i] == chr(92):
                    out.append(src[i])
                    i += 1
                if i < n:
                    out.append(src[i])
                    i += 1
            if i < n:
                out.append(src[i])
                i += 1
        elif c == "'":
            out.append(c)
            i += 1
            while i < n and src[i] != "'":
                if src[i] == chr(92):
                    out.append(src[i])
                    i += 1
                if i < n:
                    out.append(src[i])
                    i += 1
            if i < n:
                out.append(src[i])
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


shim_code = strip_c_comments(shim_src)

ck("L1 aarch64 编号表里有 accept=202 与 accept4=242",
   "#  define NR_accept    202" in shim_code
   and "#  define NR_accept4   242" in shim_code,
   "aarch64 通用 ABI 没有 poll/select，accept 是 202、accept4 是 242")

ck("L1b x86_64 编号表里有 accept=43 与 accept4=288",
   "#  define NR_accept    43" in shim_code
   and "#  define NR_accept4   288" in shim_code,
   "x86_64 的 accept 是 43、accept4 是 288（与 aarch64 完全不同）")

# 判据锁**调用顺序**：202 先发、只在失败时才走 242。若把两次调用顺序写反，
# 或者把 accept 直接改成只发 242，这一条要能红。
#
# ⚠️ 必须先把 accept() 的函数体切出来再断言，不能对全文用 find 取首次命中。
#   "raw4(NR_accept4, (long)fd" 与 "return r >= 0 ? (int)r : -1;" 在源码里各有
#   **两份**（accept() 一份、accept4() 一份）。对全文 find 时，把 accept() 里那段
#   242 兜底整个删掉、只留 accept4() 的那一份，首次命中会落到 accept4() 里，
#   下标仍大于 202 → 假绿（实测：这种变异下 39 checks 全 PASS）。
def _c_func_body(code, sig):
    """切出以 sig 开头的函数体（从签名到其后第一个行首 '}'）。找不到返回 ""。"""
    i = code.find(sig)
    if i < 0:
        return ""
    j = code.find("\n}", i)
    return code[i:(j + 2)] if j >= 0 else ""


accept_body = _c_func_body(shim_code, "int accept(int fd, void *addr, unsigned int *alen)")
accept4_body = _c_func_body(shim_code, "int accept4(int fd, void *addr, unsigned int *alen, int flags)")

i_202 = accept_body.find("raw3(NR_accept, (long)fd")
i_242 = accept_body.find("raw4(NR_accept4, (long)fd")
ck("L2 accept() 先发 202、失败才退 242（顺序不能反）",
   len(accept_body) > 0 and i_202 >= 0 and i_242 >= 0 and i_202 < i_242,
   "直接只发 242 会丢掉 accept 的原语义，反过来则等于没兜底")

ck("L2b accept() 先判成功早退，失败才落 242",
   "if (r >= 0) return (int)r;" in accept_body,
   "没有早退就会把成功的 fd 也再 accept 一遍，等于吞掉一条连接")

ck("L2c 两条都失败时按 POSIX 返回 -1，而不是裸 -errno",
   "return r >= 0 ? (int)r : -1;" in accept_body,
   "裸 syscall 的 -errno 直接返回会让调用方把 EPERM 当「有效 fd」用")

ck("L2d accept4() 自己也归一到 -1（它是独立入口，不经过 accept）",
   "raw4(NR_accept4, (long)fd" in accept4_body
   and "return r >= 0 ? (int)r : -1;" in accept4_body,
   "只改 accept 而不改 accept4，直接调 accept4 的调用方仍会拿到裸 -errno")

ck("L3 导出 accept 与 accept4 两个符号（定义形态可查）",
   "int accept(int fd, void *addr, unsigned int *alen)" in shim_code
   and "int accept4(int fd, void *addr, unsigned int *alen, int flags)" in shim_code,
   "LD_PRELOAD 只对导出符号生效，少一个 Xvfb 就还会走被拦的那条")

ck("L4 两个架构都有 raw4（aarch64 x3 寄存器 / x86_64 第 4 参走 r10）",
   shim_code.count("static long raw4(") >= 2
   and 'register long r10 __asm__("r10") = d;' in shim_code,
   "x86_64 的第 4 个参数必须显式绑 r10，走 d 会传错 flags")

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

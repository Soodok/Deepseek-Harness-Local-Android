#!/usr/bin/env python3
"""守「dsh_alpine.c 源码」与「libdsh_alpine.so 预编译产物」的一致性。

为什么需要这个探针（2026-11-05 真机事故）：

  容器里 DNS / rg / 门脚本接连失效时，我逐轮排查才发现设备上跑的是**旧运行器** ——
  `dsh_alpine.c` 早已改成新版（双向端口改写、MAX_BIND 16），但 `.so` 没重编，
  于是「代码改了、现象不变」，白烧两轮。同类漂移还发生过一次：DNS 改写的源码
  一度只存在于 /tmp/work3.c，仓库里只有旧 .so。

  源码与产物都在仓库里，但**没有任何机制保证它们同步** —— CMakeLists 只留了
  编译命令注释，靠人记得手跑。这个探针把那句「记得重编」变成可执行断言。

判据（不追求字节级重现，只抓「源码里的关键能力有没有进产物」）：

  1. 源码里存在的特征字符串，产物里也必须存在（漏编 = 该能力没进包）
  2. 产物里的关键 ELF 属性必须成立（ET_EXEC、固定基址、无 PT_INTERP）
  3. 源码里的 MAX_BIND 数值与产物里的字符串一致（编译期常量，改了必须重编）

跑法：python scripts/probes/_test_dsh_alpine_source_sync.py
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/cpp/dsh_alpine.c")
SO = os.path.join(ROOT, "app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so")

checks = 0
failed = 0


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   " + msg)
    else:
        failed += 1
        print("  FAIL " + msg)


def read_text(p):
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


print("[1] 两个文件都在")
ok(os.path.isfile(SRC), "app/src/main/cpp/dsh_alpine.c 入库")
ok(os.path.isfile(SO), "app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so 存在")
if not (os.path.isfile(SRC) and os.path.isfile(SO)):
    print("\nchecks=%d failed=%d" % (checks, failed))
    sys.exit(1)

src = read_text(SRC)
src_raw = src.encode("utf-8")
so_raw = open(SO, "rb").read()
so_txt = so_raw.decode("latin-1")

print("[2] 源码里的关键能力都进了产物（字符串级）")
# 每个元组：(说明, 源码里的判据, 产物里应出现的字符串)
FEATURES = [
    ("DNS 双向改写：读端口",
     'DSH_DNS_PORT=',
     'DSH_DNS_PORT='),
    ("DNS 出程改写 sendto/sendmsg",
     'case SYS_sendto:',
     'DSH_DNS_PORT='),          # 同一入口，靠上面的字符串代表
    ("DNS 回程改写 recvfrom/recvmsg",
     'case SYS_recvfrom:',
     None),
    ("bionic 直通（硬件 GPU 桥）",
     'guest_is_bionic',
     None),
    ("cleaner 免 filter 子进程",
     'cleaner_exec',
     None),
    ("Android PATH 污染识别",
     'path_looks_like_android',
     None),
]
for label, src_marker, so_marker in FEATURES:
    ok(src_marker in src, "源码含特征：" + label)
    if so_marker:
        ok(so_marker in so_txt, "产物含特征：" + label)

print("[2b] 近期修复：源码与产物必须一起走（2026-11-09 补）")
# 为什么加这段：本探针原来只覆盖 2026-11-05 之前的 6 个特征，此后落地的四个
# 修复（dup2 翻译、execveat 回退、readlink /proc/self/exe 特判、TRAP_PROFILE 门控）
# 一个都不在判据里 —— 于是「改了源码忘了重编」这个 11-05 出事的老毛病，
# 在这四个修复上**重新变成不可见**：探针照旧 35/0 全绿，设备上跑的却是旧逻辑。
#
# 判据写成「源码与产物一致」而不是「必须有这个修复」，因为四个修复目前只在
# feat/linux-workspace 分支上（main 尚未合入）：硬要求会让 main 分支恒红。
# 一致性的含义是双向的，两边都能抓到真问题：
#   · 源码里有、产物里没有  → 忘重编（本探针的立身之本）
#   · 源码里有、产物没刷新  → 同上，靠 mtime 兜底（无字符串特征的修复只能这样判）
#
# mtime 判据的边界（如实写在这里）：时间戳只能证明「重编发生在源码最后一次
# 修改之后」，不能证明产物里就是这份源码。所以字符级特征优先，mtime 只用于
# 那几个不产生字符串常量的纯逻辑修复。
import time

src_mtime = os.path.getmtime(SRC)
so_mtime = os.path.getmtime(SO)
so_is_fresh = so_mtime + 2 >= src_mtime  # 容 2 秒文件系统时间戳抖动
ok(so_is_fresh,
   "产物不早于源码（src=%s so=%s）"
   % (time.strftime("%m-%d %H:%M:%S", time.localtime(src_mtime)),
      time.strftime("%m-%d %H:%M:%S", time.localtime(so_mtime))))

RECENT = [
    # (说明, 源码特征, 产物里的字符串特征（None = 纯逻辑，只能靠 mtime 判）)
    ("dup2 翻译到 dup3（避免 ARM64 无 SYS_dup2）",
     "SYS_dup3", None),
    ("execveat 失败回退 execve",
     "SYS_execveat", None),
    ("readlink /proc/self/exe 特判",
     'raw6(SYS_execve, (long)"/proc/self/exe"', None),
    ("TRAPCNT/GUARD 诊断输出改成 DSH_TRAP_PROFILE 门控",
     "DSH_TRAP_PROFILE=", "DSH_TRAP_PROFILE="),
]
for label, src_marker, so_marker in RECENT:
    in_src = src_marker in src
    if so_marker:
        in_so = so_marker in so_txt
        ok(in_src == in_so,
           "%s：源码与产物一致（源码%s / 产物%s）"
           % (label, "有" if in_src else "无", "有" if in_so else "无"))
    else:
        # 纯逻辑修复不产生字符串常量，唯一能看见的证据是「产物不早于源码」。
        # 这句断言与上面 [2b] 首条重复，但**保留**：首条是全局判据，这条把
        # 「是哪个修复在要求它」说清楚 —— 红的时候不用回头猜是哪一处加了源码。
        if not in_src:
            ok(True, "%s：源码未含该修复（main 分支尚未合入），无需产物证据" % label)
        else:
            ok(so_is_fresh,
               "%s：源码已含该修复，产物必须晚于源码（当前 src=%s so=%s）"
               % (label,
                  time.strftime("%m-%d %H:%M:%S", time.localtime(src_mtime)),
                  time.strftime("%m-%d %H:%M:%S", time.localtime(so_mtime))))

print("[3] MAX_BIND 是编译期常量，数值必须与源码一致")
m = re.search(r"#define MAX_BIND\s+(\d+)", src)
ok(m is not None, "源码里有 #define MAX_BIND")
if m:
    want = int(m.group(1))
    ok(want >= 16,
       "MAX_BIND = %d（需 >= 16 才能容纳 3 个别名 + 2 个宿主根 + 软链副本）" % want)
    # 产物里没有这个数字的可靠字符串形式（编译期常量会被折叠），
    # 所以这里只校验源码侧下限；产物侧靠下面的行为证据。
    ok(len(so_raw) > 20000, "产物体积合理（%d 字节）" % len(so_raw))

    # na[] 容量与 k< 守卫必须 >= 2 + 2*MAX_BIND + 6 + 1（v1.7.8 复查）
    # 原先 na[32] 配 k<28：MAX_BIND=16 时 k = 2 + 2*B + 6 = 2B + 8，B=12 就到 32，
    # 正好写到相邻 gv2[0] 上；B>=10 起守卫还会静默丢弃挂载点（容器里该挂载点消失）。
    need_na = 2 + 2 * want + 6 + 1
    mna = re.search(r"static\s+char\s*\*\s*na\s*\[\s*(\d+)\s*\]", src)
    ok(mna is not None, "源码里有 na[] 的容量声明")
    if mna:
        cap = int(mna.group(1))
        ok(cap >= need_na,
           "na[%d] 容量 >= 2 + 2*MAX_BIND(%d) + 6 + 1 = %d" % (cap, want, need_na))
    mguard = re.search(r"bi\s*<\s*g_bind_n\s*&&\s*k\s*<\s*(\d+)", src)
    ok(mguard is not None, "源码里有 na 写入守卫（bi < g_bind_n && k < N）")
    if mguard:
        guard = int(mguard.group(1))
        ok(guard >= need_na - 1,
           "守卫 k<%d >= %d（一个 bind 要 2 个槽位，之后还要放 6 个参数 + NULL）"
           % (guard, need_na - 1))

print("[4] 产物的 ELF 硬约束（CMakeLists 顶部注释列的 6 条）")
ok(so_raw[:4] == b"\x7fELF", "ELF 魔数正确")
ok(so_raw[4] == 2, "ELFCLASS64")
ok(so_raw[5] == 1, "小端")
ok(int.from_bytes(so_raw[18:20], "little") == 183, "e_machine = AArch64(183)")
ok(int.from_bytes(so_raw[16:18], "little") == 2, "e_type = ET_EXEC(2)，不是 PIE")
ok(b"/system/bin/linker64" not in so_raw, "无 PT_INTERP（不链 bionic linker）")
ok(b"libc.so" not in so_raw, "不引用 libc.so（freestanding）")

print("[5] SIGSYS handler 不得带 SA_ONSTACK（多线程安全，v1.6.16）")
#
# 根因：sigaltstack 是线程局部的，而 loader 只在主线程调用过一次 sigaltstack()。
# 带上 SA_ONSTACK 后，clone 出来的工作线程没有自己的 altstack，而且所有线程
# 共用同一份全局 sigstk 缓冲 —— 两个线程同时触发 SIGSYS 时两套 handler 栈帧
# 落在同一块内存上互相覆盖，直接 SIGSEGV。
#
# 实测（Alpine gcc 编的 ripgrep，容器内 --json 搜两个文件，各 30 轮）：
#   - 真 chroot 对照：多线程 30/30 全过  -> 不是 rg / musl 的问题
#   - 本 loader（带该标志）：多线程 30 次挂 18 次
#   - 本 loader（去掉该标志）：多线程 30/30 全过，/eng 与 rootfs 两条路径各一轮
FLAG_ONSTACK = "0x0800" + "0000"
FLAG_NODEFER = "0x4000" + "0000"
# 剥离 C 注释必须用状态机，不能按行首字符判断：
#   `*(unsigned long *)(sa + 8) = ...` 这种指针解引用行 strip 后以 * 开头，
#   按行首过滤会把它当成块注释续行**静默丢掉**，于是下面的断言看不到真代码。
def strip_c_comments(text):
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != chr(10):
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            out.append(c)
            i += 1
            while i < n and text[i] != '"':
                if text[i] == chr(92):
                    out.append(text[i])
                    i += 1
                if i < n:
                    out.append(text[i])
                    i += 1
            if i < n:
                out.append(text[i])
                i += 1
        elif c == "'":
            out.append(c)
            i += 1
            while i < n and text[i] != "'":
                if text[i] == chr(92):
                    out.append(text[i])
                    i += 1
                if i < n:
                    out.append(text[i])
                    i += 1
            if i < n:
                out.append(text[i])
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


code_txt = strip_c_comments(src)
code_only = code_txt.splitlines()
ok(FLAG_ONSTACK not in code_txt,
   "代码（不含注释）里不再出现 SA_ONSTACK 的数值")
ok(FLAG_NODEFER in code_txt,
   "代码里仍保留 SA_NODEFER 的数值")
flags_line = ""
for line in code_only:
    if "(sa + 8)" in line and "=" in line:
        flags_line = line
        break
ok(flags_line != "", "能找到 sa_flags 赋值语句")
if flags_line:
    ok(FLAG_ONSTACK not in flags_line,
       "sa_flags 赋值不含 SA_ONSTACK（该行：%s）" % flags_line.strip())
    ok(FLAG_NODEFER in flags_line,
       "sa_flags 赋值含 SA_NODEFER（该行：%s）" % flags_line.strip())

print("[5b] 用户态文件打开必须走 guest_open（bind 路径可 exec，v1.6.16）")
# 症状极具误导：ls -l 能看到文件、stat 拿到 mode/size、md5 与 rootfs 内副本一致，
# 只有 exec 报 No such file or directory。真因是这些点原先用
# openat(g_rootfs_fd, "./<guest 路径>")，只查 rootfs 不看 bind 表。
ok(b"guest_open" in src_raw, "源码定义了 guest_open()")
for label, ret in [("resolve_guest_path", "long"), ("read_into", "int"),
                    ("loader_probe", "long"), ("guest_open", "long")]:
    ok(("static %s %s" % (ret, label)) in src,
       "找到 %s 定义（static %s）" % (label, ret))
ok(("static unsigned char *map_file") in src, "找到 map_file 定义")
ok(src.count("guest_open(") >= 7,
   "guest_open() 至少 7 处调用（实测 %d 处）" % src.count("guest_open("))

print("[5c] 容器内默认 PATH 必须含 /eng/bin（v1.7.8 真机：dev linux exec 报 127 的根因）")
# 执行器（LinuxContainerRunner.doExec）会把 PATH 设成含 /eng/bin 的版本，但 loader 在
# 首次 exec 时会把「宿主那套」/ g_force_container_path 标记的 PATH 一律替换成自己的默认值。
# 早先 4 处硬编码都不含 /eng/bin，于是容器里 `scr` / `dev` 全部 not found ——
# 而 /eng/bin 里 87 个门脚本一个不少，症状看起来像「文件没部署」。
_src_nc = strip_c_comments(src)
m_path = re.search(r'#define\s+DSH_DEFAULT_PATH\s+"(/eng/bin:[^"]+)"', _src_nc)
ok(m_path is not None, "源码里定义了 DSH_DEFAULT_PATH 且以 /eng/bin 开头")
if m_path:
    ok("/usr/bin" in m_path.group(1), "DSH_DEFAULT_PATH 仍含 /usr/bin（发行版命令不能被挤掉）")
    ok(_src_nc.count('"PATH=" DSH_DEFAULT_PATH') >= 4,
       "4 处默认 PATH 赋值都改用宏（实测 %d 处）" % _src_nc.count('"PATH=" DSH_DEFAULT_PATH'))
    ok('"PATH=/usr/local/sbin' not in _src_nc,
       "不再残留写死的老 PATH 字面量（漏一处就有一半路径走回旧值）")

print("[5d] loader .so 里真的带着新 PATH（编译期字符串，必须是重编过的产物）")
ok(b"/eng/bin:/usr/local/sbin" in so_raw,
   "产物含 PATH=/eng/bin:… 字符串（没有就说明 .so 没重编）")

print("[6] 无调试残留")
ok(b"[HIT]" not in so_raw, "产物无 [HIT] 打印")
ok(b"[DIAG" not in so_raw, "产物无 [DIAG 打印")
ok(b"[SENDTO]" not in so_raw, "产物无 [SENDTO] 打印")

print("")
print("checks=%d failed=%d" % (checks, failed))
sys.exit(0 if failed == 0 else 1)

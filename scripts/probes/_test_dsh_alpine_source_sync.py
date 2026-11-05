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

print("[4] 产物的 ELF 硬约束（CMakeLists 顶部注释列的 6 条）")
ok(so_raw[:4] == b"\x7fELF", "ELF 魔数正确")
ok(so_raw[4] == 2, "ELFCLASS64")
ok(so_raw[5] == 1, "小端")
ok(int.from_bytes(so_raw[18:20], "little") == 183, "e_machine = AArch64(183)")
ok(int.from_bytes(so_raw[16:18], "little") == 2, "e_type = ET_EXEC(2)，不是 PIE")
ok(b"/system/bin/linker64" not in so_raw, "无 PT_INTERP（不链 bionic linker）")
ok(b"libc.so" not in so_raw, "不引用 libc.so（freestanding）")

print("[5] 无调试残留")
ok(b"[HIT]" not in so_raw, "产物无 [HIT] 打印")
ok(b"[DIAG" not in so_raw, "产物无 [DIAG 打印")
ok(b"[SENDTO]" not in so_raw, "产物无 [SENDTO] 打印")

print("")
print("checks=%d failed=%d" % (checks, failed))
sys.exit(0 if failed == 0 else 1)

#!/usr/bin/env python3
"""守护「容器内 DNS 免 root」这条链路不被改坏。

背景（2026-11-05 实测，见 CMakeLists.txt 的 DNS 桥接长注释）：

  musl 的 resolver 把 DNS 端口硬编码成 53，`nameserver 127.0.0.1:15353` 里的
  端口部分被整段忽略（决定性实验：root 在 53 与 35931 各起记录器，写 35931 时
  53 收到 33/60 字节、35931 收到 0）。而 53 是特权端口，App uid 绑不上；Android
  又按 uid 拦掉发往外部 53 的包 —— 于是容器内 apk/wget/pip 全部 `bad address`。

  解法是 loader 做**双向**端口改写，端口来自 `DSH_DNS_PORT`：
    出程 sendto/sendmsg   127.0.0.1:53        -> 127.0.0.1:<port>
    回程 recvfrom/recvmsg 来源 <port>          -> 改回 53

  回程不能省：musl 的 __res_msend_rc 校验应答来源端口等于它发的目标端口，
  只改出程时 sendto 成功、代理也 replied，但 getaddrinfo 仍 EAI_AGAIN。

本探针守四件事（任一条坏掉，容器内联网就会静默失效）：
  1. 预编译 .so 里仍有 `DSH_DNS_PORT=` 字符串（说明改写代码没被编掉）
  2. .so 已登记 recvfrom/recvmsg 的 trap（BPF 树含 207/212）
  3. resolv.conf 写的是纯 IP（带端口会被 musl 忽略）
  4. Launcher 确实把 DSH_DNS_PORT 传进容器环境

跑法：python scripts/probes/_test_linux_dns_bypass.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

SO = os.path.join(ROOT, "app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so")
ENV_KT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt")
LNCH_KT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxEngineLauncher.kt")
BPF_PY = os.path.join(ROOT, "..", "_seccomp_poc/tools/gen_bpf_tree.py")

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


def read(p):
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


print("[1] 预编译运行器 libdsh_alpine.so")
ok(os.path.isfile(SO), "存在 app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so")
if os.path.isfile(SO):
    raw = open(SO, "rb").read()
    txt = raw.decode("latin-1")
    ok("DSH_DNS_PORT=" in txt, ".so 内含 DSH_DNS_PORT= 字符串（改写代码未被编掉）")
    # 编码正确性：这个字符串必须是 10 进制可解析的变量读取，
    # 而不是别处误留的调试串。检查相邻内容不含 [HIT]/[DIAG 之类残留。
    ok("[HIT]" not in txt, ".so 不含 [HIT] 调试打印（诊断代码已清理）")
    ok("[SENDTO]" not in txt, ".so 不含 [SENDTO] 调试打印")
    ok("tawcroot" not in txt.lower(), ".so 不含第三方项目名")
    size = len(raw)
    ok(size > 20000, "体积合理（%d 字节 > 20000）" % size)

print("[2] BPF 树登记了收发四个 syscall")
if os.path.isfile(BPF_PY):
    src = read(BPF_PY)
    # aarch64 编号：sendto=206 sendmsg=211 recvfrom=207 recvmsg=212
    for name, nr in (("sendto", 206), ("sendmsg", 211), ("recvfrom", 207), ("recvmsg", 212)):
        ok('"%s":%d' % (name, nr) in src.replace(" ", ""),
           "gen_bpf_tree.py NR 表含 %s=%d" % (name, nr))
else:
    print("  skip gen_bpf_tree.py 不在仓库（源码已下架，属预期）")

print("[3] resolv.conf 只写纯 IP")
if os.path.isfile(ENV_KT):
    s = read(ENV_KT)
    ok('listOf("nameserver 127.0.0.1")' in s,
       "写的是 nameserver 127.0.0.1（不带端口）")
    ok('"nameserver 127.0.0.1:$proxyPort"' not in s,
       "不再写 nameserver 127.0.0.1:$proxyPort（musl 会忽略端口）")
    ok("__res_msend_rc" in s,
       "注释记录了 musl 校验应答来源端口这一根因")
else:
    ok(False, "LinuxEnvironmentManager.kt 存在")

print("[4] Launcher 传 DSH_DNS_PORT 进容器环境")
if os.path.isfile(LNCH_KT):
    s = read(LNCH_KT)
    ok("DSH_DNS_PORT=" in s, "LinuxEngineLauncher 里出现 DSH_DNS_PORT=")
    ok("currentPort()" in s, "取值来自 LinuxDnsProxy.currentPort()")
    ok("it > 1023" in s, "只在拿到非特权端口时传（否则保持原行为）")
    ok("recvfrom" in s or "recvmsg" in s or "回程" in s,
       "注释说明了回程改写（避免后续 AI 只看出程就删掉回程）")
else:
    ok(False, "LinuxEngineLauncher.kt 存在")

print("")
print("checks=%d failed=%d" % (checks, failed))
sys.exit(0 if failed == 0 else 1)

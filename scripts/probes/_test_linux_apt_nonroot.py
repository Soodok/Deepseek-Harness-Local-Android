#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Linux 装包（Ubuntu apt / glibc）非 root 适配探针。

背景（2026-11-13 真机定位）：容器按 App uid 运行，dpkg 不带 --force-not-root
会硬性报 requested operation requires superuser privilege（rc=2）。
本探针只看源码契约，不跑容器。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")

checks = []
failed = 0


def check(name, cond, detail=""):
    global failed
    checks.append((name, bool(cond), detail))
    if not cond:
        failed += 1


def read(rel):
    with io.open(os.path.join(ENGINE, rel), encoding="utf-8") as f:
        return f.read()


env_mgr = read("LinuxEnvironmentManager.kt")
catalog = read("LinuxRootfsCatalog.kt")
pkg_mgr = read("LinuxPackageManager.kt")
dns = read("LinuxDnsProxy.kt")
runner = read("LinuxContainerRunner.kt")
selinux = read("SelinuxContext.kt")
act = io.open(os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "LinuxActivity.kt"), encoding="utf-8").read()

BS = chr(92)  # 反斜杠，避免转义歧义

# 1) dpkg -i 必须带 --force-not-root
E1BODY = None
E1BODY = ""
for _ln in env_mgr.splitlines():
    if "dpkg --force-not-root" in _ln or "set -- /var/cache" in _ln:
        E1BODY += _ln + ""
check("E1 dpkg -i 命令行存在", chr(100) in E1BODY)
check("E2 dpkg -i 带 --force-not-root", "--force-not-root" in E1BODY)
check("E2b 空 cache 时跳过 dpkg（[ -e 守卫）", "[ -e" in E1BODY)

# 2) postInstallArgv 的 configure 也带
m = re.search(r"PackageManager\.APT\s*->\s*listOf\(([\s\S]*?)\)\n?\s*\}", catalog)
check("E3 APT 的 postInstallArgv 存在", m is not None)
if m:
    check("E4 configure 带 --force-not-root", "--force-not-root" in m.group(1), m.group(1))

# 3) apt.conf 注入 DPkg::Options
m = re.search(r"val APT_CONF([^=]*)=([\s\S]*?)\)\n", catalog)
check("E5 APT_CONF 存在", m is not None)
if m:
    check("E6 APT_CONF 含 DPkg::Options", "DPkg::Options" in m.group(2))
    check("E7 APT_CONF 含 force-not-root", "--force-not-root" in m.group(2))

# 4) apt remove 也非 root
check("E8 apt remove 传 dpkg::options::=--force-not-root",
      "dpkg::options::=--force-not-root" in pkg_mgr)

# 5) quirk 脚本清 partial 目录本身
quirks = re.search(r"fun glibcQuirksScript[\s\S]*?appendLine\(\"exit 0\"\)", catalog)
check("E9 glibcQuirksScript 存在", quirks is not None)
if quirks:
    body = quirks.group(0)
    check("E10 quirk 删除 partial 目录本身", "rm -rf /var/lib/apt/lists/partial" in body)
    check("E11 quirk 重建 partial 目录", "mkdir -p /var/lib/apt/lists/partial" in body)

# 5b) configure 前必须先跑 quirks（时序缺陷：fresh rootfs 无 ldconfig.real）
m = re.search(r"PackageManager\.APT\s*->\s*listOf\(([\s\S]*?)\)\n?\s*\}", catalog)
check("E13 postInstallArgv APT 分支存在", m is not None)
if m:
    block = m.group(1)
    qi = block.find("/etc/dsh-glibc-quirks.sh")
    di = block.find("--configure")
    check("E14 quirks 在 configure 之前跑", qi != -1 and di != -1 and qi < di)

# 6) apt 全流程关掉 _apt sandbox（否则 gpgv 读不到文件 -> invalid signature）
check("S1 APT_SANDBOX_OFF 常量存在", "APT::Sandbox::User=" in env_mgr)
check("S2 update 步骤带 sandbox 关断", env_mgr.count("APT_SANDBOX_OFF") >= 3)
check("S3 装包前重置 partial 目录", "reset apt lists/partial" in env_mgr)
check("S3b partial 重建走 Java 自建 + 特权兜底两趟",
      "fun resetAptPartial(" in env_mgr and "dir.deleteRecursively()" in env_mgr and
      "chmod 0755 " in env_mgr)
check("S3c partial 不可写时中止安装（不带着坏目录往下跑）",
      "still not writable; aborting install" in env_mgr)
check("S9 装包前自愈「dpkg 上次被打断」",
      "dpkg --configure -a (recover interrupted install)" in env_mgr and
      '"var/lib/dpkg/updates"' in env_mgr)
check("S9b 自愈只在上游有 journal 条目时才跑",
      "updates.listFiles()?.any { it.isFile }" in env_mgr)
check("S4 remove 也关 sandbox", '"APT::Sandbox::User="' in pkg_mgr)

# 6b) root/CHROOT 身份写过的树：交叉身份写入留下 root:root 0700 + 裸 s0，
#     使 apt 的 chmod/open 全部 EPERM，只剩 GPG 签名失败的 W: 警告
#     （真机复现 2026-11-13：lists 一不可写就报
#      "Splitting up ..._InRelease into data and signature failed"）。
#     修法是装包前把三棵子树交还给 App uid 并重打 MCS 分类。
check("S5 装包前交还 apt lists 属主",
      re.search(r'"var/lib/apt/lists"[^\n]*', env_mgr) is not None and
      env_mgr.index('"var/lib/apt/lists"') < env_mgr.index("reset apt lists/partial"))
check("S6 三棵子树都交还（lists / keyring / dpkg）",
      all(s in env_mgr for s in ('"var/lib/apt/lists"', '"etc/apt/trusted.gpg.d"', '"var/lib/dpkg"')))
check("S7 交还走 normalizeOwnership（chown+chcon 特权通道）",
      "Privilege.normalizeOwnership(ctx, d)" in env_mgr)
check("S8 交还失败不中断装包（只记日志）",
      "pre-apt normalize failed for " in env_mgr)

# ---- DNS 代理可用性 ----
check("D1 有 socketsAlive 健康标记", "socketsAlive" in dns)
check("D2 currentPort 同时判 running 与 socketsAlive",
      re.search(r"fun currentPort\(\)[^\n]*running\.get\(\)\s*&&\s*socketsAlive\.get\(\)", dns) is not None)
start_block = re.search(r"fun start\([^\n]*\): Int \{([\s\S]*?)\n    /\*\*", dns)
check("D3 start 仅在 socket 和监听循环健康时复用端口，否则停止并重绑",
      start_block is not None and re.search(
          r"if \(running\.get\(\) && port > 0\) \{\s*"
          r"if \(socketsAlive\.get\(\) && loopAlive\.get\(\)\) return port"
          r"[\s\S]*?\bstop\(\)", start_block.group(1)) is not None)
for name, loop, operation in (("D4", "acceptLoop", "ss.accept()"),
                              ("D5", "udpLoop", "us.receive(pkt)")):
    block = re.search(r"private fun %s\([^\n]*\) \{([\s\S]*?)\n    \}" % loop, dns)
    check(name + " 监听异常时仅当前代标记 socket 死亡",
          block is not None and re.search(
              re.escape(operation) + r"\s*\} catch \(e: Throwable\) \{"
              r"[\s\S]*?if \(running\.get\(\)\) \{[\s\S]*?"
              r"if \(generation\.get\(\) == myGen\) socketsAlive\.set\(false\)",
              block.group(1)) is not None)
check("D6 容器执行前端口取不到会重绑",
      "LinuxDnsProxy.start(ctx)" in runner and "DSH_DNS_PORT" in runner)


# 7) glibc 发行版的 node 安装器（此前只有元数据、无人消费 -> Ubuntu 永远缺 node）
check("N1 installNodeTarball 已实现", re.search(r"fun installNodeTarball\(option: LinuxRootfsCatalog\.Option\): Boolean \{", env_mgr) is not None)
check("N2 bootstrap 里会装 node", re.search(r"installNodeTarball\(option\)\s*\}", env_mgr) is not None)
check("N3 tarball 装了 sha256 校验", re.search(r"expectedSha256 = tb\.sha256,", env_mgr) is not None)
check("N4 UI 补齐依赖走 tarball", re.search(r"installNodeTarball\(opt\)", act) is not None and "okNode" in act)

# 8) dpkg 备份文件：configure 前必须清掉（标签漂移时 dpkg 报 backup Permission denied）
# B1 原本断言「dpkg configure 之前先删 status-old」，那是 2026-11-13 中午基于
# 「备份文件标签漂移」错误归因加的。真因是 Android app 域**整体没有 link 权限**
# （见 dsh_alpine.c 里 ldr_copy_link 的注释），删文件不可能修好 —— 保留这条
# 断言会把错误结论焊进回归网。改成断言真正生效的那一层：loader 里有 link 的
# 复制兜底，并且被两条 link 路径（SYS_link / SYS_linkat）都接上了。
loader_c = os.path.join(ROOT, "app/src/main/cpp/dsh_alpine.c")
loader_txt = open(loader_c, encoding="utf-8", errors="replace").read() if os.path.isfile(loader_c) else ""
check("B1 loader 有 link 复制兜底（ldr_copy_link）", re.search(r"static long ldr_copy_link\(", loader_txt) is not None)
check("B1b SYS_link 分支接触发判定", re.search(r"if \(lr < 0 && ldr_link_denied\(lr\)\) lr = ldr_copy_link", loader_txt) is not None)
check("B1c SYS_linkat 分支接触发判定", re.search(r"ldr_copy_link\(ofd, use_op, nfd, use_np\)", loader_txt) is not None)
check("B1d 判据覆盖 EACCES(13) 与 EPERM(1)", re.search(r"rc == -1 \|\| rc == -13", loader_txt) is not None)
check("B2 哨兵覆盖 status-old", re.search(r"File\(rootfs, \"var/lib/dpkg/status-old\"\)", selinux) is not None)
check("B3 哨兵覆盖 diversions-old", re.search(r"File\(rootfs, \"var/lib/dpkg/diversions-old\"\)", selinux) is not None)

print()
for name, ok, detail in checks:
    print(("  ok   " if ok else "  FAIL ") + name + ((" -> " + detail) if not ok and detail else ""))
print()
print("%d checks, %d failed" % (len(checks), failed))
sys.exit(1 if failed else 0)

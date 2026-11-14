#!/usr/bin/env python3
"""SELinux 符号链接漂移自愈（2026-11-13 真机事故回归）。

事故链：rootfs 里有 26 条掉 MCS 分类的符号链接（裸 sp:object_r:app_data_file:s0）。
dpkg 解包必须先 dereference 既有链接才能覆盖，于是报
    dpkg: error processing archive .../curl_*.deb (--install):
     failed to stat (dereference) existing symlink
     '/usr/share/doc/curl/changelog.Debian.gz': Permission denied
→ curl 装不上 → bootstrap 标记停在 attempted → 页面永远「缺依赖」。

旧自愈只查 SelinuxContext.sentinelsFor() 的哨兵，而哨兵全是目录与普通文件，
链接本体坏掉时一条都碰不到，healTree（带 -h，本来能修）永远等不到触发。

本探针守四件事：
  L1  SelinuxContext 有 firstDriftedLink()，用 'find -type l -exec ls -Zd'
      （ls -Zd 对路径列表会跟随链接读目标，看不出链接本体）
  L2  healLinksIfDrifted() 存在，且内部改用 firstDriftedLink 作判据
  L3  LinuxEngineLauncher 冷启自检调用 firstDriftedLink
  L4  LinuxEnvironmentManager 在 apt 装包 / bootstrap 前调用 healLinksIfDrifted
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/app/dsh/mobile"

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok, detail))


def read(p):
    return p.read_text(encoding="utf-8")


selinux = read(SRC / "engine/SelinuxContext.kt")
launcher = read(SRC / "engine/LinuxEngineLauncher.kt")
envmgr = read(SRC / "engine/LinuxEnvironmentManager.kt")

# L1: 检测函数存在，且用的是 find -type l + ls -Zd
check("L1a firstDriftedLink declared",
      re.search(r"fun firstDriftedLink\(ctx: Context, root: File\): String\?", selinux) is not None)
check("L1b scans only symlinks (-type l)",
      "-type l -exec /system/bin/ls -Zd {} +" in selinux)
check("L1c does NOT use plain 'ls -Zd <paths>' for link check",
      "/system/bin/ls -Zd $quoted" not in selinux.split("fun firstDriftedLink")[1].split("fun healLinksIfDrifted")[0])
check("L1d returns null when privileged channel unavailable (no false alarm)",
      "val out = readContexts(ctx, cmd) ?: return null" in selinux)

# L2: 装包前兜底
check("L2a healLinksIfDrifted declared",
      "fun healLinksIfDrifted(ctx: Context, root: File): Boolean" in selinux)
check("L2b healLinksIfDrifted keys off firstDriftedLink",
      "firstDriftedLink(ctx, root)" in selinux.split("fun healLinksIfDrifted")[1][:600])
check("L2c healLinksIfDrifted delegates to healTree (has -h)",
      "return healTree(ctx, root)" in selinux.split("fun healLinksIfDrifted")[1][:600])
check("L2d healTree still uses chcon -R -h",
      "\"/system/bin/chcon -R -h \"" in selinux)

# L3: 冷启自检
check("L3a launcher calls firstDriftedLink",
      "SelinuxContext.firstDriftedLink(ctx, rootfs)" in launcher)
check("L3b launcher heals on symlink drift",
      "rootfs symlink context drifted" in launcher)
check("L3c launcher keeps sentinel check as well",
      "SelinuxContext.firstDrifted(ctx, sentinels)" in launcher)

# L4: 装包入口
check("L4a env mgr calls healLinksIfDrifted",
      "SelinuxContext.healLinksIfDrifted(ctx," in envmgr)
# 定位器不要用「函数开头 + 固定字符窗口」：apt 路径每加一段前置检查，
# `apt-get update` 的偏移就往后挪（2026-11-14 实测 2203 -> 5588），
# 4000 字符窗口直接把它挡在外面，探针抛 ValueError 而不是报红。
# 改成「取整个函数体（到下一个私有方法为止）」再比位置 —— 与实现长度解耦。
apt_body = envmgr.split("private fun installPackagesAptLocked", 1)[1]
_next_fn = apt_body.find("\n    private fun ")
if _next_fn > 0:
    apt_body = apt_body[:_next_fn]
check("L4b apt path heals before ① apt-get update",
      "healLinksIfDrifted" in apt_body
      and "apt-get update" in apt_body
      and apt_body.index("healLinksIfDrifted") < apt_body.index("apt-get update"))
check("L4c bootstrap heals before writing marker",
      envmgr.index("healLinksIfDrifted") < envmgr.index("val mark = File(root, BOOTSTRAP_MARKER"))
check("L4d bootstrap heal gated to APT (apk has no status-old dereference problem)",
      "option.pkgManager == LinuxRootfsCatalog.PackageManager.APT" in envmgr.split("bootstrap symlink heal")[0][-600:])

# L5: 删除镜像时的特权兜底（同一根因的另一面：root 属主 + 裸 s0 → App 删不掉）
activity = read(SRC / "LinuxActivity.kt")
# 2026-11-13 改法把兜底从 runSu 换成 runPrivileged（前者在 Shizuku 可用、
# su 不可用的设备上根本没有通道），中间还插了一趟「交还属主再删」。
# 断言因此改成三趟的次序：先删 -> normalizeOwnership -> runPrivileged。
check("L5a deleteImage retries via privileged rm when entries remain",
      "Privilege.normalizeOwnership(" in activity
      and "Privilege.runPrivileged(" in activity
      and activity.index("Privilege.normalizeOwnership(") < activity.index("Privilege.runPrivileged(")
      and "privileged rm recovered" in activity)
check("L5b success judged by directory gone, not by command output",
      "if (!dir.exists())" in activity)

# L6: 装包结果必须核对（假绿防线）—— 2026-11-13 用户第二次报「下载完仍缺依赖」
# 时确认：dpkg -i 的 rc 明文不作判据 + configure 对未解包包返回 0，两者叠加
# 会让「28 个 deb 一个都没解出来」被判成装成功，进而写下永久成功标记。
env_apt = envmgr[envmgr.index("private fun installPackagesAptLocked"):]
check("L6a apt install verifies against dpkg database before reporting success",
      "verifyAptInstalled(packages)" in env_apt
      and "configureOk && missing.isEmpty()" in env_apt)
check("L6b verification uses dpkg Status field, not exit code",
      "private fun verifyAptInstalled" in envmgr
      and "dpkg-query" in envmgr.split("private fun verifyAptInstalled")[1][:600]
      and 'endsWith(" installed")' in envmgr.split("private fun verifyAptInstalled")[1][:1400])
check("L6c bootstrap marker requires probe files actually present",
      "val filesOk = BOOTSTRAP_PROBE_FILES.all" in envmgr
      and "if (ok && filesOk)" in envmgr)
check("L6d UI marker write also gated on probe files",
      "if (!env.flavorPackagesReady(opt) &&" in activity
      and "bootstrapFilesPresent(opt) &&" in activity)
check("L6e apt transcript persisted for post-mortem",
      "apt-last.log" in envmgr)

failed = [c for c in checks if not c[1]]
for name, ok, detail in checks:
    print(("  ok   " if ok else "  FAIL ") + name + (("  << " + detail) if detail and not ok else ""))
print("\n%d checks / %d failed" % (len(checks), len(failed)))
sys.exit(1 if failed else 0)

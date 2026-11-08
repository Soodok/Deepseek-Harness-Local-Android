#!/usr/bin/env python3
"""三个新设备能力（location / packages / camera）的契约探针。

为什么需要：
    这三个端点补的是「AI 感知面」上的真实缺口 —— 之前 AI 问「我在哪」
    「装了什么」「看看这是什么」全都没有端点可用。但新端点最容易出的问题
    不是「不能用」，而是**假绿**：
      · 位置返回旧缓存却不说 stale，AI 当成当前位置做决策；
      · install 返回 ok=true，实际用户点了取消；
      · 相机没关，用户手动开相机全黑且不报错。
    本探针把这三类「静默错误」的防线固定下来。

覆盖：
    A. 三个 CapabilityGate 常量存在且已注册进 snapshot
    B. AgentBridge 三个端点已接线（GET location / GET+POST packages / POST camera）
    C. LocationCap 的诚实性：stale 标记、age_ms、精度降级、provider 列表
    D. PackageCap 的诚实性：install 返回 202 而非 200、system 应用拒卸、
       包可见性用 visible_total 暴露
    E. CameraCap 的诚实性：finally 里关三样（session/device/reader）、
       JPEG 魔数校验、选不到 facing 时不静默退回
    F. 全部端点都先过 CapabilityGate（不许有裸端点）

反向验证：把 install 的 202 改 200、把 finally 的 device.close 删掉、
把 stale 字段删掉，本探针必须变红。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")

checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    """去掉 // 与 /* */ 注释，只在真实代码里断言。

    ★这一步是必须的：本项目的注释里大量出现端点名与方法名，
    不剥掉的话「注释里写了」会当成「代码里实现了」——典型的假绿。
    """
    out = []
    i = 0
    n = len(src)
    in_block = False
    while i < n:
        if in_block:
            j = src.find("*/", i)
            if j < 0:
                break
            i = j + 2
            in_block = False
            continue
        if src.startswith("/*", i):
            in_block = True
            i += 2
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            if j < 0:
                break
            i = j
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


def main():
    gate_p = os.path.join(JAVA, "engine", "CapabilityGate.kt")
    bridge_p = os.path.join(JAVA, "engine", "AgentBridge.kt")
    loc_p = os.path.join(JAVA, "engine", "cap", "LocationCap.kt")
    pkg_p = os.path.join(JAVA, "engine", "cap", "PackageCap.kt")
    cam_p = os.path.join(JAVA, "engine", "cap", "CameraCap.kt")

    for p in (gate_p, bridge_p, loc_p, pkg_p, cam_p):
        if not os.path.isfile(p):
            print("FAIL: 缺文件 %s" % p)
            return 2

    gate = strip_comments(read(gate_p))
    bridge = strip_comments(read(bridge_p))
    loc = strip_comments(read(loc_p))
    pkg = strip_comments(read(pkg_p))
    cam = strip_comments(read(cam_p))

    # ---- A. 开关常量与 snapshot ----
    for const in ("LOCATION", "PACKAGES", "CAMERA"):
        check("A1 const %s 定义" % const,
              re.search(r'const val %s\s*=\s*"[a-z]+"' % const, gate) is not None)
        check("A2 %s 已进 snapshot" % const,
              re.search(r"\.put\(\s*%s\s*,\s*can\(ctx,\s*%s\)\s*\)" % (const, const), gate) is not None)
        check("A3 %s 有 permissionAvailable 分支" % const,
              re.search(r"^\s*%s\s*->" % const, gate, re.M) is not None)

    # 开关默认必须是关：prefs 用 getBoolean(key, false) 是唯一入口
    check("A4 默认关闭（getBoolean 默认 false）",
          re.search(r'getBoolean\(\s*KEY_PREFIX\s*\+\s*name\s*,\s*false\s*\)', gate) is not None)

    # ---- B. 端点接线 ----
    for ep, verb in (("/dev/location", "GET"), ("/dev/packages", "GET"),
                     ("/dev/packages", "POST"), ("/dev/camera", "POST")):
        pat = r'"%s %s" to Route' % (verb, ep)
        check("B %s %s 已注册" % (verb, ep), re.search(pat, bridge) is not None)

    # ---- C. LocationCap 的诚实性 ----
    check("C1 返回 stale 标记", re.search(r'\.put\(\s*"stale"', loc) is not None)
    check("C2 返回 age_ms", re.search(r'\.put\(\s*"age_ms"', loc) is not None)
    check("C3 返回 providers 列表", re.search(r'\.put\(\s*"providers"', loc) is not None)
    check("C4 支持精度降级", re.search(r"coarse", loc) is not None)
    check("C5 无 fix 时不报 ok=false 而是如实说 fix=false",
          re.search(r'\.put\(\s*"fix"\s*,\s*false\s*\)', loc) is not None)
    check("C6 无 fix 时仍返回 ok=true（不是错误，只是没数据）",
          re.search(r'\.put\(\s*"ok"\s*,\s*true\s*\).*?\.put\(\s*"fix"\s*,\s*false',
                    loc, re.S) is not None)
    check("C7 权限缺失与能力关闭是不同错误",
          "capability disabled" in loc and "permission not granted" in loc)
    # 局部 data class 是 Kotlin 编译期错误，必须有文件级 data class
    check("C8 Fix 是文件级/成员级 data class（不是局部类）",
          re.search(r"private data class Fix\(", loc) is not None)

    # ---- D. PackageCap 的诚实性 ----
    # ★最关键的一条：install/uninstall 必须返回 202，不能是 200
    check("D1 install 走 202 pending（不谎报成功）",
          re.search(r"return\s+202\s+to", pkg) is not None)
    check("D2 202 响应带 pending 字段",
          re.search(r'\.put\(\s*"pending"\s*,\s*true\s*\)', pkg) is not None)
    check("D3 202 明确提示后续要验证",
          re.search(r'\.put\(\s*"note"', pkg) is not None)
    check("D4 拒卸系统应用",
          "refusing to uninstall system app" in pkg)
    check("D5 用 visible_total 暴露包可见性",
          re.search(r'\.put\(\s*"visible_total"', pkg) is not None)
    check("D6 install 前检查 canRequestPackageInstalls",
          "canRequestPackageInstalls()" in pkg)
    check("D7 APK 必须先 stage 到 FileProvider 可见目录",
          re.search(r'File\(ctx\.cacheDir,\s*"apk"\)', pkg) is not None)
    check("D8 非 .apk 提前挡掉", ".apk" in pkg)
    check("D9 支持 dry_run（不真弹窗就能验通路）",
          re.search(r'\.put\(\s*"dry_run"', pkg) is not None)
    check("D10 AppRow 是成员级 data class",
          re.search(r"private data class AppRow\(", pkg) is not None)

    # ---- E. CameraCap 的诚实性 ----
    check("E1 camera2 而非 Intent 唤相机",
          "CameraManager" in cam and "ACTION_IMAGE_CAPTURE" not in cam)
    # ★最危险副作用：不关相机会让用户手动开相机全黑
    # ★必须锚定缩进：CameraCap 里有**两个** finally ——
    #   :146 内层 `} finally { image.close(); shot.countDown() }`（ImageReader 回调里）
    #   :228 外层 `} finally {` 才是释放 session/device/reader 的那个
    # 用 finally\s*\{ 非贪婪匹配会命中内层，得到「session 没关」的假失败。
    fin = re.search(r"\n {8}\} finally \{(.*?)\n {8}\}", cam, re.S)
    fin_body = fin.group(1) if fin else ""
    check("E2 finally 关了 session", re.search(r"session\?\.close\(\)", fin_body) is not None)
    check("E3 finally 关了 device", re.search(r"device\?\.close\(\)", fin_body) is not None)
    check("E4 finally 关了 reader", re.search(r"reader\?\.close\(\)", fin_body) is not None)
    check("E5 finally 退出线程", re.search(r"quitSafely\(\)", fin_body) is not None)
    check("E6 finally 兜底 countDown（防假超时）",
          fin_body.count("countDown()") >= 2)
    check("E7 JPEG 魔数校验", "0xD8" in cam)
    check("E8 等待 onOpened 回调（异步不能直接建会话）",
          "onOpened" in cam and "await(" in cam)
    # 选不到 facing 必须报错，不能静默退回后置
    check("E9 缺目标 facing 时报 404 而非退回",
          re.search(r"return\s+404\s+to", cam) is not None)
    check("E10 返回 lens 字段（不让调用方猜前后置）",
          re.search(r'\.put\(\s*"lens"', cam) is not None)
    check("E11 返回 mime 与 bytes",
          re.search(r'\.put\(\s*"mime"\s*,\s*"image/jpeg"', cam) is not None)

    # ---- F. 三个入口都先过 gate ----
    for name, src in (("LocationCap", loc), ("PackageCap", pkg), ("CameraCap", cam)):
        check("F %s 先过 CapabilityGate" % name,
              re.search(r"CapabilityGate\.can\(ctx,\s*CapabilityGate\.\w+\)", src) is not None)

    # ---- G. Root 残留的两道自愈（#100 修目录属主 + 本次修文件级陈旧锁）----
    # 两者修的是**不同**的墙，缺一会让另一半场景继续失败：
    #   ① 目录属主是 root → package.json 都写不进（healProfileOwnershipIfNeeded）
    #   ② 目录正常但锁文件属主是 root → dsh-atomic-write 的 withFileLock()
    #      把 EACCES 直接 throw（healForeignLocks）
    pc_p = os.path.join(JAVA, "engine", "PluginCenter.kt")
    pc = strip_comments(read(pc_p))

    check("G1 调 CLI 前跑目录属主自愈",
          re.search(r"healProfileOwnershipIfNeeded\(ctx\)", pc) is not None)
    check("G2 调 CLI 前跑陈旧锁清理",
          re.search(r"healForeignLocks\(ctx\)", pc) is not None)
    # ★顺序与覆盖：两个都必须出现在同一个函数体里、且都在 nodeBin 之前
    cli = re.search(r"private fun runDshPluginCli\((.*?)\n    \}", pc, re.S)
    cli_body = cli.group(1) if cli else ""
    i_own = cli_body.find("healProfileOwnershipIfNeeded(ctx)")
    i_lock = cli_body.find("healForeignLocks(ctx)")
    i_node = cli_body.find("EngineConfig.nodeBin(ctx)")
    check("G3 两道自愈都在 runDshPluginCli 里且在起进程之前",
          0 <= i_own < i_lock < i_node,
          "own=%d lock=%d node=%d" % (i_own, i_lock, i_node))

    # 清理范围不能只盯 profiles/web —— #100 的真机报错第二处落在 .cache/node
    check("G4 扫描目录覆盖 .cache/node（corepack 的写点）",
          ".cache/node" in pc and "lockScanDirs" in pc)
    check("G5 扫描目录覆盖 .local/share",
          ".local/share" in pc)
    # 只删属主不是自己的：owner<=0（取不到）宁可不删
    check("G6 只删 foreign 锁，取不到属主时不删",
          re.search(r"owner\s*<=\s*0\s*\|\|\s*owner\s*==\s*uid", pc) is not None)
    # 每隔几个文件就 fork 一次 stat 在低端机上是几百毫秒 → 必须走系统调用
    check("G7 读属主走 Files.getAttribute 而非 fork stat",
          "unix:uid" in pc and "/system/bin/stat" not in pc)
    check("G8 EACCES 落点不在已扫目录时给维护页提示",
          "Repair file ownership" in pc)

    npass = sum(1 for _, ok, _ in checks if ok)
    nfail = len(checks) - npass
    for name, ok, detail in checks:
        line = ("  OK   " if ok else "  FAIL ") + name
        if detail and not ok:
            line += "  <" + detail + ">"
        print(line)
    print("\n%d checks, %d failed" % (len(checks), nfail))
    return 0 if nfail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

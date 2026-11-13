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

    ⚠️ 必须用**字符串感知**的实现（v1.7.10 真机修）：本文件原先那份朴素剥离器
    不认识字符串字面量，于是注释里的 `tasks/*.json`、`https://…`、`content://…`
    这类内容会被当成注释起点 —— `/*` 进去后一路找不到 `*/` 就 `break`，
    **整个文件从那一行起被截断**。后果是目录里 49 条条目凭空消失、I1 报一堆
    假漂移（实测：加了一条含 `tasks/*.json` 的目录条目后，I1 从绿变红）。
    共享模块 `_kstrip` 已经正确处理引号与嵌套块注释，直接复用它。
    """
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import _kstrip
        return _kstrip.strip_comments(src)
    except Exception:
        # 兜底：不该走到这里；真走到时宁可原样返回（断言会因注释里的字面量假绿，
        # 也比静默截断文件导致一堆假红更容易发现）。
        return src


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

    # ---- H. 三条链必须同时打通（2026-11-09 补：此前只查了 A~F，全是「假绿」）----
    # 背景：本探针原有 A~F 只盯着 CapabilityGate / AgentBridge / Cap 实现三处，
    # 而 3080c31e 之后这三个能力在**用户可见面**上一直是缺失的：设置页没有开关、
    # dev 目录里没登记、提示词里没提。探针全绿但用户根本打不开 —— 这正是
    # docs/CAPABILITY_MAP.md 里写的「假绿」：断言只看实现，不看暴露面。
    pca_p = os.path.join(JAVA, "PermissionCenterActivity.kt")
    pca = strip_comments(read(pca_p))
    cat_p = os.path.join(JAVA, "engine", "EndpointCatalog.kt")
    cat = strip_comments(read(cat_p))
    seed_p = os.path.join(JAVA, "engine", "AgentContextSeed.kt")
    seed = strip_comments(read(seed_p))

    # H1/H2：每个 CapabilityGate 常量都必须出现在权限中心的磁贴定义里。
    # 这是「加了能力却忘了接 UI」的结构性防线 —— 未来任何新常量漏接都会在这里红。
    # ★ @Deprecated 的常量必须排除：PERSONAL 是「四项细开关」之前的旧捆绑开关，
    #   源码里明确标注「运行时不再读取」，它永远不该出现在磁贴里。若把它当成漏接，
    #   探针会红在一个**正确的删除**上 —— 那种红会训练人忽略信号。
    for m in re.finditer(r'const val ([A-Z_]+) = "([a-z_]+)"', gate):
        name, val = m.group(1), m.group(2)
        if val == "capabilities":  # PREFS 的键名不是能力
            continue
        head = gate[max(0, m.start() - 400):m.start()]
        prev_const = head.rfind("const val ")
        deprecated = "@Deprecated" in head[prev_const + 1:]
        if deprecated:
            check("H1 %s 已废弃且确实未被引用（不进磁贴是对的）" % name,
                  re.search(r"CapabilityGate\.%s\s*," % name, pca) is None)
            continue
        check("H1 %s 出现在 capDomains()" % name,
              re.search(r"CapabilityGate\.%s\s*," % name, pca) is not None)

    # H3：目录里必须登记三个端点（否则 dev 发现不了、MCP 也没有这些工具）
    for eid, verb, ep in (("location", "GET", "/dev/location"),
                          ("packages", "GET", "/dev/packages"),
                          ("packages", "POST", "/dev/packages"),
                          ("camera", "POST", "/dev/camera")):
        pat = r'"%s", "%s", "%s"' % (eid, verb, ep)
        check("H3 目录登记 %s %s" % (verb, ep), re.search(pat, cat) is not None)

    # H4：提示词必须提到这三组（否则 AI 不知道端点存在，等于没做）
    for word in ("dev location", "dev camera", "dev packages"):
        check("H4 提示词提到 %s" % word, word in seed)

    # H5：五套语言都要有缺权文案（i18n 会查 parity，这里查语义是否真的写到）
    res = os.path.join(ROOT, "app", "src", "main", "res")
    for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
        sp = os.path.join(res, loc, "strings.xml")
        if not os.path.isfile(sp):
            continue
        txt = read(sp)
        for key in ("setting_cap_location_need", "setting_cap_packages_need",
                    "setting_cap_camera_need"):
            check("H5 %s 有 %s" % (loc, key), ('name="%s"' % key) in txt)

    # H6：运行时权限必须真的能当场申请（只有文案而没有 requestPermissions = 死路）
    check("H6 相机/定位走真实 requestPermissions",
          re.search(r"requestPermissions\(missing\.toTypedArray\(\), REQ_CAP_RUNTIME\)", pca) is not None)
    check("H7 权限结果回调会重绘磁贴",
          re.search(r"REQ_CAP_RUNTIME\s*\)\s*\{", pca) is not None)
    # H8：应用详情页必须带 package data，否则打开的是「所有应用」列表
    check("H8 跳应用详情页带 package: data",
          re.search(r'Uri\.parse\("package:\$packageName"\)', pca) is not None)

    # ---- I. 目录 ↔ 路由表零漂移（静态等价于 EndpointCatalog.verifyAgainst）----
    # 为什么在探针里再算一遍：verifyAgainst 只在桥启动时打日志，本地跑探针时
    # 根本不会触发 —— 于是「目录说能用、实际 404」要等装到设备上、看 logcat 才
    # 发现。这里用同一套判据静态复算，改代码就能立刻看见漂移。
    #
    # 判据与 Kotlin 侧逐条对齐（改了任一侧都要同步，注释里写清楚）：
    #   ① 目录里 path 不以 / 开头的（gates/root 这类门脚本）不参与对账；
    #   ② method 可能是 "GET|POST" 或 "GET/POST"，两种分隔符都要拆；
    #   ③ 反向只按**路径**判，不按 method+path（GET 只读 + POST 动作是常态）；
    #   ④ 反向**不按前缀过滤**：v1.7.10 之前这里（和 Kotlin 侧）都写成
    #      `"/dev/" not in rk`，于是非 /dev 前缀的整块能力（/memory/* /trace/*
    #      /task/* /vision/* /ask/* /handoff/*）既不在目录里、也不被对账发现，
    #      79 个路径对 AI 完全隐身而兜底机制一次都没报。前缀不是契约的一部分。
    route_keys = set(re.findall(r'"((?:GET|POST) /[a-z0-9/_-]+)" to Route', bridge))
    cat_entries = re.findall(
        r'"([a-z0-9/_-]+)",\s*"([A-Z|/]+)",\s*"([^"]+)"', cat)
    meta_routes = {"GET /dev/help", "GET /dev/routes"}
    drift = []
    known_paths = set()
    n_http = 0
    for eid, method, path in cat_entries:
        if not path.startswith("/"):
            continue
        n_http += 1
        known_paths.add(path)
        for m in re.split(r"[|/]", method):
            key = "%s %s" % (m, path)
            if key not in route_keys:
                drift.append("catalog lists %s but no route" % key)
    for rk in sorted(route_keys):
        if rk in meta_routes:
            continue
        if rk.split(" ")[1] not in known_paths:
            drift.append("route %s not in catalog" % rk)
    check("I1 目录 HTTP 条目数与路由表无漂移（%d 条目 / %d 路由）"
          % (n_http, len(route_keys)), not drift, "; ".join(drift[:4]))
    # I2：自证解析真的读到了东西 —— 否则两个空集合也会「无漂移」，假绿。
    check("I2 解析到足够的目录条目（>=50）", n_http >= 50, "只解析到 %d 条" % n_http)
    check("I3 解析到足够的路由（>=60）", len(route_keys) >= 60, "只解析到 %d 条" % len(route_keys))

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

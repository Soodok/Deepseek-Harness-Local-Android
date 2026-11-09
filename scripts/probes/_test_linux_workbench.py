#!/usr/bin/env python3
"""Linux 工作台容器选项 + AI API 的静态断言探针。

为什么要有这个探针：本轮改动横跨 6 个文件（Catalog / EnvManager / Activity /
CapabilityGate / AgentBridge / EndpointCatalog / PermissionCenter + 布局 + 五语文案），
而其中**任何一处漏掉都会让功能"看起来有、实际用不了"**：

  · CapabilityGate 加了常量但 permissionAvailable 没给 true → 开关永久置灰
  · EndpointCatalog 没登记 → `dev` 目录里根本发现不了端点（AI 不知道它存在）
  · EngineConfig 三处列表漏一处 → 门脚本不部署，AI 敲 dev-linux 得到 not found
  · 权限中心没引用 → 用户永远打不开开关（本轮真的抓到了这个缺口）
  · ensureBootstrapPackages 标记不按 flavor 分 → 切形态后额外包永远不装

所以断言分五段：A 目录/形态  B 能力门  C 端点三处一致  D UI 接线  E 包安装

反向验证（改坏必须变红）：
  1. 把 `LINUX -> true` 改回 false          → B2 红
  2. 删 EndpointCatalog 里 linux/status 一条 → C1 红
  3. 删 EngineConfig 某一处 dev-linux        → C4 红
  4. 权限中心去掉 CapabilityGate.LINUX spec  → D1 红
  5. flavor 标记改回共用 BOOTSTRAP_MARKER    → E1 红
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
checks = 0
failed = 0


def ok(name, cond, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print(f"  ok   {name}")
    else:
        failed += 1
        print(f"  FAIL {name}" + (f"  [{detail}]" if detail else ""))


def read(rel):
    with io.open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def kt_code(src):
    """剥掉 Kotlin 注释，避免断言被注释里的字面量满足（这个坑踩过多次）。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            depth = 1
            i += 2
            while i < n and depth:
                if src.startswith("/*", i):
                    depth += 1
                    i += 2
                elif src.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        if src[i] == '"':
            out.append(src[i])
            i += 1
            while i < n:
                if src[i] == chr(92):
                    out.append(src[i:i + 2])
                    i += 2
                    continue
                out.append(src[i])
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


CAT = read("app/src/main/java/app/dsh/mobile/engine/LinuxRootfsCatalog.kt")
CAT_C = kt_code(CAT)
ENV = read("app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt")
ENV_C = kt_code(ENV)
ACT = read("app/src/main/java/app/dsh/mobile/LinuxActivity.kt")
ACT_C = kt_code(ACT)
GATE = read("app/src/main/java/app/dsh/mobile/engine/CapabilityGate.kt")
GATE_C = kt_code(GATE)
BRIDGE_C = kt_code(read("app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt"))
EPC = read("app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt")
EPC_C = kt_code(EPC)
ENGINE_C = kt_code(read("app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt"))
PERM_C = kt_code(read("app/src/main/java/app/dsh/mobile/PermissionCenterActivity.kt"))
LAYOUT = read("app/src/main/res/layout/activity_permission_center.xml")
CAP = read("app/src/main/java/app/dsh/mobile/engine/cap/LinuxCap.kt")
CAP_C = kt_code(CAP)
GATE_SCRIPT = read("app/src/main/assets/gates/dev-linux")

print("=== A. 容器目录与形态 ===")

ok("A1 Family 含 ALPINE_EDGE 且 id 稳定",
   'ALPINE_EDGE("alpine323")' in CAT_C,
   "改 id 会让已部署用户的目录找不到")

ok("A2 Flavor 有三个形态 BASE/DEV/GUI",
   all(f'{n}(' in CAT_C for n in ("BASE", "DEV", "GUI")))

ok("A3 DEV 形态带 build-base（编译能力的前提）",
   'build-base' in CAT_C)

ok("A4 GUI 形态带 xvfb 与 xterm（触摸画面功能的前提）",
   '"xvfb"' in CAT_C and '"xterm"' in CAT_C,
   "缺任一个都会让「开始画面」报 not found")

ok("A5 catalog 对 APK 系展开 flavor、对 APT 系不展开",
   re.search(r"flatMap\s*\{[^}]{0,200}pkgManager\s*!=\s*PackageManager\.APK", CAT_C) is not None,
   "apt 的额外包要走两步法，本轮不展开")

ok("A6 3.23 镜像 sha256 是实测的 64 位十六进制",
   re.search(r'sha256\s*=\s*if \(IS_ARM64\)\s*"3f1bd6811370264cc363bb991ff15ac67621b239ea6e25ff2eb22af3d0641473"',
             CAT_C) is not None,
   "sha256 必须与官方 .sha256 一致，否则下载完校验失败")

ok("A7 flavor 与 family 分开存两个 key",
   'KEY_ACTIVE_FLAVOR' in CAT_C and 'KEY_ACTIVE = "active_family"' in CAT_C,
   "拼成一个串会让只有 family 的旧版本读不出来")

ok("A8 active() 把 flavor 组合回来",
   re.search(r"fun active\(ctx: Context\)[\s\S]{0,900}activeFlavor\(ctx\)", CAT_C) is not None,
   "不组合的话切到 GUI 之后读回 BASE，额外包永远不装")

print("=== B. 能力门 ===")

ok("B1 CapabilityGate 定义了 LINUX 常量",
   re.search(r'const val LINUX = "linux"', GATE_C) is not None)

ok("B2 permissionAvailable 给 LINUX true（否则永久置灰）",
   re.search(r"LINUX -> true", GATE_C) is not None,
   "默认分支是 else -> false，不显式给 true 用户永远点不动")

ok("B3 LINUX -> true 在 else -> false 之前",
   (lambda a, b: a >= 0 and b >= 0 and a < b)(
       GATE_C.find("LINUX -> true"), GATE_C.find("else -> false")))

ok("B4 snapshot 暴露 linux 开关",
   re.search(r"\.put\(LINUX, can\(ctx, LINUX\)\)", GATE_C) is not None)

print("=== C. 端点三处一致（路由 / 目录 / 门脚本 / 部署列表）===")

ROUTES = ["/dev/linux/status", "/dev/linux/distros", "/dev/linux/switch",
          "/dev/linux/deploy", "/dev/linux/exec"]
# 路由表条目形如 "GET /dev/linux/status" / "POST /dev/linux/exec"；
# 断言必须带方法前缀，否则找的是裸路径（那条永远不存在 → 假红）。
for meth, r in (("GET", "/dev/linux/status"), ("GET", "/dev/linux/distros"),
                ("POST", "/dev/linux/switch"), ("POST", "/dev/linux/deploy"),
                ("POST", "/dev/linux/exec")):
    ok(f"C1 路由表有 {meth} {r}", f'"{meth} {r}"' in BRIDGE_C)

CATALOG_PATHS = re.findall(r'"/dev/linux/[a-z]+"', EPC_C)
ok("C2 EndpointCatalog 登记了全部 5 个路径",
   len(set(CATALOG_PATHS)) == 5,
   f"found {sorted(set(CATALOG_PATHS))}")

ok("C3 目录里每条都标了 cap = linux",
   EPC_C.count('cap = "linux"') >= 5,
   "漏标会让 dev 目录显示成「无需授权」，误导用户")

ok("C4 EngineConfig 三处列表都含 dev-linux",
   ENGINE_C.count('"dev-linux"') == 3,
   "漏一处门脚本就不会被部署，AI 敲 dev-linux 得到 not found")

ok("C5 门脚本存在且有 bash 自举头",
   "_DSH_REEXEC" in GATE_SCRIPT and "@DSH_BASH@" in GATE_SCRIPT)

# case 分支有几种写法：`status|show)`、`""|status|show)`、`deploy)`。
# 统一按「出现在某个 case 行里」判，而不是要求 `动作|` 开头。
for act in ("status", "list", "use", "deploy", "run"):
    hit = re.search(rf"^\s*(?:\S*\|)*{act}(?:\|\S*)?\)\s*$", GATE_SCRIPT, re.M) is not None
    ok(f"C6 门脚本支持 {act}", hit)

ok("C7 门脚本 run 会把参数拼成一条命令",
   "_join" in GATE_SCRIPT and "_dsh_json_escape" in GATE_SCRIPT,
   "不转义会让带引号的命令把 JSON 打断")

print("=== D. UI 接线 ===")

for rid in ("rowCapLinux", "swCapLinux", "stCapLinux", "txtDomLinux"):
    ok(f"D1 布局含 {rid}", f'@+id/{rid}' in LAYOUT)

ok("D2 权限中心引用了 LINUX / PACKAGES / LOCATION / CAMERA 四个能力",
   all(f"CapabilityGate.{c}" in PERM_C for c in ("LINUX", "PACKAGES", "LOCATION", "CAMERA")),
   "本轮发现前三项代码与文案都在、但 UI 从未引用 → 用户开不了")

ok("D3 工作台用 catalog（含 flavor）而不是 options",
   "LinuxRootfsCatalog.catalog.forEach" in ACT_C,
   "用 options 只会有 1 条 Alpine，看不到 Dev/GUI 形态")

# D5 语义：UI 切换必须**同时**记 family 与 flavor，且必须一次原子写入。
# 旧实现是 setActive(...) + setActiveFlavor(...) 两次 apply；审查指出两次之间
# 被杀会留下「新 family + 旧 flavor」不一致，已改成 setActiveExact 一次提交。
# 断言跟着改，但仍然要求「两个值都传进去」—— 不能因为换了写法就放松。
ok("D5 UI 切换时原子记 family + flavor",
   re.search(r"setActiveExact\(this@LinuxActivity, opt\.family, opt\.flavor\)", ACT_C) is not None,
   "只记 family 的话点 GUI 那行之后 active() 读回 BASE，GUI 额外包永远不装；"
   "两次独立 apply 还会在崩溃时留下不一致")

ok("D5b UI 不再用两次独立的 setActive/setActiveFlavor",
   re.search(r"setActive\(this@LinuxActivity", ACT_C) is None
   and re.search(r"setActiveFlavor\(this@LinuxActivity", ACT_C) is None,
   "两条独立提交之间存在不一致窗口，应统一走 setActiveExact")

ok("D6 UI 把「已部署」与「该形态包已装」分开显示",
   "flavorPackagesReady" in ACT_C and "linux_distro_extras_pending" in ACT_C,
   "只报 isDeployed 会让切到 GUI 显示成已安装，而 Xvfb 还没装")

ok("D7 flavorPackagesReady 认得 attempted 标记为未就绪",
   re.search(r"flavorPackagesReady[\s\S]{0,900}attempted:", ENV_C) is not None,
   "装失败也落标记；不区分会让 UI 报「已就绪」而实际缺包")

ok("D4 激活判定同时比 family 与 flavor",
   re.search(r"isActive\s*=\s*opt\.family == active\.family && opt\.flavor == active\.flavor",
             ACT_C) is not None,
   "只比 family 会让三行同时亮")

print("=== E. 包安装与标记 ===")

ok("E1 bootstrap 标记按 flavor 分开",
   re.search(r"BOOTSTRAP_MARKER \+ if \(option\.flavor == LinuxRootfsCatalog\.Flavor\.BASE\)",
             ENV_C) is not None,
   "共用标记会让切到 GUI 直接 return，额外包永远不装")

ok("E2 安装列表包含 flavor.extraPackages",
   "option.flavor.extraPackages" in ENV_C)

ok("E3 成功与失败两处都写 pkgs（而不是常量 BOOTSTRAP_PACKAGES）",
   ENV_C.count("pkgs.joinToString") == 2,
   "失败分支写常量会让 attempted 标记与真实尝试不符")

ok("D8 GUI 形态在缺随包工具的 ABI 上标为不可用",
   "availableOnThisAbi" in CAT_C
   and re.search(r"Flavor\.GUI -> !IS_ARM64", CAT_C) is not None,
   "arm64 缺 x11frsrv/inputd，选 GUI 会装好包但用不了")

ok("D9 UI 对不可用形态给出解释而不是静默撞墙",
   "linux_flavor_unsupported" in ACT_C,
   "不解释的话用户只会看到 x11frsrv: not found")

ok("D10 API 的 distros 暴露 available 字段",
   re.search(r'\.put\("available", LinuxRootfsCatalog\.availableOnThisAbi', CAP_C) is not None,
   "AI 会建议用户切到不可用形态")

ok("D11 API 的 switch 拦住不可用形态（409 而不是让它切过去）",
   re.search(r'availableOnThisAbi\(target\.flavor\)[\s\S]{0,200}409', CAP_C) is not None)

print("=== H. 审查修复回归（P1 三项）===")

# P1-1：Android getString(id, arg) 用 %1$s（java.util.Formatter），不是 MessageFormat 的 {0}
_STR_I18N = {}
for _d in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
    _STR_I18N[_d] = read("app/src/main/res/%s/strings.xml" % _d)
_bad_ph = []
for _d, _x in _STR_I18N.items():
    _m = re.search(r'<string name="linux_flavor_unsupported">(.*?)</string>', _x)
    if not _m:
        _bad_ph.append(_d + ":missing")
        continue
    _body = _m.group(1)
    if "{0}" in _body or "%1$s" not in _body:
        _bad_ph.append(_d + ":" + _body[:24])
ok("H1 linux_flavor_unsupported 五语都用 %1$s 占位符",
   not _bad_ph,
   "; ".join(_bad_ph))

ok("H2 不存在残留的 {0} 占位符（MessageFormat 语法在 Android 资源里无效）",
   all("{0}" not in _x for _x in _STR_I18N.values()))

# P1-2：原子写入 + active() 校验 ABI
ok("H3 存在原子写入 setActiveExact",
   re.search(r"fun setActiveExact\(ctx: Context, family: Family, flavor: Flavor\)", CAT_C) is not None)

# ★H4 必须验「同一个 edit()」，不能只验「两个 putString 相邻出现」。
#
# 第一版断言写的是「两个 putString 之间≤200 字符」，反向验证时我把实现改成
# `edit()...apply()` 紧接 `edit()...apply()` —— 两次 apply 的形态下两个 putString
# 依然相邻，断言照样绿。这正是"假绿"：量级不足，测不出差异等于没测。
#
# 现在改为：从 setActiveExact 起截到该方法的第一处 apply()，在这段里**不许**
# 出现第二个 edit( —— 因为原子写法只需要一次 edit。同时要求两个 putString 都
# 落在这一段内。
_m = re.search(
    r"fun setActiveExact\(ctx: Context, family: Family, flavor: Flavor\)[\s\S]{0,900}?\n    \}",
    CAT_C,
)
_h4_body = _m.group(0) if _m else ""
_h4_first_apply = _h4_body.find(".apply()")
_h4_seg = _h4_body[:_h4_first_apply + len(".apply()")] if _h4_first_apply >= 0 else _h4_body
ok("H4 setActiveExact 在同一个 edit() 里写两个 key",
   _h4_seg.count("edit(") == 1
   and "putString(KEY_ACTIVE, family.id)" in _h4_seg
   and "putString(KEY_ACTIVE_FLAVOR, flavor.id)" in _h4_seg,
   "两次独立 apply 之间被杀会留下不一致；首段 edit 数=" + str(_h4_seg.count("edit(")))

ok("H5 active() 对 ABI 不可用的 flavor 退回 BASE",
   re.search(r"fun active\(ctx: Context\)[\s\S]{0,1400}availableOnThisAbi\(f\)", CAT_C) is not None,
   "不退回的话 arm64 上残留 gui 会让整个 App 以为当前是 GUI 形态")

ok("H6 API 与 UI 都走 setActiveExact（无遗留两次调用）",
   "setActiveExact" in CAP_C
   and re.search(r"setActive\(ctx, target\.family\)", CAP_C) is None)

# P1-3：缺映射 fail-fast
ok("H7 apkRepositories 缺映射时 error() 而不是静默回落",
   re.search(r"APK_MIRROR_VERSION\[option\.family\][\s\S]{0,300}error\(", ENV_C) is not None,
   "静默回落 v3.22 正是 8e91833f 那个严重 bug 的形态")

ok("H8 UBUNTU 不再登记在 APK_MIRROR_VERSION（避免误调拿到错仓库）",
   re.search(r'Family\.UBUNTU to "v3\.', ENV_C) is None,
   "登记成 v3.22 会让误调得到看似正常的 Alpine 仓库地址")

print("=== G. 版本一致性（3.23 不能用 3.22 的索引）===")

ok("G1 apk 仓库按发行版版本分流，不是写死一份",
   re.search(r"fun apkRepositories\(option:", ENV_C) is not None
   and "APK_MIRROR_VERSION" in ENV_C,
   "写死 v3.22 会让 3.23 的 rootfs 拿 3.22 索引装包，等于把包系统降级")

ok("G2 3.22 与 3.23 映射到不同的仓库版本",
   re.search(r'Family\.ALPINE to "v3\.22"', ENV_C) is not None
   and re.search(r'Family\.ALPINE_EDGE to "v3\.23"', ENV_C) is not None)

ok("G3 常量与发行版版本号对得上（不是 ALPINE_EDGE 却给 v3.22）",
   (lambda m: m is not None and m.group(1) != m.group(2) if m else False)(
       re.search(r'ALPINE to "(v3\.\d+)"[\s\S]{0,160}ALPINE_EDGE to "(v3\.\d+)"', ENV_C)),
   "两者相同说明复制粘贴没改")

ok("G4 已装包数按包管理器分流（Ubuntu 不能恒 0）",
   re.search(r'PackageManager\.APK ->[\s\S]{0,300}lib/apk/db/installed', CAP_C) is not None
   and re.search(r'PackageManager\.APT ->[\s\S]{0,300}var/lib/dpkg/status', CAP_C) is not None,
   "只读 apk 数据库会让 Ubuntu 报 packages_installed: 0，AI 误判空容器")

print("=== F. exec 的安全边界 ===")

ok("F1 硬超时上限存在且 ≤ 120s",
   re.search(r"EXEC_TIMEOUT_MAX_MS = 120_000L", CAP_C) is not None)

ok("F2 超时被 coerceIn 夹住（不能由调用方突破）",
   re.search(r"coerceIn\(1_000L, EXEC_TIMEOUT_MAX_MS\)", CAP_C) is not None)

ok("F3 stdout / stderr 分开返回，不合并",
   '"stdout"' in CAP_C and '"stderr"' in CAP_C and "combined" not in CAP_C)

ok("F4 每个端点都先过 cap 检查",
   CAP_C.count("CapabilityGate.can(ctx, CapabilityGate.LINUX)") == 5,
   "少一处就是一处未授权入口")

ok("F5 未部署时 exec 返回 409 而不是硬跑",
   re.search(r"isDeployed\(opt\)\)[\s\S]{0,260}409", CAP_C) is not None)

ok("F6 响应里如实写出 limits（不是 pty / 无 token / 长任务怎么办）",
   CAP_C.count('"limits"') >= 2 and "not a pty" in CAP)

print()
print(f"{checks} checks, {failed} failed")
sys.exit(1 if failed else 0)

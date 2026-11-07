#!/usr/bin/env python3
"""内置用户态插件升级护栏（v1.5.x）。

## 为什么要这个探针

`ProfilePluginProvisioner.ensure()` 原来第一句就是：

    if (prefs.getBoolean(key(plugin), false)) {
        // 非首次安装：一个字节都不动
        return
    }

后果是**预装过就永久锁定**：CI 把新 tgz 打进 APK 也没用 —— `pack-mobile-layout.sh`
拉的是 `MOBILE_LAYOUT_VERSION`（默认 latest），CI 产物天天换新，而存量用户
永远停在首次安装那一版。实测现场：设备装 0.4.5、npm 已 0.4.8，插件仓库
README 明写「0.2.2-rc.1 请用 ≥0.4.8，0.4.6 及以下不要用」，UI 修复传不出去。

## 修法边界（本探针守的就是这些边界）

只在**随包版本 > 已装版本**时升级，且：

- `installedVersion` 为 null（用户彻底卸载过）→ 不动。不能借「包里有新版」
  偷偷把用户卸掉的插件装回去。
- `compareVersions(bundled, current) <= 0` → 不动。用户自己从插件页装了
  更新版时绝不降级。
- 升级路径（`upgradeFrom != null`）**绝不碰 bundles** —— 挂载/摘掉是用户的开关，
  不能因为随包带了新版就被重新打开。

## 版本比较必须是数字感知

字符串比较下 `"0.4.10" < "0.4.8"`（逐字符 '1' < '8'），0.4.9 之后就会
「该升级时不升级」。必须按数字段比，且 prerelease 语义按 semver：
`0.5.0-rc.1 < 0.5.0`。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PROV = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine",
    "ProfilePluginProvisioner.kt",
)

passed = 0
failed = 0
skipped = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


def skip(name, detail=""):
    global skipped
    skipped += 1
    print("SKIP  %s%s" % (name, ("  -> " + detail) if detail else ""))


src = open(PROV, encoding="utf-8").read()

# 剥掉注释再断言结构：注释里可以随便写「already provisioned」，含它的行不应
# 被当成有效代码逻辑。
no_block = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
code = re.sub(r"//[^\n]*", "", no_block)

print("== A. 早退逻辑不再是「prefiltered 就无脑 return」 ==")

# A1: provisioned 早退块里必须读随包版本与已装版本。
#     ★边界必须收紧（同 B2 的教训）：原来写 `(.*?)\n    \}` —— 4 空格收尾，
#     而该 if 块体是 8 空格、函数收尾才是 4 空格，于是非贪婪匹配一路吃到整个
#     ensure() 尾部，把后面 isManaged 分支里的 installedVersion 也算进来，
#     导致「改回原样只 return」时 A2/A3 仍然假绿。改为限制块长上限 + 8 空格收尾。
m = re.search(
    r"if\s*\(\s*prefs\.getBoolean\(\s*key\(plugin\)\s*,\s*false\s*\)\s*\)\s*\{(.{0,1200}?)\n {8}\}",
    code, re.S,
)
check("A1 provisioned 分支存在且被定位到", m is not None)
if m:
    body = m.group(1)
    check("A2 该分支读随包版本 readAsset", "readAsset(ctx, plugin)" in body,
          "必须拿到随包版本才能判「是否更新」")
    check("A3 该分支读已装版本 installedVersion", "installedVersion(ctx, plugin.pkg)" in body,
          "必须拿到已装版本才能比较")
    check("A4 该分支调用 compareVersions", "compareVersions(" in body,
          "必须做版本比较，不能只看标记")
    check("A5 无恒假短路（不许 if (false && ...) 骗过断言）",
          re.search(r"if\s*\(\s*false\s*&&", body) is None)
else:
    for n in ("A2", "A3", "A4", "A5"):
        check("%s (跳过：A1 未命中)" % n, False)

print("\n== B. 升级路径不得触碰 bundles（用户的开关） ==")

# B1: install 有 upgradeFrom 形参
check("B1 install() 接受 upgradeFrom 形参",
      re.search(r"fun install\([^)]*upgradeFrom\s*:\s*String\?", code, re.S) is not None)

# B2: 升级分支在挂载逻辑之前就 return。
#     ★边界必须收紧：早先写 `\n        \}` 只匹配 8 空格缩进的收尾，而这个 if 块
#     是 12 空格缩进，于是正则一路吃到后面首装段的 ensureBundleEntry，
#     把 B4/B5 判成假红。改为按本块的缩进收尾精确截断，并限制块长度上限。
m2 = re.search(
    r"if\s*\(\s*upgradeFrom\s*!=\s*null\s*\)\s*\{(.{0,2000}?)\n {12}\}",
    code, re.S,
)
check("B2 升级分支存在", m2 is not None)
if m2:
    body2 = m2.group(1)
    check("B3 升级分支含 return（不会继续走首装挂载逻辑）",
          re.search(r"\breturn\b", body2) is not None)
    check("B4 升级分支内不调 ensureBundleEntry",
          "ensureBundleEntry" not in body2,
          "升级不能把用户摘掉的插件重新挂上")
    check("B5 升级分支内不调 removeBundleEntry",
          "removeBundleEntry" not in body2,
          "升级不能把用户开着的插件摘掉")
    # 契约已变（v1.6.18）：升级不再走 pnpm，判据从「effective 是否变了」
    # 改成「解出的版本必须严格新于旧版」，任何一个不满足都保留旧版并记失败。
    check("B7 升级后核对解出的版本真的比旧版新（不许谎报 upgraded X -> X）",
          re.search(r"compareVersions\(installed, upgradeFrom\) <= 0", body2) is not None,
          "解包成功但版本没变（或更旧）时必须保留旧版，不能记成升级成功")
    check("B6 升级分支刷新 provisioned 标记",
          "markProvisioned" in body2,
          "不刷新版本标记，下次启动会重复升级")
else:
    for n in ("B3", "B4", "B5", "B6"):
        check("%s (跳过：B2 未命中)" % n, False)

# B7: 首装路径仍会挂载/摘掉（别为了升级把首装逻辑搞丢）
check("B7 首装路径仍调用 ensureBundleEntry",
      "ensureBundleEntry(ctx, plugin.pkg)" in code)
check("B8 首装路径仍调用 removeBundleEntry",
      "removeBundleEntry(ctx, plugin.pkg)" in code)

print("== B2. lockfile 与 pnpm 路线（已废弃，改为直接解包）==")

# v1.5.x 契约变更：升级不再走 `dsh plugin add`，它在「升级已存在的包」场景
# 下四种做法全失败（空 pnpm.log、1.3 秒 no-op、矛盾 lockfile、包被删）。
# 现在升级 = 直接解 tarball 覆盖 node_modules，所以 normalizeLockfileSpec /
# normalizeDependencySpec 已整体删除。
check("B2a 不再手工改 lockfile（那条路会拼出矛盾结构）",
      "normalizeLockfileSpec(" not in code)
check("B2b 不再把 file: 依赖改回纯版本号",
      "normalizeDependencySpec(" not in code)

print("\n== C. 版本比较实现 ==")

check("C1 compareVersions 存在", "fun compareVersions(" in code)

# C2: 必须按数字段比较，不能直接用字符串比较
m3 = re.search(r"fun compareVersions\([^)]*\)\s*:\s*Int\s*\{(.*?)\n    \}", code, re.S)
check("C2 compareVersions 函数体被定位到", m3 is not None)
if m3:
    b3 = m3.group(1)
    check("C3 按 '.' 切数字段", re.search(r"split\(\s*'\.'\s*\)", b3) is not None,
          "必须按段比较；字符串比较会把 0.4.10 判小于 0.4.8")
    check("C4 有 toIntOrNull 数字解析", "toIntOrNull()" in b3)
    check("C5 处理 prerelease（按 '-' 切）",
          re.search(r"substringBefore\(\s*'-'\s*\)", b3) is not None)
    check("C6 无 prerelease 的一方视为更大（semver：0.5.0-rc.1 < 0.5.0）",
          re.search(r"if\s*\(\s*prea\.isEmpty\(\)\s*\)\s*return\s*1", b3) is not None
          and re.search(r"if\s*\(\s*preb\.isEmpty\(\)\s*\)\s*return\s*-1", b3) is not None,
          "缺这条会把正式版判成旧于 rc 版，导致该升级时不升级")
    check("C7 非法输入返回 0（不更新）",
          re.search(r"\?:\s*return\s*0", b3) is not None,
          "宁可不动，也不要拿非法版本号去降级装上")
else:
    for n in ("C3", "C4", "C5", "C6", "C7"):
        check("%s (跳过：C2 未命中)" % n, False)

print("\n== D. 数值语义自测（模拟 Kotlin 实现，验证判定正确） ==")


def cmp_ver(a, b):
    """Python 模拟 compareVersions，用于验证算术判定本身。"""
    def norm(v):
        s = v.strip().lstrip("vV")
        s = s.split("+")[0]
        core, _, pre = s.partition("-")
        nums = []
        for seg in core.split("."):
            if not seg.isdigit():
                return None
            nums.append(int(seg))
        return nums, ([] if not pre else pre.split("."))

    pa = norm(a)
    pb = norm(b)
    if pa is None or pb is None:
        return 0
    na, prea = pa
    nb, preb = pb
    for i in range(max(len(na), len(nb))):
        x = na[i] if i < len(na) else 0
        y = nb[i] if i < len(nb) else 0
        if x != y:
            return -1 if x < y else 1
    if not prea and not preb:
        return 0
    if not prea:
        return 1
    if not preb:
        return -1
    for i in range(max(len(prea), len(preb))):
        if i >= len(prea):
            return -1
        if i >= len(preb):
            return 1
        x, y = prea[i], preb[i]
        if x.isdigit() and y.isdigit():
            cx, cy = int(x), int(y)
            if cx != cy:
                return -1 if cx < cy else 1
        else:
            if x != y:
                return -1 if x < y else 1
    return 0


cases = [
    # (a, b, 期望符号)
    ("0.4.5", "0.4.8", -1, "常态升级：0.4.5 -> 0.4.8"),
    ("0.4.8", "0.4.5", 1, "不降级"),
    ("0.4.8", "0.4.8", 0, "同版本不重装"),
    ("0.4.8", "0.4.10", -1, "★数字感知：0.4.8 旧于 0.4.10（字符串比较会判反）"),
    ("0.4.10", "0.4.8", 1, "★数字感知反向"),
    ("0.4", "0.4.0", 0, "缺位补 0"),
    ("1.0.0", "1.0", 0, "缺位补 0（major）"),
    ("0.5.0-rc.1", "0.5.0", -1, "★prerelease 低于同号正式版"),
    ("0.5.0", "0.5.0-rc.1", 1, "★正式版高于同号 prerelease"),
    ("0.5.0-rc.1", "0.5.0-rc.2", -1, "prerelease 递增"),
    ("v1.2.3", "1.2.3", 0, "v 前缀忽略"),
    ("1.2.3+build9", "1.2.3", 0, "+meta 忽略"),
    ("abc", "1.0.0", 0, "非法输入视为不更新"),
    ("1.0.0", "abc", 0, "非法输入视为不更新"),
]
for a, b, want, desc in cases:
    got = cmp_ver(a, b)
    sign = 0 if got == 0 else (-1 if got < 0 else 1)
    check("D %s" % desc, sign == want, "cmp(%r,%r) => %d，期望符号 %d" % (a, b, got, want))

# D 末：反向验证「若用字符串比较会犯什么错」——把这条事实本身也断言下来，
# 免得以后有人图省事改成 String.compareTo。
check("D★ 反证：字符串比较会把 0.4.10 判小于 0.4.8",
      "0.4.10" < "0.4.8",
      "这正是必须按数字段比较的原因")

print("\n== E. 回归：不许把「已装但没标记」的路径弄丢 ==")

check("E1 仍有 isManaged 分支（外部途径装好的只补记标记）",
      "isManaged(ctx, plugin.pkg)" in code)
check("E2 仍有退避逻辑（避免每次开机白等 120s）",
      "backoffRemainingMs(prefs, plugin.pkg)" in code)
check("E3 仍有断网跳过",
      "NetworkState.isOnline(ctx)" in code)
check("E4 失败会计一次 recordFailure",
      "recordFailure(prefs, plugin.pkg)" in code)
check("E5 sha256 校验仍在（防止打错包）",
      "tgz sha256 mismatch" in code)

print("\n" + "-" * 44)
print("通过 %d / 失败 %d / 跳过 %d" % (passed, failed, skipped))
sys.exit(1 if failed else 0)

#!/usr/bin/env python3
"""扩展中心多版本工具链 + 编译环境扩展的一致性探针。

守三类东西：
  A. catalog 的多版本数据本身合法（id 唯一、packages 非空、包名都在 APKINDEX 里）
  B. 数据层到 UI 的接线没断（resolvePackages / state / install 都带 versionId，
     UI 里真的传了 selectedVersion）
  C. 新增扩展的 iconRes 都有对应资源

这几类是「加一个版本忘了改一处」的典型残留：数据加了但 UI 没传，
表现是永远装默认版本，且界面上看不出任何异常。
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CAT = os.path.join(ROOT, "app", "src", "main", "assets", "extensions", "catalog.json")
CAT_ZH = os.path.join(ROOT, "app", "src", "main", "assets", "extensions",
                      "catalog.zh-rCN.json")
IDX = os.path.join(ROOT, "app", "src", "main", "assets", "linux", "apkindex-names.txt")
BRIDGE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                      "engine", "LinuxExtensionBridge.kt")
ACT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                   "ExtensionStoreActivity.kt")
DRAW = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

fails = []
def check(name, ok, hint=""):
    if ok:
        print(f"PASS  {name}")
    else:
        print(f"FAIL  {name}  -> {hint}")
        fails.append(name)

cat = json.load(open(CAT, encoding="utf-8"))
cat_zh = json.load(open(CAT_ZH, encoding="utf-8"))
items = {it["id"]: it for it in cat["items"]}
items_zh = {it["id"]: it for it in cat_zh["items"]}

print("== A. catalog 多版本数据 ==")
check("A1 catalogVersion 提到 3（加了 versions 字段）", cat.get("catalogVersion") == 3,
      f'got {cat.get("catalogVersion")}')

# APKINDEX 里的包名集合（去掉版本号）
have = set()
for line in open(IDX, encoding="utf-8", errors="replace"):
    m = re.match(r"^([a-zA-Z0-9_.+-]+)-[0-9]", line.strip())
    if m:
        have.add(m.group(1))

ver_items = {k: v for k, v in items.items() if v.get("versions")}
check("A2 至少 4 个扩展带多版本", len(ver_items) >= 4, f"got {len(ver_items)}")

all_ok = True
for eid, it in ver_items.items():
    vids = [v["id"] for v in it["versions"]]
    if len(vids) != len(set(vids)):
        all_ok = False
        print(f"      {eid}: 版本 id 重复 {vids}")
    for v in it["versions"]:
        if not v.get("packages"):
            all_ok = False
            print(f"      {eid}:{v['id']} packages 为空")
check("A3 版本 id 唯一且 packages 非空", all_ok)

miss = []
for eid, it in ver_items.items():
    for v in it["versions"]:
        for p in v["packages"]:
            if p not in have:
                miss.append(f"{eid}:{v['id']}:{p}")
check("A4 所有版本包名都在 Alpine 索引里", not miss, ",".join(miss[:5]))

check("A5 中英文 catalog 的扩展数一致", len(items) == len(items_zh),
      f'{len(items)} vs {len(items_zh)}')
check("A6 中英文 catalog 的版本表一致",
      {k: [v["id"] for v in v["versions"]] for k, v in ver_items.items()} ==
      {k: [v["id"] for v in v["versions"]]
       for k, v in items_zh.items() if v.get("versions")})

print("\n== B. 数据层到 UI 的接线 ==")
bridge = open(BRIDGE, encoding="utf-8").read()
act = open(ACT, encoding="utf-8").read()

check("B1 resolvePackages 存在且返回可空", "fun resolvePackages(" in bridge
      and "): List<String>?" in bridge)
check("B2 版本未知返回 null 而不是回落默认",
      re.search(r"firstOrNull \{ it\.id == versionId \} \?: return null", bridge) is not None,
      "必须 return null —— 回落会让「选了A装了B」显示成已装")
check("B3 install 接受 versionId", re.search(r"fun install\([^)]*versionId: String\? = null", bridge, re.S) is not None)
check("B4 remove 接受 versionId", re.search(r"fun remove\([^)]*versionId: String\? = null", bridge, re.S) is not None)
check("B5 state 接受 versionId", re.search(r"fun state\([^)]*versionId: String\? = null", bridge, re.S) is not None)
check("B6 installProgress 接受 versionId",
      re.search(r"fun installProgress\([^)]*versionId: String\? = null", bridge, re.S) is not None)
check("B7 install 里对未知版本显式报错（不是静默）",
      "unknown version:" in bridge)

check("B8 UI 有 selectedVersion()", "private fun selectedVersion(" in act)
# ★v1.7.12 契约变更：列表状态不再是「选中版本的装没装」，而是「条目里有没有
# 任何一版装齐」（ExtensionVersionPlan）。旧断言锁的是「把 selectedVersion 传给
# state()」，那正是 bug 本身 —— 纯 UI 动作（点胶囊）会连带改变三态，于是装好的
# 扩展又冒出 Install 按钮。现在要守两条新不变量：
#   (1) UI 的 stateOf() 走条目级清点，不再把 selectedVersion 塞进 state()
#   (2) 卸载/换版本定位的目标是「装着的那一版」，不是「选中的那一版」
check("B9 UI 状态改走条目级清点（不再由选中版本决定）",
      re.search(r"fun stateOf\(ext[^)]*\)[^{]*\{", act) is not None
      and "ExtensionVersionPlan.state(versionInventory(ext))" in act
      and re.search(r"LinuxExtensionBridge\.state\(this, ext, [^)]*selectedVersion", act) is None,
      "stateOf 必须用 ExtensionVersionPlan，且不能再把 selectedVersion 传给 state()")
# ★v1.7.12 二次修正：把「装着的那一版」表达成 String? 是个陷阱 —— 默认版的
# versionId 本来就是 null，于是「一版都没装」与「默认版装齐」压成同一个值。
# 真机 2026-11-13 实测：装了默认包集后点别的胶囊，`insId == null` 被当成「还没装」，
# 连卸载确认框都不弹就直接改选中，两版并存。现在必须用 VersionPresence 区分。
check("B9b 卸载定位到「装着的那一版」（用 presence，不用 String?）",
      "installedPresence(ext)" in act
      and re.search(r"fun linuxRemove\([^)]*versionId: String\? = null", act, re.S) is not None,
      "linuxRemove 必须接受版本参数并默认取装着的那一版，否则点了胶囊再卸会删错版本")
check("B9b2 「装着的那一版」不能用 String? 表达",
      "installedVersionId(ext)" not in act
      and re.search(r"private fun installedPresence\(ext[^)]*\): VersionPresence\?", act) is not None,
      "默认版装齐时 versionId 也是 null，与「一版都没装」同值；"
      "必须返回 VersionPresence 才能区分（否则换版本会静默跳过卸载确认）")
check("B9b3 换版本确认框带的是装着的版本，不是选中版本",
      "linuxRemove(ext, ins.versionId" in act,
      "删目标必须来自 presence，不能来自 selectedVersion（点胶囊那一刻它已经变了）")
check("B9c 换版本必须先卸载当前版本",
      "onPickVersion" in act and "ext_version_switch_msg" in act,
      "点另一版胶囊时要先弹「先卸谁再装谁」的确认框")
# 合并后 install() 签名是 (ctx, ext, versionId, onLine)，UI 侧把局部变量 ver
# 透传进 doLinuxInstall(ext, pkgs, ver, shownName) 再由它调桥接层。
check("B10 UI install 真的传了 ver",
      re.search(r"LinuxExtensionBridge\.install\(this, ext, versionId", act) is not None
      and re.search(r"doLinuxInstall\(ext, [^)]*ver[^)]*\)", act) is not None,
      "UI 必须把选中版本透传到 install()，否则装了默认版本还显示已装")
check("B11 选择持久化到 SharedPreferences（重启不丢）",
      '"ext_versions"' in act and "putString(ext.id, versionId)" in act)
# v1.5.5 契约变更：版本选择从「阻断式对话框」改为「行内胶囊」。
# 原断言锁的是 showVersionMenu 这个具体函数名，实现换成 refreshVersionBar 后
# 必然失败。但真正要守的不变量没变 ——
#   (1) 多版本条目在列表里就能选版本，不需要额外弹窗
#   (2) 主操作按钮不再先弹选择框，而是直接按当前选中版本执行
# 所以断言改成锁这两条行为，而不是锁某个函数名。
check("B12 多版本在行内选（不再用 showVersionMenu 阻断式对话框）",
      "showVersionMenu(" not in act and "refreshVersionBar(" in act)
check("B12b 主操作按钮直接执行（不再先弹版本选择）",
      re.search(r"if\s*\(linuxMode\)\s*\{[^}]*linuxRemove\(ext\)[^}]*else\s+linuxInstall\(ext\)", act, re.S) is not None)
check("B12c 版本胶囊标出已装状态（不只标选中）",
      "fully" in act and "partially" in act and "✓" in act)
# v1.7.12：状态行里的版本号也要跟着**事实**走 —— 旧断言锁的是 selectedVersion，
# 那会让「点一下别的版本」把状态行的 v 号也换掉（明明没装那个版本）。
check("B13 已装状态显示实际装着的那一版的包版本",
      re.search(r"ExtensionVersionPlan\.installed\(versionInventory\(ext\), selectedVersion\(ext\)\)", act)
      is not None)
# alpinePackagesOf 已被「按发行版映射」的 packagesOf(packages, family) 取代
# （双发行版支持），一键装齐的语义不变：默认版本 + 所有可选版本都算进去。
check("B14 一键装齐覆盖所有版本",
      "for (v in e.versions) out += packagesOf(v.packages, family)" in bridge)

print("\n== C. 图标资源齐全 ==")
badres = []
for eid, it in items.items():
    r = it.get("iconRes", "")
    if not r:
        continue
    if not (os.path.exists(os.path.join(DRAW, r + ".xml")) or
            os.path.exists(os.path.join(DRAW, r + ".png")) or
            os.path.exists(os.path.join(DRAW, "ic_ext_" + r + ".xml"))):
        badres.append(f"{eid}:{r}")
check("C1 所有 iconRes 都有对应 drawable", not badres, ",".join(badres[:5]))

print("\n== D. 反例守卫（防止探针自身退化）==")
# 去掉 resolvePackages 的 null 返回后 B2 必须变红 —— 这里只做静态确认那条
# 断言确实是「return null」而不是「?: 默认值」
check("D1 B2 没有写成 Elvis 回落",
      "?: alpinePackagesOf(ext.packages)" not in bridge.split("fun state(")[0].split("fun resolvePackages(")[-1][:400])

print(f"\n{'='*50}")
if fails:
    print(f"失败 {len(fails)}: {', '.join(fails)}")
    sys.exit(1)
print("全部通过")

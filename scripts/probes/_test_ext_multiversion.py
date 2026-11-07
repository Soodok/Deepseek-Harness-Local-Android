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
check("B9 UI state 真的传了 selectedVersion",
      "LinuxExtensionBridge.state(this, ext, linuxInstalled, selectedVersion(ext))" in act)
check("B10 UI install 真的传了 ver",
      "LinuxExtensionBridge.install(this, ext, ver)" in act)
check("B11 选择持久化到 SharedPreferences（重启不丢）",
      '"ext_versions"' in act and "putString(ext.id, versionId)" in act)
check("B12 多版本走对话框而不是直接装",
      "showVersionMenu(" in act and "ext.versions.size > 1" in act)
check("B13 已装状态显示选中版本的包版本",
      "resolvePackages(ext, selectedVersion(ext))" in act)
check("B14 一键装齐覆盖所有版本",
      "for (v in e.versions) out += alpinePackagesOf(v.packages)" in bridge)

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

#!/usr/bin/env python3
"""守「权限中心两张子页各显示各的内容」。

## 为什么需要

2026-11-09 用户反馈：「运行身份和系统能力都有问题啊，里面都包含了权限管理设置」。

实际是**运行身份页把系统能力页的内容也显示了一遍**。根因不在文案，在布局层级：

`activity_permission_center.xml` 里三个卡片（groupEngine / groupCaps /
groupDeviceCaps）都是根 ScrollView 的**直接孩子**，互为兄弟。而 onCreate 只藏了
`groupCaps`，从没藏过 `groupDeviceCaps` —— 于是「运行身份」页上除了三行运行身份，
还压着 28 个 AI 能力磁贴 + 总览卡（0 / 28）+ 批量授权 + 权限清单入口，
和「系统能力」页看起来几乎一模一样。

这类错误不崩、不报编译错、不触发任何既有探针 —— 只有当人在两个页面之间来回点
才看得出来。所以本探针把「三层可见性表决」固定下来。

## 守什么

A. 布局事实：三个卡片必须是同一层级的兄弟（不是父子）。这条不成立时，
   「只藏 groupCaps 就够」的旧逻辑反而变正确了 —— 所以先钉住布局结构。
B. 分派逻辑：GROUP_CAPS 时藏 groupEngine；GROUP_ENGINE 时**同时**藏 groupCaps
   与 groupDeviceCaps。
C. 反向自检：确认 B 的断言有判别力（旧实现必须判不合格）。

跑法：python scripts/probes/_test_permission_center_groups.py
"""
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LAYOUT = os.path.join(ROOT, "app/src/main/res/layout/activity_permission_center.xml")
ACT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/PermissionCenterActivity.kt")

NS = "{http://schemas.android.com/apk/res/android}"
checks = 0
failed = 0

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   " + msg)
    else:
        failed += 1
        print("  FAIL " + msg)


def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()


def walk(e, depth=0, acc=None):
    acc = acc if acc is not None else []
    i = e.get(NS + "id")
    if i:
        acc.append((depth, i.replace("@+id/", "")))
    for c in e:
        walk(c, depth + 1, acc)
    return acc


print("[A] 布局事实：三张卡片是兄弟，不是父子")
tree = ET.parse(LAYOUT)
nodes = walk(tree.getroot())
depth = {n: d for d, n in nodes}
for name in ("groupEngine", "groupCaps", "groupDeviceCaps"):
    ok(name in depth, f"布局里有 {name}")
if all(n in depth for n in ("groupEngine", "groupCaps", "groupDeviceCaps")):
    ds = {n: depth[n] for n in ("groupEngine", "groupCaps", "groupDeviceCaps")}
    ok(len(set(ds.values())) == 1,
       f"三张卡片同层级（实测深度 {ds}）—— 若不同层，隐藏规则要跟着改")
    # ★内容归属必须用「祖先链」判，不能用深度比大小。
    # 首次修这个 bug 时我用的是 depth > groupDeviceCaps 的深度 —— 那只能证明
    # 「在它下面」，无法证明「在它里面」。实测就漏了：输入监控、键盘事件流、
    # Linux 域、位置与相机、批量授权卡这五块当时是 MaxWidthLinearLayout 的直接
    # 孩子（groupDeviceCaps 的**兄弟**），深度比 groupDeviceCaps 深，断言全绿，
    # 而它们在运行身份页上照样显示。
    Anest = {}
    def walk_paths(e, path=None):
        path = path or []
        i = (e.get(NS + "id") or "").replace("@+id/", "")
        p = path + ([i] if i else [])
        if i:
            Anest[i] = p
        for c in e:
            walk_paths(c, p)
    walk_paths(tree.getroot())

    def inside(child, container):
        return container in Anest.get(child, [])

    for r in ("rowCapStatus", "rowCapSensor", "rowCapInputwatch", "rowCapKeywatch",
              "rowCapLinux", "rowCapPackages", "rowCapLocation", "rowCapCamera",
              "rowGrantAll", "rowPermList", "txtCapTotal"):
        ok(inside(r, "groupDeviceCaps"), f"{r} 在 groupDeviceCaps 里面（不是兄弟节点）")
    for r in ("rowAccess", "rowStorage", "rowNotif", "rowMic"):
        ok(inside(r, "groupCaps"), f"{r} 在 groupCaps 里面")
    for r in ("rowPriv", "rowRootStatus", "rowShizukuStatus"):
        ok(inside(r, "groupEngine"), f"{r} 在 groupEngine 里面")
    # 反向：AI 能力磁贴不能挂在 groupEngine 或 groupCaps 下
    for r in ("rowCapStatus", "rowGrantAll"):
        ok(not inside(r, "groupEngine") and not inside(r, "groupCaps"),
           f"{r} 不属于 groupEngine / groupCaps")

print("\n[B] 分派逻辑：两个分组各藏各的")
src = read(ACT)
# 只保留 onCreate 里那段可见性表决，避免匹配到别处的 group 变量
m = re.search(r"val engineCard = findViewById<View>\(R\.id\.groupEngine\)(.*?)\n\n", src, re.S)
ok(m is not None, "找到可见性分派段")
seg = m.group(1) if m else ""

ok(re.search(r'val deviceCapsCard = findViewById<View>\(R\.id\.groupDeviceCaps\)', seg) is not None,
   "取了 groupDeviceCaps 的引用（否则根本不可能藏它）")
ok(re.search(r'if\s*\(\s*group\s*==\s*GROUP_CAPS\s*\)', seg) is not None,
   "有 GROUP_CAPS 分支")
# GROUP_ENGINE 分支里必须同时藏 caps 与 deviceCaps
else_branch = seg.split("} else {")[-1] if "} else {" in seg else ""
ok("capsCard.visibility = View.GONE" in else_branch,
   "运行身份页隐藏了 groupCaps")
ok("deviceCapsCard.visibility = View.GONE" in else_branch,
   "运行身份页隐藏了 groupDeviceCaps —— 这条缺失就是用户报的「都包含了权限管理设置」")

print("\n[C] 反例自检：旧实现必须判不合格")
old_snippet = """
        val engineCard = findViewById<View>(R.id.groupEngine)
        val capsCard = findViewById<View>(R.id.groupCaps)
        if (group == GROUP_CAPS) {
            engineCard.visibility = View.GONE
        } else {
            findViewById<View>(R.id.txtGroupEngineSub).visibility = View.GONE
        }
        if (group == GROUP_ENGINE) {
            capsCard.visibility = View.GONE
        } else {
            findViewById<View>(R.id.txtGroupCapsSub).visibility = View.GONE
        }

"""
m_old = re.search(r"val engineCard = findViewById<View>\(R\.id\.groupEngine\)(.*?)\n\n", old_snippet, re.S)
old_seg = m_old.group(1) if m_old else ""
old_else = old_seg.split("} else {")[-1] if "} else {" in old_seg else ""
ok(old_seg != "", "自检：旧片段能被同样的正则切出来")
ok("deviceCapsCard.visibility = View.GONE" not in old_else,
   "自检：旧片段不含 deviceCaps 隐藏（说明 B 的断言有判别力，旧实现会被判红）")

print(f"\nchecks={checks} failed={failed}")
sys.exit(1 if failed else 0)

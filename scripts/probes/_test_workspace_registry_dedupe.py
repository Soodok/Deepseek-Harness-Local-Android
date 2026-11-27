#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「工作区注册表同一个 path 被多个 workspace 认领」（真机事故 2026-11-27）。

## 现场

上游 `dsh-workspace` 的 `WorkspaceRegistry.validateStoredState` 有一条硬约束：
同一个 `path` 只能被一个 workspace 认领。违反时它 `throw`：

    workspace domain is inconsistent: path '/storage/emulated/0/-云更新网盘'
      is claimed by both workspace '5be6e911-...' and workspace '78455992-...'

而它抛在 `[cordis.init]`（`lib/index.js:386`）里，于是**整条依赖链塌掉**：

    workspace                 -> failed
    storage-domain            -> failed
    session-persistence-jsonl -> failed
    workspaceRegistry         -> 缺失
    session-controller        -> pending (waiting for service: workspaceRegistry)
    workspace-controller      -> pending
    directoryPickerController -> 缺失
    ui-deliverables           -> pending (waiting for service: sessionController)

用户看到「会话全都不见了 / 选工作区提示 unavailable / 插件页一片黄」，而磁盘上
128 个会话文件一个都没少 —— 坏的只是这张登记表。

## 污点从哪来

1. `DataExport.mergeRegistry`（备份恢复）按 `workspaceIds` 取**并集**、`tables`
   里「缺失的才加」。于是「本机的 A」与「备份里的 B」即使 path 完全相同也会
   两条都留 —— 恢复当时看起来成功，**下次启动才炸**。
2. `ChatRecovery.recover`（会话恢复）按 cwd 找 workspace，key 用 canonicalPath；
   软链/大小写/尾斜杠任一不一致就为同一个目录新建一条。

## 本探针守什么

A 段：`WorkspaceDedupe` 的合并规则（赢家=顺序表靠前、会话取并集、时间戳取极值、
      幂等）。
B 段：**接线必须真的存在** —— 两个污点源头各调一次，且 `healWorkspaceRegistry`
      里 dedupe 必须在「按 basename 兜底」**之前**（顺序反了会让已合并掉的 id
      又被兜底逻辑摸到）。
C 段：上游约束不许被「绕过去」—— 不允许把 path 改写成别的值来躲校验（那会让
      引擎在错误目录里建工作区）。判据：合并后赢家的 path 原样不变。
D 段：JVM 断言真的被调起来（定义了不调 = 一条都没跑）。

跑法：python scripts/probes/_test_workspace_registry_dedupe.py
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEDUPE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/WorkspaceDedupe.kt")
ENGINE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
EXPORT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/DataExport.kt")
JVM = os.path.join(ROOT, "scripts/jvm/BehaviourTest.kt")
SCRIPT = os.path.join(ROOT, "scripts/run-a11y-jvm-test.sh")

fails = []


def chk(cond, msg):
    if not cond:
        fails.append(msg)
        print("FAIL  " + msg)
    else:
        print("ok    " + msg)


def read(p):
    return io.open(p, encoding="utf-8").read()


def strip_comments(s):
    """剥 Kotlin 的注释：`/* */` 整段、`//` 行尾。

    ★必须认字符串字面量：`s.contains("//")` 里的 `//` 是**代码**不是注释，
    朴素按 `//` 切会把这一行砍成 `while (s.contains("`，于是「压缩重复斜杠」
    这条断言假红（本探针第一次跑就踩了这个）。
    也要放过 `https://`（前一个字符是 `:`）。
    """
    out = []
    i, n = 0, len(s)
    in_str = False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if c == chr(92) and i + 1 < n:      # 转义：" 之类
                out.append(s[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == '/' and i + 1 < n and s[i + 1] == '*':
            j = s.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '/' and i + 1 < n and s[i + 1] == '/':
            if out and out[-1] == ':':          # https:// 之类
                out.append(c)
                out.append(s[i + 1])
                i += 2
                continue
            j = s.find(chr(10), i)
            i = n if j < 0 else j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


dedupe = read(DEDUPE)
dedupe_nc = strip_comments(dedupe)
engine_nc = strip_comments(read(ENGINE))
export_nc = strip_comments(read(EXPORT))
jvm = read(JVM)
script = read(SCRIPT)

print("[A] WorkspaceDedupe 的合并规则（纯函数，JVM 已实跑）")

chk(re.search(r"object WorkspaceDedupe\b", dedupe_nc) is not None,
    "A1  WorkspaceDedupe 存在（纯 object，不碰 android/org.json）")
# ★判据必须是「真的 import 语句」，不能全文找关键词 ——
#   注释里写着「不碰 android / org.json」会把自己判红（本探针第一次就这么假红）。
chk(re.search(r"^import\s+android", dedupe_nc, re.M) is None
    and re.search(r"^import\s+org\.json", dedupe_nc, re.M) is None,
    "A1b 该文件不 import android.* / org.json（否则进不了宿主 JVM 断言）")
chk(re.search(r"fun plan\(", dedupe_nc) is not None, "A2  plan() 存在")
chk(re.search(r"data class Plan\(", dedupe_nc) is not None, "A2b Plan 是数据类")
chk(re.search(r"data class Merge\(", dedupe_nc) is not None, "A2c Merge 是数据类")

# 赢家 = 顺序表靠前。判据查「按 order 建 visit」这一步真的存在且没被反转。
i_visit = dedupe_nc.find("for (id in order) if (id in byId && id !in visit) visit += id")
chk(i_visit >= 0, "A3  visit 顺序按 order 正向累加（赢家=顺序表靠前那个）")
chk("order.reversed()" not in dedupe_nc,
    "A3b 不许用 order.reversed()（那会让靠后的当赢家，打乱用户看到的顺序）")

# 会话取并集且去重
chk("toMutableSet()" in dedupe_nc, "A4  用集合判重（会话并集去重）")
chk(re.search(r"it\.isNotBlank\(\) && existing\.add\(it\)", dedupe_nc) is not None,
    "A4b 只有「不在集合里」的才追加（重复 id 不会出现两次）")
chk("cur.sessionIds + extra" in dedupe_nc,
    "A4c 赢家原有会话排在前面（引擎按数组顺序展示，最近的在最前）")

# 被并的必须真被剔除
chk(re.search(r"dropped \+= id", dedupe_nc) is not None,
    "A5  被并掉的 id 记进 dropped（不记就等于只并内容不删记录）")
chk(re.search(r"order\.filter \{ it !in dropped \}", dedupe_nc) is not None,
    "A5b 顺序表按 dropped 过滤（否则顺序表还指着已删的记录）")

# 时间戳取极值
chk("fun earlier(" in dedupe_nc and "fun later(" in dedupe_nc,
    "A6  earlier()/later() 都存在")
chk(re.search(r"createdAt = earlier\(", dedupe_nc) is not None,
    "A6b createdAt 取更早的（同一个目录，历史起点要合起来看）")
chk(re.search(r"updatedAt = later\(", dedupe_nc) is not None, "A6c updatedAt 取更晚的")

# path 规整：不许 canonicalPath
chk("fun normPath(" in dedupe_nc, "A7  normPath() 存在")
chk("canonicalPath" not in dedupe_nc,
    "A7b normPath 不用 canonicalPath（宿主上该目录此刻可能已不存在，会抛/返回相对量）")
bs = chr(92)
chk("replace(" + repr(bs)[1:-1] + "" in dedupe_nc or (bs in dedupe_nc and "/" in dedupe_nc),
    "A7c 反斜杠归一成斜杠（Windows 造的备份包里可能是反斜杠）")
chk("endsWith(" in dedupe_nc and "dropLast(1)" in dedupe_nc, "A7d 去尾斜杠")
chk('contains("//")' in dedupe_nc and 'replace("//", "/")' in dedupe_nc,
    "A7e 压缩重复斜杠")

chk("val changed: Boolean get() = merges.isNotEmpty()" in dedupe_nc,
    "A8  Plan.changed 由 merges 是否为空推出（干净表必须报 false，避免每次开机白写）")

print()
print("[B] 两个污点源头都要接上（否则修了等于没修）")

chk("dedupeWorkspaceRegistry" in engine_nc, "B1  EngineConfig 里有 dedupeWorkspaceRegistry")
i_heal = engine_nc.find("private fun healWorkspaceRegistry")
chk(i_heal >= 0, "B1b healWorkspaceRegistry 存在")
if i_heal >= 0:
    body = engine_nc[i_heal:i_heal + 2500]
    i_call = body.find("dedupeWorkspaceRegistry(root, tables)")
    chk(i_call >= 0, "B1c healWorkspaceRegistry 里真的调了 dedupeWorkspaceRegistry")
    i_base = body.find("val replacement = File(wsRoot, name)")
    chk(i_call >= 0 and i_base >= 0 and i_call < i_base,
        "B1d dedupe 在 basename 兜底之前（顺序反了会让已合并掉的 id 又被兜底逻辑摸到）")

i_dedupe = engine_nc.find("private fun dedupeWorkspaceRegistry")
if i_dedupe >= 0:
    seg = engine_nc[i_dedupe:i_dedupe + 3000]
    chk('ws.put("path", r.path)' in seg,
        "B1e 合并后写回的 path 就是原 path（绝不改写路径来躲上游校验）")
    chk("tables.remove(m.loser)" in seg,
        "B1f 被并的 id 真的从 tables 里删掉")
    chk('global.put("workspaceIds"' in seg,
        "B1g 顺序表也重写（只删 tables 不删 order 会撞 order references missing workspace）")
else:
    chk(False, "B1e dedupeWorkspaceRegistry 定义找不到")

chk("dedupeMergedRegistry" in export_nc, "B2  DataExport 里有 dedupeMergedRegistry")
i_merge = export_nc.find("private fun mergeRegistry")
chk(i_merge >= 0, "B2b mergeRegistry 存在")
if i_merge >= 0:
    body = export_nc[i_merge:i_merge + 3500]
    # ★必须精确到**带实参的调用**：只找 `dedupeMergedRegistry(` 会把紧跟其后的
    #   函数**定义**也命中，于是删掉调用照样绿（本探针第一次就这么假绿）。
    i_call = body.find("dedupeMergedRegistry(current")
    i_write = body.find("targetFile.writeText(")
    chk(i_call >= 0, "B2c mergeRegistry 里真的调了 dedupeMergedRegistry")
    chk(i_call >= 0 and i_write >= 0 and i_call < i_write,
        "B2d dedupe 在写盘之前（先写后改会留下一次坏状态）")
else:
    chk(False, "B2c mergeRegistry 定义找不到")

print()
print("[C] 规则只有一处真相，且不许靠「改 path」绕过上游约束")
chk("WorkspaceDedupe.plan(" in engine_nc and "WorkspaceDedupe.plan(" in export_nc,
    "C1  两处都走同一个纯函数")
chk(dedupe_nc.count("object WorkspaceDedupe") == 1,
    "C1b 只有一份实现（复制粘贴两份必然漂移）")

print()
print("[D] JVM 与脚本接线")
chk("assertWorkspaceDedupe" in jvm, "D1  BehaviourTest 里有 assertWorkspaceDedupe")
chk("assertWorkspaceDedupe()" in jvm, "D1b 它真的被 main 调起来（定义了不调 = 一条断言都没跑）")
chk("import app.dsh.mobile.engine.WorkspaceDedupe" in jvm, "D1c 已 import")
chk("WorkspaceDedupe.kt" in script, "D2  run-a11y-jvm-test.sh 的 cp 清单含 WorkspaceDedupe.kt")
chk('"$(win "$WORK/src/WorkspaceDedupe.kt")"' in script,
    "D2b 编译命令的显式文件列表也含它（只加 cp 不加编译清单 = 编不进去）")

print()
print("[E] 应急脚本 scripts/fix-workspace-registry.py（不改 APK 也能救回来）")
FIX = os.path.join(ROOT, "scripts/fix-workspace-registry.py")
chk(os.path.isfile(FIX), "E1  应急脚本存在")
if os.path.isfile(FIX):
    fx = read(FIX)
    chk("def plan(" in fx and "def norm_path(" in fx,
        "E2  脚本实现 plan()/norm_path()（与 WorkspaceDedupe 同一套规则）")
    chk('cur["sessionIds"] = (cur.get("sessionIds") or []) + extra' in fx,
        "E2b 会话取并集（赢家在前）")
    chk("root[\"tables\"][\"workspaces\"].pop(l, None)" in fx,
        "E2c 被并的 id 从 tables 删掉")
    chk('global_["workspaceIds"] = new_order' in fx,
        "E2d 顺序表同步重写（只删 tables 会撞 order references missing workspace）")
    chk("--apply" in fx and "dry-run" in fx,
        "E3  默认 dry-run，必须显式 --apply 才写盘")
    chk("before-dedupe-" in fx,
        "E3b 写盘前留带时间戳的备份")
    chk("os.replace(tmp, a.file)" in fx,
        "E3c 原子替换（半截 JSON 会让引擎彻底读不了注册表）")
    chk("force-stop" in fx,
        "E3d 文档写明必须先 force-stop（引擎把表当独占文件，运行中改会被内存态覆盖）")

print()
if fails:
    print("FAILED (%d)" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("All workspace-registry dedupe checks passed.")

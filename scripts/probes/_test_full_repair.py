#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「修复途径收敛成一条」（2026-11-27 用户诉求）。

## 现场

到 v1.8.x，用户/AI 想「修一下」至少有 5 类互不相通的入口：

| 位置 | 形态 | 谁能用 |
|---|---|---|
| `EngineSupervisor` 启动链 | 18 处自愈，自动跑 | 只有重启时 |
| `MaintenanceActivity` | 14 个按钮 | 用户手点 |
| `POST /repair/apply` | 9 种 FixKind，受归因白名单约束 | 引擎内 AI |
| `ExtensionStoreActivity` | 「一键修复」只修扩展 | 用户手点 |
| `SelfCheck` | 12 类检查，只报告不修 | 用户手点 |
| `scripts/fix-workspace-registry.py` | 离线脚本 | 电脑 + adb |

后果：用户不知道该点哪个；AI 只有 `/repair/apply` 而它的白名单常把真正
需要的动作挡成 `409`；每个入口各自实现一遍「我做完了」，口径还不一样。

`FullRepair` 把它们按固定顺序串成一条，判定与动作**复用现有实现**，
自己不写新的文件操作逻辑。本探针守四件事：

A 段：动作全集覆盖 —— `stepIds()` 里每个 id 在实现里真的存在对应分支，
      且实现里的分支数与 `stepIds()` 完全一致（不多不少，防「加了个 id 却没实现」）。
B 段：顺序 —— 属主必须在最前、体检在所有动作之后、注册表在插件之前。
      顺序错了会白做（属主不对时后面每步都动不了 dsh-home）。
C 段：容错 —— 每步都包 runCatching，单步失败不影响其余；不许外层再包一层把异常吞掉。
D 段：接线 —— `/repair/full` 的 GET/POST 都注册了、`confirm` 必填、
      MaintenanceActivity 有入口、EndpointCatalog 有目录条目（否则 I1 漂移红）。
E 段：不再新增第五条途径 —— `RepairActions.runAll()` 这个 0 调用者的死代码
      要么删、要么接进 FullRepair（不许再留一个没人用的入口）。

跑法：python scripts/probes/_test_full_repair.py
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FR = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/FullRepair.kt")
BR = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt")
MA = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MaintenanceActivity.kt")
EC = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt")

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
    out, i, n, in_str = [], 0, len(s), False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if c == chr(92) and i + 1 < n:
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
            if out and out[-1] == ':':
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


fr_raw = read(FR)
fr = strip_comments(fr_raw)

print("[A] 动作全集：stepIds() 与实现分支一一对应，不多不少")

# 从 stepIds() 里抓字符串
m = re.search(r"fun stepIds\(.*?\): List<String> = buildList \{([\s\S]*?)\n    \}", fr)
if not m:
    m = re.search(r"fun stepIds\([\s\S]{0,600?\}", fr)
seg = m.group(1) if m else ""
ids_declared = sorted(set(re.findall(r'"([a-z][a-z0-9-]*)"', seg)))
chk(len(ids_declared) >= 14, "A1  stepIds() 至少 14 个内置步骤（实际 %d）" % len(ids_declared))

# 实现里 runStep("id") 首次出现即「有分支」
ids_impl = sorted(set(re.findall(r'runStep\(\s*"([a-z][a-z0-9-]*)"', fr)))
declared_set = set(ids_declared) | {"deep-clean"}
impl_set = set(ids_impl) | {"deep-clean"}
missing = sorted(declared_set - impl_set)
extra = sorted(impl_set - declared_set)
chk(not missing, "A2  stepIds() 里的每个 id 都有实现分支（缺: %s）" % (missing or "none"))
chk(not extra, "A3  实现里没有 stepIds() 之外的孤儿分支（多: %s）" % (extra or "none"))

# 关键动作必须从既有实现复用，不许自己写新逻辑
reuse = {
    "Privilege.fixHomeOwnership": "属主修复复用 Privilege",
    "StaleLockHealer.heal": "陈旧锁复用 StaleLockHealer",
    "EngineConfig.healWorkspaceRegistry": "注册表复用 EngineConfig",
    "SessionTrash.migrateLegacy": "回收站复用 SessionTrash",
    "OrphanReaper.reap": "孤儿进程复用 OrphanReaper",
    "ProfileGuardian": "隔离 overlay 复用 ProfileGuardian",
    "PluginKillSwitch.enforceBeforeBoot": "应急开关复用 PluginKillSwitch",
    "FrontendWarmup.healInstalled": "前端复用 FrontendWarmup",
    "ProfilePluginProvisioner.restoreMissingStaged": "暂存 tgz 复用 ProfilePluginProvisioner",
    "LogJanitor.sweep": "日志复用 LogJanitor",
}
for needle, why in reuse.items():
    chk(needle in fr, "A4  %s" % why)

# 不许自己 new File(...).delete() 之类绕过既有实现
chk("deleteRecursively" not in fr,
    "A5  FullRepair 里不直接删目录（删东西一律交给既有 reaper/resetter）")
chk("ProcessBuilder" not in fr,
    "A5b 不自己起子进程（chown/find 等交给 Privilege）")

print()
print("[A2] 返回值语义：不许把「没改写」当成「失败」")

# 现场（模拟器 2026-11-27 实测）：healPnpmWorkspace 的返回值是「有没有真的改写
# 文件」，不是「工作区是否健康」。首版把它当健康判据，false 一律记 FAILED，
# 于是干净机器上弹出 `pnpm=FAILED(heal returned false)` —— 用户看到假失败。
# 判据：false 分支必须落到 CLEAN（或 SKIPPED），绝不能是 FAILED。
for fn_id in ("pnpm",):
    blk = re.search(r'runStep\("' + fn_id + r'"[\s\S]{0,900}', fr)
    assert blk is not None, "找不到 step " + fn_id
    seg = blk.group(0)
    has_failed_branch = re.search(r'Status\.FAILED', seg) is not None
    chk(not has_failed_branch,
        "A6  %s 步里没有 Status.FAILED 分支（「没改写」是常态，不是失败）" % fn_id)
chk('Status.SKIPPED, "no pnpm-workspace.yaml' in fr,
    "A6b pnpm 步对「profile 还没装」显式 SKIPPED（不是 FAILED）")
chk("dropped obsolete pnpm 11 build whitelist keys" in fr,
    "A6c 真改写时记为 FIXED 并说明改了什么")

# shebangs / corepack 两个 heal 也必须能分辨「改了」与「没坏」
chk('engine/bin")' in fr and "rewrote $n Termux shebang(s)" in fr,
    "A7  shebangs 步按改动条数区分 FIXED / CLEAN")
chk("restored $n clobbered entry" in fr,
    "A7b corepack 步按改动条数区分 FIXED / CLEAN")

print()
print("[B] 顺序：属主最先、体检在最后、注册表在插件之前")

i_own = fr.find('runStep("ownership"')
i_lock = fr.find('runStep("stale-locks"')
i_reg = fr.find('runStep("workspace-registry"')
i_pnpm = fr.find('runStep("pnpm"')
i_selfbefore = fr.find("SelfCheck.run(ctx)")
# after 体检是第二次出现
i_selfafter = fr.find("SelfCheck.run(ctx)", i_selfbefore + 1) if i_selfbefore >= 0 else -1
i_last = fr.find('runStep("kill-switch"')

chk(i_own >= 0 and i_own == min(x for x in (i_own, i_lock, i_reg, i_pnpm, i_last) if x >= 0),
    "B1  属主修复是第一步（后面每步都要读写 dsh-home，属主不对全废）")
chk(i_lock >= 0 and i_own >= 0 and i_own < i_lock,
    "B2  属主在陈旧锁之前")
chk(i_reg >= 0 and i_lock >= 0 and i_lock < i_reg,
    "B3  陈旧锁在注册表之前（lock 还在时改注册表可能撞 EACCES）")
chk(i_pnpm >= 0 and i_reg >= 0 and i_reg < i_pnpm,
    "B4  注册表在插件画像之前（脏注册表会让引擎起不来，插件读了白读）")
chk(i_selfbefore >= 0 and i_own >= 0 and i_own > i_selfbefore,
    "B5  修复前体检在第一个动作之前")
chk(i_selfafter >= 0 and i_last >= 0 and i_selfafter > i_last,
    "B6  修复后体检在所有动作之后（给用户的必须是修完的真实状态）")
chk(i_selfbefore >= 0 and i_selfafter > i_selfbefore,
    "B7  体检出现两次（before / after），不是只跑一次")

print()
print("[C] 容错：每步包 runCatching，单步失败不中断整轮")

n_steps = len(re.findall(r'steps \+= runStep\(', fr))
chk(n_steps >= 14, "C1  至少 14 处 `steps += runStep(...)`（实际 %d）" % n_steps)
chk("private inline fun runStep" in fr, "C1b runStep 是包装函数（唯一入口，不能各步自己 try）")
m_body = re.search(r"private inline fun runStep[\s\S]{0,400}", fr)
chk(m_body is not None and "runCatching" in (m_body.group(0) if m_body else ""),
    "C2  runStep 内部用 runCatching（单步异常折算成 FAILED）")
chk(m_body is not None and "getOrElse" in (m_body.group(0) if m_body else ""),
    "C2b runStep 用 getOrElse 兜底（不是 getOrThrow 让异常上浮）")
chk(n_steps >= 14 and fr.count("runStep(") >= n_steps,
    "C3  没有哪一步绕过 runStep 直接裸调（否则一步炸掉整轮）")

# 主函数不许整体包一层 runCatching 把异常吞掉
i_run = fr.find("fun run(")
i_end = fr.find("\n    private inline fun runStep", i_run) if i_run >= 0 else -1
main_body = fr[i_run:i_end] if (i_run >= 0 and i_end > i_run) else ""
chk("runCatching" not in main_body or main_body.count("runCatching") <= 3,
    "C4  run() 主体不整体包 runCatching（只允许包两次据检 + 单步）")

# 失败必须如实记，不许静默吞成 CLEAN
chk("Status.FAILED" in fr, "C5  有 FAILED 状态（失败不许伪装成 CLEAN）")
chk(re.search(r'stillBad -> Step', fr) is not None,
    "C5b 属主修完复检仍坏时如实报 FAILED（不是报 CLEAN）")

print()
print("[D] 接线：端点 + UI + 目录，三处都在")

br = strip_comments(read(BR))
chk('"/repair/full"' in br or '"GET /repair/full"' in br,
    "D1  AgentBridge 注册了 /repair/full 路由")
chk(('"GET /repair/full"' in br and '"POST /repair/full"' in br),
    "D1b GET 与 POST 都注册了（不是只挂一个）")
chk('optBoolean("confirm"' in br,
    "D2  真跑必须读 confirm 字段（防误触把修复跑在用户没预期的时候）")
chk("FullRepair.run(" in br, "D3  POST 真的调 FullRepair.run")
chk("FullRepair.stepIds(" in br, "D3b GET 用 stepIds() 预览（不写盘）")
chk("FullRepair.detail(" in br or "FullRepair.summarize(" in br,
    "D4  响应里带逐条结果（用户/AI 都要能看见哪步修了什么）")
chk("207" in br, "D5  部分失败返回 207（不是笼统 500）")

ma = strip_comments(read(MA))
chk("rowFullRepair" in ma, "D6  MaintenanceActivity 有「一键全面修复」入口")
chk("FullRepair.run(" in ma, "D6b UI 真的调 FullRepair.run")
chk("full-repair-last.txt" in ma or "fullRepairLast" in ma,
    "D7  结果落盘（崩溃/重启后还能看上次修了什么）")
chk("restartNeeded" in ma or "needsRestartFirst" in ma,
    "D8  UI 处理 restartNeeded（不静默丢「要重启」这个信息）")

ec = strip_comments(read(EC))
chk(ec.count('"repair/full"') >= 2,
    "D9  EndpointCatalog 有 GET/POST 两条目录条目（否则 I1 漂移检测红）")
chk("repair/full" in ec, "D9b 目录条目写的是 repair/full（路径与路由一致）")

print()
print("[E] 不再新增第五条途径")

ra_path = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/RepairActions.kt")
# runAll 是 2026-11-27 清掉的死代码（全仓 0 调用者）。现在两条合法出路：
#   ① 完全删掉（约定）；② 真接进 FullRepair。不许原地留一个没人用的 runAll。
if os.path.exists(ra_path):
    ra = strip_comments(read(ra_path))
    chk("fun runAll" not in ra,
        "E1  RepairActions 里已没有 runAll 死代码（0 调用者的入口已清）")
    # 其余方法（run/Outcome）仍被 /repair/apply 与 FailureDialog 使用 —— 那些是活的
    chk("fun run(" in ra, "E1c RepairActions.run 仍在（/repair/apply 与 FailureDialog 要用）")
    callers = 0
    for dirpath, _dirs, files in os.walk(os.path.join(ROOT, "app/src/main/java")):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            if os.path.abspath(p) == os.path.abspath(ra_path):
                continue
            if "RepairActions.runAll(" in strip_comments(read(p)):
                callers += 1
    chk(callers == 0,
        "E1b 全仓没有对 runAll 的引用（删干净了，没有半截残留；当前 %d）" % callers)
else:
    chk(True, "E1  RepairActions.kt 已不存在（整个文件已删）")

# FullRepair 不许成为第六套并行逻辑：它必须只编排、不复制动作
chk("SelfCheck" in fr, "E2  体检复用 SelfCheck（不自己写检查）")
chk(len(re.findall(r"Log\.(i|w)\(TAG", fr)) >= 2, "E3  有日志输出（诊断可查）")

print()
if fails:
    print("FAILED (%d)" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("All full-repair checks passed.")

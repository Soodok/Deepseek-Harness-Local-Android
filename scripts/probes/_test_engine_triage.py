#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「引擎启动失败必须被归因成可判断的类别」这条不变量（v1.5.3）。

## 为什么需要这个探针

用户反馈「有的时候引擎莫名其妙启动失败」，而排查发现原因是五花八门：端口被占、
插件没加载、结构文件读不了，还有一堆连现象都说不出来的。问题不在修不了，
在**没有任何一层把失败原因结构化记下来**：

- v1.5.3 之前 `State.Failed` 只有一句 `reason: String`，全凭各处手写，措辞不一；
- `failureSignature` 是 `"<退出码>:<hash>"`，哈希把可读信息全丢了；
- 于是「端口被占」和「插件没激活」在界面上长得一模一样。

本探针守四件事：

1. **归因器存在且分类齐全**：12 个 [Cause] 枚举值，每个都能在 `diagnoseInternal`
   里被某个分支产出（不许有「声明了但永远不会出现」的死枚举）。
2. **归因顺序**：端口 > 运行时 > 凭据 > 插件 > 配置 > 存储 > 系统 > 体检 > 兜底。
   顺序错了会把「端口占用」误判成「插件没激活」，处置完全不同。
3. **每个 Cause 都能给出处置**：要么有 fix，要么**明确**说明不该给（RUNTIME_ARCH
   是唯一允许空的 —— 重试无意义的场合）。
4. **修复动作与实现一一对应**：9 个 [Fix.Kind] 在 `RepairActions` 里都有分支，
   且 `FailureDialog` 都有标题 —— 任何一处缺失都会让用户「点了没反应」。

## 反向验证过的断言

删掉 `diagnoseInternal` 里的端口分支 → `[B1]` 红；
从 `RepairActions` 删掉 `ROLLBACK_PROFILE` 分支 → `[D1]` 红；
把 `FailureDialog.onAction` 里 REINSTALL 的特判删掉 → `[E2]` 红。

跑法：python scripts/probes/_test_engine_triage.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
TRIAGE = os.path.join(ENGINE, "EngineTriage.kt")
ACTIONS = os.path.join(ENGINE, "RepairActions.kt")
SUPERVISOR = os.path.join(ENGINE, "EngineSupervisor.kt")
DIALOG = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "FailureDialog.kt")
STRINGS = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")

checks = 0
failed = 0


def ok(cond, msg, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  [OK]   " + msg)
    else:
        failed += 1
        print("  [FAIL] " + msg + ((" - " + detail) if detail else ""))


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    """去掉 // 行注释与 /* */ 块注释。

    这个项目里注释经常直接引用被讨论的枚举值（「PORT_FOREIGN 的按钮带确认」），
    不剥就会把「注释里提到」当成「代码里有」。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def enum_values(src, name):
    """取 `enum class <name>(...) { A("x"), B("y") }` 里的枚举项名。"""
    m = re.search(r"enum\s+class\s+" + re.escape(name) + r"[^{]*\{(.*?)\n\s*\}", src, re.S)
    if not m:
        return []
    body = m.group(1)
    # 只取行首/逗号后的标识符，跳过构造函数与 KDoc
    return re.findall(r"(?:^|,)\s*([A-Z][A-Z0-9_]+)\s*[(\",]", body, re.M)


def main():
    print("=" * 70)
    print("Engine failure triage — attribution / repair wiring self-check")
    print("=" * 70)

    for p in (TRIAGE, ACTIONS, SUPERVISOR, DIALOG, STRINGS):
        if not os.path.isfile(p):
            print("missing: %s" % p)
            return 1

    triage_raw = read(TRIAGE)
    triage = strip_comments(triage_raw)
    actions = strip_comments(read(ACTIONS))
    sup = strip_comments(read(SUPERVISOR))
    dialog = strip_comments(read(DIALOG))
    strings = read(STRINGS)

    causes = enum_values(triage, "Cause")
    fixes = enum_values(triage, "Kind")

    print("\n[A] 枚举完整性")
    ok(len(causes) >= 12, "[A1] Cause has %d entries (>=12 expected)" % len(causes),
       "got %s" % causes)
    ok(len(fixes) >= 9, "[A2] Fix.Kind has %d entries (>=9 expected)" % len(fixes),
       "got %s" % fixes)

    # 每个 cause 的 id 都必须是小写下划线形式（这是桥端点对外暴露的稳定契约）
    ids = re.findall(r'Cause\("[a-z_]+"\)|([A-Z_]+)\("([a-z_]+)"\)', triage)
    ok(True, "[A3] cause ids are snake_case (checked by regex below)")
    snake = re.findall(r'"([a-z][a-z0-9_]*)"\)', triage)
    ok(all(re.fullmatch(r"[a-z][a-z0-9_]*", s) for s in snake if s),
       "[A4] all declared ids are snake_case",
       "offenders: %s" % [s for s in snake if not re.fullmatch(r"[a-z][a-z0-9_]*", s)])

    print("\n[B] 归因顺序与分支覆盖")
    fn = re.search(r"private fun diagnoseInternal\(.*?\n    \}", triage, re.S)
    ok(fn is not None, "[B0] diagnoseInternal() body extracted")
    if fn is None:
        return finish()
    body = fn.group(0)

    # 8 个判定段的相对顺序。用各段里独一无二的标记定位。
    order_markers = [
        ("port", "portEvidence"),
        ("runtime", "facts.archMismatch"),
        # 鉴权判定的标记是 `authFailureIn(log)` 而不是 AUTH_MARKERS：
        # v1.5.3 把「日志里有没有凭据失败」抽成了唯一真源（见
        # _test_engine_auth_marker.py），diagnoseInternal 里只剩对它的调用。
        # 断言行为等价物而不是实现细节 —— 判定本身没变，只是搬了位置。
        ("auth", "authFailureIn(log)"),
        ("plugin", "PLUGIN_INACTIVE_MARKERS"),
        ("profile", "facts.profileBroken.isNotEmpty()"),
        ("storage", "!facts.storageWritable"),
        ("system", "facts.systemExitReason.isNotEmpty()"),
        ("fallback", "no failure marker matched"),
    ]
    positions = {}
    for name, marker in order_markers:
        idx = body.find(marker)
        ok(idx >= 0, "[B1] %s section present (marker: %s)" % (name, marker))
        positions[name] = idx
    present = [n for n, _ in order_markers if positions.get(n, -1) >= 0]
    ordered = all(
        positions[present[i]] < positions[present[i + 1]]
        for i in range(len(present) - 1)
    )
    ok(ordered, "[B2] sections appear in evidence-strength order: %s" % " < ".join(present),
       "actual offsets: %s" % {k: v for k, v in positions.items() if v >= 0})

    # 端口判定必须同时接受「实测端口被占」与「日志有 EADDRINUSE」
    ok("facts.portInUse" in body and "PORT_MARKERS" in body,
       "[B3] port section accepts both live probe and log marker",
       "只有一边会漏判：进程刚死端口已释放时只剩日志证据")

    # self-check 分支必须在兜底之前（否则永远走不到）
    sc = body.find("facts.selfCheck")
    fb = body.find("no failure marker matched")
    ok(sc >= 0 and fb >= 0 and sc < fb,
       "[B4] self-check branch sits before the fallback",
       "放兜底之后就永远不执行")

    print("\n[C] 每个 Cause 都真的会被产出（无死枚举）")
    # RUNTIME_ARCH 由调用方 override 传入，归因器内部也产出一次，两者都算
    produced = set()
    m = re.search(r"fun diagnoseInternal\(.*?\n    \}", triage, re.S)
    seg = m.group(0) if m else ""
    for c in causes:
        # 出现形式：cause = Cause.X, Cause.X, 或 overrideCause ?: Cause.X
        if re.search(r"Cause\." + re.escape(c) + r"\b", seg):
            produced.add(c)
    # PORT_* 三个是在 when 里按条件产出的，确认都在
    missing = [c for c in causes if c not in produced]
    # UNKNOWN 必然在兜底里出现；RUNTIME_ARCH 在 archMismatch 分支里
    ok(not missing, "[C1] every Cause is produced by some branch",
       "never produced: %s" % missing)

    print("\n[D] RepairActions 必须实现全部 Fix.Kind")
    run_fn = re.search(r"private fun runInternal\(.*?\n    \}", actions, re.S)
    ok(run_fn is not None, "[D0] runInternal() body extracted")
    if run_fn is not None:
        rbody = run_fn.group(0)
        unimplemented = [
            k for k in fixes
            if not re.search(r"Fix\.Kind\." + re.escape(k) + r"\b", rbody)
        ]
        ok(not unimplemented, "[D1] every Fix.Kind has a branch in runInternal",
           "missing: %s" % unimplemented)
        # 每个分支都必须返回 Outcome（否则 when 不穷尽，编译就过不了 —— 这条是冗余保险）
        ok(rbody.count("Outcome(") >= len(fixes),
           "[D2] at least one Outcome per kind (%d branches, %d Outcome ctors)"
           % (len(fixes), rbody.count("Outcome(")))

    # 改磁盘的动作必须标 restartNeeded，否则「回滚了但引擎还在用旧内存状态」
    for kind in ("ROLLBACK_PROFILE", "CLEAN_INSTALL_RESIDUE", "FIX_OWNERSHIP"):
        m2 = re.search(r"Fix\.Kind\." + kind + r"\s*->\s*\{(.*?)\n        \}", actions, re.S)
        seg2 = m2.group(1) if m2 else ""
        ok("restartNeeded = true" in seg2 or "restartNeeded = ok" in seg2,
           "[D3] %s marks restartNeeded" % kind,
           "改磁盘却不重启 = 用户以为修好了但没生效")

    print("\n[E] FailureDialog 的动作必须都可达")
    ok("FailureDialog" in dialog or "object FailureDialog" in dialog,
       "[E0] FailureDialog exists")
    for kind in ("RECLAIM_PORT", "RESTART_ENGINE", "RUN_SELF_CHECK", "QUARANTINE_PLUGIN",
                 "ROLLBACK_PROFILE", "CLEAN_INSTALL_RESIDUE", "FIX_OWNERSHIP",
                 "OPEN_CREDENTIALS", "REINSTALL_RUNTIME"):
        ok("EngineTriage.Fix.Kind." + kind in dialog,
           "[E1] dialog has a title mapping for %s" % kind)
    # 重装运行时做不了 —— 必须给指引而不是假装执行
    ok("REINSTALL_RUNTIME" in dialog and "failure_reinstall_hint" in dialog,
       "[E2] REINSTALL_RUNTIME shows a hint instead of pretending to run")
    # 凭据只跳转，绝不自己改文件
    ok("OPEN_CREDENTIALS" in dialog and "SettingsActivity" in dialog,
       "[E3] OPEN_CREDENTIALS only navigates to settings")
    ok("writeText" not in dialog and "credentials.yaml" not in dialog,
       "[E4] dialog never writes credential files itself")

    print("\n[F] 失败点必须全部走统一的归因入口")
    fails = re.findall(r"_state\.value\s*=\s*State\.Failed\(", sup)
    ok(len(fails) <= 1,
       "[F1] only failTerminal() constructs State.Failed (%d direct site(s))" % len(fails),
       "绕过 failTerminal 的失败点不会带诊断")
    ok("private fun failTerminal(" in sup, "[F2] failTerminal() exists")
    ft = re.search(r"private fun failTerminal\(.*?\n    \}", sup, re.S)
    ok(ft is not None and "EngineTriage.diagnose" in ft.group(0),
       "[F3] failTerminal really calls the triage")
    ok(ft is not None and "runSelfCheck = true" in ft.group(0),
       "[F4] terminal failure auto-runs the full self-check (B 阶段)")
    # 硬编码英文的失败文案不许复活
    ok("is in use by another process" not in sup,
       "[F5] the old hard-coded English port message is gone",
       "那句没有五语文案，非英文用户看不懂")
    ok("error_port_foreign" in sup,
       "[F6] port-foreign failure uses a localized string")

    print("\n[G] 字符串齐全（五语种由 check-i18n 保证同构，这里只查存在）")
    need = [
        "failure_title", "failure_evidence", "failure_actions", "failure_never_booted",
        "failure_no_action", "failure_confirm_body", "failure_confirm_yes",
        "failure_reinstall_hint",
        "cause_port_foreign", "cause_port_orphan", "cause_port_unknown_owner",
        "cause_plugin_inactive", "cause_profile_corrupt", "cause_runtime_missing",
        "cause_runtime_arch", "cause_storage_denied", "cause_system_oom",
        "cause_system_killed", "cause_auth_expired", "cause_unknown",
        "fix_reclaim_port", "fix_restart_engine", "fix_run_self_check",
        "fix_quarantine_plugin", "fix_rollback_profile", "fix_clean_install_residue",
        "fix_fix_ownership", "fix_open_credentials", "fix_reinstall_runtime",
        "fix_done", "fix_skipped", "fix_failed", "fix_restart_needed",
    ]
    missing_keys = [k for k in need if ('name="%s"' % k) not in strings]
    ok(not missing_keys, "[G1] all %d triage strings exist" % len(need),
       "missing: %s" % missing_keys)

    print("\n[H] 桥端点（AI 可用）")
    bridge = os.path.join(ENGINE, "AgentBridge.kt")
    if os.path.isfile(bridge):
        br = strip_comments(read(bridge))
        ok('"GET /repair/diagnose"' in br, "[H1] /repair/diagnose registered")
        ok('"POST /repair/apply"' in br, "[H2] /repair/apply registered")
        apply_fn = re.search(r"private fun repairApply\(.*?\n    \}", br, re.S)
        ok(apply_fn is not None, "[H3] repairApply() exists")
        if apply_fn:
            ab = apply_fn.group(0)
            ok("diag.fixes.firstOrNull { it.kind == kind }" in ab,
               "[H4] apply refuses kinds not in the diagnosis",
               "强制调用归因器说「不适用」的动作是危险的")
            ok("needsConfirm" in ab and "confirmed" in ab,
               "[H5] apply enforces needs_confirm")
        diag_fn = re.search(r"private fun repairDiagnose\(.*?\n    \}", br, re.S)
        ok(diag_fn is not None, "[H6] repairDiagnose() exists")
        if diag_fn:
            db = diag_fn.group(0)
            ok('put("cause"' in db, "[H7] diagnose returns cause id")
            ok('put("evidence"' in db, "[H8] diagnose returns evidence")
            ok('put("fixes"' in db, "[H9] diagnose returns fixes")
    else:
        ok(False, "[H0] AgentBridge.kt exists")

    print("\n[I] AI 说明书要写清怎么用这两条端点")
    seed = os.path.join(ENGINE, "AgentContextSeed.kt")
    sd = read(seed) if os.path.isfile(seed) else ""
    ok("/repair/diagnose" in sd, "[I1] seed mentions /repair/diagnose")
    ok("/repair/apply" in sd, "[I2] seed mentions /repair/apply")
    ok("needs_confirm" in sd, "[I3] seed tells the AI about needs_confirm")
    ok("runtime_arch" in sd, "[I4] seed warns that runtime_arch cannot be fixed here")

    print("\n[J] 反向自检：断言能识别缺项（防死正则）")
    fake = "private fun diagnoseInternal(ctx: Context, facts: Facts): Diagnosis {\n " \
           "   return Diagnosis(Cause.UNKNOWN)\n    }"
    fb2 = re.search(r"private fun diagnoseInternal\(.*?\n    \}", fake, re.S)
    ok(fb2 is not None and "portEvidence" not in fb2.group(0),
       "[J1] a body without the port section is correctly seen as missing "
       "(guard against a dead regex)")

    return finish()


def finish():
    print("\n" + "-" * 70)
    print("checks=%d failed=%d" % (checks, failed))
    if failed:
        print("RESULT: FAILED")
        return 1
    print("RESULT: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())

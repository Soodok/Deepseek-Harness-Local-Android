#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「引擎起来了但插件降级」这件事必须对用户可见。

## 为什么需要这个探针（2026-11-07 模拟器实测发现）

引擎 HTTP 通了、状态栏显示「引擎已就绪」、WebUI 正常加载 —— 但插件探针报
`ok=false`：

```
[android-plugin-health] profile is degraded: missing=directoryPickerController,workspaceRegistry,workspaceController
failed=workspace-controller:@deepseek-ai/dsh-api-workspace-controller,
       workspace:@deepseek-ai/dsh-workspace,
       storage-domain:@deepseek-ai/dsh-storage-domain,
       session-persistence-jsonl:@deepseek-ai/dsh-session-persistence-jsonl
[health] plugin health: ok=false ...
```

也就是说**目录选择器、工作区、会话持久化全坏**，而界面一切正常。这个信息当时
只存在于 logcat 与 engine.log 的两行文本里 —— 用户点什么都报错，却没有任何地方
告诉他「有 3 项能力没加载」。

这正是「有的时候引擎会莫名其妙启动失败……有些事我根本不知道是什么 bug」里最难
自查的那一类：引擎**没失败**，它是**半残着成功**。

## 修法（本探针守的）

1. `EngineSupervisor.State.Healthy` / `SafeMode` 各带一个
   `degraded: Degraded?` 载荷，探针报 `ok=false` 时非 null。
   `Degraded` 同时记 `missingServices`（能力名，给用户看）与
   `failedEntries`（插件名，给排查看）。
2. `health == null`（探针没报告）**不能**当成降级 —— 「不知道」不等于「坏了」。
3. UI：状态栏显示「引擎降级 · N 项不可用」，并**只弹一次**说明框
   （按降级组合去重），提供进入维护中心的入口。不弹模态大字报 ——
   引擎是可用的，不该打断用户。

## 反向验证

- 把 `degradedNow` 的 `takeIf { !it.ok }` 去掉 → `[A3]` 变红
- 把 UI 的 `?: state.degraded` 分支删掉 → `[C1]` 变红
- 把去重守卫 `if (key == lastDegradedKey) return` 删掉 → `[C2]` 变红

跑法：python scripts/probes/_test_engine_degraded_visible.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SUP = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineSupervisor.kt")
MAIN = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "MainActivity.kt")
RES = os.path.join(ROOT, "app", "src", "main", "res")

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
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def main():
    print("=" * 68)
    print("Engine degraded-but-running must be visible (not only in logs)")
    print("=" * 68)

    for p in (SUP, MAIN):
        if not os.path.isfile(p):
            print("missing: %s" % p)
            return 1
    sup = strip_comments(read(SUP))
    main = strip_comments(read(MAIN))

    print("\n[A] Supervisor 必须把降级信息带进状态")
    ok("data class Degraded(" in sup,
       "[A1] EngineSupervisor.State.Degraded exists")
    m_deg = re.search(r"data class Degraded\((.*?)\n        \)", sup, re.S)
    ok(m_deg is not None, "[A2] Degraded fields extracted")
    if m_deg:
        b = m_deg.group(1)
        ok("missingServices" in b and "failedEntries" in b,
           "[A3] Degraded carries both capability names and plugin names",
           "capabilities matter to the user, plugin names matter to debugging")

    # Healthy / SafeMode 都要带 degraded
    for state in ("Healthy", "SafeMode"):
        m = re.search(r"data class %s\((.*?)\n        \) : State" % state, sup, re.S)
        ok(m is not None, "[A4] %s still parsed" % state)
        if m:
            ok("degraded" in m.group(1),
               "[A5] %s exposes degraded" % state,
               "SafeMode runs with a stripped profile — it is degraded by definition")

    print("\n[B] 只在探针明确报 ok=false 时才认定降级")
    m_now = re.search(r"val degradedNow = (.*?)\n", sup)
    ok(m_now is not None, "[B1] degradedNow assignment found")
    if m_now:
        expr = m_now.group(1)
        ok("takeIf { !it.ok }" in expr or "takeIf {!it.ok}" in expr,
           "[B2] gated on !ok — a healthy probe must NOT mark the engine degraded",
           "got: %s" % expr.strip())
    ok("health?.takeIf" in sup,
       "[B3] null-safe: a missing probe report is NOT treated as degraded",
       "'we don't know' must not become 'it is broken'")
    # 必须真的塞进状态对象
    ok(re.search(r"State\.Healthy\([^)]*degradedNow", sup) is not None
       or re.search(r"State\.Healthy\(EngineConfig\.DEFAULT_PORT, tokenUrl, notice, degradedNow\)", sup) is not None,
       "[B4] Healthy is constructed with degradedNow")
    ok(re.search(r"State\.SafeMode\([^)]*degradedNow", sup) is not None
       or "State.SafeMode(EngineConfig.DEFAULT_PORT, tokenUrl, notice, degradedNow)" in sup,
       "[B5] SafeMode is constructed with degradedNow")

    print("\n[C] UI 必须把它显示出来（不能只写日志）")
    # ⚠️ 不能只断言 `"status_degraded" in main` —— 只要文件里任何一处提到它就过，
    #    哪怕 Healthy 分支根本没用。实测反向验证时删掉整行仍然全绿（假阴性）。
    #    必须断言「`state.degraded?.let` 紧接在 recoveredPlugin 之后、且取
    #    status_degraded」这个真实形态，并且 Healthy / SafeMode 两处都要有。
    _DEG_LET = re.compile(
        r"\?:\s*state\.degraded\?\.let\s*\{\s*getString\(R\.string\.status_degraded",
        re.S)
    _deg_hits = _DEG_LET.findall(main)
    ok(len(_deg_hits) >= 2,
       "[C1] both Healthy and SafeMode status lines fall back to status_degraded",
       "found %d; expected 2 (engine usable with 3 dead capabilities must not "
       "read as 'engine ready')" % len(_deg_hits))
    # WARN: must also guard "exactly one Healthy/SafeMode branch".
    # 2026-11-07 on-device: a second identical `when` condition makes the later
    # branch unreachable in Kotlin - no compiler error, no warning. The status bar
    # still read "engine degraded, 3 unavailable" while the notice never appeared.
    render_body = main.split('private fun render(state: EngineSupervisor.State)', 1)[-1]
    render_body = render_body.split('private fun showDegradedNotice(', 1)[0]
    _healthy_branches = render_body.count(
        'is EngineSupervisor.State.Healthy, is EngineSupervisor.State.SafeMode ->')
    ok(_healthy_branches == 1,
       "[C2] exactly one Healthy/SafeMode branch in the state when()",
       "found %d; a duplicate branch is silently dead code, which is exactly how "
       "the degraded notice stopped firing" % _healthy_branches)
    ok("showDegradedNotice" in main,
       "[C3] there is a dedicated notice for the degraded state")
    m_fn = re.search(r"private fun showDegradedNotice\((.*?)\n    \}", main, re.S)
    ok(m_fn is not None, "[C4] showDegradedNotice body extracted")
    if m_fn:
        b = m_fn.group(1)
        # ⚠️ 只断言「变量名出现过」是无效的：把守卫改成 `if (false) return`
        #    之后 `lastDegradedKey` 仍在函数体里（赋值那一行），探针照样全绿。
        #    必须断言**比较本身**在起作用 —— 第一次反向验证就是被这一点骗过的。
        ok(re.search(r"if\s*\(\s*key\s*==\s*lastDegradedKey\s*\)\s*return", b) is not None,
           "[C5] deduplicated — the same degradation must not pop up repeatedly",
           "state can re-emit Healthy on every tick; guard must be a real comparison")
        ok("MaintenanceActivity" in b,
           "[C6] offers a way to act on it (maintenance center)")
        ok("AlertDialog" in b,
           "[C7] explains what is wrong, not just a status word")
    ok("lastDegradedKey" in main,
       "[C8] the dedup key is a field (survives recomposition)")

    print("\n[D] 文案五个语种齐全")
    for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
        p = os.path.join(RES, loc, "strings.xml")
        if not os.path.isfile(p):
            ok(False, "[D] %s/strings.xml missing" % loc)
            continue
        t = read(p)
        need = ["degraded_title", "degraded_body", "degraded_open_maintenance", "status_degraded"]
        miss = [k for k in need if ('name="%s"' % k) not in t]
        ok(not miss, "[D] %s has all degraded_* strings" % loc, "missing: %s" % miss)
    # status_degraded 必须带一个整数占位符（缺项数）
    base = read(os.path.join(RES, "values", "strings.xml"))
    m = re.search(r'<string name="status_degraded">(.*?)</string>', base, re.S)
    ok(m is not None and "%1$d" in (m.group(1) if m else ""),
       "[D] status_degraded carries the missing-capability count",
       "a bare 'degraded' tells the user nothing")

    print("\n[E] 反向自检：正则能识别坏形态")
    fake = "val degradedNow = health?.let { State.Degraded(...) }"
    ok("takeIf { !it.ok }" not in fake,
       "[E1] an ungated assignment is correctly seen as missing the gate")
    fake2 = "private fun showDegradedNotice(d: Degraded) {\n        android.app.AlertDialog.Builder(this)\n    }"
    ok("lastDegradedKey" not in fake2,
       "[E2] a notice without dedup is correctly seen as missing it")

    return finish()


def finish():
    print("\n" + "-" * 68)
    print("checks=%d failed=%d" % (checks, failed))
    if failed:
        print("RESULT: FAILED")
        return 1
    print("RESULT: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())

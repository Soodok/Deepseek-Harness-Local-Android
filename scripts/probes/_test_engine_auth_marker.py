#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「凭据失败判定不会被日志里的普通数字误触发」。

## 这个探针守的是一个**实际发生过的**误判（2026-11-07，模拟器实测）

引擎日志的第一行固定是：

```
---- engine start 1794011937629 ----
```

`EngineSupervisor.AUTH_FAILURE_MARKERS` 里有裸标记 `"401"`，于是
`text.contains("401")` 对**任何**日志都返回 true —— 每一次启动失败都被判成
「登录态或 API Key 失效了」。

真实后果（实测）：我故意用一个进程占住 3080 端口，期望归因器报
`port_foreign` 并给「释放端口」按钮。实际弹窗是：

```
引擎启动失败
登录态或 API Key 失效了
这台设备上引擎一次都没成功启动过
查到的情况
• log matches credential/authentication failure markers
可以这样处理
打开凭据设置
```

—— 归因器**已经**采到了「port 3080 is listening」这条硬证据（日志里有），
但 `failTerminal` 的 `overrideCause` 把它覆盖成了 AUTH_EXPIRED。用户按提示去
重新登录，端口依然被占，照样起不来。

## 三处必修（本探针逐一断言）

1. **裸数字状态码必须按数字边界匹配**：`1794011937629` 里的 401 不算，
   ` 401 ` / `HTTP 401` 才算。同一份判定逻辑只能在 `EngineTriage` 里有一份。
2. **`credentials` / `eacces` 不能当凭据失败信号**：前者是引擎日志里到处出现的
   名词（配置项名、路径名），后者是文件权限错误（属于存储类，归因器里在
   STORAGE_MARKERS）。
3. **硬证据不得被 overrideCause 覆盖**：端口占用来自真读 `/proc/net/tcp`，
   比任何日志特征串都硬，调用方传了别的 cause 也不能改它。

## 反向验证

- 把 `hasStatusCode` 的边界判断改成恒真 → `[A3]` 变红
- 把 `hardEvidence` 判断删掉 → `[C1]` 变红
- 把 `"credentials"` 加回标记表 → `[B1]` 变红

跑法：python scripts/probes/_test_engine_auth_marker.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TRIAGE = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineTriage.kt")
SUP = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineSupervisor.kt")

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
    """剥掉注释：注释里写着「不要用 contains("401")」时，不该被当成真的用了。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def has_status_code(log, code):
    """Python 版边界匹配 —— 与 Kotlin 实现同构，用来验证语义而不是比对字符串。"""
    i = log.find(code)
    while i >= 0:
        before_ok = i == 0 or not log[i - 1].isalnum()
        end = i + len(code)
        after_ok = end >= len(log) or not log[end].isalnum()
        if before_ok and after_ok:
            return True
        i = log.find(code, i + 1)
    return False


def main():
    print("=" * 68)
    print("Engine auth-failure detection: no false positives on plain digits")
    print("=" * 68)

    for p in (TRIAGE, SUP):
        if not os.path.isfile(p):
            print("missing: %s" % p)
            return 1

    triage = read(TRIAGE)
    sup = read(SUP)
    triage_code = strip_comments(triage)
    sup_code = strip_comments(sup)

    print("\n[A] 数字状态码必须按边界匹配（不能裸 contains）")
    # AUTH_MARKERS 里不得再出现裸 "401"/"403"
    m_markers = re.search(r"AUTH_MARKERS\s*=\s*listOf\((.*?)\n    \)", triage_code, re.S)
    ok(m_markers is not None, "[A1] AUTH_MARKERS list extracted")
    if m_markers:
        body = m_markers.group(1)
        ok('"401"' not in body and '"403"' not in body,
           "[A2] bare 401/403 are NOT in AUTH_MARKERS",
           "they would match the timestamp in '---- engine start 179401... ----'")
    ok("AUTH_STATUS_CODES" in triage_code,
       "[A3] status codes live in their own AUTH_STATUS_CODES list")

    ok("private fun hasStatusCode(" in triage_code,
       "[A4] hasStatusCode() exists")
    m_hsc = re.search(r"private fun hasStatusCode\((.*?)\n    \}", triage_code, re.S)
    ok(m_hsc is not None, "[A5] hasStatusCode body extracted")
    if m_hsc:
        b = m_hsc.group(1)
        ok("isLetterOrDigit()" in b,
           "[A6] hasStatusCode rejects alphanumerics on both sides",
           "digit-only boundary would still match '401abc'")
        ok(b.count("isLetterOrDigit()") >= 2,
           "[A7] both before and after are checked",
           "found %d isLetterOrDigit() calls" % b.count("isLetterOrDigit()"))

    print("\n[B] 会大面积误判的标记必须已移除")
    # ⚠️ 只在 AUTH_MARKERS 这一段里查，不能扫全文：
    #   `AUTH_STATUS_CODES = listOf("401","403")` 里本来就该有它们（只是走边界匹配），
    #   而 `eacces` 合法地存在于 STORAGE_MARKERS 里（文件权限错误属存储类）。
    #   扫全文会把这两处正确用法判成 bug —— 探针自己假红。
    auth_body = m_markers.group(1) if m_markers else ""
    for bad, why in (
        ("401", "matches the '179401...' timestamp"),
        ("403", "matches any 4-0-3 digit run"),
        ("credentials", "a noun that appears in config paths everywhere"),
        ("eacces", "that is a file-permission error, i.e. STORAGE class"),
    ):
        ok('"%s"' % bad not in auth_body,
           '[B] AUTH_MARKERS does not contain "%s"' % bad, why)
    # 正例：401/403 应在 AUTH_STATUS_CODES 里（而不是被彻底删掉）
    ok('"401"' in triage_code and '"403"' in triage_code,
       "[B] 401/403 are still detected, via AUTH_STATUS_CODES (not deleted)",
       "removing detection entirely would make real auth failures invisible")
    # Supervisor 里也不许再有裸 401 / credentials / eacces
    for bad in ("401", "403", "credentials", "eacces"):
        # 只检查字符串字面量形态（`"401"`），不管注释
        ok('"%s"' % bad not in sup_code,
           '[B] supervisor does not use "%s"' % bad)

    print("\n[C] 判定逻辑只能有一份（Supervisor 复用 Triager）")
    ok("fun authFailureIn(" in triage_code,
       "[C1] EngineTriage exposes authFailureIn()")
    ok("EngineTriage.authFailureIn(" in sup_code,
       "[C2] EngineSupervisor delegates to EngineTriage.authFailureIn()",
       "two copies of the check means the same bug has to be fixed twice")
    ok("AUTH_FAILURE_MARKERS" not in sup_code,
       "[C3] the duplicate marker list is gone from EngineSupervisor")

    print("\n[D] 硬证据不得被 overrideCause 覆盖")
    m_ft = re.search(r"private fun failTerminal\((.*?)\n    \}", sup_code, re.S)
    ok(m_ft is not None, "[D1] failTerminal body extracted")
    if m_ft:
        b = m_ft.group(1)
        ok("hardEvidence" in b,
           "[D2] failTerminal computes hardEvidence before overriding the cause")
        ok("PORT_FOREIGN" in b and "PORT_ORPHAN" in b,
           "[D3] port causes are treated as hard evidence",
           "portInUse comes from reading /proc/net/tcp — stronger than any log grep")
        ok(re.search(r"!hardEvidence", b) is not None,
           "[D4] the override is gated on !hardEvidence",
           "unconditional override is exactly the bug that was fixed")

    print("\n[E] 语义自测：Python 同构实现喂真实日志形态")
    cases = [
        ("---- engine start 1794011937629 ----", "401", False,
         "the real log header must NOT match 401"),
        ("---- engine start 1794011937629 ----", "403", False,
         "same for 403"),
        ("error: HTTP 401 Unauthorized", "401", True,
         "a real status code must match"),
        ("status=403, forbidden", "403", True,
         "trailing punctuation is a valid boundary"),
        ("token 401abc", "401", False,
         "letters adjacent means it is a hash/id fragment, not a status code"),
    ]
    for text, code, want, why in cases:
        got = has_status_code(text, code)
        ok(got == want, "[E] %s" % why,
           "expected %s, got %s for %r / %r" % (want, got, text, code))

    print("\n[F] 反向自检：正则真能识别坏形态")
    fake_bad = 'private val AUTH_MARKERS = listOf(\n        "401", "unauthorized",\n    )'
    m = re.search(r"AUTH_MARKERS\s*=\s*listOf\((.*?)\n    \)", fake_bad, re.S)
    ok(m is not None and '"401"' in m.group(1),
       "[F1] a marker list containing bare 401 is correctly detected "
       "(guard against a dead regex)")

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

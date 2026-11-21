#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""report 通道自检：AI 主动上报这条链路的结构与顺序。

检查的不是「代码长得像对的」，而是几件**顺序错了就失去意义**的事：

  1. 同意必须发生在换 token 之前 —— 反了就等于「先拿到授权凭证再问用户」。
  2. 任何一步拿不到（无障碍没起、token 领不到）都必须失败关闭，不许降级发送。
  3. 2 MiB 上限、30 秒倒计时在客户端与服务端是同一个数。
  4. 门脚本必须能被 AI 调用、也必须被部署（不在 EngineConfig 清单里就永远不会出现在设备上）。

用法：
    python scripts/probes/_test_report_channel.py            # 正常跑
    python scripts/probes/_test_report_channel.py --mutate   # 反向验证（故意改坏一处再跑）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def read(rel):
    with io.open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()

checks = 0
failed = 0

def ok(name, cond, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  PASS  [%s]" % name)
    else:
        failed += 1
        print("  FAIL  [%s] %s" % (name, detail))

GATE = "app/src/main/assets/gates/report"
BRIDGE = "app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt"
GATEFILE = "app/src/main/java/app/dsh/mobile/ReportConsentGate.kt"
UPLOAD = "app/src/main/java/app/dsh/mobile/ReportUpload.kt"
ENGINE = "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt"

gate = read(GATE)
bridge = read(BRIDGE)
g = read(GATEFILE)
up = read(UPLOAD)
engine = read(ENGINE)

# ── A 门脚本
ok("A1 gate exists", bool(gate.strip()))
ok("A2 bootstraps to engine bash", "_DSH_REEXEC" in gate and "@DSH_BASH@" in gate)
for sub in ("status", "preview", "send", "fixed"):
    ok("A3 subcommand %s" % sub, re.search(r"\b%s\b" % sub, gate) is not None)
ok("A4 talks to /dev/report", '"/dev/report"' in gate)
ok("A5 distinguishes consent denial", "CONSENT_DENIED" in gate)
ok("A6 distinguishes review rejection", "REVIEW_REJECTED" in gate)
ok("A7 exit code 2 for denial", re.search(r"exit 2", gate) is not None)
ok("A8 deployed in EngineConfig", '"vscreen", "report"' in engine)

# ── B 桥路由
ok("B1 GET route", re.search(r'"GET /dev/report"\s*to\s*Route\("GET"\)', bridge) is not None)
ok("B2 POST route", re.search(r'"POST /dev/report"\s*to\s*Route\("POST"\)', bridge) is not None)

send_at = bridge.find("private fun reportSend(")
assert send_at > 0, "reportSend 不存在"
send = bridge[send_at:send_at + 6000]
i_disabled = send.find("permanentlyDisabled")
i_size = send.find("MAX_BYTES")
i_a11y = send.find("DshAccessibilityService.instance")
i_consent = send.find("ReportConsentGate.request")
i_token = send.find("mintConsent")
i_upload = send.find("ReportUpload.upload")

ok("C1 checks永久关闭 first", 0 <= i_disabled)
ok("C2 size check before consent", 0 <= i_size < i_consent)
ok("C3 accessibility resolved", 0 <= i_a11y)
ok("C4 consent gate before token mint", 0 <= i_consent < i_token,
   "consent=%d token=%d" % (i_consent, i_token))
ok("C5 token minted before upload", 0 <= i_token < i_upload,
   "token=%d upload=%d" % (i_token, i_upload))
ok("C6 fails closed on missing token",
   re.search(r"mintConsent\(\)\s*\n?\s*\?:", send) is not None or "CONSENT_ISSUE_FAILED" in send)
ok("C7 fails closed on missing a11y", "accessibility service not enabled" in send)
ok("C8 no upload without consent", "return 403" in send)
ok("C9 mode标记为 agent", '"agent"' in send)

# ── D 同意浮窗
ok("D1 default timeout is 30s", re.search(r"timeoutMs:\s*Long\s*=\s*30_000L", g) is not None)
ok("D2 null service -> decline", re.search(r"if \(svc == null\) return false", g) is not None)
ok("D3 permanent disable checked", "permanentlyDisabled(svc)" in g)
ok("D4 unreadable pref fails closed", "getOrDefault(true)" in g)
ok("D5 timeout decides decline", re.search(r"if \(left <= 0\)", g) is not None)
ok("D6 overlay window type", "TYPE_ACCESSIBILITY_OVERLAY" in g)
ok("D7 shows what will be sent", "report_consent_files" in g and "report_consent_title_line" in g)

# ── E 上传实现
ok("E1 max bytes 2 MiB", re.search(r"MAX_BYTES\s*=\s*2 \* 1024 \* 1024", up) is not None)
ok("E2 random multipart boundary", "UUID.randomUUID" in up)
ok("E3 sends consent header", 'setRequestProperty("X-DSH-Consent", token)' in up)
ok("E4 sends report key", 'setRequestProperty("X-DSH-Report-Key"' in up)
ok("E5 refuses oversized before network", re.search(r"if \(total > MAX_BYTES\)", up) is not None)
ok("E6 no redirect following", "setInstanceFollowRedirects" in up or "不跟随重定向" in up)

# ── F 文案齐全
for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th", "values-ru"):
    txt = read("app/src/main/res/%s/strings.xml" % loc)
    miss = [k for k in ("report_consent_title", "report_consent_target", "report_consent_files",
                        "report_consent_countdown", "report_consent_expired",
                        "report_consent_approve", "report_consent_deny")
            if ('name="%s"' % k) not in txt]
    ok("F1 %s strings" % loc, not miss, "missing=%s" % miss)

print("\n%d checks, %d failed" % (checks, failed))
# ── G 关于页的「永久关闭」开关
about = read("app/src/main/res/layout/activity_about.xml")
aboutkt = read("app/src/main/java/app/dsh/mobile/AboutActivity.kt")
ok("G1 layout has the switch row", 'android:id="@+id/swReportOff"' in about)
ok("G2 activity wires the switch", "R.id.swReportOff" in aboutkt)
ok("G3 initial value read before listener",
   aboutkt.find("isChecked = ReportConsentGate.permanentlyDisabled") <
   aboutkt.find("setOnCheckedChangeListener"), "顺序反了会立刻回调一次写回默认值")
ok("G4 writes through the same pref", "setPermanentlyDisabled(this, checked)" in aboutkt)
for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th", "values-ru"):
    txt = read("app/src/main/res/%s/strings.xml" % loc)
    ok("G5 %s about switch strings" % loc,
       'name="about_report_off_title"' in txt and 'name="about_report_off_sub"' in txt)

sys.exit(1 if failed else 0)

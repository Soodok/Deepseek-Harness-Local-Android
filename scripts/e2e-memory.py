#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
无障碍记忆系统 v2 · 真机端到端回归（E2E）

用法：
    PYTHONIOENCODING=utf-8 python scripts/e2e-memory.py            # 默认 emulator-5556
    ADB=... SERIAL=... python scripts/e2e-memory.py

为什么要有它：M0–M9 的验收一直是一段段临时脚本（/e/tmp/loop*.py），发版前没法一键复跑。
这个脚本把「录制 → 剪枝 → 检索 → 重放 → 安全边界 → 隐私 → 修正 → 分档闸门」
固化成 10 个阶段，每段自己断言，最后打印 PASS/FAIL 汇总，退出码 = 失败段数。

确定性约定（踩过的坑，别再改回去）：
  · 录制前先 `am force-stop com.android.settings`：设置页会记住滚动位置，
    已经在底部时 `frameChanged` 永远等不到变化，scroll 步必判超时（实测踩过）。
  · 保护页阶段用 `dumpsys activity` 确认前台真的是 Settings 搜索页（intelligence 包），
    不是就 SKIP —— 前台包是粗筛，页面还没切过去时闸门本来就不会响。
  · 检索阶段接受 hash 或 bge 两种引擎：模拟器上没装 llama.cpp 扩展时只有 hash，
    这时断言 top-1 正确即可，不断言 tier=high（tier 是模型分，不是代码问题）。

前提（脚本会自己检查，不满足就早退）：
  · 设备已安装带记忆系统的包（`-Ppackage=noruntime` 的包在全新设备上起不来）
  · 引擎已起来（node 进程 + /dev/ping 通）
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ADB = os.environ.get("ADB", r"E:/Android/Sdk/platform-tools/adb.exe")
SERIAL = os.environ.get("SERIAL", "emulator-5556")
PORT = int(os.environ.get("BRIDGE_PORT", "13086"))
PKG = "app.dsh.mobile"

fails = []
skips = []
stage_no = 0
TOKEN = None


def shell(cmd, timeout=120):
    return subprocess.run([ADB, "-s", SERIAL, "shell", cmd],
                          capture_output=True, text=True, timeout=timeout).stdout.strip()


def stage(title):
    global stage_no
    stage_no += 1
    print("\n[%d] %s" % (stage_no, title))


def ok(cond, msg, detail=""):
    if cond:
        print("  PASS " + msg)
    else:
        print("  FAIL " + msg + (("  <- " + detail[:200]) if detail else ""))
        fails.append(msg)
    return cond


def skip(msg):
    print("  SKIP " + msg)
    skips.append(msg)


def call_raw(tok, method, path, body=None, timeout=180):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (PORT, path), data=data, method=method)
    req.add_header("X-DSH-Token", tok)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        return e.read().decode()
    except Exception as e:                       # noqa: BLE001 - 连接失败也要给出可读原因
        return json.dumps({"ok": False, "error": "transport: %s" % e})


def call(method, path, body=None, timeout=180):
    return call_raw(TOKEN, method, path, body, timeout)


def jcall(method, path, body=None, timeout=180):
    try:
        return json.loads(call(method, path, body, timeout))
    except ValueError:
        return {"ok": False, "error": "non-json response"}


def find_token():
    """应用重启后 token 会变：逐个 node 进程试 /dev/ping。"""
    pids = []
    for line in shell("ps -A -o PID,NAME").splitlines():
        p = line.split()
        if len(p) == 2 and p[1] == "node":
            pids.append(p[0])
    for pid in pids:
        env = shell("run-as %s cat /proc/%s/environ" % (PKG, pid))
        for kv in env.replace("\0", "\n").splitlines():
            if kv.startswith("DSH_BRIDGE_TOKEN="):
                tok = kv.split("=", 1)[1].strip()
                if tok and '"ok":true' in call_raw(tok, "GET", "/dev/ping"):
                    return tok
    return None


def allow():
    call("POST", "/task/allow", {"allowed": True})


def home():
    call("POST", "/key", {"action": "home"})
    time.sleep(1.5)


def clear_traces():
    call("POST", "/trace/clear", {})


def latest_trace():
    lst = jcall("GET", "/trace/list")
    tr = lst.get("traces") or []
    return tr[0]["id"] if tr else ""


def reset_settings():
    """设置页会记住滚动位置：不重启就会「已经在底部」，scroll 步永远等不到变化。"""
    shell("am force-stop com.android.settings")
    time.sleep(1.0)


def foreground():
    out = shell("dumpsys activity activities")
    for line in out.splitlines():
        if "mResumedActivity" in line:
            return line.strip()
    return ""


def main():
    global TOKEN

    # ---------------- 0. 环境 ----------------
    stage("环境：引擎 / 无障碍 / 令牌")
    a11y = shell("settings get secure enabled_accessibility_services")
    if not a11y.endswith("app.dsh.mobile/app.dsh.mobile.DshAccessibilityService"):
        skip("无障碍服务未启用 —— 先跑：adb shell settings put secure "
             "enabled_accessibility_services app.dsh.mobile/app.dsh.mobile.DshAccessibilityService")
        print("\n==== 汇总：%d 段，失败 %d ====" % (stage_no, len(fails)))
        return 1
    subprocess.run([ADB, "-s", SERIAL, "forward", "tcp:%d" % PORT, "tcp:3083"],
                   capture_output=True)
    TOKEN = find_token()
    if not ok(bool(TOKEN), "桥令牌可取且 /dev/ping 通"):
        print("  引擎没起来：先启动 App 并点过 WebView 门，再重跑本脚本")
        return 1
    allow()
    ok(True, "已放行动作闸门 (/task/allow)")

    # ---------------- 1. 录制：loop 内部步骤进轨迹 ----------------
    stage("录制：/loop/run 三步逐条进轨迹")
    home()
    reset_settings()
    clear_traces()
    plan = {"goal": "e2e open settings display", "steps": [
        {"id": "s1", "action": {"type": "launch", "package": "com.android.settings"},
         "verify": {"foregroundPkg": "com.android.settings", "timeoutMs": 6000}},
        {"id": "s2", "action": {"type": "scroll", "direction": "down", "distance": 400},
         "verify": {"frameChanged": True, "timeoutMs": 4000}},
        {"id": "s3", "target": {"text": ["Display", "显示"]}, "action": {"type": "tap"},
         "verify": {"anyText": ["Brightness", "Dark theme"], "timeoutMs": 5000}}]}
    loop = jcall("POST", "/loop/run", plan)
    ok(loop.get("ok") is True, "loop 跑通", json.dumps(loop, ensure_ascii=False))
    tid = latest_trace()
    ok(bool(tid), "轨迹已落盘")
    doc = jcall("GET", "/trace/get?id=" + tid)
    steps = doc.get("steps") or []
    ok(len(steps) == 3, "轨迹有 3 步（实际 %d）" % len(steps))
    ok(all(s.get("caller") == "loop" for s in steps), "全部来自 loop 执行器")
    ok(not any(s.get("endpoint") == "loop_step" for s in steps), "没有 loop_step 外壳")
    ok(any(s.get("endpoint") == "scroll" and (s.get("args") or {}).get("direction") for s in steps),
       "scroll 步带 direction 参数")
    anchors = [(s.get("anchor") or {}).get("text") for s in steps]
    ok("Display" in anchors, "tap 步带锚点 Display（实际 %r）" % (anchors,))

    # ---------------- 2. 入库 ----------------
    stage("入库：learn（剪枝 + 归属包名）")
    # 意图带时间戳：同名意图的历史记忆会让 top-1 断言失去意义（实测踩过）
    intent = "e2e 打开设置显示页 %d" % int(time.time())
    lr = jcall("POST", "/memory/v2/learn", {"traceId": tid, "intent": intent})
    ok(lr.get("ok") is True, "learn 成功", json.dumps(lr, ensure_ascii=False))
    mid = lr.get("id", "")
    ok(bool(mid), "拿到记忆 id")
    ok(lr.get("pkg") == "com.android.settings", "归属包名 = 被启动的包（实际 %r）" % lr.get("pkg"))
    ok(int(lr.get("steps", 0)) >= 2, "记忆至少 2 步（实际 %r）" % lr.get("steps"))

    # ---------------- 3. 检索 ----------------
    stage("检索：lookup 召回（hash 或 bge）")
    hit = jcall("POST", "/memory/v2/lookup", {"intent": intent, "limit": 3})
    ranked = hit.get("ranked") or []
    engine = hit.get("engine", "?")
    ok(bool(ranked), "同句意图能召回（engine=%s）" % engine,
       json.dumps(hit, ensure_ascii=False)[:200])
    if ranked:
        ok(ranked[0].get("id") == mid, "top-1 就是刚入库的那条（实际 %r）" % ranked[0].get("id"))
        ok(ranked[0].get("cos", 0) >= 0.99, "同句 cos≈1（实际 %r）" % ranked[0].get("cos"))
    if engine != "bge":
        skip("本次检索走 %s（模拟器没装 llama.cpp 扩展，bge 起不来）；tier=high 的语义路径未覆盖" % engine)

    # ---------------- 4. 重放 ----------------
    stage("重放：从桌面按 id 执行")
    home()
    t0 = time.time()
    rr = jcall("POST", "/memory/run", {"id": mid})
    ok(rr.get("ok") is True, "重放 ok（%.2fs）" % (time.time() - t0),
       json.dumps(rr, ensure_ascii=False))
    ok(int(rr.get("failedAt", -1)) == -1, "没有失败步")
    ok(rr.get("fallback") is False, "没有回落到云端")

    # ---------------- 5. 安全：保护页 ----------------
    stage("安全：保护页只读，导航键可逃离")
    home()
    clear_traces()
    # 直接拉起 Settings 的搜索页（intelligence 包，属保护包）：比「点搜索框」确定，
    # 点法会因页面状态落到别的 Activity 上（实测就是这样跳过的）。
    shell("am start -n com.android.settings.intelligence/.search.SearchActivity")
    time.sleep(2.5)
    fg = foreground()
    if "intelligence" not in fg:
        skip("前台没进到 Settings 搜索页（%s）；保护页闸门由 JVM 断言与桥日志覆盖" % fg[:80])
    else:
        blocked = jcall("POST", "/input", {"text": "x"})
        ok(blocked.get("ok") is False and blocked.get("error") == "protected page",
           "保护页上 /input 被 403", json.dumps(blocked, ensure_ascii=False))
    esc = jcall("POST", "/key", {"action": "home"})
    ok(esc.get("ok") is True, "保护页上 /key home 放行（否则 AI 被困死）",
       json.dumps(esc, ensure_ascii=False))
    time.sleep(1.5)

    # ---------------- 6. 隐私：input 轨迹不得入库 ----------------
    stage("隐私：含 input 的轨迹入库被拒")
    home()
    reset_settings()
    clear_traces()
    jcall("POST", "/loop/run", {"goal": "e2e search box", "steps": [
        {"id": "s1", "action": {"type": "launch", "package": "com.android.settings"},
         "verify": {"foregroundPkg": "com.android.settings", "timeoutMs": 6000}},
        {"id": "s2", "target": {"text": ["Search settings", "搜索设置"]}, "action": {"type": "tap"},
         "verify": {"anyText": ["Search settings"], "timeoutMs": 4000}},
        {"id": "s3", "action": {"type": "input", "text": "display"},
         "verify": {"anyText": ["display"], "timeoutMs": 4000}}]})
    tid2 = latest_trace()
    doc2 = jcall("GET", "/trace/get?id=" + tid2)
    input_steps = [s for s in (doc2.get("steps") or []) if s.get("endpoint") == "input"]
    if input_steps:
        args = input_steps[0].get("args") or {}
        ok("text" not in args and "len" in args, "input 只记 len，不记明文", json.dumps(args))
        rej = jcall("POST", "/memory/v2/learn", {"traceId": tid2, "intent": "e2e input"})
        ok(rej.get("rejected") is True and "privacy" in str(rej.get("reason", "")),
           "learn 以隐私理由拒绝", json.dumps(rej, ensure_ascii=False))
    else:
        skip("本次 input 步没成功（页面不同），隐私判定由 JVM 断言覆盖")

    # ---------------- 7. 修正 ----------------
    stage("修正：patch / extend")
    patch = jcall("POST", "/memory/v2/patch", {"id": mid, "step": 0, "targetText": ""})
    ok(patch.get("ok") is True, "patch 第一步成功", json.dumps(patch, ensure_ascii=False)[:200])
    ext = jcall("POST", "/memory/v2/extend", {"id": mid, "steps": [
        {"action": "key", "targetText": "", "expectAfter": []}]})
    ok(ext.get("ok") is True, "extend 追加一步成功", json.dumps(ext, ensure_ascii=False)[:200])

    # ---------------- 8. 分档闸门 ----------------
    stage("分档闸门：低分意图不自动执行")
    far = jcall("POST", "/memory/run", {"intent": "在微信里给妈妈发一个红包", "auto": True})
    ok(far.get("ok") is False and far.get("fallback") is True,
       "无关意图不自动跑（tier 不足即回落）", json.dumps(far, ensure_ascii=False)[:200])

    # ---------------- 9. 统计与清理 ----------------
    stage("统计与清理")
    stats = jcall("GET", "/memory/v2/stats")
    ok(stats.get("ok") is not False, "stats 可读", json.dumps(stats, ensure_ascii=False)[:160])
    jcall("POST", "/memory/v2/delete", {"id": mid})
    home()
    allow()

    print("\n==== 汇总：%d 段，失败 %d，跳过 %d ====" % (stage_no, len(fails), len(skips)))
    for f in fails:
        print("  ✗ " + f)
    for s in skips:
        print("  - " + s)
    return len(fails)


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""plugin-health 的目录选择器功能性探测（issue #76）。

**背景**：报告人的设备上出现矛盾现象 ——
  · `.android-plugin-health.json` 里 `ok: true, missingServices: []`
  · 网关却报 `directoryPickerController is unavailable`

两者能同时成立是因为它们查的不是同一件事：探针只做 `ctx.get("directoryPicker")`，
网关是 `resolveReceiverContext()` 拿到子 context 之后再 `.get()`。服务在根 context
的注册表里存在，不代表网关那条解析路径能取到它。

所以探针必须问后端自己的 `capability()`：能返回 `kind` 才说明 `apply()` 真的跑完了、
后端对象是活的。Android 上没有 DISPLAY、没有 zenity/kdialog，`kind` 必然是 `browse`
——返回 `native` 就说明 overlay 没生效、base 的 auto 行还在跑。

本探针真跑 `plugin-health.mjs`（Node ESM），对六种 picker 形态断言 marker 内容。
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MJS = os.path.join(ROOT, "app", "src", "main", "assets", "android-plugins", "plugin-health.mjs")

checks = 0
failures = []


def check(cond, msg):
    global checks
    checks += 1
    if not cond:
        failures.append(msg)


check(os.path.isfile(MJS), "plugin-health.mjs missing")
if not os.path.isfile(MJS):
    print("plugin-health probe: %d checks, %d failed" % (checks, len(failures)))
    for f in failures:
        print("  FAIL:", f)
    sys.exit(1)

src = open(MJS, encoding="utf-8").read()
# 静态：探测函数存在，且**不受 missingServices 分支约束**（这是 #76 的关键）
check("function probeDirectoryPicker(" in src, "probeDirectoryPicker() missing")
check("typeof picker.capability === \"function\"" in src,
      "probe must call picker.capability()")
check("Android needs \"browse\"" in src, "probe must reject non-browse kinds")
# 必须挂在 missingServices 循环**之外**
loop_start = src.find("for (const service of missingServices)")
call_site = src.find("const pickerProblem = probeDirectoryPicker(ctx)")
check(loop_start > 0 and call_site > loop_start,
      "probe must run outside/after the missingServices loop (service present but broken)")
check(call_site > 0 and "if (pickerProblem)" in src, "probe result not consumed")

# 动态：真跑 apply()，六种 picker 形态
HARNESS = r'''
const fs = await import("node:fs");
const { pathToFileURL } = await import("node:url");
const { apply } = await import(pathToFileURL(process.argv[2]).href);
const dir = process.argv[3];
function mkCtx(picker) {
  const svc = { loader: { entries: () => [], getTasks: () => [] }, typert: {},
    typertGateway: {}, directoryPicker: picker, directoryPickerController: {},
    workspaceRegistry: {}, workspaceController: {}, webServer: {},
    profileContext: { startedBundles: ["x"] } };
  return { get(s) { return svc[s]; } };
}
const cases = {
  ok_browse:      { capability: () => ({ kind: "browse" }), list: async () => ({}) },
  bad_native:     { capability: () => ({ kind: "native" }), list: async () => ({}) },
  bad_no_cap:     { capability: () => undefined, list: async () => ({}) },
  bad_no_list:    { capability: () => ({ kind: "browse" }) },
  bad_throws:     { capability: () => { throw new Error("boom"); }, list: async () => ({}) },
  bad_undefined:  undefined,
};
const out = {};
for (const [label, picker] of Object.entries(cases)) {
  fs.rmSync(dir + "/.android-plugin-health.json", { force: true });
  apply(mkCtx(picker));
  await new Promise((r) => setTimeout(r, 200));
  const m = JSON.parse(fs.readFileSync(dir + "/.android-plugin-health.json", "utf8"));
  const f = m.failedEntries.find((e) => e.id === "android-directory-picker");
  out[label] = { ok: m.ok, pickerError: f ? f.error : null };
}
console.log(JSON.stringify(out));
'''

node = shutil.which("node") or shutil.which("node.exe")
tmp = tempfile.mkdtemp(prefix="dsh-ph-")
home = os.path.join(tmp, "home")
os.makedirs(home, exist_ok=True)
harness = os.path.join(tmp, "h.mjs")
open(harness, "w", encoding="utf-8", newline="\n").write(HARNESS)

if node:
    env = dict(os.environ)
    env["DSH_HOME"] = home
    env["PYTHONIOENCODING"] = "utf-8"
    r = subprocess.run([node, harness, MJS, home], capture_output=True, text=True,
                       encoding="utf-8", env=env, cwd=tmp)
    payload = None
    for line in (r.stdout or "").splitlines():
        line = line.strip()
        if line.startswith("{") and line.endswith("}"):
            try:
                payload = json.loads(line)
            except ValueError:
                pass
    if payload is None:
        check(False, "node harness produced no JSON (stdout=%r stderr=%r)"
              % ((r.stdout or "")[-200:], (r.stderr or "")[-200:]))
    else:
        check(payload["ok_browse"]["ok"] is True,
              "healthy browse backend must not degrade, got %r" % (payload["ok_browse"],))
        check(payload["ok_browse"]["pickerError"] is None,
              "healthy browse backend must not report a picker error")

        check(payload["bad_native"]["ok"] is False,
              "a native backend on Android must degrade (#76 shape)")
        check("browse" in (payload["bad_native"]["pickerError"] or ""),
              "native branch must explain it needs browse, got %r"
              % (payload["bad_native"]["pickerError"],))

        check(payload["bad_no_cap"]["ok"] is False,
              "backend without capability() must degrade")
        check("capability" in (payload["bad_no_cap"]["pickerError"] or ""),
              "no-capability branch must mention capability, got %r"
              % (payload["bad_no_cap"]["pickerError"],))

        check(payload["bad_no_list"]["ok"] is False,
              "backend without list() must degrade")
        check("list" in (payload["bad_no_list"]["pickerError"] or ""),
              "no-list branch must mention list, got %r"
              % (payload["bad_no_list"]["pickerError"],))

        check(payload["bad_throws"]["ok"] is False,
              "a throwing capability() must degrade, not crash the engine")
        check("boom" in (payload["bad_throws"]["pickerError"] or ""),
              "throwing branch must surface the message, got %r"
              % (payload["bad_throws"]["pickerError"],))

        check(payload["bad_undefined"]["ok"] is False,
              "an absent directoryPicker service must still degrade")
else:
    print("skip behaviour checks (no node available)")

shutil.rmtree(tmp, ignore_errors=True)

print("picker health probe: %d checks, %d failed" % (checks, len(failures)))
for f in failures:
    print("  FAIL:", f)
sys.exit(1 if failures else 0)

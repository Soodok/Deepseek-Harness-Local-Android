#!/usr/bin/env python3
"""插话（steer）补丁的**行为**回归：不止改对了文本，改完之后逻辑真的通。

静态探针（_test_steer_patch.py）只能证明"锚点被替换成了我们那串字"。它可以全绿，
而补丁逻辑本身是错的 —— 比如把守卫删得过头（对 idle 也放行），或者遍历队列时
拼错了键名。所以这里把补丁后的代码**真的拿出来执行**：

  A. 从 client.js 里按花括号截出 async steerQueue 的函数体，配一套假 session，
     断言"next-step 里的行会被 steer、next-turn 里的行也会"。
  B. 从 index.js 里取出补丁后的 steer 守卫条件，配假 request/agent，
     断言"next-step + running 放行、idle 一律拒"。

跑法：python3 scripts/probes/_test_steer_patch_behavior.py
"""
import io
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ZIP = "_runtime_bak/rt-x86_64-current.zip"
PATCHER = "scripts/patch-steer-android.py"
SC_ENTRY = "lib/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js"
CONV_ENTRY = "lib/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js"

ok = 0
fail = 0


def check(label, cond, extra=""):
    global ok, fail
    if cond:
        ok += 1
        print("  ok   %s" % label)
    else:
        fail += 1
        print("  FAIL %s%s" % (label, ("  -> " + str(extra)) if extra else ""))


def brace_block(text, start_index):
    """从 start_index（函数名首字符）开始，按 { } 配对切出整段。字符串感知。"""
    i = text.find("{", start_index)
    if i < 0:
        return None
    depth = 0
    in_str = None
    in_tpl = 0
    esc = False
    while i < len(text):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == in_str:
                in_str = None
        elif c == '"' or c == "'":
            in_str = c
        elif c == "\x60":
            in_tpl = 1 - in_tpl
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[start_index:i + 1]
        i += 1
    return None


def main():
    if not os.path.isfile(ZIP):
        print("FAIL missing %s" % ZIP)
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(ZIP) as z:
            for entry in (SC_ENTRY, CONV_ENTRY):
                dest = os.path.join(tmp, entry)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with z.open(entry) as src, io.open(dest, "wb") as out:
                    out.write(src.read())

        proc = subprocess.run([sys.executable, PATCHER, tmp], capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        check("补丁执行成功", proc.returncode == 0, proc.stdout + proc.stderr)

        conv = io.open(os.path.join(tmp, CONV_ENTRY), encoding="utf-8").read()
        sc = io.open(os.path.join(tmp, SC_ENTRY), encoding="utf-8").read()

        # ── A. 取补丁后的 steerQueue 并真跑 ────────────────────────────
        idx = conv.find("async steerQueue(session, shell)")
        check("能在补丁后的 client.js 里找到 steerQueue", idx >= 0)
        body = brace_block(conv, idx) if idx >= 0 else None
        check("能按花括号切出完整函数体", body is not None and body.rstrip().endswith("}"))

        # ⚠️ client.js 里它是**类方法**（`async steerQueue(...) { ... }` 没有 function 关键字），
        # 原样塞进脚本 = "Unexpected identifier 'steerQueue'"（第一版就栽在这）。
        # 改写成函数表达式再执行：参数与 this 用法都不变，被执行的仍是补丁后的那段代码。
        fn_expr = body.replace("async steerQueue(session, shell)", "const steerQueue = async function (session, shell)", 1)
        harness = """
const results = [];
%(fn_expr)s;
// ↑ 这个分号必须自己补：上游函数体结尾的 `}` 后面没有分号，
// 而下一行 `(async () => ...)()` 会因此被解析成「调用这个函数」——
// 实测报 TypeError: (intermediate value)(...) is not a function。


(async () => {
  const makeSession = (inbox, calls) => ({
    projections: { faceOf: () => ({ getSnapshot: () => inbox }) },
    updateQueue: async (id, action) => { calls.push([id, action.kind]); return { ok: true }; },
  });
  const quiet = { notify: () => {}, t: () => "t" };
  // steerQueue 本来的调用方是 shell/controller 的实例，内部用 this.t(...)；
  // 单独拿出来跑必须给个假 this，否则 TypeError（第一版就是在这一步红的）。
  const host = { t: (k) => k };

  const calls1 = [];
  await steerQueue.call(host, makeSession({ "next-step": [{ id: "s1" }] }, calls1), quiet);
  const calls2 = [];
  await steerQueue.call(host, makeSession({ "next-turn": [{ id: "t1" }], "next-step": [{ id: "s1" }] }, calls2), quiet);
  const calls3 = [];
  await steerQueue.call(host, makeSession({}, calls3), quiet);
  console.log(JSON.stringify({ step_only: calls1, both: calls2, empty: calls3 }));
})();
""" % {"fn_expr": fn_expr}

        harness_path = os.path.join(tmp, "harness.mjs")
        io.open(harness_path, "w", encoding="utf-8").write(harness)
        run = subprocess.run(["node", harness_path], capture_output=True, text=True,
                             encoding="utf-8", errors="replace")
        check("补丁后的 steerQueue 能被执行（node 退出 0）", run.returncode == 0,
              run.stdout + run.stderr)
        try:
            data = __import__("json").loads(run.stdout.strip().splitlines()[-1])
        except Exception:
            data = {}
        check("A1 只有 next-step 时也会 steer（旧实现这里是空转）",
              data.get("step_only") == [["s1", "steer"]], data.get("step_only"))
        check("A2 两个队列都有时两条都 steer",
              data.get("both") == [["t1", "steer"], ["s1", "steer"]], data.get("both"))
        check("A3 队列为空时一次都不调", data.get("empty") == [], data.get("empty"))

        # ── A' 对照：对**未打补丁**的原始文件跑同一套 → 必须复现缺陷（否则判据无效）──
        raw = None
        with zipfile.ZipFile(ZIP) as z:
            raw = z.read(CONV_ENTRY).decode("utf-8")
        raw_idx = raw.find("async steerQueue(session, shell)")
        raw_body = brace_block(raw, raw_idx) if raw_idx >= 0 else None
        if raw_body:
            raw_expr = raw_body.replace(
                "async steerQueue(session, shell)",
                "const steerQueueUnpatched = async function (session, shell)", 1)
            raw_harness = """
%(fn)s;
(async () => {
  const makeSession = (inbox, calls) => ({
    projections: { faceOf: () => ({ getSnapshot: () => inbox }) },
    updateQueue: async (id, action) => { calls.push([id, action.kind]); return { ok: true }; },
  });
  const quiet = { notify: () => {}, t: () => "t" };
  const host = { t: (k) => k };
  const calls = [];
  await steerQueueUnpatched.call(host, makeSession({ "next-step": [{ id: "s1" }] }, calls), quiet);
  console.log(JSON.stringify({ step_only: calls }));
})();
""" % {"fn": raw_expr}
            raw_path = os.path.join(tmp, "raw.mjs")
            io.open(raw_path, "w", encoding="utf-8").write(raw_harness)
            rrun = subprocess.run(["node", raw_path], capture_output=True, text=True,
                                  encoding="utf-8", errors="replace")
            try:
                rdata = __import__("json").loads(rrun.stdout.strip().splitlines()[-1])
            except Exception:
                rdata = {}
            check("A'1 未打补丁的原始实现**不会** steer next-step（缺陷可复现）",
                  rdata.get("step_only") == [], rdata.get("step_only"))

        # ── B. 取补丁后的 steer 守卫条件并真跑 ──────────────────────────
        m = re.search(r"if \(request\.action\.kind === \"steer\" && ([^\n{]+)\)\s*throw", sc)
        check("能在补丁后的控制器里找到 steer 守卫", m is not None)
        cond = m.group(1).strip() if m else ""
        check("守卫不再包含 target 判断（next-step 不该被挡）",
              "target" not in cond, cond)
        check("守卫仍保留 agent.status !== \"running\"", 'agent.status !== "running"' in cond, cond)

        guard_harness = """
const out = [];
function holds(cond, request, agent) { return eval(cond); }
const cases = [
  ["next-step + running", { target: "next-step" }, { status: "running" }],
  ["next-turn + running", { target: "next-turn" }, { status: "running" }],
  ["next-step + idle",    { target: "next-step" }, { status: "idle" }],
  ["next-turn + idle",    { target: "next-turn" }, { status: "idle" }],
];
for (const [label, req, ag] of cases) {
  out.push([label, holds("request.action.kind === \\"steer\\" && " + %r, { action: { kind: "steer" } }, ag)]);
}
console.log(JSON.stringify(out));
""" % cond
        # 上面的 eval 需要在 request/agent 作用域里；重写成更直接的写法
        guard_harness = """
function rejects(cond, agent) {
  const request = { action: { kind: "steer" }, itemId: "x" };
  const scope = { request: request, agent: agent };
  return new Function("request", "agent", "return (" + cond + ");")(request, agent);
}
const cases = [
  ["next-step + running", { status: "running" }],
  ["next-step + idle",    { status: "idle" }],
];
const out = cases.map(([label, ag]) => [label, rejects(%r, ag)]);
console.log(JSON.stringify(out));
""" % cond
        guard_path = os.path.join(tmp, "guard.mjs")
        io.open(guard_path, "w", encoding="utf-8").write(guard_harness)
        grun = subprocess.run(["node", guard_path], capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        check("补丁后的守卫能被求值（node 退出 0）", grun.returncode == 0,
              grun.stdout + grun.stderr)
        try:
            gdata = dict(__import__("json").loads(grun.stdout.strip().splitlines()[-1]))
        except Exception:
            gdata = {}
        check("B1 next-step + running → 不拒绝（插话放行）", gdata.get("next-step + running") is False,
              gdata.get("next-step + running"))
        check("B2 next-step + idle → 拒绝（空闲期插话仍报 unavailable）", gdata.get("next-step + idle") is True,
              gdata.get("next-step + idle"))

    print("")
    print("%d passed, %d failed" % (ok, fail))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())

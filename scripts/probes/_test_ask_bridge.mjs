/**
 * ask-bridge 插件的**运行时**行为测试（不是源码 grep）。
 *
 * 被验的是这条链路：模型调 ask_user_question → 插件在 tools/execute 里接住 →
 * 把问题 POST 到桥的 /ask（悬浮窗面板）→ 轮询到用户答完 → 把答案当工具结果返回。
 * 静态探针证明不了这件事，因为它依赖**不调 next()** 这个动作。
 *
 * 用一个假桥（真 HTTP，监听 127.0.0.1:3083）驱动：
 *   A. 允许操作 + 用户答了      → 接管，next() 一次都不调
 *   B. 允许操作关着             → 让路，next() 被调
 *   C. 桥不可达                 → 让路（接管失败绝不能变成「问不了」）
 *   D. 不是提问工具             → 让路
 *   E. 两道题                   → 依次问，两题答案都进结果
 *   F. /ask 起不来（已有待答）  → 让路
 *
 * 跑法：node scripts/probes/_test_ask_bridge.mjs [plugin.mjs]
 */
import http from "node:http";
import { pathToFileURL } from "node:url";

const PLUGIN = process.argv[2] || "app/src/main/assets/android-plugins/ask-bridge.mjs";
const PORT = 3083;

let pass = 0;
let fail = 0;
function check(name, ok, detail = "") {
  if (ok) { pass++; console.log("PASS  " + name); }
  else { fail++; console.log("FAIL  " + name + (detail ? "  -> " + detail : "")); }
}

// ── 假桥 ──────────────────────────────────────────────────────────────

/** 每次场景重置的桥状态。 */
let bridge = {
  allowed: true,
  askOk: true,
  answer: "选项A",
  /** 真桥会带这个字段（AgentBridge.kt 的 /ask/result 实测返回 fromOption）。 */
  fromOption: true,
  asks: [],       // 收到的提问
  pendingOnce: 1, // 第一次 GET /ask 仍报 pending，之后才给答案
  pendingLeft: 0,
};

function resetBridge(over = {}) {
  bridge = { allowed: true, askOk: true, answer: "选项A", fromOption: true, asks: [], pendingOnce: 1, pendingLeft: 0, ...over };
}

const server = http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => { body += c; });
  req.on("end", () => {
    const send = (code, obj) => {
      res.writeHead(code, { "content-type": "application/json" });
      res.end(JSON.stringify(obj));
    };
    if (req.headers["x-dsh-token"] !== "test-token") return send(403, { ok: false, error: "unauthorised" });
    const url = req.url.split("?")[0];
    if (url === "/task/allow") return send(200, { ok: true, allowed: bridge.allowed });
    if (url === "/ask" && req.method === "POST") {
      if (!bridge.askOk) return send(409, { ok: false, error: "a question is already pending" });
      let parsed = {};
      try { parsed = JSON.parse(body); } catch { /* ignore */ }
      bridge.asks.push(parsed);
      bridge.pendingLeft = bridge.pendingOnce;
      return send(200, { ok: true, id: bridge.asks.length, asked: true });
    }
    if (url === "/ask" && req.method === "GET") {
      if (bridge.pendingLeft > 0) { bridge.pendingLeft--; return send(200, { ok: true, pending: true }); }
      return send(200, { ok: true, pending: false });
    }
    if (url === "/ask/result") {
      return send(200, {
        ok: true, state: "ANSWERED", pending: false,
        answer: bridge.answer,
        ...bridge.fromOption === undefined ? {} : { fromOption: bridge.fromOption },
      });
    }
    return send(404, { ok: false, error: "unknown route" });
  });
});

// ── 假引擎 ────────────────────────────────────────────────────────────

function makeCtx() {
  const hooks = {};
  return {
    hooks,
    on(event, fn) { (hooks[event] || (hooks[event] = [])).push(fn); },
  };
}

/** 跑一次 tools/execute 瀑布；next 记录被调次数。 */
async function dispatch(hooks, exec) {
  const list = hooks["tools/execute"] || [];
  check("tools/execute hook registered", list.length === 1, "handlers=" + list.length);
  let nextCalls = 0;
  const next = async () => { nextCalls++; return { isError: false, value: { native: true }, content: [{ type: "text", text: "native" }] }; };
  let result = null;
  let threw = null;
  try {
    result = await list[0](exec, next);
  } catch (e) { threw = e; }
  return { result, nextCalls, threw };
}

/**
 * 原生 ask_user_question 的 output schema（从运行时包 dsh-tool-ask-user 抄下来的，
 * 见 lib/index.js 的 output.schema）。逐条搬过来，是为了让这个探针能证明
 * 「插件返回的结构引擎真的会收」——只断言"有 answers 字段"是不够的：
 * 引擎走 validateJsonSchemaValue(tool.output.schema, ...)，多一个 `source`、
 * answers 的元素不是对象，都会在工具层直接抛 ToolOutputError，
 * 用户侧看到的就是「答了但模型拿不到」。
 *
 * 返回违规列表；空数组 = 合规。
 */
function validateAskOutput(value) {
  const errs = [];
  if (value === null || typeof value !== "object" || Array.isArray(value)) return ["value must be an object"];
  for (const k of Object.keys(value)) {
    if (k !== "answers") errs.push('"value.' + k + '" is not a declared property (additionalProperties: false)');
  }
  if (!Array.isArray(value.answers)) {
    errs.push('"value.answers" must be an array (required)');
    return errs;
  }
  value.answers.forEach((a, i) => {
    if (a === null || typeof a !== "object" || Array.isArray(a)) {
      errs.push('"value.answers[' + i + ']" must be an object');
      return;
    }
    for (const k of Object.keys(a)) {
      if (k !== "id" && k !== "selected" && k !== "custom") {
        errs.push('"value.answers[' + i + '].' + k + '" is not a declared property (additionalProperties: false)');
      }
    }
    if (typeof a.id !== "string" || a.id.length === 0) errs.push('"value.answers[' + i + '].id" must be a non-empty string (required)');
    if (!Array.isArray(a.selected)) errs.push('"value.answers[' + i + '].selected" must be an array (required)');
    else if (a.selected.some((s) => typeof s !== "string")) errs.push('"value.answers[' + i + '].selected[]" must be strings');
    if (a.custom !== undefined && typeof a.custom !== "string") errs.push('"value.answers[' + i + '].custom" must be a string');
  });
  return errs;
}

function textOf(result) {
  const blocks = (result && result.content) || [];
  return blocks.map((b) => (b && b.type === "text" ? b.text : "")).join("\n");
}

async function main() {
  process.env.DSH_BRIDGE_TOKEN = "test-token";
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(PORT, "127.0.0.1", resolve);
  });

  const mod = await import(pathToFileURL(PLUGIN).href);
  check("plugin exports apply", typeof mod.apply === "function");
  check("plugin exports name", mod.name === "android-ask-bridge");
  const ctx = makeCtx();
  mod.apply(ctx);

  // A. 正常接管
  resetBridge();
  const a = await dispatch(ctx.hooks, {
    name: "ask_user_question",
    arguments: { questions: [{ id: "q1", question: "去哪家店", options: [{ label: "科技园店" }, { label: "深大店" }] }] },
    signal: new AbortController().signal,
  });
  check("A: intercepted (next not called)", a.nextCalls === 0, "nextCalls=" + a.nextCalls);
  check("A: returned a successful result", a.result && a.result.isError === false);
  // ① 契约：返回值必须通过原生 output schema（2026-11-26 反馈 #65261bd4 的阻塞点）
  const aViolations = a.result && a.result.value !== undefined
    ? validateAskOutput(JSON.parse(JSON.stringify(a.result.value)))
    : ["no value"];
  check("A: value passes the native output schema", aViolations.length === 0, aViolations.join("; "));
  check("A: no undeclared top-level field (source removed)",
    !(a.result && a.result.value && "source" in a.result.value));
  check("A: answers are objects with id+selected",
    a.result && a.result.value && a.result.value.answers.length === 1 &&
    a.result.value.answers[0].id === "q1" &&
    a.result.value.answers[0].selected[0] === "选项A",
    JSON.stringify(a.result && a.result.value));
  check("A: the user's answer is in the result text", textOf(a.result).includes("选项A"), textOf(a.result));
  check("A: the question text reached the panel", bridge.asks.length === 1 && bridge.asks[0].text === "去哪家店");
  check("A: options reached the panel", JSON.stringify(bridge.asks[0].options) === JSON.stringify(["科技园店", "深大店"]), JSON.stringify(bridge.asks[0].options));

  // A2. 手打自由文本 → 进 custom，selected 留空（与点选项区分开）
  resetBridge({ answer: "我自己写的回答", fromOption: false });
  const a2 = await dispatch(ctx.hooks, {
    name: "ask_user_question",
    arguments: { questions: [{ id: "q1", question: "说个理由" }] },
    signal: new AbortController().signal,
  });
  check("A2: free-text answer goes to custom",
    a2.result && a2.result.value &&
    a2.result.value.answers[0].custom === "我自己写的回答",
    JSON.stringify(a2.result && a2.result.value));
  check("A2: free-text leaves selected empty",
    a2.result && a2.result.value && a2.result.value.answers[0].selected.length === 0);
  const a2v = a2.result && a2.result.value !== undefined
    ? validateAskOutput(JSON.parse(JSON.stringify(a2.result.value)))
    : ["no value"];
  check("A2: free-text value passes the native output schema", a2v.length === 0, a2v.join("; "));

  // B. 允许操作关着 → 让路
  resetBridge({ allowed: false });
  const b = await dispatch(ctx.hooks, {
    name: "ask_user_question",
    arguments: { questions: [{ id: "q1", question: "问什么" }] },
    signal: new AbortController().signal,
  });
  check("B: allowed=false falls through to native", b.nextCalls === 1, "nextCalls=" + b.nextCalls);
  check("B: nothing was asked", bridge.asks.length === 0);

  // C. 桥不可达 → 让路
  await new Promise((r) => server.close(r));
  const c = await dispatch(ctx.hooks, {
    name: "ask_user_question",
    arguments: { questions: [{ id: "q1", question: "问什么" }] },
    signal: new AbortController().signal,
  });
  check("C: unreachable bridge falls through to native", c.nextCalls === 1, "nextCalls=" + c.nextCalls);
  check("C: no exception escaped", c.threw === null, String(c.threw));

  // 重新起桥做剩下的场景
  await new Promise((resolve) => { server.listen(PORT, "127.0.0.1", resolve); });

  // D. 不是提问工具
  resetBridge();
  const d = await dispatch(ctx.hooks, { name: "bash", arguments: { command: "ls" }, signal: new AbortController().signal });
  check("D: other tools are untouched", d.nextCalls === 1 && bridge.asks.length === 0);

  // E. 两道题
  resetBridge({ answer: "第二题答案" });
  const e = await dispatch(ctx.hooks, {
    name: "ask_user_question",
    arguments: { questions: [
      { id: "q1", question: "第一题" },
      { id: "q2", question: "第二题" },
    ] },
    signal: new AbortController().signal,
  });
  check("E: both questions were asked", bridge.asks.length === 2, "asks=" + bridge.asks.length);
  check("E: both answers are in the result", e.result && e.result.value && e.result.value.answers.length === 2);
  const eViolations = e.result && e.result.value !== undefined
    ? validateAskOutput(JSON.parse(JSON.stringify(e.result.value)))
    : ["no value"];
  check("E: two-question value passes the native output schema", eViolations.length === 0, eViolations.join("; "));
  check("E: each answer echoes its own question id",
    e.result && e.result.value && e.result.value.answers.map((x) => x.id).join(",") === "q1,q2",
    JSON.stringify(e.result && e.result.value && e.result.value.answers.map((x) => x.id)));

  // F. /ask 起不来 → 让路
  resetBridge({ askOk: false });
  const f = await dispatch(ctx.hooks, {
    name: "ask_user_question",
    arguments: { questions: [{ id: "q1", question: "问什么" }] },
    signal: new AbortController().signal,
  });
  check("F: a rejected /ask falls through to native", f.nextCalls === 1, "nextCalls=" + f.nextCalls);

  await new Promise((r) => server.close(r));
  console.log();
  console.log("passed=" + pass + " failed=" + fail);
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error("ask-bridge runtime test failed to run: " + (e && e.message));
  try { server.close(); } catch { /* ignore */ }
  process.exit(2);
});
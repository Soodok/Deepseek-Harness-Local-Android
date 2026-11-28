import test from "node:test";
import assert from "node:assert/strict";

import { apply } from "../../../app/src/main/assets/android-plugins/ask-bridge.mjs";

/**
 * Fake bridge: routes are answered from a mutable table so each case can
 * describe exactly what the phone reported at that moment.
 */
function bridge(routes) {
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    const path = String(url).replace("http://127.0.0.1:3083", "");
    calls.push({ path, method: (init.method || "GET"), body: init.body });
    const handler = routes[path];
    if (!handler) throw new Error("unexpected bridge route " + path);
    const value = handler(init);
    return { status: value.status ?? 200, text: async () => JSON.stringify(value.json ?? null) };
  };
  return calls;
}

function spec(nonce) {
  return {
    model: "qa",
    qid: "target-one-" + nonce,
    callId: "call_qa_ask_one_" + nonce,
    question: "Choose the fictional target one " + nonce + "?",
    options: ["Alpha-one-" + nonce, "Beta-one-" + nonce],
  };
}

function exec(payload) {
  return {
    name: "ask_user_question",
    arguments: { questions: [{ id: payload.qid, question: payload.question, options: payload.options.map((label) => ({ label })) }] },
    signal: undefined,
  };
}

/** Mount the plugin the way the engine does and return the intercept result. */
async function run(routes, payload, next) {
  let waterfall = null;
  apply({ on: (event, handler) => { if (event === "tools/execute") waterfall = handler; } });
  assert.ok(waterfall, "plugin must register tools/execute");
  return await waterfall(exec(payload), next);
}

test("AI 操作关闭时不接管并保持原有工具结果", async () => {
  process.env.DSH_BRIDGE_TOKEN = "t";
  const calls = bridge({ "/task/allow": () => ({ json: { ok: true, allowed: false } }) });
  const native = { isError: false, content: [{ type: "text", text: "native card" }] };
  const result = await run({}, spec("a1"), async () => native);
  assert.deepEqual(result, native);
  assert.deepEqual(calls.map((c) => c.path), ["/task/allow"]);
});

test("允许操作但悬浮面板不可用时也必须让路给原生卡片", async () => {
  process.env.DSH_BRIDGE_TOKEN = "t";
  const s = spec("b2");
  const calls = bridge({
    "/task/allow": () => ({ json: { ok: true, allowed: true } }),
    "/overlay/available": () => ({ json: { ok: true, available: false, mounted: false, shown: false } }),
  });
  const native = { isError: false, content: [{ type: "text", text: "native card" }] };
  const result = await run({}, s, async () => native);
  assert.deepEqual(result, native, "an invisible panel must never swallow the question");
  assert.deepEqual(calls.map((c) => c.path), ["/task/allow", "/overlay/available"]);
});

test("无障碍可用但窗口尚未显示时仍接管（POST /ask 负责拉起窗口）", async () => {
  process.env.DSH_BRIDGE_TOKEN = "t";
  const s = spec("c3");
  let polls = 0;
  const calls = bridge({
    "/task/allow": () => ({ json: { ok: true, allowed: true } }),
    "/overlay/available": () => ({ json: { ok: true, available: true, mounted: false, shown: false } }),
    "/ask": (init) => {
      if (init.method === "POST") return { json: { ok: true, id: 7 } };
      polls += 1;
      return polls === 1 ? { json: { ok: true, pending: true } } : { json: { ok: true, pending: false } };
    },
    "/ask/result": () => ({ json: { ok: true, answer: s.options[0] } }),
  });
  const result = await run({}, s, async () => ({ isError: false, content: [{ type: "text", text: "native card" }] }));
  assert.equal(result.isError, false);
  assert.equal(result.value.source, "overlay_panel");
  assert.deepEqual(result.value.answers, [s.options[0]]);
  assert.match(result.content[0].text, /问：/);
  // 一次 POST 发起 + 两次 GET 轮询（第一次仍 pending、第二次已答）+ 一次取答案。
  assert.deepEqual(calls.map((c) => c.path), ["/task/allow", "/overlay/available", "/ask", "/ask", "/ask", "/ask/result"]);
});

/**
 * [dsh-android] `ask_user_question` 捕获插件（v1.7.0）。
 *
 * ## 为什么需要它
 * 模型要问用户时，默认走引擎自带的 `ask_user_question`：问题变成对话里的一张卡片，
 * 用户要在聊天流里回答。实测在无障碍任务里这是最差的一条路 ——
 *  · 问题卡片连同前后文进上下文，一问一答要烧掉一大段 token；
 *  · 模型看到「有选项的提问」就容易反复权衡，反而卡在提问上不动手。
 * 而悬浮窗那边本来就有专门的面板（PhoneAsk：选项按钮 + 自由输入 + 超时）。
 *
 * 所以这里把原生提问**接走**：拦在 tools/execute 之前，改问悬浮窗，
 * 拿到答案后直接作为这次工具调用的结果返回 —— 对话流里既没有卡片也没有等待。
 *
 * ## 边界
 * 只在「允许 AI 操作」开着的时候接管（GET /task/allow）。关着的时候说明用户不是
 * 在让 AI 操作手机，正常聊天该走原生卡片就走原生卡片。
 *
 * ## 失败即让路
 * 拿不到 token / 桥不可达 / 用户超时没答 / 题目为空 —— 任何一步出问题都直接
 * `next()` 交回原生实现。接管是优化，**绝不能**变成「提问彻底不可用」。
 *
 * @module dsh-android-ask-bridge
 */

const name = "android-ask-bridge";

/** 只监听事件，不需要注入任何服务。 */
const inject = [];

/** 桥固定监听 127.0.0.1:3083（AgentBridge.PORT）。 */
const BRIDGE = "http://127.0.0.1:3083";

/** 引擎自带的提问工具名（@deepseek-ai/dsh-tool-ask-user）。 */
const ASK_TOOL = "ask_user_question";

/** 轮询间隔：悬浮窗是人手点，250ms 足够快又不会把桥打满。 */
const POLL_MS = 250;

/** 等用户回答的上限。超过就交回原生实现，不把 agent 永久挂住。 */
const WAIT_MS = 5 * 60 * 1000;

/** 回给模型的答案前缀：让模型一眼看出这是悬浮窗里答的。 */
const ANSWER_PREFIX = "[用户通过悬浮窗回答] ";

function token() {
  return process.env.DSH_BRIDGE_TOKEN || "";
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 打一次桥。返回 { status, json }；网络失败时抛错（由调用方兜住）。
 * @param {string} method - HTTP method.
 * @param {string} path - 桥的路径，如 /ask。
 * @param {object|undefined} body - JSON 请求体。
 * @param {AbortSignal|undefined} signal - 调用方的取消信号。
 */
async function call(method, path, body, signal) {
  const t = token();
  if (!t) throw new Error("DSH_BRIDGE_TOKEN missing");
  const res = await fetch(BRIDGE + path, {
    method,
    headers: { "X-DSH-Token": t, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 就按失败处理 */ }
  return { status: res.status, json };
}

/** 现在是否允许 AI 操作手机（悬浮窗那个开关）。读不到就当没开。 */
async function allowedOn(signal) {
  const r = await call("GET", "/task/allow", undefined, signal);
  return r.status === 200 && r.json && r.json.allowed === true;
}

/**
 * 把一道题送进悬浮窗，等用户答。
 * @returns {Promise<string|null>} 用户答的文本；没答成返回 null。
 */
async function askOne(question, signal) {
  const text = String((question && question.question) || "").trim();
  if (!text) return null;
  const rawOptions = Array.isArray(question && question.options) ? question.options : [];
  const options = rawOptions
    .map((o) => String((o && o.label) || o || "").trim())
    .filter((s) => s.length > 0);
  const started = await call("POST", "/ask", { text, options, freeText: true }, signal);
  if (started.status !== 200 || !started.json || started.json.ok !== true) return null;
  const deadline = Date.now() + WAIT_MS;
  while (Date.now() < deadline) {
    if (signal && signal.aborted) return null;
    await sleep(POLL_MS);
    const st = await call("GET", "/ask", undefined, signal);
    if (!st.json || st.json.ok !== true) continue;
    if (st.json.pending === true) continue;
    // 已经不 pending 了：取一次答案。取到就是回答，取不到就是超时/取消。
    const res = await call("POST", "/ask/result", {}, signal);
    const answer = res.json && typeof res.json.answer === "string" ? res.json.answer.trim() : "";
    return answer.length > 0 ? answer : null;
  }
  return null;
}

/**
 * 接管一次提问调用。
 * @returns {Promise<object>} 工具结果；任何一步失败都回落到原生实现。
 */
async function intercept(exec, next) {
  try {
    if (!exec || exec.name !== ASK_TOOL) return next();
    if (!token()) return next();
    if (!(await allowedOn(exec.signal))) return next();
    const args = exec.arguments || {};
    const questions = Array.isArray(args.questions) ? args.questions : [];
    if (questions.length === 0) return next();
    const pairs = [];
    for (const q of questions) {
      const answer = await askOne(q, exec.signal);
      // 只要有一题没答成，就整体交回原生实现：
      // 半答半问会让模型拿到残缺答案，比不问更糟。
      if (answer === null) return next();
      pairs.push({ question: String((q && q.question) || "").trim(), answer });
    }
    const text = pairs
      .map((p) => "问：" + p.question + "\n答：" + p.answer)
      .join("\n\n");
    return {
      isError: false,
      value: { source: "overlay_panel", answers: pairs.map((p) => p.answer) },
      content: [{ type: "text", text: ANSWER_PREFIX + text }],
    };
  } catch {
    // 桥挂了、token 丢了、信号取消了 —— 一律让路给原生提问。
    return next();
  }
}

/**
 * 挂载：在 `tools/execute` 瀑布最前面接住提问工具。
 * 不调 `next()` 就代表这次调用由我们回答，引擎不会再执行原生实现。
 */
function apply(ctx) {
  ctx.on("tools/execute", (exec, next) => intercept(exec, next));
}

export { apply, inject, name };
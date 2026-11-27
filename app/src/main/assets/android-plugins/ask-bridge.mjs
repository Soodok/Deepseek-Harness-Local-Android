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
 *
 * 返回值要带上「是点的选项还是手打的字」—— 这不是装饰：原生 output schema 把
 * 两者放在不同字段里（selected = 选项标签数组，custom = 自由文本），
 * 混在一起填会让模型分不清「用户选了某一项」还是「用户自己写了句话」。
 *
 * @returns {Promise<{answer: string, fromOption: boolean}|null>} 没答成返回 null。
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
  // 提问已经挂上了 → 主动把悬浮窗抬到能看见它的档位（2026-11-26 反馈 65261bd4）。
  // 这一步是**兜底**：App 侧现在折叠态也轮询 /ask，本该自己升档；
  // 但万一轮询被系统掐掉（悬浮窗被隐藏/服务刚重连），让 bridge 也推一把，
  // 用户就不必自己猜「要点一下那颗球」。失败不致命，忽略即可。
  try { await call("POST", "/overlay", { op: "expand" }, signal); } catch { /* 拉起失败不影响提问本身 */ }
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
    if (answer.length === 0) return null;
    // 桥上 fromOption=true 表示「点了选项胶囊」，false 表示「手打的字」。
    // 旧实现一律当字符串收，把这个区分丢掉了。
    const fromOption = res.json && res.json.fromOption === true;
    return { answer, fromOption };
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
      const got = await askOne(q, exec.signal);
      // 只要有一题没答成，就整体交回原生实现：
      // 半答半问会让模型拿到残缺答案，比不问更糟。
      if (got === null) return next();
      pairs.push({
        id: String((q && q.id) || "").trim(),
        question: String((q && q.question) || "").trim(),
        answer: got.answer,
        fromOption: got.fromOption,
      });
    }
    const text = pairs
      .map((p) => "问：" + p.question + "\n答：" + p.answer)
      .join("\n\n");
    // ⚠️ value 必须严格符合 dsh-tool-ask-user 的 output.schema：
    //   { answers: [ { id, selected: string[], custom?: string } ] }
    // 顶层 only `answers`（additionalProperties:false），不能塞 source；
    // answers 的元素必须是对象，不能是裸字符串。
    // 违反任意一条 → validateJsonSchemaValue 抛 ToolOutputError，
    // 用户侧表现就是「卡片答了、模型拿不到、还报工具失败」。
    const answers = pairs.map((p) => (
      p.fromOption
        // 点了选项：标签进 selected —— 悬浮窗给的选项标签就是 schema 里
        // selected 的语义（模型据此知道「用户选了某一项」）。
        ? { id: p.id, selected: [p.answer] }
        // 手打的字：放进 custom，selected 留空数组（skip 与 custom 的区别见原生说明）
        : { id: p.id, selected: [], custom: p.answer }
    ));
    return {
      isError: false,
      value: { answers },
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
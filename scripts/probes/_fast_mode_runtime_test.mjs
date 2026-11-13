/**
 * fast-mode 插件的运行时行为测试（不是静态断言）。
 *
 * 为什么需要这个：静态探针只能证明「代码里写了 agent.model.select」，
 * 不能证明「它真的把 reasoningEffort 改对了、并且真的挂在了 pre-step 上」。
 * 而这条 bug 的本质是**钩子挂错位置**，只有跑一遍才能验证。
 *
 * 这里用假的 ctx / agent 模拟引擎的两条路径：
 *   A. 新引擎（有 AgentModel）：必须挂 agent/pre-step，且在 fast 开启时
 *      model.select 被调用成 reasoningEffort=off，agent/request 不被触碰。
 *   B. 老引擎（无 AgentModel）：必须挂 agent/request，且返回 reasoningEffort=off。
 *
 * 假引擎按 0.2.2-rc.1 prepareRequest 的断言规则校验：如果插件在 agent/request
 * 里改了 effort，就模拟抛错 —— 这样一旦有人改回去，这个测试会立刻红。
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const PLUGIN = process.argv[2];
if (!PLUGIN) {
  console.error("usage: node fast_mode_runtime_test.mjs <plugin.mjs>");
  process.exit(2);
}

let pass = 0;
let fail = 0;
function check(name, ok, detail = "") {
  if (ok) {
    pass++;
    console.log("PASS  " + name);
  } else {
    fail++;
    console.log("FAIL  " + name + (detail ? "  -> " + detail : ""));
  }
}

// ── 假引擎 ────────────────────────────────────────────────────────────────

/**
 * 造一个假 agent。withAgentModel=false 时模拟 0.2.1-rc.x（没有 model 成员）。
 */
function makeAgent(withAgentModel, initialEffort) {
  const state = {
    selection: { provider: "deepseek", model: "deepseek-v4", reasoningEffort: initialEffort },
    selectCalls: [],
  };
  const agent = {};
  if (withAgentModel) {
    agent.model = {
      current() {
        return { ...state.selection };
      },
      select(sel) {
        state.selectCalls.push({ ...sel });
        state.selection = { ...sel };
      },
    };
  }
  return { agent, state };
}

/**
 * 按 0.2.2-rc.1 的真实规则跑一遍 agent/request：
 * preparedConfig 必须与 selection 完全一致，否则 throw（原文照抄引擎断言）。
 */
async function runRequestHook(hooks, agent, state) {
  const handlers = hooks["agent/request"] || [];
  if (handlers.length === 0) return { touched: false, threw: null };
  // 模拟 prepareRequest：selection 已由 preStep 定死
  const selection = agent.model ? agent.model.current() : { ...state.selection };
  let cfg = { ...selection };
  let touched = false;
  let threw = null;
  try {
    for (const h of handlers) {
      cfg = await h({ turn: 1, step: 1, signal: { aborted: false, throwIfAborted() {} } },
                    () => Promise.resolve(cfg));
      touched = true;
    }
    if (cfg.provider !== selection.provider || cfg.model !== selection.model) {
      throw new Error("agent/request proposed route after step preparation fixed it");
    }
    if (cfg.reasoningEffort !== selection.reasoningEffort) {
      throw new Error(
        `agent/request proposed reasoning effort "${cfg.reasoningEffort ?? "adapter default"}" ` +
        `after step preparation fixed "${selection.reasoningEffort ?? "adapter default"}"; ` +
        `use agent.model.select() before step preparation`);
    }
  } catch (e) {
    threw = e;
  }
  return { touched, threw, cfg };
}

/** 跑 pre-step 钩子（返回 waterfall 的 next 结果）。 */
async function runPreStepHook(hooks, agent, state) {
  const handlers = hooks["agent/pre-step"] || [];
  for (const h of handlers) {
    return h(
      { agent, messages: [], turn: 1, step: 1, signal: { aborted: false, throwIfAborted() {} } },
      () => Promise.resolve({ kind: "enter", messages: [] }),
    );
  }
  return null;
}

/** 造一个最小可用的插件 ctx（只实现 fast-mode 用到的那些面）。 */
/**
 * 造一个最小可用的插件 ctx。
 *
 * @param reasoningEfforts - 该模型声明的可选档位。传 null 表示「适配器没声明
 *   reasoning 能力」（＝默认思考、参数关不掉的那类模型）。这正是 v1.7.x 真机
 *   崩溃的形态：插件把 effort 改成 off，引擎派发期抛 UNSUPPORTED_REASONING_EFFORT。
 */
function makeCtx(reasoningEfforts = ["off", "low", "high", "xhigh"]) {
  const hooks = { "agent/request": [], "agent/pre-step": [] };
  const llmCalls = [];
  const llm = {
    async resolveModelInfo(provider, model) {
      llmCalls.push(provider + "/" + model);
      const base = { provider, id: model, name: model };
      if (reasoningEfforts === null) return base;      // 无 reasoning 字段
      return {
        ...base,
        reasoning: { efforts: reasoningEfforts.map((id) => ({ id })) },
      };
    },
  };
  return {
    hooks,
    llmCalls,
    ctx: {
      llm,
      on(name, fn) {
        (hooks[name] || (hooks[name] = [])).push(fn);
        return () => {};
      },
      tools: {
        register(tool) {
          hooks.__tool = tool;
        },
      },
    },
  };
}

/** 加载插件。先把它拷到一个有 engine 目录结构的位置，好让能力探测能命中。 */
async function loadPlugin(engineWithAgentModel) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "fm-"));
  const engineRoot = path.join(tmp, "engine");
  const pluginDir = path.join(engineRoot, "android-plugins");
  fs.mkdirSync(pluginDir, { recursive: true });
  // 造一个假 dsh-agent 包，让 hasAgentModel() 的探测能读到（或不读到）
  const agentLib = path.join(engineRoot, "lib", "node_modules", "@deepseek-ai", "dsh-agent", "lib");
  fs.mkdirSync(agentLib, { recursive: true });
  fs.writeFileSync(
    path.join(agentLib, "index.js"),
    engineWithAgentModel
      ? "var AgentModel = class { constructor(){} };\nexport { AgentModel };\n"
      : "// 0.2.1-rc.x: no AgentModel\nexport const x = 1;\n",
  );
  // tools 包（defineTool 会被动态 import；给个最小实现）
  const toolsLib = path.join(engineRoot, "lib", "node_modules", "@deepseek-ai", "dsh-tools", "lib");
  fs.mkdirSync(toolsLib, { recursive: true });
  fs.writeFileSync(
    path.join(toolsLib, "index.js"),
    "export function defineTool(o){ return o; }\n",
  );
  const dst = path.join(pluginDir, "fast-mode.mjs");
  fs.copyFileSync(PLUGIN, dst);
  // DSH_HOME 指到临时目录，标记文件写这里
  const home = path.join(tmp, "home");
  fs.mkdirSync(home, { recursive: true });
  process.env.DSH_HOME = home;
  const mod = await import(pathToFileURL(dst).href + "?t=" + Date.now());
  return { mod, home, tmp };
}

// ── A. 新引擎路径 ────────────────────────────────────────────────────────

{
  const { mod, home, tmp } = await loadPlugin(true);
  const { hooks, ctx } = makeCtx();
  await mod.apply(ctx);

  check("A1 新引擎：挂了 agent/pre-step", (hooks["agent/pre-step"] || []).length === 1,
        "hooks=" + Object.keys(hooks).filter((k) => !k.startsWith("__")).join(","));
  check("A2 新引擎：没有挂 agent/request（挂了就会踩引擎断言）",
        (hooks["agent/request"] || []).length === 0,
        "agent/request 处理器数=" + (hooks["agent/request"] || []).length);

  const { agent, state } = makeAgent(true, "xhigh");

  // A3: fast 关闭时不动
  await runPreStepHook(hooks, agent, state);
  check("A3 fast 关闭：select 未被调用", state.selectCalls.length === 0,
        "calls=" + JSON.stringify(state.selectCalls));

  // A4: fast 开启 → select(off)
  fs.writeFileSync(path.join(home, "fast-mode"), String(Date.now()));
  await new Promise((r) => setTimeout(r, 1600)); // 越过 1.5s 缓存
  await runPreStepHook(hooks, agent, state);
  const offCall = state.selectCalls.find((c) => c.reasoningEffort === "off");
  check("A4 fast 开启：select 带 reasoningEffort=off", offCall !== undefined,
        "calls=" + JSON.stringify(state.selectCalls));
  check("A5 select 同时带全 provider/model（否则写坏 selection）",
        offCall !== undefined && offCall.provider === "deepseek" && offCall.model === "deepseek-v4",
        JSON.stringify(offCall));

  // A6: 关键 —— 走一遍 prepareRequest 的断言，确认不再抛错
  {
    const r = await runRequestHook(hooks, agent, state);
    check("A6 fast 开启时 agent/request 不抛错（真实故障点）", r.threw === null,
          r.threw ? String(r.threw.message) : "");
    check("A7 pre-step 之后 model.current() 已是 off",
          agent.model.current().reasoningEffort === "off",
          JSON.stringify(agent.model.current()));
  }

  // A8: fast 关掉后要撤回 off
  fs.unlinkSync(path.join(home, "fast-mode"));
  await new Promise((r) => setTimeout(r, 1600));
  await runPreStepHook(hooks, agent, state);
  check("A8 fast 关闭：撤回 off（否则用户后续都没思考了）",
        agent.model.current().reasoningEffort !== "off",
        JSON.stringify(agent.model.current()));

  // A9: 已经在 off 且 fast 开 → 幂等，不重复 select
  fs.writeFileSync(path.join(home, "fast-mode"), String(Date.now()));
  await new Promise((r) => setTimeout(r, 1600));
  await runPreStepHook(hooks, agent, state);
  const n1 = state.selectCalls.length;
  await runPreStepHook(hooks, agent, state);
  check("A9 已是 off 时不重复 select（幂等）", state.selectCalls.length === n1,
        `${n1} -> ${state.selectCalls.length}`);

  check("A10 fast_mode 工具已注册", hooks.__tool !== undefined && hooks.__tool.name === "fast_mode");
  fs.rmSync(tmp, { recursive: true, force: true });
}

// ── B. 老引擎路径 ────────────────────────────────────────────────────────

{
  const { mod, home, tmp } = await loadPlugin(false);
  const { hooks, ctx } = makeCtx();
  await mod.apply(ctx);

  check("B1 老引擎：挂了 agent/request",
        (hooks["agent/request"] || []).length === 1,
        "hooks=" + Object.keys(hooks).filter((k) => !k.startsWith("__")).join(","));
  check("B2 老引擎：没挂 agent/pre-step（那边没有 agent.model，挂了也没用）",
        (hooks["agent/pre-step"] || []).length === 0);

  const { agent, state } = makeAgent(false, "xhigh");

  // B3: fast 关闭时不动
  let r = await runRequestHook(hooks, agent, state);
  check("B3 老引擎 fast 关闭：agent/request 不改 effort",
        r.threw === null && r.cfg.reasoningEffort === "xhigh",
        JSON.stringify(r.cfg));

  // B4: fast 开启 → 返回 off（老引擎允许，这是它的合法能力）
  fs.writeFileSync(path.join(home, "fast-mode"), String(Date.now()));
  await new Promise((rr) => setTimeout(rr, 1600));
  r = await runRequestHook(hooks, agent, state);
  check("B4 老引擎 fast 开启：effort 变成 off", r.cfg.reasoningEffort === "off",
        JSON.stringify(r.cfg));

  fs.rmSync(tmp, { recursive: true, force: true });
}

// ── C. 模型「默认思考、关不掉」时必须一个字都不改 ────────────────────────
//
// 这是 v1.7.x 真机崩溃的回归测试。引擎 dsh-llm 的派发校验是：
//
//   if (info.reasoning === void 0) {
//     if (requested !== void 0) throw LlmError(..., "UNSUPPORTED_REASONING_EFFORT")
//   }
//
// select() 本身不报错，派发期才炸 —— 整轮对话失败。所以插件必须先问能力。

{
  const { mod, home } = await loadPlugin(true);
  const { hooks, ctx, llmCalls } = makeCtx(null);   // null = 适配器没声明 reasoning
  await mod.apply(ctx);
  const { agent, state } = makeAgent(true, "xhigh");

  fs.writeFileSync(path.join(home, "fast-mode"), String(Date.now()));
  await new Promise((r) => setTimeout(r, 1600));
  await runPreStepHook(hooks, agent, state);

  check("C1 不支持 off 的模型：select 一次都没调",
        state.selectCalls.length === 0,
        "calls=" + JSON.stringify(state.selectCalls));
  check("C2 不支持 off 的模型：selection 仍是原来的 xhigh",
        agent.model.current().reasoningEffort === "xhigh",
        JSON.stringify(agent.model.current()));
  check("C3 探测真的问过 llm（否则只是碰巧没改）",
        llmCalls.includes("deepseek/deepseek-v4"),
        "llmCalls=" + JSON.stringify(llmCalls));
}

{
  // 声明了 reasoning 但没有 off 档 —— 同样不能改
  const { mod, home } = await loadPlugin(true);
  const { hooks, ctx } = makeCtx(["low", "medium", "high"]);
  await mod.apply(ctx);
  const { agent, state } = makeAgent(true, "high");
  fs.writeFileSync(path.join(home, "fast-mode"), String(Date.now()));
  await new Promise((r) => setTimeout(r, 1600));
  await runPreStepHook(hooks, agent, state);
  check("C4 没有 off 档的模型：select 一次都没调",
        state.selectCalls.length === 0,
        "calls=" + JSON.stringify(state.selectCalls));
}

{
  // 老引擎路径同样要守：agent/request 不能改
  const { mod, home } = await loadPlugin(false);
  const { hooks, ctx } = makeCtx(null);
  await mod.apply(ctx);
  const { agent, state } = makeAgent(false, "xhigh");
  fs.writeFileSync(path.join(home, "fast-mode"), String(Date.now()));
  await new Promise((r) => setTimeout(r, 1600));
  const r = await runRequestHook(hooks, agent, state);
  check("C5 老引擎 + 不支持 off：effort 保持原值",
        r.cfg && r.cfg.reasoningEffort === "xhigh",
        JSON.stringify(r.cfg));
  check("C6 老引擎 + 不支持 off：不抛错", r.threw === null,
        r.threw ? String(r.threw.message) : "");
}

console.log("\n" + "-".repeat(44));
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);

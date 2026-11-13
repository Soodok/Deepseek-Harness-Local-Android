/**
 * [dsh-android] `fast_mode` 工具插件（v1.2.166，用户要求「直接做成内置插件」）。
 *
 * ## 为什么从 shell 闸门改成工具插件
 * 最初做成 `fast` 闸门脚本（AI 敲 shell 命令）。用户实测：AI **不会主动用** ——
 * 因为它是 shell 命令、藏在 seed 说明里，模型要先"想起"再敲命令；
 * 而**注册成工具**后它会出现在模型的工具列表里，伴随任务型对话自然被选中。
 *
 * ## 它做什么
 * 无障碍操作（读屏 → 点按 → 验证）的每一步都走一次完整云端推理，慢；而这些
 * 步骤不需要思考（路径早已规划好）。打开 fast 后，本插件在**每一步**把
 * reasoningEffort 置为 off → 不再付思考成本；关闭即恢复常规对话。
 *
 * 工具：`fast_mode(action: "on" | "off" | "status")`
 * 状态存在 `$DSH_HOME/fast-mode` 标记文件 → 跨进程生效（含 daemon 子进程）、
 * 不需重启、幂等，且与遗留的 `fast` 闸门完全兼容（同一份标记）。
 *
 * ## 为什么不做成纯内存状态
 * daemon 与主会话是不同的进程；文件标记是最简单可靠的共享方式。
 * 1.5s 缓存避免每一步都碰磁盘（agent/request 钩子每步都跑）。
 *
 * ## 安全阀
 * 开关带 **30 分钟 TTL**：超过后自动失效（防止"忘了关"让后续任务全程无思考）。
 *
 * @module dsh-android-fast-mode
 */

import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { pathToFileURL, fileURLToPath } from "node:url";

const name = "android-fast-mode";

/** 需要 tools 服务来注册工具；agent 钩子本身不额外依赖。 */
const inject = ["tools"];

/**
 * 动态加载 `@deepseek-ai/dsh-tools`（v1.2.168 实测修复）。
 *
 * ⚠️ 不能写静态 `import { defineTool } from "@deepseek-ai/dsh-tools"`：
 * 本插件位于 `engine/android-plugins/`，**不在 node 的模块解析链上**
 * （engine/ 下没有 node_modules），裸包名解析必然失败 →
 * 引擎日志 `android-fast-mode: failed to import` → **工具从未注册**
 * （实测：AI 因此完全没有 fast_mode 可用）。
 *
 * 实测结论：裸包名不可用（NODE_PATH 对 ESM 无效），但**绝对路径可用**
 * （`…/engine/lib/node_modules/@deepseek-ai/dsh-tools/lib/index.js` 导入成功）。
 * 这里从插件自身位置推算 engine 根，再按绝对路径动态 import。
 */
/**
 * 本文件所在目录与 engine 根。
 *
 * ⚠️ 必须用 `fileURLToPath`，不能用 `new URL(import.meta.url).pathname`：
 * 后者在 Windows 上给出 `/E:/a%20b/...`（前导斜杠 + 百分号转义），拼出的路径
 * 必然 not exists —— 能力探测于是永远返回 false，静默退回旧路径，且没有任何
 * 报错。Android 上既无盘符也无空格，恰好能跑，但那是运气不是正确性。
 */
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ENGINE_ROOT = path.resolve(HERE, "..");

async function loadDefineTool() {
  // 本文件在 <engine>/android-plugins/fast-mode.mjs → engine 根是上一级
  const candidates = [
    path.join(ENGINE_ROOT, "lib/node_modules/@deepseek-ai/dsh-tools/lib/index.js"),
    path.join(ENGINE_ROOT, "node_modules/@deepseek-ai/dsh-tools/lib/index.js"),
  ];
  for (const c of candidates) {
    try {
      if (fs.existsSync(c)) {
        const m = await import(pathToFileURL(c).href);
        if (typeof m.defineTool === "function") return m.defineTool;
      }
    } catch { /* try next */ }
  }
  return void 0;
}

/** 标记文件（与 gates/fast 约定一致）。 */
const MARKER_NAME = "fast-mode";

/** 开关自动失效时长：防「开了忘了关」（用户反馈的真实风险）。 */
const TTL_MS = 30 * 60 * 1000;

/** 钩子里的状态缓存窗口（agent/request 每步都跑，不宜每步 statSync）。 */
const CACHE_MS = 1500;

const DESCRIPTION = [
  "Toggle FAST MODE for accessibility / UI-automation work on this Android device.",
  "",
  "WHY: every step of a UI task (read screen -> tap -> verify) otherwise pays a full",
  "cloud reasoning round trip, while the path is already planned; thinking per",
  "step only adds seconds. With fast mode ON the engine skips reasoning on each",
  "step, so multi-step UI work runs several times faster.",
  "",
  "WHEN: turn it ON right before a multi-step UI task (anything that will read the",
  "screen and act repeatedly, including delegating to the planner daemon). Turn it",
  "OFF as soon as that task is finished. Use \"status\" to check.",
  "",
  "IMPORTANT: do NOT leave it on. Coding, writing, analysis and any task that",
  "benefits from deliberation need thinking. It auto-expires after 30 minutes as a",
  "safety net.",
].join("\n");

/** 工具结果的人类可读渲染（模型与 UI 都会读它） */
function renderFastMode(v) {
  if (!v || typeof v !== "object") return String(v);
  if (v.ok !== true) return "fast_mode failed: " + (v.error || "unknown error");
  if (v.fast === true) {
    return "Fast mode is ON" +
      (v.elapsedMinutes ? " (on for " + v.elapsedMinutes + " min)" : "") +
      (v.autoExpiresInMinutes ? ", auto-expires in " + v.autoExpiresInMinutes + " min" : "") +
      ". Per-step reasoning is skipped — remember to turn it off when the UI task is done.";
  }
  return "Fast mode is OFF — normal mode (thinking on).";
}

/** 读标记：返回 {on, ageMs} —— 超过 TTL 视为已失效并顺手清理。 */
function readMarker(marker) {
  if (!marker) return { on: false, ageMs: 0 };
  try {
    const st = fs.statSync(marker);
    const ageMs = Date.now() - st.mtimeMs;
    if (ageMs > TTL_MS) {
      try { fs.unlinkSync(marker); } catch {}
      return { on: false, ageMs };
    }
    return { on: true, ageMs };
  } catch {
    return { on: false, ageMs: 0 };
  }
}

/**
 * 挂载：注册 `fast_mode` 工具 + 每步读标记来关思考。
 * @param ctx - 插件上下文（需 tools 服务）。
 */
async function apply(ctx) {
  const home = process.env.DSH_HOME || "";
  const marker = home ? path.join(home, MARKER_NAME) : "";

  // ---- 每步：fast 模式下关思考 ----
  //
  // ⚠️ 引擎 0.2.2-rc.1 起有一条硬契约（实测踩坑，`agent "…": agent/request
  // proposed reasoning effort "off" after step preparation fixed "xhigh"`）：
  //
  //   preStep() 在**发出 agent/pre-step 之前**就 `const selection = this.model.current()`
  //   把路线定死，然后 step() → prepareRequest(turn, step, decision.selection) 拿它
  //   去绑定 adapter；prepareRequest 里有两条断言：
  //     proposedConfig.provider/model     !== selection.provider/model      → throw
  //     proposedConfig.reasoningEffort    !== selection.reasoningEffort     → throw
  //   报错原文自己给了正解：`use agent.model.select() before step preparation`。
  //
  // 所以旧写法（在 agent/request 里 `{ ...proposed, reasoningEffort: "off" }`）
  // 在新引擎上**每一次 fast 步骤都会直接失败整轮**，而不是悄悄降级。必须改成
  // 在 pre-step 之前走 `agent.model.select()` 把选择写进 session 的
  // `model/selection`，让 `model.current()` 本身就返回 off。
  //
  // 旧引擎（0.2.1-rc.x）没有 `agent.model` 这套 API（`AgentModel` 类都不存在），
  // 那边 agent/request 里改还是合法且有效的 —— 所以两条路径都保留，按运行时
  // 能力择一，不能只写新路径（会让老引擎上 fast 彻底失效）。
  let cached = false;
  let cachedAt = 0;
  const isFast = () => {
    if (!marker) return false;
    const now = Date.now();
    if (now - cachedAt < CACHE_MS) return cached;
    cached = readMarker(marker).on;
    cachedAt = now;
    return cached;
  };

  // ---- 模型能力探测：这个模型到底能不能「关思考」----
  //
  // ⚠️ 必须先问再改（0.2.2 真机事故）：引擎 `dsh-llm` 的派发校验是
  //
  //     if (info.reasoning === void 0) {
  //       if (requested !== void 0) throw LlmError("... does not support reasoning
  //                                          effort \"off\"", "UNSUPPORTED_REASONING_EFFORT")
  //     }
  //
  // 也就是说：**模型适配器没声明 reasoning 能力**（＝某些「默认就思考、参数关不掉」
  // 的模型）时，只要请求里带了 reasoningEffort，就在派发期直接抛错 —— 整轮对话
  // 失败，而不是悄悄降级。select() 本身不校验（它只是往 session 追加一条选择），
  // 所以 try/catch 包 select 完全挡不住这个崩溃，必须**改之前就问清楚**。
  //
  // 探测走 `llm.resolveModelInfo(provider, model)`：它返回的 `reasoning.efforts`
  // 就是该模型声明的可选档位。没有 `reasoning` 字段 = 不支持任何档位。
  // 每 (provider, model) 只问一次，结果缓存到进程结束（能力不会中途变）。
  const probeCache = new Map();
  const probeInflight = new Map();

  const getLlm = () => {
    // ⚠️ v1.7.x 真机（2026-11-13）：**每个属性访问都必须各自包在 try 里**。
    //
    // 原来的写法是
    //     for (const c of [ctx, ctx && ctx.root, ctx && ctx.scope]) { try { ... } }
    // 数组字面量在 `try` **外面**求值，而 ctx 是 cordis 的 Proxy：
    // 它只放行 `inject` 声明过的服务名，访问别的名字会**直接抛**
    //     cannot get property "scope" without inject
    // 于是异常从 getLlm 冒出去 → fastSelectHook 抛错 → **整轮对话失败**
    // （用户侧：无障碍操作中途报错，之后发任何消息都「本轮运行失败」）。
    //
    // 为什么「把思考调到 off 就能继续」：fastSelectHook 只在
    // `cur.reasoningEffort !== "off"` 时才调 supportsOff，off 状态下压根不走
    // 这条路 —— 崩溃点被绕过，而不是被修好。这个现象是定位真因的关键线索。
    //
    // `scope` 也根本不是 cordis 的服务名（服务是 `llm` / `tools` 这些），
    // 所以那一项纯属误写，直接去掉。
    const candidates = [];
    for (const pick of [() => ctx, () => ctx.root]) {
      try { candidates.push(pick()); } catch { /* Proxy 拒绝就跳过 */ }
    }
    for (const c of candidates) {
      try {
        const v = c && (c.llm || (typeof c.get === "function" ? c.get("llm") : null));
        if (v && typeof v.resolveModelInfo === "function") return v;
      } catch { /* 未注册就换下一个 */ }
    }
    return null;
  };

  // 只警告一次：每步都刷一行会让 engine.log 被同一个原因淹掉。
  const warnedUnsupported = new Set();
  const warnUnsupportedOnce = (key) => {
    if (warnedUnsupported.has(key)) return;
    warnedUnsupported.add(key);
    console.warn(
      "[android-fast-mode] " + key + " cannot disable reasoning " +
      "(this model always thinks); fast mode has no effect on it. " +
      "The UI steps still work, they are just not faster.",
    );
  };

  /**
   * 该模型是否支持 `off`。
   * @returns true / false；拿不到能力信息时返回 null（未知）。
   *
   * ⚠️ 本函数**任何情况下都不许抛**：它是 agent 钩子里的同步路径，抛出去就是
   * 整轮对话失败（见 getLlm 顶部 2026-11-13 的记录）。外层再包一层 try 兜底。
   */
  const supportsOff = async (provider, model) => {
    try {
      const key = provider + "/" + model;
      if (probeCache.has(key)) return probeCache.get(key);
      if (probeInflight.has(key)) return probeInflight.get(key);
      const llm = getLlm();
      if (llm === null) return null;
      const task = (async () => {
        try {
          const info = await llm.resolveModelInfo(provider, model);
          const efforts = info && info.reasoning && info.reasoning.efforts;
          // 没有 reasoning 字段 ⇒ 不支持任何显式档位（传 off 必抛）
          const ok = Array.isArray(efforts) && efforts.some((e) => e && e.id === "off");
          probeCache.set(key, ok);
          return ok;
        } catch {
          return null;                       // 探测失败不写缓存，下次再试
        } finally {
          probeInflight.delete(key);
        }
      })();
      probeInflight.set(key, task);
      return task;
    } catch (e) {
      console.warn("[android-fast-mode] supportsOff failed: " + (e && e.message));
      return null;                           // 探测崩了就当未知，绝不改参数
    }
  };

  /** 老引擎路径：agent/request 里直接改（0.2.2 起会抛错，所以只作兜底）。 */
  const legacyHook = async (payload, next) => {
    const proposed = await next();
    if (proposed === void 0) return proposed;
    if (!isFast()) return proposed;              // 常规模式：一字不改
    const provider = proposed.provider;
    const model = proposed.model;
    if (provider && model) {
      const ok = await supportsOff(provider, model);
      // ok === false 明确不支持；ok === null 探测失败 —— 两种都不改，
      // 宁可这一步慢一点，也不要整轮对话因一个参数崩掉。
      if (ok !== true) return proposed;
    }
    return { ...proposed, reasoningEffort: "off" };
  };

  /**
   * 新引擎路径：在 pre-step 之前把选择写进 session，让 model.current() 返回 off。
   *
   * 为什么挂在 `agent/pre-step` 上而不是更早：`preStep()` 读 `model.current()`
   * 发生在**发出 pre-step 事件之前**，所以在 pre-step 处理器里 select 只对
   * **下一个** step 生效。这是可接受的（首步仍带思考，之后各步都关），也是
   * 上游唯一公开的注入点 —— 再早没有属于插件的钩子。
   *
   * 幂等：`AgentModel.select()` 内部先比 `sameModelSelection`，相同就不再 append，
   * 所以每步调也不会把 session 写爆。
   */
  const fastSelectHook = async (payload, next) => {
    const agent = payload && payload.agent;
    const model = agent && agent.model;
    if (!model || typeof model.current !== "function" || typeof model.select !== "function") {
      return legacyHook(payload, next);
    }
    if (isFast()) {
      const cur = model.current();
      if (cur && cur.reasoningEffort !== "off") {
        const ok = await supportsOff(cur.provider, cur.model);
        if (ok === true) {
          try {
            model.select({ provider: cur.provider, model: cur.model, reasoningEffort: "off" });
          } catch (e) {
            console.warn("[android-fast-mode] model.select failed: " + (e && e.message));
          }
        } else if (ok === false) {
          // 这个模型「默认思考、关不掉」。不改 —— 改了会在派发期抛
          // UNSUPPORTED_REASONING_EFFORT，整轮任务失败（比慢更糟）。
          warnUnsupportedOnce(cur.provider + "/" + cur.model);
        }
        // ok === null（探测失败）同样不改：保守优先，不能拿整轮任务赌。
      }
    } else {
      // fast 关掉时要把之前写进去的 off 撤回来，否则会话会一直停在 off。
      // 只撤自己写的那档：用户/别的插件设的其它档位不动。
      const cur = model.current();
      if (cur && cur.reasoningEffort === "off") {
        try {
          model.select({ provider: cur.provider, model: cur.model });
        } catch (e) {
          console.warn("[android-fast-mode] model.select revert failed: " + (e && e.message));
        }
      }
    }
    return next();
  };

  // 引擎能力探测：有 AgentModel 就只走新路径（agent/request 改 effort 会被判违规），
  // 没有才退回老路径。两个钩子**不能同时挂** —— 同时挂会在新引擎上先 select 成 off
  // 再被 agent/request 改成 off（无害）但老引擎上没有 agent.model，反而白挂。
  const hasAgentModel = () => {
    try {
      const p = path.join(ENGINE_ROOT, "lib/node_modules/@deepseek-ai/dsh-agent/lib/index.js");
      if (!fs.existsSync(p)) return false;
      // 直接读源码找 AgentModel：比 import 一个 ESM 包更轻、也不会触发副作用。
      // 0.2.2+ 有 `var AgentModel = class`；0.2.1-rc.x grep 命中数为 0。
      return fs.readFileSync(p, "utf8").includes("var AgentModel = class");
    } catch { return false; }
  };

  if (hasAgentModel()) {
    ctx.on("agent/pre-step", fastSelectHook);
  } else {
    ctx.on("agent/request", legacyHook);
  }

  // ---- 工具：让模型自己开关（比 shell 命令更容易被选中）----
  // defineTool 动态加载（见 loadDefineTool 说明）；失败则只保留钩子能力，
  // 并明确记一条日志，避免"工具没注册却无人知晓"（实测踩坑）。
  const defineTool = await loadDefineTool();
  if (defineTool === void 0) {
    console.warn("[android-fast-mode] defineTool unavailable — tool NOT registered (hook still active)");
    return;
  }
  ctx.tools.register(defineTool({
    name: "fast_mode",
    description: DESCRIPTION,
    parameters: {
      action: {
        type: "string",
        required: true,
        enum: ["on", "off", "status"],
        description:
          "on = start fast mode (skip per-step reasoning) before a UI task; " +
          "off = return to normal mode when the UI task is done; " +
          "status = report the current state without changing it.",
      },
    },
    // ⚠️ `output` 是 defineTool 的**必填**字段（v1.2.169 实测踩坑）：
    // 缺它会在 defineTool 内部直接抛
    //   TypeError: Cannot read properties of undefined (reading 'render')
    // （源码：`const userRender = options.output.render`）→ 插件加载失败 → 工具没注册。
    // 形状照抄引擎自带的 tool-goal：{ schema, render }。
    output: {
      schema: {
        type: "object",
        additionalProperties: true,
        properties: {
          ok: { type: "boolean", required: true },
          fast: { type: "boolean" },
          note: { type: "string" },
          elapsedMinutes: { type: "number" },
          autoExpiresInMinutes: { type: "number" },
          error: { type: "string" },
        },
      },
      render: (_args, value) => [{ type: "text", text: renderFastMode(value) }],
    },
    execute(args) {
      const action = String((args && args.action) || "").toLowerCase();
      if (action === "on") {
        if (!marker) {
          return Promise.resolve({ ok: false, error: "DSH_HOME is not set; cannot store fast mode" });
        }
        try {
          fs.writeFileSync(marker, String(Date.now()));
          cached = true; cachedAt = Date.now();
        } catch (e) {
          return Promise.resolve({ ok: false, error: "cannot write " + marker + ": " + (e && e.message) });
        }
        return Promise.resolve({
          ok: true, fast: true,
          note: "fast mode ON — subsequent steps skip reasoning. Turn it off (action=off) as soon as the UI task is done.",
        });
      }
      if (action === "off") {
        try { fs.unlinkSync(marker); } catch {}
        cached = false; cachedAt = Date.now();
        return Promise.resolve({ ok: true, fast: false, note: "fast mode OFF — back to normal (thinking on)." });
      }
      if (action === "status") {
        const st = readMarker(marker);
        return Promise.resolve({
          ok: true,
          fast: st.on,
          elapsedMinutes: st.on ? Math.round(st.ageMs / 60000) : 0,
          autoExpiresInMinutes: st.on ? Math.max(0, Math.round((TTL_MS - st.ageMs) / 60000)) : 0,
          // 让模型能自己发现「开了但没用」—— 否则它以为提速了，实际每一步都在思考。
          note: st.on
            ? "Fast mode is ON. Note: a model that always thinks (its adapter declares no reasoning levels) " +
              "cannot be switched off; on such a model this flag changes nothing."
            : undefined,
        });
      }
      return Promise.resolve({ ok: false, error: "unknown action \"" + (args && args.action) + "\" (use on|off|status)" });
    },
  }));
}

export { apply, inject, name };

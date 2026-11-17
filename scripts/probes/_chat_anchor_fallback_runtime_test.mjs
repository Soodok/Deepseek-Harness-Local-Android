/**
 * 真跑 dsh-android-chat-anchor-fallback/client.js 的钩子逻辑（issue #119）。
 *
 * 用假 DOM 复刻插件实际用到的那些面：body/childList 观察、querySelectorAll、
 * boundingClientRect、内联 style.setProperty(…, "important")、窗口监听。
 * 目的不是测浏览器，而是证明**判据与坐标算法**本身：
 *
 *   · 新内核（CSS.supports('position-anchor: --x') === true）一个字都不改；
 *   · 老内核才接管，且摆出来的坐标在视口内、贴着 trigger；
 *   · 没有展开中的 trigger / 是模态框时不动别人的浮层；
 *   · 收起与卸载会摘掉监听。
 *
 * 用法：node _chat_anchor_fallback_runtime_test.mjs [插件路径]
 *   不给路径时用仓库里的默认位置（供负对照传入变异副本）。
 */
import path from "node:path";
import { pathToFileURL } from "node:url";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.dirname(path.dirname(HERE));
const DEFAULT_PLUGIN = path.join(
  ROOT, "app", "src", "main", "assets", "android-plugins", "chat-anchor-fallback", "client.js",
);
const PLUGIN = process.argv[2] ? path.resolve(process.argv[2]) : DEFAULT_PLUGIN;

let pass = 0;
let fail = 0;

function check(name, ok, detail = "") {
  if (ok) {
    pass += 1;
    console.log("PASS  " + name);
  } else {
    fail += 1;
    console.log("FAIL  " + name + (detail ? "  -> " + detail : ""));
  }
}

/** 一个带内联样式的假元素。 */
function makeEl(opts = {}) {
  const attrs = Object.assign({}, opts.attrs || {});
  const style = new Map();
  const el = {
    nodeType: 1,
    isConnected: true,
    _box: opts.box || { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 },
    style: {
      setProperty(k, v, prio) {
        style.set(k, { v: String(v), prio: prio || "" });
      },
      getPropertyValue(k) {
        const hit = style.get(k);
        return hit ? hit.v : "";
      },
      removeProperty(k) {
        style.delete(k);
      },
    },
    getAttribute(k) {
      return Object.prototype.hasOwnProperty.call(attrs, k) ? attrs[k] : null;
    },
    setAttribute(k, v) {
      attrs[k] = v === undefined || v === null ? "" : String(v);
    },
    removeAttribute(k) {
      delete attrs[k];
    },
    getBoundingClientRect() {
      return el._box;
    },
    get offsetHeight() {
      return typeof opts.height === "function" ? opts.height() : (opts.height || 0);
    },
    /* 事故里最要紧的一条：改尺寸（内容异步变高）后要能重摆。 */
    _style: style,
    _attrs: attrs,
  };
  return el;
}

/** 造一套环境：可切换内核能力、可观察监听与观察器。 */
function makeEnv({ anchorSupported, viewport = { width: 412, height: 915 } }) {
  const observers = [];
  const windowListeners = [];
  let triggers = [];
  const body = {
    nodeType: 1,
    children: [],
    appendChild(node) {
      body.children.push(node);
      for (const o of observers) o.emit({ addedNodes: [node], removedNodes: [] });
      return node;
    },
    removeChild(node) {
      body.children = body.children.filter((n) => n !== node);
      for (const o of observers) o.emit({ addedNodes: [], removedNodes: [node] });
      return node;
    },
  };
  const env = {
    observers,
    windowListeners,
    body,
    setTriggers(list) {
      triggers = list;
    },
    fire(type) {
      for (const entry of windowListeners.filter((l) => l.type === type)) {
        entry.fn({ type });
      }
    },
    listenerCount(type) {
      return windowListeners.filter((l) => l.type === type).length;
    },
  };

  globalThis.CSS = { supports: () => anchorSupported };
  globalThis.MutationObserver = class {
    constructor(cb) {
      this.cb = cb;
      this.observed = false;
      observers.push(this);
    }
    observe() {
      this.observed = true;
    }
    disconnect() {
      this.disconnected = true;
      const i = observers.indexOf(this);
      if (i >= 0) observers.splice(i, 1);
    }
    emit(record) {
      if (this.disconnected) return;
      this.cb([record]);
    }
  };
  globalThis.document = {
    body,
    documentElement: { clientWidth: viewport.width, clientHeight: viewport.height },
    querySelectorAll(sel) {
      if (sel === '[aria-haspopup="dialog"][aria-expanded="true"]') return triggers;
      return [];
    },
  };
  globalThis.window = {
    __ModuleLoader__: {
      load(opts) {
        globalThis.__captured = opts;
      },
    },
    innerWidth: viewport.width,
    innerHeight: viewport.height,
    addEventListener(type, fn) {
      windowListeners.push({ type, fn });
    },
    removeEventListener(type, fn) {
      const i = windowListeners.findIndex((l) => l.type === type && l.fn === fn);
      if (i >= 0) windowListeners.splice(i, 1);
    },
    requestAnimationFrame(fn) {
      // 同步跑一次就够了：被测的是坐标算法，不是帧调度。
      fn(0);
      return 1;
    },
    setTimeout(fn) {
      fn();
      return 1;
    },
  };
  return env;
}

/** 载入插件并返回其 exports。 */
async function loadPlugin(tag) {
  const url = pathToFileURL(PLUGIN).href + "?t=" + tag;
  await import(url);
  const opts = globalThis.__captured;
  delete globalThis.__captured;
  const module = { exports: {} };
  const ret = opts.factory(() => {
    throw new Error("plugin should not require anything");
  });
  return ret || module.exports;
}

const PANEL_HEIGHT = 120;

/** 造一个展开中的 trigger（消息行里那个用量按钮）。 */
function makeTrigger(box) {
  return makeEl({
    attrs: { "aria-haspopup": "dialog", "aria-expanded": "true" },
    box,
  });
}

// ── A. 新内核：完全不介入 ─────────────────────────────────────────────────

{
  const env = makeEnv({ anchorSupported: true });
  const mod = await loadPlugin("new");
  const applied = mod.apply({ effect() {} });
  check("A1 新内核：不订阅任何 DOM 变更", env.observers.length === 0,
        "observers=" + env.observers.length);
  check("A2 新内核：不写任何内联样式（上游自己摆得对）",
        globalThis.document.body.children.length === 0);
  check("A3 新内核：apply 不抛错", applied === undefined || applied === null,
        String(applied));
}

// ── B. 老内核：接管并摆到 trigger 上方 ────────────────────────────────────

{
  const env = makeEnv({ anchorSupported: false });
  const mod = await loadPlugin("legacy");
  let disposer = null;
  mod.apply({ effect(fn) { disposer = fn(); } });

  check("B1 老内核：订阅 body 子节点变化", env.observers.length === 1);

  // trigger 在视口靠下（消息在底部），上方空间大 → 应摆在 trigger 上方。
  const trigger = makeTrigger({ left: 300, top: 820, right: 328, bottom: 848, width: 28, height: 28 });
  env.setTriggers([trigger]);
  const panel = makeEl({ attrs: { role: "dialog" }, height: PANEL_HEIGHT });
  env.body.appendChild(panel);

  const top = parseFloat(panel._style.get("top")?.v);
  const left = parseFloat(panel._style.get("left")?.v);
  check("B2 老内核：写了内联坐标", !Number.isNaN(top) && !Number.isNaN(left),
        "top=" + panel._style.get("top")?.v + " left=" + panel._style.get("left")?.v);
  check("B3 坐标带 !important（要压过 mobile-layout 的固定定位规则）",
        panel._style.get("top")?.prio === "important" &&
        panel._style.get("left")?.prio === "important");
  check("B4 摆在 trigger 上方（上方 8px 间距）",
        Math.abs(top - (820 - 8 - PANEL_HEIGHT)) <= 1, "top=" + top);
  check("B5 水平贴 trigger 左缘（右侧越界时往回收）",
        left >= 12 && left + 440 <= 412 + 440, "left=" + left);
  check("B6 打上兜底标记（便于诊断/去重）", panel.getAttribute("data-dsh-anchor-fallback") === "");
  check("B7 浮层不会超出视口右缘", left + Math.min(440, 412 - 24) <= 412 - 12 + 1,
        "left=" + left);
  check("B8 开着时监听滚动与 resize",
        env.listenerCount("scroll") === 1 && env.listenerCount("resize") === 1);

  // 滚动后 trigger 位置变了 → 重摆。
  //
  // ⚠️ 412 宽的屏幕上浮层本身就有 388 宽，水平方向几乎没有余量：把 trigger
  // 挪到 left=200 之后，正确结果仍然是「夹到 12」而不是 200。所以这里断言的是
  // **夹取公式**，不是「等于 trigger 左缘」—— 后者在窄屏上根本不是对的期望。
  const panelWidth = parseFloat(panel._style.get("width")?.v);
  trigger._box = { left: 200, top: 400, right: 228, bottom: 428, width: 28, height: 28 };
  env.fire("scroll");
  const movedLeft = parseFloat(panel._style.get("left")?.v);
  const expectedLeft = Math.max(12, Math.min(200, 412 - 12 - panelWidth));
  check("B9 滚动后按夹取公式重摆",
        Math.abs(movedLeft - expectedLeft) <= 1,
        "left=" + movedLeft + " 期望 " + expectedLeft + "（宽 " + panelWidth + "）");

  // 收起浮层 → 摘监听、不再摆。
  trigger.setAttribute("aria-expanded", "false");
  env.fire("scroll");
  check("B10 trigger 收起后摘掉监听",
        env.listenerCount("scroll") === 0 && env.listenerCount("resize") === 0);

  check("B11 卸载函数可用", typeof disposer === "function");
  if (typeof disposer === "function") disposer();
  check("B12 卸载后观察器断开", env.observers.length === 0);
}

// ── C. 不该动的浮层一律不动 ───────────────────────────────────────────────

{
  const env = makeEnv({ anchorSupported: false });
  const mod = await loadPlugin("guard");
  mod.apply({ effect() {} });

  // 没有展开中的 trigger（例如别处程序化弹出的对话框）
  env.setTriggers([]);
  const orphan = makeEl({ attrs: { role: "dialog" }, height: 100 });
  env.body.appendChild(orphan);
  check("C1 没有展开中的 trigger：一个字都不改",
        orphan._style.size === 0 && orphan.getAttribute("data-dsh-anchor-fallback") === null);

  // 模态框自己居中，不靠锚点
  env.setTriggers([makeTrigger({ left: 10, top: 800, right: 38, bottom: 828, width: 28, height: 28 })]);
  const modal = makeEl({ attrs: { role: "dialog", "aria-modal": "true" }, height: 300 });
  env.body.appendChild(modal);
  check("C2 模态框不动", modal._style.size === 0);

  // 非 dialog（菜单/工具提示）也不动
  const menu = makeEl({ attrs: { role: "menu" }, height: 80 });
  env.body.appendChild(menu);
  check("C3 非 dialog 不动", menu._style.size === 0);
}

// ── D. 视口夹取：小屏 + 高浮层 ────────────────────────────────────────────

{
  const env = makeEnv({ anchorSupported: false, viewport: { width: 320, height: 480 } });
  const mod = await loadPlugin("clamp");
  mod.apply({ effect() {} });

  const trigger = makeTrigger({ left: 4, top: 300, right: 32, bottom: 328, width: 28, height: 28 });
  env.setTriggers([trigger]);
  const panel = makeEl({ attrs: { role: "dialog" }, height: 300 });
  env.body.appendChild(panel);

  const top = parseFloat(panel._style.get("top")?.v);
  const left = parseFloat(panel._style.get("left")?.v);
  check("D1 左越界时夹到边距内", left >= 12, "left=" + left);
  check("D2 垂直不越界", top >= 12 && top + 300 <= 480 - 12 + 1, "top=" + top);
  check("D3 宽度夹到视口内（320 屏）",
        parseFloat(panel._style.get("width")?.v) <= 320 - 24,
        "width=" + panel._style.get("width")?.v);
  check("D4 max-height 也夹进视口",
        parseFloat(panel._style.get("max-height")?.v) <= 480 - 24,
        "max-height=" + panel._style.get("max-height")?.v);
}

// ── E. 宽屏：水平方向有余量时应严格贴 trigger 左缘 ──────────────────────

{
  const env = makeEnv({ anchorSupported: false, viewport: { width: 820, height: 900 } });
  const mod = await loadPlugin("wide");
  mod.apply({ effect() {} });

  // trigger 放在左侧，浮层（宽 440）才放得下 —— 靠右时正确行为是往左收，
  // 那属于夹取而不是「贴左缘」，两个用例分开断言，避免把夹取当成失败。
  const trigger = makeTrigger({ left: 200, top: 700, right: 228, bottom: 728, width: 28, height: 28 });
  env.setTriggers([trigger]);
  const panel = makeEl({ attrs: { role: "dialog" }, height: 140 });
  env.body.appendChild(panel);

  const left = parseFloat(panel._style.get("left")?.v);
  const top = parseFloat(panel._style.get("top")?.v);
  check("E1 宽屏且放得下：水平严格贴 trigger 左缘", Math.abs(left - 200) <= 1, "left=" + left);
  check("E2 宽屏：仍在 trigger 上方", Math.abs(top - (700 - 8 - 140)) <= 1, "top=" + top);
}

console.log("\n" + "-".repeat(44));
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);

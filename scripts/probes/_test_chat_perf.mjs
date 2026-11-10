/**
 * [dsh-android] chat-perf 插件验证（v1.4.5，issue #26 第二层）。
 *
 * 用 jsdom 复刻引擎聊天流的结构，验证：
 *   ① 会话界面存在时注入样式表，且规则只作用于「聊天流的直接子行」；
 *   ② 样式表不碰导航条 / 输入框 / 侧栏（作用域严格）；
 *   ③ 会话界面晚于插件挂载时，观察器能补上注入；
 *   ④ 卸载后零残留；
 *   ⑤ **不劫持 DOM**：不改任何节点属性/结构（只加一条 <style>）。
 *
 * 这些是「不破坏现有行为」的底线 —— 至于 `content-visibility` 的实际性能收益，
 * 由浏览器实现决定，jsdom 无法模拟，故不在本文件断言。
 *
 * 运行：node _test_chat_perf.mjs
 */

import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

const SRC = readFileSync(
  new URL("../../app/src/main/assets/android-plugins/chat-perf/client.js", import.meta.url),
  "utf8",
);

let pass = 0, fail = 0;
const check = (label, cond, extra) => {
  if (cond) { console.log("   ✓ " + label); pass++; }
  else { console.log("   ✗ " + label + (extra ? "  (" + extra + ")" : "")); fail++; }
};

/** 引擎聊天流的真实结构（属性名取自 dsh-client-ui-chat 源码）。 */
function sessionHtml() {
  return `<!doctype html><html><body>
    <div data-slot="conversation.chat.flow">
      <div data-chat-turn="0" data-chat-flow-key="a"><span>turn 0</span></div>
      <div data-chat-turn="1" data-chat-flow-key="b"><span>turn 1</span></div>
      <div data-chat-turn="2" data-chat-flow-key="c"><span>turn 2</span></div>
      <div data-chat-group-key="g1"><span>group</span></div>
    </div>
    <nav><button class="mark">mark</button></nav>
    <div data-composer-seat><textarea></textarea></div>
    <aside data-sidebar-right-panel><span>right</span></aside>
  </body></html>`;
}

function boot(html) {
  const dom = new JSDOM(html, { pretendToBeVisual: true });
  const { window } = dom;
  let factory = null;
  window.__ModuleLoader__ = { load: (d) => { factory = d.factory; } };
  new Function(
    "window", "document", "MutationObserver", "CustomEvent",
    "setTimeout", "clearTimeout", "requestAnimationFrame",
    SRC,
  )(
    window, window.document, window.MutationObserver, window.CustomEvent,
    window.setTimeout.bind(window), window.clearTimeout.bind(window),
    window.requestAnimationFrame.bind(window),
  );
  const mod = factory((n) => require(n));
  const cleanups = [];
  const ctx = { effect: (fn) => { cleanups.push(fn()); } };
  // apply() 的返回值就是卸载函数 —— 引擎在插件卸载时调用它。
  // 之前这里丢弃了返回值，导致「卸载后移除样式表」永远测不到（假失败）。
  const dispose = mod.apply(ctx);
  return { window, cleanups, dispose };
}

const STYLE_ID = "dsh-android-chat-perf-style";

console.log("=== ① 会话界面存在时注入样式 ===");
{
  const { window } = boot(sessionHtml());
  const style = window.document.getElementById(STYLE_ID);
  check("样式表已注入", style !== null);
  const css = style ? style.textContent : "";
  check("规则限定在聊天流容器内", css.includes('[data-slot="conversation.chat.flow"]'));
  check("规则只作用于带 data-chat-turn 的行", css.includes("[data-chat-turn]"));
  check("使用 content-visibility: auto", css.includes("content-visibility: auto"));
  check("提供占位高度（滚动条不跳）", css.includes("contain-intrinsic-size"));
}

console.log("\n=== ② 作用域严格：不碰导航条/输入框/侧栏 ===");
{
  const { window } = boot(sessionHtml());
  const css = (window.document.getElementById(STYLE_ID) || {}).textContent || "";
  check("不含 nav 选择器", !/nav\b/.test(css));
  check("不含 composer 选择器", !/composer/.test(css));
  check("不含 sidebar 选择器", !/sidebar/.test(css));
  // 选择器必须是「直接子元素」形式，不能深入行内部
  check("使用直接子元素选择器 (>)" , css.includes("> [data-chat-turn]"));
}

console.log("\n=== ③ 不劫持 DOM：节点结构与属性零改动 ===");
{
  const before = sessionHtml();
  const { window } = boot(before);
  const rows = [...window.document.querySelectorAll("[data-chat-turn]")];
  check("仍为 3 个轮次行", rows.length === 3);
  check("行属性未被改写", rows.every((r) => r.getAttribute("data-chat-turn") !== null));
  check("没有新增内联 style 属性", rows.every((r) => !r.hasAttribute("style")));
  const styleCount = window.document.querySelectorAll("style").length;
  check("只增加了 1 个 <style>", styleCount === 1, "style=" + styleCount);
}

console.log("\n=== ④ 晚挂载：会话界面后出现时能补注入 ===");
{
  const { window } = boot(`<!doctype html><html><body><div id="shell"></div></body></html>`);
  check("初始未注入", window.document.getElementById(STYLE_ID) === null);
  // 模拟切到会话页
  const shell = window.document.getElementById("shell");
  const flow = window.document.createElement("div");
  flow.setAttribute("data-slot", "conversation.chat.flow");
  shell.appendChild(flow);
  // MutationObserver 是微任务；等一个 tick
  await new Promise((r) => setTimeout(r, 0));
  check("会话出现后已注入", window.document.getElementById(STYLE_ID) !== null);
}

console.log("\n=== ⑤ 卸载零残留 ===");
{
  const { window, cleanups, dispose } = boot(sessionHtml());
  check("注入前存在", window.document.getElementById(STYLE_ID) !== null);
  cleanups.forEach((fn) => { try { fn(); } catch {} });
  // 引擎卸载插件时调用 apply 返回的 dispose
  if (typeof dispose === "function") dispose();
  check("样式表已移除", window.document.getElementById(STYLE_ID) === null);
}

console.log("\n────────────────────────────");
console.log(`通过 ${pass} / 失败 ${fail}`);
if (fail > 0) process.exit(1);

/**
 * 用 CDP 在页面里 fetch **它自己刚刚加载过的那条 combo URL**，确认浏览器拿到的是补丁版源码。
 *
 * 关键：URL 不能用我猜的 rev —— 必须取 performance entries 里那条真实资源名。
 * 之前手拼 rev 会挂起（服务端认为 rev 不符），看起来像"卡死"，其实是 URL 错。
 */
const WS = process.argv[2];
if (!WS) { console.log("usage: cdp-served-exact.cjs <ws-url>"); process.exit(2); }

const ws = new WebSocket(WS);
let id = 0;
const pending = new Map();
ws.addEventListener("message", (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
});
const send = (method, params) => new Promise((resolve, reject) => {
  const n = ++id;
  pending.set(n, (m) => (m.error ? reject(Error(JSON.stringify(m.error))) : resolve(m.result)));
  ws.send(JSON.stringify({ id: n, method, params }));
});

const expr = `(async () => {
  const url = performance.getEntriesByType("resource")
    .map((e) => e.name)
    .find((name) => name.includes("ui-conversation/client.js"));
  if (!url) return { err: "no combo url in performance entries" };
  const res = await fetch(url, { credentials: "same-origin" });
  const text = await res.text();
  return {
    status: res.status,
    bytes: text.length,
    marker: text.includes("dsh-android: steer"),
    nextStep: text.includes("next-step"),
    hasSteerQueue: text.includes("steerQueue"),
    rev: (url.match(/rev=[0-9a-f]+/) || [""])[0],
  };
})()`;

const timeout = setTimeout(() => { console.log("CDP_TIMEOUT"); process.exit(1); }, 30000);
ws.addEventListener("open", async () => {
  try {
    await send("Runtime.enable", {});
    const r = await send("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true });
    const v = r.result && r.result.value;
    clearTimeout(timeout);
    console.log(JSON.stringify(v, null, 1));
    if (!v || v.status !== 200) { console.log("VERIFY_FAIL"); process.exit(1); }
    const ok = v.marker === true && v.nextStep === true;
    console.log(ok ? "VERIFY_OK_PATCH_IS_LIVE" : "VERIFY_FAIL_no_patch_marker");
    process.exit(ok ? 0 : 1);
  } catch (e) {
    clearTimeout(timeout);
    console.log("CDP_ERROR " + e.message);
    process.exit(1);
  }
});
ws.addEventListener("error", () => { clearTimeout(timeout); console.log("WS_ERROR"); process.exit(1); });

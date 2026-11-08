#!/usr/bin/env node
/**
 * patch-agent-preset-fallback.py 的语义测试。
 *
 * 关键设计：不手抄语义。第一版把补丁后的 resolve() 手抄在本文件里，实测发现
 * 把补丁改成 if (false)（等于完全不降级）后本探针照样全绿 —— 它在测自己的
 * 复刻，不是在测补丁。所以改为从补丁脚本取出真实的 PATCHED 片段来跑。
 *
 * 用法: node _agent_preset_fallback.mjs <patched-js-file>
 * 取法由 Python 侧完成（importlib 载入补丁模块 -> 取 PATCHED -> 拼成 JS 写临时文件）。
 */

import { readFileSync } from "node:fs";

const file = process.argv[2];
if (!file) {
  console.error("usage: node _agent_preset_fallback.mjs <patched-js-file>");
  process.exit(2);
}

const body = readFileSync(file, "utf8");
if (!body.includes("async resolve(id)")) {
  console.error("patched snippet has no resolve():\n" + body.slice(0, 400));
  process.exit(2);
}

class RemoteError extends Error {
  constructor(code, message, data) {
    super(message);
    this.code = code;
    this.data = data;
  }
}

// 把片段拼成类：resolve 的 defs 来自 this，RemoteError 从闭包拿。
const factory = new Function(
  "RemoteError",
  "return class PatchedRegistry {\n" +
  "  constructor(defs, defaultId) {\n" +
  "    this.definitions = new Map(Object.entries(defs));\n" +
  "    this.defaultId = defaultId;\n" +
  "    this.diagnostic = async () => undefined;\n" +
  "  }\n" +
  // ANCHOR 只覆盖 resolve() 前半段，末尾闭合花括号要补。
  body + "\n  }\n" +
  "};",
)(RemoteError);

let pass = 0;
let fail = 0;

function check(name, ok, detail) {
  if (ok) {
    console.log("PASS  " + name);
    pass++;
  } else {
    console.log("FAIL  " + name + (detail ? "  [" + detail + "]" : ""));
    fail++;
  }
}

const defs = {
  standard: { config: { id: "standard", plugins: [] } },
  jli7399386: { config: { id: "jli7399386", plugins: [] } },
};
const reg = new factory(defs, "standard");

const ok = await reg.resolve("jli7399386");
check("valid preset resolves to itself", ok.id === "jli7399386", JSON.stringify(ok));
check("valid preset has no fellBackFrom", ok.fellBackFrom === undefined, JSON.stringify(ok));

const gone = await reg.resolve("qq7399386-01");
check("deleted preset falls back to default", gone.id === "standard", JSON.stringify(gone));
check("fallback reports the original id", gone.fellBackFrom === "qq7399386-01", JSON.stringify(gone));

const dflt = await reg.resolve(undefined);
check("omitted id uses the default", dflt.id === "standard", JSON.stringify(dflt));
check("omitted id is not a fallback", dflt.fellBackFrom === undefined, JSON.stringify(dflt));

let threw = null;
try {
  await new factory({}, "standard").resolve("whatever");
} catch (e) {
  threw = e;
}
check("missing default still throws", threw !== null && threw.code === "agent-preset/not-found",
      threw === null ? "no throw" : String(threw.code));

console.log("\n" + pass + " passed, " + fail + " failed");
process.exit(fail ? 1 : 0);

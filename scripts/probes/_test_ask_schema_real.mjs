/**
 * 用**引擎真实**的 validateJsonSchemaValue 校验 ask-bridge 的返回值。
 *
 * 为什么值得单独一个探针：手写一份"差不多"的 schema 校验，很容易比真实校验器宽松
 * （additionalProperties、required、string[] 三项里漏一项就假绿）。这里直接 import
 * 运行时包导出的 validateJsonSchemaValue，再喂**从 dsh-tool-ask-user 原文抄下来**的
 * output.schema —— 判据与被测对象同源，改坏必红。
 *
 * 跑法：node scripts/probes/_test_ask_schema_real.mjs
 */
import { createRequire } from "node:module";
import { existsSync, readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const ROOT = process.cwd();
// 只解出 dsh-tools 依赖闭包的那几个包（cordis/cosmokit/dsh-base 等），
// 全量解 zip 在 Windows 上太慢且没必要。
const TOOLS_PKG = ROOT + "/.scratch/validator/lib/node_modules/@deepseek-ai/dsh-tools/lib/index.js";
const ASK_PKG = ROOT + "/.scratch/validator/lib/node_modules/@deepseek-ai/dsh-tool-ask-user/lib/index.js";

let ok = 0, fail = 0;
function check(label, cond, extra = "") {
  if (cond) { ok++; console.log("PASS  " + label); }
  else { fail++; console.log("FAIL  " + label + (extra ? "  -> " + extra : "")); }
}

// ── 1. 从真实运行时取校验器 ─────────────────────────────────────────
// 依赖缺失时先自动解一次（可复现），解不出来就红着退出并给出命令 —— 不许静默跳过。
if (!existsSync(TOOLS_PKG)) {
  console.log("runtime packages missing -> extracting (scripts/probes/_fetch_runtime_js.py)");
  const { spawnSync } = await import("node:child_process");
  // ⚠️ Windows 上 Node 不认 PATH 里的 "python3"（那是 Git Bash 的 shell 函数，
  // 不是 .exe；实测 spawnSync 直接 ENOENT、status=null）。按顺序试真实可执行名。
  const candidates = ["python", "python3", "py"];
  let ex = null;
  for (const bin of candidates) {
    ex = spawnSync(bin, ["scripts/probes/_fetch_runtime_js.py"], { encoding: "utf8", stdio: "inherit" });
    if (ex.status === 0) break;
    if (ex.error === undefined && ex.status !== null) break;  // 跑起来了但不成功：不再换名
  }
  if (ex === null || ex.status !== 0) {
    console.log("(extraction failed: " + (ex && (ex.error ? ex.error.code : "exit " + ex.status)) + ")");
  }
}
if (!existsSync(TOOLS_PKG)) {
  console.log("FAIL  cannot find " + TOOLS_PKG);
  console.log("      run: python3 scripts/probes/_fetch_runtime_js.py");
  process.exit(1);
}
const tools = await import(pathToFileURL(TOOLS_PKG).href);
check("runtime exports validateJsonSchemaValue", typeof tools.validateJsonSchemaValue === "function");
check("runtime exports ToolOutputError", typeof tools.ToolOutputError === "function");

// ── 2. 从真实插件源码抄 output.schema（answers 那一支）───────────────
const askSrc = readFileSync(ASK_PKG, "utf8");
const schema = {
  type: "object",
  additionalProperties: false,
  required: ["answers"],
  properties: {
    answers: {
      type: "array",
      required: true,
      items: {
        type: "object",
        additionalProperties: false,
        required: ["id", "selected"],
        properties: {
          id: { type: "string", required: true },
          selected: { type: "array", required: true, items: { type: "string" } },
          custom: { type: "string" },
        },
      },
    },
  },
};
check("schema text unchanged upstream (additionalProperties:false present)",
  askSrc.includes("additionalProperties: false"));
check("schema text unchanged upstream (selected semantics present)",
  askSrc.includes("Selected option labels."));

// ── 3. 旧实现的返回结构必须被判红（这是本探针的存在理由）─────────────
const OLD = { source: "overlay_panel", answers: ["选项A"] };
const oldViolations = tools.validateJsonSchemaValue(schema, OLD, "value");
check("OLD shape is rejected by the real validator", oldViolations.length > 0,
  JSON.stringify(oldViolations).slice(0, 200));
check("OLD shape names the undeclared property",
  oldViolations.some((v) => String(v).includes("source")),
  JSON.stringify(oldViolations).slice(0, 200));

// ── 4. 新实现的两种返回都必须通过 ─────────────────────────────────
const NEW_OPTION = { answers: [{ id: "q1", selected: ["A 立即修"] }] };
const NEW_FREE = { answers: [{ id: "q1", selected: [], custom: "我自己写的理由" }] };
const NEW_MULTI = { answers: [
  { id: "q1", selected: ["A"] },
  { id: "q2", selected: [], custom: "手打" },
] };

check("NEW option-backed answer passes", tools.validateJsonSchemaValue(schema, NEW_OPTION, "value").length === 0,
  JSON.stringify(tools.validateJsonSchemaValue(schema, NEW_OPTION, "value")));
check("NEW free-text answer passes", tools.validateJsonSchemaValue(schema, NEW_FREE, "value").length === 0,
  JSON.stringify(tools.validateJsonSchemaValue(schema, NEW_FREE, "value")));
check("NEW two-question batch passes", tools.validateJsonSchemaValue(schema, NEW_MULTI, "value").length === 0,
  JSON.stringify(tools.validateJsonSchemaValue(schema, NEW_MULTI, "value")));

// ── 5. 最小破坏：真实校验器必须能抓住每一种错法 ───────────────────────
const breaks = [
  ["裸字符串 answers", { answers: ["A"] }],
  ["缺 id", { answers: [{ selected: ["A"] }] }],
  ["缺 selected", { answers: [{ id: "q1" }] }],
  ["selected 里塞非字符串", { answers: [{ id: "q1", selected: [1] }] }],
  ["多一个未声明字段", { answers: [{ id: "q1", selected: ["A"], extra: 1 }] }],
  ["custom 不是字符串", { answers: [{ id: "q1", selected: [], custom: 5 }] }],
];
for (const [label, value] of breaks) {
  const v = tools.validateJsonSchemaValue(schema, value, "value");
  check("break detected: " + label, v.length > 0, JSON.stringify(v).slice(0, 160));
}

console.log("");
console.log(ok + " passed, " + fail + " failed");
process.exit(fail === 0 ? 0 : 1);

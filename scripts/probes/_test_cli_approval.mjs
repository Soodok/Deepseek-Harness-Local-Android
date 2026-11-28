import { pathToFileURL } from "node:url";
import { mock } from "node:test";

const pluginPath = process.argv[2] || "app/src/main/assets/android-plugins/cli-approval.mjs";
let passed = 0;
let failed = 0;
function check(name, value, detail = "") {
  if (value) { passed++; console.log("PASS " + name); }
  else { failed++; console.log("FAIL " + name + (detail ? " -> " + detail : "")); }
}
function makeContext(approval) {
  const hooks = {};
  return {
    hooks, approval,
    workingDirectory: {
      defaultDirectory: "/fixture/default",
      get(session) { return "/fixture/" + session.header.id; },
    },
    systemPrompt: {
      getSectionOrder(key) {
        if (key !== "TOOL_BASH") throw new TypeError("Unknown Host prompt section order: " + key);
        return 100;
      },
      section({ name, order }) {
        if (!Number.isFinite(order)) throw new TypeError("prompt section " + name + " order must be a finite number");
      },
    },
    on(event, handler) { (hooks[event] ||= []).push(handler); },
  };
}
async function dispatch(hooks, exec) {
  const handlers = hooks["tools/execute"] || [];
  check("tools/execute hook registered", handlers.length === 1, "handlers=" + handlers.length);
  let nextCalls = 0;
  const next = async () => {
    nextCalls++;
    return { isError: false, value: { native: true }, content: [{ type: "text", text: "native" }] };
  };
  let result;
  let error;
  try { result = await handlers[0](exec, next); } catch (caught) { error = caught; }
  return { result, nextCalls, error };
}
function exec(command, overrides = {}) {
  return {
    name: "bash",
    arguments: { command },
    callId: "call-cli-1",
    rootCallId: "root-cli-1",
    agent: { id: "agent-cli-1", session: { header: { id: "session-cli-1" } } },
    signal: new AbortController().signal,
    ...overrides,
  };
}
function text(result) {
  return (result?.content || []).map((block) => block?.text || "").join("\n");
}
// Foreground branch of dsh-tool-bash's closed output schema, specialised to receipts.
function foregroundReceipt(result, { cwd, exitCode, timedOut = false }) {
  const value = result?.value;
  const keys = ["kind", "cwd", "exitCode", "signal", "timedOut", "aborted", "timeoutMs", "stdout", "stderr"];
  const stream = (item) => item && Object.keys(item).length === 2 &&
    typeof item.text === "string" && item.truncated === false;
  return value && Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key)) && value.kind === "foreground" &&
    value.cwd === cwd && value.exitCode === exitCode && value.signal === null &&
    value.timedOut === timedOut && value.aborted === false && value.timeoutMs === 20_000 &&
    stream(value.stdout) && stream(value.stderr) &&
    (value.stdout.text.length > 0 || value.stderr.text.length > 0) &&
    text(result).includes("[exit code: " + exitCode + "]");
}

async function main() {
  let requests = [];
  let mode = "allow";
  const approval = {
    async request(input) {
      requests.push(input);
      if (mode === "allow") return "allowed-once";
      if (mode === "reject") return "rejected";
      return await new Promise((resolve) => input.signal.addEventListener("abort", () => resolve("cancelled"), { once: true }));
    },
  };
  const module = await import(pathToFileURL(pluginPath).href);
  check("plugin exports apply", typeof module.apply === "function");
  check("plugin has the expected name", module.name === "android-cli-approval");
  check("plugin requests the Host approval service", Array.isArray(module.inject) && module.inject.includes("approval"));
  const context = makeContext(approval);
  module.apply(context);

  mode = "allow";
  const allowed = await dispatch(context.hooks, exec("dsh-request-permission --capability camera --reason \"Capture a diagnostic image\""));
  check("allow does not execute the shell command", allowed.nextCalls === 0);
  check("allow returns a successful command result", allowed.result?.isError === false);
  check("allow conforms to the Bash result schema", foregroundReceipt(allowed.result, { cwd: "/fixture/session-cli-1", exitCode: 0 }));
  check("allow returns the approval outcome", text(allowed.result).includes("allowed-once"));
  check("approval is bound to the invoking agent", requests[0]?.agent?.id === "agent-cli-1");
  check("approval carries the exact tool call ID", requests[0]?.callId === "call-cli-1");
  check("approval identifies the specific command", requests[0]?.toolName === "dsh-request-permission");
  check("approval reason contains the requested capability", requests[0]?.reason?.includes("camera"));

  mode = "reject";
  const rejected = await dispatch(context.hooks, exec("dsh-request-permission --capability camera --reason rejected"));
  check("reject does not execute the shell command", rejected.nextCalls === 0);
  check("reject is reported as a non-error command result", rejected.result?.isError === false);
  check("reject conforms to the Bash result schema", foregroundReceipt(rejected.result, { cwd: "/fixture/session-cli-1", exitCode: 1 }));
  check("reject result tells the agent permission was refused", text(rejected.result).includes("rejected"));

  mode = "timeout";
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const pending = dispatch(context.hooks, exec("dsh-request-permission --capability camera --reason timeout", { callId: "call-timeout" }));
    const signal = requests.at(-1)?.signal;
    mock.timers.tick(19_999);
    check("request remains open before 20 seconds", signal?.aborted === false);
    mock.timers.tick(1);
    check("deadline aborts the Host request at 20 seconds", signal?.aborted === true);
    const timedOut = await pending;
    check("timeout does not execute the shell command", timedOut.nextCalls === 0);
    check("timeout is reported as a refusal", text(timedOut.result).startsWith("rejected:"));
    check("timeout has a distinct receipt", text(timedOut.result).includes("within 20 seconds"));
    check("timeout conforms to the Bash result schema", foregroundReceipt(timedOut.result, { cwd: "/fixture/session-cli-1", exitCode: 1, timedOut: true }));
  } finally {
    mock.timers.reset();
  }
  mode = "reject";

  const beforeUnsafe = requests.length;
  const unsafe = await dispatch(context.hooks, exec("dsh-request-permission --capability camera; touch /tmp/should-not-run --reason bad"));
  check("shell metacharacters are rejected", unsafe.nextCalls === 0);
  check("unsafe command never reaches approval", requests.length === beforeUnsafe);
  check("unsafe command returns an error", unsafe.result?.isError === true);
  check("invalid command conforms to the Bash result schema", foregroundReceipt(unsafe.result, { cwd: "/fixture/session-cli-1", exitCode: 2 }));

  const other = await dispatch(context.hooks, exec("printf safe"));
  check("ordinary bash commands pass through", other.nextCalls === 1);
  check("ordinary bash command does not request approval", requests.length === beforeUnsafe);

  const second = await dispatch(context.hooks, exec("dsh-request-permission --capability camera --reason second", {
    callId: "call-cli-2",
    agent: { id: "agent-cli-2", session: { header: { id: "session-cli-2" } } },
  }));
  check("a second invocation creates a fresh request", requests.length === beforeUnsafe + 1);
  check("second request keeps its own agent identity", requests.at(-1)?.agent?.id === "agent-cli-2");
  check("second request keeps its own call ID", requests.at(-1)?.callId === "call-cli-2");
  check("second invocation is not replayed from the first", second.result?.isError === false && text(second.result).includes("rejected"));

  console.log("passed=" + passed + " failed=" + failed);
  process.exitCode = failed === 0 ? 0 : 1;
}
main().catch((error) => { console.error(error); process.exitCode = 2; });
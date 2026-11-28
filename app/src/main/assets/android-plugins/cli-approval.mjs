/** CLI entry point for a bounded, single-use Host approval request. */
export const name = "android-cli-approval";
export const inject = ["approval", "systemPrompt", "workingDirectory"];

const COMMAND = "dsh-request-permission";
const WAIT_MS = 20_000;
const START = /^[ \t]*dsh-request-permission(?![\w-])/;
const SYNTAX = /^[ \t]*dsh-request-permission[ \t]+--capability[ \t]+(?:"([a-z][a-z0-9-]{0,63})"|([a-z][a-z0-9-]{0,63}))[ \t]+--reason[ \t]+(?:"([^"\r\n]+)"|'([^'\r\n]+)'|([\w.,:-]+))[ \t]*$/u;
const CONTROL = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u;
const RECEIPTS = {
  allowed: ["allowed-once", "User approved this request once.", 0],
  rejected: ["rejected", "User did not approve this request.", 1],
  timeout: ["rejected", "User did not approve within 20 seconds.", 1],
  cancelled: ["cancelled", "The request was cancelled.", 1],
  unavailable: ["unavailable", "An interactive approval was unavailable.", 1],
  invalid: ["rejected", 'Usage: dsh-request-permission --capability <name> --reason "<reason>" (standalone foreground command).', 2],
};

function receipt(reason, cwd) {
  const [outcome, message, exitCode] = RECEIPTS[reason];
  const output = outcome + ": " + message;
  return {
    isError: reason === "invalid",
    value: {
      kind: "foreground",
      cwd,
      exitCode,
      signal: null,
      timedOut: reason === "timeout",
      aborted: false,
      timeoutMs: WAIT_MS,
      stdout: { text: reason === "invalid" ? "" : output, truncated: false },
      stderr: { text: reason === "invalid" ? output : "", truncated: false },
    },
    content: [{ type: "text", text: output + "\n[exit code: " + exitCode + "]" }],
  };
}

function parse(command) {
  if (command.length > 640 || /[\r\n]/.test(command)) return undefined;
  const parts = SYNTAX.exec(command);
  if (parts === null) return undefined;
  const capability = parts[1] ?? parts[2];
  const reason = (parts[3] ?? parts[4] ?? parts[5]).trim();
  if (!reason || reason.length > 400 || CONTROL.test(reason)) return undefined;
  return { capability, reason };
}

async function ask(approval, exec, { capability, reason }) {
  const { agent, callId, signal } = exec;
  if (!agent?.session || !callId || !signal) return "unavailable";
  if (signal.aborted) return "cancelled";
  const deadline = new AbortController();
  const timer = setTimeout(() => deadline.abort(), WAIT_MS);
  try {
    const outcome = await approval.request({
      agent,
      callId,
      toolName: COMMAND,
      reason: capability + ": " + reason,
      displayReason: {
        en: "Permission request for " + capability + " · closes after 20 seconds\n" + reason,
        zh: "申请权限：" + capability + "（20 秒内有效）\n" + reason,
      },
      signal: AbortSignal.any([signal, deadline.signal]),
    });
    if (deadline.signal.aborted) return "timeout";
    if (signal.aborted) return "cancelled";
    switch (outcome) {
      case "allowed-once": return "allowed";
      case "rejected": return "rejected";
      case "cancelled": return "cancelled";
      default: return "unavailable";
    }
  } catch {
    if (deadline.signal.aborted) return "timeout";
    if (signal.aborted) return "cancelled";
    return "unavailable";
  } finally {
    clearTimeout(timer);
  }
}

export function apply(ctx) {
  ctx.on("tools/execute", async (exec, next) => {
    const args = exec?.arguments;
    if (exec?.name !== "bash" || typeof args?.command !== "string" || !START.test(args.command)) return next();
    const session = exec.agent?.session;
    const cwd = session ? ctx.workingDirectory.get(session) : ctx.workingDirectory.defaultDirectory;
    const request = parse(args.command);
    if (!request || args.run_in_background === true || args.sandbox_permissions !== undefined || args.justification !== undefined) {
      return receipt("invalid", cwd);
    }
    return receipt(await ask(ctx.approval, exec, request), cwd);
  });
  ctx.systemPrompt.section({
    name: "android:cli-approval",
    order: ctx.systemPrompt.getSectionOrder("TOOL_BASH"),
    text: 'If the session allows interactive approvals, request a one-time user decision with the standalone foreground bash command dsh-request-permission --capability <name> --reason "<reason>". The Host waits up to 20 seconds. Check the receipt before continuing. This command records consent only; follow the normal permissions for the actual action.',
  });
}

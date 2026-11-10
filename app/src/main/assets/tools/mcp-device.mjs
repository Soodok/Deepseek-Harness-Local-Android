/**
 * [dsh-android] MCP server (stdio) — exposes every device bridge endpoint as an
 * MCP tool.
 *
 * ## Why this file exists
 * The bridge (127.0.0.1:3083) already gives the agent 37 device capabilities
 * (screen reading, tapping, HID injection, radios, SMS, sensors, ...). They are
 * reachable from inside the engine via the gate scripts, but *no external MCP
 * client* (Claude Desktop, Cursor, any agent host) can see them. This server is
 * the adapter: it speaks MCP over stdio and forwards each `tools/call` to the
 * bridge over loopback HTTP.
 *
 * ## Why the tool list is derived at runtime
 * `EndpointCatalog.kt` is the single source of truth for what the bridge can do.
 * Hardcoding a tool list here would immediately drift (that exact drift is why
 * the catalog exists — see the header of EndpointCatalog.kt). So on startup we
 * fetch `GET /dev/help?detail=1`, which carries each endpoint's id, method,
 * path, summary, params, examples and *limits*, and build the tools from that.
 * Add an endpoint to the catalog and it appears here on next start with no edit.
 *
 * ## Protocol
 * JSON-RPC 2.0, one message per line (MCP stdio framing: no Content-Length,
 * no embedded newlines). We answer the legacy `initialize` handshake with
 * `2025-11-25` and deliberately do **not** implement `server/discover`: a
 * 2026-era peer then gets -32601 and falls back to `initialize`, which is the
 * documented era-negotiation path. Answering discover here would claim 2026-era
 * wire semantics (ttlMs/cacheScope/resultType on every result) that we do not
 * implement.
 *
 * ## Auth
 * The bridge rejects anything without `X-DSH-Token`. The app exports
 * `DSH_BRIDGE_TOKEN` into the engine process, so a server launched from inside
 * the engine inherits it. Launching from outside (a desktop MCP host) requires
 * passing the token explicitly in the host's `env` block — it is deliberately
 * not persisted anywhere readable.
 *
 * ## Modes
 *   node mcp-device.mjs                 # serve MCP on stdin/stdout
 *   node mcp-device.mjs --list          # print the derived tool list (debug)
 *   node mcp-device.mjs --probe         # print one bridge call result (debug)
 *   node mcp-device.mjs --self-test     # run an in-process JSON-RPC round trip
 *
 * Env:
 *   DSH_BRIDGE_TOKEN   required for real calls (see above)
 *   DSH_BRIDGE_PORT    default 3083
 *   DSH_HELP_JSON      read the catalog from this file instead of the bridge
 *                      (used by tests, and by offline inspection)
 *
 * @module dsh-android-mcp-device
 */

import fs from "node:fs";
import { pathToFileURL } from "node:url";

/** Loopback bridge port (AgentBridge.PORT). */
const BRIDGE_PORT = process.env.DSH_BRIDGE_PORT || "3083";

/** Per-call timeout for bridge requests. Tool calls that hit the UI can be slow. */
const CALL_TIMEOUT_MS = 60_000;

/**
 * Endpoints that run a whole multi-step program inside one HTTP request.
 *
 * /loop/run executes a plan step by step and answers only when it finishes, so
 * the 60s default killed long plans the bridge itself was still running happily
 * (the bridge budget is now 300s). The client timeout must stay *above* the
 * server one: cutting the connection first means the caller never sees the
 * structured report and retries the plan — the one path that duplicates real
 * side effects (repeated taps, repeated submits).
 */
const LONG_CALL_PATHS = new Set(["/loop/run", "/batch"]);
const LONG_CALL_TIMEOUT_MS = 320_000;

/** Catalog fetch is local-only and must be fast; a hung bridge must not stall init. */
const CATALOG_TIMEOUT_MS = 10_000;

/** Legacy protocol revisions we can speak (these are what the 2025-era handshake allows). */
const SUPPORTED_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26"];

/** Version we answer with when the client asks for something unknown. */
const PREFERRED_VERSION = "2025-11-25";

const SERVER_INFO = { name: "dsh-android-device", version: "1.0.0" };

/**
 * Instructions handed to the client once. Kept short: it is prepended to every
 * conversation, and the per-tool descriptions already carry the detail.
 */
const INSTRUCTIONS = [
  "Tools in this server call into an Android phone's local capability bridge.",
  "They affect a real device, immediately and visibly: taps, keys, radios, SMS.",
  "",
  "Read before you act:",
  "  · `status` / `ping` first if you are unsure the phone is reachable.",
  "  · Many capabilities are user-gated. A tool that returns",
  "    `capability_disabled` means the human must enable that switch in the app",
  "    (Settings > Permissions > AI device capabilities); it is not a bug.",
  "  · Screen is asleep → input is dropped. Call `control` with op=wake, or",
  "    `device_wake` where available, before tapping.",
  "  · Each tool's description ends with that endpoint's known limits. Trust it.",
].join("\n");

// ---------------------------------------------------------------------------
// Bridge access
// ---------------------------------------------------------------------------

function bridgeHeaders() {
  const token = process.env.DSH_BRIDGE_TOKEN || "";
  return token ? { "X-DSH-Token": token } : {};
}

/**
 * GET the endpoint catalog.
 *
 * Reads `DSH_HELP_JSON` when set (test/offline), otherwise asks the bridge.
 * Returns the `groups` array. Throws when neither path yields a catalog — a
 * server with zero tools is worse than a loud failure, because the client
 * reports "connected, no tools" and the human concludes the phone is broken.
 */
async function fetchCatalogGroups() {
  const fromFile = process.env.DSH_HELP_JSON;
  if (fromFile) {
    return JSON.parse(fs.readFileSync(fromFile, "utf8")).groups || [];
  }
  const url = `http://127.0.0.1:${BRIDGE_PORT}/dev/help?detail=1`;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), CATALOG_TIMEOUT_MS);
  try {
    const res = await fetch(url, { headers: bridgeHeaders(), signal: ac.signal });
    const text = await res.text();
    if (!res.ok) throw new Error(`catalog HTTP ${res.status}: ${text.slice(0, 200)}`);
    const value = JSON.parse(text);
    if (!value || !Array.isArray(value.groups)) throw new Error("catalog has no groups");
    return value.groups;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Call one bridge endpoint.
 * @returns {{status:number, value:any, raw:string}}
 */
async function bridgeCall(method, path, query, body) {
  const qs = query && Object.keys(query).length > 0
    ? "?" + new URLSearchParams(query).toString()
    : "";
  const url = `http://127.0.0.1:${BRIDGE_PORT}${path}${qs}`;
  const init = { method, headers: bridgeHeaders() };
  if (method !== "GET" && body !== undefined) {
    init.headers = { ...init.headers, "Content-Type": "application/json" };
    init.body = typeof body === "string" ? body : JSON.stringify(body);
  }
  const ac = new AbortController();
  const budget = LONG_CALL_PATHS.has(path) ? LONG_CALL_TIMEOUT_MS : CALL_TIMEOUT_MS;
  const timer = setTimeout(() => ac.abort(), budget);
  try {
    const res = await fetch(url, { ...init, signal: ac.signal });
    const raw = await res.text();
    let value;
    try {
      value = JSON.parse(raw);
    } catch {
      // The compact screen dump is text/plain by design (saves JSON escaping).
      value = { text: raw };
    }
    return { status: res.status, value, raw };
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------------------
// Tool derivation
// ---------------------------------------------------------------------------

/**
 * MCP tool names must match the host's identifier rules; catalog ids contain
 * `/` (perms/status, ext/list). Normalise them and stay identifier-safe.
 */
function toolNameFor(id) {
  return String(id).replace(/[^A-Za-z0-9_-]/g, "_");
}

/**
 * Assign one unique tool name per endpoint.
 *
 * Catalog ids are not unique: `media`, `calendar` and `sms` each appear once per
 * HTTP method (read vs write), and `POST /dev/sms` is registered twice by the
 * catalog itself. A duplicate name is fatal for the client (it rejects the
 * whole list with "server listed tool ... more than once"), so disambiguate
 * deterministically instead of trusting the ids: the first use keeps the plain
 * id, later ones take a `_<method>` suffix, and anything still clashing gets a
 * numeric tail.
 */
function assignToolNames(entries) {
  const used = new Set();
  const out = [];
  for (const entry of entries) {
    const base = toolNameFor(entry.id);
    let name = base;
    if (used.has(name)) name = `${base}_${String(entry.method).toLowerCase()}`;
    let n = 2;
    while (used.has(name)) name = `${base}_${String(entry.method).toLowerCase()}_${n++}`;
    used.add(name);
    out.push({ name, entry });
  }
  return out;
}

/**
 * Turn one catalog entry into an MCP tool.
 *
 * The parameters are deliberately loose: `args` carries the same `k=v k=v`
 * form the `dev` gate script accepts (so the AI can reuse habit and docs),
 * `query`/`body` carry the structured form. Endpoint params in the catalog are
 * human prose, not JSON Schema, so inventing a strict schema per endpoint would
 * be fiction that rejects valid calls.
 */
function toolFromEntry(name, entry) {
  const lines = [entry.summary || entry.id];
  lines.push("");
  lines.push(`${entry.method} ${entry.path}`);
  if (entry.params) lines.push("", "Params: " + entry.params);
  if (entry.body) lines.push("", "Body example: " + entry.body);
  if (entry.examples && entry.examples.length) {
    lines.push("", "Examples: " + entry.examples.join(" | "));
  }
  if (entry.perm) lines.push("", "Needs permission: " + entry.perm);
  if (entry.cap) {
    lines.push(
      "",
      `Gated by capability switch "${entry.cap}"` +
        (entry.cap_enabled === false ? " (currently OFF in the app)" : "") +
        ". If the call returns capability_disabled, ask the human to enable it.",
    );
  }
  if (entry.limits) lines.push("", "Limits: " + entry.limits);

  const properties = {
    args: {
      type: "string",
      description:
        "Space-separated k=v pairs, same syntax as the `dev` gate script " +
        '(e.g. "type=accel limit=5 enabled=true"). true/false become booleans, ' +
        "bare numbers become numbers, values starting with [ or { are parsed as JSON.",
    },
  };
  if (entry.method !== "GET") {
    properties.body = {
      type: "object",
      description: "JSON request body. Overrides anything set via args.",
    };
  }

  return {
    name,
    description: lines.join("\n"),
    inputSchema: { type: "object", properties },
    _endpoint: entry,
  };
}

/** Parse the `args` k=v string with the same coercions the gate script uses. */
function parseArgs(spec) {
  const out = {};
  if (!spec) return out;
  for (const pair of String(spec).trim().split(/\s+/)) {
    if (!pair) continue;
    const i = pair.indexOf("=");
    if (i <= 0) {
      out[pair] = true; // bare flag, matches `dev` behaviour
      continue;
    }
    const key = pair.slice(0, i);
    const raw = pair.slice(i + 1);
    if (raw === "true" || raw === "false") out[key] = raw === "true";
    else if (/^-?\d+$/.test(raw)) out[key] = parseInt(raw, 10);
    else if (/^-?\d+\.\d+$/.test(raw)) out[key] = parseFloat(raw);
    else if (/^[[{]/.test(raw)) {
      try {
        out[key] = JSON.parse(raw);
      } catch {
        out[key] = raw;
      }
    } else out[key] = raw;
  }
  return out;
}

/**
 * Build tools from the catalog.
 *
 * GET endpoints take everything through the querystring; writable endpoints
 * split `body` keys off into the JSON body (the bridge reads them there) and
 * leave the rest in the query.
 */
function buildTools(groups, only) {
  const flat = [];
  for (const group of groups) {
    for (const entry of group.items || []) flat.push(entry);
  }
  const kept = only === undefined ? flat : flat.filter((e) => only.has(e.id));
  return assignToolNames(kept).map(({ name, entry }) => {
    const tool = toolFromEntry(name, entry);
    tool._group = (groups.find((g) => (g.items || []).includes(entry)) || {}).group;
    return tool;
  });
}

/**
 * Optional allow-list of catalog ids, from `--only a,b,c` or DSH_MCP_ONLY.
 *
 * The full catalog is 40+ endpoints. An MCP host that only needs to *see* the
 * screen should not pay for all of them: every registered tool costs prompt
 * space on every request and invalidates the provider's cached prefix. The
 * app's own engine registers this server with --only screenshot.
 */
function parseOnly(argv) {
  const parts = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--only") {
      parts.push(argv[i + 1] || "");
      i++;
    } else if (a.startsWith("--only=")) {
      parts.push(a.slice("--only=".length));
    }
  }
  if (parts.length === 0 && process.env.DSH_MCP_ONLY) parts.push(process.env.DSH_MCP_ONLY);
  const ids = parts.join(",").split(",").map((s) => s.trim()).filter(Boolean);
  return ids.length > 0 ? new Set(ids) : undefined;
}

// ---------------------------------------------------------------------------
// tools/call
// ---------------------------------------------------------------------------

/**
 * Whether a bridge response is a picture rather than data.
 *
 * /screenshot answers {ok, format, width, height, base64}. Dumping that JSON as
 * text costs a few hundred KB of context AND still does not show the model the
 * picture — the caller has to save it and read it back. Returning a real MCP
 * image block instead lets the harness store it as an attachment and put it in
 * the conversation directly.
 */
function imageBlockOf(entry, value) {
  if (entry.path !== "/screenshot") return undefined;
  if (!value || typeof value !== "object") return undefined;
  const data = typeof value.base64 === "string" ? value.base64 : "";
  // The harness re-encodes and compares byte for byte, so only canonical
  // base64 is accepted; anything else would be rejected downstream anyway.
  if (!data || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) return undefined;
  const fmt = String(value.format || "png").toLowerCase();
  const mimeType = fmt === "jpg" || fmt === "jpeg" ? "image/jpeg"
    : fmt === "webp" ? "image/webp"
    : "image/png";
  return { type: "image", data, mimeType };
}

/** The same response with the payload dropped: the image block already carries it. */
function withoutPayload(value) {
  if (!value || typeof value !== "object" || typeof value.base64 !== "string") return value;
  const { base64, ...rest } = value;
  return { ...rest, image: "attached below as an image block" };
}

async function runTool(tools, name, rawArgs) {
  const tool = tools.find((t) => t.name === name);
  if (!tool) {
    return {
      isError: true,
      text: `unknown tool "${name}" — ${tools.length} tools available: ` +
        tools.slice(0, 12).map((t) => t.name).join(", ") + (tools.length > 12 ? ", ..." : ""),
    };
  }
  const entry = tool._endpoint;
  const args = rawArgs || {};

  const flat = { ...parseArgs(args.args) };
  const explicitQuery = args.query && typeof args.query === "object" ? args.query : {};
  Object.assign(flat, explicitQuery);

  let query = {};
  let body;
  if (entry.method === "GET") {
    query = flat;
  } else if (args.body && typeof args.body === "object") {
    body = args.body;
    query = flat;
  } else {
    // No explicit body: treat the flat k=v map as the body. The bridge's write
    // endpoints read named fields from the JSON body, and an empty body would
    // make every such call fail validation for no reason.
    body = flat;
    if (entry.path === "/dev/sms" || entry.path === "/dev/dialog") {
      // These two accept query filters as well; duplicate keys are harmless and
      // keep `?q=` filtering working when the caller passes it.
      query = {};
    }
  }

  try {
    const { status, value, raw } = await bridgeCall(entry.method, entry.path, query, body);
    const ok = status >= 200 && status < 300 && !(value && value.ok === false);
    const image = ok ? imageBlockOf(entry, value) : undefined;
    const head = ok
      ? `${entry.method} ${entry.path} → ${status}`
      : `${entry.method} ${entry.path} → ${status} ${JSON.stringify(value?.error ?? value)}`;
    // An image result gets a short JSON line instead of the raw body: the
    // base64 would be truncated at 12k (useless) and cost ~300k tokens.
    const shown = image !== undefined ? JSON.stringify(withoutPayload(value)) : raw;
    return {
      isError: !ok,
      text: `${head}\n\n${shown.length > 12_000 ? shown.slice(0, 12_000) + "\n…(truncated)" : shown}`,
      structured: value && typeof value === "object" ? value : undefined,
      image,
    };
  } catch (e) {
    const msg = e && e.name === "AbortError"
      ? `bridge did not answer within ${CALL_TIMEOUT_MS / 1000}s — the phone may be asleep ` +
        "(the app process is frozen while the screen is off); wake it and retry"
      : String(e && e.message ? e.message : e);
    return { isError: true, text: `${entry.method} ${entry.path} failed: ${msg}` };
  }
}

// ---------------------------------------------------------------------------
// JSON-RPC over stdio
// ---------------------------------------------------------------------------

/**
 * Ids of tool calls still awaiting the bridge.
 *
 * Needed because `tools/call` is asynchronous while every other method answers
 * synchronously: without this the process could exit (stdin closed) in the
 * middle of a call and the client would get nothing back.
 */
const pending = new Set();

/** Set once stdin reaches EOF — the last settled call then ends the process. */
let stdinClosed = false;

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
}

function reply(id, result) {
  send({ jsonrpc: "2.0", id, result });
}

function replyError(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } });
}

/** -32601 is what makes a 2026-era peer fall back to the legacy handshake. */
const METHOD_NOT_FOUND = -32601;
const INVALID_PARAMS = -32602;

function negotiateVersion(requested) {
  if (SUPPORTED_VERSIONS.includes(requested)) return requested;
  return PREFERRED_VERSION;
}

function handleRequest(tools, msg) {
  const { id, method, params } = msg;

  // Notifications (no id) must never produce a response.
  if (id === undefined || id === null) {
    return;
  }

  switch (method) {
    case "initialize": {
      reply(id, {
        protocolVersion: negotiateVersion(params && params.protocolVersion),
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions: INSTRUCTIONS,
      });
      return;
    }
    case "ping": {
      reply(id, {});
      return;
    }
    case "tools/list": {
      reply(id, {
        tools: tools.map((t) => ({
          name: t.name,
          description: t.description,
          inputSchema: t.inputSchema,
        })),
      });
      return;
    }
    case "tools/call": {
      const name = params && params.name;
      if (!name) {
        replyError(id, INVALID_PARAMS, "tools/call requires params.name");
        return;
      }
      // Track in-flight calls so stdin closing does not truncate a reply.
      pending.add(id);
      runTool(tools, name, params.arguments)
        .then((r) => {
          // Text first (a one-line header plus the JSON without the payload),
          // then the picture itself. MCP allows both in one result and the
          // harness projects the image block into durable conversation content.
          const content = [{ type: "text", text: r.text }];
          if (r.image !== undefined) content.push(r.image);
          const result = { content, isError: r.isError };
          if (r.structured !== undefined) result.structuredContent = r.structured;
          reply(id, result);
        })
        .catch((e) => replyError(id, -32603, String((e && e.message) || e)))
        .finally(() => {
          pending.delete(id);
          if (stdinClosed && pending.size === 0) process.exit(0);
        });
      return;
    }
    case "resources/list":
    case "resources/templates/list": {
      reply(id, method === "resources/list" ? { resources: [] } : { resourceTemplates: [] });
      return;
    }
    case "prompts/list": {
      reply(id, { prompts: [] });
      return;
    }
    default:
      replyError(id, METHOD_NOT_FOUND, `method not found: ${method}`);
  }
}

async function serve() {
  let groups;
  try {
    groups = await fetchCatalogGroups();
  } catch (e) {
    process.stderr.write(
      `mcp-device: cannot read the endpoint catalog (${e.message}).\n` +
        "Start the DSH Android app (bridge on 127.0.0.1:3083) or set DSH_HELP_JSON.\n",
    );
    process.exit(1);
  }
  const only = parseOnly(process.argv.slice(2));
  const tools = buildTools(groups, only);
  if (only !== undefined && tools.length === 0) {
    // A filter that matches nothing is a configuration bug, not an empty
    // server: the host would report "connected, no tools" and the human
    // would conclude the phone is broken.
    process.stderr.write(`mcp-device: --only matched no endpoint (${[...only].join(",")})\n`);
    process.exit(1);
  }
  process.stderr.write(
    `mcp-device: ${tools.length} tools ready` + (only ? ` (filtered from ${only.size} ids)` : "") + "\n",
  );

  let buffer = "";
  process.stdin.setEncoding("utf8");
  process.stdin.on("data", (chunk) => {
    buffer += chunk;
    let nl;
    while ((nl = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, nl).trim();
      buffer = buffer.slice(nl + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        send({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } });
        continue;
      }
      try {
        handleRequest(tools, msg);
      } catch (e) {
        if (msg && msg.id !== undefined) replyError(msg.id, -32603, String(e.message || e));
      }
    }
  });
  // stdin closing means "the client is done sending", NOT "all work is done":
  // a `tools/call` that is still awaiting the bridge must be allowed to answer.
  // Exiting immediately here made every piped invocation (echo … | node …)
  // lose its tool result — reproduced on device, the client saw no response.
  process.stdin.on("end", () => {
    stdinClosed = true;
    if (pending.size === 0) process.exit(0);
    // Otherwise let the outstanding calls settle; each one exits when done.
  });
}

// ---------------------------------------------------------------------------
// Debug / self-test entry points
// ---------------------------------------------------------------------------

async function probeOnce() {
  const groups = await fetchCatalogGroups();
  const tools = buildTools(groups, parseOnly(process.argv.slice(2)));
  console.error(`tools: ${tools.length}`);
  for (const t of tools) console.error("  " + t.name);
  const status = tools.find((t) => t.name === "status");
  if (status) {
    const r = await runTool(tools, "status", {});
    console.error("\nstatus →", r.text.slice(0, 400));
  }
}

/**
 * In-process round trip: proves framing, handshake, listing and call plumbing
 * without needing a desktop MCP host.
 */
async function selfTest() {
  const groups = await fetchCatalogGroups();
  const tools = buildTools(groups, parseOnly(process.argv.slice(2)));
  const seen = [];
  const fakeStdout = (line) => seen.push(JSON.parse(line));

  const cases = [
    { jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "t", version: "1" } } },
    { jsonrpc: "2.0", method: "notifications/initialized" },
    { jsonrpc: "2.0", id: 2, method: "tools/list" },
    { jsonrpc: "2.0", id: 3, method: "server/discover", params: {} },
    { jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "definitely-not-a-tool", arguments: {} } },
  ];

  const origWrite = process.stdout.write.bind(process.stdout);
  process.stdout.write = (chunk) => {
    fakeStdout(String(chunk).trim());
    return true;
  };
  try {
    for (const c of cases) handleRequest(tools, c);
    // tools/call resolves asynchronously; let the microtask queue drain.
    await new Promise((r) => setTimeout(r, 50));
  } finally {
    process.stdout.write = origWrite;
  }

  const byId = new Map(seen.filter((m) => m.id !== undefined).map((m) => [m.id, m]));
  const checks = [];
  const check = (label, cond) => checks.push({ label, ok: !!cond });

  check("initialize answered", byId.has(1));
  check(
    "initialize protocolVersion supported",
    SUPPORTED_VERSIONS.includes(byId.get(1)?.result?.protocolVersion),
  );
  check("initialize advertises tools capability", byId.get(1)?.result?.capabilities?.tools);
  check("initialize carries instructions", typeof byId.get(1)?.result?.instructions === "string");
  check("notification produced no response", !seen.some((m) => m.method === "notifications/initialized"));
  check("tools/list answered", byId.has(2));
  const listed = byId.get(2)?.result?.tools || [];
  check("tools/list non-empty", listed.length > 0);
  check("every tool has inputSchema object type", listed.every((t) => t.inputSchema?.type === "object"));
  check("every tool has a description", listed.every((t) => typeof t.description === "string" && t.description.length > 10));
  check("tool names are identifier-safe", listed.every((t) => /^[A-Za-z0-9_-]+$/.test(t.name)));
  check("tool names unique", new Set(listed.map((t) => t.name)).size === listed.length);
  check("server/discover returns -32601 (forces legacy fallback)", byId.get(3)?.error?.code === -32601);
  check("unknown tool reported as tool error, not crash", byId.get(4)?.result?.isError === true);
  check("unknown tool error names the tool", /definitely-not-a-tool/.test(byId.get(4)?.result?.content?.[0]?.text || ""));

  let failed = 0;
  for (const c of checks) {
    if (!c.ok) failed++;
    console.log(`${c.ok ? "ok  " : "FAIL"}  ${c.label}`);
  }
  console.log(`\n${checks.length - failed}/${checks.length} checks passed`);
  process.exit(failed === 0 ? 0 : 1);
}

/**
 * Whether this file is the process entry point.
 *
 * The main block below starts an MCP server on stdin, which must not happen
 * when a test imports the module to exercise buildTools/imageBlockOf: the
 * import would block reading stdin forever.
 */
const invokedDirectly = process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(process.argv[1]).href;

const mode = invokedDirectly ? process.argv[2] : undefined;
if (!invokedDirectly) {
  // Imported as a library: exports only.
} else if (mode === "--list") {
  fetchCatalogGroups().then((g) => {
    for (const t of buildTools(g, parseOnly(process.argv.slice(2)))) {
      console.log(`${t.name}\t${t._endpoint.method} ${t._endpoint.path}`);
    }
  }).catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
} else if (mode === "--probe") {
  probeOnce().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
} else if (mode === "--self-test") {
  selfTest().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
} else {
  serve();
}

export {
  buildTools,
  parseArgs,
  parseOnly,
  imageBlockOf,
  withoutPayload,
  negotiateVersion,
  toolNameFor,
  SUPPORTED_VERSIONS,
};

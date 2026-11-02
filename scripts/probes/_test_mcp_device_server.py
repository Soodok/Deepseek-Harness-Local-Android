#!/usr/bin/env python3
"""Lock the MCP device server's contract (app/src/main/assets/tools/mcp-device.mjs).

Why a static probe instead of running the server: CI has no phone and no
bridge, so the runtime behaviour (real tool calls) is verified on-device. What
can be pinned here is the part that silently breaks a *client*: the JSON-RPC
framing, the handshake answer, and the rule that makes a 2026-era peer fall
back to the legacy handshake at all.

Each check names the failure it prevents.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/assets/tools/mcp-device.mjs"
CATALOG = ROOT / "app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt"
ENGINE = ROOT / "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt"

checks = []


def check(label, ok, detail=""):
    checks.append((label, bool(ok), detail))


def read(path):
    return path.read_text(encoding="utf-8")


src = read(SRC)

# --- framing ---------------------------------------------------------------
# MCP stdio is newline-delimited JSON. A Content-Length framing or an
# unescaped newline inside a message deadlocks the client at the first reply.
check(
    "writes one JSON object per line (stdio framing)",
    'process.stdout.write(JSON.stringify(msg) + "\\n")' in src,
    "respond() must emit a single line per message",
)
check(
    "decodes incoming messages by newline",
    'buffer.indexOf("\\n")' in src,
)

# --- handshake -------------------------------------------------------------
# The client's legacy list is exactly these three; answering with a version it
# does not know makes it treat the server as unusable.
for version in ("2025-11-25", "2025-06-18", "2025-03-26"):
    check(f"supports legacy protocol version {version}", version in src)

check(
    "initialize result carries protocolVersion/capabilities/serverInfo",
    all(k in src for k in ("protocolVersion:", "capabilities:", "serverInfo:")),
)
check(
    "declares tools capability",
    "capabilities: { tools: { listChanged: false } }" in src,
)
check(
    "sends instructions on initialize",
    "instructions: INSTRUCTIONS" in src,
)

# --- era negotiation -------------------------------------------------------
# Implementing server/discover would claim 2026-era semantics (ttlMs/cacheScope
# on every result) that this server does not produce. Answering -32601 is what
# makes the client fall back to `initialize`.
check(
    "does NOT implement server/discover",
    'case "server/discover"' not in src,
    "a discover handler would claim 2026-era wire semantics we do not implement",
)
check(
    "unknown methods answer -32601",
    "const METHOD_NOT_FOUND = -32601;" in src and "replyError(id, METHOD_NOT_FOUND" in src,
)
check(
    "never responds to notifications (no id)",
    re.search(r"if \(id === undefined \|\| id === null\) \{\s*\n\s*return;", src) is not None,
)

# --- tool derivation -------------------------------------------------------
# The catalog is the single source of truth; a hardcoded list drifts the day
# an endpoint is added.
check(
    "tool list comes from GET /dev/help?detail=1",
    "/dev/help?detail=1" in src,
)
check(
    "catalog can be read from DSH_HELP_JSON (tests / offline)",
    "DSH_HELP_JSON" in src,
)
check(
    "duplicate catalog ids are disambiguated",
    "function assignToolNames(" in src and "_${String(entry.method).toLowerCase()}" in src,
    "media/calendar/sms appear once per method; a duplicate name makes the client "
    "reject the entire tool list",
)
check(
    "tool names are normalised to identifier-safe characters",
    'replace(/[^A-Za-z0-9_-]/g, "_")' in src,
)

# --- call path -------------------------------------------------------------
check(
    "forwards X-DSH-Token from DSH_BRIDGE_TOKEN",
    "DSH_BRIDGE_TOKEN" in src and "X-DSH-Token" in src,
)
check(
    "bridge port is overridable (DSH_BRIDGE_PORT)",
    "DSH_BRIDGE_PORT" in src,
)
check(
    "aborted calls explain the frozen-app case",
    "may be asleep" in src or "screen is off" in src,
    "the app process is frozen while the screen is off; a bare timeout is unactionable",
)

# --- lifecycle (regression) ------------------------------------------------
# Bug found on device: `process.stdin.on("end", () => process.exit(0))` killed
# the process while a tools/call was still awaiting the bridge, so a piped
# invocation lost its result entirely.
check(
    "stdin EOF does not exit while a call is in flight",
    "stdinClosed" in src and "pending.size === 0" in src,
    "exiting on EOF truncated in-flight tool replies (reproduced on device)",
)
check(
    "in-flight tool calls are tracked",
    "const pending = new Set();" in src and "pending.add(id)" in src,
)

# --- self-test mode --------------------------------------------------------
check(
    "ships --self-test (framing + handshake + list + unknown tool)",
    '"--self-test"' in src and "async function selfTest(" in src,
)
check(
    "self-test asserts discover is rejected",
    "-32601" in src and "server/discover returns -32601" in src,
)

# --- deployment ------------------------------------------------------------
check(
    "EngineConfig deploys mcp-device.mjs to $DSH_HOME/tools",
    '"lan-web-proxy.mjs", "hid.mjs", "mcp-device.mjs"' in read(ENGINE),
)

# --- catalog integrity -----------------------------------------------------
# The catalog doubled POST /dev/sms with two different field names (`to` vs
# `number`); PersonalCap.smsCompose only reads `to`, so the second entry
# documented a request shape that cannot work.
cat = read(CATALOG)
sms_posts = re.findall(r'e\(\s*"sms",\s*"POST",\s*"/dev/sms"', cat)
check(
    "catalog registers POST /dev/sms exactly once",
    len(sms_posts) == 1,
    f"found {len(sms_posts)} entries; a duplicate id also collides in MCP tool names",
)
check(
    'catalog does not document sms field "number"',
    '"number":\\"10086\\"' not in cat and 'number\\":' not in cat,
    "smsCompose reads `to`; documenting `number` sends callers down a dead field",
)

# --- report ----------------------------------------------------------------
failed = 0
for label, ok, detail in checks:
    if ok:
        print(f"ok    {label}")
    else:
        failed += 1
        print(f"FAIL  {label}" + (f"\n        → {detail}" if detail else ""))

print(f"\n{len(checks) - failed}/{len(checks)} checks passed")
if failed:
    print("\nscanned:", SRC.relative_to(ROOT))
    sys.exit(1)
print("All MCP server checks passed.")

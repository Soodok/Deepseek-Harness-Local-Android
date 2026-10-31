/** dsh-android device capability tools. */
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const name = "android-device-tools";
const inject = ["tools"];

async function loadDefineTool() {
  const here = path.dirname(new URL(import.meta.url).pathname);
  const engineRoot = path.resolve(here, "..");
  const candidates = [
    path.join(engineRoot, "lib/node_modules/@deepseek-ai/dsh-tools/lib/index.js"),
    path.join(engineRoot, "node_modules/@deepseek-ai/dsh-tools/lib/index.js"),
  ];
  for (const candidate of candidates) {
    try {
      if (!fs.existsSync(candidate)) continue;
      const mod = await import(pathToFileURL(candidate).href);
      if (typeof mod.defineTool === "function") return mod.defineTool;
    } catch { /* try the next engine layout */ }
  }
  return undefined;
}

async function request(method, endpoint, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const headers = { "X-DSH-Token": process.env.DSH_BRIDGE_TOKEN || "" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(`http://127.0.0.1:3083${endpoint}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal,
    });
    const text = await response.text();
    let value;
    try { value = JSON.parse(text); } catch { value = { ok: response.ok, text }; }
    return { ...value, http_status: response.status };
  } finally {
    clearTimeout(timer);
  }
}

const output = {
  schema: { type: "object", additionalProperties: true },
  render: (_args, value) => [{ type: "text", text: JSON.stringify(value, null, 2) }],
};

async function apply(ctx) {
  const defineTool = await loadDefineTool();
  if (!defineTool) {
    console.warn("[android-device-tools] defineTool unavailable — tools not registered");
    return;
  }
  ctx.tools.register(defineTool({
    name: "device_status",
    description: "Read this Android device status: battery, screen, audio, network, WiFi, Bluetooth, storage, memory and foreground app.",
    parameters: {
      fields: { type: "string", description: "Optional comma-separated fields; omitted returns the complete snapshot." },
    },
    output,
    execute: async () => {
      const ping = await request("GET", "/dev/ping");
      if (ping.ok !== true) return ping;
      const status = await request("GET", "/dev/status");
      return { ...status, bridge: ping };
    },
  }));
  ctx.tools.register(defineTool({
    name: "device_control",
    description: "Control Android WiFi, Bluetooth, volume, brightness, torch and vibration. Explicit user capability switch is required.",
    parameters: {
      op: { type: "string", required: true, enum: ["wifi", "bt", "volume", "brightness", "torch", "vibrate"] },
      enabled: { type: "boolean",  },
      stream: { type: "string",  },
      value: { type: "number",  },
      ms: { type: "number",  },
    },
    output,
    execute: (args = {}) => request("POST", "/dev/control", {
      op: args.op, enabled: args.enabled, stream: args.stream, value: args.value, ms: args.ms,
    }),
  }));
  ctx.tools.register(defineTool({
    name: "remind",
    description: "Create, list or cancel an AI reminder on this Android device. Reminders persist across app upgrades and reboot.",
    parameters: {
      operation: { type: "string", required: true, enum: ["create", "list", "cancel"] },
      title: { type: "string",  },
      body: { type: "string",  },
      at: { type: "string", description: "ISO-8601 absolute time" },
      in_seconds: { type: "number",  },
      repeat: { type: "object", additionalProperties: true },
      priority: { type: "string", enum: ["high", "default", "low"] },
      id: { type: "string",  },
    },
    output,
    execute: (args = {}) => {
      if (args.operation === "list") return request("GET", "/dev/remind-list");
      if (args.operation === "cancel") return request("POST", "/dev/remind-cancel", { id: args.id });
      return request("POST", "/dev/remind", args);
    },
  }));
  ctx.tools.register(defineTool({
    name: "bt_radio",
    description: "Bluetooth radio at the packet level. ops: status | scan | advertise | connect | services | read | write | rfcomm | log. "
      + "Use op=write with a hex payload to send arbitrary bytes over GATT, op=rfcomm to write arbitrary bytes over classic "
      + "RFCOMM, op=advertise to broadcast your own BLE payload, op=log to read the on-device HCI trace. "
      + "Requires the Bluetooth capability switch to be on.",
    parameters: {
      op: { type: "string", required: true, enum: ["status", "enable", "disable", "scan", "advertise", "connect", "services", "read", "write", "rfcomm", "log"] },
      address: { type: "string", description: "Remote MAC, e.g. AA:BB:CC:DD:EE:FF" },
      service_uuid: { type: "string" },
      char_uuid: { type: "string" },
      hex: { type: "string", description: "Hex payload, e.g. 0102ff" },
      manufacturer_id: { type: "number" },
      manufacturer_hex: { type: "string", description: "Raw BLE advertisement manufacturer data as hex (max 27 bytes)" },
      channel: { type: "number" },
      ms: { type: "number" },
      lines: { type: "number" },
      filter: { type: "string" },
      connectable: { type: "boolean" },
      no_response: { type: "boolean" },
    },
    output,
    execute: (args = {}) => request("POST", "/dev/bt", args),
  }));
  ctx.tools.register(defineTool({
    name: "wifi_radio",
    description: "WiFi at the link level. ops: status | scan | enable | disable | link | capture. "
      + "op=scan lists nearby networks with RSSI, op=link returns iw link details (channel/width/txpower), "
      + "op=capture runs a bounded tcpdump to a pcap file. Requires the WiFi capability switch to be on.",
    parameters: {
      op: { type: "string", required: true, enum: ["status", "scan", "enable", "disable", "link", "capture"] },
      iface: { type: "string", description: "Interface for capture, default wlan0" },
      seconds: { type: "number", description: "Capture duration, 1-30" },
      count: { type: "number", description: "Max packets, 1-2000" },
      filter: { type: "string", description: "tcpdump filter expression, no shell metacharacters" },
      ms: { type: "number" },
    },
    output,
    execute: (args = {}) => request("POST", "/dev/wifi-radio", args),
  }));
}

export { apply, inject, name };

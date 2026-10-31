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
  ctx.tools.register(defineTool({
    name: "notify_reply",
    description: "Reply to a notification on behalf of the user through RemoteInput (WeChat, SMS, QQ, …). "
      + "Call with no arguments to list replyable notifications and their keys, then call again with key + text. "
      + "This sends a message as the user, so it needs the separate reply capability switch.",
    parameters: {
      key: { type: "string", description: "Notification key from the listing" },
      text: { type: "string", description: "Text to send back" },
      action_index: { type: "number", description: "Which action to use; omitted picks the first replyable one" },
    },
    output,
    execute: async (args = {}) => {
      if (!args.key) {
        const items = await request("GET", "/dev/notifications?limit=20");
        if (items.ok !== true) return items;
        // 只挑出真正带 remoteInput 的 action：其余点进去只是打开 App，不能回复。
        const replyable = (items.items || []).filter((n) =>
          (n.actions || []).some((a) => a.remote_input === true));
        return {
          ok: true,
          count: replyable.length,
          items: replyable.map((n) => ({
            key: n.key, package: n.package, title: n.title, text: n.text,
            actions: (n.actions || []).filter((a) => a.remote_input === true),
          })),
        };
      }
      return request("POST", "/dev/notify-reply", {
        key: args.key, text: args.text, action_index: args.action_index,
      });
    },
  }));
  ctx.tools.register(defineTool({
    name: "media_control",
    description: "Read and control the media that is currently playing: play, pause, toggle, next, previous, "
      + "seek, and volume. Uses MediaSession through notification access, so the media capability switch must be on.",
    parameters: {
      op: { type: "string", required: true, enum: ["list", "play", "pause", "toggle", "next", "previous", "stop", "seek", "volume", "stream-volume"] },
      position_ms: { type: "number", description: "Target position for op=seek" },
      volume: { type: "number", description: "0..1 ratio, or an absolute step when > 1" },
      stream: { type: "string", description: "For op=stream-volume: music|ring|alarm|notification|call" },
    },
    output,
    execute: (args = {}) => (args.op === "list"
      ? request("GET", "/dev/media")
      : request("POST", "/dev/media", args)),
  }));
  ctx.tools.register(defineTool({
    name: "personal_data",
    description: "Read the user's calendar, contacts and SMS, and add calendar events. Each domain needs its own "
      + "system permission; the personal capability switch must also be on. SMS sending is handed off to the "
      + "system messaging UI rather than sent silently.",
    parameters: {
      domain: { type: "string", required: true, enum: ["calendar", "calendar_add", "contacts", "sms", "sms_send"] },
      query: { type: "string", description: "Search text for contacts, or address filter for sms" },
      from_ms: { type: "number", description: "Calendar window start" },
      to_ms: { type: "number", description: "Calendar window end" },
      limit: { type: "number" },
      title: { type: "string" },
      begin_ms: { type: "number" },
      end_ms: { type: "number" },
      location: { type: "string" },
      notes: { type: "string" },
      to: { type: "string", description: "Recipient for sms_send" },
      text: { type: "string", description: "Message body for sms_send" },
    },
    output,
    execute: (args = {}) => {
      const limit = args.limit ? `&limit=${args.limit}` : "";
      switch (args.domain) {
        case "calendar":
          return request("GET", `/dev/calendar?from=${args.from_ms || 0}&to=${args.to_ms || 0}${limit}`);
        case "calendar_add":
          return request("POST", "/dev/calendar", {
            title: args.title, begin_ms: args.begin_ms, end_ms: args.end_ms,
            location: args.location, notes: args.notes,
          });
        case "contacts":
          return request("GET", `/dev/contacts?q=${encodeURIComponent(args.query || "")}${limit}`);
        case "sms":
          return request("GET", `/dev/sms?address=${encodeURIComponent(args.query || "")}${limit}`);
        default:
          return request("POST", "/dev/sms", { to: args.to, text: args.text });
      }
    },
  }));
  ctx.tools.register(defineTool({
    name: "nfc_and_fence",
    description: "Read NFC tags tapped to the phone, and manage location fences. NFC cannot be polled on demand: "
      + "call op=enable first, ask the user to tap, then op=read. Fences fire on enter and leave.",
    parameters: {
      op: { type: "string", required: true, enum: ["status", "enable", "disable", "read", "clear", "fence_add", "fence_remove", "fence_list"] },
      id: { type: "string", description: "Fence id" },
      lat: { type: "number" },
      lon: { type: "number" },
      radius_m: { type: "number", description: "50-5000, default 200" },
      label: { type: "string" },
    },
    output,
    execute: (args = {}) => {
      if (args.op === "status") return request("GET", "/dev/nfc");
      if (args.op === "fence_list") return request("POST", "/dev/geofence", { op: "list" });
      if (args.op === "fence_add" || args.op === "fence_remove") {
        return request("POST", "/dev/geofence", {
          op: args.op === "fence_add" ? "add" : "remove",
          id: args.id, lat: args.lat, lon: args.lon, radius_m: args.radius_m, label: args.label,
        });
      }
      return request("POST", "/dev/nfc", { op: args.op });
    },
  }));
  ctx.tools.register(defineTool({
    name: "ui_guard",
    description: "Two things that make GUI automation reliable: detect and dismiss blocking dialogs, and verify "
      + "that an action actually took effect. op=dialog scans the current screen and reports risky buttons "
      + "(never auto-taps allow/pay/agree); op=verify waits until expected text appears or disappears.",
    parameters: {
      op: { type: "string", required: true, enum: ["dialog", "verify"] },
      auto: { type: "boolean", description: "For op=dialog: auto-tap only safe dismiss buttons" },
      include_danger: { type: "boolean", description: "For op=dialog: also report dangerous buttons (default true)" },
      expect: { type: "string", description: "For op=verify: text that should appear" },
      gone: { type: "boolean", description: "For op=verify: wait for the text to disappear instead" },
      timeout_ms: { type: "number" },
    },
    output,
    execute: (args = {}) => (args.op === "verify"
      ? request("POST", "/dev/verify", {
        expect: args.expect, gone: args.gone, timeout_ms: args.timeout_ms,
      })
      : request("POST", "/dev/dialog", {
        auto: args.auto, includeDanger: args.include_danger !== false,
      })),
  }));
  ctx.tools.register(defineTool({
    name: "screen_record",
    description: "Record the screen to an MP4 file. Android requires the user to approve every single recording, "
      + "so the flow is op=request (raises the system dialog, needs the app in the foreground), then op=start, "
      + "then op=stop. op=status returns the current file.",
    parameters: {
      op: { type: "string", required: true, enum: ["request", "start", "stop", "status"] },
    },
    output,
    execute: (args = {}) => (args.op === "status"
      ? request("GET", "/dev/record")
      : request("POST", "/dev/record", { op: args.op })),
  }));
}

export { apply, inject, name };

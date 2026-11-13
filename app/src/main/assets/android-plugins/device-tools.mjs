/** dsh-android device capability tools. */
import fs from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const name = "android-device-tools";
const inject = ["tools"];

async function loadDefineTool() {
  // ⚠️ 不能用 new URL(import.meta.url).pathname：
  //   · Windows 上给出 "/E:/a/b"（盘符前多一个斜杠）→ path.join 拼出无效路径；
  //   · 任何平台上游目录含空格/中文时是百分号编码（%20 / %E4%B8%AD）→ existsSync 必然 false。
  // 仓库里 fast-mode 曾踩过同一个坑并改成 fileURLToPath，这里补齐。
  const here = path.dirname(fileURLToPath(import.meta.url));
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

/**
 * 直接执行 hid 工具脚本（不经过桥）。
 *
 * 为什么不走 `/dev/hid` 桥端点：那条路要 App 进程去 `su`，而 KernelSU 在无 UI
 * 时不会弹授权，调用会挂住（实测 curl 60s 超时）。但本插件运行在 **引擎 node
 * 进程**里，该进程本身就是 root（uid 0 / u:r:ksu:s0），可以直接 exec 脚本，
 * 完全不需要提权。这是唯一可靠的路。
 */
function runHid(args) {
  const home = process.env.DSH_HOME || "";
  const candidates = [
    home ? path.join(home, "tools", "hid.mjs") : "",
    path.join(path.dirname(process.execPath), "..", "dsh-home", "tools", "hid.mjs"),
    path.join(path.dirname(process.execPath), "..", "tools", "hid.mjs"),
  ].filter(Boolean);
  const script = candidates.find((p) => { try { return fs.existsSync(p); } catch { return false; } });
  if (!script) {
    return { ok: false, error: "hid_script_missing", tried: candidates };
  }
  const result = spawnSync(process.execPath, [script, ...args], {
    encoding: "utf8",
    timeout: 30000,
    env: { ...process.env },
  });
  const out = (result.stdout || "").trim();
  const err = (result.stderr || "").trim();
  let parsed;
  try { parsed = JSON.parse(out); } catch { parsed = null; }
  if (parsed) return parsed;
  return {
    ok: result.status === 0,
    status: result.status,
    stdout: out.slice(0, 2000),
    stderr: err.slice(0, 2000),
  };
}

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
    description: "Control Android device switches. op=wifi|bt|volume|brightness|torch|vibrate work without privilege; " +
      "op=system toggles airplane mode, battery saver, Do Not Disturb, auto-rotate, eye comfort and hotspot " +
      "(these have no public API, they need Root or Shizuku and every write is verified by reading the state back). " +
      "Explicit user capability switch is required.",
    parameters: {
      op: { type: "string", required: true, enum: ["wifi", "bt", "volume", "brightness", "torch", "vibrate", "system"] },
      enabled: { type: "boolean",  },
      stream: { type: "string",  },
      value: { type: "number",  },
      ms: { type: "number",  },
      target: { type: "string", enum: ["airplane", "battery_saver", "dnd", "rotation", "eye_comfort", "hotspot"] },
      action: { type: "string", enum: ["status", "on", "off", "toggle"] },
      ssid: { type: "string",  },
      password: { type: "string",  },
      security: { type: "string", enum: ["open", "wpa2"] },
    },
    output,
    execute: (args = {}) => request("POST", "/dev/control", {
      op: args.op, enabled: args.enabled, stream: args.stream, value: args.value, ms: args.ms,
      target: args.target, action: args.action, ssid: args.ssid, password: args.password, security: args.security,
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

  ctx.tools.register(defineTool({
    name: "device_hid",
    description: "Send real hardware key presses by registering a virtual HID device with the kernel. "
      + "Unlike `input keyevent` (which the system can tell apart), this creates a genuine external "
      + "keyboard/consumer-control device, so media keys, volume keys and typed text behave exactly as if "
      + "a physical keyboard were plugged in. Requires the HID capability switch to be on and the engine "
      + "running as root. IMPORTANT: the screen must be awake — Android's InputDispatcher drops injected "
      + "keys while the device is dozing, so wake it first (see op=wake).",
    parameters: {
      op: {
        type: "string",
        required: true,
        enum: ["tap", "text", "key", "mouse", "status", "wake"],
        description: "tap = press and release one key; text = type printable ASCII; key = hold keys; "
          + "mouse = move/click relative; status = report device node; wake = turn the screen on",
      },
      key: {
        type: "string",
        description: "For op=tap/key: a named alias (vol+, vol-, mute, play, pause, next, prev, stop, "
          + "enter, esc, back, space, tab, up, down, left, right, home, power, bright+, bright-) or a "
          + "raw HID usage number. Aliases always carry the right HID page, so prefer them over raw numbers.",
      },
      text: { type: "string", description: "For op=text: the printable ASCII string to type." },
      kind: {
        type: "string",
        enum: ["keyboard", "consumer"],
        description: "For op=tap/key with a raw numeric usage: which HID page the number belongs to. "
          + "Consumer page (media/volume) and Keyboard page use different numbers for the same key.",
      },
      shift: { type: "boolean", description: "For op=tap on a keyboard usage: hold shift." },
      dx: { type: "number", description: "For op=mouse: horizontal movement." },
      dy: { type: "number", description: "For op=mouse: vertical movement." },
      button: { type: "number", description: "For op=mouse: 0 none, 1 left, 2 right, 4 middle." },
      wheel: { type: "number", description: "For op=mouse: wheel clicks." },
    },
    output,
    execute: (args = {}) => {
      const op = args.op || "status";
      if (op === "status") return runHid(["probe"]);
      if (op === "wake") {
        // 屏幕必须 Awake，否则 InputDispatcher 丢弃注入的按键。
        const r = spawnSync("/system/bin/input", ["keyevent", "KEYCODE_WAKEUP"], {
          encoding: "utf8", timeout: 10000,
        });
        return { ok: r.status === 0, action: "wakeup", stderr: (r.stderr || "").trim().slice(0, 500) };
      }
      if (op === "mouse") {
        const a = ["mouse"];
        if (args.dx !== undefined) a.push("dx=" + args.dx);
        if (args.dy !== undefined) a.push("dy=" + args.dy);
        if (args.button !== undefined) a.push("button=" + args.button);
        if (args.wheel !== undefined) a.push("wheel=" + args.wheel);
        return runHid(a);
      }
      if (op === "text") return runHid(["text", String(args.text || "")]);
      if (op === "key") return runHid(["key", String(args.key || "")]);
      const a = ["tap", String(args.key)];
      if (args.kind) a.push("kind=" + args.kind);
      if (args.shift) a.push("shift=true");
      return runHid(a);
    },
  }));
}

export { apply, inject, name };

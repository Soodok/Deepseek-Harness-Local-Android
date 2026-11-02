// [dsh-android] hid —— 虚拟 HID 设备（/dev/uhid），一次性执行器
//
// ## 为什么是独立脚本而不是桥端点
//
// `/dev/uhid` 权限位 `crw-rw---- uhid uhid`，SELinux 只允许 `u:r:ksu:s0`（KernelSU
// 的 root 域）写。而 AgentBridge 跑在 **App 进程（uid 10488, u:r:untrusted_app）**：
//   · 直接 open → EACCES
//   · 走 Shizuku → `u:r:shell:s0`，被 SELinux 拒（实测 shell 虽在 uhid 组，仍写不了）
//   · 走 su → 在 App 进程内请求 KernelSU 授权，无 UI 时永久挂起（实测 curl 超时 60s）
//
// 而**引擎本身就是 root**（`node` uid 0 / `u:r:ksu:s0`，实测可写）。所以这个能力
// 必须由门脚本执行，不能经桥中转。`/dev/hid` 桥端点保留为「探测/说明」用途，
// 真正注入走本脚本。
//
// ## 用法
//
//   hid                                  探测（/dev/uhid 是否可写、当前身份）
//   hid create [name=..] [kind=keyboard|consumer|mouse]
//   hid tap <code> [kind=..]             按一下（键盘 usage / 媒体 usage）
//   hid text <string>                    输入 US 布局可打印 ASCII
//   hid key <code>[,<code>...]           按下若干键（不抬起）
//   hid mouse dx=<n> dy=<n> [button=0|1|2] [wheel=<n>]
//   hid help
//
// ## 单次进程的生命周期限制（必须知道）
//
// 每次调用都会 **创建 → 注入 → 销毁** 虚拟设备（脚本退出 fd 关闭，内核回收）。
// 因此：
//   · `create` 单独调用**没有意义** —— 它创建的设备随进程退出立刻消失
//   · `key` 的「按下不抬起」跨进程无效 —— 请用 `tap`
//   · 要长期按住某个键需要长驻进程，本设计不支持（够用优先，避免常驻泄漏）
//
// 真机实测（OPPO PLZ110 / Android 16 / KernelSU 4.1.3）：
//   · `hid tap 128 kind=keyboard`（KEY_VOLUMEUP）使 STREAM_MUSIC 60 → 70
//   · `hid text ab` 在输入层产出完整的 `KEY_A DOWN/UP` / `KEY_B DOWN/UP`
//   · `dumpsys input` 可见设备路径 `/sys/devices/virtual/misc/uhid/...`
//
// ## ★ 硬前提：屏幕必须 Awake
//
// 实测 `mWakefulness=Dozing` 时，HID 事件**能到内核**（getevent 抓得到
// `KEY_VOLUMEUP DOWN/UP`）但**系统不响应**（音量不变、不注入文字）——
// InputDispatcher 在 Doze 下不把按键派给窗口。调用前先唤醒：
//   input keyevent KEYCODE_WAKEUP
// 门脚本不做自动唤醒（AI 不该静默点亮用户屏幕），由调用方决定。
'use strict';

import fs from 'node:fs';

const UHID_CREATE2 = 11;
const UHID_INPUT2 = 12;
const UHID_DESTROY = 1;
const MAX_RD = 4096;

// ---- 报告描述符（HID descriptor）----
// 键盘：HID Usage Page Generic Desktop(01) / Usage Keyboard(06)，8 字节修饰键位图 +
// 保留字节 + 6 键 rollover。这是标准 boot keyboard 描述符，Android 直接认。
const D_KEYBOARD = [
  0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
  0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
  0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
  0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
  0x95, 0x01, 0x75, 0x08, 0x15, 0x00, 0x25, 0xFF,
  0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, 0x81, 0x00,
  0xC0,
];
// 媒体键：Usage Page Consumer(0C) / Usage Consumer Control(01)，16 位 usage。
// Android 用它发音量/播放/静音等系统级动作。
const D_CONSUMER = [
  0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
  0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
  0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
  0xC0,
];
// 鼠标：3 键 + 相对 X/Y/滚轮。
const D_MOUSE = [
  0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x03,
  0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
  0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
  0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
  0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
  0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
  0xC0, 0xC0,
];

const ASCII = buildAsciiTable();

// ---- 常用按键别名（真机实测对照表）----
//
// ★ 为什么需要这张表：Consumer 页与 Keyboard 页的 usage 号完全不同，
// 直接让 AI 记 hex 必然出错。实测（Android 16 / OPPO PLZ110）：
//
//   hid tap 233 kind=consumer   → KEY 音量+   STREAM_MUSIC 60 → 70
//   hid tap 234 kind=consumer   → KEY 音量-   STREAM_MUSIC 60 → 50
//   hid tap 128 kind=keyboard   → KEY_VOLUMEUP  60 → 70
//   hid tap 129 kind=keyboard   → KEY_VOLUMEDOWN 60 → 50
//   hid tap 127 kind=keyboard   → KEY_MUTE      60 → 0
//
// 注意 0x7F(127) 是**静音**不是音量小，0x80(128) 才是音量加。
const ALIASES = {
  // Consumer page（kind=consumer 默认）
  'vol+':      { kind: 'consumer', code: 0xE9 },
  'vol-':      { kind: 'consumer', code: 0xEA },
  'mute':      { kind: 'consumer', code: 0xE2 },
  'play':      { kind: 'consumer', code: 0xB0 },
  'pause':     { kind: 'consumer', code: 0xB1 },
  'next':      { kind: 'consumer', code: 0xB5 },
  'prev':      { kind: 'consumer', code: 0xB6 },
  'stop':      { kind: 'consumer', code: 0xB7 },
  'rewind':    { kind: 'consumer', code: 0xB4 },
  'forward':   { kind: 'consumer', code: 0xB3 },
  'bright+':   { kind: 'consumer', code: 0x6F },
  'bright-':   { kind: 'consumer', code: 0x70 },
  // Keyboard page（Android keycode 语义）
  'enter':     { kind: 'keyboard', code: 0x28 },
  'esc':       { kind: 'keyboard', code: 0x29 },
  'back':      { kind: 'keyboard', code: 0x29 },
  'space':     { kind: 'keyboard', code: 0x2C },
  'tab':       { kind: 'keyboard', code: 0x2B },
  'up':        { kind: 'keyboard', code: 0x52 },
  'down':      { kind: 'keyboard', code: 0x51 },
  'left':      { kind: 'keyboard', code: 0x50 },
  'right':     { kind: 'keyboard', code: 0x4F },
  'home':      { kind: 'keyboard', code: 0x4A },
  'power':     { kind: 'keyboard', code: 0x30 },
};

/** 把别名解析成 {kind, code}；不是别名则原样返回。 */
function resolveAlias(name, kind) {
  const a = ALIASES[String(name).toLowerCase()];
  if (!a) return null;
  return { kind: kind || a.kind, code: a.code };
}


function putU16(b, o, v) { b[o] = v & 0xFF; b[o + 1] = (v >> 8) & 0xFF; }
function putU32(b, o, v) {
  b[o] = v & 0xFF;
  b[o + 1] = (v >> 8) & 0xFF;
  b[o + 2] = (v >> 16) & 0xFF;
  b[o + 3] = (v >> 24) & 0xFF;
}

// struct uhid_event create2：
//   u32 type | char name[128] | u8 phys[64] | u8 uniq[64] |
//   u16 rd_size | u16 bus | u32 vendor | u32 product | u32 version | u32 country | u8 rd_data[4096]
// 注意 bus 必须是 BUS_USB(0x03) —— 填 0 会让 Android 忽略该设备（实测）。
function buildCreate2(name, desc) {
  const total = 4 + 128 + 64 + 64 + 2 + 2 + 16 + MAX_RD;
  const b = Buffer.alloc(total);
  let o = 0;
  putU32(b, o, UHID_CREATE2); o += 4;
  const nb = Buffer.from(name, 'utf8');
  nb.copy(b, o, 0, Math.min(nb.length, 127)); o += 128;
  o += 64; o += 64;
  putU16(b, o, desc.length); o += 2;
  putU16(b, o, 0x03); o += 2;          // bus = BUS_USB
  putU32(b, o, 0); o += 4;             // vendor
  putU32(b, o, 0); o += 4;             // product
  putU32(b, o, 0x19); o += 4;          // version
  putU32(b, o, 0); o += 4;             // country
  Buffer.from(desc).copy(b, o, 0, Math.min(desc.length, MAX_RD));
  return b;
}

function buildInput2(payload) {
  const b = Buffer.alloc(6 + payload.length);
  putU32(b, 0, UHID_INPUT2);
  putU16(b, 4, payload.length);
  Buffer.from(payload).copy(b, 6);
  return b;
}

// 忙等（不能用 setTimeout：脚本要在同一个同步流程里保持 fd 打开）
function sleep(ms) { const t = Date.now(); while (Date.now() - t < ms) { /* spin */ } }

function kbReport(code, shift) {
  return [0x01, shift ? 0x02 : 0x00, 0x00, code & 0xFF, 0, 0, 0, 0, 0];
}
function consReport(usage) {
  return [0x02, usage & 0xFF, (usage >> 8) & 0xFF];
}

function buildAsciiTable() {
  const t = {};
  // 字母
  for (let i = 0; i < 26; i++) {
    t[String.fromCharCode(0x61 + i)] = [0x04 + i, false];
    t[String.fromCharCode(0x41 + i)] = [0x04 + i, true];
  }
  // 数字行
  for (let i = 1; i <= 9; i++) t[String(i)] = [0x1E + (i - 1), false];
  t['0'] = [0x27, false];
  // 符号（无 Shift）
  const plain = {
    '-': 0x2D, '=': 0x2E, '[': 0x2F, ']': 0x30, '\\': 0x31,
    ';': 0x33, "'": 0x34, '`': 0x35, ',': 0x36, '.': 0x37, '/': 0x38,
  };
  for (const [c, v] of Object.entries(plain)) t[c] = [v, false];
  // 符号（带 Shift）
  const shifted = {
    '_': 0x2D, '+': 0x2E, '{': 0x2F, '}': 0x30, '|': 0x31,
    ':': 0x33, '"': 0x34, '~': 0x35, '<': 0x36, '>': 0x37, '?': 0x38,
    '!': 0x1E, '@': 0x1F, '#': 0x20, '$': 0x21, '%': 0x22, '^': 0x23,
    '&': 0x24, '*': 0x25, '(': 0x26, ')': 0x27,
  };
  for (const [c, v] of Object.entries(shifted)) t[c] = [v, true];
  t[' '] = [0x2C, false];
  t['\n'] = [0x28, false];
  t['\t'] = [0x2B, false];
  return t;
}

function emit(o) { process.stdout.write(JSON.stringify(o) + '\n'); }

function probe() {
  let writable = false;
  let reason = '';
  try {
    const fd = fs.openSync('/dev/uhid', 'r+');
    fs.closeSync(fd);
    writable = true;
  } catch (e) {
    reason = e.message;
  }
  emit({
    ok: true,
    node: '/dev/uhid',
    exists: fs.existsSync('/dev/uhid'),
    writable,
    uid: typeof process.getuid === 'function' ? process.getuid() : -1,
    reason: reason || undefined,
    note: 'each call creates and destroys the virtual device; run tap/text, not create alone',
  });
}

function parseArgs(argv) {
  const body = {};
  const positional = [];
  for (const a of argv) {
    if (a.indexOf('=') > 0 && !/^\d+$/.test(a)) {
      const k = a.slice(0, a.indexOf('='));
      let v = a.slice(a.indexOf('=') + 1);
      if (v === 'true') body[k] = true;
      else if (v === 'false') body[k] = false;
      else if (/^-?\d+$/.test(v)) body[k] = parseInt(v, 10);
      else body[k] = v;
    } else {
      positional.push(a);
    }
  }
  return { body, positional };
}

function run(op, req) {
  const kind = req.kind || 'keyboard';
  if (kind !== 'keyboard' && kind !== 'consumer' && kind !== 'mouse') {
    emit({ ok: false, error: 'unknown_kind', kind });
    return 1;
  }
  const desc = kind === 'keyboard' ? D_KEYBOARD : (kind === 'consumer' ? D_CONSUMER : D_MOUSE);
  const defName = kind === 'keyboard' ? 'DSH Virtual Keyboard'
    : (kind === 'consumer' ? 'DSH Virtual Media Keys' : 'DSH Virtual Mouse');
  const name = req.name || defName;

  let fd = null;
  const cleanup = () => {
    if (fd !== null) {
      try { const d = Buffer.alloc(4); putU32(d, 0, UHID_DESTROY); fs.writeSync(fd, d); } catch (e) { /* 已在销毁 */ }
      try { fs.closeSync(fd); } catch (e) { /* ignore */ }
      fd = null;
    }
  };
  const fail = (code, extra) => {
    emit(Object.assign({ ok: false, error: code }, extra || {}));
    cleanup();
    return 1;
  };

  try {
    fd = fs.openSync('/dev/uhid', 'r+');
  } catch (e) {
    return fail('uhid_open_failed', { reason: e.message, hint: 'needs uid 0 (engine process is root)' });
  }

  try {
    fs.writeSync(fd, buildCreate2(name, desc));
  } catch (e) {
    return fail('uhid_create_failed', { reason: e.message });
  }

  // ★ 必须等内核把设备注册进 InputManager 再注入首帧。
  // 实测：create 后立刻写 INPUT2，事件被丢弃（音量、按键都不生效），
  // 因为 uhid 的 device_add 是异步的、要经 udev/InputReader 才能识别。
  // 200ms 是实测可靠的下限（50ms 时约一半失败）。
  if (op !== 'create') sleep(200);

  try {
    if (op === 'create') {
      emit({ ok: true, created: true, kind, name });
    } else if (op === 'tap') {
      const code = typeof req.code === 'number' ? req.code : Number(req.code);
      if (!isFinite(code) || code < 0) return fail('code_required', { usage: 'hid tap <code|alias> [kind=..]' });
      if (kind === 'keyboard') {
        fs.writeSync(fd, buildInput2(kbReport(code, !!req.shift)));
        sleep(30);
        fs.writeSync(fd, buildInput2(kbReport(0, false)));
      } else if (kind === 'consumer') {
        fs.writeSync(fd, buildInput2(consReport(code)));
        sleep(30);
        fs.writeSync(fd, buildInput2(consReport(0)));
      } else {
        return fail('tap_not_for_mouse', {});
      }
      emit({ ok: true, tapped: code, kind });
    } else if (op === 'key') {
      const codes = Array.isArray(req.keys) ? req.keys
        : (req.code !== undefined ? [Number(req.code)] : []);
      if (!codes.length) return fail('code_required', { usage: 'hid key <code>[,<code>...]' });
      for (const c of codes) {
        if (kind === 'consumer') fs.writeSync(fd, buildInput2(consReport(c)));
        else fs.writeSync(fd, buildInput2(kbReport(c, false)));
      }
      emit({ ok: true, sent: codes, note: 'keys held until process exits (fd close releases)' });
    } else if (op === 'text') {
      const s = String(req.text !== undefined ? req.text : '');
      if (!s) return fail('text_required', { usage: 'hid text <string>' });
      let sent = 0;
      const unmapped = [];
      for (const ch of s) {
        const m = ASCII[ch];
        if (!m) { unmapped.push(ch); continue; }
        fs.writeSync(fd, buildInput2(kbReport(m[0], m[1])));
        sleep(12);
        fs.writeSync(fd, buildInput2(kbReport(0, false)));
        sleep(12);
        sent++;
      }
      const o = { ok: true, sent };
      if (unmapped.length) {
        o.unmapped = unmapped.join('');
        o.hint = 'US layout printable ASCII only; for CJK use accessibility ACTION_SET_TEXT';
      }
      emit(o);
    } else if (op === 'mouse') {
      if (kind !== 'mouse') return fail('hid_kind_mismatch', { need: 'kind=mouse' });
      const cl = (v) => {
        const n = Math.max(-127, Math.min(127, parseInt(v, 10) || 0));
        return n < 0 ? n + 256 : n;
      };
      const btn = parseInt(req.button, 10);
      if (btn >= 0 && btn <= 2) {
        fs.writeSync(fd, buildInput2([0x03, (1 << btn) & 0xFF, 0, 0, 0]));
        sleep(30);
      }
      fs.writeSync(fd, buildInput2([0x03, 0, cl(req.dx), cl(req.dy), cl(req.wheel)]));
      emit({
        ok: true,
        dx: parseInt(req.dx, 10) || 0,
        dy: parseInt(req.dy, 10) || 0,
        wheel: parseInt(req.wheel, 10) || 0,
      });
    } else {
      return fail('unknown_op', { op });
    }
  } catch (e) {
    return fail('uhid_write_failed', { reason: e.message });
  }

  cleanup();
  return 0;
}

function usage() {
  process.stdout.write([
    'hid —— 虚拟 HID 设备（/dev/uhid）',
    '',
    '用法：',
    '  hid                                   探测（能否写 /dev/uhid、当前 uid）',
    '  hid create [name=..] [kind=..]        创建并立刻销毁（仅验证描述符）',
    '  hid tap <code> [kind=..]              按一下',
    '  hid text <string>                     输入 US 布局可打印 ASCII',
    '  hid key <c1,c2,..> [kind=..]          按下若干键',
    '  hid mouse dx=<n> dy=<n> [button=0|1|2] [wheel=<n>]',
    '',
    'kind（默认 keyboard）：',
    '  keyboard  键盘 usage（0x04=a，0x2C=空格，0x28=回车）',
    '  consumer  媒体 usage（0x80=音量+，0x81=音量-，0xCD=播放暂停，0xE2=静音）',
    '  mouse     dx/dy/wheel 相对移动',
    '',
    '限制：',
    '  · 每次调用都 创建→注入→销毁，create 单独调用无残留意义',
    '  · 中文等非 ASCII 请用无障碍（dev api POST /dev/type {"text":".."}）',
    '  · 需要引擎以 root 运行（uid 0，KernelSU/Magisk）',
    '  · 屏幕必须 Awake（Dozing 下系统丢弃按键）：先 input keyevent KEYCODE_WAKEUP',
    '',
    '常用别名（自动带正确 kind，优先用别名而不是 hex）：',
    '  vol+ vol- mute  play pause next prev stop rewind forward  bright+ bright-',
    '  enter esc back space tab up down left right home power',
    '  例：hid tap vol+    hid tap next    hid tap enter   hid key vol-',
    '',
    '例：',
    '  hid tap 128 kind=consumer       音量 +1',
    '  hid text "hello world"',
    '  hid mouse dx=20 dy=-10',
  ].join('\n') + '\n');
}

// ---- 入口 ----
const argv = process.argv.slice(2);
if (!argv.length || argv[0] === 'help' || argv[0] === '--help' || argv[0] === '-h') {
  if (!argv.length) probe(); else usage();
  process.exit(0);
}
const op = argv[0];
if (op === 'probe') { probe(); process.exit(0); }
const { body, positional } = parseArgs(argv.slice(1));
// 位置参数映射：tap/key 的第一个位置参数是 code；text 是整段文字
if (op === 'tap' || op === 'key') {
  if (positional.length && body.code === undefined) {
    const raw = positional[0];
    const al = resolveAlias(raw, body.kind);
    if (al) { body.code = al.code; body.kind = al.kind; }
    else body.code = /^\d+$/.test(raw) ? parseInt(raw, 10) : raw;
  }
  // 也允许通过 code=<别名> 传
  if (typeof body.code === 'string' && !/^\d+$/.test(body.code)) {
    const al2 = resolveAlias(body.code, body.kind);
    if (al2) { body.code = al2.code; body.kind = al2.kind; }
  }
  if (op === 'key' && positional.length) {
    const all = positional.join(',').split(',').filter(Boolean);
    body.keys = all.map((x) => parseInt(x, 10)).filter((x) => isFinite(x));
  }
} else if (op === 'text') {
  if (positional.length && body.text === undefined) body.text = positional.join(' ');
} else if (op === 'create') {
  if (positional.length && body.name === undefined) body.name = positional.join(' ');
}
process.exit(run(op, body));

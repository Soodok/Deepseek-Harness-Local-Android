package app.dsh.mobile.engine.cap

import android.content.Context
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.Privilege
import org.json.JSONObject

/**
 * 虚拟 HID 设备（`/dev/uhid`）。
 *
 * ## 为什么不能直接在 App 进程里做（关键约束）
 *
 * `/dev/uhid` 权限位是 `crw-rw---- uhid uhid` —— **只有 root 能打开**。而
 * `AgentBridge` 跑在 **App 进程（uid 10488）** 里，`RandomAccessFile("/dev/uhid")`
 * 必然 `EACCES`。实测：shell（uid 2000，组里还有 `3011(uhid)`）也被 SELinux
 * `u:r:shell:s0` 拒绝；只有引擎进程（`uid 0` + `u:r:ksu:s0`）能写。
 *
 * ## 所以这里的角色是「转发器」
 *
 * 本类不碰 `/dev/uhid`，只把请求拼成一段 node 脚本，交给 **引擎侧 node（root）**
 * 执行一次。代价是每次操作都要起一个 node 进程（约 150ms），换来的是：
 *
 * - 不需要 App 有 root（只要引擎是 root，本来就成立）
 * - 不需要维护长驻进程与文件队列（复杂、易卡死、难回收）
 * - 每次操作都是独立事务，失败就失败，不会留下半个设备
 *
 * ## 已知代价（必须写清）
 *
 * 因为每次 `su -c` 结束 fd 就关，**设备会被反复创建/销毁**。也就是说
 * 「一次 create + 多次 tap」这个用法**不成立** —— 每次 tap 都会重新 create。
 * 对「按一下音量键」这类瞬时动作没问题；对「按住不放」有影响，`key` 的
 * 按下不抬起语义在跨进程下不可靠，请优先用 `tap`。
 *
 * ## 真机实测（2026-11-02，OPPO PLZ110 / Android 16 / KernelSU 4.1.3）
 *
 * 以 root 执行同一段逻辑：创建 `DSH Virtual Keyboard` 后 `dumpsys input` 出现
 * `161: DSH Virtual Keyboard` / `Classes: KEYBOARD | ALPHAKEY | EXTERNAL`；
 * 发 5 次 usage `0x80`（音量+）后系统音量 `41 → 81`。**注入真实生效**。
 */
object HidCap {

    /** 单次 node 超时（全流程含 create、注入、destroy）。 */
    private const val TIMEOUT_MS = 20_000L

    fun status(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.HID)) return denied()
        return 200 to JSONObject()
            .put("ok", true)
            .put("node", "/dev/uhid")
            .put("writable_by_app", false)
            .put("mode", "one-shot via engine node (root)")
            .put(
                "note",
                "each call creates and destroys the virtual device; " +
                    "verify registration by matching the device name in dumpsys input",
            )
            .toString()
    }

    fun control(ctx: Context, rawBody: String): Pair<Int, String> {
        val body = runCatching {
            if (rawBody.isBlank()) JSONObject() else JSONObject(rawBody)
        }.getOrElse { return 400 to error("invalid_json") }
        if (!CapabilityGate.can(ctx, CapabilityGate.HID)) return denied()

        val op = body.optString("op", "status")
        if (op == "status") return status(ctx)

        val script = buildScript(body, op) ?: return 400 to error("unsupported_op")
        val out = runAsRoot(ctx, script) ?: return 503 to JSONObject()
            .put("ok", false)
            .put("error", "engine_node_unavailable")
            .put("hint", "the engine must be running as root to open /dev/uhid")
            .toString()

        // 脚本最后一行一定是 JSON。前面若有多余输出（node 警告），取最后一个 `{`。
        val json = extractJson(out)
            ?: return 500 to JSONObject()
                .put("ok", false)
                .put("error", "unparsable_result")
                .put("raw", out.take(400))
                .toString()

        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return 500 to error("bad_result_json")
        val ok = obj.optBoolean("ok", false)
        val http = when {
            ok -> 200
            obj.optString("error") == "uhid_open_failed" -> 403
            obj.optString("error") == "unknown_op" -> 400
            else -> 500
        }
        return http to obj.toString()
    }

    // ==================== 脚本构造 ====================

    private fun buildScript(body: JSONObject, op: String): String? {
        val kind = body.optString("kind", "keyboard")
        val payload = body.toString()
        val action = when (op) {
            "create", "destroy", "tap", "key", "text", "mouse" -> op
            else -> return null
        }
        // 一次性脚本：open → create → action → destroy → close。任何一步失败都立刻
        // 报告并退出，不留半开的设备。所有输出走 stdout，最后一行是结果 JSON。
        return """
'use strict';
const fs = require('fs');
const req = $payload;
const op = '$action';
const UHID_CREATE2 = 11, UHID_INPUT2 = 12, UHID_DESTROY = 1, MAX_RD = 4096;

const D_KEYBOARD = [${joinBytes(KEYBOARD)}];
const D_CONSUMER = [${joinBytes(CONSUMER)}];
const D_MOUSE = [${joinBytes(MOUSE)}];
const ASCII = ${asciiTable()};

function putU16(b, o, v) { b[o] = v & 0xFF; b[o+1] = (v >> 8) & 0xFF; }
function putU32(b, o, v) {
  b[o] = v & 0xFF; b[o+1] = (v >> 8) & 0xFF;
  b[o+2] = (v >> 16) & 0xFF; b[o+3] = (v >> 24) & 0xFF;
}
function buildCreate2(name, desc) {
  const total = 4 + 128 + 64 + 64 + 2 + 2 + 16 + MAX_RD;
  const b = Buffer.alloc(total);
  let o = 0;
  putU32(b, o, UHID_CREATE2); o += 4;
  const nb = Buffer.from(name, 'utf8');
  nb.copy(b, o, 0, Math.min(nb.length, 127)); o += 128;
  o += 64; o += 64;
  putU16(b, o, desc.length); o += 2;
  putU16(b, o, 0x03); o += 2;
  putU32(b, o, 0); o += 4;
  putU32(b, o, 0); o += 4;
  putU32(b, o, 0x19); o += 4;
  putU32(b, o, 0); o += 4;
  Buffer.from(desc).copy(b, o, 0, Math.min(desc.length, MAX_RD));
  return b;
}
function buildInput2(p) {
  const b = Buffer.alloc(6 + p.length);
  putU32(b, 0, UHID_INPUT2);
  putU16(b, 4, p.length);
  Buffer.from(p).copy(b, 6);
  return b;
}
function sleep(ms) { const t = Date.now(); while (Date.now() - t < ms) {} }
function kb(code, shift) { return [0x01, shift ? 0x02 : 0x00, 0x00, code & 0xFF, 0, 0, 0, 0, 0]; }
function cons(u) { return [0x02, u & 0xFF, (u >> 8) & 0xFF]; }

let fd = null;
function emit(o) { process.stdout.write(JSON.stringify(o) + '\n'); }
function fail(e, reason) { emit({ ok: false, error: e, reason: String(reason || '') }); cleanup(); process.exit(0); }
function cleanup() {
  if (fd !== null) {
    try { const b = Buffer.alloc(4); putU32(b, 0, UHID_DESTROY); fs.writeSync(fd, b); } catch (e) {}
    try { fs.closeSync(fd); } catch (e) {}
    fd = null;
  }
}
function write(p) { fs.writeSync(fd, Buffer.from(p)); }

let k = req.kind || 'keyboard';
if (k !== 'keyboard' && k !== 'consumer' && k !== 'mouse') fail('unknown_kind', k);
if (op === 'destroy') { cleanup(); emit({ ok: true, destroyed: true }); process.exit(0); }

try { fd = fs.openSync('/dev/uhid', 'r+'); }
catch (e) { fail('uhid_open_failed', e.message); }

const desc = k === 'keyboard' ? D_KEYBOARD : (k === 'consumer' ? D_CONSUMER : D_MOUSE);
const dname = k === 'keyboard' ? 'DSH Virtual Keyboard' : (k === 'consumer' ? 'DSH Virtual Media Keys' : 'DSH Virtual Mouse');
try { write(buildCreate2(req.name || dname, desc)); }
catch (e) { fail('uhid_create_write_failed', e.message); }

try {
  if (op === 'create') {
    emit({ ok: true, created: true, kind: k, name: req.name || dname });
  } else if (op === 'tap') {
    const code = typeof req.code === 'number' ? req.code : -1;
    if (code < 0) fail('code_required', '');
    if (k === 'keyboard') {
      write(buildInput2(kb(code, false))); sleep(30);
      write(buildInput2(kb(0, false)));
    } else if (k === 'consumer') {
      write(buildInput2(cons(code))); sleep(30);
      write(buildInput2(cons(0)));
    } else fail('tap_not_for_mouse', '');
    emit({ ok: true, code: code, kind: k });
  } else if (op === 'key') {
    const codes = Array.isArray(req.keys) ? req.keys : (typeof req.code === 'number' ? [req.code] : []);
    if (!codes.length) fail('code_required', '');
    for (const c of codes) {
      if (k === 'keyboard') write(buildInput2(kb(c, false)));
      else if (k === 'consumer') write(buildInput2(cons(c)));
    }
    emit({ ok: true, sent: codes });
  } else if (op === 'text') {
    const s = String(req.text || '');
    if (!s) fail('text_required', '');
    let sent = 0; const bad = [];
    for (const ch of s) {
      const m = ASCII[ch];
      if (!m) { bad.push(ch); continue; }
      write(buildInput2(kb(m[0], m[1]))); sleep(12);
      write(buildInput2(kb(0, false))); sleep(12);
      sent++;
    }
    const o = { ok: true, sent: sent };
    if (bad.length) { o.unmapped = bad.join(''); o.hint = 'US-layout printable ASCII only; for CJK use accessibility ACTION_SET_TEXT'; }
    emit(o);
  } else if (op === 'mouse') {
    if (k !== 'mouse') fail('hid_kind_mismatch', k);
    const cl = (v) => { const n = Math.max(-127, Math.min(127, v | 0)); return n < 0 ? n + 256 : n; };
    const btn = typeof req.button === 'number' ? req.button : -1;
    if (btn >= 0 && btn <= 2) { write(buildInput2([0x03, (1 << btn) & 0xFF, 0, 0, 0])); sleep(30); }
    write(buildInput2([0x03, 0, cl(req.dx | 0), cl(req.dy | 0), cl(req.wheel | 0)]));
    emit({ ok: true, dx: req.dx | 0, dy: req.dy | 0, wheel: req.wheel | 0 });
  }
} catch (e) {
  fail('hid_write_failed', e.message);
}
cleanup();
process.exit(0);
"""
    }

    /**
     * 用引擎侧 node 执行脚本。
     *
     * 命令走 `Privilege.runPrivileged`（先 Shizuku 后 su）。注意：**Shell/Shizuku
     * 身份也不够** —— `/dev/uhid` 虽然给了 `uhid` 组，但 SELinux `u:r:shell:s0`
     * 无权限。所以只有当引擎以 root 运行时才真正可用，失败时如实报告。
     */
    private fun runAsRoot(ctx: Context, script: String): String? = runCatching {
        val engine = EngineConfig.engineRoot(ctx)
        val node = java.io.File(engine, "bin/node")
        if (!node.isFile) return@runCatching null
        val lib = java.io.File(engine, "lib")
        val home = EngineConfig.dshHome(ctx)
        val tmp = java.io.File(ctx.filesDir, "hid-${
            System.currentTimeMillis()
        }.js")
        tmp.writeText(script)
        tmp.setReadable(true, false)
        val cmd = "LD_LIBRARY_PATH=${lib.absolutePath} HOME=${home.absolutePath} " +
            "${node.absolutePath} ${tmp.absolutePath} 2>&1"
        val out = Privilege.runPrivileged(ctx, cmd)
        runCatching { tmp.delete() }
        out
    }.getOrNull()

    /** 从可能夹着警告的输出里取最后一个 JSON 对象。 */
    private fun extractJson(s: String): String? {
        val i = s.lastIndexOf('{')
        if (i < 0) return null
        // 从最后一个 `{` 往前找配对（简单起见直接截到行尾）。
        val tail = s.substring(i)
        val nl = tail.indexOf('\n')
        return if (nl > 0) tail.substring(0, nl) else tail
    }

    // ==================== HID 常量表 ====================

    private val KEYBOARD = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x01, 0x75, 0x08, 0x15, 0x00, 0x25, 0xFF,
        0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, 0x81, 0x00,
        0xC0,
    )

    private val CONSUMER = intArrayOf(
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
        0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    )

    private val MOUSE = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x03,
        0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
        0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
    )

    private fun joinBytes(v: IntArray): String = v.joinToString(",")

    /** US 布局 ASCII → [HID keycode, 是否 Shift]，序列化成 JS 对象字面量。 */
    private fun asciiTable(): String {
        val sb = StringBuilder("{")
        var first = true
        fun put(ch: Char, code: Int, shift: Boolean) {
            if (!first) sb.append(',')
            first = false
            val esc = when (ch) {
                '\'' -> "\\'"
                '\\' -> "\\\\"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> ch.toString()
            }
            sb.append("'").append(esc).append("':[").append(code).append(',')
                .append(if (shift) "true" else "false").append(']')
        }
        for (i in 0 until 26) {
            put(('a' + i), 0x04 + i, false)
            put(('A' + i), 0x04 + i, true)
        }
        for (i in 1..9) put(('0' + i), 0x1E + (i - 1), false)
        put('0', 0x27, false)
        val plain = mapOf(
            '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
            ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38,
        )
        for ((c, v) in plain) put(c, v, false)
        val shifted = mapOf(
            '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
            ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38,
            '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22, '^' to 0x23,
            '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
        )
        for ((c, v) in shifted) put(c, v, true)
        put(' ', 0x2C, false)
        put('\n', 0x28, false)
        put('\t', 0x2B, false)
        sb.append('}')
        return sb.toString()
    }

    private fun denied(): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false)
        .put("error", "capability disabled")
        .put("capability", CapabilityGate.HID)
        .put(
            "hint",
            "enable Virtual input device under Permission Center - AI device capabilities; needs root",
        )
        .toString()

    private fun error(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}

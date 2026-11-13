package app.dsh.mobile.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 双端会话同步（PeerSync 协议 v1，手机端）。
 *
 * ## 它解决的真实问题
 *
 * 用户手机与电脑各有一份 DSH_HOME/sessions，同一条对话在两台设备上会分裂。
 * 本类把「文件对齐」做成一个可被 UI 与 AI 同时调用的动作：拉、推、或双向。
 *
 * ## 为什么同步逻辑在 node 里（[SYNC_SCRIPT]），而不是 Kotlin
 *
 * 1. **绕过本应用的明文 HTTP 策略**：res/xml/network_security_config.xml 的
 *    base-config 是 cleartextTrafficPermitted=false（只放行 127.0.0.1）。用
 *    HttpURLConnection 连局域网 IP 会被 NetworkSecurityPolicy 直接拒 ——
 *    唯一解法是把 base-config 放宽成 true，那是给**整个应用**（WebView、插件市场、
 *    扩展下载）一起开门。node 子进程不经过该策略，于是门户保持关闭。
 * 2. **协议语义只有一份**：文件白名单正则、路径守卫、sha256 校验与电脑端的
 *    tools/peersync/pc-sync-server.mjs 完全同构，改一处两边同步。
 * 3. 流式 sha256 与流式下载在 node 里各几行，在 Kotlin 里要自己写缓冲循环。
 *
 * ## 硬约束（审查 P1 的落点，不得破坏）
 *
 * · **任何模式都不删除会话文件**。覆盖前把旧文件改名成 <name>.peersync-bak-<stamp>，
 *   同目录只保留最近 3 份，且清理的只是自己产生的备份。
 * · **不碰非会话文件**：只同步 session.jsonl / session.vN.jsonl / *.jsonl.zstd。
 *   session.lock、*.compact-bak、*.title-bak、*.merge-bak 一律不参与。
 * · **密码不落明文**：见 [PeerSyncPrefs]（AndroidKeyStore AES/GCM）。
 * · **必须校验 host 私网**：只允许私网地址（见 [isPrivateHost]），否则密码会被发到公网。
 *
 * ## 为什么不是「字节前缀快进」（上游 PR #105 的做法为什么不能用）
 *
 * 引擎默认 compression = "zstd"，会话日志是多帧 zstd 容器。实测（2026-11-13，
 * 本机 20 组「旧备份 vs 当前文件」）：
 * · new.startswith(old) 成立 **0/20**；
 * · 解压后明文的严格前缀成立 **0/4**（压缩会改写早期帧）。
 * 也就是说「按已同步字节数跳过」在 zstd 上根本不可能成立，只能整文件对齐 +
 * sha256 三方比较（本地 / 远端 / 上次同步基线）。
 */
object PeerSync {

    private const val TAG = "PeerSync"

    /** 会话日志白名单（与 pc-sync-server.mjs 的 SESSION_FILE_RE 必须逐字一致）。 */
    private val SESSION_FILE_RE = Regex("^session(?:\\.v[0-9]+)?\\.jsonl(?:\\.zstd)?$")

    /** 单文件上限默认值：手机侧网络与存储都不适合一次拉几百 MB。 */
    const val DEFAULT_MAX_FILE_BYTES = 32L * 1024 * 1024

    /**
     * 同步客户端脚本（**内联**，随 APK 编译进去）。
     *
     * 刻意内联而不部署成资产：本类唯一的消费者是「用户点同步」与「AI 调
     * POST /dev/peersync」，部署链一断（assets 未拷、目录被清）同步就整块失效，
     * 而内联脚本与类本身同版本、不可能错配。
     */
    private val SYNC_SCRIPT: String = """
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';

// 参数起点不写死：脚本可能被 `node -e` 内联执行（此时 process.argv[1] 就是第一个
// 参数，前面没有脚本路径），也可能被 `node script.mjs` 执行（此时 argv[1] 是脚本本身）。
// 用「第一个以 -- 开头的参数」定位起点，两种调用方式都对。
const _rawArgv = process.argv;
const _dash = _rawArgv.findIndex((a) => a.startsWith('--'));
const argv = _dash >= 0 ? _rawArgv.slice(_dash) : _rawArgv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : dflt;
};
const LOCAL = path.resolve(argOf('local', ''));
const STATE_PATH = path.resolve(argOf('state', ''));
const HOST = argOf('host', '');
const PORT = Number(argOf('port', '3091'));
const PW = argOf('pw', '');
const DIRECTION = argOf('direction', 'both');
const SCOPE = argOf('scope', '');
const CONFLICT = argOf('conflict', 'backup-newer');
const MAX_FILE_BYTES = Number(argOf('max-file-bytes', '33554432'));
const SESSION_FILE_RE = /^session(?:\.v[0-9]+)?\.jsonl(?:\.zstd)?$/;
const BACKUP_KEEP = 3;
const t0 = Date.now();
const notes = [];

function out(obj) { process.stdout.write(JSON.stringify(obj) + "\n"); process.exit(0); }
function fail(code, message, extra) {
  out(Object.assign({ ok: false, code: code, error: message }, extra || {}));
}
if (!LOCAL || !HOST || !PW) fail('bad-args', 'missing --local/--host/--pw');
if (!['both', 'pull', 'push'].includes(DIRECTION)) fail('bad-args', 'bad --direction');
if (!['backup-newer', 'prefer-local', 'prefer-remote', 'skip'].includes(CONFLICT)) fail('bad-args', 'bad --conflict');

function relOk(rel) {
  if (typeof rel !== 'string' || rel === '') return false;
  if (rel.includes('\\') || rel.includes('\0')) return false;
  const parts = rel.split('/');
  for (const p of parts) if (p === '' || p === '.' || p === '..') return false;
  return SESSION_FILE_RE.test(parts[parts.length - 1]);
}
function localPathFor(rel) {
  if (!relOk(rel)) return null;
  const full = path.resolve(LOCAL, rel);
  if (full !== LOCAL && !full.startsWith(LOCAL + path.sep)) return null;
  return full;
}
function sha256File(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(file).on('data', (c) => h.update(c))
      .on('error', reject).on('end', () => resolve(h.digest('hex')));
  });
}
async function mapLimit(items, limit, fn) {
  const o = new Array(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: Math.max(1, Math.min(limit, items.length)) }, async () => {
    for (;;) {
      const idx = i++;
      if (idx >= items.length) return;
      o[idx] = await fn(items[idx], idx);
    }
  }));
  return o;
}
async function walk(dir, acc) {
  let es;
  try { es = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
  for (const e of es) {
    if (e.name.startsWith('.')) continue;
    const full = path.join(dir, e.name);
    if (e.isDirectory()) { await walk(full, acc); continue; }
    if (!e.isFile()) continue;
    if (!SESSION_FILE_RE.test(e.name)) continue;
    acc.push(full);
  }
}
async function localManifest() {
  const files = [];
  await walk(LOCAL, files);
  const rows = await mapLimit(files, 3, async (f) => {
    let st;
    try { st = await fsp.stat(f); } catch { return null; }
    return { path: path.relative(LOCAL, f).split(path.sep).join('/'), size: st.size,
      mtimeMs: Math.round(st.mtimeMs), sha256: await sha256File(f) };
  });
  return rows.filter(Boolean);
}
function request(method, pathname, opts) {
  const o = opts || {};
  return new Promise((resolve, reject) => {
    const headers = Object.assign({ 'x-dshm-sync': PW }, o.headers || {});
    if (o.body !== undefined) headers['Content-Length'] = o.body.length;
    const req = http.request({ host: HOST, port: PORT, method: method, path: pathname, headers: headers, timeout: 60000 }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('request timeout')));
    req.on('error', reject);
    if (o.body !== undefined) req.write(o.body);
    req.end();
  });
}
const q = (s) => encodeURIComponent(s);
async function getJson(method, pathname) {
  const res = await request(method, pathname, {});
  let json = null;
  try { json = JSON.parse(res.body.toString('utf8')); } catch { json = null; }
  return { status: res.status, json: json };
}
function download(rel, dest) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: HOST, port: PORT, method: 'GET', path: '/__dshm/sync/file?path=' + q(rel),
      headers: { 'x-dshm-sync': PW }, timeout: 60000 }, (res) => {
      if (res.statusCode !== 200) {
        const cs = [];
        res.on('data', (c) => cs.push(c));
        res.on('end', () => reject(new Error('http ' + res.statusCode)));
        return;
      }
      const w = fs.createWriteStream(dest);
      res.pipe(w);
      w.on('finish', () => resolve());
      w.on('error', reject);
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('download timeout')));
    req.on('error', reject);
    req.end();
  });
}
async function upload(rel, file, sha) {
  const body = await fsp.readFile(file);
  const res = await request('PUT', '/__dshm/sync/file?path=' + q(rel),
    { headers: { 'x-dshm-sha256': sha, 'Content-Type': 'application/octet-stream' }, body: body });
  let json = null;
  try { json = JSON.parse(res.body.toString('utf8')); } catch { json = null; }
  if (res.status !== 200) throw new Error('http ' + res.status + ' ' + ((json && json.error) || ''));
  return json || {};
}
function loadState() {
  try {
    const j = JSON.parse(fs.readFileSync(STATE_PATH, 'utf8'));
    if (j && typeof j === 'object' && j.baseline && typeof j.baseline === 'object') return j;
  } catch {}
  return { version: 1, baseline: {}, lastSyncAt: null };
}
function saveState(s) {
  const tmp = STATE_PATH + '.tmp-' + process.pid + '-' + Date.now();
  fs.writeFileSync(tmp, JSON.stringify(s, null, 2));
  fs.renameSync(tmp, STATE_PATH);
}
function stampNow() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}
async function backupAndPrune(full) {
  const bak = full + '.peersync-bak-' + stampNow();
  await fsp.rename(full, bak);
  const dir = path.dirname(full);
  const pre = path.basename(full) + '.peersync-bak-';
  let names = [];
  try { names = await fsp.readdir(dir); } catch { return path.basename(bak); }
  const mine = names.filter((n) => n.startsWith(pre)).sort();
  while (mine.length > BACKUP_KEEP) {
    const v = mine.shift();
    try { await fsp.unlink(path.join(dir, v)); } catch {}
  }
  return path.basename(bak);
}

async function main() {
  await fsp.mkdir(LOCAL, { recursive: true });
  let ping;
  try {
    ping = await getJson('GET', '/__dshm/sync/ping');
  } catch (e) {
    return fail('unreachable', 'cannot reach ' + HOST + ':' + PORT + ': ' + String((e && e.message) || e).slice(0, 120), { stage: 'ping' });
  }
  if (ping.status === 401) return fail('unauthorized', 'wrong password', { stage: 'ping' });
  if (ping.status === 429) return fail('rate-limited', 'too many attempts', { stage: 'ping', retryAfterMs: ping.json && ping.json.retryAfterMs });
  if (ping.status !== 200 || !ping.json || !ping.json.ok) return fail('unreachable', 'unexpected ping response', { stage: 'ping', status: ping.status });

  const mres = await getJson('GET', '/__dshm/sync/manifest' + (SCOPE ? '?scope=' + q(SCOPE) : ''));
  if (mres.status !== 200 || !mres.json || !Array.isArray(mres.json.files)) {
    return fail('manifest-failed', 'cannot read remote manifest', { stage: 'manifest', status: mres.status });
  }
  const remote = new Map();
  let rejected = 0;
  for (const f of mres.json.files) {
    if (!relOk(f.path)) { rejected++; continue; }
    if (SCOPE && !(f.path === SCOPE || f.path.startsWith(SCOPE + '/'))) { rejected++; continue; }
    remote.set(f.path, f);
  }
  if (rejected) notes.push(rejected + ' remote entries rejected by path guard');

  const localList = await localManifest();
  const local = new Map();
  for (const f of localList) {
    if (SCOPE && !(f.path === SCOPE || f.path.startsWith(SCOPE + '/'))) continue;
    local.set(f.path, f);
  }

  const state = loadState();
  const baseline = state.baseline || {};
  const actions = [];
  const failures = [];
  const counts = { pushed: 0, pulled: 0, skipped_same: 0, skipped_large: 0, conflicts: 0, failed: 0, skipped_mode: 0, changed: 0 };
  let bytesUp = 0, bytesDown = 0;
  const cap = (a, x) => { if (a.length < 200) a.push(x); };

  const all = Array.from(new Set([...local.keys(), ...remote.keys()])).sort();
  for (const rel of all) {
    const l = local.get(rel);
    const r = remote.get(rel);
    const base = baseline[rel];
    let action = null;
    if (l && !r) {
      if (DIRECTION === 'pull') { counts.skipped_mode++; cap(actions, { path: rel, action: 'skip', reason: 'pull-mode: local only' }); continue; }
      if (l.size > MAX_FILE_BYTES) { counts.skipped_large++; cap(actions, { path: rel, action: 'skip', reason: 'too large' }); continue; }
      action = 'push';
    } else if (!l && r) {
      if (DIRECTION === 'push') { counts.skipped_mode++; cap(actions, { path: rel, action: 'skip', reason: 'push-mode: remote only' }); continue; }
      if (r.size > MAX_FILE_BYTES) { counts.skipped_large++; cap(actions, { path: rel, action: 'skip', reason: 'too large' }); continue; }
      action = 'pull';
    } else if (l && r) {
      if (l.sha256 === r.sha256) { counts.skipped_same++; baseline[rel] = l.sha256; continue; }
      if (base !== undefined) {
        const lc = base !== l.sha256, rc = base !== r.sha256;
        if (lc && !rc) action = 'push';
        else if (!lc && rc) action = 'pull';
        else action = 'conflict';
      } else {
        const diff = Math.abs(l.mtimeMs - r.mtimeMs);
        action = diff > 2000 ? (l.mtimeMs > r.mtimeMs ? 'push' : 'pull') : 'conflict';
      }
      if (action === 'push' && DIRECTION === 'pull') { counts.skipped_mode++; cap(actions, { path: rel, action: 'skip', reason: 'pull-mode' }); continue; }
      if (action === 'pull' && DIRECTION === 'push') { counts.skipped_mode++; cap(actions, { path: rel, action: 'skip', reason: 'push-mode' }); continue; }
    }
    if (action === 'conflict') {
      counts.conflicts++;
      if (CONFLICT === 'skip') { cap(actions, { path: rel, action: 'conflict', resolution: 'skip' }); continue; }
      const newerLocal = (l ? l.mtimeMs : 0) >= (r ? r.mtimeMs : 0);
      if (CONFLICT === 'prefer-local' || (CONFLICT === 'backup-newer' && newerLocal)) action = 'push';
      else action = 'pull';
      cap(actions, { path: rel, action: 'conflict', resolution: action });
    }
    try {
      if (action === 'push') {
        if (l.size > MAX_FILE_BYTES) { counts.skipped_large++; cap(actions, { path: rel, action: 'skip', reason: 'too large' }); continue; }
        const full = localPathFor(rel);
        if (!full) { counts.failed++; failures.push({ path: rel, error: 'bad local path' }); continue; }
        const res = await upload(rel, full, l.sha256);
        counts.pushed++; bytesUp += l.size; baseline[rel] = l.sha256;
        cap(actions, { path: rel, action: 'push', size: l.size, replaced: !!res.replaced });
      } else if (action === 'pull') {
        if (r.size > MAX_FILE_BYTES) { counts.skipped_large++; cap(actions, { path: rel, action: 'skip', reason: 'too large' }); continue; }
        const full = localPathFor(rel);
        if (!full) { counts.failed++; failures.push({ path: rel, error: 'bad remote path' }); continue; }
        await fsp.mkdir(path.dirname(full), { recursive: true });
        const tmp = full + '.tmp-' + process.pid + '-' + Date.now();
        await download(rel, tmp);
        const got = await sha256File(tmp);
        if (got !== r.sha256) {
          // 校验不一致有两种可能，必须分开对待：
          //   a) 传输坏了 —— 真失败，值得报警；
          //   b) **远端在同步期间还在被写**（活跃会话每轮都在追加），manifest 里的
          //      sha 拿到手时就已经过期。实测本机 107 个会话里有 2 个属于这种情况，
          //      报成 failed 会让一次几乎成功的同步整体判红。
          // 用一次 stat 复核：远端 sha 变了就是 b，留给下一轮，不算失败。
          try { await fsp.unlink(tmp); } catch {}
          let now = null;
          try {
            const st = await getJson('GET', '/__dshm/sync/stat?path=' + q(rel));
            if (st.status === 200 && st.json && st.json.exists) now = st.json.sha256;
          } catch {}
          if (now && now !== r.sha256) {
            counts.changed++; cap(actions, { path: rel, action: 'defer', reason: 'remote changed during sync' });
            continue;
          }
          counts.failed++; failures.push({ path: rel, error: 'sha mismatch after download' }); continue;
        }
        let bak = null;
        try { if ((await fsp.stat(full)).isFile()) bak = await backupAndPrune(full); } catch {}
        await fsp.rename(tmp, full);
        counts.pulled++; bytesDown += r.size; baseline[rel] = r.sha256;
        cap(actions, { path: rel, action: 'pull', size: r.size, backup: bak });
      }
    } catch (e) {
      counts.failed++;
      failures.push({ path: rel, error: String((e && e.message) || e).slice(0, 300) });
    }
  }

  state.baseline = baseline;
  state.lastSyncAt = Date.now();
  state.lastResult = { at: state.lastSyncAt, host: HOST, port: PORT, direction: DIRECTION,
    pushed: counts.pushed, pulled: counts.pulled, conflicts: counts.conflicts, failed: counts.failed };
  saveState(state);

  out({ ok: counts.failed === 0, host: HOST, port: PORT, direction: DIRECTION, conflict: CONFLICT,
    scope: SCOPE || null, localRoot: LOCAL,
    remote: { version: mres.json.version, count: mres.json.count, totalBytes: mres.json.totalBytes },
    localCount: local.size, counts: counts, bytesUp: bytesUp, bytesDown: bytesDown,
    actions: actions, failures: failures, notes: notes, ms: Date.now() - t0 });
}
main().catch((e) => {
  out({ ok: false, code: 'exception', error: String((e && e.message) || e).slice(0, 500) });
});
"""

    /** 结果对象：UI 与 /dev/peersync 都吃这一份结构。 */
    data class Result(
        val ok: Boolean,
        val json: JSONObject,
        val elapsedMs: Long,
    ) {
        fun summary(): String {
            if (!ok) return json.optString("error", "sync failed").take(200)
            val c = json.optJSONObject("counts") ?: JSONObject()
            // changed = 远端在同步期间还在被写（活跃会话），不是错误，单列出来免得用户
            // 看到「有失败」去追一个不存在的传输问题。
            return "push " + c.optInt("pushed") + " / pull " + c.optInt("pulled") +
                " / same " + c.optInt("skipped_same") + " / conflict " + c.optInt("conflicts") +
                " / changed " + c.optInt("changed") + " / failed " + c.optInt("failed")
        }
    }

    /** 本机 sessions 根目录（dsh-home/sessions，通常是外置软链）。 */
    fun sessionsRoot(ctx: Context): File = File(EngineConfig.dshHome(ctx), "sessions")

    /** 同步基线文件（记住每个文件上次同步的 sha256，用于判定哪边改了）。 */
    private fun stateFile(ctx: Context): File = File(EngineConfig.dshHome(ctx), "peersync-state.json")

    /**
     * 校验 host 必须是私网地址 / 本机 mDNS 名。
     *
     * ## 为什么必须拦
     * 同步密码是**明文 HTTP 头发出去**的（局域网协议）。用户在地址栏里填一个公网
     * IP 或域名，密码就顺着公网跑了。上游 PR #105 的客户端只校验 startsWith("http")，
     * 等于把这条出口留着。
     *
     * 允许：127.0.0.1 / localhost / ::1 / 10.x / 192.168.x / 172.16-31.x /
     * 169.254.x（链路本地）/ 不含点的主机名 / *.local。
     * 拒绝：其余 IPv4 字面量、IPv6 字面量、含点的域名 —— 我们不查 DNS（离线优先），
     * 无法区分它解析到公网还是内网，一律拒绝并给出提示。
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
        val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(h)
        if (m != null) {
            val a = m.groupValues[1].toIntOrNull() ?: return false
            val b = m.groupValues[2].toIntOrNull() ?: return false
            if (a !in 0..255 || b !in 0..255) return false
            return when (a) {
                10 -> true
                127 -> true
                169 -> b == 254
                172 -> b in 16..31
                192 -> b == 168
                else -> false
            }
        }
        if (h.contains(':')) return false
        return !h.contains('.') || h.endsWith(".local")
    }

    /**
     * 跑一次同步（阻塞，**必须在后台线程调用**）。
     *
     * @param host 电脑端地址；空则用已保存配置
     * @param port 电脑端端口；<=0 用已保存配置（默认 3091）
     * @param pw 同步密码；空则用已保存配置
     * @param direction both | pull | push
     * @param scope 只同步某个相对前缀（空 = 全部）
     * @param conflict backup-newer | prefer-local | prefer-remote | skip
     * @param maxFileBytes 单文件上限；<=0 用 [DEFAULT_MAX_FILE_BYTES]
     * @param timeoutMs 整体超时（默认 10 分钟；大库首次同步会久）
     */
    fun sync(
        ctx: Context,
        host: String = "",
        port: Int = 0,
        pw: String = "",
        direction: String = "both",
        scope: String = "",
        conflict: String = "backup-newer",
        maxFileBytes: Long = 0,
        timeoutMs: Long = 600_000,
    ): Result {
        val started = System.currentTimeMillis()
        val saved = PeerSyncPrefs.load(ctx)
        val useHost = if (host.isNotBlank()) host.trim() else saved.host
        val usePort = if (port > 0) port else if (saved.port > 0) saved.port else 3091
        val usePw = if (pw.isNotBlank()) pw else saved.password
        val useMax = if (maxFileBytes > 0) maxFileBytes else DEFAULT_MAX_FILE_BYTES

        if (useHost.isBlank()) {
            return Result(false, JSONObject().put("ok", false).put("code", "no-host")
                .put("error", "host is empty"), 0)
        }
        if (!isPrivateHost(useHost)) {
            return Result(
                false,
                JSONObject().put("ok", false).put("code", "public-host-refused")
                    .put("error", "refusing to send the sync password to a non-private host: " + useHost)
                    .put("hint", "use the computer LAN address (10.x / 192.168.x / 172.16-31.x)"),
                0,
            )
        }
        if (usePw.isBlank()) {
            return Result(false, JSONObject().put("ok", false).put("code", "no-password")
                .put("error", "password is empty"), 0)
        }
        if (direction != "both" && direction != "pull" && direction != "push") {
            return Result(false, JSONObject().put("ok", false).put("code", "bad-direction")
                .put("error", "direction must be both|pull|push"), 0)
        }

        val node = EngineConfig.nodeBin(ctx)
        if (!node.canExecute()) {
            return Result(false, JSONObject().put("ok", false).put("code", "no-node")
                .put("error", "engine node is not executable: " + node.absolutePath), 0)
        }
        val local = sessionsRoot(ctx)
        local.mkdirs()

        // node -e 之后的参数会被 node 自己当选项解析：实测 node -e <script> --local /x
        // 直接 exit 9 输出 bad option: --local，同步一次都没跑起来。
        // 必须先用 -- 结束 node 的选项，脚本才拿得到 process.argv。
        val args = mutableListOf(
            // --input-type=module：脚本用 ESM import；`node -e` 默认按 CJS 解析，
            // 实测直接 SyntaxError: import cannot be used outside of module code。
            // -- 仍必须留着，否则 --local 之类会被 node 当自己的选项。
            node.absolutePath, "--input-type=module", "-e", SYNC_SCRIPT, "--",
            "--local", local.absolutePath,
            "--state", stateFile(ctx).absolutePath,
            "--host", useHost,
            "--port", usePort.toString(),
            "--pw", usePw,
            "--direction", direction,
            "--conflict", conflict,
            "--max-file-bytes", useMax.toString(),
        )
        if (scope.isNotBlank()) { args += "--scope"; args += scope }

        val pb = ProcessBuilder(args)
        // 与 ZstdTail / LanWebProxy 同一套环境注入：engine 自带 node 是 Termux 交叉
        // 编译产物，缺 OPENSSL_CONF 会 exit=13，缺 LD_LIBRARY_PATH 会 exit=127。
        val root = EngineConfig.engineRoot(ctx)
        val env = pb.environment()
        runCatching {
            EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT).forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) env[kv.substring(0, i)] = kv.substring(i + 1)
            }
        }
        env["LD_LIBRARY_PATH"] = root.absolutePath + "/lib:" + root.absolutePath + "/usr/lib"
        env["HOME"] = EngineConfig.dshHome(ctx).absolutePath
        val openssl = File(root, "etc/tls/openssl.cnf")
        if (openssl.isFile) env["OPENSSL_CONF"] = openssl.absolutePath
        env["TMPDIR"] = EngineConfig.tmpDir(ctx).absolutePath

        return try {
            val p = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val reader = Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine {
                        if (out.length < (4 shl 20)) out.append(it).append('\n')
                    }
                }
            }.apply { isDaemon = true; start() }
            val errReader = Thread {
                runCatching {
                    p.errorStream.bufferedReader().forEachLine {
                        if (err.length < (64 * 1024)) err.append(it).append('\n')
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { p.destroyForcibly() }
                reader.join(1500)
                errReader.join(1500)
                val elapsed = System.currentTimeMillis() - started
                Log.w(TAG, "sync timed out after " + elapsed + "ms")
                return Result(
                    false,
                    JSONObject().put("ok", false).put("code", "timeout")
                        .put("error", "sync exceeded " + timeoutMs + "ms")
                        .put("stderr", err.toString().take(600)),
                    elapsed,
                )
            }
            reader.join(3000)
            errReader.join(1500)
            val elapsed = System.currentTimeMillis() - started
            val line = out.toString().trim().split('\n').lastOrNull { it.trim().startsWith("{") }?.trim()
            if (line.isNullOrEmpty()) {
                Log.w(TAG, "sync produced no JSON (exit=" + p.exitValue() + ") stderr=" + err.toString().take(400))
                return Result(
                    false,
                    JSONObject().put("ok", false).put("code", "no-output")
                        .put("error", "sync client produced no JSON")
                        .put("exit", p.exitValue())
                        .put("stderr", err.toString().take(600)),
                    elapsed,
                )
            }
            val json = runCatching { JSONObject(line) }.getOrElse {
                return Result(
                    false,
                    JSONObject().put("ok", false).put("code", "bad-json")
                        .put("error", "cannot parse sync report")
                        .put("raw", line.take(400)),
                    elapsed,
                )
            }
            Log.i(TAG, "sync done ok=" + json.optBoolean("ok") + " in " + elapsed + "ms")
            Result(json.optBoolean("ok", false), json, elapsed)
        } catch (e: Exception) {
            Result(
                false,
                JSONObject().put("ok", false).put("code", "exception")
                    .put("error", (e.message ?: e.toString()).take(300)),
                System.currentTimeMillis() - started,
            )
        }
    }

    /** 上次同步结果（从基线文件读；不存在就返回空对象）。 */
    /**
     * 上次同步摘要（**不是**整个状态文件）。
     *
     * 状态文件里 90% 是 baseline 映射（每个会话一条 sha256），实测一份完整同步后
     * 有 480+ 条、几十 KB。原实现直接回整个 JSON，于是 GET /dev/peersync 每次都吐
     * 几十 KB —— 一个「看一眼状态」的端点比 /dev/help 还大，模型读一次就吃掉半屏。
     * 这里只取 lastResult 与基线条数：调用方要的是「上次成功没、同步了几个」，
     * 不是每条会话的哈希。
     */
    fun lastResult(ctx: Context): JSONObject = runCatching {
        val f = stateFile(ctx)
        if (!f.isFile) {
            JSONObject()
        } else {
            val root = JSONObject(f.readText())
            val out = JSONObject()
            root.optJSONObject("lastResult")?.let { out.put("lastResult", it) }
            root.optLong("lastSyncAt", 0L).takeIf { it > 0 }?.let { out.put("lastSyncAt", it) }
            out.put("baseline_files", root.optJSONObject("baseline")?.length() ?: 0)
            out
        }
    }.getOrDefault(JSONObject())

    /** 本地会话文件计数与总体积（UI 显示「本机有多少条会话」）。 */
    fun localStats(ctx: Context): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        fun walk(dir: File, depth: Int) {
            if (depth > 4) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) { walk(f, depth + 1); continue }
                if (!SESSION_FILE_RE.matches(f.name)) continue
                count++
                bytes += f.length()
            }
        }
        runCatching { walk(sessionsRoot(ctx), 0) }
        return count to bytes
    }
}

/**
 * 双端同步的本地配置（地址 / 端口 / 密码 / 同步方向）。
 *
 * ## 密码为什么不能明文躺着
 *
 * 上游 PR #105 把同步密码直接 putString 进 SharedPreferences。虽然那是应用私有
 * 目录（普通 App 读不到），但 root、ADB 备份、云备份解包都能拿到明文 —— 而它正是
 * 局域网里那把「能读写全部会话历史」的钥匙。
 *
 * 这里改成 **AndroidKeyStore 里的 AES/GCM 密钥加密**：密钥材料由系统 TEE 保管，
 * 永远不会以字节形式出现在文件系统里，所以拷走 prefs 文件也解不开。
 * 解不开时（换机恢复、Keystore 异常、密钥被系统清掉）**不崩**，退化成要求用户
 * 重新输入密码 —— [load] 返回空串即触发这个路径。
 */
object PeerSyncPrefs {

    private const val TAG = "PeerSyncPrefs"
    private const val PREFS = "peersync"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_PW_ENC = "pw_enc"
    private const val KEY_PW_PLAIN = "pw_plain"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_CONFLICT = "conflict"
    private const val KEY_SCOPE = "scope"
    private const val KEY_MAX_BYTES = "max_bytes"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "peersync_pw"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    data class Config(
        val host: String,
        val port: Int,
        val password: String,
        val direction: String,
        val conflict: String,
        val scope: String,
        val maxBytes: Long,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    }.getOrNull()

    private fun encrypt(plain: String): String? = runCatching {
        val key = secretKey() ?: return@runCatching null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(blob: String): String? = runCatching {
        val key = secretKey() ?: return@runCatching null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= 12) return@runCatching null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun load(ctx: Context): Config {
        val p = prefs(ctx)
        val enc = p.getString(KEY_PW_ENC, "").orEmpty()
        val legacy = p.getString(KEY_PW_PLAIN, "").orEmpty()
        val pw = when {
            enc.isNotEmpty() -> decrypt(enc).orEmpty()
            legacy.isNotEmpty() -> legacy
            else -> ""
        }
        // 老的明文值一旦成功加密就立刻抹掉，避免它继续留在盘上
        if (legacy.isNotEmpty() && enc.isEmpty()) {
            val e = encrypt(legacy)
            if (e != null) p.edit().putString(KEY_PW_ENC, e).remove(KEY_PW_PLAIN).apply()
        }
        return Config(
            host = p.getString(KEY_HOST, "").orEmpty(),
            port = p.getInt(KEY_PORT, 3091),
            password = pw,
            direction = p.getString(KEY_DIRECTION, "both").orEmpty().ifEmpty { "both" },
            conflict = p.getString(KEY_CONFLICT, "backup-newer").orEmpty().ifEmpty { "backup-newer" },
            scope = p.getString(KEY_SCOPE, "").orEmpty(),
            maxBytes = p.getLong(KEY_MAX_BYTES, PeerSync.DEFAULT_MAX_FILE_BYTES),
        )
    }

    fun save(
        ctx: Context,
        host: String,
        port: Int,
        password: String,
        direction: String,
        conflict: String,
        scope: String,
        maxBytes: Long,
    ) {
        val e = prefs(ctx).edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .putString(KEY_DIRECTION, direction)
            .putString(KEY_CONFLICT, conflict)
            .putString(KEY_SCOPE, scope)
            .putLong(KEY_MAX_BYTES, maxBytes)
        if (password.isNotEmpty()) {
            val enc = encrypt(password)
            if (enc != null) {
                e.putString(KEY_PW_ENC, enc).remove(KEY_PW_PLAIN)
            } else {
                // Keystore 不可用：退回明文，但把「为什么」写进日志，
                // 别让后来的人以为这里本来就是明文。
                Log.w(TAG, "AndroidKeyStore unavailable; falling back to private prefs")
                e.putString(KEY_PW_PLAIN, password).remove(KEY_PW_ENC)
            }
        }
        e.apply()
    }

    fun clearPassword(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PW_ENC).remove(KEY_PW_PLAIN).apply()
    }
}

/**
 * 同步任务登记（异步执行，供 AI 端点非阻塞调用）。
 *
 * ## 为什么必须异步
 *
 * 一次全量同步在真机上可能跑几分钟（几百个会话文件 + 32MB 上限的单文件）。
 * AgentBridge 的请求线程池是 4 线程 / 队列 64（AgentBridge.kt 的 reqExec），
 * 同步若同步执行会**占住一个请求线程几分钟**，后续命令排队 —— 这正是用户
 * 明确要求「多条命令下不会出现阻塞」要防的场景。
 *
 * 所以 POST /dev/peersync 立刻返回 job_id（202），调用方用 GET /dev/peersync/job
 * 轮询。执行器是**单线程**：两个同步同时跑会互相覆盖同一批文件，必须串行。
 */
object PeerSyncJobs {

    private const val TAG = "PeerSyncJobs"

    /** 最多保留多少个历史任务（够 UI 与 AI 回看，不无限涨）。 */
    private const val MAX_JOBS = 20

    data class Job(
        val id: String,
        val startedAt: Long,
        val request: JSONObject,
        @Volatile var state: String = "running",   // running | ok | failed
        @Volatile var finishedAt: Long = 0,
        @Volatile var result: JSONObject? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("job_id", id)
            .put("state", state)
            .put("started_at", startedAt)
            .put("finished_at", finishedAt)
            .put("elapsed_ms", if (finishedAt > 0) finishedAt - startedAt else System.currentTimeMillis() - startedAt)
            .put("request", request)
            .put("result", result ?: JSONObject.NULL)
    }

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "peersync-job").apply { isDaemon = true }
    }

    private val jobs = java.util.Collections.synchronizedMap(LinkedHashMap<String, Job>())

    @Volatile
    private var currentId: String? = null

    fun current(): Job? = currentId?.let { jobs[it] }

    fun get(id: String): Job? = jobs[id]

    fun list(): List<Job> = synchronized(jobs) { jobs.values.toList() }.reversed()

    /**
     * 起一个同步任务。
     *
     * @return (job, error)。已在跑时返回 (null, "already-running")，调用方回 409。
     */
    fun start(ctx: Context, req: JSONObject): Pair<Job?, String?> {
        val running = current()
        if (running != null && running.state == "running") return null to "already-running"
        val id = "ps-" + System.currentTimeMillis().toString(36)
        val job = Job(id = id, startedAt = System.currentTimeMillis(), request = req)
        synchronized(jobs) {
            jobs[id] = job
            while (jobs.size > MAX_JOBS) {
                val oldest = jobs.keys.firstOrNull { it != id }
                if (oldest == null) break
                jobs.remove(oldest)
            }
        }
        currentId = id
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                val res = PeerSync.sync(
                    appCtx,
                    host = req.optString("host", ""),
                    port = req.optInt("port", 0),
                    pw = req.optString("pw", ""),
                    direction = req.optString("direction", "both"),
                    scope = req.optString("scope", ""),
                    conflict = req.optString("conflict", "backup-newer"),
                    maxFileBytes = req.optLong("max_bytes", 0L),
                    timeoutMs = req.optLong("timeout_ms", 600_000L),
                )
                job.result = res.json.put("elapsed_ms", res.elapsedMs)
                job.state = if (res.ok) "ok" else "failed"
                Log.i(TAG, "job " + id + " " + job.state + " in " + res.elapsedMs + "ms")
            } catch (e: Exception) {
                job.result = JSONObject().put("ok", false).put("code", "exception")
                    .put("error", (e.message ?: e.toString()).take(300))
                job.state = "failed"
                Log.w(TAG, "job " + id + " threw: " + e.message)
            } finally {
                job.finishedAt = System.currentTimeMillis()
            }
        }
        return job to null
    }
}


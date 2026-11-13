#!/usr/bin/env node
/**
 * phone-sync.mjs -- 手机端 DSH 会话同步（PeerSync 协议 v1 的客户端）
 *
 * 由 Kotlin 的 engine/PeerSync.kt 拉起（引擎自带 node），跑完即退，
 * stdout 只吐**一行 JSON** 报告，stderr 留给诊断信息。
 *
 * ## 为什么同步逻辑放在 node 而不是 Kotlin
 * 1. **绕过 Android 明文策略**：本应用 network_security_config 的 base-config 是
 *    cleartextTrafficPermitted=false（只放行 127.0.0.1）。走 HttpURLConnection 连
 *    局域网 IP 会被 NetworkSecurityPolicy 直接拒绝，唯一解法是把 base-config 放宽成
 *    true —— 那是给**整个应用**（含 WebView / 插件市场）开门。node 子进程不受该策略
 *    约束，于是门户保持关闭。
 * 2. **同一套协议语义只有一份**：文件白名单正则、路径守卫、sha 校验与电脑端
 *    tools/peersync/pc-sync-server.mjs 完全一致，改一处两边同步。
 * 3. 流式 sha256 在 node 里是几行代码，在 Kotlin 里要自己写缓冲区循环。
 *
 * ## 铁律
 * · **任何模式都不删除会话文件**。覆盖前把旧文件改名成 <name>.peersync-bak-<stamp>，
 *   同目录只保留最近 3 份（清理的也只是自己产生的备份）。
 * · 下载先写 .tmp-* 再校验 sha256，最后 rename —— 半文件绝不落地。
 * · 远端给的相对路径必须过白名单正则 + 无 .. 段 + resolve 后仍在本地下，否则拒绝。
 *
 * 用法：
 *   node phone-sync.mjs --local <sessions 目录> --state <state.json> \
 *        --host 192.168.1.9 --port 3091 --pw 123456 \
 *        [--direction both|pull|push] [--scope <前缀>] \
 *        [--conflict backup-newer|prefer-local|prefer-remote|skip] \
 *        [--max-file-bytes 33554432]
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';

// ---------- 参数 ----------
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : dflt;
};
const LOCAL = path.resolve(argOf('local', ''));
const STATE_PATH = path.resolve(argOf('state', path.join(LOCAL, '..', 'peersync-state.json')));
const HOST = argOf('host', '');
const PORT = Number(argOf('port', '3091'));
const PW = argOf('pw', '');
const DIRECTION = argOf('direction', 'both');
const SCOPE = argOf('scope', '');
const CONFLICT = argOf('conflict', 'backup-newer');
const MAX_FILE_BYTES = Number(argOf('max-file-bytes', String(32 * 1024 * 1024)));
const TIMEOUT_MS = Number(argOf('timeout-ms', '600000'));

const SESSION_FILE_RE = /^session(?:\.v[0-9]+)?\.jsonl(?:\.zstd)?$/;
const BACKUP_KEEP = 3;

function fail(code, message, extra) {
  process.stdout.write(JSON.stringify(Object.assign({
    ok: false, code: code, error: message,
  }, extra || {})) + '\n');
  process.exit(0);   // 用 0 退出：错误通过 JSON 表达，Kotlin 侧不必区分 exit code 与 stderr
}

if (!LOCAL) fail('bad-args', 'missing --local');
if (!HOST) fail('bad-args', 'missing --host');
if (!PW) fail('bad-args', 'missing --pw');
if (!['both', 'pull', 'push'].includes(DIRECTION)) fail('bad-args', 'bad --direction');
if (!['backup-newer', 'prefer-local', 'prefer-remote', 'skip'].includes(CONFLICT)) fail('bad-args', 'bad --conflict');
if (!(PORT > 0 && PORT < 65536)) fail('bad-args', 'bad --port');

const t0 = Date.now();
const notes = [];

// ---------- 路径守卫 ----------
function relOk(rel) {
  if (typeof rel !== 'string' || rel === '') return false;
  if (rel.includes('\\') || rel.includes('\0')) return false;
  const parts = rel.split('/');
  for (const p of parts) if (p === '' || p === '.' || p === '..') return false;
  if (!SESSION_FILE_RE.test(parts[parts.length - 1])) return false;
  return true;
}

function localPathFor(rel) {
  if (!relOk(rel)) return null;
  const full = path.resolve(LOCAL, rel);
  if (full !== LOCAL && !full.startsWith(LOCAL + path.sep)) return null;
  return full;
}

// ---------- sha256 ----------
function sha256File(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('data', (c) => h.update(c))
      .on('error', reject)
      .on('end', () => resolve(h.digest('hex')));
  });
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let i = 0;
  const n = Math.max(1, Math.min(limit, items.length));
  await Promise.all(Array.from({ length: n }, async () => {
    for (;;) {
      const idx = i++;
      if (idx >= items.length) return;
      out[idx] = await fn(items[idx], idx);
    }
  }));
  return out;
}

// ---------- 本地 manifest ----------
async function walk(dir, out) {
  let entries;
  try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    if (e.name.startsWith('.')) continue;
    const full = path.join(dir, e.name);
    if (e.isDirectory()) { await walk(full, out); continue; }
    if (!e.isFile()) continue;
    if (!SESSION_FILE_RE.test(e.name)) continue;
    out.push(full);
  }
}

async function localManifest() {
  const files = [];
  await walk(LOCAL, files);
  const rows = await mapLimit(files, 3, async (f) => {
    let st;
    try { st = await fsp.stat(f); } catch { return null; }
    return {
      path: path.relative(LOCAL, f).split(path.sep).join('/'),
      size: st.size,
      mtimeMs: Math.round(st.mtimeMs),
      sha256: await sha256File(f),
    };
  });
  return rows.filter(Boolean);
}

// ---------- HTTP ----------
function request(method, pathname, opts) {
  const o = opts || {};
  return new Promise((resolve, reject) => {
    const headers = Object.assign({ 'x-dshm-sync': PW }, o.headers || {});
    if (o.body !== undefined) headers['Content-Length'] = o.body.length;
    const req = http.request({
      host: HOST, port: PORT, method: method, path: pathname, headers: headers, timeout: 60000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('timeout', () => { req.destroy(new Error('request timeout')); });
    req.on('error', reject);
    if (o.body !== undefined) req.write(o.body);
    req.end();
  });
}

function q(s) { return encodeURIComponent(s); }

async function getJson(method, pathname, body) {
  const res = await request(method, pathname, body === undefined ? {} : { body: body });
  let parsed = null;
  try { parsed = JSON.parse(res.body.toString('utf8')); } catch { parsed = null; }
  return { status: res.status, json: parsed };
}

async function download(rel, destTmp) {
  // 流式落盘，避免大文件进内存
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: HOST, port: PORT, method: 'GET',
      path: '/__dshm/sync/file?path=' + q(rel),
      headers: { 'x-dshm-sync': PW }, timeout: 60000,
    }, (res) => {
      if (res.statusCode !== 200) {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => reject(new Error('download ' + res.statusCode + ': ' + Buffer.concat(chunks).toString('utf8').slice(0, 200))));
        return;
      }
      const out = fs.createWriteStream(destTmp);
      res.pipe(out);
      out.on('finish', () => resolve({ size: Number(res.headers['content-length'] || 0) }));
      out.on('error', reject);
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('download timeout')));
    req.on('error', reject);
    req.end();
  });
}

async function upload(rel, file, sha256) {
  const body = await fsp.readFile(file);   // 单文件上限 64 MiB（服务端 413），手机端通常远小于它
  const res = await request('PUT', '/__dshm/sync/file?path=' + q(rel), {
    headers: { 'x-dshm-sha256': sha256, 'Content-Type': 'application/octet-stream' },
    body: body,
  });
  let parsed = null;
  try { parsed = JSON.parse(res.body.toString('utf8')); } catch { parsed = null; }
  if (res.status !== 200) {
    throw new Error('upload ' + res.status + ': ' + (parsed && parsed.error || res.body.toString('utf8').slice(0, 200)));
  }
  return parsed || {};
}

// ---------- 状态（上次同步的 sha 基线）----------
function loadState() {
  try {
    const j = JSON.parse(fs.readFileSync(STATE_PATH, 'utf8'));
    if (j && typeof j === 'object' && j.baseline && typeof j.baseline === 'object') return j;
  } catch { /* 首次或损坏：按空基线处理 */ }
  return { version: 1, baseline: {}, lastSyncAt: null };
}

function saveState(state) {
  const tmp = STATE_PATH + '.tmp-' + process.pid + '-' + Date.now();
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
  fs.renameSync(tmp, STATE_PATH);
}

// ---------- 备份 ----------
function stampNow() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return String(d.getFullYear()) + p(d.getMonth() + 1) + p(d.getDate()) + '-' +
    p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}

async function backupAndPrune(full) {
  const bak = full + '.peersync-bak-' + stampNow();
  await fsp.rename(full, bak);
  const dir = path.dirname(full);
  const base = path.basename(full) + '.peersync-bak-';
  let names = [];
  try { names = await fsp.readdir(dir); } catch { return path.basename(bak); }
  const mine = names.filter((n) => n.startsWith(base)).sort();
  while (mine.length > BACKUP_KEEP) {
    const victim = mine.shift();
    try { await fsp.unlink(path.join(dir, victim)); } catch {}
  }
  return path.basename(bak);
}

// ---------- 主流程 ----------
async function main() {
  await fsp.mkdir(LOCAL, { recursive: true });

  let ping;
  try {
    ping = await getJson('GET', '/__dshm/sync/ping');
  } catch (e) {
    // 连接被拒/DNS/超时都归到同一类：用户要的是「手机连不上电脑」这一个结论，
    // 而不是 ECONNREFUSED 这种给开发者看的字符串。
    return fail('unreachable', '连不上电脑端同步服务（' + HOST + ':' + PORT + '）：' +
      String((e && e.message) || e).slice(0, 120), { stage: 'ping' });
  }
  if (ping.status === 401) return fail('unauthorized', '密码不正确（电脑端与本机不一致）', { stage: 'ping' });
  if (ping.status === 429) return fail('rate-limited', '尝试过于频繁，请稍后再试', { stage: 'ping', retryAfter: ping.json && ping.json.retryAfterMs });
  if (ping.status !== 200 || !ping.json || !ping.json.ok) {
    return fail('unreachable', '连不上电脑端同步服务（' + HOST + ':' + PORT + '）', { stage: 'ping', status: ping.status });
  }

  const remoteQ = SCOPE ? '?scope=' + q(SCOPE) : '';
  const mres = await getJson('GET', '/__dshm/sync/manifest' + remoteQ);
  if (mres.status !== 200 || !mres.json || !Array.isArray(mres.json.files)) {
    return fail('manifest-failed', '读取电脑端清单失败', { stage: 'manifest', status: mres.status });
  }
  const remote = new Map();
  let rejectedRemote = 0;
  for (const f of mres.json.files) {
    if (!relOk(f.path)) { rejectedRemote++; continue; }
    if (SCOPE && !(f.path === SCOPE || f.path.startsWith(SCOPE + '/'))) { rejectedRemote++; continue; }
    remote.set(f.path, f);
  }
  if (rejectedRemote) notes.push('远端有 ' + rejectedRemote + ' 项被路径守卫拒绝');

  const localList = await localManifest();
  const local = new Map();
  for (const f of localList) {
    if (SCOPE && !(f.path === SCOPE || f.path.startsWith(SCOPE + '/'))) continue;
    local.set(f.path, f);
  }

  const state = loadState();
  const baseline = state.baseline || {};

  const actions = [];        // 执行记录（最多保留 200 条进报告）
  const counts = { pushed: 0, pulled: 0, skipped_same: 0, skipped_large: 0, conflicts: 0, failed: 0, skipped_mode: 0, changed: 0 };
  let bytesUp = 0, bytesDown = 0;
  const failures = [];
  const cap = (arr, item) => { if (arr.length < 200) arr.push(item); };

  const allRel = Array.from(new Set([...local.keys(), ...remote.keys()])).sort();

  for (const rel of allRel) {
    const l = local.get(rel);
    const r = remote.get(rel);
    const base = baseline[rel];

    let action = null;
    // —— 只有一边有 ——
    if (l && !r) {
      if (DIRECTION === 'pull') { counts.skipped_mode++; cap(actions, { path: rel, action: 'skip', reason: 'pull-mode: local only' }); continue; }
      if (l.size > MAX_FILE_BYTES) { counts.skipped_large++; cap(actions, { path: rel, action: 'skip', reason: 'too large: ' + l.size }); continue; }
      action = 'push';
    } else if (!l && r) {
      if (DIRECTION === 'push') { counts.skipped_mode++; cap(actions, { path: rel, action: 'skip', reason: 'push-mode: remote only' }); continue; }
      if (r.size > MAX_FILE_BYTES) { counts.skipped_large++; cap(actions, { path: rel, action: 'skip', reason: 'too large: ' + r.size }); continue; }
      action = 'pull';
    } else if (l && r) {
      if (l.sha256 === r.sha256) {
        counts.skipped_same++;
        baseline[rel] = l.sha256;
        continue;
      }
      const localChanged = base !== undefined && base !== l.sha256;
      const remoteChanged = base !== undefined && base !== r.sha256;
      if (base !== undefined) {
        if (localChanged && !remoteChanged) action = 'push';
        else if (!localChanged && remoteChanged) action = 'pull';
        else action = 'conflict';
      } else {
        // 首次同步（无基线）：mtime 相差 > 2s 才敢让「新的赢」，否则按冲突处理
        const diff = Math.abs(l.mtimeMs - r.mtimeMs);
        if (diff > 2000) action = l.mtimeMs > r.mtimeMs ? 'push' : 'pull';
        else action = 'conflict';
      }
      if (action === 'push' && DIRECTION === 'pull') { counts.skipped_mode++; cap(actions, { path: rel, action: 'skip', reason: 'pull-mode' }); continue; }
      if (action === 'pull' && DIRECTION === 'push') { counts.skipped_mode++; cap(actions, { path: rel, action: 'skip', reason: 'push-mode' }); continue; }
    }

    if (action === 'conflict') {
      counts.conflicts++;
      const newerLocal = (l ? l.mtimeMs : 0) >= (r ? r.mtimeMs : 0);
      if (CONFLICT === 'skip') {
        cap(actions, { path: rel, action: 'conflict', resolution: 'skip', localMtime: l ? l.mtimeMs : null, remoteMtime: r ? r.mtimeMs : null });
        continue;
      }
      if (CONFLICT === 'prefer-local' || (CONFLICT === 'backup-newer' && newerLocal)) action = 'push';
      else action = 'pull';
      cap(actions, { path: rel, action: 'conflict', resolution: action, localMtime: l ? l.mtimeMs : null, remoteMtime: r ? r.mtimeMs : null });
    }

    try {
      if (action === 'push') {
        if (l.size > MAX_FILE_BYTES) { counts.skipped_large++; cap(actions, { path: rel, action: 'skip', reason: 'too large' }); continue; }
        const full = localPathFor(rel);
        if (!full) { counts.failed++; failures.push({ path: rel, error: 'bad local path' }); continue; }
        const res = await upload(rel, full, l.sha256);
        counts.pushed++;
        bytesUp += l.size;
        baseline[rel] = l.sha256;
        cap(actions, { path: rel, action: 'push', size: l.size, replaced: !!res.replaced, backup: res.backup || null });
      } else if (action === 'pull') {
        if (r.size > MAX_FILE_BYTES) { counts.skipped_large++; cap(actions, { path: rel, action: 'skip', reason: 'too large' }); continue; }
        const full = localPathFor(rel);
        if (!full) { counts.failed++; failures.push({ path: rel, error: 'bad remote path' }); continue; }
        await fsp.mkdir(path.dirname(full), { recursive: true });
        const tmp = full + '.tmp-' + process.pid + '-' + Date.now();
        await download(rel, tmp);
        const got = await sha256File(tmp);
        if (got !== r.sha256) {
          try { await fsp.unlink(tmp); } catch {}
          // 远端在同步期间还在被写（活跃会话每轮追加）：manifest 的 sha 已过期。
          // 复核一次 stat —— sha 变了就留给下一轮，不算失败。
          let now = null;
          try {
            const st = await getJson('GET', '/__dshm/sync/stat?path=' + q(rel));
            if (st.status === 200 && st.json && st.json.exists) now = st.json.sha256;
          } catch {}
          if (now && now !== r.sha256) {
            counts.changed++;
            cap(actions, { path: rel, action: 'defer', reason: 'remote changed during sync' });
            continue;
          }
          counts.failed++;
          failures.push({ path: rel, error: 'sha mismatch after download' });
          continue;
        }
        let backupName = null;
        try { if ((await fsp.stat(full)).isFile()) backupName = await backupAndPrune(full); } catch {}
        await fsp.rename(tmp, full);
        counts.pulled++;
        bytesDown += r.size;
        baseline[rel] = r.sha256;
        cap(actions, { path: rel, action: 'pull', size: r.size, backup: backupName });
      }
    } catch (e) {
      counts.failed++;
      failures.push({ path: rel, error: String((e && e.message) || e).slice(0, 300) });
    }
  }

  state.baseline = baseline;
  state.lastSyncAt = Date.now();
  state.lastResult = {
    at: state.lastSyncAt, host: HOST, port: PORT, direction: DIRECTION,
    pushed: counts.pushed, pulled: counts.pulled, conflicts: counts.conflicts, failed: counts.failed,
  };
  saveState(state);

  process.stdout.write(JSON.stringify({
    ok: counts.failed === 0,
    host: HOST, port: PORT, direction: DIRECTION, conflict: CONFLICT,
    scope: SCOPE || null,
    localRoot: LOCAL,
    remote: { version: mres.json.version, count: mres.json.count, totalBytes: mres.json.totalBytes },
    localCount: local.size,
    counts: counts,
    bytesUp: bytesUp, bytesDown: bytesDown,
    actions: actions,
    failures: failures,
    notes: notes,
    ms: Date.now() - t0,
  }) + '\n');
}

main().catch((e) => {
  process.stdout.write(JSON.stringify({
    ok: false, code: 'exception', error: String((e && e.message) || e).slice(0, 500),
    stack: String((e && e.stack) || '').split('\n').slice(0, 4).join(' | '),
  }) + '\n');
});

#!/usr/bin/env node
/**
 * pc-sync-server.mjs -- 电脑端 DSH 会话同步服务（PeerSync 协议 v1）
 *
 * 配套手机端：dsh-android 的 engine/PeerSync.kt（HTTP 客户端）。
 * 协议细节见 docs/PEERSYNC_PROTOCOL.md -- 本文件是它唯一的事实实现。
 *
 * ## 为什么重写而不是复用上游 PR #105 的 sync 段
 * 上游按 .jsonl 后缀过滤 + 「字节前缀快进」，两个前提都不成立：
 *   · 引擎默认 compression="zstd"，真实文件名是 session.v4.jsonl.zstd
 *     （实测 ~/.dsh/sessions 命中 0/465）；
 *   · zstd 多帧容器追加后**不是**旧文件的字节前缀（实测 new.startswith(old) 0/20，
 *     解压后明文前缀 0/4），「按长度跳过已同步部分」在 zstd 上不成立。
 * 因此 v1 改为「整文件 + sha256 + 三方比较」。
 *
 * ## 用法
 *   node pc-sync-server.mjs --pw 123456 [--port 3091] [--root ~/.dsh/sessions]
 *   密码也可用环境变量 DSH_SYNC_PW 提供（命令行优先）。
 *
 * ## 硬约束（不得破坏）
 *   · 只监听同步端点，不代理 WebUI（那是 3080/3090 的事）；
 *   · 任何删除动作只允许发生在**自己产生的备份**上，且只保留最近 3 份；
 *   · 覆盖前必须做原子写（tmp -> fsync -> rename），绝不半文件落地。
 */
import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';

// ---------- 参数 ----------
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
if (argv.includes('--help') || argv.includes('-h')) {
  console.log('pc-sync-server.mjs -- DSH 会话同步（PeerSync v1）\n\n' +
    '用法: node pc-sync-server.mjs --pw <密码> [--port 3091] [--root <sessions 目录>] [--bind 0.0.0.0]\n\n' +
    '  --pw     必填；同步密码（与手机端「双端同步」里填的一致）\n' +
    '  --port   默认 3091（避开 3080 引擎 / 3083 能力桥 / 3090 WebUI 代理）\n' +
    '  --root   默认 ~/.dsh/sessions\n' +
    '  --bind   默认 0.0.0.0（仅局域网使用；只想本机调试可填 127.0.0.1）\n\n' +
    '端点: GET /__dshm/sync/ping | /manifest | /stat?path= | /file?path=\n' +
    '      PUT /__dshm/sync/file?path=\n' +
    '鉴权: 请求头 x-dshm-sync: <密码>\n');
  process.exit(0);
}
const PORT = Number(argOf('port', process.env.DSH_SYNC_PORT || '3091'));
const PW = argOf('pw', process.env.DSH_SYNC_PW || '');
const BIND = argOf('bind', '0.0.0.0');
const rawRoot = argOf('root', path.join(os.homedir(), '.dsh', 'sessions'));
const ROOT = path.resolve(rawRoot.replace(/^~(?=[\\/])/, os.homedir()));

const MAX_BYTES = 64 * 1024 * 1024;
const BACKUP_KEEP = 3;

const LOG = (...a) => console.log('[peersync ' + new Date().toISOString() + ']', ...a);

if (!PW) {
  LOG('refusing to start: no password (pass --pw or set DSH_SYNC_PW)');
  process.exit(2);
}
if (PW.length < 4) {
  LOG('refusing to start: password too short (< 4)');
  process.exit(2);
}

// ---------- 路径与文件名白名单 ----------
/** 唯一允许参与同步的文件形态：session.v4.jsonl.zstd / session.jsonl 等 */
const SESSION_FILE_RE = /^session(?:\.v[0-9]+)?\.jsonl(?:\.zstd)?$/;

/** 相对路径 -> 绝对路径；任何越界/非法形态返回 null。 */
function syncRel(raw) {
  if (typeof raw !== 'string' || raw === '') return null;
  let rel;
  try { rel = decodeURIComponent(raw); } catch { return null; }
  if (rel.includes('\\') || rel.includes('\0')) return null;
  rel = rel.replace(/^\/+/, '');
  const parts = rel.split('/');
  if (!parts.length) return null;
  for (const p of parts) if (p === '' || p === '.' || p === '..') return null;
  if (!SESSION_FILE_RE.test(parts[parts.length - 1])) return null;
  const full = path.resolve(ROOT, rel);
  if (full !== ROOT && !full.startsWith(ROOT + path.sep)) return null;
  return full;
}

function acceptsName(name) {
  return SESSION_FILE_RE.test(name);
}

// ---------- sha256（流式 + stat 缓存）----------
const shaCache = new Map();

function sha256FileStreaming(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('data', (c) => h.update(c))
      .on('error', reject)
      .on('end', () => resolve(h.digest('hex')));
  });
}

async function sha256Of(file, st) {
  const hit = shaCache.get(file);
  if (hit && hit.size === st.size && hit.mtimeMs === Math.round(st.mtimeMs)) return hit.sha256;
  const sha256 = await sha256FileStreaming(file);
  shaCache.set(file, { size: st.size, mtimeMs: Math.round(st.mtimeMs), sha256 });
  return sha256;
}

// ---------- 鉴权退避 ----------
const FAIL_WINDOW_MS = 10 * 60 * 1000;
const LOCK_AFTER = 5;
const LOCK_MS = 10 * 60 * 1000;
const failState = new Map();

function clientKey(req) { return req.socket.remoteAddress || 'unknown'; }

function backoffRemainingMs(req) {
  const k = clientKey(req);
  const st = failState.get(k);
  if (!st) return 0;
  const now = Date.now();
  if (st.lockedUntil && now < st.lockedUntil) return st.lockedUntil - now;
  if (now - st.last > FAIL_WINDOW_MS) { failState.delete(k); return 0; }
  if (st.count <= 0) return 0;
  const waitTotal = Math.min(8000, 500 * Math.pow(2, st.count - 1));
  return Math.max(0, waitTotal - (now - st.last));
}
function noteFailure(req) {
  const k = clientKey(req);
  const now = Date.now();
  const st = failState.get(k) || { count: 0, last: 0, lockedUntil: 0 };
  if (now - st.last > FAIL_WINDOW_MS) st.count = 0;
  st.count += 1;
  st.last = now;
  if (st.count >= LOCK_AFTER) st.lockedUntil = now + LOCK_MS;
  failState.set(k, st);
  return st;
}
function noteSuccess(req) { failState.delete(clientKey(req)); }

// ---------- 响应 helpers ----------
function json(res, status, value, extraHeaders) {
  const body = Buffer.from(JSON.stringify(value));
  const headers = Object.assign({
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  }, extraHeaders || {});
  res.writeHead(status, headers);
  res.end(body);
}

// ---------- manifest ----------
async function walk(dir, out) {
  let entries;
  try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    if (e.name.startsWith('.')) continue;
    const full = path.join(dir, e.name);
    if (e.isDirectory()) { await walk(full, out); continue; }
    if (!e.isFile()) continue;
    if (!acceptsName(e.name)) continue;
    out.push(full);
  }
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let i = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    for (;;) {
      const idx = i++;
      if (idx >= items.length) return;
      out[idx] = await fn(items[idx], idx);
    }
  });
  await Promise.all(workers);
  return out;
}

async function buildManifest(scopePrefix) {
  const files = [];
  await walk(ROOT, files);
  const filtered = scopePrefix
    ? files.filter((f) => {
        const rel = path.relative(ROOT, f).split(path.sep).join('/');
        return rel === scopePrefix || rel.startsWith(scopePrefix + '/');
      })
    : files;
  const rows = await mapLimit(filtered, 4, async (f) => {
    let st;
    try { st = await fsp.stat(f); } catch { return null; }
    const sha256 = await sha256Of(f, st);
    return {
      path: path.relative(ROOT, f).split(path.sep).join('/'),
      size: st.size,
      mtimeMs: Math.round(st.mtimeMs),
      sha256,
    };
  });
  const list = rows.filter(Boolean).sort((a, b) => a.path.localeCompare(b.path));
  return {
    version: 1,
    root: 'sessions',
    generatedAt: Date.now(),
    count: list.length,
    totalBytes: list.reduce((s, x) => s + x.size, 0),
    files: list,
  };
}

// ---------- 备份保留 ----------
function backupNameFor(full) {
  const d = new Date();
  const p = (n, w) => String(n).padStart(w || 2, '0');
  const stamp = String(d.getFullYear()) + p(d.getMonth() + 1) + p(d.getDate()) + '-' +
    p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
  return full + '.peersync-bak-' + stamp;
}

/** 只清理**自己产生的**同目录备份，保留最近 BACKUP_KEEP 份。 */
async function pruneBackups(full) {
  const dir = path.dirname(full);
  const base = path.basename(full) + '.peersync-bak-';
  let entries;
  try { entries = await fsp.readdir(dir); } catch { return 0; }
  const mine = entries.filter((n) => n.startsWith(base)).sort();
  let removed = 0;
  while (mine.length > BACKUP_KEEP) {
    const victim = mine.shift();
    try { await fsp.unlink(path.join(dir, victim)); removed++; } catch {}
  }
  return removed;
}

// ---------- 主服务 ----------
const server = http.createServer(async (req, res) => {
  const started = Date.now();
  const rawUrl = req.url || '/';
  let url;
  try { url = new URL(rawUrl, 'http://sync.local'); } catch { json(res, 400, { error: 'bad-url' }); return; }

  if (!url.pathname.startsWith('/__dshm/sync/')) {
    json(res, 404, { error: 'unknown-endpoint', hint: 'this server only serves /__dshm/sync/*' });
    return;
  }

  // 鉴权与退避。
  //
  // 顺序很关键：**先比密码**，只有错了才计入退避。早期版本先查退避——
  // 用户输错一次后立刻改对也会被 429 挡 500ms（手机端点一次「同步」直接失败，
  // 用户看到的是「密码对了还是连不上」，这是纯 UX 陷阱）。
  // 爆破仍然被挡住：攻击者不可能猜对，每次错都在累积 → 5 次后锁 10 分钟。
  if (req.headers['x-dshm-sync'] !== PW) {
    const st = noteFailure(req);
    const wait = backoffRemainingMs(req);
    if (wait > 0 && st.count >= LOCK_AFTER) {
      json(res, 429, { error: 'rate-limited', failures: st.count, retryAfterMs: wait },
        { 'Retry-After': String(Math.ceil(wait / 1000)) });
      return;
    }
    json(res, 401, { error: 'sync-unauthorized', failures: st.count });
    return;
  }
  noteSuccess(req);

  try {
    if (url.pathname === '/__dshm/sync/ping') {
      const files = [];
      await walk(ROOT, files);
      let bytes = 0;
      for (const f of files) { try { bytes += (await fsp.stat(f)).size; } catch {} }
      json(res, 200, { ok: true, version: 1, root: 'sessions', rootPath: ROOT, files: files.length, bytes });
      return;
    }

    if (url.pathname === '/__dshm/sync/manifest') {
      if (req.method !== 'GET') { json(res, 405, { error: 'GET only' }); return; }
      const scope = url.searchParams.get('scope') || '';
      if (scope && (scope.includes('..') || scope.includes('\\'))) { json(res, 400, { error: 'bad-scope' }); return; }
      const m = await buildManifest(scope);
      LOG('manifest scope=' + (scope || '*') + ' files=' + m.count + ' bytes=' + m.totalBytes + ' ' + (Date.now() - started) + 'ms');
      json(res, 200, m);
      return;
    }

    if (url.pathname === '/__dshm/sync/stat') {
      if (req.method !== 'GET') { json(res, 405, { error: 'GET only' }); return; }
      const full = syncRel(url.searchParams.get('path'));
      if (!full) { json(res, 400, { error: 'bad-path' }); return; }
      try {
        const st = await fsp.stat(full);
        if (!st.isFile()) { json(res, 200, { ok: true, exists: false }); return; }
        const sha256 = await sha256Of(full, st);
        json(res, 200, {
          ok: true, exists: true,
          path: path.relative(ROOT, full).split(path.sep).join('/'),
          size: st.size, mtimeMs: Math.round(st.mtimeMs), sha256,
        });
      } catch {
        json(res, 200, { ok: true, exists: false });
      }
      return;
    }

    if (url.pathname === '/__dshm/sync/file') {
      const full = syncRel(url.searchParams.get('path'));
      if (!full) { json(res, 400, { error: 'bad-path' }); return; }

      if (req.method === 'GET') {
        let st;
        try { st = await fsp.stat(full); } catch { json(res, 404, { error: 'not-found' }); return; }
        if (!st.isFile()) { json(res, 404, { error: 'not-found' }); return; }
        res.writeHead(200, {
          'Content-Type': 'application/octet-stream',
          'Content-Length': st.size,
          'Cache-Control': 'no-store',
        });
        const rs = fs.createReadStream(full);
        rs.on('error', () => { try { res.destroy(); } catch {} });
        rs.pipe(res);
        return;
      }

      if (req.method === 'PUT') {
        const expectSha = (req.headers['x-dshm-sha256'] || '').toString().toLowerCase();
        await fsp.mkdir(path.dirname(full), { recursive: true });
        const tmp = full + '.tmp-' + process.pid + '-' + Date.now();
        const h = crypto.createHash('sha256');
        let size = 0;
        let tooLarge = false;
        const out = fs.createWriteStream(tmp);
        try {
          await new Promise((resolve, reject) => {
            req.on('data', (chunk) => {
              if (tooLarge) return;
              size += chunk.length;
              if (size > MAX_BYTES) { tooLarge = true; req.destroy(); reject(new Error('too-large')); return; }
              h.update(chunk);
            });
            req.on('error', reject);
            out.on('error', reject);
            req.pipe(out);
            out.on('finish', resolve);
          });
        } catch (e) {
          try { await fsp.unlink(tmp); } catch {}
          if (tooLarge) { json(res, 413, { error: 'too-large', limit: MAX_BYTES }); return; }
          json(res, 500, { error: 'write-failed', detail: String((e && e.message) || e) });
          return;
        }
        const sha256 = h.digest('hex');
        if (expectSha && expectSha !== sha256) {
          try { await fsp.unlink(tmp); } catch {}
          json(res, 409, { error: 'sha-mismatch', expected: expectSha, actual: sha256, size });
          return;
        }
        // 覆盖前备份
        let backup = null;
        try {
          const st = await fsp.stat(full);
          if (st.isFile()) {
            const oldSha = await sha256Of(full, st);
            if (oldSha !== sha256) {
              backup = backupNameFor(full);
              await fsp.rename(full, backup);
            }
          }
        } catch { /* 目标不存在 */ }
        await new Promise((resolve, reject) => {
          const fd = fs.openSync(tmp, 'r+');
          try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
          fs.rename(tmp, full, (e) => (e ? reject(e) : resolve()));
        });
        const pruned = await pruneBackups(full);
        LOG('PUT ' + path.relative(ROOT, full) + ' size=' + size + ' sha=' + sha256.slice(0, 12) +
          ' backup=' + (backup ? path.basename(backup) : '-'));
        json(res, 200, {
          ok: true,
          path: path.relative(ROOT, full).split(path.sep).join('/'),
          size: size, sha256: sha256, replaced: !!backup,
          backup: backup ? path.basename(backup) : null,
          pruned: pruned,
          ms: Date.now() - started,
        });
        return;
      }

      json(res, 405, { error: 'GET or PUT only' });
      return;
    }

    json(res, 404, { error: 'unknown-sync-endpoint' });
  } catch (e) {
    LOG('error ' + ((e && e.stack) || e));
    json(res, 500, { error: 'internal', detail: String((e && e.message) || e) });
  }
});

server.on('clientError', (err, socket) => { try { socket.destroy(); } catch {} });

server.listen(PORT, BIND, () => {
  LOG('listening on ' + BIND + ':' + PORT + ' root=' + ROOT);
  LOG('password length: ' + PW.length);
  LOG('endpoints: /__dshm/sync/{ping,manifest,stat,file}');
});

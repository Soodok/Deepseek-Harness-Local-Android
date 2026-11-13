#!/usr/bin/env node
/**
 * e2e.mjs -- PeerSync v1 端到端自检（真跑 PeerSync.kt 内联客户端 + pc-sync-server.mjs）
 *
 * 模拟：远端（电脑）= 临时目录 A，本地（手机）= 临时目录 B。
 * 覆盖：首同步 / 双向 / 单边新增 / 单边修改 / 双方冲突 / pull / push / scope /
 *      大文件跳过 / 密码错 / 路径守卫 / 不删除会话 / 备份上限。
 */
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import net from 'node:net';

const node = process.execPath;

// ⚠️ 被测客户端**必须**是从 PeerSync.kt 里抽出来的那段内联脚本，不是
// tools/peersync/phone-sync.mjs（后者只是同构原型）。两份脚本会漂移：
// 这轮修 bug 时内联版先后改了 argv 起点、--input-type、changed 计数，
// 原型版只跟上了一部分 —— 于是「自检全绿」证明的是原型，而不是真正装进
// APK 的那份。把内联脚本抽到临时文件再跑，被测对象 == 出货对象。
const KT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')),
  '..', '..', 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile', 'engine', 'PeerSync.kt');
const ktSrc = fs.readFileSync(KT, 'utf8');
const scriptMatch = ktSrc.match(/private val SYNC_SCRIPT: String = """([\s\S]*?)"""/);
if (!scriptMatch) { console.error('cannot extract SYNC_SCRIPT from ' + KT); process.exit(1); }
const CLIENT = path.join(os.tmpdir(), 'peersync-inline-client-' + process.pid + '.mjs');
fs.writeFileSync(CLIENT, scriptMatch[1]);
// 出货时 Kotlin 以 `node --input-type=module -e <script> -- <args>` 调起；
// 写成 .mjs 文件等价（同样按 ESM 解析），参数语义一致。
const CLIENT_ARGS = [CLIENT];
// 端口必须动态取空闲口：写死 3201 会被本机正在跑的其它服务占用（实测撞上
// dsh --port 3201 的 GUI 进程），此时服务端起不来，客户端 ping 拿到对方的 404，
// 一整批断言连锁假红 —— 而失败信息看起来像协议坏了。
function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.unref();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}
const PORT = await freePort();
const PW = 'e2e12345';
const base = await fsp.mkdtemp(path.join(os.tmpdir(), 'peersync-e2e-'));
const REMOTE = path.join(base, 'remote');
const LOCAL = path.join(base, 'local');
const STATE = path.join(base, 'state.json');
await fsp.mkdir(REMOTE, { recursive: true });
await fsp.mkdir(LOCAL, { recursive: true });

const F = 'session.v4.jsonl.zstd';
function rel(slug, sid) { return slug + '/' + sid + '/' + F; }
const A = rel('--slug-a--', 'session-aaaa');
const B = rel('--slug-b--', 'session-bbbb');
const C = rel('--slug-a--', 'session-cccc');

function put(dir, r, content) {
  const full = path.join(dir, r);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, content);
}
function get(dir, r) {
  try { return fs.readFileSync(path.join(dir, r), 'utf8'); } catch { return null; }
}

let pass = 0, fail = 0;
function check(name, cond, detail) {
  if (cond) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log('  FAIL ' + name + (detail ? ' -- ' + detail : '')); }
}

function sync(extra) {
  const args = [...CLIENT_ARGS, '--local', LOCAL, '--state', STATE,
    '--host', '127.0.0.1', '--port', String(PORT), '--pw', PW].concat(extra || []);
  const r = spawnSync(node, args, { encoding: 'utf8', timeout: 120000 });
  const line = (r.stdout || '').trim().split('\n').filter(Boolean).pop() || '{}';
  try { return JSON.parse(line); } catch { return { ok: false, code: 'unparsable', raw: r.stdout, stderr: r.stderr }; }
}

// 起服务端
const server = spawn(node, ['tools/peersync/pc-sync-server.mjs', '--pw', PW, '--port', String(PORT),
  '--root', REMOTE, '--bind', '127.0.0.1'], { stdio: ['ignore', 'pipe', 'pipe'] });
let srvLog = '';
server.stdout.on('data', (d) => { srvLog += d.toString(); });
server.stderr.on('data', (d) => { srvLog += d.toString(); });
await new Promise((r) => setTimeout(r, 900));

try {
  // ---------- 1. 首同步：远端有 A，本地有 B ----------
  put(REMOTE, A, 'remote-A-v1');
  put(LOCAL, B, 'local-B-v1');
  let j = sync();
  check('首同步 ok', j.ok === true, JSON.stringify(j).slice(0, 300));
  check('远端独有 -> 拉下 A', get(LOCAL, A) === 'remote-A-v1');
  check('本地独有 -> 推上 B', get(REMOTE, B) === 'local-B-v1');
  check('counts.pulled=1', j.counts && j.counts.pulled === 1, JSON.stringify(j.counts));
  check('counts.pushed=1', j.counts && j.counts.pushed === 1, JSON.stringify(j.counts));

  // ---------- 2. 再同步一次：全同，0 传输 ----------
  j = sync();
  check('幂等：第二次 pushed=0 pulled=0', j.counts.pushed === 0 && j.counts.pulled === 0, JSON.stringify(j.counts));
  check('幂等：skipped_same=2', j.counts.skipped_same === 2, JSON.stringify(j.counts));

  // ---------- 3. 本地改 B -> 推上去（远端先备份） ----------
  put(LOCAL, B, 'local-B-v2-longer');
  j = sync();
  check('本地改 -> push', j.counts.pushed === 1 && get(REMOTE, B) === 'local-B-v2-longer', JSON.stringify(j.counts));
  const bakFile = fs.readdirSync(path.join(REMOTE, path.dirname(B))).filter((n) => n.includes('.peersync-bak-'));
  check('远端覆盖前留了备份', bakFile.length === 1, bakFile.join(','));

  // ---------- 4. 远端改 A -> 拉下来（本地先备份） ----------
  put(REMOTE, A, 'remote-A-v2');
  j = sync();
  check('远端改 -> pull', j.counts.pulled === 1 && get(LOCAL, A) === 'remote-A-v2', JSON.stringify(j.counts));
  const localBak = fs.readdirSync(path.join(LOCAL, path.dirname(A))).filter((n) => n.includes('.peersync-bak-'));
  check('本地覆盖前留了备份', localBak.length === 1, localBak.join(','));

  // ---------- 5. 双方都改 -> 冲突，按 backup-newer 解决，两边都不丢 ----------
  put(LOCAL, A, 'local-A-v3');
  // 让远端更新一点
  await new Promise((r) => setTimeout(r, 1200));
  put(REMOTE, A, 'remote-A-v3-newest');
  j = sync();
  check('冲突被记录', j.counts.conflicts === 1, JSON.stringify(j.counts));
  check('冲突解决后本地内容 = 远端较新那份', get(LOCAL, A) === 'remote-A-v3-newest', get(LOCAL, A));

  // ---------- 6. pull 模式：远端独有 C 会拉下来，本地独有 B 不推 ----------
  put(REMOTE, C, 'remote-C-v1');
  put(LOCAL, B, 'local-B-v3-only-local');
  j = sync(['--direction', 'pull']);
  check('pull 模式拉下 C', get(LOCAL, C) === 'remote-C-v1');
  check('pull 模式不推 B', get(REMOTE, B) === 'local-B-v2-longer', get(REMOTE, B));
  check('pull 模式 skipped_mode>=1', j.counts.skipped_mode >= 1, JSON.stringify(j.counts));

  // ---------- 7. push 模式：本地独有推上去，远端独有不拉 ----------
  const D = rel('--slug-b--', 'session-dddd');
  put(LOCAL, D, 'local-D-v1');
  const E = rel('--slug-c--', 'session-eeee');
  put(REMOTE, E, 'remote-E-v1');
  j = sync(['--direction', 'push']);
  check('push 模式推上 D', get(REMOTE, D) === 'local-D-v1');
  check('push 模式不拉 E', get(LOCAL, E) === null, String(get(LOCAL, E)));

  // ---------- 8. scope 过滤 ----------
  const F2 = rel('--slug-scope--', 'session-ffff');
  put(LOCAL, F2, 'local-F-v1');
  j = sync(['--scope', '--slug-scope--']);
  check('scope 只同步指定前缀', get(REMOTE, F2) === 'local-F-v1' && j.localCount === 1, JSON.stringify({ c: j.localCount }));

  // ---------- 9. 大文件跳过 ----------
  const G = rel('--slug-big--', 'session-gggg');
  put(LOCAL, G, 'x'.repeat(4096));
  j = sync(['--max-file-bytes', '1024']);
  check('大文件被跳过', j.counts.skipped_large >= 1 && get(REMOTE, G) === null, JSON.stringify(j.counts));

  // ---------- 10. 密码错 -> unauthorized ----------
  const badPw = spawnSync(node, [...CLIENT_ARGS, '--local', LOCAL, '--state', STATE,
    '--host', '127.0.0.1', '--port', String(PORT), '--pw', 'wrong-pw'], { encoding: 'utf8' });
  let bj = {};
  try { bj = JSON.parse((badPw.stdout || '').trim().split('\n').pop()); } catch {}
  check('密码错 -> code=unauthorized', bj.code === 'unauthorized', JSON.stringify(bj).slice(0, 200));

  // ---------- 11. 不删除任何会话（本地与远端并集都还在） ----------
  const localCount = (function count(dir, n = 0) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.isDirectory()) n = count(path.join(dir, e.name), n);
      else if (/^session(?:\.v[0-9]+)?\.jsonl(?:\.zstd)?$/.test(e.name)) n++;
    }
    return n;
  })(LOCAL);
  check('本地会话数 >= 6（无删除）', localCount >= 6, 'got ' + localCount);

  // ---------- 12. 越界 scope 被服务端拒绝，客户端给出明确错误（不是静默成功） ----------
  const evil = sync(['--scope', '..']);
  check('scope=.. 被拒且报告 manifest-failed', evil.ok === false && evil.code === 'manifest-failed',
    JSON.stringify(evil).slice(0, 200));

  // ---------- 13. 服务端不可达 -> 明确 unreachable ----------
  const down = spawnSync(node, [...CLIENT_ARGS, '--local', LOCAL, '--state', STATE,
    '--host', '127.0.0.1', '--port', '3299', '--pw', PW], { encoding: 'utf8', timeout: 30000 });
  let dj = {};
  try { dj = JSON.parse((down.stdout || '').trim().split('\n').pop()); } catch {}
  check('端口不通 -> code=unreachable', dj.code === 'unreachable', JSON.stringify(dj).slice(0, 200));

  console.log('\nRESULT: pass=' + pass + ' fail=' + fail);
} finally {
  server.kill();
  await new Promise((r) => setTimeout(r, 300));
  await fsp.rm(base, { recursive: true, force: true }).catch(() => {});
}
process.exit(fail === 0 ? 0 : 1);

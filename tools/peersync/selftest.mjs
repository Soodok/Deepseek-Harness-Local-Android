#!/usr/bin/env node
/** 自检：pc-sync-server.mjs 在临时目录上跑一遍协议全流程。 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';

const node = process.execPath;
const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'peersync-test-'));
const PORT = 3199;
const PW = 'test1234';
const SLUG = '--test-slug--';
const SID = 'session-11111111-2222-3333-4444-555555555555';
const REL = SLUG + '/' + SID + '/session.v4.jsonl.zstd';
const dir = path.join(root, SLUG, SID);
await fsp.mkdir(dir, { recursive: true });

const server = spawn(node, ['tools/peersync/pc-sync-server.mjs', '--pw', PW, '--port', String(PORT), '--root', root, '--bind', '127.0.0.1'], { stdio: ['ignore', 'pipe', 'pipe'] });
let srvLog = '';
server.stdout.on('data', (d) => { srvLog += d.toString(); });
server.stderr.on('data', (d) => { srvLog += d.toString(); });
await new Promise((r) => setTimeout(r, 900));

const base = 'http://127.0.0.1:' + PORT + '/__dshm/sync';
const H = { 'x-dshm-sync': PW };
let pass = 0, fail = 0;
function check(name, cond, detail) {
  if (cond) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log('  FAIL ' + name + (detail ? ' -- ' + detail : '')); }
}

// 1. ping
let r = await fetch(base + '/ping', { headers: H });
let j = await r.json();
check('ping 200', r.status === 200 && j.ok === true, JSON.stringify(j));

// 2. 未授权
r = await fetch(base + '/ping', { headers: { 'x-dshm-sync': 'wrong' } });
check('wrong pw -> 401', r.status === 401);

// 3. PUT 新文件
const body1 = Buffer.from('hello-peersync-1');
const sha1 = crypto.createHash('sha256').update(body1).digest('hex');
r = await fetch(base + '/file?path=' + encodeURIComponent(REL), { method: 'PUT', headers: { ...H, 'x-dshm-sha256': sha1 }, body: body1 });
j = await r.json();
check('PUT new 200', r.status === 200 && j.ok && j.replaced === false, JSON.stringify(j));
check('PUT 落地字节一致', fs.readFileSync(path.join(dir, 'session.v4.jsonl.zstd')).equals(body1));

// 4. manifest 命中
r = await fetch(base + '/manifest', { headers: H });
j = await r.json();
check('manifest count=1', j.count === 1 && j.files[0].sha256 === sha1, JSON.stringify(j.files));

// 5. sha 不符 -> 409 且不落盘
const bad = Buffer.from('tampered');
r = await fetch(base + '/file?path=' + encodeURIComponent(REL), { method: 'PUT', headers: { ...H, 'x-dshm-sha256': 'deadbeef' }, body: bad });
check('sha mismatch -> 409', r.status === 409);
check('sha mismatch 未落盘', fs.readFileSync(path.join(dir, 'session.v4.jsonl.zstd')).equals(body1));

// 6. 覆盖 -> 生成备份
const body2 = Buffer.from('hello-peersync-2-longer');
const sha2 = crypto.createHash('sha256').update(body2).digest('hex');
r = await fetch(base + '/file?path=' + encodeURIComponent(REL), { method: 'PUT', headers: { ...H, 'x-dshm-sha256': sha2 }, body: body2 });
j = await r.json();
check('PUT replace 200 且有备份', r.status === 200 && j.replaced === true && !!j.backup, JSON.stringify(j));
const files = fs.readdirSync(dir);
check('备份文件存在', files.some((f) => f.includes('.peersync-bak-')), files.join(','));

// 7. GET 回读
r = await fetch(base + '/file?path=' + encodeURIComponent(REL), { headers: H });
const got = Buffer.from(await r.arrayBuffer());
check('GET 字节一致', got.equals(body2));

// 8. stat 存在 / 不存在
r = await fetch(base + '/stat?path=' + encodeURIComponent(REL), { headers: H });
j = await r.json();
check('stat exists sha 对', j.exists === true && j.sha256 === sha2);
r = await fetch(base + '/stat?path=' + encodeURIComponent('--other-slug--/' + SID + '/session.v4.jsonl.zstd'), { headers: H });
j = await r.json();
check('stat 不存在 -> exists=false 且 200', r.status === 200 && j.exists === false, JSON.stringify(j));

// 9. 非法路径
for (const badPath of ['../etc/passwd', 'a/../../x/session.v4.jsonl.zstd', 'x/session.lock', 'x/foo.txt', 'x/session.v4.jsonl.zstd.compact-bak', '']) {
  r = await fetch(base + '/file?path=' + encodeURIComponent(badPath), { headers: H });
  check('bad path 拒绝 [' + badPath + ']', r.status === 400, 'got ' + r.status);
}

// 10. scope 过滤
r = await fetch(base + '/manifest?scope=' + encodeURIComponent('nonexistent-slug'), { headers: H });
j = await r.json();
check('scope 不匹配 count=0', j.count === 0, JSON.stringify(j));

// 11. 非 sync 路径 404
r = await fetch('http://127.0.0.1:' + PORT + '/index.html', { headers: H });
check('非同步路径 404', r.status === 404);

// 12. 备份保留上限 3
for (let i = 0; i < 5; i++) {
  const b = Buffer.from('gen-' + i + '-' + 'x'.repeat(20 + i));
  const s = crypto.createHash('sha256').update(b).digest('hex');
  await fetch(base + '/file?path=' + encodeURIComponent(REL), { method: 'PUT', headers: { ...H, 'x-dshm-sha256': s }, body: b });
  await new Promise((rr) => setTimeout(rr, 1100)); // 备份名按秒打戳，避免同名
}
const baks = fs.readdirSync(dir).filter((f) => f.includes('.peersync-bak-'));
check('备份最多保留 3 份', baks.length <= 3, 'got ' + baks.length + ': ' + baks.join(','));

// 13. 退避：连续 5 次错密码后 429
for (let i = 0; i < 5; i++) await fetch(base + '/ping', { headers: { 'x-dshm-sync': 'nope' + i } });
r = await fetch(base + '/ping', { headers: { 'x-dshm-sync': 'nope' } });
check('连续失败后 429 退避', r.status === 429, 'got ' + r.status);
// 正确密码在锁定期间仍可用，并且会清除该来源的失败记录（避免误锁住合法用户）
r = await fetch(base + '/ping', { headers: H });
check('锁定期间正确密码仍可用', r.status === 200, 'got ' + r.status);
r = await fetch(base + '/ping', { headers: { 'x-dshm-sync': 'nope-again' } });
check('成功后失败计数清零（回 401 而非 429）', r.status === 401, 'got ' + r.status);

console.log('\nRESULT: pass=' + pass + ' fail=' + fail);
console.log('--- server log tail ---');
console.log(srvLog.split('\n').slice(-8).join('\n'));
server.kill();
await fsp.rm(root, { recursive: true, force: true });
process.exit(fail === 0 ? 0 : 1);

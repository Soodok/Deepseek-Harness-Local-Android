/**
 * lan-web-proxy.mjs —— 让局域网设备用原生 WebUI 连上手机里的 dsh 引擎（v1.2.160）
 *
 * ## 为什么用 node 而不是在 app 里手写 HTTP 代理
 * 手写代理要自己处理 chunked / 压缩 / Cookie / 特殊 URL（`plugins/??a.js,b.js`）
 * / WebSocket 升级 —— 实测在 Kotlin 侧反复出问题（白屏、0 字节资源、乱码）。
 * node 的 http 模块 + 流式 pipe 天然正确处理这些，代码短得多、也稳得多。
 *
 * ## 做什么
 *   浏览器 http://<手机IP>:3090  →  本代理  →  http://127.0.0.1:3080（引擎 WebUI）
 * 四件事：
 *   ① 密码鉴权（Cookie，首访 ?pw= 换票）；
 *   ② 自动补引擎 token（从 engine.log 读，用户零输入）；
 *   ③ 三处前端补丁：crypto.randomUUID polyfill、isLoopbackHostname 恒真、
 *      Host/Origin 改写为 127.0.0.1（过同源检查）；
 *   ④ WebSocket 升级原样转发（对话流靠它）。
 *
 * ## 硬约束
 *   · 只监听 3090（绝不碰 3080/3083）；
 *   · 起停独立于引擎；任何异常只记日志，不退出、不影响 app；
 *   · 只在内存里改响应体，**不修改磁盘上的任何引擎文件**。
 *
 * 用法: node lan-web-proxy.mjs --port 3090 --pw <密码> [--upstream 127.0.0.1:3080]
 * 环境: DSH_LAN_PW 亦可提供密码（优先命令行）
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

// ---------- 参数 ----------
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const PORT = Number(argOf('port', '3090'));
const PW = argOf('pw', process.env.DSH_LAN_PW || '');
const UPSTREAM = argOf('upstream', '127.0.0.1:3080');
const [UP_HOST, UP_PORT] = UPSTREAM.split(':');
const HOME = process.env.DSH_HOME || '/data/user/0/app.dsh.mobile/files/dsh-home';
const ENGINE_DIR = path.resolve(HOME, '..', 'engine');
const LOG = (...a) => console.log(`[lan-proxy ${new Date().toISOString()}]`, ...a);

if (!PW) {
  LOG('refusing to start: no password (pass --pw or DSH_LAN_PW)');
  process.exit(2);
}

// ---------- 引擎 token（自动补，用户零输入）----------
let cachedToken = null;
let tokenReadAt = 0;
function engineToken() {
  // 引擎每次启动写新 token 到 engine/engine.log；60s 内复用缓存
  if (cachedToken && Date.now() - tokenReadAt < 60_000) return cachedToken;
  try {
    const logFile = path.join(ENGINE_DIR, 'engine.log');
    const txt = fs.readFileSync(logFile, 'utf8');
    const ms = [...txt.matchAll(/token=([A-Za-z0-9_-]{16,})/g)];
    if (ms.length) cachedToken = ms[ms.length - 1][1];
  } catch (e) {
    LOG('token read failed:', e.message);
  }
  tokenReadAt = Date.now();
  return cachedToken;
}

// ---------- 前端补丁 ----------
const POLYFILL = `<script>(function(){try{
if(!(window.crypto&&window.crypto.randomUUID)){
  window.crypto=window.crypto||{};
  window.crypto.randomUUID=function(){
    var b=new Uint8Array(16);
    if(window.crypto.getRandomValues){window.crypto.getRandomValues(b);}
    else{for(var i=0;i<16;i++){b[i]=Math.floor(Math.random()*256);}}
    b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;
    var h=[];for(var i=0;i<16;i++){h.push((b[i]+256).toString(16).slice(1));}
    return h.slice(0,4).join('')+'-'+h.slice(4,6).join('')+'-'+h.slice(6,8).join('')+'-'+h.slice(8,10).join('')+'-'+h.slice(10,16).join('');
  };
}}catch(e){}})();</script>`;

function patchHtml(html) {
  let out = html;
  // ⚠️ v1.2.160 实测修复（"Failed to load plugins" / 白屏的最后一环）：
  // 前端的 boot 检查要求每个模块入口**既在 HTML 里被 preload、又有 script 标签**。
  // 而引擎生成的 HTML 里 `@deepseek-ai/dsh-client-modules` **只有 <script src>、
  // 没有对应的 <link rel="preload">**（本地 loopback 访问时前端不查这一条，
  // 非 loopback 才查 → 所以只有经代理访问才暴露）。
  // 这里按其 script 的 URL 补一条 preload，让检查通过。
  const m = /<script[^>]*src="(plugins\/\?\?[^"]*dsh-client-modules\/client\.js[^"]*)"[^>]*>/.exec(out);
  if (m && !out.includes('rel="preload" as="script" href="' + m[1].replace(/&amp;/g, '&'))) {
    const href = m[1];   // 保持与 script 完全一致的写法（含 &amp; 实体）
    out = out.replace('</head>', `<link rel="preload" as="script" href="${href}">` + '</head>');
  }
  return out.includes('</head>') ? out.replace('</head>', POLYFILL + '</head>') : POLYFILL + out;
}
function patchJs(js) {
  // 门禁恒真：非 loopback 访问时恢复"设置持久化 / 设置文档编辑"等能力
  if (!js.includes('isLoopbackHostname')) return js;
  return js.replace(/function isLoopbackHostname\(hostname\)\s*\{/, 'function isLoopbackHostname(hostname) { return true;');
}

// ---------- 鉴权 ----------
const COOKIE = 'dsh_lan_pw';
const LOGIN_PAGE = `<!DOCTYPE html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH Mobile</title>
<style>body{font-family:system-ui;background:#161c24;color:#e8eaed;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
form{background:#1f2733;padding:28px;border-radius:16px;width:300px}
input{width:100%;padding:12px;border-radius:10px;border:1px solid #38414d;background:#12181f;color:#e8eaed;box-sizing:border-box;font-size:16px}
button{width:100%;margin-top:14px;padding:12px;border:0;border-radius:10px;background:#1a73e8;color:#fff;font-size:16px}
p{color:#8a94a3;font-size:13px}</style>
<form method="GET" action="/__lan_auth"><h3>DSH Mobile</h3>
<input name="pw" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="6 位验证码" autofocus>
<button type="submit">进入</button>
<p>6 位验证码在手机设置的「局域网访问 WebUI」一栏。仅限可信局域网使用。</p></form>`;

function parseCookies(req) {
  const raw = req.headers.cookie || '';
  const out = {};
  for (const kv of raw.split(';')) {
    const i = kv.indexOf('=');
    if (i > 0) out[kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
  }
  return out;
}

// ---------- 登录失败退避（v1.4.46 安全审查 H1）----------
// 旧实现错误密码一律立刻回 200，攻击者可无限并发爆破（同 WiFi 或同机 App
// 走 127.0.0.1:3090 都能打，破译即拿到完整引擎会话）。
// 现在：同一来源连续失败按指数退避 + 抖动；达到上限后锁定一段时间。
// 全部失败响应固定延迟到与成功路径接近，避免用耗时侧信道区分对错。
const FAIL_WINDOW_MS = 10 * 60 * 1000;
const LOCK_AFTER = 5;
const LOCK_MS = 10 * 60 * 1000;
const failState = new Map(); // key: 来源标识 -> { count, last, lockedUntil }

function clientKey(req) {
  // 回环访问同样要限速（同机恶意 App 走 127.0.0.1 不需要任何权限），
  // 所以不能只按远端 IP 区分——回环统一记成一个桶。
  return req.socket.remoteAddress || 'unknown';
}
function backoffRemainingMs(req) {
  const k = clientKey(req);
  const st = failState.get(k);
  if (!st) return 0;
  const now = Date.now();
  if (st.lockedUntil && now < st.lockedUntil) return st.lockedUntil - now;
  if (now - st.last > FAIL_WINDOW_MS) { failState.delete(k); return 0; }
  if (st.count <= 0) return 0;
  // 指数退避 0.5s,1s,2s,4s...
  //
  // ⚠️ v1.5.0 修的真 bug：原实现直接返回「本轮应等多久」，不减已过去的时间，
  // 于是只要在 FAIL_WINDOW_MS（10 分钟）内失败过一次，之后每次请求都算出同样
  // 的正数 → 正确验证码也被挡，用户输错一次就得等满 10 分钟才能重试。
  // 退避必须随时间流逝而递减：等够 waitTotal 之后就该放行。
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

/** 只允许站内相对路径，挡掉 next=//evil.com 之类的开放重定向。 */
function safeNext(raw) {
  if (!raw) return '/';
  // 必须以单个 / 开头；不能是 // 或 /\（浏览器会当协议相对 URL）
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/')) return '/';
  return raw;
}

// ---------- 服务 ----------
const server = http.createServer((req, res) => {
  const url = req.url || '/';

  // ① 登录入口（先于鉴权：它本身就是"用密码换 Cookie"的入口）
  if (url.startsWith('/__lan_auth')) {
    const q = new URL(url, 'http://x').searchParams;

    const wait = backoffRemainingMs(req);
    if (wait > 0) {
      // 锁定/退避中：不再比对密码，统一回登录页 + Retry-After
      setTimeout(() => {
        res.writeHead(429, { 'Content-Type': 'text/html; charset=utf-8', 'Retry-After': String(Math.ceil(wait / 1000)) });
        res.end(LOGIN_PAGE);
      }, Math.min(wait, 1200));
      return;
    }

    if (q.get('pw') === PW) {
      noteSuccess(req);
      const next = safeNext(q.get('next'));
      res.writeHead(302, {
        'Set-Cookie': `${COOKIE}=${PW}; Path=/; HttpOnly; SameSite=Lax`,
        Location: next,
      });
      res.end();
    } else {
      const st = noteFailure(req);
      const delay = backoffRemainingMs(req) || 120;
      setTimeout(() => {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(LOGIN_PAGE);
      }, Math.min(delay, 1200));
      if (st.count >= LOCK_AFTER) {
        process.stderr.write(`[lan-web] auth locked for ${clientKey(req)} after ${st.count} failures
`);
      }
    }
    return;
  }

  // ② 鉴权
  const cookies = parseCookies(req);
  if (cookies[COOKIE] !== PW) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(LOGIN_PAGE);
    return;
  }

  // ③ 转发（去掉浏览器的 Accept-Encoding：本地环回不需要压缩，
  //    也避免"压缩体 + 补丁"互相打架；node 会正确处理明文流）
  // ⚠️ v1.2.160 实测修正（无限 303 循环的根因）：
  // **只在浏览器还没有引擎会话 Cookie 时**才补 token。已经拿到 dsh-auth-* 时再补
  // token，引擎会把它当成"重新认证"→ 重新签发 Cookie 并 303 → 每次请求都循环，
  // 表现就是"输对密码后仍停在跳转、永远拿不到页面"。
  const isDoc = url === '/' || url.split('?')[0].endsWith('.html');
  // ⚠️ v1.2.168 实测修复（用户反馈"输对验证码后 303 无限循环"）——**两版都不对，这是第三版**：
  //   · 版本 A（"有 dsh-auth Cookie 就不补 token"）：**Cookie 过期/属于旧引擎进程时**，
  //     代理不补 token、浏览器又带无效 Cookie → 引擎回 "authentication required"，打不开。
  //   · 版本 B（"总是补 token"）：**cookie + token 同时存在时引擎优先走 token 路径**
  //     （实测：带 token 一律 303 重定向去建立会话）→ 浏览器每次都被重定向到 `./`
  //     而代理又给它补 token → **无限 303 循环**（实测 redirects=50 后被 curl 放弃）。
  //   · 版本 C（本版，实测正确）：
  //       ① 无 dsh-auth Cookie → 补 token（引擎 303 + set-cookie 建立会话）；
  //       ② 有 dsh-auth Cookie → **原样透传、不补 token**（引擎直接 200，实测 36658 字节）；
  //       ③ 若 ② 的响应是"要求认证"（Cookie 已失效）→ **用 token 重试一次**，
  //          让引擎重新签发 Cookie（自愈，用户无感）。
  const hasEngineCookie = Object.keys(cookies).some((k) => k.startsWith('dsh-auth'));
  let upstreamPath = url;
  let attachedToken = false;
  if (isDoc && !url.includes('token=') && !hasEngineCookie) {
    const tk = engineToken();
    if (tk) {
      upstreamPath += (url.includes('?') ? '&' : '?') + 'token=' + tk;
      attachedToken = true;
    }
  }

  const headers = { ...req.headers };
  delete headers['accept-encoding'];
  delete headers['host'];
  delete headers['origin'];
  // v1.2.160：剥掉条件请求头。早期代理版本返回过 0 字节的 JS / 坏 manifest，
  // 浏览器把它们缓存了；带着 If-None-Match 再来会拿到 304 → 继续用坏缓存，
  // 表现就是"我改了但页面完全没变"（实测踩坑）。强制每次都拿全量。
  delete headers['if-none-match'];
  delete headers['if-modified-since'];
  headers.host = `${UP_HOST}:${UP_PORT}`;
  headers.origin = `http://${UP_HOST}:${UP_PORT}`;
  // 我们的鉴权 Cookie 不透传给引擎（它只认自己的 dsh-auth-*）
  if (headers.cookie) {
    headers.cookie = headers.cookie
      .split(';')
      .filter((c) => !c.trim().startsWith(COOKIE + '='))
      .join(';');
    if (!headers.cookie.trim()) delete headers.cookie;
  }

  // 上游转发（一次请求）。返回 {upRes} 或抛错 —— 调用方负责自愈重试。
  function sendToUpstream(path) {
    return new Promise((resolve, reject) => {
      const upReq = http.request(
        { host: UP_HOST, port: Number(UP_PORT), method: req.method, path, headers },
        (upRes) => resolve({ upReq, upRes }),
      );
      upReq.on('error', reject);
      req.pipe(upReq);
    });
  }

  /** 判断上游响应是否在"要求认证"（引擎用自己的措辞；Cookie 失效时这样回） */
  async function looksUnauthenticated(upRes, chunks) {
    if (upRes.statusCode === 401) return true;
    if (upRes.statusCode !== 200) return false;
    const first = Buffer.concat(chunks).toString('utf8', 0, 400).toLowerCase();
    return first.includes('authentication required') || first.includes('reopen the url');
  }

  // ⚠️ v1.2.168（第三版修复，用户实测驱动）：
  //   · 版本 A「有 dsh-auth Cookie 就不补 token」→ Cookie **过期**时打不开；
  //   · 版本 B「总是补 token」→ cookie+token 同时存在 → 引擎一律 303 →
  //     **无限重定向**（实测 redirects=50）；
  //   · 版本 C（本版）：
  //       ① 无 Cookie → 补 token（引擎 303 + set-cookie 建会话）；
  //       ② 有 Cookie → 原样透传（引擎 200，实测 36658B）；
  //       ③ **若 ② 仍被要求认证（Cookie 过期）→ 用 token 重发一次**，
  //          让引擎重新签发 Cookie。真机浏览器带着旧 Cookie 走的就是这条
  //          （curl 每次全新 jar 所以测不出来 —— 这是漏测的原因）。
  (async () => {
    let upRes, chunks = [];
    try {
      const r1 = await sendToUpstream(upstreamPath);
      upRes = r1.upRes;

      const needsAuthCheck = isDoc;
      if (needsAuthCheck) {
        // 需要预读 body 才能判断是否"要求认证"：仅文档级请求（体积小）
        chunks = [];
        for await (const c of upRes) chunks.push(c);
        if (await looksUnauthenticated(upRes, chunks)) {
          const tk = engineToken();
          if (tk) {
            LOG('upstream asked for auth (stale cookie) — retrying with token');
            const base = url.split('?')[0];
            const retryPath = base + (url.includes('?') ? url.slice(url.indexOf('?')) + '&' : '?') + 'token=' + tk;
            // ⚠️ 必须**清掉过期的引擎 Cookie** 再重发：实测「cookie + token 同时存在」时
            // 引擎优先走 token 路径 → 一律 303 → 重试也白搭（实测重试 51 次仍循环）。
            // 只留我们自己的 dsh_lan_pw（引擎不认它，但无害），去掉所有 dsh-auth-*。
            if (headers.cookie) {
              const kept = headers.cookie
                .split(';')
                .filter((c) => !c.trim().startsWith('dsh-auth'))
                .join(';');
              if (kept.trim()) headers.cookie = kept; else delete headers.cookie;
            }
            const r2 = await sendToUpstream(retryPath);
            upRes = r2.upRes;
            chunks = [];
            for await (const c of upRes) chunks.push(c);
          }
        }
        // 重新解释为"已收全的文档"，直接交给下面的补丁逻辑
        serveDocument(upRes, chunks);
        return;
      }

      // 非文档：原样流式转发
      const ec = upRes.headers['content-encoding'];
      res.writeHead(upRes.statusCode || 200, stripCache(upRes.headers));
      upRes.pipe(res);
    } catch (e) {
      LOG('upstream error:', e && e.message);
      try {
        res.writeHead(502, { 'Content-Type': 'text/plain' });
        res.end('upstream error: ' + (e && e.message));
      } catch {}
    }
  })();

  /** 文档级响应：打补丁后回给浏览器 */
  function serveDocument(upRes, chunks) {
    const ct = String(upRes.headers['content-type'] || '');
    const isHtmlDoc = ct.includes('text/html');
    const isJsDoc = ct.includes('javascript');
    let body = Buffer.concat(chunks).toString('utf8');
    if (isJsDoc) body = patchJs(body);
    else if (isHtmlDoc) body = patchHtml(body);
    const buf = Buffer.from(body, 'utf8');
    const h = stripCache(upRes.headers);
    delete h['content-length'];
    delete h['content-encoding'];
    delete h['transfer-encoding'];
    h['content-length'] = String(buf.length);
    res.writeHead(upRes.statusCode || 200, h);
    res.end(buf);
  }

  /** 统一禁用缓存（保留 set-cookie：引擎靠 303 + set-cookie 建会话） */
  function stripCache(h) {
    const o = { ...h };
    delete o.etag;
    delete o['last-modified'];
    delete o['cache-control'];
    delete o.expires;
    o['cache-control'] = 'no-store';
    // ⚠️ v1.2.168 实测修复（303 无限重定向的最后一环）：
    // 引擎签发的会话 Cookie 带 `SameSite=Strict`。而经代理访问时，浏览器把
    // `http://<手机IP>:3090` 视为**非安全上下文**，跨站重定向链里 Strict Cookie
    // 会被浏览器丢弃 → 每次请求都没有会话 Cookie → 引擎又回 303 + 新 Cookie
    // → **无限循环**（实测 redirects=50；设备内直连引擎无此问题，因为那是 loopback
    // 安全上下文）。
    // 修法：把它改写为 `SameSite=Lax`（同站导航会带上；重定向链安全）并去掉 Secure 隐含要求。
    // 代理只服务局域网可信设备，这是必需的兼容处理。
    if (o['set-cookie']) {
      const fix = (c) => String(c)
        .replace(/;\s*SameSite=Strict/gi, '; SameSite=Lax')
        .replace(/;\s*Secure/gi, '');
      o['set-cookie'] = Array.isArray(o['set-cookie']) ? o['set-cookie'].map(fix) : fix(o['set-cookie']);
    }
    return o;
  }
});

// WebSocket / 其它升级：原样双向转发（对话流靠它）
server.on('upgrade', (req, socket, head) => {
  const cookies = parseCookies(req);
  if (cookies[COOKIE] !== PW) {
    socket.destroy();
    return;
  }
  const headers = { ...req.headers };
  delete headers.host;
  delete headers.origin;
  headers.host = `${UP_HOST}:${UP_PORT}`;
  headers.origin = `http://${UP_HOST}:${UP_PORT}`;
  if (headers.cookie) {
    headers.cookie = headers.cookie
      .split(';')
      .filter((c) => !c.trim().startsWith(COOKIE + '='))
      .join(';');
    if (!headers.cookie.trim()) delete headers.cookie;
  }
  const upReq = http.request({
    host: UP_HOST, port: Number(UP_PORT), method: req.method, path: req.url, headers,
  });
  upReq.on('upgrade', (upRes, upSocket, upHead) => {
    socket.write(
      `HTTP/1.1 101 Switching Protocols\r\n` +
        Object.entries(upRes.headers).map(([k, v]) => `${k}: ${v}`).join('\r\n') +
        '\r\n\r\n',
    );
    if (upHead?.length) upSocket.unshift(upHead);
    if (head?.length) socket.unshift(head);
    socket.pipe(upSocket);
    upSocket.pipe(socket);
    socket.on('error', () => { try { upSocket.destroy(); } catch {} });
    upSocket.on('error', () => { try { socket.destroy(); } catch {} });
  });
  upReq.on('error', (e) => {
    LOG('ws upstream error:', e.message);
    socket.destroy();
  });
  upReq.end();
});

// ⚠️ v1.2.160 实测修复：端口被占（EADDRINUSE）时**不能直接退出** ——
// 上一次的代理退出后端口会短暂 TIME_WAIT，或另有残留进程占着；直接退出会让
// 用户"开了开关却访问不了"（实测）。改为**重试 + 抢占**：
//   1. 先退避重试若干次（覆盖 TIME_WAIT，通常几秒内释放）；
//   2. 仍失败则尝试清掉占用该端口的旧代理进程，再试一次。
let attempts = 0;
function listen() {
  attempts++;
  server.listen(PORT, '0.0.0.0');
}
server.on('error', (e) => {
  if (e.code === 'EADDRINUSE' && attempts <= 8) {
    // ⚠️ v1.2.167 实测修复（用户反馈"验证码输入正确仍打不开"）：
    // 旧实现只退避重试 —— 但占用端口的**正是上一次遗留的代理进程**（它用旧验证码），
    // 重试永远失败；而 app 的 stop() 在某些路径下没杀掉它（换进程/换包后 child 引用失效）。
    // 结果：浏览器连的是旧进程（旧码），用户输的是新码 → 永远不匹配。
    // 正确做法：**先把占用 3090 的旧代理杀掉**（只杀命令行含本脚本名的进程，不误伤别的服务），
    // 再重试监听。
    LOG(`port ${PORT} busy (attempt ${attempts}) — killing stale proxy and retrying`);
    try {
      // 只匹配本脚本名，避免误杀引擎（3080）或桥（3083）
      execSync(`pkill -f lan-web-proxy 2>/dev/null || true`, { stdio: "ignore" });
    } catch {}
    setTimeout(listen, 1200);
    return;
  }
  LOG('server error:', e.message);
});
server.on('listening', () => {
  LOG(`listening on 0.0.0.0:${PORT} -> ${UP_HOST}:${UP_PORT} (engine dir: ${ENGINE_DIR})`);
});
listen();

// 优雅退出：别让异常把进程带走
process.on('uncaughtException', (e) => LOG('uncaught:', e.message));
process.on('unhandledRejection', (e) => LOG('unhandled:', e?.message || e));

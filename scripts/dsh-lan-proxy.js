#!/usr/bin/env node
/**
 * dsh-lan-proxy —— 手机端局域网访问反代（零依赖，只用 node 内置模块）
 *
 * 为什么需要它：dsh 官方**有意**不支持 --host 0.0.0.0（源码注释：会把远程代码执行
 * 能力暴露给网络），服务器只 bind 127.0.0.1。而直接把 127.0.0.1:3080 反代出去
 * 又会撞上 dsh 前端的三个「非 localhost」硬限制：
 *
 *   1. crypto.randomUUID() 在非安全上下文不存在（局域网 IP 访问 = 非安全上下文）
 *      → RPC 请求发不出去 → WebSocket 建不起来 → 界面能开但功能全废
 *   2. connection.isLoopback 为假时，设置类功能（模型配置、文件按钮）被隐藏
 *   3. /api 同源校验要求 Origin 与 Host 一致，反代后容易 403
 *
 * 本脚本把这三条都修好（思路参考 smanx/deepseek-harness-docker 的 proxy，但零依赖
 * 重写、适配手机，并自动从引擎日志取 token）：
 *   · 注入 crypto.randomUUID polyfill
 *   · 把 isLoopbackHostname(pageLocation.hostname) 判定改写为 true
 *   · Origin / Host 对齐到上游
 *   · 首页自动带上 ?token=（从引擎日志提取最新 token）
 *   · 可选 Basic Auth 保护
 *   · WebSocket 升级转发
 *
 * 用法（在手机 shell 里，引擎已在运行）：
 *   node dsh-lan-proxy.js                    # 监听 0.0.0.0:3081 → 转发 127.0.0.1:3080
 *   PORT=3081 node dsh-lan-proxy.js          # 自定义对外端口
 *   PROXY_USER=me PROXY_PASS=secret node dsh-lan-proxy.js  # 启用 Basic Auth（强烈建议）
 *
 * 然后局域网内其他设备访问 http://<手机IP>:3081
 *
 * ⚠️ 安全：这会把一个能读写文件、执行命令的 Agent 暴露给局域网。只在可信网络用，
 *    务必设置 PROXY_USER/PROXY_PASS，用完及时停掉。
 */
const http = require('node:http');
const fs = require('node:fs');
const net = require('node:net');
const crypto = require('node:crypto');

const UPSTREAM_PORT = Number(process.env.UPSTREAM_PORT || 3080);
const LISTEN_PORT = Number(process.env.PORT || 3081);
const LISTEN_HOST = process.env.BIND || '0.0.0.0';
const UPSTREAM = `127.0.0.1:${UPSTREAM_PORT}`;

const USER = process.env.USER_AUTH || process.env.PROXY_USER || '';
const PASS = process.env.PASS_AUTH || process.env.PROXY_PASS || '';

// 引擎日志：从里面抓最新的 launch token（dsh 0.2.0 起 WebUI 强制认证）
const LOG_CANDIDATES = [
  process.env.DSH_ENGINE_LOG,
  process.env.HOME && `${process.env.HOME}/../engine/engine.log`,
  process.env.PREFIX && `${process.env.PREFIX}/engine.log`,
  '/data/user/0/app.dsh.mobile/files/engine/engine.log',
  '/data/data/app.dsh.mobile/files/engine/engine.log',
].filter(Boolean);

const TOKEN_RE = /[?&]token=([A-Za-z0-9._~-]{16,})/;

function readLatestToken() {
  for (const p of LOG_CANDIDATES) {
    try {
      const st = fs.statSync(p);
      const start = Math.max(0, st.size - 64 * 1024);      // 只看尾部 64KB
      const fd = fs.openSync(p, 'r');
      const buf = Buffer.alloc(st.size - start);
      fs.readSync(fd, buf, 0, buf.length, start);
      fs.closeSync(fd);
      const text = buf.toString('utf8');
      let last = null;
      let m;
      const re = new RegExp(TOKEN_RE.source, 'g');
      while ((m = re.exec(text)) !== null) last = m[1];     // 取最后一个（最新）
      if (last) return last;
    } catch { /* 换下一个候选路径 */ }
  }
  return null;
}

// ── 注入用的 JS ────────────────────────────────────────────────────────────────
// crypto.randomUUID：非安全上下文没有该 API，用 getRandomValues（非安全源也可用）实现
const POLYFILL = `<script>(function(){try{var c=window.crypto;if(c&&typeof c.randomUUID!=="function"&&typeof c.getRandomValues==="function"){c.randomUUID=function(){var b=c.getRandomValues(new Uint8Array(16));b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;var h="";for(var i=0;i<16;i++){h+=b[i].toString(16).padStart(2,"0")}return h.slice(0,8)+"-"+h.slice(8,12)+"-"+h.slice(12,16)+"-"+h.slice(16,20)+"-"+h.slice(20)}}}catch(e){}})();</script>`;

// 只把「调用结果」改成 true，保留属性名 —— 直接整段替换会得到 `connection.true`（语法错误，
// 实测踩过）。dsh 的写法是 connection.isLoopbackHostname(pageLocation.hostname)，
// 故替换为 connection.isLoopbackHostname(() => true) 的等价形式：
// 属性保留、参数换成恒真函数；调用点不变，返回 true。
const LOOPBACK_CALL_RE = /(\.isLoopbackHostname)\s*\(\s*pageLocation\.hostname\s*\)/g;

function injectIntoHead(html, snippet) {
  const i = html.search(/<head[^>]*>/i);
  if (i === -1) return snippet + html;
  const end = html.indexOf('>', i) + 1;
  return html.slice(0, end) + snippet + html.slice(end);
}

function rewriteBody(buf, contentType) {
  const ct = String(contentType || '');
  if (!ct.includes('text/html') && !ct.includes('javascript')) return null;
  const text = buf.toString('utf8');
  if (ct.includes('text/html')) return Buffer.from(injectIntoHead(text, POLYFILL), 'utf8');
  // 命中判定串才改写；未命中原样透传
  if (/isLoopbackHostname\s*\(\s*pageLocation\.hostname\s*\)/.test(text)) {
    // 参数换成恒真函数：connection.isLoopbackHostname(() => true)
    // 调用点语法完整，返回值恒为 true → 设置类功能（模型配置/文件按钮）在局域网也可见
    const patched = text.replace(LOOPBACK_CALL_RE, '$1(() => true)');
    return Buffer.from(patched, 'utf8');
  }
  return null;
}

// ── Basic Auth ────────────────────────────────────────────────────────────────
function authed(req) {
  if (!USER || !PASS) return true;                       // 未配置 = 不启用认证
  const m = /^Basic\s+(.+)$/i.exec(req.headers.authorization || '');
  if (!m) return false;
  let dec;
  try { dec = Buffer.from(m[1], 'base64').toString('utf8'); } catch { return false; }
  const i = dec.indexOf(':');
  if (i === -1) return false;
  const a = Buffer.from(dec.slice(0, i));
  const b = Buffer.from(dec.slice(i + 1));
  const ua = Buffer.from(USER);
  const pb = Buffer.from(PASS);
  return a.length === ua.length && b.length === pb.length &&
    crypto.timingSafeEqual(a, ua) && crypto.timingSafeEqual(b, pb);
}

// 公开静态资源：浏览器抓 <link rel=manifest>/favicon 时不带 Basic 凭据，
// 强制认证会让控制台刷 401（不影响功能，但噪音大）。仅放行这几个非敏感文件。
const PUBLIC_PATHS = new Set(['/manifest.webmanifest', '/favicon.svg', '/favicon.ico']);

function deny(res) {
  res.writeHead(401, {
    'WWW-Authenticate': 'Basic realm="dsh-lan"',
    'Content-Type': 'text/plain; charset=utf-8',
  });
  res.end('401 Unauthorized — set PROXY_USER/PROXY_PASS and use those credentials\n');
}

// ── 转发 ──────────────────────────────────────────────────────────────────────
function upstreamHeaders(req, extra = {}) {
  const h = { ...req.headers, ...extra };
  h.host = UPSTREAM;                       // Host 对齐上游（否则 /api 同源校验 403）
  if (h.origin) h.origin = `http://${UPSTREAM}`;
  delete h['accept-encoding'];             // 拿明文便于改写；上游压缩收益在局域网不重要
  return h;
}

function proxyHttp(req, res) {
  const path = req.url && req.url.startsWith('/') ? req.url : '/';
  const isRoot = path === '/' || path.startsWith('/?');

  // 首页：自动补 token（浏览器拿到会话 cookie 后后续请求无需再带）
  let target = path;
  if (isRoot) {
    const tok = readLatestToken();
    if (tok && !/[?&]token=/.test(path)) {
      target = path.includes('?') ? `${path}&token=${tok}` : `/?token=${tok}`;
    }
  }

  const up = http.request(
    { host: '127.0.0.1', port: UPSTREAM_PORT, method: req.method, path: target,
      headers: upstreamHeaders(req) },
    (upRes) => {
      const ct = upRes.headers['content-type'] || '';
      const enc = String(upRes.headers['content-encoding'] || '');
      // 已请求明文（剥了 accept-encoding），但若上游仍压缩则不改写，原样透传（避免把压缩
      // 字节当 UTF-8 改写产生垃圾）；identity/空值才走改写
      const canRewrite = /text\/html|javascript/i.test(String(ct)) &&
        (enc === '' || enc === 'identity');
      if (!canRewrite) {
        res.writeHead(upRes.statusCode || 502, upRes.headers);
        upRes.pipe(res);
        return;
      }
      const chunks = [];
      upRes.on('data', (c) => chunks.push(c));
      upRes.on('end', () => {
        const orig = Buffer.concat(chunks);
        const rewritten = rewriteBody(orig, ct) || orig;
        const headers = { ...upRes.headers };
        delete headers['content-length'];
        delete headers['content-encoding'];   // 已按明文处理
        delete headers['transfer-encoding'];  // 缓冲后按 content-length 发送（两者并存会冲突）
        headers['content-length'] = String(rewritten.length);
        res.writeHead(upRes.statusCode || 502, headers);
        res.end(rewritten);
      });
    },
  );
  up.on('error', (e) => {
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(`502 上游 dsh 不可达（127.0.0.1:${UPSTREAM_PORT}）：${e.message}\n` +
              `请确认 App 里引擎已启动（状态栏显示"引擎已就绪"）\n`);
    } else res.end();
  });
  req.pipe(up);
}

const server = http.createServer((req, res) => {
  const pathname = (req.url || '/').split('?')[0];
  if (!PUBLIC_PATHS.has(pathname) && !authed(req)) return deny(res);
  proxyHttp(req, res);
});

// WebSocket / SSE 升级转发（dsh 的实时通道走这里，缺了就"能开页面但功能全废"）
server.on('upgrade', (req, socket, head) => {
  if (!authed(req)) {
    socket.end('HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm="dsh-lan"\r\nConnection: close\r\n\r\n');
    return;
  }
  const up = net.connect(UPSTREAM_PORT, '127.0.0.1', () => {
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    const h = upstreamHeaders(req, { connection: 'Upgrade', upgrade: req.headers.upgrade || 'websocket' });
    for (const [k, v] of Object.entries(h)) {
      if (Array.isArray(v)) v.forEach((x) => lines.push(`${k}: ${x}`));
      else if (v !== undefined) lines.push(`${k}: ${v}`);
    }
    up.write(lines.join('\r\n') + '\r\n\r\n');
    if (head && head.length) up.write(head);
    up.pipe(socket);
    socket.pipe(up);
  });
  up.on('error', () => socket.destroy());
  socket.on('error', () => up.destroy());
});

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
  const tok = readLatestToken();
  console.log(`[dsh-lan-proxy] 监听 ${LISTEN_HOST}:${LISTEN_PORT} → 127.0.0.1:${UPSTREAM_PORT}`);
  console.log(`[dsh-lan-proxy] token: ${tok ? tok.slice(0, 4) + '****' + tok.slice(-4) : '未找到（引擎未启动？稍后会自动重试）'}`);
  console.log(`[dsh-lan-proxy] 认证: ${USER && PASS ? '已启用 Basic Auth' : '⚠️ 未启用（建议设 PROXY_USER/PROXY_PASS）'}`);
  console.log(`[dsh-lan-proxy] 局域网访问: http://<手机IP>:${LISTEN_PORT}`);
});

## Issue #3 最终回复（可直接粘贴）

---

你好，感谢反馈！我沿着你提到的思路把 dsh 上游源码和那个 docker 项目都读了，**找到了确切原因**，而且**不是配置没写对** —— 是上游有意为之 + 前端对非 localhost 访问有四个硬限制。

### 为什么直接开局域网访问走不通

**① dsh 官方禁止绑定 `0.0.0.0`**（源码 `@deepseek-ai/dsh-web-app/lib/startup.js`，原文）：

```js
if (options.host === "0.0.0.0") program.error(
  "error: --host 0.0.0.0 is intentionally not supported yet for safety: " +
  "it would expose remote code execution to the network; use 127.0.0.1 instead");
```

服务器**永远只 bind `127.0.0.1`** —— 这个限制是对的（Agent 能执行任意代码，暴露到网络等于开放 RCE）。

**② 前端在非安全上下文会直接坏掉**（这才是你"能开页面但功能全废"的真凶）：

| 限制 | 后果 |
|---|---|
| `crypto.randomUUID()` 只在安全上下文（https/localhost）存在，**局域网 IP 不是安全上下文** | 生成不了 RPC id → **实时通道（WebSocket）建不起来** → 页面能开，什么都点不动 |
| `connection.isLoopbackHostname(...)` 为假时隐藏设置类功能 | **加不了模型、传不了文件** |
| `/api` 强制同源校验（Origin 必须等于它看到的 Host） | 反代后 **403** |

所以你遇到的现象是**四个原因叠加**的结果，单纯做端口转发必然失败。

### 解决方案：一个几十行的反代（零依赖，用手机自带的 node 跑）

思路参考了 [smanx/deepseek-harness-docker](https://github.com/smanx/deepseek-harness-docker) 的 proxy（它把同样的坑全踩过并修好了），我重写成**手机版、零依赖、能自动取 token**。

**最简单的方式 —— 直接让手机里的 AI 帮你做**：

在对话里说：

> 帮我把局域网访问开起来：写一个反代监听 0.0.0.0:3081 转发到 127.0.0.1:3080，
> 注入 crypto.randomUUID polyfill、把 isLoopbackHostname 判定改成恒真、
> 对齐 Origin/Host、转发 WebSocket 升级、从 engine.log 取最新 token 补到首页，
> 并加 Basic Auth（用户名 me 密码自定）。

（本项目的 AGENTS.md 里已经写好了这套要求，AI 会照做。）

**或者手动跑**（脚本要点如下，存成 `lan-proxy.js` 用 `node lan-proxy.js` 启动）：

```js
// 关键修复点（其余是普通转发）
// 1. 注入 polyfill 到 HTML <head>
const POLYFILL = `<script>(function(){try{var c=window.crypto;
if(c&&typeof c.randomUUID!=="function"&&typeof c.getRandomValues==="function"){
c.randomUUID=function(){var b=c.getRandomValues(new Uint8Array(16));
b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;var h="";
for(var i=0;i<16;i++){h+=b[i].toString(16).padStart(2,"0")}
return h.slice(0,8)+"-"+h.slice(8,12)+"-"+h.slice(12,16)+"-"+h.slice(16,20)+"-"+h.slice(20)}}}catch(e){}})();</script>`;

// 2. JS 改写：保留属性名，只换参数（整段替换会得到 connection.true 语法错误）
text.replace(/(\.isLoopbackHostname)\s*\(\s*pageLocation\.hostname\s*\)/g, '$1(() => true)')

// 3. 上游头对齐
headers.host = '127.0.0.1:3080';
if (headers.origin) headers.origin = 'http://127.0.0.1:3080';

// 4. WebSocket 升级转发（server.on('upgrade') → net.connect 到 3080 并双向 pipe）

// 5. token：读 engine.log 尾部 64KB，取【最后一个】?token= 匹配，补到首个 "/" 请求
```

启动后，局域网设备访问 `http://<手机IP>:3081` 即可（浏览器会先弹 Basic Auth，输入后自动带上 token 换到会话 cookie）。

### ⚠️ 安全（必须看）

这样做等于**把一个能读写文件、执行命令的 Agent 开放给局域网**：

- 只在**自己信任的网络**里用，绝不在公共 Wi-Fi / 咖啡厅网络开
- **务必设置 Basic Auth 密码**，别裸奔
- 用完就停掉反代进程（`killx node` 或 Ctrl+C）
- 不要试图用 `--host 0.0.0.0` 之类的方式绕过上游限制 —— 那个限制是有道理的

### 关于"旧手机焕发第二春"

思路完全可行 ✓ 旧手机当常驻 Agent 服务器 + 反代让局域网设备接入，上面这套就是为此准备的。如果你在跑的时候遇到具体报错（比如认证后仍白屏、WebSocket 连不上），把浏览器控制台的报错贴上来，我继续帮你看。

---

## 简短版（想回短一点就用这个）

> 查到根因了，**不是配置问题**：dsh 官方有意禁止 `--host 0.0.0.0`（源码里显式拒绝，防 RCE 外泄），服务器只绑 127.0.0.1。而且它的前端有四个「非 localhost」硬限制：
> ① `crypto.randomUUID` 在非安全上下文不存在 → RPC/WebSocket 全废（这是"能开页面但点不动"的真凶）
> ② `isLoopbackHostname` 为假时隐藏设置功能 → 加不了模型/传不了文件
> ③ `/api` 同源校验 → 反代后 403
>
> 所以单纯端口转发必然失败。解决办法是写个反代修掉这三条 + 自动补 token（参考 smanx/deepseek-harness-docker 的做法，但手机版零依赖）。
>
> **最省事的方式**：直接在对话里让手机里的 AI 做 —— 说「帮我把局域网访问开起来，写个反代监听 0.0.0.0:3081 转发 127.0.0.1:3080，注入 randomUUID polyfill、isLoopbackHostname 改恒真、对齐 Origin/Host、转发 WebSocket、从 engine.log 取 token、加 Basic Auth」，项目的 AGENTS.md 里已写好这套要求，它会照做。
>
> ⚠️ 安全：这等于把能执行命令的 Agent 开放给局域网，只在自己网络用，务必设密码，用完停掉。

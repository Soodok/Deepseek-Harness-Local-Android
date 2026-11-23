import { spawn } from 'node:child_process';
import fs from 'node:fs';
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const OUT = 'E:/Deepeek harness/dsh-android/docs/ui-preview/shots/onboarding-redesign';
const BASE = 'file:///E:/Deepeek%20harness/dsh-android/docs/ui-preview/2026-11-12-onboarding-redesign.html';
const PORT = 9333;
fs.mkdirSync(OUT, { recursive: true });
const sleep = ms => new Promise(r => setTimeout(r, ms));

// 每页期望的眉标 / 标题片段，用于截图前断言「拍到的是不是这一页」
const EXPECT = {
  0: ['DEEPSEEK HARNESS · 完整移植', '完整的 DeepSeek Harness'],
  1: ['01 / 08', '选择界面语言'],
  2: ['02 / 08', '默认方向'],
  3: ['03 / 08', '外观'],
  4: ['04 / 08', '强调色'],
  5: ['05 / 08', '屏幕与布局'],
  6: ['06 / 08', '运行模式'],
  7: ['07 / 08', '给 AI 的能力'],
  // 完成页标题随运行环境四态变化，四种都合法；只断言状态块存在
  8: ['运行环境'],
};

const rtOf = file => (file.match(/state-(preparing|ready|failed|lite)/) || [])[1] || null;

const chrome = spawn(CHROME, ['--headless=new','--disable-gpu','--hide-scrollbars','--no-first-run','--no-default-browser-check','--remote-debugging-port=' + PORT,'--user-data-dir=C:/Users/Qt/AppData/Local/Temp/dsh-shoot-profile','about:blank'], { stdio: 'ignore' });
let ver = null;
for (let i = 0; i < 80 && !ver; i++) { try { ver = await fetch('http://127.0.0.1:' + PORT + '/json/version').then(r => r.json()); } catch { await sleep(250); } }
if (!ver) { console.error('chrome not ready'); process.exit(1); }
const ws = new WebSocket(ver.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let seq = 0; const pend = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params, sessionId) => new Promise(res => { const id = ++seq; pend.set(id, res); ws.send(JSON.stringify({ id, method, params: params || {}, sessionId })); });
const t = await send('Target.createTarget', { url: 'about:blank' });
const sessionId = (await send('Target.attachToTarget', { targetId: t.result.targetId, flatten: true })).result.sessionId;
await send('Page.enable', {}, sessionId);
await send('Runtime.enable', {}, sessionId);

async function shot(file, w, h, dsf, q, page, fontScale) {
  await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: dsf, mobile: w < 1000 }, sessionId);
  if (fontScale) await send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 }, sessionId);
  await send('Page.navigate', { url: BASE + '?' + q }, sessionId);
  await sleep(700);
  // 断言：目标页是 .on、标题/眉标命中、设备尺寸等于请求
  const ev = await send('Runtime.evaluate', {
    expression: `(()=>{
      const p=[...document.querySelectorAll('#pages .page')].find(x=>x.classList.contains('on'));
      const txt=p?p.innerText:'';
      const d=document.querySelector('#device').getBoundingClientRect();
      return JSON.stringify({idx:p?+p.dataset.page:-1, txt:txt.slice(0,400), w:Math.round(d.width), h:Math.round(d.height)});
    })()`,
    returnByValue: true,
  }, sessionId);
  const info = JSON.parse(ev.result.result.value);

  // q0）截图模式（clean=1）设备铺满视口：实拍尺寸必须等于请求尺寸，
  //     否则「出图对了」只是幻觉（headless 最小窗宽 500 就是这么坑过一次）。
  if (q.includes('clean=1') && (info.w !== w || info.h !== h)) {
    throw new Error(file + ': 实拍 ' + info.w + '×' + info.h + ' ≠ 请求 ' + w + '×' + h);
  }

  // q1）画廊：没有 page 参数，断言必须在 page==null 之外单独走
  if (file.startsWith('gallery')) {
    const g = await send('Runtime.evaluate', { expression: "JSON.stringify({on:document.querySelector('#gallery').classList.contains('on'), cards:document.querySelectorAll('#gGrid .g-card').length, stage:getComputedStyle(document.querySelector('#stage')).display, shots:[...document.querySelectorAll('#gGrid .g-card')].map(c=>c.querySelectorAll('.page.on').length)})", returnByValue: true }, sessionId);
    const gi = JSON.parse(g.result.result.value);
    if (!gi.on) throw new Error(file + ': 画廊未打开');
    if (gi.cards !== 9) throw new Error(file + ': 画廊卡片数 ' + gi.cards + '，期望 9');
    if (gi.stage === 'flex') throw new Error(file + ': 画廊打开时舞台未隐藏');
    const bad = gi.shots.filter(n => n !== 1).length;
    if (bad) throw new Error(file + ': 有 ' + bad + ' 张缩略卡不是恰好显示 1 页');
    info.w = gi.cards; info.h = 0;
  } else if (page != null) {
    if (info.idx !== page) throw new Error(file + ': 拍到的页是 ' + info.idx + '，期望 ' + page);
    const [a, b] = EXPECT[page] || [];
    for (const s of [a, b]) if (s && !info.txt.includes(s)) throw new Error(file + ': 页内缺少 "' + s + '"');
    if (page === 8) {
      const t = info.txt;
      const titles = ['还差一步','准备就绪','正在准备','界面设置已完成'];
      if (!titles.some(x => t.includes(x))) throw new Error(file + ': 完成页标题不是任何一态');
      const expect = { preparing:'正在解压运行环境', failed:'运行环境准备未完成', lite:'精简包', ready:'运行环境已就绪' }[rtOf(file)];
      if (expect && !t.includes(expect)) throw new Error(file + ': 状态块与 ' + rtOf(file) + ' 不符，缺少 "' + expect + '"');
    }
  }
  const r = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
  if (!r.result || !r.result.data) throw new Error(file + ': captureScreenshot 无数据');
  const buf = Buffer.from(r.result.data, 'base64');
  fs.writeFileSync(OUT + '/' + file, buf);

  // q2）PNG 实际像素必须等于 CSS 尺寸 × DPR：只看设备 CSS 尺寸会漏掉窗口钳制
  //     （实测 headless 曾把 320 拍成 321，直接把 642×1280 当 640×1280 用）。
  const pw = buf.readUInt32BE(16), ph = buf.readUInt32BE(20);
  if (pw !== w * dsf || ph !== h * dsf) throw new Error(file + ': PNG ' + pw + '×' + ph + ' ≠ ' + (w * dsf) + '×' + (h * dsf));

  return fs.statSync(OUT + '/' + file).size + ' B · ' + pw + '×' + ph;
}

const slugs = ['welcome','lang','orient','theme','accent','screen','mode','caps','done'];
const jobs = [];
for (let p = 0; p < 9; p++) {
  jobs.push([ 'p' + p + '-' + slugs[p] + '-dark.png', 390, 844, 2, 'clean=1&theme=dark&page=' + p, p ]);
  jobs.push([ 'p' + p + '-' + slugs[p] + '-light.png', 390, 844, 2, 'clean=1&theme=light&page=' + p, p ]);
  jobs.push([ 'p' + p + '-' + slugs[p] + '-tablet.png', 820, 900, 2, 'clean=1&theme=dark&form=tablet&page=' + p, p ]);
  jobs.push([ 'p' + p + '-' + slugs[p] + '-small.png', 320, 640, 2, 'clean=1&theme=dark&page=' + p, p ]);
}
for (const rt of ['preparing','ready','failed','lite']) jobs.push([ 'state-' + rt + '.png', 390, 844, 2, 'clean=1&theme=dark&page=8&rt=' + rt, 8 ]);
jobs.push([ 'p7-caps-landscape.png', 844, 390, 2, 'clean=1&theme=dark&page=7', 7 ]);
jobs.push([ 'gallery-phone-dark.png', 1500, 1700, 1, 'gallery=1&theme=dark&form=phone', null ]);
jobs.push([ 'editor-dark.png', 1440, 1000, 1, 'page=7&theme=dark', null ]);

const ONLY = process.argv[2] || '';
let ok = 0; const fails = [];
for (const [file, w, h, dsf, q, page] of jobs) {
  if (ONLY && !file.includes(ONLY)) continue;
  try { const info = await shot(file, w, h, dsf, q, page); ok++; console.log('OK  ' + file + '  ' + info); }
  catch (e) { fails.push(String(e.message || e)); console.log('ERR ' + (e.message || e)); }
}
if (fails.length) {
  console.log('FAILED ' + fails.length + '/' + jobs.length);
  for (const fmsg of fails) console.log('  · ' + fmsg);
} else {
  console.log('done ' + ok + '/' + jobs.length + ' (全部断言通过)');
}
await send('Browser.close');
process.exit(fails.length ? 1 : 0);

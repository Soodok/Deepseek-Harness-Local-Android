import { spawn } from 'node:child_process';
import fs from 'node:fs';
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const OUT = 'E:/Deepeek harness/dsh-android/docs/ui-preview/shots/onboarding-redesign';
const BASE = 'file:///E:/Deepeek%20harness/dsh-android/docs/ui-preview/2026-11-12-onboarding-redesign.html';
const PORT = 9333;
fs.mkdirSync(OUT, { recursive: true });
const sleep = ms => new Promise(r => setTimeout(r, ms));
const chrome = spawn(CHROME, ['--headless=new','--disable-gpu','--hide-scrollbars','--no-first-run','--no-default-browser-check','--remote-debugging-port=' + PORT,'--user-data-dir=C:/Users/Qt/AppData/Local/Temp/dsh-shoot-profile','about:blank'], { stdio: 'ignore' });
let ver = null;
for (let i = 0; i < 60 && !ver; i++) {
  try { ver = await fetch('http://127.0.0.1:' + PORT + '/json/version').then(r => r.json()); } catch { await sleep(250); }
}
if (!ver) { console.error('chrome not ready'); process.exit(1); }
const ws = new WebSocket(ver.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let seq = 0; const pend = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params, sessionId) => new Promise(res => { const id = ++seq; pend.set(id, res); ws.send(JSON.stringify({ id: id, method: method, params: params || {}, sessionId: sessionId })); });
const t = await send('Target.createTarget', { url: 'about:blank' });
const targetId = t.result.targetId;
const a = await send('Target.attachToTarget', { targetId: targetId, flatten: true });
const sessionId = a.result.sessionId;
await send('Page.enable', {}, sessionId);
await send('Runtime.enable', {}, sessionId);
const slugs = ['welcome','lang','orient','theme','accent','screen','mode','caps','done'];
const shots = [];
for (let p = 0; p < 9; p++) {
  shots.push({ file: 'p' + p + '-' + slugs[p] + '-dark.png', w: 390, h: 844, dsf: 2, q: 'clean=1&theme=dark&page=' + p });
  shots.push({ file: 'p' + p + '-' + slugs[p] + '-light.png', w: 390, h: 844, dsf: 2, q: 'clean=1&theme=light&page=' + p });
}
for (let p = 0; p < 9; p++) shots.push({ file: 'p' + p + '-' + slugs[p] + '-tablet.png', w: 820, h: 900, dsf: 2, q: 'clean=1&theme=dark&form=tablet&page=' + p });
for (const rt of ['preparing','ready','failed','lite']) shots.push({ file: 'state-' + rt + '.png', w: 390, h: 844, dsf: 2, q: 'clean=1&theme=dark&page=8&rt=' + rt });
shots.push({ file: 'gallery-phone-dark.png', w: 1500, h: 1500, dsf: 1, q: 'gallery=1&theme=dark&form=phone' });
const failures = [];
for (const s of shots) {
  await send('Emulation.setDeviceMetricsOverride', { width: s.w, height: s.h, deviceScaleFactor: s.dsf, mobile: s.w < 1000 }, sessionId);
  await send('Page.navigate', { url: BASE + '?' + s.q }, sessionId);
  await sleep(650);
  const r = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
  if (!r.result || !r.result.data) { failures.push(s.file); continue; }
  fs.writeFileSync(OUT + '/' + s.file, Buffer.from(r.result.data, 'base64'));
  console.log(s.file, fs.statSync(OUT + '/' + s.file).size);
}
console.log('done', shots.length - failures.length, 'of', shots.length, failures.length ? 'FAILED ' + failures.join(',') : '');
await send('Browser.close');

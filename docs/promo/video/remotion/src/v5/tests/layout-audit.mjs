import {bundle} from '@remotion/bundler';
import {openBrowser, renderStill, selectComposition} from '@remotion/renderer';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile, mkdir, writeFile, stat} from 'node:fs/promises';
import {dirname, extname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const output = resolve(root, 'out/v5-layout-audit');
await mkdir(output, {recursive: true});
const bundled = await bundle({entryPoint: resolve(root, 'src/index.tsx'), outDir: resolve(output, 'bundle')});
const types = {'.html': 'text/html', '.js': 'application/javascript', '.json': 'application/json', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.ttc': 'font/collection'};
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost');
    let target = resolve(bundled, `.${decodeURIComponent(url.pathname)}`);
    if (!(await stat(target)).isFile()) target = resolve(target, 'index.html');
    response.writeHead(200, {'Content-Type': types[extname(target)] ?? 'application/octet-stream'});
    response.end(await readFile(target));
  } catch { response.writeHead(404); response.end(); }
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const serveUrl = `http://127.0.0.1:${server.address().port}`;
const require = createRequire(import.meta.url);
const rendererPath = dirname(require.resolve('@remotion/renderer'));
const {seekToFrame} = require(resolve(rendererPath, 'seek-to-frame.js'));
const browser = await openBrowser('chrome', {logLevel: 'error'});
const results = [];
try {
  for (const id of ['DSHMobilePortrait-v5', 'DSHMobileLandscape-v5']) {
    const composition = await selectComposition({serveUrl, id, puppeteerInstance: browser, logLevel: 'error'});
    const newPage = browser.newPage.bind(browser);
    let page;
    let close;
    browser.newPage = async (options) => {
      page = await newPage(options);
      close = page.close.bind(page);
      page.close = async () => {};
      return page;
    };
    await renderStill({serveUrl, composition, frame: 30, output: resolve(output, `${id}.png`), puppeteerInstance: browser, logLevel: 'error'});
    browser.newPage = newPage;
    const failures = [];
    const pickupBoxes = {};
    // Sample every tenth frame, plus cut/arrival extrema: check live projected text rectangles.
    const samples = new Set(Array.from({length: 312}, (_, i) => i * 10));
    const boundaries = [0, 180, 600, 990, 1260, 1620, 1830, 2160, 2490, 2850, 3120];
    for (const start of boundaries.slice(0, -1)) for (const d of [0, 9, 16, 30, 50, 80]) samples.add(start + d);
    for (const frame of [...samples].sort((a, b) => a - b)) {
      await seekToFrame({frame, page, composition: id, timeoutInMilliseconds: 30000, indent: false, logLevel: 'error', attempt: 0});
      const audit = await page.evaluate(({width, height}) => {
        const errors = [];
        const words = [];
        const canvas = document.createElement('canvas');
        const context = canvas.getContext('2d');
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) {
          const text = node.textContent.trim();
          const element = node.parentElement;
          if (!text || !element?.closest('[data-v5-text], [data-v5-pickup]')) continue;
          let visible = true;
          for (let p = element; p; p = p.parentElement) {
            const css = getComputedStyle(p);
            if (css.display === 'none' || css.visibility === 'hidden' || Number(css.opacity) < .1) visible = false;
          }
          if (!visible) continue;
          const range = document.createRange();
          range.selectNodeContents(node);
          for (const rect of range.getClientRects()) {
            const css = getComputedStyle(element);
            context.font = `${css.fontWeight} ${css.fontSize} ${css.fontFamily}`;
            const metrics = context.measureText(text);
            const em = metrics.fontBoundingBoxAscent + metrics.fontBoundingBoxDescent;
            const scaleY = em > 0 ? rect.height / em : 1;
            const topInset = (metrics.fontBoundingBoxAscent - metrics.actualBoundingBoxAscent) * scaleY;
            const bottomInset = (metrics.fontBoundingBoxDescent - metrics.actualBoundingBoxDescent) * scaleY;
            // DOM ranges include the font's unused ascender/descender area; compare painted glyphs.
            const box = {left: rect.left, top: rect.top + topInset, right: rect.right, bottom: rect.bottom - bottomInset, width: rect.width, height: rect.height - topInset - bottomInset};
            if (box.width < 1 || box.height < 1) continue;
            if (box.left < 40 || box.top < 35 || box.right > width - 35 || box.bottom > height - 35) errors.push({type: 'outside-frame', text, box});
            for (let p = element; p; p = p.parentElement) {
              const css = getComputedStyle(p);
              if (!['hidden', 'clip'].includes(css.overflow)) continue;
              const clip = p.getBoundingClientRect();
              if (box.left < clip.left - 1 || box.right > clip.right + 1 || box.top < clip.top - 1 || box.bottom > clip.bottom + 1) {
                errors.push({type: 'clipped-text', text, box}); break;
              }
            }
            words.push({text, element, box});
          }
        }
        for (let i = 0; i < words.length; i++) for (let j = i + 1; j < words.length; j++) {
          const a = words[i], b = words[j];
          if (a.element === b.element) continue;
          const x = Math.min(a.box.right, b.box.right) - Math.max(a.box.left, b.box.left);
          const y = Math.min(a.box.bottom, b.box.bottom) - Math.max(a.box.top, b.box.top);
          if (x > 3 && y > 4) errors.push({type: 'text-overlap', a: a.text, b: b.text, width: x, height: y});
        }
        const pickups = [...document.querySelectorAll('[data-v5-pickup]')].map((e) => {
          const r = e.getBoundingClientRect();
          return {hint: e.getAttribute('data-v5-pickup'), x: r.left, y: r.top, width: r.width, height: r.height};
        });
        for (const img of document.querySelectorAll('img')) if (!img.complete || !img.naturalWidth) errors.push({type: 'missing-image', src: img.src});
        return {errors, pickups, fonts: document.fonts.status};
      }, {width: composition.width, height: composition.height});
      if (audit.errors.length) failures.push({frame, errors: audit.errors});
      if ([1150, 1450, 2050].includes(frame)) for (const p of audit.pickups) pickupBoxes[p.hint] = p;
      if (frame % 600 === 0) console.log(id, 'frame', frame, 'failures', failures.length);
    }
    results.push({composition: id, samples: samples.size, failingFrames: failures.length, pickupBoxes, failures});
    console.log(id, 'done:', samples.size, 'sampled frames;', failures.length, 'failing frames');
    await close();
  }
} finally {
  await browser.close({silent: true});
  await new Promise((done) => server.close(done));
}
await writeFile(resolve(output, 'results.json'), JSON.stringify(results, null, 2));
if (results.some((r) => r.failingFrames > 0)) process.exitCode = 1;

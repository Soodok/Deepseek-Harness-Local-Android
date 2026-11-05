#!/usr/bin/env node
/**
 * WebView 语法门槛探针（v1.6.8）。
 *
 * ## 它守的是什么
 * `AppGate.MIN_WEBVIEW_CHROME` 必须 >= 前端 bundle 真实的**语法**门槛。
 * 门槛定低了，低版本内核会「通过门禁 → 加载 bundle → 解析期 SyntaxError → 白屏」，
 * 用户连一句提示都看不到 —— 比直接拦下来更糟。这正是 v1.6.8 修掉的缺陷：
 * 旧值是 85（那是 API 层门槛），而 bundle 里有 `class static {}`（Chrome 94）。
 *
 * ## 怎么判
 * 用 acorn 按 ecmaVersion 逐级降版解析 bundle 的**入口 chunk**。
 * 入口 chunk 由 index.html 直接 <script type="module"> 或 modulepreload 加载，
 * 必然执行，所以它的门槛就是整个前端的门槛。
 *
 * ## 用法
 *   node scripts/probes/_test_webview_syntax_floor.mjs <runtime.zip>
 *   node scripts/probes/_test_webview_syntax_floor.mjs <dir-with-dist/>
 *
 * 无参数时自动找仓库里最新的 `_runtime_bak/*.zip` 或 `dist/*.apk` 内置 runtime。
 *
 * 退出码：0 = 门槛与常量一致；1 = 不一致或有 chunk 无法解析；2 = 环境/输入问题。
 *
 * ## 依赖
 * 需要 `acorn`。它不在本仓库依赖里（探针按需安装即可）：
 *   npm i --no-save acorn
 * 缺失时脚本直接退出 2 并给出安装命令，不会假装通过。
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
let acorn;
try {
  acorn = require('acorn');
} catch {
  console.error('需要 acorn：npm i --no-save acorn');
  process.exit(2);
}

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** ecmaVersion → 支持它的最低 Chrome 大版本（保守取值，只列我们关心的档位） */
const ES_TO_CHROME = {
  2015: 49,
  2016: 52,
  2017: 55,
  2018: 64,
  2019: 74,
  2020: 80,
  2021: 85,
  2022: 94,
  2023: 110,
  2024: 119,
  2025: 126,
};

/** 从 AppGate.kt 读常量，避免「文档与代码各写一份」 */
function readMinChrome() {
  const p = path.join(ROOT, 'app/src/main/java/app/dsh/mobile/WebViewDiagnostics.kt');
  const src = fs.readFileSync(p, 'utf8');
  const m = /const val MIN_CHROME = (\d+)/.exec(src);
  if (!m) {
    console.error('读不到 MIN_CHROME，源文件结构变了？' + p);
    process.exit(2);
  }
  return Number(m[1]);
}

/** 在 zip 里找前端 dist 目录的入口 chunk 名（从 index.html 解析） */
function entriesFromIndexHtml(html) {
  const out = [];
  for (const m of html.matchAll(/<script[^>]*type="module"[^>]*src="([^"]+)"/g)) out.push(m[1]);
  for (const m of html.matchAll(/<link[^>]*rel="modulepreload"[^>]*href="([^"]+)"/g)) out.push(m[1]);
  return out.map((s) => s.replace(/^\.?\//, ''));
}

function minEsOf(src) {
  // ⚠️ 必须**升序**遍历并返回第一个成功的版本 —— 那才是「最低能解析的版本」。
  // 降序会返回最高档（2025），得出「需要 Chrome 126」的假结论。
  const versions = Object.keys(ES_TO_CHROME).map(Number).sort((a, b) => a - b);
  for (const v of versions) {
    try {
      acorn.parse(src, { ecmaVersion: v, sourceType: 'module', allowHashBang: true });
      return v;
    } catch {
      /* 这个档位不够，升一档再试 */
    }
  }
  return 0;
}

async function main() {
  // 输入：命令行 → 环境变量 → 自动探测
  let target = process.argv[2];
  if (!target) {
    const candidates = [];
    for (const dir of ['_runtime_bak', 'dist']) {
      const abs = path.join(ROOT, dir);
      if (!fs.existsSync(abs)) continue;
      for (const f of fs.readdirSync(abs)) {
        if (f.endsWith('.zip') || f.endsWith('.apk')) candidates.push(path.join(abs, f));
      }
    }
    candidates.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
    target = candidates[0];
  }
  if (!target || !fs.existsSync(target)) {
    console.error('找不到可分析的 runtime zip / apk，请显式传路径');
    process.exit(2);
  }



  // 用 python 的 zipfile 读（Node 侧没有零依赖的 zip 读取；python3 在开发机与 CI 都有）
  const py = `
import sys, zipfile, io, json
p = sys.argv[1]
z = zipfile.ZipFile(p)
# apk 里 runtime 是嵌套 zip
if any(n == 'assets/runtime.zip' for n in z.namelist()):
    z = zipfile.ZipFile(io.BytesIO(z.read('assets/runtime.zip')))
want_html = 'lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html'
html = z.read(want_html).decode('utf-8')
out = {'html': html, 'files': {}}
for n in z.namelist():
    if n.startswith('lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/') and n.endswith('.js'):
        rel = n.split('dist/')[1]
        if rel.count('/') == 0 or rel.startswith('assets/') and rel.count('/') == 1:
            out['files'][rel] = z.read(n).decode('utf-8', 'replace')
print(json.dumps(out))
`;
  let bundle;
  try {
    const raw = execFileSync('python', ['-c', py, target], { maxBuffer: 1024 * 1024 * 256 });
    bundle = JSON.parse(raw.toString('utf8'));
  } catch (e) {
    console.error('解包失败：' + e.message);
    process.exit(2);
  }

  const entries = entriesFromIndexHtml(bundle.html);
  if (!entries.length) {
    console.error('index.html 里没找到 module 入口，前端形态变了？');
    process.exit(2);
  }

  const minChrome = readMinChrome();
  console.log('runtime: ' + target);
  console.log('MIN_CHROME (code): ' + minChrome);
  console.log('entry chunks: ' + entries.join(', '));

  let worst = 0;
  let failed = false;
  for (const rel of entries) {
    const src = bundle.files[rel];
    if (src === undefined) {
      console.error('  ✗ ' + rel + ' 不在 bundle 里（index.html 引用了但没打包？）');
      failed = true;
      continue;
    }
    const es = minEsOf(src);
    const chrome = ES_TO_CHROME[es] ?? 0;
    console.log('  ' + rel.padEnd(34) + ' es' + es + ' → Chrome ≥ ' + chrome);
    if (es === 0) failed = true;
    worst = Math.max(worst, chrome);
  }

  if (failed) {
    console.error('FAILED：有入口 chunk 在全部档位都解析失败，门槛无法判定');
    process.exit(1);
  }

  console.log('required Chrome (measured): ' + worst);
  if (worst > minChrome) {
    console.error(
      'FAILED：代码里的 MIN_CHROME=' + minChrome + ' 低于实测门槛 ' + worst +
      '，低版本内核会通过门禁后白屏。请把 MIN_CHROME 提到 ' + worst + '。',
    );
    process.exit(1);
  }
  console.log('PASS：MIN_CHROME=' + minChrome + ' ≥ 实测门槛 ' + worst);
}

main();

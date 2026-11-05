#!/usr/bin/env node
/**
 * WebView 语法门槛探针（v1.6.9）。
 *
 * ## 它守的是什么
 * `WebViewDiagnostics.MIN_CHROME` 必须 >= 前端 bundle 真实的**语法**门槛。
 * 门槛定低了，低版本内核会「通过门禁 → 加载 bundle → 解析期 SyntaxError → 白屏」，
 * 用户连一句提示都看不到 —— 比直接拦下来更糟。
 *
 * ## 为什么不再用 acorn 的 ecmaVersion 推断
 * 上一版按 `ecmaVersion` 逐级降版解析，把「能按 es2022 解析」当成「需要 Chrome 94」。
 * **这个推断是错的**：ES2022 是若干特性的合集，它们在 Chrome 的落地时间差很多：
 *   · `?.` / `??`                   → Chrome 80
 *   · `#privateField`（读/写）       → Chrome 74 / 84
 *   · `#privateMethod`              → Chrome 84
 *   · 逻辑赋值 `??=` / `||=` / `&&=` → Chrome 85
 *   · `static {}` 静态初始化块       → **Chrome 94**  ← 唯一真正卡 94 的
 * 实测原始 bundle「61/61 通过 es2022」不等于「需要 94」，它是上述各项的混合。
 * 现在改成**直接扫 AST 节点**，对每个关键特性查一张明确的 Chrome 版本表再取最大值。
 * 这样既不会高估（把 84 的私有方法算成 94），也不会低估。
 *
 * ## 转译是前置条件，不是可选项
 * 上游 `dsh-web-frontend` 产物含 `static {}`，门槛 94。构建期
 * `scripts/patch-webview-syntax-floor.py` 用 esbuild `--target=chrome85` 转译掉它，
 * 门槛回落到 85（与 polyfill 的 API 门槛对齐）。本探针若在有 `static {}` 的
 * 产物上运行就会判 FAILED —— 那正是「转译步骤被漏掉」的报警。
 *
 * ## 用法
 *   node scripts/probes/_test_webview_syntax_floor.mjs <runtime.zip>
 *   node scripts/probes/_test_webview_syntax_floor.mjs <apk>
 * 无参数时自动找 `_runtime_bak/*.zip` 或 `dist/*.apk` 里最新的一个。
 *
 * 退出码：0 = 门槛与常量一致；1 = 不一致或解析失败；2 = 环境/输入问题。
 *
 * ## 依赖
 * 需要 `acorn`（仓库根无 package.json）：
 *   npm i --no-save acorn
 * 缺失时退出 2 并给出安装命令，不假装通过。
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

/**
 * 关键语法特性 → 所需 Chrome 大版本。
 * 只列真正会卡门槛的项（80 以下的不用管，门槛不会低于 80）。
 * 依据：MDN / caniuse 的「Chrome 起始版本」列。
 */
const FEATURE_CHROME = {
  staticBlock: 94,          // class static {} 静态初始化块
  topLevelAwait: 89,        // 模块顶层 await
  logicAssign: 85,          // ??= ||= &&=
  privateMethod: 84,        // #privateMethod()
  privateFieldWrite: 84,    // this.#x = v（只读是 74）
  numericSeparator: 75,     // 1_000_000
  privateFieldRead: 74,     // this.#x
  classField: 72,           // 公有实例字段
  staticPublicField: 72,    // 静态公有字段
  bigintLiteral: 67,        // 1n
};

function scanFeatures(ast) {
  const seen = new Set();

  function walk(node, inFn) {
    if (!node || typeof node !== 'object') return;

    if (node.type === 'StaticBlock') seen.add('staticBlock');

    if (node.type === 'MethodDefinition' && node.key && node.key.type === 'PrivateIdentifier') {
      seen.add('privateMethod');
    }
    if (node.type === 'PropertyDefinition') {
      const isPrivate = node.key && node.key.type === 'PrivateIdentifier';
      if (isPrivate) seen.add('privateFieldRead');
      if (node.static) seen.add('staticPublicField');
      else if (!isPrivate) seen.add('classField');
    }
    if (
      node.type === 'AssignmentExpression' &&
      node.left &&
      (node.left.type === 'MemberExpression' || node.left.type === 'ChainExpression')
    ) {
      const mem = node.left.type === 'ChainExpression' ? node.left.expression : node.left;
      if (mem && mem.property && mem.property.type === 'PrivateIdentifier') {
        seen.add('privateFieldWrite');
      }
      if (node.operator === '??=' || node.operator === '||=' || node.operator === '&&=') {
        seen.add('logicAssign');
      }
    }
    // 顶层 await：只在模块最外层算（进了任何函数就不是 top-level 了）
    if (node.type === 'AwaitExpression' && !inFn) seen.add('topLevelAwait');
    if (node.type === 'Literal') {
      if (node.bigint !== undefined) seen.add('bigintLiteral');
      if (typeof node.raw === 'string' && /^[0-9][0-9_]*$/.test(node.raw) && node.raw.includes('_')) {
        seen.add('numericSeparator');
      }
    }

    const childInFn =
      inFn ||
      node.type === 'FunctionDeclaration' ||
      node.type === 'FunctionExpression' ||
      node.type === 'ArrowFunctionExpression';

    for (const k in node) {
      const v = node[k];
      if (Array.isArray(v)) {
        for (const c of v) if (c && typeof c === 'object') walk(c, childInFn);
      } else if (v && typeof v === 'object' && v.type) {
        walk(v, childInFn);
      }
    }
  }

  walk(ast, false);
  return seen;
}

/** 从 WebViewDiagnostics.kt 读常量，避免「文档与代码各写一份」 */
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

/** 在 index.html 里找必然执行的分包 */
function entriesFromIndexHtml(html) {
  const out = [];
  for (const m of html.matchAll(/<script[^>]*type="module"[^>]*src="([^"]+)"/g)) out.push(m[1]);
  for (const m of html.matchAll(/<link[^>]*rel="modulepreload"[^>]*href="([^"]+)"/g)) out.push(m[1]);
  return out.map((s) => s.replace(/^\.?\//, ''));
}

async function main() {
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

  // 用 python 的 zipfile 读（Node 侧没有零依赖的 zip 读取）
  const py = `
import sys, zipfile, io, json
p = sys.argv[1]
z = zipfile.ZipFile(p)
if any(n == 'assets/runtime.zip' for n in z.namelist()):
    z = zipfile.ZipFile(io.BytesIO(z.read('assets/runtime.zip')))
want_html = 'lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html'
html = z.read(want_html).decode('utf-8')
out = {'html': html, 'files': {}}
for n in z.namelist():
    if n.startswith('lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/') and n.endswith('.js'):
        rel = n.split('dist/')[1]
        if rel.count('/') == 0 or (rel.startswith('assets/') and rel.count('/') == 1):
            out['files'][rel] = z.read(n).decode('utf-8', 'replace')
print(json.dumps(out))
`;
  let bundle;
  try {
    const raw = execFileSync('python', ['-c', py, target], { maxBuffer: 1024 * 1024 * 512 });
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
      console.error('  x ' + rel + ' 不在 bundle 里（index.html 引用了但没打包？）');
      failed = true;
      continue;
    }
    let ast;
    try {
      ast = acorn.parse(src, { ecmaVersion: 'latest', sourceType: 'module', allowHashBang: true });
    } catch (e) {
      console.error('  x ' + rel + ' 解析失败: ' + e.message);
      failed = true;
      continue;
    }
    const feats = scanFeatures(ast);
    let need = 0;
    const detail = [];
    for (const f of feats) {
      const c = FEATURE_CHROME[f] ?? 0;
      if (c > need) need = c;
      if (c >= 80) detail.push(f + '=' + c);
    }
    console.log(
      '  ' + rel.padEnd(32) + ' -> Chrome >= ' + need +
        (detail.length ? '   [' + detail.join(', ') + ']' : '')
    );
    worst = Math.max(worst, need);
  }

  if (failed) {
    console.error('FAILED：有入口 chunk 无法解析，门槛无法判定');
    process.exit(1);
  }

  console.log('required Chrome (measured): ' + worst);
  if (worst > minChrome) {
    console.error(
      '\nFAILED：实测语法门槛 ' + worst + ' > MIN_CHROME=' + minChrome + '。\n' +
        '  修法：\n' +
        '  (1) 优先 -- 让构建期转译生效：\n' +
        '      python3 scripts/patch-webview-syntax-floor.py <runtime>\n' +
        '      它用 esbuild --target=chrome85 把 static {} 等降级掉。\n' +
        '  (2) 若确实无法转译，把 MIN_CHROME 提到 ' + worst + '（放弃这批老设备）。\n' +
        '  低版本内核会「通过门禁 -> 白屏」，比直接拦住更糟。'
    );
    process.exit(1);
  }
  console.log('PASS：MIN_CHROME=' + minChrome + ' >= 实测语法门槛 ' + worst);
}

main();

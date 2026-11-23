#!/usr/bin/env node
/**
 * 重新抓取 Material 3 官方文档到 docs/md3/。
 *
 * 用途：官方文档更新后（新组件、规范修订）重新同步，
 * 保证仓库里的「强制依据」不会过期。
 *
 * 用法：node tools/fetch-md3-docs.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.dirname(path.dirname(new URL(import.meta.url).pathname));
const OUT = path.join(ROOT, 'docs', 'md3');
fs.mkdirSync(OUT, { recursive: true });

const MDC = 'https://raw.githubusercontent.com/material-components/material-components-android/master/docs/';

const THEMING = [
  'getting-started.md', 'theming.md', 'theming/Color.md',
  'theming/Typography.md', 'theming/Shape.md', 'theming/Motion.md',
];

const COMPONENTS = [
  'TopAppBar', 'Card', 'List', 'Divider', 'Switch', 'RadioButton', 'Checkbox',
  'Slider', 'TextField', 'Button', 'ButtonGroup', 'Chip', 'ToggleButtonGroup',
  'Dialog', 'Menu', 'Snackbar', 'ProgressIndicator', 'LoadingIndicator',
  'IconButton', 'BadgeDrawable', 'BottomSheet', 'NavigationDrawer',
  'NavigationRail', 'Tabs', 'Search', 'SplitButton', 'FloatingActionButton',
  'FloatingActionButtonMenu', 'Tooltip', 'MaterialTextView', 'ImageList',
  'SideSheet', 'Carousel', 'DatePicker', 'TimePicker',
];

async function get(url, tries = 4) {
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(url, { headers: { 'User-Agent': 'dsh-docs' } });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return await r.text();
    } catch (e) {
      if (i === tries - 1) throw e;
      await new Promise((s) => setTimeout(s, 800 * (i + 1)));
    }
  }
}

let ok = 0;
const failed = [];

for (const f of THEMING) {
  const name = 'mdc-' + f.replace(/\//g, '-');
  try {
    fs.writeFileSync(path.join(OUT, name), `SOURCE: ${MDC}${f}\n\n` + (await get(MDC + f)));
    ok++;
  } catch (e) {
    failed.push(`${f}: ${e.message}`);
  }
}

for (const n of COMPONENTS) {
  try {
    fs.writeFileSync(path.join(OUT, n + '.md'), `SOURCE: ${MDC}components/${n}.md\n\n` + (await get(`${MDC}components/${n}.md`)));
    ok++;
  } catch (e) {
    failed.push(`${n}: ${e.message}`);
  }
}

console.log(`同步完成：${ok} 个文件 → docs/md3/`);
if (failed.length) console.log('失败：\n  ' + failed.join('\n  '));

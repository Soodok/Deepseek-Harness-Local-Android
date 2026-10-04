#!/usr/bin/env python3
"""为旧 WebView 注入缺失的现代 Web API polyfill。

## 为什么需要

dsh 前端（含 pdf.js / Excel 预览等 7MB 级客户端插件）使用了大量较新的 Web API。
国产 ROM（华为/荣耀/小米等）的 WebView 更新依赖厂商应用市场，常停留在较旧版本，
缺失这些 API 时前端 JS 直接抛 TypeError → WebUI 白屏或功能不可用
（典型报错：`AbortSignal.any is not a function`，华为 Mate40 实测）。

## 引擎实际需要的 API 与所需 Chrome 版本（全量扫描 runtime 得到）

    URL.parse()                    Chrome 126   12 次（pdf.js）
    Promise.withResolvers()        Chrome 119   68 次（10 个客户端插件）
    AbortSignal.any()              Chrome 116   28 次（9 个客户端插件）  ← 华为 Mate40 实测报错
    Array#toSorted/toReversed      Chrome 110    4 次
    structuredClone()              Chrome  98    6 次
    Array#findLast/findLastIndex   Chrome  97   15 次
    Object.hasOwn()                Chrome  93   62 次
    Element#replaceChildren()      Chrome  86   14 次
    String#replaceAll()            Chrome  85   62 次

→ 引擎实际门槛是 **Chrome 126**；本 polyfill 补齐全部，使旧 WebView 也能运行。

## 注入方式

在 dist/index.html 的第一个 `<script type="module">` 之前插入**同步** polyfill
（必须同步：任何模块代码执行前就要就位）。

幂等：已有旧版本会被替换为当前版本（便于升级 polyfill）。
"""
import os
import sys
import re

MARK_PREFIX = '<!-- [dsh-android] legacy-webview polyfill v'

POLYFILL = r'''<!-- [dsh-android] legacy-webview polyfill v3 -->
<script>
/* [dsh-android] polyfill for legacy WebView (Chrome <126).
   Covers the modern Web APIs used by the dsh frontend, pdf.js and Excel preview.
   Every guard is "if missing", so modern WebView is unaffected. */
(function () {
  'use strict';
  function def(obj, name, value) {
    try {
      Object.defineProperty(obj, name, { value: value, writable: true, enumerable: false, configurable: true });
    } catch (e) { try { obj[name] = value; } catch (e2) {} }
  }

  /* ---- Object.hasOwn (Chrome 93) ---- */
  if (!Object.hasOwn) {
    def(Object, 'hasOwn', function (o, k) {
      if (o == null) throw new TypeError('Cannot convert undefined or null to object');
      return Object.prototype.hasOwnProperty.call(Object(o), k);
    });
  }

  /* ---- String#replaceAll (Chrome 85) ---- */
  if (!String.prototype.replaceAll) {
    def(String.prototype, 'replaceAll', function (s, r) {
      if (s instanceof RegExp) {
        if (!s.global) throw new TypeError('replaceAll must be called with a global RegExp');
        return this.replace(s, r);
      }
      return this.split(s).join(r === undefined ? 'undefined' : String(r));
    });
  }

  /* ---- Array/String #at (Chrome 92) ---- */
  if (!Array.prototype.at) {
    def(Array.prototype, 'at', function (n) {
      n = Math.trunc(n) || 0;
      if (n < 0) n += this.length;
      if (n < 0 || n >= this.length) return undefined;
      return this[n];
    });
  }
  if (!String.prototype.at) {
    def(String.prototype, 'at', function (n) {
      n = Math.trunc(n) || 0;
      if (n < 0) n += this.length;
      if (n < 0 || n >= this.length) return undefined;
      return this[n];
    });
  }

  /* ---- Array#findLast / findLastIndex (Chrome 97) ---- */
  if (!Array.prototype.findLast) {
    def(Array.prototype, 'findLast', function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) {
        if (fn.call(thisArg, this[i], i, this)) return this[i];
      }
      return undefined;
    });
  }
  if (!Array.prototype.findLastIndex) {
    def(Array.prototype, 'findLastIndex', function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) {
        if (fn.call(thisArg, this[i], i, this)) return i;
      }
      return -1;
    });
  }

  /* ---- Element#replaceChildren (Chrome 86) ---- */
  if (typeof Element !== 'undefined' && !Element.prototype.replaceChildren) {
    def(Element.prototype, 'replaceChildren', function () {
      while (this.lastChild) this.removeChild(this.lastChild);
      if (arguments.length) this.append.apply(this, arguments);
    });
  }

  /* ---- structuredClone (Chrome 98) ---- */
  if (typeof structuredClone === 'undefined') {
    window.structuredClone = function (v) {
      try { return JSON.parse(JSON.stringify(v)); } catch (e) { return v; }
    };
  }

  /* ---- Array#toSorted / toReversed / toSpliced / with (Chrome 110) ---- */
  if (!Array.prototype.toSorted) {
    def(Array.prototype, 'toSorted', function (cmp) { return this.slice().sort(cmp); });
  }
  if (!Array.prototype.toReversed) {
    def(Array.prototype, 'toReversed', function () { return this.slice().reverse(); });
  }
  if (!Array.prototype.toSpliced) {
    def(Array.prototype, 'toSpliced', function () {
      var a = this.slice();
      var args = Array.prototype.slice.call(arguments, 0, 2);
      var items = Array.prototype.slice.call(arguments, 2);
      a.splice.apply(a, args.concat(items));
      return a;
    });
  }
  if (!Array.prototype.with) {
    def(Array.prototype, 'with', function (i, v) {
      var a = this.slice();
      i = Math.trunc(i) || 0;
      if (i < 0) i += a.length;
      if (i < 0 || i >= a.length) throw new RangeError('Invalid index');
      a[i] = v;
      return a;
    });
  }

  /* ---- AbortSignal.any (Chrome 116) ---- */
  /* pdf.js 与多个客户端插件用它合并多个 AbortSignal：
     返回的 signal 在任一输入中止时中止（已中止的立即生效）。 */
  if (typeof AbortSignal !== 'undefined' && !AbortSignal.any) {
    def(AbortSignal, 'any', function (signals) {
      var ctrl = new AbortController();
      function abort(reason) {
        try { ctrl.abort(reason); } catch (e) { try { ctrl.abort(); } catch (e2) {} }
      }
      if (!signals || !signals.length) return ctrl.signal;
      for (var i = 0; i < signals.length; i++) {
        if (signals[i].aborted) { abort(signals[i].reason); return ctrl.signal; }
      }
      for (var j = 0; j < signals.length; j++) {
        (function (s) {
          s.addEventListener('abort', function () { abort(s.reason); }, { once: true });
        })(signals[j]);
      }
      return ctrl.signal;
    });
  }

  /* ---- AbortSignal.timeout (Chrome 103) ---- */
  if (typeof AbortSignal !== 'undefined' && !AbortSignal.timeout) {
    def(AbortSignal, 'timeout', function (ms) {
      var ctrl = new AbortController();
      setTimeout(function () {
        try { ctrl.abort(new DOMException('Signal timed out.', 'TimeoutError')); }
        catch (e) { ctrl.abort(); }
      }, ms);
      return ctrl.signal;
    });
  }

  /* ---- Promise.withResolvers (Chrome 119) ---- */
  if (!Promise.withResolvers) {
    def(Promise, 'withResolvers', function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
      return { promise: promise, resolve: resolve, reject: reject };
    });
  }

  /* ---- URL.parse (Chrome 126) ---- */
  /* 与 new URL 的区别：解析失败返回 null 而不是抛异常 */
  if (typeof URL !== 'undefined' && !URL.parse) {
    def(URL, 'parse', function (url, base) {
      try { return new URL(url, base); } catch (e) { return null; }
    });
  }

  /* ---- Object.groupBy / Map.groupBy (Chrome 117) ---- */
  if (!Object.groupBy) {
    def(Object, 'groupBy', function (items, cb) {
      var out = Object.create(null);
      var i = 0;
      for (var it of items) {
        var k = cb(it, i++);
        (out[k] || (out[k] = [])).push(it);
      }
      return out;
    });
  }
  if (typeof Map !== 'undefined' && !Map.groupBy) {
    def(Map, 'groupBy', function (items, cb) {
      var out = new Map();
      var i = 0;
      for (var it of items) {
        var k = cb(it, i++);
        if (!out.has(k)) out.set(k, []);
        out.get(k).push(it);
      }
      return out;
    });
  }

  /* ---- Array.fromAsync (Chrome 121) ---- */
  if (!Array.fromAsync) {
    def(Array, 'fromAsync', function (iterable) {
      return (async function () {
        var out = [];
        for await (var v of iterable) out.push(v);
        return out;
      })();
    });
  }
})();
</script>
'''

ANCHOR_RE = re.compile(r'<script[^>]*type="module"[^>]*>')


def patch(root: str) -> None:
    html_path = os.path.join(root, 'lib', 'node_modules', '@deepseek-ai',
                             'dsh-web-frontend', 'dist', 'index.html')
    if not os.path.isfile(html_path):
        print(f'WARN: frontend index.html not found at {html_path}')
        return
    with open(html_path, 'r', encoding='utf-8') as f:
        s = f.read()

    # 幂等：先移除任何旧版本（含其后的注释行），再插入当前版本 —— 便于升级 polyfill。
    # 注意旧版注入会产生"注释 + 注释 + script"三段（脚本自带的 MARK 与内联注释重复），
    # 只删 script 块会留下孤立注释（实测 v2 注释残留）。
    s = re.sub(r'(?:<!-- \[dsh-android\] legacy-webview polyfill v\d+[^>]*-->\s*)+<script>.*?</script>\s*',
               '', s, flags=re.S)

    m = ANCHOR_RE.search(s)
    if not m:
        print('WARN: module script anchor not found; skipped')
        return
    s = s[:m.start()] + POLYFILL + '\n' + s[m.start():]
    with open(html_path, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    print('polyfill v3 injected:', html_path)


if __name__ == '__main__':
    # 目标目录只从 DSH_PATCH_TARGET 环境变量读取（不接受命令行路径）
    raw = os.environ.get('DSH_PATCH_TARGET', '')
    if not raw:
        sys.exit('rejected: DSH_PATCH_TARGET not set by caller')
    patch(os.path.realpath(raw))

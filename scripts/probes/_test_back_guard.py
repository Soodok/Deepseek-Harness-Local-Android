# -*- coding: utf-8 -*-
"""返回键兜底（back-guard）契约锁。

## 锁的是什么

`MainActivity.onBackPressed()` 的旧逻辑是「能后退就后退，不能就退出应用」，
而 WebUI 不往浏览器历史写记录（前端 bundle 里 pushState/popstate 出现 0 次），
所以在插件页 / 自动化任务页 / SSH 工作区这些**整页界面上按返回会直接退出应用**。

修法是在页面里注入 `back-guard.js`，由外壳 `askBackGuard()` 主动问
「有没有东西可关」。这个探针钉住三件事：

1. 注入点存在，且在 `onPageFinished` 里（每次导航后都要重新注入）；
2. `onBackPressed` 先问页面、后走历史、最后才退出 —— 顺序不能反；
3. `askBackGuard` 有超时兜底，且页面未注入时按「没处理」走原逻辑。

## 为什么断言「顺序」而不是只看有没有调用

只断言「调用了 askBackGuard」会漏掉最危险的一种改法：把它挪到
`webView.canGoBack()` 之后。那样返回键在浮层打开时仍会先 goBack()，
把用户带到一个不相干的位置。所以这里断言的是**可执行代码里 askBackGuard
出现在 canGoBack 之前**（注释要剥掉 —— 注释里也提到 canGoBack()）。
"""

import io
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MainActivity.kt")
GUARD = os.path.join(ROOT, "app/src/main/assets/js/back-guard.js")

checks = []


def check(name, cond):
    checks.append((name, bool(cond)))


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(text):
    """剥掉注释行。

    注释里提到 canGoBack() 会让位置判断失真 —— 这是本项目反复踩的坑：
    断言必须锚定真正被执行的代码形态，注释与复刻都不算。
    """
    out = []
    for line in text.splitlines():
        t = line.strip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        if "//" in line:
            line = line.split("//", 1)[0]
        out.append(line)
    return chr(10).join(out)


main_src = read(MAIN)
guard_src = read(GUARD)

# ── 1. 脚本与注入点 ────────────────────────────────────────────────
check("back-guard.js exists", os.path.isfile(GUARD))
check("guard defines __dshBackGuard", "__dshBackGuard" in guard_src)
check("guard exposes back()", "back: function" in guard_src)
check("guard exposes state()", "state: function" in guard_src)
check(
    "guard uses geometry not class names for overlay",
    "getBoundingClientRect" in guard_src and "COVER_RATIO" in guard_src,
)
check("guard handles data-global-panel", "data-global-panel" in guard_src)
check("guard dispatches Escape", "Escape" in guard_src)
check("guard injected from MainActivity", 'assets.open("js/back-guard.js")' in main_src)
check("guard injection logged on failure", "back guard inject failed" in main_src)

# ── 2. onBackPressed 的顺序 ─────────────────────────────────────────
i_back = main_src.find("override fun onBackPressed()")
check("onBackPressed exists", i_back > 0)
j = main_src.find("\n    override fun", i_back + 10)
body = main_src[i_back: j if j > 0 else len(main_src)]
code = code_only(body)

check("onBackPressed calls askBackGuard", "askBackGuard" in code)
i_ask = code.find("askBackGuard")
i_can = code.find("canGoBack")
check(
    "askBackGuard appears before canGoBack in onBackPressed",
    i_ask > 0 and i_can > i_ask,
)
check("onBackPressed keeps super.onBackPressed fallback", "super.onBackPressed()" in code)

# ── 3. askBackGuard 的兜底 ─────────────────────────────────────────
i_fn = main_src.find("private fun askBackGuard(")
check("askBackGuard defined", i_fn > 0)
k = main_src.find("\n    override fun", i_fn + 10)
fn = main_src[i_fn: k if k > 0 else len(main_src)]

check("askBackGuard has timeout fallback", "postDelayed" in fn and "decided" in fn)
check("askBackGuard guards empty url", "webView.url.isNullOrEmpty()" in fn)
check("askBackGuard calls onNotHandled on failure", "onNotHandled()" in fn)
check("askBackGuard removes pending timeout", "removeCallbacks" in fn)
check("askBackGuard invokes page back()", "__dshBackGuard" in fn)
check("askBackGuard evaluates JS asynchronously", "evaluateJavascript" in fn)

# ── 4. 整页面板不能硬改 display ────────────────────────────────────
check(
    "guard does not hard-hide global panel without a control",
    "不硬改" in guard_src,
)

# ── 输出 ────────────────────────────────────────────────────────────
passed = sum(1 for _, ok in checks if ok)
failed = [n for n, ok in checks if not ok]
for n, ok in checks:
    print(("  PASS  " if ok else "  FAIL  ") + n)
print()
print("%d/%d checks passed" % (passed, len(checks)))
if failed:
    print("FAILED:", ", ".join(failed))
    sys.exit(1)
print("All back-guard checks passed.")

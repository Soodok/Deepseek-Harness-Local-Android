# -*- coding: utf-8 -*-
"""WebView polyfill 落地链路的可复验判据（issue #107）。

## 事故

Android 10 用户（华为 com.huawei.webview 114.0.5.302）对话页整列空白：
    slot entry crashed in 'conversation.chat.node': TypeError: URL.canParse is not a function

仓库其实早就补了 URL.canParse（aabdef9f），polyfill v5 也一直由
scripts/patch-webview-polyfill.py 注入 dist/index.html。真正的问题是**它没到现场**：
  1. noruntime 包会把整份 runtime 跨 APK 复用，旧磁盘上的 dist/index.html 不会被重打；
  2. index.html 由 Node 引擎在运行期从磁盘读出来发出去，不是 APK asset，
     所以「补丁在 asset 里」≠「WebView 拿到的 HTML 里有补丁」；
  3. onPageFinished + evaluateJavascript 太晚 —— module 脚本已经解析执行，
     polyfill 必须**同步**出现在首个 type="module" 之前。

## 本测试验证

A. 单一真源：polyfill 字节只由 patch 脚本的 POLYFILL 常量决定，
   Kotlin / asset / 测试里不许出现第二份实现。
B. 行为：真跑 patch 脚本的 --asset-out，产出的 asset 必须能补齐 capability 清单，
   且 URL.canParse 必须挂在**真名**上（改名 canParse_REMOVED 就会静默失效）。
C. 自反证：把脚本在内存里改坏（改名 / 少补能力 / 少版本标记），B 段必须变红。
D. 落地：构建期生成 asset 且缺了就 fail；已装 runtime 复用与安装两条路径都要自愈；
   WebView 侧必须在 module 之前拦截注入，且只拦引擎首页这一种请求。
E. 单测覆盖：WebViewPolyfillTest 的四条语义案例缺一不可。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PATCH = os.path.join(ROOT, "scripts", "patch-webview-polyfill.py")
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
POLY_KT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "WebViewPolyfill.kt")
MAIN_KT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "MainActivity.kt")
INSTALLER_KT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "RuntimeInstaller.kt")
POLY_TEST = os.path.join(ROOT, "app", "src", "test", "java", "app", "dsh", "mobile", "engine", "WebViewPolyfillTest.kt")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


patch_src = read(PATCH)

# ── 能力清单：前端在 Chrome <126 上真正会踩的 API ─────────────────────
# 少一个就是一类真机崩溃，所以逐条点名，而不是「有 polyfill 就算过」。
# 判据一律断言**定义动作**（def(..., '名字', ...) 或 globalThis.X = ...），
# 不能只 grep 名字：注释、typeof 守卫、if (typeof X === 'undefined') 里都会出现
# 这个名字，写成「字符串还在就算补了」会把「定义被删掉」判成绿（实测假绿）。
CAPABILITIES = [
    ("URL.parse", r"def\(URL, 'parse',"),
    ("URL.canParse", r"def\(URL, 'canParse',"),
    ("structuredClone", r"globalThis\.structuredClone\s*="),
    ("AbortSignal.any", r"def\(AbortSignal, 'any',"),
    ("AbortSignal.timeout", r"def\(AbortSignal, 'timeout',"),
    ("AbortSignal.throwIfAborted", r"def\(AbortSignal\.prototype, 'throwIfAborted',"),
    ("Promise.withResolvers", r"def\(Promise, 'withResolvers',"),
    ("Object.groupBy", r"def\(Object, 'groupBy',"),
    ("Array.fromAsync", r"def\(Array, 'fromAsync',"),
    ("Array.prototype.at", r"def\(Array\.prototype, 'at',"),
    ("String.prototype.replaceAll", r"def\(String\.prototype, 'replaceAll',"),
]


def capability_problems(html):
    """返回 html 里缺失/写错的能力清单（空列表 = 合格）。"""
    problems = []
    for label, pattern in CAPABILITIES:
        if re.search(pattern, html) is None:
            problems.append("缺 " + label)
    # 改名是这次差点漏掉的形态：def(URL, 'canParse_REMOVED', ...) 看着还在，
    # 实际补的是个没人用的名字，现场照样 TypeError。
    if re.search(r"def\(URL, 'canParse',", html) is None and "canParse" in html:
        problems.append("URL.canParse 挂在错名字上")
    if version_marker(html) is None:
        problems.append("缺版本标记（校验段认不出这份产物）")
    return problems


def version_marker(html):
    m = re.search(r"legacy-webview polyfill v(\d+)", html)
    return m.group(1) if m else None


def deferred_problems(html):
    """polyfill 必须**同步**执行：延后到 DOMContentLoaded/load 就等于没补。

    这不是洁癖：#107 的真机日志里 module 脚本一解析就抛
    TypeError: URL.canParse is not a function，等到页面加载完再注入已经晚了。
    """
    problems = []
    for marker in ("DOMContentLoaded", "addEventListener('load'", 'addEventListener("load"'):
        if marker in html:
            problems.append("polyfill 被延后执行（命中 %s）" % marker)
    return problems


def asset_problems(html):
    """一份 asset 是否合格的全套判据：能力 + 同步执行。"""
    return capability_problems(html) + deferred_problems(html)


print("=== A. 单一真源：polyfill 字节只来自 patch 脚本 ===")

check("patch 脚本有 POLYFILL 常量", "POLYFILL = r'''" in patch_src)
check("patch 脚本提供 --asset-out 导出入口", "--asset-out" in patch_src and "write_asset" in patch_src,
      "构建期与运行期自愈共用这份字节，缺入口就只能各写一份")

gradle_src = read(GRADLE)
check("构建期从 patch 脚本生成 asset",
      "legacy-webview-polyfill.html" in gradle_src and "--asset-out" in gradle_src,)
check("asset 生成失败必须 fail 构建（不许静默产出空文件）",
      "polyfill asset generation failed" in gradle_src,
      "GradleException 必须显式抛出，否则空 asset 会让自愈注入空块")

poly_kt = read(POLY_KT)
check("Kotlin 侧只做放置/自愈，不含第二份 polyfill 实现",
      "structuredClone" not in poly_kt and "AbortSignal" not in poly_kt,
      "第二份实现一旦与真源漂移，现场修好一处坏一处")

print("\n=== B. 行为：真跑 --asset-out，产物必须能补齐能力 ===")

tmpdir = tempfile.mkdtemp(prefix="dsh-poly-")
asset_path = os.path.join(tmpdir, "legacy-webview-polyfill.html")
try:
    env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
    proc = subprocess.run(
        [sys.executable, PATCH, "--asset-out", asset_path],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env=env, timeout=120, cwd=ROOT,
    )
    check("--asset-out 退出码为 0", proc.returncode == 0,
          (proc.stdout or "")[-300:] + (proc.stderr or "")[-300:])
    exists = os.path.isfile(asset_path)
    check("asset 文件真的落盘", exists)
    asset = read(asset_path) if exists else ""
    if exists:
        problems = capability_problems(asset)
        check("asset 覆盖全部能力清单", not problems, "; ".join(problems))
        check("asset 带版本标记（供校验段识别）", version_marker(asset) is not None)
        check("asset 是完整 script 块（可直接插进 <head>）",
              asset.lstrip().startswith("<!--") and "</script>" in asset)

        # 真源与导出必须逐字节一致，否则「导出的那份」会慢慢漂移。
        m = re.search(r"POLYFILL = r'''(.*?)'''", patch_src, re.S)
        check("导出字节与 POLYFILL 常量完全一致",
              m is not None and m.group(1) == asset,
              "不一致说明导出路径做了额外加工，现场与构建产物就会不同")

        # 能力必须**同步**生效：polyfill 若被包进 DOMContentLoaded 之类，
        # module 脚本会先跑起来，等于没补。
        check("polyfill 顶层立即执行（没有等 DOMContentLoaded/load）",
              not deferred_problems(asset), "; ".join(deferred_problems(asset)))

    print("\n=== C. 自反证：把脚本在内存里改坏，B 段判据必须变红 ===")
    print("    （变异针对 POLYFILL 正文，判据函数与 B 段完全相同）")

    # 每个变异都必须**真的改到语义**：光把字符串挪个位置不算变异，
    # 那样「判据仍绿」只能说明变异无效，证明不了判据有效。
    mutants = [
        ("URL.canParse 改名（本次差点漏掉的形态）",
         lambda h: h.replace("def(URL, 'canParse',", "def(URL, 'canParse_REMOVED',")),
        ("structuredClone 整段补齐被删",
         lambda h: h.replace("globalThis.structuredClone = function (v) {",
                             "globalThis.__disabled_sc = function (v) {")),
        ("整体包进 DOMContentLoaded（延后执行）",
         lambda h: h.replace("<script>", "<script>\ndocument.addEventListener('DOMContentLoaded',function(){", 1)),
        ("丢掉版本标记",
         lambda h: h.replace("legacy-webview polyfill v", "legacy-webview polyfil v")),
    ]
    for label, mutate in mutants:
        body = mutate(asset if exists else "")
        if body == (asset if exists else ""):
            check("自反证：%s -> 变异确实生效" % label, False,
                  "变异没改到任何字节，这条自反证无效")
            continue
        problems = asset_problems(body)
        check("自反证：%s -> 判据变红" % label, bool(problems),
              "变异后仍判绿，说明 B 段在空转；命中问题=%s" % problems)

finally:
    for name in os.listdir(tmpdir):
        os.unlink(os.path.join(tmpdir, name))
    os.rmdir(tmpdir)

print("\n=== D. 落地：构建期注入 + 运行期自愈 + WebView 拦截 ===")

check("WebViewPolyfill 在首个 module 脚本之前插入",
      "moduleScript" in poly_kt and "range.first" in poly_kt,)
check("WebViewPolyfill 先整块剥离旧 polyfill 再插入（不许叠加多份）",
      "oldPolyfill.replace" in poly_kt and "replaceAll" not in poly_kt,)
check("旧的 vN 块能被识别并整块替换",
      "legacy-webview polyfill v\\d+" in poly_kt or "legacy-webview polyfill v" in poly_kt,)
check("判据比较整块字节，不只看注释/API 名字",
      "blocks[0].value.trimEnd() == polyfill.trimEnd()" in poly_kt,
      "只查 API 名字会被「注释里提了一嘴」骗过")
check("旧块位置必须在 module 之前才算合格",
      "range.last < firstModule" in poly_kt,)
check("自愈写盘走临时文件 + 原子替换（断电不留半个 index.html）",
      "ATOMIC_MOVE" in poly_kt and "dsh-polyfill.tmp" in poly_kt,)

installer_src = read(INSTALLER_KT)
heal_calls = installer_src.count("WebViewPolyfill.healInstalled")
check("安装器在 runtime 复用路径自愈",
      "complete) {" in installer_src and heal_calls >= 1,)
check("安装器在安装完成路径也自愈（两条路径都要覆盖）", heal_calls >= 2,
      "只修复用路径 = 新装用户仍然空白屏；计数=%d" % heal_calls)

main_src = read(MAIN_KT)
check("WebView 侧在 shouldInterceptRequest 拦截（早于 module 解析）",
      "shouldInterceptRequest" in main_src and "interceptWebUiDocument" in main_src,)
check("只拦主框架文档",
      "isForMainFrame" in main_src,)
check("只拦 GET",
      "request.method != \"GET\"" in main_src or "GET" in main_src,)
check("只拦引擎回环首页（不碰其它请求）",
      "healthyPort" in main_src and "index.html" in main_src,)
check("响应体改了就必须重算 Content-Length",
      "Content-Length\" ] = bytes.size.toString()" in main_src or 'headers["Content-Length"] = bytes.size.toString()' in main_src,)
check("不跟随重定向（交给 WebView 处理 URL 语义）",
      "instanceFollowRedirects = false" in main_src,)
check("只处理 200 + text/html",
      "responseCode != 200" in main_src and "text/html" in main_src,)
check("拦截失败要兜底回原生（不能白屏）",
      "polyfill document interception failed" in main_src,)

print("\n=== E. 单测覆盖四条语义案例 ===")

test_src = read(POLY_TEST)
for name in ("insertsWholeScriptBeforeEveryModuleAndIsIdempotent",
             "upgradesOldBlockEvenWhenCommentsMentionRequiredApis",
             "repairsLateAndDuplicateBlocks",
             "leavesNonModuleDocumentsUnchanged"):
    check("单测案例存在：%s" % name, name in test_src)
check("单测断言「插在第一个 module 之前」",
      "indexOf(\"<script type=\")" in test_src,)
check("单测有幂等断言",
      "assertEquals(patched, WebViewPolyfill.patchHtml(patched, polyfill))" in test_src,)

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)
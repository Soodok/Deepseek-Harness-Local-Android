#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""锚定浮层定位兜底的可复验判据（issue #119）。

## 事故

Android 上消息操作行最后那个「用量」图标点了没反应：能点、无障碍 ACTION_CLICK
返回 ok，但屏幕上什么都不出现。桌面版同一个按钮正常。

## 根因（代码级）

引擎 0.2.2-rc.3 把浮层定位从「JS 量测 + 内联坐标」换成了**纯 CSS 锚点定位**：

  trigger 外层 span  → style="anchor-name: --dsh-stat-xxx"
  面板              → style="position-anchor: --dsh-stat-xxx"
  CSS               → top:...anchor(top)...; left:...anchor(left)...;
                      position-area:none; position-try-fallbacks:...

**CSS anchor positioning 是 Chrome 125+ 的能力**。Android WebView 普遍更老
（issue #107 里那位华为用户是 com.huawei.webview 114.0.5.302），老内核把这些
属性整条丢弃，面板拿不到任何相对 trigger 的坐标 —— 于是「点了没反应」。
rc.1 用的还是 JS 量测，所以这是升级引入的老内核回归。

## 修法

只在 CSS.supports('position-anchor: --x') === false 时接管：用 trigger 的
boundingClientRect 算视口内坐标，写内联 !important（内联 !important 在层叠里
高于任何作者样式表 !important，因此也压得过 mobile-layout 给「统计胶囊详情」
写的固定定位规则）。新内核一个字都不改。

## 本测试验证

A. 插件形态正确：目录三件套齐全、overlay 注册、部署清单登记。
B. 能力门禁：判据是 position-anchor，且在 apply 的最前面短路。
C. 保守性：只接管 role=dialog、非 aria-modal、且存在展开中 aria-haspopup=dialog
   trigger 的浮层。
D. 行为：真跑 client.js 的钩子（见 _chat_anchor_fallback_runtime_test.mjs）。
E. 负对照：把门禁 / 夹取 / trigger 判据分别改坏，D 段必须变红。
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG = os.path.join(ROOT, "app", "src", "main", "assets", "android-plugins", "chat-anchor-fallback")
CLIENT = os.path.join(PKG, "client.js")
ENGINE_CFG = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineConfig.kt")
RUNTIME = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_chat_anchor_fallback_runtime_test.mjs")

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


client = read(CLIENT)
cfg = read(ENGINE_CFG)

print("=== A. 插件形态：目录三件套 + overlay 注册 + 部署清单 ===")

for fn in ("package.json", "index.js", "client.js"):
    check("包内文件存在：%s" % fn, os.path.isfile(os.path.join(PKG, fn)))

check("package.json 声明了 ./client 导出（否则引擎不加载浏览器端）",
      '"\"./client\""' in read(os.path.join(PKG, "package.json")).replace(" ", "")
      or '"./client"' in read(os.path.join(PKG, "package.json")))
check("overlay 里注册了 index.js",
      "android-chat-anchor-fallback" in cfg and "android-plugins/chat-anchor-fallback/index.js" in cfg)
check("部署清单登记了三个文件（缺一个 client 就不加载）",
      '"chat-anchor-fallback" to listOf("package.json", "index.js", "client.js")' in cfg)

print("\n=== B. 能力门禁：position-anchor + 最前面短路 ===")

check("判据是 position-anchor（上游真正依赖的那条属性）",
      'CSS.supports("position-anchor: --x")' in client,
      "探 anchor() 之类没有意义：决定上游那套 CSS 能否解析的就是 position-anchor")
check("支持的探测失败时按「不支持」处理（宁可兜底也不要空白）",
      "catch (e)" in client and "return false;" in client)
check("apply 里先判能力再装兜底（新内核必须走空分支）",
      re.search(r"function apply\(ctx\) \{\s*if \(supportsAnchor\(\)\)", client) is not None)
check("新内核什么都不做（提前 return，不订阅 DOM）",
      "fallback idle" in client)
check("老内核才 install（有安装调用）", "install()" in client)

print("\n=== C. 保守性：不动别人的浮层 ===")

check("只接管 role=dialog", 'getAttribute("role") !== "dialog"' in client)
check("跳过 aria-modal=true（模态框自己居中）",
      'getAttribute("aria-modal") === "true"' in client)
check("必须有展开中的 aria-haspopup=dialog trigger 才动",
      'aria-haspopup="dialog"' in client and 'aria-expanded="true"' in client)
check("没有 trigger 时直接返回（不乱摆）", "if (!trigger) return;" in client)
check("坐标带 !important（压过作者样式表的 !important）",
      '"important"' in client and client.count('setProperty(') >= 8)
check("视口夹取：不小于边距、不超视口",
      "Math.max(MARGIN" in client and "Math.min(" in client)
check("滚动/resize 重摆", 'addEventListener("scroll"' in client and 'addEventListener("resize"' in client)
check("收起（aria-expanded 变 false）后停止", 'getAttribute("aria-expanded") !== "true"' in client)
check("卸载时断开观察器与监听", "observer.disconnect()" in client and "function dispose()" in client)

print("\n=== D. 行为：真跑 client.js 的钩子 ===")


def run_runtime(plugin_path):
    r = subprocess.run(
        ["node", RUNTIME, plugin_path],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=240,
    )
    tail = (r.stdout or r.stderr or "")[-400:]
    return r.returncode == 0, tail


if not os.path.isfile(RUNTIME):
    check("运行期测试脚本存在", False, RUNTIME)
else:
    ok, tail = run_runtime(CLIENT)
    check("D1 运行期行为全绿（门禁/摆位/夹取/生命周期）", ok, tail)

print("\n=== E. 负对照：改坏判据必须变红 ===")

mutants = [
    ("去掉能力门禁（新内核也强行接管）",
     lambda s: s.replace("function apply(ctx) {\n      if (supportsAnchor()) {",
                         "function apply(ctx) {\n      if (false) {")),
    ("去掉视口夹取（浮层可能跑到屏幕外）",
     lambda s: s.replace("top = Math.max(MARGIN, Math.min(top, vh - MARGIN - height));",
                         "top = top;")),
    ("不看 trigger 的展开态（别人的浮层也摆）",
     lambda s: s.replace('if (trigger.getAttribute("aria-expanded") !== "true") {',
                         "if (false) {")),
]

tmpdir = tempfile.mkdtemp(prefix="dsh-anchor-")
try:
    base_ok, _ = run_runtime(CLIENT) if os.path.isfile(RUNTIME) else (True, "")
    for label, mutate in mutants:
        mutated = mutate(client)
        if mutated == client:
            check("负对照：%s -> 变异生效" % label, False, "变异没改到任何字节")
            continue
        path = os.path.join(tmpdir, "mutant.js")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(mutated)
        ok, tail = run_runtime(path)
        check("负对照：%s -> 运行期判据变红" % label, not ok, "变异后仍全绿，说明判据在空转")
finally:
    for name in os.listdir(tmpdir):
        os.unlink(os.path.join(tmpdir, name))
    os.rmdir(tmpdir)

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)

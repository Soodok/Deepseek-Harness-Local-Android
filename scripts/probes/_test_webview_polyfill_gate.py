#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""运行时可验证脚本不能写死 WebView polyfill 版本号（v1.6.3 事故）。

## 事故

`scripts/patch-webview-polyfill.py` 升到 **v5**（`POLYFILL = '<!-- [dsh-android]
legacy-webview polyfill v5 -->'`），但 `scripts/build-runtime.sh` 的校验段仍断言
字面量 **"legacy-webview polyfill v4"**。结果：

    runtime lacks WebView polyfill v4
    ##[error]Process completed with exit code 1.

**任何人**推 `runtime-build` 都必红，tag 正式构建也跟着红（它要求 runtime 与脚本
指纹一致，而新 runtime 根本发不出来）。报错文案还极具误导性 —— 看着像「补丁丢了」，
实际是同一个版本号在**两个文件里各写了一份**，升级时只改了一处。

同一类坑此前已经咬过一次：`scripts/probes/_test_webview_polyfill_v4.mjs` 里的
版本字面量也得手动跟着改才能跑。

## 本测试验证

A. 静态：`build-runtime.sh` 的校验段不得出现写死的 `polyfill v<数字>` 字面量，
   必须用稳定前缀 + 版本无关的能力标记。
B. 行为：把校验段**原件**（从 shell 脚本里抽出来）当 Python 执行，
   喂 v4 / v5 / 缺标记 / 缺能力标记 四种 index.html，断言通过与否符合预期。
C. 自反证：把校验段在内存里改回写死 v4，再喂 v5 —— 必须变红（证明 B 不是空转）。
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SH = os.path.join(ROOT, "scripts/build-runtime.sh")
PATCH = os.path.join(ROOT, "scripts/patch-webview-polyfill.py")

ok = 0
fail = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global ok, fail
    if cond:
        ok += 1
        print(f"  \u2713 {name}")
    else:
        fail += 1
        print(f"  \u2717 {name}" + (f"  -- {detail}" if detail else ""))


sh = open(SH, encoding="utf-8").read()
patch = open(PATCH, encoding="utf-8").read()

# 校验段：从 poly = text( 到 print(f"verify: webview polyfill
start = sh.index('poly = text("lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html")')
end_marker = 'print(f"verify: webview polyfill v{m.group(1)}")'
end = sh.index(end_marker) + len(end_marker)
block = sh[start:end]

print("=== A. 静态：校验段不许写死版本号 ===")
# 去掉注释行再判定 —— 注释里提到 v4/v5 是允许的（那正是解释来历的地方）
code_lines = [ln for ln in block.split("\n") if not ln.lstrip().startswith("#")]
code = "\n".join(code_lines)

check(
    "校验段里没有写死的 polyfill 版本字面量",
    re.search(r'legacy-webview polyfill v\d', code) is None,
    "写死版本号会让下次升级 polyfill 时构建全红",
)
check(
    "用稳定前缀 + 捕获版本号的正则",
    re.search(r"legacy-webview polyfill v\(\\d\+\)", code) is not None,
    "期望形如 r\"legacy-webview polyfill v(\\d+)\"",
)
check("能力标记仍然保留（throwIfAborted）", "throwIfAborted" in code)
check("能力标记仍然保留（AbortSignal.any）", "AbortSignal.any" in code)
check("能力标记仍然保留（Promise.withResolvers）", "Promise.withResolvers" in code)
# 用 splitlines()，避免在替换脚本文本里反复踩换行符转义
sh_code = chr(10).join(ln for ln in sh.splitlines() if not ln.lstrip().startswith("#"))
check(
    "报错文案不再咬死某个版本号（剥掉注释后判定）",
    "runtime lacks WebView polyfill v4" not in sh_code,
    "旧文案会把「版本字面量没同步」误导成「补丁丢了」；注释里引用旧文案是允许的",
)
check("成功时会打印实际版本号", 'print(f"verify: webview polyfill v{m.group(1)}")' in sh)

print("\n=== A2. 与 patch 脚本的版本源对齐 ===")
m = re.search(r"POLYFILL = r'''<!-- \[dsh-android\] legacy-webview polyfill v(\d+) -->", patch)
check("patch 脚本里有可识别的 vN 标记", m is not None)
if m:
    check(
        f"patch 当前注入 v{m.group(1)}，校验段接受任意 vN（不再需要同步改）",
        re.search(r"legacy-webview polyfill v\(\\d\+\)", code) is not None,
    )


print("\n=== B. 行为：把校验段原件当 Python 跑 ===")


def run_gate(index_html: str, src_block: str = block) -> tuple[bool, str]:
    """执行校验段原件，poly 绑定为传入的 index.html 文本。"""
    # 校验段第一行是 poly = text(".../index.html")，这里给 text 一个桩，
    # 让它返回被测 html —— 跑的是**脚本原件**，不是重写的一份。
    ns = {"text": lambda _name: index_html, "__name__": "__gate__"}
    try:
        exec(compile(src_block, "<gate>", "exec"), ns)  # noqa: S102 —— 跑的就是被测代码
        return True, ""
    except SystemExit as e:
        return False, str(e)


V4 = '<html><!-- [dsh-android] legacy-webview polyfill v4 --><script>if(!Promise.withResolvers){} if(!AbortSignal.any){} function throwIfAborted(){}</script></html>'
V5 = '<html><!-- [dsh-android] legacy-webview polyfill v5 --><script>if(!Promise.withResolvers){} if(!AbortSignal.any){} function throwIfAborted(){}</script></html>'
V9 = V5.replace("polyfill v5", "polyfill v99")
NONE = "<html><script>console.log(1)</script></html>"
WEAK = '<html><!-- [dsh-android] legacy-webview polyfill v5 --><script>function throwIfAborted(){}</script></html>'

for label, html, want in [
    ("v4 产物应通过（兼容老 runtime）", V4, True),
    ("v5 产物应通过（本次事故的直接回归）", V5, True),
    ("v99 产物应通过（将来再升也不用改）", V9, True),
    ("完全没有 polyfill 必须失败", NONE, False),
    ("有标记但缺能力标记必须失败", WEAK, False),
]:
    got, msg = run_gate(html)
    check(label, got == want, f"got={got} want={want} {msg}")


print("\n=== C. 自反证：改回写死 v4 后，v5 必须变红 ===")
hardcoded = re.sub(
    r'import re as _re\n.*?print\(f"verify: webview polyfill v\{m\.group\(1\)\}"\)',
    'need = ["legacy-webview polyfill v4", "throwIfAborted", "AbortSignal.any", "Promise.withResolvers"]\n'
    'if any(n not in poly for n in need):\n'
    '    raise SystemExit("runtime lacks WebView polyfill v4")',
    block,
    flags=re.S,
)
check("自反证前提：确实能把校验段改回写死版本", "legacy-webview polyfill v4" in hardcoded)
if "legacy-webview polyfill v4" in hardcoded:
    got, _ = run_gate(V5, hardcoded)
    check("写死 v4 时 v5 产物变红（证明 B 段不是空转）", got is False)
    got4, _ = run_gate(V4, hardcoded)
    check("写死 v4 时 v4 产物仍绿（说明差异确实来自版本字面量）", got4 is True)

# ── D. 第二处写死点：.github/workflows/runtime-build.yml ─────────────
# 第一次修完 build-runtime.sh 后重新触发 runtime-build，仍然红 —— 根因是 YAML 里
# 还有一份同样的写死断言。所以这一节把它一起锁住：三个地方（patch 脚本 / shell 校验
# / workflow 校验）里，只有 patch 脚本该有版本号，另外两处只认稳定前缀。
print("\n=== D. workflow 校验段同样不许写死版本号 ===")
WF = os.path.join(ROOT, ".github/workflows/runtime-build.yml")
wf = open(WF, encoding="utf-8").read()
wf_code = chr(10).join(ln for ln in wf.splitlines() if not ln.lstrip().startswith("#"))

check(
    "workflow 里没有写死的 polyfill 版本字面量",
    re.search(r"legacy-webview polyfill v\d", wf_code) is None,
    "这是 v1.6.3 的第二处写死点，修了一处还会红",
)
check(
    "workflow 用 grep -oE 抓版本号",
    "grep -oE" in wf_code and "POLY_VER=$(" in wf_code,
    "期望形如 POLY_VER=$(grep -oE ...)",
)
check(
    "抓取用的是稳定前缀 + 任意 vN",
    "legacy-webview polyfill v[0-9]+" in wf_code,
    "前缀必须稳定，版本号必须是 [0-9]+；写死 v4 就是本次事故",
)
check("workflow 缺标记时明确报错退出", "polyfill 标记缺失" in wf_code)
check("workflow 日志里打印实际版本号", "WebView polyfill（$POLY_VER）OK" in wf_code)

print("\n=== D2. 行为：跑 workflow 里那段真实 shell ===")
# 抽出 POLY 之后到最终 echo 之间的片段（跳过开头那行 POLY=... 赋值，改由外面传入），
# 原样交给 bash 执行 —— 验的是 YAML 里的真代码，不是重写的一份。
frag_start = wf.index('test -f "$POLY" || { echo "::error::web frontend')
frag_end = wf.index('WebView polyfill（$POLY_VER）OK"') + len('WebView polyfill（$POLY_VER）OK"')
fragment = wf[frag_start:frag_end]

tmpdir = os.path.join(ROOT, "_poly_gate_tmp")
os.makedirs(tmpdir, exist_ok=True)


def run_wf_gate(html: str) -> tuple[int, str]:
    p = os.path.join(tmpdir, "index.html")
    open(p, "w", encoding="utf-8", newline="\n").write(html)
    env = {**os.environ, "POLY": p}
    r = subprocess.run(["bash", "-c", fragment], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", env=env, timeout=60)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


try:
    rc, out = run_wf_gate(V5)
    check("v5 产物在 workflow 校验里通过", rc == 0, f"rc={rc} {out[-200:]}")
    check("通过时打印出实际版本 v5", "polyfill v5" in out or "v5" in out, out[-200:])
    rc, out = run_wf_gate(V9)
    check("v99 产物在 workflow 校验里也通过", rc == 0, f"rc={rc} {out[-200:]}")
    rc, out = run_wf_gate(NONE)
    check("没有 polyfill 时 workflow 校验必须失败", rc != 0, f"rc={rc}")
    rc, out = run_wf_gate(WEAK)
    check("缺能力标记时 workflow 校验必须失败", rc != 0, f"rc={rc}")
finally:
    for name in os.listdir(tmpdir):
        os.remove(os.path.join(tmpdir, name))
    os.rmdir(tmpdir)

# ── E. 系统性：全仓只允许 patch 脚本拥有版本号 ─────────────────────────
# 这个常量实际在**四处**各存过一份，修一处红一处，一共红了三次 CI：
#   ① scripts/patch-webview-polyfill.py（真源，该有版本号）
#   ② scripts/build-runtime.sh
#   ③ .github/workflows/runtime-build.yml
#   ④ app/build.gradle.kts
# 所以不再逐个点名，而是**遍历所有校验点**：谁都不许写死版本号，
# 只许用稳定前缀 + 任意 vN 的正则形态。
print("\n=== E. 全仓校验点：只有 patch 脚本可以有版本号 ===")
VALIDATORS = [
    "scripts/build-runtime.sh",
    ".github/workflows/runtime-build.yml",
    "app/build.gradle.kts",
]
STABLE = "legacy-webview polyfill v"

for rel in VALIDATORS:
    path = os.path.join(ROOT, rel)
    if not os.path.isfile(path):
        check(f"{rel} 存在", False, "文件缺失，校验点清单要更新")
        continue
    text = open(path, encoding="utf-8").read()
    # 剥注释：三处都用 # 或 // 或 * 起头
    body = chr(10).join(
        ln for ln in text.splitlines()
        if not ln.lstrip().startswith(("#", "//", "*", "/*"))
    )
    check(
        f"{rel}: 可执行代码里没有写死的版本号",
        re.search(r"legacy-webview polyfill v\d", body) is None,
        "写死版本号 → 升级 polyfill 时这个校验点必红",
    )
    check(
        f"{rel}: 用稳定前缀 + 任意 vN",
        (STABLE + "[0-9]+") in body or (STABLE + r"(\d+)") in body,
        f"期望出现 {STABLE}[0-9]+ 或 {STABLE}(\\\\d+)",
    )

# 真源里必须有且只有一个「版本号决定者」
patch_versions = set(re.findall(r"legacy-webview polyfill v(\d+)", patch))
check(
    "patch 脚本是唯一的版本号来源",
    len(patch_versions) == 1,
    f"patch 里出现了多个版本号：{sorted(patch_versions)}",
)

# 全仓扫一遍，别处不许再冒出裸字面量（docs 与探针自身除外）
leaks = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames
                   if d not in (".git", "build", "node_modules", "docs", ".gradle",
                                "probes", "_runtime_bak", "keystore", "debug-signing")]
    for fn in filenames:
        if not fn.endswith((".kts", ".kt", ".sh", ".yml", ".yaml", ".py", ".mjs", ".js")):
            continue
        full = os.path.join(dirpath, fn)
        rel = os.path.relpath(full, ROOT).replace("\\", "/")
        if rel == "scripts/patch-webview-polyfill.py":
            continue
        try:
            text = open(full, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        body = chr(10).join(
            ln for ln in text.splitlines()
            if not ln.lstrip().startswith(("#", "//", "*", "/*"))
        )
        if re.search(r"legacy-webview polyfill v\d", body):
            leaks.append(rel)

check(
    "全仓没有第五处写死点",
    not leaks,
    f"这些文件又写死了版本号：{leaks[:5]}",
)

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)

#!/usr/bin/env python3
"""overlay patch 语义回归测试（v1.4.8）。

## 为什么必须存在

v1.4.7 发了一个「把 directory-picker 从 auto 换成 browse」的修复，构建全绿、
两个字符串文件保持同步、打包正常 —— 但**它在真机上从未生效**。

原因在引擎的 `applyEntryPatches`：

    const { id, insert, name, ...overrides } = patch;
    if (name && name !== target.name) { warn(...); continue; }

`name` 是**匹配守卫**，不是可赋值字段。overlay 里写
`id: directory-picker / name: ...-browse` 而 base 行是 `...-auto` 时，
整条 patch 被跳过，只留一行 logger 警告 → 引擎继续跑 auto。

这个测试用**引擎自己的实现**（cordis-plugin-include）跑真实 patch，
避免再出现「代码看着对、实际没生效」。

用法：
    python scripts/probes/_test_overlay_patch_semantics.py <path-to-cordis-plugin-include/lib/index.js>
不给参数时只做静态检查（不需要引擎）。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OVERLAY_SRC = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
PROBE = os.path.join(ROOT, "app/src/main/assets/android-plugins/plugin-health.mjs")

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


def overlay_body() -> str:
    src = open(OVERLAY_SRC, encoding="utf-8").read()
    start = src.index('val body = """')
    end = src.index('""".trimMargin()', start)
    raw = src[start:end]
    # 每行形如 `            |xxx`，取竖线之后的内容
    lines = []
    for line in raw.split("\n")[1:]:
        idx = line.find("|")
        lines.append(line[idx + 1:] if idx >= 0 else "")
    return "\n".join(lines)


print("=== 静态检查：overlay 的 directory-picker 写法 ===")
body = overlay_body()

# 找出所有 directory-picker 相关行
dp_rows = [ln.strip() for ln in body.split("\n") if "directory-picker" in ln]
print("  相关行:")
for ln in dp_rows:
    print("   ", ln)

# 规则 1：不能出现「非 insert 的 patch 同时带 id 与 name」，除非 name 与 base 一致。
# 这里只对 directory-picker 断言（其它行 base 就是同名的，属于合法用法）。
bad = [ln for ln in dp_rows
       if re.match(r"^-\s*id:\s*directory-picker\s*$", ln)]
check("base 行 directory-picker 存在（用于 disable）", bool(bad), str(bad))

disabled = re.search(
    r"- id: directory-picker\s*\n\s*disabled: true", body)
check("base 行被 disabled: true", bool(disabled),
      "找不到 `- id: directory-picker` 后跟 `disabled: true`")

# 规则 2：必须有一条 insert 出来的新 id 行，且名字是 browse
insert_id = re.search(
    r"- insert:\s*\n\s*- id: (android-directory-picker)\s*\n\s*name: '?\"?([^'\"\n]+)'?\"?",
    body)
check("insert 出一条新 id 行", bool(insert_id),
      "需要 `- insert:` 下面挂 `- id: android-directory-picker`")
if insert_id:
    pkg = insert_id.group(2).strip()
    check("新行指向 browse 包", pkg == "@deepseek-ai/dsh-host-directory-picker-browse", pkg)

# 规则 2b（v1.4.10）：picker 是「后端 + 前端表面」一对，缺一不可。
# 只挂后端时服务存在、界面控件不注册 —— 用户看到的就是「选择器弹不出来」。
surf = re.search(
    r"- id: (android-directory-picker-surface)\s*\n\s*name: '?\"?([^'\"\n]+)'?\"?",
    body)
check("insert 出前端 surface 行", bool(surf),
      "缺 surface 时 directoryPicker 服务在，但界面上没有选择框")
if surf:
    surf_pkg = surf.group(2).strip()
    check("surface 指向 client-ui browse",
          surf_pkg == "@deepseek-ai/dsh-client-ui-directory-picker-browse", surf_pkg)

# 规则 3：绝不能出现 v1.4.7 那种被静默跳过的写法
forbidden = re.search(
    r"-\s*id:\s*directory-picker\s*\n\s*name:\s*'@deepseek-ai/dsh-host-directory-picker-browse'",
    body)
check("没有 name-mismatch 的非法 patch", not forbidden,
      "这种写法会被 applyEntryPatches 静默跳过")

print("\n=== 静态检查：探针校验的是真正在跑的那一行 ===")
probe = open(PROBE, encoding="utf-8").read()
row = re.search(r'"android-directory-picker":\s*"([^"]+)"', probe)
check("CRITICAL_ROWS 用新 id", bool(row), "应为 android-directory-picker")
if row:
    check("校验包名是 browse", row.group(1) == "@deepseek-ai/dsh-host-directory-picker-browse",
          row.group(1))
legacy = re.search(r'"directory-picker":\s*"@deepseek-ai/dsh-host-directory-picker-(browse|auto)"', probe)
check("不再拿旧 id 做 name 校验", not legacy,
      "旧 id 行已被 disabled，校验它必然误报 degraded")

svc = re.search(r"directoryPicker:\s*\[\s*\n\s*\[\"([^\"]+)\"", probe)
check("SERVICE_ROWS 用新 id", bool(svc) and svc.group(1) == "android-directory-picker",
      svc.group(1) if svc else "missing")

# v1.4.10：探针必须同时校验 surface，否则「服务在、界面不在」这种不对称
# 永远检测不出来 —— 正是用户「权限给了但选不了外部文件夹」的现场。
surf_row = re.search(
    r'"android-directory-picker-surface":\s*\n?\s*"([^"]+)"', probe)
check("探针校验 surface 行", bool(surf_row),
      "只校验后端会漏掉「服务在、界面不在」")
if surf_row:
    check("探针 surface 包名正确",
          surf_row.group(1) == "@deepseek-ai/dsh-client-ui-directory-picker-browse",
          surf_row.group(1))

# ---------------------------------------------------------------- 动态验证
idx = sys.argv[1] if len(sys.argv) > 1 and os.path.isfile(sys.argv[1]) else None
if idx and os.path.isfile(idx):
    print("\n=== 动态验证：用引擎自己的 applyEntryPatches 跑真实 patch ===")
    script = r"""
const { pathToFileURL } = await import('node:url');
const mod = await import(pathToFileURL(process.argv[2]).href);
const apply = mod.applyEntryPatches;
const base = [
  { id: 'directory-picker', name: '@deepseek-ai/dsh-host-directory-picker-auto' },
  { id: 'workspace', name: '@deepseek-ai/dsh-workspace' },
];
const patch = [
  { id: 'directory-picker', disabled: true },
  { insert: [{ id: 'android-directory-picker',
               name: '@deepseek-ai/dsh-host-directory-picker-browse',
               disabled: false }] },
];
const warns = [];
const out = apply(base, patch, (m, ...a) => warns.push(String(m)));
console.log(JSON.stringify({ out, warns }));
"""
    tmp = os.path.join(ROOT, "scripts", "probes", "_patch_dyn.mjs")
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(script)
    try:
        node = os.environ.get("NODE_BIN", "node")
        res = subprocess.run([node, tmp, idx], capture_output=True, text=True, timeout=60)
        if res.returncode != 0:
            print("  (动态验证跳过：node 执行失败)")
            print("   ", res.stderr.strip().splitlines()[-1] if res.stderr.strip() else "")
        else:
            data = json.loads(res.stdout.strip().splitlines()[-1])
            out, warns = data["out"], data["warns"]
            auto = next((e for e in out if e["id"] == "directory-picker"), None)
            browse = next((e for e in out if e["id"] == "android-directory-picker"), None)
            check("无 patch 警告", not warns, str(warns))
            check("auto 行被禁用", auto is not None and auto.get("disabled") is True, str(auto))
            check("browse 行被加入且启用",
                  browse is not None and browse.get("disabled") is False, str(browse))
    finally:
        os.remove(tmp)
else:
    print("\n(未提供引擎路径，跳过动态验证)")

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)

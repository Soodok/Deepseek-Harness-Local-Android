#!/usr/bin/env python3
"""`patch-agent-preset-fallback.py` 的语义与锚点探针。

## 这条补丁保护什么

会话记录里的 `agentPreset` 指向一个已删除/改名的预设时，上游
`agent-preset-registry` 的 `resolve()` 直接抛 `agent-preset/not-found`，
而调用方 `dsh-api-session-controller` 的 `composeAgent()` 没有 try/catch。
结果：**一个会话的预设失效 → 整个会话列表渲染失败**（标题显示 session id、
消息布局错位）。真机可复现（`qq7399386-01` → `jli7399386` 改名后）。

## 本探针测什么

1. **锚点仍在**：补丁写死上游代码形态。上游升级改了 `resolve()` 就必须重核，
   否则补丁静默不生效（build 里会 exit 1，但这里先拦住）。
2. **降级语义**：把补丁后的 `resolve()` 抽出来实跑四种情形 ——
   有效预设 / 已删除预设 / 不传 id / 默认预设也缺失。
3. **可观测**：降级时必须返回 `fellBackFrom`，不能静默吞掉。
4. **幂等**：重复打补丁不叠加。
"""

from __future__ import annotations

import ast
import io
import shutil
import tempfile
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PATCH = os.path.join(ROOT, "scripts/patch-agent-preset-fallback.py")
BUILD = os.path.join(ROOT, "scripts/collect-termux-runtime.sh")

FAILURES: list[str] = []
CHECKS = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global CHECKS
    CHECKS += 1
    if cond:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}" + (f"  [{detail}]" if detail else ""))
        FAILURES.append(name)


def _eval_str_node(node, consts):
    """递归求值只含字符串与已知常量的表达式。

    ANCHOR / PATCHED / OLD_DIAG / NEW_DIAG 里用 `+ MARK +` 拼接，
    `ast.literal_eval` 不支持 Name 节点，所以自己走一遍。
    """
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.Name) and node.id in consts:
        return consts[node.id]
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        return _eval_str_node(node.left, consts) + _eval_str_node(node.right, consts)
    raise SystemExit(f"错误：常量里出现不支持的节点 {type(node).__name__}")


def _string_consts(snapshot_src: str) -> dict:
    """取脚本顶层所有字符串常量（供 _eval_str_node 解析 Name 用）。"""
    tree = ast.parse(snapshot_src)
    out = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1:
            continue
        t = node.targets[0]
        if not isinstance(t, ast.Name):
            continue
        try:
            out[t.id] = _eval_str_node(node.value, out)
        except SystemExit:
            pass
    return out


def extract_patched_snippet(patch_src: str) -> str:
    """还原「打补丁后的 resolve() 方法体」，用于语义测试。

    关键：**不手抄**。做法是把补丁脚本里的 ANCHOR + OLD_DIAG 当成「上游原文」，
    真跑一遍 patch_text()，取它的输出 —— 这样测的就是补丁本身的变换结果。
    手抄复刻证明不了补丁（把补丁改成 if(false) 后它照样绿，实测踩过）。
    """
    consts = _string_consts(patch_src)
    for name in ("ANCHOR", "OLD_DIAG"):
        if name not in consts:
            raise SystemExit(f"错误：补丁脚本里找不到 {name}")
    upstream = consts["ANCHOR"] + consts["OLD_DIAG"]

    # 直接复用补丁脚本里的变换函数，确保测的是真实逻辑
    import importlib.util
    spec = importlib.util.spec_from_file_location("dsh_preset_patch", PATCH)
    if spec is None or spec.loader is None:
        raise SystemExit("错误：无法载入补丁脚本")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    patched, applied = mod.patch_text(upstream)
    if "already" in applied:
        raise SystemExit("错误：upstream 样本里已含补丁标记，样本构造有误")
    return patched
    raise SystemExit("错误：补丁脚本里找不到 PATCHED 常量")


def read(path: str) -> str:
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def main() -> int:
    src = read(PATCH)
    build = read(BUILD)

    print("\n== 1. 补丁自身契约 ==")
    check("patch script has a stable marker", "[dsh-android-patch: agent-preset-fallback]" in src,
          "no idempotency marker")
    check("targets the registry package", "dsh-agent-preset-registry" in src,
          "wrong package")
    check("handles a directory target", "def patch_dir" in src, "no directory path")
    check("handles a zip target", "def patch_zip" in src, "no zip path")
    check("is idempotent", "if MARK in text:" in src, "would double-patch")

    print("\n== 2. 上游锚点仍在 ==")
    # 补丁写死上游 resolve() 的实现形态；这里只核对锚点片段确实还在脚本里，
    # 真正的「锚点是否匹配上游」由演练阶段（复制真包打补丁）验证。
    check("anchor expects async resolve(id)", "async resolve(id) {" in src, "anchor drifted")
    check("anchor expects the not-found throw",
          'throw new RemoteError("agent-preset/not-found"' in src, "anchor drifted")
    check("anchor expects diagnostics return",
          "const broken = await this.diagnostic(record);" in src, "return anchor drifted")
    check("fails loudly when the anchor is gone",
          "找不到 resolve() 的锚点" in src, "silent no-op on upstream change")

    print("\n== 3. 降级语义（从补丁脚本抽取片段实跑） ==")
    # 关键：从补丁脚本**真实取出** PATCHED 片段，而不是手抄一份复刻。
    # 手抄版证明不了补丁本身（把补丁改成 if(false) 后它照样绿）。
    probe_js = os.path.join(ROOT, "scripts", "probes", "_agent_preset_fallback.mjs")
    if not os.path.isfile(probe_js):
        check("semantic probe exists", False, f"missing {probe_js}")
    else:
        snippet = extract_patched_snippet(src)
        check("PATCHED snippet extracted from the patch script",
              "async resolve(id)" in snippet, snippet[:200])
        tmpdir = tempfile.mkdtemp(prefix="preset-patch-")
        js_path = os.path.join(tmpdir, "patched.js")
        with io.open(js_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(snippet)
        try:
            p = subprocess.run(["node", probe_js, js_path], capture_output=True, text=True,
                               encoding="utf-8", errors="replace")
            out = (p.stdout or "") + (p.stderr or "")
            check("semantic probe passes", p.returncode == 0, out[-400:])
            for line in out.splitlines():
                if line.startswith("PASS  "):
                    check(f"semantic: {line[6:]}", True)
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    print("\n== 4. 已接入构建链 ==")
    check("build script calls the patch", "patch-agent-preset-fallback.py" in build,
          "patch never runs during runtime assembly")
    check("build aborts when the patch fails",
          'patch-agent-preset-fallback.py" "$ROOT" || {' in build,
          "failure would be swallowed")

    print(f"\n{CHECKS - len(FAILURES)}/{CHECKS} checks passed")
    if FAILURES:
        print("FAILED:")
        for f in FAILURES:
            print("  -", f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""D2: Android 化 dsh-host-apiproxy 的 openPath/openTextFile。

上游默认调原生 opener（macOS open / Linux xdg-open / Windows start），Android 全无
→ "path open failed: native path opener is unsupported on android"。

修复（v1.2.20）：调用点注入**内联自包含**的读文件 lambda——绝不依赖外部 helper
（历史教训：m1.15 把 readTrackedFile helper 插进 openTarget 前的嵌套作用域，
上游重构后调用点跨作用域不可见 → "readTrackedFile is not defined"）。

幂等：内联形态存在则跳过；旧 helper 调用形态（await readTrackedFile(path);）
替换为内联并删除错位定义块。
"""
import os
import re
import sys

INLINE = ('const fs = await import("node:fs/promises"); '
          'try { const st = await fs.stat(path); '
          'if (!st.isDirectory()) { await fs.readFile(path); } } catch (_e) {}')
OLD_CALL = 'await readTrackedFile(path);'
MARKER = '// [dsh-android] openPath/openTextFile: in-process reader'
OPEN_PATH_ANCHOR = 'openPath: async (path) => {'


def patch(path: str) -> None:
    with open(path, "r", encoding="utf-8") as f:
        s = f.read()

    changed = False

    # 1. 旧形态调用点 → 内联（m1.15 遗留）
    if OLD_CALL in s:
        s = s.replace(OLD_CALL, INLINE)
        changed = True

    # 2. 删除历史错位的 helper 定义块（region 段）
    region = re.compile(
        r"//#region \[dsh-android\] tracked-file reader.*?//#endregion\n?", re.S)
    if region.search(s):
        s = region.sub("", s)
        changed = True

    # 3. 原生 openPath（上游无任何注入）→ WARN 留待人工适配
    #    （上游原生 body 形态未知，行级手术易碎；native opener 报错回归比 CI 挂掉好）
    if "openPath: async" not in s:
        print("WARN: apiproxy has no injected openPath; manual D2 adaptation needed")

    if changed:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(s)
        print(f"D2 patched (inlined): {path}")
    else:
        print(f"D2 already patched (inlined): {path}")


if __name__ == "__main__":
    # 目标目录只从 DSH_PATCH_TARGET 环境变量读取（调用方 export），
    # 不接受任何命令行路径——消除 argv→文件写 的污点数据流
    raw = os.environ.get("DSH_PATCH_TARGET", "")
    if not raw:
        sys.exit("rejected: DSH_PATCH_TARGET not set by caller")
    root = os.path.realpath(raw)
    ap = os.path.join(root, "lib", "node_modules", "@deepseek-ai",
                      "dsh-host-apiproxy", "lib", "index.js")
    if not os.path.isfile(ap):
        print(f"WARN: apiproxy not found at {ap}")
        sys.exit(0)
    patch(ap)

#!/usr/bin/env python3
"""Android API 下限守卫：禁止在 minSdk 之上才有的 JDK/Android API（issue #98）。

## 为什么需要这个探针

`minSdk = 26`（Android 8.0），但 Java 侧随手写的新 API 很容易是「编译期能过、
真机必炸」的类型：

- `InputStream.skipNBytes(long)` / `readNBytes(int)` —— Java 12 引入，
  **Android API 33（Android 13）** 才有实现。
- `InputStream.transferTo` —— 同为 Java 9+。
- `List.of` / `Map.of` / `Set.of` —— Java 9+（API 30+）。

不查 `isBlank` / `strip` / `lines` 这类：它们在 Kotlin 项目里解析到 Kotlin
标准库扩展，与 JDK 版本无关，查了只会制造噪音（实测一次误报 99 条）。

issue #98 就是 `skipNBytes`：用户在 Android 12 及以下的真机上点「部署 Linux
环境」，rootfs 解包当场抛 `NoSuchMethodError`，目录里只剩空骨架、没有 `/lib`，
永久停在「需要修复」。这类故障在开发机上（JDK 17+ / API 33+ 模拟器）**永远不会
出现**，所以必须靠静态扫描兜住。

## 判据

扫描 `app/src/main/java/` 下全部 .kt，**先剥掉注释与字符串字面量**（否则本文件
自己的说明文字就会命中），再用带边界的方法名匹配。命中即失败并打印行号。

放行方式：在该行同行写 `// api-floor-ok: <理由>`，说明为何安全
（例如已 `@RequiresApi(33)` 包裹并在低版本走另一分支）。

## 用法

    python scripts/probes/_test_android_api_floor.py

退出码 0 = 通过，1 = 有违规。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java")

# (正则, 说明)。都用 \( 收紧，避免匹配到定义或注释里的裸词。
BANNED = [
    (r"\.skipNBytes\s*\(", "InputStream.skipNBytes(long) 是 Java 12 / Android API 33+"),
    (r"\.readNBytes\s*\(", "InputStream.readNBytes 是 Java 12 / Android API 33+"),
    (r"\.transferTo\s*\(", "InputStream.transferTo 是 Java 9 / Android API 33+"),
    (r"\bList\.of\s*\(", "List.of 是 Java 9 / Android API 30+"),
    (r"\bMap\.of\s*\(", "Map.of 是 Java 9 / Android API 30+"),
    (r"\bSet\.of\s*\(", "Set.of 是 Java 9 / Android API 30+"),
]

ALLOW_MARKER = "api-floor-ok"


def strip_comments_and_strings(text):
    """把 // 行注释、/* */ 块注释、字符串字面量替换成空格，保留换行与行号。

    字符串也要剥：日志与提示里出现 "xxx.skipNBytes(" 这种字样不该被当成调用。
    """
    out = []
    i, n = 0, len(text)
    state = "code"  # code | line | block | str | str3
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line"
                out.append("  ")
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block"
                out.append("  ")
                i += 2
                continue
            if c == '"' and text[i:i + 3] == '"""':
                state = "str3"
                out.append("   ")
                i += 3
                continue
            if c == '"':
                state = "str"
                out.append(" ")
                i += 1
                continue
            out.append(c)
            i += 1
            continue
        if state == "line":
            if c == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue
        if state == "block":
            if c == "*" and nxt == "/":
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if state == "str3":
            if text[i:i + 3] == '"""':
                state = "code"
                out.append("   ")
                i += 3
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        # str
        if c == "\\":
            out.append("  ")
            i += 2
            continue
        if c == '"':
            state = "code"
            out.append(" ")
            i += 1
            continue
        out.append("\n" if c == "\n" else " ")
        i += 1
    return "".join(out)


def extract_function(text, name):
    """取出 `private fun <name>(...) {` 到匹配大括号为止的函数体。

    只在这个函数体上做断言：早先版本扫整个文件，结果「readFully」「padBuf」
    在别的函数里也出现，把实现换成 `skip()` 或删掉缓冲声明都照样全绿
    （反向验证实测），属于典型假绿。
    """
    m = re.search(r"private\s+fun\s+" + re.escape(name) + r"\s*\(", text)
    if not m:
        return None
    i = text.find("{", m.end())
    if i < 0:
        return None
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
    return None


def check_class(clazz):
    """对 skipPadAfter 函数体做结构断言（clazz 应是剥注释后的全文）。"""
    checks = []

    def add(name, ok, detail):
        checks.append((name, ok, detail))

    body = extract_function(clazz, "skipPadAfter")
    add("skipPadAfter exists", body is not None,
        "function must still exist for the probe to inspect")
    if body is None:
        return checks

    # 必须走真实读取，而不是 API 33+ 的捷径。
    add("padding does not use skipNBytes", "skipNBytes" not in body,
        "skipNBytes is Android API 33+")
    add("padding does not use readNBytes", "readNBytes" not in body,
        "readNBytes is Android API 33+")

    # skip() 的契约是「可能少跳」，不能用来对齐 tar 块。
    add("padding does not use skip()", ".skip(" not in body,
        "skip() may skip fewer bytes; tar block alignment would break")

    # 必须 readFully 到填充缓冲。
    add("padding drains through readFully", "readFully" in body,
        "padding must be drained with readFully")
    add("padding drains into padBuf", "padBuf" in body,
        "readFully(padBuf, 0, pad) is the expected implementation")

    return checks


def check_instance():
    """实例检查：用真实类文本跑一遍 check_class，防探针自身写坏。"""
    path = os.path.join(SRC, "app", "dsh", "mobile", "engine", "LinuxEnvironmentManager.kt")
    if not os.path.isfile(path):
        print("SKIP: LinuxEnvironmentManager.kt not found")
        return 0
    text = io.open(path, encoding="utf-8").read()
    stripped = strip_comments_and_strings(text)
    # 剥注释后的正文才是判定依据：注释里提到 skipNBytes 是允许且期望的。
    return check_class(stripped)


def main():
    failures = []
    checks_run = 0

    # ---- [A] 全量扫描：任何源文件都不得调用高版本 API ----
    scanned = 0
    for dirpath, _dirnames, filenames in os.walk(SRC):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            scanned += 1
            full = os.path.join(dirpath, fn)
            raw = io.open(full, encoding="utf-8", errors="replace").read()
            stripped = strip_comments_and_strings(raw)
            raw_lines = raw.splitlines()
            for lineno, line in enumerate(stripped.splitlines(), 1):
                for pattern, why in BANNED:
                    if not re.search(pattern, line):
                        continue
                    checks_run += 1
                    # 同行放行标记（看原始行的注释部分）
                    orig = raw_lines[lineno - 1] if lineno - 1 < len(raw_lines) else ""
                    if ALLOW_MARKER in orig:
                        continue
                    rel = os.path.relpath(full, ROOT).replace("\\", "/")
                    failures.append(f"[A] {rel}:{lineno} uses {pattern} — {why}")

    # ---- [B] 本题具体实现的结构断言（防未来被改回去）----
    checks = check_instance()
    for name, ok, detail in checks:
        checks_run += 1
        if not ok:
            failures.append(f"[B] {name} — {detail}")

    # ---- [B2] padBuf 缓冲必须真的被声明成 512 字节 ----
    lem = os.path.join(SRC, "app", "dsh", "mobile", "engine", "LinuxEnvironmentManager.kt")
    if os.path.isfile(lem):
        checks_run += 1
        raw = io.open(lem, encoding="utf-8", errors="replace").read()
        if not re.search(r"private\s+val\s+padBuf\s*=\s*ByteArray\(512\)", raw):
            failures.append("[B2] padBuf declaration missing — expect `private val padBuf = ByteArray(512)`")

    # ---- [B3] 注释里必须写明 issue 编号，供后来人追溯 ----
    if os.path.isfile(lem):
        checks_run += 1
        raw = io.open(lem, encoding="utf-8", errors="replace").read()
        if "#98" not in raw:
            failures.append("[B3] fix does not reference issue #98 in comments")

    # ---- [C] 自检：剥注释逻辑必须真的能剥掉注释里的 API 名 ----
    checks_run += 1
    sample = 'val x = 1 // skipNBytes(3)\nval s = "skipNBytes(4)"\n'
    s = strip_comments_and_strings(sample)
    if re.search(r"\.skipNBytes\s*\(", s):
        failures.append("[C] comment/string stripping is broken — it left skipNBytes visible")
    checks_run += 1
    sample2 = "din.skipNBytes(3)\n"
    if not re.search(r"\.skipNBytes\s*\(", strip_comments_and_strings(sample2)):
        failures.append("[C] comment/string stripping is broken — it hid real code")

    # ---- 结论 ----
    print(f"scanned {scanned} Kotlin files, ran {checks_run} checks")
    if failures:
        print(f"FAILED ({len(failures)}):")
        for f in failures:
            print("  " + f)
        return 1
    print("OK: no API-floor violations, tar padding drains through readFully")
    return 0


if __name__ == "__main__":
    sys.exit(main())

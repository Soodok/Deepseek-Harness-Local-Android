#!/usr/bin/env python3
"""修 Kotlin 字符串字面量里的「裸换行」。

## 为什么需要这个脚本
批量改 `EndpointCatalog.kt` 这类密集字符串的源码时，`\n` 很容易在多轮
shell/Python 转义里退化成真实换行，结果字符串在源码中断成两行，报
`Expecting '"'`。同一个坑在本仓库已经踩了三次（见 AI_CONTEXT 十四/十六），
所以把它做成脚本而不是每次都手改。

## 判据
一行里**未转义的双引号个数是奇数** → 这个字符串没在本行收尾 → 把下一行
拼上来，中间补 `\n` 转义，直到引号补齐。

用法：
    python scripts/fix-kotlin-string-newlines.py <file.kt> [more.kt ...]
不带参数时默认修 `EndpointCatalog.kt`。
"""

import pathlib
import sys

BS = chr(92)


def unescaped_quote_count(s: str) -> int:
    cnt = 0
    i = 0
    while i < len(s):
        if s[i] == BS:
            i += 2
            continue
        if s[i] == '"':
            cnt += 1
        i += 1
    return cnt


def is_broken(line: str) -> bool:
    s = line.strip()
    if '"' not in s:
        return False
    return unescaped_quote_count(s) % 2 == 1


def repair(path: pathlib.Path) -> int:
    lines = path.read_text(encoding="utf-8").split("\n")
    out = []
    i = 0
    fixed = 0
    while i < len(lines):
        ln = lines[i]
        if is_broken(ln):
            merged = ln
            j = i
            while is_broken(merged) and j + 1 < len(lines):
                merged = merged.rstrip() + BS + "n" + lines[j + 1].lstrip()
                j += 1
            out.append(merged)
            fixed += 1
            i = j + 1
        else:
            out.append(ln)
            i += 1
    if fixed:
        path.write_text("\n".join(out), encoding="utf-8")
    return fixed


def main(argv):
    if len(argv) > 1:
        targets = [pathlib.Path(a) for a in argv[1:]]
    else:
        root = pathlib.Path(__file__).resolve().parents[1]
        targets = [root / "app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt"]
    total = 0
    for t in targets:
        if not t.is_file():
            print("skip (not a file): %s" % t)
            continue
        n = repair(t)
        total += n
        print("%-60s %s" % (t.name, ("repaired %d" % n) if n else "clean"))
    print()
    print("total repaired: %d" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

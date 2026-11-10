"""探针共用：从 Kotlin/Java 源码里剥掉注释。

## 为什么每个探针都需要它

断言写 `"MAX_PAIR_ATTEMPTS" in src` 时，**KDoc 注释里也会出现这个标识符**。
于是把真正的代码删掉、注释留着，断言照样通过 —— 反向验证时就表现为「改坏了
却不变红」的假绿。这一轮我在三条 C 段断言上正是这么栽的。

规则：
· `//` 行注释（但 `://` 不算，那是 URL）
· `/* */` 块注释，支持嵌套（Kotlin 允许）
· 字符串字面量里的 `//` 与 `/*` 不算注释（`"http://..."` 很常见）

用法：
    import kstrip
    code = kstrip.strip_comments(open(path, encoding="utf-8").read())
"""

from __future__ import annotations

NL = chr(10)
BACKSLASH = chr(92)
QUOTE = chr(34)


def strip_comments(src: str) -> str:
    out = []
    i = 0
    n = len(src)
    in_line = False
    in_block = 0
    in_str = False

    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""

        if in_line:
            if c == NL:
                in_line = False
                out.append(c)
            i += 1
            continue

        if in_block:
            if c == "/" and nxt == "*":
                in_block += 1
                i += 2
                continue
            if c == "*" and nxt == "/":
                in_block -= 1
                i += 2
                continue
            if c == NL:
                out.append(c)
            i += 1
            continue

        if in_str:
            out.append(c)
            if c == BACKSLASH:
                if i + 1 < n:
                    out.append(src[i + 1])
                i += 2
                continue
            if c == QUOTE:
                in_str = False
            i += 1
            continue

        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue

        if c == "/" and nxt == "*":
            in_block = 1
            i += 2
            continue

        if c == QUOTE:
            in_str = True
            out.append(c)
            i += 1
            continue

        out.append(c)
        i += 1

    return "".join(out)


def line_of(src: str, needle: str) -> int:
    """needle 第一次出现的行号（1 基）；找不到返回 0。"""
    idx = src.find(needle)
    if idx < 0:
        return 0
    return src.count(NL, 0, idx) + 1


if __name__ == "__main__":
    import sys

    print(strip_comments(open(sys.argv[1], encoding="utf-8").read()))

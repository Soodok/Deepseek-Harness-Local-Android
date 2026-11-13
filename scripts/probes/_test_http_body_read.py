#!/usr/bin/env python3
"""_dsh_http.sh 读响应体的三条不变量（真机 2026-11-13 事故）

背景：`read -N` 在设备 bash 上对非阻塞 socket 只读「当前可用的那一段」就返回，
且返回非 0 时**已读内容仍在变量里**。旧写法只看返回值就把整段丢掉，于是
「读到 19031 / 期望 25571」被判成彻底失败 —— 调用方拿到空串 + 报错，
`scr dump`、`dev status` 全废，而同端点 curl 直连完全正常。
"""
import io
import re
import sys

SRC = "app/src/main/assets/gates/_dsh_http.sh"


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(text):
    """剥掉注释行 —— 本仓教训：注释里出现的字符串会骗过「必须存在/必须不存在」类断言。"""
    out = []
    for line in text.split("\n"):
        s = line.strip()
        if s.startswith("#"):
            continue
        out.append(line)
    return "\n".join(out)


def fn_body(text, name):
    """截取 shell 函数体（`name() {` 到同缩进的 `}`）——不按全文件搜，避免命中别处。"""
    lines = text.split("\n")
    start = None
    for i, line in enumerate(lines):
        if re.match(r"^\s*" + re.escape(name) + r"\s*\(\)\s*\{", line):
            start = i
            break
    if start is None:
        return None
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if depth <= 0 and i > start:
            return "\n".join(lines[start:i + 1])
    return "\n".join(lines[start:])


def main():
    ok = fail = 0

    def check(label, cond):
        nonlocal ok, fail
        if cond:
            ok += 1
            print("  ok   %s" % label)
        else:
            fail += 1
            print("  FAIL %s" % label)

    src = read(SRC)
    code = code_only(src)

    body_fn = fn_body(code, "dsh_http")
    check("dsh_http 函数存在", body_fn is not None)
    fn = body_fn or ""

    # B1 —— 不许再出现「一次 read -N 读完」的旧写法：必须配 while 循环。
    # B1 —— 必须循环读（不能一次 read -N 就当读完了）。
    check("B1 读体使用循环（while : 或 while [）",
          re.search(r"while\s+(:|\[)", fn) is not None)
    check("B1b 循环里有 read -r -N", re.search(r"read\s+-r\s+-N", fn) is not None)
    check("B1c 已经没有单次裸 read -N 直接赋值给 _body 的写法",
          re.search(r"read\s+-r\s+-N\s+\"?\$_clen\"?\s+[^\n]*\s_body\s*<&3", fn) is None)

    # B2 —— 循环累加，空格子为终点（EOF 或超时）。
    check("B2 有 _body+= 累加", "_body+=" in fn)
    check("B2b 拿到空块即结束（[ -z \"$_chunk\" ] && break）",
          re.search(r'\[\s*-z\s+"\$_chunk"\s*\]\s*&&\s*break', fn) is not None)

    # B3 —— 必须有总时限，否则半开连接会把调用方永久挂住。
    check("B3 有 deadline 计算", re.search(r"_deadline=\$\(\(\s*\$\(date\s*\+%s\)", fn) is not None)
    check("B3b 剩余时间取 deadline 差值", re.search(r"_left=\$\(\(\s*_deadline\s*-", fn) is not None)
    check("B3c 剩余时间兜底到 1（read -t 0 表示不超时）",
          re.search(r'\[\s*"\$_left"\s*-lt\s*1\s*\]\s*&&\s*_left=1', fn) is not None)

    # B4 —— ★核心：绝不能用 ${#var} 跟 Content-Length 比（那是字符数 vs 字节数）。
    check("B4 不再用 ${#_body} 与 Content-Length 比较",
          re.search(r'\$\{#_body\}"?\s*(-lt|-ne|-eq|-gt)', fn) is None)
    check("B4a 真实字节数用 wc -c 得来",
          re.search(r"_got_bytes=\$\(printf .*\| *wc -c\)", fn) is not None)
    check("B4b 按 _got_bytes 与 _clen 不等才判失败",
          re.search(r'\[\s*"\$_got_bytes"\s*!=\s*"\$_clen"\s*\]', fn) is not None)
    check("B4c 失败文案报出实得与期望字节",
          re.search(r"拿到\s*\$\{_got_bytes", fn) is not None)
    check("B4d 失败文案说明已读部分已输出",
          re.search(r"已读部分已照常输出", fn) is not None)

    # B5 —— 不许回退到 fork 外部命令（容器模式 REEXEC: preload FAIL 老坑）。
    for bad in ["timeout ", "head -c", "cat <&3", "tr -d"]:
        check("B5 读体不含 `%s`" % bad, bad not in fn)

    # B6 —— LC_ALL=C 必须**紧贴 read -N**（否则按字符计数，中文响应读错）。
    # 不能只判「文件里有 LC_ALL=C」：`_dsh_byte_len` 与无 Content-Length 的分支
    # 各有一处，于是删掉 read -N 那处照样绿 —— 这正是本仓反复踩的假绿形状。
    check("B6 read -N 那行带 LC_ALL=C",
          re.search(r"LC_ALL=C\s+IFS=\s+read\s+-r\s+-N", fn) is not None)

    print("\n%d passed, %d failed" % (ok, fail))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())

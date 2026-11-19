#!/usr/bin/env python3
"""Guard the 3080 reclaim script (v1.6.0-beta.18)."""
import os
import re
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
SUPERVISOR = os.path.join(ENGINE, "EngineSupervisor.kt")
RECLAIM = os.path.join(ENGINE, "PortReclaim.kt")
TEST = os.path.join(ROOT, "app", "src", "test", "java", "app", "dsh", "mobile", "engine", "PortReclaimTest.kt")

passed = 0
failed = []


def check(name, cond, detail=""):
    global passed
    if cond:
        passed += 1
        print("PASS  " + name)
    else:
        failed.append(name)
        print("FAIL  " + name + (("  -- " + detail) if detail else ""))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def raw_strings(src):
    i = 0
    q = chr(34) * 3
    while True:
        start = src.find(q, i)
        if start < 0:
            return
        end = src.find(q, start + 3)
        if end < 0:
            return
        yield src[start + 3:end]
        i = end + 3


def strip_comments(src):
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return src


DOLLAR = chr(36)
BACKSLASH = chr(92)
INTERP = chr(36) + chr(123) + chr(39) + chr(36) + chr(39) + chr(125)


def escaped_dollar_hits(src):
    hits = []
    for body in raw_strings(src):
        for line in body.splitlines():
            idx = line.find(BACKSLASH + DOLLAR)
            if idx >= 0:
                hits.append(line.strip()[:120])
    return hits


def main():
    check("A1 PortReclaim.kt exists", os.path.isfile(RECLAIM))
    if not os.path.isfile(RECLAIM):
        print("\n%d passed, %d failed" % (passed, len(failed)))
        return 1

    rsrc = read(RECLAIM)
    hits = escaped_dollar_hits(rsrc)
    check("A2 raw string has no backslash-dollar", not hits, "hits=" + repr(hits[:2]))

    check(
        "B1 script reads /proc/net/tcp{,6} looking for LISTEN",
        "/proc/net/tcp" in rsrc and "/proc/net/tcp6" in rsrc and "0A" in rsrc,
    )
    check("B2 script resolves socket inode then kills", "socket:" in rsrc and "kill -9 " in rsrc)
    check("B3 script ends with a bare true", rsrc.count("true") >= 2)

    # readlink 给的是 socket:[967201]，tcp 表的 inode 列是裸 967201；只剥尾部会留下
    # 前导 [，case 的 *" $ino "*) 永远不匹配 —— 又一条 exit 0 的静默 no-op。
    # 源码写的是插值形态：ino=${&apos;$&apos;}{ino#[} / ino=${&apos;$&apos;}{ino%]}。
    def has_frag(src, frag):
        return (frag in src) or (frag.replace(DOLLAR, INTERP) in src)

    check(
        "B4 socket inode is stripped on both sides",
        has_frag(rsrc, "ino=" + DOLLAR + "{ino#[}") and has_frag(rsrc, "ino=" + DOLLAR + "{ino%]}"),
    )

    ssrc = read(SUPERVISOR)
    ssrc_nc = strip_comments(ssrc)
    check("C1 Supervisor calls PortReclaim.script", "PortReclaim.script(" in ssrc_nc)
    check("C2 Supervisor no longer inlines PORT_HEX block", "PORT_HEX=" not in ssrc_nc)

    check("D1 Supervisor falls back to PortReclaim.cmdlineSweepScript", "PortReclaim.cmdlineSweepScript(" in ssrc_nc)
    check("D2 cmdline sweep excludes lan-web-proxy", "lan-web-proxy" in rsrc)

    # D3/D4：tr 的 NUL 转义必须恰好一个反斜杠。写成两个，sh 会把 NUL 换成字面
    # 「反斜杠 0」，cmdline 的分隔符还在，case 永远匹配不到 —— 又一条 exit 0 的静默 no-op。
    sweep_fn = rsrc[rsrc.find("fun cmdlineSweepScript"):]
    single = "tr '" + BACKSLASH + "0' "
    doubled = "tr '" + BACKSLASH + BACKSLASH + "0' "
    check("D3 cmdline sweep uses single backslash NUL", single in sweep_fn, "need " + repr(single))
    check("D4 cmdline sweep does not double the backslash", doubled not in sweep_fn, "found " + repr(doubled))

    # D5：清场结论必须进 engine.log，否则用户导出的日志里看不出清场跑没跑、成没成。
    check(
        "D5 reclaim result is appended to engine.log",
        "[port-reclaim] port=" in ssrc_nc and "EngineLog.append" in ssrc_nc,
    )

    if os.path.isfile(TEST):
        tsrc = strip_comments(read(TEST))
        check("E1 unit test asserts no escaped dollar", "renderedScriptHasNoEscapedDollars" in tsrc)
        check("E2 unit test asserts bare awk fields", "awkFieldReferencesAreBare" in tsrc)
        check("E3 unit test asserts bare true tail", "scriptEndsWithTrue" in tsrc)
        check("E4 unit test asserts single-backslash NUL", "cmdlineSweepUsesSingleBackslashNul" in tsrc)
        check("E5 unit test asserts inode bracket strip", "socketInodeIsStrippedOnBothSides" in tsrc)
    else:
        check("E1 unit test file exists", False, TEST)

    sabotaged = rsrc.replace(INTERP + "4 ==", BACKSLASH + DOLLAR + "4 ==", 1)
    if sabotaged == rsrc:
        check("F negative control reproducible", False, "pattern missing")
    else:
        tmp = tempfile.mkdtemp()
        p = os.path.join(tmp, "PortReclaim.kt")
        with open(p, "w", encoding="utf-8") as f:
            f.write(sabotaged)
        bad = escaped_dollar_hits(read(p))
        check("F negative control turns red", bool(bad), "sabotage produced no hit")

    print("\n%d passed, %d failed" % (passed, len(failed)))
    if failed:
        print("FAILED: " + ", ".join(failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

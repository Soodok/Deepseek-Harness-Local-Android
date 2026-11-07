#!/usr/bin/env python3
"""外部文件入口（SEND / VIEW / 分享到 DSH）的契约探针。

背景：App 此前只有 LAUNCHER 入口，`SEND` / `SEND_MULTIPLE` / `VIEW` /
`PROCESS_TEXT` 四个 intent-filter 一个都没有 —— 用户在微信里点一个 PDF
「用其他应用打开」，或长按文件「分享」，候选列表里根本不出现我们。

这条链路有四个必须同时成立的环节，任何一个断了用户都只会看到「没反应」：
1. **manifest 注册**：intent-filter 真的存在，且 exported（系统要能唤起）
2. **不可信输入处理**：文件名要清洗（防目录穿越）、同名不覆盖
3. **落盘**：content:// 的临时授权会被回收，必须先复制进私有目录
4. **投递**：MainActivity 在页面就绪后把路径注入 WebUI

第 4 步的 JS 注入行为只有真机/模拟器能验（本探针只验代码形态与契约）。
"""

from __future__ import annotations

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
SHARE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ShareInboxActivity.kt")
INBOX = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/Inbox.kt")
MAIN = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MainActivity.kt")
ENGINE_CFG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")

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


def read(path: str) -> str:
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(src: str) -> str:
    """去掉 // 与 /* */ 注释，只留可执行代码。

    这个仓库的注释里大量写着「不要这样做」的反例，裸子串断言会命中它们
    而永远变绿。
    """
    out: list[str] = []
    i = 0
    n = len(src)
    in_line = in_block = in_str = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\":
                if i + 1 < n:
                    out.append(src[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c == '"':
            in_str = True
        out.append(c)
        i += 1
    return "".join(out)


def main() -> int:
    manifest = read(MANIFEST)
    share = read(SHARE)
    share_code = strip_comments(share)
    inbox = read(INBOX)
    inbox_code = strip_comments(inbox)
    main = read(MAIN)
    main_code = strip_comments(main)
    engine_cfg = read(ENGINE_CFG)

    print("\n== 1. manifest 注册 ==")
    # 定位 ShareInboxActivity 那一段
    m = re.search(r'<activity\s+android:name="\.ShareInboxActivity"(.*?)</activity>',
                  manifest, re.S)
    check("ShareInboxActivity declared", m is not None, "activity block missing")
    block = m.group(1) if m else ""

    check("ShareInboxActivity is exported", 'android:exported="true"' in block,
          "system cannot launch it without exported=true")
    # 三个 filter 必须各自是一个 <intent-filter> 块，不能靠字符串出现与否判断
    # —— 把 SEND_MULTIPLE 改成 SEND_MULTIPLE_X 后字符串仍含子串，断言不会红。
    filters = re.findall(r"<intent-filter>(.*?)</intent-filter>", block, re.S)
    action_sets = [set(re.findall(r'action android:name="([^"]+)"', f)) for f in filters]
    for action in ("android.intent.action.SEND",
                   "android.intent.action.SEND_MULTIPLE",
                   "android.intent.action.VIEW"):
        found = any(action in s for s in action_sets)
        check(f"{action} has its own filter", found, f"no filter declares {action}")
    check("SEND accepts any mime type",
          re.search(r'action android:name="android.intent.action.SEND".*?mimeType="\*/\*"',
                    block, re.S) is not None,
          "SEND without */* will not appear in most share sheets")
    check("VIEW declares concrete mime types",
          block.count('android:mimeType="') >= 6,
          "VIEW needs explicit types or the entry never shows up")
    check("VIEW registers content scheme", 'android:scheme="content"' in block,
          "most shares use content:// not file://")
    check("excludeFromRecents is set", 'android:excludeFromRecents="true"' in block,
          "receiver should not leave a task in recents")

    # Theme.NoDisplay 是真实踩过的坑：它要求 onCreate 同步 finish()，
    # 而复制文件必须后台线程。
    check("does NOT use Theme.NoDisplay",
          "Theme.NoDisplay" not in block,
          "NoDisplay crashes when finish() is deferred to a worker thread")
    check("uses a finishing-friendly translucent theme",
          "Translucent" in block, "expected a non-NoDisplay theme")

    print("\n== 2. 不可信输入处理 ==")
    check("sanitizes path separators",
          "replace('/', '_')" in share_code and "replace('\\\\', '_')" in share_code,
          "external filename could traverse directories")
    check("strips control characters", ".filter { it.code >= 0x20 }" in share_code,
          "newlines/NULs in names would break the queue")
    check("strips leading dots", "trimStart('.')" in share_code,
          "'.'/'..' style names are dangerous")
    check("caps filename length", "take(120)" in share_code, "unbounded name length")
    # 必须真的「存在就换名」：`if (!first.exists()) return first` 这条早退是关键，
    # 改成 if(true) 就意味着永远返回第一个名字（后到的覆盖先到的）。
    check("dedupe short-circuits only when the name is free",
          "if (!first.exists()) return first" in share_code,
          "missing the exists() short-circuit")
    check("dedupe appends a timestamp to the stem",
          re.search(r'return File\(dir, "\$stem-\$\{System\.currentTimeMillis\(\)\}\$ext"\)',
                    share_code) is not None,
          "no collision-breaking suffix")

    print("\n== 3. 落盘到私有目录 ==")
    check("inboxDir exists in EngineConfig", "fun inboxDir(" in engine_cfg,
          "no landing directory")
    check("inbox lives under filesDir",
          re.search(r'fun inboxDir\([^)]*\)[^=]*=\s*\n?\s*File\(ctx\.filesDir, "inbox"\)',
                    engine_cfg) is not None,
          "inbox must be app-private")
    check("copies the stream instead of keeping the URI",
          "openInputStream" in share_code and "copyTo" in share_code,
          "content:// grants are temporary and get revoked")
    check("copy uses a buffered chunk", "64 * 1024" in share_code,
          "unbuffered copy is slow on large files")
    check("deletes partial file on failure",
          re.search(r'catch \(e: Exception\)\s*\{[^}]*delete\(\)', share_code, re.S) is not None,
          "a failed copy would leave a truncated file")
    # `Thread {` 裸子串太弱（改成 `run {` 后仍有别的 Thread 字样吗？没有，但要更严）。
    # 要求它真的是「起线程 + 里面做复制」这个组合。
    # ⚠ 不能用 "Thread {" 裸子串：`runOnUiThread {` 里就含这个串，把起线程改成
    # `run {` 之后断言仍然命中（实测踩过）。必须按**整行**判定起线程这一句。
    spawn_lines = [ln.strip() for ln in share_code.splitlines()]
    check("copy runs on a spawned thread",
          "Thread {" in spawn_lines and "val saved = ArrayList<String>(uris.size)" in share_code,
          "copying on the main thread ANRs on big files")

    print("\n== 4. 队列与投递 ==")
    check("Inbox object exists", "object Inbox" in inbox_code, "no queue")
    check("pending queue is JSON (not line-based)",
          "JSONArray" in inbox_code,
          "line format breaks on filenames containing newlines")
    # 原子性 = 先写临时文件再 rename。若改成直接写 target，rename 仍在（别处用），
    # 所以必须断言「writeText 的目标是 tmp」这个具体形态。
    # 「先写临时文件」的实质是：tmp 那次写入带着**真实内容** arr.toString()。
    # 若把 tmp 写成空串（或干脆不写），target 的降级分支仍在、rename 也仍在，
    # 所以必须锚定这条具体的调用形态。
    check("queue writes real content to the temp file",
          'tmp.writeText(arr.toString())' in inbox_code,
          "temp file written without content (queue silently empty)")
    check("queue promotes temp to target via rename",
          "tmp.renameTo(target)" in inbox_code,
          "no atomic promotion")
    # 原子性的另一半：try 块里**只**写 tmp，不提前写 target。
    # target.writeText 只允许出现在 rename 失败后的降级分支（if 内），
    # 所以它前面必须紧跟 `if (!tmp.renameTo(target))`。
    check("target is written directly only as a rename fallback",
          "if (!tmp.renameTo(target)) {" in inbox_code
          and inbox_code.index("target.writeText") > inbox_code.index("tmp.renameTo"),
          "target written before rename — a crash leaves a half-written queue")
    check("queue falls back when rename fails", "target.writeText" in inbox_code,
          "some filesystems refuse rename")
    # drain 必须真的清空；`fun drain(` 存在不代表它会清。
    check("drain clears the queue after reading",
          "val got = pending(ctx)" in inbox_code
          and "if (got.isNotEmpty()) writePending(ctx, emptyList())" in inbox_code,
          "drain returns but never empties the queue (files delivered twice)")
    check("delivery happens after page finished",
          "deliverInboxFiles" in main_code, "no delivery call in MainActivity")
    check("delivery injects into the composer",
          "setRangeText" in main_code,
          "assigning .value does not notify the framework")
    check("delivery dispatches an input event",
          "new Event('input'" in main_code,
          "controlled components need the native event")
    # 手拼 "\"" + text + "\"" 也含引号，但不转义内部引号/换行 → 注入 JS 语法错误。
    # 断言必须是 quote() 这个 API。
    # 文件里另有两处 `.quote(text)`（别的方法），只断言 API 名会命中它们而恒绿。
    # 必须锚定 deliverInboxFiles 里那个具体赋值。
    check("delivery serialises text with a real JSON quoter",
          "val jsText = org.json.JSONObject.quote(text)" in main_code,
          "hand-rolled quoting breaks on paths containing quotes")

    print(f"\n{CHECKS - len(FAILURES)}/{CHECKS} checks passed")
    if FAILURES:
        print("FAILED:")
        for f in FAILURES:
            print("  -", f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

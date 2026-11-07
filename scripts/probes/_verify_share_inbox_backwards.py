#!/usr/bin/env python3
"""`_test_share_inbox.py` 的反向验证：逐条改坏，确认真的变红。

无法变红的断言是假绿 —— 它保护不了任何东西。每个变换都先断言锚点
命中次数恰好为 1，命中 0 就报「变换未生效」而不是「通过」。
"""

from __future__ import annotations

import io
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
SHARE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ShareInboxActivity.kt")
INBOX = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/Inbox.kt")
MAIN = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MainActivity.kt")
ENGINE_CFG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
PROBE = os.path.join(ROOT, "scripts/probes/_test_share_inbox.py")

results: list[tuple[str, bool, str]] = []


def run_probe() -> tuple[int, str]:
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    p = subprocess.run([sys.executable, PROBE], capture_output=True, text=True,
                       env=env, encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def mutate(label: str, path: str, old: str, new: str) -> None:
    src = io.open(path, encoding="utf-8").read()
    n = src.count(old)
    if n != 1:
        results.append((label, False, f"ANCHOR COUNT {n} != 1 (变换未生效)"))
        print(f"  SKIP  {label}: anchor count {n}")
        return
    backup = src
    try:
        io.open(path, "w", encoding="utf-8", newline="\n").write(src.replace(old, new))
        rc, _ = run_probe()
        red = rc != 0
        results.append((label, red, "" if red else "探针未变红"))
        tag = "RED " if red else "GREEN(!!!)"
        print(f"  {tag} {label}")
    finally:
        io.open(path, "w", encoding="utf-8", newline="\n").write(backup)


def main() -> int:
    rc, out = run_probe()
    print(f"baseline: rc={rc}")
    if rc != 0:
        print(out[-1200:])
        return 1

    print("\n== 反向验证 ==")

    mutate("ShareInbox becomes unexported", MANIFEST,
           'android:name=".ShareInboxActivity"\n            android:exported="true"',
           'android:name=".ShareInboxActivity"\n            android:exported="false"')

    mutate("drop SEND_MULTIPLE filter", MANIFEST,
           '<action android:name="android.intent.action.SEND_MULTIPLE" />',
           '<action android:name="android.intent.action.SEND_MULTIPLE_X" />')

    mutate("regress to Theme.NoDisplay", MANIFEST,
           'android:theme="@android:style/Theme.Translucent.NoTitleBar"',
           'android:theme="@android:style/Theme.NoDisplay"')

    mutate("stop sanitising slashes", SHARE,
           "            .replace('/', '_')",
           "            .replace('/', '/')")

    mutate("stop stripping leading dots", SHARE,
           "            .trimStart('.')",
           "            .trimStart(' ')")

    mutate("stop deduplicating names", SHARE,
           "        if (!first.exists()) return first",
           "        if (true) return first")

    mutate("copy on the main thread", SHARE,
           "        Thread {",
           "        run {")

    mutate("keep the uri instead of copying", SHARE,
           "            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }",
           "            input.close()")

    mutate("queue write is no longer atomic", INBOX,
           "            tmp.writeText(arr.toString())",
           "            target.writeText(arr.toString()); tmp.writeText(arr.toString())")

    mutate("drain stops clearing the queue", INBOX,
           "        if (got.isNotEmpty()) writePending(ctx, emptyList())",
           "        if (false) writePending(ctx, emptyList())")

    mutate("delivery stops using setRangeText", MAIN,
           "                el.setRangeText(ins, start, end, 'end');",
           "                el.value = ins;")

    mutate("delivery stops quoting paths", MAIN,
           "        val jsText = org.json.JSONObject.quote(text)",
           "        val jsText = \"\\\"\" + text + \"\\\"\"")

    mutate("inbox moves out of filesDir", ENGINE_CFG,
           'File(ctx.filesDir, "inbox").apply { mkdirs() }',
           'File(ctx.cacheDir, "inbox").apply { mkdirs() }')

    print("\n== 汇总 ==")
    ok = sum(1 for _, r, _ in results if r)
    for label, red, reason in results:
        mark = "RED " if red else "FAIL"
        print(f"  {mark}  {label}" + (f"  <- {reason}" if reason else ""))
    print(f"\n{ok}/{len(results)} mutations turned the probe red")
    return 0 if ok == len(results) and results else 1


if __name__ == "__main__":
    sys.exit(main())

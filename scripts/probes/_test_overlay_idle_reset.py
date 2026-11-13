#!/usr/bin/env python3
"""悬浮窗「执行中」必须能自己回到空闲（2026-11-13 真机反馈）

用户原话：「AI 结束操控的时候，无障碍悬浮窗还是会一直提示正在操作中」。

根因链（两条叠加，缺一不可）：
1. `PhoneTask.snapshot().active` 要等 `idleMs = 3 分钟`无动作才变 false；
2. `onTurnEnd` 刻意只在「任务已不活跃」时才刷完成；
3. 而 `StatusOverlay.noteWorking()` 只 applyState(WORKING)，**不排任何回空闲定时器**。
于是 AI 收手后悬浮窗停在「执行中」，且没有任何路径会把它改回来。
"""
import io
import re
import sys

SRC = "app/src/main/java/app/dsh/mobile/StatusOverlay.kt"
TASK = "app/src/main/java/app/dsh/mobile/PhoneTask.kt"


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(text):
    out = []
    for line in text.split("\n"):
        if line.strip().startswith("//") or line.strip().startswith("*"):
            continue
        out.append(line)
    return "\n".join(out)


def fn_body(text, name, kind="fun"):
    """截 `fun name(` / `private fun name(` 到同缩进收尾的 `}`。"""
    lines = text.split("\n")
    start = None
    for i, line in enumerate(lines):
        if re.match(r"^\s*(private\s+|internal\s+|public\s+)?fun\s+" + re.escape(name) + r"\s*\(", line):
            start = i
            break
    if start is None:
        return None
    indent = len(lines[start]) - len(lines[start].lstrip())
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if depth <= 0 and i > start:
            return "\n".join(lines[start:i + 1])
    return "\n".join(lines[start:])


def main():
    ok = fail = 0

    def check(label, cond, extra=""):
        nonlocal ok, fail
        if cond:
            ok += 1
            print("  ok   %s" % label)
        else:
            fail += 1
            print("  FAIL %s" % label)

    src = read(SRC)
    code = code_only(src)

    nw = fn_body(code, "noteWorking")
    check("noteWorking 存在", nw is not None)

    # D1 —— 核心：执行中必须排兜底定时器，否则永不回空闲。
    check("D1 noteWorking 里排了回空闲兜底（scheduleIdle）",
          nw is not None and "scheduleIdle(" in nw)
    check("D1b 兜底时长引用 TASK_IDLE_TIMEOUT_MS（单一来源，不写死数字）",
          nw is not None and "TASK_IDLE_TIMEOUT_MS" in nw)

    # D2 —— 常量必须不小于任务空闲阈值（否则长任务被误显示为空闲）。
    m = re.search(r"TASK_IDLE_TIMEOUT_MS\s*=\s*([0-9L\s*]+)", code)
    check("D2 TASK_IDLE_TIMEOUT_MS 已定义", m is not None)
    if m:
        expr = m.group(1).strip().rstrip("L").strip()
        val = 1
        for part in expr.split("*"):
            part = part.strip().rstrip("L")
            if part.isdigit():
                val *= int(part)
        check("D2b 兜底时长 >= 3 分钟（对齐 PhoneTask.idleMs）", val >= 180_000,
              "got %d" % val)

    # D3 —— 与 PhoneTask 的 idleMs 对齐（防止一边改另一边没改）。
    task = read(TASK)
    tm = re.search(r"idleMs:\s*Long\s*=\s*([0-9L\s*]+)", task)
    check("D3 PhoneTask.idleMs 可读", tm is not None)
    if tm and m:
        texpr = tm.group(1).strip().rstrip("L").strip()
        tval = 1
        for part in texpr.split("*"):
            part = part.strip().rstrip("L")
            if part.isdigit():
                tval *= int(part)
        check("D3b 悬浮窗兜底 >= PhoneTask.idleMs", val >= tval,
              "sw=%d task=%d" % (val, tval))

    # D4 —— 定时器必须可被新状态取消，否则会把正在干活的任务刷成空闲。
    sid = fn_body(code, "scheduleIdle")
    check("D4 scheduleIdle 先 cancelIdleReset（可取消）",
          sid is not None and "cancelIdleReset()" in sid)
    check("D4b 有 cancelIdleReset 定义",
          fn_body(code, "cancelIdleReset") is not None or "fun cancelIdleReset" in code)

    # D5 —— 其它「执行中」入口也要能收尾（noteActionStarted 走同一条兜底）。
    nas = fn_body(code, "noteActionStarted")
    check("D5 noteActionStarted 也有兜底或复用 noteWorking",
          nas is not None and ("scheduleIdle(" in nas or "noteWorking" in nas))

    print("\n%d passed, %d failed" % (ok, fail))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())

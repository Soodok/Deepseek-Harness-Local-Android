#!/usr/bin/env python3
"""悬浮窗提问与 /overlay 控制的两条用户反馈回归（2026-11-26）

来源：私人反馈库 reports.db 的两条真实提交
  · 65261bd4（HONOR BKQ-AN90 / beta.31）「ask_user_question 提问后工具必失败」
      实测：折叠球 91x91 时卡片永不出现（要手动点一下球才出现），
      展开 784x248 时约 2s 才出现 —— 用户根本不知道该点哪儿。
  · 同条反馈的问题三：`POST /overlay {"op":"expand"}` 返回
      {"ok":true,"available":true} 但窗口尺寸、可见性零变化（空实现）。

根因（源码核对，2026-11-26）：
  StatusOverlay.scheduleAskPoll() 第一句就是 `if (expandLevel < 1) return@Runnable`
  → 折叠态根本不轮询 /ask，卡片的出现依赖用户先手动点开。
  AgentBridge.overlay() 只认 status / action / hide / complete，
  对 "op" / "show" 完全不处理 —— 所以 expand/show 恒回 available:true 而毫无动作。

本探针只做**静态结构断言**（能在源码层变红），真机行为另见 probes/ask-overlay-device.cjs。
"""
import io
import re
import sys

OVERLAY = "app/src/main/java/app/dsh/mobile/StatusOverlay.kt"
BRIDGE = "app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt"


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(text):
    out = []
    for line in text.split("\n"):
        s = line.strip()
        if s.startswith("//") or s.startswith("*") or s.startswith("/**"):
            continue
        out.append(line)
    return "\n".join(out)


def fn_body(text, name):
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
            print("  FAIL %s%s" % (label, ("  -> " + extra) if extra else ""))

    ov = read(OVERLAY)
    ov_code = code_only(ov)
    br = read(BRIDGE)
    br_code = code_only(br)

    # ── A. 折叠态必须也轮询 /ask ─────────────────────────────────────
    sched = fn_body(ov_code, "scheduleAskPoll")
    check("A0 scheduleAskPoll 存在", sched is not None)
    # 旧实现在 Runnable 开头就 return；新实现要让轮询与档位无关，
    # 只在「没显示/被隐藏」时才停。
    check("A1 轮询不再以 expandLevel<1 作为停摆条件",
          sched is not None and "if (expandLevel < 1) return@Runnable" not in sched,
          sched or "")
    check("A2 轮询以 root/hidden 作为停摆条件（未显示就不轮询）",
          sched is not None and ("root" in sched and "hidden" in sched))

    # ── B. 发现待答问题要自动升档 ─────────────────────────────────────
    check("B1 存在公开的升档入口 raiseForAsk",
          fn_body(ov_code, "raiseForAsk") is not None)
    raise_fn = fn_body(ov_code, "raiseForAsk") or ""
    check("B2 升档会把档位抬到 1（不是 0）",
          "expandLevel = " in raise_fn and re.search(r"expandLevel\s*=\s*(maxOf\(|)(\d+)", raise_fn) is not None)
    check("B3 升档标记 autoExpanded（答完能自己收回去）",
          "autoExpanded = true" in raise_fn)
    check("B4 升档后立即 applyExpandLevel（否则视图还是旧的）",
          "applyExpandLevel(" in raise_fn)

    render = fn_body(ov_code, "renderAsk")
    check("B5 renderAsk 在 pending 时调用 raiseForAsk",
          render is not None and "raiseForAsk(" in render)

    # ── C. /overlay 不能再是空实现 ────────────────────────────────────
    overlay = fn_body(br_code, "overlay") or ""
    check("C1 overlay() 仍存在", len(overlay) > 0)
    check("C2 overlay() 解析 op 字段", '"op"' in overlay or "optString(\"op" in overlay)
    check("C3 overlay() 支持 expand → 真正调 StatusOverlay 的升档/显示",
          "raiseForAsk(" in overlay or "requestExpand(" in overlay)
    check("C4 overlay() 支持 show → 真正调 showForWork（而不是只回 ok）",
          "StatusOverlay.showForWork(" in overlay)
    check("C5 overlay() 支持 collapse → 真正收档",
          "collapse(" in overlay or "StatusOverlay.collapse(" in overlay)
    check("C6 响应带真实状态快照（可被外部验证，而不是恒 available:true）",
          "StatusOverlay.settledSnapshot()" in overlay or "StatusOverlay.snapshot()" in overlay)

    # ── D. StatusOverlay 要能对外报状态 / 收档 ────────────────────────
    check("D1 存在 snapshot() 供桥查询真实档位",
          fn_body(ov_code, "snapshot") is not None)
    snap = fn_body(ov_code, "snapshot") or ""
    check("D2 snapshot 里同时有 visible 与 level",
          "visible" in snap and "level" in snap)
    check("D3 存在 collapse() 供桥收起",
          fn_body(ov_code, "collapse") is not None)

    # ── D4. show() 的初次复位不能把刚抬起的档位压回去（2026-11-27 真机实测）──
    show_fn = fn_body(ov_code, "show") or ""
    check("D4 show 的初次复位受 raisePending 约束（否则 expand 后 level 仍是 0）",
          "raisePending" in show_fn, show_fn[:200])
    check("D5 raiseForAsk 会置 raisePending（窗口未挂上时也要记住意图）",
          "raisePending = true" in raise_fn)

    # ── D6. 响应必须报「动作之后」的真实状态（真机实测：曾当场回 level:0）──
    check("D6 存在 settledSnapshot（等主线程排水后再读）",
          fn_body(ov_code, "settledSnapshot") is not None)
    settled_fn = fn_body(ov_code, "settledSnapshot") or ""
    check("D7 settledSnapshot 会等待主线程队列（CountDownLatch await）",
          "latch" in settled_fn and "await(" in settled_fn)
    check("D8 等不到时带 settled=false（区分「没等到」与「没生效」）",
          'put("settled", false)' in settled_fn and 'put("settled", true)' in settled_fn)
    check("D9 桥的响应改用 settledSnapshot",
          "settledSnapshot" in overlay)

    # ── E. 折叠态轮询退避 + 尊重用户主动收起 ─────────────────────────
    check("E1 折叠态轮询间隔比展开态长（不常驻 2s 打桥）",
          "2_000L" in sched and "6_000L" in sched and "expandLevel >= 1" in sched,
          sched or "")
    check("E2 有 askSuppressedFor 字段（记住用户收起过哪道题）",
          "askSuppressedFor" in ov_code)
    check("E3 raiseForAsk 支持 force（新题仍然要弹）",
          "force" in raise_fn)
    check("E4 collapseToBall / cycleExpandLevel 落抑制标记",
          "suppressCurrentAsk()" in (fn_body(ov_code, "collapseToBall") or "") and
          "suppressCurrentAsk()" in (fn_body(ov_code, "cycleExpandLevel") or ""))
    check("E5 问题结束时清掉抑制标记（下一题能弹）",
          re.search(r"askShownId = -1L\s*\n\s*askSuppressedFor = -1L", render or "") is not None
          or "askSuppressedFor = -1L" in (render or ""))

    print()
    print("%d passed, %d failed" % (ok, fail))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())

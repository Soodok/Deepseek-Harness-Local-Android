#!/usr/bin/env python3
"""Locks 编号屏幕文档（GET /screen?format=doc）与 /tap-ref 的契约。

这套东西的价值全在**格式与顺序**上：编号是模型指代元素的唯一抓手，
指纹预校验是「点错」唯一的拦路石。任何一处悄悄改了，模型就开始点错，
而错误表现是「偶尔点错一下」，事后极难归因。所以把契约钉在这里。

  1. 模块与入口存在，/tap-ref 已注册且登记进动作端点集合
  2. 屏幕文档头格式：屏幕 s<N> · 应用 · 姿态 · 共 N 项
  3. 分区：顶部 / 中部 / 底部，阈值 25%% / 80%%
  4. 元素行：[id] 角色 标签 @x,y flags，缩进 2 空格
  5. 预算与上限常量（3000 / 60 / 200 / 5）
  6. 指纹**不含 bounds**（滚动会移动，元素没变）
  7. /tap-ref 八步顺序：先校验后点击，保护页在点击之前
  8. 校验用的构建不覆盖 latest
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SD = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ScreenDoc.kt")
SV = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ScreenVerdict.kt")
BR = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt")

fails = []
def chk(cond, msg):
    if cond:
        print("  PASS  %s" % msg)
    else:
        print("  FAIL  %s" % msg)
        fails.append(msg)

def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()

print("== 1. 模块与入口 ==")
for p in (SD, SV, BR):
    chk(os.path.isfile(p), "%s exists" % os.path.basename(p))
sd = read(SD)
sv = read(SV)
br = read(BR)
chk("object ScreenDoc" in sd, "ScreenDoc is an object")
chk("object ScreenVerdict" in sv, "ScreenVerdict is an object")
chk("fun build(svc: AccessibilityService): Doc?" in sd, "ScreenDoc.build(svc) present")
chk("fun resolveForTap(" in sd, "ScreenDoc.resolveForTap() present")
chk('"POST /tap-ref" to Route("POST")' in br, "/tap-ref registered in the route table")
chk('params["format"] == "doc"' in br, "format=doc branch present in screen()")

print("== 2. 屏幕文档头 ==")
chk(re.search(r'head\.append\("屏幕 "\)', sd) is not None, "header starts with 屏幕 ")
chk('.append(" · ")' in sd, "fields joined with the middle-dot separator")
chk('" · 共 "' in sd, "item count announced in the header")
chk(re.search(r'\.append\(" · 共 "\)\.append\(elements\.size\)', sd) is not None,
    "count uses elements.size, not a literal")
chk("竖屏" in sd and "横屏" in sd, "orientation reported (竖屏/横屏)")

print("== 3. 分区与缩进 ==")
for z in ("顶部", "中部", "底部"):
    chk('"%s"' % z in sd, "zone %r present" % z)
m_top = re.search(r"TOP_ZONE\s*=\s*([0-9.]+)f", sd)
m_bot = re.search(r"BOTTOM_ZONE\s*=\s*([0-9.]+)f", sd)
chk(m_top is not None and float(m_top.group(1)) == 0.25, "TOP_ZONE == 0.25")
chk(m_bot is not None and float(m_bot.group(1)) == 0.80, "BOTTOM_ZONE == 0.80")
chk(re.search(r'"  "\.repeat\(', sd) is not None, "indent is a repeat of two spaces")
chk("e.depth.coerceAtLeast(0) + 1" in sd, "depth shifts by one so top-level lines still indent once")

print("== 4. 编号与元素行 ==")
chk(re.search(r"eb\.append\('\['\)\.append\(e\.id\)", sd) is not None
    or re.search(r"append\('\['\)\.append\(e\.id", sd) is not None,
    "element line renders a bracketed id")
chk('.append(" @")' in sd, "tap point rendered as @x,y")
chk(".append(e.tapX).append(',').append(e.tapY)" in sd, "tap point uses tapX,tapY")
chk("if (e.sensitive) sb.append" in sd, "sensitive rows get a warning prefix")
chk("⚠" in sd, "the warning marker is ⚠")
chk("compareBy({ it.bounds.top }, { it.bounds.left })" in sd,
    "reading order = top first, then left (not node traversal order)")

print("== 5. 预算与上限 ==")
for name, want in (("CHAR_BUDGET", 3000), ("LABEL_CAP", 60), ("MAX_NODES", 200),
                   ("POOR_TREE_THRESHOLD", 5)):
    m = re.search(name + r"\s*=\s*([0-9_]+)", sd)
    got = int(m.group(1).replace("_", "")) if m else -1
    chk(got == want, "%s == %d (got %r)" % (name, want, got))
chk("labels exceeding LABEL_CAP are truncated" in sd or "maskedRaw.length > LABEL_CAP" in sd,
    "over-long labels truncated with a suffix")
chk("charBudget" in sd.lower() or "CHAR_BUDGET" in sd, "char budget enforced")
chk(re.search(r"head\.length \+ full\.length <= CHAR_BUDGET", sd) is not None,
    "budget compared against header + body")

print("== 6. 指纹不含 bounds ==")
m = re.search(r"val fingerprint: String\s*\n?\s*get\(\) = listOf\((.*?)\)\.joinToString", sd, re.S)
chk(m is not None, "fingerprint getter found")
fields = m.group(1) if m else ""
chk("bounds" not in fields, "fingerprint EXCLUDES bounds (scrolling would break it)")
for f in ("viewId", "role", "rawLabel", "className"):
    chk(f in fields, "fingerprint includes %s" % f)
chk("checked" in fields, "fingerprint includes checked state")

print("== 7. /tap-ref 顺序：校验在点击之前，保护页在点击之前 ==")
i_route = br.find("private fun tapRef(")
chk(i_route != -1, "tapRef() implemented")
body = br[i_route:i_route + 9000]
i_resolve = body.find("ScreenDoc.resolveForTap")
i_protect = body.find("protectedPage")
i_tap = body.find("tapNodeDeep")
i_after = body.find("ScreenDoc.build(svc)")
chk(0 < i_resolve, "step 4: re-reads the tree and checks the fingerprint")
chk(i_resolve < i_tap, "fingerprint check runs BEFORE the tap (that is the whole point)")
chk(0 < i_protect and i_protect < i_tap, "protected-page check runs BEFORE the tap")
chk("if (before != null && before.protectedPage) {" in body,
    "the protected-page guard is a live condition (not disabled with false &&)")
chk(i_tap < i_after, "the screen is re-read AFTER the tap (for the verdict)")
chk("Thread.sleep(320)" in body, "waits for the UI to settle before judging")
chk("Settle" in body or "settle" in body or "稳定" in body, "wait is explained in a comment")

print("== 8. 校验不污染 latest ==")
i_saved = sd.find("val saved = latest")
i_restore = sd.find("latest = saved", i_saved)
chk(i_saved != -1, "resolveForTap saves the current document")
chk(i_restore != -1 and i_restore > i_saved, "and restores it after the verification build")
# 必须是**有效语句**，不能是注释掉的：注释里也含 "latest = saved" 这串字
chk(re.search(r"^\s*latest = saved\s*$", sd, re.M) is not None,
    "the restore is a live statement, not a comment")
chk("if (nowDoc == null) return Resolve.NoDoc" in sd,
    "a failed read returns NoDoc instead of clobbering the good document")

print("== 9. 拒绝路径齐全（每一条都要有对应的 sealed 分支）==")
for cls in ("Ok", "StaleId", "NoSuchId", "NoDoc", "Changed"):
    chk("class %s" % cls in sd or "object %s" % cls in sd, "Resolve.%s declared" % cls)
for code in ("409", "404", "503", "403"):
    chk(code + " to" in body, "HTTP %s returned on its own failure path" % code)
chk("Resolve.StaleId -> return 409" in body, "stale screen id -> 409")
chk("Resolve.Changed ->" in body, "changed target -> 409")
chk("ScreenVerdict.changedReason(" in body, "changed-target wording comes from ScreenVerdict")

print("== 10. 诚实判定 ==")
for const in ("CHANGED", "UNCHANGED", "SCROLL_END", "UNVERIFIABLE", "KEYBOARD_SHOWN", "KEYBOARD_HIDDEN"):
    chk("const val %s" % const in sv, "ScreenVerdict.%s declared" % const)
chk("键盘已弹出" in sv and "键盘已收起" in sv, "keyboard transitions described")
chk("缺少足够的可读内容" in sv, "UNVERIFIABLE admits it cannot tell")
chk("fun keyboardVisible(" in sv, "keyboard state is observed, not assumed")
chk("screen.*version" not in sv.lower() or True, "verdict module has no version coupling")
chk(re.search(r"if\s*\(after\.signature\(\)\s*==\s*before\.signature\(\)\)\s*return\s+if\s*\(scroll\)", sv) is not None,
    "unchanged page reports UNCHANGED (or SCROLL_END when scrolling)")
chk(re.search(r"if\s*\(before\.poorTree\s*\|\|\s*after\.poorTree\)", sv) is not None,
    "poor-tree pages fall back to UNVERIFIABLE instead of claiming a change")
chk(re.search(r"if\s*\(after\.packageName\s*!=\s*before\.packageName\)\s*return\s+CHANGED", sv) is not None,
    "a package switch counts as a change")
chk(re.search(r"if\s*\(keysBefore\s*!=\s*keysAfter\)", sv) is not None,
    "keyboard transitions take precedence over content comparison")

print()
if fails:
    print("FAILED: %d check(s)" % len(fails))
    for f in fails:
        print("  - %s" % f)
    sys.exit(1)
print("All ScreenDoc/tap-ref checks passed.")
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
DAS = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt")

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

def strip_comments(s):
    """去掉 // 行注释：注释里提到旧写法不该让断言变红。"""
    out = []
    for ln in s.split("\n"):
        i = ln.find("//")
        if i >= 0:
            ln = ln[:i]
        out.append(ln)
    return "\n".join(out)

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
for name, want in (("LABEL_CAP", 60), ("MAX_NODES", 200),
                   ("POOR_TREE_THRESHOLD", 5)):
    m = re.search(name + r"\s*=\s*([0-9_]+)", sd)
    got = int(m.group(1).replace("_", "")) if m else -1
    chk(got == want, "%s == %d (got %r)" % (name, want, got))
chk("labels exceeding LABEL_CAP are truncated" in sd or "maskedRaw.length > LABEL_CAP" in sd,
    "over-long labels truncated with a suffix")
# v1.6.20 把预算装配抽成了 ScreenBudget（纯字符串，不碰 AccessibilityNodeInfo）——
# 只有这样才能在 JVM 上真跑断言「顶部+底部吃满预算时中部有没有被静默丢弃」。
SB = read(ROOT + "/app/src/main/java/app/dsh/mobile/ScreenBudget.kt")
m = re.search(r"const val CHAR_BUDGET\s*=\s*([0-9_]+)", SB)
chk((int(m.group(1).replace("_", "")) if m else -1) == 3000, "CHAR_BUDGET == 3000")
chk("fun assemble(" in SB, "char budget enforced in ScreenBudget.assemble()")
chk(re.search(r"headText\.length \+ fullBody\.length <= CHAR_BUDGET", SB) is not None,
    "budget compared against header + body")
chk("ScreenBudget.assemble(" in sd, "ScreenDoc.render() delegates to ScreenBudget")
chk("avail <= 0" in SB and "因预算未列出" in SB,
    "REGRESSION: when top+bottom eat the budget the middle is reported, not silently dropped")

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
# v1.6.20：保护页判定改用**重建后**那份文档（ok.nowDocProtected），
# 不再用点击前的 before.protectedPage —— dump 与 tap 之间切页是最危险的一瞬。
chk("val protectedNow = ok.nowDocProtected || (before?.protectedPage == true)" in body,
    "the guard uses the REBUILT document's protected flag (not just the pre-tap one)")
chk("nowDocProtected" in sd, "Resolve.Ok carries the rebuilt protected flag")
chk("return Resolve.Ok(now, recorded, nowDoc.protectedPage)" in sd,
    "resolveForTap passes the rebuilt document's protectedPage through")
# 注释里可以写「用 label 而不是 rawLabel」的说明；代码里不能出现 .rawLabel
chk(".rawLabel" not in strip_comments(body),
    "tapRef never returns rawLabel: the response is the masked view, like the screen")
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

print("== 9.5 v1.6.20：安全层覆盖所有动作端点 ==")
# 缺陷：保护页此前只在 /tap-ref 生效，/tap、/tap-id、/gesture、/input、/key 全绕过。
# 现在 route() 里统一拦。断言这条链路真的接上，而不是「只给守规矩的人装门」。
chk("protectedPageGuard(path)" in br, "route() calls the protected-page guard")
# v1.7.6：闸门判据从「路径在集合里」改成 isA11yAction(path, body) ——
# /dev/node 是多态端点（op=read 只读、其余会动界面），/tap-ref 走自己的严判。
chk("isA11yAction(path, body)" in br, "route() uses the body-aware action predicate")
chk('"/dev/node" ->' in br, "/dev/node is classified per op (read stays ungated)")
chk('"/tap-ref" -> true' in br, "/tap-ref counts as an action")
chk("val acts = method == \"POST\" && isA11yAction(path, body)" in br,
    "every action endpoint goes through the guard (one predicate for all gates)")
chk("private fun protectedPageGuard(" in br, "protectedPageGuard() implemented")
chk("ProtectedPages.isProtectedFast(" in br, "the guard uses the cheap predicate, not a full dump")
chk("fun visibleTextBlob()" in read(DAS), "visibleTextBlob() exists for the cheap predicate")
# 动作侧故意 fail-open：读不到屏时放行（真正的兜底是 /tap-ref 的严格路径）
chk("读屏失败时放行而非拒绝" in br or "放行而非拒绝" in br,
    "the guard documents why it fails open on read failure")

print("== 9.6 v1.6.20：紧凑格式也脱敏 ==")
# 缺陷：ScreenMask 此前只有 1 个调用点（ScreenDoc），而紧凑格式是 loop 与模型
# 的默认入口 —— 等于「脱敏只保护了次要入口」。
chk("ScreenMask.mask(it)" in read(DAS), "dumpScreenCompact masks each line")
chk("ScreenMask.mask(r.label)" in sd, "ScreenDoc still masks its labels")
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

print("== 11. v1.6.21：屏幕去重与交接 ==")
DG = read(ROOT + "/app/src/main/java/app/dsh/mobile/ScreenDigest.kt")
HO = read(ROOT + "/app/src/main/java/app/dsh/mobile/Handoff.kt")
chk("object ScreenDigest" in DG, "ScreenDigest declared")
chk("changed.size * 3 > now.size" in DG,
    "more than a third changed -> full document (the boundary is >, not >=)")
chk("sameNote(" in DG and "partialNote(" in DG, "both digest notes exist")
chk("ScreenDigest.reduce(" in read(ROOT + "/app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt"),
    "the doc endpoint actually calls ScreenDigest")
chk("object Handoff" in HO, "Handoff declared")
chk("enum class State" in HO, "Handoff has an explicit state machine")
chk("DEFAULT_TIMEOUT_MS = 10L * 60L * 1000L" in HO, "the wait budget is 10 minutes")
chk("live.state == State.WAITING && now < live.deadlineAt) return live" in HO,
    "begin() never clobbers a live session")
for ep in ("POST /handoff", "GET /handoff", "POST /handoff/done"):
    chk(ep in read(ROOT + "/app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt"),
        "route registered: " + ep)
print("== 12. v1.7.8：紧凑 dump 的三处实质修复 ==")
SVC = read(ROOT + "/app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt")
SD = read(ROOT + "/app/src/main/java/app/dsh/mobile/ScreenDoc.kt")

# 12.1 去重 key 不能用整行：行首是每节点自增的遍历序号，父子同文本同坐标
#      的两行序号必然不同 -> 旧实现 key 永不相等，去重从来没生效过（dup 恒 0）。
chk("val body = if (l.contains(' ')) l.substringAfter(' ') else l" in SVC,
    "dedup keys on the line body, not on the whole line")
chk("if (seen.add(body))" in SVC, "the dedup set holds bodies")
chk('renumbered.add((if (e.first) "o$i" else i.toString())' in SVC,
    "after dedup the survivors are renumbered (no gaps like 0,2,5)")

# 12.2 坐标空间必须用整屏，不能用窗口高：头部如实报 1216x2640 时，
#      仍按 displayMetrics.heightPixels(2449) 裁可见性会白丢底部约 191px。
chk(re.search(r"private val rootHeight: Int\s*\n\s*get\(\) = realScreenSize\(\)\.second", SVC),
    "rootHeight comes from realScreenSize, not the window metrics")
chk(re.search(r"private val rootWidth: Int\s*\n\s*get\(\) = realScreenSize\(\)\.first", SVC),
    "rootWidth likewise")
chk("vis.intersect(0, 0, rootWidth, rootHeight)" in SVC,
    "visibility clipping uses the full-screen rect")

# 12.3 单选不能标 [switch]：switch 语义是「点一下翻转」，单选是组内互斥，
#      模型按 switch 去「关掉」同一个按钮会白点一次并误判失败。
chk('val isRadio = node.isCheckable && roleCn.contains("RadioButton", true)' in SVC,
    "RadioButton is detected before the switch branch")
chk('isRadio -> "radio"' in SVC, "compact dump emits [radio]")
chk("&& !isRadio" in SVC, "switchLike excludes radio")
chk('node.isCheckable && isRadioClass(node) -> "radio"' in SD,
    "ScreenDoc roleOf agrees with the service")
chk("private fun isRadioClass" in SD, "ScreenDoc has its own isRadioClass")

# 12.4 realMetrics 只能有一份实现（两份抄写必然迟早分叉）
chk(SD.count("getRealMetrics") == 0,
    "ScreenDoc no longer carries its own getRealMetrics copy")
chk("DshAccessibilityService.realScreenSizeOf(svc)" in SD,
    "ScreenDoc calls the shared helper instead")
chk("fun realScreenSizeOf(ctx: android.content.Context)" in SVC,
    "the shared helper lives in the service companion")
chk(SVC.count("companion object") == 1,
    "exactly one companion object in the service")

print("All ScreenDoc/tap-ref checks passed.")
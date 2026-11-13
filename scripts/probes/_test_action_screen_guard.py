#!/usr/bin/env python3
"""探针：动作与屏幕状态的对账（v1.7.x，内置 AI 反馈）。

背景（真实事故）：AI 点「轨迹」后屏幕切到 QQ，下一次工具调用仍在**旧快照**
上匹配「时长」，于是点开了 QQ 聊天。根因不是动作错，而是「动作的起点」与
「屏幕的状态」之间没有任何对账 —— 动作成功了，世界却已经换了。

三类约束：
  A. `/dev/node` 支持 expect_pkg 硬校验，不符直接 409 不派发。
  B. 不给 expect_pkg 时也要回报 foregroundChanged + reason:"foreground-changed"（不拒绝）。
  C. `/dev/verify` 的失败必须区分 text-not-found / foreground-changed / wrong-screen。
  D. click 响应带 hit 快照（pkg/text/role/bounds），密码节点 text 是 [PASSWORD]。
  E. roleOfNode 公开且自己算 clickable。
  F. 端点目录同步。

写法规矩（本仓库已因判据比被测对象宽栽过五次，见 docs/PROBE_FALSE_GREEN.md）：
  · 断言一律落在**函数体**内（fn_body 按缩进配平截取），不做全文件搜索。
  · 「这个分支做了什么」用 block_after 按花括号配平截出该 if 块再断言，
    因为「文件里有 409」在别处还有两个 409 时永远为真。
  · 分支映射关系要断言 `x -> "reason"`，不能只断言字符串存在。
  · 禁某某类断言基于 code_only（剥注释），注释里写着同样会骗过朴素 in。
"""
import io
import re
import sys

ROOT = "E:/Deepeek harness/dsh-android"
BRIDGE = ROOT + "/app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt"
DIALOG = ROOT + "/app/src/main/java/app/dsh/mobile/engine/cap/DialogCap.kt"
SVC = ROOT + "/app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt"
DOC = ROOT + "/app/src/main/java/app/dsh/mobile/ScreenDoc.kt"
CATALOG = ROOT + "/app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt"

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("  ok   " + name)
    else:
        failed += 1
        print("  FAIL " + name + ("  [" + detail + "]" if detail else ""))


def read(p):
    return io.open(p, encoding="utf-8").read()


def fn_body(text, name):
    """按 `fun <name>(` 截到下一条同缩进的 `}`。

    不用字面量窗口也不用「下一个空行」：函数体里本来就有空行，用长度开窗
    会随实现重构漂移（本仓库踩过）。
    """
    m = re.search(r"^(\s*)(?:private |internal |public )?(?:suspend )?fun "
                  + re.escape(name) + r"\s*\(", text, re.M)
    if not m:
        return ""
    indent = m.group(1)
    end = re.compile(r"^" + re.escape(indent) + r"\}", re.M)
    m2 = end.search(text, m.end())
    return text[m.start(): m2.end() if m2 else len(text)]


def block_after(text, needle):
    """从 needle 起，按花括号配平截出这一段代码块（含 needle 所在行到块尾）。"""
    i = text.find(needle)
    if i < 0:
        return ""
    j = text.find("{", i + len(needle) - 1)
    if j < 0:
        return ""
    depth = 0
    k = j
    while k < len(text):
        c = text[k]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[i:k + 1]
        k += 1
    return text[i:]


def code_only(src):
    """剥掉注释行，只留可执行代码。"""
    out = []
    for line in src.split("\n"):
        s = line.strip()
        if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            continue
        out.append(line)
    return "\n".join(out)


bridge = read(BRIDGE)
dialog = read(DIALOG)
svc = read(SVC)
doc = read(DOC)
catalog = read(CATALOG)

print("=== A. /dev/node 的 expect_pkg 硬校验 ===")
node = fn_body(bridge, "nodeAct")
node_code = code_only(node)
check("A1 找到 nodeAct 函数体", len(node) > 2000, "len=" + str(len(node)))
check("A2 读 expect_pkg", re.search(r'optString\("expect_pkg"\)', node_code) is not None)

guard = code_only(block_after(node_code, "if (expectPkg != null"))
check("A3 找到 expect_pkg 的 guard 块", len(guard) > 50, "len=" + str(len(guard)))
check("A3b guard 里返回 409", re.search(r"return 409 to", guard) is not None)
check("A4 guard 带 reason:\"foreground-changed\"",
      re.search(r'put\("reason",\s*"foreground-changed"\)', guard) is not None)
check("A5 guard 带 dispatched:false（明确没派发）",
      re.search(r'put\("dispatched",\s*false\)', guard) is not None)
check("A5b guard 用 pkgMatches 比对（不是字符串等号）",
      "pkgMatches(fgNow, expectPkg)" in guard)
check("A5c 条件里不许常量短路（false && / true ||）",
      re.search(r"if\s*\(\s*(false|true)\s*(&&|\|\||\))", guard) is None)
check("A6 nodeAct 里 409 只此一处",
      len(re.findall(r"return 409 to", node_code)) == 1,
      "count=" + str(len(re.findall(r"return 409 to", node_code))))
check("A7 校验发生在 runCatching{when(op)} 之前",
      node_code.find("expect_pkg") < node_code.find("val result = runCatching"))

print("=== B. 不给 expect_pkg 时只回报、不拒绝 ===")
check("B1 有 fgChanged 计算", re.search(r"val fgChanged\s*=", node_code) is not None)
check("B2 与 lastActionPkg 比对", "lastActionPkg" in node_code)
check("B3 回报 foregroundChanged", re.search(r'put\("foregroundChanged"', node_code) is not None)
check("B4 回报里带 foreground", re.search(r'put\("foreground"', node_code) is not None)
check("B5 变化时附 reason", re.search(r'put\("reason",\s*"foreground-changed"\)', node_code) is not None)

print("=== C. /dev/verify 区分两种失败 ===")
ver = fn_body(dialog, "verify")
ver_code = code_only(ver)
check("C1 找到 verify 函数体", len(ver) > 1500, "len=" + str(len(ver)))
check("C2 读 expect_pkg", re.search(r'optString\("expect_pkg"\)', ver_code) is not None)
check("C3 记录前台包历史", "foreground_history" in ver_code)
# 必须断言**映射关系**，不能只断言字符串存在 —— 把 fgChanged 分支也改成
# text-not-found 时字符串仍在，presence-check 会假绿（本轮实测过）。
check("C4 fgChanged 映射到 foreground-changed",
      re.search(r'fgChanged\s*->\s*"foreground-changed"', ver_code) is not None)
check("C5 else 映射到 text-not-found",
      re.search(r'else\s*->\s*"text-not-found"', ver_code) is not None)

wrong = code_only(block_after(ver_code, "if (expectPkg != null"))
check("C6 找到 wrong-screen 的 if 块", len(wrong) > 50, "len=" + str(len(wrong)))
check("C6b wrong-screen 块里立刻返回 200",
      re.search(r"return 200 to", wrong) is not None)
check("C6c 该块标 reason:\"wrong-screen\"",
      re.search(r'put\("reason",\s*"wrong-screen"\)', wrong) is not None)
check("C6d 立刻返回不等满 timeout（waited_ms 0）",
      re.search(r'waited_ms"\s*,\s*0L', wrong) is not None)
check("C6e wrong-screen 的 if 不许常量短路",
      re.search(r"if\s*\(\s*(false|true)\s*(&&|\|\||\))", wrong) is None)
check("C7 foreground-changed 的 hint 要求重新 dump（不许再点）",
      re.search(r"dump again and re-plan", ver_code) is not None)
check("C8 text-not-found 的 hint 才建议换定位",
      re.search(r"retry with a different locator", ver_code) is not None)
# 边界必须写到 fg 之后：`... != fgEnd` 也匹配掉 "!= fg"，会把「循环里没去重」
# 这条 bug 完全遮住（本轮实测假绿过）。
check("C9 循环里按 lastOrNull 去重（同一包连续采样不重复记）",
      re.search(r"history\.lastOrNull\(\)\s*!=\s*fg", ver_code) is not None)
check("C9b 去重与 add 在同一行（不是先 add 再判断）",
      re.search(r"if\s*\(fg != null && history\.lastOrNull\(\) != fg\)\s*history\.add\(fg\)",
                ver_code) is not None)
check("C10 verify 自己实现 pkgMatches（不跨类引用）",
      "private fun pkgMatches" in dialog)

print("=== D. click 带命中快照 ===")
check("D1 tapTextDeepDetailed 存在", "fun tapTextDeepDetailed" in svc)
dt = fn_body(svc, "tapTextDeepDetailed")
dt_code = code_only(dt)
check("D2 返回 via 与 detail 两者", re.search(r"return via to detail", dt_code) is not None)
check("D3 detail 在动作之前取",
      dt_code.find("describeNode") < dt_code.find("tapNodeDeep"))
check("D4 describeNode 存在", "fun describeNode" in svc)
dn = fn_body(svc, "describeNode")
dn_code = code_only(dn)
for field in ("pkg", "text", "role", "bounds", "password"):
    check("D5 describeNode 输出 " + field,
          re.search(r'put\("' + field + '"', dn_code) is not None)
check("D6 密码节点 text 替换为 [PASSWORD]",
      re.search(r"\[PASSWORD\]", dn_code) is not None)
check("D7 nodeAct 的 click 用 detailed 版",
      "tapTextDeepDetailed(" in node_code)
check("D8 click 响应带 hit", re.search(r'put\("hit"', node_code) is not None)

print("=== E. roleOfNode 公开且自己算 clickable ===")
check("E1 ScreenDoc.roleOfNode 存在", "fun roleOfNode" in doc)
rn = fn_body(doc, "roleOfNode")
rn_code = code_only(rn)
check("E2 只收 node 一个参数",
      re.search(r"fun roleOfNode\(node: AccessibilityNodeInfo\)", rn) is not None)
check("E3 内部查 isClickable", "isClickable" in rn_code)
check("E4 复用现有 roleOf（不复制判定表）", "roleOf(node," in rn_code)

print("=== F. 端点目录同步 ===")
check("F1 node 条目参数含 expect_pkg", "expect_pkg=<包名>" in catalog)
check("F2 node 条目讲清「快照过期」的风险",
      "旧快照" in catalog or "foreground-changed" in catalog)
check("F3 verify 条目说明三种 reason",
      all(s in catalog for s in ("text-not-found", "foreground-changed", "wrong-screen")))
check("F4 node 条目列出 click（此前漏了）", "op=click|long_click" in catalog)

print()
print(str(passed) + " passed, " + str(failed) + " failed")
sys.exit(0 if failed == 0 else 1)

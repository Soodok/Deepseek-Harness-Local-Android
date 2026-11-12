#!/usr/bin/env python3
"""记忆匹配质量的门禁（2026-11-12 真机反馈驱动）。

锁四件事，每件都对应一个实测到的坏行为：

1. **指纹降噪**：模型名 / 版本号 / 时间 / 百分比这类易变文本不许进指纹，
   否则同一页面每次进都算「不同场景」。
2. **fp 用覆盖率不用 Jaccard**：当前屏多出内容不该扣分（用户多发一条消息就掉分
   是原 bug 的直接原因）。
3. **toggle 幂等**：已经是目标状态的开关不许重复拨（否则「打开侧栏」会把已开的关掉）。
4. **pending 有出路**：命中累计到阀值必须转正，不能死锁在「不参与命中」里。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
fails = []
passes = 0


def check(name, ok, detail=""):
    global passes
    if ok:
        passes += 1
    else:
        fails.append(name + ((" — " + detail) if detail else ""))


def read(rel):
    p = os.path.join(ROOT, rel)
    return io.open(p, encoding="utf-8").read() if os.path.isfile(p) else ""


def fn_body(text, name):
    """按 `fun <name>(` 截出函数体。全文件搜索会被同名 API 骗成假绿。"""
    i = text.find("fun " + name + "(")
    if i < 0:
        return ""
    j = text.find("\n    }", i)
    return text[i:j if j > 0 else i + 4000]


scene = read("app/src/main/java/app/dsh/mobile/engine/SceneMemory.kt")
index = read("app/src/main/java/app/dsh/mobile/engine/MemoryIndex.kt")
replay = read("app/src/main/java/app/dsh/mobile/engine/MemoryReplay.kt")
store = read("app/src/main/java/app/dsh/mobile/engine/MemoryStore.kt")

# 1. 指纹降噪
fp_body = fn_body(scene, "fingerprint")
check("fingerprint filters volatile text", "isVolatileText" in fp_body,
      "易变文本混进指纹 = 同一页面每次算不同场景")
check("volatile filter covers timestamps", "VOLATILE_TIME" in scene)
check("volatile filter covers versions", "VOLATILE_VERSION" in scene)
check("volatile filter covers percentages", "VOLATILE_PERCENT" in scene)
# 这两个必须同时成立：常量在**判定函数体内**被真正使用，且列表里确实有那几个词。
# 只查「文件里出现过 VOLATILE_MODEL_HINTS」会被「定义还在、判据被清空」骗过去。
check(
    "volatile filter uses the model-name list",
    "VOLATILE_MODEL_HINTS" in fn_body(scene, "isVolatileText"),
    "常量定义了却没用，等于模型名照旧进指纹",
)
check(
    "model-name list still has real entries",
    '"FlashHigh"' in scene and '"预览版"' in scene.replace("预览版", "预览版") and "预览版" in scene,
    "列表被清空时判据就成了空转",
)
check("volatile filter is a real predicate", fn_body(scene, "isVolatileText") != "",
      "必须有独立可断言的函数，不能内联在 fingerprint 里")

# 2. fp 用覆盖率
rank_body = fn_body(index, "rank")
check("rank scores with coverage not jaccard",
      "coverage(e.fingerprint(), fp)" in rank_body,
      "Jaccard 对非对称变化惩罚过重，用户多一行文本就归零")
check("coverage is memory-side",
      "hit.toDouble() / want.size" in fn_body(index, "coverage"),
      "分母必须是记忆侧，否则新增内容会扣分")
check("coverage documented as asymmetric",
      "不对称" in index or "asymmetric" in index.lower())
check("old jaccard kept for diagnostics",
      "fpJaccardOf" in index,
      "诊断要能对比两种口径，别把旧算法删了")

# 3. toggle 幂等
check("replay has alreadyInTargetState", fn_body(replay, "alreadyInTargetState") != "")
check("replay has desiredToggleState", fn_body(replay, "desiredToggleState") != "")
check("idempotence guard is wired before execute",
      "alreadyInTargetState(step, screen.candidates)" in replay,
      "只定义不接线等于没修")
check("guard only applies to tap",
      "step.action.equals(TraceEndpoint.TAP" in replay)
check("unknown checked state is not a skip",
      "hit.checked ?: return false" in fn_body(replay, "alreadyInTargetState"),
      "读不到状态当成「已到位」会漏点，那是新的假成功")
check("ambiguous copy yields null",
      "else -> null" in fn_body(replay, "desiredToggleState"))
check("screen carries candidates",
      "val candidates: List<FastPicker.Candidate>" in replay)

# 4. pending 有出路
check("afterHit promotes pending", "PROMOTE_HITS" in fn_body(store, "afterHit"))
check("promotion forces active state",
      "if (promoted) MemoryContract.STATE_ACTIVE" in fn_body(store, "afterHit"),
      "promoted 的 suspect 必须同时复位，否则仍是 suspect")
check("promote threshold is 2", "const val PROMOTE_HITS = 2" in store,
      "1 会让单次偶然直接固化，3 会让新记忆长期不可用")

# 5. 语义层就绪必须自报（用户第 12 条）
routes = read("app/src/main/java/app/dsh/mobile/engine/MemoryRoutes.kt")
check("stats reports semantic readiness", '"semantic"' in routes,
      "只看 /local/status 不看记忆统计，AI 会不知道 cos 是语义还是哈希")
check("semantic block names the engine",
      'if (semanticReady) "bge" else "hash-fallback"' in routes,
      "必须说明当前实际走哪一级")
check("degraded state carries an action",
      "/local/ensure" in routes,
      "降级要告诉调用方怎么恢复，不能只说「未就绪」")

# 6. v1 必须标 legacy（用户第 9 条）
check("v1 list marks itself legacy", '.put("legacy", true)' in scene)
check("v1 stats mentions v2", "v2 (current store) has" in scene,
      "两套库并列显示才能消除「0 条 vs 1 条」的困惑")

# 7. 鉴权失败要说清判据（用户第 11 条）
bridge = read("app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt")
check("403 explains why", '"hint"' in bridge and "missing X-DSH-Token header" in bridge)
check("403 explains how", '"how_to"' in bridge)
check("403 never echoes the token",
      "X-DSH-Token does not match the current bridge token" in bridge,
      "错误信息里回显 token 等于把它写到日志里")

print(f"passes={passes} fails={len(fails)}")
for f in fails:
    print("  FAIL:", f)
sys.exit(1 if fails else 0)

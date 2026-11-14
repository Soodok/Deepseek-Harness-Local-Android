#!/usr/bin/env python3
"""fast-mode 插件与引擎 0.2.2 硬契约的兼容护栏。

## 这条护栏防的是什么（真实故障）

用户报错（真机 1.5.0 / 引擎 0.2.2 系）：

    agent "session-…": agent/request proposed reasoning effort "off"
    after step preparation fixed "xhigh"; use agent.model.select() before step preparation

`fast_mode` 打开后**每一步都整轮失败**，不是降级、不是卡顿，是直接报处理失败。

## 机制（读引擎源码确认，不是推测）

`@deepseek-ai/dsh-agent-loop@0.2.2-rc.1`：

- `preStep()` 第 671 行 `const selection = this.model.current()` —— **在派发
  `agent/pre-step` 之前**就把本步路线定死；
- `step()` 把 `decision.selection` 原样交给 `prepareRequest(turn, step, selection, …)`；
- `prepareRequest()` 第 1000/1001 行两条断言，任一不等即 `throw`：
  - `proposedConfig.provider/model !== selection.provider/model`
  - `proposedConfig.reasoningEffort !== selection.reasoningEffort`

旧插件在 `agent/request` 瀑布里 return `{ ...proposed, reasoningEffort: "off" }`，
正好踩中第二条。报错文案自己给了正解：`use agent.model.select() before step
preparation`。

## 修法

挂 `agent/pre-step`，在处理器里 `agent.model.select({ ...cur, reasoningEffort: "off" })`，
让**下一步**的 `model.current()` 本身返回 off。fast 关掉时把 off 撤回
（只撤自己写的那档，不动用户/别的插件设的其它档位）。

## 双引擎兼容

`AgentModel` 类只在 0.2.2+ 存在（0.2.1-rc.2 的 dsh-agent 里 grep 命中数为 0），
所以老引擎保留 `agent/request` 路径。两条路径**按运行时能力择一挂载**，
不能同时挂。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLUGIN = os.path.join(ROOT, "app", "src", "main", "assets", "android-plugins", "fast-mode.mjs")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


src = open(PLUGIN, encoding="utf-8").read()

# 剥注释：注释里可以出现 `reasoningEffort: "off"` 这类示例，不能当代码断言。
no_block = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
code = re.sub(r"//[^\n]*", "", no_block)

print("== A. 不再在 agent/request 里无条件改 reasoningEffort ==")

# A1: agent/request 的处理器仍存在（老引擎兜底），但必须是「按能力择一」的那个
check("A1 保留 legacyHook 作为老引擎兜底", "legacyHook" in code)

# A2: 关键字面：必须按 hasAgentModel() 择一挂载，不能两个都挂
check("A2 有引擎能力探测 hasAgentModel", "hasAgentModel" in code)
check("A3 探测依据是 AgentModel 类存在",
      "var AgentModel = class" in code,
      "0.2.2+ 才导出 AgentModel；0.2.1-rc.x 里没有")
m_mount = re.search(
    r"if\s*\(\s*hasAgentModel\(\)\s*\)\s*\{(.{0,300}?)\}\s*else\s*\{(.{0,300}?)\}", code, re.S
)
check("A4 按能力二选一挂载（if/else）", m_mount is not None)
if m_mount:
    yes, no = m_mount.group(1), m_mount.group(2)
    check("A5 新引擎挂 agent/pre-step", "agent/pre-step" in yes, yes.strip()[:60])
    check("A6 老引擎才挂 agent/request", "agent/request" in no, no.strip()[:60])
    check("A7 新引擎分支不挂 agent/request（否则仍会踩断言）",
          "agent/request" not in yes)
else:
    for n in ("A5", "A6", "A7"):
        check("%s (跳过：A4 未命中)" % n, False)

print("\n== B. 新路径用 agent.model.select（引擎指定的正解）==")

check("B1 存在 fastSelectHook", "fastSelectHook" in code)
m_hook = re.search(r"const fastSelectHook\s*=\s*async\s*\((.*?)\n  \};", code, re.S)
check("B2 fastSelectHook 被定位到", m_hook is not None)
if m_hook:
    body = m_hook.group(1)
    check("B3 先从 payload 拿 agent", re.search(r"payload\s*&&\s*payload\.agent", body) is not None)
    check("B4 拿 agent.model", re.search(r"agent\.model", body) is not None)
    check("B5 校验 current/select 都是函数（能力探测不靠猜）",
          "typeof model.current" in body and "typeof model.select" in body)
    check("B6 fast 开启时 select reasoningEffort=off",
          re.search(r'reasoningEffort:\s*"off"', body) is not None)
    # ★这里必须锚定「同一次 select 调用」，不能分开搜两个字符串：
    # 函数体里撤回分支也存在 `provider: cur.provider`，分散断言会在把开启路径
    # 改成 `model.select({ reasoningEffort: "off" })` 后仍然通过（实测假绿）。
    m_sel = re.search(r"model\.select\(\s*\{[^}]{0,200}\}\s*\)", body)
    check("B7 开启路径的 select 一次带全 provider/model/reasoningEffort",
          m_sel is not None
          and "reasoningEffort" in m_sel.group(0)
          and re.search(r"provider:\s*cur\.provider", m_sel.group(0)) is not None
          and re.search(r"model:\s*cur\.model", m_sel.group(0)) is not None,
          "AgentModel.select() 会 append 整条 model/selection，"
          "缺 provider/model 会把会话路线写坏（当前匹配：%s）"
          % (m_sel.group(0)[:80] if m_sel else "未匹配"))
    # ★ B8/B9 在 2026-11-14 反转（真机事故，本插件第三处崩溃点）：
    # 原来断言「fast 关闭时必须撤回 off」，撤回写法是 `select({provider, model})`
    # —— 不带 effort 等于回到适配器默认（high = thinking 开启）。而 off 期间写进
    # 会话的 assistant 消息没有 thinking 块，API 要求 thinking 模式下历史必须
    # 回传 thinking，补不回来 → 整轮 400：
    #   The `content[].thinking` in the thinking mode must be passed back to the API.
    # 触发几乎必然（标记文件 30 分钟 TTL 到期是自动的）。
    # 正确做法：只停止强制 off，绝不主动恢复思考 —— 新会话天然从默认档位开始。
    m_revert = re.search(
        r"model\.select\(\s*\{\s*provider:\s*cur\.provider,\s*model:\s*cur\.model\s*\}\s*\)", body)
    check("B8 fast 关闭时不撤回 off（撤回会让下一轮 400）",
          m_revert is None,
          "不带 effort 的 select 就是「恢复默认档位」。实测后果：整轮 "
          "The `content[].thinking` ... must be passed back to the API —— "
          "历史里 off 期间的消息没有 thinking 块，补不回来。命中：%s"
          % (m_revert.group(0) if m_revert else "无（正确）"))
    check("B9 仍识别「本会话已由本插件写 off」这一状态",
          "offAppliedByUs" in body,
          "只有本插件自己写过的 off 才需要提示；别处来的 off 与本插件无关")
    check("B9b 有「本会话不能再恢复思考」的一次性警告",
          "warnNoRevertOnce" in body and "warnNoRevert" in src,
          "用户手动调回 high 会撞同一个 400，必须让他在日志里看到原因")
    check("B10 select 失败不抛出（一次写失败不该让整轮崩）",
          "model.select failed" in body)
    check("B11 缺 agent.model 时退回 legacyHook",
          "legacyHook(payload, next)" in body)
    check("B12 结尾 return next()（瀑布必须往下走）",
          re.search(r"return\s+next\(\)\s*;?\s*$", body.strip()) is not None)
else:
    for n in ("B3", "B4", "B5", "B6", "B7", "B8", "B9", "B10", "B11", "B12"):
        check("%s (跳过：B2 未命中)" % n, False)

print("\n== C. legacyHook 仍保留原行为（老引擎不能退化）==")

m_legacy = re.search(r"const legacyHook\s*=\s*async\s*\((.*?)\n  \};", code, re.S)
check("C1 legacyHook 存在", m_legacy is not None)
if m_legacy:
    lb = m_legacy.group(1)
    check("C2 常规模式一字不改", "if (!isFast()) return proposed;" in lb)
    check("C3 仍返回 reasoningEffort=off", re.search(r'reasoningEffort:\s*"off"', lb) is not None)

print("\n== D. 结构完整性 ==")

check("D1 仍导出 apply/inject/name",
      re.search(r"export\s*\{\s*apply,\s*inject,\s*name\s*\}", code) is not None)
check("D2 仍注册 fast_mode 工具", re.search(r'name:\s*"fast_mode"', code) is not None)
check("D3 仍有 30 分钟 TTL 安全阀", "TTL_MS" in code)
check("D4 仍有 1.5 秒状态缓存", "CACHE_MS" in code)
check("D5 标记文件仍是 fast-mode（与 gates/fast 同源）",
      re.search(r'MARKER_NAME\s*=\s*"fast-mode"', code) is not None)
check("D6 有 createRequire 导入（探测用）", "createRequire" in code)

# 语法自检：用 node 解析（不执行）。插件是 ESM，--input-type=module 只做解析检查。
node_ok = True
import subprocess, tempfile
try:
    with tempfile.NamedTemporaryFile("w", suffix=".mjs", delete=False, encoding="utf-8") as f:
        f.write(src)
        tmp = f.name
    r = subprocess.run(["node", "--check", tmp], capture_output=True, text=True, timeout=60)
    node_ok = r.returncode == 0
    detail = (r.stderr or "")[:200]
    os.unlink(tmp)
except FileNotFoundError:
    detail = "node 不可用，跳过语法检查"
    node_ok = True
except Exception as e:
    detail = str(e)[:200]
check("D7 node --check 语法通过", node_ok, detail)

print("\n== E. 运行时行为（真跑钩子，不只静态看代码）==")

RUNTIME = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_fast_mode_runtime_test.mjs")
if not os.path.isfile(RUNTIME):
    check("E1 运行时测试脚本存在", False, RUNTIME)
else:
    try:
        r = subprocess.run(
            ["node", RUNTIME, PLUGIN], capture_output=True, text=True, timeout=180
        )
        ok = r.returncode == 0
        check("E1 运行时行为全绿（钩子位置 + select 参数 + 撤回 + 幂等 + 双引擎）",
              ok, (r.stdout or r.stderr or "")[-700:])
    except FileNotFoundError:
        check("E1 运行时行为测试（node 不可用，跳过）", True)
    except subprocess.TimeoutExpired:
        check("E1 运行时行为测试（超时）", False)

print("\n" + "-" * 44)
# == F. 必须先探测模型能力再改（v1.7.x 真机崩溃的根治点）==
print("\n== F. 先探测能力再改 effort ==")
check("F1 有 supportsOff 探测函数", "const supportsOff" in src, "缺它就等于没修")
check("F2 探测读 reasoning.efforts",
      "info.reasoning && info.reasoning.efforts" in src or "reasoning.efforts" in src)
check("F3 新引擎路径先探测再 select",
      "const ok = await supportsOff(cur.provider, cur.model)" in src,
      "不探测就 select，默认思考的模型会在派发期抛 UNSUPPORTED_REASONING_EFFORT")
check("F4 老引擎路径也先探测",
      "await supportsOff(provider, model)" in src)
check("F5 只有 ok === true 才改", "if (ok === true)" in src,
      "写成 ok !== false 会把「探测失败」也当成可以改")
check("F6 探测结果按 provider/model 缓存", "probeCache.set(key, ok)" in src)
check("F7 不支持时只警告一次", "warnUnsupportedOnce" in src and "warnedUnsupported" in src)
check("F8 status 如实说明不生效", "always thinks" in src)

print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)

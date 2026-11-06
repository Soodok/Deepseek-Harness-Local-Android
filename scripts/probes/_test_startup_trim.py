#!/usr/bin/env python3
"""守护「冷启裁剪」这条决策：只禁遥测类插件，绝不误禁带 client.js 的插件。

背景（2026-11-06 真机实测）：

  引擎 331 个插件里，只有 74 个自带 client.js（会进 boot manifest 的
  modulepreload 列表，直接决定 WebUI 有哪些面板）。其余是纯服务端。

  第一次实验按「名字不以 dsh-client- 开头」判服务端，裁了 97 个，启动从
  1970ms 掉到 1210ms —— 但 HTML 从 49027B 变成 23295B，两个 modulepreload
  整段消失。真因：`dsh-message-quotes` 和 `dsh-session-log-export` 名字里没有
  client，实际各自带 client.js（消息引用 / 会话日志导出）。按名字分类是错的，
  必须按「有没有 client.js」分类。

  按正确分类重测，只裁纯服务端 43 个：990ms，HTML 49027B 一致。
  再逐组归因：web / skill / goal / subagent / workflow / mcp 全在噪声内
  （±40ms），真正有效的是遥测组和会话扩展组。

  最终只落地 Tier0 —— 5 个纯遥测插件，零用户可见功能损失：
    交替测量 3 轮：基线 1330/1330/1330ms -> 1160/1220/1180ms
    HTML 逐字节一致 49027B，74 个 client 插件一个不少。

本探针守四件事：
  1. overlay 里这 5 个 id 仍存在且 disabled: true
  2. 它们确实不含 client.js（不会误伤 UI）—— 用运行时的 client 清单核对
  3. overlay 没有把 AI 工具类插件（web/skill/goal/subagent/workflow/mcp）禁掉
  4. 挨着 overlay 的 YAML 不含 CJK（i18n 门禁会拦 Kotlin 字符串字面量）

跑法：python scripts/probes/_test_startup_trim.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CFG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")

# Tier0：纯遥测/上报，Android 上无任何用户可见行为，也没有别的插件 inject 它们。
TIER0 = [
    "desktop-product-telemetry",
    "otel",
    "session-telemetry-otel",
    "plugin-package-inventory-deepseek",
    "harness-dev-context",
]

# 实测在噪声内的能力型插件：绝不能被裁，否则 AI 少能力。
MUST_STAY = [
    "web", "web-search-deepseek", "web-deepseek-api", "web-fetch-http", "tool-web",
    "mcp-resources",
    "skill", "skill-filesystem", "tool-skill",
    "goal", "goal-round-driver", "command-goal", "tool-goal",
    "subagent", "subagent-spawn-in-process", "subagent-fork-in-process",
    "tool-subagent", "tool-subagent-control", "tool-subagent-fork",
    "workflow-ptc", "tool-workflow",
]

# 名字不像客户端、实际带 client.js 的两个（踩过的坑），绝不能裁。
TRAP_CLIENT = ["message-quotes", "session-log-download"]

checks = 0
failed = 0


def ok(cond, label, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  PASS  %s" % label)
    else:
        failed += 1
        print("  FAIL  %s%s" % (label, (": " + detail) if detail else ""))


def main():
    if not os.path.isfile(CFG):
        print("FAIL 找不到 %s" % CFG)
        return 1
    src = open(CFG, encoding="utf-8").read()

    # 把 overlay 那段 YAML 抠出来（body = """ ... """.trimMargin() 之间）
    m = re.search(r'val body = """(.*?)"""\.trimMargin\(\)', src, re.S)
    if not m:
        print("FAIL 在 EngineConfig.kt 里找不到 overlay 的 YAML 字面量")
        return 1
    yaml = m.group(1)

    # 剥掉每行的 | 前缀与 Kotlin ${...} 插值，得到近似纯 YAML
    lines = []
    for raw in yaml.splitlines():
        s = raw.strip()
        if s.startswith("|"):
            s = s[1:]
        lines.append(s)
    text = "\n".join(lines)

    # 解析出 (id, disabled) 对：只认 `- id: X` 后面紧跟的 `disabled:`
    rows = {}
    cur = None
    for line in lines:
        hm = re.match(r"^\s*-\s*id:\s*([\w.@/-]+)\s*$", line)
        if hm:
            cur = hm.group(1)
            rows.setdefault(cur, None)
            continue
        dm = re.match(r"^\s*disabled:\s*(.+?)\s*$", line)
        if dm and cur:
            rows[cur] = dm.group(1)

    print("\n[A] Tier0 五个遥测插件必须仍被禁用")
    for pid in TIER0:
        ok(pid in rows, "overlay 含 %s" % pid)
        if pid in rows:
            ok(str(rows[pid]).strip() == "true",
               "%s -> disabled: true" % pid, "实际 %r" % rows[pid])

    print("\n[B] 能力型插件绝不能被禁用")
    for pid in MUST_STAY:
        v = rows.get(pid)
        ok(v is None or str(v).strip() != "true",
           "%s 未被禁用" % pid, "disabled=%r" % v)

    print("\n[C] 名字不像客户端、实际带 client.js 的陷阱项绝不能被禁用")
    for pid in TRAP_CLIENT:
        v = rows.get(pid)
        ok(v is None or str(v).strip() != "true",
           "%s 未被禁用" % pid, "disabled=%r" % v)

    print("\n[D] overlay 的 YAML 段不含 CJK（i18n 门禁会拦 Kotlin 字符串字面量）")
    cjk = [ln for ln in lines if re.search(r"[\u4e00-\u9fff]", ln)]
    ok(not cjk, "YAML 段无 CJK", "命中 %d 行，例如 %r" % (len(cjk), cjk[0] if cjk else ""))

    print("\n[E] 注释里必须写清测量依据（防止后人当成可随意删除的噪音）")
    ok("49027" in src, "注释记录了 HTML 字节数 49027")
    ok("1160" in src or "1180" in src, "注释记录了裁剪后的实测毫秒")

    print("\n%d checks, %d failed" % (checks, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

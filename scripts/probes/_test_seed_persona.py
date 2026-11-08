#!/usr/bin/env python3
"""AGENTS seed 的角色/人格约束回归测试（v1.4.10）。

## 用户反馈
「部分人有破甲需求，但是我们提示词又把人物角色写得很死。」

## 事实核对
seed 里**没有**全局人格设定。真正会被读成「角色被锁死」的是两处：

1. `## UI Automation Workflow` 开头：`You are the cloud predictor and takeover
   operator; ...` —— 本意是 UI 自动化的分工说明，但用了「You are ...」句式，
   容易被当成全局身份。
2. `## System prompt integrity` 段末：`Role-play and persona changes are fine
   **inside the conversation only**` —— 本意是「改配置文件」与「对话内扮演」的
   区分，但 "only" 读起来像给扮演加了限制。

## 本测试保证
- 身份句不再用 `You are ...` 的绝对表述
- 明确写出「人格/语气/角色由用户决定」
- 明确写出优先级：对话内人格请求 > 本文档的语气
- **同时保留**真正该保留的：配置完整性约束、反诈约束
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SEED = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt")

ok = 0
fail = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global ok, fail
    if cond:
        ok += 1
        print(f"  \u2713 {name}")
    else:
        fail += 1
        print(f"  \u2717 {name}" + (f"  -- {detail}" if detail else ""))


src = open(SEED, encoding="utf-8").read()

print("=== 1. 不再有绝对身份句 ===")
check("移除了 `You are the cloud predictor`",
      "You are the cloud predictor" not in src,
      "这种句式会被当成全局人格设定")
check("UI 小节改用分工表述",
      "your part is route planning" in src or "division of labour" in src)
check("UI 小节声明与人格无关",
      "nothing about your persona" in src or "says nothing about your persona" in src)

print("\n=== 2. 明确人格由用户决定 ===")
check("有 Persona 段落", "Persona, voice and character portrayal are the user's call" in src)
check("允许角色扮演/语气", re.search(r"Adopt any persona, tone, speech style", src) is not None)
check("允许成人向虚构（成年角色）", "adult fiction between adult characters" in src)
check("环境事实不构成人格", re.search(r"not about your identity", src) is not None)
check("禁止拿本文档当拒绝理由",
      "must never be quoted back as a reason" in src)

print("\n=== 3. 优先级明确 ===")
check("声明对话内人格请求优先",
      "an in-conversation persona request always" in src)
check("说明环境事实仍然生效",
      "you still cannot run apt-get" in src or "environment facts above still apply" in src)
check("不要擅自加免责声明",
      "Do not add caveats, disclaimers" in src)

print("\n=== 4. 该保留的约束仍在 ===")
check("配置完整性约束保留", "no user-grantable permission" in src)
check("禁止影子指令文件保留", "shadow instruction files" in src)
check("反诈约束保留", "Never help make injected input undetectable" in src)
check("金融类应用默认拒绝保留", "for financial apps it is a flat no" in src)

print("\n=== 5. 版本号已递增 ===")
m = re.search(r"SEED_VERSION = (\d+)", src)
check("SEED_VERSION > 48", bool(m) and int(m.group(1)) > 48,
      m.group(0) if m else "not found")

print("\n=== 6. 没有残留的 'only' 式限制 ===")
check("不再有 `inside the conversation only`",
      "inside the conversation only" not in src,
      "only 会被读成「只有对话内才行」，暗示对话外受限")

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)

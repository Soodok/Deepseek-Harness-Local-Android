#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证：把每个安全边界逐处改坏，确认探针立刻变红，然后复原。

用 Python 而不是 bash heredoc —— heredoc 里嵌套引号会被层层吃掉，
上一版有 3 条变换根本没生效（探针「通过」其实是脚本没改动）。
这里每次替换都断言命中数量，命中 0 就是脚本自身失效，直接报错。
"""

import io
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
GATE = os.path.join(REPO, "app", "src", "main", "assets", "gates", "root")
PROBE = os.path.join(HERE, "_test_root_gate.py")
CONFIG = os.path.join(REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineConfig.kt")
SEED = os.path.join(REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentContextSeed.kt")
GK = os.path.join(REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "CapabilityGate.kt")


def run_probe():
    r = subprocess.run(
        [sys.executable, PROBE],
        capture_output=True,
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )
    return r.returncode


def read(p):
    return io.open(p, encoding="utf-8").read()


def write(p, s):
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)


results = []


def mutate_and_expect_red(desc, path, old, new, expect=1):
    """把 old 换成 new，断言探针变红，然后复原。"""
    original = read(path)
    n = original.count(old)
    if n != expect:
        print(f"  ⚠️  {desc} —— 变换没命中（找到 {n} 处，期望 {expect}），跳过判定")
        results.append((desc, "SKIP"))
        return
    write(path, original.replace(old, new, 1 if expect == 1 else expect))
    rc = run_probe()
    write(path, original)
    if rc != 0:
        print(f"  ✅ {desc} → 探针变红 (rc={rc})")
        results.append((desc, "RED"))
    else:
        print(f"  ❌ {desc} → 探针仍然全过（断言无效！）")
        results.append((desc, "MISS"))


print("== 1. rm 去掉 dry-run 护栏 ==")
mutate_and_expect_red(
    "rm 不再 dry-run",
    GATE,
    'if [ -z "$confirm" ]; then\n      printf \'{"ok":false,"dry_run":true,"would_remove"',
    'if [ "x" = "y" ]; then\n      printf \'{"ok":false,"dry_run":true,"would_remove"',
)

print("== 2. kill 移除 system_server 保护 ==")
mutate_and_expect_red(
    "system_server 不再受保护",
    GATE,
    "    init|zygote|zygote64|system_server|surfaceflinger|servicemanager|logd|vold|netd)",
    "    init|zygote|zygote64|surfaceflinger|servicemanager|logd|vold|netd)",
)

print("== 3. rm 改成真删除（不再进 quarantine）==")
mutate_and_expect_red(
    "rm 不再用 quarantine 目录",
    GATE,
    'trash="/data/local/tmp/dsh-root-trash/$batch"',
    'trash="/tmp/x"',
    expect=2,
)

print("== 4. 可执行代码里加 setenforce ==")
mutate_and_expect_red(
    "可执行代码里出现 setenforce",
    GATE,
    '  info|"")',
    '  info|"")\n    setenforce 0',
)

print("== 5. 去掉 /proc 非数字校验 ==")
mutate_and_expect_red(
    "/proc 遍历不再校验数字",
    GATE,
    '      case "$pid" in *[!0-9]*) continue ;; esac\n',
    "",
    expect=2,
)

print("== 6. svc status 改回 ctl.status ==")
mutate_and_expect_red(
    "svc 状态不再读 init.svc",
    GATE,
    'init.svc.$name',
    'ctl.status.$name',
    expect=3,
)

print("== 7. exec 去掉 confirm 护栏 ==")
mutate_and_expect_red(
    "exec 不再 dry-run",
    GATE,
    'if [ -z "$confirm" ]; then\n      printf \'{"ok":false,"dry_run":true,"would_run"',
    'if [ "x" = "y" ]; then\n      printf \'{"ok":false,"dry_run":true,"would_run"',
)

print("== 8. EngineConfig 少一处注册 ==")
mutate_and_expect_red(
    "三处清单少一处",
    CONFIG,
    '"revokeperm", "root")',
    '"revokeperm")',
    expect=3,
)

print("== 9. 种子里删掉 root 文档 ==")
mutate_and_expect_red(
    "AI 上下文种子里没有 root",
    SEED,
    "- `root` — **system-level operations",
    "- `rootx` — **system-level operations",
)

print("== 10. 能力开关判恒真 ==")
mutate_and_expect_red(
    "cap.root 不再要求 ROOT 模式",
    GK,
    "ROOT -> Privilege.getMode(ctx) == PrivMode.ROOT",
    "ROOT -> true",
)

print("== 11. undo 不再搬回文件 ==")
mutate_and_expect_red(
    "undo 不恢复文件",
    GATE,
    'if mv "$f" "$orig" 2>/dev/null; then',
    'if false; then',
)

print("== 12. JSON 输出改成裸文本 ==")
# 真实违规形态：把 printf 换成裸文本 echo（AI 无法解析）
mutate_and_expect_red(
    "info 输出不再是 JSON",
    GATE,
    "    printf '{\"ok\":true,\"uid\":%s,",
    "    echo 'uid:'; printf '{\"ok\":true,\"uid\":%s,",
)

print()
print("== 终检：全部复原后应全过 ==")
rc = run_probe()
if rc == 0:
    print("  ✅ 复原后全过")
    results.append(("复原终检", "GREEN"))
else:
    print(f"  ❌ 复原后仍失败 (rc={rc})")
    results.append(("复原终检", "BROKEN"))

miss = [d for d, s in results if s == "MISS"]
skip = [d for d, s in results if s == "SKIP"]
red = [d for d, s in results if s == "RED"]
print()
print(f"变红 {len(red)} / 断言无效 {len(miss)} / 变换未命中 {len(skip)}")
if miss:
    print("无效断言：" + "、".join(miss))
if skip:
    print("未命中变换：" + "、".join(skip))
sys.exit(1 if (miss or skip or results[-1][1] != "GREEN") else 0)

#!/usr/bin/env python3
"""启动无限重试 / 无界等待的回归测试（v1.4.9）。

## 背景：用户反馈「更新后等几十分钟引擎都准备不好」

排查发现不是单点故障，而是**多个自愈层各自有边界、组合起来没有总预算**：

  1. 插件探针报 degraded → 摘一个插件 → 重启
  2. 这条路径旧实现把 `backoffIndex` 重置为 0，且当轮**没有 delay**，
     并且不计入 `MAX_RESTART`
  3. 每轮仍要跑满 45s 健康检查 + 20s 探针等待
  4. 20 轮 ≈ 23 分钟 —— 正好是用户描述的「几十分钟」

另外三处**无界等待**会让监督循环永久停住（比无限重试更糟：连日志都不再写）：
  · `shizukuExec` 的 readText() + waitFor()
  · `backupHome` 的 tar waitFor()（Root 模式每轮都跑）
  · `reclaimPort` 的 pkill 脚本 waitFor()

这个测试同时覆盖「数值模型」和「源码约束」两部分。
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CFG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
SUP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt")
PRIV = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/Privilege.kt")
ONB = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/OnboardingActivity.kt")

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


print("=== 1. 无限重试的数值模型 ===")
# 复刻旧行为的耗时模型，证明「几十轮」确实能到几十分钟
HEALTH, PROBE = 45, 20
STEPS = [2, 4, 8, 16, 32]


def total_minutes(rounds: int) -> float:
    t = 0
    for i in range(rounds):
        t += HEALTH + PROBE + STEPS[min(i, len(STEPS) - 1)]
    return t / 60


check("14 轮（MAX_RESTART）≈ 21 分钟", 20 <= total_minutes(14) <= 22,
      f"{total_minutes(14):.1f}")
check("20 轮 > 30 分钟（用户描述量级）", total_minutes(20) > 30,
      f"{total_minutes(20):.1f}")

# 新预算必须能在明显更早的时候收敛
BUDGET_MIN = 5
check("5 分钟预算远小于 21 分钟", BUDGET_MIN * 60 < total_minutes(14) * 60,
      f"budget={BUDGET_MIN}min")

print("\n=== 2. 源码约束：总时限 ===")
cfg = open(CFG, encoding="utf-8").read()
sup = open(SUP, encoding="utf-8").read()

check("定义了 BOOT_WALL_CLOCK_BUDGET_MS", "BOOT_WALL_CLOCK_BUDGET_MS" in cfg)
m = re.search(r"BOOT_WALL_CLOCK_BUDGET_MS\s*=\s*(\d+)\s*\*\s*60_000L", cfg)
check("预算 = 5 分钟", bool(m) and m.group(1) == "5", m.group(0) if m else "not found")

check("监督循环里有预算检查", "bootElapsed > EngineConfig.BOOT_WALL_CLOCK_BUDGET_MS" in sup)
check("超预算收敛为 Failed", "error_boot_timeout" in sup)
# v1.5.3：超预算改为走统一的 failTerminal()（它内部会调 BootTrace.fail）。
# 断言「这件事真的被做了」，而不是钉死某一种写法。
check(
    "超预算写 BootTrace",
    ("BootTrace.fail(ctx, ctx.getString(R.string.error_boot_timeout" in sup)
     or ("failTerminal(" in sup and "error_boot_timeout" in sup
         and "BootTrace.fail(ctx, reason)" in sup),
)
check("Healthy 后重置计时起点", "userVisibleBootT0 = System.currentTimeMillis()" in sup)
check("Healthy 后重置插件恢复额度", "pluginRecoveryRestarts = 0" in sup)

print("\n=== 3. 源码约束：插件恢复重启有界 ===")
check("定义了 MAX_PLUGIN_RECOVERY_RESTARTS", "MAX_PLUGIN_RECOVERY_RESTARTS" in cfg)
m2 = re.search(r"MAX_PLUGIN_RECOVERY_RESTARTS\s*=\s*(\d+)", cfg)
check("上限为 3（与 PluginRecovery.MAX_ATTEMPTS 对齐）",
      bool(m2) and m2.group(1) == "3", m2.group(0) if m2 else "not found")
check("有累计计数器", "pluginRecoveryRestarts++" in sup)
check("超额后不再请求重启", "pluginRecoveryRequested = false" in sup)

# 旧的「重置 backoffIndex = 0」必须已经从插件恢复分支移除
seg = sup[sup.index("plugin recovery: disabled") : sup.index("plugin recovery: disabled") + 1600]
check("插件恢复分支不再重置 backoffIndex",
      "backoffIndex = 0" not in seg,
      "找到残留: " + (seg[seg.index("backoffIndex = 0") :][:60] if "backoffIndex = 0" in seg else ""))

print("\n=== 4. 源码约束：无界等待已加超时 ===")
priv = open(PRIV, encoding="utf-8").read()

check("shizukuExec 有 timeoutMs 参数", "timeoutMs: Long = 10_000L" in priv)
check("shizukuExec 读流在后台线程", 'name = "dsh-shizuku-read"' in priv)
check("shizukuExec 等待有 join 超时", 'name = "dsh-shizuku-wait"' in priv and "waiter.join(" in priv)
check("shizukuExec 超时会 destroy", "rp.destroy()" in priv)

b = priv.index("fun backupHome")
be = priv.index("/**", b + 10) if "/**" in priv[b + 10 :] else len(priv)
bseg = priv[b:be]
check("backupHome 的 tar 有 60s 超时", "waitFor(60" in bseg)
check("超时会销毁 tar 进程", "proc.destroy()" in bseg)
check("超时会删掉半个备份文件", "out.delete()" in bseg)

check("reclaimPort 的 kill 脚本有超时",
      "waitFor(3, java.util.concurrent.TimeUnit.SECONDS)" in sup)
check("reclaimPort 超时会 destroy", "p.destroy()" in sup)

print("\n=== 5. Root 模式提示 ===")
onb = open(ONB, encoding="utf-8").read()
check("Root 二次确认里引用新提示", "ob_priv_root_paths_warn" in onb)

for path, label in [
    ("app/src/main/res/values/strings.xml", "en"),
    ("app/src/main/res/values-zh-rCN/strings.xml", "zh"),
    ("app/src/main/res/values-de/strings.xml", "de"),
]:
    p = os.path.join(ROOT, path)
    s = open(p, encoding="utf-8").read()
    check(f"[{label}] 有 ob_priv_root_paths_warn",
          'name="ob_priv_root_paths_warn"' in s)
    check(f"[{label}] 有 error_boot_timeout",
          'name="error_boot_timeout"' in s)
    check(f"[{label}] 没有裸单引号（AAPT 会报错）",
          not re.search(r'(?<!\\)\'(?!\\)', re.search(
              r'<string name="ob_priv_root_paths_warn">(.*?)</string>', s, re.S).group(1)),
          "裸 ' 需要转义")

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)

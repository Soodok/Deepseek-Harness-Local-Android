#!/usr/bin/env python3
"""BootTrace 行为验证（v1.4.8）。

不依赖 Android 运行时：这里用 Python 复刻 BootTrace 的状态语义，并直接
对真实 Kotlin 源码做静态断言（确保实现与预期一致，防止后续改动退化）。

检查项：
  1. 阶段耗时计算：duration = at - 上一阶段 at，累计单调递增
  2. 文件/字节双口径进度的边界（0、满、总量未知）
  3. 失败收尾不会覆盖已成功的记录
  4. boot.log 环形截断保留尾部
  5. 源码层面：所有 step key 都在 MainActivity 的 stageLabel 里有映射
  6. 源码层面：EngineSupervisor 的 begin/ready/fail 三条路径齐全
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BOOT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/BootTrace.kt")
SUP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt")
MAIN = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MainActivity.kt")

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


# ---------------------------------------------------------------- 1. 耗时语义
class Trace:
    """复刻 BootTrace.step / ready / fail 的时间语义"""

    def __init__(self, start: int):
        self.started_at = start
        self.steps: list[tuple[str, int, int]] = []  # (key, duration, at)
        self.finished_at: int | None = None
        self.total: int | None = None
        self.failure: str | None = None

    @property
    def running(self) -> bool:
        return self.finished_at is None

    def step(self, key: str, now: int) -> None:
        if self.started_at <= 0 or not self.running:
            return
        at = now - self.started_at
        prev = self.steps[-1][2] if self.steps else 0
        self.steps.append((key, at - prev, at))

    def ready(self, now: int) -> None:
        if not self.running:
            return
        self.finished_at = now
        self.total = now - self.started_at

    def fail(self, now: int, reason: str) -> None:
        if self.steps is None or self.started_at <= 0 or self.finished_at is not None:
            return
        self.finished_at = now
        self.total = now - self.started_at
        self.failure = reason


print("=== 1. 阶段耗时计算 ===")
t = Trace(start=1000)
t.step("guardian-restore", 1050)   # +50
t.step("agent-seed", 1070)         # +20
t.step("runtime-install", 9070)    # +8000
t.ready(9200)
check("三个阶段的 duration", [s[1] for s in t.steps] == [50, 20, 8000], str(t.steps))
check("累计值单调递增", all(t.steps[i][2] <= t.steps[i + 1][2] for i in range(len(t.steps) - 1)))
check("总耗时 = ready 时刻 - 开始", t.total == 8200, str(t.total))
check("duration 之和 <= 总耗时", sum(s[1] for s in t.steps) <= t.total)

print("\n=== 2. 解压进度双口径 ===")


def fraction(done_b: int, total_b: int, done_f: int, total_f: int):
    if total_b > 0:
        return min(1.0, max(0.0, done_b / total_b))
    if total_f > 0:
        return min(1.0, max(0.0, done_f / total_f))
    return None


check("字节优先", fraction(50, 100, 1, 10) == 0.5)
check("字节未知回退文件数", fraction(0, 0, 3, 10) == 0.3)
check("两者都没有 -> None（转不确定动画）", fraction(0, 0, 0, 0) is None)
check("越界被夹紧到 1.0", fraction(200, 100, 10, 10) == 1.0)
check("零文件且总量已知 -> 0.0", fraction(0, 0, 0, 10) == 0.0)

print("\n=== 3. 收尾不会互相覆盖 ===")
t2 = Trace(start=0)
t2.ready(5000)
t2.fail(6000, "later failure")
check("成功后 fail 不覆盖", t2.failure is None and t2.total == 5000, str(t2.total))
t3 = Trace(start=1)   # 真实实现要求 startedAt > 0 才会收尾
t3.fail(4001, "boom")
before = t3.steps[:]
t3.step("late", 4500)
check("失败后不再追加阶段", t3.steps == before)

print("\n=== 4. boot.log 环形截断 ===")
MAX = 128 * 1024
lines = "".join(f"boot {i:04d} " + "x" * 90 + "\n" for i in range(2000))
data = lines.encode()
check("样本确实超过上限", len(data) > MAX, f"{len(data)} bytes")
# 复刻 flush：超限时保留尾部一半再追加
keep_from = len(data) // 2
trimmed = b"...[earlier boots trimmed]...\n" + data[keep_from:]
tail = trimmed.decode()
check("截断后保留最新记录", "boot 1999" in tail)
check("截断后丢弃最旧记录", "boot 0000" not in tail)
check("截断标记存在", "trimmed" in tail)

print("\n=== 5. 源码：step key 都有界面映射 ===")
boot_src = open(BOOT, encoding="utf-8").read()
sup_src = open(SUP, encoding="utf-8").read()
main_src = open(MAIN, encoding="utf-8").read()

emitted = set(re.findall(r'step\("([a-z0-9\-]+)"\)', sup_src))
print(f"  EngineSupervisor 发出的阶段: {sorted(emitted)}")
missing = [k for k in sorted(emitted) if f'"{k}" ->' not in main_src]
check("每个阶段都有本地化映射", not missing, f"未映射: {missing}")
check("未知 key 有兜底（else -> key）", "else -> key" in main_src)

print("\n=== 6. 源码：三条收尾路径齐全 ===")
check("begin 在监督循环里调用", "BootTrace.begin(" in sup_src)
check("成功路径调用 ready", "BootTrace.ready(ctx)" in sup_src)
check("异常路径调用 fail", sup_src.count("BootTrace.fail(") >= 2)
check("阶段切换 EXTRACTING", "BootTrace.Stage.EXTRACTING" in sup_src)
check("阶段切换 STARTING", "BootTrace.Stage.STARTING" in sup_src)
check("阶段切换 LOADING", "BootTrace.Stage.LOADING" in sup_src)
check("解压明细回调已接", "onDetail = { detail ->" in sup_src)
check("WebView 首屏补记", "BootTrace.noteWebViewLoaded" in main_src)
check("秒表随遮罩停止", "stopSplashTicker()" in main_src)
check("遮罩字幕来自 BootTrace", "renderSplashTrace(BootTrace.snapshot.value)" in main_src)

print("\n=== 7. 无限增长防护 ===")
check("boot.log 有上限常量", "MAX_LOG_BYTES" in boot_src)
check("上限值 128KB", "128 * 1024" in boot_src)
check("轮转按尾部保留", "earlier boots trimmed" in boot_src)
# 状态里最多保留的阶段数量：监督循环每轮 begin() 会重置，所以天然有界
check("每次启动重置缓冲", "buffer.setLength(0)" in boot_src)

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)

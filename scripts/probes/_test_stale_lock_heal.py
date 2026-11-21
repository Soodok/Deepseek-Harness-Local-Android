#!/usr/bin/env python3
"""残留锁清理（StaleLockHealer）的形态守卫。

## 这一层为什么必须单独守

功能本身的判定表已有 JVM 单测（StaleLockHealerTest，11 条）。但单测只覆盖
**纯判定**，守不住「接线」与「判活实现」—— 而这两处恰好各踩过一次：

1. **接线漏了探测参数**：heal(ctx) 不带 engineRunning，退避重试期间上一轮
   引擎还没死透时会删掉真活的锁 → 两个 node 同时写 package.json。
2. **判活用了错误的系统调用**：Process.sendSignal(pid, 0) 对超出 pid_max
   （该机 32768）的 pid 不报错，于是 999999 被判成 alive=true —— 设备日志里
   出现过 "removed stale lock ... pid=999999 alive=true" 这种自相矛盾的行。
   改成 Os.stat("/proc/<pid>") 读 errno 三态才正确。

## 数据来源

五种残留形态与耗时都是模拟器 emulator-5554 上实测的（2026-11-21）：
只有「死 pid」这一种上游 takeOverExitedLock() 能自己接管，其余四种等到超时。
真机后果：往 .credentials.yaml.lock 写一个活着的无关 pid 后冷启，
boot step health-poll: 40484ms 后整个引擎失败。
"""
from __future__ import annotations

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HEALER = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/StaleLockHealer.kt")
SUP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt")
TEST = os.path.join(ROOT, "app/src/test/java/app/dsh/mobile/engine/StaleLockHealerTest.kt")

ok = 0
fail = 0


def check(label, cond, detail=""):
    global ok, fail
    if cond:
        ok += 1
        print("  \u2713 " + label)
    else:
        fail += 1
        print("  \u2717 " + label + ("  [" + detail + "]" if detail else ""))


def read(path):
    return io.open(path, encoding="utf-8").read()


src = read(HEALER)
sup = read(SUP)
test = read(TEST)


def code_lines(text):
    """剔除注释与文档字符串，只留会真正编译进产物的代码行。

    必需：判活那一段的注释里必须写出「为什么不能用 sendSignal」，
    否则下一个人会把它改回去。断言只看代码，才既保留解释又不误判。
    """
    out = []
    in_block = False
    for line in text.splitlines():
        t = line.strip()
        if in_block:
            if "*/" in t:
                in_block = False
            continue
        if t.startswith("/*"):
            if "*/" not in t:
                in_block = True
            continue
        if t.startswith("*") or t.startswith("//"):
            continue
        out.append(line)
    return "\n".join(out)


healer_code = code_lines(src)

print("=== 1. 残留锁清理已接入启动序列 ===")
check("heal task exists in the parallel preflight block", 'runParallelInit("stale-locks")' in sup)
check(
    "heal task passes the live-engine probe",
    "StaleLockHealer.heal(ctx) { portInUse(" in sup,
    "少了 engineRunning 参数就会删真活的锁",
)
check("heal result is logged", "stale-lock heal: removed=" in sup)

print("")
print("")
print("=== 2. 判活实现必须靠 errno 三态 ===")
# 设备实测的反向断言：sendSignal(pid,0) 把 999999 判成 alive。
check(
    "liveness uses Os.stat, not sendSignal",
    "Os.stat(" in healer_code and "sendSignal" not in healer_code,
    "sendSignal(pid,0) 对超出 pid_max 的 pid 不报错",
)
check(
    "ENOENT is the only proof of death",
    "ENOENT" in healer_code,
    "EACCES/EPERM 都是「在但看不到」",
)

print("")
print("=== 2b. 判定不靠 uid/域，而靠锁的年龄 ===")
# 被证伪过两次的路线：先用 sendSignal 判活（999999 假活），再想用
# /proc/<pid> 的 st_uid 区分（EACCES 同时覆盖「别的 uid」与「同 uid 异域」）。
# 现在唯一判据是锁文件的 mtime —— 与 pid 复用、uid 可见性、SELinux 域无关。
check(
    "freshness threshold is defined",
    "FRESH_MS" in healer_code,
)
check(
    "decision uses the lock file mtime",
    "f.lastModified()" in healer_code and "ageMs" in healer_code,
)
check(
    "uid-based guessing was removed",
    "st_uid" not in healer_code and "uidOfProcess" not in healer_code,
    "EACCES 既可能是 pid 复用也可能是跨域活写者，判不出来",
)
check(
    "unknown age keeps the lock",
    "ageMs >= FRESH_MS" in healer_code,
)
check(
    "malformed lock records are deleted regardless of age",
    "parseHolder(record) ?: return Action.DELETE" in healer_code,
    "写到一半被杀留下的空锁没有合法持有者，留着就是 40.5s 启动失败",
)
print("=== 3. 谨慎的边界（不该删的一定不删）===")
check(
    "session.lock is never touched",
    '"session.lock"' in src and "NEVER" in src,
    "flock 内核锁，删文件会造成双写",
)
check("only own-uid locks are removed", "owner > 0 && owner != uid" in src, "属主不是自己就不删")
check(
    "live dsh-held locks are kept",
    "Action.KEEP" in src and "holderIsDsh == true" in src,
)
check(
    "scan is a whitelist, never recursive",
    "scanDirs" in src and "walkTopDown" not in src,
    "递归会进 node_modules / sessions / workspaces",
)
check("deletion count is bounded", "MAX_DELETIONS" in src, "磁盘满时不能拖死启动")
check(
    "takeover claim files are covered",
    '".lock.takeover-"' in src,
    "死在「建了 claim 没删 claim」窗口会永久自锁",
)

print("")
print("=== 4. 判定表被单测覆盖 ===")
for name in [
    "liveDshHolderIsKept",
    "oldLockWithLiveForeignHolderIsDeleted",
    "freshUnprovableHolderIsKept",
    "oldUnprovableHolderIsDeleted",
    "unknownAgeIsKept",
    "malformedRecordsAreAlwaysDeleted",
]:
    check("unit test covers " + name, name in test)

print("")
print("-" * 48)
print("通过 " + str(ok) + " / 失败 " + str(fail))
sys.exit(1 if fail else 0)

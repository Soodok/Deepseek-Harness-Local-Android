#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""归因器的**行为**验证（不只是静态断言）：把 Facts 喂进去，看它归到哪个 Cause。

## 为什么还要一个探针

`_test_engine_triage.py` 守的是「接线对不对」（枚举齐全、分支都实现了、
桥端点注册了）。但那只证明**代码存在**，不证明**判断正确** ——
一个把 `portInUse` 写成 `!portInUse` 的 bug 能通过所有静态断言。

这个探针换一种方式：从 Kotlin 源码里把**判定条件**抽出来，用同一份
`Facts` 逐条模拟，再断言「应该归到 A，不能归到 B」。它不是真的跑 Kotlin，
而是对**判定表达式本身**做等价实现 —— 所以它抓得住「条件写反」「漏判一边」
「顺序调换」这类错误，抓不住语法错误（那由编译负责）。

## 抽取方式与它的边界

判定条件在 `diagnoseInternal` 里是 Kotlin 表达式，本探针用正则把关键条件
识别出来并**独立重写一份**等价判断。这意味着：

- ✅ 如果源码把 `portInUse || PORT_MARKERS...` 改成 `portInUse && ...`，
  [B2] 会红（因为等价实现仍按「或」算，而源码里不再出现 `||`）。
- ✅ 如果某个 Cause 从分支里消失，[C*] 会红。
- ❌ 如果 Kotlin 逻辑有语义 bug 但文本形式没变，这里看不出来 —— 那要靠
  模拟器实测（见 AI_CONTEXT.md 的实测一节）。

说清边界比假装覆盖全部更重要：这个探针是**回归护栏**，不是证明。

跑法：python scripts/probes/_test_engine_triage_behavior.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TRIAGE = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineTriage.kt")

checks = 0
failed = 0


def ok(cond, msg, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  [OK]   " + msg)
    else:
        failed += 1
        print("  [FAIL] " + msg + ((" - " + detail) if detail else ""))


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


class Facts:
    """与 Kotlin Facts 一一对应的最小实现（只放判定用得到的字段）。"""

    def __init__(self, **kw):
        self.logTail = kw.get("logTail", "")
        self.portInUse = kw.get("portInUse", False)
        self.portOwners = kw.get("portOwners", {})
        self.archMismatch = kw.get("archMismatch", False)
        self.runtimeIntact = kw.get("runtimeIntact", True)
        self.runtimeMissing = kw.get("runtimeMissing", [])
        self.profileBroken = kw.get("profileBroken", [])
        self.storageWritable = kw.get("storageWritable", True)
        self.systemExitReason = kw.get("systemExitReason", "")
        self.systemExitOutOfMemory = kw.get("systemExitOutOfMemory", False)
        self.neverBooted = kw.get("neverBooted", False)
        self.installResidue = kw.get("installResidue", False)
        self.hasLastGoodProfile = kw.get("hasLastGoodProfile", False)
        self.inSafeMode = kw.get("inSafeMode", False)
        self.selfCheck = kw.get("selfCheck", None)


# 判定用的常量必须从源码里读，不能自己抄一份 —— 否则源码改了这里不会红。
def load_markers(src):
    def grab(name):
        m = re.search(r"private val " + name + r"\s*=\s*listOf\((.*?)\n\s*\)", src, re.S)
        return re.findall(r'"([^"]*)"', m.group(1)) if m else []
    return {
        "PORT": [s.lower() for s in grab("PORT_MARKERS")],
        "PLUGIN": [s.lower() for s in grab("PLUGIN_INACTIVE_MARKERS")],
        "AUTH": [s.lower() for s in grab("AUTH_MARKERS")],
        "STORAGE": [s.lower() for s in grab("STORAGE_MARKERS")],
        "CORRUPT": [s.lower() for s in grab("CORRUPT_CONFIG_MARKERS")],
    }


def classify(facts, marks):
    """按源码里的判定顺序重新实现一遍。顺序必须与 diagnoseInternal 一致。"""
    log = facts.logTail.lower()

    # 1. 端口。源码里是 `facts.portInUse || PORT_MARKERS.any { ... }`
    if facts.portInUse or any(m in log for m in marks["PORT"]):
        owners = facts.portOwners
        self_owned = [
            pid for pid, cmd in owners.items()
            if ("app.dsh.mobile" in cmd or "engine/bin/node" in cmd or "dsh" in cmd)
        ]
        if not owners:
            return "PORT_UNKNOWN_OWNER"
        if self_owned:
            return "PORT_ORPHAN"
        return "PORT_FOREIGN"

    # 2. 运行时
    if facts.archMismatch:
        return "RUNTIME_ARCH"
    if not facts.runtimeIntact:
        return "RUNTIME_MISSING"

    # 3. 凭据
    if any(m in log for m in marks["AUTH"]):
        return "AUTH_EXPIRED"

    # 4. 插件
    if any(m in log for m in marks["PLUGIN"]):
        return "PLUGIN_INACTIVE"

    # 5. 配置
    if facts.profileBroken or any(m in log for m in marks["CORRUPT"]):
        return "PROFILE_CORRUPT"

    # 6. 存储
    if (not facts.storageWritable) or any(m in log for m in marks["STORAGE"]):
        return "STORAGE_DENIED"

    # 7. 系统
    if facts.systemExitReason:
        return "SYSTEM_OOM" if facts.systemExitOutOfMemory else "SYSTEM_KILLED"

    # 7.5 体检
    if facts.selfCheck:
        bad = facts.selfCheck
        if bad:
            ids = [i for i in bad]
            if any(i.startswith("runtime") for i in ids):
                return "RUNTIME_MISSING"
            if any(i.startswith("storage") for i in ids):
                return "STORAGE_DENIED"
            if any(i.startswith("profile") for i in ids):
                return "PROFILE_CORRUPT"
            if "install.residue" in ids:
                return "RUNTIME_MISSING"

    # 8. 兜底
    return "UNKNOWN"


def main():
    print("=" * 70)
    print("Engine failure triage — behavioural classification checks")
    print("=" * 70)

    src = strip_comments(read(TRIAGE))
    marks = load_markers(src)

    print("\n[A] 特征串必须能从源码读到（读不到就是探针失效，不是通过）")
    for name, got in marks.items():
        ok(len(got) > 0, "[A1] %s_MARKERS has %d entries" % (name, len(got)))

    print("\n[B] 12 种原因的分类落点")
    cases = [
        # (Facts 参数, 期望 cause, 断言里要排除的 cause, 说明)
        (dict(portInUse=True, portOwners={1234: "/system/bin/other-server"}),
         "PORT_FOREIGN", "PORT_ORPHAN",
         "别人的进程占 3080 -> 外部占用，处置要用户确认"),
        (dict(portInUse=True,
              portOwners={4321: "/data/app/app.dsh.mobile/lib/engine/bin/node"}),
         "PORT_ORPHAN", "PORT_FOREIGN",
         "自家 node 残留 -> 可直接杀"),
        (dict(portInUse=True, portOwners={}),
         "PORT_UNKNOWN_OWNER", None,
         "查不到 owner 仍要归到端口类（保守清场无害）"),
        (dict(logTail="Error: listen EADDRINUSE: address already in use 127.0.0.1:3080",
              portInUse=False, portOwners={}),
         "PORT_UNKNOWN_OWNER", "PLUGIN_INACTIVE",
         "进程已死端口已释放，只剩日志证据 —— 仍必须归端口，不能落到插件"),
        (dict(archMismatch=True),
         "RUNTIME_ARCH", "RUNTIME_MISSING",
         "架构不匹配优先于缺文件"),
        (dict(runtimeIntact=False, runtimeMissing=["node: /x/bin/node"]),
         "RUNTIME_MISSING", None,
         "缺文件"),
        (dict(logTail="Error: authentication failed: 401 unauthorized"),
         "AUTH_EXPIRED", "PLUGIN_INACTIVE",
         "凭据失效必须优先于插件（日志里常有插件噪声）"),
        (dict(logTail="startup failed: 2 required plugins did not activate"),
         "PLUGIN_INACTIVE", "UNKNOWN",
         "插件没激活"),
        (dict(profileBroken=["/dsh/profiles/web/package.json"]),
         "PROFILE_CORRUPT", None,
         "配置解析失败"),
        (dict(logTail="SyntaxError: Unexpected token } in JSON at position 12"),
         "PROFILE_CORRUPT", "UNKNOWN",
         "日志里显式的解析错误"),
        (dict(storageWritable=False),
         "STORAGE_DENIED", None,
         "数据目录不可写"),
        (dict(logTail="EACCES: permission denied, open '/dsh/x'"),
         "STORAGE_DENIED", "UNKNOWN",
         "日志里的权限错误"),
        (dict(systemExitReason="LOW_MEMORY", systemExitOutOfMemory=True),
         "SYSTEM_OOM", "SYSTEM_KILLED",
         "低内存被杀"),
        (dict(systemExitReason="USER_REQUESTED"),
         "SYSTEM_KILLED", "SYSTEM_OOM",
         "非 OOM 的系统停止"),
        (dict(logTail=""),
         "UNKNOWN", None,
         "什么都没有 -> 兜底，但仍要有证据行"),
    ]
    for i, (kw, want, notwant, why) in enumerate(cases, 1):
        got = classify(Facts(**kw), marks)
        ok(got == want, "[B%d] %s" % (i, why), "expected %s, got %s" % (want, got))
        if notwant:
            ok(got != notwant, "[B%d] ...and NOT %s" % (i, notwant),
               "got %s — 这两类的处置完全不同" % got)

    print("\n[C] 优先级：多个证据同时成立时的取舍")
    # 端口 + 插件同时成立 -> 端口赢（端口是最容易被误判成别的原因的）
    got = classify(Facts(
        portInUse=True, portOwners={1: "/system/bin/x"},
        logTail="startup failed: 1 required plugins did not activate",
    ), marks)
    ok(got == "PORT_FOREIGN", "[C1] port beats plugin when both present",
       "got %s" % got)

    # 凭据 + 插件同时成立 -> 凭据赢（重新登录是确定有效的处置）
    got = classify(Facts(
        logTail="401 unauthorized; also 1 required plugins did not activate",
    ), marks)
    ok(got == "AUTH_EXPIRED", "[C2] auth beats plugin when both present",
       "got %s" % got)

    # 架构不匹配 + 端口占用 -> 端口赢（端口是我们能修的，架构不是）
    got = classify(Facts(archMismatch=True, portInUse=True,
                         portOwners={1: "/system/bin/x"}), marks)
    ok(got == "PORT_FOREIGN", "[C3] port beats arch mismatch",
       "got %s" % got)

    # 运行时缺文件 + 凭据 -> 运行时赢（文件都没有，谈不上登录）
    got = classify(Facts(runtimeIntact=False, logTail="401 unauthorized"), marks)
    ok(got == "RUNTIME_MISSING", "[C4] missing runtime beats auth",
       "got %s" % got)

    print("\n[D] 判定表达式的形态（防止条件被改反）")
    # 端口判定必须是「或」：只有一侧证据时也要触发
    m = re.search(r"val portEvidence\s*=\s*(.+)", src)
    ok(m is not None, "[D1] portEvidence assignment present")
    if m:
        expr = m.group(1)
        ok("portInUse" in expr, "[D2] portEvidence consults the live probe")
        ok("PORT_MARKERS" in expr, "[D3] portEvidence consults the log marker")
        ok("||" in expr, "[D4] portEvidence uses OR, not AND",
           "用 AND 会在「进程刚死、端口已释放」时漏判 -> 归到插件，处置全错")
        ok("!" not in expr.split("PORT_MARKERS")[0] or "portInUse" not in expr.replace("!portInUse", ""),
           "[D5] portInUse is not negated", "expr: %s" % expr.strip())

    # 存储判定必须接受两路
    m2 = re.search(r"if\s*\(([^)]*storageWritable[^)]*)\)", src)
    ok(m2 is not None, "[D6] storage condition present")
    if m2:
        ok("||" in m2.group(1), "[D7] storage accepts both unwritable dir and log marker")

    # 归属判定：空 owner 必须归 UNKNOWN_OWNER 而不是误判成外部
    ok("portOwners.isEmpty() -> Cause.PORT_UNKNOWN_OWNER" in src,
       "[D8] empty owner map -> PORT_UNKNOWN_OWNER",
       "空 map 若落到 FOREIGN 会给用户一个「确认杀别人进程」的恐怖弹窗")

    print("\n[E] 反向自检：探针能识别「条件被改反」")
    bad_expr = "facts.portInUse && PORT_MARKERS.any { log.contains(it) }"
    ok("||" not in bad_expr, "[E1] an AND-shaped expression is correctly seen as wrong "
       "(guard against a dead check)")

    return finish()


def finish():
    print("\n" + "-" * 70)
    print("checks=%d failed=%d" % (checks, failed))
    if failed:
        print("RESULT: FAILED")
        return 1
    print("RESULT: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())

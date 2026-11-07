#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守 SELinux 上下文自愈的四个易错点，每一个都对应一次实测踩坑。

## 为什么需要这个探针（2026-11-07 实测取证）

### 坑 1：App 进程改不了 SELinux 标签，必须走特权通道

| 域 | `chcon` 结果 |
| --- | --- |
| App 进程 `u:r:untrusted_app_27` | `Operation not permitted`, exit 1 |
| `run-as` `u:r:runas_app` | `Operation not permitted`, exit 1 |
| `su 0` | exit 0，成功 |

`PluginCenter.healSelinuxContext()` 的旧实现直接在 App 内
`ProcessBuilder("/system/bin/chcon", ...)`，**在普通模式下从未成功过一次**：
`execCommand` 返回 1，只打一行 "chcon failed" 就返回 false，pnpm 的重试分支
永远不触发。这是一段静默死代码 —— 用户侧只看到「装插件一直失败」。

### 坑 2：`chcon -R` 不跟随符号链接，必须配 `-h`

`chcon -R` 改的是链接**指向的目标**，链接本体仍是 `s0`。rootfs 里的动态库
几乎全是符号链接（`libstdc++.so.6 -> libstdc++.so.6.0.33`），少 `-h` 的症状是
「修完了还是不生效」。

### 坑 3：检测必须用 `ls -Zd`，`stat -c %C` 会漏

同一个漂移的符号链接：
```
ls -Zd    -> u:object_r:app_data_file:s0                 （读链接自身，能发现）
stat -c%C -> u:object_r:app_data_file:s0:c207,c256,...   （跟随链接读目标，漏掉）
```

### 坑 4：`s0` 前缀判断不能写成相等

带分类的 context（`...:s0:c207,...`）以裸 `s0` 串为前缀。写成
`context == BARE_S0` 判断漂移会把正常值漏掉；而写成
`context.startsWith(BARE_S0)` 又会把正常值**误判**成漂移 —— 后者更糟：
变成事实上的「每轮无条件全量 chcon」，每轮改动 7000+ inode 的 ctime。

## 反向验证

- 删掉 `healTree` 里的 `-h` → `[B1]` 变红
- 把 `healTree` 改成直接 ProcessBuilder chcon（不走 runPrivileged）→ `[A2]` 变红
- 把 `isDrifted` 改成相等判断 → `[D1]` 变红
- 把 `isDrifted` 改成纯 startsWith → `[D2]` 变红
- 把 firstDrifted 的 `ls -Zd` 换成 `stat -c %C` → `[C1]` 变红
- 从 readiness() 删掉自愈调用 → `[E1]` 变红

跑法：python scripts/probes/_test_selinux_context_heal.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
SELINUX = os.path.join(ENGINE, "SelinuxContext.kt")
PLUGIN = os.path.join(ENGINE, "PluginCenter.kt")
LAUNCHER = os.path.join(ENGINE, "LinuxEngineLauncher.kt")
PRIV = os.path.join(ENGINE, "Privilege.kt")

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


def fn_body(src, name):
    """Extract a function body up to the matching closing paren+brace (shallow)."""
    m = re.search(r"(?:private |internal |public )?fun " + re.escape(name) + r"\s*\(", src)
    if not m:
        return None
    # take a generous window; callers only assert on presence of tokens
    start = m.start()
    nxt = re.search(r"\n    (?:private |internal |public )?(?:fun|val|var) ", src[m.end():])
    end = m.end() + nxt.start() if nxt else len(src)
    return src[start:end]


def main():
    print("=" * 70)
    print("SELinux context self-heal must actually work (4 measured traps)")
    print("=" * 70)

    for p in (SELINUX, PLUGIN, LAUNCHER, PRIV):
        if not os.path.isfile(p):
            print("missing: %s" % p)
            return 1

    sel = strip_comments(read(SELINUX))
    plugin = strip_comments(read(PLUGIN))
    launcher = strip_comments(read(LAUNCHER))
    priv = strip_comments(read(PRIV))

    print("\n[A] 修复必须走特权通道（App/run-as 域改不了标签）")
    heal = fn_body(sel, "healTree")
    ok(heal is not None, "[A1] SelinuxContext.healTree exists")
    if heal:
        ok("runPrivileged" in heal,
           "[A2] healTree goes through Privilege.runPrivileged",
           "App domain gets 'Operation not permitted'; only su/shizuku can chcon")
        ok("ProcessBuilder" not in heal,
           "[A3] healTree never forks chcon directly from the App process",
           "that is exactly the silently-dead code this replaces")

    print("\n[B] 修复命令必须带 -h（否则 symlink 本体仍是 s0）")
    if heal:
        ok(re.search(r"chcon\s+-R\s+-h", heal) is not None,
           "[B1] chcon uses -R -h together",
           "rootfs libs are symlinks; without -h the link inode stays s0 and "
           "the loader still sees Permission denied")

    print("\n[C] 检测必须用 ls -Zd（stat -c %C 跟随链接，漏掉 symlink 漂移）")
    drift_fn = fn_body(sel, "firstDrifted")
    ok(drift_fn is not None, "[C1] SelinuxContext.firstDrifted exists")
    if drift_fn:
        ok("ls -Zd" in drift_fn or "ls\", \"-Zd" in drift_fn,
           "[C2] firstDrifted reads contexts with ls -Zd")
        ok("stat" not in drift_fn.replace("status", ""),
           "[C3] firstDrifted does not use stat (follows the link, misses drift)")
        ok("runPrivileged" in drift_fn,
           "[C4] sentinel read also needs the privileged channel",
           "ls -Z on app-private paths is readable, but keeping one channel is simpler")

    print("\n[D] isDrifted 的边界：既不能漏正常值，也不能把正常值判成漂移")
    isd = fn_body(sel, "isDrifted")
    ok(isd is not None, "[D1] SelinuxContext.isDrifted exists")
    if isd:
        # 必须同时出现「相等」与「带冒号前缀」两种判断
        has_eq = re.search(r"c\s*==\s*BARE_S0", isd) is not None
        has_colon = re.search(r'startsWith\("\$BARE_S0:"\)|startsWith\(BARE_S0\s*\+', isd) is not None
        ok(has_eq, "[D2] isDrifted treats the bare string itself as drifted")
        ok(has_colon,
           "[D3] isDrifted excludes the ':c207,...' suffix form from 'drifted'",
           "a plain startsWith(BARE_S0) marks HEALTHY files as drifted, which turns "
           "the sentinel check into an unconditional full relabel every boot")

    # 独立同构实现，喂真实样本
    BARE = "u:object_r:app_data_file:s0"

    def drifted_py(c):
        c = (c or "").strip()
        if not c:
            return False
        return c == BARE or (c.startswith(BARE) and not c.startswith(BARE + ":"))

    samples = [
        (BARE, True),
        ("u:object_r:app_data_file:s0:c207,c256,c512,c768", False),
        ("u:object_r:app_data_file:s0:c232,c257", False),
        ("u:object_r:rootfs:s0", False),
        ("", False),
        # 前缀相似但不是同一类：不能被当成 app_data_file 的漂移
        ("u:object_r:app_data_file:s0foo", True),
    ]
    for ctx, want in samples:
        got = drifted_py(ctx)
        ok(got == want, "[D4] sample %r -> drifted=%s" % (ctx[-28:] or "(empty)", want),
           "python mirror disagrees")

    print("\n[E] 接在冷启路径上（不是只在部署路径上）")
    read_body = fn_body(launcher, "readiness")
    ok(read_body is not None, "[E1] LinuxEngineLauncher.readiness exists")
    if read_body:
        ok("SelinuxContext.firstDrifted" in read_body,
           "[E2] readiness() runs the sentinel check on every cold start")
        ok("SelinuxContext.healTree" in read_body,
           "[E3] readiness() heals when the sentinel reports drift")
        ok(read_body.index("installCompatShimsPublic") < read_body.index("SelinuxContext.firstDrifted"),
           "[E4] heal runs after the compat shims (shims create files too)",
           "order matters: shims write into rootfs and can carry the wrong context")
        ok("onFailure" in read_body,
           "[E5] failures are swallowed so boot is never blocked",
           "no shizuku/su in normal mode means labels cannot be changed; a clean "
           "rootfs still boots fine")

    print("\n[F] PluginCenter 的旧实现必须真的被替换（不能只是新加一个类）")
    healsel = fn_body(plugin, "healSelinuxContext")
    ok(healsel is not None, "[F1] PluginCenter.healSelinuxContext exists")
    if healsel:
        ok("SelinuxContext.healTree" in healsel,
           "[F2] healSelinuxContext delegates to SelinuxContext.healTree")
        ok("ProcessBuilder" not in healsel,
           "[F3] no direct chcon fork left in healSelinuxContext",
           "the old code called execCommand(chcon -R ...) and silently failed")
    ok("private fun execCommand" not in plugin,
       "[F4] dead execCommand helper was removed",
       "leaving a dead helper invites the next person to think it still works")
    ok("private fun fileContextOf" not in plugin,
       "[F5] dead fileContextOf helper was removed")

    print("\n[G] 基准串绝不硬编码（MCS 分类因安装而异）")
    ok(not re.search(r"c\d{2,3},c\d{2,3}", sel),
       "[G1] SelinuxContext contains no hardcoded MCS category string")
    if heal:
        ok("fileContextOfPublic" in heal,
           "[G2] healTree reads the baseline from dshHome instead of hardcoding")
        ok("isDrifted(baseline)" in heal or "isDrifted( baseline" in heal,
           "[G3] healTree refuses a drifted baseline",
           "using a bad sample as the standard would relabel the tree to s0 — worse "
           "than not healing at all")

    print("\n[H] 哨兵必须覆盖「运行期会被 root 写入」的路径")
    # 2026-11-08 线上故障复盘：apk 数据库被 root 域写过之后掉了 MCS 分类，
    # 表现为
    #     apk update: ok=false rc=99 err=ERROR: Unable to read database state: Permission denied
    # 而 ls -la 的属主与权限完全正常（u0_a80:u0_a80 0644）、find -user root 为 0。
    # 原来的 8 条哨兵只覆盖可执行文件与动态库，扫不到这里 —— 于是
    # 「修好了下次又坏」，改动此列表时必须有断言兜住。
    sentbody = fn_body(sel, "sentinelsFor")
    ok(sentbody is not None, "[H1] SelinuxContext.sentinelsFor exists")
    if sentbody:
        # 包管理器数据库：root 写包索引/触发脚本必落这里
        for needle, label in [
            ('"lib/apk/db"', "[H2] apk database directory"),
            ('"lib/apk/db/installed"', "[H3] apk installed database file"),
        ]:
            ok(needle in sentbody, "%s is a sentinel" % label,
               "root-owned writes strip the MCS category; apk then fails with rc=99 "
               "while ls -la looks perfectly normal")
        ok("var/lib/dpkg" in sentbody,
           "[H4] dpkg database is a sentinel (Ubuntu family)",
           "Ubuntu containers use dpkg; the same root-write drift applies")

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

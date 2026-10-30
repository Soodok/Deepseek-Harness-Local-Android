#!/usr/bin/env python3
"""issue #65 回归护栏：root 模式下 pnpm 锁目录的属主不能被 chown 掉。

## 事件

用户（KernelSU，`DSH_ANDROID_PRIV_MODE=root`）报：插件页点任何插件的「卸载」
都失败，所有插件一样：

    ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK
    lock directory must be a real directory owned by the current user:
    .../dsh-home/.cache/pnpm-store-operation-locks-0

**每次重启引擎都复发**，手动改回属主只能撑到下一次启动。

## 根因

pnpm 把操作锁建在 `$HOME/.cache/pnpm-*-locks-<uid>` 这类**按 uid 分后缀**的目录
里。引擎以 root 跑时用的是 `...-locks-0`；App 主进程是普通 uid。而
`Privilege.fixHomeOwnership()` 在每次启动引擎时对整棵 `dsh-home` 递归
`chown -R <appUid>:<appUid>` —— 把 root 那一套锁目录也改成了 app uid，于是
root 身份的 pnpm 认为「锁目录不属于我」，拒绝使用。

## 守卫

`fixHomeOwnership` 必须在递归 chown 之后，把 `.cache` 下 pnpm 锁/交换目录的
属主**还原成各自的数字后缀**（`-0` → root，`-<appUid>` → app）。这样两个身份
各拿自己那份，互不干扰。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PRIV = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                    "engine", "Privilege.kt")

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


src = open(PRIV, encoding="utf-8").read()

# 1) v1.4.35 方案：chown 时整棵跳过 pnpm 锁树（prune），而不是「先改再还原」
check("fix: 使用 find -prune 排除 pnpm 锁树",
      "-prune" in src,
      "v1.4.34 的「只还原目录本身」漏了目录里的文件 → issue #67")
check("fix: 覆盖 store-operation-locks",
      "pnpm-store-operation-locks-*" in src,
      "缺它 → root 模式 pnpm 拿不到自己的锁，装卸插件必失败")
check("fix: 覆盖 dir-locks",
      "pnpm-dir-locks-*" in src,
      "pnpm 的目录锁是另一套前缀")
check("fix: 覆盖 modules-swaps",
      "pnpm-modules-swaps-*" in src)
OLD_RESTORE = 'chown "' + chr(92) + '"$n:$n' + chr(92) + '"' + '"'
check("fix: 不再用「chown 全树再逐个还原」的旧方案",
      OLD_RESTORE not in src,
      "旧方案只改目录本身，目录内文件仍被改回 app uid（#67 的根因）")
check("fix: 整棵树用 -exec chown 批量处理（幂等且高效）",
      "-exec chown " in src and "{} +" in src,
      "逐文件 fork 一次 chown 在几万文件的 dsh-home 上会非常慢")

# 4) 仍然保留原有的关键行为，避免修 #65 时破坏别的自愈
check("keep: 仍对 engine 目录补 u+rwX",
      "chmod -R u+rwX" in src,
      "重装后新 uid 读不了插件，缺这条引擎插件全哑")
check("keep: 仍 restorecon 重置 SELinux 标签",
      "restorecon -R" in src)

# 5) 命令拼接不能引入未转义的 Kotlin 模板
check("safety: -path 参数用 shellQuote 整体加引号",
      "shellQuote(homePlain + pat)" in src,
      "裸拼 homeQ + pat 会让通配符在引号外被 shell 提前展开")
check("safety: 不用未转义的分组括号",
      "append('(')" not in src and 'append("(")' not in src,
      "toybox find 对分组括号的转义与 GNU 不一致，能不用就不用")
check("#74: metadata 纳入自管理（chown prune 掉）",
      '-o -path " + shellQuote(home.absolutePath + PNPM_METADATA + "*") + " -prune -o' in src,
      "root 写的 metadata 在普通模式写不进；chown 又会被 root 写回（翻烧饼）")
check("#74: 检测侧也 prune 自管理状态（正向 -path）",
      "pnpmSelfManagedPruneExpr" in src and
      (chr(92) + 'not -path " + shellQuote(home.absolutePath + PNPM_METADATA') not in src,
      "否则 -not -user 每轮命中外来锁 → 每轮检出、每轮不修 → 死循环；"
      "且 -not -path L -prune -o X 在 L 命中时左侧为假，prune 形同虚设")
check("#74: 外来状态探测（锁后缀 ≠ 引擎 uid）",
      "pnpmForeignStateProbe" in src and
      ("case " + chr(92) + chr(34) + chr(92) + "$d" + chr(92) + chr(34) + " in *-") in src,
      "启动时能主动发现该清的锁，而不是等安装报错")
check("#74: metadata 只在非 root 引擎时探 root 属主",
      "if (euid != 0)" in src,
      "root 模式 metadata 属主 root 是正常态，探了永远命中（又死循环）")
check("#74: chcon 用真实 context 兜底（stat -c %C）",
      "stat -c '%C'" in src and "chcon -R" in src,
      "restorecon 各设备差异大；类别集（c512,c768 等）因设备而异不能写死")
check("#74: 自检与审计有独立日志标记",
      "ISLAND-SELFTEST-READ-OK" in src and "ISLAND-ROOT-LEFTOVER" in src,
      "远程排障时 grep 一眼定位是属主还是 SELinux 挡路")
check("#74: 自检真实读 root 写过的关键文件",
      (chr(92) + "$(su ") in src and "profiles/web/package.json" in src,
      "「实际 open」才算数（与 hasUnreadableEntries 同一哲学）")

print("\n" + "-" * 44)

# ============================================================
# v1.4.46（群友 dsh-root-doctor v0.2.0 实测经验）
# susfs / 隐藏挂载环境下**无界遍历会卡死**。对方实测：唯一安全的形式是
# `find $F/dsh-home -xdev`（约 20ms）；去掉 -xdev 会挂住。
# 另外不能对 engine 本体做递归 chown —— 引擎正在里面执行。
# ============================================================
check("susfs: chown 的 find 带 -xdev（防隐藏挂载卡死）",
      "append(\"-xdev \")" in src,
      "无界遍历在 susfs 环境会挂住，启动链路直接卡死")
check("susfs: 检测的 find 带 -xdev",
      chr(34) + " -xdev " + chr(34) + " +" in src.replace("'", chr(34)),
      "检测也是遍历，同样会卡")
check("safety: chown 不再递归 engine 本体",
      "append(engineQ).append(\" \")" not in src,
      "引擎正运行在 engine/ 里，递归改属主有风险且无必要")

print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)

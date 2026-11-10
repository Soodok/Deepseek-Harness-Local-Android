#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Linux 模式的 Web 终端必须跑在容器里，而不是宿主 bionic shell。

## 这道探针守的是什么

用户在 Linux 模式下打开 dsh 终端，敲 `apk` / `gcc` 全部 `not found`。

根因链（实测过，不是推测）：
1. 引擎开终端走 node-pty → 本仓库的 Android 兼容层 → 原生助手 `dsh_pty_exec`。
2. `dsh_pty_exec` 是 **bionic（NDK）** 二进制。`dsh_alpine` 的
   `guest_is_bionic()` 把它判定成「宿主程序」，交给宿主的 cleaner 代跑 ——
   那一侧**没有容器的 bind 视图**。
3. 于是它 `execv("/bin/sh")` 命中的是宿主的 `/system/bin/sh`。
   模拟器实测：`readlink /proc/$$/exe` = `/system/bin/sh`、
   `[ -d /etc/apk ]` = NO、`PATH` 全是 `/product/bin:/apex/...`。

修法是把「exec shell」改写成「经 dsh_alpine 进容器再 exec shell」：
Kotlin 侧 `LinuxEngineLauncher.buildEngineArgv` 把容器参数（runner / rootfs /
interp / bind 表）以 `DSH_TERMINAL_*` 环境变量注入，node-pty 兼容层的
`containerWrap()` 读出来拼成容器 argv。缺任一必需量时原样直通，原生模式行为不变。

## 判据

静态检查，不跑构建、不连设备。仿真侧的真实效果已在模拟器上用
`dsh_pty_exec` 直接重放 shim 生成的 argv 验过（`RELEASE=3.22.2` / `PWD=/ws`）。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LAUNCHER = os.path.join(ROOT, 'app/src/main/java/app/dsh/mobile/engine/LinuxEngineLauncher.kt')
PATCH = os.path.join(ROOT, 'scripts/patch-node-pty-android.py')
COLLECT = os.path.join(ROOT, 'scripts/collect-termux-runtime.sh')

checks = []


def check(name, ok, detail=''):
    checks.append((name, bool(ok), detail))


def read(p):
    if not os.path.isfile(p):
        return None
    with open(p, encoding='utf-8') as f:
        return f.read()


def main():
    launcher = read(LAUNCHER)
    patch = read(PATCH)

    check('[A1] LinuxEngineLauncher.kt exists', launcher is not None, LAUNCHER)
    check('[A2] patch-node-pty-android.py exists', patch is not None, PATCH)

    if launcher is None or patch is None:
        return report()

    # ---- [B] Kotlin 侧注入四个容器参数 ----
    for key in ('DSH_TERMINAL_RUNNER', 'DSH_TERMINAL_ROOTFS',
                'DSH_TERMINAL_INTERP', 'DSH_TERMINAL_BINDS'):
        check('[B] engine env injects %s' % key, key in launcher)

    # bind 表必须是「每行 guest:host」—— 路径带空格，不能用空格分隔
    binds_line = next((l for l in launcher.split('\n') if 'DSH_TERMINAL_BINDS' in l), '')
    check('[B5] binds encoded per-line (joinToString with \\n)',
          'joinToString("\\n")' in binds_line, binds_line.strip()[:90])

    # ---- [C] 兼容层实现 containerWrap ----
    check('[C1] shim defines containerWrap', 'function containerWrap(' in patch)
    check('[C2] shim reads DSH_TERMINAL_RUNNER', 'DSH_TERMINAL_RUNNER' in patch)
    check('[C3] shim reads DSH_TERMINAL_ROOTFS', 'DSH_TERMINAL_ROOTFS' in patch)
    check('[C4] shim reads DSH_TERMINAL_INTERP', 'DSH_TERMINAL_INTERP' in patch)
    check('[C5] shim reads DSH_TERMINAL_BINDS', 'DSH_TERMINAL_BINDS' in patch)

    # ---- [D] spawn 里真的用上了包装结果 ----
    #
    # ★必须匹配**赋值调用**，不能只匹配 `containerWrap(file, args)` ——
    #   那个形状函数定义那一行也有，探针会永远绿（反向验证时踩到过）。
    check('[D1] spawnPty assigns the containerWrap result',
          re.search(r'=\s*containerWrap\(\s*file\s*,\s*args\s*\)', patch) is not None)
    # program 必须取自 wrapped，否则要么不包装、要么包装了仍 exec 原路径
    check('[D2] exec program comes from the wrap result',
          re.search(r'const\s+program\s*=\s*wrapped\s*===\s*undefined\s*\?\s*file\s*:\s*wrapped\.program', patch) is not None)
    check('[D3] program args come from the wrap result',
          re.search(r'const\s+programArgs\s*=\s*wrapped\s*===\s*undefined\s*\?', patch) is not None)
    # argv[4] 是 dsh_pty_exec 的 execv 起点；program 必须落在第 5 位
    check('[D4] wrapped program occupies argv[4]',
          re.search(r'\[\s*String\(rows\)\s*,\s*String\(cols\)\s*,\s*cwd\s*,\s*program\s*\]', patch) is not None)

    # ---- [E] 缺参数时原样直通（原生模式不能回归） ----
    check('[E1] missing runner/rootfs/interp returns undefined',
          re.search(r'if\s*\(\s*!runner\s*\|\|\s*!rootfs\s*\|\|\s*!interp\s*\)\s*return\s+undefined', patch) is not None)
    # runner 不可执行时也要回退，否则终端直接起不来
    check('[E2] non-executable runner falls back instead of wrapping',
          'accessSync(runner, fs.constants.X_OK)' in patch)

    # ---- [F] 构建流水线会应用这份补丁 ----
    collect_src = read(COLLECT) or ''
    check('[F1] collect-termux-runtime.sh applies the node-pty patch',
          'patch-node-pty-android.py' in collect_src)

    # ---- [G] 注释里不能残留「terminal 在宿主跑」这种旧断言 ----
    check('[G1] no stale claim that terminal runs on host shell',
          'execv(program' not in launcher)

    return report()


def report():
    ok = sum(1 for _, o, _ in checks if o)
    bad = [(n, d) for n, o, d in checks if not o]
    for n, o, d in checks:
        print(('  PASS  ' if o else '  FAIL  ') + n + (('  :: ' + d) if (d and not o) else ''))
    print('\n%d checks, %d failed' % (len(checks), len(bad)))
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()

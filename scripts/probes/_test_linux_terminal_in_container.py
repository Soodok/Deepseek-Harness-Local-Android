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
CMAKE = os.path.join(ROOT, 'app/src/main/cpp/CMakeLists.txt')

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

    # ---- [H] chroot 沙箱：助手必须能跑、且不能重复进容器（v1.8.3） ----
    #
    # 这两条对应真实故障：「chroot 模式下只有 pty 终端不能用，其它功能都正常」。
    # 判据取自 ELF 事实与 env 过滤，而不是注释里的说法。
    cmake = read(CMAKE) or ''
    check('[H1] CMakeLists exists', cmake != '', CMAKE)
    # 动态 bionic 可执行文件的 PT_INTERP 是 /system/bin/linker64，而 chroot 只挂
    # /proc /dev /sys /tmp /dev/pts 与 /data 系 —— 没有 /system，execv 必 ENOENT。
    # 静态链接没有 PT_INTERP，判据就是这一行链接选项。
    check('[H2] dsh_pty_exec is statically linked (no PT_INTERP needed)',
          re.search(r'target_link_options\(\s*dsh_pty_exec\s+PRIVATE\s+"-static"', cmake) is not None,
          'chroot 内没有 /system/bin/linker64，动态助手 execv 会 ENOENT')
    # chroot 模式下引擎本来就在 rootfs 里，再注入 DSH_TERMINAL_* 会被 shim 包成
    # 「容器里再起一个容器」，而 rootfs/interp/bind 全是宿主路径。
    #
    # ⚠️ 不能用 `\{...\n\s*\}\n` 这种按括号收尾的捕获：第一个 `}` 很可能是内层
    #    `if (chrootArgv != null) {` 的收尾，块会被截断，后面的断言全部恒假。
    #    改成「从 Sandbox.CHROOT 起截固定窗口」。
    chroot_block = None
    _ci = launcher.find('Sandbox.CHROOT)')
    if _ci >= 0:
        chroot_block = launcher[_ci:_ci + 2600]
    check('[H3] chroot branch located', chroot_block is not None)
    if chroot_block:
        check('[H4] chroot branch drops DSH_TERMINAL_* before building env',
              re.search(r'filterNot\s*\{\s*it\.startsWith\("DSH_TERMINAL_"\)\s*\}',
                        chroot_block) is not None,
              '否则 shim 会在 chroot 内再包一层容器，rootfs/interp 都是宿主路径')
        check('[H5] chroot LaunchSpec uses the filtered env',
              re.search(r'env\s*=\s*envForChroot\b', chroot_block) is not None,
              '只过滤了 envMap、却把原始 envVars 交出去，等于没过滤')

    # ---- [I] guest shell 兜底：rootfs 没有 bash 时退到 /bin/sh ----
    #
    # 引擎终端默认 DEFAULT_BASH_SHELL="/bin/bash"，而极简 rootfs 里只有
    # busybox ash（bash 是 bootstrap 阶段 best-effort 装的）。不兜底 → exec ENOENT
    # → 一开终端就死，而原生终端用 /bin/sh 一切正常。
    check('[I1] shim checks for /bin/bash presence in the rootfs',
          re.search(r'existsSync\(\s*path\.join\(\s*rootfs\s*,\s*"bin/bash"\s*\)\s*\)', patch) is not None,
          '判据必须是磁盘事实（rootfs 里有没有），不能靠配置猜')
    check('[I2] missing bash falls back to /bin/sh',
          'guestFile = "/bin/sh"' in patch)
    check('[I3] bash-only flags are dropped on fallback',
          all(f in patch for f in ('--noprofile', '--norc')))

    # ── J 段：兜底逻辑的行为验证（不是读源码文本，而是真跑一遍）────────────
    # 源码文本断言证明不了「有没有 bash」真的会走两个不同分支，这里把
    # containerWrap 抽出来，用 posix path + 桩 fs 在 node 里跑四种环境。
    fn_start = patch.index('function containerWrap(file, args) {')
    fn_end = patch.index(chr(10) + 'function ', fn_start + 10)
    fn = patch[fn_start:fn_end]
    harness = '''const path = require('path').posix;
const fs = {
  constants: { X_OK: 1 },
  accessSync(p) { if (p === '/badrunner') throw new Error('not executable'); },
  existsSync(p) { return haveBash.has(p); },
};
let haveBash = new Set();
%s
const BASE = { DSH_TERMINAL_RUNNER: '/runner', DSH_TERMINAL_ROOTFS: '/rootfs',
               DSH_TERMINAL_INTERP: '/lib/ld-musl.so' };
function run(env, withBash) {
  for (const k of ['DSH_TERMINAL_RUNNER', 'DSH_TERMINAL_ROOTFS', 'DSH_TERMINAL_INTERP',
                   'DSH_TERMINAL_BINDS']) delete process.env[k];
  Object.assign(process.env, env);
  haveBash = new Set();
  if (withBash) haveBash.add(env.DSH_TERMINAL_ROOTFS + '/bin/bash');
  const r = containerWrap('/bin/bash', ['--noprofile', '--norc', '-i']);
  return r ? r.argv : null;
}
console.log(JSON.stringify({
  noBash: run(BASE, false),
  hasBash: run(BASE, true),
  badRunner: run(Object.assign({}, BASE, { DSH_TERMINAL_RUNNER: '/badrunner' }), false),
  noInterp: run({ DSH_TERMINAL_RUNNER: '/runner', DSH_TERMINAL_ROOTFS: '/rootfs' }, false),
}));
''' % fn
    import tempfile, subprocess, json as _json, shutil
    node = shutil.which('node') or shutil.which('node.exe')
    if node is None:
        check('[J0] node available for behaviour test', False, '找不到 node，J 段跳过')
    else:
        with tempfile.TemporaryDirectory() as td:
            hp = os.path.join(td, 'cw.js')
            open(hp, 'w', encoding='utf-8').write(harness)
            r = subprocess.run([node, hp], capture_output=True, text=True,
                               encoding='utf-8', errors='replace', timeout=120)
        out = (r.stdout or '').strip().splitlines()
        try:
            got = _json.loads(out[-1]) if out else {}
        except Exception as exc:  # noqa: BLE001
            got = {}
            check('[J0] harness produced JSON', False, '%s / stderr=%s' % (exc, (r.stderr or '')[:200]))
        no_bash = got.get('noBash') or []
        has_bash = got.get('hasBash') or []
        check('[J1] rootfs 无 bash → guest 退 /bin/sh',
              len(no_bash) >= 3 and no_bash[2] == '/bin/sh',
              '实际 argv=%s' % no_bash)
        check('[J2] rootfs 无 bash → 丢掉 --noprofile/--norc',
              '--noprofile' not in no_bash and '--norc' not in no_bash and '-i' in no_bash,
              '实际 argv=%s' % no_bash)
        check('[J3] rootfs 有 bash → 原样保留 /bin/bash 与参数（无回归）',
              len(has_bash) >= 3 and has_bash[2] == '/bin/bash'
              and '--noprofile' in has_bash and '--norc' in has_bash,
              '实际 argv=%s' % has_bash)
        check('[J4] runner 不可执行 → 返回 undefined（退回旧行为）',
              got.get('badRunner') is None, '实际=%s' % got.get('badRunner'))
        check('[J5] 缺 INTERP → 返回 undefined（非容器模式）',
              got.get('noInterp') is None, '实际=%s' % got.get('noInterp'))

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

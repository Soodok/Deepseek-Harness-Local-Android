#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Linux 模式下「打开配置文件」必须先把容器路径翻译成宿主路径。

## 这道探针守的是什么

Linux 模式下引擎跑在容器里，`DSH_HOME=/dsh`。上游「打开配置文件」的路径来自
`settings.prepareDocument()` → `profileContext.patchPath`，而

    resolveProfileDir(name, home) = join(home, "profiles", name)
    patchPath = join(resolveProfileDir(...), PROFILE_PATCH_FILENAME)
    home = resolveDshHome() = $DSH_HOME

所以容器里点「打开配置文件」，App 收到的是
**`/dsh/profiles/web/cordis.patch.yml`** —— guest 视角。

宿主上没有 `/dsh`，于是：
- `File(raw).isFile` = false → 404 `not a file`
- 即使文件存在（用户真建了 `/dsh`），`isOpenablePath` 也会判越界 → 403

用户看到的就是「配置文件打不开」。修法是进校验前按 **bind 表**做一次
guest→host 翻译（`LinuxEngineLauncher.hostPathOf`），且**只在 Linux 模式生效**
（原生模式下 `/dsh` 不该被当 guest 路径）。

## 判据

全静态。翻译算法的等价实现在本文件里用 Python 复刻一遍做逐例核对
（最长前缀优先、恒等映射、相对路径拒绝、无匹配返回 None）——
这样即使 Kotlin 侧格式漂移，语义仍被钉住。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LAUNCHER = os.path.join(ROOT, 'app/src/main/java/app/dsh/mobile/engine/LinuxEngineLauncher.kt')
BRIDGE = os.path.join(ROOT, 'app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt')
PLUGIN = os.path.join(ROOT, 'app/src/main/assets/android-plugins/open-file.mjs')

checks = []


def check(name, ok, detail=''):
    checks.append((name, bool(ok), detail))


def read(p):
    if not os.path.isfile(p):
        return None
    with open(p, encoding='utf-8') as f:
        return f.read()


def host_path_of(guest, binds):
    """LinuxEngineLauncher.hostPathOf 的语义复刻。"""
    if not guest.startswith('/'):
        return None
    g = re.sub(r'/{2,}', '/', guest)
    if len(g) > 1:
        g = g.rstrip('/')
    best = None
    for bg, bh in binds:
        bg2 = bg.rstrip('/')
        if g == bg2 or g.startswith(bg2 + '/'):
            if best is None or len(bg2) > len(best[0].rstrip('/')):
                best = (bg, bh)
    if best is None:
        return None
    tail = g[len(best[0].rstrip('/')):].lstrip('/')
    return best[1] if not tail else best[1] + '/' + tail


def main():
    launcher = read(LAUNCHER)
    bridge = read(BRIDGE)
    plugin = read(PLUGIN)

    check('[A1] LinuxEngineLauncher.kt readable', launcher is not None)
    check('[A2] AgentBridge.kt readable', bridge is not None)
    check('[A3] open-file.mjs readable', plugin is not None)
    if not (launcher and bridge):
        return report()

    # ---- [B] 翻译能力存在于 launcher，且依据 bind 表 ----
    check('[B1] hostPathOf() defined', 'fun hostPathOf(' in launcher)
    body_start = launcher.find('fun hostPathOf(')
    body = launcher[body_start:body_start + 2200] if body_start >= 0 else ''
    check('[B2] hostPathOf consults buildBinds()', 'buildBinds()' in body)
    check('[B3] rejects non-absolute paths', 'if (!guestPath.startsWith("/")) return null' in body)
    check('[B4] normalises repeated slashes',
          'replace(Regex("/{2,}"), "/")' in body)
    check('[B5] longest-prefix wins (maxByOrNull on length)',
          'maxByOrNull' in body and 'length' in body)
    # 前缀必须落在路径边界上，否则 /dshx 会被 /dsh 命中
    check('[B6] prefix must land on a path boundary',
          'guest.startsWith(b.guest.trimEnd(\'/\') + "/")' in body)

    # ---- [C] 语义逐例核对 ----
    DSH = '/data/user/0/app.dsh.mobile/files/dsh-home'
    ENG = '/data/user/0/app.dsh.mobile/files/engine'
    WS = '/storage/emulated/0/Documents/dshdata/workspaces'
    PUB = '/storage/emulated/0/Download/DSH MOBILE'
    # 真实 bind 表形状：三个别名 + 宿主同名挂载三类
    binds = [
        ('/eng', ENG), ('/ws', WS), ('/dsh', DSH),
        (WS, WS), (DSH, DSH), (PUB, PUB),
        ('/storage/emulated/0', '/storage/emulated/0'),
        ('/data/user', '/data/user'), ('/data/data', '/data/data'),
    ]
    cases = [
        # 用户报障的那一条：上游「打开配置文件」
        ('/dsh/profiles/web/cordis.patch.yml', DSH + '/profiles/web/cordis.patch.yml'),
        ('/dsh/AGENTS.md', DSH + '/AGENTS.md'),
        ('/dsh', DSH),
        ('/dsh/', DSH),
        ('/dsh//a//b', DSH + '/a/b'),
        ('/eng/lib/bin.js', ENG + '/lib/bin.js'),
        ('/ws/proj/a.txt', WS + '/proj/a.txt'),
        # 宿主同名挂载是恒等映射
        ('/data/user/0/app.dsh.mobile/files/engine/x',
         '/data/user/0/app.dsh.mobile/files/engine/x'),
        ('/storage/emulated/0/Documents/dshdata/workspaces/k', WS + '/k'),
        # 无匹配 / 非绝对 / 前缀不落边界
        ('/nope/x', None),
        ('relative/x', None),
        ('/dshx/a', None),
    ]
    bad = []
    for g, want in cases:
        got = host_path_of(g, binds)
        if got != want:
            bad.append((g, got, want))
    check('[C1] translation table matches expectation (%d cases)' % len(cases), not bad, str(bad[:3]))

    # ---- [D] 桥接端点真的用了它，且只在 Linux 模式 ----
    i = bridge.find('private fun openFile(')
    of = bridge[i:i + 2600] if i >= 0 else ''
    check('[D1] openFile calls hostPathOf', 'hostPathOf(' in of)
    # ★必须锁定**赋值那一行**。只查 `EngineModeSelector.Mode.LINUX` 是不够的：
    #   下面 else-if 里也出现这串，把门改成 `if (true)` 探针仍绿（反向验证踩到过）。
    check('[D2] translation gated on Linux mode (the assignment itself)',
          re.search(
              r'val\s+translated\s*=\s*if\s*\(\s*EngineModeSelector\.lastMode\s*==\s*'
              r'EngineModeSelector\.Mode\.LINUX\s*\)\s*\{', of) is not None)
    # 翻译结果必须真的被用作 File 目标，否则等于没翻译。
    # 语义：**原路径在宿主上不是文件时**才采用翻译结果（以前能打开的仍能打开）。
    check('[D3] translated path becomes the File target when raw does not exist',
          re.search(r'target\s*=\s*if\s*\(\s*rawFile\.isFile\s*\|\|\s*translated\s*==\s*null\s*\)'
                    r'\s*rawFile\s*else\s*File\(translated\)', of) is not None)
    # 翻译后的路径仍要过原有的根检查（不能因为翻译就放宽权限）
    check('[D4] translated path still goes through isOpenablePath',
          'isOpenablePath(ctx, real)' in of)
    # 未命中时要留痕，否则排查会误判成「文件不存在」
    check('[D5] unmapped guest path is logged',
          'cannot map guest path to host' in of)

    # ---- [E] 上游路径来源确实带 DSH_HOME 前缀（文档一致性）----
    check('[E1] engine env keeps DSH_HOME=/dsh',
          'DSH_HOME=/dsh' in launcher)
    check('[E2] shim / plugin still routes to POST /open-file',
          '/open-file' in (plugin or ''))

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

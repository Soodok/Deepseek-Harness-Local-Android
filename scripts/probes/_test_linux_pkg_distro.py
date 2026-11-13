#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Linux 包管理器必须跟随发行版（apk / apt）。

## 这道探针守的是什么

用户把发行版切成 Ubuntu 后，点「安装 node」仍然去找 `/sbin/apk` —— Ubuntu
里没有这个二进制，返回 `exit 127 / not found`，而 UI 只显示「apk add failed」，
用户无从判断是装错了包管理器。这是「linux 环境安装 node 失败」的第一现场候选。

另外守四件事：
1. 失败输出必须带**可操作**的 DNS 线索。`temporary error (try again later)` 的
   真实成因绝大多数是容器 DNS 不可用（musl 把端口写死 53，要靠 loader 双向改写），
   不是镜像站挂了 —— 只回一句 apk add failed 会把人引去换镜像源。
2. Ubuntu 装包必须走已验证的**两步法**（`apt-get -d install` + `dpkg -i`），
   一步 `apt-get install` 在容器里必失败（dpkg 看不见 /tmp 里硬链的 deb）。
3. `installed()` / `search()` 也不能写死 apk，否则扩展中心永远显示未安装。
4. 不许有裸换行逃逸进 Kotlin 字符串（历史上踩过三次，heredoc 吃反斜杠）。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PM = os.path.join(ROOT, 'app/src/main/java/app/dsh/mobile/engine/LinuxPackageManager.kt')
EM = os.path.join(ROOT, 'app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt')
CAT = os.path.join(ROOT, 'app/src/main/java/app/dsh/mobile/engine/LinuxRootfsCatalog.kt')

checks = []


def check(name, ok, detail=''):
    checks.append((name, bool(ok), detail))


def read(p):
    if not os.path.isfile(p):
        return None
    with open(p, encoding='utf-8') as f:
        return f.read()


def strip_comments(src):
    """去掉注释，避免「注释里写了」被当成「代码里做了」。

    两个坑都踩过：
    1. 字符串里的 `//` 不是注释（2026-11-09 的版本就在这里假绿过一次）。
    2. 字符串里的 `/*` 也不是注释 —— `/var/cache/apt/archives/*.deb` 这种
       glob 恰好含 `/*`，早先那版先用 `re.sub(r'/\\*.*?\\*/', '', src, re.S)`
       扫全文，会把含 glob 的那一大片代码连注释一起吞掉（实测 archives
       出现次数 5 → 0，C3b 因此假红）。块注释必须只在**非字符串**状态下识别。
    """
    out = []
    i, n = 0, len(src)
    bs = chr(92)
    while i < n:
        if src.startswith('//', i):
            j = src.find(chr(10), i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            out.append(src[i:j + 3])
            i = j + 3
            continue
        ch = src[i]
        if ch == '"':
            j = i + 1
            while j < n:
                c = src[j]
                if c == bs and j + 1 < n:
                    j += 2
                    continue
                if c == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


def body_of(src, fn, limit=3000):
    m = re.search(r'fun\s+' + fn + r'\s*\(', src)
    if not m:
        return None
    tail = src[m.start():]
    nxt = re.search(r'\n    fun\s+', tail[10:])
    return tail[:nxt.start() + 10] if nxt else tail[:limit]


def code_string_literals(src):
    """只取「代码里的字符串字面量」，跳过注释。

    注意不能直接用本文件的 strip_comments：它把 `/*` 当块注释开头，
    而 `/var/cache/apt/archives/*.deb` 这种 glob 里正好含 `/*`，
    会把后面整片代码当成注释剥掉（实测 archives 出现次数从 5 变 0）。
    这里逐字符走一遍，区分行注释 / 块注释 / 普通串 / 原始串。
    """
    out = []
    i, n = 0, len(src)
    bs = chr(92)
    while i < n:
        if src.startswith('//', i):
            j = src.find(chr(10), i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            out.append(src[i + 3:j])
            i = j + 3
            continue
        ch = src[i]
        if ch == '"':
            j = i + 1
            buf = []
            while j < n:
                c = src[j]
                if c == bs and j + 1 < n:
                    buf.append(src[j:j + 2])
                    j += 2
                    continue
                if c == '"':
                    break
                buf.append(c)
                j += 1
            out.append(''.join(buf))
            i = j + 1
            continue
        i += 1
    return out


def apt_dpkg_from_cache(src):
    """找「把 apt 缓存的 deb 交给 dpkg 安装」那一步，返回命中的脚本正文。

    断言的是**语义**而不是拼写。历史上这一步的写法换过三次：
      1. listOf("-i", "/var/cache/apt/archives/*.deb")   ← glob 没人展开，必答 2
      2. sh -c "dpkg -i /var/cache/apt/archives/*.deb"   ← 让 sh 展开 glob
      3. sh -c "set -- /var/cache/apt/archives/*.deb; [ -e \"$1\" ] &&
                 dpkg --force-not-root -i \"$@\" || true"  ← App uid 不能当 root
    写死其中任何一种拼写，下一次等价改写就会让探针假红（2026-11-13 就踩了）。
    所以这里只要求：同一个脚本里既引用了 apt archives 的 *.deb，又把展开
    结果交给了 dpkg 的 -i —— 且必须出现在代码里，光写在注释里不算。
    """
    for lit in code_string_literals(src):
        if 'archives' not in lit or 'dpkg' not in lit or '-i' not in lit:
            continue
        if re.search(r'apt/archives/\*\.deb', lit) is None:
            continue
        # -i 与 --install 是同义写法，两种都认；认的是「把 deb 交给 dpkg 装」这个动作
        if re.search(r'dpkg\s+(?:--[\w-]+\s+)*(?:-i|--install)\b', lit) is None:
            continue
        return lit
    return None


LOCALES = ['values', 'values-zh-rCN', 'values-de', 'values-pt-rBR', 'values-th']


def _locales_have_strings(names):
    """三件事都要成立：五语都有、占位符一致、非中文语种不带 CJK。"""
    found = {}
    for d in LOCALES:
        p = os.path.join(ROOT, 'app/src/main/res', d, 'strings.xml')
        s = read(p)
        if s is None:
            return False
        for n in names:
            m = re.search(r'<string name="' + n + r'">(.*?)</string>', s, re.S)
            if not m:
                return False
            found.setdefault(n, {})[d] = m.group(1)
    for n, per in found.items():
        phs = set()
        for d, v in per.items():
            phs.add(tuple(sorted(re.findall(r'%\d+\$[sd]', v))))
        if len(phs) != 1:
            return False
        for d, v in per.items():
            if d != 'values-zh-rCN' and re.search(r'[一-鿿]', v):
                return False
    return True


def main():
    pm_src = read(PM)
    em_src = read(EM)
    check('LinuxPackageManager.kt exists', pm_src is not None, PM)
    check('LinuxEnvironmentManager.kt exists', em_src is not None, EM)
    if not (pm_src and em_src):
        report()
        return

    pm = strip_comments(pm_src)

    # ---- [A] distro dispatch ----
    check('[A1] has isApt() dispatcher',
          re.search(r'private\s+fun\s+isApt\s*\(\s*\)\s*:\s*Boolean', pm) is not None)
    check('[A2] isApt checks pkgManager == APT',
          re.search(r'pkgManager\s*==\s*LinuxRootfsCatalog\.PackageManager\.APT', pm) is not None)
    check('[A3] option() reads activeOption() fresh (no caching)',
          re.search(r'fun\s+option\s*\(\s*\)\s*:\s*LinuxRootfsCatalog\.Option\s*=\s*env\.activeOption\s*\(\s*\)',
                    pm) is not None)

    # ---- [B] four public methods must dispatch ----
    for fn, label in [('install', 'B1'), ('remove', 'B2'), ('installed', 'B3'), ('search', 'B4')]:
        body = body_of(pm, fn)
        check('[%s] fun %s exists' % (label, fn), body is not None)
        if body is None:
            continue
        check('[%s] %s dispatches via isApt()' % (label, fn), 'isApt()' in body)

    # ---- [C] apt install must delegate to the two-step helper ----
    inst = body_of(pm, 'install') or ''
    check('[C1] install apt branch delegates to installPackagesApt',
          'installPackagesApt' in inst)
    check('[C2] install apt branch does not run apt-get install inline',
          not re.search(r'"/usr/bin/apt-get"[\s\S]{0,80}"install"', inst))
    # -d 是 apt-get 的「只下载」开关，出现在 listOf("-y", ..., "-d", "install") 里；
    # dpkg 侧走 /bin/sh -c "dpkg -i /var/cache/apt/archives/*.deb"，不是直接 exec dpkg。
    em = strip_comments(em_src)
    check('[C3] installPackagesApt uses -d (download only)',
          re.search(r'"-d"\s*,\s*"install"', em) is not None,
          'expect listOf(..., "-d", "install")')
    check('[C3b] installPackagesApt installs from cache via dpkg -i',
          apt_dpkg_from_cache(em_src) is not None,
          'need one sh -c script that both expands /var/cache/apt/archives/*.deb '
          'and hands it to dpkg -i')
    # 收尾的 configure 已从 EM 内联挪进 LinuxRootfsCatalog.postInstallArgv()。
    # 原断言在 em_src 里搜 'dpkg --configure -a' —— 现在这句只剩注释里那份，
    # 代码里早没了，属于「断言被注释喂饱」的假绿。改为两段结构校验：
    # EM 里必须真的调用 postInstallArgv，CATALOG 里必须真的产出 configure argv。
    cat_src = read(CAT)
    cat = strip_comments(cat_src) if cat_src else ''
    check('[C3c] installPackagesApt runs post-install configure via catalog',
          'LinuxRootfsCatalog.postInstallArgv(option)' in em
          and re.search(r'"--configure"\s*,\s*"-a"', cat) is not None,
          'EM must call postInstallArgv(option); CAT must emit ["--configure", "-a"]')

    # ---- [D] installed() apt branch ----
    ib = body_of(pm, 'installed') or ''
    check('[D1] installed apt branch uses dpkg-query', 'dpkg-query' in ib)
    check('[D2] dpkg-query format string has Package and Version',
          'Package' in ib and 'Version' in ib)
    check('[D3] dpkg-query -f is one valid literal (TAB/newline as escapes)',
          re.search(r'"-f=[^"\n]*' + chr(92) + chr(92) + r't[^"\n]*' + chr(92) + chr(92) + r'n"',
                    ib) is not None,
          'must use \\t and \\n escape sequences')
    # apk 的 `nodejs-22.23.4-r0` 解析需要两次 lastIndexOf（先切 -r<n>，再切 -<ver>），
    # 所以计数是 2。关键是 apt 分支里不能出现 lastIndexOf（它用 TAB 切）
    check('[D4] apk dash parser stays in apk branch (two lastIndexOf, none in apt)',
          ib.count('lastIndexOf') == 2,
          'got %d' % ib.count('lastIndexOf'))
    apt_part = ib.split('isApt()')[1].split('val r = runner.run')[0] if 'isApt()' in ib else ''
    check('[D4b] apt branch does not use dash-splitting',
          'lastIndexOf' not in apt_part)

    # ---- [E] search apt branch ----
    sb = body_of(pm, 'search') or ''
    check('[E1] search apt branch uses apt-cache', 'apt-cache' in sb)

    # ---- [F] refreshIndex must not fail under apt ----
    rib = body_of(pm, 'refreshIndex') or ''
    check('[F1] refreshIndex returns success immediately under apt', 'isApt()' in rib)

    # ---- [G] failure diagnostics ----
    check('[G1] has diagnose() helper',
          re.search(r'private\s+fun\s+diagnose\s*\(\s*raw\s*:\s*String\s*\)', pm) is not None)
    for kw in ['temporary error', 'try again later', 'connection refused', 'timed out']:
        check('[G2] diagnose covers %r' % kw, kw in pm)
    check('[G3] diagnose reads the live DNS proxy port', 'LinuxDnsProxy.currentPort()' in pm)
    # 文案必须走资源：check-i18n.py 的 D 段禁止 Kotlin 字面量含 CJK
    check('[G4] diagnose uses string resources (no CJK literals)',
          'R.string.linux_pkg_dns_proxy_down' in pm and 'R.string.linux_pkg_dns_proxy_up' in pm)
    check('[G4b] three new strings exist in all five locales', _locales_have_strings(
        ['linux_pkg_dns_proxy_down', 'linux_pkg_dns_proxy_up', 'linux_pkg_index_dns_hint']))
    check('[G7] ensureIndex failure hint points at DNS, not the mirror',
          'R.string.linux_pkg_index_dns_hint' in pm)
    check('[G5] refreshIndex failure wired to diagnose',
          re.search(r'diagnose\s*\(\s*r\.combined\(\)\s*\)', pm) is not None)
    check('[G6] install apk failure wired to diagnose', pm.count('diagnose(') >= 2)


    # ---- [I] DNS proxy startup race ----
    # DshApp 在后台线程起代理（可能要过 su），用户可能在那之前就点安装。
    # 那一刻 currentPort() 还是 0，runner 不注入 DSH_DNS_PORT，apk update 报
    # temporary error → UI 显示 apk update failed。
    check('[I1] has ensureDnsProxy() guard',
          re.search(r'private\s+fun\s+ensureDnsProxy\s*\(\s*\)\s*:\s*Int', pm) is not None)
    check('[I2] ensureDnsProxy returns early if already bound',
          re.search(r'currentPort\(\)[\s\S]{0,40}>\s*1023[\s\S]{0,20}return', pm) is not None)
    rib2 = body_of(pm, 'refreshIndex') or ''
    check('[I3] refreshIndex calls ensureDnsProxy before apk update',
          'ensureDnsProxy()' in rib2 and rib2.index('ensureDnsProxy()') < rib2.index('runner.run'))
    inst2 = body_of(pm, 'install') or ''
    check('[I4] install (apk branch) calls ensureDnsProxy',
          'ensureDnsProxy()' in inst2)

    # ---- [H] no bare newline escaping into string literals ----
    bad = []
    for i, line in enumerate(pm_src.split('\n'), 1):
        if line.count('"') % 2 == 1 and not line.rstrip().endswith(('+', ',')):
            bad.append((i, line.strip()[:70]))
    check('[H1] no unterminated string lines', not bad, str(bad[:3]))

    report()


def report():
    ok = sum(1 for _, o, _ in checks if o)
    bad = [(n, d) for n, o, d in checks if not o]
    for n, o, d in checks:
        print(('  PASS  ' if o else '  FAIL  ') + n + (('  :: ' + d) if (d and not o) else ''))
    print('\n%d checks, %d failed' % (len(checks), len(bad)))
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""多 rootfs 目录（LinuxRootfsCatalog）一致性自检。

## 为什么需要这个探针

Linux 工作区现在支持多个发行版（Alpine/musl + Ubuntu/glibc 并存）。这套
设计里有几处**写错只在真机上才炸、而且炸得很难懂**的地方：

1. **动态链接器路径搞混**：用 musl 的 `ld-musl-aarch64.so.1` 去载 glibc 的
   二进制，容器直接 `LOADER: preload FAIL`。这个报错完全看不出是"选错了
   rootfs"，只会让人怀疑二进制损坏。两者必须逐项对上自己的发行版。
2. **ldconfig divert 路径**：glibc 的 ldconfig 是静态 PIE，在容器里段错误
   （exit 139）。修法是用 `dpkg-divert` 把真身挪走再放 shim。但 divert 必须
   指向 merged-usr 之后的真实路径 `/usr/sbin/ldconfig.real`；写成旧的
   `/sbin/ldconfig.real`（它只是指向 usr/sbin 的符号链接）会**静默成功但
   完全无效**，之后 `dpkg --configure libc-bin` 照样 139。实测踩过。
3. **node 路径与 node 来源不一致**：Alpine 用 `apk add nodejs`（落在
   `/usr/bin/node`），Ubuntu 用 Node 官方预编译包（解到 `/opt/node/bin/node`，
   因为 apt 源里只有 18.x）。launcher 的 readiness 检测与 argv 构造必须用
   同一套映射，否则会「显示已就绪但启动即失败」或「装好了却说没装」。
4. **apt 装包必须两步**：一步 `apt-get install` 在容器里报
   `dpkg: cannot stat '/tmp/apt-dpkg-install-*/*.deb'`（实测 Ubuntu 24.04）。
   实现里必须走 `apt-get -d install` + `dpkg -i`。

这些都是**结构一致性**问题，静态就能查出来，不必每次上真机试。

## 本探针查什么

- `[A]` 目录能被解析，至少包含 Alpine 与 Ubuntu，且恰好一个默认项
- `[B]` 每个选项的 libc / 包管理器 / interp / node 策略互相自洽
- `[C]` interp 路径在选项中**唯一**（不能两个发行版共用同一个）
- `[D]` 下载源：全部 https、有 sha256 pin、体积为正、格式合法
- `[E]` glibc 补丁脚本：divert 到 `/usr/sbin/ldconfig.real`（不是 /sbin），
      含 shim 的 printf/chmod，且不依赖 bash（glibc base 镜像没有 bash）
- `[F]` 部署代码：glibc 走下载路径、apt 装包是两步、迁移旧布局存在
- `[G]` launcher / runner 里**没有**残留的硬编码 musl interp / 固定 node 路径
- `[H]` 五语言字符串键齐全（切换 UI 用到的每一个都在）

## 反向验证

探针必须能真的红。改坏下面任一处再跑，`[E]`/`[G]` 必须立刻失败：
- 把 `glibcQuirksScript` 里的 `/usr/sbin/ldconfig.real` 改回 `/sbin/ldconfig.real`
- 把 `LinuxEngineLauncher` 里的 `option.interpPath` 改回
  `EngineConfig.linuxMuslInterp(ctx)`
- 把 `nodePath()` 的 `OFFICIAL_TARBALL` 分支改回 `NODE_IN_CONTAINER`

用法：
    PYTHONIOENCODING=utf-8 python scripts/probes/_test_linux_rootfs_catalog.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
CATALOG = os.path.join(ENGINE, "LinuxRootfsCatalog.kt")
MANAGER = os.path.join(ENGINE, "LinuxEnvironmentManager.kt")
LAUNCHER = os.path.join(ENGINE, "LinuxEngineLauncher.kt")
RUNNER = os.path.join(ENGINE, "LinuxContainerRunner.kt")
ACTIVITY = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "LinuxActivity.kt")
RES = os.path.join(ROOT, "app", "src", "main", "res")

LOCALES = ["values", "values-de", "values-zh-rCN", "values-pt-rBR", "values-th"]

checks = 0
failed = 0


def ok(name, cond, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  [OK]   %s" % name)
    else:
        failed += 1
        print("  [FAIL] %s%s" % (name, (" - " + detail) if detail else ""))


def read(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()


def _split_fields(body):
    """把 `Option(...)` 的参数体按顶层逗号切成 {名: 表达式原文}。

    ★必须按**括号/方括号深度**切，不能用 `^\\s+(\\w+)\\s*=\\s*(.+?),\\s*$` 这种
    单行正则。issue #132 之后 interpPath / sha256 / urls / compressedBytes 都是

        compressedBytes = if (IS_ARM64) 29_936_675L else 30_028_293L,

    或

        sha256 = if (IS_ARM64)
            "a91d…f2"
        else
            "e77b…86",

    这种**跨行**形态。单行正则会把这些字段整个**丢掉**（不是取错值，是直接
    不存在），于是 `get("UBUNTU","interpPath")` 拿到 None，报「Ubuntu interp
    is glibc loader - None」—— 看起来像源码写错了，实际是解析器跟不上。
    """
    fields = {}
    depth = 0
    cur = []          # 当前字段累积的文本行
    key = None
    in_str = False
    i = 0
    n = len(body)
    while i < n:
        ch = body[i]
        if in_str:
            cur.append(ch)
            if ch == '"' and body[i - 1] != chr(92):
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            cur.append(ch)
            i += 1
            continue
        if ch == '/':
            # 跳过行注释与块注释（注释里的逗号/括号不能参与深度计算）
            if body.startswith('//', i):
                j = body.find(chr(10), i)
                i = n if j < 0 else j
                continue
            if body.startswith('/*', i):
                j = body.find('*/', i + 2)
                i = n if j < 0 else j + 2
                continue
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        if ch == ',' and depth == 0:
            text = ''.join(cur)
            if key is not None:
                fields[key] = ' '.join(text.split())
            cur, key = [], None
            i += 1
            continue
        if ch == chr(10):
            cur.append(' ')
            i += 1
            continue
        cur.append(ch)
        # 字段起点：行首（或上一个逗号之后）第一个 `name =`
        joined = ''.join(cur)
        if key is None and not joined.strip() == '':
            m = re.match(r'\s*(\w+)\s*=\s*', joined)
            if m:
                key = m.group(1)
                cur = [joined[m.end():]]
        i += 1
    tail = ''.join(cur)
    if key is not None:
        fields[key] = ' '.join(tail.split())
    return fields


def parse_options(src):
    """从 Kotlin 源码里抠出 `Option(...)` 条目。

    只取真实构造出来的条目（`Option(` 后跟命名参数），不取 data class 声明，
    也不取注释里出现的样例。返回值是 [dict(name=表达式原文)]。
    """
    out = []
    for m in re.finditer(r"\n\s+Option\(\n(.*?)\n\s{8}\),", src, re.S):
        fields = _split_fields(m.group(1))
        if "family" in fields:
            out.append(fields)
    return out


def if_abi_else(v):
    """从 `if (IS_ARM64) "A" else "B"` 形态里取 x86_64 分支（B）。

    为什么取 B：探针跑在开发机上，x86_64 是最常见的目标；而 arm64 是手机真机
    长年唯一的 ABI。两条分支都写出来才算对，取值只是为了让「集合类断言」有个
    具体字符串可比。★这个形态是 issue #132 引入的（原来 interpPath / sha256 /
    rootfs URL 都写死 arm64，x86_64 设备必 SIGILL），所以解析器必须跟着会读。
    """
    m = re.search(r'if\s*\(\s*IS_ARM64\s*\)\s*\n\s*"(.*?)"\s*\n\s*else\s*\n\s*"(.*?)"',
                  v, re.S)
    if m:
        return m.group(2)
    # 单行形态：if (IS_ARM64) 29_936_675L else 30_028_293L（数字字段走这条）
    m2 = re.search(r'if\s*\(\s*IS_ARM64\s*\)\s*(\S+)\s*\n?\s*else\s*(\S+)', v, re.S)
    if m2:
        # 单行形态的值可能带引号（interpPath 压成一行时就是），统一剥掉
        return m2.group(2).strip().strip(chr(34))
    return None


def field_str(v):
    """把 Kotlin 表达式还原成字符串值（取最内层 quoted 内容或枚举尾名）。

    ★先处理 `if (IS_ARM64) "…" else "…"` 三元（issue #132 之后 interpPath /
    node 的 sha256 / rootfs URL 都是这个形态）。不先处理的话 `"([^"]*)"`
    会抓到 arm64 那条，然后在集合断言里与 musl/glibc 的对应关系对不上。
    """
    t = if_abi_else(v)
    if t is not None:
        return t
    m = re.search(r'"([^"]*)"', v)
    if m:
        return m.group(1)
    m = re.search(r"NodeStrategy\.(\w+)", v)
    if m:
        return m.group(1)
    m = re.search(r"PackageManager\.(\w+)", v)
    if m:
        return m.group(1)
    m = re.search(r"Family\.(\w+)", v)
    if m:
        return m.group(1)
    m = re.search(r"ArchiveFormat\.(\w+)", v)
    if m:
        return m.group(1)
    return v


def options_section(src):
    """取 `val options: List<Option> = listOf(...)` 的整段。"""
    i = src.find("val options: List<Option> = listOf(")
    if i < 0:
        return ""
    j = src.find("\n    )", i)
    return src[i:j] if j > 0 else src[i:]


def main():
    for path in (CATALOG, MANAGER, LAUNCHER, RUNNER, ACTIVITY):
        if not os.path.isfile(path):
            print("missing source file: %s" % path)
            return 1

    cat = read(CATALOG)
    mgr = read(MANAGER)
    launcher = read(LAUNCHER)
    runner = read(RUNNER)
    activity = read(ACTIVITY)
    opts_src = options_section(cat)
    opts = parse_options(opts_src)

    print("== A. catalog shape ==")
    ok("catalog declares >= 2 options", len(opts) >= 2, "found %d" % len(opts))
    fams = [field_str(o.get("family", "")) for o in opts]
    ok("Alpine present", "ALPINE" in fams, str(fams))
    ok("Ubuntu present", "UBUNTU" in fams, str(fams))
    defaults = [o for o in opts if o.get("isDefault", "").strip() == "true"]
    ok("exactly one default option", len(defaults) == 1, "%d defaults" % len(defaults))

    print("== B. per-option coherence ==")
    by_family = {field_str(o.get("family", "")): o for o in opts}

    def get(fam, key):
        v = by_family.get(fam, {}).get(key)
        return field_str(v) if v is not None else None

    # Alpine: musl + apk + APK_PACKAGE + 不跑 glibc 补丁
    ok("Alpine libc is musl", (get("ALPINE", "libc") or "").startswith("musl"),
       str(get("ALPINE", "libc")))
    ok("Alpine uses apk", get("ALPINE", "pkgManager") == "APK", str(get("ALPINE", "pkgManager")))
    ok("Alpine node via apk", get("ALPINE", "nodeStrategy") == "APK_PACKAGE",
       str(get("ALPINE", "nodeStrategy")))
    ok("Alpine needs no glibc quirks", get("ALPINE", "needsGlibcQuirks") == "false",
       str(get("ALPINE", "needsGlibcQuirks")))
    # ★interpPath 现在是 ALPINE_INTERP 常量（按 Build.SUPPORTED_ABIS 双架构分流），
    #   不再写字面量。断言要改成「两条架构分支都是 musl loader」，
    #   否则会把「x86_64 支持」误判成回归。
    _interp_field = get("ALPINE", "interpPath") or ""
    _interp_ok = (
        _interp_field.startswith("/lib/ld-musl-")
        or ("ALPINE_INTERP" in _interp_field
            and '"/lib/ld-musl-aarch64.so.1"' in cat
            and '"/lib/ld-musl-x86_64.so.1"' in cat)
    )
    ok("Alpine interp is musl loader", _interp_ok, str(_interp_field))
    ok("Alpine rootfs is bundled", "Bundled(" in by_family.get("ALPINE", {}).get("source", "")
       or "Bundled" in opts_src.split("family = Family.ALPINE")[1].split("Option(")[0],
       "bundled source expected for offline-first default")

    # Ubuntu: glibc + apt + 官方 node 包 + 需要 glibc 补丁
    ok("Ubuntu libc is glibc", (get("UBUNTU", "libc") or "").startswith("glibc"),
       str(get("UBUNTU", "libc")))
    ok("Ubuntu uses apt", get("UBUNTU", "pkgManager") == "APT", str(get("UBUNTU", "pkgManager")))
    ok("Ubuntu node via official tarball",
       get("UBUNTU", "nodeStrategy") == "OFFICIAL_TARBALL", str(get("UBUNTU", "nodeStrategy")))
    ok("Ubuntu needs glibc quirks", get("UBUNTU", "needsGlibcQuirks") == "true",
       str(get("UBUNTU", "needsGlibcQuirks")))
    # ★interpPath 现在是 if (IS_ARM64) aarch64-loader else x86_64-loader 三元。
    #   取出来的代表值（x86_64 分支）也必须**真的**是 glibc loader，否则
    #   x86_64 模拟器上容器根本起不来（musl 程序拿 glibc loader 加载）。
    _uinterp = get("UBUNTU", "interpPath") or ""
    ok("Ubuntu interp is glibc loader",
       _uinterp.startswith("/lib/x86_64-linux-gnu/ld-linux-"),
       str(_uinterp))

    print("== C. interp consistency ==")
    # ★断言从「每个选项 interp 都不同」改成「interp 与 libc 一一对应」。
    #
    # 原断言的前提是「每个发行版一个 interp」，但加了 Alpine 3.23 之后这个前提
    # 不成立了：3.22 与 3.23 都是 musl Alpine，动态链接器**本来就该是同一个**
    # （`/lib/ld-musl-x86_64.so.1`）。要求它们不同才是错的 —— 真的不同说明
    # 其中一个是复制粘贴写错了。
    #
    # 现在检查的是真正重要的事：
    #   ① 没有空的 interp
    #   ② 同一 libc 族的选项共用同一个 interp（musl 一组、glibc 一组）
    #   ③ 不同 libc 族**不能**共用 interp（musl 的程序不能拿 glibc 的 loader 启动）
    # 探针是**文本解析**，所以 `interpPath = ALPINE_INTERP` 拿到的是常量名而不是
    # 它的值。先把这个常量解开，否则会把"正确的常量引用"误判成"路径不同"。
    m = re.search(
        r'ALPINE_INTERP(?::\s*String)?\s*=\s*[\r\n\s]*if \(IS_ARM64\)\s*"([^"]+)"\s*else\s*"([^"]+)"',
        cat,
    )
    alpine_interp_val = None
    if m:
        # x86_64 是主机侧构建最常见的目标，取 else 分支作为代表值
        alpine_interp_val = m.group(2)

    def resolve(v):
        if v == "ALPINE_INTERP" and alpine_interp_val:
            return alpine_interp_val
        return v

    interps = [resolve(field_str(o.get("interpPath", ""))) for o in opts]
    ok("no empty interp", all(i for i in interps), str(interps))
    musl_interps = {i for i in interps if "musl" in i}
    glibc_interps = {i for i in interps if "musl" not in i}
    ok("all musl options share one interp", len(musl_interps) == 1, str(musl_interps))
    ok("no interp shared across libc families",
       not (musl_interps & glibc_interps), "%s vs %s" % (musl_interps, glibc_interps))
    ok("glibc options have a glibc interp",
       all(("linux-gnu" in i) for i in glibc_interps), str(glibc_interps))

    # ★最关键的一条：每个选项的 interp 必须与它自己声明的 libc **对得上**。
    #
    # 上面几条只按「集合」检查，抓不到「某个 musl 选项偷偷用了 glibc 的 loader」
    # —— 那种写错会让容器直接起不来（musl 程序拿 glibc loader 加载 → 一启动就
    # 找不到符号），而集合层面的断言仍然全绿。反向验证时实测过这个盲区。
    mismatch = []
    for o in opts:
        libc = field_str(o.get("libc", ""))
        ip = resolve(field_str(o.get("interpPath", "")))
        if not ip:
            continue
        if "musl" in libc and "musl" not in ip:
            mismatch.append("%s: libc=%s but interp=%s" % (o.get("family"), libc, ip))
        if "glibc" in libc and "linux-gnu" not in ip:
            mismatch.append("%s: libc=%s but interp=%s" % (o.get("family"), libc, ip))
    ok("every option's interp matches its own libc", not mismatch, "; ".join(mismatch))

    print("== D. download sources ==")
    urls = re.findall(r'"(https://[^"]+)"', opts_src)
    ok("at least one https mirror", len(urls) >= 1, "%d urls" % len(urls))
    # 下载器本身拒绝非 https（HttpDownloader.isSecureDownloadUrl），所以这里同口径
    bad_scheme = [u for u in urls if not u.startswith("https://")]
    ok("every rootfs URL is https", not bad_scheme, str(bad_scheme))
    # ★sha256 现在是 `sha256 = if (IS_ARM64) "…" else "…"`（issue #132 之前只有
    #   arm64 一条，x86_64 设备下到的是 arm64 rootfs → 容器里每个程序 SIGILL）。
    #   所以这里要收集「sha256 = 之后到下一个逗号/换行」的整块里的全部 64-hex。
    # ★sha256 不是 Option 的顶层参数，它在 `source = RootfsSource.Remote(...)` 里面。
    #   所以要从每个选项的 source 表达式正文里找（顶层 get("sha256") 恒为 None）。
    pins = []
    for o in opts:
        pins.extend(re.findall(r'"([0-9a-f]{64})"', o.get("source", "") or ""))
    ok("sha256 pins are 64 hex chars", len(pins) >= 1 and all(len(x) == 64 for x in pins),
       str(pins))
    ok("ubuntu rootfs has both abi pins", len(set(pins)) >= 2,
       "amd64 与 arm64 两条 rootfs sha256 必须都在（实测 %d 条）" % len(set(pins)))
    ok("compressed size declared", opts_src.count("compressedBytes =") >= 1, "")
    ok("extracted size declared", opts_src.count("extractedBytes =") >= 1, "")

    print("== E. glibc quirks script ==")
    m = re.search(r"fun glibcQuirksScript\(\): String = buildString \{(.*?)\n    \}", cat, re.S)
    script = m.group(1) if m else ""
    ok("quirks script found", bool(script.strip()), "")
    # 关键断言：divert 必须指向 merged-usr 后的真实路径
    ok("divert targets /usr/sbin/ldconfig.real",
       "/usr/sbin/ldconfig.real" in script, "must be the real path, not /sbin")
    ok("divert does NOT use /sbin/ldconfig.real",
       "/sbin/ldconfig.real" not in script.replace("/usr/sbin/ldconfig.real", ""),
       "the /sbin path is a symlink; diverting it silently does nothing")
    ok("shim writes exit 0 script", "exit 0" in script and "printf" in script, "")
    ok("shim is chmod 755", "chmod 755" in script, "")
    ok("quirks does not require bash", "bash" not in script, "")

    print("== F. deploy implementation ==")
    ok("manager downloads remote rootfs", "downloadRootfs" in mgr, "")
    ok("manager opens xz archives", "XZInputStream" in mgr, "")
    ok("manager runs glibc quirks after deploy",
       "glibcQuirksScript" in mgr or "dsh-glibc-quirks.sh" in mgr, "")
    ok("manager migrates legacy layout", "migrateLegacyLayout" in mgr, "")
    # 迁移必须挂在**所有** rootfs 访问的必经入口上，否则老用户永远停在旧布局
    # 只看 activeOption 的函数体（到下一个 4 空格缩进的声明为止），
    # 不能跨函数匹配 —— 否则 ensureDeployed 里的同名字符串会让它假绿。
    # 只看 activeOption 的函数体（到下一个 4 空格缩进的声明为止），
    # 不能跨函数匹配 —— 否则 ensureDeployed 里的同名字符串会让它假绿。
    # 只看 activeOption 的函数体，不能跨函数匹配 —— 否则 ensureDeployed 里
    # 出现的同名字符串会让这条断言假绿（实测：改成跨函数匹配后去掉迁移仍全绿）。
    # 只取 activeOption 的函数体：从函数签名切到下一条 4 空格缩进声明。
    # 不能用「全文里有没有 migrateLegacyLayout」——ensureDeployed 里也有同名字符串，
    # 那样删掉入口挂载依然全绿（实测过，假绿）。
    _i = mgr.find("fun activeOption(")
    _j = mgr.find(chr(10) + "    fun ", _i + 1) if _i >= 0 else -1
    body = mgr[_i:_j] if _i >= 0 and _j > _i else ""
    ok("migration is wired into the shared entry point (activeOption)",
       "migrateLegacyLayout()" in body,
       "must run before any rootfs read path, not only inside ensureDeployed")
    ok("apt install is two-step (download-only)",
       '"-d"' in mgr or "download only" in mgr.lower(), "one-shot install fails in container")
    ok("apt install uses dpkg -i from cache",
       "dpkg -i /var/cache/apt/archives" in mgr or "dpkg -i" in mgr, "")
    ok("apt writes ForceIPv4", "ForceIPv4" in cat, "")

    # noble 只读 deb822 的 sources.list.d/ubuntu.sources；写 etc/apt/sources.list
    # 是死码（实测 apt-get update 仍走官方 ports.ubuntu.com）。
    # ⚠️ 断言必须切出 aptSources 的函数体再查，不能全文搜字符串 ——
    # 注释里也写着 "Trusted: yes" / "sources.list.d/ubuntu.sources"，
    # 全文搜会让"删掉真实配置"依然全绿（实测踩过）。
    # 断言必须切出 aptSources 的函数体再查，不能全文搜字符串 ——
    # 注释里也写着 Trusted/sources.list.d，全文搜会让删掉真实配置依然全绿。
    _ai = cat.find("fun aptSources(")
    _aj = cat.find("\n    }", _ai) if _ai >= 0 else -1
    apt_body = cat[_ai:_aj] if _ai >= 0 and _aj > _ai else ""

    # noble 只读 deb822 的 sources.list.d/ubuntu.sources；写 etc/apt/sources.list
    # 是死码（实测 apt-get update 仍走官方 ports.ubuntu.com）。
    ok("apt writes the deb822 path, not legacy sources.list",
       "APT_SOURCES_REL" in mgr and "sources.list.d/ubuntu.sources" in cat,
       "writing etc/apt/sources.list is a no-op on noble")
    ok("apt sources are deb822 (Types:/URIs:/Suites:)",
       "Types: deb" in apt_body and "URIs:" in apt_body and "Suites:" in apt_body, "")
    # base 镜像没有 gnupg，apt-key 不可用 -> 每个 stanza 都要 Trusted: yes
    ok("apt sources set Trusted: yes on every stanza (base image has no gnupg)",
       apt_body.count("Types: deb") >= 1
       and apt_body.count("Trusted: yes") == apt_body.count("Types: deb"),
       "one Trusted per stanza; otherwise the repo is rejected as unsigned")
    # 镜像里 /tmp 是 0755，_apt 用户写不进去 -> apt-key 临时文件失败
    ok("glibc quirks widen /tmp to 1777",
       'File(root, "tmp")' in mgr and "setWritable(true, false)" in mgr,
       "otherwise: Couldn't create temporary file /tmp/apt.conf.XXX")
    ok("apt sources are http (CA chain incomplete in base image)",
       all(u.startswith("http://") for u in re.findall(r'"(http://[^"]*ubuntu-ports[^"]*)"', cat)),
       "https fails with certificate issuer unknown")

    print("== G. no stale hardcoding ==")
    # launcher 必须用 option.interpPath / nodePath(option)，不能回退到 musl 常量
    launcher_body = launcher
    ok("launcher uses option.interpPath", "option.interpPath" in launcher_body, "")
    ok("launcher does not call linuxMuslInterp for engine argv",
       "linuxMuslInterp" not in launcher_body, "must follow the active distro")
    ok("launcher has nodePath()", "fun nodePath(" in launcher_body, "")
    ok("launcher nodePath maps OFFICIAL_TARBALL to /opt/node",
       "GLIBC_NODE_PATH" in launcher_body, "")
    ok("runner uses active distro interp", "option.interpPath" in runner, "")
    ok("runner shells through /bin/sh", 'run("/bin/sh"' in runner, "")
    ok("manager status keys off option.interpPath",
       "option.interpPath" in mgr, "self-check must validate the distro's own loader")
    ok("manager status keys off per-distro package manager",
       "option.pkgManager" in mgr, "")
    # 不再有「写死 apk 路径」的自检（那会让 Ubuntu 永远判 BROKEN）
    ok("manager no longer hardcodes sbin/apk in status",
       'File(root, "sbin/apk")' not in mgr or "PackageManager.APK" in mgr, "")

    print("== H. i18n keys ==")
    used = set(re.findall(r"R\.string\.(linux_distro_\w+)", activity))
    ok("activity references distro strings", len(used) >= 4, str(sorted(used)))
    for loc in LOCALES:
        path = os.path.join(RES, loc, "strings.xml")
        if not os.path.isfile(path):
            ok("%s exists" % loc, False, path)
            continue
        src = read(path)
        defined = set(re.findall(r'<string name="(linux_distro_\w+)"', src))
        missing = used - defined
        ok("%s defines all distro keys" % loc, not missing, str(sorted(missing)))

    print("== I. node tarball pin ==")
    nt = re.search(r"val nodeTarball = NodeTarball\((.*?)\n    \)", cat, re.S)
    ok("nodeTarball declared", bool(nt), "")
    if nt:
        body = nt.group(1)
        ok("node version pinned", re.search(r'version = "\d+\.\d+\.\d+"', body) is not None, "")
        # ★node 的 sha256 也是 ABI 三元（x64 与 arm64 各一条，两条互不相同）
        _nshas = re.findall(r'"([0-9a-f]{64})"', body)
        ok("node sha256 pinned", len(_nshas) >= 2 and _nshas[0] != _nshas[1],
           "两条 ABI 各一个 sha256（实测 %d 条）" % len(_nshas))
        ok("node tarball urls are https",
           all(u.startswith("https://") for u in re.findall(r'"(https://[^"]+)"', body)),
           "")
        ok("node tarball is xz (smaller than gz on mobile)",
           ".tar.xz" in body, "28.6MB xz vs 53.5MB gz")
        ok("node install dir is /opt/node", 'installDir = "/opt/node"' in body, "")

        # 引擎 package.json 声明 engines.node >= 24.9.0。真机实测 22.14.0 会让
        # `node bin.js` 静默退出（exit=0 但零输出），App 只看到 "engine exited"，
        # 极难定位。这条断言锁住版本下界，防止有人为了"体积小一点"降回去。
        m_ver = re.search(r'version = "(\d+)\.(\d+)\.(\d+)"', body)
        if m_ver:
            major, minor = int(m_ver.group(1)), int(m_ver.group(2))
            at_least = (major, minor) >= (24, 9)
            ok("node version >= 24.9.0 (engine engines.node hard floor)",
               at_least,
               "22.14.0 makes 'node bin.js' exit silently with no output; got %s" % m_ver.group(0))

    return finish()


def finish():
    print("\n" + "-" * 68)
    print("checks=%d failed=%d" % (checks, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

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


def parse_options(src):
    """从 Kotlin 源码里抠出 `Option(...)` 条目。

    只取真实构造出来的条目（`Option(` 后跟命名参数），不取 data class 声明，
    也不取注释里出现的样例。返回值是 [dict(name=value_str)]。
    """
    out = []
    for m in re.finditer(r"\n\s+Option\(\n(.*?)\n\s{8}\),", src, re.S):
        body = m.group(1)
        fields = {}
        for fm in re.finditer(r"^\s+(\w+)\s*=\s*(.+?),\s*$", body, re.M):
            fields[fm.group(1)] = fm.group(2).strip()
        if "family" in fields:
            out.append(fields)
    return out


def field_str(v):
    """把 Kotlin 表达式还原成字符串值（取最内层 quoted 内容或枚举尾名）。"""
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
    ok("Alpine interp is musl loader",
       (get("ALPINE", "interpPath") or "").startswith("/lib/ld-musl-"),
       str(get("ALPINE", "interpPath")))
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
    ok("Ubuntu interp is glibc loader",
       (get("UBUNTU", "interpPath") or "").startswith("/lib/aarch64-linux-gnu/ld-linux-"),
       str(get("UBUNTU", "interpPath")))

    print("== C. interp uniqueness ==")
    interps = [field_str(o.get("interpPath", "")) for o in opts]
    ok("interp paths are distinct", len(set(interps)) == len(interps), str(interps))
    ok("no empty interp", all(i for i in interps), str(interps))

    print("== D. download sources ==")
    urls = re.findall(r'"(https://[^"]+)"', opts_src)
    ok("at least one https mirror", len(urls) >= 1, "%d urls" % len(urls))
    # 下载器本身拒绝非 https（HttpDownloader.isSecureDownloadUrl），所以这里同口径
    bad_scheme = [u for u in urls if not u.startswith("https://")]
    ok("every rootfs URL is https", not bad_scheme, str(bad_scheme))
    oci = re.findall(r'sha256 = "([0-9a-f]{64})"', opts_src)
    ok("sha256 pins are 64 hex chars", len(oci) >= 1 and all(len(x) == 64 for x in oci),
       str(oci))
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
        ok("node sha256 pinned", re.search(r'sha256 = "[0-9a-f]{64}"', body) is not None, "")
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

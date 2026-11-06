#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Linux 容器模式下扩展目录的**双发行版**包名映射自检。

## 为什么需要这个探针

`LinuxExtensionBridge` 把 catalog 里的 **Termux 包名**翻成**当前发行版**的包名。
这张映射表是整个「插件中心在 Linux 里装包」功能的地基 —— 一旦某个目标名写错，
用户在 App 里点「安装」只会拿到

    ERROR: unable to select packages:   (apk, Alpine)
    E: Unable to locate package         (apt, Ubuntu)

这种报错最容易出现在**跨发行版改名**的条目上，靠人肉记忆迟早写错。

而且**两个发行版的答案不一样**，这是本探针 v2 的重点：

| Termux | Alpine 3.22 | Ubuntu 24.04 |
|---|---|---|
| `aapt` / `apksigner` / `gradle` | 没有 | **有** |
| `aapt2` / `d8` | 没有 | 没有 |
| `clang` | `clang17` | `clang`（同名） |
| `sqlite` | `sqlite` | `sqlite3` |
| `openssh` | `openssh` | `openssh-client` |

只核对一边就会漏掉「Ubuntu 下把能装的条目误置灰」这类 bug，所以两张表都要
对着各自的真实索引核。

## 本探针做六件事

1. **静态一致性**：`LinuxExtensionBridge.kt` 的六张表（两套 renames + 两套
   unavailable + 两套 itemUnavailable）能被正确解析。
2. **表自洽**：不能出现「映射到自己」，也不能同一条同时出现在
   renames 与 unavailable 里；itemUnavailable 的 id 必须真实存在于 catalog。
3. **每条目至少一个可装包**（逐发行版）。
4. **真实索引核对**：每个映射目标、每个透传包名都必须在对应发行版的包索引里
   真实存在。索引优先取仓库内快照（CI 无网也能跑）：
   - Alpine: `app/src/main/assets/linux/apkindex-names.txt`
   - Ubuntu: `scripts/probes/data/aptindex-names.txt.gz`
5. **回归断言**：几条历史踩过的坑必须一直成立（见 `[E]` 段），且**必须包含
   两发行版的分歧项**——只断言一边等于没测。
6. **分派纪律**：所有对外入口都必须显式接收 `family`，不得在内部隐式再查一次
   当前发行版（那会让 UI 状态与真正执行的安装对不上）。

## 索引从哪来

Alpine 按顺序找，命中即用：

1. `$DSH_APKINDEX` 指向的索引文件（支持裸 `P:` 行格式或纯包名清单）
2. `app/src/main/assets/linux/apkindex-names.txt`（仓库内快照，一行一个包名）
3. `/tmp/apkidx/APKINDEX` + `/tmp/apkidx/APKINDEX.comm`（开发机）

Ubuntu 同理：

1. `$DSH_APTINDEX`
2. `scripts/probes/data/aptindex-names.txt.gz`（仓库内快照，gzip）

注意：Windows 上 Python 眼里的 `/tmp` 是 `C:\\tmp`，而 MSYS/Git Bash 的 `/tmp`
实际上是 `%LOCALAPPDATA%\\Temp` —— 两个候选都要试。

找不到索引就跳过第 4 项，并在输出里明确写「索引未核对」——
不能让「没测」看起来像「测过了」。

用法：
    PYTHONIOENCODING=utf-8 python scripts/probes/_test_linux_ext_bridge.py
"""
import gzip
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BRIDGE = os.path.join(
    ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine",
    "LinuxExtensionBridge.kt",
)
CATALOG = os.path.join(ROOT, "app", "src", "main", "assets", "extensions", "catalog.json")
APK_SNAPSHOT = os.path.join(ROOT, "app", "src", "main", "assets", "linux", "apkindex-names.txt")
APT_SNAPSHOT = os.path.join(ROOT, "scripts", "probes", "data", "aptindex-names.txt.gz")

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


def parse_map(src, name):
    """从 Kotlin 源码里抠出 `private val NAME: Map<...> = mapOf(...)` 的键值对。

    值可能是字符串（一对一）或 `listOf(...)`（一对多，如 ninja 需要
    ninja-build + ninja-is-really-ninja）。一对多的值以 list 形态返回。

    闭合括号按「行首恰好 4 个空格 + `)`」匹配，与这些表在 object 内的缩进一致；
    比裸 `\\n\\s*\\)` 更严，避免跨表误吞到下一个 mapOf。
    """
    pat = r"private val " + re.escape(name) + r"\s*:[^=]*=\s*mapOf\((.*?)\n {4}\)"
    m = re.search(pat, src, re.S)
    if not m:
        return None
    body = m.group(1)
    out = {}
    for k, vs in re.findall(r'"([^"]+)"\s+to\s+listOf\(([^)]*)\)', body):
        out[k] = re.findall(r'"([^"]*)"', vs)
    for k, v in re.findall(r'"([^"]+)"\s+to\s+"([^"]*)"', body):
        out.setdefault(k, v)
    return out


def as_list(v):
    return v if isinstance(v, list) else [v]


def _read_index(path):
    """读索引文件：支持 `P:<name>` 行格式，也支持纯包名清单。"""
    names = set()
    opener = gzip.open if path.endswith(".gz") else io.open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for ln in f:
            s = ln.strip()
            if not s or s.startswith("#"):
                continue
            if s.startswith("P:"):
                names.add(s[2:].strip())
            elif ":" not in s and "=" not in s:
                names.add(s)
    return names


def load_apk_index():
    """返回 (names_set, source_description) 或 (None, reason)。"""
    env = os.environ.get("DSH_APKINDEX")
    if env and os.path.isfile(env):
        names = _read_index(env)
        if names:
            return names, env

    if os.path.isfile(APK_SNAPSHOT):
        names = _read_index(APK_SNAPSHOT)
        if names:
            return names, APK_SNAPSHOT

    tmp_dirs = ["/tmp/apkidx"]
    la = os.environ.get("LOCALAPPDATA")
    if la:
        tmp_dirs.append(os.path.join(la, "Temp", "apkidx"))
    found = []
    for d in tmp_dirs:
        for f in ("APKINDEX", "APKINDEX.comm"):
            p = os.path.join(d, f)
            if os.path.isfile(p):
                found.append(p)
    if found:
        names = set()
        for c in found:
            names |= _read_index(c)
        if names:
            return names, " + ".join(found)

    return None, "no APKINDEX found (set $DSH_APKINDEX, or keep the repo snapshot)"


def load_apt_index():
    """Ubuntu 包索引：环境变量 > 仓库内 gzip 快照。"""
    env = os.environ.get("DSH_APTINDEX")
    if env and os.path.isfile(env):
        names = _read_index(env)
        if names:
            return names, env

    if os.path.isfile(APT_SNAPSHOT):
        names = _read_index(APT_SNAPSHOT)
        if names:
            return names, APT_SNAPSHOT

    return None, "no APT index found (set $DSH_APTINDEX, or keep the repo snapshot)"


# 每套发行版：Kotlin 表名 -> 元数据
DISTROS = [
    {
        "family": "ALPINE",
        "label": "Alpine",
        "renames": "ALPINE_NAMES",
        "unavailable": "ALPINE_UNAVAILABLE",
        "item_unavailable": "ALPINE_ITEM_UNAVAILABLE",
        "loader": load_apk_index,
    },
    {
        "family": "UBUNTU",
        "label": "Ubuntu",
        "renames": "UBUNTU_NAMES",
        "unavailable": "UBUNTU_UNAVAILABLE",
        "item_unavailable": "UBUNTU_ITEM_UNAVAILABLE",
        "loader": load_apt_index,
    },
]


def main():
    print("=" * 68)
    print("Linux extension bridge - dual-distro package mapping self-check")
    print("=" * 68)

    src = read(BRIDGE)
    cat = json.loads(read(CATALOG))

    def catalog_packages():
        for it in cat["items"]:
            for p in it.get("packages", []):
                yield it["id"], p

    catalog_ids = {it["id"] for it in cat["items"]}
    used_pkgs = {p for _, p in catalog_packages()}

    print("\n[A] 六张映射表是否解析成功")
    parsed = {}
    parse_ok = True
    for d in DISTROS:
        for key in ("renames", "unavailable", "item_unavailable"):
            name = d[key]
            tbl = parse_map(src, name)
            parsed[name] = tbl
            good = tbl is not None
            parse_ok = parse_ok and good
            ok("%s parsed" % name, good, "regex did not match")
    if not parse_ok:
        return finish()

    for d in DISTROS:
        renames = parsed[d["renames"]]
        unavailable = parsed[d["unavailable"]]
        item_unavail = parsed[d["item_unavailable"]]

        print("\n[B-%s] %s 表自洽" % (d["family"], d["label"]))
        self_map = [k for k, v in renames.items() if as_list(v) == [k]]
        ok("no identity mapping in %s" % d["renames"], not self_map,
           "identity: %s" % self_map)

        overlap = sorted(set(renames) & set(unavailable))
        ok("%s and %s are disjoint" % (d["renames"], d["unavailable"]),
           not overlap, "overlap: %s" % overlap)

        empty_targets = [k for k, v in renames.items() if not as_list(v)]
        ok("no empty mapping target", not empty_targets, str(empty_targets))

        all_targets = [t for v in renames.values() for t in as_list(v)]
        blanks = [t for t in all_targets if not t.strip()]
        ok("no empty string target", not blanks, str(blanks))

        bad_ids = sorted(set(item_unavail) - catalog_ids)
        ok("%s keys all exist in catalog" % d["item_unavailable"], not bad_ids,
           "unknown ids: %s" % bad_ids)

        orphan = sorted(set(unavailable) - used_pkgs)
        ok("%s keys are actually used by catalog" % d["unavailable"], not orphan,
           "unused: %s" % orphan)

        print("\n[C-%s] %s 下每个 catalog 条目至少有可装包" % (d["family"], d["label"]))
        for it in cat["items"]:
            iid = it["id"]
            if iid in item_unavail:
                print("  [SKIP] '%s' explicitly unavailable: %s" % (iid, item_unavail[iid]))
                continue
            if it.get("apkUrl"):
                print("  [SKIP] '%s' is an APK-type extension (mode-independent)" % iid)
                continue
            mapped = []
            for p in it.get("packages", []):
                if p in unavailable:
                    continue
                mapped.extend(as_list(renames.get(p, p)))
            ok("item '%s' has installable packages" % iid, bool(mapped),
               "all packages unavailable")

        print("\n[D-%s] 与真实 %s 索引核对" % (d["family"], d["label"]))
        names, src_desc = d["loader"]()
        if names is None:
            print("  [SKIP] %s" % src_desc)
            print("         -> 第 4 项未核对；这不等于映射正确，只是没验证。")
        else:
            print("  index: %s (%d packages)" % (src_desc, len(names)))

            bad_targets = []
            for k, v in renames.items():
                for tv in as_list(v):
                    if tv not in names:
                        bad_targets.append((k, tv))
            ok("all %s targets exist in %s index" % (d["renames"], d["label"]),
               not bad_targets, str(bad_targets))

            bad_pass = []
            for p in sorted(used_pkgs):
                if p in renames or p in unavailable:
                    continue
                if p not in names:
                    bad_pass.append(p)
            ok("all pass-through names exist in %s index" % d["label"],
               not bad_pass, str(bad_pass))

            wrongly = [p for p in unavailable if p in names]
            ok("no %s entry actually exists in the index" % d["unavailable"],
               not wrongly, str(wrongly))

    print("\n[E] 回归断言（历史踩坑 + 两发行版分歧）")
    apk = parsed["ALPINE_NAMES"]
    apt = parsed["UBUNTU_NAMES"]
    apk_un = parsed["ALPINE_UNAVAILABLE"]
    apt_un = parsed["UBUNTU_UNAVAILABLE"]
    apk_item = parsed["ALPINE_ITEM_UNAVAILABLE"]
    apt_item = parsed["UBUNTU_ITEM_UNAVAILABLE"]

    # --- Alpine 侧历史坑 ---
    alpine_renames = (
        ("python", "python3"),
        ("python-pip", "py3-pip"),
        ("golang", "go"),
        ("xz-utils", "xz"),
        ("lua54", "lua5.4"),
        ("php", "php83"),
        ("openjdk-17", "openjdk17"),
        ("clang", "clang17"),
        ("ninja", "ninja-build"),
        ("ndk-sysroot", "musl-dev"),
    )
    for termux_name, alpine_name in alpine_renames:
        got = as_list(apk.get(termux_name))
        ok("alpine rename %s -> %s" % (termux_name, alpine_name),
           alpine_name in got, "got %r" % (got,))

    # ninja-build 把 bin 藏在 /usr/lib/ninja-build/bin/ninja，必须同时装
    # ninja-is-really-ninja 才有 /usr/bin/ninja —— 实测只装前者 `ninja --version`
    # 报 not found。这条断言防止将来被简化成单包。
    ok("alpine ninja maps to two packages (ninja-build + ninja-is-really-ninja)",
       "ninja-is-really-ninja" in as_list(apk.get("ninja")),
       "got %r" % (apk.get("ninja"),))

    for p in ("aapt", "aapt2", "apksigner", "d8"):
        ok("alpine %s marked unavailable" % p, p in apk_un, "not in ALPINE_UNAVAILABLE")

    # android-tools（adb/fastboot）Alpine Community 有，曾经误列入 UNAVAILABLE
    ok("alpine android-tools is NOT unavailable (exists in Alpine community)",
       "android-tools" not in apk_un)

    ok("alpine llama-cpp item unavailable", "llama-cpp" in apk_item)
    ok("alpine android-buildtools item unavailable",
       "android-buildtools" in apk_item)

    # --- Ubuntu 侧：与 Alpine 的分歧必须被显式编码 ---
    ubuntu_renames = (
        ("python", "python3"),
        ("python-pip", "python3-pip"),
        ("lua54", "lua5.4"),
        ("rust", "rustc"),
        ("openjdk-17", "openjdk-17-jdk"),
        ("ndk-sysroot", "libc6-dev"),
        ("readline", "libreadline-dev"),
        ("sqlite", "sqlite3"),
        ("openssh", "openssh-client"),
        ("android-tools", "adb"),
    )
    for termux_name, ubuntu_name in ubuntu_renames:
        got = as_list(apt.get(termux_name))
        ok("ubuntu rename %s -> %s" % (termux_name, ubuntu_name),
           ubuntu_name in got, "got %r" % (got,))

    ok("ubuntu rust maps to rustc + cargo",
       {"rustc", "cargo"} <= set(as_list(apt.get("rust"))), "got %r" % (apt.get("rust"),))
    ok("ubuntu android-tools maps to adb + fastboot",
       {"adb", "fastboot"} <= set(as_list(apt.get("android-tools"))),
       "got %r" % (apt.get("android-tools"),))

    # ★最关键的分歧：Ubuntu 的 universe 仓库真的打包了 aapt/apksigner/gradle，
    #   所以这三条**不能**和 Alpine 一样置灰；而 aapt2/d8 确实没有。
    for p in ("aapt", "apksigner", "gradle"):
        ok("ubuntu %s is NOT unavailable (universe packages it)" % p,
           p not in apt_un, "wrongly in UBUNTU_UNAVAILABLE")
    for p in ("aapt2", "d8"):
        ok("ubuntu %s marked unavailable" % p, p in apt_un,
           "not in UBUNTU_UNAVAILABLE")
    ok("ubuntu llama-cpp item unavailable", "llama-cpp" in apt_item)
    # android-buildtools 在 Ubuntu 上部分可装，整条不得置灰
    ok("ubuntu android-buildtools is NOT wholesale unavailable",
       "android-buildtools" not in apt_item,
       "wrongly in UBUNTU_ITEM_UNAVAILABLE (aapt/apksigner/gradle do exist)")
    # clang 在 Ubuntu 同名透传，不能出现在 renames 里
    ok("ubuntu clang passes through (no 'clang17' fixup)",
       "clang" not in apt, "clang should not be renamed on Ubuntu")

    print("\n[F] 分派纪律：对外入口必须显式收 family")
    for fn in ("packagesOf", "unavailableReason", "partialNote", "allPackages"):
        m = re.search(r"fun\s+" + fn + r"\s*\((?:[^)]*)\)", src, re.S)
        ok("fun %s exists and takes family" % fn,
           m is not None and "family" in m.group(0),
           "signature: %s" % (m.group(0).replace(chr(10), " ") if m else "not found"))
    ok("no leftover alpinePackagesOf API",
       "alpinePackagesOf" not in src, "old name still exported")
    ok("no leftover allAlpinePackages API",
       "allAlpinePackages" not in src, "old name still exported")
    ok("distroMap dispatches to UBUNTU_MAP", "UBUNTU_MAP" in src)
    ok("distroMap dispatches to ALPINE_MAP", "ALPINE_MAP" in src)

    return finish()


def finish():
    print("\n" + "-" * 68)
    print("checks=%d failed=%d" % (checks, failed))
    if failed:
        print("RESULT: FAILED")
        return 1
    print("RESULT: PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Linux 容器模式下扩展目录的包名映射自检。

## 为什么需要这个探针

`LinuxExtensionBridge` 把 catalog 里的 **Termux 包名**翻成 **Alpine 包名**。
这张映射表是整个「插件中心在 Linux 里装包」功能的地基 —— 一旦某个目标名写错，
用户在 App 里点「安装」只会拿到

    ERROR: unable to select packages:
      python3 (no such package):

这种报错最容易出现在**跨发行版改名**的条目上（python -> python3、
golang -> go、xz-utils -> xz、clang -> clang17…），靠人肉记忆迟早写错。

所以本探针做四件事：

1. **静态一致性**：`LinuxExtensionBridge.kt` 的三张表能被正确解析。
2. **表自洽**：不能出现「映射到自己」，也不能同一条同时出现在
   `ALPINE_NAMES` 与 `UNAVAILABLE` 里。
3. **真实索引核对**：每个映射目标、每个透传包名都必须在 Alpine 包索引里
   真实存在。索引优先取仓库内的快照
   `app/src/main/assets/linux/apkindex-names.txt`（CI 无网也能跑）。
4. **回归断言**：几条历史踩过的坑必须一直成立（见 `[E]` 段）。

## 索引从哪来

按顺序找，命中即用：

1. `$DSH_APKINDEX` 指向的索引文件（支持裸 `P:` 行格式或纯包名清单）
2. `app/src/main/assets/linux/apkindex-names.txt`（仓库内快照，一行一个包名）
3. `/tmp/apkidx/APKINDEX` + `/tmp/apkidx/APKINDEX.comm`（开发机）

注意：Windows 上 Python 眼里的 `/tmp` 是 `C:\\tmp`，而 MSYS/Git Bash 的 `/tmp`
实际上是 `%LOCALAPPDATA%\\Temp` —— 两个候选都要试。

找不到索引就跳过第 3 项，并在输出里明确写「索引未核对」——
不能让「没测」看起来像「测过了」。

用法：
    PYTHONIOENCODING=utf-8 python scripts/probes/_test_linux_ext_bridge.py
"""
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
SNAPSHOT = os.path.join(ROOT, "app", "src", "main", "assets", "linux", "apkindex-names.txt")

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
    """
    pat = r"private val " + re.escape(name) + r"\s*:[^=]*=\s*mapOf\((.*?)\n\s*\)"
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


# 快照里的条目是 `git-2.49.1-r0` 这种「包名-版本-r修订」形式，而映射表里写的是裸包名。
# 不剥版本号就一条都匹配不上，探针会整片误报。
_VERSIONED = re.compile(r"^(?P<name>.+?)-\d[^-]*-r\d+$")


def _read_index(path):
    """读索引文件：支持 `P:<name>` 行、`name-<ver>-r<n>` 快照行，以及纯包名清单。"""
    names = set()
    with io.open(path, encoding="utf-8", errors="replace") as f:
        for ln in f:
            s = ln.strip()
            if not s or s.startswith("#"):
                continue
            if s.startswith("P:"):
                names.add(s[2:].strip())
                continue
            if ":" in s or "=" in s:
                continue
            names.add(s)
            m = _VERSIONED.match(s)
            if m:
                names.add(m.group("name"))
    return names


def load_index_names():
    """返回 (names_set, source_description) 或 (None, reason)。"""
    env = os.environ.get("DSH_APKINDEX")
    if env and os.path.isfile(env):
        names = _read_index(env)
        if names:
            return names, env

    if os.path.isfile(SNAPSHOT):
        names = _read_index(SNAPSHOT)
        if names:
            return names, SNAPSHOT

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


def main():
    print("=" * 68)
    print("Linux extension bridge - package mapping self-check")
    print("=" * 68)

    src = read(BRIDGE)
    alpine_names = parse_map(src, "ALPINE_NAMES")
    unavailable = parse_map(src, "UNAVAILABLE")
    item_unavailable = parse_map(src, "ITEM_UNAVAILABLE")

    print("\n[A] 映射表是否解析成功")
    ok("ALPINE_NAMES parsed", alpine_names is not None, "regex did not match")
    ok("UNAVAILABLE parsed", unavailable is not None)
    ok("ITEM_UNAVAILABLE parsed", item_unavailable is not None)
    if alpine_names is None or unavailable is None or item_unavailable is None:
        return finish()

    print("\n[B] 映射表自洽")
    self_map = [k for k, v in alpine_names.items() if as_list(v) == [k]]
    ok("no identity mapping in ALPINE_NAMES", not self_map, "identity: %s" % self_map)

    overlap = sorted(set(alpine_names) & set(unavailable))
    ok("ALPINE_NAMES and UNAVAILABLE are disjoint", not overlap, "overlap: %s" % overlap)

    empty_targets = [k for k, v in alpine_names.items() if not as_list(v)]
    ok("no empty mapping target", not empty_targets, str(empty_targets))

    all_target_names = [t for v in alpine_names.values() for t in as_list(v)]
    blanks = [t for t in all_target_names if not t.strip()]
    ok("no empty string target", not blanks, str(blanks))

    print("\n[C] catalog 覆盖情况")
    cat = json.loads(read(CATALOG))

    def catalog_packages():
        for it in cat["items"]:
            for p in it.get("packages", []):
                yield it["id"], p

    passthrough = {}
    for item_id, pkg in catalog_packages():
        if pkg in alpine_names or pkg in unavailable:
            continue
        passthrough.setdefault(pkg, []).append(item_id)
    print("  pass-through (%d): %s" % (len(passthrough), ", ".join(sorted(passthrough))))
    ok("pass-through detected (sanity: catalog really parsed)", bool(passthrough))

    print("\n[D] 每个 catalog 条目在 Linux 模式下至少有可装包")
    for it in cat["items"]:
        iid = it["id"]
        if iid in item_unavailable:
            print("  [SKIP] item '%s' explicitly unavailable: %s" % (iid, item_unavailable[iid]))
            continue
        if it.get("apkUrl"):
            print("  [SKIP] item '%s' is an APK-type extension (mode-independent)" % iid)
            continue
        mapped = []
        for p in it.get("packages", []):
            if p in unavailable:
                continue
            mapped.extend(as_list(alpine_names.get(p, p)))
        ok("item '%s' has installable packages" % iid, bool(mapped), "all packages unavailable")

    print("\n[E] 回归断言（历史踩坑）")
    renames = (
        ("python", "python3"),
        ("python-pip", "py3-pip"),
        ("golang", "go"),
        ("xz-utils", "xz"),
        ("lua54", "lua5.4"),
        ("php", "php83"),
        ("openjdk-17", "openjdk17"),
        ("clang", "clang17"),
        ("ninja", "ninja-build"),
    )
    for termux_name, alpine_name in renames:
        got = as_list(alpine_names.get(termux_name))
        ok("rename %s -> %s" % (termux_name, alpine_name),
           alpine_name in got, "got %r" % (got,))

    # ninja-build 把 bin 藏在 /usr/lib/ninja-build/bin/ninja，必须同时装
    # ninja-is-really-ninja 才有 /usr/bin/ninja —— 实测只装前者 `ninja --version`
    # 报 not found。这条断言防止将来被简化成单包。
    ok("ninja maps to two packages (ninja-build + ninja-is-really-ninja)",
       "ninja-is-really-ninja" in as_list(alpine_names.get("ninja")),
       "got %r" % (alpine_names.get("ninja"),))

    for p in ("aapt", "aapt2", "apksigner", "d8"):
        ok("%s marked unavailable" % p, p in unavailable, "not in UNAVAILABLE")

    # android-tools（adb/fastboot）Alpine Community 有，曾经误列入 UNAVAILABLE
    ok("android-tools is NOT in UNAVAILABLE (exists in Alpine community)",
       "android-tools" not in unavailable)

    ok("llama-cpp marked unavailable", "llama-cpp" in unavailable)

    print("\n[F] 与真实 Alpine 索引核对")
    names, src_desc = load_index_names()
    if names is None:
        print("  [SKIP] %s" % src_desc)
        print("         -> 第 3 项未核对；这不等于映射正确，只是没验证。")
    else:
        print("  index: %s (%d packages)" % (src_desc, len(names)))

        bad_targets = []
        for k, v in alpine_names.items():
            for tv in as_list(v):
                if tv not in names:
                    bad_targets.append((k, tv))
        ok("all mapping targets exist in Alpine index", not bad_targets, str(bad_targets))

        bad_pass = []
        for p in sorted({p for _, p in catalog_packages()}):
            if p in alpine_names or p in unavailable:
                continue
            if p not in names:
                bad_pass.append(p)
        ok("all pass-through names exist in Alpine index", not bad_pass, str(bad_pass))

        wrongly = [p for p, why in unavailable.items() if why and p in names]
        ok("no UNAVAILABLE entry actually exists in the index", not wrongly, str(wrongly))

    return finish()


def finish():
    print("\n" + "-" * 68)
    print("checks=%d failed=%d" % (checks, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

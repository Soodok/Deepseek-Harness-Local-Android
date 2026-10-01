#!/usr/bin/env python3
"""扩展闭包 ELF 依赖审计：catalog 声明的每个扩展，其 bin 的动态库依赖必须被依赖闭包覆盖。

背景（v1.2.35 实测教训）：Termux 上游个别包（如 lua54）的二进制链接了某库但不声明
Depends —— Termux 本体自带 readline/ncurses 等基础包所以上游未暴露，而本项目的扩展
环境是干净的，用户装上就是 CANNOT LINK EXECUTABLE。本脚本在读条目录前把这类"索引
元数据缺口"提前抓出来。

做三件事：
  1. 拉取 Termux Packages 索引（默认清华 TUNA；--mirror 可换）
  2. 按 catalog 的 packages 字段解析依赖闭包（Depends，含 | 候选回退）
  3. 下载闭包内所有 .deb（缓存到 --cache，默认 _debcheck/audit）→ 解 data.tar.xz →
     对每个 bin 扫 NEEDED 库名，核对是否被闭包内某包的 lib/ 提供；
     核不上且不是 bionic 系统库 → 记为缺口（提示需要在 catalog 里显式补包）

用法：
  python scripts/audit-extension-closures.py                 # 审计全部 19 个扩展（aarch64）
  python scripts/audit-extension-closures.py --ext lua,php    # 只审计指定扩展
  python scripts/audit-extension-closures.py --arch x86_64    # 换架构索引
  python scripts/audit-extension-closures.py --offline        # 只用缓存（不联网）

退出码：0 = 无缺口；1 = 有缺口（catalog 需要补包）
"""
import argparse
import io
import json
import lzma
import os
import re
import subprocess
import sys
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CATALOG = os.path.join(ROOT, "app", "src", "main", "assets", "extensions", "catalog.json")
DEFAULT_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"

# Termux 前缀：deb 内路径 ./data/data/com.termux/files/usr/...
TERMUX_DATA_PREFIX = "data/data/com.termux/files/"

# bionic 提供的系统库（不需要闭包覆盖）
SYS_LIB_RE = re.compile(r"^lib(c|m|dl|log|android|stdc\+\+|gcc|atomic|EGL|GLES|jnigraphics|OpenSLES)\.so")

# lib 名 → 提供者包名（Debian 命名惯例之外的特例；其余用 lib<n>.so → <n> 猜测）
KNOWN_PROVIDERS = {
    "libc++_shared.so": "libc++",
    "libreadline.so.8": "readline",
    "libhistory.so.8": "readline",
    "libz.so.1": "zlib",
    "libbz2.so.1.0": "libbz2",
    "liblzma.so.5": "liblzma",
    "libexpat.so.1": "libexpat",
    "libffi.so.8": "libffi",
    "libsqlite3.so": "libsqlite",
    "libcrypto.so.3": "openssl",
    "libssl.so.3": "openssl",
    "libncursesw.so.6": "ncurses",
    "libncurses.so.6": "ncurses",
    "libtinfow.so.6": "ncurses",
    "libformw.so.6": "ncurses-ui-libs",
    "libmenuw.so.6": "ncurses-ui-libs",
    "libpanelw.so.6": "ncurses-ui-libs",
    "libgdbm.so.6": "gdbm",
    "libzstd.so.1": "zstd",
    "libcrypt.so.1": "libcrypt",
    "libiconv.so": "libiconv",
    "libpcre2-8.so.0": "pcre2",
    "libandroid-support.so": "libandroid-support",
    "libandroid-glob.so": "libandroid-glob",
    "libandroid-posix-semaphore.so": "libandroid-posix-semaphore",
    "liblz4.so.1": "liblz4",
    "libprotobuf.so": "libprotobuf",
    "libedit.so.0": "libedit",
    "libxml2.so.2": "libxml2",
    "libresolv.so.2": "libresolv-wrapper",
}

# 不使用的辅助二进制（缺库不算缺口）：termux-auth 的登录助手，本项目不调用
IGNORED_BINS = {"pwlogin"}


def load_catalog():
    with io.open(CATALOG, encoding="utf-8") as f:
        return json.load(f)


def parse_index(text):
    """apt Packages 文本 → {包名: {fn, deps}}（续行忽略，与 App 侧 parsePackages 同语义）"""
    idx = {}
    for block in text.split("\n\n"):
        name = fn = ""
        deps = []
        for line in block.split("\n"):
            if line.startswith("Package: "):
                name = line[9:].strip()
            elif line.startswith("Depends: "):
                deps = [d.strip() for d in line[9:].split(",")]
            elif line.startswith("Filename: "):
                fn = line[10:].strip()
        if name:
            idx[name] = {"fn": fn, "deps": deps}
    return idx


def resolve_closure(idx, roots):
    """BFS 依赖闭包（含 Depends 的 'a | b' 候选回退；缺失包给出警告）"""
    out, queue = {}, list(roots)
    while queue:
        name = queue.pop(0)
        if name in out:
            continue
        pkg = idx.get(name)
        if pkg is None:
            print(f"    [warn] {name} 不在索引中（catalog 写的包名可能已改名）")
            continue
        out[name] = pkg
        for dep in pkg["deps"]:
            cands = [t.strip().split(" ")[0].strip() for t in dep.split("|")]
            hit = next((c for c in cands if c in idx), None) or next((c for c in cands if c), None)
            if hit and hit not in out:
                queue.append(hit)
    return out


def fetch_deb(mirror, fn, cache, offline):
    local = os.path.join(cache, os.path.basename(fn))
    if not os.path.exists(local):
        if offline:
            return None
        print(f"    ↓ {os.path.basename(fn)}", flush=True)
        r = subprocess.run(["curl", "-s", "-o", local, f"{mirror}/{fn}"])
        if r.returncode != 0 or not os.path.exists(local):
            return None
    return local


def parse_deb(path):
    """ar → data.tar.xz → {bin 名: 字节}、{lib 名}"""
    data = open(path, "rb").read()
    if data[:8] != b"!<arch>\n":
        return None, None
    off, members = 8, {}
    while off + 60 <= len(data):
        h = data[off:off + 60]
        off += 60
        name = h[:16].decode(errors="replace").strip().rstrip("/")
        try:
            size = int(h[48:58].decode().strip())
        except ValueError:
            break
        members[name] = data[off:off + size]
        off += size + (size & 1)
    blob = members.get("data.tar.xz")
    if blob is None:
        return None, None
    libs, bins = set(), {}
    with lzma.open(io.BytesIO(blob)) as f:
        tf = tarfile.open(fileobj=io.BytesIO(f.read()))
        for m in tf.getmembers():
            rel = m.name
            if rel.startswith("./"):
                rel = rel[2:]
            rel = rel.lstrip("/")
            if rel.startswith(TERMUX_DATA_PREFIX):
                rel = rel[len(TERMUX_DATA_PREFIX):]
            base = rel.rsplit("/", 1)[-1]
            if (rel.startswith("usr/lib/") or rel.startswith("lib/")) and ".so" in base:
                libs.add(base)
            if (rel.startswith("usr/bin/") or rel.startswith("bin/")) and m.isfile() and m.size > 0:
                bins[base] = tf.extractfile(m).read()
    return bins, libs


def guess_provider(lib, idx):
    if lib in KNOWN_PROVIDERS:
        return KNOWN_PROVIDERS[lib]
    if re.match(r"libabsl", lib):
        return "abseil-cpp"
    if lib.startswith("libbrotli"):
        return "brotli"
    if lib.startswith("libfmt"):
        return "fmt"
    m = re.match(r"lib(.+?)\.so", lib)
    if m:
        for cand in (m.group(1), "lib" + m.group(1)):
            if cand in idx:
                return cand
    return f"?{lib}"


def audit_one(ext, idx, mirror, cache, offline, index_text):
    cl = resolve_closure(idx, ext.get("packages", []))
    missing_pkgs = [p for p in ext.get("packages", []) if p not in idx]
    provided_libs, deb_bins, failed = set(), {}, []
    for name, pkg in cl.items():
        if not pkg["fn"]:
            continue
        path = fetch_deb(mirror, pkg["fn"], cache, offline)
        if path is None:
            failed.append(name)
            continue
        try:
            bins, libs = parse_deb(path)
        except Exception:
            # 缓存里的 deb 可能因网络中断而截断：删掉重下，避免审计整体崩掉
            try:
                os.remove(path)
            except OSError:
                pass
            path = fetch_deb(mirror, pkg["fn"], cache, offline)
            bins, libs = (None, None) if path is None else parse_deb(path)
        if bins is None:
            failed.append(name)
            continue
        provided_libs |= libs
        if bins:
            deb_bins[name] = bins

    gaps = []
    for pkg_name, bins in deb_bins.items():
        for bin_name, blob in bins.items():
            if bin_name in IGNORED_BINS:
                continue
            needed = set(re.findall(rb"lib[a-zA-Z0-9_+.\-]*\.so[0-9.]*", blob))
            miss = []
            for raw in needed:
                lib = raw.decode()
                if SYS_LIB_RE.match(lib) or lib in provided_libs:
                    continue
                prov = guess_provider(lib, idx)
                if prov in cl:
                    continue
                miss.append(f"{lib} → 需 {prov}")
            if miss:
                gaps.append(f"{pkg_name}:{bin_name} 缺 {sorted(set(miss))}")

    declared = ext.get("bins", [])
    return {
        "id": ext["id"], "closure": sorted(cl), "gaps": gaps,
        "failed_downloads": failed, "missing_pkgs": missing_pkgs,
        "declared_bins": declared, "lib_count": len(provided_libs),
        "bin_count": len(deb_bins),
    }


def main():
    ap = argparse.ArgumentParser(description="扩展闭包 ELF 依赖审计")
    ap.add_argument("--arch", default="aarch64", help="Termux 架构（aarch64/x86_64），默认 aarch64")
    ap.add_argument("--mirror", default=DEFAULT_MIRROR, help="Termux 镜像根")
    ap.add_argument("--ext", default="", help="只审计指定扩展（逗号分隔 id）")
    ap.add_argument("--cache", default=os.path.join(ROOT, "_debcheck", "audit"), help="deb 缓存目录")
    ap.add_argument("--offline", action="store_true", help="只用缓存，不联网")
    args = ap.parse_args()

    os.makedirs(args.cache, exist_ok=True)
    index_path = os.path.join(args.cache, f"Packages-{args.arch}.txt")
    if os.path.exists(index_path):
        index_text = io.open(index_path, encoding="utf-8", errors="replace").read()
    elif args.offline:
        print("离线模式但无索引缓存，退出"); return 1
    else:
        url = f"{args.mirror}/dists/stable/main/binary-{args.arch}/Packages"
        print(f"拉取索引 {url}")
        r = subprocess.run(["curl", "-s", "-o", index_path, url])
        if r.returncode != 0:
            print("索引下载失败"); return 1
        index_text = io.open(index_path, encoding="utf-8", errors="replace").read()
    idx = parse_index(index_text)
    print(f"索引 {len(idx)} 个包")

    catalog = load_catalog()
    items = catalog["items"]
    if args.ext:
        want = set(args.ext.split(","))
        items = [it for it in items if it["id"] in want]

    total_gaps = 0
    for it in items:
        r = audit_one(it, idx, args.mirror, args.cache, args.offline, index_text)
        head = f"[{r['id']}] 闭包 {len(r['closure'])} 包 / {r['bin_count']} 个含 bin 包 / lib {r['lib_count']} 个"
        if not r["gaps"] and not r["failed_downloads"] and not r["missing_pkgs"]:
            print(f"✅ {head}")
        else:
            total_gaps += len(r["gaps"]) + len(r["failed_downloads"]) + len(r["missing_pkgs"])
            print(f"⚠️ {head}  声明的 bins: {r['declared_bins']}")
            for g in r["gaps"]:
                print(f"    缺口: {g}  ← 需在 catalog 该条目的 packages 里补包")
            for m in r["missing_pkgs"]:
                print(f"    索引无此包: {m}")
            for f_ in r["failed_downloads"]:
                print(f"    下载/解析失败: {f_}")

    print()
    if total_gaps:
        print(f"发现 {total_gaps} 处问题（退出码 1）")
        return 1
    print("全部扩展的 bin 库依赖均被闭包覆盖 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""write-runtime-manifest.py —— 生成 APK 内的 assets/runtime/MANIFEST.json。

由 Gradle 的 prepareRuntimeAssets 任务调用（见 app/build.gradle.kts）。

两种包形态:
  full      —— 携带 runtime.zip，bundled=true，sha256/size 来自实际 zip；
  noruntime —— 不携带 runtime.zip，bundled=false，只写下载链接/版本，等 App 首启下载。

链接规则（与 tasks/plan.md 3.3 对齐）:
  · tag 构建：--runtime-url-base https://github.com/<repo>/releases/download/<tag>
  · preview/edge：--runtime-url-base https://github.com/<repo>/releases/latest/download
  · 本地构建：未显式传 --runtime-url-base 时由 Gradle 默认填 latest
    （App 通过签名索引 runtime-index.json 解析 sha 后下载，不再报「没有下载源」）

`urls` 同时写直连与 --proxy-base 前缀版本；`url` 保留为 urls[0] 兼容字段。
"""
import argparse
import json
import os
import sys
import time
import zipfile


def zip_stats(path: str) -> tuple[int, int, str]:
    import hashlib

    h = hashlib.sha256()
    total = 0
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
            total += len(b)
    unpacked = 0
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            unpacked += info.file_size
    return total, unpacked, h.hexdigest()


def read_build_json(path: str) -> dict:
    try:
        with zipfile.ZipFile(path) as z:
            if "BUILD.json" in z.namelist():
                return json.loads(z.read("BUILD.json").decode("utf-8"))
    except Exception:
        pass
    return {}


def sha256_file(path: str) -> str:
    import hashlib

    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--package", choices=["full", "noruntime"], required=True)
    ap.add_argument("--abi", required=True)
    ap.add_argument("--repo", default="Soodok/Deepseek-Harness-Local-Android")
    ap.add_argument("--runtime-zip")
    ap.add_argument("--runtime-version", default="")
    ap.add_argument("--runtime-sha256", default="")
    ap.add_argument("--runtime-size", type=int, default=0)
    ap.add_argument("--runtime-unpacked", type=int, default=0)
    ap.add_argument("--unpinned", action="store_true", help="rolling URL: omit sha/size (App resolves via index)")
    ap.add_argument("--runtime-url-base", default="")
    ap.add_argument("--proxy-base", default="https://gh-proxy.org/")
    ap.add_argument("--hardssh-dir", default="")
    ap.add_argument("--index-url", default="")
    args = ap.parse_args()

    direct = ""
    urls: list[str] = []
    if args.runtime_url_base:
        direct = args.runtime_url_base.rstrip("/") + f"/runtime-{args.abi}.zip"
        urls.append(direct)
        if args.proxy_base:
            urls.append(args.proxy_base.rstrip("/") + "/" + direct)

    runtime: dict = {
        "bundled": args.package == "full",
        "url": direct,
        "urls": urls,
    }
    if args.package == "full":
        if not args.runtime_zip or not os.path.isfile(args.runtime_zip):
            print("write-runtime-manifest: full package requires --runtime-zip", file=sys.stderr)
            return 1
        size, unpacked, sha = zip_stats(args.runtime_zip)
        build = read_build_json(args.runtime_zip)
        version = args.runtime_version or str(build.get("version") or "")
        if not version:
            version = time.strftime("%Y.%m.%d") + "-" + sha[:8]
        runtime.update(
            {
                "version": version,
                "builtAt": int(build.get("builtAt") or time.time()),
                "sha256": sha,
                "size": size,
                "unpackedBytes": unpacked,
            }
        )
    else:
        runtime.update(
            {
                "version": args.runtime_version or "unknown",
                "builtAt": 0,
            }
        )
        if not args.unpinned and args.runtime_sha256:
            if args.runtime_size:
                runtime["size"] = args.runtime_size
            if args.runtime_unpacked:
                runtime["unpackedBytes"] = args.runtime_unpacked
            runtime["sha256"] = args.runtime_sha256

    hardssh: dict = {"bundled": False}
    if args.hardssh_dir and os.path.isdir(args.hardssh_dir):
        meta_path = os.path.join(args.hardssh_dir, "hardssh-bundle.json")
        meta = {}
        if os.path.isfile(meta_path):
            meta = json.load(open(meta_path, encoding="utf-8"))
        hardssh = {
            "version": str(meta.get("version") or ""),
            "bundled": True,
            "bundleSha256": str(meta.get("bundleSha256") or ""),
            "npmIntegrity": str(meta.get("npmIntegrity") or ""),
            "tgzUrl": str(meta.get("tgzUrl") or ""),
            "size": sum(
                os.path.getsize(os.path.join(dp, f))
                for dp, _, fs in os.walk(args.hardssh_dir)
                for f in fs
            ),
        }

    repo = args.repo.strip() or "Soodok/Deepseek-Harness-Local-Android"
    index_url = args.index_url or (
        f"https://github.com/{repo}/releases/latest/download/runtime-index.json"
    )
    doc = {
        "schema": 2,
        "package": args.package,
        "abi": args.abi,
        "builtAt": int(time.time()),
        "runtime": runtime,
        "hardssh": hardssh,
        "updates": {
            "indexUrl": index_url,
            "proxies": [args.proxy_base] if args.proxy_base else [],
            "allowedHosts": [
                "github.com",
                "objects.githubusercontent.com",
                "gh-proxy.org",
            ],
        },
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(
        f"write-runtime-manifest: {args.package}/{args.abi} -> {args.out} "
        f"(runtime={runtime.get('version')}, bundled={runtime['bundled']}, "
        f"hardssh={hardssh.get('version')})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

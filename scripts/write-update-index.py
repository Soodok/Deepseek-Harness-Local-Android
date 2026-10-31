#!/usr/bin/env python3
"""write-update-index.py —— 生成 `update-index.json`（App 自动更新的哈希清单）。

## 为什么需要这个文件
自动更新必须能**机器校验**每个安装包的 SHA-256。原先 SHA-256 只写在 Release
说明正文里（人眼可读），App 拿不到 → `HttpDownloader` 的 sha pin 契约过不了 →
只能退化成「不校验完整性」，而这正是代理投毒的入口。

本脚本扫描构建产物目录，产出结构化清单：

```json
{
  "schema": 1,
  "channel": "stable",
  "latest": { "versionCode": 287, "versionName": "1.5.0", ... },
  "assets": [ { "abi": "arm64-v8a", "package": "full", "variant": "release",
                "name": "...apk", "size": 223456789, "sha256": "...",
                "runtimeVersion": "2026.10.28-eb83a614", "runtimeBuiltAt": 1761667200000 } ]
}
```

清单本身由 `scripts/sign-runtime-index.py` 签名（同一把 key），App 侧
`RuntimeIndexClient` 那套验签代码复用 → 代理改清单会被签名校验挡住。

## 用法
```bash
python3 scripts/write-update-index.py \
  --apk-dir apk \
  --version-code 287 --version-name 1.5.0 \
  --tag v1.4.37 --channel stable \
  --min-app-version-code 260 \
  --out release/update-index.json
```

产物名解析规则与 `.github/workflows/android-build.yml` 的重命名步骤严格对应：
`dsh-android-<ref>[-v<ver>]-<abi>-<variant>[-noruntime].apk`
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from datetime import datetime, timezone

SCHEMA = 1
CHANNELS = ("stable", "edge", "preview")
VARIANTS = ("release", "debug")
# 顺序有意义：先匹配长的，避免 arm64-v8a 被当成 v8a
KNOWN_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


def sha256_of(path: str, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def parse_apk_name(name: str) -> dict:
    """从产物文件名解析 (abi, package, variant)。

    从右往左剥：`.apk` → `-noruntime` → variant → abi。左侧的 ref/版本段
    一律忽略（它们对 App 选包没有意义，只进日志）。
    """
    if not name.endswith(".apk"):
        raise ValueError(f"not an apk: {name}")
    stem = name[: -len(".apk")]

    package = "full"
    if stem.endswith("-noruntime"):
        stem = stem[: -len("-noruntime")]
        package = "noruntime"

    if "-" not in stem:
        raise ValueError(f"cannot parse variant: {name}")
    stem, variant = stem.rsplit("-", 1)
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant '{variant}' in {name}")

    # 构建产物的版本段有两种位置：
    #   tag 构建：`dsh-android-<ref>-<abi>[-v<ver>]-...`，ref 本身就是 v<ver>，版本段在 abi 左边；
    #   push 构建：`dsh-android-main-<abi>-v<ver>-...`，版本段在 abi 右边。
    # 版本段必须同时接受试发行的预发布标识（`-v1.5.0-beta`、`-v1.5.0-rc.1`），否则
    # publish-edge（产物名带 `-v1.5.0-beta`）会剥不掉版本段，报 `cannot parse abi` 整条红。
    # 预发布标识的字符集与 ABI 名重叠（`arm64-v8a` 可被 `[0-9A-Za-z-]` 吃掉），所以
    # 只在「末尾不是已知 ABI」时才剥 —— 版本段永远紧贴 variant 侧，不会误伤 abi。
    def _ends_with_abi(s: str) -> bool:
        return any(s.endswith("-" + a) for a in KNOWN_ABIS)

    if not _ends_with_abi(stem):
        stem = re.sub(r"-v\d+(?:\.\d+)*(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$", "", stem)

    abi = None
    for a in KNOWN_ABIS:
        if stem.endswith("-" + a):
            abi = a
            stem = stem[: -len(a) - 1]
            break
    if abi is None:
        raise ValueError(f"cannot parse abi: {name}")

    return {"abi": abi, "package": package, "variant": variant}


def load_runtime_meta(path: str | None) -> tuple[str | None, int, str | None]:
    """读构建期 MANIFEST.json 里的 runtime.version / builtAt / sha256（选包判据）。

    F2 依赖 `builtAt`：App 用它与本机 `.runtime.json` 的 builtAt 比大小，
    判断「本次更新到底有没有换运行时」，从而决定给不给用户 noruntime 选项。
    v1.4.56 起同时写 `runtimeSha256`：builtAt 只说明"重新构建过"，内容 sha
    才说明"内容真的变了"—— 重构建但内容相同的 runtime 不该让用户重下 250MB。
    """
    if not path or not os.path.isfile(path):
        return None, 0, None
    try:
        doc = json.load(open(path, encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        print(f"WARN: cannot read runtime manifest {path}: {e}", file=sys.stderr)
        return None, 0, None
    rt = doc.get("runtime") or {}
    version = rt.get("version") or doc.get("version") or None
    if version in ("", "unknown"):
        version = None
    built_at = rt.get("builtAt") or doc.get("builtAt") or 0
    try:
        built_at = int(built_at)
    except (TypeError, ValueError):
        built_at = 0
    sha = rt.get("sha256") or doc.get("sha256") or None
    if sha in ("", "unknown"):
        sha = None
    return version, built_at, sha


def load_meta(apk_dir: str, abi: str, package: str) -> dict | None:
    """读 build-apk 阶段产出的 `meta-<abi>-<package>.json`。

    为什么需要它：publish-* 作业只下载 APK 工件，**没有** `assets/runtime/MANIFEST.json`
    （那是构建期生成、不入库的）。没有运行时版本就无法判断「本次更新有没有换运行时」，
    F2 的二选一弹窗就永远不出现。所以构建端把运行时事实随工件一起带出来。
    """
    path = os.path.join(apk_dir, f"meta-{abi}-{package}.json")
    if not os.path.isfile(path):
        return None
    try:
        return json.load(open(path, encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        print(f"WARN: cannot read {path}: {e}", file=sys.stderr)
        return None


def runtime_from_apk(apk_path: str) -> tuple[str | None, int, str | None]:
    """从 full APK 内嵌的 `assets/runtime/MANIFEST.json` 读运行时事实。

    为什么还要这一层：meta 文件依赖构建步骤去读清单，而构建步骤曾经读的是迁移前的
    旧路径（`app/src/main/assets/runtime/`），导致 v1.4.37 的 meta 里 `builtAt` 恒 0，
    索引里 full 包全是 `null`。APK 里那份清单是**权威来源**（Gradle 打进包的就是它），
    所以 meta 缺字段时直接开包读 —— 不依赖任何中间产物搬运动作。
    """
    try:
        with zipfile.ZipFile(apk_path) as z:
            man = json.loads(z.read("assets/runtime/MANIFEST.json"))
    except KeyError:
        print(f"WARN: {apk_path} has no assets/runtime/MANIFEST.json", file=sys.stderr)
        return None, 0, None
    except Exception as e:  # noqa: BLE001
        print(f"WARN: cannot read manifest from {apk_path}: {e}", file=sys.stderr)
        return None, 0, None
    rt = man.get("runtime") or {}
    version = rt.get("version") or man.get("version") or None
    if version in ("", "unknown"):
        version = None
    try:
        built_at = int(rt.get("builtAt") or man.get("builtAt") or 0)
    except (TypeError, ValueError):
        built_at = 0
    sha = rt.get("sha256") or man.get("sha256") or None
    if sha in ("", "unknown"):
        sha = None
    return version, built_at, sha


def collect(apk_dir: str, want_variant: str | None) -> list[dict]:
    assets = []
    for name in sorted(os.listdir(apk_dir)):
        if not name.endswith(".apk"):
            continue
        path = os.path.join(apk_dir, name)
        if not os.path.isfile(path):
            continue
        meta = parse_apk_name(name)
        if want_variant and meta["variant"] != want_variant:
            continue
        assets.append(
            {
                "abi": meta["abi"],
                "package": meta["package"],
                "variant": meta["variant"],
                "name": name,
                "size": os.path.getsize(path),
                "sha256": sha256_of(path),
            }
        )
    # 稳定排序：App 侧遇到重复格时有确定行为（取第一个并告警）
    assets.sort(key=lambda a: (a["variant"], a["abi"], a["package"], a["name"]))
    return assets


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk-dir", required=True, help="包含 *.apk 的目录")
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--version-name", required=True)
    ap.add_argument("--tag", default="")
    ap.add_argument("--channel", default="stable", choices=CHANNELS)
    ap.add_argument("--min-app-version-code", type=int, default=0)
    ap.add_argument("--runtime-manifest", default="app/build/generated/runtimeAssets/runtime/MANIFEST.json",
                    help="构建期清单（app 模块的 build 目录）；缺失时退回读 APK 内的 assets/runtime/MANIFEST.json")
    ap.add_argument("--notes-url", default="")
    ap.add_argument("--notes", default="")
    ap.add_argument("--published-at", default="")
    ap.add_argument("--only-variant", default=None, choices=VARIANTS,
                    help="只收录某一签名类型的包（edge/preview 用 debug，tag 用 release）")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    if not os.path.isdir(args.apk_dir):
        print(f"ERROR: apk dir not found: {args.apk_dir}", file=sys.stderr)
        return 2

    assets = collect(args.apk_dir, args.only_variant)
    if not assets:
        print(f"ERROR: no apk found in {args.apk_dir}", file=sys.stderr)
        return 2

    fallback_version, fallback_built_at, fallback_sha = load_runtime_meta(args.runtime_manifest)
    missing_runtime = []
    for a in assets:
        if a["package"] == "noruntime":
            # noruntime 包不含运行时 → 运行时字段全部置空，App 侧据此不参与运行时比较
            a["runtimeVersion"] = None
            a["runtimeBuiltAt"] = 0
            a["runtimeSha256"] = None
            continue
        meta = load_meta(args.apk_dir, a["abi"], a["package"])
        meta_code = (meta or {}).get("versionCode")
        if meta_code and int(meta_code) != args.version_code:
            print(
                f"ERROR: {a['name']} meta versionCode={meta_code} 与 --version-code={args.version_code} 不一致"
                "（工件与版本号不匹配，拒绝生成索引）",
                file=sys.stderr,
            )
            return 2
        version = (meta or {}).get("runtimeVersion")
        built_at = (meta or {}).get("runtimeBuiltAt") or 0
        try:
            built_at = int(built_at)
        except (TypeError, ValueError):
            built_at = 0
        sha = (meta or {}).get("runtimeSha256") or None
        if not version or not built_at or not sha:
            # meta 缺字段（构建步骤读错清单路径就会这样）→ 直接开包读权威清单。
            apk_version, apk_built_at, apk_sha = runtime_from_apk(os.path.join(args.apk_dir, a["name"]))
            version = version or apk_version
            built_at = built_at or apk_built_at
            sha = sha or apk_sha
        version = version or fallback_version
        built_at = built_at or fallback_built_at or 0
        sha = sha or fallback_sha
        a["runtimeVersion"] = version or None
        a["runtimeBuiltAt"] = built_at
        a["runtimeSha256"] = (sha or None)
        if not built_at:
            missing_runtime.append(a["name"])
    if missing_runtime:
        # 不致命：App 侧会退回比较版本字符串。但这意味着 F2 的「运行时有没有变更」
        # 判不准（可能白让用户选或白下 250MB），所以必须显式喊出来。
        print(
            "WARN: 以下 full 包缺少 runtimeBuiltAt（meta 文件与 APK 内 MANIFEST 都没读到），"
            f"App 侧会按「运行时未变更」处理: {missing_runtime}",
            file=sys.stderr,
        )

    # 每个 (variant, abi, package) 必须唯一，否则 App 侧选包会歧义
    seen = {}
    for a in assets:
        key = (a["variant"], a["abi"], a["package"])
        if key in seen:
            print(f"ERROR: duplicate asset for {key}: {seen[key]} vs {a['name']}", file=sys.stderr)
            return 2
        seen[key] = a["name"]

    doc = {
        "schema": SCHEMA,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "latest": {
            "versionCode": args.version_code,
            "versionName": args.version_name,
            "tag": args.tag,
            "channel": args.channel,
            "publishedAt": args.published_at,
            "minAppVersionCode": args.min_app_version_code,
            "rollout": 100,
            "notesUrl": args.notes_url,
            "notes": args.notes[:500],
        },
        "assets": assets,
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"wrote {args.out}: {len(assets)} asset(s), v{args.version_name} ({args.version_code})")
    for a in assets:
        print(f"  {a['variant']:<7} {a['abi']:<11} {a['package']:<9} {a['size']:>12}  {a['sha256'][:16]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())

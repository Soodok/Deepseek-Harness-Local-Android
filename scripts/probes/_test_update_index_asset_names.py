#!/usr/bin/env python3
"""产物文件名解析回归（v1.5.0）—— 试发行版本号不能让发布链路红掉。

## 为什么单独立一条探针
`scripts/write-update-index.py` 的 `parse_apk_name()` 从产物文件名反解
(abi, package, variant)。文件名里的**版本段有两种位置**：

- tag 构建：`dsh-android-v1.5.0-beta-arm64-v8a-release-noruntime.apk`
  （`ref` 本身就是 `v<versionName>`，版本段在 abi 左边）
- push 构建：`dsh-android-main-arm64-v8a-v1.5.0-beta-release-noruntime.apk`
  （`ref=main`，版本段在 abi 右边）

v1.5.0 把 `versionName` 改成 `1.5.0-beta` 后，旧正则 `-v\\d+(\\.\\d+)*$` 只认
`-v1.5.0` 这种纯数字版本段，带预发布标识的剥不掉 → `cannot parse abi` →
**publish-edge 在「生成并签名 update-index.json」这步整条红**（真机上已复现：
main 分支 run 41181800850 的 publish-edge 失败）。而 tag 构建因为版本段在
abi 左边、且 `re.sub` 只从末尾剥，反而侥幸通过 —— 这正是"只有 main 红、
tag 绿"这种难查表现的来源。

本探针用真实文件名钉住两种位置 × 预发布标识的解析结果，并覆盖原有形态防回归。

运行：python scripts/probes/_test_update_index_asset_names.py
"""
from __future__ import annotations

import importlib.util
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WRITER = os.path.join(ROOT, "scripts/write-update-index.py")

failures: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    if cond:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}{(' — ' + detail) if detail else ''}")
        failures.append(name)


def load_writer():
    spec = importlib.util.spec_from_file_location("write_update_index", WRITER)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main() -> int:
    m = load_writer()

    # (文件名, 期望的 (abi, package, variant))
    cases = {
        # v1.5.0 试发行：tag 构建（版本段在 abi 左边）
        "dsh-android-v1.5.0-beta-arm64-v8a-release-noruntime.apk": ("arm64-v8a", "noruntime", "release"),
        "dsh-android-v1.5.0-beta-x86_64-debug.apk": ("x86_64", "full", "debug"),
        "dsh-android-v1.5.0-rc.1-x86_64-debug.apk": ("x86_64", "full", "debug"),
        # v1.5.0 试发行：push main 构建（版本段在 abi 右边）—— 就是红掉的那批
        "dsh-android-main-arm64-v8a-v1.5.0-beta-debug-noruntime.apk": ("arm64-v8a", "noruntime", "debug"),
        "dsh-android-main-arm64-v8a-v1.5.0-beta-release-noruntime.apk": ("arm64-v8a", "noruntime", "release"),
        "dsh-android-main-x86_64-v1.5.0-beta-debug-noruntime.apk": ("x86_64", "noruntime", "debug"),
        "dsh-android-main-x86_64-v1.5.0-beta-release-noruntime.apk": ("x86_64", "noruntime", "release"),
        "dsh-android-main-arm64-v8a-v1.5.0-beta.2-debug.apk": ("arm64-v8a", "full", "debug"),
        # 原有形态必须继续可用（防回归）
        "dsh-android-v1.4.76-arm64-v8a-release.apk": ("arm64-v8a", "full", "release"),
        "dsh-android-main-arm64-v8a-v1.4.76-release-noruntime.apk": ("arm64-v8a", "noruntime", "release"),
        "dsh-android-edge-x86_64-debug-noruntime.apk": ("x86_64", "noruntime", "debug"),
        "dsh-android-preview-armeabi-v7a-v1.4.9-release.apk": ("armeabi-v7a", "full", "release"),
        "dsh-android-10-beta-x86_64-debug.apk": ("x86_64", "full", "debug"),
    }

    print("== 1. 产物名解析：预发布版本段（两种位置） ==")
    for name, expect in cases.items():
        try:
            got = m.parse_apk_name(name)
            actual = (got["abi"], got["package"], got["variant"])
            check(f"{name} -> {expect}", actual == expect, f"got {actual}")
        except Exception as e:  # noqa: BLE001
            check(f"{name} -> {expect}", False, f"{type(e).__name__}: {e}")

    print("== 2. 版本段判定不得吃掉 ABI 名 ==")
    # `arm64-v8a` 与预发布标识字符集重叠：解析器必须靠「末尾是否为已知 ABI」区分，
    # 不能把 `-v8a` 当成版本段。
    check(
        "已知 ABI 结尾时不剥版本段",
        "_ends_with_abi" in open(WRITER, encoding="utf-8").read(),
        "v1.5.0 前的实现会在 arm64-v8a 上误剥",
    )
    got = m.parse_apk_name("dsh-android-main-arm64-v8a-v1.5.0-beta-release.apk")
    check("arm64-v8a 未被误剥", got["abi"] == "arm64-v8a", f"got {got['abi']}")

    print("== 3. 非法名必须显式报错（不静默产出错资产） ==")
    for bad in ("dsh-android-main-v1.5.0-beta.apk", "dsh-android-x86_64-release.doc"):
        try:
            m.parse_apk_name(bad)
            check(f"{bad} 被拒绝", False, "没有抛错")
        except ValueError:
            check(f"{bad} 被拒绝", True)

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print(f"All asset-name checks passed ({len(cases)} cases).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

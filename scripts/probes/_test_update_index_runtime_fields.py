#!/usr/bin/env python3
"""update-index 的运行时字段：不能是 null，也不能被读成字符串 "null"（v1.4.37）。

## 为什么要这条探针

v1.4.37 的 tag 发布索引里，**每个** asset 的 `runtimeVersion` 都是 null、
`runtimeBuiltAt` 都是 0。链路是这样断的：

1. `build-apk` 的 meta 步骤去读 `app/src/main/assets/runtime/MANIFEST.json`——
   这是迁走前的旧路径，清单现在在 `build/generated/runtimeAssets/runtime/`，
   于是 `-f` 恒假 → `meta-<abi>-<package>.json` 里 builtAt=0；
2. `write-update-index.py` 见 meta 没数，退回 `--runtime-manifest`（同样旧路径，
   publish 作业里也不存在）→ 索引写出 null；
3. App 侧 `JSONObject.optString("runtimeVersion")` 对 JSON null 返回字面量
   `"null"`，`isNullOrBlank()` 判不过 → `runtimeChanged()` 认定"运行时变了"：
   full 用户每次更新都会被问要不要下 150MB 大包（白下 + 白解压 580MB）。

修法是三处一起：构建步骤改读新路径、发布脚本在 meta 缺字段时**开 APK 读权威清单**、
App 侧把 JSON null 归一化成"没有"。这条探针锁住后两处（构建步骤由
`_test_ci_native_components.py` 之外的 workflow 断言覆盖，见 §4）。

运行：python scripts/probes/_test_update_index_runtime_fields.py
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, "scripts/write-update-index.py")
MODEL = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdate.kt")
WF = os.path.join(ROOT, ".github/workflows/android-build.yml")

failures: list[str] = []


def check(name: str, got, want) -> None:
    if got == want:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name} — got {got!r}, want {want!r}")
        failures.append(name)


def make_apk(path: str, name: str, manifest: dict | None) -> None:
    """造一个只有清单的假 APK（索引脚本只读清单，不校验签名/二进制）。"""
    with zipfile.ZipFile(path, "w") as z:
        if manifest is not None:
            z.writestr("assets/runtime/MANIFEST.json", json.dumps(manifest))
        z.writestr("AndroidManifest.xml", b"\x00")
    os.rename(path, os.path.join(os.path.dirname(path), name))


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="dsh-index-probe-")
    try:
        apk_dir = os.path.join(tmp, "apk")
        os.makedirs(apk_dir)
        built_at = 1761667200000
        man = {"runtime": {"bundled": True, "version": "2026.10.28-eb83a614", "builtAt": built_at}}

        full = os.path.join(apk_dir, "_full.zip")
        make_apk(full, "dsh-android-v9.9.9-x86_64-release.apk", man)
        norun = os.path.join(apk_dir, "_norun.zip")
        make_apk(norun, "dsh-android-v9.9.9-x86_64-release-noruntime.apk", None)
        # meta 里 builtAt=0 / runtimeVersion=null：复刻 v1.4.37 事故现场
        for pkg in ("full", "noruntime"):
            json.dump(
                {"versionCode": 999, "versionName": "9.9.9", "abi": "x86_64",
                 "package": pkg, "runtimeVersion": None, "runtimeBuiltAt": 0},
                open(os.path.join(apk_dir, f"meta-x86_64-{pkg}.json"), "w"),
            )

        out = os.path.join(tmp, "update-index.json")
        r = subprocess.run(
            [sys.executable, SCRIPT, "--apk-dir", apk_dir, "--version-code", "999",
             "--version-name", "9.9.9", "--tag", "v9.9.9", "--channel", "stable",
             "--out", out],
            capture_output=True, text=True,
        )
        check("索引脚本退出码", r.returncode, 0)
        if r.returncode != 0:
            print(r.stdout[-2000:], r.stderr[-2000:])
            return 1
        doc = json.load(open(out, encoding="utf-8"))
        by_pkg = {a["package"]: a for a in doc["assets"]}

        print("\n[1] meta 缺字段时，full 包要能从 APK 内的清单补上")
        check("full.runtimeVersion", by_pkg["full"]["runtimeVersion"], "2026.10.28-eb83a614")
        check("full.runtimeBuiltAt", by_pkg["full"]["runtimeBuiltAt"], built_at)
        check("WARN 不应再出现（已从 APK 补齐）", "缺少 runtimeBuiltAt" in r.stderr, False)

        print("\n[2] noruntime 包保持为空（不含运行时，不参与比较）")
        check("noruntime.runtimeVersion", by_pkg["noruntime"]["runtimeVersion"], None)
        check("noruntime.runtimeBuiltAt", by_pkg["noruntime"]["runtimeBuiltAt"], 0)

        print("\n[3] App 侧把 JSON null 归一化（否则拿到字符串 \"null\"）")
        kt = open(MODEL, encoding="utf-8").read()
        check("runtimeVersion 走 optStringOrNull", 'optStringOrNull("runtimeVersion")' in kt, True)
        check("不再直接用 optString 取值",
              bool(re.search(r'runtimeVersion\s*=\s*o\.optString\("runtimeVersion"\)', kt)), False)
        check("归一化函数处理 isNull", "isNull(key)" in kt, True)
        check("归一化函数吞掉字面量 null", 'it != "null"' in kt, True)

        print("\n[4] 构建步骤读的清单路径必须是生成目录")
        wf = open(WF, encoding="utf-8").read()
        check("meta 步骤用生成路径", "build/generated/runtimeAssets/runtime/MANIFEST.json" in wf, True)
        check("meta 步骤不再用旧路径",
              "test -f app/src/main/assets/runtime/MANIFEST.json" in wf, False)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print(f"\n{len(failures)} 项失败：{', '.join(failures)}")
        return 1
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())

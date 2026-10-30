#!/usr/bin/env python3
"""护栏：`files/engine/.runtime.json` 必须记录「实际装的那份运行时」。

背景（v1.4.38）
-------------
v1.4.37 真机验收时发现设备上的戳是：

    {"version":"unknown","builtAt":0,"sha256":"","source":"download"}

看着像「索引没带版本」，其实是 App 自己写错了：`RuntimeInstaller.install()`
从头到尾用 `manifest.runtime`，而 noruntime 包的 MANIFEST 里 `runtime` 段是
**占位值**（构建期没有运行时内容，version 恒为 "unknown"、builtAt=0、sha 为空）。
真实事实只存在于运行时签名索引的条目里，而那条路径当时只用来取 sha 和 urls。

连带后果有三条，都是静默的：

1. `UpdateAssetPicker.runtimeChanged()` 拿 "unknown" 和索引里的真版本比 →
   永远"变了" → full 包用户每次升级都被劝去多下 ~130MB；
2. `ensureInstalled()` 里「云端已更新的运行时别被旧 APK 降级」靠
   `installed.builtAt > manifest.runtime.builtAt`，而 installed.builtAt=0 →
   保护失效，full 包启动会把云端更新的运行时打回包内版本；
3. 诊断/关于页的运行时版本显示为 unknown。

本探针只看源码事实（真机事实由验收脚本另测），守护三件事：
  · 下载分支把索引条目回填进 `info`；
  · 写戳/写换装日志用的都是 `info`，不是 `manifest.runtime`；
  · 写戳与读戳的键名一致（改一边忘另一边 = 静默变 unknown）。
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INSTALLER = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/RuntimeInstaller.kt")
INDEX_CLIENT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/RuntimeIndexClient.kt")
CHECKER = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdateChecker.kt")
MANIFEST_PY = os.path.join(ROOT, "scripts/write-runtime-manifest.py")
GRADLE_KTS = os.path.join(ROOT, "app/build.gradle.kts")
WORKFLOW = os.path.join(ROOT, ".github/workflows/android-build.yml")
SCRATCH = os.path.join(ROOT, "build/.scratch/probe-runtime-stamp")

failures: list[str] = []


def check(name: str, got, want) -> None:
    if got == want:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name} — got {got!r}, want {want!r}")
        failures.append(name)


def read(path: str) -> str:
    return open(path, encoding="utf-8").read()


def install_body(src: str) -> str:
    """截出 `private fun install(` 到 `private fun writeStamp(` 之间的函数体。"""
    start = src.index("private fun install(")
    end = src.index("private fun writeStamp(")
    return src[start:end]


def main() -> int:
    src = read(INSTALLER)
    body = install_body(src)

    print("== 1. 下载分支把索引条目回填进 info ==")
    check("install 的 info 是 var（能被回填）", "var info = manifest.runtime" in body, True)
    check("不再有 val info = manifest.runtime", "val info = manifest.runtime" in body, False)
    check("拿到索引条目后 copy 出新的 info", "info = info.copy(" in body, True)
    # 事实字段必须一起回填：只补 sha 不补 version 等于没修
    copy_block = ""
    if "info = info.copy(" in body:
        rest = body[body.index("info = info.copy("):]
        # 结尾是缩进对齐的 `)` 行（`takeIf { ... }` 里也有右括号，不能截到第一个 `)`）
        m = re.search(r"\n[ \t]*\)\s*\n", rest)
        copy_block = rest[: m.end()] if m else rest[:800]
    for field, why in [
        ("version = entry.version", "标识"),
        ("builtAt = entry.builtAt", "新旧"),
        ("sha256 = entry.sha256", "校验和"),
        ("size = entry.size", "体积"),
        ("unpackedBytes = entry.unpackedBytes", "解压体积/空间预检"),
    ]:
        check(f"回填 {field.split(' =')[0]}（{why}）", field in copy_block, True)
    # 回填必须在取 pinned/urls 的同一个 entry 分支里，否则 entry 为 null 时 NPE 风险
    check(
        "回填处于 entry != null 分支内",
        re.search(r"if \(entry != null\) \{[^}]*?info = info\.copy\(", body, re.S) is not None,
        True,
    )

    print("== 2. 写戳与换装日志都用 info，不用 manifest.runtime ==")
    check("writeStamp 收到 info", "writeStamp(info, bundled)" in body, True)
    check("没有 writeStamp(manifest.runtime", "writeStamp(manifest.runtime" in body, False)
    check("换装日志 version 取 info.version", "version = info.version," in body, True)
    check("换装日志 builtAt 取 info.builtAt", "builtAt = info.builtAt," in body, True)
    # 占位值不该被当成事实传播到日志之外的地方（注释里提到它不算）
    code_lines = [
        ln for ln in body.splitlines()
        if "manifest.runtime" in ln and not ln.strip().startswith("//")
    ]
    check(
        "install 内只有 var info = manifest.runtime 这一处引用",
        len(code_lines),
        1,
    )
    check("那一处是 var 声明", code_lines[0].strip() if code_lines else "", "var info = manifest.runtime")

    print("== 3. 索引客户端认得占位值（不回填的前提） ==")
    idx = read(INDEX_CLIENT)
    check('resolve 放过 exactVersion == "unknown"', 'exactVersion != "unknown"' in idx, True)
    check("Entry 带 version", re.search(r"data class Entry\([^)]*val version", idx, re.S) is not None, True)
    check("Entry 带 builtAt", re.search(r"data class Entry\([^)]*val builtAt", idx, re.S) is not None, True)
    check("Entry 带 sha256", re.search(r"data class Entry\([^)]*val sha256", idx, re.S) is not None, True)

    print("== 4. 写戳 / 读戳键名一致（改一边忘另一边 = 静默 unknown） ==")
    # writeStamp 里 put("x") 的键
    written = set(re.findall(r'\.put\("([a-zA-Z0-9_]+)"', src))
    consumed = set(re.findall(r'optString\("([a-zA-Z0-9_]+)"|optLong\("([a-zA-Z0-9_]+)"', read(CHECKER)))
    consumed = {a or b for a, b in consumed}
    for key in ("version", "builtAt"):
        check(f"写戳含 {key}", key in written, True)
        check(f"读戳认 {key}", key in consumed, True)
    # sha256 只写入、当前无人消费（供诊断/核对），所以不要求读侧出现
    check("写戳含 sha256（诊断/核对用）", "sha256" in written, True)

    print("== 5. 戳的语义：unknown 只能来自「没安装」，不能来自「装完了」 ==")
    check(
        "writeStamp 用 info.version（不是硬编码 unknown）",
        'put("version", info.version)' in src,
        True,
    )
    check(
        "旧文本戳同源",
        "stampFile.writeText(info.version)" in src,
        True,
    )

    print("== 6. 构建链：noruntime 只是不打运行时，身份必须打进去 ==")
    man_py = read(MANIFEST_PY)
    check("脚本接受 --runtime-built-at", '"--runtime-built-at"' in man_py, True)
    check("noruntime 分支不再写死 builtAt=0", '"builtAt": 0,' not in man_py, True)
    check("noruntime 的 builtAt 来自参数", '"builtAt": int(args.runtime_built_at),' in man_py, True)
    check(
        "full 分支参数优先于 BUILD.json",
        'int(args.runtime_built_at or build.get("builtAt")' in man_py,
        True,
    )
    # noruntime 的 sha 只能来自待发布的那一份，不能拿本地 zip 充数（App 会拿它 pin 校验）
    check(
        "noruntime 不在缺事实时猜 sha",
        "if not args.unpinned and args.runtime_sha256:" in man_py,
        True,
    )

    gradle = read(GRADLE_KTS)
    check("Gradle 声明 runtimeBuiltAt 属性", 'findProperty("runtimeBuiltAt")' in gradle, True)
    check("Gradle 透传 --runtime-built-at", '"--runtime-built-at"' in gradle, True)

    wf = read(WORKFLOW)
    check("CI 从 runtime-meta 读 builtAt", "'.builtAt // 0'" in wf, True)
    check('CI 把 builtAt 传给 Gradle', '-PruntimeBuiltAt="$RBUILT"' in wf, True)
    check(
        "CI 清单路径带 app/ 前缀（模块内 build 目录）",
        'MAN="app/build/generated/runtimeAssets/runtime/MANIFEST.json"' in wf,
        True,
    )
    check(
        "索引字段仍只对 full 取（noruntime 语义上是空）",
        'if [ "${{ matrix.package }}" = "full" ] && [ -f "$MAN" ]' in wf,
        True,
    )

    print("== 7. 行为验证：真跑清单脚本（CI 参数 / 无事实两路） ==")
    import json
    import subprocess

    os.makedirs(SCRATCH, exist_ok=True)
    base = [
        sys.executable,
        MANIFEST_PY,
        "--abi", "x86_64",
        "--repo", "XiaoPb/Deepseek-Harness-Local-Android",
        "--runtime-url-base",
        "https://github.com/XiaoPb/Deepseek-Harness-Local-Android/releases/download/v1.4.37",
    ]
    pinned = os.path.join(SCRATCH, "noruntime-pinned.json")
    rc = subprocess.run(
        base + [
            "--out", pinned, "--package", "noruntime",
            "--runtime-version", "2026.10.28-7432d79c",
            "--runtime-built-at", "1793181069",
            "--runtime-sha256", "d9" * 32,
            "--runtime-size", "133280200",
            "--runtime-unpacked", "425959317",
        ],
        capture_output=True, text=True,
    )
    check("带事实时脚本退出 0", rc.returncode, 0)
    got = json.load(open(pinned, encoding="utf-8"))["runtime"] if rc.returncode == 0 else {}
    check("产物 version = 真实版本", got.get("version"), "2026.10.28-7432d79c")
    check("产物 builtAt = 真实时间戳", got.get("builtAt"), 1793181069)
    check("产物 bundled = false", got.get("bundled"), False)
    check("产物带 sha256", bool(got.get("sha256")), True)
    check("产物带 size", got.get("size"), 133280200)
    check("产物带 unpackedBytes", got.get("unpackedBytes"), 425959317)
    check("产物带下载地址（直连 + 代理）", len(got.get("urls") or []), 2)

    bare = os.path.join(SCRATCH, "noruntime-bare.json")
    rc2 = subprocess.run(base + ["--out", bare, "--package", "noruntime"], capture_output=True, text=True)
    check("无事实时脚本仍退出 0", rc2.returncode, 0)
    got2 = json.load(open(bare, encoding="utf-8"))["runtime"] if rc2.returncode == 0 else {}
    check("无事实时 version=unknown（不编造）", got2.get("version"), "unknown")
    check("无事实时 builtAt=0（不编造）", got2.get("builtAt"), 0)
    check("无事实时不写 sha", got2.get("sha256"), None)

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print("All runtime-stamp checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

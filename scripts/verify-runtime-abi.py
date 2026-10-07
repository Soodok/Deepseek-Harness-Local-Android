#!/usr/bin/env python3
"""APK 运行时架构校验（v1.2.97 事故防线）。

## 为什么需要这个脚本
2026-10 事故：`assets/runtime.zip` 曾被替换成为模拟器收集的 **x86_64** 闭包，
而 APK 本体（lib/arm64-v8a/libdshpty.so）仍是 arm64 —— 构建、签名、发布全程无人
察觉，直到真机上 `node` 无法执行（`not executable: 64-bit ELF file`），引擎陷入
无限崩溃重启。用户从 v1.2.27 升级到 v1.2.56 / v1.2.95 全部无法启动。

只看 APK 里的 `lib/<abi>/*.so` 是查不出来的（那只是壳），必须查 **runtime.zip 里
的 bin/node 本体**。本脚本同时校验两者，不一致即失败退出。

## 用法
    python scripts/verify-runtime-abi.py <apk-or-runtime.zip> [arm64-v8a|x86_64]

不给期望架构时只做「APK 壳架构 vs runtime 架构」一致性检查；
给出时额外校验与期望值一致（发布前建议显式给）。

退出码：0 = 一致；1 = 不一致（并打印明确原因）。
"""
import sys
import zipfile

EM = {0xB7: "arm64-v8a", 0x3E: "x86_64", 0x28: "armeabi-v7a", 0x03: "x86"}

# APK 里 lib/<abi> 目录名 → e_machine
APK_ABI_EM = {"arm64-v8a": 0xB7, "x86_64": 0x3E, "armeabi-v7a": 0x28, "x86": 0x03}


def elf_machine(data: bytes) -> int:
    """ELF header 的 e_machine（偏移 18，2 字节小端）。"""
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return -1
    return int.from_bytes(data[18:20], "little")


def node_machine(runtime_zip: zipfile.ZipFile) -> int:
    with runtime_zip.open("bin/node") as f:
        return elf_machine(f.read(20))


def check_apk(path: str, expect_abi: str | None) -> int:
    z = zipfile.ZipFile(path)

    # 1) APK 原生库架构（壳）
    apk_abis = {
        n.split("/")[1]
        for n in z.namelist()
        if n.startswith("lib/") and n.count("/") >= 2
    }
    apk_abis = {a for a in apk_abis if a in APK_ABI_EM}
    print(f"APK 原生库架构: {sorted(apk_abis) or '(无)'}")

    # 2) runtime.zip 里的 node 架构（真正的引擎）
    if "assets/runtime.zip" not in z.namelist():
        print("!! APK 内没有 assets/runtime.zip")
        return 1
    import io
    with z.open("assets/runtime.zip") as f:
        inner = zipfile.ZipFile(io.BytesIO(f.read()))
    m = node_machine(inner)
    rt_abi = EM.get(m, f"unknown(0x{m:x})")
    print(f"runtime.zip 内 bin/node 架构: {rt_abi}")

    # 3) manifest 自述（仅信息）
    try:
        import json
        if "assets/runtime/MANIFEST.json" in z.namelist():
            man = json.loads(z.read("assets/runtime/MANIFEST.json"))
            print(f"MANIFEST version: {man.get('version')}")
    except Exception:
        pass

    ok = True
    # 一致性：壳与引擎必须同架构
    if apk_abis and m not in (-1,):
        shell_ok = any(APK_ABI_EM[a] == m for a in apk_abis)
        if not shell_ok:
            print(f"!! 不一致：APK 壳为 {sorted(apk_abis)}，但 runtime 内 node 是 {rt_abi}")
            ok = False
    # 期望值
    if expect_abi:
        want = APK_ABI_EM.get(expect_abi)
        if want is None:
            print(f"!! 未知期望架构: {expect_abi}")
            return 1
        if m != want:
            print(f"!! 与期望不符：期望 {expect_abi}，实际 {rt_abi}")
            ok = False

    print("== 通过 ==" if ok else "== 失败 ==")
    return 0 if ok else 1


def check_runtime(path: str, expect_abi: str | None) -> int:
    inner = zipfile.ZipFile(path)
    m = node_machine(inner)
    rt_abi = EM.get(m, f"unknown(0x{m:x})")
    print(f"runtime.zip 内 bin/node 架构: {rt_abi}")
    if expect_abi and APK_ABI_EM.get(expect_abi) != m:
        print(f"!! 与期望不符：期望 {expect_abi}，实际 {rt_abi}")
        return 1
    print("== 通过 ==")
    return 0


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    expect = sys.argv[2] if len(sys.argv) > 2 else None
    if path.lower().endswith(".apk"):
        return check_apk(path, expect)
    return check_runtime(path, expect)


if __name__ == "__main__":
    sys.exit(main())

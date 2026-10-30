#!/usr/bin/env python3
"""CI 预装 native 组件（CMake / NDK）的版本一致性与落地校验（v1.4.37）。

## 为什么要这条探针

v1.4.37 的 tag 构建（run 40728859646）里，`build-apk (aarch64, arm64-v8a, noruntime)`
挂在 configure 阶段：

    Preparing "Install NDK (Side by side) 26.1.10909125"
    Failed to install the following SDK components: ndk;26.1.10909125
    Caused by: java.util.zip.ZipException: Archive is not a ZIP archive

同批 x86_64 lane、同代码全绿 —— 典型的 runner 网络抖动把 NDK 包下坏了。同类事故
在 v1.4.25 已经用 CMake 3.22.1 踩过一次（"Error on ZipFile unknown archive"），
当时的修法是"提前装 + 重试"，但**只覆盖了 CMake**，NDK 依然在构建中现场拉取。

这条探针锁住修法不退化：

 1. **版本号单一来源**：`app/build.gradle.kts` 写死 `ndkVersion`；CI 的两个
    `NDK_VERSION` env 必须与它相等。以前靠 AGP 默认值，CI 也就没法"按版本号
    预装"，两边一旦漂移就是又一次现场下载。
 2. **预装必须带重试 + 清残留**：损坏包不清掉，重试只会继续沿用坏包。
 3. **就绪判定看落地文件**：`sdkmanager` 有时退出码 0 但内容缺件，
    所以必须查 `ndk/<v>/source.properties` 与 clang 目录。
 4. **磁盘清理别整块删 ndk**：整目录删掉等于强制每个 lane 重下 ~650MB，
    把"腾空间"变成了新的抖动源；只删非钉住版本。
 5. **CMake 版本两处一致**：`externalNativeBuild.cmake.version` 与 CI 预装的
    `CMAKE_VERSION` 不一致时，Gradle 会再装一个，预装就白做了。

运行：python scripts/probes/_test_ci_native_components.py
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GRADLE = os.path.join(ROOT, "app/build.gradle.kts")
WF = os.path.join(ROOT, ".github/workflows/android-build.yml")

failures: list[str] = []


def check(name: str, got, want) -> None:
    if got == want:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name} — got {got!r}, want {want!r}")
        failures.append(name)


def step_block(wf_text: str, name_prefix: str) -> str:
    """按 `- name:` 切出单个 step 的 run 块（不引入 yaml 依赖）。"""
    m = re.search(rf"^\s*- name: {re.escape(name_prefix)}.*?$", wf_text, re.M)
    if not m:
        return ""
    rest = wf_text[m.end():]
    nxt = re.search(r"^\s*- (name|uses):", rest, re.M)
    return rest[: nxt.start()] if nxt else rest


def main() -> int:
    gradle = open(GRADLE, encoding="utf-8").read()
    wf = open(WF, encoding="utf-8").read()

    print("[1] 版本号单一来源")
    m = re.search(r'^\s*ndkVersion\s*=\s*"([^"]+)"', gradle, re.M)
    check("build.gradle.kts 显式声明 ndkVersion", bool(m), True)
    ndk_pin = m.group(1) if m else "<none>"
    env_ndk = re.findall(r'^\s*NDK_VERSION:\s*"([^"]+)"', wf, re.M)
    check("workflow 里的 NDK_VERSION 数量", len(env_ndk), 2)  # 磁盘清理 + 预装
    check("NDK_VERSION 与 ndkVersion 一致", sorted(set(env_ndk)), [ndk_pin])

    m = re.search(r'externalNativeBuild\s*\{[^}]*?version\s*=\s*"([^"]+)"', gradle, re.S)
    check("build.gradle.kts 声明 cmake version", bool(m), True)
    cmake_pin = m.group(1) if m else "<none>"
    env_cmake = re.findall(r'^\s*CMAKE_VERSION:\s*"([^"]+)"', wf, re.M)
    check("CMAKE_VERSION 与 cmake.version 一致", sorted(set(env_cmake)), [cmake_pin])

    print("\n[2] 预装步骤：重试 + 清残留 + 落地校验")
    pre = step_block(wf, "预装 native 组件")
    check("预装步骤存在", bool(pre), True)
    check("预装覆盖 cmake", '"cmake;$CMAKE_VERSION"' in pre, True)
    check("预装覆盖 ndk", '"ndk;$NDK_VERSION"' in pre, True)
    check("每次尝试前清残留", 'purge "$PKG"' in pre, True)
    check("重试有上限", bool(re.search(r"for i in 1 2 3 4;", pre)), True)
    check("失败要打 ::error:: 并退出", "::error::failed to install" in pre, True)
    check(
        "就绪判定看落地文件（NDK）",
        "source.properties" in pre and "toolchains/llvm/prebuilt" in pre,
        True,
    )
    check("就绪判定看落地文件（CMake）", 'cmake/$CMAKE_VERSION/bin/cmake' in pre, True)

    print("\n[3] 磁盘清理不整块删 ndk")
    clean = step_block(wf, "释放磁盘并报告可用空间")
    check("清理步骤存在", bool(clean), True)
    blanket = re.search(r"rm -rf\s+(?:[^\n]*\s)?[^\s]*sdk/ndk(?:\s|$)", clean, re.M)
    check("没有整目录 rm sdk/ndk", blanket is None, True)
    check("保留钉住的 NDK 版本", '! -name "$NDK_VERSION"' in clean, True)

    print("\n[4] 步骤顺序：预装必须在构建之前")
    i_pre = wf.find("- name: 预装 native 组件")
    i_build = wf.find("- name: 构建 APK")
    check("预装早于构建", 0 < i_pre < i_build, True)

    if failures:
        print(f"\n{len(failures)} 项失败：{', '.join(failures)}")
        return 1
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())

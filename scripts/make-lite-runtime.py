#!/usr/bin/env python3
"""生成「轻量版」runtime：从完整 runtime.zip 中剥离可选的重量级组件。

## 为什么需要
完整包 230MB，其中 **145MB 是 LibreOffice WASM**（`soffice.wasm` 121.9MB +
`soffice.data` 22MB）。它只服务于一件事：**把 .docx/.xlsx/.pptx 转成 PDF 以便在
侧边栏预览**。

关键是这跟「PDF 预览」无关 —— 侧边栏的 PDF 渲染是浏览器原生实现的
（`dsh-client-ui-sidebar-documentpreview` 里没有任何 libreoffice 引用，只有 pdf
引用）。也就是说，剥掉 LibreOffice 之后：

  · 打开 .pdf 文件 —— 照常预览（浏览器自带渲染器）；
  · 打开 .docx/.xlsx/.pptx —— 显示上游原本就写好的降级文案
    「Office 预览不可用」，而不是崩溃。上游为「没有转换服务的主机」准备了
    这条路径（`officePreview.unavailable` 文案就是这个用途）；
  · 让 AI 读取/转换 Office 文件 —— 仍然可以，走 shell 工具或扩展；
  · 用户想恢复 —— 装完整版即可，会话与配置通用。

## 剥离清单（每一项都注明依据）

| 目标 | 大小 | 依据 |
|---|---|---|
| `libreoffice-kit-wasm` | 145.3 MB | 仅 office→pdf 转换用；PDF 预览不依赖它 |
| `libreoffice-kit` | 少量 | 同上（createConverter 的实现） |
| `dsh-experimental-inspector` | 22.3 MB | 开发者的 Chrome DevTools 面板；普通用户无入口 |

## 安全边界

只删「包目录」与「指向它们的依赖声明」两处：
  · 不删任何 `@deepseek-ai/dsh-*` 核心包；
  · 不删 `bin/`、`lib/*.so`（Node 与 bash 运行时）；
  · 每删一个包，同步从依赖它的 package.json 里移除 dependency 条目，
    否则引擎的 profile 解析会因为「声明了但文件不在」而报错。

任何一步形态不符 → 非零退出，绝不产出半残的包。

## 用法
    python scripts/make-lite-runtime.py <完整 runtime.zip> <输出 lite.zip>
    python scripts/make-lite-runtime.py <runtime.zip> <lite.zip> --backup-dir _runtime_bak
"""
import argparse
import io
import json
import os
import shutil
import sys
import zipfile

# 要剥离的包（相对 lib/node_modules/ 的路径前缀）
STRIP_PREFIXES = [
    # LibreOffice WASM：office→pdf 转换专用，145MB
    "lib/node_modules/@deepseek-ai/libreoffice-kit-wasm/",
    "lib/node_modules/@deepseek-ai/libreoffice-kit/",
    # 开发者工具面板：22MB
    "lib/node_modules/@deepseek-ai/dsh-experimental-inspector/",
]

# 从这些 package.json 里移除对被剥离包的依赖声明
#
# 0.2.2-rc.3 起上游把 office-to-pdf 换成了 office-kit（转换实现搬到
# @deepseek-ai/libreoffice-kit，由 office-kit 在**首次转换时**才 import）。
# 旧名字留着是死项；新名字不加，lite 包里 office-kit 仍声明依赖一个已被
# 删除的包 —— 运行时不一定会炸，但依赖校验与后续 plugin add 会报缺包。
DEPENDENCY_HOSTS = [
    "lib/node_modules/@deepseek-ai/dsh-office-kit/package.json",
    "lib/node_modules/@deepseek-ai/dsh-office-to-pdf/package.json",  # rc.3 之前
    "lib/node_modules/@deepseek-ai/dsh-skill-office/package.json",
    "lib/node_modules/@deepseek-ai/dsh-experimental-inspector-profile/package.json",
]

# 被剥离的包名（用于清理 dependencies 字段）
STRIPPED_PACKAGES = [
    "@deepseek-ai/libreoffice-kit-wasm",
    "@deepseek-ai/libreoffice-kit",
    "@deepseek-ai/dsh-experimental-inspector",
]


def should_strip(name: str) -> bool:
    return any(name.startswith(p) for p in STRIP_PREFIXES)


def patch_package_json(data: bytes, path: str) -> tuple[bytes, bool]:
    """从 package.json 里移除指向被剥离包的依赖。返回 (新内容, 是否改动)。"""
    try:
        doc = json.loads(data.decode("utf-8"))
    except Exception:
        return data, False

    changed = False
    for field in ("dependencies", "peerDependencies", "optionalDependencies"):
        deps = doc.get(field)
        if not isinstance(deps, dict):
            continue
        for pkg in STRIPPED_PACKAGES:
            if pkg in deps:
                del deps[pkg]
                changed = True
                print(f"    {path}: 移除 {field}[\"{pkg}\"]")

    if not changed:
        return data, False
    return json.dumps(doc, indent=2, ensure_ascii=False).encode("utf-8"), True


def build(src: str, dst: str) -> None:
    with zipfile.ZipFile(src, "r") as zin:
        total = len(zin.infolist())
        stripped_files = 0
        stripped_bytes = 0
        kept: list[tuple[zipfile.ZipInfo, bytes | None]] = []

        for info in zin.infolist():
            name = info.filename
            if should_strip(name):
                stripped_files += 1
                stripped_bytes += info.file_size
                continue
            kept.append((info, None))

        if stripped_files == 0:
            raise SystemExit(
                "错误：一个包都没匹配到 —— 上游目录结构变了？中止，避免产出与预期不符的包"
            )

        print(
            f"  剥离 {stripped_files} 个文件，"
            f"{stripped_bytes / 1048576:.1f} MB（解压后）"
        )

        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
            patched = 0
            for info, _ in kept:
                data = zin.read(info.filename)
                if info.filename in DEPENDENCY_HOSTS:
                    new_data, changed = patch_package_json(data, info.filename)
                    if changed:
                        data = new_data
                        patched += 1
                # 保留原压缩方式与时间戳语义（writestr 用 ZipInfo 复制元数据）
                out_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                out_info.compress_type = zipfile.ZIP_DEFLATED
                out_info.external_attr = info.external_attr
                zout.writestr(out_info, data)

        if patched == 0:
            raise SystemExit(
                "错误：没有任何 package.json 被清理 —— "
                "依赖声明可能已变，剥离后会导致 profile 解析失败。中止。"
            )
        print(f"  清理了 {patched} 个 package.json 的依赖声明")

    src_mb = os.path.getsize(src) / 1048576
    dst_mb = os.path.getsize(dst) / 1048576
    print(
        f"  完成：{src_mb:.1f} MB → {dst_mb:.1f} MB "
        f"（省 {src_mb - dst_mb:.1f} MB，{100 * (src_mb - dst_mb) / src_mb:.1f}%）"
    )


def main() -> int:
    ap = argparse.ArgumentParser(description="生成轻量版 runtime（剥离 LibreOffice 与 inspector）")
    ap.add_argument("src", help="完整 runtime.zip")
    ap.add_argument("dst", help="输出 lite runtime.zip")
    ap.add_argument("--backup-dir", help="可选：先备份源文件到该目录")
    args = ap.parse_args()

    if not os.path.isfile(args.src):
        raise SystemExit(f"错误：找不到 {args.src}")
    if os.path.abspath(args.src) == os.path.abspath(args.dst):
        raise SystemExit("错误：源与目标不能是同一个文件")

    if args.backup_dir:
        os.makedirs(args.backup_dir, exist_ok=True)
        bak = os.path.join(args.backup_dir, os.path.basename(args.src))
        if not os.path.exists(bak):
            shutil.copy2(args.src, bak)
            print(f"  备份 → {bak}")

    print(f"轻量版 runtime：{args.src} → {args.dst}")
    build(args.src, args.dst)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""runtime.zip 体积优化：把内容重复的 SONAME 别名去重（v1.2.98）。

## 为什么
Termux 的库目录里，同一份库有多个文件名（完整版本号 / SONAME / 无版本号），
例如 ICU：

    lib/libicudata.so.78.3   ← 真实数据 31.6MB
    lib/libicudata.so.78     ← 同一内容（Termux 里是符号链接）
    lib/libicudata.so        ← 同一内容

`collect-termux-runtime.sh` 为了绕开 Android SELinux 对 symlink 的限制，用 `cp -p`
造了实体副本（见脚本 3.7 节），于是同一份数据在 zip 里存了 2~3 遍。
实测全库有 13 组重复、**浪费 89.4MB**（占 APK 的 57%）。

## 做法
对 lib/ 下内容完全相同的 .so 组，只保留一个「本体」条目存数据，其余条目：
  · 数据区留空（0 字节）
  · zip comment 写 `LINK:<本体相对路径>`
App 侧 RuntimeInstaller 解压时读到 LINK 标记就用 `Files.createSymbolicLink()` 建符号链接
（Android SELinux 禁硬链接、放行 symlink；失败自动回退为硬链接再回退复制，功能不受影响）。

## 用法
    python scripts/dedupe-runtime.py <runtime.zip> [--dry-run]

就地重写 zip（先写临时文件再替换）。打印每组的节省量与总计。
"""
import hashlib
import os
import shutil
import sys
import zipfile

LINK_PREFIX = "LINK:"


def is_so(name: str) -> bool:
    base = name.rsplit("/", 1)[-1]
    return base.endswith(".so") or ".so." in base


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    dry = "--dry-run" in sys.argv

    src = zipfile.ZipFile(path)
    infos = src.infolist()

    # 1) 只对 lib/ 下的 .so 求哈希（按内容分组）
    by_hash: dict[str, list[zipfile.ZipInfo]] = {}
    for i in infos:
        if i.is_dir() or not i.filename.startswith("lib/") or not is_so(i.filename):
            continue
        if i.compress_size == 0 and i.file_size == 0:
            continue
        h = hashlib.sha256(src.read(i.filename)).hexdigest()
        by_hash.setdefault(h, []).append(i)

    groups = {h: v for h, v in by_hash.items() if len(v) > 1}
    if not groups:
        print("没有可去重的重复文件")
        return 0

    # 2) 每组选一个本体（优先「无版本号」名，回退到第一个），其余标记 LINK
    link_of: dict[str, str] = {}   # 别名路径 -> 本体路径
    savings = 0
    for h, members in sorted(groups.items(), key=lambda kv: -kv[1][0].file_size):
        names = [m.filename for m in members]
        # 本体选择：lib/x.so（无版本号）优先，其次 lib/x.so.N，最后任意
        def rank(n: str) -> tuple:
            base = n.rsplit("/", 1)[-1]
            return (base.count(".") > 1, base, n)   # 无版本号者 .count(".")==1
        keeper = sorted(names, key=rank)[0]
        for n in names:
            if n != keeper:
                link_of[n] = keeper
        size = members[0].file_size
        saved = size * (len(names) - 1)
        savings += saved
        if saved > 1048576:
            print(f"  {size/1048576:6.1f}MB × {len(names)} -> {keeper.rsplit('/',1)[-1]}"
                  f"  省 {saved/1048576:5.1f}MB")

    print(f"\n共 {len(groups)} 组、{len(link_of)} 个别名条目，可省 {savings/1048576:.1f} MB")
    if dry:
        print("(--dry-run，未写入)")
        return 0

    # 3) 重写 zip：别名条目空数据 + comment 标记
    tmp = path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as dst:
        for i in infos:
            data = None if i.is_dir() else src.read(i.filename)
            comment = i.comment or b""
            if i.filename in link_of:
                data = b""                                   # 数据由硬链接提供
                comment = (LINK_PREFIX + link_of[i.filename]).encode()
            zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
            zi.compress_type = i.compress_type if not i.is_dir() else zipfile.ZIP_STORED
            zi.external_attr = i.external_attr
            zi.internal_attr = i.internal_attr
            zi.create_system = i.create_system
            zi.comment = comment
            if i.is_dir():
                dst.writestr(zi, b"")
            else:
                dst.writestr(zi, data)
    src.close()

    before = os.path.getsize(path)
    after = os.path.getsize(tmp)
    shutil.move(tmp, path)
    print(f"\n完成：{before/1048576:.1f} MB -> {after/1048576:.1f} MB"
          f"（省 {(before-after)/1048576:.1f} MB）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

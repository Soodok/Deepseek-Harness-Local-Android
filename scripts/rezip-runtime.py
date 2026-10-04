#!/usr/bin/env python3
"""把 runtime 目录重新打包为 zip（替代 shell 的 zip 命令，跨平台）。

用法: python rezip-runtime.py <source_dir> <output.zip>
压缩级别 6（与常规 zip -r 相当；-9 对已压缩的 .so/.tar 收益极小但慢很多）。
"""
import os
import sys
import zipfile

def main() -> int:
    if len(sys.argv) != 3:
        print('usage: rezip-runtime.py <source_dir> <output.zip>', file=sys.stderr)
        return 2
    src, out = os.path.realpath(sys.argv[1]), os.path.realpath(sys.argv[2])
    if not os.path.isdir(src):
        print(f'source not a directory: {src}', file=sys.stderr)
        return 1
    n = 0
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for root, dirs, files in os.walk(src):
            dirs.sort()
            rel_root = os.path.relpath(root, src)
            # 空目录也要保留（包管理器/脚本可能依赖目录存在）
            if not dirs and not files:
                arc = (rel_root + '/') if rel_root != '.' else './'
                z.writestr(arc, b'')
            for f in sorted(files):
                full = os.path.join(root, f)
                arc = os.path.relpath(full, src).replace(os.sep, '/')
                try:
                    z.write(full, arc)
                    n += 1
                except OSError as e:
                    print(f'WARN: skip {arc}: {e}', file=sys.stderr)
    print(f'rezipped {n} files -> {out}')
    return 0

if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""把运行时里的 JS 解到 .scratch/validator，供 _test_ask_schema_real.mjs 导入。

为什么需要：那个探针要用引擎**真实**的 validateJsonSchemaValue，而它藏在
@deepseek-ai/dsh-tools 里，import 时还会拉 cordis / cosmokit / schemastery
等一串依赖。逐个手写包名会漏（实测连补三次还缺一个），所以整棵 JS 一起解。
只解 .js/.mjs/.cjs/package.json —— 二进制与资源对这个用途毫无用处，解全量太慢。

用法：python3 scripts/probes/_fetch_runtime_js.py [zip] [dest]
"""
import os
import sys
import zipfile

DEFAULT_ZIP = "_runtime_bak/rt-x86_64-current.zip"
DEFAULT_DEST = ".scratch/validator"
PREFIX = "lib/node_modules/"


def main(argv):
    zip_path = argv[1] if len(argv) > 1 else DEFAULT_ZIP
    dest_root = argv[2] if len(argv) > 2 else DEFAULT_DEST
    if not os.path.isfile(zip_path):
        print("missing runtime zip: " + zip_path)
        return 2
    count = 0
    with zipfile.ZipFile(zip_path) as z:
        for name in z.namelist():
            if not name.startswith(PREFIX) or name.endswith("/"):
                continue
            base = name.rsplit("/", 1)[-1]
            if not (base.endswith(".js") or base.endswith(".mjs")
                    or base.endswith(".cjs") or base == "package.json"):
                continue
            dest = os.path.join(dest_root, name)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            try:
                with z.open(name) as src, open(dest, "wb") as out:
                    out.write(src.read())
                count += 1
            except PermissionError:
                pass
    print("extracted %d files -> %s" % (count, dest_root))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

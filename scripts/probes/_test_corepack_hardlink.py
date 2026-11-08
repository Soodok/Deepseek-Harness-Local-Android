#!/usr/bin/env python3
"""硬链接写穿 / corepack 入口污染的回归测试（v1.4.9）。

## 事故（用户报告 #1，定位精确）

`dist/corepack.js` 本应是 node 入口，却被写成了 shell wrapper，于是：
  · `node dist/corepack.js` → SyntaxError
  · 直接执行 → exec 目标是自身 → 无限自循环

根因**不是**「修复器缺少 JS 判断」，而是 **硬链接写穿**：
Termux 的 npm 把 `bin/corepack` 硬链接到 `dist/corepack.js`（同一 inode），
而构建脚本用 `cat > "$ROOT/bin/corepack"` 生成 wrapper —— shell 的 `>` 是
**truncate**，不是新建，于是写的是共享 inode，源 JS 被一并覆盖。

为什么只有 corepack 坏：其余 npm/npx/pnpm/pnpx/yarn/yarnpkg 的 bin 条目
与对应 dist 条目不共享 inode。

## 本测试验证

1. 构建脚本用 `rm -f` 先断链（不再是裸 `cat >`）
2. 构建脚本有写后污染检测
3. App 侧有运行时自愈
4. 当前 runtime.zip 里的 dist/*.js 全是合法 JS 入口
5. 用真实 inode 复现「不 rm 会写穿、rm 后不会」
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SH = os.path.join(ROOT, "scripts/collect-termux-runtime.sh")
CFG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
ZIP = os.path.join(ROOT, "app/src/main/assets/runtime.zip")

ok = 0
fail = 0
skipped = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global ok, fail
    if cond:
        ok += 1
        print(f"  \u2713 {name}")
    else:
        fail += 1
        print(f"  \u2717 {name}" + (f"  -- {detail}" if detail else ""))


def skip(name: str, why: str) -> None:
    """显式跳过：单独计数、打印原因。

    不用「静默 return」伪装全绿 —— 跳过的项必须让人看见。
    """
    global skipped
    skipped += 1
    print(f"  - SKIP {name}: {why}")


print("=== 1. 构建脚本：先断链再写 ===")
sh = open(SH, encoding="utf-8").read()
start = sh.index("make_corepack_wrapper()")
end = sh.index("\n}", start) + 2
fn = sh[start:end]

check("先 rm -f 目标", re.search(r"rm -f \"?\$dst\"?", fn) is not None,
      "function body 里没有 rm -f $dst")
# 确认 cat > 的目标在 rm 之后
rm_pos = fn.find("rm -f")
cat_pos = fn.find("cat >")
check("rm 在 cat 之前", 0 <= rm_pos < cat_pos, f"rm@{rm_pos} cat@{cat_pos}")
check("有写后污染检测", "硬链接写穿" in fn or "污染了" in fn)
check("污染时中止构建", 'exit 1' in fn)

print("\n=== 2. App 侧运行时自愈 ===")
cfg = open(CFG, encoding="utf-8").read()
check("有 healClobberedCorepackEntry", "fun healClobberedCorepackEntry" in cfg)
check("启动时调用自愈", "healClobberedCorepackEntry(ctx)" in cfg)
h = cfg.index("fun healClobberedCorepackEntry")
hseg = cfg[h : h + 2400]
check("判据是 shell wrapper", "Android corepack" in hseg and "/system/bin/sh" in hseg)
check("还原成 node 入口", "env node" in hseg and "corepack.cjs" in hseg)

print("\n=== 3. 当前 runtime.zip 健康 ===")
dist = "lib/node_modules/corepack/dist"
entries = ["corepack", "npm", "npx", "pnpm", "pnpx", "yarn", "yarnpkg"]
HAVE_ZIP = os.path.isfile(ZIP)
if not HAVE_ZIP:
    skip(
        "3/4 节（runtime.zip 内容）",
        "仓库里没有 app/src/main/assets/runtime.zip —— 这是 `-Ppackage=noruntime` "
        "构建的正常形态（运行时由 CI 单独产出并随包外发），不是回归",
    )
    z = None
    names = set()
else:
    z = zipfile.ZipFile(ZIP)
    names = set(z.namelist())
for e in (entries if HAVE_ZIP else []):
    p = f"{dist}/{e}.js"
    if p not in names:
        check(f"{p} 存在", False, "缺失")
        continue
    text = z.read(p).decode("utf-8", "replace")
    first = text.split("\n")[0]
    is_wrapper = "Android corepack" in text and "/system/bin/sh" in first
    check(f"{p} 不是 shell wrapper", not is_wrapper,
          "被污染成 wrapper，node 无法执行")
    check(f"{p} 是 JS 入口", "corepack.cjs" in text or "process.env" in text,
          text[:60])

print("\n=== 4. bin/* wrapper 不与 dist 共享内容 ===")
# 内容完全相同 ⇒ 极可能又是同一次写入（历史 bug 特征）
for e in (entries if HAVE_ZIP else []):
    b = z.read(f"bin/{e}") if f"bin/{e}" in names else b""
    d = z.read(f"{dist}/{e}.js") if f"{dist}/{e}.js" in names else b""
    check(f"bin/{e} 与 dist/{e}.js 内容不同", b != d,
          "内容完全相同：疑似又一次写穿")

print("\n=== 5. 真实 inode 复现 ===")
if os.name == "nt":
    print("  (Windows 上硬链接语义不同，跳过动态复现)")
else:
    with tempfile.TemporaryDirectory() as td:
        distdir = os.path.join(td, "dist")
        bindir = os.path.join(td, "bin")
        os.makedirs(distdir)
        os.makedirs(bindir)
        src = os.path.join(distdir, "corepack.js")
        with open(src, "w", encoding="utf-8") as f:
            f.write('#!/data/data/com.termux/files/usr/bin/env node\nconsole.log("real")\n')
        dst = os.path.join(bindir, "corepack")
        os.link(src, dst)
        same_before = os.stat(src).st_ino == os.stat(dst).st_ino
        check("测试前提：写前同 inode", same_before)

        # 旧行为：裸 cat >
        with open(dst, "w", encoding="utf-8") as f:
            f.write("#!/system/bin/sh\nexec node ../dist/corepack.js\n")
        clobbered = open(src, encoding="utf-8").read().startswith("#!/system/bin/sh")
        check("复现：不 rm 会写穿源文件", clobbered, "未能复现，测试前提失效")

        # 复原，验证修复
        with open(src, "w", encoding="utf-8") as f:
            f.write('#!/data/data/com.termux/files/usr/bin/env node\nconsole.log("real")\n')
        os.remove(dst)
        os.link(src, dst)
        os.remove(dst)          # ← 修复的关键一步
        with open(dst, "w", encoding="utf-8") as f:
            f.write("#!/system/bin/sh\nexec node ../dist/corepack.js\n")
        src_ok = open(src, encoding="utf-8").read().startswith("#!/data/data")
        check("修复：rm 后不再写穿", src_ok,
              "源文件仍被污染")
        check("修复：目标已成独立 inode",
              os.stat(src).st_ino != os.stat(dst).st_ino)

print("\n" + "-" * 44)
print(f"通过 {ok} / 失败 {fail} / 跳过 {skipped}")
if skipped:
    # 明确announce，避免被当成「全绿」
    print(f"SKIPPED: {skipped} 组（原因见上）—— 这些组本轮**没有验证**")
sys.exit(1 if fail else 0)

#!/usr/bin/env python3
"""给 @deepseek-ai/dsh-attachment-local 打 Android 适配补丁（单一真源）。

本地改包与 CI collect-termux-runtime.sh 都调这一个脚本，避免两处漂移。
输入自动识别：目录（CI 阶段，runtime 已解压）或 .zip（本地改包）。

## 为什么需要（issue #7：「read_image dies with EINVAL: invalid argument, fsync」）
两个缺陷叠加，修掉一个只会把报错挪个位置：

1. **祖先逐级 sync 在 Android 上有两种死法**：`ensureDurableHome()` 从 $DSH_HOME
   一路 `dirname` 到文件系统根，对每一级调 `syncDirectory()`。这段遍历会爬出
   app 沙箱，于是：
   · **root 模式**：各级 open 都能过，但 `/` 是**只读 erofs/dm-verity**，
     `fsync` 返回 `EINVAL` → 首次存图即 `ATTACHMENT_WRITE_FAILED`
     （issue #7 的原始报错，正在这一行）。
   · **普通模式**：`/data/data`、`/data` 都在沙箱之外，SELinux 根本不让 app 域
     `open` → `EACCES`，比 fsync 更早抛出（2026-10-09 模拟器实测复现）。
   这两级的持久性都不该由我们负责（只读挂载上目录项持久性本就无意义；无权
   打开的层级更无从 sync）→ 一起容忍。**其余 errno 照旧上抛**。

2. **`rename as link` 破坏了暂存文件清理**：早年把 `import { link }` 改写成
   `rename as link`（因为 Android SELinux 禁硬链接）是错的——`rename` 会**移走**
   源文件：
   · `publishStagedObject` 之后 `unlink(staged)` 必 ENOENT → 发布整体判失败
   · `publishImmutableAlias` 的 source 是内容寻址正本，移走会让其余别名悬空
   正确做法：`link as fsLink` + 模块级 shim —— 先试真硬链接（将来平台放行时
   保持零拷贝），EACCES/EPERM/ENOTSUP/EXDEV/EMLINK/ENOSYS 退化
   `copyFile(..., COPYFILE_EXCL)`。**源保留**，且目标已存在时仍抛 EEXIST
   （上游靠它做 digest 校验的竞争分支）。

## 用法
    python scripts/patch-attachment-local.py <runtime 根目录|runtime.zip> [更多...]
    # 可选留底（**别放 assets/ 下** —— 那里一切文件都会进 APK）
    python scripts/patch-attachment-local.py <runtime.zip> --backup-dir _runtime_bak

幂等：已打过补丁的跳过（按 `isAndroidUnsyncableLevel` 标记判断）。
任何形态未识别 → 非零退出（CI 必须 fail-fast：静默漏打补丁等于把坏包发给用户）。
"""
import os
import re
import shutil
import sys
import zipfile

# 入口形态有两种，取决于该 runtime 是否被**旧补丁**改过：
#   ① 上游原始：      import { chmod, link, ... }
#   ② 旧补丁之后：    import { chmod, rename as link, ... }
# 两种都要能升到新方案（link as fsLink + copyFile 兜底）。
OLD_IMPORTS = [
    'import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } '
    'from "node:fs/promises";',
    'import { chmod, rename as link, mkdir, open, readFile, rename, rm, unlink, writeFile } '
    'from "node:fs/promises";',
]
NEW_IMPORT = (
    'import { chmod, copyFile, link as fsLink, mkdir, open, readFile, rename, rm, unlink, writeFile } '
    'from "node:fs/promises";'
)

SHIM = '''/* [dsh-android] Android SELinux forbids link(); degrade to copyFile (source kept).
 * COPYFILE_EXCL keeps the no-clobber contract: an existing target still raises
 * EEXIST, which upstream turns into a digest check of the existing object. */
const link = async (source, target) => {
\ttry {
\t\tawait fsLink(source, target);
\t} catch (error) {
\t\tconst code = error instanceof Error && "code" in error ? error.code : void 0;
\t\tif (code === "EACCES" || code === "EPERM" || code === "ENOTSUP" ||
\t\t\tcode === "EXDEV" || code === "EMLINK" || code === "ENOSYS") {
\t\t\tawait copyFile(source, target, constants.COPYFILE_EXCL);
\t\t\treturn;
\t\t}
\t\tthrow error;
\t}
};'''

# syncDirectory 整函数替换 —— 不匹配函数内部形态，上游重构函数体也不会静默漏打。
SYNC_RE = re.compile(r'async function syncDirectory\(path\)\s*\{.*?\n\}', re.S)

HELPER = '''/* [dsh-android] Levels the app sandbox can neither open nor fsync (see syncDirectory). */
const isAndroidUnsyncableLevel = (error) => error instanceof Error && "code" in error &&
\t(error.code === "EINVAL" || error.code === "EROFS" || error.code === "EACCES" ||
\t error.code === "EPERM" || error.code === "ENOTSUP" || error.code === "EOPNOTSUPP");

'''

NEW_SYNC = '''async function syncDirectory(path) {
\t/* v8 ignore next -- Windows cannot open directory handles; NTFS metadata journaling owns entry durability there. */
\tif (process.platform === "win32") return;
\t/* [dsh-android] The durable-home ancestor walk climbs from $DSH_HOME to the
\t * filesystem root and syncs every parent on the way. Two Android-only failures
\t * sit on that walk, and both must be skipped rather than fatal:
\t *  1) open() fails with EACCES/EPERM -- /data/data and /data are outside the
\t *     app sandbox; SELinux never lets this domain open them (non-root mode).
\t *  2) fsync() fails with EINVAL/EROFS -- Android mounts / read-only
\t *     (erofs/dm-verity); issue #7 died exactly here, "EINVAL: invalid argument,
\t *     fsync" on the first saveImageFile (root mode, where open() succeeds).
\t * Durability of a level we can neither open nor sync is not ours to provide.
\t * Any other errno still propagates. */
\tlet handle;
\ttry {
\t\thandle = await open(path, constants.O_RDONLY);
\t} catch (error) {
\t\tif (isAndroidUnsyncableLevel(error)) return;
\t\tthrow error;
\t}
\ttry {
\t\tawait handle.sync();
\t} catch (error) {
\t\tif (!isAndroidUnsyncableLevel(error)) throw error;
\t} finally {
\t\tawait handle.close();
\t}
}'''

REL_PATH = "lib/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js"
TARGET_SUFFIX = "@deepseek-ai/dsh-attachment-local/lib/index.js"
MARKER = "isAndroidUnsyncableLevel"


class PatchShapeError(Exception):
    """上游形态变了 —— 必须人工核对，绝不能静默跳过。"""


def patch_source(src: str) -> str | None:
    """返回补丁后的源码；None 表示已打过（幂等跳过）。形态不识别则抛 PatchShapeError。"""
    if MARKER in src:
        return None
    hit = next((o for o in OLD_IMPORTS if o in src), None)
    if hit is None:
        raise PatchShapeError("import 形态未识别（上游改了 import？）")
    src = src.replace(hit, NEW_IMPORT + "\n" + SHIM, 1)
    if not SYNC_RE.search(src):
        raise PatchShapeError("syncDirectory 未找到（上游改了函数名/形态？）")
    src = SYNC_RE.sub(NEW_SYNC, src, count=1)
    src = src.replace(NEW_SYNC, HELPER + NEW_SYNC, 1)
    return src


def _backup(src_path: str, backup_dir: str | None):
    if not backup_dir:
        return
    os.makedirs(backup_dir, exist_ok=True)
    dst = os.path.join(backup_dir, os.path.basename(src_path))
    shutil.copy2(src_path, dst)
    print(f"  备份 -> {dst}")


def patch_tree(root: str, backup_dir: str | None = None) -> bool:
    """目录模式（CI collect 阶段）。$root 传 runtime 根目录，不是 lib/node_modules。"""
    target = os.path.join(root, *REL_PATH.split("/"))
    if not os.path.isfile(target):
        raise PatchShapeError(f"未找到 {REL_PATH}（runtime 不完整？）")
    with open(target, encoding="utf-8") as f:
        src = f.read()
    new = patch_source(src)
    if new is None:
        print(f"  已打过补丁，跳过: {target}")
        return False
    _backup(target, backup_dir)
    with open(target, "w", encoding="utf-8", newline="") as f:
        f.write(new)
    print(f"  已打补丁: {target}")
    return True


def patch_zip(zip_path: str, backup_dir: str | None = None) -> bool:
    """.zip 模式（本地改包）。只重压改动的那一个条目，其余照写。"""
    zin = zipfile.ZipFile(zip_path)
    items = [(i, zin.read(i.filename)) for i in zin.infolist()]
    zin.close()

    changed = False
    out = []
    for info, data in items:
        if info.filename.endswith(TARGET_SUFFIX):
            new = patch_source(data.decode("utf-8"))
            if new is None:
                print(f"  已打过补丁，跳过: {info.filename}")
            else:
                data = new.encode("utf-8")
                changed = True
                print(f"  已打补丁: {info.filename}")
        out.append((info, data))

    if not changed:
        return False

    _backup(zip_path, backup_dir)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for info, data in out:
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type if not info.is_dir() else zipfile.ZIP_STORED
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            zi.comment = info.comment
            zout.writestr(zi, data)
    return True


def verify_zip(zip_path: str) -> bool:
    z = zipfile.ZipFile(zip_path)
    hit = next((n for n in z.namelist() if n.endswith(TARGET_SUFFIX)), None)
    if not hit:
        print("  !! 找不到 attachment-local")
        return False
    return _report(z.read(hit).decode("utf-8", "replace"))


def verify_tree(root: str) -> bool:
    target = os.path.join(root, *REL_PATH.split("/"))
    if not os.path.isfile(target):
        print("  !! 找不到 attachment-local")
        return False
    with open(target, encoding="utf-8") as f:
        return _report(f.read())


def _report(d: str) -> bool:
    checks = {
        "sync 容忍注入": MARKER in d,
        "EACCES/EPERM 覆盖": '"EACCES"' in d and '"EPERM"' in d,
        "EINVAL/EROFS 覆盖": '"EINVAL"' in d and '"EROFS"' in d,
        "link shim": "constants.COPYFILE_EXCL" in d,
        "无裸 rename as link": "rename as link" not in d,
    }
    ok = all(checks.values())
    print("  校验: " + " | ".join(f"{k}={'OK' if v else '缺失'}" for k, v in checks.items()))
    return ok


if __name__ == "__main__":
    argv = sys.argv[1:]
    backup_dir = None
    if "--backup-dir" in argv:
        i = argv.index("--backup-dir")
        if i + 1 >= len(argv):
            print("--backup-dir 需要一个目录参数", file=sys.stderr)
            sys.exit(2)
        backup_dir = argv[i + 1]
        del argv[i:i + 2]
    if not argv:
        print(__doc__)
        sys.exit(2)

    ok = True
    for path in argv:
        print(f"{path}:")
        try:
            if os.path.isdir(path):
                patch_tree(path, backup_dir)
                ok &= verify_tree(path)
            else:
                patch_zip(path, backup_dir)
                ok &= verify_zip(path)
        except PatchShapeError as e:
            print(f"  !! 补丁失败: {e}", file=sys.stderr)
            print("     上游形态已变，需人工核对新版（见文件顶部说明）", file=sys.stderr)
            ok = False
    sys.exit(0 if ok else 1)

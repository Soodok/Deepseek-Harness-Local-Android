#!/usr/bin/env python3
"""给 @deepseek-ai/dsh-fs-local 打 Android 适配补丁（单一真源）。

本地改包与 CI collect-termux-runtime.sh 都调这一个脚本，避免两处漂移。
输入自动识别：目录（CI 阶段，runtime 已解压）或 .zip（本地改包）。

## 为什么需要（issue #25 后续：「安卓14 LOS 通过 DSH 文件系统连文件都读不了，
## 提示文件资源服务不可用」）

`dsh-fs-local` 的 `probeStats()` 只把 **ENOENT / ENOTDIR** 当成"路径不存在"，
其余错误一律原样上抛：

```js
async function probeStats(absolutePath, readStats) {
    try { return await readStats(absolutePath); }
    catch (error) {
        if (!isENOENT(error) && !isENOTDIR(error)) throw error;   // ← EACCES 在这里被抛出
        return null;
    }
}
```

这段假设在 Linux/macOS 上成立（权限位由 app 自己掌握），但 **Android 不成立**：

1. **共享存储走 FUSE（sdcardfs / fuse-sdcard）**。`Documents/dshdata` 这类路径
   属于 media_provider 卷，app 即使拿到 MANAGE_EXTERNAL_STORAGE，`stat()`/`lstat()`
   仍可能返回 **EACCES**（SELinux 域迁移、volume 正在重新挂载、或该目录项属于
   其他 uid 的 media 文件）。上游把它当成致命 IO 错误。
2. 于是 `resolve()` / `stat()` / `lstat()` / `listDir()` 整条链失败：
   - 文件预览：`locateFile()` 拿不到 `entry.type` → 抛 `workspace-file/not-regular-file`
     （或 stat 阶段直接抛 IO）→ 前端 `meta.status === "none"` → **「文件资源服务不可用」**；
   - 目录列表：`listDirectory()` 对**每个子项**都 `resolveListedChildTarget()` +
     `probe()`，任一子项 EACCES 就 `throw listingIoError(...)` → **整目录列举失败**。
3. 同一现象在 root 模式（app 以 uid 0 跑，SELinux 仍是 `untrusted_app` 之外但
   FUSE 挂载点权限检查不看 uid）和普通模式都存在，所以不是权限模式的问题 ——
   这也解释了为什么用户换成 root 仍读不了。

## 修法（两处，都保持上游的可观测语义）

**① `probeStats`：把 EACCES/EPERM 视为"无法观测"而非致命错误。**
   `stat` 失败不代表路径不存在，但调用方要的是"能不能读到元数据"。返回 `null`
   会让上层走 `FS_NOT_FOUND`（"文件不存在"），这比"资源服务不可用"更准确，
   且不会中断整个目录列举。**其余 errno（EIO、ENOMEM 等）照旧上抛** —— 真故障
   不应该被吞掉。

**② `listDirectory`：单个子项失败时降级为 `type: "other"` 而不是整目录失败。**
   上游对每个子项都 resolve + probe，一个不可读项就 `throw`。Android 的共享存储
   里经常混着别的 app 创建的文件（media 卷），逐个失败会让"目录打不开"扩散成
   "整个文件面板不可用"。改为：子项解析失败 → 该行标记 `other` 继续，并保留
   `name`，用户可以继续浏览同一目录的其余内容。

## 用法
    python scripts/patch-fs-local-android.py <runtime 根目录|runtime.zip> [更多...]

幂等：已打过补丁的跳过（按 `[dsh-android]` 标记判断）。
任何形态未识别 → 非零退出（CI 必须 fail-fast：静默漏打补丁等于把坏包发给用户）。
"""
import os
import re
import shutil
import sys
import zipfile

MARKER = "[dsh-android] fs-local"

# ① probeStats：ENOENT/ENOTDIR 之外也容忍权限错误。
#
# 上游 0.2.2-rc.2 把这里从 `throw error` 改成了 `throw fsErrorFromErrno("stat", ...)`
# （错误被包成带 verb/路径的 FsError）。两种形态都要认，否则升级引擎时 CI 直接红。
PROBE_OLD = """		if (!isENOENT(error) && !isENOTDIR(error)) throw error;
		return null;
	}
}"""

PROBE_OLD_RC2 = """		if (!isENOENT(error) && !isENOTDIR(error)) throw fsErrorFromErrno("stat", absolutePath, error);
		return null;
	}
}"""

PROBE_NEW = """		if (!isENOENT(error) && !isENOTDIR(error)) {
			// [dsh-android] Android shared storage (FUSE) reports EACCES for entries
			// the app cannot observe even with MANAGE_EXTERNAL_STORAGE: SELinux domain
			// transitions, a volume being remounted, or a media-owner uid. Upstream
			// treats every such failure as fatal, which turns "one unreadable entry"
			// into "the whole file panel is unavailable". Report it as unobserved
			// (null) instead; the caller then says "not found" rather than failing the
			// entire listing. Every other errno (EIO, ENOMEM, ...) still propagates.
			if (isPermissionError(error)) return null;
			throw error;
		}
		return null;
	}
}"""

PROBE_NEW_RC2 = """		if (!isENOENT(error) && !isENOTDIR(error)) {
			// [dsh-android] Android shared storage (FUSE) reports EACCES for entries
			// the app cannot observe even with MANAGE_EXTERNAL_STORAGE: SELinux domain
			// transitions, a volume being remounted, or a media-owner uid. Upstream
			// treats every such failure as fatal, which turns "one unreadable entry"
			// into "the whole file panel is unavailable". Report it as unobserved
			// (null) instead; the caller then says "not found" rather than failing the
			// entire listing. Every other errno (EIO, ENOMEM, ...) still propagates.
			if (isPermissionError(error)) return null;
			throw fsErrorFromErrno("stat", absolutePath, error);
		}
		return null;
	}
}"""

# ② listDirectory：单个子项失败降级为 other，不中断整目录。
LIST_OLD = """		} catch (error) {
			throw listingIoError(localDisplayPath(target.displayPath, entry.name), error);
		}"""

# 上游 0.2.2-rc.2：per-child 失败从 `throw listingIoError(...)` 改成
# `throw fsErrorFromErrno("list", ...)`（listingIoError 已不存在）。
LIST_OLD_RC2 = """		} catch (error) {
			throw fsErrorFromErrno("list", localDisplayPath(target.displayPath, entry.name), error);
		}"""

LIST_NEW = """		} catch (error) {
			// [dsh-android] One unobservable child must not fail the whole directory.
			// Shared storage (media volumes) routinely holds entries this app can list
			// but not stat; upstream aborted the entire listing on the first one. Emit
			// the name with type "other" so the rest of the directory stays browsable.
			if (isPermissionError(error)) {
				result.push({
					name: entry.name,
					type: "other",
					target: {
						displayPath: localDisplayPath(target.displayPath, entry.name),
						targetKey: FsTargetKey(localDisplayPath(target.displayPath, entry.name))
					}
				});
				continue;
			}
			throw listingIoError(localDisplayPath(target.displayPath, entry.name), error);
		}"""

LIST_NEW_RC2 = """		} catch (error) {
			// [dsh-android] One unobservable child must not fail the whole directory.
			// Shared storage (media volumes) routinely holds entries this app can list
			// but not stat; upstream aborted the entire listing on the first one. Emit
			// the name with type "other" so the rest of the directory stays browsable.
			if (isPermissionError(error)) {
				result.push({
					name: entry.name,
					type: "other",
					target: {
						displayPath: localDisplayPath(target.displayPath, entry.name),
						targetKey: FsTargetKey(localDisplayPath(target.displayPath, entry.name))
					}
				});
				continue;
			}
			throw fsErrorFromErrno("list", localDisplayPath(target.displayPath, entry.name), error);
		}"""


def patch_text(text: str) -> tuple[str, list[str]]:
    """应用两处替换，返回 (新文本, 已应用的标记列表)。已打过的跳过。"""
    applied: list[str] = []

    # 先认「已打过」的两种形态，再按 新版(rc.2) → 旧版(≤rc.1) 顺序找锚点。
    if PROBE_NEW_RC2 in text or PROBE_NEW in text:
        applied.append("probeStats: already patched")
    elif PROBE_OLD_RC2 in text:
        text = text.replace(PROBE_OLD_RC2, PROBE_NEW_RC2, 1)
        applied.append("probeStats: tolerate EACCES (rc.2 form)")
    elif PROBE_OLD in text:
        text = text.replace(PROBE_OLD, PROBE_NEW, 1)
        applied.append("probeStats: tolerate EACCES (legacy form)")
    else:
        raise SystemExit(
            "错误：probeStats 形态未识别（上游已变？）—— 中止，避免静默漏打补丁"
        )

    if LIST_NEW_RC2 in text or LIST_NEW in text:
        applied.append("listDirectory: already patched")
    elif LIST_OLD_RC2 in text:
        text = text.replace(LIST_OLD_RC2, LIST_NEW_RC2, 1)
        applied.append("listDirectory: degrade one child to other (rc.2 form)")
    elif LIST_OLD in text:
        text = text.replace(LIST_OLD, LIST_NEW, 1)
        applied.append("listDirectory: degrade one child to other (legacy form)")
    else:
        raise SystemExit(
            "错误：listDirectory 形态未识别（上游已变？）—— 中止，避免静默漏打补丁"
        )

    return text, applied


def patch_file(path: str) -> bool:
    with open(path, "r", encoding="utf-8", newline="") as f:
        original = f.read()
    updated, applied = patch_text(original)
    if updated == original:
        print(f"  fs-local: 已是补丁形态（{len(applied)} 项）")
        return False
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(updated)
    print(f"  fs-local: 已打补丁 -> {', '.join(applied)}")
    return True


def patch_dir(root: str) -> None:
    target = os.path.join(
        root, "lib", "node_modules", "@deepseek-ai", "dsh-fs-local", "lib", "index.js"
    )
    if not os.path.isfile(target):
        raise SystemExit(f"错误：找不到 {target}")
    patch_file(target)


def patch_zip(zip_path: str, backup_dir: str | None) -> None:
    member = "lib/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js"
    with zipfile.ZipFile(zip_path, "r") as z:
        try:
            data = z.read(member)
        except KeyError:
            raise SystemExit(f"错误：zip 内找不到 {member}")
        text = data.decode("utf-8")
        updated, applied = patch_text(text)
        if updated == text:
            print(f"  fs-local: 已是补丁形态（{len(applied)} 项）")
            return
        if backup_dir:
            os.makedirs(backup_dir, exist_ok=True)
            shutil.copy2(zip_path, os.path.join(backup_dir, os.path.basename(zip_path)))
            print(f"  备份 -> {backup_dir}")

        # 重写 zip：逐条复制，仅替换目标条目（保持其它条目字节不变）
        tmp = zip_path + ".tmp"
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as out:
            for info in z.infolist():
                if info.filename == member:
                    out.writestr(info, updated.encode("utf-8"))
                else:
                    out.writestr(info, z.read(info.filename))
    os.replace(tmp, zip_path)
    print(f"  fs-local: 已打补丁 -> {', '.join(applied)}")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    backup_dir = None
    targets = []
    i = 1
    while i < len(argv):
        arg = argv[i]
        if arg == "--backup-dir":
            i += 1
            backup_dir = argv[i]
        else:
            targets.append(arg)
        i += 1
    for target in targets:
        print(f"fs-local @ {target}")
        if os.path.isdir(target):
            patch_dir(target)
        elif os.path.isfile(target) and target.lower().endswith(".zip"):
            patch_zip(target, backup_dir)
        else:
            raise SystemExit(f"错误：既不是目录也不是 zip -> {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

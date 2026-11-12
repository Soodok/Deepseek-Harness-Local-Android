#!/usr/bin/env python3
"""给 @deepseek-ai/dsh-llm-deepseek 补「TRANSPORT 带上底层原因」（单一真源）。

输入自动识别：目录（CI 阶段，runtime 已解压）或 .zip（本地改包）。

## 为什么需要（issue #108：40 MB 大会话发消息必 TRANSPORT，5 次重试全失败）

上游 generate() 的收尾把三类以外的所有异常都归成 TRANSPORT：

    } catch (error) {
        if (timeoutOf(watchdog.signal, "MESSAGES_IDLE") !== void 0) throw new LlmError("DeepSeek Messages stream idle timeout", "TIMEOUT", { cause: error });
        if (options.signal?.aborted) throw new LlmError("DeepSeek Messages request aborted", "ABORTED", { cause: error });
        if (error instanceof LlmError) throw error;
        throw new LlmError("DeepSeek Messages transport failed", "TRANSPORT", { cause: error });   // ← 这里
    }

cause 是挂上了，但**界面上只显示 error.message**，于是用户看到的就是一句
「DeepSeek Messages transport failed」—— 到底是 ECONNRESET、socket hang up、
premature close，还是请求体过大被中间设备掐断，全都看不到。issue #108 里作者和
用户都卡在这一步，只能靠猜。

## 修法

把底层异常的 code/message（含 cause 链，最多 4 层）拼进 message：

    DeepSeek Messages transport failed: ECONNRESET socket hang up

只改**消息文本**，错误码、cause、重试策略全部不变 —— 不改变任何控制流，
不影响 dsh-llm-retry 的判断（它只看 code）。消息上限 240 字符，避免超长栈撑爆 UI。
"""
from __future__ import annotations

import os
import shutil
import sys
import zipfile

TARGET_MEMBER = "lib/index.js"
PKG_DIR = os.path.join("node_modules", "@deepseek-ai", "dsh-llm-deepseek")

ANCHOR = (
    '\t\t\t\tthrow new LlmError("DeepSeek Messages transport failed", "TRANSPORT", { cause: error });'
)

PATCHED = (
    '\t\t\t\tthrow new LlmError('
    '"DeepSeek Messages transport failed: " + transportDetail(error), "TRANSPORT", { cause: error });'
)

HELPER_ANCHOR = "var DeepSeekAdapter = class extends LlmAdapter {"

HELPER = """/**
 * v1.7.9 / issue #108：把底层网络异常的原因拼进 TRANSPORT 消息里。
 *
 * 上游只留一句 "DeepSeek Messages transport failed"，用户与作者都无从下手。
 * 这里顺着 cause 链取 code + message（最多 4 层），去重后拼成一行，上限 240 字符。
 * **只改展示文本**：错误码、cause、重试策略都不动。
 */
function transportDetail(error) {
\tconst seen = new Set();
\tconst parts = [];
\tlet current = error;
\tfor (let depth = 0; depth < 4; depth++) {
\t\tif (current === void 0 || current === null || seen.has(current)) break;
\t\tseen.add(current);
\t\tconst code = typeof current.code === "string" ? current.code : "";
\t\tlet message = "";
\t\ttry {
\t\t\tmessage = typeof current.message === "string" ? current.message : String(current);
\t\t} catch {
\t\t\tmessage = "";
\t\t}
\t\tconst piece = code !== "" && message !== "" && !message.includes(code) ? code + " " + message : (code !== "" ? code : message);
\t\tif (piece !== "" && !parts.includes(piece)) parts.push(piece);
\t\tcurrent = current.cause;
\t}
\tif (parts.length === 0) return "unknown cause";
\tconst joined = parts.join(" <- ");
\treturn joined.length > 240 ? joined.slice(0, 237) + "..." : joined;
}

"""


def patch_text(text: str) -> tuple[str, list[str]]:
    if "function transportDetail(error)" in text:
        return text, ["already-patched"]
    if ANCHOR not in text:
        raise SystemExit(
            "错误：找不到 TRANSPORT 抛出点（上游形态已变？）\n"
            '  期望：throw new LlmError("DeepSeek Messages transport failed", "TRANSPORT", { cause: error });'
        )
    if text.count(ANCHOR) != 1:
        raise SystemExit(f"错误：TRANSPORT 锚点出现 {text.count(ANCHOR)} 次，拒绝猜")
    if HELPER_ANCHOR not in text:
        raise SystemExit("错误：找不到注入点 var DeepSeekAdapter = class extends LlmAdapter {")
    applied: list[str] = []
    text = text.replace(ANCHOR, PATCHED, 1)
    applied.append("transport-detail")
    text = text.replace(HELPER_ANCHOR, HELPER + HELPER_ANCHOR, 1)
    applied.append("helper")
    return text, applied


def patch_dir(root: str) -> None:
    # runtime 里包在 <root>/lib/node_modules/...，而 PKG_DIR 不含 lib/ 前缀
    # （zip 模式在 patch_zip 里自己补）。目录模式漏了 lib/ 会直接报「找不到」，
    # 而 collect-termux-runtime.sh 是 fail-fast 调用 → 整个 runtime 构建失败。
    target = os.path.join(root, "lib", PKG_DIR, TARGET_MEMBER)
    if not os.path.isfile(target):
        raise SystemExit(f"错误：找不到 {target}")
    with open(target, "r", encoding="utf-8") as f:
        text = f.read()
    updated, applied = patch_text(text)
    if updated == text:
        print(f"  llm-deepseek: 已是补丁形态（{', '.join(applied)}）")
        return
    with open(target, "w", encoding="utf-8", newline="\n") as f:
        f.write(updated)
    print(f"  llm-deepseek: 已打补丁 -> {', '.join(applied)}")


def patch_zip(zip_path: str, backup_dir: str | None) -> None:
    member = "lib/" + PKG_DIR.replace(os.sep, "/") + "/" + TARGET_MEMBER
    with zipfile.ZipFile(zip_path, "r") as z:
        try:
            data = z.read(member)
        except KeyError:
            raise SystemExit(f"错误：zip 内找不到 {member}")
        text = data.decode("utf-8")
        updated, applied = patch_text(text)
        if updated == text:
            print(f"  llm-deepseek: 已是补丁形态（{', '.join(applied)}）")
            return
        if backup_dir:
            os.makedirs(backup_dir, exist_ok=True)
            shutil.copy2(zip_path, os.path.join(backup_dir, os.path.basename(zip_path)))
            print(f"  备份 -> {backup_dir}")
        tmp = zip_path + ".tmp"
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as out:
            for info in z.infolist():
                if info.filename == member:
                    out.writestr(info, updated.encode("utf-8"))
                else:
                    out.writestr(info, z.read(info.filename))
    os.replace(tmp, zip_path)
    print(f"  llm-deepseek: 已打补丁 -> {', '.join(applied)}")


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
        print(f"llm-deepseek @ {target}")
        if os.path.isdir(target):
            patch_dir(target)
        elif os.path.isfile(target) and target.lower().endswith(".zip"):
            patch_zip(target, backup_dir)
        else:
            raise SystemExit(f"错误：既不是目录也不是 zip -> {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

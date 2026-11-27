#!/usr/bin/env python3
"""插话（steer）在 Android 端的两处引擎侧修复（单一真源）。

对应私人反馈库 reports.db 的 82c93406（HONOR BKQ-AN90 / 1.6.0-beta.31 / vC407）：
「移动端插话失效：消息进不了会话」。反馈者自己扒出了四层阻断，其中两层在引擎：

  A. 会话控制器（dsh-api-session-controller/lib/index.js）
     ```
     if (request.action.kind === "steer" &&
         (target !== "next-turn" || agent.status !== "running")) throw ...
     ```
     而 `agent.steer()` 写入的恰恰是 **next-step**（见 dsh-agent-loop：
     `steer(input) { this.send(input, "next-step", true); }`）。
     于是引擎自己的语义对不上：「插话」只能插 next-turn，而插话的落点就是 next-step。

  B. 会话 UI（dsh-client-ui-conversation/lib/client.js）
     `steerQueue()` 只遍历 `inbox["next-turn"]`，next-step 里的行永远轮不到；
     且 `session/steer-unavailable` 被直接 `return` 吞掉 —— 用户看不到任何提示，
     表现就是「消息发出去了，AI 像没看见」。

## 修法

A. 去掉 `target !== "next-turn"` 这一条，保留 `agent.status !== "running"`：
   插话本来只对「正在跑的回合」有意义，这个守卫是正确语义，不能一起删。
B. 遍历 next-turn + next-step 两个队列；`steer-unavailable` 改为**提示一次**，
   `queue-item-not-found` 仍静默收敛（那是「这一行已被 agent 取走」，属正常竞态）。

两条都是**文本级替换**，不引入新依赖、不改控制流以外的结构。幂等：打过就跳过。

输入自动识别：目录（runtime 已解压）或 .zip（本地改包），与其它 patch-*.py 一致。
"""
from __future__ import annotations

import os
import shutil
import sys
import zipfile

MARKER = "[dsh-android: steer]"

SC_DIR = os.path.join("node_modules", "@deepseek-ai", "dsh-api-session-controller")
SC_MEMBER = "lib/index.js"
CONV_DIR = os.path.join("node_modules", "@deepseek-ai", "dsh-client-ui-conversation")
CONV_MEMBER = "lib/client.js"

# ── A. 会话控制器 ────────────────────────────────────────────────────────
SC_ANCHOR = (
    'if (request.action.kind === "steer" && (target !== "next-turn" || '
    'agent.status !== "running")) throw new RemoteError("session/steer-unavailable", '
    '"current turn no longer accepts steering", { itemId: request.itemId });'
)

SC_PATCHED = (
    "// " + MARKER + " 插话的落点本来就是 next-step（agent.steer 写的就是它），\n"
    "\t\t// 旧守卫只放行 next-turn，等于把引擎自己的插话语义挡死。\n"
    "\t\t// 保留 status !== running：插话只对正在跑的回合有意义。\n"
    "\t\tif (request.action.kind === \"steer\" && agent.status !== \"running\") "
    "throw new RemoteError(\"session/steer-unavailable\", "
    "\"current turn no longer accepts steering\", { itemId: request.itemId });"
)

# ── B. 会话 UI ───────────────────────────────────────────────────────────
CONV_ANCHOR_QUEUE = (
    'const queued = session.projections.faceOf("inbox").getSnapshot()?.["next-turn"] ?? [];'
)

CONV_PATCHED_QUEUE = (
    "// " + MARKER + " 插话要覆盖 next-step：那才是 steer 的落点。\n"
    "\t\t\tconst inbox = session.projections.faceOf(\"inbox\").getSnapshot() ?? {};\n"
    "\t\t\tconst queued = [...(inbox[\"next-turn\"] ?? []), ...(inbox[\"next-step\"] ?? [])];"
)

CONV_ANCHOR_FAIL = (
    'if (result.error.code === "session/steer-unavailable" || '
    'result.error.code === "session/queue-item-not-found") return;'
)

CONV_PATCHED_FAIL = (
    "// " + MARKER + " 只有「这一行已被 agent 取走」才静默收敛；\n"
    "\t\t\t\t// steer-unavailable 必须让用户看到一次 —— 原来的静默 return\n"
    "\t\t\t\t// 就是「消息发出去了、AI 像没看见」的直接成因。\n"
    "\t\t\t\tif (result.error.code === \"session/queue-item-not-found\") return;"
)


def patch_sc(text: str) -> tuple[str, list[str]]:
    if MARKER in text and "agent.status !== \"running\") throw new RemoteError(\"session/steer-unavailable\"" in text:
        return text, ["already-patched"]
    count = text.count(SC_ANCHOR)
    if count != 1:
        raise SystemExit(
            f"错误：会话控制器的 steer 守卫出现 {count} 次（期望 1）—— 上游形态变了？"
        )
    return text.replace(SC_ANCHOR, SC_PATCHED, 1), ["steer-allow-next-step"]


def patch_conv(text: str) -> tuple[str, list[str]]:
    applied = []
    if MARKER not in text:
        n = text.count(CONV_ANCHOR_QUEUE)
        if n != 1:
            raise SystemExit(f"错误：steerQueue 的队列锚点出现 {n} 次（期望 1）")
        text = text.replace(CONV_ANCHOR_QUEUE, CONV_PATCHED_QUEUE, 1)
        applied.append("steer-covers-next-step")
    else:
        applied.append("queue-already-patched")

    # 失败提示这条单独判：上面的幂等标记无法区分「只打了队列、没打提示」。
    if CONV_ANCHOR_FAIL in text:
        text = text.replace(CONV_ANCHOR_FAIL, CONV_PATCHED_FAIL, 1)
        applied.append("steer-failure-notice")
    else:
        applied.append("failure-notice-already-patched")
    return text, applied


def _patch_file(path: str, fn) -> None:
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    updated, applied = fn(text)
    if updated == text:
        print(f"  {os.path.basename(path)}: 已是补丁形态（{', '.join(applied)}）")
        return
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(updated)
    print(f"  {os.path.basename(path)}: 已打补丁 -> {', '.join(applied)}")


def patch_dir(root: str) -> None:
    sc = os.path.join(root, "lib", SC_DIR, SC_MEMBER)
    conv = os.path.join(root, "lib", CONV_DIR, CONV_MEMBER)
    for path, fn in ((sc, patch_sc), (conv, patch_conv)):
        if not os.path.isfile(path):
            raise SystemExit(f"错误：找不到 {path}")
        _patch_file(path, fn)


def patch_zip(zip_path: str, backup_dir: str | None) -> None:
    members = [
        ("lib/" + SC_DIR.replace(os.sep, "/") + "/" + SC_MEMBER, patch_sc),
        ("lib/" + CONV_DIR.replace(os.sep, "/") + "/" + CONV_MEMBER, patch_conv),
    ]
    with zipfile.ZipFile(zip_path, "r") as z:
        texts = {}
        for member, fn in members:
            try:
                raw = z.read(member)
            except KeyError:
                # 前端 UI 包可能不随运行时发布（只随引擎包）；缺了只报跳过，不算失败。
                print(f"  跳过：zip 内没有 {member}")
                continue
            updated, applied = fn(raw.decode("utf-8"))
            if updated != raw.decode("utf-8"):
                texts[member] = (updated, applied)
                print(f"  {os.path.basename(member)}: 已打补丁 -> {', '.join(applied)}")
            else:
                print(f"  {os.path.basename(member)}: 已是补丁形态（{', '.join(applied)}）")
        if not texts:
            return
        if backup_dir:
            os.makedirs(backup_dir, exist_ok=True)
            shutil.copy2(zip_path, os.path.join(backup_dir, os.path.basename(zip_path)))
            print(f"  备份 -> {backup_dir}")
        tmp = zip_path + ".tmp"
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as out:
            for info in z.infolist():
                if info.filename in texts:
                    out.writestr(info, texts[info.filename][0].encode("utf-8"))
                else:
                    out.writestr(info, z.read(info.filename))
    os.replace(tmp, zip_path)


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
        print(f"steer @ {target}")
        if os.path.isdir(target):
            patch_dir(target)
        elif os.path.isfile(target) and target.lower().endswith(".zip"):
            patch_zip(target, backup_dir)
        else:
            raise SystemExit(f"错误：既不是目录也不是 zip -> {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

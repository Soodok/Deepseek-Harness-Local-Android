#!/usr/bin/env python3
"""给 `@deepseek-ai/dsh-agent-preset-registry` 打健壮性补丁：预设缺失时优雅降级。

## 为什么需要

会话记录里存着它创建时用的 `agentPreset`（`agent-preset/selected` 事件写入
projection）。恢复会话时 `@deepseek-ai/dsh-api-session-controller` 会走：

```js
async composeAgent(presetId) {
  const presets = this.ctx.get("agentPresets");
  if (presets === void 0) return { setup: ... };
  const resolvedId = (await presets.resolve(presetId)).id;   // ← 这里
  ...
}
```

而 `agent-preset-registry` 的 `resolve()` 在预设不存在时**直接抛**：

```js
async resolve(id) {
  const wanted = id ?? this.defaultId;
  const record = this.definitions.get(wanted);
  if (record === void 0) throw new RemoteError("agent-preset/not-found",
    `Unknown agent preset: ${wanted}`, { agentPreset: wanted, available: [...] });
  ...
}
```

`composeAgent` 没有 try/catch，于是整条恢复路径失败。

## 用户看到什么（真机实测）

引擎升级后，若某个预设被删除/改名（本例：`qq7399386-01` → `jli7399386`），
引用它的那个会话就坏了：

- 会话列表抬头显示的是 **session id** 而不是会话标题
- 打开后消息布局错位、文字叠在一起
- 同一个账号下**其它会话正常**（它们的 preset 引用有效）
- 重启引擎后状态重新加载，暂时绕开那条错误路径 → 看起来「自己好了」

## 修法：把「未知预设」降级为「用默认预设」

`resolve()` 查不到时返回默认预设的元数据，而不是抛异常。理由：

1. 预设只是**子插件组合**。丢掉一个自定义组合，会话本体（消息历史、cwd、
   模型选择）都还在，用默认组合继续跑是**有意义的降级**。
2. 抛异常会把「一个会话的预设失效」放大成「整个会话列表渲染失败」——
   错误的爆炸半径远超问题本身。
3. 保留可观测性：降级时往 `broken` 里写 `agent-preset/not-found` 与原始 id +
   可用列表，前端/日志仍能看见发生过降级，不会静默。

**默认预设也查不到**时（`defaultId` 本身缺失，属于配置损坏）仍保持原行为抛错
—— 那种情况下没有任何合理组合可用，静默降级只会让人更难查。

幂等：用 `[dsh-android-patch: agent-preset-fallback]` 标记；已打过则跳过。

## 输入

- 目录（runtime 已解压）：`<root>/lib/node_modules/@deepseek-ai/dsh-agent-preset-registry/lib/index.js`
- 或 `.zip`（本地改包）：定位同一路径下的 `index.js`
"""

from __future__ import annotations

import os
import sys
import zipfile

MARK = "[dsh-android-patch: agent-preset-fallback]"
PKG = "dsh-agent-preset-registry"
REL = os.path.join("lib", "node_modules", "@deepseek-ai", PKG, "lib", "index.js")

# 上游原文（anchor）。缩进用 tab —— 该包 lib 产物是 tab 缩进。
ANCHOR = (
    "\t\tasync resolve(id) {\n"
    "\t\t\tconst wanted = id ?? this.defaultId;\n"
    "\t\t\tconst record = this.definitions.get(wanted);\n"
    "\t\t\tif (record === void 0) throw new RemoteError(\"agent-preset/not-found\", "
    "`Unknown agent preset: ${wanted}`, {\n"
    "\t\t\t\tagentPreset: wanted,\n"
    "\t\t\t\tavailable: [...this.definitions.keys()]\n"
    "\t\t\t});\n"
)

PATCHED = (
    "\t\tasync resolve(id) {\n"
    "\t\t\tconst wanted = id ?? this.defaultId;\n"
    "\t\t\tlet record = this.definitions.get(wanted);\n"
    "\t\t\t// " + MARK + "\n"
    "\t\t\t// 会话记录里的 preset 引用可能指向已删除/改名的预设。上游在这里抛\n"
    "\t\t\t// agent-preset/not-found，而调用方 composeAgent() 没有 try/catch，\n"
    "\t\t\t// 于是一个会话的预设失效会让整个会话列表渲染失败（标题显示 session id、\n"
    "\t\t\t// 消息布局错位）。降级到默认预设：会话本体都在，继续可用。\n"
    "\t\t\tlet fellBackFrom = null;\n"
    "\t\t\tif (record === void 0) {\n"
    "\t\t\t\tconst fallback = this.definitions.get(this.defaultId);\n"
    "\t\t\t\tif (fallback === void 0) throw new RemoteError(\"agent-preset/not-found\", "
    "`Unknown agent preset: ${wanted}`, {\n"
    "\t\t\t\t\tagentPreset: wanted,\n"
    "\t\t\t\t\tavailable: [...this.definitions.keys()]\n"
    "\t\t\t\t});\n"
    "\t\t\t\trecord = fallback;\n"
    "\t\t\t\tfellBackFrom = wanted;\n"
    "\t\t\t}\n"
)


OLD_DIAG = (
        "\t\t\tconst broken = await this.diagnostic(record);\n"
        "\t\t\treturn {\n"
        "\t\t\t\tid: wanted,\n"
        "\t\t\t\t...broken === void 0 ? {} : { broken }\n"
        "\t\t\t};"
    )

NEW_DIAG = (
        "\t\t\tconst broken = await this.diagnostic(record);\n"
        "\t\t\t// " + MARK + "\n"
        "\t\t\t// 降级时把原始引用一并暴露，前端/日志能看见发生过什么。\n"
        "\t\t\tconst fellBack = fellBackFrom === null ? {} : { fellBackFrom };\n"
        "\t\t\treturn {\n"
        "\t\t\t\tid: record.config.id,\n"
        "\t\t\t\t...fellBack,\n"
        "\t\t\t\t...broken === void 0 ? {} : { broken }\n"
        "\t\t\t};"
    )


def patch_text(text: str) -> tuple[str, list[str]]:
    applied: list[str] = []
    if MARK in text:
        return text, ["already"]

    if ANCHOR not in text:
        raise SystemExit(
            "错误：找不到 resolve() 的锚点。上游形态已变（升级了包版本？），"
            "请重新核对 lib/index.js 里 async resolve(id) 的实现再更新本补丁。"
        )

    text = text.replace(ANCHOR, PATCHED, 1)
    applied.append("resolve() falls back to the default preset")

    # 诊断信息里带上降级来源，保证「不静默」
    if OLD_DIAG in text:
        text = text.replace(OLD_DIAG, NEW_DIAG, 1)
        applied.append("resolve() reports fellBackFrom")
    else:
        raise SystemExit(
            "错误：找不到 resolve() 返回值锚点（第二段）。补丁只打了第一段，"
            "文件已改但语义可能不一致 —— 请核对后手工修正。"
        )

    return text, applied


def patch_file(path: str) -> bool:
    with open(path, "r", encoding="utf-8", newline="") as f:
        original = f.read()
    updated, applied = patch_text(original)
    if updated == original:
        print(f"  {PKG}: 已是补丁形态（{', '.join(applied)}）")
        return False
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(updated)
    print(f"  {PKG}: 已打补丁 -> {', '.join(applied)}")
    return True


def patch_dir(root: str) -> None:
    target = os.path.join(root, REL)
    if not os.path.isfile(target):
        raise SystemExit(f"错误：找不到 {target}")
    patch_file(target)


def patch_zip(zip_path: str, backup_dir: str | None) -> None:
    with zipfile.ZipFile(zip_path, "r") as zf:
        names = [n for n in zf.namelist() if n.endswith(REL.replace(os.sep, "/"))]
        if not names:
            raise SystemExit(f"错误：zip 里找不到 {REL}")
        name = names[0]
        original = zf.read(name).decode("utf-8")
        updated, applied = patch_text(original)
        if updated == original:
            print(f"  {PKG}: zip 内已是补丁形态（{', '.join(applied)}）")
            return
        if backup_dir:
            os.makedirs(backup_dir, exist_ok=True)
            bak = os.path.join(backup_dir, os.path.basename(name) + ".orig")
            with open(bak, "wb") as f:
                f.write(original.encode("utf-8"))
            print(f"  备份原文件 -> {bak}")
        tmp = zip_path + ".tmp"
        with zipfile.ZipFile(zip_path, "r") as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
            for item in src.infolist():
                data = src.read(item.filename)
                if item.filename == name:
                    data = updated.encode("utf-8")
                dst.writestr(item, data)
        os.replace(tmp, zip_path)
        print(f"  {PKG}: zip 已打补丁 -> {', '.join(applied)}")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    backup_dir = None
    targets: list[str] = []
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
        print(f"{PKG} @ {target}")
        if os.path.isdir(target):
            patch_dir(target)
        elif os.path.isfile(target) and target.lower().endswith(".zip"):
            patch_zip(target, backup_dir)
        else:
            raise SystemExit(f"错误：既不是目录也不是 zip -> {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

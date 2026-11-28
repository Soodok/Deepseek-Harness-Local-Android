#!/usr/bin/env python3
"""git 源插件的构建脚本批准在 Android 上永远走不通（2026-11-28 用户截图反馈）。

## 现象

插件页安装 https://github.com/dsh-market/dsh-market 失败，弹窗只有一句
「有依赖的安装脚本被 pnpm 拦下，请在 profile 的 pnpm-workspace.yaml 的
allowBuilds 中放行后重试」，**没有**「批准并重试」按钮。

## 取证（本机实测，不是推测）

pnpm 12.21.0 对 git 源包的真实输出（夹具 scripts/probes/data/pnpm-git-dep-prepare.txt）：

    Error: ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED
      ╰─▶ The git-hosted package "dshmarket@1.82.0" needs to execute build scripts
          but is not in the "allowBuilds" allowlist.
      help: Add the package to "allowBuilds" in your project's pnpm-workspace.yaml
            to allow it to run scripts. For example:
            allowBuilds:
              dshmarket@https://codeload.github.com/dsh-market/dsh-market/
            tar.gz/18004a19b1c5bca1cd3ea405e2171a5fb3c089ce: true

两个关键形态：

1. help 段里的 allowBuilds: 是**缩进**的，上游 0.2.2 的正则要求顶格 ^allowBuilds:；
2. 长 URL 键被 pnpm 折成**两行**，上游只捕获单行 (.+?): true。

对真实夹具跑上游代码的结果：
  · 0.2.2-rc.2 的 GIT_DEP_PREPARE 正则**匹配失败**（实测 undefined）→
    pendingBuilds 恒为空 → UI 回落到 installFailureBuildBlockedManual（截图那句话）；
  · 0.2.1-rc.2 更彻底：LOG_KINDS 里连 ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED 都没有，
    失败被归成 unknown。

## 修法

把「解析 pnpm 建议的 allowBuilds 键」从单行正则换成逐行累加的函数
gitDepPrepareKey：允许缩进、允许键跨行；取到键后仍交 carriesCredential 过滤
（带凭据的 git 键不落盘、不进待批名单）。

只改解析，不改写盘逻辑（上游 recordPendingBuilds / approveBuilds 用 yaml 库，
任意字符串键都能安全序列化；本机实测把该键写成 true 后 pnpm 安装 exit 0）。

输入自动识别：目录（runtime 已解压）或 .zip，与其它 patch-*.py 一致。
幂等：打过就跳过。每代形态的锚点必须**唯一命中**，0 次或 >1 次都非零退出。
"""
from __future__ import annotations

import io
import os
import shutil
import sys
import zipfile

MARKER = "[dsh-android: build-approval]"

PM_DIR = "lib/node_modules/@deepseek-ai/dsh-plugin-manager"
BA_MEMBER = PM_DIR + "/lib/types/build-approval.js"
IDX_MEMBER = PM_DIR + "/lib/index.js"

# ── 形态 A（0.2.2-rc.*，设备上现跑的就是它）：有正则但写坏了 ──────────────
A_REGEX = (
    r"const GIT_DEP_PREPARE = /ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED"
    r"[\s\S]*?^allowBuilds:\r?\n[ \t]+(.+?): true$/m;"
)
A_USE = "const git = GIT_DEP_PREPARE.exec(output)?.[1];"
A_USE_PATCHED = "const git = gitDepPrepareKey(output);"

# ── 形态 B（0.2.1-rc.2，仓库内 _runtime_bak zip 就是它）：压根不认这个错误码 ──
B_LOG_KINDS = '["build-blocked", /ERR_PNPM_IGNORED_BUILDS|Ignored build scripts/],'
B_LOG_KINDS_PATCHED = (
    '["build-blocked", /ERR_PNPM_IGNORED_BUILDS|Ignored build scripts'
    "|ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED/],"
)

IGNORED_DECL = r"const IGNORED_BUILDS = /\bIgnored build scripts: (.+)/;"

B_BODY_ESM = """    const reported = IGNORED_BUILDS.exec(output)?.[1];
    if (reported === undefined)
        return [];
    return reported.split(',').map(name => name.trim()).filter(name => name !== '' && !/[*?]/.test(name));"""

B_BODY_ESM_NEW = """    const reported = IGNORED_BUILDS.exec(output)?.[1];
    const git = gitDepPrepareKey(output);
    const names = [...(reported === undefined ? [] : reported.split(',')), ...(git === undefined ? [] : [git])];
    return names.map(name => name.trim()).filter(name => name !== '' && !/[*?]/.test(name) && !carriesCredential(name));"""

B_BODY_CJS = "\tconst reported = IGNORED_BUILDS.exec(output)?.[1];\n" \
             "\tif (reported === void 0) return [];\n" \
             "\treturn reported.split(\",\").map((name) => name.trim()).filter((name) => name !== \"\" && !/[*?]/.test(name));"

B_BODY_CJS_NEW = "\tconst reported = IGNORED_BUILDS.exec(output)?.[1];\n" \
                 "\tconst git = gitDepPrepareKey(output);\n" \
                 "\tconst names = [...(reported === void 0 ? [] : reported.split(\",\")), ...(git === void 0 ? [] : [git])];\n" \
                 "\treturn names.map((name) => name.trim()).filter((name) => name !== \"\" && !/[*?]/.test(name) && !carriesCredential(name));"

HELPER = """/**
 * MARKER_PLACEHOLDER pnpm 的 help 段把 allowBuilds: 缩进写、把长 URL 键折成多行，
 * 上游那条「顶格 + 单行」正则因此永远匹配不到真实输出（表现为「请手动改 YAML」）。
 * 这里逐行累加：缩进不管，遇到 ": true" / ": false" 收尾即返回键。
 */
function gitDepPrepareKey(output) {
\tconst code = "ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED";
\tconst at = output.indexOf(code);
\tif (at === -1) return undefined;
\tconst head = output.indexOf("allowBuilds:", at);
\tif (head === -1) return undefined;
\tlet key = "";
\tfor (const raw of output.slice(head + "allowBuilds:".length).split("\\n")) {
\t\tif (raw.trim() === "") continue;
\t\tconst line = raw.trim();
\t\tconst suffix = line.endsWith(": true") ? ": true" : line.endsWith(": false") ? ": false" : undefined;
\t\tif (suffix === undefined) {
\t\t\tkey += line;
\t\t\tcontinue;
\t\t}
\t\tconst merged = (key + line.slice(0, -suffix.length)).trim();
\t\treturn merged === "" ? undefined : merged;
\t}
\treturn undefined;
}"""

CREDENTIAL_HELPER = """/**
 * MARKER_PLACEHOLDER 带凭据的 git 键不落盘：有密码，或 http(s) 里带用户名都算。
 * （与上游 carriesCredential 同一口径；形态 B 里原本没有这个函数。）
 */
function carriesCredential(key) {
\tconst at = key.indexOf("@git+");
\tif (at === -1) return false;
\ttry {
\t\tconst url = new URL(key.slice(at + "@git+".length).replace(/#.*$/s, ""));
\t\treturn url.password !== "" || (url.username !== "" && /^https?:$/.test(url.protocol));
\t} catch {
\t\treturn true;
\t}
}"""


class ShapeError(Exception):
    pass


def helper_text() -> str:
    return HELPER.replace("MARKER_PLACEHOLDER", MARKER)


def credential_text() -> str:
    return CREDENTIAL_HELPER.replace("MARKER_PLACEHOLDER", MARKER)


def once(text: str, anchor: str, label: str) -> None:
    n = text.count(anchor)
    if n != 1:
        raise ShapeError(f"锚点出现 {n} 次（期望 1）: {label}")


def patch_shape_a(text: str) -> tuple[str, str]:
    once(text, A_REGEX, "GIT_DEP_PREPARE 正则")
    once(text, A_USE, "GIT_DEP_PREPARE 使用点")
    return text.replace(A_REGEX, helper_text(), 1).replace(A_USE, A_USE_PATCHED, 1), "shape-a"


def patch_shape_b(text: str, member: str) -> tuple[str, str]:
    """形态 B：补错误码识别 + 助手函数 + git 键并进待批名单。"""
    if member.endswith("build-approval.js"):
        once(text, IGNORED_DECL, "IGNORED_BUILDS 声明（ESM）")
        once(text, B_BODY_ESM, "ignoredBuildNames 函数体（ESM）")
        text = text.replace(B_BODY_ESM, B_BODY_ESM_NEW, 1)
    else:
        once(text, B_LOG_KINDS, "LOG_KINDS build-blocked 行")
        once(text, IGNORED_DECL, "IGNORED_BUILDS 声明（CJS 内联）")
        once(text, B_BODY_CJS, "ignoredBuildNames 函数体（CJS 内联）")
        text = text.replace(B_LOG_KINDS, B_LOG_KINDS_PATCHED, 1)
        text = text.replace(B_BODY_CJS, B_BODY_CJS_NEW, 1)
    block = helper_text()
    if "function carriesCredential(" not in text:
        block = credential_text() + "\n\n" + block
    text = text.replace(IGNORED_DECL, IGNORED_DECL + "\n\n" + block, 1)
    return text, "shape-b"


def patch_text(text: str, member: str) -> tuple[str, str]:
    if MARKER in text:
        return text, "already-patched"
    if A_REGEX in text:
        return patch_shape_a(text)
    return patch_shape_b(text, member)


def write_one(path: str, text: str) -> None:
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def patch_dir(root: str) -> None:
    for member in (BA_MEMBER, IDX_MEMBER):
        path = os.path.join(root, member.replace("/", os.sep))
        if not os.path.isfile(path):
            raise ShapeError(f"找不到 {path}")
        with io.open(path, "r", encoding="utf-8", newline="") as f:
            text = f.read().replace("\r\n", "\n")
        updated, shape = patch_text(text, member)
        if updated == text:
            print(f"  {os.path.basename(path)}: 已是补丁形态（{shape}）")
        else:
            write_one(path, updated)
            print(f"  {os.path.basename(path)}: 已打补丁 -> {shape}")


def patch_zip(zip_path: str, backup_dir: str | None) -> None:
    with zipfile.ZipFile(zip_path, "r") as z:
        texts = {}
        for member in (BA_MEMBER, IDX_MEMBER):
            try:
                raw = z.read(member).decode("utf-8").replace("\r\n", "\n")
            except KeyError:
                raise ShapeError(f"zip 内没有 {member}")
            updated, shape = patch_text(raw, member)
            if updated != raw:
                texts[member] = updated
                print(f"  {member}: 已打补丁 -> {shape}")
            else:
                print(f"  {member}: 已是补丁形态（{shape}）")
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
                    out.writestr(info, texts[info.filename].encode("utf-8"))
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
    try:
        for target in targets:
            print(f"build-approval @ {target}")
            if os.path.isdir(target):
                patch_dir(target)
            elif os.path.isfile(target) and target.lower().endswith(".zip"):
                patch_zip(target, backup_dir)
            else:
                raise ShapeError(f"既不是目录也不是 zip -> {target}")
    except ShapeError as e:
        print(f"错误：{e}（上游形态变了？）", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

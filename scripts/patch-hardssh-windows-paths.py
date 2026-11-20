#!/usr/bin/env python3
r"""给内置的 @xiaopb/dsh-hardssh 打「Windows 远端路径」补丁。

背景（0.2.13 实测）
  远端路径层是纯 POSIX 假定：绑定 C:\Users\me 时
  - lib/health-*.js 的 normalizeRemoteRoot 用 posix.normalize 后检查 startsWith("/")，
  - lib/index.js 的 normalizeRemoteRoot 直接 if (root.includes("\\")) 抛 root-mismatch。
  Win32 OpenSSH 的 SFTP 只认 /C:/Users/me 这种形式，所以两种写法都在任何 SFTP 动作之前失败。

本补丁做两件事
  P1（路径方言化）——把 C:\x、C:/x、/c:/x 统一收敛成 /C:/x，并让「包含在根内」的比较在
      Windows 根下大小写不敏感（realpath 返回的盘符/大小写与用户输入常不一致）。
  P0（命令层明示不支持）——在 subprocess 的 resolveExecutable 里，对 Windows 根工作区直接
      抛出可读错误，而不是让 cmd.exe 去解析 POSIX 包壳命令后报语法错。

不动的部分：SFTP 读写/搜索（remote/sftp-search.ts 本来就不 shell out）、命令包装
（wrapCommandWithServerBudget 仍是 POSIX 形态，P2 再改）。

用法:
  python scripts/patch-hardssh-windows-paths.py --root <解包后的 dsh-hardssh 目录>
  python scripts/patch-hardssh-windows-paths.py --root <dir> --check   # 只检查是否已打
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

MARKER = "dsh-patch: windows-paths v1"

HELPER = r'''/** dsh-patch: windows-paths v1 —— Windows 远端路径方言收敛。
 *  Win32 OpenSSH 的 SFTP 只认 /C:/Users/me 这种 POSIX 形式；用户输入常见
 *  C:\Users\me / C:/Users/me / /c:/Users/me 三种写法，统一收敛成
 *  /C:/Users/me（盘符大写、正斜杠、去尾斜杠），下游 posix.* 逻辑才能照旧工作。 */
const DSH_WIN_DRIVE_ABS = /^(?:\/)?[A-Za-z]:[\\/]/;
function dshIsWindowsRemotePath(value) {
	return typeof value === "string" && (DSH_WIN_DRIVE_ABS.test(value) || value.includes("\\"));
}
function dshCanonicalRemotePath(value) {
	if (typeof value !== "string" || value.includes("\0")) return value;
	let path = value.replace(/\\/g, "/");
	if (/^\/[A-Za-z]:(\/|$)/.test(path)) path = path.slice(1);
	const bare = /^([A-Za-z]):(\/|$)/.exec(path);
	if (bare !== null) path = "/" + bare[1].toUpperCase() + ":" + path.slice(2);
	const normalized = posix.normalize(path);
	if (/^\/[A-Z]:\/?$/.test(normalized)) return normalized.slice(0, 3);
	return normalized === "/" ? "/" : normalized.replace(/\/+$/, "");
}
function dshIsAbsoluteRemotePath(value) {
	const path = dshCanonicalRemotePath(value);
	return typeof path === "string" && posix.isAbsolute(path);
}
function dshIsWindowsRemoteRoot(root) {
	return typeof root === "string" && /^\/[A-Za-z]:(\/|$)/.test(root);
}
'''

EDITS = [
    (
        "lib/index.js",
        "P1 normalizeRemoteRoot（backend）",
        r'''function normalizeRemoteRoot(root) {
	if (typeof root !== "string" || root.includes("\0")) throw new BackendError("invalid", "remote root is invalid");
	if (root.includes("\\") || !posix.isAbsolute(root)) throw new BackendError("root-mismatch", `remote root must be an absolute POSIX path (got '${root}')`);
	const normalized = posix.normalize(root);
	return normalized === "/" ? "/" : normalized.replace(/\/+$/, "");
}''',
        r'''function normalizeRemoteRoot(root) {
	if (typeof root !== "string" || root.includes("\0")) throw new BackendError("invalid", "remote root is invalid");
	const canonical = dshCanonicalRemotePath(root);
	if (!posix.isAbsolute(canonical)) throw new BackendError("root-mismatch", `remote root must be an absolute path (got '${root}')`);
	return canonical;
}''',
    ),
    (
        "lib/index.js",
        "P1 isInside（根包含比较）",
        r'''function isInside(root, abs) {
	const normalizedRoot = normalizeRemoteRoot(root);
	const normalizedAbs = posix.normalize(abs);
	if (!posix.isAbsolute(normalizedAbs)) return false;
	if (normalizedRoot === "/") return normalizedAbs.startsWith("/");
	return normalizedAbs === normalizedRoot || normalizedAbs.startsWith(`${normalizedRoot}/`);
}''',
        r'''function isInside(root, abs) {
	const normalizedRoot = normalizeRemoteRoot(root);
	const normalizedAbs = dshCanonicalRemotePath(abs);
	if (!posix.isAbsolute(normalizedAbs)) return false;
	const windows = dshIsWindowsRemoteRoot(normalizedRoot);
	const base = windows ? normalizedRoot.toLowerCase() : normalizedRoot;
	const target = windows ? normalizedAbs.toLowerCase() : normalizedAbs;
	if (base === "/") return target.startsWith("/");
	if (target === base) return true;
	return target.startsWith(`${base}/`);
}''',
    ),
    (
        "lib/index.js",
        "P1 posixParentPath（目录浏览）",
        r'''	const posixParentPath = (value) => {
		if (value.includes("\0") || value.includes("\\") || !value.startsWith("/")) return void 0;
		return posix.normalize(value);
	};''',
        r'''	const posixParentPath = (value) => {
		if (typeof value !== "string" || value.includes("\0")) return void 0;
		const canonical = dshCanonicalRemotePath(value);
		return posix.isAbsolute(canonical) ? canonical : void 0;
	};''',
    ),
    (
        "lib/index.js",
        "P1 目录浏览路由守卫",
        r'''						if (requested.includes("\0") || requested.includes("\\") || !requested.startsWith("/")) {
							writeJson(res, 400, { error: `path must be an absolute POSIX path (got '${requested}')` });
							return;
						}
						writeJson(res, 200, { entries: await engine.ls(alias, requested, request.signal) });''',
        r'''						if (requested.includes("\0")) {
							writeJson(res, 400, { error: `path must not contain NUL (got '${requested}')` });
							return;
						}
						const requestedPath = dshCanonicalRemotePath(requested);
						if (!posix.isAbsolute(requestedPath)) {
							writeJson(res, 400, { error: `path must be an absolute remote path (got '${requested}')` });
							return;
						}
						writeJson(res, 200, { entries: await engine.ls(alias, requestedPath, request.signal) });''',
    ),
    (
        "lib/index.js",
        "P1 建工作区守卫",
        r'''				if (!remoteRoot.startsWith("/")) {
					writeJson(res, 400, { error: `remoteRoot must be an absolute remote path (got '${remoteRoot}')` });
					return;
				}''',
        r'''				if (!posix.isAbsolute(dshCanonicalRemotePath(remoteRoot))) {
					writeJson(res, 400, { error: `remoteRoot must be an absolute remote path (got '${remoteRoot}')` });
					return;
				}''',
    ),
    (
        "lib/index.js",
        "P1 上传远端路径守卫",
        r'''		if (!remotePath.startsWith("/")) throw new Error(`remotePath must be an absolute path (got '${remotePath}')`);''',
        r'''		remotePath = dshCanonicalRemotePath(remotePath);
		if (!posix.isAbsolute(remotePath)) throw new Error(`remotePath must be an absolute path (got '${remotePath}')`);''',
    ),
    (
        "lib/index.js",
        "P1 confine（大小写不敏感）",
        r'''	confine(path, code = "FS_IO_ERROR") {
		if (this.confineRoot === void 0) return path;
		const root = this.confineRoot.endsWith("/") ? this.confineRoot : `${this.confineRoot}/`;
		if (path !== this.confineRoot && !path.startsWith(root)) throw new FsError(`workspace.ssh-outside-root: '${path}' is outside '${this.confineRoot}'`, code);
		return path;
	}''',
        r'''	confine(path, code = "FS_IO_ERROR") {
		if (this.confineRoot === void 0) return path;
		const windows = dshIsWindowsRemoteRoot(this.confineRoot);
		const base = windows ? this.confineRoot.toLowerCase() : this.confineRoot;
		const target = windows ? String(path).toLowerCase() : path;
		const root = base.endsWith("/") ? base : `${base}/`;
		if (target !== base && !target.startsWith(root)) throw new FsError(`workspace.ssh-outside-root: '${path}' is outside '${this.confineRoot}'`, code);
		return path;
	}''',
    ),
    (
        "lib/index.js",
        "P1 contains（大小写不敏感）",
        r'''	contains(parent, child) {
		const relative = posix.relative(this.processPath(parent), this.processPath(child));
		return relative === "" || relative !== ".." && !relative.startsWith("../") && !posix.isAbsolute(relative);
	}''',
        r'''	contains(parent, child) {
		const left = this.processPath(parent);
		const right = this.processPath(child);
		const windowsRoot = this.confineRoot === void 0 ? left : this.confineRoot;
		if (dshIsWindowsRemoteRoot(windowsRoot) || dshIsWindowsRemoteRoot(left)) {
			const self = left.toLowerCase();
			const other = right.toLowerCase();
			return other === self || other.startsWith(self.endsWith("/") ? self : `${self}/`);
		}
		const relative = posix.relative(left, right);
		return relative === "" || relative !== ".." && !relative.startsWith("../") && !posix.isAbsolute(relative);
	}''',
    ),
    (
        "lib/index.js",
        "P0 resolveExecutable（Windows 根明示不支持）",
        r'''	async resolveExecutable(command, env, signal) {
		if (command.length === 0) throw new Error("subprocess-ssh: executable name must be non-empty");
		signal?.throwIfAborted();
		const state = this.getState();
		if (state.mode !== "remote" || state.alias === void 0) throw new Error("subprocess-ssh: not in remote mode — switch the GUI to SSH mode first");''',
        r'''	async resolveExecutable(command, env, signal) {
		if (command.length === 0) throw new Error("subprocess-ssh: executable name must be non-empty");
		signal?.throwIfAborted();
		const state = this.getState();
		if (state.mode !== "remote" || state.alias === void 0) throw new Error("subprocess-ssh: not in remote mode — switch the GUI to SSH mode first");
		if (dshIsWindowsRemoteRoot(state.remoteRoot)) throw new Error(`subprocess-ssh: '${state.remoteRoot}' 在 Windows 主机上。当前版本只适配了 SFTP 文件读写/搜索，命令执行层（bash / 子进程）尚未适配 cmd.exe / pwsh，请改用文件类工具。`);''',
    ),
    (
        "lib/health-Byv4nAVe.js",
        "P1 normalizeRemoteRoot（ledger/health 副本）",
        r'''function normalizeRemoteRoot(raw) {
	if (raw.includes("\0")) throw new Error(`remoteRoot must not contain NUL (got '${raw}')`);
	const normalized = posix.normalize(raw.trim());
	if (!normalized.startsWith("/")) throw new Error(`remoteRoot must be an absolute POSIX path (got '${raw}')`);
	return normalized === "/" ? "/" : normalized.replace(/\/+$/, "");
}''',
        r'''function normalizeRemoteRoot(raw) {
	if (typeof raw !== "string" || raw.includes("\0")) throw new Error(`remoteRoot must not contain NUL (got '${raw}')`);
	const canonical = dshCanonicalRemotePath(raw.trim());
	if (!posix.isAbsolute(canonical)) throw new Error(`remoteRoot must be an absolute path (got '${raw}')`);
	return canonical;
}''',
    ),
]

HELPER_ANCHORS = [
    ("lib/index.js", "function normalizeRemoteRoot(root) {"),
    ("lib/health-Byv4nAVe.js", "function normalizeRemoteRoot(raw) {"),
]


def resolve_rel(root: Path, rel: str) -> Path:
    """上游 chunk 文件名带内容哈希（lib/health-Byv4nAVe.js）：按前缀 glob 解析。"""
    direct = root / rel
    if direct.is_file():
        return direct
    directory, _, name = rel.rpartition("/")
    stem = name.split("-")[0]
    matches = sorted((root / directory).glob(stem + "-*.js"))
    if len(matches) == 1:
        return matches[0]
    return direct


def apply(root: Path, check_only: bool) -> int:
    problems = []
    changed = []

    for rel, name, old, new in EDITS:
        path = resolve_rel(root, rel)
        if not path.is_file():
            problems.append(rel + ": 文件不存在")
            continue
        text = path.read_text(encoding="utf-8")
        if new in text:
            continue
        count = text.count(old)
        if count != 1:
            problems.append(name + ": 锚点命中 " + str(count) + " 次（期望 1）—— 上游改版了，review 后再改锚点")
            continue
        if check_only:
            problems.append(name + ": 未打补丁")
            continue
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
        changed.append(name)

    for rel, anchor in HELPER_ANCHORS:
        path = resolve_rel(root, rel)
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        if MARKER in text:
            continue
        hits = text.count(anchor)
        if hits != 1:
            problems.append(rel + ": 注入锚点命中 " + str(hits) + " 次（期望 1）")
            continue
        if check_only:
            problems.append(rel + ": 未注入 helper")
            continue
        path.write_text(text.replace(anchor, HELPER + anchor, 1), encoding="utf-8")
        changed.append(rel + ": helper")

    if problems:
        for p in problems:
            print("FAIL " + p, file=sys.stderr)
        return 1
    if check_only:
        print("OK 已打补丁（" + MARKER + "）")
    else:
        print("OK 已打 " + str(len(changed)) + " 处：")
        for c in changed:
            print("  - " + c)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="解包后的 dsh-hardssh 包目录")
    ap.add_argument("--check", action="store_true", help="只检查是否已打补丁")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    if not (root / "lib" / "index.js").is_file():
        print("FAIL " + str(root) + " 不是 dsh-hardssh 包目录（缺 lib/index.js）", file=sys.stderr)
        return 1
    return apply(root, args.check)


if __name__ == "__main__":
    raise SystemExit(main())

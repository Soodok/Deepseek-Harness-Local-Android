// 0.2.13 原始片段的快照（由 lib/index.js 按 patch 锚点切片生成，勿手改）。
// 供 tests/probes 在无网 CI 里跑 patch-hardssh-windows-paths.py 的锚点与行为断言。
import { posix } from "node:path";
class BackendError extends Error {}
class FsError extends Error {}
// ---- P1 normalizeRemoteRoot（backend） ----
function normalizeRemoteRoot(root) {
	if (typeof root !== "string" || root.includes("\0")) throw new BackendError("invalid", "remote root is invalid");
	if (root.includes("\\") || !posix.isAbsolute(root)) throw new BackendError("root-mismatch", `remote root must be an absolute POSIX path (got '${root}')`);
	const normalized = posix.normalize(root);
	return normalized === "/" ? "/" : normalized.replace(/\/+$/, "");
}

// ---- P1 isInside（根包含比较） ----
function isInside(root, abs) {
	const normalizedRoot = normalizeRemoteRoot(root);
	const normalizedAbs = posix.normalize(abs);
	if (!posix.isAbsolute(normalizedAbs)) return false;
	if (normalizedRoot === "/") return normalizedAbs.startsWith("/");
	return normalizedAbs === normalizedRoot || normalizedAbs.startsWith(`${normalizedRoot}/`);
}

// ---- P1 posixParentPath（目录浏览） ----
	const posixParentPath = (value) => {
		if (value.includes("\0") || value.includes("\\") || !value.startsWith("/")) return void 0;
		return posix.normalize(value);
	};

// ---- P1 目录浏览路由守卫 ----
						if (requested.includes("\0") || requested.includes("\\") || !requested.startsWith("/")) {
							writeJson(res, 400, { error: `path must be an absolute POSIX path (got '${requested}')` });
							return;
						}
						writeJson(res, 200, { entries: await engine.ls(alias, requested, request.signal) });

// ---- P1 建工作区守卫 ----
				if (!remoteRoot.startsWith("/")) {
					writeJson(res, 400, { error: `remoteRoot must be an absolute remote path (got '${remoteRoot}')` });
					return;
				}

// ---- P1 上传远端路径守卫 ----
		if (!remotePath.startsWith("/")) throw new Error(`remotePath must be an absolute path (got '${remotePath}')`);

// ---- P1 confine（大小写不敏感） ----
	confine(path, code = "FS_IO_ERROR") {
		if (this.confineRoot === void 0) return path;
		const root = this.confineRoot.endsWith("/") ? this.confineRoot : `${this.confineRoot}/`;
		if (path !== this.confineRoot && !path.startsWith(root)) throw new FsError(`workspace.ssh-outside-root: '${path}' is outside '${this.confineRoot}'`, code);
		return path;
	}

// ---- P1 contains（大小写不敏感） ----
	contains(parent, child) {
		const relative = posix.relative(this.processPath(parent), this.processPath(child));
		return relative === "" || relative !== ".." && !relative.startsWith("../") && !posix.isAbsolute(relative);
	}

// ---- P0 resolveExecutable（Windows 根明示不支持） ----
	async resolveExecutable(command, env, signal) {
		if (command.length === 0) throw new Error("subprocess-ssh: executable name must be non-empty");
		signal?.throwIfAborted();
		const state = this.getState();
		if (state.mode !== "remote" || state.alias === void 0) throw new Error("subprocess-ssh: not in remote mode — switch the GUI to SSH mode first");

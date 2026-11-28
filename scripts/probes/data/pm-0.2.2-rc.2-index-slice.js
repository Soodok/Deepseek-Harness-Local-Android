// [fixture] dsh-plugin-manager 0.2.2-rc.2 lib/index.js 的两段真实原文（设备 2026-11-28 拉取）
// 第 1 段：lib/index.js L1030-1040（LOG_KINDS）
* names, which the run's captured tail carries whatever the locale.
* @module @deepseek-ai/dsh-plugin-manager/install-failure
*/
/** Patterns in the order they decide: a specific code before the generic network family. */
const LOG_KINDS = [
	["build-blocked", /ERR_PNPM_IGNORED_BUILDS|Ignored build scripts|ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED/],
	["not-found", /ERR_PNPM_FETCH_404|\bE404\b|404 Not Found|Not Found - GET/],
	["no-matching-version", /ERR_PNPM_NO_MATCHING_VERSION|\bETARGET\b|No matching version/],
	["disk-full", /\bENOSPC\b|no space left on device/i],
	["permission", /\bEACCES\b|\bEPERM\b|permission denied/i],
	["integrity", /ERR_PNPM_TARBALL_INTEGRITY|ERR_PNPM_BAD_TARBALL_SIZE|\bEINTEGRITY\b/],
// [fixture] 中间省略若干行
// 第 2 段：lib/index.js L1242-1340（内联 build-approval 区）
//#region lib/types/build-approval.js
/** Approve pnpm's pending dependency scripts in the current profile's workspace settings. */
/** Value pnpm leaves for a build script that still needs a decision. */
const UNDECIDED_BUILD = "set this to true or false";
/** Where a non-interactive run names the build scripts it skipped. */
const IGNORED_BUILDS = /\bIgnored build scripts: (.+)/;
/** The `allowBuilds` key pnpm suggests when it refuses to prepare a git-hosted package; a subdirectory key can contain spaces. */
const GIT_DEP_PREPARE = /ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED[\s\S]*?^allowBuilds:\r?\n[ \t]+(.+?): true$/m;
/**
* Whether an `allowBuilds` key names a git URL that carries a credential: a password, or any user name over HTTP(S).
* Such a key is not recorded, so the credential stays out of the profile settings and the pending list.
*/
function carriesCredential(key) {
	const at = key.indexOf("@git+");
	if (at === -1) return false;
	const url = URL.parse(key.slice(at + 5).replace(/#.*$/s, ""));
	return url !== null && (url.password !== "" || url.username !== "" && /^https?:$/.test(url.protocol));
}
async function readPolicy(dir) {
	let text;
	try {
		text = await readFile(join(dir, "pnpm-workspace.yaml"), "utf8");
	} catch (error) {
		if (error.code !== "ENOENT") throw error;
		text = "{}\n";
	}
	const document = parseDocument(text);
	if (document.errors[0] !== void 0) throw document.errors[0];
	if (!isMap(document.contents)) throw new Error("pnpm-workspace.yaml must be a YAML mapping");
	const builds = document.get("allowBuilds");
	if (builds !== void 0 && !isMap(builds)) throw new Error("allowBuilds must be a YAML mapping");
	visit(builds ?? null, (_key, node) => {
		if (isAlias(node) || isNode(node) && "anchor" in node && node.anchor) throw new Error("allowBuilds must not contain YAML anchors or aliases");
	});
	const keys = isMap(builds) ? builds.items.flatMap(({ key }) => isScalar(key) && typeof key.value === "string" ? [key.value] : []) : [];
	return {
		document,
		pending: isMap(builds) ? builds.items.flatMap(({ key, value }) => isScalar(key) && typeof key.value === "string" && !/[*?]/.test(key.value) && isScalar(value) && value.value === UNDECIDED_BUILD ? [key.value] : []) : [],
		keys
	};
}
/**
* Record the build scripts a failed run reported as blocked, then list every `allowBuilds` key awaiting a decision.
*
* pnpm writes those policy entries itself only when it can prompt, and the
* manager always runs it non-interactively, so the run's own report is the
* record that survives.
* @param dir Current profile directory.
* @param output Captured output of the failed run.
* @returns Exact `allowBuilds` keys awaiting a build decision; wildcard rules are excluded.
*/
async function recordPendingBuilds(dir, output) {
	const { document, pending, keys } = await readPolicy(dir);
	const known = new Set(keys);
	const added = blockedBuildNames(output).filter((name) => !known.has(name));
	if (added.length === 0) return pending;
	for (const name of added) document.setIn(["allowBuilds", name], UNDECIDED_BUILD);
	await writeFileAtomic(join(dir, "pnpm-workspace.yaml"), String(document), { mode: 384 });
	return [...pending, ...added];
}
/**
* Read the `allowBuilds` keys a pnpm run reported as ignored build scripts or as a git-hosted package it refused to prepare.
* @param output Captured output of the run.
* @returns Exact keys in report order; wildcard patterns and git keys carrying a credential are excluded.
*/
function blockedBuildNames(output) {
	const ignored = IGNORED_BUILDS.exec(output)?.[1]?.split(",") ?? [];
	const git = GIT_DEP_PREPARE.exec(output)?.[1];
	return [...ignored, ...git === void 0 ? [] : [git]].map((name) => name.trim()).filter((name) => name !== "" && !/[*?]/.test(name) && !carriesCredential(name));
}
/** Persist approval without running scripts; the caller holds the profile manifest lock.
* @param dir Current profile directory.
* @param names Explicit package names from the pending build list.
* @throws If a name is no longer pending or allowBuilds contains YAML anchors or aliases; no approvals are written.
*/
async function approveBuilds(dir, names) {
	const { document, pending } = await readPolicy(dir);
	if (names.some((name) => !pending.includes(name))) throw new ManagementFailure("stale-approval");
	if (names.length === 0) return;
	for (const name of names) document.setIn(["allowBuilds", name], true);
	await writeFileAtomic(join(dir, "pnpm-workspace.yaml"), String(document), { mode: 384 });
}
//#endregion
//#region lib/types/github-connection.js
/** Bounded Git repository connection checks using the installer's Git configuration and environment. */
/**
* Check a GitHub repository before pnpm starts, without downloading or building its package.
* @param spec The parsed installation address; npm packages, local paths and other hosts are not checked.
* @param dir The profile directory where installation runs.
* @param options The connection deadline, output bound and operation cancellation.
* @returns As {@link checkGitConnection}, or undefined for a spec that does not name a GitHub repository.
*/
async function checkGithubConnection(spec, dir, options) {
	if (spec.kind !== "git") return void 0;
	const host = spec.host.toLowerCase().replace(/:.*$/, "");
	if (host !== "github.com" && !host.endsWith(".github.com")) return void 0;
	return checkGitConnection(spec, dir, options);
}
/**
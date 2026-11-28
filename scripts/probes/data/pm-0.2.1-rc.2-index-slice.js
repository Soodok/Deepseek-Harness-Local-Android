// [fixture] dsh-plugin-manager 0.2.1-rc.2 lib/index.js 的两段真实原文（_runtime_bak zip 2026-11-22）
// 第 1 段：lib/index.js L873-877（LOG_KINDS）
/** Patterns in the order they decide: a specific code before the generic network family. */
const LOG_KINDS = [
	["build-blocked", /ERR_PNPM_IGNORED_BUILDS|Ignored build scripts/],
	["not-found", /ERR_PNPM_FETCH_404|\bE404\b|404 Not Found|Not Found - GET/],
	["no-matching-version", /ERR_PNPM_NO_MATCHING_VERSION|\bETARGET\b|No matching version/],
// [fixture] 中间省略若干行
// 第 2 段：lib/index.js L1083-1140（内联 build-approval 区）
/** Approve pnpm's pending dependency scripts in the current profile's workspace settings. */
/** Value pnpm leaves for a build script that still needs a decision. */
const UNDECIDED_BUILD = "set this to true or false";
/** Where a non-interactive run names the build scripts it skipped. */
const IGNORED_BUILDS = /\bIgnored build scripts: (.+)/;
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
* Record the build scripts a failed run reported as ignored, then list every name awaiting a decision.
*
* pnpm writes those policy entries itself only when it can prompt, and the
* manager always runs it non-interactively, so the run's own report is the
* record that survives.
* @param dir Current profile directory.
* @param output Captured output of the failed run.
* @returns Exact package names awaiting a build decision; wildcard rules are excluded.
*/
async function recordPendingBuilds(dir, output) {
	const { document, pending, keys } = await readPolicy(dir);
	const known = new Set(keys);
	const added = ignoredBuildNames(output).filter((name) => !known.has(name));
	if (added.length === 0) return pending;
	for (const name of added) document.setIn(["allowBuilds", name], UNDECIDED_BUILD);
	await writeFileAtomic(join(dir, "pnpm-workspace.yaml"), String(document), { mode: 384 });
	return [...pending, ...added];
}
/**
* Read the package names a pnpm run reported as ignored build scripts.
* @param output Captured output of the run.
* @returns Exact names in report order; wildcard patterns are excluded.
*/
function ignoredBuildNames(output) {
	const reported = IGNORED_BUILDS.exec(output)?.[1];
	if (reported === void 0) return [];
	return reported.split(",").map((name) => name.trim()).filter((name) => name !== "" && !/[*?]/.test(name));
}
/** Persist approval without running scripts; the caller holds the profile manifest lock.
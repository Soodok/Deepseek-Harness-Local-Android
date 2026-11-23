// 0.2.13 原始片段的快照（由 lib/health-*.js 按 patch 锚点切片生成，勿手改）。
import { posix } from "node:path";
// ---- P1 normalizeRemoteRoot（ledger/health 副本） ----
function normalizeRemoteRoot(raw) {
	if (raw.includes("\0")) throw new Error(`remoteRoot must not contain NUL (got '${raw}')`);
	const normalized = posix.normalize(raw.trim());
	if (!normalized.startsWith("/")) throw new Error(`remoteRoot must be an absolute POSIX path (got '${raw}')`);
	return normalized === "/" ? "/" : normalized.replace(/\/+$/, "");
}

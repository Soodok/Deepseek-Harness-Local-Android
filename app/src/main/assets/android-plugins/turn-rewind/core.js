/**
 * [dsh-android] turn-rewind 核心：文件级回合快照与还原（无 cordis 依赖，可单测）。
 *
 * ## 为什么自建文件快照
 * rc.2 运行时**不带 git**（runtime zip 的 bin/ 里没有 git），所以官方
 * dsh-workspace-changes 的 snapshotTree 方案在 Android 上不可用；而它的
 * summary/diff 只活在会话存活期、且不持久化恢复所需内容。撤回要能在重启后
 * 依然可还原，只能自己落盘。
 *
 * ## 快照粒度
 * 每个**回合**（turn）一份 manifest：基线 = turn/start 时的目录扫描，
 * 改动 = turn 结束时再扫一次并按 sha256 对比。文件内容进内容寻址 blob 库
 * （同会话同内容只存一份）。fs intent 钩子在写入前抓到精确旧内容，
 * 覆盖扫描被配额截断或文件超限的情形，合并时 intent 优先。
 *
 * ## 还原语义（撤回第 T 轮及之后）
 * 每个被 T 轮及之后改过的路径取**最早**那条记录的 before 作为目标：
 *   before = null          该文件在 T 轮之前不存在 → 删除
 *   before = {blob,size}   写回该内容
 * 还原前逐文件比对"当前内容 == 该路径最后一次改动后的内容"，不等即冲突，
 * 列出清单而不静默覆盖。
 *
 * @module dsh-android-turn-rewind/core
 */

import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";

/** 默认跳过的目录名（构建产物、依赖、缓存、VCS）。 */
export const DEFAULT_SKIP_DIRS = [
  ".git", ".hg", ".svn", "node_modules", ".gradle", "build", "dist", "target",
  "__pycache__", ".dsh", ".venv", "venv", ".cache", ".next", ".turbo",
];

/** 默认上限。 */
export const DEFAULT_LIMITS = {
  maxFileBytes: 1024 * 1024,
  maxScanFiles: 4000,
  maxTurnBytes: 64 * 1024 * 1024,
  quotaBytes: 256 * 1024 * 1024,
};

/**
 * 判断一段字节是否二进制。只看 NUL 字节：GBK 等编码无法用 UTF-8 校验判定，
 * 而 NUL 在文本里几乎不存在。
 * @param {Buffer} buf - 文件字节。
 * @returns {boolean} true 表示二进制。
 */
export function looksBinary(buf) {
  const limit = Math.min(buf.length, 8192);
  for (let i = 0; i < limit; i += 1) if (buf[i] === 0) return true;
  return false;
}

/**
 * 计算字节的 sha256。
 * @param {Buffer} buf - 字节。
 * @returns {string} 十六进制摘要。
 */
export function sha256(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

/**
 * 规范化相对路径为 POSIX 形式（manifest 跨平台可读）。
 * @param {string} rel - 相对路径。
 * @returns {string} POSIX 形式的相对路径。
 */
function toPosix(rel) {
  return rel.split(path.sep).join("/");
}

/**
 * 判断 child 是否位于 parent 内（含相等）；用于阻止越界还原。
 * @param {string} parent - 已 realpath 的父目录。
 * @param {string} child - 目标路径。
 * @returns {boolean} 是否在内部。
 */
export function isInside(parent, child) {
  const rel = path.relative(parent, child);
  return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel));
}

/**
 * 把 errno 收敛成稳定的原因码，避免把带绝对路径的消息写进 manifest。
 * @param {unknown} error - 捕获的异常。
 * @returns {string} 原因码。
 */
export function describeFsError(error) {
  const code = error && typeof error === "object" ? error.code : undefined;
  return typeof code === "string" ? code : "unreadable";
}

/** 内容寻址 blob 库。 */
export class BlobStore {
  /**
   * @param {string} root - 会话快照根目录（其下建 blobs/）。
   * @param {{quotaBytes?: number}} [options] - 配额。
   */
  constructor(root, options = {}) {
    this.root = root;
    this.blobDir = path.join(root, "blobs");
    this.quotaBytes = options.quotaBytes ?? DEFAULT_LIMITS.quotaBytes;
  }

  /**
   * 读取一个 blob。
   * @param {string} hash - sha256。
   * @returns {Promise<Buffer|null>} blob 字节；缺失返回 null。
   */
  async read(hash) {
    try {
      return await fsp.readFile(path.join(this.blobDir, hash));
    } catch (error) {
      if (error && error.code === "ENOENT") return null;
      throw error;
    }
  }

  /**
   * 写入一个 blob（幂等：同内容同 hash 只存一次）。
   * @param {Buffer} buf - 内容。
   * @returns {Promise<{hash: string, created: boolean, bytes: number}>} 写入结果。
   */
  async write(buf) {
    const hash = sha256(buf);
    const target = path.join(this.blobDir, hash);
    if (fs.existsSync(target)) return { hash, created: false, bytes: buf.length };
    await fsp.mkdir(this.blobDir, { recursive: true });
    const temp = path.join(this.blobDir, "." + hash + "." + process.pid + "." + Date.now() + ".tmp");
    await fsp.writeFile(temp, buf);
    await fsp.rename(temp, target);
    return { hash, created: true, bytes: buf.length };
  }

  /**
   * 统计当前 blob 库占用。
   * @returns {Promise<{bytes: number, count: number}>} 占用字节与文件数。
   */
  async usage() {
    let bytes = 0;
    let count = 0;
    let names;
    try {
      names = await fsp.readdir(this.blobDir);
    } catch (error) {
      if (error && error.code === "ENOENT") return { bytes: 0, count: 0 };
      throw error;
    }
    for (const entry of names) {
      if (entry.startsWith(".")) continue;
      try {
        const stat = await fsp.stat(path.join(this.blobDir, entry));
        if (!stat.isFile()) continue;
        bytes += stat.size;
        count += 1;
      } catch {
        // 并发清理导致的瞬时缺失：忽略。
      }
    }
    return { bytes, count };
  }

  /**
   * 清理未被任何 manifest 引用的 blob（只在本会话目录内）。
   * @param {Set<string>} referenced - 被引用的 hash 集合。
   * @returns {Promise<{removed: number, freedBytes: number}>} 清理结果。
   */
  async collect(referenced) {
    let removed = 0;
    let freedBytes = 0;
    let names;
    try {
      names = await fsp.readdir(this.blobDir);
    } catch (error) {
      if (error && error.code === "ENOENT") return { removed, freedBytes };
      throw error;
    }
    for (const entry of names) {
      if (entry.startsWith(".") || referenced.has(entry)) continue;
      const target = path.join(this.blobDir, entry);
      try {
        const stat = await fsp.stat(target);
        await fsp.unlink(target);
        removed += 1;
        freedBytes += stat.size;
      } catch {
        // 已被并发删除：忽略。
      }
    }
    return { removed, freedBytes };
  }
}

/**
 * 扫描目录树，产出相对路径到元数据的映射。符号链接**不跟随**（记为 skipped），
 * 这样还原时不会因为链接指向工作区外而写坏别处文件。
 * @param {string} dir - 已存在的根目录。
 * @param {{skipDirs?: readonly string[], maxScanFiles?: number, maxFileBytes?: number}} [options] - 扫描上限。
 * @returns {Promise<{files: Map<string, object>, skipped: Array<object>, truncated: boolean, scanned: number}>} 扫描结果。
 */
export async function scanTree(dir, options = {}) {
  const skipDirs = new Set(options.skipDirs ?? DEFAULT_SKIP_DIRS);
  const maxScanFiles = options.maxScanFiles ?? DEFAULT_LIMITS.maxScanFiles;
  const maxFileBytes = options.maxFileBytes ?? DEFAULT_LIMITS.maxFileBytes;
  const files = new Map();
  const skipped = [];
  let truncated = false;
  const stack = [""];
  while (stack.length > 0) {
    const rel = stack.pop();
    if (rel === undefined) break;
    const abs = rel === "" ? dir : path.join(dir, rel);
    let entries;
    try {
      entries = await fsp.readdir(abs, { withFileTypes: true });
    } catch (error) {
      skipped.push({ path: toPosix(rel || "."), reason: describeFsError(error) });
      continue;
    }
    entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
      const childRel = rel === "" ? entry.name : path.join(rel, entry.name);
      const childAbs = path.join(dir, childRel);
      if (entry.isDirectory()) {
        if (skipDirs.has(entry.name)) continue;
        stack.push(childRel);
        continue;
      }
      if (entry.isSymbolicLink()) {
        skipped.push({ path: toPosix(childRel), reason: "symlink" });
        continue;
      }
      if (!entry.isFile()) {
        skipped.push({ path: toPosix(childRel), reason: "not-regular-file" });
        continue;
      }
      if (files.size >= maxScanFiles) {
        truncated = true;
        break;
      }
      let stat;
      try {
        stat = await fsp.stat(childAbs);
      } catch (error) {
        skipped.push({ path: toPosix(childRel), reason: describeFsError(error) });
        continue;
      }
      if (stat.size > maxFileBytes) {
        files.set(toPosix(childRel), { size: stat.size, hash: "", oversized: true });
        continue;
      }
      let buf;
      try {
        buf = await fsp.readFile(childAbs);
      } catch (error) {
        skipped.push({ path: toPosix(childRel), reason: describeFsError(error) });
        continue;
      }
      const info = { size: buf.length, hash: sha256(buf) };
      if (looksBinary(buf)) info.binary = true;
      files.set(toPosix(childRel), info);
    }
    if (truncated) break;
  }
  return { files, skipped, truncated, scanned: files.size };
}

/**
 * 建立一回合的基线：扫描 + 把可入快照的内容写入 blob 库。
 * @param {string} cwd - 会话工作目录。
 * @param {BlobStore} store - blob 库。
 * @param {object} [options] - 上限。
 * @returns {Promise<object>} 基线。
 */
export async function captureBaseline(cwd, store, options = {}) {
  const maxTurnBytes = options.maxTurnBytes ?? DEFAULT_LIMITS.maxTurnBytes;
  const scan = await scanTree(cwd, options);
  let bytes = 0;
  let cappedByBytes = false;
  for (const [rel, info] of scan.files) {
    if (info.oversized) continue;
    if (bytes >= maxTurnBytes) {
      cappedByBytes = true;
      info.hash = "";
      info.oversized = true;
      continue;
    }
    let buf;
    try {
      // 扫描与读内容之间文件可能被改：以读到的字节为准重算 hash。
      buf = await fsp.readFile(path.join(cwd, rel));
    } catch (error) {
      info.hash = "";
      info.oversized = true;
      scan.skipped.push({ path: rel, reason: describeFsError(error) });
      continue;
    }
    const written = await store.write(buf);
    info.hash = written.hash;
    info.size = buf.length;
    if (looksBinary(buf)) info.binary = true;
    bytes += buf.length;
    if (bytes >= maxTurnBytes) cappedByBytes = true;
  }
  return { files: scan.files, skipped: scan.skipped, truncated: scan.truncated, bytes, cappedByBytes };
}

/**
 * 计算一回合的改动清单。
 * @param {object} input - baseline / cwd / store / intents / options。
 * @returns {Promise<object>} 改动清单。
 */
export async function diffTurn(input) {
  const baseline = input.baseline;
  const cwd = input.cwd;
  const intents = input.intents ?? new Map();
  const options = input.options ?? {};
  const current = await scanTree(cwd, options);
  const files = [];
  const skipped = [...baseline.skipped, ...current.skipped];
  const known = new Set([...baseline.files.keys(), ...current.files.keys(), ...intents.keys()]);
  for (const rel of [...known].sort()) {
    const before = baseline.files.get(rel);
    const after = current.files.get(rel);
    const intent = intents.get(rel);
    // intent 是写入前抓到的权威旧状态，比扫描基线更精确。
    // intent 抓取时文件超限会带空的 blob；那等于"没有可用基线"，标成不可用，
    // 让还原阶段如实报 snapshot-oversized 而不是拿空内容覆盖文件。
    const beforeState = intent !== undefined
      ? (intent.absent === true ? null : (intent.blob === "" ? { unavailable: "oversized", size: intent.size } : { blob: intent.blob, size: intent.size, binary: intent.binary === true }))
      : (before === undefined ? null : (before.oversized ? { unavailable: "oversized" } : { blob: before.hash, size: before.size, binary: before.binary === true }));
    const afterState = after === undefined
      ? null
      : (after.oversized ? { unavailable: "oversized" } : { blob: after.hash, size: after.size, binary: after.binary === true });
    const beforeKey = beforeState === null ? "absent" : JSON.stringify(beforeState);
    const afterKey = afterState === null ? "absent" : JSON.stringify(afterState);
    if (beforeKey === afterKey) continue;
    const status = beforeState === null && afterState !== null ? "created"
      : beforeState !== null && afterState === null ? "deleted"
        : "modified";
    const incomplete = (beforeState !== null && beforeState.unavailable !== undefined)
      || (afterState !== null && afterState.unavailable !== undefined);
    const record = { path: rel, status, before: beforeState, after: afterState };
    if (incomplete) record.incomplete = true;
    files.push(record);
  }
  return { files, skipped, truncated: baseline.truncated || current.truncated };
}

/**
 * 把清单写成一个回合的 manifest（原子写）。
 * @param {string} sessionDir - 会话快照根目录。
 * @param {object} manifest - manifest 内容。
 * @returns {Promise<void>} 完成。
 */
export async function writeManifest(sessionDir, manifest) {
  const dir = path.join(sessionDir, "turns");
  await fsp.mkdir(dir, { recursive: true });
  const target = path.join(dir, String(manifest.turn) + ".json");
  const temp = target + "." + process.pid + "." + Date.now() + ".tmp";
  await fsp.writeFile(temp, JSON.stringify(manifest, null, 2));
  await fsp.rename(temp, target);
}

/**
 * 读取一个会话全部 manifest（按 turn 升序）。
 * @param {string} sessionDir - 会话快照根目录。
 * @returns {Promise<Array<object>>} manifest 列表；目录缺失返回空。
 */
export async function readManifests(sessionDir) {
  const dir = path.join(sessionDir, "turns");
  let names;
  try {
    names = await fsp.readdir(dir);
  } catch (error) {
    if (error && error.code === "ENOENT") return [];
    throw error;
  }
  const out = [];
  for (const entry of names) {
    if (!entry.endsWith(".json")) continue;
    try {
      out.push(JSON.parse(await fsp.readFile(path.join(dir, entry), "utf8")));
    } catch {
      // 半个 manifest（断电）视为不可用：跳过但保留文件供人工排查。
    }
  }
  out.sort((a, b) => a.turn - b.turn);
  return out;
}

/**
 * 计算"撤回第 targetTurn 轮及之后"的还原计划。
 * @param {Array<object>} manifests - 该会话全部 manifest。
 * @param {number} targetTurn - 目标回合号（含）。
 * @returns {{plan: Array<object>, turns: number[]}} 每路径的目标状态与涉及回合。
 */
export function planRewind(manifests, targetTurn) {
  const suffix = manifests.filter((m) => m.turn >= targetTurn);
  const earliest = new Map();
  for (const manifest of suffix) {
    for (const file of manifest.files ?? []) {
      if (!earliest.has(file.path)) earliest.set(file.path, { file, turn: manifest.turn });
    }
  }
  const plan = [];
  for (const [rel, entry] of earliest) {
    const before = entry.file.before ?? null;
    plan.push({
      path: rel,
      target: before === null ? null : (before.unavailable !== undefined ? { unavailable: before.unavailable } : { blob: before.blob, size: before.size, binary: before.binary === true }),
      firstTurn: entry.turn,
      last: latestStateFor(suffix, rel),
    });
  }
  plan.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { plan, turns: suffix.map((m) => m.turn) };
}

/**
 * 找出某路径在给定 manifest 序列里最后一次改动后的状态（用于冲突检测）。
 * @param {Array<object>} manifests - manifest 序列。
 * @param {string} rel - 相对路径。
 * @returns {object|null} after 状态。
 */
function latestStateFor(manifests, rel) {
  let last = null;
  for (const manifest of manifests) {
    for (const file of manifest.files ?? []) if (file.path === rel) last = file.after ?? null;
  }
  return last;
}

/**
 * 还原一个文件到目标状态。
 * @param {object} input - cwd / store / item。
 * @returns {Promise<object>} 单项结果。
 */
export async function restoreFile(input) {
  const cwd = input.cwd;
  const store = input.store;
  const item = input.item;
  const root = await fsp.realpath(cwd);
  const absolute = path.resolve(root, item.path);
  if (!isInside(root, absolute)) return { path: item.path, action: "skip", ok: false, reason: "outside-workspace" };
  const parent = path.dirname(absolute);
  let parentReal;
  try {
    parentReal = await fsp.realpath(parent);
  } catch {
    parentReal = parent;
    while (!isInside(root, parentReal) && parentReal !== path.dirname(parentReal)) parentReal = path.dirname(parentReal);
    if (!isInside(root, parentReal)) return { path: item.path, action: "skip", ok: false, reason: "symlink-escape" };
  }
  if (!isInside(root, parentReal)) return { path: item.path, action: "skip", ok: false, reason: "symlink-escape" };
  const target = item.target;
  if (target !== null && target.unavailable !== undefined) {
    return { path: item.path, action: "skip", ok: false, reason: "snapshot-" + target.unavailable };
  }
  if (target === null) {
    try {
      const stat = await fsp.lstat(absolute);
      if (stat.isDirectory()) return { path: item.path, action: "skip", ok: false, reason: "is-directory" };
      await fsp.unlink(absolute);
      return { path: item.path, action: "deleted", ok: true };
    } catch (error) {
      if (error && error.code === "ENOENT") return { path: item.path, action: "already-absent", ok: true };
      return { path: item.path, action: "delete-failed", ok: false, reason: describeFsError(error) };
    }
  }
  const blob = await store.read(target.blob);
  if (blob === null) return { path: item.path, action: "skip", ok: false, reason: "blob-missing" };
  await fsp.mkdir(parent, { recursive: true });
  const temp = path.join(parent, ".turn-rewind." + process.pid + "." + Date.now() + ".tmp");
  await fsp.writeFile(temp, blob);
  await fsp.rename(temp, absolute);
  return { path: item.path, action: "restored", ok: true };
}

/**
 * 冲突检测：当前磁盘状态是否仍等于"该路径在序列中最后一次改动后的状态"。
 * @param {object} input - cwd / plan。
 * @returns {Promise<Array<object>>} 冲突清单；空数组表示可以直接还原。
 */
export async function detectConflicts(input) {
  const cwd = input.cwd;
  const plan = input.plan;
  const conflicts = [];
  for (const item of plan) {
    const absolute = path.resolve(cwd, item.path);
    let current = null;
    try {
      const stat = await fsp.lstat(absolute);
      if (stat.isSymbolicLink()) {
        conflicts.push({ path: item.path, reason: "symlink-now" });
        continue;
      }
      if (!stat.isFile()) {
        conflicts.push({ path: item.path, reason: "not-regular-file" });
        continue;
      }
      const buf = await fsp.readFile(absolute);
      current = { blob: sha256(buf), size: buf.length };
    } catch (error) {
      if (!(error && error.code === "ENOENT")) {
        conflicts.push({ path: item.path, reason: describeFsError(error) });
        continue;
      }
      current = null;
    }
    const expected = item.last;
    if (expected === null) {
      if (current !== null) conflicts.push({ path: item.path, reason: "changed-after-turn" });
      continue;
    }
    if (expected.unavailable !== undefined) {
      // 记录不完整时无法证明当前状态属于本任务，按冲突处理（可 force 跳过）。
      conflicts.push({ path: item.path, reason: "snapshot-incomplete" });
      continue;
    }
    if (current === null || current.blob !== expected.blob) {
      conflicts.push({ path: item.path, reason: "changed-after-turn" });
    }
  }
  return conflicts;
}

/**
 * 汇总 manifest 里引用的全部 blob（用于 GC）。
 * @param {Array<object>} manifests - manifest 列表。
 * @returns {Set<string>} 被引用的 hash。
 */
export function referencedBlobs(manifests) {
  const set = new Set();
  for (const manifest of manifests) {
    for (const file of manifest.files ?? []) {
      for (const state of [file.before, file.after]) {
        if (state && typeof state.blob === "string" && state.blob !== "") set.add(state.blob);
      }
    }
  }
  return set;
}
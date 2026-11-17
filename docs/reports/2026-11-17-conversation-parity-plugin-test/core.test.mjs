

import test from "node:test";
import assert from "node:assert/strict";
import fsp from "node:fs/promises";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import {
  BlobStore, captureBaseline, detectConflicts, diffTurn, planRewind,
  readManifests, restoreFile, rewindForkFirst, scanTree, writeManifest, sha256, looksBinary,
} from "../../../app/src/main/assets/android-plugins/turn-rewind/core.js";

/** 建一个临时工作区。 */
async function workspace() {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), "turn-rewind-ws-"));
  return dir;
}

/** 建一个临时快照根。 */
async function snapRoot() {
  return await fsp.mkdtemp(path.join(os.tmpdir(), "turn-rewind-snap-"));
}

/** 记录一个回合的 manifest（基线 + 变更后扫描）。 */
async function record(sessionDir, cwd, turn, mutate, store) {
  const baseline = await captureBaseline(cwd, store);
  await mutate();
  const diff = await diffTurn({ baseline, cwd, store, intents: new Map() });
  await writeManifest(sessionDir, {
    version: 1, sessionId: "s", turn, files: diff.files,
    skipped: diff.skipped, truncated: diff.truncated,
  });
  return diff;
}

test("scanTree 跳过 node_modules/.git 与符号链接", async () => {
  const cwd = await workspace();
  await fsp.mkdir(path.join(cwd, "src"), { recursive: true });
  await fsp.writeFile(path.join(cwd, "src", "a.txt"), "a");
  await fsp.mkdir(path.join(cwd, "node_modules", "x"), { recursive: true });
  await fsp.writeFile(path.join(cwd, "node_modules", "x", "index.js"), "x");
  await fsp.mkdir(path.join(cwd, "build"), { recursive: true });
  await fsp.writeFile(path.join(cwd, "build", "out.bin"), "y");
  await fsp.writeFile(path.join(cwd, "outside.txt"), "outside");
  try {
    await fsp.symlink(path.join(cwd, "outside.txt"), path.join(cwd, "link.txt"));
  } catch {
    // Windows 无权限时跳过链接断言。
  }
  const scan = await scanTree(cwd);
  assert.ok(scan.files.has("src/a.txt"));
  assert.equal(scan.files.has("node_modules/x/index.js"), false);
  assert.equal(scan.files.has("build/out.bin"), false);
  assert.equal(scan.files.has("link.txt"), false);
  assert.ok(scan.skipped.some((s) => s.reason === "symlink") || process.platform === "win32");
});

test("自由函数：looksBinary 只按 NUL 判定", () => {
  assert.equal(looksBinary(Buffer.from("hello 世界")), false);
  assert.equal(looksBinary(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x00, 0x01])), true);
});

test("新建/修改/删除文件都被记入 manifest，并能精确还原", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "keep.txt"), "keep");
  await fsp.writeFile(path.join(cwd, "modify.txt"), "v1");

  const diff = await record(sessionDir, cwd, 1, async () => {
    await fsp.writeFile(path.join(cwd, "modify.txt"), "v2");
    await fsp.writeFile(path.join(cwd, "new.txt"), "created");
    await fsp.unlink(path.join(cwd, "keep.txt"));
  }, store);

  const byPath = new Map(diff.files.map((f) => [f.path, f]));
  assert.equal(byPath.get("modify.txt").status, "modified");
  assert.equal(byPath.get("new.txt").status, "created");
  assert.equal(byPath.get("keep.txt").status, "deleted");

  const manifests = await readManifests(sessionDir);
  assert.equal(manifests.length, 1);
  const planned = planRewind(manifests, 1);
  assert.deepEqual(await detectConflicts({ cwd, plan: planned.plan }), []);

  for (const item of planned.plan) {
    const outcome = await restoreFile({ cwd, store, item });
    assert.equal(outcome.ok, true, JSON.stringify(outcome));
  }
  assert.equal(await fsp.readFile(path.join(cwd, "modify.txt"), "utf8"), "v1");
  assert.equal(await fsp.readFile(path.join(cwd, "keep.txt"), "utf8"), "keep");
  assert.equal(fs.existsSync(path.join(cwd, "new.txt")), false);
});

test("二进制文件按字节还原，不经过文本层", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  const original = Buffer.from([0x00, 0x01, 0x02, 0xff, 0xfe]);
  await fsp.writeFile(path.join(cwd, "bin.dat"), original);

  await record(sessionDir, cwd, 1, async () => {
    await fsp.writeFile(path.join(cwd, "bin.dat"), Buffer.from([0x09, 0x09]));
  }, store);
  const manifests = await readManifests(sessionDir);
  assert.equal(manifests[0].files[0].before.binary, true);
  const planned = planRewind(manifests, 1);
  for (const item of planned.plan) assert.equal((await restoreFile({ cwd, store, item })).ok, true);
  assert.deepEqual(await fsp.readFile(path.join(cwd, "bin.dat")), original);
});

test("撤回多个回合时取最早一条 before，而不是最近一条", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "f.txt"), "turn0");

  await record(sessionDir, cwd, 1, async () => { await fsp.writeFile(path.join(cwd, "f.txt"), "turn1"); }, store);
  await record(sessionDir, cwd, 2, async () => { await fsp.writeFile(path.join(cwd, "f.txt"), "turn2"); }, store);

  const manifests = await readManifests(sessionDir);
  const planned = planRewind(manifests, 1);
  for (const item of planned.plan) assert.equal((await restoreFile({ cwd, store, item })).ok, true);
  assert.equal(await fsp.readFile(path.join(cwd, "f.txt"), "utf8"), "turn0");
});

test("外部修改被检测为冲突，且不被静默覆盖", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "f.txt"), "base");
  await record(sessionDir, cwd, 1, async () => { await fsp.writeFile(path.join(cwd, "f.txt"), "agent"); }, store);

  // 回合之后用户在外面又改了一次。
  await fsp.writeFile(path.join(cwd, "f.txt"), "user-edit");

  const manifests = await readManifests(sessionDir);
  const planned = planRewind(manifests, 1);
  const conflicts = await detectConflicts({ cwd, plan: planned.plan });
  assert.equal(conflicts.length, 1);
  assert.equal(conflicts[0].path, "f.txt");
  assert.equal(await fsp.readFile(path.join(cwd, "f.txt"), "utf8"), "user-edit");
});

test("缺快照（插件启用前的历史）如实报 snapshot 缺失，不产生假成功", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "f.txt"), "current");

  const manifests = [{ version: 1, sessionId: "s", turn: 1, files: [
    { path: "f.txt", status: "modified", before: { unavailable: "oversized" }, after: { blob: sha256(Buffer.from("current")), size: 7 } },
  ] }];
  const planned = planRewind(manifests, 1);
  const outcome = await restoreFile({ cwd, store, item: planned.plan[0] });
  assert.equal(outcome.ok, false);
  assert.equal(outcome.reason, "snapshot-oversized");
});

test("blob 缺失时报 blob-missing（不写入空文件）", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "f.txt"), "now");
  const outcome = await restoreFile({
    cwd, store,
    item: { path: "f.txt", target: { blob: "0".repeat(64), size: 3 }, last: null, firstTurn: 1 },
  });
  assert.equal(outcome.ok, false);
  assert.equal(outcome.reason, "blob-missing");
  assert.equal(await fsp.readFile(path.join(cwd, "f.txt"), "utf8"), "now");
});

test("越界路径（../）被拒绝", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  const outcome = await restoreFile({
    cwd, store,
    item: { path: "../escape.txt", target: null, last: null, firstTurn: 1 },
  });
  assert.equal(outcome.ok, false);
  assert.equal(outcome.reason, "outside-workspace");
});

test("同一内容只存一份 blob（内容寻址）", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "a.txt"), "same");
  await fsp.writeFile(path.join(cwd, "b.txt"), "same");
  await captureBaseline(cwd, store);
  const usage = await store.usage();
  assert.equal(usage.count, 1);
});

test("单文件超限标记 oversized，不中断其余文件", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "small.txt"), "s");
  await fsp.writeFile(path.join(cwd, "big.bin"), Buffer.alloc(64));
  const scan = await scanTree(cwd, { maxFileBytes: 16 });
  assert.equal(scan.files.get("big.bin").oversized, true);
  assert.equal(scan.files.get("small.txt").oversized, undefined);
});

test("fork 失败时一个文件都不动，撤回不会造成不可见的数据改动", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "f.txt"), "base");
  await record(sessionDir, cwd, 1, async () => { await fsp.writeFile(path.join(cwd, "f.txt"), "agent"); }, store);

  const manifests = await readManifests(sessionDir);
  const planned = planRewind(manifests, 1);
  let restored = 0;
  const outcome = await rewindForkFirst({
    plan: { items: planned.plan, conflicts: [], restore: async () => { restored += 1; return { ok: true }; } },
    fork: async () => { throw Object.assign(new Error("gateway refused"), { code: "fork-failed" }); },
    restore: async (entry) => { restored += 1; return await restoreFile({ cwd, store, item: entry.item }); },
  });

  assert.equal(outcome.ok, false);
  assert.equal(outcome.error.code, "fork-failed");
  assert.equal(restored, 0, "fork 失败后不应有任何文件被还原");
  assert.equal(await fsp.readFile(path.join(cwd, "f.txt"), "utf8"), "agent");
});

test("撤回先 fork 再还原文件：还原发生在 fork 成功之后", async () => {
  const cwd = await workspace();
  const sessionDir = await snapRoot();
  const store = new BlobStore(sessionDir);
  await fsp.writeFile(path.join(cwd, "f.txt"), "base");
  await record(sessionDir, cwd, 1, async () => { await fsp.writeFile(path.join(cwd, "f.txt"), "agent"); }, store);

  const order = [];
  const manifests = await readManifests(sessionDir);
  const planned = planRewind(manifests, 1);
  const outcome = await rewindForkFirst({
    plan: { items: planned.plan, conflicts: [] },
    fork: async () => { order.push("fork"); return { sessionId: "child" }; },
    restore: async (entry) => {
      order.push("restore:" + await fsp.readFile(path.join(cwd, "f.txt"), "utf8"));
      return await restoreFile({ cwd, store, item: entry.item });
    },
  });

  assert.equal(outcome.ok, true);
  assert.deepEqual(order, ["fork", "restore:agent"]);
  assert.equal(await fsp.readFile(path.join(cwd, "f.txt"), "utf8"), "base");
});

test("只撤回文本时不碰任何文件", async () => {
  let restored = 0;
  const outcome = await rewindForkFirst({
    plan: undefined,
    fork: async () => ({ sessionId: "child" }),
    restore: async () => { restored += 1; return { ok: true }; },
  });
  assert.equal(outcome.ok, true);
  assert.equal(outcome.files.applied, false);
  assert.equal(restored, 0);
});

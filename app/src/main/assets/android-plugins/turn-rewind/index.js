/**
 * [dsh-android] turn-rewind 宿主侧插件：撤回/重做所需的权威后端。
 *
 * ## 与上游的关系
 * rc.2 没有任何 rewind/undo 包；session 的 surface 只有 append 与 replace 两种
 * surfaceOp，而人类 transcript 取 append 来源、replace 只喂模型，所以"撤回"
 * **不能**靠遮蔽或截断日志实现。这里走权威路径：`session/fork(atSeq)` 复制到
 * 目标回合之前的前缀，生成子会话；原会话一个字节都不动，随时可回原分支。
 *
 * ## 端点（Typert Gateway，命名空间 turnRewind）
 *   turnRewind/list    列出该会话每个回合的文件变更摘要（来自本地快照）
 *   turnRewind/rewind  撤回：可只改文本（fork），或文本 + 文件回滚
 *
 * ## 幂等与并发
 * 每个 requestId 的结论写入 `rewinds/<requestId>.json`，重放同一 requestId
 * 直接返回原结论（不重复 fork、不重复写文件）。同一会话的 rewind 串行执行
 * （Promise 链），避免两次并发回滚互相覆盖。
 *
 * ## 边界
 * · 只处理 origin!=='subagent' 且 delegationDepth===0 的会话（与 workspace-changes 一致）；
 * · 快照根目录固定在 `$DSH_HOME/turn-rewind`，清理只发生在该目录内；
 * · 还原前逐文件做冲突检测，冲突时**不覆盖**，返回清单交给 UI 决策；
 * · 未记录过快照的历史（插件启用前）如实返回 no-snapshot，不假装能恢复。
 *
 * @module dsh-android-turn-rewind
 */

import fsp from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  BlobStore, DEFAULT_LIMITS, captureBaseline, detectConflicts, diffTurn,
  planRewind, readManifests, referencedBlobs, restoreFile, rewindForkFirst, writeManifest,
} from "./core.js";

/** Cordis 插件名。 */
export const name = "android-turn-rewind";

/** 依赖：会话存储、fs、以及内建 Remote 基类所在包。 */
export const inject = ["sessions", "fs", "sessionPersistence"];

/** 快照根目录名（位于 $DSH_HOME 下）。 */
const ROOT_DIR_NAME = "turn-rewind";

/** 解析 engine 根：本文件在 engine/android-plugins/turn-rewind/ 下。 */
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ENGINE_ROOT = path.resolve(HERE, "..", "..");

/**
 * 按绝对路径加载 typert 协议包。
 *
 * ⚠️ 不能用裸包名：插件位于 engine/android-plugins/，不在 node 的解析链上
 * （engine/ 下没有 node_modules），裸名解析必然失败且日志只留一行
 * "failed to import"。fast-mode.mjs 已经踩过同一个坑。
 * @returns {Promise<object>} 模块命名空间。
 */
async function loadTypert() {
  const candidates = [
    path.join(ENGINE_ROOT, "lib", "node_modules", "@deepseek-ai", "dsh-typert-protocol", "lib", "index.js"),
    path.join(ENGINE_ROOT, "node_modules", "@deepseek-ai", "dsh-typert-protocol", "lib", "index.js"),
  ];
  for (const candidate of candidates) {
    try {
      await fsp.access(candidate);
      return await import(pathToFileURL(candidate).href);
    } catch {
      // 试下一个候选。
    }
  }
  throw new Error("turn-rewind: cannot locate @deepseek-ai/dsh-typert-protocol");
}

/** 快照根目录：$DSH_HOME/turn-rewind。 */
function rootDir() {
  const home = process.env.DSH_HOME || "";
  if (home === "") return undefined;
  return path.join(home, ROOT_DIR_NAME);
}

/** 仅顶层会话参与记录。 */
function eligible(session) {
  const header = session.header;
  if (header.cwd === undefined) return undefined;
  if (header.origin === "subagent") return undefined;
  if ((header.delegationDepth ?? 0) > 0) return undefined;
  return header.cwd;
}

/** 业务失败（冻结对象，序列化安全）。 */
function rejected(code, extra) {
  return Object.freeze({ ok: false, error: Object.freeze({ code: code, ...(extra ?? {}) }) });
}

/** 业务成功。 */
function success(value) {
  return Object.freeze({ ok: true, value: Object.freeze(value) });
}

/**
 * 插件体。
 * @param {object} ctx - 宿主上下文。
 */
export async function apply(ctx) {
  const root = rootDir();
  if (root === undefined) {
    ctx.logger.warn("turn-rewind: DSH_HOME is not set; turn snapshots are disabled");
    return;
  }
  const typert = await loadTypert();
  const { TypertRemoteService } = typert;

  /** 会话快照目录。 */
  const sessionDirOf = (sessionId) => path.join(root, sessionId);
  const storeOf = (sessionId) => new BlobStore(sessionDirOf(sessionId), { quotaBytes: DEFAULT_LIMITS.quotaBytes });

  /** 进行中的回合记录：sessionId → { turn, baseline, cwd, intents } */
  const active = new Map();
  /** exec → sessionId，用于把 fs intent 归到会话。 */
  const execOwner = new WeakMap();
  /** 每会话串行队列。 */
  const tails = new Map();

  /** 读取会话事件（用于定位目标 seq 与它所属的回合号）。 */
  /**
   * 读一个会话的完整事件日志（含 data）。
   *
   * ⚠️ 不能用 `sessionQuery.listEvents`：它返回的是**轻量记录**
   * （{sessionId, seq, type, time, surface}），没有 `data`，读取
   * `event.data.turn` 会直接抛 "Cannot read properties of undefined"。
   * 这里走 `sessionPersistence` 的只读句柄拿原始事件（含 data），并以
   * seq 作为下标 —— 存储保证事件从 0 连续，所以 index 与 seq 一一对应，
   * 撤回时可以直接 `events[seq]` 定位。
   *
   * @param {string} sessionId - 会话标识。
   * @returns {Promise<Array<object>|undefined>} 事件数组；读不到则 undefined。
   */
  const eventsOf = async (sessionId) => {
    const persistence = ctx.get("sessionPersistence");
    if (persistence !== undefined) {
      try {
        const handle = await persistence.open(sessionId, "read");
        try {
          const slice = await handle.read();
          return slice.events;
        } finally {
          await handle.close();
        }
      } catch (error) {
        ctx.logger.warn("turn-rewind: persistence read failed", error);
      }
    }
    const query = ctx.get("sessionQuery");
    if (query === undefined) return undefined;
    try {
      const records = await query.listEvents(sessionId);
      // 兜底：记录里没有 data 时无法判断来源，宁可报"读不到"也不猜。
      if (!Array.isArray(records) || records.length === 0) return [];
      if (records.every((record) => record !== null && typeof record === "object" && record.data === undefined)) {
        return undefined;
      }
      return records;
    } catch (error) {
      ctx.logger.warn("turn-rewind: query read failed", error);
      return undefined;
    }
  };

  /** 判定会话是否正在跑回合。 */
  const isRunning = (sessionId) => {
    const agents = ctx.get("agents");
    const agent = agents === undefined ? undefined : agents.get(sessionId);
    return agent !== undefined && agent.status === "running";
  };

  /** 串行执行同一会话的操作。 */
  const enqueue = (sessionId, operation) => {
    const previous = tails.get(sessionId) ?? Promise.resolve();
    const result = previous.then(operation, operation);
    const tail = result.then(() => undefined, () => undefined);
    tails.set(sessionId, tail);
    return result.finally(() => {
      if (tails.get(sessionId) === tail) tails.delete(sessionId);
    });
  };

  /** 抓取一个路径在写入前的内容（intent 用）。 */
  const captureIntent = async (state, absolute) => {
    const cwd = state.cwd;
    const rel = path.relative(cwd, absolute);
    if (rel === "" || rel.startsWith("..") || path.isAbsolute(rel)) return;
    if (state.intents.has(rel)) return;
    try {
      const stat = await fsp.lstat(absolute);
      if (!stat.isFile()) return;
      if (stat.size > DEFAULT_LIMITS.maxFileBytes) {
        state.intents.set(rel, { absent: false, blob: "", size: stat.size });
        return;
      }
      const buf = await fsp.readFile(absolute);
      const written = await storeOf(state.sessionId).write(buf);
      state.intents.set(rel, { absent: false, blob: written.hash, size: buf.length });
    } catch (error) {
      if (error && error.code === "ENOENT") state.intents.set(rel, { absent: true });
    }
  };

  ctx.on("session/event", (session, event) => {
    const cwd = eligible(session);
    if (cwd === undefined) return;
    const sessionId = session.id;
    if (event.type === "turn/start") {
      const turn = event.data.turn;
      const state = { sessionId: sessionId, cwd: cwd, turn: turn, intents: new Map(), startedAt: event.time };
      active.set(sessionId, state);
      void (async () => {
        try {
          state.baseline = await captureBaseline(cwd, storeOf(sessionId), DEFAULT_LIMITS);
        } catch (error) {
          ctx.logger.warn("turn-rewind: baseline capture failed", error);
          state.baseline = undefined;
        }
      })();
      return;
    }
    if (event.type === "turn/end") {
      const state = active.get(sessionId);
      if (state === undefined || state.turn !== event.data.turn) return;
      active.delete(sessionId);
      void (async () => {
        try {
          if (state.baseline === undefined) return;
          const diff = await diffTurn({
            baseline: state.baseline, cwd: cwd, store: storeOf(sessionId),
            intents: state.intents, options: DEFAULT_LIMITS,
          });
          if (diff.files.length === 0) return;
          await writeManifest(sessionDirOf(sessionId), {
            version: 1,
            sessionId: sessionId,
            turn: state.turn,
            startedAt: state.startedAt,
            endedAt: event.time,
            reason: event.data.reason,
            truncated: diff.truncated,
            skipped: diff.skipped,
            files: diff.files,
          });
        } catch (error) {
          ctx.logger.warn("turn-rewind: manifest write failed", error);
        }
      })();
    }
  });

  ctx.on("tools/pre-execute", (exec, next) => {
    const session = exec.agent === undefined ? undefined : exec.agent.session;
    if (session !== undefined && active.has(session.id)) execOwner.set(exec, session.id);
    return next();
  });

  /** 三个 fs 写钩子：写入前抓旧内容，覆盖扫描被配额截断的情形。 */
  const intentHook = async (target, actor, next) => {
    const sessionId = actor === undefined ? undefined : execOwner.get(actor);
    const state = sessionId === undefined ? undefined : active.get(sessionId);
    if (state !== undefined) {
      try {
        await captureIntent(state, ctx.fs.processPath(target));
      } catch (error) {
        ctx.logger.warn("turn-rewind: intent capture failed", error);
      }
    }
    return next();
  };
  ctx.on("fs/write-intent", intentHook, { prepend: true });
  ctx.on("fs/edit-intent", intentHook, { prepend: true });
  ctx.on("fs/delete-intent", intentHook, { prepend: true });

  ctx.effect(() => () => {
    active.clear();
  });

  /** 已完成 requestId 的结论（幂等）。 */
  const readReceipt = async (sessionId, requestId) => {
    const file = path.join(sessionDirOf(sessionId), "rewinds", requestId + ".json");
    try {
      return JSON.parse(await fsp.readFile(file, "utf8"));
    } catch {
      return undefined;
    }
  };
  const writeReceipt = async (sessionId, requestId, value) => {
    const dir = path.join(sessionDirOf(sessionId), "rewinds");
    await fsp.mkdir(dir, { recursive: true });
    const target = path.join(dir, requestId + ".json");
    const temp = target + "." + process.pid + "." + Date.now() + ".tmp";
    await fsp.writeFile(temp, JSON.stringify(value, null, 2));
    await fsp.rename(temp, target);
  };

  /**
   * 会话日志里每个回合号 → 开启该回合的用户消息 seq。
   *
   * 客户端只按人类输入定位撤回目标，但 Web 端的"重新输出"入口拿到的 owner 是
   * 回合号（turnTail 槽），所以由宿主把这层对应关系算好，两边都不用自己猜。
   * @param {Array<object>} events - 该会话的完整事件列表（按 seq 递增）。
   * @returns {Map<number, number>} turn → 该回合首条用户消息的 seq。
   */
  const turnAnchors = (events) => {
    const anchors = new Map();
    let turn = undefined;
    for (const event of events) {
      if (event.type === "turn/start") turn = event.data.turn;
      if (
        turn !== undefined &&
        event.type === "user/message" &&
        event.data.source !== undefined &&
        event.data.source.kind === "user" &&
        !anchors.has(turn)
      ) {
        anchors.set(turn, event.seq);
      }
    }
    return anchors;
  };

  /**
   * 业务实现：列出每个回合的落点与文件变更摘要。
   *
   * 回合只要开过就一定出现（哪怕没改文件），因为"重新输出"必须能定位到它；
   * 文件数、新建/修改/删除来自本地快照，没有快照的回合如实报 0，而不是
   * 假装"没有变化可回滚"以外的东西。
   */
  const listTurns = async (request) => {
    const sessionId = request.sessionId;
    if (typeof sessionId !== "string" || sessionId === "") return rejected("bad-request");
    const events = await eventsOf(sessionId);
    if (events === undefined) return rejected("query-unavailable");
    const manifests = new Map(
      (await readManifests(sessionDirOf(sessionId))).map((manifest) => [manifest.turn, manifest]),
    );
    const store = storeOf(sessionId);
    const usage = await store.usage();
    const anchors = turnAnchors(events);
    const turns = [];
    for (const [turn, startSeq] of [...anchors.entries()].sort((a, b) => a[0] - b[0])) {
      const manifest = manifests.get(turn);
      const files = manifest === undefined ? [] : (manifest.files ?? []);
      turns.push({
        turn: turn,
        startSeq: startSeq,
        files: files.length,
        created: files.filter((f) => f.status === "created").length,
        modified: files.filter((f) => f.status === "modified").length,
        deleted: files.filter((f) => f.status === "deleted").length,
        incomplete: files.some((f) => f.incomplete === true),
        truncated: manifest !== undefined && manifest.truncated === true,
        snapshot: manifest !== undefined,
      });
    }
    return success({
      sessionId: sessionId,
      turns: turns,
      usage: usage,
      quotaBytes: DEFAULT_LIMITS.quotaBytes,
    });
  };

  /** 业务实现：撤回。 */
  const rewind = async (request) => {
    const sessionId = request.sessionId;
    const requestId = request.requestId;
    const seq = request.seq;
    const includeFiles = request.includeFiles === true;
    const force = request.force === true;
    if (typeof sessionId !== "string" || sessionId === "") return rejected("bad-request");
    if (typeof requestId !== "string" || requestId === "") return rejected("bad-request");
    if (!Number.isSafeInteger(seq) || seq < 0) return rejected("bad-request");

    const cached = await readReceipt(sessionId, requestId);
    if (cached !== undefined) return success({ ...cached, replayed: true });

    // ⚠️ 不能用 `ctx.sessions.get(sessionId)` 判存在：它只返回**当前进程里活着**
    // 的会话对象；一个只是躺在磁盘上、没有被打开的会话（历史会话正是这种）
    // 会拿到 undefined，于是"撤回"永远报 session-not-found。存在性以持久化
    // 存储为准，cwd 也从存储头里读。
    const persistence = ctx.get("sessionPersistence");
    if (persistence === undefined) return rejected("query-unavailable");
    const stored = await persistence.stat(sessionId);
    if (stored === undefined) return rejected("session-not-found");
    if (isRunning(sessionId)) return rejected("session-busy");

    const events = await eventsOf(sessionId);
    if (events === undefined) return rejected("query-unavailable");
    // 以会话日志里的绝对 seq 定位：调用方（原生与 Web）手里都有消息的 seq，
    // 而 LLM message id 与人类输入的 id 不是同一个概念，容易认错。
    const target = events[seq];
    const source = target === undefined ? undefined : target.data?.source;
    if (target === undefined || target.type !== "user/message" || source === undefined || source.kind !== "user") {
      return rejected("target-not-found");
    }
    // events 数组下标与 seq 一一对应（Session 保证连续），但仍显式校验一次。
    if (events[target.seq] !== target) return rejected("target-not-found");

    const turnOfTarget = (() => {
      let turn = undefined;
      for (const event of events) {
        if (event.seq > target.seq) break;
        if (event.type === "turn/start") turn = event.data.turn;
      }
      return turn;
    })();
    if (turnOfTarget === undefined) return rejected("target-not-in-turn");

    const boundary = target.seq - 1;
    if (boundary < 0) return rejected("no-prefix");

    // 冲突预检放在 fork 之前：它只读磁盘，不产生任何改动，先拦下来可以省掉
    // 一次注定要撤销的 fork。
    let planned;
    if (includeFiles) {
      const manifests = await readManifests(sessionDirOf(sessionId));
      if (manifests.length > 0) {
        const cwd = stored.header.cwd;
        if (cwd === undefined) return rejected("no-cwd");
        const plan = planRewind(manifests, turnOfTarget);
        const conflicts = await detectConflicts({ cwd: cwd, plan: plan.plan });
        if (conflicts.length > 0 && !force) {
          return success({
            status: "conflict",
            turn: turnOfTarget,
            conflicts: conflicts,
            turnCount: plan.turns.length,
          });
        }
        const store = storeOf(sessionId);
        planned = {
          items: plan.plan,
          conflicts: conflicts,
          restore: (entry) => restoreFile({ cwd: cwd, store: store, item: entry.item }),
        };
      }
    }

    const controller = ctx.get("sessionController");
    if (controller === undefined) return rejected("fork-unavailable");
    // fork 在还原文件之前（core.rewindForkFirst 的注释说明了为什么顺序不能反）。
    const outcome = await rewindForkFirst({
      plan: planned,
      force: force,
      fork: () => controller.fork({ sessionId: sessionId, atSeq: boundary }),
      restore: planned === undefined ? undefined : planned.restore,
    });
    if (!outcome.ok) {
      const error = outcome.error;
      const code = error && typeof error === "object" && typeof error.code === "string" ? error.code : "fork-failed";
      return rejected(code, { message: error instanceof Error ? error.message : String(error) });
    }
    const forked = outcome.forked;
    const fileResult = outcome.files;
    const value = Object.freeze({
      status: "rewound",
      sessionId: sessionId,
      childSessionId: forked.sessionId,
      turn: turnOfTarget,
      files: Object.freeze({
        applied: fileResult.applied,
        restored: Object.freeze(fileResult.restored),
        skipped: Object.freeze(fileResult.skipped),
        conflicts: Object.freeze(fileResult.conflicts),
      }),
    });
    await writeReceipt(sessionId, requestId, value);
    return success(value);
  };

  /**
   * Remote 服务：手工补上 Typert 描述符（本插件是纯 JS，没有装饰器）。
   */
  class TurnRewindService extends TypertRemoteService {
    /**
     * @param {object} context - cordis 上下文。
     */
    constructor(context) {
      super(context, "turnRewind", { namespace: "turnRewind" });
    }

    /**
     * 列出该会话各回合的文件变更摘要。
     * @param {object} request - {sessionId}。
     * @returns {Promise<object>} 业务结果。
     */
    list(request) {
      return listTurns(request);
    }

    /**
     * 撤回指定用户消息及其之后的回合（可选回滚文件）。
     * @param {object} request - {sessionId, requestId, seq, includeFiles?, force?}。
     * @returns {Promise<object>} 业务结果。
     */
    rewind(request) {
      return enqueue(request.sessionId, () => rewind(request));
    }
  }

  // 等价于在两个方法上加 @Remote('list') / @Remote('rewind')：
  // 描述符直接挂在原型上，网关按此发现 src 端点。
  Object.defineProperty(TurnRewindService.prototype, "@deepseek-ai/dsh-typert-protocol/remote-methods", {
    configurable: true,
    value: Object.freeze({
      version: 1,
      methods: Object.freeze([
        Object.freeze({ method: "list", invocation: Object.freeze({ kind: "direct" }) }),
        Object.freeze({ method: "rewind", invocation: Object.freeze({ kind: "direct" }) }),
      ]),
    }),
  });

  ctx.plugin(TurnRewindService);
}
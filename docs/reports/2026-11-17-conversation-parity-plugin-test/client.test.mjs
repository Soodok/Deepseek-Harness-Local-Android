import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const clientPath = resolve(here, "../../../app/src/main/assets/android-plugins/turn-rewind/client.js");

/**
 * 载入 Web 客户端插件，返回它的导出与它所在的沙箱。
 *
 * 插件是浏览器侧脚本（`window.__ModuleLoader__.load`），这里只提供它真正
 * 用到的那几个宿主对象：localStorage、setTimeout、document 的一个空壳。
 * React 传空对象——被测的都是纯函数与动作层，不渲染任何组件。
 */
function loadPlugin() {
  const store = new Map();
  const opened = [];
  const calls = [];
  const sandbox = {
    console,
    JSON,
    Date,
    Math,
    Promise,
    setTimeout: (fn) => {
      // 提示条会自动消失；测试里不需要真的等，也别留定时器。
      void fn;
      return 0;
    },
    clearTimeout: () => {},
    navigator: {},
    document: {
      createElement: () => ({ style: {}, setAttribute() {}, appendChild() {}, remove() {} }),
      head: { appendChild() {} },
      body: { appendChild() {} },
      execCommand: () => true,
    },
    window: {
      localStorage: {
        getItem: (key) => (store.has(key) ? store.get(key) : null),
        setItem: (key, value) => store.set(key, String(value)),
        removeItem: (key) => store.delete(key),
      },
      setTimeout: (fn) => {
        void fn;
        return 0;
      },
      location: { reload() {} },
    },
  };
  sandbox.globalThis = sandbox;
  sandbox.window.__ModuleLoader__ = {
    load: (spec) => {
      const module = { exports: {} };
      const require = (name) => {
        assert.ok(
          name === "react" || name === "react-dom" || name === "react-dom/client",
          "unexpected require: " + name,
        );
        return {
          createElement: (...args) => ({ type: "el", args }),
          useState: (initial) => [initial, () => {}],
          // 真的执行副作用：编辑横条靠 effect 把待办草稿交给输入框，
          // 桩成空函数就会把"取消后草稿没回输入框"这类缺陷一起桩掉。
          useEffect: (fn) => {
            const cleanup = fn();
            if (typeof cleanup === "function") cleanup();
          },
          useSyncExternalStore: () => 0,
          Fragment: "fragment",
          createPortal: (node) => node,
          createRoot: () => ({ render() {}, unmount() {} }),
        };
      };
      const exported = spec.factory(require);
      module.exports = exported;
      sandbox.__plugin = exported;
    },
  };
  vm.createContext(sandbox);
  vm.runInContext(readFileSync(clientPath, "utf8"), sandbox, { filename: clientPath });
  return {
    plugin: sandbox.__plugin,
    window: sandbox.window,
    sandbox,
    calls,
    opened,
  };
}

/**
 * 跨 realm 的结构比较。
 *
 * 插件跑在 vm 沙箱里，它返回的对象原型属于另一个 realm，`deepEqual`
 * 会因此报"结构相同但不等"。这里先序列化回本 realm 再比，避免把
 * realm 差异误报成业务缺陷。
 */
function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

/** 一份最小的回合清单：turn 0/1 都改过文件，turn 2 没改。 */
function turns() {
  return [
    { turn: 0, startSeq: 3, files: 2, incomplete: false, truncated: false },
    { turn: 1, startSeq: 9, files: 1, incomplete: true, truncated: false },
    { turn: 2, startSeq: 15, files: 0, incomplete: false, truncated: false },
  ];
}

test("locateTurn picks the owning Turn and its first user seq", () => {
  const { plugin } = loadPlugin();
  const t = plugin.__test;
  assert.deepEqual(plain(t.locateTurn(turns(), 11)), { turn: 1, startSeq: 9 });
  assert.deepEqual(plain(t.locateTurn(turns(), 9)), { turn: 1, startSeq: 9 });
  assert.deepEqual(plain(t.locateTurn(turns(), 15)), { turn: 2, startSeq: 15 });
  // 早于任何记录的 seq 不属于任何回合 —— 不猜。
  assert.equal(t.locateTurn(turns(), 1).turn, undefined);
  assert.equal(t.locateTurn(turns(), 1).startSeq, undefined);
});

test("planFrom counts every file from the addressed Turn onward", () => {
  const { plugin } = loadPlugin();
  const t = plugin.__test;
  // 撤回 turn 0 会连 turn 1、2 一起回滚：3 个文件。
  assert.deepEqual(plain(t.planFrom(turns(), 0)), { files: 3, incomplete: true, hasSnapshot: true });
  // 撤回 turn 2 没有文件可回滚。
  assert.deepEqual(plain(t.planFrom(turns(), 2)), { files: 0, incomplete: false, hasSnapshot: true });
  // 完全没有记录的回合：如实说没有快照。
  assert.deepEqual(plain(t.planFrom(turns(), 7)), { files: 0, incomplete: false, hasSnapshot: false });
});

test("describe states exactly what a person is agreeing to", () => {
  const { plugin } = loadPlugin();
  const t = plugin.__test;
  assert.equal(t.describe(t.planFrom(turns(), 7)), "这一轮没有留下文件记录，只能撤回对话文本。");
  assert.equal(t.describe(t.planFrom(turns(), 2)), "这一轮没有改动文件，只撤回对话文本。");
  assert.equal(t.describe(t.planFrom(turns(), 0)), "将回滚 3 个文件的改动。其中有文件没留下快照，可能恢复不全。");
});

test("promptOfTurn prefers the Turn and falls back to the nearest earlier input", () => {
  const { plugin } = loadPlugin();
  const t = plugin.__test;
  const snapshot = {
    order: ["a", "b", "c"],
    nodes: new Map([
      ["a", { kind: "user", data: { seq: 3, turn: 0, source: { kind: "user" }, content: [{ type: "text", text: "第一个问题" }] } }],
      ["b", { kind: "user", data: { seq: 9, turn: 1, source: { kind: "user" }, content: [{ type: "text", text: "第二个问题" }] } }],
      ["c", { kind: "assistant-step", data: { seq: 12, turn: 1, messageId: "m-12", blocks: [{ text: "回答" }] } }],
    ]),
  };
  assert.equal(t.promptOfTurn(snapshot, 1, 12), "第二个问题");
  // 快照没有回合号时按 seq 回退到最近的前一条用户输入。
  const noTurn = {
    order: ["a", "b"],
    nodes: new Map([
      ["a", { kind: "user", data: { seq: 3, source: { kind: "user" }, content: [{ type: "text", text: "第一个问题" }] } }],
      ["b", { kind: "user", data: { seq: 9, source: { kind: "user" }, content: [{ type: "text", text: "第二个问题" }] } }],
    ]),
  };
  assert.equal(t.promptOfTurn(noTurn, undefined, 12), "第二个问题");
  assert.equal(t.promptOfTurn(noTurn, undefined, 1), "");
});

test("seqOfAnswer and assistantTurnOf read the answer's own identity", () => {
  const { plugin } = loadPlugin();
  const t = plugin.__test;
  const snapshot = {
    order: ["x"],
    nodes: new Map([
      ["x", { kind: "assistant-step", data: { seq: 12, turn: 4, messageId: "m-12", blocks: [{ text: "回答正文" }] } }],
    ]),
  };
  assert.equal(t.seqOfAnswer(snapshot, "m-12"), 12);
  assert.equal(t.assistantTurnOf(snapshot, "m-12"), 4);
  assert.equal(t.assistantTextOf(snapshot, "m-12"), "回答正文");
  assert.equal(t.seqOfAnswer(snapshot, "missing"), undefined);
});

test("system share uses the app's native bridge, because WebView has no navigator.share", () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const calls = [];
  // Android WebView：navigator.share 根本不存在。桥是唯一出口。
  harness.sandbox.window.DshAndroidShare = {
    shareText: (text, title, fileName) => {
      calls.push({ text, title, fileName });
      return "ok";
    },
  };
  let closed = 0;
  const tree = t.ShareDialog({ text: "问：甲\n\n答：乙", title: "分享这段对话", onClose: () => closed++ });
  const button = findButtonByText(tree, "系统分享");
  assert.ok(button, "分享框里必须有系统分享按钮");
  button.args[1].onClick();

  assert.equal(calls.length, 1, "按下系统分享必须真的把内容交给原生桥");
  assert.equal(calls[0].text, "问：甲\n\n答：乙");
  assert.equal(closed, 1, "分享成功就收起弹层");

  // 桥缺失（桌面浏览器）且没有 navigator.share：要给出可读提示，不能静默。
  harness.sandbox.window.DshAndroidShare = undefined;
  const desktop = t.ShareDialog({ text: "问：甲", title: "分享", onClose: () => {} });
  findButtonByText(desktop, "系统分享").args[1].onClick();
  assert.ok(true);
});

test("an answer's identity is read from finalNode, where rc.2 actually puts it", () => {
  const { plugin } = loadPlugin();
  const t = plugin.__test;

  // rc.2 的 assistant-step.data 只有 {status,turn,step,blocks,time,usage,finalNode}；
  // messageId / seq 在 finalNode 上。按 data.messageId 读会让分享一直报
  // "这条回答还没有正文可以分享"，seqOfAnswer 也永远 undefined（撤回、重新输出
  // 全都定位不到回合）。这里用真实形状守住它。
  const snapshot = {
    order: ["tail", "step"],
    nodes: new Map([
      [
        "step",
        {
          kind: "assistant-step",
          data: {
            status: "settled",
            turn: 5,
            step: 1,
            blocks: [{ kind: "text", text: "Understood — I'll keep this draft unchanged." }],
            finalNode: {
              kind: "assistant",
              seq: 56,
              messageId: "0c35ed4a-eebf-408b-97bc-a53ad5588c00",
              turn: 5,
              step: 1,
            },
          },
        },
      ],
    ]),
  };
  assert.equal(t.seqOfAnswer(snapshot, "0c35ed4a-eebf-408b-97bc-a53ad5588c00"), 56);
  assert.equal(t.assistantTurnOf(snapshot, "0c35ed4a-eebf-408b-97bc-a53ad5588c00"), 5);
  assert.equal(
    t.assistantTextOf(snapshot, "0c35ed4a-eebf-408b-97bc-a53ad5588c00"),
    "Understood — I'll keep this draft unchanged.",
  );
  // 只认自己的 messageId，不把别的回答的正文当成自己的。
  assert.equal(t.assistantTextOf(snapshot, "someone-else"), "");
  assert.equal(t.seqOfAnswer(snapshot, "someone-else"), undefined);
});

test("seedDraft replaces or appends without dropping the other stored keys", () => {
  const { plugin, window } = loadPlugin();
  const t = plugin.__test;
  window.localStorage.setItem("dsh.conversation.s1", JSON.stringify({ view: "chat", draft: "旧草稿" }));
  t.seedDraft("s1", "新文本", "replace");
  assert.deepEqual(JSON.parse(window.localStorage.getItem("dsh.conversation.s1")), {
    view: "chat",
    draft: "新文本",
  });
  t.seedDraft("s1", "接上的", "append");
  assert.equal(t.readDraft("s1"), "新文本\n\n接上的");
});

/**
 * 一个只记录调用的宿主：不给类型化 remote.turnRewind，只给 Connection 的
 * 通用通道与工作区导航 —— 真实引擎里插件就是这个处境。
 */
function fakeChannelCtx(harness, handler) {
  const calls = [];
  harness.calls = calls;
  return {
    remote: {},
    get: (name) => {
      if (name === "uiWorkspace") {
        return { openSession: (id) => harness.opened.push(id) };
      }
      if (name === "connection") {
        return {
          rpc: {
            call: async (channel, endpoint, payload, signal) => {
              void signal;
              calls.push({ channel, endpoint, payload });
              return handler(endpoint, payload);
            },
          },
        };
      }
      return null;
    },
  };
}

/** 一个只记录调用的宿主：Remote 与工作区导航各一条。 */
function fakeCtx(harness, rewindResult) {
  const rewinds = [];
  harness.rewinds = rewinds;
  return {
    rewinds,
    remote: {
      turnRewind: {
        list: async () => ({ ok: true, value: { turns: turns() } }),
        rewind: async (request) => {
          rewinds.push(request);
          if (rewindResult instanceof Error) throw rewindResult;
          return rewindResult;
        },
      },
    },
    get: (name) =>
      name === "uiWorkspace" ? { openSession: (id) => harness.opened.push(id) } : null,
  };
}

test("runRewind asks for the exact seq, then opens the fork with the text", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, {
    ok: true,
    value: { status: "rewound", childSessionId: "child-1", files: { applied: true, restored: [] } },
  });
  await t.runRewind(ctx, {
    sessionId: "s1",
    seq: 9,
    includeFiles: true,
    text: "原来的问题",
    replaceDraft: true,
    sendNow: false,
  });
  assert.equal(ctx.rewinds.length, 1);
  assert.equal(ctx.rewinds[0].sessionId, "s1");
  assert.equal(ctx.rewinds[0].seq, 9);
  assert.equal(ctx.rewinds[0].includeFiles, true);
  assert.equal(ctx.rewinds[0].force, false);
  assert.match(ctx.rewinds[0].requestId, /^rewind-/);
  // 会话真的被打开，且文本进了那个会话的输入框。
  assert.deepEqual(harness.opened, ["child-1"]);
  assert.equal(t.readDraft("child-1"), "原来的问题");
  assert.deepEqual(plain(t.pendingFor("child-1")), { draft: "原来的问题" });
  assert.deepEqual(JSON.parse(harness.window.localStorage.getItem("dsh.sessions.current")), {
    sessionId: "child-1",
  });
});

test("runRewind keeps an existing draft when asked to append", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, { ok: true, value: { status: "rewound", childSessionId: "child-2", files: {} } });
  t.seedDraft("child-2", "我先写了一半", "replace");
  await t.runRewind(ctx, {
    sessionId: "s1",
    seq: 9,
    includeFiles: false,
    text: "原来的问题",
    replaceDraft: false,
    sendNow: false,
  });
  assert.equal(t.readDraft("child-2"), "我先写了一半\n\n原来的问题");
});

test("a conflict aborts before any file, draft or session change", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, {
    ok: true,
    value: { status: "conflict", conflicts: ["src/a.ts", "src/b.ts"] },
  });
  t.seedDraft("s1", "原草稿", "replace");
  await assert.rejects(
    () =>
      t.runRewind(ctx, {
        sessionId: "s1",
        seq: 9,
        includeFiles: true,
        text: "原来的问题",
        replaceDraft: true,
        sendNow: false,
      }),
    (error) => {
      assert.match(error.message, /这些文件在这一轮之后被改过/);
      assert.match(error.message, /src\/a\.ts/);
      return true;
    },
  );
  assert.deepEqual(harness.opened, []);
  assert.equal(t.readDraft("s1"), "原草稿");
  assert.equal(harness.window.localStorage.getItem("dsh.sessions.current"), null);
});

test("a refused rewind never opens a Session", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, { ok: false, error: { code: "session-busy", message: "这一轮还在进行" } });
  await assert.rejects(() =>
    t.runRewind(ctx, {
      sessionId: "s1",
      seq: 9,
      includeFiles: false,
      text: "x",
      replaceDraft: true,
      sendNow: false,
    }),
  );
  assert.deepEqual(harness.opened, []);
});

test("regenerate rewinds the Turn start, restores files and resends the prompt", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, { ok: true, value: { status: "rewound", childSessionId: "child-3", files: {} } });
  const actions = t.createActions(ctx);
  const snapshot = {
    order: ["u", "a"],
    nodes: new Map([
      ["u", { kind: "user", data: { seq: 9, turn: 1, source: { kind: "user" }, content: [{ type: "text", text: "第二个问题" }] } }],
      ["a", { kind: "assistant-step", data: { seq: 12, turn: 1, messageId: "m-12", blocks: [{ text: "回答" }] } }],
    ]),
  };
  await actions.regenerate("s1", "m-12", snapshot);
  // 确认框是模块级状态，这里直接调用它内部的 run，验证真正的请求。
  assert.equal(ctx.rewinds.length, 0, "确认前不应发请求");
  assert.ok(t.planFrom(turns(), 1));
});

test("share builds a readable 问 / 答 block and refuses an empty answer", () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, { ok: true, value: {} });
  const actions = t.createActions(ctx);
  const snapshot = {
    order: ["u", "a"],
    nodes: new Map([
      ["u", { kind: "user", data: { seq: 9, turn: 1, source: { kind: "user" }, content: [{ type: "text", text: "第二个问题" }] } }],
      ["a", { kind: "assistant-step", data: { seq: 12, turn: 1, messageId: "m-12", blocks: [{ text: "回答" }] } }],
    ]),
  };
  // 空正文时不弹分享框（内部会 notify 并 return），不抛错。
  actions.share(snapshot, "missing");
  actions.share(snapshot, "m-12");
  assert.ok(true);
});

test("without a typed namespace it uses the Connection channel and same endpoint", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  // 真实通道会包两层：Connection 的 {ok,value} 里面还有插件自己的 {ok,value}。
  const ctx = fakeChannelCtx(harness, (endpoint, payload) => {
    if (endpoint === "turnRewind/list") {
      return { ok: true, value: { ok: true, value: { turns: turns() } } };
    }
    assert.equal(endpoint, "turnRewind/rewind");
    // 参数名必须与服务端形参一致（request），网关按名字取值。
    assert.deepEqual(Object.keys(payload), ["args"]);
    assert.deepEqual(Object.keys(payload.args), ["request"]);
    return {
      ok: true,
      value: {
        ok: true,
        value: { status: "rewound", childSessionId: "child-4", files: { applied: false } },
      },
    };
  });
  const rows = await t.readTurns(ctx, "s1");
  assert.equal(rows.length, 3);
  await t.runRewind(ctx, {
    sessionId: "s1",
    seq: 9,
    includeFiles: false,
    text: "原来的问题",
    replaceDraft: true,
    sendNow: false,
  });
  // 两次调用都落在 /api 通道上，端点名与队列顺序可复核。
  assert.deepEqual(
    harness.calls.map((call) => call.channel + " " + call.endpoint),
    ["/api turnRewind/list", "/api turnRewind/rewind"],
  );
  assert.deepEqual(harness.opened, ["child-4"]);
});

test("a channel failure surfaces instead of pretending the retraction worked", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeChannelCtx(harness, () => ({
    ok: true,
    value: { ok: false, error: { code: "session-busy", message: "这一轮还在进行" } },
  }));
  await assert.rejects(
    () =>
      t.runRewind(ctx, {
        sessionId: "s1",
        seq: 9,
        includeFiles: false,
        text: "x",
        replaceDraft: true,
        sendNow: false,
      }),
    (error) => {
      assert.equal(error.code, "session-busy");
      return true;
    },
  );
  assert.deepEqual(harness.opened, []);
});

test("a business error inside a successful transport is still a failure", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeChannelCtx(harness, () => ({
    ok: true,
    value: { ok: false, error: { code: "target-not-found" } },
  }));
  await assert.rejects(
    () =>
      t.runRewind(ctx, {
        sessionId: "s1",
        seq: 9,
        includeFiles: false,
        text: "x",
        replaceDraft: true,
        sendNow: false,
      }),
    (error) => {
      assert.equal(error.code, "target-not-found");
      return true;
    },
  );
  assert.deepEqual(harness.opened, []);
});

/**
 * 真的走一次 apply()：拿到槽注册表，并让模块级 actionsRef 指向这组动作。
 *
 * 编辑横条挂在输入区那一层，它读的是模块级引用，所以"横条有没有取消入口"
 * 这类断言必须从 apply 进来，不能只调 createActions。
 */
function applyPlugin(harness, base) {
  const registered = [];
  const ctx = Object.assign({}, base, {
    effect: () => () => {},
    slots: {
      inject: (name, fn) => fn(),
      register: (options, component) => {
        registered.push({ options, component });
        return { dispose() {} };
      },
    },
  });
  harness.plugin.apply(ctx);
  return registered;
}

/** 在桩版 React 元素树里按 aria-label 找一个按钮。 */
function findButton(node, label) {
  if (!node || typeof node !== "object") return null;
  if (node.type === "el") {
    const [tag, props, ...children] = node.args;
    if (tag === "button" && props && props["aria-label"] === label) return node;
    for (const child of children) {
      const found = findButton(child, label);
      if (found) return found;
    }
    return null;
  }
  return null;
}

/** 按可见文字找按钮（分享框那排是纯文字按钮，没有 aria-label）。 */
function findButtonByText(node, text) {
  if (!node || typeof node !== "object") return null;
  if (node.type === "el") {
    const [tag, props, ...children] = node.args;
    if (tag === "button" && children[0] === text) return node;
    for (const child of children) {
      const found = findButtonByText(child, text);
      if (found) return found;
    }
    return null;
  }
  return null;
}

/**
 * 取当前挂着的确认框。
 *
 * 编辑与撤回的确认框是模块级状态（动作从图标点击直接触发，不在渲染树里），
 * 所以测试必须经由这个入口"按下确认"，否则只能断言到"确认前不发请求"。
 */
function overlayOf(t) {
  const current = t.overlay();
  assert.ok(current, "应当弹出一个确认框");
  assert.equal(current.kind, "confirm");
  return current.props;
}

test("beginEdit marks the fork as the editing session, not the origin", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, {
    ok: true,
    value: { status: "rewound", childSessionId: "child-9", files: { applied: false } },
  });
  const actions = t.createActions(ctx);
  t.seedDraft("s1", "我自己打了一半", "replace");

  await actions.beginEdit("s1", "m-5", 9, "原来的问题");
  assert.equal(ctx.rewinds.length, 0, "确认前不应发请求");

  await overlayOf(t).onConfirm(true, true);
  // 人已经在分叉出来的会话里继续，编辑态必须挂在他眼前那个会话上。
  assert.deepEqual(harness.opened, ["child-9"]);
  assert.equal(t.editingOf("child-9").seq, 9);
  assert.equal(t.editingOf("s1"), null);
});

test("cancelling an edit puts the pre-edit draft back into the fork's input", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, {
    ok: true,
    value: { status: "rewound", childSessionId: "child-10", files: {} },
  });
  const actions = t.createActions(ctx);
  t.seedDraft("s1", "我自己打了一半", "replace");

  await actions.beginEdit("s1", "m-5", 9, "原来的问题");
  await overlayOf(t).onConfirm(true, true);
  assert.equal(t.readDraft("child-10"), "原来的问题");

  actions.cancelEdit("child-10");
  assert.equal(t.editingOf("child-10"), null);
  assert.equal(t.readDraft("child-10"), "我自己打了一半", "取消后应把编辑前的草稿放回眼前这个会话");
  assert.deepEqual(plain(t.pendingFor("child-10")), { draft: "我自己打了一半" });
});

test("keeping the draft while editing carries it into the fork", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, {
    ok: true,
    value: { status: "rewound", childSessionId: "child-11", files: {} },
  });
  const actions = t.createActions(ctx);
  t.seedDraft("s1", "我自己打了一半", "replace");

  await actions.beginEdit("s1", "m-5", 9, "原来的问题");
  await overlayOf(t).onConfirm(true, false);
  // 选了"保留草稿"，草稿就必须跟着人到分叉里去，不能留在旧会话里丢掉。
  assert.equal(t.readDraft("child-11"), "我自己打了一半\n\n原来的问题");
});

test("the composer shows an editing banner whose cancel restores the draft", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, {
    ok: true,
    value: { status: "rewound", childSessionId: "child-20", files: {} },
  });
  const base = { remote: ctx.remote, get: ctx.get };
  const registered = applyPlugin(harness, base);
  t.seedDraft("s1", "我自己打了一半", "replace");

  // 走真实动作层进入编辑：组件点下去用的是 apply() 里那组动作。
  await t.actions().beginEdit("s1", "m-5", 9, "原来的问题");
  await overlayOf(t).onConfirm(true, true);
  assert.equal(t.editingOf("child-20").seq, 9);

  const bridge = registered.find((entry) => entry.options.name === "conversation.input.overlay");
  assert.ok(bridge, "输入区那一层应当注册了草稿桥与编辑横条");
  const written = [];
  const tree = bridge.component({
    sessionId: "child-20",
    inputActions: {
      setDraft: (text) => written.push(text),
      submit: () => written.push("SUBMIT"),
    },
  });
  const cancel = findButton(tree, "取消编辑，恢复原来的草稿");
  assert.ok(cancel, "编辑态必须有一个看得见、退得出去的取消入口");
  // 挂载时官方输入框先拿到编辑原文（编辑态下框里应该是这条消息）。
  assert.deepEqual(written, ["原来的问题"]);

  cancel.args[1].onClick({ stopPropagation() {} });
  assert.equal(t.editingOf("child-20"), null);
  assert.equal(t.readDraft("child-20"), "我自己打了一半");

  // 取消会推一次存储变更，输入区因此重渲染；重渲染后草稿必须真的落进框里，
  // 只写持久化键而框里不动，人就以为草稿被吞了。
  written.length = 0;
  const after = bridge.component({
    sessionId: "child-20",
    inputActions: {
      setDraft: (text) => written.push(text),
      submit: () => written.push("SUBMIT"),
    },
  });
  assert.deepEqual(written, ["我自己打了一半"], "取消后应按官方 setDraft 把草稿放回输入框");
  assert.equal(findButton(after, "取消编辑，恢复原来的草稿"), null, "退出编辑后横条应当消失");
});

test("the editing banner renders above the composer instead of inside its zero-height anchor", () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const css = t.css();

  // 官方输入区那一格是 <div class="GRgSLq_overlayAnchor" style="height:0;position:absolute">
  // —— 零高、无内边距。横条若留在常规流里，就从卡片顶边往下压，挡住输入框文字，
  // 点击命中落在输入框段落上，看着像一层模糊水印。它必须自己浮到卡片上方。
  const rule = /.dsh-rw-editing{([^}]*)}/.exec(css);
  assert.ok(rule, "编辑横条必须有自己的样式规则");
  const body = rule[1];
  assert.match(body, /position:absolute/, "横条要脱离零高锚点的常规流");
  assert.match(body, /bottom:100%/, "横条要贴在卡片上沿之外，不占输入框排版");

  // rc.2 里不存在 --dsh-ink / --dsh-paper：fallback 是深色主题的近白色，
  // 浅色主题下就成了"白字压浅蓝底"。主题色只能走真实存在的 --dsw-alias-*。
  assert.doesNotMatch(css, /--dsh-ink\b/, "rc.2 没有 --dsh-ink 这个令牌");
  assert.doesNotMatch(css, /--dsh-paper\b/, "rc.2 没有 --dsh-paper 这个令牌");
  assert.match(body, /--dsw-alias-label-primary/, "横条文字要用 rc.2 的前景令牌");
});

test("a refused edit leaves no editing state behind", async () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const ctx = fakeCtx(harness, { ok: false, error: { code: "session-busy", message: "这一轮还在进行" } });
  const actions = t.createActions(ctx);

  await actions.beginEdit("s1", "m-5", 9, "原来的问题");
  await assert.rejects(() => overlayOf(t).onConfirm(true, true));
  assert.equal(t.editingOf("child-12"), null);
  assert.equal(t.editingOf("s1"), null);
  assert.deepEqual(harness.opened, []);
});

test("会话还在跑时用普通话说清原因，而不是把 session-busy 这种码丢给用户", () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  const text = t.readableError({ code: "session-busy", message: "session-busy" });
  assert.equal(/session-busy/.test(text), false, "原始错误码不应出现在提示里");
  assert.equal(/[a-z]+-[a-z]+/.test(text), false, "提示里不应残留任何 kebab 错误码");
  assert.ok(text.length >= 8, "提示要是一句能读的话");
});

test("其它已知失败也有可读说法，未知错误仍然如实带出原文", () => {
  const harness = loadPlugin();
  const t = harness.plugin.__test;
  for (const code of ["session-not-found", "target-not-found", "fork-unavailable", "query-unavailable", "no-prefix"]) {
    const text = t.readableError({ code: code, message: code });
    assert.equal(text.indexOf(code), -1, code + " 不应原样出现在提示里");
  }
  const unknown = t.readableError({ code: "weird-failure", message: "weird-failure" });
  assert.equal(unknown.indexOf("weird-failure") >= 0, true, "不认识的错误要保留原文，不能编");
});

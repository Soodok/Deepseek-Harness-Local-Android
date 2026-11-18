/**
 * [dsh-android] 撤回 / 编辑 / 重新输出 / 分享 —— Web 端客户端插件。
 *
 * ## 为什么需要它
 *
 * 宿主侧 `turn-rewind` 插件只提供两个 Remote：`turnRewind/list`（每个回合
 * 改过哪些文件）与 `turnRewind/rewind`（按消息 seq 撤回，可选还原文件，
 * 结果是一个新的子会话）。它不知道界面长什么样，也不该知道。本文件就是
 * 那一层的客户端：把能力挂到真实的聊天行上，让 Web 与原生调同一份契约。
 *
 * ## 关键设计
 *
 * 1. **用户行要拿到 seq。** `conversation.chat.user.before` 只是 chain 槽，
 *    owner 只有 `{source, kind}`，拿不到 seq；而撤回必须按 seq 定位。
 *    所以这里复用 keyed 槽 `conversation.chat.node` 的 `user` 键（契约原文：
 *    "Reusing a key replaces that node renderer"），从而拿到
 *    `node.data.seq`。气泡本体不重画 —— 仍交给官方 factory 槽
 *    `conversation.chat.user.content`，只在动作位放自己的两个图标。
 *    `steering` 行不覆盖，继续走官方渲染器（它没有 durable seq）。
 *
 * 2. **点赞 / 踩赞不重复实现。** 官方 `ui-message-feedback` 已经用
 *    `conversation.chat.assistant-actions` 的 `feedback` 条目接了宿主
 *    `messageFeedback` 契约（含理由对话框）。再注册一份同功能按钮会让同
 *    一行出现两组赞踩。因此这里用**新 id**只补官方行缺的两件事：
 *    「重新输出」与「分享」；复制官方已有。
 *
 * 3. **回填草稿走持久化层，不硬塞组件状态。** 撤回成功后要打开子会话并把
 *    原文放进输入框。`requestDraftInitialization` 要求手里已经持有该会话的
 *    当前 binding，而子会话刚创建、还没被导航物化。官方在
 *    `InputHub.shellFor` 里创建 shell 时会读 `localStorage` 的
 *    `dsh.conversation.<sessionId>`，所以写入那个键再打开会话，草稿就会在
 *    输入框首次挂载时被采用 —— 这是官方自己的恢复路径，不是旁路。
 *
 * 4. **失败不假装成功。** 冲突（文件在这一轮之后被改过）、宿主拒绝、
 *    会话正忙，都如实显示，绝不修改文件、不清空气泡。
 *
 * @module dsh-android-turn-rewind/client
 */

window.__ModuleLoader__.load({
  id: "dsh-android-turn-rewind",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var React = require("react");
    var ReactDOM = require("react-dom");
    var ReactDOMClient = require("react-dom/client");
    var h = React.createElement;

    /** 必需服务：槽注册表、Remote、会话与工作区导航。 */
    /**
     * 必需服务。
     *
     * 不写 `remote.turnRewind`：那是由 dsh-api-remotes 的固定产物清单装配的
     * 类型化命名空间，本插件不在清单里，等它等于永远不激活（见 callTurnRewind
     * 的说明）。这里只需要槽注册表、Remote（给 messageFeedback）与 Connection
     * 的通用通道。
     */
    var inject = ["slots", "remote", "connection"];

    var name = "dsh-android-turn-rewind";

    /** 草稿持久化键前缀，与 ui-conversation 的 stores.ts 保持一致。 */
    var DRAFT_KEY = "dsh.conversation";

    /** 图标用内联 SVG：不依赖图集包是否在当前构建里被 materialize。 */
    function icon(paths, size) {
      return h(
        "svg",
        {
          width: size || 16,
          height: size || 16,
          viewBox: "0 0 24 24",
          fill: "none",
          stroke: "currentColor",
          strokeWidth: 1.8,
          strokeLinecap: "round",
          strokeLinejoin: "round",
          "aria-hidden": "true",
        },
        paths.map(function (d, i) {
          return h("path", { key: i, d: d });
        }),
      );
    }

    var GLYPH = {
      copy: ["M9 9h9v11H9z", "M6 15H4V4h11v2"],
      undo: ["M9 14l-4-4 4-4", "M5 10h8a5 5 0 1 1 0 10h-3"],
      retry: ["M20 12a8 8 0 1 1-2.4-5.7", "M20 4v5h-5"],
      share: ["M12 16V4", "M8 8l4-4 4 4", "M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5"],
    };

    /** 与界面同一套令牌；浅深色跟随 `color-scheme`。 */
    var CSS = [
      ".dsh-rw-row{display:flex;align-items:center;gap:2px;margin-top:4px}",
      ".dsh-rw-btn{display:inline-flex;align-items:center;justify-content:center;",
      "width:28px;height:28px;padding:0;border:0;border-radius:8px;cursor:pointer;",
      "background:transparent;color:var(--dsw-alias-label-tertiary,rgba(128,128,128,.85));",
      "transition:background .15s ease,color .15s ease}",
      ".dsh-rw-btn:hover:not(:disabled){background:rgba(128,128,128,.16);",
      "color:var(--dsw-alias-label-primary,rgba(230,230,230,.95))}",
      ".dsh-rw-btn:disabled{opacity:.4;cursor:default}",
      ".dsh-rw-btn[data-done]{color:#6fd3b8}",
      ".dsh-rw-scrim{position:fixed;inset:0;z-index:2147483000;display:flex;",
      "align-items:center;justify-content:center;padding:24px;",
      "background:rgba(0,0,0,.45);backdrop-filter:blur(6px)}",
      ".dsh-rw-card{width:min(460px,100%);max-height:80vh;overflow:auto;",
      "border-radius:18px;padding:20px 20px 14px;",
      "background:var(--dsw-alias-bg-layer-1,#1c1f24);",
      "color:var(--dsw-alias-label-primary,#e8eaee);",
      "box-shadow:0 24px 64px rgba(0,0,0,.45);font-size:14px;line-height:1.6}",
      ".dsh-rw-card h3{margin:0 0 8px;font-size:16px;font-weight:600}",
      ".dsh-rw-card p{margin:0 0 10px;opacity:.78}",
      ".dsh-rw-line{display:flex;gap:9px;align-items:flex-start;padding:7px 0;",
      "cursor:pointer;border-radius:10px}",
      ".dsh-rw-line:hover{background:rgba(128,128,128,.10)}",
      ".dsh-rw-box{width:17px;height:17px;flex:none;margin-top:2px;border-radius:5px;",
      "border:1.5px solid rgba(128,128,128,.6);display:flex;align-items:center;",
      "justify-content:center;font-size:11px}",
      ".dsh-rw-box[data-on]{background:#5b9cf6;border-color:#5b9cf6;color:#fff}",
      ".dsh-rw-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:14px}",
      ".dsh-rw-actions button{border:0;border-radius:10px;padding:9px 16px;",
      "font-size:14px;cursor:pointer;background:rgba(128,128,128,.18);color:inherit}",
      ".dsh-rw-actions button[data-primary]{background:#5b9cf6;color:#fff}",
      ".dsh-rw-actions button:disabled{opacity:.5;cursor:default}",
      ".dsh-rw-note{font-size:12px;opacity:.6;margin-top:8px}",
      ".dsh-rw-toast{display:flex;align-items:center;gap:7px;max-width:min(420px,calc(100vw - 32px));padding:7px 10px;border:1px solid color-mix(in srgb,var(--dsw-alias-label-tertiary,rgba(128,128,128,.85)) 22%,transparent);border-radius:10px;background:color-mix(in srgb,var(--dsw-alias-bg-layer-1,#1c1f24) 82%,transparent);color:var(--dsw-alias-label-primary,#e8eaee);box-shadow:0 8px 24px rgba(0,0,0,.18);font-size:12.5px;line-height:1.35}",
      ".dsh-rw-toast[data-error]{border-color:color-mix(in srgb,var(--dsw-alias-button-danger-fill,#c86565) 38%,transparent)}",
      ".dsh-rw-toast-icon{width:16px;height:16px;flex:none;display:grid;place-items:center;color:var(--dsw-alias-button-danger-fill,#c86565);font-size:12px}",
      // 回合结束提示：默认只有一行小字摘要，点开才给原因/建议/代码/复制。
      // 与原生 TurnNoticeCard 同一套信息层级，数据来自官方 turn-error 节点，
      // 不从正文或 toast 反推失败。
      ".dsh-rw-notice{margin:8px 0 4px}",
      ".dsh-rw-notice-row{display:flex;align-items:center;gap:7px;width:100%;",
      "min-height:48px;padding:4px 6px;border:0;border-radius:8px;background:transparent;",
      "color:var(--dsw-alias-label-secondary,rgba(200,204,210,.9));",
      "font:inherit;font-size:12.5px;line-height:1.35;text-align:left;cursor:pointer}",
      ".dsh-rw-notice-row:hover{background:rgba(128,128,128,.12)}",
      ".dsh-rw-notice-row:focus-visible{outline:2px solid var(--dsw-alias-button-info-fill,#4176e6);outline-offset:1px}",
      ".dsh-rw-notice-icon{width:15px;height:15px;flex:none;display:grid;place-items:center;",
      "color:var(--dsw-alias-button-danger-fill,#c86565)}",
      ".dsh-rw-notice[data-neutral] .dsh-rw-notice-icon{color:var(--dsw-alias-label-tertiary,rgba(128,128,128,.85))}",
      ".dsh-rw-notice-summary{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}",
      ".dsh-rw-notice-chevron{width:14px;height:14px;flex:none;opacity:.7;transition:transform .15s ease}",
      ".dsh-rw-notice[data-expanded] .dsh-rw-notice-chevron{transform:rotate(180deg)}",
      ".dsh-rw-notice-body{margin:2px 0 0 22px;padding:0 2px 4px}",
      ".dsh-rw-notice-title{margin:0 0 3px;font-size:12.5px;font-weight:600;",
      "color:var(--dsw-alias-label-primary,rgba(232,234,238,.95))}",
      ".dsh-rw-notice-reason{margin:0;font-size:12.5px;line-height:1.5;",
      "color:var(--dsw-alias-label-secondary,rgba(200,204,210,.9));overflow-wrap:anywhere}",
      ".dsh-rw-notice-next{margin:5px 0 0;font-size:12.5px;line-height:1.5;",
      "color:var(--dsw-alias-label-primary,rgba(232,234,238,.95))}",
      ".dsh-rw-notice-code{margin:5px 0 0;font-family:ui-monospace,monospace;font-size:11.5px;",
      "color:var(--dsw-alias-label-tertiary,rgba(128,128,128,.85));overflow-wrap:anywhere}",
      ".dsh-rw-notice-copy{display:inline-flex;align-items:center;gap:5px;margin-top:6px;",
      "min-height:48px;padding:0 12px;border:1px solid rgba(128,128,128,.28);",
      "border-radius:9px;background:transparent;color:inherit;font:inherit;font-size:12px;cursor:pointer}",
      ".dsh-rw-notice-copy:hover{background:rgba(128,128,128,.12)}",
      // 输入区那格的宿主锚点（官方 .GRgSLq_overlayAnchor）是
      // position:absolute; inset:0 0 auto; height:0——它只是个零高的定位参照，
      // 子节点若留在常规流里会从卡片顶部往下压，正好盖住输入框文字，行高也被
      // 挤成 24px，点击命中落在输入框段落上。所以这条横条必须自己绝对定位，
      // 贴住锚点底边（即卡片顶边）往上浮，既不占输入框的排版，也不挡输入。
      ".dsh-rw-editing{position:absolute;left:0;right:0;bottom:100%;",
      "display:flex;align-items:center;gap:8px;margin:0 0 8px;",
      "padding:7px 10px 7px 12px;border-radius:12px;font-size:12.5px;line-height:1.4;",
      // 主题色只能走 rc.2 真实存在的别名令牌（--dsw-alias-*）。原先写的
      // --dsh-ink / --dsh-paper 在 rc.2 里没有任何定义，fallback 是深色主题的
      // 近白色，于是在浅色主题下变成"白字压浅蓝底"，看起来像水印。
      "background:color-mix(in srgb,var(--dsw-alias-button-info-fill,#4176e6) 12%,transparent);",
      "border:1px solid color-mix(in srgb,var(--dsw-alias-button-info-fill,#4176e6) 28%,transparent);",
      "color:var(--dsw-alias-label-primary,rgba(232,234,238,.92))}",
      ".dsh-rw-editing-text{flex:1;min-width:0}",
      ".dsh-rw-editing-cancel{display:inline-flex;align-items:center;justify-content:center;",
      "width:24px;height:24px;flex:none;padding:0;border:0;border-radius:7px;cursor:pointer;",
      "background:transparent;color:inherit;opacity:.72}",
      ".dsh-rw-editing-cancel:hover{opacity:1;background:rgba(128,128,128,.22)}",
      ".dsh-rw-share{width:min(560px,100%)}",
      ".dsh-rw-share pre{white-space:pre-wrap;word-break:break-word;margin:0 0 4px;",
      "max-height:46vh;overflow:auto;font:inherit;font-size:13.5px;opacity:.9}",
    ].join("");

    /**
     * 把 CLI 侧的一次调用包装成 {ok,value} / {ok,error} 判定。
     *
     * @param {Promise<object>} promise - Remote 返回的携带体。
     * @returns {Promise<object>} 成功取值或抛出带 code 的错误。
     */
    async function unwrap(promise) {
      var carried = await promise;
      if (!carried || carried.ok !== true) {
        var error = (carried && carried.error) || {};
        var failure = new Error(error.message || error.code || "unknown");
        failure.code = error.code || "unknown";
        throw failure;
      }
      return carried.value;
    }

    /**
     * 调一次 turnRewind 端点。
     *
     * 这里说明为什么不能只写 `ctx.remote.turnRewind.list(...)`：
     * 客户端的 `remote.<namespace>` 服务由 `dsh-api-remotes` 按**它自己**
     * 的 `/remote` 产物清单装配（messageFeedback、session 那些都在那张表里）。
     * 我们的插件不在那张表里，引擎也不会为它生成 `/remote` 产物，所以
     * `remote.turnRewind` 永远不会出现 —— 一直等下去就是上次"pending
     * (waiting for service: remote.turnRewind)"的成因。
     *
     * 官方网关自己走的是 Connection 的通用通道：`connection.rpc.call('/api',
     * endpoint, { args })`。这里用同一条通道，服务端仍是同一个 Remote，
     * 权限、序列化与错误码完全一致，只是不经过客户端那层类型化装配。
     *
     * 若某个构建里确实装配了 `remote.turnRewind`（例如以后有了产物），
     * 优先走它，行为不变。
     *
     * @param {object} ctx - 客户端上下文。
     * @param {string} method - "list" 或 "rewind"。
     * @param {object} args - 端点参数（{request: {...}}）。
     * @returns {Promise<object>} 业务取值。
     */
    /**
     * 把业务错误（带 code）原样抛出。
     *
     * @param {object} carried - 插件返回的 {ok,value} / {ok,error}。
     * @returns {object} value。
     */
    function carriedValue(carried) {
      if (!carried || carried.ok !== true) {
        var error = (carried && carried.error) || {};
        var failure = new Error(error.message || error.code || "unknown");
        failure.code = error.code || "unknown";
        throw failure;
      }
      return carried.value;
    }

    async function callTurnRewind(ctx, method, args) {
      var typed = ctx.remote && ctx.remote.turnRewind;
      if (typed && typeof typed[method] === "function") {
        // 类型化命名空间：网关已经解掉传输层，只留插件自己的 {ok,value}。
        return carriedValue(await typed[method](args.request));
      }
      var connection = typeof ctx.get === "function" ? ctx.get("connection") : null;
      if (!connection || !connection.rpc || typeof connection.rpc.call !== "function") {
        throw new Error("引擎连接不可用，暂时无法撤回");
      }
      // ⚠️ 通用通道会**再包一层**：Connection 自己返回 {ok,value}，而插件方法
      // 返回的也是 {ok,value}。只解一层的话拿到的仍是 {ok,value}，于是
      // `value.childSessionId` 恒为 undefined —— 界面会报"宿主没有返回新的会话"，
      // 而 list 会静默退化成"没有文件记录"。两层都必须解。
      var result = await connection.rpc.call("/api", "turnRewind/" + method, { args: args });
      if (!result || result.ok !== true) {
        var transportError = (result && result.error) || {};
        var transportFailure = new Error(
          transportError.message || transportError.code || "引擎没有回应",
        );
        transportFailure.code = transportError.code || "unknown";
        throw transportFailure;
      }
      return carriedValue(result.value);
    }

    /**
     * 读一次某会话的回合文件变更。
     *
     * @param {object} ctx - 客户端上下文。
     * @param {string} sessionId - 会话标识。
     * @returns {Promise<Array<object>>} 按回合升序的行。
     */
    async function readTurns(ctx, sessionId) {
      try {
        var value = await callTurnRewind(ctx, "list", { request: { sessionId: sessionId } });
        return Array.isArray(value && value.turns) ? value.turns : [];
      } catch (error) {
        // 读不到就当作"没有记录"：界面据此说"只能撤回文本"，不假装有快照。
        return [];
      }
    }

    /**
     * 找到某条消息所属的回合，以及该回合首条用户消息的 seq。
     *
     * @param {Array<object>} turns - turnRewind/list 的行。
     * @param {number} seq - 目标消息的 seq。
     * @returns {{turn: number|undefined, startSeq: number|undefined}} 定位结果。
     */
    function locateTurn(turns, seq) {
      var owner;
      for (var i = 0; i < turns.length; i++) {
        var row = turns[i];
        if (typeof row.startSeq !== "number") continue;
        if (row.startSeq <= seq && (owner === undefined || row.turn > owner)) owner = row.turn;
      }
      if (owner === undefined) return { turn: undefined, startSeq: undefined };
      var startSeq;
      for (var j = 0; j < turns.length; j++) {
        if (turns[j].turn === owner) startSeq = turns[j].startSeq;
      }
      return { turn: owner, startSeq: startSeq };
    }

    /**
     * 撤回前的文件影响说明：只讲真会发生的事。
     *
     * @param {Array<object>} turns - 全部回合行。
     * @param {number} turn - 目标回合。
     * @returns {{files: number, incomplete: boolean, hasSnapshot: boolean}} 统计。
     */
    function planFrom(turns, turn) {
      var files = 0;
      var incomplete = false;
      var hasSnapshot = false;
      for (var i = 0; i < turns.length; i++) {
        var row = turns[i];
        if (row.turn === turn) hasSnapshot = true;
        // 撤回会把这一段之后的每个回合一起回滚，所以"可能恢复不全"要看
        // 所有这些回合的快照状态，而不只是被点中的那一个。原生端
        // `planForSeq` 用的是同一套口径，两端必须说同一句话。
        if (row.turn < turn) continue;
        files += typeof row.files === "number" ? row.files : 0;
        incomplete = incomplete || row.incomplete === true || row.truncated === true;
      }
      return { files: files, incomplete: incomplete, hasSnapshot: hasSnapshot };
    }

    /**
     * 读某会话已存的草稿文本。
     *
     * @param {string} sessionId - 会话标识。
     * @returns {string} 草稿文本；无则空串。
     */
    function readDraft(sessionId) {
      try {
        var raw = window.localStorage.getItem(DRAFT_KEY + "." + sessionId);
        if (raw === null) return "";
        var stored = JSON.parse(raw);
        if (!stored || typeof stored !== "object") return "";
        var draft = stored.draft;
        if (typeof draft === "string") return draft;
        if (draft && typeof draft === "object" && typeof draft.text === "string") {
          return draft.text;
        }
        return "";
      } catch (error) {
        return "";
      }
    }

    /**
     * 给会话预置草稿。
     *
     * 写官方持久化键 `dsh.conversation.<id>`：输入框首次挂载时由
     * `readConversationDraft` 采用，因此跨"打开子会话"这一跳也生效。
     *
     * @param {string} sessionId - 会话标识。
     * @param {string} text - 要放进去的文本。
     * @param {string} mode - "replace" 整体替换，"append" 接在后面。
     */
    function seedDraft(sessionId, text, mode) {
      try {
        var key = DRAFT_KEY + "." + sessionId;
        var existing = readDraft(sessionId);
        var next =
          mode === "append" && existing.trim() !== ""
            ? existing.trimEnd() + "\n\n" + text
            : text;
        var raw = window.localStorage.getItem(key);
        var stored = raw === null ? {} : JSON.parse(raw);
        if (!stored || typeof stored !== "object") stored = {};
        stored.draft = next;
        window.localStorage.setItem(key, JSON.stringify(stored));
      } catch (error) {
        // 隐私模式或配额已满时持久化会失败；界面侧的 setDraft 仍会填好输入框。
      }
    }

    /**
     * 复制文本，旧的浏览器没有异步剪贴板时退回选区方案。
     *
     * @param {string} text - 要复制的文本。
     * @returns {Promise<boolean>} 是否成功。
     */
    async function copyText(text) {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(text);
          return true;
        }
      } catch (error) {
        // 不安全上下文里 writeText 会抛错，继续尝试旧方案。
      }
      return legacyCopy(text);
    }

    /** 旧方案：临时 textarea + execCommand，成功后立刻移除。 */
    function legacyCopy(text) {
      try {
        var area = document.createElement("textarea");
        area.value = text;
        area.setAttribute("readonly", "");
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.appendChild(area);
        area.select();
        var ok = document.execCommand("copy");
        area.remove();
        return ok === true;
      } catch (error) {
        return false;
      }
    }
    /**
     * 极小的可观察存储：给"正在编辑"这类跨组件状态用。
     *
     * @param {object} initial - 初值。
     * @returns {{get:Function,set:Function,subscribe:Function}} 三件套。
     */
    function createStore(initial) {
      var value = initial;
      var listeners = new Set();
      return {
        get: function () {
          return value;
        },
        set: function (next) {
          value = next;
          listeners.forEach(function (fn) {
            fn();
          });
        },
        subscribe: function (fn) {
          listeners.add(fn);
          return function () {
            listeners.delete(fn);
          };
        },
      };
    }

    /**
     * 每个会话的编辑态与待发送任务。
     *
     * 键是会话标识：编辑只影响打开它的那个会话；待发送任务跨"打开子会话"
     * 这一跳存活，所以在模块层而不是组件层。
     */
    var edits = new Map();

    /** 子会话打开后要立刻发送的文本（编辑提交、重新输出）。 */
    var pendingSends = new Map();

    /** 子会话打开后只预填、不自动发送的文本（取消编辑后恢复草稿等）。 */
    var pendingDrafts = new Map();

    /** 输入桥的触发计数：每变一次，挂载中的输入区重读一次待办。 */
    var drafts = createStore(0);

    /** 编辑态触发计数。 */
    var editStore = createStore(0);

    /** 合并 content 里的文本块，与官方 contentParts 的口径一致。 */
    function joinedText(content) {
      var out = "";
      if (!Array.isArray(content)) return out;
      for (var i = 0; i < content.length; i++) {
        var block = content[i];
        if (block && block.type === "text" && typeof block.text === "string") out += block.text;
      }
      return out;
    }

    /**
     * 一条 assistant-step 节点的身份（messageId、seq）。
     *
     * rc.2 里这些字段**不在** node.data 上：data 只有 {status,turn,step,blocks,
     * time,usage,finalNode}，真正的身份挂在 data.finalNode（assistant/message
     * 事件带来的那一条）。早先按 data.messageId 读会导致分享永远"没有正文"、
     * 定位不到回合。两处都读，谁先有值用谁，旧结构（若哪天回到 data 上）也不坏。
     *
     * @param {object} data - assistant-step 的 node.data。
     * @returns {{messageId: (string|undefined), seq: (number|undefined)}}
     */
    function answerIdentity(data) {
      var final = (data && data.finalNode) || {};
      return {
        messageId: typeof data?.messageId === "string" ? data.messageId : final.messageId,
        seq: typeof data?.seq === "number" ? data.seq : final.seq,
        turn: typeof data?.turn === "number" ? data.turn : final.turn,
      };
    }

    /** 从聊天快照里找一条助手消息的正文。 */
    function assistantTextOf(snapshot, messageId) {
      var keys = (snapshot && snapshot.order) || [];
      for (var i = 0; i < keys.length; i++) {
        var node = snapshot.nodes.get(keys[i]);
        if (!node || node.kind !== "assistant-step") continue;
        if (answerIdentity(node.data).messageId !== messageId) continue;
        var blocks = node.data.blocks || [];
        var text = "";
        for (var j = 0; j < blocks.length; j++) {
          var block = blocks[j];
          if (block && typeof block.text === "string") text += block.text;
        }
        // 过程块与正文块都在 data.blocks 里；正文为空时退回 finalNode 的块，
        // 免得"回答明明在屏幕上"却报没有正文。
        if (text === "") {
          var finalBlocks = (node.data.finalNode && node.data.finalNode.blocks) || [];
          for (var k = 0; k < finalBlocks.length; k++) {
            var fb = finalBlocks[k];
            if (fb && typeof fb.text === "string") text += fb.text;
          }
        }
        return text;
      }
      return "";
    }

    /** 从聊天快照里找一条助手消息所属的回合。 */
    function assistantTurnOf(snapshot, messageId) {
      var keys = (snapshot && snapshot.order) || [];
      for (var i = 0; i < keys.length; i++) {
        var node = snapshot.nodes.get(keys[i]);
        if (!node || node.kind !== "assistant-step") continue;
        var identity = answerIdentity(node.data);
        if (identity.messageId === messageId) {
          return typeof identity.turn === "number" ? identity.turn : undefined;
        }
      }
      return undefined;
    }

    /**
     * 回合里那条用户输入原文（重新输出时重发它）。
     *
     * 优先用回合号精确匹配；快照里没有回合号时退回"seq 不大于回答的
     * 最后一条用户输入"，仍然是真实记录，不猜文本。
     *
     * @param {object} snapshot - 聊天快照。
     * @param {number} turn - 目标回合（可能未知）。
     * @param {number} answerSeq - 该回答的 seq（回合号未知时用于回退）。
     * @returns {string} 原文；找不到则空串。
     */
    function promptOfTurn(snapshot, turn, answerSeq) {
      var keys = snapshot.order || [];
      var fallback = "";
      var fallbackSeq = -1;
      for (var i = 0; i < keys.length; i++) {
        var node = snapshot.nodes.get(keys[i]);
        if (!node || node.kind !== "user") continue;
        if (!node.data) continue;
        var source = node.data.source;
        if (!source || source.kind !== "user") continue;
        if (typeof turn === "number" && node.data.turn === turn) {
          var exact = joinedText(node.data.content);
          if (exact) return exact;
        }
        if (typeof answerSeq === "number" && typeof node.data.seq === "number") {
          if (node.data.seq <= answerSeq && node.data.seq > fallbackSeq) {
            var text = joinedText(node.data.content);
            if (text) {
              fallback = text;
              fallbackSeq = node.data.seq;
            }
          }
        }
      }
      return fallback;
    }

    /**
     * 一条助手回答自己的 seq（撤回、定位回合都要它）。
     *
     * @param {object} snapshot - 聊天快照。
     * @param {string} messageId - 助手消息标识。
     * @returns {number|undefined} seq；找不到则 undefined。
     */
    function seqOfAnswer(snapshot, messageId) {
      var keys = (snapshot && snapshot.order) || [];
      for (var i = 0; i < keys.length; i++) {
        var node = snapshot.nodes.get(keys[i]);
        if (!node || node.kind !== "assistant-step") continue;
        var identity = answerIdentity(node.data);
        if (identity.messageId === messageId && typeof identity.seq === "number") {
          return identity.seq;
        }
      }
      return undefined;
    }

    /** 一句话说清撤回会改什么文件。 */
    function describe(plan) {
      if (!plan.hasSnapshot) return "这一轮没有留下文件记录，只能撤回对话文本。";
      if (plan.files === 0) return "这一轮没有改动文件，只撤回对话文本。";
      var base = "将回滚 " + plan.files + " 个文件的改动。";
      return plan.incomplete ? base + "其中有文件没留下快照，可能恢复不全。" : base;
    }

    /** 顶部浮层提示：失败要说清原因，不做静默。 */
    var toasts = createStore([]);
    var toastSeq = 0;
    function notify(text, isError) {
      var id = ++toastSeq;
      toasts.set(toasts.get().concat([{ id: id, text: text, isError: isError === true }]));
      window.setTimeout(function () {
        toasts.set(
          toasts.get().filter(function (item) {
            return item.id !== id;
          }),
        );
      }, isError ? 6000 : 2600);
    }

    function ToastLayer() {
      var list = React.useSyncExternalStore(toasts.subscribe, toasts.get);
      if (list.length === 0) return null;
      return ReactDOM.createPortal(
        h(
          "div",
          {
            style: {
              position: "fixed",
              bottom: "max(96px, calc(env(safe-area-inset-bottom) + 24px))",
              zIndex: 2147483100,
              display: "flex",
              flexDirection: "column",
              gap: 8,
              pointerEvents: "none",
              left: "max(16px, env(safe-area-inset-left))",
              right: "max(16px, env(safe-area-inset-right))",
              alignItems: "center",
            },
          },
          list.map(function (item) {
            return h(
              "div",
              {
                key: item.id,
                className: "dsh-rw-toast",
                "data-error": item.isError ? "" : undefined,
                role: item.isError ? "status" : undefined,
              },
              item.isError
                ? h("span", { className: "dsh-rw-toast-icon", "aria-hidden": "true" }, "!")
                : null,
              h("span", { style: { minWidth: 0, overflowWrap: "anywhere" } }, item.text),
            );
          }),
        ),
        document.body,
      );
    }

    /** 已知失败码 → 一句人能读的话。界面只给普通文案，不把 kebab 错误码丢给用户。 */
    var ERROR_TEXT = {
      "session-busy": "这一轮还在进行，等它停下来再试。",
      "session-not-found": "找不到这个会话了，可能已经被归档。",
      "target-not-found": "没有找到这条消息对应的记录，可能已经被撤回。",
      "target-not-in-turn": "这条消息不在任何一轮里，没法从这里开始撤回。",
      "no-prefix": "这一条之前没有可以撤回的内容。",
      "query-unavailable": "暂时读不到会话记录，稍后再试。",
      "fork-unavailable": "宿主暂时不能开新分支，稍后再试。",
      "no-cwd": "这个会话没有可用的工作目录，文件无法回滚。",
      "bad-request": "这次请求不完整，请重新操作一次。",
      "session-unknown": "这个会话的状态还不清楚，稍后再试。",
    };

    /**
     * 把一次失败说成一句人话。
     *
     * 错误对象里的 message 往往就是 code 本身（插件端 `rejected(code)` 只带
     * code），直接拼给用户会看到「撤回失败：session-busy」这种机器话。
     * 已知码一律换成可读说法；**不认识的码保留原文** —— 宁可露出没见过的
     * 失败，也不要编一个看起来合理的假原因。
     *
     * @param {object} error - 抛出的 Error（可能带 code）。
     * @returns {string} 可直接显示的文案。
     */
    function readableError(error) {
      var code = error && typeof error === "object" && typeof error.code === "string"
        ? error.code
        : undefined;
      if (code !== undefined && Object.prototype.hasOwnProperty.call(ERROR_TEXT, code)) {
        return ERROR_TEXT[code];
      }
      var raw = error && typeof error === "object" && typeof error.message === "string"
        ? error.message
        : String(error);
      if (code !== undefined && (raw === "" || raw === code)) {
        return "操作没有完成（" + code + "）";
      }
      return raw === "" ? "操作没有完成" : raw;
    }

    /**
     * 撤回 / 编辑 / 重新输出的确认框。
     *
     * 两个选择都是真选项，不替用户预设：是否回滚文件、已有草稿替换还是保留。
     */
    function ConfirmDialog(props) {
      var plan = props.plan;
      var hasDraft = props.hasDraft;
      var state = React.useState({ files: plan.files > 0, replace: true });
      var choice = state[0];
      var setChoice = state[1];
      var busy = React.useState(false);
      var working = busy[0];
      var setWorking = busy[1];

      function toggleFiles() {
        setChoice({ files: !choice.files, replace: choice.replace });
      }
      function toggleReplace(next) {
        setChoice({ files: choice.files, replace: next });
      }
      function confirm() {
        setWorking(true);
        Promise.resolve(props.onConfirm(choice.files, choice.replace)).then(
          function () {
            setWorking(false);
            props.onClose();
          },
          function (error) {
            setWorking(false);
            notify((props.failPrefix || "操作失败") + "：" + readableError(error), true);
          },
        );
      }

      return ReactDOM.createPortal(
        h(
          "div",
          {
            className: "dsh-rw-scrim",
            role: "dialog",
            "aria-modal": "true",
            onClick: function (event) {
              if (event.target === event.currentTarget) props.onClose();
            },
          },
          h(
            "div",
            { className: "dsh-rw-card" },
            h("h3", null, props.title || "撤回这条消息？"),
            h("p", null, describe(plan)),
            plan.files > 0
              ? h(
                  "div",
                  { className: "dsh-rw-line", onClick: toggleFiles },
                  h("span", { className: "dsh-rw-box", "data-on": choice.files || undefined }, choice.files ? "✓" : ""),
                  h(
                    "span",
                    null,
                    "同时回滚这一轮改过的文件",
                    h(
                      "span",
                      { className: "dsh-rw-note", style: { display: "block" } },
                      "只恢复记录里的文件；这一轮之后被外部改过的文件不会被覆盖",
                    ),
                  ),
                )
              : null,
            hasDraft
              ? h(
                  "div",
                  null,
                  h("p", { style: { marginTop: 12 } }, "输入框里已经有草稿："),
                  h(
                    "div",
                    { className: "dsh-rw-line", onClick: function () { toggleReplace(true); } },
                    h("span", { className: "dsh-rw-box", "data-on": choice.replace || undefined }, choice.replace ? "✓" : ""),
                    h("span", null, "用这条消息替换草稿"),
                  ),
                  h(
                    "div",
                    { className: "dsh-rw-line", onClick: function () { toggleReplace(false); } },
                    h("span", { className: "dsh-rw-box", "data-on": !choice.replace || undefined }, choice.replace ? "" : "✓"),
                    h("span", null, "保留草稿，把这条消息接在后面"),
                  ),
                )
              : null,
            h(
              "div",
              { className: "dsh-rw-actions" },
              h("button", { type: "button", onClick: props.onClose, disabled: working }, "取消"),
              h(
                "button",
                { type: "button", "data-primary": true, onClick: confirm, disabled: working },
                working ? "处理中…" : props.confirmLabel || "撤回",
              ),
            ),
          ),
        ),
        document.body,
      );
    }

    /**
     * 分享预览：先在本机看清内容，再决定复制、存成文件或交给系统分享。
     *
     * 不默认上传、不替用户发到任何外部服务。
     */
    function ShareDialog(props) {
      function save() {
        try {
          var blob = new Blob([props.text], { type: "text/plain;charset=utf-8" });
          var url = URL.createObjectURL(blob);
          var link = document.createElement("a");
          link.href = url;
          link.download = props.fileName || "dsh-conversation.txt";
          document.body.appendChild(link);
          link.click();
          document.body.removeChild(link);
          window.setTimeout(function () {
            URL.revokeObjectURL(url);
          }, 4000);
          notify("已下载到本机");
        } catch (error) {
          notify("保存失败：" + (error && error.message ? error.message : error), true);
        }
      }
      function share() {
        // Android WebView 没有 W3C Web Share（navigator.share 恒为 undefined），
        // 所以先问应用自己的原生分享桥；桌面浏览器再退回 navigator.share。
        // 直接判 navigator.share 会把应用内的"系统分享"变成死按钮。
        var bridge = window.DshAndroidShare;
        if (bridge && typeof bridge.shareText === "function") {
          var reply = bridge.shareText(props.text, props.title || "分享这段对话", "dsh-conversation");
          if (reply === "ok") {
            props.onClose();
            return;
          }
          notify("系统分享没有完成：" + String(reply || "未知原因"), true);
          return;
        }
        if (!navigator.share) {
          notify("这个浏览器没有系统分享，可以先复制或保存到文件", true);
          return;
        }
        navigator.share({ text: props.text, title: props.title || "对话内容" }).catch(function (error) {
          if (error && error.name === "AbortError") return;
          notify("系统分享没有完成", true);
        });
      }
      return ReactDOM.createPortal(
        h(
          "div",
          {
            className: "dsh-rw-scrim",
            role: "dialog",
            "aria-modal": "true",
            onClick: function (event) {
              if (event.target === event.currentTarget) props.onClose();
            },
          },
          h(
            "div",
            { className: "dsh-rw-card dsh-rw-share" },
            h("h3", null, "分享这段对话"),
            h("pre", null, props.text.length > 4000 ? props.text.slice(0, 4000) + String.fromCharCode(10) + "…" : props.text),
            h(
              "div",
              { className: "dsh-rw-actions" },
              h("button", { type: "button", onClick: props.onClose }, "关闭"),
              h(
                "button",
                {
                  type: "button",
                  onClick: function () {
                    copyText(props.text).then(function (ok) {
                      notify(ok ? "已复制" : "复制失败", !ok);
                    });
                  },
                },
                "复制",
              ),
              h("button", { type: "button", onClick: save }, "保存到文件"),
              h("button", { type: "button", "data-primary": true, onClick: share }, "系统分享"),
            ),
          ),
        ),
        document.body,
      );
    }

    /**
     * 一个动作图标：带 tooltip、可禁用、成功后短暂变勾。
     *
     * @param {object} props - {label, glyph, disabled, done, onClick}。
     * @returns {object} React 元素。
     */
    function ActionIcon(props) {
      return h(
        "button",
        {
          type: "button",
          className: "dsh-rw-btn",
          title: props.label,
          "aria-label": props.label,
          disabled: props.disabled === true,
          "data-done": props.done === true || undefined,
          onClick: function (event) {
            event.stopPropagation();
            if (props.disabled === true) return;
            props.onClick();
          },
        },
        icon(props.glyph, 16),
      );
    }

    /**
     * 回合结束提示的数据：官方 `turn-error` 节点已经过 `displayFailure` 脱敏。
     *
     * 节点里只有 {seq,time,turn,step,message,code}，没有原始 payload —— AUTH 类
     * 失败在上游被削成 `{code:'AUTH', message:''}`，所以这里无论展开还是复制都
     * 不可能带出凭据。本表只为已知 code 补一句人话与恢复建议；不认识的 code
     * 只说"未知/可能"，不编原因。
     */
    var TURN_NOTICE = {
      AUTH: {
        title: "本轮运行失败",
        reason: "API 密钥无效或已过期。",
        next: "检查模型设置里的密钥后重试。",
      },
      ACCOUNT_SIGN_IN_REQUIRED: {
        title: "账号需要登录",
        reason: "这一轮要求账号认证，但当前没有可用的登录状态。",
        next: "登录 DeepSeek 后重试。",
      },
      ACCOUNT_SIGNED_OUT: {
        title: "账号已退出，本轮已停止",
        reason: "你在这一轮结束前退出了 DeepSeek 登录。",
        next: "重新登录后重试。",
      },
      INTERRUPTED: {
        title: "运行已中断",
        reason: "DSH 进程在本轮完成前退出，未完成的工具调用结果未知。",
        next: "恢复后重试。",
      },
      QUOTA: { title: "本轮运行失败", reason: "当前请求的额度已用尽。", next: "" },
      ACCOUNT_QUOTA: { title: "本轮运行失败", reason: "当前请求的额度已用尽。", next: "" },
    };

    /** 中性（非失败色）的两种结束：用户/父会话主动停止，或本轮已正常收尾。 */
    var NEUTRAL_CODES = { ACCOUNT_SIGNED_OUT: true, INTERRUPTED: true };

    /**
     * 把官方 turn-error 节点折成提示要显示的四段文字。
     *
     * @param {object} node - turn-error 节点数据。
     * @returns {{title: string, reason: string, next: string, code: string, neutral: boolean}}
     */
    function describeTurnError(node) {
      var code = typeof node.code === "string" ? node.code : "";
      var known = Object.prototype.hasOwnProperty.call(TURN_NOTICE, code)
        ? TURN_NOTICE[code]
        : null;
      var message = typeof node.message === "string" ? node.message : "";
      var reason;
      if (known !== null) {
        // 已知码优先用本地可读说法；服务端 message 只作为补充证据留在复制文本外。
        reason = known.reason;
      } else if (message !== "") {
        reason = message;
      } else if (code !== "") {
        reason = "未知原因（只有错误码 " + code + "，没有可读说明）。";
      } else {
        reason = "未知原因（这一轮没有记录失败说明）。";
      }
      return {
        title: known !== null ? known.title : "本轮运行失败",
        reason: reason,
        next: known !== null ? known.next : "",
        code: code,
        neutral: NEUTRAL_CODES[code] === true,
      };
    }

    /** 摘要行：一行小字，优先可读原因，其次状态名。 */
    function turnSummary(node) {
      var described = describeTurnError(node);
      if (described.reason !== "") return described.reason;
      return described.title === "" ? "本轮运行失败" : described.title;
    }

    /**
     * 回合结束提示（Web 表现层）。
     *
     * 替换官方 `conversation.chat.node` 的 `turn-error` 渲染器：契约写明"重复
     * 注册同一个 key 会替换该 occupant"，而这里正是要换掉官方那块常驻的大段
     * 失败面板。默认只画一行小字摘要 + 状态图标 + 箭头；整行可点开，展开后才有
     * 原因、恢复建议、错误码与复制。数据全部来自官方节点，不从正文猜失败。
     */
    function TurnNoticeRow(props) {
      var node = (props.node && props.node.data) || {};
      var state = React.useState(false);
      var copied = React.useState(false);
      return noticeView(node, {
        expanded: state[0],
        onToggle: function () {
          state[1](!state[0]);
        },
        copied: copied[0],
        onCopy: function () {
          copied[1](true);
        },
      });
    }

    /**
     * 提示的纯渲染部分：展开与否、复制状态都由调用方给。
     *
     * 拆出来是为了能离线核对"折叠时只有一行"、"展开后才有代码与复制"这两条
     * 契约 —— 组件里的 useState 在测试桩里不会真的改状态。
     *
     * @param {object} node - turn-error 节点数据（已由上游脱敏）。
     * @param {{expanded:boolean,onToggle:Function,copied:boolean,onCopy:Function}} ui - 交互状态。
     * @returns {object} React 元素。
     */
    function noticeView(node, ui) {
      var described = describeTurnError(node);
      var expanded = ui.expanded === true;
      var isCopied = ui.copied === true;

      var copyText = [
        described.title,
        described.reason,
        described.next === "" ? null : described.next,
        described.code === "" ? null : "代码：" + described.code,
      ]
        .filter(function (line) {
          return line !== null && line !== "";
        })
        .join("\n");

      return h(
        "div",
        {
          className: "dsh-rw-notice",
          "data-neutral": described.neutral ? "" : undefined,
          "data-expanded": expanded ? "" : undefined,
        },
        h(
          "button",
          {
            type: "button",
            className: "dsh-rw-notice-row",
            "aria-expanded": expanded ? "true" : "false",
            title: expanded ? "收起这一轮的结束原因" : "展开这一轮的结束原因",
            onClick: function () {
              ui.onToggle();
            },
          },
          h(
            "span",
            { className: "dsh-rw-notice-icon", "aria-hidden": "true" },
            icon(
              described.neutral
                ? ["M12 4a8 8 0 1 0 8 8", "M12 8v4", "M12 16h.01"]
                : ["M12 4l9 16H3z", "M12 10v4", "M12 17h.01"],
              15,
            ),
          ),
          h(
            "span",
            { className: "dsh-rw-notice-summary" },
            turnSummary(node),
          ),
          h(
            "span",
            { className: "dsh-rw-notice-chevron", "aria-hidden": "true" },
            icon(["M6 9l6 6 6-6"], 14),
          ),
        ),
        expanded
          ? h(
              "div",
              { className: "dsh-rw-notice-body" },
              h("p", { className: "dsh-rw-notice-title" }, described.title),
              h("p", { className: "dsh-rw-notice-reason" }, described.reason),
              described.next === ""
                ? null
                : h("p", { className: "dsh-rw-notice-next" }, described.next),
              described.code === ""
                ? null
                : h("p", { className: "dsh-rw-notice-code" }, "代码：" + described.code),
              h(
                "button",
                {
                  type: "button",
                  className: "dsh-rw-notice-copy",
                  onClick: function () {
                    void copyText2(copyText).then(function (ok) {
                      if (ok) ui.onCopy();
                    });
                  },
                },
                h(
                  "span",
                  { "aria-hidden": "true" },
                  icon(GLYPH.copy, 13),
                ),
                isCopied ? "已复制" : "复制详情",
              ),
            )
          : null,
      );
    }

    /**
     * 复制提示文本。与 toast 版的 copyText 分开：这里静默成功（按钮自己回显"已复制"），
     * 失败才用 toast 说明，避免展开一次就弹一条提示。
     *
     * @param {string} text - 要复制的文本。
     * @returns {Promise<boolean>} 是否成功。
     */
    async function copyText2(text) {
      var ok = await copyText(text);
      if (!ok) notify("复制失败，请手动选择文本", true);
      return ok;
    }

    /** 会话级上下文：动作实现 + 当前会话 id 的取值方式。 */
    var actionsRef = null;

    /**
     * 用户自己那一行的两个动作（复制、撤回）。
     *
     * 气泡本体仍由官方 factory 槽渲染，这里只补动作位，所以排版、引文、
     * 附件预览与官方完全一致。
     */
    function UserRow(props) {
      var node = props.node;
      var data = node.data || {};
      var seq = typeof data.seq === "number" ? data.seq : undefined;
      var sessionId = props.sessionId;
      var text = joinedText(data.content);
      var copied = React.useState(false);
      var isCopied = copied[0];
      var setCopied = copied[1];
      var actions = actionsRef;

      var prepared = {
        text: text,
        attachments: [],
        extraBlocks: [],
      };
      for (var i = 0; i < (data.content || []).length; i++) {
        var block = data.content[i];
        if (!block) continue;
        if (block.type === "text") continue;
        if (block.type === "image" && block.attachment !== undefined) {
          prepared.attachments.push({ type: "image", image: { attachment: block.attachment } });
          continue;
        }
        if (block.type === "file" && block.attachment !== undefined) {
          prepared.attachments.push({ type: "file", file: block.attachment });
          continue;
        }
        prepared.extraBlocks.push(block);
      }

      return h(
        "div",
        { style: { display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 6 } },
        // 子槽由官方那一格声明；这里只在确实拿到渲染器时补（无声明时为 undefined）。
        typeof props.renderSlotChain === "function"
          ? props.renderSlotChain("conversation.chat.user.before", { source: data.source, kind: node.kind })
          : null,
        props.renderFactorySlot("conversation.chat.user.content", {
          content: prepared,
          loadImage: props.loadImage,
          facts: {},
          interactive: true,
        }),
        h(
          "div",
          { className: "dsh-rw-row" },
          h(ActionIcon, {
            label: isCopied ? "已复制" : "复制",
            glyph: isCopied ? GLYPH.copy : GLYPH.copy,
            done: isCopied,
            onClick: function () {
              if (!actions) return;
              void actions.copy(text).then(function (ok) {
                if (!ok) return;
                setCopied(true);
                window.setTimeout(function () {
                  setCopied(false);
                }, 1400);
              });
            },
          }),
          h(ActionIcon, {
            label: seq === undefined ? "这条消息还没有可撤回的记录" : "撤回这条消息及其之后的内容",
            glyph: GLYPH.undo,
            disabled: seq === undefined || !sessionId,
            onClick: function () {
              if (!actions || seq === undefined) return;
              void actions.rewind(sessionId, seq, {
                title: "撤回这条消息？",
                confirmLabel: "撤回",
                failPrefix: "撤回失败",
                draft: text,
              });
            },
          }),
          h(ActionIcon, {
            label: "编辑这条消息",
            glyph: ["M4 20h4l10-10-4-4L4 16v4", "M14 6l4 4"],
            disabled: seq === undefined || !sessionId,
            onClick: function () {
              if (!actions || seq === undefined) return;
              actions.beginEdit(sessionId, data.messageId || String(seq), seq, text);
            },
          }),
        ),
      );
    }

    /**
     * 助手消息那一行补的两种动作：重新输出、分享。
     *
     * 复制与赞踩由官方条目提供，这里不重复注册，避免同一行出现两组。
     */
    function AssistantRow(props) {
      var messageId = props.messageId;
      var sessionId = props.sessionId;
      var snapshot = props.useChat(function (value) {
        return value;
      });
      var actions = actionsRef;
      var reseed = React.useState(0);
      void reseed;
      return h(
        "span",
        { className: "dsh-rw-row", style: { marginTop: 0 } },
        h(ActionIcon, {
          label: "重新输出（先回滚这一轮的文件改动）",
          glyph: GLYPH.retry,
          onClick: function () {
            if (!actions) return;
            void actions.regenerate(sessionId, messageId, snapshot);
          },
        }),
        h(ActionIcon, {
          label: "分享",
          glyph: GLYPH.share,
          onClick: function () {
            if (!actions) return;
            actions.share(snapshot, messageId);
          },
        }),
      );
    }



    /**
     * 输入区桥：把"打开子会话后要放进去的文字"交给真正的输入框。
     *
     * 用 `inputActions.setDraft`（官方公开动作）而不是直接改 DOM，所以
     * Lexical 的内部状态、撤销栈和持久化都保持官方口径。
     */
    function DraftBridge(props) {
      var sessionId = props.sessionId;
      var inputActions = props.inputActions;
      var ticket = React.useSyncExternalStore(drafts.subscribe, drafts.get);
      void ticket;
      var editTicket = React.useSyncExternalStore(editStore.subscribe, editStore.get);
      void editTicket;
      var editing = sessionId ? actionsRef && actionsRef.editingOf(sessionId) : null;
      React.useEffect(
        function () {
          if (!sessionId || !inputActions) return;
          var send = pendingSends.get(sessionId);
          if (send !== undefined) {
            pendingSends.delete(sessionId);
            inputActions.setDraft(send);
            inputActions.submit();
            return;
          }
          var text = pendingDrafts.get(sessionId);
          if (text !== undefined) {
            pendingDrafts.delete(sessionId);
            inputActions.setDraft(text);
          }
        },
        [sessionId, inputActions, ticket],
      );
      if (!editing) return null;
      // 编辑态必须看得见、退得出去：与原生端同一条横条，取消把草稿还回来。
      return h(
        "div",
        { className: "dsh-rw-editing", "data-dsh-editing": "true" },
        h(
          "span",
          { className: "dsh-rw-editing-text" },
          "正在编辑这条消息，发送会从这里继续",
        ),
        h(
          "button",
          {
            type: "button",
            className: "dsh-rw-editing-cancel",
            title: "取消编辑，恢复原来的草稿",
            "aria-label": "取消编辑，恢复原来的草稿",
            onClick: function (event) {
              event.stopPropagation();
              if (actionsRef) actionsRef.cancelEdit(sessionId);
            },
          },
          icon(["M6 6l12 12", "M18 6L6 18"], 14),
        ),
      );
    }





    /**
     * 把文本交给某会话的输入框，并可选地立刻发送。
     *
     * 两条路互为兜底：写官方持久化键 `dsh.conversation.<id>`（输入框首次
     * 挂载时由 `readConversationDraft` 采用），同时挂一个待办让已挂载的
     * 输入桥用 `inputActions.setDraft` 立刻替换编辑器内容。任一条生效，
     * 用户就能看到文本 —— 不会出现"提示成功了但框里是空的"。
     *
     * @param {string} sessionId - 目标会话。
     * @param {string} text - 文本。
     * @param {boolean} sendNow - true 时填好立刻发送。
     */
    function publishDraft(sessionId, text, sendNow) {
      seedDraft(sessionId, text, "replace");
      if (sendNow === true) pendingSends.set(sessionId, text);
      else pendingDrafts.set(sessionId, text);
      drafts.set(drafts.get() + 1);
    }

    /** 当前打开着的确认框 / 分享框；null 表示没有。 */
    var overlays = createStore(null);

    /**
     * 弹一个确认框，等用户做出选择。
     *
     * 用模块级 store 而不是组件状态，是因为动作由图标点击直接触发，
     * 触发点不在渲染树里。
     *
     * @param {object} spec - {props, run}：props 是弹窗参数，run 是确认后的动作。
     * @returns {void}
     */
    function openConfirm(spec) {
      overlays.set({
        kind: "confirm",
        props: {
          title: spec.title,
          confirmLabel: spec.confirmLabel,
          plan: spec.plan,
          hasDraft: spec.hasDraft,
          failPrefix: spec.failPrefix,
          onConfirm: spec.run,
          onClose: function () {
            overlays.set(null);
          },
        },
      });
    }

    /**
     * 弹分享框。
     *
     * @param {object} spec - {title, text}。
     * @returns {void}
     */
    function openShare(spec) {
      overlays.set({
        kind: "share",
        props: {
          title: spec.title,
          text: spec.text,
          onClose: function () {
            overlays.set(null);
          },
        },
      });
    }

    /** 按当前 overlay 状态渲染弹层。 */
    function Layers() {
      var current = React.useSyncExternalStore(overlays.subscribe, overlays.get);
      var dialog = null;
      if (current && current.kind === "confirm") dialog = h(ConfirmDialog, current.props);
      else if (current && current.kind === "share") dialog = h(ShareDialog, current.props);
      return h(React.Fragment, null, h(ToastLayer, null), dialog);
    }

    /**
     * 打开某个会话：先写当前选择（官方持久化键），再交给工作区导航。
     *
     * @param {object} ctx - 客户端上下文。
     * @param {string} sessionId - 目标会话。
     * @returns {boolean} 导航服务是否已接手跳转。
     */
    function openSession(ctx, sessionId) {
      try {
        window.localStorage.setItem("dsh.sessions.current", JSON.stringify({ sessionId: sessionId }));
      } catch (error) {
        // 隐私模式下写不进去；导航仍然继续，只是刷新后回到旧会话。
      }
      var workspace = typeof ctx.get === "function" ? ctx.get("uiWorkspace") : null;
      if (workspace && typeof workspace.openSession === "function") {
        workspace.openSession(sessionId);
        return true;
      }
      return false;
    }

    /** 每次请求一个不重复的 id：重试不会在宿主侧重复分叉。 */
    function newRequestId(seed) {
      return seed + "-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 8);
    }

    /** 冲突清单：宿主可能放在顶层，也可能放在 files 里。 */
    function conflictsOf(value) {
      if (Array.isArray(value && value.conflicts)) return value.conflicts;
      var files = value && value.files;
      if (files && Array.isArray(files.conflicts)) return files.conflicts;
      return [];
    }

    /**
     * 真的执行一次撤回：调用宿主、按结果打开子会话、把文本放回输入框。
     *
     * @param {object} ctx - 客户端上下文。
     * @param {object} spec - {sessionId, seq, includeFiles, text, replaceDraft, sendNow}。
     * @returns {Promise<object>} 宿主返回值。
     */
    async function runRewind(ctx, spec) {
      var value = await callTurnRewind(ctx, "rewind", {
        request: {
          sessionId: spec.sessionId,
          requestId: newRequestId("rewind"),
          seq: spec.seq,
          includeFiles: spec.includeFiles === true,
          force: false,
        },
      });
      if (value && value.status === "conflict") {
        var list = conflictsOf(value).slice(0, 4);
        throw new Error(
          "这些文件在这一轮之后被改过，没有覆盖：" + (list.length ? list.join("、") : "见宿主记录") + "；可以取消\"回滚文件\"后重试",
        );
      }
      var childId = value && value.childSessionId;
      if (!childId) throw new Error("宿主没有返回新的会话");
      var carried = spec.text ? spec.text : "";
      if (carried) {
        // "保留草稿"保留的是人刚才所在那个会话里的草稿：分叉是新会话，草稿不会
        // 自己跟过去，必须由这里搬。只读分叉自己的草稿会把原文当唯一内容，
        // 人就以为草稿被吞了。
        var base = spec.replaceDraft === false ? readDraft(spec.sessionId) : "";
        if (base.trim() === "" && spec.replaceDraft === false) base = readDraft(childId);
        var next = spec.replaceDraft === false && base.trim() !== "" ? base.trimEnd() + "\n\n" + carried : carried;
        publishDraft(childId, next, spec.sendNow === true);
      } else if (spec.sendNow === true) {
        publishDraft(childId, readDraft(childId), true);
      }
      openSession(ctx, childId);
      return value;
    }

    /**
     * 所有动作的实现。
     *
     * 组件只调用这里的方法；弹窗、通知、跳转都在这一层，所以两个渲染器
     * 的状态机只有一份。
     *
     * @param {object} ctx - 客户端上下文。
     * @returns {object} 动作集合。
     */
    function createActions(ctx) {
      return {
        /** 复制一段文本，成功后给一次回执。 */
        copy: async function (text) {
          var ok = await copyText(text);
          notify(ok ? "已复制" : "复制失败，请手动选择文本", !ok);
          return ok;
        },

        /**
         * 撤回某条消息及其之后的内容。
         *
         * @param {string} sessionId - 会话标识。
         * @param {number} seq - 该消息的 durable seq。
         * @param {object} opts - {title, confirmLabel, failPrefix, draft}。
         */
        rewind: async function (sessionId, seq, opts) {
          var turns = await readTurns(ctx, sessionId);
          var located = locateTurn(turns, seq);
          var plan = planFrom(turns, located.turn);
          openConfirm({
            title: opts.title,
            confirmLabel: opts.confirmLabel,
            failPrefix: opts.failPrefix,
            plan: plan,
            hasDraft: readDraft(sessionId).trim() !== "",
            run: async function (includeFiles, replaceDraft) {
              await runRewind(ctx, {
                sessionId: sessionId,
                seq: seq,
                includeFiles: includeFiles,
                text: opts.draft,
                replaceDraft: replaceDraft,
                sendNow: false,
              });
              notify(
                includeFiles
                  ? "已撤回，并把这一轮的文件改动还原了"
                  : "已撤回对话文本",
              );
            },
          });
        },

        /**
         * 进入编辑：先按同一套选择撤回，再把原文放进输入框。
         *
         * 撤回后输入框里就是可编辑的原文，改完按发送即从这一轮继续；
         * 原来的草稿被暂存，取消编辑时放回去。
         */
        beginEdit: async function (sessionId, messageId, seq, text) {
          var turns = await readTurns(ctx, sessionId);
          var located = locateTurn(turns, seq);
          var plan = planFrom(turns, located.turn);
          openConfirm({
            title: "编辑这条消息？",
            confirmLabel: "开始编辑",
            failPrefix: "编辑失败",
            plan: plan,
            hasDraft: readDraft(sessionId).trim() !== "",
            run: async function (includeFiles, replaceDraft) {
              // 编辑前的草稿先留一份：取消编辑要原样还回去，与"替换还是接在后面"无关。
              var stashed = readDraft(sessionId);
              var value = await runRewind(ctx, {
                sessionId: sessionId,
                seq: seq,
                includeFiles: includeFiles,
                text: text,
                replaceDraft: replaceDraft,
                sendNow: false,
              });
              // 撤回会把人带到分叉出来的新会话；编辑态必须挂在眼前那个会话上，
              // 否则横条留在已经不在屏幕上的旧会话里，取消也还错地方。
              var childId = (value && value.childSessionId) || sessionId;
              if (childId !== sessionId) edits.delete(sessionId);
              edits.set(childId, {
                messageId: messageId,
                seq: seq,
                stashed: stashed,
              });
              editStore.set(editStore.get() + 1);
              notify("原文已放回输入框，改完发送就从这里继续");
            },
          });
        },

        /** 当前会话是否处在编辑态。 */
        editingOf: function (sessionId) {
          return edits.get(sessionId) || null;
        },

        /** 取消编辑：把暂存的草稿放回输入框。 */
        cancelEdit: function (sessionId) {
          var state = edits.get(sessionId);
          if (!state) return;
          edits.delete(sessionId);
          editStore.set(editStore.get() + 1);
          publishDraft(sessionId, state.stashed || "", false);
          notify("已退出编辑，原来的草稿放回输入框");
        },

        /**
         * 重新输出：回滚这一轮的文件改动，然后重发原来的问题。
         *
         * 默认回滚文件 —— 重新输出必须从干净的起点开始，否则模型看到的
         * 会是这一轮自己改过的文件。
         */
        regenerate: async function (sessionId, messageId, snapshot) {
          if (!snapshot || !snapshot.nodes) {
            notify("还读不到这一轮的记录，请稍后再试", true);
            return;
          }
          var turns = await readTurns(ctx, sessionId);
          var seq = seqOfAnswer(snapshot, messageId);
          var located = locateTurn(turns, seq === undefined ? -1 : seq);
          if (typeof located.startSeq !== "number") {
            notify("这一轮没有留下回合记录，无法重新输出", true);
            return;
          }
          var prompt = promptOfTurn(snapshot, located.turn, seq);
          if (!prompt) {
            notify("找不到这一轮的问题原文，无法重新输出", true);
            return;
          }
          var plan = planFrom(turns, located.turn);
          openConfirm({
            title: "重新输出这一轮？",
            confirmLabel: "重新输出",
            failPrefix: "重新输出失败",
            plan: plan,
            hasDraft: readDraft(sessionId).trim() !== "",
            run: async function (includeFiles, replaceDraft) {
              await runRewind(ctx, {
                sessionId: sessionId,
                seq: located.startSeq,
                includeFiles: includeFiles,
                text: prompt,
                replaceDraft: replaceDraft,
                sendNow: true,
              });
              notify("已回到这一轮的起点并重新发送");
            },
          });
        },

        /** 分享一条回答：正文 + 它对应的问题，交到系统分享或下载。 */
        share: function (snapshot, messageId) {
          if (!snapshot || !snapshot.nodes) {
            notify("还读不到这条回答，请稍后再试", true);
            return;
          }
          var answer = assistantTextOf(snapshot, messageId);
          if (!answer) {
            notify("这条回答还没有正文可以分享", true);
            return;
          }
          var seq = seqOfAnswer(snapshot, messageId);
          var turn = assistantTurnOf(snapshot, messageId);
          var prompt = promptOfTurn(snapshot, turn, seq);
          var text = (prompt ? "问：" + prompt + "\n\n" : "") + "答：" + answer;
          openShare({ title: "分享这条回答", text: text });
        },
      };
    }

    /** 把一段 CSS 挂到文档头上，返回卸载函数。 */
    function injectCss() {
      var style = document.createElement("style");
      style.setAttribute("data-dsh-turn-rewind", "");
      style.textContent = CSS;
      document.head.appendChild(style);
      return function () {
        style.remove();
      };
    }

    /**
     * 客户端插件体。
     *
     * @param {object} ctx - 客户端 Cordis 上下文。
     * @returns {void}
     */
    function apply(ctx) {
      actionsRef = createActions(ctx);

      ctx.effect(function () {
        return injectCss();
      }, "turn-rewind: styles");

      // 用户行：复用 keyed 槽的 user 键，拿到真实 seq 后在同一位置补动作。
      //
      // 两处必须遵守的组合约束（都来自引擎的注册期校验，违反会直接抛错）：
      // 1. priority 必须比官方那一格更低（更小）：keyed 槽同一 key 上官方占着
      //    priority 0，同优先级会被拒绝（"already has an entry for key user"），
      //    契约是"最低 priority 者渲染"，所以这里 -10。
      // 2. **不能**再声明 children 里的 'conversation.chat.user.before'：官方
      //    user 渲染器已经声明过它，第二份声明会被判 "slot ... is already
      //    declared"。不声明就拿不到 renderSlotChain，那一小段来源装饰由官方
      //    自己那层负责 —— 我们只替换"这一行由谁画"，不接管它的子槽。
      ctx.slots.inject("conversation.chat.node", function () {
        return ctx.slots.register(
          {
            name: "conversation.chat.node",
            key: "user",
            priority: -10,
          },
          UserRow,
        );
      });

      // 回合结束提示：替换官方 `turn-error` 那一格。
      //
      // 官方渲染器（client-ui-chat `TurnErrorNodeView`）是一块常驻面板；
      // 契约写明 keyed 槽"重复注册同一个 key 会替换 occupant"，且最低
      // priority 者渲染（官方为 0，这里 -10）。只换"这一格由谁画"，节点数据、
      // 脱敏（event-projection `displayFailure` 对 AUTH 只留 code）与分组都由
      // 官方投影提供，本插件不重算失败分类。
      ctx.slots.inject("conversation.chat.node", function () {
        return ctx.slots.register(
          { name: "conversation.chat.node", key: "turn-error", priority: -10 },
          TurnNoticeRow,
        );
      });

      // 助手行：新增 id，不替换官方的 feedback 与 copy。
      ctx.slots.inject("conversation.chat.assistant-actions", function () {
        return ctx.slots.register(
          { name: "conversation.chat.assistant-actions", id: "rewind-share", order: 20 },
          AssistantRow,
        );
      });

      // 输入区：接管"打开后立刻发送/预填"的文字。
      ctx.slots.inject("conversation.input.overlay", function () {
        return ctx.slots.register(
          { name: "conversation.input.overlay", id: "rewind-draft-bridge", order: 30 },
          DraftBridge,
        );
      });

      // 提示与弹层：挂在 body 上，不受聊天滚动容器裁剪（弹窗 portal 也从这里出）。
      var host = document.createElement("div");
      host.setAttribute("data-dsh-turn-rewind", "");
      document.body.appendChild(host);
      var root = ReactDOMClient.createRoot(host);
      root.render(h(Layers, null));
      ctx.effect(function () {
        return function () {
          root.unmount();
          host.remove();
        };
      }, "turn-rewind: layers");

      /** 给端到端验收用的诊断入口，不参与业务。 */
      window.__dshTurnRewind = {
        editing: function (sessionId) {
          return actionsRef.editingOf(sessionId);
        },
        pending: function (sessionId) {
          return pendingSends.get(sessionId) || null;
        },
      };
    }

    exports.apply = apply;
    exports.inject = inject;
    exports.name = name;

    /**
     * 供离线测试用的内部视图。
     *
     * 加载器只读 apply/inject/name，多挂一个属性没有副作用；而撤回的回合
     * 算术、草稿回填、冲突处理这些真正容易错的地方需要一个能直接调用的
     * 入口，才能在不启动完整 WebUI 的前提下写成可回归的用例。
     */
    exports.__test = {
      locateTurn: locateTurn,
      planFrom: planFrom,
      describe: describe,
      joinedText: joinedText,
      promptOfTurn: promptOfTurn,
      seqOfAnswer: seqOfAnswer,
      assistantTurnOf: assistantTurnOf,
      assistantTextOf: assistantTextOf,
      readTurns: readTurns,
      callTurnRewind: callTurnRewind,
      /** 当前挂着的确认框/分享框：测试需要真正"按下确认"。 */
      overlay: function () {
        return overlays.get();
      },
      runRewind: runRewind,
      /** 失败码 → 可读文案：界面不许把 kebab 错误码丢给用户。 */
      readableError: readableError,
      createActions: createActions,
      /** 输入区那层：编辑横条与草稿桥都挂在它上面。 */
      DraftBridge: DraftBridge,
      /** 分享框：系统分享按钮在应用内必须走原生桥，不能是死按钮。 */
      ShareDialog: ShareDialog,
      /** apply() 里那组动作：组件点下去走的就是它。 */
      actions: function () {
        return actionsRef;
      },
      /** 某会话的编辑态：给用例核对"编辑挂在哪个会话上"。 */
      editingOf: function (sessionId) {
        return edits.get(sessionId) || null;
      },
      seedDraft: seedDraft,
      readDraft: readDraft,
      pendingFor: function (sessionId) {
        return {
          send: pendingSends.get(sessionId),
          draft: pendingDrafts.get(sessionId),
        };
      },
      resetPending: function () {
        pendingSends.clear();
        pendingDrafts.clear();
      },
      /** 注入到页面的那段样式：用来守住"横条不再回流进零高锚点"这类回归。 */
      css: function () {
        return CSS;
      },
      /** 回合结束提示：官方 turn-error 节点 → 单行摘要/展开详情的折法。 */
      TurnNoticeRow: TurnNoticeRow,
      noticeView: noticeView,
      describeTurnError: describeTurnError,
      turnSummary: turnSummary,
    };

    return module.exports;
  },
});

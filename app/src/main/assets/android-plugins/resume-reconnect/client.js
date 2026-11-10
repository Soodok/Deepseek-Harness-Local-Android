/**
 * [dsh-android] 回到前台就主动重连（v1.6.20）。
 *
 * ## 现场
 *
 * 用户报告：软件在后台放久了再切回来，WebUI 里所有进度都停住、消息发不出去；
 * 但去列表点一下「恢复连接」就立刻正常。而且**那条"没发出去"的消息其实已经
 * 发出去了** —— 服务端早就收到了。
 *
 * ## 根因
 *
 * Android 把后台进程冻结（cgroup `frozen`）时，**整个进程连同子进程一起挂起**，
 * 包括 WebView 的 JS 线程和引擎的 node 进程。冻结期间内核仍然保持着那条 TCP，
 * 但没有任何一方去读写它，于是连接进入**半开（half-open）**状态：服务端认为
 * 还连着，客户端也认为还连着，但两边谁都不会再收到对方的数据。
 *
 * 解冻后：
 *   · 进度不更新 —— 服务端推过来的增量都丢了；
 *   · 发消息发不出去 —— 走 WebSocket 的那条路静默失败；
 *   · 但消息**发出去了** —— 前端发消息有一条 HTTP 兜底路径，那条路真的通。
 *
 * ## 为什么前端自己不会恢复
 *
 * 引擎的 `@deepseek-ai/dsh-client-connection` 有完整重连机制：指数退避
 * （`backoffBaseMs=500`、`backoffFactor=2`、`backoffMaxMs=10000`）、
 * `manual reconnect`、以及 `watchBrowserNetwork()` 监听 `online`/`offline`。
 *
 * 但它**只监听网络状态变化**，没有任何 `visibilitychange` / `pageshow` 处理
 * （实测 `client.browser.js` 60601 字节里 `visibilitychange` 出现 **0 次**）。
 * 而解冻时网络没有变化 —— 网络一直是通的，断的是那条半开连接 —— 所以
 * `online` 事件不会触发。更糟的是：**半开时 `WebSocket.readyState` 仍是 OPEN**，
 * 所以连退避都不会开始，它会一直"以为自己好着"。
 *
 * ## ★ 判据：为什么只能靠「后台待了多久」，不能靠探活
 *
 * 第一版我用 `HEAD /` 探活判断「服务端还活着吗」，只在它不响应时重连。
 * **这是错的，而且实测当场就否证了**（`lastReconnectAt` 始终为 0，
 * generationId 恒为 1，即从未重连）：
 *
 *   · 解冻后引擎也一起活了，`HEAD /` 必然 200；
 *   · 半开连接下 `state` 仍是 `connected`；
 *   · 于是「服务端活着 + state 是 connected」→ 判定健康 → 永远不重连。
 *
 * 探活对**这个**故障毫无鉴别力：它测的是「服务端是否在跑」，而出问题的是
 * 「这条 socket 是否还有效」—— 服务端好好的，socket 死了。想真正测 socket
 * 得往里写数据（会污染业务），或者等引擎自己超时（10 秒起步），都不合适。
 *
 * 所以改成按**后台停留时长**分档：
 *
 *   · < `FORCE_AFTER_MS`（默认 10 秒）：用户只是切出去看一眼通知就回来，
 *     连接极可能仍有效。仍用探活兜一层（探活失败也重连），但不强制。
 *   · ≥ `FORCE_AFTER_MS`：系统大概率已冻结过进程，连接不可信 ——
 *     **不探活，直接重连**。代价是页面重新拉一次快照（会话列表、插件列表…），
 *     与用户手动点「恢复连接」完全相同，而那次点击是被验证有效的。
 *
 * 10 秒的依据：Android 的 cached→frozen 通常发生在后台数十秒后，但厂商差异大；
 * 取 10 秒偏保守（宁可多重连一次，也不要用户卡住）。
 *
 * ## 为什么不在前端改，而是在 App 侧做主叫醒
 *
 * `dsh-client-connection` 属于上游引擎，改它会被升级覆盖。而且「后台待了多久」
 * 只有壳（App）知道得最准 —— `onPause`/`onResume` 是系统直接通知的，
 * 网页侧的 `visibilitychange` 在冻结场景下可能根本不投递。
 *
 * 所以两边配合：
 *   · 这个插件把「检查并重连」暴露成 `window.__dshResume(elapsedMs)`；
 *   · App 侧（`MainActivity.onResume`）把 `onPause`→`onResume` 的毫秒数传进来。
 *   · 插件自己也监听 `visibilitychange` / `pageshow`，覆盖浏览器里切标签页的情形。
 *
 * 两处都调是**幂等**的：`ConnectionController.reconnect()` 第一句是
 * `if (!this.running) return`，内部 `abort(MANUAL_RECONNECT)` 打断当前退避
 * 或连接并把 `attempt` 归零；`MIN_INTERVAL_MS` 再把几乎同时到达的第二次吃掉。
 *
 * @module dsh-android-resume-reconnect
 */

window.__ModuleLoader__.load({
  id: "dsh-android-resume-reconnect",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    /** 必需服务（cordis fiber inject）：只依赖 connection。 */
    var inject = ["connection"];

    /** 插件名：报错与调试日志里用来指认来源。 */
    var name = "dsh-android-resume-reconnect";

    /** 探活超时：太久会让用户觉得切回来没反应。 */
    var PROBE_TIMEOUT_MS = 2500;

    /**
     * 两次重连之间的最小间隔。
     *
     * `MainActivity.onResume` 与网页 `visibilitychange` 会几乎同时到达，
     * 这个窗口把重复的那次吃掉，不改变最终结果。
     */
    var MIN_INTERVAL_MS = 1500;

    /**
     * 后台停留超过这个时长就**强制重连，不做探活**。
     *
     * 见文件头「判据」一节：探活只能证明服务端在跑，证明不了这条 socket 还活着。
     * 短时切回靠探活兜底，长时切回直接换连接。
     */
    var FORCE_AFTER_MS = 10000;

    /** 最近一次变为不可见的时刻（`Date.now()`；0 表示没记录）。 */
    var hiddenAt = 0;

    /**
     * 探活：确认服务端是否还应答。
     *
     * 用 `HEAD /` 而不是 `GET /`：只要状态码，不要几百 KB 的 HTML。
     * `redirect: "manual"` 让它停在引擎回的 303 上，省一次往返。
     *
     * ⚠️ **它测不出半开连接** —— 解冻后服务端是活的。这里只用于
     * 「短时切回」这个分支，失败就重连，成功就放过。
     *
     * @returns {Promise<boolean>} 服务端是否应答了（<500 都算活着）。
     */
    function serverAlive() {
      var ac = new AbortController();
      var timer = setTimeout(function () {
        ac.abort();
      }, PROBE_TIMEOUT_MS);
      return fetch("/", {
        method: "HEAD",
        redirect: "manual",
        cache: "no-store",
        signal: ac.signal,
      })
        .then(function (res) {
          clearTimeout(timer);
          return res.status < 500;
        })
        .catch(function () {
          clearTimeout(timer);
          return false;
        });
    }

    /**
     * 浏览器端插件体。
     *
     * @param {object} ctx - 客户端 Cordis 上下文。
     * @returns {void}
     */
    function apply(ctx) {
      var connection = ctx.connection;

      /** 上次真正发起重连的时刻（`Date.now()`）。 */
      var lastReconnectAt = 0;

      /** 正在探活，避免并发探活堆叠。 */
      var probing = false;

      /** 最近一次决策的来源与结果，供诊断接口读。 */
      var lastDecision = null;

      /**
       * 检查并（必要时）重连。幂等，可被任意次数调用。
       *
       * @param {string} why - 触发来源，只用于调试日志。
       * @param {boolean} force - 为真时跳过探活直接重连（后台停留过久）。
       * @returns {Promise<void>}
       */
      function checkAndReconnect(why, force) {
        if (probing) return Promise.resolve();
        if (Date.now() - lastReconnectAt < MIN_INTERVAL_MS) return Promise.resolve();
        // state 为空表示宿主还没开始连接（boot 早期），轮不到我们插手。
        if (connection.state.getSnapshot() === undefined) return Promise.resolve();

        function doReconnect(reason) {
          lastReconnectAt = Date.now();
          lastDecision = { why: why, reason: reason, at: lastReconnectAt };
          console.log("[resume-reconnect] reconnecting (" + why + ": " + reason + ")");
          connection.reconnect();
        }

        if (force) {
          // 长时后台：连接不可信，直接换（等价于用户手动点「恢复连接」）。
          doReconnect("forced");
          return Promise.resolve();
        }

        probing = true;
        return serverAlive()
          .then(function (alive) {
            // 短时切回且服务端活着 → 连接可能仍然有效，不打扰。
            // （探活对半开无鉴别力，所以这个分支只覆盖「切出去一下」的情形。）
            if (alive && connection.state.getSnapshot() === "connected") {
              lastDecision = { why: why, reason: "healthy-skip", at: Date.now() };
              return;
            }
            doReconnect(alive ? "state-not-connected" : "probe-failed");
          })
          .finally(function () {
            probing = false;
          });
      }

      /**
       * 暴露给 App 壳：Android 冻结会吞掉网页事件，只能由壳来叫醒。
       *
       * @param {number} elapsedMs - `onPause` 到 `onResume` 的毫秒数。
       *   壳知道得最准（系统直接通知）；缺省按「不知道」处理，即强制重连。
       */
      window.__dshResume = function (elapsedMs) {
        var force = typeof elapsedMs !== "number" || elapsedMs >= FORCE_AFTER_MS;
        void checkAndReconnect("host-resume", force);
      };

      /**
       * 诊断快照 —— 给端到端测试读，不参与业务逻辑。
       *
       * `generation.id` 是引擎在**每次连接成功**时自增的计数
       * （`client.browser.js` 里 `id: ++generationId`），是判断「到底有没有真的
       * 重连过」唯一可靠的量：`state` 在半开连接下本来就是骗人的。
       */
      window.__dshResumeDiag = function () {
        var gen = connection.generation.getSnapshot();
        return {
          state: connection.state.getSnapshot() || null,
          generationId: gen && typeof gen.id === "number" ? gen.id : null,
          lastReconnectAt: lastReconnectAt,
          lastDecision: lastDecision,
          probing: probing,
          hiddenAt: hiddenAt,
        };
      };

      /** 浏览器里正常切标签页的情形。 */
      function onVisibility() {
        if (document.visibilityState === "hidden") {
          hiddenAt = Date.now();
          return;
        }
        if (document.visibilityState !== "visible") return;
        var elapsed = hiddenAt ? Date.now() - hiddenAt : Number.MAX_SAFE_INTEGER;
        hiddenAt = 0;
        void checkAndReconnect("visible", elapsed >= FORCE_AFTER_MS);
      }

      /** bfcache 恢复：页面从往返缓存里取回来时，其他事件都不会再来一遍。 */
      function onPageShow(e) {
        // bfcache 恢复意味着页面被挂起过一段未知时长，按最保守处理。
        if (e && e.persisted) void checkAndReconnect("pageshow", true);
      }

      document.addEventListener("visibilitychange", onVisibility);
      window.addEventListener("pageshow", onPageShow);

      ctx.effect(function () {
        return function () {
          document.removeEventListener("visibilitychange", onVisibility);
          window.removeEventListener("pageshow", onPageShow);
          if (window.__dshResume) delete window.__dshResume;
          if (window.__dshResumeDiag) delete window.__dshResumeDiag;
        };
      });
    }

    exports.apply = apply;
    exports.inject = inject;
    exports.name = name;
    return module.exports;
  },
});

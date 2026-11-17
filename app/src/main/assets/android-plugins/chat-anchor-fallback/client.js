/**
 * [dsh-android] 锚定浮层的定位兜底（issue #119）。
 *
 * ## 现场
 *
 * Android 上每条消息操作行最后那个「用量」图标点了没反应：能点（无障碍树
 * clickable=true、ACTION_CLICK 返回 ok），但屏幕上什么都不出现。桌面版同一个
 * 按钮正常弹出「本轮用量」小卡片，所以是**平台特定**。
 *
 * ## 根因
 *
 * 引擎 0.2.2-rc.3 把浮层定位从「JS 量测 + 内联坐标」改成了**纯 CSS 锚点定位**：
 *
 *   · trigger 外层 span 带 `style="anchor-name: --dsh-stat-xxx"`；
 *   · 面板带 `style="position-anchor: --dsh-stat-xxx"`；
 *   · CSS 里写 `top:max(12px, var(--dsh-frame-overlay-top,12px));`
 *     `bottom:max(12px, calc(anchor(top) + 8px));` `left:max(12px, anchor(left));`
 *     `position-area:none;` `position-try-fallbacks:...`。
 *   （源码依据：@deepseek-ai/dsh-client-ui-chat 的 stat-dialog.module.css +
 *    `useStatDialog()`，见 rc.3 的 client.js。）
 *
 * **CSS anchor positioning 是 Chrome 125+ 才有的功能**。Android 的 WebView 普遍
 * 落后：issue #107 那位用户的 `com.huawei.webview` 是 **114.0.5.302**，不少机型
 * 更低。老内核里 `anchor()` / `position-area` / `position-try-fallbacks` /
 * `position-anchor` 全部无效 —— 面板拿不到任何**相对 trigger** 的坐标，只剩
 * 一条 `position:fixed`，于是它要么贴在视口角落、要么被祖先的层叠/裁剪吃掉，
 * 用户看到的就是「点了没反应」。rc.1 用的是 JS 量测（`useAnchoredPosition`），
 * 所以这是一处**升级带来的老内核回归**。
 *
 * ## 为什么修在这里，而不是改上游前端
 *
 * 上游 bundle 由引擎版本决定，改它下次升级必被覆盖；而且这是**老内核兼容**
 * 问题，正确做法是能力检测后补兜底，而不是改掉新内核的行为。
 *
 * 所以这个插件只在 `CSS.supports('position-anchor: --x') === false` 时启用：
 * 监听 body 新增的锚定浮层，用 trigger 的 `getBoundingClientRect()` 算一个
 * 视口内、贴着 trigger 的坐标，写进**内联 !important**（内联 !important 在
 * 层叠里高于任何作者样式表的 !important，因此也能压过 mobile-layout 插件
 * 给「统计胶囊详情」写的固定定位规则 —— 那套规则按文本关键词匹配，
 * 会把每轮用量面板一起命中）。新内核上这个插件什么都不做。
 *
 * ## 判据
 *
 *   · 识别浮层：body 直属、`[role="dialog"]`、非 `aria-modal="true"`（模态框
 *     自己居中，不靠锚点）；且此刻存在 `[aria-haspopup="dialog"][aria-expanded="true"]`
 *     的 trigger。没有 trigger 就不动 —— 宁可不动，也不要乱摆别人的浮层。
 *   · 位置：优先放在 trigger **上方**（消息在页面底部，上方空间大），放不下
 *     就放下方；水平跟着 trigger 左缘，越界就往里收；宽高都夹在视口内。
 *   · 生命周期：浮层出现即摆一次，开着期间滚动/resize 重摆；trigger 收起
 *     （aria-expanded 变 false）或浮层移除时停止。
 *
 * @module dsh-android-chat-anchor-fallback
 */

window.__ModuleLoader__.load({
  id: "dsh-android-chat-anchor-fallback",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    /** 只用 DOM，不依赖引擎服务；空数组也必须显式声明（cordis 契约）。 */
    var inject = [];

    var name = "dsh-android-chat-anchor-fallback";

    /** 视口边距，与上游 PANEL_MARGIN 一致。 */
    var MARGIN = 12;

    /** 浮层与 trigger 之间的间距，与上游 PANEL_GAP 一致。 */
    var GAP = 8;

    /** 兜底浮层的层叠高度：高于上游 1100，低于 toast 的极端值。 */
    var Z_INDEX = 1200;

    /** 我们摆过的浮层标记，便于诊断与去重。 */
    var MARK = "data-dsh-anchor-fallback";

    /**
     * 当前内核支不支持 CSS 锚点定位。
     *
     * 判据就是那条**实际被用到的**属性：`position-anchor`。只探 `anchor()`
     * 之类的函数没有意义 —— 真正决定上游那套 CSS 能否解析的就是它。
     *
     * @returns {boolean} true = 新内核，上游自己摆得对，本插件不介入。
     */
    function supportsAnchor() {
      try {
        return typeof CSS !== "undefined" && typeof CSS.supports === "function" &&
          CSS.supports("position-anchor: --x");
      } catch (e) {
        return false;
      }
    }

    /**
     * 找到当前展开中的「锚定浮层 trigger」。
     *
     * 只认 `aria-haspopup="dialog"` + `aria-expanded="true"` 这一对：菜单类
     * 按钮（aria-haspopup=menu）与按钮自己的展开态不属于这一族，动了会错位。
     *
     * @returns {Element|null} 展开中的 trigger；没有则 null。
     */
    function openTrigger() {
      var list = document.querySelectorAll('[aria-haspopup="dialog"][aria-expanded="true"]');
      return list.length === 0 ? null : list[list.length - 1];
    }

    /**
     * 判断一个刚出现的节点是不是我们要接管的锚定浮层。
     *
     * @param {Node} node 新插入 body 的节点。
     * @returns {boolean} true = 需要兜底定位。
     */
    function isCandidate(node) {
      if (!node || node.nodeType !== 1) return false;
      if (!node.getAttribute || node.getAttribute("role") !== "dialog") return false;
      // 模态框自己居中（上游用 flex 定位），不依赖锚点，别动它。
      if (node.getAttribute("aria-modal") === "true") return false;
      return true;
    }

    /**
     * 把浮层摆到 trigger 旁边，写内联 !important。
     *
     * @param {Element} panel 浮层节点。
     * @param {Element} trigger 展开中的 trigger。
     */
    function place(panel, trigger) {
      var rect = trigger.getBoundingClientRect();
      var vw = window.innerWidth || document.documentElement.clientWidth || 0;
      var vh = window.innerHeight || document.documentElement.clientHeight || 0;
      if (!(vw > 0 && vh > 0)) return;

      // 宽度先定下来再量高度：max-content 在老内核上宽度会飘，量出来的高度不可信。
      var width = Math.max(0, Math.min(440, vw - MARGIN * 2));
      var style = panel.style;
      style.setProperty("position", "fixed", "important");
      style.setProperty("right", "auto", "important");
      style.setProperty("bottom", "auto", "important");
      style.setProperty("margin", "0", "important");
      style.setProperty("box-sizing", "border-box", "important");
      style.setProperty("width", width + "px", "important");
      style.setProperty("max-width", (vw - MARGIN * 2) + "px", "important");
      style.setProperty("max-height", (vh - MARGIN * 2) + "px", "important");
      style.setProperty("z-index", String(Z_INDEX), "important");

      var height = panel.offsetHeight || 0;
      var spaceAbove = rect.top - GAP - MARGIN;
      var spaceBelow = vh - rect.bottom - GAP - MARGIN;

      var top;
      if (height <= spaceAbove || spaceAbove >= spaceBelow) {
        // 上方放得下（或上方空间更大）：底边贴着 trigger 上沿。
        top = rect.top - GAP - height;
      } else {
        top = rect.bottom + GAP;
      }
      // 夹进视口。放不下时就近对齐，宁可盖住一点也不能跑到屏幕外。
      top = Math.max(MARGIN, Math.min(top, vh - MARGIN - height));

      var left = rect.left;
      left = Math.max(MARGIN, Math.min(left, vw - MARGIN - width));

      style.setProperty("top", Math.round(top) + "px", "important");
      style.setProperty("left", Math.round(left) + "px", "important");
      panel.setAttribute(MARK, "");

      // 开着期间内容可能变高（详情行异步出现），下一帧再校正一次。
      requestFrame(function () {
        if (!panel.isConnected) return;
        var grown = panel.offsetHeight || 0;
        if (grown <= height) return;
        var again = Math.max(MARGIN, Math.min(rect.top - GAP - grown, vh - MARGIN - grown));
        style.setProperty("top", Math.round(again) + "px", "important");
      });
    }

    /** requestAnimationFrame 的兜底（极老内核/后台标签页里可能不存在）。 */
    function requestFrame(fn) {
      if (typeof window.requestAnimationFrame === "function") {
        window.requestAnimationFrame(fn);
        return;
      }
      window.setTimeout(fn, 16);
    }

    /**
     * 安装兜底。返回卸载函数。
     *
     * @returns {Function} 卸载。
     */
    function install() {
      /** 正在跟的浮层与其 trigger。 */
      var current = null;

      /** 重摆当前浮层（滚动 / resize / 内容变化时）。 */
      function reposition() {
        if (!current) return;
        var panel = current.panel;
        var trigger = current.trigger;
        if (!panel.isConnected) {
          stop();
          return;
        }
        if (trigger.getAttribute("aria-expanded") !== "true") {
          stop();
          return;
        }
        place(panel, trigger);
      }

      /** 停止跟踪。 */
      function stop() {
        if (!current) return;
        window.removeEventListener("scroll", reposition, true);
        window.removeEventListener("resize", reposition);
        current = null;
      }

      /** 新浮层出现：接管并摆位。 */
      function adopt(node) {
        if (!isCandidate(node)) return;
        var trigger = openTrigger();
        // 没有展开中的 trigger 说明不是「点了某个按钮弹出来的浮层」，
        // 不动它 —— 乱摆别人的对话框比不摆更糟。
        if (!trigger) return;
        stop();
        current = { panel: node, trigger: trigger };
        place(node, trigger);
        window.addEventListener("scroll", reposition, true);
        window.addEventListener("resize", reposition);
      }

      var observer = new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i += 1) {
          var added = records[i].addedNodes || [];
          for (var j = 0; j < added.length; j += 1) adopt(added[j]);
          var removed = records[i].removedNodes || [];
          for (var k = 0; k < removed.length; k += 1) {
            if (current && removed[k] === current.panel) stop();
          }
        }
      });
      observer.observe(document.body, { childList: true });

      return function dispose() {
        stop();
        observer.disconnect();
      };
    }

    /**
     * 插件入口。
     *
     * @param {Object} ctx cordis 上下文（只用 effect）。
     */
    function apply(ctx) {
      if (supportsAnchor()) {
        // 新内核：上游那套 CSS 锚点定位本来就是对的，一个字都不改。
        if (typeof console !== "undefined") {
          console.info("[android-anchor-fallback] CSS anchor positioning available; fallback idle");
        }
        return;
      }
      if (typeof console !== "undefined") {
        console.info("[android-anchor-fallback] legacy WebView (no position-anchor); anchoring dialogs by script");
      }
      var dispose = install();
      if (ctx && typeof ctx.effect === "function") {
        ctx.effect(function () {
          return dispose;
        });
      }
    }

    exports.apply = apply;
    exports.inject = inject;
    exports.name = name;
    return module.exports;
  },
});

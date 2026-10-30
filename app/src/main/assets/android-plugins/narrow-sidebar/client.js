/**
 * [dsh-android] 窄屏侧栏全宽 —— 浏览器端 bundle（v1.2.175）。
 *
 * ## 症状与目标
 * 手机竖屏（实测 1216x2640，视口约 405px 逻辑宽）下，用户展开侧栏后：
 * 引擎 `dsh-client-ui-layout` 的 `AppFrame` 按 `computeColumns()` 分配三列，
 * 侧栏固定 264~420px，中心对话区被压到 336px（实测截图取证）—— 文字逐字换行。
 * 用户要求：**侧栏全展开 + 隐藏对话流**，横屏时不做任何事。
 *
 * ## 为什么用 CSS 而不是操作 React
 * ① 引擎源码不该改（用户要求，且会被升级覆盖）；
 * ② React 内部状态（`narrowExpanded`、`sidebar` 宽度）是引擎私有的，插件直接改
 *    会很脆（版本一变就崩），且与 `dsh-client-store` 的响应式模型耦合；
 * ③ 真需要变的只有**渲染结果**（列宽/可见性），而这三列都是 AppFrame 网格的直接
 *    子元素 —— 一段作用域限定在 `[data-android-narrow]` 下的 CSS 就能精确表达，
 *    并且天然可回滚（移除属性即恢复）。
 *
 * ## 选择器怎么定位（不依赖哈希类名）
 * 哈希类名（`pI_x6G_sidebarCol`）随构建变化，不能写死。这里改用**结构关系**：
 *   · 网格容器 = 直接子元素里同时含"侧栏列"与"中心列"的那个 `display:grid` 元素；
 *   · 侧栏列 = 含折叠按钮（无障碍标签 `收起侧边栏`/`Collapse sidebar`）的那一列；
 *   · 中心列 = 网格里紧邻侧栏列的下一个元素（引擎 DOM 顺序固定：sidebarCol → centerCol）。
 * 定位后给网格容器加 `data-android-narrow`，样式只在该属性下生效。
 *
 * ## 安全边界（用户要求「做个屏幕判定，看看会不会导致屏幕显示异常」）
 * 同时满足才算「窄屏竖屏」：
 *   ① `innerWidth <= 900`（横屏/桌面模式通常 > 900）；
 *   ② `innerHeight > innerWidth`（必须竖屏）；
 *   ③ 侧栏处于展开态（存在折叠按钮）；
 *   ④ 网格里确实**有**中心列（否则布局已被引擎自己收起，无需接管）。
 * 任一不满足 → 立刻移除属性（恢复引擎原样）。横屏永不动。
 *
 * ## 冲突防护
 * 样式表插在 `<head>` 末尾且每条选择器都带 `[data-android-narrow]` 前缀，
 * 不覆盖引擎规则；卸载时移除属性、样式表与全部观察器，零残留。
 *
 * ## 注册方式（易错点，实测确认）
 * 客户端 bundle 由引擎以**经典 script**（非 ES module）加载，必须调用
 * `window.__ModuleLoader__.load({ id, factory })` 注册，且 `id` 必须严格等于
 * package.json 的 `name`（引擎按包名建索引，见 `dsh-client-modules`
 * 的 `reconcilePackage()`）。因此本文件**不用 ESM 语法**。
 *
 * @module dsh-android-narrow-sidebar/client
 */

window.__ModuleLoader__.load({
  id: "dsh-android-narrow-sidebar",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    /** 视口宽度上限（超过视作横屏/桌面，不接管）。 */
    const MAX_VIEWPORT_PX = 900;

    /** 侧栏展开态的无障碍标签（中文界面 + 英文界面）。 */
    const COLLAPSE_LABELS = ["收起侧边栏", "Collapse sidebar"];

    /** 插件自有的样式表 id（幂等：重复激活只保留一份）。 */
    const STYLE_ID = "dsh-android-narrow-sidebar-style";

    /** 侧栏列的标记属性（由 JS 打在定位到的具体元素上）。 */
    const SIDEBAR_ATTR = "data-android-narrow-sidebar-col";

    /** 让位列的标记属性（中心列 + 右栏）。 */
    const YIELD_ATTR = "data-android-narrow-yield-col";

    /**
     * 注入的样式：让侧栏列横跨全宽、对话列让位。
     *
     * ## 为什么用 `grid-column` 而不是改 `grid-template-columns`
     * 后者由 React 内联样式在每次布局时重写（拖拽/动画），外部覆盖会和引擎的
     * `transition: grid-template-columns` 打架；而 `grid-column: 1 / -1` 让侧栏
     * **自己**跨过全部轨道，轨道定义仍由引擎掌握、过渡动画不受影响。
     *
     * ## 为什么给元素打标记而不是用 `:nth-child`
     * 曾用 `> *:first-child` / `> *:nth-child(2)` 指代侧栏与中心列 —— 那是**猜**
     * 引擎的 DOM 顺序（AppFrame 里还插了 DocumentTitle、overlay、DragHandle 等
     * 兄弟节点，任一版本调整顺序就会误伤）。改为由 `locate()` 定位后把标记打在
     * **确定是那一列**的元素上，语义明确、不受兄弟顺序影响。
     */
    const STYLE_TEXT = [
      "/* 侧栏占满整行：从第一条轨道跨到最后一条 */",
      "[" + SIDEBAR_ATTR + "] { grid-column: 1 / -1; }",
      "/* 对话列让位（隐藏对话流，符合「侧栏全展开」） */",
      "[" + YIELD_ATTR + "] { display: none; }",
    ].join("\n");

    /** 按无障碍标签查找元素（中文/英文界面都覆盖）。 */
    function byLabel(labels) {
      for (var i = 0; i < labels.length; i++) {
        var el =
          document.querySelector('[aria-label="' + labels[i] + '"]') ||
          document.querySelector('[title="' + labels[i] + '"]');
        if (el) return el;
      }
      return null;
    }

    /**
     * 定位网格容器与侧栏/中心列。
     * @returns {{grid: Element, sidebar: Element, center: Element} | null}
     */
    function locate() {
      var toggle = byLabel(COLLAPSE_LABELS);
      if (!toggle) return null; // 侧栏未展开（或页面还没渲染）→ 不该接管
      var column = toggle;
      while (column && column.parentElement) {
        var parent = column.parentElement;
        var style = window.getComputedStyle(parent);
        if (style.display === "grid" || style.display === "inline-grid") {
          var center = column.nextElementSibling;
          if (!center) return null; // 没有中心列 → 引擎已自行收起
          return { grid: parent, sidebar: column, center: center };
        }
        column = parent;
      }
      return null;
    }

    /** 移除样式表（幂等）。 */
    function removeStyle() {
      var old = document.getElementById(STYLE_ID);
      // parentElement 与 parentNode 都试：前者是本插件自己插入的必然存在，
      // 后者兼容极端情况下元素被包进 fragment 的场景。
      var p = old && (old.parentElement || old.parentNode);
      if (p) p.removeChild(old);
    }

    /** 插入样式表（带自身 id，便于卸载）。 */
    function insertStyle() {
      if (document.getElementById(STYLE_ID)) return;
      var style = document.createElement("style");
      style.id = STYLE_ID;
      style.textContent = STYLE_TEXT;
      (document.head || document.documentElement).appendChild(style);
    }

    /**
     * 浏览器端插件体。
     * @param {object} ctx - 客户端 Cordis 上下文。
     * @returns {() => void} 卸载函数。
     */
    function apply(ctx) {
      /** 当前是否处于接管态。 */
      var marked = false;
      /** 被我们标记的侧栏列与让位列（持有引用，撤销是纯内存操作）。 */
      var markedSidebar = null;
      var markedYield = [];
      /** 是否有一次 rAF 回调在等待执行（避免连环回调重复判定）。 */
      var pending = false;
      var cleanups = [];

      /** 当前是否应接管（见文件头「安全边界」）。 */
      function shouldTakeOver(grid) {
        // ⚠️ v1.4.47：社区插件 @xiaopb/dsh-mobile-layout 已内置。它在手机竖屏下
        // 用官方槽位把侧栏改成抽屉、设置页改成全屏（功能是本插件的超集）。
        // 两者同时生效会互相打架（同一个侧栏既被要求全宽又被要求做成抽屉），
        // 所以它接管时本插件让位。它不激活（横屏/桌面/平板）时本插件照旧。
        if (document.documentElement.hasAttribute("data-dshm-mode")) return false;
        var w = window.innerWidth;
        var h = window.innerHeight;
        if (w > MAX_VIEWPORT_PX) return false; // 横屏/桌面：不接管
        if (h <= w) return false; // 不是竖屏：不接管
        return grid.children.length >= 2; // 必须确实还有中心列
      }

      /**
       * 撤销接管：移除元素标记与样式表。
       *
       * 用**持有的元素引用**而不是 `querySelector` 找回来 —— 后者依赖选择器
       * 引擎与文档根的可达性（元素若被引擎卸载/替换就查不到，属性会残留在
       * 旧节点上）。持有引用后撤销是纯内存操作。
       */
      function unmark() {
        if (markedSidebar) {
          markedSidebar.removeAttribute(SIDEBAR_ATTR);
          markedSidebar = null;
        }
        for (var i = 0; i < markedYield.length; i++) {
          markedYield[i].removeAttribute(YIELD_ATTR);
        }
        markedYield = [];
        removeStyle();
        marked = false;
      }

      /**
       * 判断元素是否是「网格里的列」（而不是浮层/拖拽手柄等辅助节点）。
       *
       * 引擎 AppFrame 的直接子元素顺序是：
       *   sidebarCol → (CenterColumn, RightbarColumn 片段) → overlayLayer → leadingSeat → DragHandle
       * 其中只有列才会被 `grid-template-columns` 分配宽度。浮层（overlayLayer）是
       * `position:absolute; inset:0; pointer-events:none`，隐藏它会打断弹窗/提示；
       * DragHandle 是细长热区，也不该动。
       *
       * 判据：**计算样式的 position 是 static/relative**（列是常规流/相对定位），
       * 且不是绝对/固定定位的浮层。这样无需依赖任何类名。
       *
       * @param {Element} el - 候选元素。
       * @returns {boolean} 是否应作为列让位。
       */
      function isColumn(el) {
        var pos = "";
        try {
          pos = window.getComputedStyle(el).position;
        } catch (e) {
          pos = ""; // 取不到样式时保守放行（下面按结构兜底）
        }
        if (pos === "absolute" || pos === "fixed") return false;
        var role = el.getAttribute && el.getAttribute("data-shell-overlay");
        if (role !== null && role !== undefined) return false; // 引擎自标的浮层
        return true;
      }

      /**
       * 应用接管：给侧栏列与让位列打标记，并注入样式。
       * @param {{grid: Element, sidebar: Element, center: Element}} found - 定位结果。
       */
      function mark(found) {
        insertStyle();
        found.sidebar.setAttribute(SIDEBAR_ATTR, "");
        markedSidebar = found.sidebar;
        // 让位的列：中心列及其后的同级**列**（右栏），跳过浮层等辅助节点。
        markedYield = [];
        var el = found.center;
        while (el) {
          if (isColumn(el)) {
            el.setAttribute(YIELD_ATTR, "");
            markedYield.push(el);
          }
          el = el.nextElementSibling;
        }
        marked = true;
        // 用 [dsh-android] 前缀：MainActivity 的 onConsoleMessage 只转发带该标记的
        // 消息（以及 WARNING 以上）到 engine.log，便于真机远程确认插件实际生效。
        console.log(
          "[dsh-android] narrow-sidebar: 已接管（侧栏全宽，隐藏 " +
            markedYield.length + " 个对话列；视口 " +
            window.innerWidth + "x" + window.innerHeight + "）"
        );
      }

      /** 依据当前 DOM 与视口重新判定，应用或撤销。 */
      function refresh() {
        pending = false;
        var found = locate();
        if (!found) {
          if (marked) unmark();
          return;
        }
        var want = shouldTakeOver(found.grid);
        if (want && !marked) {
          mark(found);
        } else if (want && marked && markedSidebar !== found.sidebar) {
          // 引擎重建了列（面板切换/热更新）：把标记迁移到新节点上
          unmark();
          mark(found);
        } else if (!want && marked) {
          unmark();
          console.log(
            "[dsh-android] narrow-sidebar: 已恢复（视口 " +
              window.innerWidth + "x" + window.innerHeight + "）"
          );
        }
      }

      /**
       * 合并高频触发（两个观察器可能连环回调）。
       *
       * ⚠️ pending 必须在调用 rAF **之前**置位：写成 `raf = rAF(refresh)` 时，
       * 若 rAF 同步执行（测试桩 / 某些内嵌场景），refresh 内清掉的标志会被
       * 返回值重新写回，之后所有 schedule 都被 "已在排队" 挡掉，转屏不再重判
       * （实测复现：竖屏转横屏后布局没恢复）。
       */
      function schedule() {
        if (pending) return;
        pending = true;
        window.requestAnimationFrame(refresh);
      }

      /**
       * 带最短间隔的触发（v1.4.0，issue #26）。
       *
       * AI 工作时消息流持续增删节点，`childList` 会被每几毫秒触发一次。虽然本插件的
       * `locate()` 只做两次 `querySelector` + 一次 `getComputedStyle`（比
       * narrow-settings 便宜得多），但乘以每帧、再乘以三个插件，依然是纯浪费 ——
       * 而且这些变化**不可能**改变侧栏的开合状态。
       *
       * 200ms 下限只作用于 DOM 变化引起的触发；resize / orientationchange /
       * 首帧补判仍走 [schedule] 立即执行（转屏必须马上响应）。
       */
      var lastRun = 0;
      var cooldownTimer = null;
      var MIN_INTERVAL_MS = 200;

      function scheduleThrottled() {
        var now = Date.now();
        var wait = MIN_INTERVAL_MS - (now - lastRun);
        if (wait <= 0) {
          lastRun = now;
          schedule();
          return;
        }
        if (cooldownTimer !== null) return;
        cooldownTimer = window.setTimeout(function () {
          cooldownTimer = null;
          lastRun = Date.now();
          schedule();
        }, wait);
      }
      cleanups.push(function () {
        if (cooldownTimer !== null) window.clearTimeout(cooldownTimer);
      });

      // 首帧 + 延迟补判（侧栏可能晚于本插件挂载）
      schedule();
      var timers = [300, 1200, 2600].map(function (ms) {
        return window.setTimeout(schedule, ms);
      });
      cleanups.push(function () {
        timers.forEach(function (t) {
          window.clearTimeout(t);
        });
      });

      // 视口变化（转屏、分屏、软键盘）
      window.addEventListener("resize", schedule);
      cleanups.push(function () {
        window.removeEventListener("resize", schedule);
      });
      window.addEventListener("orientationchange", schedule);
      cleanups.push(function () {
        window.removeEventListener("orientationchange", schedule);
      });

      // DOM 变化（侧栏展开/折叠、面板切换）。
      //
      // ⚠️ 性能边界（v1.3.3，issue #22）：旧版监听 subtree 下的**所有属性**。
      // AI 工作时引擎会持续更新消息/动画节点，任何无关 class/style/data 变化都会
      // 让插件每帧重新 locate + getComputedStyle；三个窄屏插件叠加后，WebView
      // 在后台也会持续吃 CPU。这里只关心侧栏按钮的状态属性；视口变化由
      // resize/orientationchange 覆盖。
      //
      // v1.4.0（issue #26）：保留 subtree（列结构可能嵌在任意深度，收窄会漏判），
      // 但把触发换成 scheduleThrottled —— DOM 变化引起的重判最多 5 次/秒，
      // 而 locate() 本身只有两次 querySelector + 若干 getComputedStyle，代价可控。
      // 关键收益来自「不再每帧都做」，而不是缩小观察范围。
      try {
        var mo = new MutationObserver(scheduleThrottled);
        mo.observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ["aria-label", "title"],
        });
        cleanups.push(function () {
          mo.disconnect();
        });
      } catch (e) {
        /* 观察器不可用时退化为首帧判定，不影响正确性 */
      }

      return function () {
        for (var i = 0; i < cleanups.length; i++) {
          try {
            cleanups[i]();
          } catch (e) {
            /* 卸载阶段忽略单个清理失败 */
          }
        }
        unmark();
      };
    }

    exports.apply = apply;
    return module.exports;
  },
});

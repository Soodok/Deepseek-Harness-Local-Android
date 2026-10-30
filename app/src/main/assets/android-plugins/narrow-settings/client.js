/**
 * [dsh-android] 窄屏设置面板修复 —— 浏览器端 bundle（v1.2.178）。
 *
 * ## 症状（真机截图 + uiautomator 取证）
 * 手机竖屏（1216x2640，视口约 386px 逻辑宽）打开设置面板后布局畸形：
 *   · 面板 `max-width: calc(100vw - 48px)` → 实际宽约 338px；
 *   · 左导航 `.VOzbGW_nav` 固定 `width: 188px; flex: none` → 吃掉一大半；
 *   · 右内容区 `.VOzbGW_content` 只剩约 150px，其中每行的标签列被压到
 *     **47px**（uiautomator 实测 `[743,437][790,563]`），于是
 *     "选择新会话的默认权限模式" 这类标签**逐字竖排**，控件也跟着堆叠。
 *
 * ## 引擎侧缺什么
 * 该页 CSS 只有 `@media (prefers-reduced-motion)` 一条媒体查询，**没有任何
 * 宽度断点** —— 它是按桌面设计的（面板固定 800px、导航固定 188px）。
 *
 * ## 修法（与 narrow-sidebar 同思路：只改渲染结果，不碰引擎源码/React 状态）
 * 窄屏（视口宽度 <= 断点）时给面板打标记 `data-android-narrow-settings`，
 * 由我们自己的样式表把两列改成**竖向堆叠**：
 *   · 导航变成顶部横向滚动的图标条（`.nav` 定宽 188px → auto，行改 row）；
 *   · 内容区拿到整宽，标签不再被压。
 * 宽屏（平板横屏/桌面模式）一律不启用，保持引擎原样。
 *
 * ## 为什么不写死哈希类名
 * `VOzbGW_nav` 这类哈希前缀随构建变化。这里用**结构定位**：
 *   · 面板 = 含"设置"标题与导航列表的那个固定定位浮层；
 *   · 导航列 = 面板内**直接子元素**中宽度最大且含多个可点单元格的那个；
 *   · 内容列 = 面板内另一个直接子元素（flex 兄弟）。
 * 定位成功后把标记打在**具体元素**上，样式以标记属性为前缀，天然作用域隔离。
 *
 * ## 冲突防护 / 可回滚
 * 样式表插在 `<head>` 末尾、选择器全部带本插件属性前缀；卸载或回到宽屏时
 * 移除标记与样式表，引擎恢复原样。持有元素引用做撤销，不依赖选择器反查。
 *
 * ## 注册约定（同 narrow-sidebar，实测确认）
 * 客户端 bundle 由引擎以**经典 script** 加载（非 ES module），必须调用
 * `window.__ModuleLoader__.load({ id, factory })`，且 `id` 严格等于
 * package.json 的 `name`。故本文件不用 ESM 语法。
 *
 * @module dsh-android-narrow-settings/client
 */

window.__ModuleLoader__.load({
  id: "dsh-android-narrow-settings",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    /** 视口宽度上限：超过视为宽屏（平板横屏/桌面模式），不接管。 */
    const MAX_VIEWPORT_PX = 720;

    /** 面板标记属性（打在设置面板根元素上）。 */
    const PANEL_ATTR = "data-android-narrow-settings";

    /** 导航列标记属性。 */
    const NAV_ATTR = "data-android-narrow-settings-nav";

    /** 内容列标记属性。 */
    const CONTENT_ATTR = "data-android-narrow-settings-content";

    /** 样式表 id（幂等）。 */
    const STYLE_ID = "dsh-android-narrow-settings-style";

    /**
     * 注入的样式：面板改为竖向堆叠（导航在顶部横滑，内容在下方）。
     *
     * 只覆盖布局相关的几个轴（宽/高/方向/溢出），不动颜色与间距，避免与其它
     * UI 插件或主题打架。每条选择器都带本插件属性前缀，因此只在**我们主动
     * 标记过**的元素上生效。
     *
     * ⚠️ 绝不隐藏导航列的任何子元素。v1.2.179 曾用
     * `[nav] > :first-child { display: none }` 想隐藏"设置"小标题，结果标记
     * 落到了错误的元素上，把**整条导航栏**隐掉了（真机反馈「看不到选项栏」）。
     * 标题本身就是导航的一部分，保留它更安全。
     */
    const STYLE_TEXT = [
      "/* 面板：占满可用宽度，竖向堆叠 */",
      "[" + PANEL_ATTR + "] {",
      "  width: calc(100vw - 24px) !important;",
      "  max-width: calc(100vw - 24px) !important;",
      "  height: min(760px, calc(100vh - 48px)) !important;",
      "  flex-direction: column !important;",
      "  overflow: hidden !important;",
      "}",
      "/* 导航：从左侧竖列改为顶部横向条（可横滑看全所有项） */",
      "[" + NAV_ATTR + "] {",
      "  box-sizing: border-box !important;",
      "  width: 100% !important;",
      "  max-width: 100% !important;",
      "  height: auto !important;",
      "  flex: none !important;",
      "  flex-direction: row !important;",
      "  align-items: center !important;",
      "  gap: 4px !important;",
      "  padding: 6px 8px 8px !important;",
      "  overflow-x: auto !important;",
      "  overflow-y: hidden !important;",
      "  border-bottom: 0.5px solid var(--dsw-alias-border-l3, rgba(128,128,128,.25)) !important;",
      "}",
      "/* 导航标题：作为横条开头的紧凑标签（不隐藏） */",
      "[" + NAV_ATTR + "] > *:first-child {",
      "  flex: none !important;",
      "  padding: 0 6px 0 2px !important;",
      "  font-size: 12px !important;",
      "  opacity: 0.6 !important;",
      "  white-space: nowrap !important;",
      "}",
      "/* 导航列表：横向排列、可横滑 */",
      "[" + NAV_ATTR + "] > *:last-child {",
      "  display: flex !important;",
      "  flex-direction: row !important;",
      "  gap: 4px !important;",
      "  flex: 1 1 auto !important;",
      "  min-width: 0 !important;",
      "  overflow-x: auto !important;",
      "  overflow-y: hidden !important;",
      "}",
      "/* 每个导航项：不换行、不缩字、不收缩 */",
      "[" + NAV_ATTR + "] button {",
      "  flex: none !important;",
      "  white-space: nowrap !important;",
      "  overflow: visible !important;",
      "  min-width: auto !important;",
      "  padding: 6px 10px !important;",
      "  height: 32px !important;",
      "}",
      "/* 内容列：拿满剩余整宽，禁止横向溢出 */",
      "[" + CONTENT_ATTR + "] {",
      "  width: 100% !important;",
      "  max-width: 100% !important;",
      "  flex: 1 1 auto !important;",
      "  min-width: 0 !important;",
      "  overflow-x: hidden !important;",
      "}",
    ].join("\n");

    /**
     * 元素是否处在「浮层」子树里（自身或任一祖先为 fixed/absolute 定位）。
     *
     * 这是区分**设置面板**与**主界面框架**的关键判据之一：设置面板挂在引擎的
     * `.VOzbGW_overlay`（`position:fixed; inset:0`）之下，而主界面 AppFrame 是
     * 常规文档流里的网格，祖先链上没有定位元素。
     *
     * @param {Element} el - 候选元素。
     * @returns {boolean} 是否在浮层内。
     */
    function isInOverlay(el) {
      var node = el;
      while (node && node !== document.documentElement) {
        var st = null;
        try { st = window.getComputedStyle(node); } catch (e) { st = null; }
        if (st && (st.position === "fixed" || st.position === "absolute")) return true;
        node = node.parentElement;
      }
      return false;
    }

    /**
     * 元素内是否有「关闭」按钮。
     *
     * 设置面板标题栏必带关闭按钮（真机 uiautomator 取证：`content-desc="关闭"`），
     * 主界面框架没有。这是关键判据 —— 只靠「按钮多 + 有大兄弟」会把**主界面**
     * （侧栏里按钮很多、右边是对话区）误判成设置面板，实测把界面改坏成黑屏空白。
     *
     * 四种匹配都试（引擎各版本用过的写法）：
     *   ① `aria-label` 属性；② `title` 属性；③ 可见文本；④ lucide 图标类名。
     *
     * @param {Element} el - 候选元素。
     * @returns {boolean} 是否含关闭按钮。
     */
    function hasCloseButton(el) {
      var labels = ["关闭", "Close"];
      for (var i = 0; i < labels.length; i++) {
        var L = labels[i];
        if (el.querySelector('[aria-label="' + L + '"]')) return true;
        if (el.querySelector('[title="' + L + '"]')) return true;
      }
      // 文本或图标类名（大小写不敏感）：「关闭」/ close / x
      var btns = el.querySelectorAll("button");
      for (var j = 0; j < btns.length; j++) {
        var txt = (btns[j].textContent || "").trim();
        if (txt === "关闭" || txt.toLowerCase() === "close") return true;
        var cls = btns[j].getAttribute("class") || "";
        if (/lucide-x|icon-close|close-icon/i.test(cls)) return true;
        if (btns[j].querySelector && btns[j].querySelector(".lucide-x")) return true;
      }
      return false;
    }

    /**
     * 找设置面板根元素。
     *
     * ## 引擎真实结构（拉取 `dsh-client-ui-settings-general/lib/client.js` 取证）
     * ```
     * <div class="overlay(VOzbGW_overlay)">        position:fixed; z-index:1000
     *   <div class="mask(VOzbGW_mask)" aria-hidden> 遮罩，点击关闭
     *   <div class="panel(VOzbGW_panel)" role="dialog" aria-modal="true"
     *        data-shortcut-modal="settings">        ← 这就是我们要改的面板
     *     <nav class="nav(VOzbGW_nav)">              ← **语义标签 nav**，宽 188px
     *       <div class="navTitle">设置</div>
     *       <div class="navList">…按钮…</div>
     *     </nav>
     *     <div class="content(VOzbGW_content)">…    ← 内容列
     *   </div>
     * </div>
     * ```
     * 面板带 `role="dialog"` + `aria-modal="true"` —— 这是**最可靠的判据**
     * （引擎自标的无障碍语义，不随类名/版本变化）；导航是 `<nav>` 语义标签。
     *
     * ## 判据（优先级从高到低）
     * ① `[role="dialog"][aria-modal="true"]` 且内有 `<nav>` 与 ≥3 个按钮
     *    → 直接命中（引擎当前版本走这条）；
     * ② 退路：含"关闭"按钮 + 在浮层内 + 有 `<nav>` 子元素
     *    （兼容未来把 role 改掉的情况）。
     *
     * ⚠️ 曾经的误判：只看「按钮多 + 有大兄弟 + 规模可观」时，**主界面 AppFrame**
     * （侧栏一堆按钮 + 大对话区）完全命中，被改成竖向堆叠 → 界面黑屏（真机事故）。
     * 现在 ①② 都要求 `<nav>` 语义标签，主界面侧栏用的是 div，天然被排除。
     *
     * @returns {{panel: Element, nav: Element, content: Element} | null}
     */
    function findPanel() {
      // ① 首选：引擎自标的对话框语义
      var dialogs = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
      for (var i = 0; i < dialogs.length; i++) {
        var hit = structureOf(dialogs[i]);
        if (hit) return hit;
      }
      // ② 退路：浮层内、含关闭按钮、有 nav 语义标签
      var candidates = document.querySelectorAll("div, section, aside");
      for (var j = 0; j < candidates.length; j++) {
        var el = candidates[j];
        if (!isInOverlay(el)) continue;
        if (!hasCloseButton(el)) continue;
        var fallback = structureOf(el);
        if (fallback) return fallback;
      }
      return null;
    }

    /**
     * 从候选元素里提取「导航列 + 内容列」结构。
     *
     * 导航列优先取 `<nav>` 语义标签（引擎实测），退路取「含最多按钮的直接子元素」。
     * 内容列取另一个直接子元素里面积最大者。
     *
     * @param {Element} el - 候选面板元素。
     * @returns {{panel: Element, nav: Element, content: Element} | null}
     */
    function structureOf(el) {
      var kids = [];
      for (var c = 0; c < el.children.length; c++) kids.push(el.children[c]);
      if (kids.length < 2) return null;

      // 导航列：优先 <nav>（引擎语义标签）
      var nav = null;
      for (var n = 0; n < kids.length; n++) {
        if (kids[n].tagName === "NAV") { nav = kids[n]; break; }
      }
      if (!nav) {
        // 退路：含最多按钮的直接子元素
        var best = 0;
        for (var b = 0; b < kids.length; b++) {
          var cnt = kids[b].querySelectorAll("button").length;
          if (cnt > best) { best = cnt; nav = kids[b]; }
        }
        if (best < 3) nav = null;
      }
      if (!nav) return null;
      if (nav.querySelectorAll("button").length < 3) return null;

      // 内容列：除导航列外面积最大的直接子元素
      var content = null, bestArea = 0;
      for (var k = 0; k < kids.length; k++) {
        var sib = kids[k];
        if (sib === nav) continue;
        var r;
        try { r = sib.getBoundingClientRect(); } catch (e) { continue; }
        var area = r.width * r.height;
        if (area > bestArea) { bestArea = area; content = sib; }
      }
      if (!content) return null;

      return { panel: el, nav: nav, content: content };
    }

    /** 移除样式表（幂等）。 */
    function removeStyle() {
      var old = document.getElementById(STYLE_ID);
      var p = old && (old.parentElement || old.parentNode);
      if (p) p.removeChild(old);
    }

    /** 插入样式表。 */
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
      var marked = false;
      var markedPanel = null;
      var markedNav = null;
      var markedContent = null;
      var pending = false;
      var cleanups = [];

      /** 是否应接管：窄屏 + 找到面板。 */
      function shouldTakeOver() {
        // v1.4.47：@xiaopb/dsh-mobile-layout 接管设置页时本插件让位（见 narrow-sidebar 同处说明）
        if (document.documentElement.hasAttribute("data-dshm-mode")) return false;
        return window.innerWidth <= MAX_VIEWPORT_PX;
      }

      /** 撤销：移除全部标记与样式表。 */
      function unmark() {
        if (markedPanel) markedPanel.removeAttribute(PANEL_ATTR);
        if (markedNav) markedNav.removeAttribute(NAV_ATTR);
        if (markedContent) markedContent.removeAttribute(CONTENT_ATTR);
        markedPanel = markedNav = markedContent = null;
        removeStyle();
        marked = false;
      }

      /** 应用：打标记 + 注入样式。 */
      function mark(panel, cols) {
        insertStyle();
        panel.setAttribute(PANEL_ATTR, "");
        cols.nav.setAttribute(NAV_ATTR, "");
        cols.content.setAttribute(CONTENT_ATTR, "");
        markedPanel = panel;
        markedNav = cols.nav;
        markedContent = cols.content;
        marked = true;
        // [dsh-android] 前缀 → MainActivity 的 onConsoleMessage 会转发到 engine.log
        console.log(
          "[dsh-android] narrow-settings: 已接管设置面板（视口 " +
            window.innerWidth + "x" + window.innerHeight + "）"
        );
      }

      /**
       * 廉价预判：页面上到底有没有「可能是设置面板」的候选。
       *
       * ## 为什么必须加这一步（v1.4.0，issue #26）
       * 旧版 `refresh()` 直接进 `findPanel()`，而它在找不到 dialog 时会
       * `querySelectorAll("div, section, aside")` —— **整个页面所有块级元素** ——
       * 再对每个候选沿祖先链 `getComputedStyle()`（每次都是一次样式计算）、
       * 多次 `querySelectorAll("button")`、一次 `getBoundingClientRect()`（强制布局）。
       *
       * 主界面没有 `[role=dialog][aria-modal=true]`，所以那条退路**每个候选都要走完
       * 才失败**。AI 工作时消息流持续插入节点，`childList` 观察器把每次插入都变成
       * 一轮全页扫描，而且都在 rAF 里执行 —— 直接吃掉渲染帧。用户感知就是
       * 「滑一下就卡、开侧边栏/弹输入法也卡」（issue #26）。
       *
       * 现在先查一次属性选择器（引擎原生索引，成本与页面规模基本无关）：
       * 没有 dialog 就立刻返回，一步都不多走。设置面板本身就是 dialog，
       * 这条预判不会漏判。
       *
       * @returns {boolean} 是否值得进入完整定位。
       */
      function maybePanelPresent() {
        // ⚠️ v1.4.6（审查）：预判不能只认 role/aria-modal。
        // 引擎里还有其它 dialog（turn usage、stats pills、context meter、
        // settings-account 的 PlatformOverlay）—— 用户打开其中任何一个，
        // 预判都会放行，然后 findPanel() ① 全失败 → ② 扫全页 div/section/aside，
        // 对每个候选做 getComputedStyle + getBoundingClientRect。这正是 issue #26
        // 的原始复现路径，等于预判没解决问题。
        //
        // 现在优先查引擎自己打的稳定属性 `data-shortcut-modal="settings"`
        //（设置面板专用，见 dsh-client-ui-settings-general）；只有它存在才继续。
        // 同时保留 dialog 作为次要信号，但**必须**是设置类面板才算 —— 通过
        // 判断面板内是否含 nav 语义标签来区分（设置面板独有）。
        if (document.querySelector('[data-shortcut-modal="settings"]')) {
          // 引擎明确标记了设置面板：走完整定位
          return true;
        }
        // 退路：没有稳定属性时，只在「dialog 内部含 nav」时继续 ——
        // 其它 dialog（usage/pills/meter）没有 nav，扫描会白做。
        var dialogs = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
        for (var i = 0; i < dialogs.length; i++) {
          if (dialogs[i].querySelector("nav")) return true;
        }
        return false;
      }

      /** 重新判定。 */
      function refresh() {
        pending = false;
        if (!shouldTakeOver()) {
          if (marked) {
            unmark();
            console.log("[dsh-android] narrow-settings: 已恢复（视口 " +
                        window.innerWidth + "x" + window.innerHeight + "）");
          }
          return;
        }
        // 廉价预判：没有 dialog 就没有设置面板，避免全页扫描（见 maybePanelPresent）。
        if (!maybePanelPresent()) {
          if (marked) unmark(); // 面板已关闭
          return;
        }
        var found = findPanel();
        if (!found) {
          if (marked) unmark(); // 面板已关闭
          return;
        }
        if (!marked) {
          mark(found.panel, found);
        } else if (markedPanel !== found.panel || markedNav !== found.nav) {
          // 面板重建（切换设置页/重新挂载）→ 标记迁移
          unmark();
          mark(found.panel, found);
        }
      }

      /**
       * 合并高频触发。
       *
       * ⚠️ pending 必须在调用 rAF **之前**置位：若 rAF 同步执行（测试桩），
       * refresh 内清掉的标志会被返回值重新写回，后续 schedule 全被挡掉
       * （narrow-sidebar 实测踩过这个坑）。
       */
      function schedule() {
        if (pending) return;
        pending = true;
        window.requestAnimationFrame(refresh);
      }

      /**
       * 带最短间隔的触发（v1.4.0，issue #26）。
       *
       * ## 为什么需要
       * 设置面板的开关本身很稀疏，但 `childList` 观察器在 AI 工作期间会被消息流
       * **每几毫秒**触发一次。即便 `refresh()` 现在有廉价预判，无节流时仍然每帧
       * 都要跑一次选择器查询。这里给「由 DOM 变化引起」的触发加 200ms 下限，
       * 而 resize/orientationchange 这类**必须立即响应**的路径仍走 [schedule]。
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

      schedule();
      var timers = [300, 1200, 2600].map(function (ms) {
        return window.setTimeout(schedule, ms);
      });
      cleanups.push(function () {
        timers.forEach(function (t) { window.clearTimeout(t); });
      });

      window.addEventListener("resize", schedule);
      cleanups.push(function () { window.removeEventListener("resize", schedule); });
      window.addEventListener("orientationchange", schedule);
      cleanups.push(function () {
        window.removeEventListener("orientationchange", schedule);
      });

      // 设置面板的结构属性只在打开/关闭或面板重建时变化；忽略消息流里的 class/style/data 高频刷新。
      // 视口变化仍由 resize/orientationchange 处理。
      //
      // v1.4.0（issue #26）：保留 subtree（面板可能嵌在浮层容器里，收窄会漏判），
      // 但把触发换成 scheduleThrottled。本插件的 refresh 曾在找不到 dialog 时做
      // 全页 `div, section, aside` 扫描，必须靠「廉价预判 + 节流」双重限制；
      // 预判是主要收益（直接跳过整段），节流是兜底（限制预判本身的调用频率）。
      try {
        var mo = new MutationObserver(scheduleThrottled);
        mo.observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ["aria-label", "title"],
        });
        cleanups.push(function () { mo.disconnect(); });
      } catch (e) {
        /* 观察器不可用时退化为首帧判定 */
      }

      return function () {
        for (var i = 0; i < cleanups.length; i++) {
          try { cleanups[i](); } catch (e) { /* 卸载阶段忽略 */ }
        }
        unmark();
      };
    }

    exports.apply = apply;
    return module.exports;
  },
});

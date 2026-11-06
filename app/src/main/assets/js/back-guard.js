/**
 * dsh-android-back-guard —— 返回键兜底（v1.6.15）。
 *
 * ## 问题
 *
 * DSH WebUI 是单页应用，切换页面（插件页、自动化任务页、SSH 工作区……）时
 * **不往浏览器历史里写记录**（实测：前端 bundle 里 `pushState` / `popstate`
 * 出现次数为 0）。而 Android 壳的返回键逻辑是：
 *
 * ```kotlin
 * if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
 * ```
 *
 * 历史为空 → `canGoBack()` 恒 false → 在这些整页界面上按返回直接退出到桌面。
 *
 * ## 为什么不用 history.pushState 补历史
 *
 * 试过：往历史里压条目确实能让 `canGoBack()` 变 true，但**压了之后没法退回上一页**
 * —— 因为那些界面不是浮层，而是 `data-global-panel` 驱动的**流内面板替换**
 * （`layout-client.js` 里 `"data-global-panel": panelId !== null || void 0`），
 * `popstate` 触发时我们没有可靠的「关掉它」的手段，只会留下一条死历史。
 *
 * 所以改成**由外壳主动问页面**：返回键 → 调 `__dshBackGuard.back()` →
 * 它自己判断有没有东西可关、关掉并返回 true；只有返回 false 时外壳才退出应用。
 * 这条路径不依赖历史，也不改上游渲染逻辑。
 *
 * ## 三级判断
 *
 * 1. **浮层**（fixed/absolute，盖住视口 ≥90%）：点它的关闭控件 → Escape →
 *    最后才降级隐藏。用几何特征识别，不依赖类名（类名属于上游内部实现）。
 * 2. **整页面板**（`[data-global-panel]`）：点面板内的返回/关闭控件。
 *    这是插件页、自动化任务页这类界面的形态。
 * 3. 都没有 → 返回 false，外壳按原逻辑退出。
 *
 * ## 局部性
 *
 * `__dshBackGuard` 单例；所有查找都是即时 DOM 查询，不常驻 MutationObserver，
 * 避免在这个已经因为 DOM 节点多而卡顿的页面上再加一层观察者。
 */

(function () {
  if (window.__dshBackGuard) return;
  window.__dshBackGuard = true;

  var COVER_RATIO = 0.9;

  /** 关闭控件的候选选择器（按「最贴近用户预期」排序） */
  var CLOSE_SEL = [
    '[data-dsh-close]',
    '[aria-label*="back" i]',
    '[aria-label*="close" i]',
    '[aria-label*="返回"]',
    '[title*="back" i]',
    '[title*="close" i]',
    'button[class*="back" i]',
    'button[class*="close" i]',
    '[class*="backBtn" i]',
    '[class*="closeBtn" i]',
    '[class*="back-btn" i]',
    '[class*="close-btn" i]',
  ].join(',');

  function visible(el) {
    if (!el || el.nodeType !== 1) return false;
    var cs;
    try {
      cs = window.getComputedStyle(el);
    } catch (e) {
      return false;
    }
    if (!cs) return false;
    if (cs.display === 'none' || cs.visibility === 'hidden') return false;
    if (parseFloat(cs.opacity || '1') === 0) return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function isFullOverlay(el) {
    if (!visible(el)) return false;
    var cs;
    try {
      cs = window.getComputedStyle(el);
    } catch (e) {
      return false;
    }
    if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
    var r = el.getBoundingClientRect();
    return (
      r.width >= window.innerWidth * COVER_RATIO &&
      r.height >= window.innerHeight * COVER_RATIO
    );
  }

  /** 找到最外层整页浮层（深度优先，先看容器自身） */
  function topOverlay() {
    var found = null;
    var all = document.body.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      if (isFullOverlay(all[i])) {
        found = all[i];
        break;
      }
    }
    return found;
  }

  function clickAnythingClose(scope) {
    if (!scope) return false;
    var btn = null;
    try {
      btn = scope.querySelector(CLOSE_SEL);
    } catch (e) {
      return false;
    }
    if (!btn || !visible(btn)) return false;
    try {
      btn.click();
      return true;
    } catch (e) {
      return false;
    }
  }

  function dispatchEscape(scope) {
    try {
      var target = (scope && scope.querySelector('[role="dialog"]')) || scope || document;
      target.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'Escape',
          code: 'Escape',
          keyCode: 27,
          which: 27,
          bubbles: true,
          cancelable: true,
        })
      );
      document.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'Escape',
          code: 'Escape',
          keyCode: 27,
          which: 27,
          bubbles: true,
        })
      );
      return true;
    } catch (e) {
      return false;
    }
  }

  /** ① 关浮层 */
  function closeOverlay() {
    var ov = topOverlay();
    if (!ov) return false;
    if (clickAnythingClose(ov)) return true;
    if (dispatchEscape(ov)) {
      // 给了上游一个微任务时间；仍可见才降级隐藏
      setTimeout(function () {
        if (visible(ov)) {
          try {
            ov.style.display = 'none';
          } catch (e) {
            /* 忽略 */
          }
        }
      }, 60);
      return true;
    }
    return false;
  }

  /**
   * ② 关整页面板。
   *
   * `data-global-panel` 是 layout 包标记「当前主面板被一个全局面板占据」的属性。
   * 面板本身是流内 DOM，退出要靠它自己的返回控件 —— 所以这里只找控件点一下，
   * 找不到就如实返回 false（**不硬改 display**：那会让 React 状态与实际 DOM
   * 不一致，下一次渲染又把它弹回来，用户会觉得返回键时好时坏）。
   */
  function closeGlobalPanel() {
    var panel = document.querySelector('[data-global-panel]');
    if (!panel) return false;
    if (clickAnythingClose(panel)) return true;
    // 面板内没有明确关闭控件时，退一步在整个面板所在容器里找
    var host = panel.parentElement;
    if (host && clickAnythingClose(host)) return true;
    if (dispatchEscape(panel)) return true;
    return false;
  }

  /**
   * 外壳调用入口。返回 true = 已处理（外壳不要退出），false = 无事可做。
   *
   * 注意：`closeOverlay` 的降级隐藏是异步的（setTimeout 60ms），但这里**同步**
   * 返回 true 是对的 —— 用户按了返回，行为已经发生，外壳不该再退出应用。
   */
  window.__dshBackGuard = {
    back: function () {
      try {
        if (closeOverlay()) return true;
        if (closeGlobalPanel()) return true;
        return false;
      } catch (e) {
        return false;
      }
    },
    /** 诊断出口：AI / 用户可读当前状态 */
    state: function () {
      return {
        overlay: !!topOverlay(),
        globalPanel: !!document.querySelector('[data-global-panel]'),
      };
    },
  };
})();

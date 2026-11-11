/**
 * dsh-android-mobile-keys —— 移动端键盘行为修正（v1.4.27，issue #53）。
 *
 * ## 问题
 *
 * WebUI 的编辑器沿用桌面约定：`Enter` 提交、`Shift+Enter` 换行。手机软键盘上
 * 没有 Shift 键（或极难按到），于是「写多行消息」实际上做不到——用户一按回车
 * 消息就发出去了。
 *
 * ## 做法
 *
 * 在**捕获阶段**拦 keydown，只对「触屏窄屏」设备把不加修饰键的 `Enter`
 * 改写成 `Shift+Enter` 后重新派发：
 *
 *  · **一律换行**：回车不再发送消息，发送只走界面上的发送按钮（微信/QQ 的
 *    习惯）。手机上没有 Shift，也不该让「某一次回车」突然变成发送——
 *    空一行、只想换行时被发出去是最容易误操作的场景；
 *  · 桌面/外接键盘（`pointer: fine`）完全不受影响；
 *  · 输入法组字中（`isComposing` / keyCode 229）不拦——那是候选词确认，不是换行。
 *
 * 为什么要「重新派发」而不是直接 `preventDefault()`：上游是 ProseMirror，
 * 直接吞掉事件会让编辑器收不到 keydown，光标行为会乱。合成一个 shiftKey=true
 * 的事件重新派发，等于让上游走它本来就有、且经过测试的「换行」分支。
 *
 * ## 局部性
 *
 * 只挂一次（`__dshMobileKeys` 单例），且用捕获阶段 + `stopPropagation` 避免
 * 同一个事件被处理两次。
 */

(function () {
  if (window.__dshMobileKeys) return;
  window.__dshMobileKeys = true;

  /** 是否移动端触屏窄屏（与窄屏 UI 插件同门槛） */
  function isTouchNarrow() {
    try {
      return window.matchMedia('(pointer: coarse)').matches &&
        window.matchMedia('(max-width: 1023px)').matches;
    } catch (_) {
      return false;
    }
  }

  /** 找到事件来源的可编辑区域（编辑器/输入框） */
  function editableFrom(target) {
    if (!target || target.nodeType !== 1) {
      target = target && target.parentElement;
    }
    var el = target;
    for (var i = 0; el && i < 8; i++, el = el.parentElement) {
      if (el.isContentEditable) return el;
      var tag = (el.tagName || '').toUpperCase();
      if (tag === 'TEXTAREA') return el;
      if (tag === 'INPUT') {
        var t = (el.getAttribute('type') || 'text').toLowerCase();
        if (t === 'text' || t === 'search' || t === 'url' || t === 'email') return el;
      }
    }
    return null;
  }

  window.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    if (e.isComposing || e.keyCode === 229) return;   // 输入法组字中
    if (!isTouchNarrow()) return;                     // 桌面/外设键盘不干预
    // Shift/Ctrl/Alt/Meta 任一按下 → 交给上游（Shift+Enter 本就是换行，
    // Ctrl+Enter 等组合留给上游自己的语义）
    if (e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) return;

    var el = editableFrom(e.target);
    if (!el) return;

    // 有内容 / 没内容都换行：回车不再是发送键，发送只走发送按钮。
    // （旧实现留了「空输入框时回车发送」的旁路，用户在空行里想换行时会误发。）

    // 有内容：把「Enter」改写成「Shift+Enter」——上游的换行分支
    e.preventDefault();
    e.stopPropagation();
    var clone = new KeyboardEvent('keydown', {
      key: 'Enter',
      code: e.code || 'Enter',
      keyCode: 13,
      which: 13,
      shiftKey: true,
      bubbles: true,
      cancelable: true,
      composed: true,
    });
    // keyCode/which 在构造函数里多数浏览器会被忽略，显式补上供旧代码读取
    try {
      Object.defineProperty(clone, 'keyCode', { get: function () { return 13; } });
      Object.defineProperty(clone, 'which', { get: function () { return 13; } });
    } catch (_) { /* 只读环境忽略 */ }
    el.dispatchEvent(clone);
  }, true);   // ← 捕获阶段：先于上游 keymap

  // 供原生侧查询状态（调试/自检用）
  window.__dshMobileKeysInfo = function () {
    return { touchNarrow: isTouchNarrow(), installed: true };
  };
})();

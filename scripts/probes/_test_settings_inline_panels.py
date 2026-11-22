#!/usr/bin/env python3
"""设置页「行内展开」守护探针（issue #111）。

## 它守的是什么
用户提的不是「弹窗难看」，而是**弹窗把当前值藏起来了**：

    · 语言：弹一个单选列表，选之前看不到当前值在哪儿
    · 运行时代理：弹一个带输入框的对话框，要等它盖上来才见到输入框里的值

所以判据不能写成「有没有 MaterialAlertDialogBuilder」（那只是表面形态），
要钉住这几件事：

  A. 两个设置项**都不再弹窗** —— 而且这个「不弹窗」只针对这两项，
     **同意类**（远程访问 / 局域网 WebUI）必须仍然弹窗。写死「全页无对话框」
     就会把安全确认一起做掉，那是倒退。
  B. 面板**按行 id 缓存**（同 C 一处 B 那条「再点不会越插越多」），
     且**插在行的紧邻之后**而不是组尾 —— 追加到组尾会让内容离所属行隔着几行。
  C. 展开是幂等的：重复点只切换可见性。
  D. 单选行要同时给出**三条**「当前项」线索：加粗、勾号、强调色。
     只靠颜色对色弱/灰度屏用户是不可读的（这也是上游无障碍清单的同类要求）。
  E. 语义不变：代理保存仍走 `RuntimeDownloadConfig.isValid` →
     `setProxy` → toast，语言切换仍是 `LocaleHelper.set` + `recreate()`。
  F. 分隔线用 1dp；面板默认 GONE。

## 用法
    python scripts/probes/_test_settings_inline_panels.py
"""
import os
import re
import sys

ROOT = os.environ.get("DSH_ANDROID_ROOT") or os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..")
)
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")
SETTINGS = os.path.join(JAVA, "SettingsActivity.kt")
PANELS = os.path.join(JAVA, "InlinePanels.kt")
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout", "activity_settings.xml")

PASS = 0
FAIL = 0


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  PASS  [%s]%s" % (name, (" " + detail) if detail else ""))
    else:
        FAIL += 1
        print("  FAIL  [%s]%s" % (name, (" " + detail) if detail else ""))


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def strip_kotlin_comments(src):
    """去掉 // 行注释与 /* */ 块注释。

    ⚠️ 这一步是必须的：本项目的注释里大量出现「旧写法长什么样」的原文
    （比如 `MaterialAlertDialogBuilder(this).setSingleChoiceItems(...)`），
    不剥注释，「不许出现 X」这类断言会永远红、「必须出现 Y」会被注释喂饱
    —— 两个方向都是假绿/假红。
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            if j < 0:
                break
            out.append("\n")
            i = j + 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
        elif c == '"':
            # 字符串要原样保留（里面有 `//` 的 URL 很常见）
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\" and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def main():
    for p in (SETTINGS, PANELS, LAYOUT):
        check("file exists: %s" % os.path.basename(p), os.path.isfile(p), p)
        if not os.path.isfile(p):
            print("\n%d checks, %d failed" % (PASS + FAIL, FAIL))
            return 1

    st = strip_kotlin_comments(read(SETTINGS))
    pn = strip_kotlin_comments(read(PANELS))
    lay = read(LAYOUT)

    # ---- A. 两项改行内、同意类仍弹窗 ----
    check("A1 language row no longer opens a dialog",
          "setSingleChoiceItems" not in st)

    # 代理编辑器：函数还在（沿用旧名，调用点不用改），但体内不许再建对话框。
    # 判据必须**限定在这个函数体内** —— 全文件搜 MaterialAlertDialogBuilder 会命中
    # 同意类对话框，那本来就应该保留（A3 正是要它保留）。
    body = ""
    m = re.search(r"private fun showRuntimeProxyDialog\(\)\s*\{", st)
    if m:
        tail = st[m.end():]
        nxt = re.search(r"\n    (?:/\*\*|private fun |fun |internal fun )", tail)
        body = tail[:nxt.start()] if nxt else tail
    check("A2 proxy editor builds no dialog",
          bool(body) and "MaterialAlertDialogBuilder" not in body,
          "函数体长度 %d" % len(body))

    # 同意类必须还在：找 remote_access_consent / lan_web_consent 的对话框
    consent = [s for s in ("remote_access_consent", "lan_web_consent") if s in st or s in lay]
    check("A3 consent dialogs preserved", len(consent) >= 1, ",".join(consent))

    # ---- B. 面板缓存 + 插在行后 ----
    check("B1 panels cached by row id tag", "inline-panel-%d" in pn or "panelTag(row.id)" in pn)
    check("B2 reuses cached panel instead of stacking",
          "findViewWithTag" in pn and "return it as LinearLayout" in pn)
    check("B3 inserts right after the row (not at group tail)",
          "indexOfChild(row)" in pn and "index + 1" in pn)
    check("B4 panel defaults to GONE", re.search(r"visibility\s*=\s*View\.GONE", pn) is not None)
    check("B5 divider is 1dp", re.search(r"dp\(1\)", pn) is not None)

    # ---- C/D. 幂等 toggle + 三条「当前项」线索 ----
    check("C1 toggle flips visibility only", "panel.visibility = if (show)" in pn)
    check("D1 checked row is bold", "Typeface.BOLD" in pn)
    check("D2 checked row shows a check mark", "R.drawable.ic_check" in pn)
    check("D3 checked row uses accent color", "dshAccent" in pn)

    # ---- E. 语义不变 ----
    check("E1 proxy keeps isValid gate", "RuntimeDownloadConfig.isValid" in st)
    check("E2 proxy keeps setProxy", "RuntimeDownloadConfig.setProxy" in st)
    check("E3 proxy keeps reset action",
          "setProxy(this, null)" in st and "setting_runtime_proxy_reset_done" in st)
    check("E4 language keeps LocaleHelper.set", "LocaleHelper.set(this, code)" in st)
    check("E5 language still recreates to apply immediately", "recreate()" in st)
    check("E6 proxy save still toasts", "setting_runtime_proxy_saved" in st)
    check("E7 proxy invalid still reports inline",
          "setting_runtime_proxy_invalid" in st)
    check("E8 inline error shown without dialog.error",
          "input.error" not in st.split("showRuntimeProxyDialog")[-1])

    # ---- F. 布局前提：两行都有父容器且是垂直 LinearLayout ----
    for row in ("rowLanguage", "rowRuntimeProxy"):
        check("F1 %s present in layout" % row, 'android:id="@+id/%s"' % row in lay)
    check("F2 both rows attach panels via parent",
          st.count("InlinePanels.attach(") >= 2,
          "attach 调用 %d 处" % st.count("InlinePanels.attach("))
    check("F3 host cast to LinearLayout with null guard",
          "row.parent as? LinearLayout ?: return" in st or "rowLanguage.parent as LinearLayout" in st)

    # ---- G. 单源：不允许两处各写一套 dp 换算 ----
    check("G1 dp helper defined once for panels",
          pn.count("internal fun Context.dp") == 1)
    check("G2 ripple helper centralized", "selectableItemBackground()" in pn)

    print("\n%d checks, %d failed" % (PASS + FAIL, FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())

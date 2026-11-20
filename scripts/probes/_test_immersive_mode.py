#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""全屏模式三档（issue #123）：隐藏导航栏必须**同时**改两处，缺一白带还在。

## 为什么值得单独守

用户报「全屏只隐藏了状态栏，底部导航栏依旧占位，正文贴不到屏幕最底边」。拆开是
两件独立的事叠在一起：

  ① hide() 只传了 statusBars()，导航栏根本没 hide；
  ② 底部让位 bottomPad = navBottom 主动把那条高度让出来 —— 即使 hide 了也还空着。

只改 ① 会得到「导航栏消失但白带还在」，只改 ② 会得到「内容画到导航栏底下被手势条
压住」。所以本探针的每一条都成对守在**这两处**上，任何一处回退都必须变红。

## 反向验证（每条都能被打红）

  B1 改回只 hide statusBars                → B1 红
  C2 bottomPad 改回无条件 navBottom        → C2 红
  D3 删掉 IME 弹起后的重断言               → D3 红
  E1 退出时不清 HIDE_NAVIGATION            → E1 红
  A2 枚举删掉 STATUS_NAV 档                → A2 红
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
MAIN = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "MainActivity.kt")
APPEAR = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "AppearanceActivity.kt")
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout", "activity_appearance.xml")

checks = 0
failed = 0


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   %s" % msg)
    else:
        failed += 1
        print("  FAIL %s" % msg)


def strip_kt_comments(src):
    out = []
    i, n = 0, len(src)
    in_str = in_raw = False
    bs = chr(92)
    while i < n:
        c = src[i]
        if in_raw:
            if src.startswith('"""', i):
                in_raw = False
                out.append('"""')
                i += 3
            else:
                out.append(c)
                i += 1
            continue
        if in_str:
            if c == bs:
                out.append(src[i:i + 2])
                i += 2
                continue
            if c == '"':
                in_str = False
            out.append(c)
            i += 1
            continue
        if src.startswith('"""', i):
            in_raw = True
            out.append('"""')
            i += 3
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find(chr(10), i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def main():
    main_src = io.open(MAIN, encoding="utf-8").read()
    code = strip_kt_comments(main_src)

    print("[A] 三档枚举与存取")
    ok("enum class ImmersiveMode" in code, "A1 定义了 ImmersiveMode 枚举")
    # ⚠️ 不能用 "STATUS_NAV" in code：它会被 STATUS_NAVX 这类子串蒙对（实测 M4 变异
    # 改名成 STATUS_NAVX 后本行仍绿）。必须在**枚举体内**按独立标识符匹配。
    enum_body = ""
    em = re.search(r"enum class ImmersiveMode \{([\s\S]{0,400}?)\n\s*\}", code)
    if em:
        enum_body = em.group(1)
    ok(
        enum_body != "" and re.search(r"\bSTATUS_NAV\b", enum_body) is not None,
        "A2 枚举里有独立的一档 STATUS_NAV（状态栏 + 导航栏）",
    )
    ok("fun immersiveMode(" in code and "fun setImmersiveMode(" in code, "A3 有读写入口")
    ok("fun immersiveNavEnabled(" in code, "A4 有只读的 nav 判定（调用方不必自己拼条件）")
    ok(
        code.count("KEY_IMMERSIVE_NAV") >= 2,
        "A5 nav 档有自己的偏好键（升级后老用户仍是「仅状态栏」）",
    )

    print("[B] 隐藏时两处必须同时到位")
    # hide() 的实参是一个**变量**（bars / bars2），不是字面拼接 —— 只看 hide(...) 的
    # 参数列表会漏判（第一版就这么假红过）。改为：先找「bars 里加了 navigationBars」，
    # 再要求同一个变量被 hide 掉，两步都成立才算真的隐藏了导航栏。
    bars_added = re.findall(
        r"var (\w+) = WindowInsetsCompat\.Type\.statusBars\(\)[\s\S]{0,200}?"
        r"if \(\w*[Nn]av\w*\) \1 = \1 or WindowInsetsCompat\.Type\.navigationBars\(\)",
        code,
    )
    ok(len(bars_added) >= 1, "B1 有 statusBars 变量按档位并入 navigationBars")
    hid = [v for v in bars_added if re.search(r"\.hide\(\s*" + v + r"\s*\)", code)]
    ok(len(hid) >= 1, "B1b 并进来的那个变量真的传给 hide()（只拼不 hide 等于没做）")
    ok(
        re.search(r"if \(hideNav\)\s*bars\s*=\s*bars or", code) is not None
        or re.search(r"if \(\w*[Nn]av\w*\)\s*bars\s*=", code) is not None,
        "B2 导航栏是按档位条件加入的，不是无条件隐藏",
    )
    ok("SYSTEM_UI_FLAG_HIDE_NAVIGATION" in code, "B3 API<30 用 SYSTEM_UI_FLAG_HIDE_NAVIGATION 兜底")

    print("[C] 底部让位：隐藏了就不能再让")
    m = re.search(r"val bottomPad = ([\s\S]{0,260})", code)
    pad_expr = m.group(1) if m else ""
    ok(pad_expr != "", "C1 找到 bottomPad 表达式")
    ok(
        "navHidden" in pad_expr or "immersiveNav" in pad_expr,
        "C2 bottomPad 参考了「导航栏已隐藏」这一状态（否则白带还在）",
    )
    ok(re.search(r"val imeAffectsViewport = imeVisible \|\| imeBottom > 0", code) is not None
       and re.search(r"if \(imeAffectsViewport\)\s*\{\s*imeBottom\b", pad_expr) is not None,
       "C3 键盘弹起时仍然让位（IME 不受 nav 档位影响）")

    print("[D] 生命周期：系统会重置，必须重断言")
    ok(code.count("immersiveNavEnabled") >= 3, "D1 nav 档在 >=3 处被重新读取（应用 / 重断言 / insets 回调）")
    tail = code.split("private fun scheduleImmersiveReassert")[-1][:4000] if "private fun scheduleImmersiveReassert" in code else ""
    ok("navigationBars()" in tail, "D2 兜底重断言里也带上了导航栏（OEM 重置后能自己回来）")
    ok(
        re.search(r"imeBottom > 0", code) is not None and code.count("Type.navigationBars()") >= 3,
        "D3 输入法弹起后会重新隐藏导航栏（否则键盘一出就把手势条带回来）",
    )

    print("[E] 退出必须还原")
    # 取 clearMask 的**赋值块**（首个出现处），而不是全文最后出现的 clearMask.inv()。
    # 第一版取 [-1] 落到了使用点之后，必然假红。
    cm = re.search(r"val clearMask = ([\s\S]{0,700}?)\n\s*@Suppress", code)
    clear_block = cm.group(1) if cm else ""
    ok(
        "SYSTEM_UI_FLAG_HIDE_NAVIGATION" in clear_block
        and "SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION" in clear_block,
        "E1 关闭全屏时清掉两个 nav 相关 flag（否则导航栏永远不回来）",
    )
    ok(
        re.search(r"Type\.statusBars\(\) or\s*[\s\S]{0,80}Type\.navigationBars\(\)", code) is not None,
        "E2 关闭时同时 show(statusBars + navigationBars)",
    )

    print("[F] 设置页")
    appear = io.open(APPEAR, encoding="utf-8").read()
    ok("swImmersiveNav" in appear, "F1 设置页有「同时隐藏导航栏」开关")
    ok(
        "rowImmersiveNav.isEnabled" in appear or "swImmersiveNav.isEnabled" in appear,
        "F2 第一级关闭时第二级禁用（避免半截状态）",
    )
    ok("ImmersiveMode.OFF" in appear, "F3 关第一级时一并回落成 OFF")
    layout = io.open(LAYOUT, encoding="utf-8").read()
    ok('android:id="@+id/swImmersiveNav"' in layout, "F4 布局里加了开关控件")
    ok('android:id="@+id/rowImmersiveNav"' in layout, "F5 布局里加了整行（用于禁用态）")

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())

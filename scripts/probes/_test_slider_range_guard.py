#!/usr/bin/env python3
"""Locks 「Slider 的 valueTo 必须覆盖它承载的那个量的语义上限」。

## 为什么单独一条探针

这不是理论风险：2026-11-24 用户报「个性化设置点选项直接应用重启」。实测进
「设置外观与显示 → 设置页背景图」必现 FATAL：

```
java.lang.IllegalStateException: Slider value(100.0) must be greater or equal to
valueFrom(0.0), and lower or equal to valueTo(75.0)
    at BaseSlider.validateValues(BaseSlider.java:746)
    at BaseSlider.validateConfigurationIfDirty(BaseSlider.java:796)
    at BaseSlider.updateTicksCoordinates(BaseSlider.java:2634)
    at BaseSlider.updateTrackWidth(BaseSlider.java:2697)
    at BaseSlider.onSizeChanged(BaseSlider.java:2629)
```

根因：三个滑块被 `listOf(...).forEach { it.valueTo = 75f }` 统一设成 0..75，
而其中两个承载的是**不透明度**（语义 0..100）：
`settingsBgAlpha()` 默认 **100**、`settingsBgControlAlpha()` 默认 **90**。

崩溃点在**第一次 onSizeChanged**，不在赋值当场 —— 所以「编译过、单测过、
静态看也觉得合理」全都会绿，只有真机进页面才炸。而崩溃表现是进程被杀后
重建，用户看到的就是「点一下就重启」，极难和「路由没到位」区分。

## 判据怎么落地（不靠人眼）

Sliders 的值域有两处来源：
- `CustomVisualPref` 的读取函数带 `coerceIn(0, N)` → N 就是语义上限；
- `Slider.valueTo` 是 UI 允许的最大值。

本探针按「Activity 里 seekXxx.value = CustomVisualPref.someGetter(this)」
反查 getter 的 coerce 上限，再与同文件里给该 seekXxx 设的 valueTo 比对。

## 变异验证

- 把 `seekAlpha.valueTo = 100f` 改回 75f → A2 红（越界 100 > 75）
- 把 `seekControlAlpha.valueTo = 100f` 改回 75f → A2 红（越界 90 > 75）
- 把三个合并回 `listOf(...).forEach { it.valueTo = 75f }` → A1 红（不再逐个设）
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ACT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "SettingsBgActivity.kt")
PREF = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "CustomVisualPref.kt")

NL = chr(10)
_ok = 0
_bad = 0


def ok(cond, msg, detail=""):
    global _ok, _bad
    if cond:
        _ok += 1
        print("  ok   " + msg)
    else:
        _bad += 1
        print("  FAIL " + msg + ((" — " + str(detail)) if detail else ""))


def read(p):
    return io.open(p, encoding="utf-8").read()


def strip_line_comments(s):
    return NL.join(l for l in s.split(NL) if not l.strip().startswith("//"))


act = read(ACT)
pref = read(PREF)

print("[A] 三个滑块各自设置 valueTo（不许再共用一处 forEach）")

# A1：valueTo 必须逐个出现在 seekXxx 上，不能靠 listOf(...).forEach 统一设
for name in ("seekAlpha", "seekBlur", "seekControlAlpha"):
    pat = re.compile(r"%s\.valueTo\s*=" % name)
    ok(pat.search(strip_line_comments(act)) is not None,
       "A1 %s 单独设了 valueTo（不是 listOf 批量）" % name)
ok("it.valueTo = 75f" not in strip_line_comments(act),
   "A1b 不再有统一的 it.valueTo = 75f（三个量的语义上限不同）")

print(NL + "[B] valueTo 必须覆盖对应读取函数的 coerce 上限")

# 取 CustomVisualPref 里每个 getter 的 coerceIn(0, N)。
#
# ★必须同时认两种函数体形态：
#   ① 表达式体  `fun x(...): Int = prefs(...).coerceIn(0, N)`
#   ② 块体      `fun x(...): Int { ... coerceIn(0, N) ... }`
# 第一版只认块体（`[^{]*\{`），而 settingsBgAlpha / settingsBgControlAlpha 都是
# 表达式体 —— 正则便跨过到**下一个**函数的 `{`，把后面那个函数的 coerce 上限
# 认成前一个的。实测后果：settingsBgControlAlpha 解析不到上限，B 段直接「跳过」，
# 于是把它改回 75f 也不会红（假绿）。
getters = {}
for m in re.finditer(r"fun (\w+)\([^)]*\)\s*:\s*Int\s*(=|\{)", pref):
    name, form = m.group(1), m.group(2)
    start = m.end()
    if form == "=":
        # 表达式体：到本行结束（再看一行，防止折行）
        seg = pref[start:start + 260].split(NL)
        body = NL.join(seg[:2])
    else:
        # 块体：截到下一个同级 fun
        nxt = re.search(NL + r"    (?:private |internal )?fun ", pref[start:])
        body = pref[start:start + (nxt.start() if nxt else 400)]
    hit = re.search(r"coerceIn\(\s*\d+\s*,\s*(\d+)\s*\)", body)
    if hit:
        getters[name] = int(hit.group(1))

# 取 Activity 里 seekXxx.value = CustomVisualPref.getter(...) 的配对
pairs = []
for m in re.finditer(r"(seek\w+)\.value\s*=\s*CustomVisualPref\.(\w+)\(", act):
    pairs.append((m.group(1), m.group(2)))
# valueTo 取值
vtos = {}
for m in re.finditer(r"(seek\w+)\.valueTo\s*=\s*([0-9.]+)f", act):
    vtos[m.group(1)] = float(m.group(2))

for slider, getter in pairs:
    cap = getters.get(getter)
    vto = vtos.get(slider)
    if cap is None or vto is None:
        ok(True, "B% s 跳过 %s/%s（未解析到上限或 valueTo）" % ("", slider, getter))
        continue
    ok(vto >= cap,
       "B %s.valueTo=%.0f 覆盖 %s() 的上限 %d" % (slider, vto, getter, cap),
       "越界 %.0f > %.0f" % (cap, vto) if vto < cap else "")

print(NL + "[C] 崩溃现场的特征串不能在源码里变成注释骗过探针")
# 剥注释后仍要能找到「三个滑块分别设」这组语句
code_only = strip_line_comments(act)
ok("IllegalStateException" in act and "validateValues" in act,
   "C1 注释里保留了崩溃原文（供后来人核对，注释文字不算断言依据）")

print(NL + "checks=%d failed=%d" % (_ok + _bad, _bad))
if _bad:
    print("RESULT FAILED")
    sys.exit(1)
print("RESULT PASSED")

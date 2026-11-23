#!/usr/bin/env python3
"""findViewById<T> 的类型必须与布局里该 id 的真实控件兼容。

换了控件的 XML 标签（Button → MaterialButton、ImageView → ImageButton、
Switch → MaterialSwitch）而 Kotlin 侧没同步，编译能过，运行时
findViewById 返回 null 才崩 —— 这类问题只有真机才暴露，用静态检查先拦住。
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(APP, "res")
JAVA = os.path.join(APP, "java", "app", "dsh", "mobile")
ANDROID = "{http://schemas.android.com/apk/res/android}"

# 简单名 -> 可接受的基类链（只列常用件，够覆盖本项目）
PARENTS = {
    "Button": {"Button", "TextView", "View"},
    "MaterialButton": {"MaterialButton", "AppCompatButton", "Button", "TextView", "View"},
    "ImageView": {"ImageView", "View"},
    "ImageButton": {"ImageButton", "ImageView", "View"},
    "TextView": {"TextView", "MaterialTextView", "Button", "MaterialButton", "EditText", "TextInputEditText", "View"},
    "Switch": {"Switch", "CompoundButton", "Button", "TextView", "View"},
    "MaterialSwitch": {"MaterialSwitch", "SwitchCompat", "CompoundButton", "View"},
    "SeekBar": {"SeekBar", "AbsSeekBar", "ProgressBar", "View"},
    "LinearLayout": {"LinearLayout", "ViewGroup", "View"},
    "FrameLayout": {"FrameLayout", "ViewGroup", "View"},
    "ScrollView": {"ScrollView", "FrameLayout", "ViewGroup", "View"},
    "View": {"View"},
    "ViewGroup": {"ViewGroup", "View"},
    "MaterialCardView": {"MaterialCardView", "CardView", "FrameLayout", "ViewGroup", "View"},
}

# id -> 真实控件简单名
id_type = {}
for p in glob.glob(os.path.join(RES, "layout", "*.xml")):
    try:
        root = ET.parse(p).getroot()
    except ET.ParseError:
        continue
    for node in root.iter():
        aid = node.get(ANDROID + "id") or ""
        if aid.startswith("@+id/"):
            id_type.setdefault(aid[5:], node.tag.split("}")[-1].split(".")[-1])

FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok else ""))
    if not ok:
        FAILS.append(name)


problems = []
for f in glob.glob(os.path.join(JAVA, "*.kt")):
    src = open(f, encoding="utf-8").read()
    for m in re.finditer(r"findViewById<([\w.]+)>\(R\.id\.(\w+)\)", src):
        decl = m.group(1).split(".")[-1]
        rid = m.group(2)
        real = id_type.get(rid)
        if real is None or decl not in PARENTS:
            continue
        # 声明成 View / ViewGroup 永远安全（任何控件都是它的子类），不算问题
        if decl in ("View", "ViewGroup"):
            continue
        if real not in PARENTS[decl]:
            line = src[: m.start()].count("\n") + 1
            problems.append(f"{os.path.basename(f)}:{line} findViewById<{decl}>({rid}) 实际是 {real}")

check("findViewById types match layout tags", not problems, "\n      " + "\n      ".join(problems[:8]))

if FAILS:
    print(f"\nFAILED: {len(FAILS)} check(s)")
    sys.exit(1)
print("\nOK: view type checks passed")

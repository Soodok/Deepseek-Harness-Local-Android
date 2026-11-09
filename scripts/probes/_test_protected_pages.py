#!/usr/bin/env python3
"""Locks ProtectedPages 的契约：能读不能点，且不误伤正常设置页。

判据正确与否直接决定「agent 能不能自己给自己开无障碍」——写错的后果是
能力边界失效，不是体验问题。所以这里用**可独立复算的样本**验证分类函数本身：
把 Kotlin 的三张表（PACKAGES / SETTINGS_PACKAGES / TITLE_WORDS）从源码里解析
出来，再用 Python 复刻 isProtected / titleTexts 的语义跑断言。

  1. 三张表存在且非空
  2. 整包保护覆盖 AOSP + 小米 + OPPO + vivo + 华为 + 三星（漏一家就等于漏一台机器）
  3. 设置类按标题拦，不能整包拦（否则改壁纸都被拦）
  4. isProtected 语义：整包命中→True；设置包+标题命中→True；设置包+普通标题→False
  5. titleTexts 口径：只取上部、滚动容器内的不算标题
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ProtectedPages.kt")

fails = []
def chk(cond, msg):
    if cond:
        print("  PASS  %s" % msg)
    else:
        print("  FAIL  %s" % msg)
        fails.append(msg)

def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()

def strip_comments(s):
    """去掉 // 行注释，避免把「注释里提到的旧写法」当成代码断言。"""
    out_lines = []
    for ln in s.split("\n"):
        i = ln.find("//")
        if i >= 0:
            # 粗略但足够：本项目字符串里不出现 //
            ln = ln[:i]
        out_lines.append(ln)
    return "\n".join(out_lines)

print("== 1. 文件与结构 ==")
chk(os.path.isfile(PP), "ProtectedPages.kt exists")
src = read(PP)
chk("object ProtectedPages" in src, "declared as object ProtectedPages")
for fn in ("fun isProtected(", "fun titleTexts("):
    chk(fn in src, "%s present" % fn)
chk("data class TitleItem" in src, "TitleItem data class present")

def parse_set(name):
    m = re.search(name + r"\s*:\s*Set<String>\s*=\s*setOf\((.*?)\n\s*\)", src, re.S)
    if not m:
        return None
    return set(re.findall(r'"([^"]+)"', m.group(1)))

PACKAGES = parse_set("val PACKAGES")
SETTINGS = parse_set("val SETTINGS_PACKAGES")
m = re.search(r"val TITLE_WORDS\s*:\s*List<String>\s*=\s*listOf\((.*?)\n\s*\)", src, re.S)
TITLES = re.findall(r'"([^"]+)"', m.group(1)) if m else []

print("== 2. 三张表非空 ==")
chk(PACKAGES is not None and len(PACKAGES) >= 15, "PACKAGES has >= 15 entries (got %s)" % (len(PACKAGES) if PACKAGES else 0))
chk(SETTINGS is not None and len(SETTINGS) >= 5, "SETTINGS_PACKAGES has >= 5 entries (got %s)" % (len(SETTINGS) if SETTINGS else 0))
chk(len(TITLES) >= 15, "TITLE_WORDS has >= 15 entries (got %d)" % len(TITLES))

print("== 3. 整包保护覆盖各 OEM（漏一家 = 漏一批机器）==")
for pkg, label in [
    ("com.android.packageinstaller", "AOSP installer"),
    ("com.google.android.packageinstaller", "Google installer"),
    ("com.miui.securitycenter", "MIUI security"),
    ("com.coloros.securitypermission", "ColorOS permission"),
    ("com.vivo.permissionmanager", "vivo permission"),
    ("com.huawei.systemmanager", "Huawei system manager"),
    ("com.samsung.android.packageinstaller", "Samsung installer"),
]:
    chk(pkg in (PACKAGES or set()), "protected package %s (%s)" % (pkg, label))

print("== 4. 设置类必须按标题拦，不能整包拦 ==")
chk("com.android.settings" in (SETTINGS or set()), "com.android.settings in SETTINGS_PACKAGES")
chk("com.android.settings" not in (PACKAGES or set()), "com.android.settings NOT wholesale-blocked")
chk("com.miui.settings" in (SETTINGS or set()), "MIUI settings in SETTINGS_PACKAGES")

print("== 5. 复刻 isProtected 语义 ==")
def is_protected(pkg, titles):
    if not pkg:
        return False
    if pkg in PACKAGES:
        return True
    if pkg not in SETTINGS:
        return False
    return any(w.lower() in t.lower() for t in titles for w in TITLES)

chk(is_protected("com.android.packageinstaller", []) is True,
    "installer package blocked regardless of titles")
chk(is_protected("com.miui.securitycenter", []) is True,
    "MIUI security center blocked")
chk(is_protected("com.android.settings", ["无障碍"]) is True,
    "settings + 无障碍 title blocked")
chk(is_protected("com.android.settings", ["Developer options"]) is True,
    "settings + Developer options blocked")
chk(is_protected("com.android.settings", ["安装未知应用"]) is True,
    "settings + 安装未知应用 blocked")
chk(is_protected("com.android.settings", ["壁纸", "显示", "声音"]) is False,
    "settings + harmless titles NOT blocked")
chk(is_protected("com.tencent.mm", ["无障碍"]) is False,
    "non-settings app with the word 无障碍 NOT blocked")
chk(is_protected(None, ["无障碍"]) is False, "null package -> not protected")
chk(is_protected("", []) is False, "blank package -> not protected")

print("== 6. titleTexts 口径 —— 阈值与规则从 Kotlin 源码解析，不写死在探针里 ==")
# 关键：把 Kotlin 里真正的阈值与「滚动容器排除」解析出来，再用它驱动复刻。
# 如果有人在 Kotlin 里把 0.2 改成 0.9、或删掉 inScrollable 的 continue，
# 这里的断言会立刻变红——否则探针只是在测自己。
# 变量名大小写无关，只认「哪个表达式配哪个阈值」：inTop 看 centerY，inHeadingZone 还要求 heading
m_top = re.search(r"val\s+in_?[Tt]op\s*=\s*item\.centerY\s*<\s*screenHeight\s*\*\s*([0-9.]+)", src)
m_head = re.search(r"val\s+in_?[Hh]eading_?[Zz]one\s*=\s*item\.heading\s*&&\s*item\.centerY\s*<\s*screenHeight\s*\*\s*([0-9.]+)", src)
chk(m_top is not None, "top-zone threshold parsed from Kotlin")
chk(m_head is not None, "heading-zone threshold parsed from Kotlin")
TOP_T = float(m_top.group(1)) if m_top else -1.0
HEAD_T = float(m_head.group(1)) if m_head else -1.0
chk(TOP_T == 0.2, "top zone is 20%% of the screen (got %r)" % TOP_T)
chk(HEAD_T == 0.3, "heading zone is 30%% of the screen (got %r)" % HEAD_T)
chk(re.search(r"if\s*\(item\.depth\s*>\s*1\)\s*continue", src) is not None,
    "deeply nested list rows are excluded by an explicit guard")
# 注释里可以留「为什么不用 inScrollable」的说明；代码里不能再用它。
chk("inScrollable" not in strip_comments(src),
    "inScrollable is gone from the code: settings titles LIVE inside a RecyclerView")
chk(re.search(r"if\s*\(item\.inHeadingZone\s*\|\|\s*inTop\)\s*out\.add\(text\)", src) is not None
    or re.search(r"if\s*\(inHeadingZone\s*\|\|\s*inTop\)\s*out\.add\(text\)", src) is not None,
    "title accepted only when in heading zone OR at the very top")
chk("if (out.size >= limit) break" in src, "limit honoured with an early break")

class Item:
    def __init__(self, text, centerY, heading=False, depth=0):
        self.text, self.centerY, self.heading, self.depth = text, centerY, heading, depth

def title_texts(items, h, limit=8):
    if h <= 0:
        return []
    out = []
    for it in items:
        if len(out) >= limit:
            break
        t = it.text.strip()
        if not t:
            continue
        in_top = it.centerY < h * TOP_T
        in_heading = it.heading and it.centerY < h * HEAD_T
        if it.depth > 1:
            continue
        if in_heading or in_top:
            out.append(t)
    return out

H = 2400
chk(title_texts([Item("无障碍", 100)], H) == ["无障碍"], "top-of-screen text is a title")
chk(title_texts([Item("无障碍", 2000)], H) == [], "mid-screen text is NOT a title")
# 真实回归用例：模拟器 Android 14 的设置 → 无障碍页，标题「无障碍」
# 位于 RecyclerView 的第一项（depth=1）。必须算标题，否则保护页判不出来。
chk(title_texts([Item("无障碍", 279, depth=1)], H) == ["无障碍"],
    "REGRESSION: a settings title inside a RecyclerView (depth=1) IS a title")
chk(title_texts([Item("无障碍", 279, depth=2)], H) == [],
    "a row nested deeper (depth=2) is list content, not a title")
chk(title_texts([Item("标题", 600, heading=True)], H) == ["标题"], "heading in the 30% band is a title")
chk(title_texts([Item("标题", 900, heading=True)], H) == [], "heading below 30% is NOT a title")
chk(title_texts([Item("", 100)], H) == [], "blank text skipped")
chk(title_texts([Item("x", 100)], 0) == [], "zero screen height -> empty")
chk(len(title_texts([Item("t%d" % i, 10) for i in range(20)], H)) == 8, "limit honoured (8)")

print()
if fails:
    print("FAILED: %d check(s)" % len(fails))
    for f in fails:
        print("  - %s" % f)
    sys.exit(1)
print("All ProtectedPages checks passed.")
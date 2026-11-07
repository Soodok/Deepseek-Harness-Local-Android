#!/usr/bin/env python3
"""MD3 主题分派一致性：设置体系页面必须真的拿到 Theme.Dsh.MD3。

背景（2026-11-07）：合并 main 的 60aed9c1（PR #93 整包 revert）时，
ThemedActivity 的 `setTheme(if (usesSettingsBackground) Theme_Dsh_MD3 else Theme_Dsh)`
被静默覆盖成硬编码 `Theme_Dsh`，同时 themes.xml 里的 Theme.Dsh.MD3 主题块也被删。
因为 Git 按「一方删除、另一方未改」自动处理，没有任何冲突提示 —— 直到模拟器上
打开设置页抛 InflateException 才暴露（55 处 ?attr/colorOnSurface 解析不到）。

这个探针锁住三件事，任一丢失都会红：
  [A] ThemedActivity 必须按 usesSettingsBackground 分派，不能硬编码
  [B] Theme.Dsh.MD3 必须在明暗两套色板里都有定义，且父主题是 Material3
  [C] 设置体系布局里若用了 MD3 属性，就必须存在对应的 MD3 主题
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ok = 0
fail = []


def check(name, cond, detail=""):
    global ok
    if cond:
        ok += 1
        print("PASS  " + name)
    else:
        fail.append(name + ((" :: " + detail) if detail else ""))
        print("FAIL  " + name + ((" :: " + detail) if detail else ""))


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.exists(p):
        return ""
    return io.open(p, encoding="utf-8", errors="ignore").read()


# ---------- [A] ThemedActivity 必须条件分派 ----------
themed = read("app/src/main/java/app/dsh/mobile/ThemedActivity.kt")
check("[A1] ThemedActivity 存在", bool(themed))

# 断言分派表达式本身，而不是「文件里出现过 Theme_Dsh_MD3」——
# 后者在把 setTheme 改成硬编码后仍然为真（注释里也会出现），是假绿。
dispatch = re.search(
    r"setTheme\s*\(\s*if\s*\(\s*usesSettingsBackground\s*\)\s*R\.style\.Theme_Dsh_MD3\s*"
    r"else\s*R\.style\.Theme_Dsh\s*\)",
    themed,
)
check("[A2] setTheme 必须按 usesSettingsBackground 分派", dispatch is not None,
      "找不到 setTheme(if (usesSettingsBackground) ... else ...)")

hard = re.search(r"^\s*setTheme\s*\(\s*R\.style\.Theme_Dsh\s*\)\s*$", themed, re.M)
check("[A3] 不得存在硬编码 setTheme(Theme_Dsh)", hard is None,
      "发现硬编码 setTheme，MD3 页面会退回无 MD3 属性的主题")

check("[A4] usesSettingsBackground 仍可被子类覆盖",
      re.search(r"protected\s+open\s+val\s+usesSettingsBackground", themed) is not None)

# ---------- [B] Theme.Dsh.MD3 明暗两套都要有 ----------
for rel, label in (
    ("app/src/main/res/values/themes.xml", "light"),
    ("app/src/main/res/values-night/themes.xml", "night"),
):
    t = read(rel)
    m = re.search(r'<style\s+name="Theme\.Dsh\.MD3"\s+parent="([^"]+)"', t)
    check("[B:%s] Theme.Dsh.MD3 已定义" % label, m is not None, rel)
    if m:
        check("[B:%s] 父主题是 Material3" % label,
              "Material3" in m.group(1), "parent=%s" % m.group(1))

# ---------- [C] 用了 MD3 属性就必须有 MD3 主题 ----------
MS_ATTRS = (
    "colorOnSurface", "colorOnSurfaceVariant", "colorPrimary", "colorSurface",
    "textAppearanceHeadlineSmall", "textAppearanceBodyLarge",
    "textAppearanceBodyMedium", "textAppearanceTitleSmall",
    "colorOutlineVariant", "colorOnPrimary", "colorPrimaryContainer",
    "colorOnPrimaryContainer", "colorSecondaryContainer", "colorOnSecondaryContainer",
)
layouts = os.path.join(ROOT, "app/src/main/res/layout")
users = []
if os.path.isdir(layouts):
    for f in sorted(os.listdir(layouts)):
        if not f.endswith(".xml"):
            continue
        t = io.open(os.path.join(layouts, f), encoding="utf-8", errors="ignore").read()
        used = [a for a in MS_ATTRS if "?attr/" + a in t]
        if used:
            users.append((f, len(used)))

check("[C1] 至少有布局使用 MD3 属性（否则本项无意义）", len(users) > 0,
      "没有布局用 ?attr/ MD3 属性")

# 每个用到 MD3 属性的布局，其宿主 Activity 必须 derives ThemedActivity
# 且 usesSettingsBackground 能覆盖到 —— 简化判据：MD3 主题确实存在（[B] 已证）
# 这里额外确认没有布局同时混用「旧主题 + MD3 属性」的键名拼写错误
bogus = []
for f, _n in users:
    t = io.open(os.path.join(layouts, f), encoding="utf-8", errors="ignore").read()
    for m in re.finditer(r"\?attr/([A-Za-z0-9_]+)", t):
        a = m.group(1)
        if a.startswith("color") or a.startswith("textAppearance"):
            # 全部 ?attr/color*/textAppearance* 必须在 MD3 属性白名单里
            if a not in MS_ATTRS and not a.startswith("textAppearance"):
                bogus.append((f, a))
check("[C2] 布局里的 ?attr/color* 均在已知 MD3 属性表内", len(bogus) == 0,
      str(bogus[:5]))

# ---------- [D] 反向自检：判定逻辑本身必须能判不合格 ----------
# 用一条真实的「坏样本」跑同一正则，必须判不合格，确保探针不是恒真
bad_sample = """
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Dsh)
        super.onCreate(savedInstanceState)
    }
"""
check("[D1] 自检：硬编码样本必须被 A3 判红",
      re.search(r"^\s*setTheme\s*\(\s*R\.style\.Theme_Dsh\s*\)\s*$", bad_sample, re.M) is not None)
check("[D2] 自检：硬编码样本必须不被 A2 匹配",
      re.search(
          r"setTheme\s*\(\s*if\s*\(\s*usesSettingsBackground\s*\)\s*R\.style\.Theme_Dsh_MD3\s*"
          r"else\s*R\.style\.Theme_Dsh\s*\)",
          bad_sample,
      ) is None)

print()
if fail:
    print("FAILED: %d check(s)" % len(fail))
    for f in fail:
        print("  - " + f)
    sys.exit(1)
print("OK: md3 theme dispatch checks passed (%d)" % ok)

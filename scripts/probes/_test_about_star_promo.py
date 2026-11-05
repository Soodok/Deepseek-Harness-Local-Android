#!/usr/bin/env python3
"""关于页「求 star」卡片的契约探针。

这个卡片是 v1.6.1 加的一处推广位，风险不在逻辑复杂，而在几个静默失效点：

  1. **被 dimCards 压暗**：ThemedActivity.dimCards 会把 GradientDrawable 背景
     压到 86% 不透明度。如果卡片背景写成单个 <shape>，用户一设背景图它就会变暗，
     「广告」就不显眼了。必须用 layer-list（LayerDrawable 不在那个分支里）。
  2. **不跟配色走**：布局里的 accent 是静态值。用户换成紫色配色后，卡片若仍
     显示天蓝，就是明显的不一致。代码必须在运行时按 Accent.current 重涂。
  3. **点击落点错**：必须跳仓库根（Star 按钮首屏可见），不能跳 /stargazers
     （未登录会先去登录页，登录完还得自己找按钮）。
  4. **有浏览器路径也要有兜底**：startActivity 失败要退回复制，不能静默什么都不做。

删任一处理都应立刻变红。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java/app/dsh/mobile"
fails = []
checks = 0


def check(ok, msg):
    global checks
    checks += 1
    print(("  ok    " if ok else "  FAIL  ") + msg)
    if not ok:
        fails.append(msg)


def read(p):
    p = Path(p)
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


layout = read(RES / "layout/activity_about.xml")
kotlin = read(JAVA / "AboutActivity.kt")
bg = read(RES / "drawable/bg_star_promo.xml")
action_bg = read(RES / "drawable/bg_star_action.xml")
icon = read(RES / "drawable/ic_star_white.xml")

print("== 1. 布局：卡片存在且位置合理 ==")
check('android:id="@+id/rowStar"' in layout, "关于页有 rowStar 卡片")
check('android:id="@+id/txtStarAction"' in layout, "卡片内有行动标签 txtStarAction")
check("bg_star_promo" in layout, "卡片用了专用背景 bg_star_promo")
check("ic_star_white" in layout, "卡片有星形图标")
check("about_star_title" in layout and "about_star_sub" in layout,
      "标题与副文案都绑了字符串")
check('android:clickable="true"' in layout.split('rowStar')[1].split('</LinearLayout>')[0],
      "卡片可点（clickable=true）")

# 顺序：rowTelegram → rowStar → 隐私声明（最后一个 TextView）
i_tg = layout.find('@+id/rowTelegram')
i_star = layout.find('@+id/rowStar')
i_priv = layout.find('about_privacy_note')
check(i_tg > 0 and i_star > i_tg, "rowStar 排在 Telegram 之后")
check(i_priv > i_star, "rowStar 排在隐私声明之前（不被挤到页面末尾）")

print("== 2. 背景必须是 layer-list（躲开 dimCards 压暗）==")
check("<layer-list" in bg, "bg_star_promo 是 layer-list")
check("<gradient" in bg, "含高光渐变层")
check(bg.count("<item>") >= 2, "至少两层（底色 + 高光）")

print("== 3. 强调色跟随配色 ==")
check("applyStarPromoAccent" in kotlin, "AboutActivity 有 applyStarPromoAccent()")
check("Accent.current" in kotlin, "读当前强调色")
check("LayerDrawable" in kotlin, "运行时重建 LayerDrawable（保持 layer-list 形态）")
check("cornerRadius" in kotlin, "重建时保留圆角")
check("setTextColor" in kotlin, "行动标签文字也按强调色重涂")
# 必须在 onCreate 里真的被调用，不能只是定义了函数。
# 锚定「行首（仅缩进）+ 调用」：注释掉的 `// applyStarPromoAccent()` 不会命中，
# 否则把调用注掉探针还绿着（反证时踩过）。
check(re.search(r"^\s*applyStarPromoAccent\(\)\s*$",
                kotlin.split("private fun applyStarPromoAccent")[0], re.M) is not None,
      "applyStarPromoAccent() 在 onCreate 里被调用（不只是定义）")

print("== 4. 点击落点 ==")
m = re.search(r'R\.id\.rowStar\)\.setOnClickListener\s*\{(.*?)\n        \}', kotlin, re.S)
blk = m.group(1) if m else ""
check(blk != "", "rowStar 挂了点击监听")
check("ACTION_VIEW" in blk, "用 ACTION_VIEW 打开")
check("RepoConfig.REPO" in blk, "用 RepoConfig.REPO 拼地址（fork 构建跟着变）")
check("/stargazers" not in blk, "不跳 /stargazers（未登录会被踢去登录页）")
check("runCatching" in blk, "startActivity 包了 runCatching")
check("setPrimaryClip" in blk, "打开失败时有复制兜底")

print("== 5. 五语种文案齐全 ==")
for d in ("values", "values-zh-rCN", "values-de", "values-th", "values-pt-rBR"):
    s = read(RES / d / "strings.xml")
    for key in ("about_star_title", "about_star_sub", "about_star_action"):
        check(f'name="{key}"' in s, f"{d}: {key} 存在")

print("== 6. 资源可解析 ==")
check("<vector" in icon, "星形图标是 vector")
check('android:fillColor="#FFFFFFFF"' in icon, "星形图标是白色（压在 accent 底上）")
check("<shape" in action_bg, "行动标签底是 shape")
check('android:color="#FFFFFFFF"' in action_bg, "行动标签底是白色")

print()
if fails:
    print(f"{len(fails)} FAILED / {checks} checks")
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print(f"All about-page star promo checks passed ({checks} checks).")

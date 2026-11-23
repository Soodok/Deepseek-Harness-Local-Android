## 评审结论：已合并（经修正）—— 感谢这份高质量的贡献 🎉

我把你的分支拉到本地**实际编译 + 装机实测**了一遍，确认了它的价值，也发现了两个需要修正的地方。最终以修正版合并进 main（v1.2.48）。

### ✅ 采纳的部分

1. **中英双语资源架构**（英文为默认 `values/`、中文 `values-zh-rCN/`）—— 这个划分是对的：非中文系统会落到英文而不是中文。实测双向切换正常（英文系统 → 全英文；中文系统 → 全中文）。
2. **扩展目录双语**（`catalog.json` + `catalog.zh-rCN.json`）—— 按 locale 选择的实现没问题。
3. **`scripts/check-i18n.py` + `i18n-check.yml`** —— 这份校验脚本质量很高（XML 合法性、默认语言 CJK 泄漏、双语占位符对齐、`R.string` 引用可解析）。我扩充了它的白名单机制：`AgentContextSeed.kt` 里故意保留的双语示例文案需要文件级豁免（否则整段模板会被当成一个字面量误报）。
4. **`android-build.yml` 增加 `pull_request` 触发** —— 有用。
5. **⭐ 横竖屏修复** —— 这条价值最大，而且你在 PR 描述里没怎么强调：
   - `SCREEN_ORIENTATION_UNSPECIFIED` 确实**不是**锁竖屏（我核对了 main 分支的代码，确认是真 bug）
   - Onboarding 把方向设在正在 `finish()` 的 Activity 上、异步派发被丢弃 —— 也是真 bug
   - 修复方案（抽 `applyOrientation()` + 由 MainActivity 统一应用）我原样采纳了。
6. **用户可见文案外移到资源** —— 采纳（启动失败原因、通知标题与正文等）。

### 🔧 我修正的部分

**① `AgentContextSeed` 的 `scr tap-text` 指令不能写死语言**

原改动把种子里的点击指令改成了英文：

- `scr tap-text "重启"` → `scr tap-text "Restart"`
- `scr tap-text "允许"` → `scr tap-text "Allow"`

这会带来两个回归（你的 PR 描述里也担心过 automation would silently break，但方向反了）：

- **中文界面的用户**让 AI 自动重启引擎时，AI 按 `"Restart"` 找不到按钮（界面上是「重启」）→ 静默失败
- 那个「允许」是**系统权限弹窗**，文案由**系统语言**决定、与 App 语言无关 —— 中文系统上永远是「允许」，写死 `"Allow"` 必然失败

我改成了**语言无关**的写法：要求 AI 先 `scr dump` 看清屏幕上的实际文案再点，并明确列出两种语言的候选值。`SEED_VERSION` 相应递增（10 → 11，含我另加的 LAN access 章节）。

**② README 语言翻转回退了**

你把主 `README.md` 翻成英文、中文移到 `README.zh-CN.md` —— 考虑到项目的主要用户群与搜索收录，主文档保持中文更合适。已回退为：`README.md`（中文）+ `README_EN.md`（英文）+ `README.de.md`（德语，**已采纳你的版本** ✓），三份互相导航。

### 📌 顺带纠正两处描述

- PR 描述里说"这个仓库没有 Android SDK 也没有 gradle wrapper，所以本地无法构建" —— 实测**可以构建**：用系统装的 gradle 直接 `assembleDebug` 即可（我这台机器 1 分钟出包）。加上你的 `pull_request` 触发，CI 也能覆盖了。
- 你提到的 `docs/android-agent-routes.md` / `.zh.md` 坏链 —— **确实存在** ✓，原因是那两个文件当时只在本地、未推送到仓库。已推送修复。

### 关于 `accessibility_desc` 的说明文案

你在 review notes 里指出「无障碍服务描述写着"不读取屏幕内容"，但实际 `dumpScreenJson()` 会读屏」—— 这个观察是对的 ✓，我记下来了，会在后续版本单独处理（属于产品文案问题，不该混在 i18n PR 里改）。

再次感谢！这份 PR 的 i18n 架构和那个 CI 校验脚本都很有价值，横竖屏那条更是顺手修掉了一个一直没被发现的老 bug 🙏

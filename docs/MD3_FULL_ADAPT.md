# MD3 全量适配 · 划分与实施计划（v1.8.0）

> 目标：把 Material 3 从「设置体系 18 页」推到全站，并加一个**风格切换**
> （经典冷蓝灰 ↔ 暖灰青墨），切换后每一页都必须整体一致，不允许出现
> 「图标冷、卡片暖」的花脸。

## 一、现状实测（2026-11-14）

### 1.1 主题分派
`app/src/main/java/app/dsh/mobile/ThemedActivity.kt:217`
```kotlin
setTheme(if (usesSettingsBackground) R.style.Theme_Dsh_MD3 else R.style.Theme_Dsh)
```
`usesSettingsBackground = true` 的 Activity **18 个**（另有 6 个继承 ThemedActivity
但没开：MainActivity / FileEditorActivity / LinuxActivity / OnboardingActivity /
TerminalActivity / VoiceServiceActivity；LogViewerActivity、ShareInboxActivity 根本
不继承 ThemedActivity）。

### 1.2 两套主题**视觉等价**（关键发现）
`app/src/main/res/values/themes.xml` 里 Theme.Dsh（28 个 item）与
Theme.Dsh.MD3（27 个 item）逐项比对，**唯一差异是 Theme.Dsh 多
`colorOnSurfaceInverse`，没有任何同名 item 取不同值**。
→ 也就是说：作者搭好了 MD3 的**结构**，但把语义色全部回映射到旧色板，
两套主题画出来一模一样。所谓「切换风格」现在切了等于没切。

### 1.3 阻塞点：@color 是静态的
| 位置 | `@color/` | `?attr/` |
|---|---|---|
| layout 29 文件 | **267** | 639 |
| drawable 137 文件 | **174** | 13 |
| 合计 | **441** | 652 |

其中**核心语义色占 403 处（91.4%）**：

| 色名 | 布局 | drawable | 合计 |
|---|---|---|---|
| icon_color | 0 | 101 | 101 |
| accent | 63 | 7 | 70 |
| island_text | 35 | 0 | 35 |
| stroke | 23 | 9 | 32 |
| surface | 24 | 6 | 30 |
| text_secondary | 24 | 6 | 30 |
| text_muted | 25 | 0 | 25 |
| text_primary | 22 | 0 | 22 |
| bg | 14 | 0 | 14 |
| divider / danger / on_accent / btn_ripple / warning / surface_high / stroke_glass / surface_alt / accent_soft / dialog_bg / success / overlay / island_accent | 余下 | | 38 |

剩下 38 处是**功能专属色**（splash_* 15、island_* 8、ext_chip_* 4、
onboarding_* 3、action_* 2、surface_glass 2 等），它们本来就该固定，不动。

`@color` 是资源解析期定型的 —— 换主题**改不到它**。这就是不能只靠
「多写一套主题」完成全量适配的根本原因。

### 1.4 其它量化
- 代码 `getColor(R.color.*)` **173 处**（OnboardingActivity 34、PetSettingsActivity 32、
  LinuxActivity 25、PluginStoreActivity 23、ExtensionStoreActivity 16…）
- 写死 `0xFF` 常量 **88 处 / 22 文件**（IslandChrome 23、Accent 10、AccentTint 6、
  StatusOverlay 6、TerminalActivity 5、OverlayState 5、PointMarker 5…）
- vector drawable **116 个**，其中 96 个 `fillColor="@color/icon_color"`
- **已验证**：`?attr/` 可以写在 vector 的 `fillColor` 与 shape 的 `solid/color` 里
  （`gradle :app:mergeReleaseResources` EXIT=0，探针文件已删）

## 二、边界：哪些「没适配」

原作者（PR #97）改了 21 个 layout。当前 33 个 layout 里，**还没 MD3 化的**：

| layout | 所属 | 处置 |
|---|---|---|
| activity_voice_service | 语音服务（设置→无障碍可达） | **整屏重写 + 开 MD3** |
| activity_user_prompt | 自定义提示词（设置可达） | **整屏重写 + 开 MD3** |
| activity_file_editor | 文件编辑（备份页可达） | **整屏重写 + 开 MD3** |
| activity_log_viewer | 日志查看（维护页可达） | **整屏重写 + 开 MD3** |
| activity_permission_list | 权限清单行（已 MD3 但行模板没换） | **重做 item_permission** |
| activity_memory_panel | 记忆面板（MD3 但无顶栏/无 maxWidth） | **补骨架** |
| activity_memory_detail | 记忆详情（无 bg_md3_*） | **补卡片** |
| activity_extension_store | 扩展中心（70 行壳，19 处 addView 在代码里） | **代码侧改** |
| activity_plugin_store | 插件市场（13 处 addView） | **代码侧改** |
| activity_settings_bg | 背景设置（只有 bg_md3_input） | **补分段** |
| activity_main / activity_terminal / activity_onboarding / activity_pet / overlay_status / voice_input_panel | 非设置体系 | **不动骨架，只跟着 attr 走** |

## 三、方案：三层

### 第 0 层 · 风格令牌（新建）
新增 `app/src/main/res/values/attrs_style.xml`，定义 21 个 `dsh*` 主题属性：
`dshBg / dshSurface / dshSurfaceAlt / dshSurfaceHigh / dshDialogBg /
dshTextPrimary / dshTextSecondary / dshTextMuted /
dshAccent / dshAccentSoft / dshOnAccent /
dshDivider / dshStroke / dshDanger / dshSuccess / dshWarning /
dshIconColor / dshBtnRipple / dshOverlay / dshIslandText / dshIslandAccent`

两套主题各给一套值：
- **Theme.Dsh（经典）**：现有冷蓝灰（bg #F5F7FA / text #0F172A / accent #0284C7）
- **Theme.Dsh.MD3（材料 3）**：md3_* 暖灰青墨（#F7F6F5 / #1A1C1D / #1B6C8C）

前缀 `dsh` 是必须的：不带前缀会和 Material 的 `colorXxx` 撞名，
aapt2 报错会指向错误位置。

### 第 1 层 · 静态色 → 主题属性（机械替换）
按**显式映射表**把 layout/drawable 里的 `@color/X` 换成 `?attr/dshY`：

| @color | → ?attr |
|---|---|
| bg | dshBg |
| surface | dshSurface |
| surface_alt | dshSurfaceAlt |
| surface_high | dshSurfaceHigh |
| dialog_bg | dshDialogBg |
| text_primary | dshTextPrimary |
| text_secondary | dshTextSecondary |
| text_muted | dshTextMuted |
| accent | dshAccent |
| accent_soft | dshAccentSoft |
| on_accent | dshOnAccent |
| divider | dshDivider |
| stroke | dshStroke |
| danger | dshDanger |
| success | dshSuccess |
| warning | dshWarning |
| icon_color | dshIconColor |
| btn_ripple | dshBtnRipple |
| overlay | dshOverlay |
| island_text | dshIslandText |
| island_accent | dshIslandAccent |

**排除清单（绝不能 attr 化）**——这些在没有 Activity 主题的上下文里取图：
- `ic_download.xml`（DownloadNotifier）、`ic_health.xml`（SensitiveAccess）、
  `ic_pet.xml`（PetOverlayService / PetPackDownloadService）
  —— 通知栏 `setSmallIcon` 用的，必须静态
- `splash_*` 全部（启动闪屏，主题尚未生效）
- `bg_dynamic_island_dot` / `bg_overlay_*` / `ic_island_*`（WindowManager 浮层，
  Service 上下文，主题属性解析到 application theme 会拿错值）

### 第 2 层 · 补齐未适配页面 + 风格切换入口
- 上表 7 个页面按 `activity_settings_a11y.xml` 的骨架重写
  （`MaxWidthLinearLayout` + `@dimen/md3_topbar_height` 顶栏 + `bg_md3_group`
  + `bg_md3_seg_top/mid/bottom` 分段 + `?attr/textAppearance*` 字级 + `?attr/colorOnSurface*`）
- `ThemeActivity` 加「风格」分组：经典 / 材料 3（写 `StylePref`）
- `ThemedActivity.setTheme` 改成
  `if (usesSettingsBackground && style == MATERIAL) MD3 else Dsh`

## 四、验收（多轮）
1. **静态**：`mergeReleaseResources` 通过；`check-i18n.py`；
   JVM 断言（新增 `assertStyleTokens`：每个 `?attr/dsh*` 必须两套主题都定义、
   排除清单不得含 `?attr/`）
2. **变异**：删掉 MD3 主题里一个 `dsh*` item → 断言必须红；
   给 `ic_download` 塞 `?attr/` → 断言必须红
3. **构建**：隔离 worktree `assembleRelease`
4. **真机**：OnePlus PLZ110 / Android 16，逐页截图两套风格对比，
   量测同一像素点的色值确认切换生效

## 五、不做
- 不合并 PR #102 的整体做法（删 `ThemeAccent.Mode`、删 `setTheme`）
- 不改 island/overlay/splash/terminal 的固定配色（它们浮在别的 App 上或先于主题）
- 不动圆角半径（49 处写死 radius，与风格切换无关，单列）

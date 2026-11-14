# Material 3 官方规范（本仓库的强制依据）

本目录包含从 Google 官方渠道保存的参考材料；本 README 是项目维护说明，
不是 Google 官方规范。内容随库版本变化，实际实现应以链接的官方文档和源码为准。

抓取来源：

- Material Components for Android 官方文档仓库
  `https://github.com/material-components/material-components-android/tree/master/docs`
  （`getting-started` / `theming` / `theming/Color|Typography|Shape|Motion` / `components/*`）
- Android 开发者官网「动态取色」
  `https://developer.android.com/develop/ui/views/theming/dynamic-colors`

> 重新抓取：`node tools/fetch-md3-docs.mjs`（脚本见仓库 `tools/`）。

---

## 一、硬性规则（不可协商）

1. **只用官方组件。** 每一处 UI 都必须是 `com.google.android.material.*` 里的控件；
   不允许自绘卡片、自绘圆角、自绘按钮、自绘输入框、自绘开关。
   官方没有提供「设置行」这类复合控件时，用
   `MaterialCardView` + 行容器 + `MaterialDivider` 组合——这是官方文档里的做法，
   不是「自己拼」。

2. **颜色只用主题属性。** 代码与布局里只写 `?attr/colorPrimary` /
   `?attr/colorSurface` / `?attr/colorOnSurfaceVariant` 这类语义属性，
   不写死 `#RRGGBB`，也不直接引用 `@color/xxx`（设计令牌除外，且令牌本身要映射进主题）。

3. **系统动态取色走官方 API。** 本项目在 Application 中注册：
   在 `Application.onCreate()` 里调用
   `DynamicColors.applyToActivitiesIfAvailable(application, options)`，
   由 `onActivityPreCreated` 套用覆盖层。
   Activity 不应在动态色覆盖层之后重置主题，否则可能抹掉颜色。
   Google 的 `setContentBasedSource` 是官方支持的内容色 API，但它不是系统壁纸 Monet，
   且 1.14.0 的公开 DynamicColors API 在 Android 12 以下不可用。本项目为兼容
   API 26+，用官方 Material Color Utilities 预生成浅/深色预设 overlay。

4. **形状与字号用令牌。** 圆角走 `shapeAppearanceCorner*`，字号走
   `?attr/textAppearance*`（DisplayLarge…LabelSmall）。不手写 `textSize="15sp"`。

5. **深浅色靠资源限定符。** 只维护一份 `values/themes.xml`，parent 用
   `Theme.Material3.DayNight.*`；深色差异放 `values-night/colors.xml`。
   禁止再造 `Theme.Xxx.MD3` 这类副本主题（副本必然漏映射色阶）。

6. **可点击元素必须有 MD3 反馈。** `?attr/selectableItemBackground` 或
   组件自带 ripple；不允许出现点了没反应的「死控件」。

7. **对话框用 `MaterialAlertDialogBuilder`**，不使用平台 `AlertDialog.Builder`。

8. **完整性优先。** 一个页面要么整体按规范做完，要么不要动；
   不允许「一半原生一半自绘」的混合状态。

---

## 二、本仓库的落地对照表

| 场景 | 必须使用 | 官方文档 |
|---|---|---|
| 页面顶栏 | `MaterialToolbar`（navigationIcon + title） | `components/TopAppBar.md` |
| 分组容器 | `MaterialCardView`（`?attr/colorSurfaceContainer`、`shapeAppearanceCornerLarge`） | `components/Card.md` |
| 行分隔 | `MaterialDivider`（`dividerInsetStart` 对齐文字起点） | `components/Divider.md` |
| 开关 | `MaterialSwitch` | `components/Switch.md` |
| 单选 | `MaterialRadioButton` + 整行点击 | `components/RadioButton.md` |
| 多选 | `MaterialCheckBox` | `components/Checkbox.md` |
| 滑杆 | `Slider`（`tickVisible=false`、`labelBehavior`） | `components/Slider.md` |
| 文本输入 | `TextInputLayout` + `TextInputEditText` | `components/TextField.md` |
| 按钮 | `MaterialButton` / `ButtonGroup`（M3E） | `components/Button.md`、`ButtonGroup.md` |
| 分段选择 | `MaterialButtonToggleGroup` / `ToggleButtonGroup` | `components/ToggleButtonGroup.md` |
| 标签 | `Chip` + `ChipGroup` | `components/Chip.md` |
| 图标容器 | `ShapeableImageView` + `shapeAppearanceOverlay` | `theming-Shape.md` |
| 图标按钮 | `MaterialButton`（icon 样式） | `components/IconButton.md` |
| 加载 | `CircularProgressIndicator` / `LoadingIndicator`（M3E） | `components/ProgressIndicator.md`、`LoadingIndicator.md` |
| 提示条 | `Snackbar` | `components/Snackbar.md` |
| 底部弹层 | `BottomSheetDialog` | `components/BottomSheet.md` |
| 对话框 | `MaterialAlertDialogBuilder` | `components/Dialog.md` |

---

## 三、动态取色（官方流程）

```kotlin
// Application.onCreate()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    DynamicColors.applyToActivitiesIfAvailable(
        this,
        DynamicColorsOptions.Builder()
            .setPrecondition { activity, _ -> /* 用户是否开启 */ true }
            .build(),
    )
}
```

配套要求：

- `Theme.*` 必须继承 `Theme.Material3.*`（或 DayNight），否则没有 `dynamicColorThemeOverlay`。
- Activity **不要** `setTheme()`；主题由 Manifest 的 `android:theme` 决定。
- 需要强制浅/深色时，改 `resources.configuration.uiMode`（`attachBaseContext`），
  不要用 `setTheme` 切换主题资源。
- Android 11 及以下没有 Monet，自动回落到主题里的静态色板。

---

## 四、检查清单（提交前自查）

- [ ] 没有自绘 `shape` 充当卡片/按钮/输入框
- [ ] 没有写死颜色；`?attr/color*` 覆盖全部取色
- [ ] 圆角来自 `shapeAppearance*`，字号来自 `textAppearance*`
- [ ] 所有可点元素有 ripple
- [ ] 深浅色都验证过（`values-night` 有对应令牌）
- [ ] 动态取色开启/关闭两种状态都验证过
- [ ] 没有 `Theme.*.MD3` 副本主题
- [ ] 没有 `app:tint`（框架 ImageView 不认）

---

## 五、常见反例（本仓库真实踩过）

| 反例 | 后果 |
|---|---|
| `Theme.Dsh.MD3` 副本主题 | 漏映射 `colorSurfaceContainer*`，卡片容器色无定义 |
| 自绘 `bg_md3_seg_*` 分段圆角 | 与 MD3 卡片规范不符，且深浅色难维护 |
| 将 `setContentBasedSource(种子色)` 宣称为系统壁纸色 | 它生成内容配色而非系统壁纸方案，且公开 API 依赖设备版本 |
| Activity 里 `setTheme()` + 动态色 | 覆盖层被冲掉，或触发重建闪烁 |
| `app:tint` 用在框架 `ImageView` | 完全不生效，图标保持原色 |
| `@color/accent_sky` 只定义了 string | AAPT 直接报错，整包构建失败 |

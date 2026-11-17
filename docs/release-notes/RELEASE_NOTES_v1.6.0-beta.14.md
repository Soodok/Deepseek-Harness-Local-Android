# DSH Mobile v1.6.0-beta.14 — 攒了 50 个提交的一次试发行

> **发版形态**：试发行（versionCode **390**，Release 标 `prerelease: true`，不进 `releases/latest`，
> 正式版用户不会被动升级）。上一个 tag 是 `v1.6.0-beta.1`（377），之后已合入 **50 个提交**；
> 正式版说明仍以 [RELEASE_NOTES_v1.6.0.md](RELEASE_NOTES_v1.6.0.md) 为底稿，本文只补新进包的内容。

## 1. Linux 工作台「点开始画面出不来」—— 三处真因，四条链路重写

用户反馈点了开始，容器起来了但画面永远是黑的。拆开是三件独立的事叠在一起：

- **`pkill -f` 自杀**：停旧进程用的 `pkill -f framesrv` 会匹配到自己这条命令行（`sh -c` 里也含该串），
  于是刚起来的服务立刻被自己杀掉。改成**行首锚定 + pidfile**，只停真属于我们的进程。
- **Xvfb 写死 `:99`**：上一次异常退出留下的 `/tmp/.X99-lock` 会让新的 Xvfb 直接失败，
  之后所有截图都是黑的。改成 **99..119 现挑可用 display 号**。
- **`-nolock` 非 root 无效**：容器里不是 root，这个参数不生效，锁文件照样建。
- **假成功**：以前只看进程活着就报「已启动」。现在**等真实帧落地**才算成功；display 号写进 bind 目录，
  供 inputd 复用（`/data/data/<pkg>/files` 两边都可见，不能放容器内 /tmp —— 它有时是 tmpfs）。

配套修复：装完包后自愈「包在但文件不可用」（补 x 位 + 交还头文件目录属主）；容器里 C 编译跑不起来的两层根因。

## 2. MD3 —— 从「只改颜色」补上形态分叉

上一版用户一眼看出问题：「这 MD3 不就改了个颜色吗？」确实如此 —— 两套主题逐项比对，
取值不同的 item **全是颜色**，shape / corner / elevation / textAppearance 两套都是 0 个，
圆角全部写死在布局与 drawable 里。

- 新增 **7 个 dimension 令牌**（`dshCardRadius` / `dshCardStroke` / `dshIconChipRadius` /
  `dshIconChipStroke` / `dshButtonRadius` / `dshControlRadius` / `dshGridCellRadius`），
  经典风格取现有原值（一个像素不改），MD3 是 24dp 大圆角 + 无描边 + 正圆图标底 + 全圆胶囊按钮。
- 令牌化 7 个布局 + 9 个 drawable。
- 补上 `materialAlertDialogTheme`：以前全仓从没设过，11 处弹窗吃 Material 默认主题。

## 3. 弹窗「先上面闪一下再归位」—— Material 的背景 inset

`MaterialAlertDialogBuilder.create()` 会用 `MaterialDialogs.insetDrawable(background, backgroundInsets)`
把窗口背景包成 InsetDrawable（MaterialComponents 默认上下 80dp / 左右 24dp）；
而 `showMd3()` 在 `setOnShowListener` 里又 `setBackgroundDrawableResource`，把 inset 丢掉 →
面板从「窗口内缩一圈」变成「整个窗口」，首帧后突然变大。

修法：新增 `values/md3_dialog_theme.xml`（Material3 parent，四边 inset 统一 24dp，圆角走 `dshCardRadius`），
**全仓 12 处「show 之后换背景」全部删除**，首帧即终态。

## 4. 动态取色闪屏 —— 是两次 relaunch，不是重建闭环

用户报「点一下直接闪屏」。`adb logcat -b events` 抓到单次点击出现 **2 次** `handleRelaunchActivity`；
插桩确认页面自己只 `recreate()` 了一次（闭环那个更早就修好了）。

第二次来自 `DynamicColors.applyToActivityIfAvailable`：它在 API 31+ 走 `ColorResourcesOverride` →
`ResourcesLoader` 替换颜色资源，**改掉了本 Activity 的 Resources 配置**，系统随即再派一次 configuration change。

- 不再注入 `DynamicColors`（不是挪位置，是彻底不注入）；
- 新增 `SystemAccent.kt` 直读 `android.R.color.system_accent1_200`（深）/ `_600`（浅）；
- 动态开关回调 `recreate()` → `reapaintAccentLive()`（就地铺色 + 刷摘要 + 刷 chip）。

实测（Android 14）：PRESET↔DYNAMIC 双向 `relaunch=0` / `onCreate=0`，连点 8 次仍 0，无崩溃。

## 5. 首次启动跟随系统语言（#109，@xiaowu8848）

没写过语言偏好时回落系统语言，之后一律以偏好为准；不支持的语种与旧值仍收敛到 `en`。

## 6. 其他

- **插件安装**：坏 lockfile 自动修复；失败按钮不再只剩一串路径。
- **引导页**：九页向导落地（封面 + 八步），运行状态胶囊移到进度行。
- **灵动岛**：收起态「展开两次」修复；侧边面板边缘投影。
- **扩展中心**：新增 Bun 运行时（58 项）；启动清理不再删掉正在解包的 `.tmp-install`。
- **任务化执行**：`/dev/jobs` —— 提交 → 轮询 → 取产物。
- **DNS 代理**：跑久后静默失效 —— 有界转发池 + 看门狗自愈。

## 7. 已知限制

- `gate-memory.sh static` 仍有 5 个 py 探针红项（`_test_linux_capture` / `_test_linux_frame_surface` /
  `_test_pkill_selfmatch` / 等），已确认在纯净基线上同样红，属既有问题，非本版引入。
- 逐页两套风格的视觉对比只在外观页与主题页做过，其余页面未逐页截图核对。

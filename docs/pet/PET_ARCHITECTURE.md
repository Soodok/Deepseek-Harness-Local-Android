# 桌宠：架构与内部逻辑

> 面向维护者。代码在 `app/src/main/java/app/dsh/mobile/pet/`（23 个 Kotlin 文件，约 7800 行），
> 资源与字符串在 `app/src/main/assets/pet/`、`app/src/main/res/values*/strings.xml`。
>
> 安装与使用见 [PET_GUIDE.md](PET_GUIDE.md)，功能现状与待办见 [PET_ROADMAP.md](PET_ROADMAP.md)。

## 1. 分层总览

| 层 | 职责 | 主要类 |
|---|---|---|
| 入口与装配 | 权限、门禁、Service 生命周期、窗口挂载 | `PetSettingsActivity`、`PetOverlayService` |
| 桥接 | 回环 HTTP 接收、token 鉴权、五字段解析 | `PetBridgeServer`、`PetProtocol` |
| 决策 | 事件 → 状态 → 该播哪一段 | `PetActionController`、`PetAnimChain`、`PetAnimPicker` |
| 表现 | 解码、泵帧、放大上屏、气泡、位移与物理 | `PetView`、`PetBubbleDrawable`、`PetMovePlanner`、`PetPhysics` |
| 资源 | 多包目录、导入校验、下载、配置解析 | `PetPack`、`PetPackInstaller`、`PetPackIndex`、`PetPackDownloader` |
| 周边 | 偏好、状态总线、插件探测、台词库、菜单、音效 | `PetPrefs`、`PetStatus`、`PetPluginDetector`、`PetLines`、`PetChatter`、`PetActionMenu` |

模块清单（行数为 2026-10-27 main 分支实测，改动后请顺手更新）：

| 文件 | 行数 | 职责 |
|---|---|---|
| `PetOverlayService.kt` | 1579 | 前台服务、悬浮窗与气泡窗口、漫游/贴边/甩抛、通知、门禁收口 |
| `PetView.kt` | 1226 | 动画解码与上屏、泵帧、缓存、角色矩形测量、卡帧自愈 |
| `PetSettingsActivity.kt` | 921 | 设置页：开关、权限、插件、包管理、尺寸、音效、台词入口 |
| `PetPackInstaller.kt` | 479 | zip 校验、原子安装、旧包迁移、半成品巡检 |
| `PetPack.kt` | 398 | `pet-config.json` 解析、已装包枚举、目录维护 |
| `PetLinesActivity.kt` | 350 | 台词库编辑器（分类、导入导出、恢复内置） |
| `PetAnimChain.kt` | 328 | 随机链 / 工作态 / 一次性动作的推进 |
| `PetActionMenu.kt` | 311 | 长按菜单（主菜单 + 分类子菜单） |
| `PetBridgeServer.kt` | 282 | 单线程 accept + 2 worker 的极简 HTTP |
| `PetActionController.kt` | 251 | 事件语义 → 表情/气泡/工作槽位 |
| `PetBubbleDrawable.kt` | 217 | 单 Path 气泡底板 + 柔光投影 |
| `PetPackDownloader.kt` | 187 | 分卷下载、Range 续传、sha256 |
| `PetLines.kt` | 187 | 台词读写、内置兜底、分类定义 |
| `PetPrefs.kt` | 171 | 偏好键、包目录约定、安全 id |
| `PetChatter.kt` | 158 | 碎碎念触发条件与冷却 |
| `PetPluginDetector.kt` | 154 | 桥插件安装/启用/版本探测与门禁判定 |
| `PetPackIndex.kt` | 127 | `index.json` 拉取与相对地址解析 |
| `PetProtocol.kt` | 121 | 协议常量、五字段解析与清洗 |
| `PetAnimPicker.kt` | 111 | 加权掷骰、防重复、noMirror 过滤 |
| `PetMovePlanner.kt` | 100 | move 分支的位移时间轴插值 |
| `PetPhysics.kt` | 82 | 甩抛物理（重力/回弹/摩擦/Q 弹） |
| `PetStatus.kt` | 42 | 进程内状态总线（StateFlow） |

## 2. 依赖的外部插件

唯一的外部依赖是 **`@xiaopb/dsh-pet-bridge` ≥ 0.3.1**（纯 JS，装在 web profile 里）。
原生侧不调用 dsh 的任何 API，只收它发来的 HTTP 事件。

**为什么必须 0.3.1**

| 原因 | 细节 |
|---|---|
| token 鉴权 | 协议 v2 起 `/bubble` 强制 `X-DSH-Pet-Token`，缺失/不匹配一律 403；token 支持从 0.3.1 才有 |
| `Content-Length` | 上游 0.1.0 POST 时走 chunked 编码，不带 `Content-Length`；原生侧按定长读 body，必然回 400 |
| 包名归属 | npm 上无 scope 的 `dsh-pet-bridge` 只到 0.1.0，归上游作者，无法发新版，装上一定不通 |

**探测口径**（`PetPluginDetector.detect`）：读 `profiles/web/package.json`，
从 `dependencies`（或 `node_modules/<pkg>` 目录）认包名，从 `dsh.profile.bundles` 认是否启用，
从 `node_modules/<pkg>/package.json` 读版本。三者都满足才算 `bridgeReady`：

```
bridgeReady = bridgeInstalled && bridgeEnabled && version >= 0.3.1
```

官方 scoped 包优先于遗留包名；同时存在时以 `@xiaopb/dsh-pet-bridge` 为准。
版本解析失败按不达标处理（fail-closed）。

## 3. 端到端事件链路

```
dsh-pet-bridge（引擎进程内）
   │ hook: turn/start · tool/call · tool/result · turn/end · agent/disposed
   ▼
POST http://127.0.0.1:7779/bubble        X-DSH-Pet-Token: <每进程随机>
   │ 五字段 JSON，≤8KB
   ▼
PetBridgeServer ── 403/400/413 ──▶ 直接返回，不改状态
   │ 解析成功
   ▼
PetActionController.onEvent(kind)
   ├─ 工作槽位 ──▶ PetAnimChain.setWorkStatus(slot)
   ├─ 气泡文本 ──▶ PetOverlayService.showWorkBubble() / showBubble()
   └─ 状态总线 ──▶ PetStatusBus（设置页与通知读它）
   ▼
PetAnimChain（决定播哪一段、播完去哪）──▶ PetView.play(slug, loop)
   ▼
PetView：缓存命中直接上屏 / 未命中解码 → 泵帧 → 按角色矩形放大绘制
```

`stop` 有 **1.5s 宽限**，避免多 agent 同时收尾时抢掉仍在工作的会话；
`PetActionController.onTick` 由服务每 30s 调一次，引擎不在或超过 15 分钟无事件就回待机。

## 4. 桥接协议

请求体就是五字段，**没有气泡文本**——气泡由原生侧根据 `kind + tool_name + tool_input` 拼：

```json
{
  "kind": "user|pre|post|stop|error|waiting",
  "agent_source": "dsh",
  "tool_name": "read",
  "tool_input": { "file_path": "README.md" },
  "caller_pid": 12345
}
```

| 约束 | 值 |
|---|---|
| 监听地址 | `127.0.0.1:7779`（只绑回环，不暴露局域网） |
| 路由 | `GET /ping`（免鉴权，只报存在性）、`POST /bubble`（必须带 token） |
| 鉴权 | `X-DSH-Pet-Token` 等于本进程 token；fail-closed，缺失/不匹配 403 |
| 限额 | body ≤ 8KB、header ≤ 8KB、5s 超时、2 个 worker、单线程 accept |
| 字段清洗 | `tool_name` ≤ 64 字符；摘要 ≤ 32 字符；`tool_input` 只认 `file_path`/`command`/`pattern`/`query` 四个键 |
| 协议版本 | `/ping` 返回 `protocol: 2`；1 表示旧的、无 token 的 APK |

token 由 `PetBridgeServer` 每进程惰性生成一次，**不落盘、不复用**，经引擎环境变量
`DSH_PET_BRIDGE_TOKEN`（`EngineConfig` 注入）只交给引擎内部的桥插件——同设备第三方 App 拿不到，
伪造表情/气泡的通道被关闭。

## 5. 状态机与动画链

`PetAnimChain` 是桌面版 `pickNext` + `handleEnded` 的 Kotlin 移植，三种模式：

| Mode | 何时进入 | 播完去哪 |
|---|---|---|
| `RANDOM` | 空闲 | 按权重掷骰：idle 10 / turn 5 / move 5 / 分类动作 80 |
| `WORK` | 收到工作事件 | 非终态轮换或常驻循环；终态播一遍后回随机链 |
| `ONCE` | 点击回应、菜单点播、拖拽 | 先尝试恢复工作动画，否则回随机链 |

工作槽位 6 档（`WorkSlot`），终态与非终态语义不同：

| 槽位 | 终态 | 行为 |
|---|---|---|
| 思考 / 工作 / 整理 / 等待 | 否 | 多候选循环轮换，单候选常驻循环；不被随机链抢走 |
| 成功 / 出错 | 是 | 播一遍就走，回随机链 |

三条容易改坏的语义（改动前先读类头注释）：
1. 选段一律避开正在播的那一段，否则会出现「同一段反复重播」；
2. `turn` 动画播完立刻翻转朝向，并**立即同步**给后续选取；
3. `noMirror` 分类（如「文字」）在已镜像时不参与随机链。

漫游、贴边、气泡所有时序都读同一份「忙」判定：`PetAnimChain.isBusy`（一次性动画在播）或
`PetActionController.isTurnActive`（引擎回合中）为真时让路。

## 6. 渲染与播放

播放链分两层：`PetAnimChain` 决定「现在播什么、播完接着播什么」，`PetView` 负责解码与上屏。

**不依赖 drawable 自调度。** `AnimatedImageDrawable` 通过 `Drawable.Callback` 请求重绘，这条自调度
偶发丢一次就永久停住（`isRunning` 仍为 true），且 `View.verifyDrawable()` 默认不认非背景/前景的
drawable，`invalidateDrawable` 会被整个丢掉。现在：重写 `verifyDrawable()` 接受动画槽位，
并由 ticker 按 **41ms（素材帧率）主动泵帧**。

**一次性动画只播一遍。** `repeatCount` 是「重复次数」：写 `1` 等于播完再重播一轮（共两遍），
而链按清单时长推进 → 动作被截断。一次性统一写 `0`，定时器与 `pet-config.json` 段时长对齐
（夹在 0.4s–30s），播完**停在最后一帧**再交回链。

**失败与卡帧都有人管**

| 情况 | 处理 |
|---|---|
| 四类失败（`unavailable` / `no_asset` / 解码失败 ×2） | `onPlaybackError` 进日志 + 状态总线，服务侧同 slug 5s 限流 |
| 解码失败 | **保留旧帧**不清屏，另发 400ms 跳过信号让链继续推进 |
| 连续解码失败 5 次 | 熔断停链，不再 400ms 一跳地空转 |
| 卡帧 | 每 500ms 查「是否 2s 没有新帧」，命中记 `stalled` 并自愈：循环停摆走 `restart()`，泵帧断链重挂 ticker；同段最多救 2 次 |

看门狗判据是「**有没有在重绘**」，不是「画面有没有变化」。素材若带 `blend=1/dispose=0`
（libwebp 默认），静止旧帧会被一直重画成越来越脏的残影——那是资源产物问题，见 §12。

**放大上屏。** 素材画布只有 300px（480p 包 480px），角色在素材里约 120px 宽，窗口常 600px+，
即放大 5 倍。`AnimatedImageDrawable` / `Movie` 自己按目标 bounds 绘制时用内部 Paint（没开位图过滤），
结果是沿轮廓一圈硬台阶。现在先把每帧画进「素材原始尺寸」的离屏位图，再用自建双线性过滤 Paint 放大
（顺带把交叉淡入的 `saveLayerAlpha` 换成 Paint alpha，每帧少一个全尺寸图层）。
代价是边缘柔和——5 倍放大本无细节可还原。

### 6.1 设计动因：三个踩过的坑

| 症状 | 根因 | 现在的防线 |
|---|---|---|
| 动作播两遍 / 被截断 | `repeatCount = 1` 被当成「播一遍」 | 一次性写 `0`；定时器与段时长都夹 0.4–30s |
| 画面彻底不动，日志无异常 | drawable 自调度丢一次 + `verifyDrawable` 不认槽位 | 41ms 主动泵帧 + 重写 `verifyDrawable` + 2s 卡帧看门狗 |
| 越播越脏、身后黑雾 | 素材帧 `blend=1`，帧间叠加累积 | `scripts/probes/fix_webp_blend.py` 就地改帧标志；`inspect_webp_anim.py` / `check_webp_ghost.py` 定位 |

## 7. 窗口几何与触摸

素材是整幅 16:9 视频，角色只占中间一块（300p 包实测左右各约 30%、上 15%、下 6% 透明）。
悬浮窗按**量出来的角色矩形**开，不再按整幅画布开——透明像素不吃触摸，角色也能真正贴到屏幕边缘。

量法：每段动画第一次上屏时画进 160px 小图求 alpha 包围盒（alpha < 24 视为透明，压掉有损 WebP
在透明区的噪声），取所有播过段落的并集，左右再取镜像并集，各边留 2% 余量，按包 id 缓存
（`PetPrefs.packInsets`），下次开服务直接用。量不出来时维持整幅画布，行为与老版本一致。

并集变化超过 0.2% 才重排窗口，避免每段都抖动。

**残留限制**：窗口仍是矩形，角色轮廓以内的透明角（比如头顶两侧凹处）仍算窗口区域；
这是 overlay 输入模型的限制，除非用私有 API（`TOUCHABLE_INSETS_REGION`）否则无法按轮廓裁。

## 8. 气泡

- **底板** `PetBubbleDrawable`：一条闭合 Path（圆角矩形 + 底部尾巴）一次填充、一次描边；
  圆角按单行正文高度收敛成胶囊；`setShadowLayer` 柔光投影（该窗口走软件层渲染，硬件画布会忽略模糊）。
- **尾巴**指向角色中线；气泡被屏幕边缘夹偏时尾巴自己跟着挪，始终「指着头顶说话」。
- **度量**：字号 = 角色宽 × 0.075，夹在 11–19sp；内边距/圆角/尾巴都是 em 口径，字号一变整套同步缩放。
- **位置**：正文底边贴角色头顶，只有尾巴尖压进 15%；头顶留白 < 24dp 时自动翻到脚下、尾巴朝上。
  最大宽度 = min(屏宽 74%, 角色宽 × 1.35)，最多 3 行后省略。
- **字体**：包内 `fonts/`（兼容旧命名 `font/`）有 ttf/otf 就加载并缓存，按 `包id/字体名` 记忆；没有则系统默认。
- **优先级**：终态气泡（10s）> 碎碎念 > 工作态气泡；同时只显示一条。

## 9. 资源包模型

**目录**（`PetPrefs.packsRoot()`，即 `filesDir/pet/packs/`）

```
packs/
└── <pack-id>/
    ├── pet-config.json      # 必需：业务配置
    ├── webp/                # 必需：动画 WebP（按 slug 命名，如 a001.webp）
    ├── fonts/ (或 font/)    # 可选：气泡字体 .ttf/.otf
    └── LICENSE / NOTICE.md  # 可选：随包保留
```

包体积不落元数据文件，由 `PetPack.dirSize()` 现算（设置页可要求跳过体积统计以省 IO）。

包 id 取 `pets[0].id`（为 `main` 或空时用 `imported`），冲突时追加 `-2`、`-3`……
同一 id 显式替换时走「旧包先改名 → 新包落位 → 删旧包」的原子流程，失败保留旧包。

> 注意：**没有 `manifest.json`**。旧设计稿里的清单文件从未实现，`index.json` 只用于下载端
> （体积/分卷/sha256/字体名），安装与运行时都只信 `pet-config.json` + 实际文件。
>
> 面向打包者的完整字段说明、可复制示例与自检清单见 [PET_GUIDE.md §4](PET_GUIDE.md#4-自制动画包进阶)；
> 本节只记录实现侧「到底读了什么、怎么校验」。

**`pet-config.json` 实际读取的字段**

| 键 | 用途 |
|---|---|
| `pets[0]` | `id`（安装目录名）/ `name`（列表显示）；`size` / `corner` / `marginX` / `marginY` 只解析不生效（尺寸与位置由 `PetPrefs` 决定） |
| `names` | slug → 中文动作名（菜单与日志用） |
| `durations` | slug → 段时长 ms（缺省 10041） |
| `animations.idle` / `turn` / `drag` / `clicks` | 各固定用途的候选段 |
| `animations.moves[]` | `slug` / `minDist` / `maxDist` / `leadSec` / `tailSec` / `margin` |
| `animations.categories[]` | `id` / `weight` / `actions[]` / `noMirror` |
| `animations.events.workStatus[]` | 6 槽位候选列表（思考/工作/整理/等待/成功/出错） |
| `animationWeights` | `idle` / `turn` / `move` 顶层权重（缺省 10/5/5） |
| `font` | 包内字体文件名（相对字体目录） |

`physics` 与 `pack` 键存在于现有包中，**当前实现不读**（物理常量固定在 `PetPhysics`，
许可与来源信息只在文档/`NOTICE` 里体现）。新增字段前请先决定是读配置还是继续硬编码。

**导入校验矩阵**（`PetPackInstaller`，失败一律错误码 + 本地化文案 `pet_pack_err_*`）

| 检查 | 阈值 / 判据 | 错误码 |
|---|---|---|
| 是否为 APK | MIME 或显示名 | `apk` |
| zip 文件头 | `PK\x03\x04` | `not_zip` |
| 条目数 / 单文件 / 总解压 / 配置大小 | 2000 / 50MB / 600MB / 1MB | `too_many_entries`、`entry_too_large`、`too_large` |
| 路径安全 | 绝对路径、`..`、穿越 | `unsafe_path` |
| 压缩方式 | ZipCrypto / AES 加密 | `encrypted` |
| WebP 头 | 每个 `.webp` 验 RIFF/WEBP（`__MACOSX/`、`._*` 不算坏包） | `bad_webp` |
| 必需动画 | 缺 idle / clicks | `missing_anim` |
| 空间 | 解压量 ×1.2 + 20MB 余量 | `no_space` |

任何一步失败都保留旧包；`.tmp-*` / `.old-*` 半成品下次导入无条件清，另有超过 1 小时的巡检。

**下载管道**

`PetPackIndexClient` 拉 `index.json`（默认
`https://github.com/XiaoPb/dsh-pet-mobile-assets/releases/latest/download/index.json`，
可在设置页改），相对地址按 index 所在目录解析。`PetPackDownloader`：
单文件直接 Range 续传到 `cacheDir/pet-pack/<id>.zip`；分卷逐卷续传到 `partNN`，
逐卷 sha256 通过后拼装、再校验整包 sha256，任何一步不匹配都不进安装器；
与本次无关且超 7 天的残件在每次下载开始时清掉。

**缓存预算**（`PetView`）

- 解码产物进 LRU，按**字节**记账：`maxMemory/16`，钳在 12–48MB；条数上限 12 只防碎片。
- 解码边长上限 1280px；`memoryClass < 192MB` 的机型压到 720px。只降不升，
  所以实际占用由包分辨率决定：300p 单帧约 0.73MB，480p 约 1.6MB，640p 约 2.9MB。
- `Movie` 路径（API 26–27）另有一套池，预算为上述的 1/4。

## 10. 门禁与生命周期

**单一判定源**：`PetPluginDetector.canEnable()` = 悬浮窗权限 + 桥插件就绪 + 资源包就绪。
设置页开关、`restoreIfEnabled()`（App 每次 `onResume`）、服务启动全部用它，避免多处判断走偏。

**卡点收口在 `PetOverlayService.attachPet()`**：`onCreate`、`onStartCommand`、`reloadPet`
三条路最后都汇到这里。被拦时（`holdForBridge`）前台服务照跑、窗口与驱动收干净，
状态置 `bridgeMissing`：

- 通知栏显示「已暂停：桥插件未就绪」（优先于「已连接/等待」）；
- 设置页开关卡出现 warning 色提示行，点击直达插件中心；状态副标题变橙色，不再显示「运行中」；
- 桥插件就绪后重新开启即恢复。

**生命周期**

- 前台服务类型 `specialUse`（subtype `floating_desktop_pet`），避免被系统按 dataSync 6h 限时杀掉。
- `pet_enabled=true` 时被回收走 `START_STICKY` 重建；用户「收起来」或关开关会清偏好。
- 不注册开机自启；打开 App 时按偏好恢复。
- `onConfigurationChanged` 时隐藏气泡并重新收敛窗口位置（横竖屏/分屏切换）。
- `onDestroy` 清 handler 回调（漫游 / 贴边 / 动画）→ 停甩抛与位移驱动 → 停链 → 收菜单与气泡 →
  重置控制器 → 放音效 → 摘视图 → 停服务端 → 注销亮灭屏广播 → 撤前台通知。

## 11. 安全边界

| 面 | 做法 |
|---|---|
| 网络 | 只绑 `127.0.0.1`，不监听局域网；无出站请求（下载包除外，目标由用户可见的 index URL 决定） |
| 鉴权 | `/bubble` 强制 token，fail-closed；`/ping` 免鉴权只报存在性 |
| 输入 | body/header 各 ≤8KB、5s 超时、2 worker；只解析五字段；工具摘要二次截断；完整参数不落日志 |
| 存储 | 动画包只写 `filesDir/pet/`，不碰 `dsh-home` / `profiles` |
| 素材 | APK 不含任何动画素材；导入内容做路径与格式双校验，拒绝加密包 |

## 12. 调试与排障

| 手段 | 用法 |
|---|---|
| 播放日志 | `adb shell setprop log.tag.DshPetView DEBUG`，关注 `play` / `land` / `stalled` / `heal` / `decode_failed` |
| 服务日志 | `log.tag.DshPetService`，看端口绑定、门禁拦截、事件计数 |
| 协议自检 | `curl http://127.0.0.1:7779/ping`（设备上 `adb shell curl` 或 `toybox wget`） |
| 静态回归 | `python scripts/probes/_test_pet_overlay.py`（结构、协议、门禁、包校验、文档一致性） |
| 帧头 dump | `python scripts/probes/inspect_webp_anim.py <pack-dir>` |
| 残影量化 | `python scripts/probes/check_webp_ghost.py <pack-dir>` |
| 修 blend 标志 | `python scripts/probes/fix_webp_blend.py <pack-dir> --apply`（不重编码、秒级、幂等） |
| 台词 | `filesDir/pet/lines/<cat>.txt` 是用户改动，内置文件在 `assets/pet/lines-default.txt` 永不修改 |

常见日志与含义：

| 日志 | 含义 |
|---|---|
| `stalled <slug> <ms>` | 2s 无新帧，看门狗介入 |
| `heal <slug> attempt=N` | 自愈尝试（同段上限 2 次） |
| `decode_failed <slug>` | 解码失败，保留旧帧并 400ms 后跳过 |
| `bridge missing, hold` | 门禁拦下，服务在跑但不画角色 |
| `port busy` | 7779 被占，只显示不接收 |

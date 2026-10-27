# 桌宠：功能清单与体验优化计划

> 状态口径：以 `main` 分支代码为准（2026-10-27，`8484c4a`）。
> 「已实现」= 代码可见且有用户入口；「确认未实现」= 代码里搜不到对应实现；
> 「建议项」= 有明确动机但尚未定方案，需要先讨论再排期。
>
> 使用说明见 [PET_GUIDE.md](PET_GUIDE.md)，实现细节见 [PET_ARCHITECTURE.md](PET_ARCHITECTURE.md)。

## 1. 已实现功能

### 1.1 运行与生命周期

| 能力 | 说明 | 入口 |
|---|---|---|
| 系统级悬浮窗 | `TYPE_APPLICATION_OVERLAY` 前台服务（`specialUse`，避免被限时杀） | `PetOverlayService` |
| 硬门禁开启 | 悬浮窗权限 + 桥插件 ≥0.3.1 + 资源包，三者齐备才能开；无旁路 | `PetPluginDetector.canEnable()` |
| 偏好恢复 | 打开 App 时按 `pet_enabled` 恢复；被回收后 `START_STICKY` 重建 | `restoreIfEnabled()` |
| 收起来 | 通知栏按钮 / 菜单项 / 设置页开关，三处等价，均清偏好 | `ACTION_STOP` |
| 状态可见 | 通知栏与设置页显示：运行中 / 等待事件 / 已连接 / 已暂停（桥未就绪）/ 端口占用 / 引擎未运行 | `PetStatusBus` |
| 横竖屏切换 | `onConfigurationChanged` 时隐藏气泡并重新收敛窗口位置 | `PetOverlayService` |

### 1.2 资源包

| 能力 | 说明 | 入口 |
|---|---|---|
| 多包共存 | `filesDir/pet/packs/<id>/`，可同时装多个包，切换立即生效 | `PetPrefs` / `PetPack` |
| 本地导入 | 自定义文件选择器导入 zip，带进度与错误码文案（三语） | `PetPackInstaller` |
| 在线下载 | 从 `index.json` 拉列表，支持分卷、Range 续传、逐卷 + 整包 sha256 | `PetPackIndex` / `PetPackDownloader` |
| 下载源可改 | 设置页可改 `index.json` 地址（自建镜像） | `PetPrefs.packIndexUrl` |
| 包管理 | 当前包摘要（段数/体积/分辨率）、已装列表、切换、删除 | `PetSettingsActivity` |
| 格式硬校验 | 只收 zip；拒 APK / 加密包 / 路径穿越 / 伪 WebP；2000 条目、50MB 单文件、600MB 总量上限 | `PetPackInstaller` |
| 空间回收 | 半成品目录下次导入清、1 小时巡检、下载残件 7 天清、旧单包自动迁移 | `PetPack` / `PetPackDownloader` |
| 原子替换 | 同 id 更新走「旧包改名 → 新包落位 → 删旧包」，失败保留旧包 | `PetPackInstaller` |

### 1.3 播放与渲染

| 能力 | 说明 | 入口 |
|---|---|---|
| 动画链 | 加权随机（idle 10 / turn 5 / move 5 / 分类 80）、防重复、`noMirror` 过滤 | `PetAnimChain` / `PetAnimPicker` |
| 工作状态 | 6 槽位（思考/工作/整理/等待/成功/出错），非终态循环、终态播一遍 | `PetAnimChain.WorkSlot` |
| 一次性动作 | 点击回应、菜单点播、拖拽；播一遍即回链（`repeatCount=0`） | `PetView` |
| 主动泵帧 | 41ms 定时泵帧，不依赖 drawable 自调度 | `PetView` |
| 解码缓存 | 按字节记账 12–48MB，条数 12 防碎片；低内存机型解码边长降 720px | `PetView` |
| 交叉淡入 | 新段首帧就绪才切换，180ms 淡入，杜绝切换黑帧 | `PetView` |
| 失败可观测 | 四类播放失败上报 + 同 slug 5s 限流 + 连续 5 次熔断 | `PetView` / `PetOverlayService` |
| 卡帧自愈 | 2s 无新帧记 `stalled`，循环停摆 `restart()`、断链重挂 ticker，同段最多 2 次 | `PetView` |
| 放大过滤 | 离屏原尺寸绘制 + 双线性过滤放大，消除硬台阶 | `PetView` |
| 包内字体 | 气泡加载 `fonts/` 的 ttf/otf，按包缓存 | `PetOverlayService.petTypeface` |

### 1.4 交互与自动行为

| 能力 | 说明 | 入口 |
|---|---|---|
| 单击 / 长按 / 拖动 | 单击 Q 弹 + 随机回应 + 音效；长按 450ms 出菜单；拖动跟手 | `PetView` / `PetActionMenu` |
| 甩抛物理 | 重力 1400 / 回弹 0.78 / 地面摩擦 2.5，落地 Q 弹挤压 | `PetPhysics` |
| 贴边躲藏 | 28dp 内吸附，静置 12s 挪出半个身位，碰一下探出 | `PetOverlayService` |
| 横向漫游 | 26dp/s，走 2.5–6s / 停 4–11s，遇边界掉头，忙碌时让路 | `PetOverlayService` |
| 动画驱动位移 | `moves[]` 的 `leadSec/tailSec/margin` 时间轴插值 | `PetMovePlanner` |
| 碎碎念 | 320 句 / 11 分类，按时段与情境触发，20s 冷却 | `PetChatter` / `PetLines` |
| 台词库编辑 | 分类逐行编辑、导出/导入 JSON、单类或全部恢复内置 | `PetLinesActivity` |
| 气泡绘制 | 单 Path 胶囊 + 尾巴 + 柔光投影；字号/内边距/圆角 em 化；贴顶或翻到脚下 | `PetBubbleDrawable` |
| 单击音效 | 内置 `click.wav`，可关 | `PetOverlayService` |

### 1.5 桥接与安全

| 能力 | 说明 | 入口 |
|---|---|---|
| 五字段协议 | `user/pre/post/stop/error/waiting`；tool 摘要二次截断 | `PetProtocol` |
| token 鉴权 | 每进程随机、不落盘，经 `DSH_PET_BRIDGE_TOKEN` 注入引擎；`/bubble` fail-closed | `PetBridgeServer` |
| 回环约束 | 只绑 `127.0.0.1:7779`；body/header 各 8KB、5s 超时、2 worker | `PetBridgeServer` |
| 端口冲突可解释 | 7779 被占时状态显示「端口被占用」，桌宠仍显示但不收事件 | `PetOverlayService` |
| 三语 | 中 / 英 / 德，含全部错误码文案；`check-i18n.py` 把关 | `res/values*` |

## 2. 已修复的关键缺陷（回归资产）

| 症状 | 根因 | 提交 |
|---|---|---|
| 动作播两遍 / 被截断 | `repeatCount=1` 被当成「播一遍」 | `7b759d0` |
| 画面彻底不动且无日志 | drawable 自调度丢一次 + `verifyDrawable` 不认槽位 | `2a8999c` |
| 抽到 idle 后整条链停摆 | idle 被当作一次性播放，链没有下一次推进 | `ab09bdb` |
| 移动后闪回原位、漫游被挡死 | 位移与窗口约束互相覆盖 | `5a10af7` |
| 无插件也能开桌宠 | 门禁只在设置页一条路径，`restoreIfEnabled` 与 `START_STICKY` 绕过 | `aa2246c` → `d4eb98f`（改硬门禁并删旁路） |
| 改坏素材也照开，用户面对静默失败 | 无版本门禁，旧插件 403/400 不解释 | `cac984d` |
| 导入把 rar/加密包当「缺配置」 | 没有 zip 文件头与压缩方式校验 | `508d851` |
| 导入/下载中退出设置页报 `WindowLeaked` | 对话框无 `onDestroy` 兜底，回调不判 `isDestroyed` | `cf9936c` |
| 换包 / 删 300MB 包卡主线程 | `PetPack.ensure()` 在调用线程删/搬整包 | `4b224ba` |
| 640p 包可能 OOM 且无观测 | 缓存按条数计，无字节预算 | `5c34510` |
| 坏段无限重试、换包时甩抛错位、息屏空转 | 无退避熔断；`reloadPet` 漏 `stopFling`；漫游判断在 `postDelayed` 之后 | `3c832b7` |

## 3. 待实现与体验优化

优先级按「用户可感知程度 × 改动风险」排，P1 最早；当前没有阻塞发布的 P0 项。

### P1 — 资源分发与获取体验

| # | 事项 | 现状 | 目标 | 状态 |
|---|---|---|---|---|
| 1 | 资源仓库 CI/CD 出包 | 包由本地 `dist/` 产物提供（300p 78MB、480p 275MB），转化与分卷靠脚本 | `webm → 动画 WebP` 多分辨率 + 生成 index + 上传 Release 全自动 | 确认未实现（在本仓库之外） |
| 2 | 下载前的体积与网络提示 | 对话框列体积/分辨率，直接开始下载 | 非 Wi-Fi 二次确认；剩余空间预检前置到点击时 | 建议项 |
| 3 | 手动暂停 / 队列 | 只有失败重试时的 Range 续传，无暂停入口 | 可暂停/继续，多包排队 | 建议项 |
| 4 | 640p 档位 | 代码不限制档位，取决于 index 是否提供 | 高配设备可选 640p（单帧约 2.9MB，需配合缓存预算验证） | 待确认 |

### P2 — 表现力

| # | 事项 | 现状 | 目标 | 状态 |
|---|---|---|---|---|
| 5 | 气泡配图 | `memes/`（27 张）与 `pic/` 未进入实现，代码无引用 | 特定情境（庆祝/出错）在气泡旁配图 | 确认未实现 |
| 6 | 菜单向桌面版对齐 | 现有 7 项：待机 / 点击回应 / 动作分类 / 回位 / 翻转 / 设置 / 收起 | 补转向、拖拽、移动、工作状态子菜单 | 确认未实现 |
| 7 | 余额 / 用量状态 | 协议无 `balance` 类事件，原生侧无对应槽位 | 若产品需要，先定协议再定表现 | 确认未实现 |
| 8 | 落地灰尘 / 贴边探头等细节动作 | 只有 Q 弹挤压 | 视素材包能力补充（受上游 106 段限制） | 建议项 |

### P3 — 质量与工程

| # | 事项 | 现状 | 目标 | 状态 |
|---|---|---|---|---|
| 9 | 功耗收敛 | 漫游 32ms tick（息屏已停），泵帧 41ms | 静止时降到事件驱动；泵帧按 drawable 是否需要重绘动态开关 | 建议项 |
| 10 | 分屏 / 折叠屏几何 | `onConfigurationChanged` 只隐藏气泡 + 收敛位置 | 分屏窗口边界、折叠展开后的重新测量与贴边重算 | 建议项（需真机矩阵） |
| 11 | 触摸精度 | 矩形窗口，透明角吃触摸 | 私有 `TOUCHABLE_INSETS_REGION` 可按轮廓裁，但属受限 API | 明确不做（记入限制 L2） |
| 12 | 死字段与文件拆分 | `PetStatus.placeholder` 仍存在（3 处赋值、无读取）；`PetOverlayService` 1579 行、`PetView` 1226 行 | 删死字段；按「窗口 / 漫游 / 气泡 / 通知」拆服务 | 确认未清理 |
| 13 | 探针覆盖 | `_test_pet_overlay.py` 已覆盖结构/协议/门禁/包校验/文档 | 增加播放时序与缓存预算的静态断言 | 建议项 |

## 4. 明确不做

| 项 | 原因 |
|---|---|
| `<video>` + webm alpha | Android 不支持该 alpha 通道组合；动画 WebP 是可用格式 |
| 毛玻璃 / `backdrop-filter` | Android overlay 无对应原语，纯色 + 双层阴影已接近 |
| 多显示器几何 | 移动端无此场景 |
| 第三方点播回执（playbackId） | 仅桌面网页端需要 |
| 按轮廓裁窗口 | 依赖受限 API，兼容性风险高于收益 |
| 开机自启 | 桌面宠物非必需常驻能力；打开 App 即恢复 |
| 同屏多宠 | 包配置只用 `pets[0]`，多宠会放大内存与交互冲突 |

## 5. 维护约定

- 改行为 → 同步 [PET_GUIDE.md](PET_GUIDE.md) 的「限制」或「日常使用」。
- 改实现 → 同步 [PET_ARCHITECTURE.md](PET_ARCHITECTURE.md) 的模块表与对应小节；模块行数变了顺手改。
- 做完上表任一项 → 从第 3 节移到第 1 节，并在第 2 节补提交号。
- 提交前跑 `python scripts/probes/_test_pet_overlay.py` 与 `python scripts/check-i18n.py`。

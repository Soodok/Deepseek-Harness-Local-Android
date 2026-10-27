# 实施计划：桌宠资源包化（去占位宠物 · 多包管理 · 云端下载 · 参考 dsh-pet 优化）

> 分支：dev。相关代码：`app/src/main/java/app/dsh/mobile/pet/`。
> 资源仓库：`E:\Code\DSH\plugins\dsh-pet-mobile-assets`（git@github.com:XiaoPb/dsh-pet-mobile-assets.git），**用 CI/CD 打包发布**，本阶段只定结构，产出放 Release。
> 参考实现：桌面版 `C:\Users\lzh17\.dsh\profiles\desktop\node_modules\dsh-pet`（以下称「桌面版」）。

---

## 1. 背景与目标

1. 当前 App 内置一只 **Canvas 占位鲸鱼**（`PetView.drawPlaceholder`），**删掉**：没有资源包就不显示桌宠，也不提示"可用"。
2. 桌宠 **必须先装动画包**；支持 **同时安装多个包**、设置页 **切换当前包**、可 **删除**。
3. APK **不再膨胀**：包走远端安装/下载，不需要桌宠的用户零下载。
4. 参考桌面版 dsh-pet 的实现做**移动端优化**（动画选取、位移、物理、气泡字体与样式、菜单结构、工作状态），并把桌面版的 webm 资产 **继续用已转好的安卓格式（动画 WebP）** 分发。

## 2. 现状差距（已核实）

| 维度 | 现状（App） | 桌面版 | 结论 |
|---|---|---|---|
| 包模型 | 单包 `files/pet/pack/`（config + webp/），导入整包替换 | 单 kinds 目录 + 包内目录 | 改为 `files/pet/packs/<id>/` 多包 |
| 无包表现 | 画占位鲸鱼 | 无 | 删占位，无包不贴窗 + 引导下载 |
| 动画格式 | 动画 WebP（`VP8X+ANMF`，106 段），API28 `AnimatedImageDrawable` / 旧版 `Movie` | `<video>`+webm alpha（Android 不支持） | WebP 方案保留，是"安卓可用格式" |
| 解码策略 | 每次 `play()` 现场解码，**无缓存**（100–300ms 黑洞） | 零预加载（webm 流式） | 加 LRU 预热缓存 + 双缓冲淡入 |
| 动画选取 | 简单随机 | 两级加权 + 防重复 + noMirror 过滤 | 对齐桌面版权重 |
| 位移 | 硬编码 26dp/s 漫游 | 时间轴插值 + leadSec/tailSec + 身体贴边 `sideAllow=0.3125×size` | 按 config 参数化 |
| 工作状态 | 6 槽位单段播放 | 非终态循环、终态播一遍+10s 气泡、打断可恢复 | 补齐终态/非终态语义 |
| 气泡 | 系统字体 TextView | 软糖体 + em 锚点样式 + 毛玻璃 | 用包内字体 + 复刻样式（毛玻璃降为纯色+双层阴影） |
| 菜单 | 待机/点击/动作 + 工具项 | 含转向/拖拽/移动/余额/工作状态，且含 "更多" 分支 | 结构对齐桌面版 `buildMenuTree` |

**不能照搬**的桌面版能力：`<video>`+webm alpha、CSS `max-content`/`backdrop-filter`、多显示器几何、HTTP 长轮询 `/state`、第三方 playbackId 机制。

## 3. 资源仓库最终结构（已定稿，CI/CD 后置实现）

```
dsh-pet-mobile-assets/
├── pet-config.json                        # 业务逻辑配置（由桌面版 config.jsonc 收敛而来：
│                                          #   pets / names / durations / animations{idle,turn,drag,clicks,moves,categories,events}
│                                          #   / animationWeights / font）
├── manifest.json                          # 资源清单 + 渲染元数据（融合版）：
│                                          #   id / name / version / resolution / fps / hitBox / thinkingAnchor
│                                          #   / sizes / animations[]（slug→文件、宽高、帧数、时长、体积、sha256）
│                                          #   / assets{fonts,memes,pic}
├── .github/workflows/build-packs.yml      # CI：webm→webp 转换 → 生成 pet-config/manifest →
│                                          #   多分辨率打包 zip（分卷）→ 更新 index.json → 上传 Release assets
├── index.json                             # App 拉取入口（随 Release 发布）
├── fonts/                                 # 气泡字体（桌面版 上首软糖体.ttf）
├── memes/                                 # 27 张表情包（后续用于气泡配图）
├── pic/                                   # 静态插图
└── webp/                                  # 由桌面版 assets/webm **本地转换**而来，不依赖 dsh-pet-android 仓库
    ├── a001.webp                          # 直接按 ID 命名，不建子目录
    ├── a002.webp
    └── …a106.webp
```

要点：

- **转换链路本地化**：来源只有桌面版 `dsh-pet/assets/webm`（106 段 VP9-alpha webm）；
  CI 里用 ffmpeg（`libwebp -pix_fmt yuva420p -loop 0`）转成动画 WebP，**保留 alpha**，
  按 pet-config 里的顺序编为 `a001…a106`，同时产出 320p / 480p / 640p 三档分辨率。
- **包内容**（App 侧安装器读取）：`pet-config.json` + `manifest.json` + `webp/` + `fonts/`（+ 可选 `memes/`、`pic/`）。
- **下载地址**：App 默认 `https://github.com/XiaoPb/dsh-pet-mobile-assets/releases/latest/download/index.json`，
  index 内 `url` / `parts[].url` 相对解析；整包与分卷双重 sha256 校验。
- **本地无 ffmpeg 也能推进**：转换脚本 `tools/build-webp.py` 只在 CI 或装有 ffmpeg 的机器上跑；
  当前验证阶段用仓库外的现成包 + 本地 index served by HTTP 完成端到端联调。

## 4. 执行阶段

### Phase A — 多资源包模型（去占位）
- A1 `PetPrefs`：新增 `pet_pack_id`；`packsRoot() = files/pet/packs`；删除 `packDir/packConfigFile/packWebpDir` 单包语义，改为 `'<id>'` 参数化；**旧 `files/pet/pack` 首次启动自动迁移**为 `packs/<id>` 并置为当前包。
- A2 `PetPack`：`load(ctx, id)`、`listInstalled(ctx)`、`metaOf(id)`；`parseConfig` 接受可选 `font`。
- A3 `PetPackInstaller`：安装到 `packs/<id>`（id = 包配置 `pets[0].id`，冲突时追加序号），**保留其他包**；按包粒度原子替换；额外解出 `font/*.ttf|otf` 与 `meta.json`；返回 `installedId`。
- A4 `PetView`：**删除 `drawPlaceholder` 全部占位绘制与 `usingPlaceholder`**；`PetOverlayService` 无可用包时不贴窗（服务与 bridge 仍运行），设置页显示"未安装动画包"引导。

### Phase B — 下载管道与设置页
- B1 `PetPackIndex`：`index.json` 模型 + 拉取 + 相对路径解析 + 分卷展开。
- B2 `PetPackDownloader`：`HttpURLConnection`，支持 **分卷、Range 断点续传、sha256 校验、进度回调**，落 cache 后交 `PetPackInstaller.install(file)`。
- B3 设置页新增「动画包」区：当前包（名称/分辨率/动画数）、**已装包列表可切换/删除**、本地导入、**在线下载**（对话框列可选包，显示体积/分辨率，下载进度）、下载源地址可改。
- B4 i18n（中/英/德）+ `check-i18n.py`。

### Phase C — 参考桌面版的渲染优化
- C1 **加权选取**：idle 10 / turn 5 / move 5 / 动作 80（小动作20 玩耍20 吃什么16 时节14 文字10），排除当前动画，`noMirror` 分类朝右时过滤。
- C2 **解码缓存**：`LruCache<slug, Movie/AnimatedImageDrawable>` + idle/clicks 预热；新动画首帧就绪才切换（双缓冲 180ms 交叉淡入），杜绝切换黑帧。
- C3 **位移参数化**：读 config `moves.{minDist,maxDist,margin,leadSec,tailSec}`，按动画时间轴插值位移，边界用身体矩形 `sideAllow = 0.3125 × size`。
- C4 **工作状态**：非终态循环 + 终态播一遍（气泡 10s）+ 打断后 `resumeWorkStatusAnim` 恢复 + 多档优先级聚合。
- C5 **气泡**：加载包内 `font/` 字体（异步预热）+ 复刻桌面版 em 锚点样式（字号基准 21×`size/462`、圆角/内边距/尾巴/双层阴影）+ 单槽优先级（终态 > 碎碎念 > 余额 > 非终态）。
- C6 **菜单对齐**：待机/转向/拖拽/点击回应/移动/5 分类/余额/碎碎念/工作状态 + 工具项；`noMirror` 点播前强制朝左。
- C7 **物理与手势**：对齐 `GRAVITY=1400 / RESTITUTION=0.78`、落地 Q 弹挤压；长按 500ms、移动 >8px 取消长按、拖拽阈值 5px。

### Phase D — 资源仓库 CI/CD + 全量验收
- D1 仓库按第 3 节落地：`tools/build-webp.py`（webm→动画 WebP + 多分辨率）、`tools/build-manifest.py`
  （生成 pet-config.json / manifest.json）、`tools/build-index-entry.py`（分卷 + index 条目）、
  `.github/workflows/build-packs.yml` + README/NOTICE（**应用端改造完成后做**）。
- D2 App 默认 index 指向 `releases/latest/download/index.json`。
- D3 MuMu 验证：无包状态 → 下载（300p）→ 多包（再装 480p）→ 切换 → 删除 → 物理/位移/气泡/字体/菜单；`check-i18n.py`、`_test_pet_overlay.py`、`BUILD SUCCESSFUL`；提交 dev。

## 5. 验收标准

| 项 | 标准 |
|---|---|
| 无资源包 | 屏幕不出现任何占位图形；设置页明确提示去下载 |
| 多包共存 | `files/pet/packs/` 下可并存 ≥2 个包，切换立即生效（重启服务即可见新角色/分辨率） |
| 下载 | 300p（~84MB）与 480p（~291MB 分 4 卷）均可下载安装，sha256 校验失败即报错不入库 |
| 删除 | 删包后若当前包被删自动切到其他包或回到无包状态 |
| 包体 | APK 不含任何桌宠素材（仅保留 `click.wav`、`lines-default.txt`） |
| 回归 | i18n 中/英/德齐平、overlay 探针通过、无 logcat 异常 |

## 6. 风险与决策

- **包冲突 id**：不同来源的同名 id 追加 `-2` 后缀，不覆盖用户已有包。
- **磁盘**：480p 291MB，设置页展示下载体积并在安装前校验剩余空间（现有 installer 已做）。
- **解码内存**：LRU 上限按屏幕密度估算（默认 6 段常驻），OOM 时降级为逐次解码。
- **占位删除影响**：`PetStatus.placeholder` 字段随改造去掉，桥接协议若被插件依赖需保留兼容字段（检查 `PetProtocol` 后再定）。
- **不做**：`<video>`/webm、毛玻璃 blur、多显示器几何、第三方点播回执。

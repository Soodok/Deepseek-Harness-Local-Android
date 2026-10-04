# DSH Mobile GitHub 宣传图设计与制作规格

- 版本：banner-v1；日期：2026-10-01。
- 目标：交付能够直接用于 GitHub README 的横幅，另导出仓库 Social preview 所需的 1280 × 640 图片。
- 用户已授权自行决定视觉方案。对外品牌使用 DSH Mobile；上游为 DeepSeek Harness，本项目为社区 Android 移植。

## 构图与内容

主方案为深海蓝产品海报：左侧巨幅 DSH Mobile 品牌、英文主张与中文定位；右侧两台具有精细金属边框和景深的悬浮手机，内置项目真实截图；背景右侧使用克制的蓝色玻璃流光环，左侧留黑色负空间。其他评估方向为浅色极简与终端主题，最终选择深蓝方案以匹配应用品牌。

文案使用 DSH Mobile、DeepSeek Harness. Now in your pocket.、完整 Agent 引擎，在 Android 上运行。卖点为 No Root、No Termux、19 Extensions；前者对应默认普通模式，19 项指按需安装的环境扩展。不使用完全离线大模型或所有数据永不离开设备的表述。

实际截图：`E:/Deepeek harness/_build_v128/emu35-toolbar.png`（1080 × 2400，真实欢迎界面）与 `E:/Deepeek harness/_build_v128/emu44-tools.png`（1080 × 2400，当前扩展中心）。保留 UI 内容，只作缩放、裁去模拟器状态栏并置入手机框。装饰标签是海报设计元素。

## 输出文件

- `dsh-mobile-github.png`：1600 × 900，README 首屏使用。
- `dsh-mobile-github@2x.png`：3200 × 1800，高分辨率。
- `dsh-mobile-social.png`：1280 × 640，仓库社交预览。
- `dsh-mobile-github.webp`：1600 × 900，网页轻量副本。
- `source/`：本地程序渲染的背景原件、矢量鲸鱼与使用的真实截图。生图服务因 ByteString 配置编码错误未成功提交请求，最终未使用 AI 生成图像。
- `render_banner.py`：Pillow + NumPy 的可复现排版与导出脚本。
- `verify_banner.py`：输出尺寸、颜色模式、必要文案、来源与边界的检查。
- `manifest.json`：版本、时间、文案与素材来源记录。
- `qa/`：仅用于独立视觉验收的缩略预览及结果，不替代视觉验收。

## 制作与验收计划

1. 制作前先编写检查器，验证输出应为 RGB PNG、准确尺寸、非空图片，且字体和来源存在；首次执行应因成品不存在而失败。
2. 生成独立背景素材，以本地字体、真实 UI 与金属设备框在 2× 画布合成；输出适配 GitHub 的规格。
3. 运行检查器并保存证据。委派独立视觉验收查看海报 PNG 及 GitHub 缩略尺寸，检查构图、文案、剪裁、层级和 UI 清晰度。
4. 根据明确的视觉问题修复后只复验受影响的导出物。
5. 更新工作区 `AI_CONTEXT.md` 并交付文件与缩略预览。此次不构建 APK，不改 App 版本，不发布到远端；banner 版本单独记录。

## 范围

仅新增宣传图与制作文件。不变更产品代码，不修改 README，不提交或推送 GitHub，不覆盖既有视频素材。

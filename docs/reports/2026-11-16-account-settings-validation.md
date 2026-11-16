# 账号与 MIUI 设置验证

基线：8d932a56d78edf71eecf02b994ca18f4fee0b22a。隔离工作树：E:/tmp/wt-flutter-native-chat，分支 feat/flutter-native-chat。主仓库与桌面 DSH 登录态未使用或修改。

## 最终行为

- 会话抽屉底部显示账号入口、头像或默认头像；已登录菜单包含设置与退出登录。退出先确认，失败保留已有资料；成功清除客户端资料，不删除对话或 API Key。
- 设置主页采用大标题、搜索、资料区、Token卡和分组圆角设置行，提供模型配置、插件管理、扩展编译环境、容器环境选择、语言、个性化主题、权限管理、Web UI、诊断日志、备份恢复、关于。
- 模型子页选择当前会话模型；提供商、API Key及完整参数通过完整Web UI管理。主题子页设置深浅/系统与四套自然配色；当前主题选择随本次应用运行保存，尚无跨重启持久化。
- 插件、编译扩展、容器、系统权限、语言、日志、备份和关于连接已有Android管理页面。语言设置调整Android管理界面，Flutter文案目前为中文。Web预览对原生管理能力给出明确反馈。
- Token默认为紧凑12周格点概览；展开84日明细后，每日仍有48dp操作区域及输入/输出/合计播报。

## 真实契约与数据范围

参考本机DSH 0.2.2-rc.2的dsh-deepseek-account/src/index.ts:201、220、472、534、552及src/types.ts，使用account/getState/getProfile/startSignIn/watch/cancelSignIn/signOut/hasRunningAccountTasks。登录使用本机HTTP回调及浏览器授权，watch按稳定attempt.id归属；只读取Host投影资料，平台凭据不进入Flutter或URL。

DSH没有公开账号按日Token RPC。格点读取session/list与session/events，最多最近50个主会话、每会话最近500条记录；设备本地时区归日，输入包含缓存读写，输出单列，不含子代理，不是官方账号账单。数据缺失保持无记录，明确零样本才显示0。单步重复usage替换，匹配llm/retry-started开启新尝试；非法计数、内部序号缺口和缺少尾部均拒绝。官方用量入口使用Host提供的Platform usage链接，沿用浏览器自己的账号态。

## 验证证据

- 最终完整Flutter门禁：303项测试通过（72s）；analyze No issues found（10.6s），日志[最终门禁](assets/2026-11-16-account-settings/settings-final-check.log)。此前299项、300项完整门禁亦通过。
- 登录初始化取消与即时完成弹窗在新增真实失败回归后修复；入口测试实际打开抽屉、设置、演示登录、返回与确认退出，并验证跨页面资料同步。
- 实际截图发现用量卡占屏过大，增加紧凑概览；新增红测验证其默认高度不超过160dp，展开每日48dp目标。更新后页面与入口25项通过，analyze无问题（34.7s）。
- beta.30/code405最终Web构建退出0（137.8s）；主bundle SHA256：b2ca5297919dc369d908ed04cfd61287832ab36b1ec8341a58800d049e5e8d05。8张PNG及视口/语义旁证均绑定该bundle；宽432和360、DPR1、bodyWidth正确，无Runtime.exceptionThrown。[本地画廊](assets/2026-11-16-account-settings/index.html)、[哈希清单](assets/2026-11-16-account-settings/manifest.json)及脚本/构建/失败回归日志已归档。画廊及8张图HTTP200，实际响应哈希与归档一致。Web预览的CJK回退字体加载存在时序差异，主题图采用已逐字目检的同bundle采集结果；manifest.sourceCapture保留实际采集文件名和原始旁证。未由此推断Android字体行为。
- 独立审查：具名checker调用超时、后台协调无报告，改由可追踪只读队友settings-checker-review完成。核实并红测修复旧账号资料、旧Token统计及Host失联复用旧服务；最终只读复核无残留P1/P2，详见[审查记录](2026-11-16-account-settings-review.md)。

## 未验证范围

本轮没有完成Android APK编译、设备安装、真实DeepSeek在线授权/退出及真实账号头像网络加载。Android管理页仅核对源码路由与Flutter MethodChannel测试，不能用Web预览证明设备导航。截图数据为显式fixture演示，不能作为真实用量或在线登录证据。原有Android依赖下载阻塞未在本轮扩展排查。

# 账号与设置独立审查记录

范围：隔离工作树 E:/tmp/wt-flutter-native-chat，基线 8d932a56d78edf71eecf02b994ca18f4fee0b22a。只读队友 settings-checker-review 核对 Flutter 设置状态、原生路由，以及 DSH rc.2 账号和本地用量契约。具名 checker 调用超时且后台协调无报告，最终由可追踪的只读队友完成审查；不将启动失败当作已审查。

## 核实及修复

1. P1：资料刷新失败后，旧 profile 仍作为当前身份显示；Host 不可用时仍复用旧服务。新增回归先复现 Expected: null / Actual: AccountProfile；Host 回归也先失败。当前分别清空失败资料投影，Host 读取使用 epoch 过滤迟到结果，失联或 URL 变化销毁旧控制器并重建。不可用原生服务返回离线错误且 isDemo=false，不退回演示账号。
2. P2：用量读取失败仍显示旧总量及热力图。新增回归先复现 Expected: null / Actual: UsageHistory；当前只清空失败用量投影，页面显示用量暂不可用。若仅用量失败而资料读取成功，仍保留有效资料；原有断言保留。
3. 登录初始化时取消可能遗漏实际 attempt ID；即时完成的登录可能遗留进度弹框。先前有效红测已修复：稳定 attempt ID 在 epoch 判定前保存，取消等待初始化落定后撤销；进度 DialogRoute 先推入再开始授权，完成只移除原路由。

主代理复跑状态、Host 生命周期和真实抽屉入口 12 项通过。最终完整测试 303 项通过（72s），analyze No issues found（10.6s）。红绿日志分别为 [失败复现](assets/2026-11-16-account-settings/settings-review-red.log) 与 [修复回归](assets/2026-11-16-account-settings/settings-review-green.log)，[完整门禁](assets/2026-11-16-account-settings/settings-final-check.log) 包含最终结果。

2026-11-16 12:15，独立队友只读复核确认上述旧身份、旧用量和 Host 失联服务复用触发链均已覆盖，无残留 P1/P2。队友未运行构建、未访问账号或凭据；验证命令由主代理串行执行。

Android APK、设备导航、真实在线授权和账号头像网络加载仍未验收。Web 截图中的资料与用量是明确标注的 fixture，不是这些边界的完成证据。

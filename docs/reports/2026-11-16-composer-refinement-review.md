# Composer 细化复核与收尾记录

- 实现提交：`1891d5f4`，基线：`831bd414`，版本：`1.6.0-beta.35 / code410`。本次收尾只改文档与证据，不改应用代码，不重新构建。
- 独立 checker 会话：`tool-management-child-a8b9d43f-3e0f-4645-ae1c-ec9647d88614`，最终正文 seq1366、turn/end seq1368=`completed`。从既有压缩会话日志逐帧恢复，未重启审查。
- 实际 `request/header.config`：provider=`opencode-go-2`，model=`opencode-2-glm-5.3-flash`。该审查未委派 GPT，未运行设备或构建，未修改文件。

## 独立复核结论及主会话处理

代码未发现 P1/P2；报告中有两项 P2。主会话依据原始事件核验后修正：

1. 工具时序：bash 任务 turn4 在 seq60 的 attempt.stream 为空，seq62 被用户停止；该回合没有 TIMEOUT 事件。read 任务 turn5 在 seq68/71 两次 300000ms 超时，seq69/72 重试，seq76 最终停止。补存完整 `assets/2026-11-16-composer-refinement/rpc-events-beta35-final.json`（77条事件），修正文档将两次任务都描述为超时的问题。
2. 能力归因：一次直连 HTTP200、tool_calls=0 与设备任务失败，只能证明本轮没有成功工具调用。已删除“Luna不真正执行工具调用”“上游稳定能力限制”等全称结论；根因未确定，在线工具、审批与子代理仍未验收。

P3 证据问题已处理：

- 最终分析检查补跑退出0，输出 `No issues found! (ran in 35.4s)`；完整测试以实际日志末行 `01:24 +353: All tests passed!`、`EXIT=0` 为准。Gradle为 `BUILD SUCCESSFUL in 1m 11s`、`EXIT=0`。三份日志均已归档；归档副本仅去掉四处控制台行尾空格以通过git空白检查，原始日志仍在 E:/tmp/native-chat-web-preview 的同名文件，未改其输出。
- 动作矩阵实际有9项测试，既有聊天队列测试本轮未修改；已修正报告计数和变更归属。
- DSH Go本地为稀疏检出；固定提交 `97635eb8cae420cd70076eee5ae446a8dd47b131` 的Git对象包含自绘玻璃，已导出到仓库外核对。glass.dart blob=`054335e7da0a5015539d95a9b8a0d23ed4705ce3`（13025B），surfaces.dart blob=`368ebb1a93ebd040175540987f2c039ed0bde935`（11269B）。pubspec无玻璃第三方依赖，LICENSE为AGPL-3.0。旧9f31a462引用未能联网复核，现有结论绑定97635eb8。参考源码未放入本应用目录。
- 端点地址与API密钥区分记录；删除“密钥不落地”的不实声明，补记实际暂存及清理。

## 凭据与临时文件

- 主会话以实际授权密钥精确扫描1884个受版本管理文件、最终journal和APK的1354个解压条目，命中均为0；APK SHA256再次验证为 `5ef94ff9599dcac8b3c21d05536857710dbe5202aac8eb3c41ae64705f2adc9e`。扫描不是对所有未知编码或嵌套包的通用秘密审计。
- 本地 `E:/tmp/dsh-luna-provision/apply-.credentials.yaml` 曾含明文密钥，已删除；同目录另外三份传输暂存一并删除。设备 `/data/local/tmp/` 的本轮四个配置暂存均已删除；目录仅余dalvik-cache。
- 写入前的回滚备份 `E:/tmp/dsh-luna-provision/backup-20261116-205020/` 保留，不含Luna密钥；该目录仍是私有凭据备份，不作为公开交付物。
- 仅专用模拟器应用私有配置保留已授权密钥；只读检查确认其与授权引用相符，设备安装版本code410。桌面profile未写入。后验判定在 `assets/2026-11-16-composer-refinement/beta35-secret-audit-post-cleanup.json`。原 `beta35-secret-audit.json` 是清理前记录，不能当作清理后状态。

## 保留的功能边界与P3观察

- 子会话目前没有已证实的取消RPC，停止能力关闭；模型标签在建立watch时读取，尚未验证中途切模型的同步。真实子会话未在此模拟器跑通。
- 模型胶囊文字缩放最多1.2倍；大字号布局测试通过不等于完整无障碍阅读验收，禁用文字对比与真机GPU观感未专项量测。
- 停止失败可在同一槽位重试，但后续事件会清除瞬态错误提示；发送请求在飞行中的极短窗口可能显示不准确的“不支持停止”提示。
- 原生附件仍按Host能力禁用；真实多问题/专属问题类型仍需Web路径。组件及协议测试不替代在线工具与审批验收。
- 本轮产物仅x86_64模拟器debug full APK，非ARM真机发行包；未覆盖长时性能与Web双端同步。

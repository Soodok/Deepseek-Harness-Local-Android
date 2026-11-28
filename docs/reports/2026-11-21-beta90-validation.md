# beta89 模拟器与 beta90 ARM64 验证记录

截至 2026-11-21 09:20，功能实现、本地回归和 PLZ110 保数据覆盖安装已完成；真机原生入口已启动并回到聊天页。仅修改隔离树 E:/tmp/wt-flutter-native-chat；主仓库与其他设备未写。

## 实现与本地门禁

- 文件、模型、权限菜单共用逐项入场；默认位移 7dp、单项 170ms、间隔 34ms、整组最多 320ms，减少动态与无障碍导航直接显示。
- 主/子发送受理成功后单次系统轻触，实际进入/切换编辑后单次选择反馈；失败、拒绝、重复和取消路径有负对照。
- GLM 只读审查没有必须修复项。已核实并修复发送异常导致锁定的建议：composer.dart 以 try/finally 复位 _sending。其余低优先级建议不等于已验证缺陷。独立报告 D:/dsh-native-chat-validation/2026-11-21-checker-review.md。
- Flutter 最终全量：498 项，exit 0；analyze：No issues found，exit 0。日志分别为 D:/dsh-native-chat-validation/2026-11-21-flutter-full-test-final.log（末行 560）与 2026-11-21-flutter-analyze-final.log。

## 模拟器证据与边界

- 专用 emulator-5570，beta89/code464 同签名覆盖安装，正常 MainActivity 工具栏进入 NativeChatActivity；没有清数据或发送模型请求。
- 真实模型菜单与三档权限最终可见。050/150/400ms 是主机文件标签，期间画面未更新；不得把这些截图当动画时序证明，设备流畅度与完整录像仍未验收。
- 通过 VM 在内存创建临时 ConversationFlow 路由，输入本地助手 Markdown；生产组件识别 PNG/Dart/PDF/ZIP/APK 五张卡，并连接原 ChatShell 的生产文件服务回调。截图 D:/dsh-native-chat-validation/beta89-file-transcript.png。该路由不写用户会话，不证明真实模型输出到权威历史的完整端到端流程。
- 点实际 PNG 卡 → 菜单 → DSH 预览，成功解码色带图。截图 beta89-file-actions.png、beta89-image-preview.png。
- 预览点外部打开 → Android resolver → 自有验证器，仅此一次。独立 UID 10193 以 ACTION_VIEW 读到 image/png 822 B，SHA-256 ee2eced8dfb4d58c89e38d1c06c70209135ed0dabc6768c133d5032aab06b2bf 与原件一致，writeDenied=true。原始证据 D:/dsh-native-chat-validation/beta89-image-open-result.json。
- ACTION_SEND 的有效独立 UID 证据为此前 beta88 的 PNG 和 Dart，分别见 beta88-image-share-result.json 与 beta88-code-share-result.json；本次后续分享尝试停在旧接收器活动，未获得 beta89 新 SEND 记录，不计通过。PDF/ZIP/APK 的设备外部交付及真实产品会话入口仍未验收。

## ARM64 可审查产物

- 构建 :app:assembleRelease exit 0，9m43s。JDK21、Gradle9.3.1、Flutter3.50.0；仅2 workers、Xmx1536m，输出/项目缓存重定向 D:。日志 D:/dsh-native-chat-validation/arm64-build.log。
- APK D:/dsh-native-chat-validation/arm64-build/app/outputs/apk/release/app-release.apk，161491464 B，beta90/code465，arm64-v8a 单一 ABI，非 debuggable。SHA-256 1b5e8bbe6ee5b0145ad6cd5b6819c527429b1a78cd95c847bc4f94a164a32482。
- release 证书 SHA-256 d28ca4f5b7467ea19564983f492af25fd21b26b2577fbfa32c46ed59042892c8，与 PLZ110 当前包一致。内嵌 runtime.zip 104529708 B，SHA-256 61bc738970a435dea61e8e5f445281a4ffad16cfe3012cba2682aefa4367a2c1。详细身份 beta90-arm64-identity.json。
- PLZ110 安装前实时复核为 beta25/code401（期间另一轮已更新设备），序列 3B164M0105000000，192.168.101.21:9000，arm64-v8a；同签名证书 d28ca4f5…892c8。期间保留并行容器任务，未停止其他会话。
- 执行 `adb -s 192.168.101.21:9000 install -r`，未使用卸载、清数据或 `-d`；命令成功。安装后 `dumpsys package` 为 beta90/code465，pull 回设备 APK 为 161491464 B、SHA-256 1b5e8bbe6ee5b0145ad6cd5b6819c527429b1a78cd95c847bc4f94a164a32482，证书 SHA-256 d28ca4f5b7467ea19564983f492af25fd21b26b2577fbfa32c46ed59042892c8。证据：plz110-preinstall-current.apk、plz110-beta90-installed.apk、plz110-beta90-launch.log。
- 正常 MainActivity 入口启动后，runtime 安装并完成首次启动；最终 `topResumedActivity=app.dsh.mobile/.NativeChatActivity`，顶部 Bionic Node 显示“已连接”，历史消息与 composer 可见。最终截图 D:/dsh-native-chat-validation/plz110-beta90-native-final.png，SHA-256 bce84830e58efea2ab02d953983a1280ff152fd12ae9de5e2e49aaa66ce0e2b0；最终筛选日志无 FATAL EXCEPTION/ANR。
- 运行日志出现 `dsh-home backup failed` 警告；另有 run-as 对部分 home symlink 读取权限不足的记录，两者因果未验证，完整数据备份仍未建立。本次 install -r 后历史聊天页可见数据仍在，不能把截图外的数据完整性写成全量证明。
- 后续复核时手机前台已切换到 com.deepseek.chat/.MainActivity；未再次抢占前台。09:19:47 的原生页截图与当时 resumed 记录仍为启动验收证据，不宣称持续保持该前台页面。

## 清理与源码身份

- 已关闭临时路由；五个原始样本与五个 staging 副本均核对哈希后按精确文件名删除，空目录以 rmdir 删除。复核 cache/shared/deliveries 的 ls -A 退出0且无条目。自有接收器已卸载，包列表无该包。新旧自有 attach 均已结束，tcp50103 已移除；其他设备/转发未动，emulator-5570 保持运行。
- 恢复后的 Web 页仍保留 probe-target 草稿，截图 D:/dsh-native-chat-validation/beta89-after-cleanup.png。
- 源码快照 D:/dsh-native-chat-validation/beta90-source-snapshot.zip：765 文件，21392872 B，SHA-256 19559430c9e89c9bc5c65bf84bd52a75953097f4738d394c5b4f4a8aa5e80fad。内含逐文件路径/大小/SHA 的 _SOURCE_IDENTITY.json；HEAD 7d9b26dfd42391fbed9c776a21c3f97f97a67b05 加未提交实现。未推送或发布。

## 已完成的真机安装门禁

PLZ110 beta90/code465 已经以同签名 `install -r` 覆盖安装并从正常入口启动；版本、ABI、安装后 APK 哈希、签名、NativeChatActivity resumed 和最终聊天页截图均有证据。

## 保留限制

真实会话中的文件卡→预览→系统打开/分享、物理触感、完整大字体/深色/TalkBack/加载失败矩阵仍未在 PLZ110 逐项验收。临时路由和模拟器证据不能替代这些设备流程；不把它们写成已通过。不得卸载、清数据或降级。清理结果另列于工程交接顶部。

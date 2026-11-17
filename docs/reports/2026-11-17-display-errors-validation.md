# beta.36 显示与错误界面验收

2026-11-17，宿主 Asia/Shanghai。仅操作隔离树 E:/tmp/wt-flutter-native-chat 与本任务专用 emulator-5570；模拟器截图时钟与宿主相差8小时，未为截图改系统时区。主仓库与其他设备未写入。

## 产物与基线

| 项目 | 结果 |
| --- | --- |
| 基线 | 59f59401533784c00c4b0446fc5b4e392e2c4a41，feat/flutter-native-chat |
| 版本 | 1.6.0-beta.36 / versionCode 411；设备 dumpsys package 实际确认 |
| APK | E:/tmp/native-chat-web-preview/dsh-native-chat-beta36-x86_64-full.apk |
| 类型/ABI | full debug；ZIP中原生库仅 x86_64；不是 ARM 手机发行包 |
| 大小 | 248,050,674 bytes |
| SHA-256 | 25521c852d5ea858f7484019abc7ae31602e1a518ea6734ef392dbc8ff31befe |
| 内嵌 runtime | assets/runtime.zip，138,391,732 bytes，SHA-256 0b693c76ae829035bb9c2f74ebaa869ad3f7abd086c97840d861bbf80c5e92a9 |
| 设备 | 自建 dsh_flutter_20261116，Android 34，emulator-5570，1080×2340，440dpi（2.75px/dp） |
| 绑定清单 | source-state.json 的32个源码/配置/测试文件hash，beta36-artifact.json，capture-manifest.json |

## 需求与实际结果

| 需求 | 验收 | 证据/边界 |
| --- | --- | --- |
| 模型胶囊稍左、短名称 | 已靠加号一侧，34dp视觉表面和≥48dp触控区；短标签只作用显示 | 实际compact模型目标为[221,2093,486,2225]，高132px=48dp；坐标点中后打开引擎真实模型弹层；完整名仍可访问 |
| DeepSeek显示为ds | 大小写缩写、版本/后缀保留，真实id/provider/wire不动 | 单元/组件测试验证；本机真实目录只有gpt-6-luna，未人为添加模型来伪造设备缩写证据 |
| 错误从正文独立 | 真正的历史turn/end失败显示独立提示卡，有原因、代码、详情和复制 | 复用保留会话的MISSING_CREDENTIAL历史；展开详情为code-only，复制后出现明确反馈；不是新发模型请求 |
| 停止/签出/中断区分 | 用户停止为中性；签出/中断有各自标题与下一步 | 结构化事件测试及组件测试；该轮未新增在线取消请求 |
| 设置更密、选项字体稍小 | 标题/留白/行高收紧，选项15sp，副标题与分组不低于12sp | 实际设置选项页PNG；保留原有账号与Token概览，不重构其信息结构 |
| 整体显示大小 | 个性化可选90%/100%/110%，文字、控件、间距一起变化 | 三档实际点击、截图与XML；不是仅TextScaler换字大小 |
| 换档不丢当前工作 | 同页切换继续停在个性化；返回原设置滚动位置；两档均保留未发草稿 | DISPLAY_SIZE_DRAFT坐标输入并在compact/relaxed往返后仍在，最终已清空且未点发送 |
| 弹层与键盘 | 模型弹层在compact可点；compact/relaxed下输入区均在实际Gboard上沿之上 | 实际PNG/XML；弹层打开时换档的状态/坐标另由widget测试覆盖 |
| 持久化与冷启动 | Android prefs读回compact/relaxed；强制停止后真实入口再次打开仍为compact、light、sand | native_chat_ui-after-cold-start.xml及cold-restored截图；保留真实会话ID未重建演示会话 |

实际会话：session-a417e50d-81a2-48fe-92c8-231d197ee6e9。MainActivity的“原生对话”按钮打开未导出的NativeChatActivity，未修改export或绕过真实入口。

## 三档真实几何

由对应截图的UIAutomator全局bounds除以2.75计算，原始数据见 device-geometry.json：

| 档位 | 显示大小两行选项高度 | “跟随系统”主题选项高度 |
| --- | ---: | ---: |
| 标准100% | 66.91dp | 52.00dp |
| 紧凑90% | 60.36dp | 48.00dp |
| 宽松110% | 73.82dp | 57.09dp |

后者在紧凑方向有触控下限补偿，因此不是单纯52×0.9。详情/复制/模型入口的compact目标也为132px=48dp。设备测量与widget测试的值分别报告，不混用像素、dp或字体单位。

## 系统大字与恢复

专用模拟器临时设置 font_scale=2.0，真实重新进入保留会话，检查正文及错误卡换行、顶栏和输入控件可见；完成后读回1.0。首次设置读取为null（系统默认），删除临时值后系统写回1.0；最终有效字号已恢复。没有修改宿主或其他设备字号。胶囊本身保留既有系统字号1.2上限策略，完整名有另外入口，不把其宣称为2倍缩放。

系统字号变更触发Android Activity重建，返回原生列表属于本次观察，不等同于应用内90%/110%切换（后者已实际保留当前路由和草稿）。真实320dp物理设备与TalkBack未测试；320×640、2x系统TextScaler、compact的无溢出和可点通过widget测试。

## 门禁

Lead最终实际执行（无后续Dart/Kotlin修改）：

```text
E:/tools/flutter/bin/flutter.bat test --concurrency=1 --reporter expanded
01:11 +396: All tests passed!
EXIT=0

E:/tools/flutter/bin/flutter.bat analyze
No issues found! (ran in 1.2s)
EXIT=0
```

Gradle9.3.1、JDK21、Android SDK E:/Android/Sdk，单任务/最多2 workers、Xmx1536m、Kotlin in-process与关闭incremental：

```text
:app:assembleDebug -Pabi=x86_64 -Ptarget-platform=android-x64 -Ppackage=full
-PruntimeZip=E:/tmp/dsh-flutter-runtime-20261116/runtime-x86_64-dsh-0.2.2-rc.2-20261116.zip
BUILD SUCCESSFUL in 3m 9s
exit code: 0
```

已知构建警告包括未来最低Gradle/AGP版本、现有deprecated API、本机debug签名、未打包SSH workspace/solution-explorer；未关闭依赖校验来绕过。完整门禁归档在资产目录，原始日志仍在 E:/tmp/native-chat-web-preview。归档仅统一行尾、去行尾水平空白，双份hash与处理说明见 log-manifest.json。

覆盖安装 `adb -s emulator-5570 install -r` 成功；后续强制停止后的Main入口 `LaunchState: COLD`、TotalTime=2507ms（只量MainActivity首帧，不代表完整引擎/对话可用耗时）。设备crash buffer为空；这是本次缓冲观察，不作长期稳定性结论。

## 采集异常与保全

早期安装后的首次 `am start -W` 报timeout，但回读屏幕后Main已就绪；随后真实操作可用，冷启动复查Status=ok。UIAutomator在首次Flutter/字体重建过渡时数次返回空输出，helper报“No fresh UI hierarchy”并停止，不读取旧XML；稳定后用新唯一文件重取。失败采集不作为通过依据，也未据此推断产品根因。

应用验收未新增在线模型请求、未读写凭据；模型工具/审批/子代理和附件原有在线缺口仍保留。本轮仅证实新UI与本地真实引擎会话的配置、历史显示和界面交互。

专用模拟器已正常退出，AVD及安装数据保留；其他设备和主仓库保持不动。

## 快速查看

- [冷启动后的独立错误卡与模型胶囊](assets/2026-11-17-display-errors/beta36-cold-restored-compact.png)
- [紧凑个性化页](assets/2026-11-17-display-errors/beta36-personalization-compact.png)
- [紧凑设置选项页](assets/2026-11-17-display-errors/beta36-settings-compact-options.png)
- [宽松档真实键盘](assets/2026-11-17-display-errors/beta36-keyboard-relaxed-with-draft.png)
- [系统200%字号真实对话](assets/2026-11-17-display-errors/beta36-system-font-2x-chat.png)
- [指定技能的审查与逐项处置](2026-11-17-display-errors-review.md)

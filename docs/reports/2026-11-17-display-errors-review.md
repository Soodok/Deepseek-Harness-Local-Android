# 界面审查与处置（beta.36）

## Scope

| 字段 | 值 |
| --- | --- |
| Target | working：本轮模型标签、错误卡、设置密度与显示大小变更 |
| Base | 59f59401533784c00c4b0446fc5b4e392e2c4a41 |
| Head | feat/flutter-native-chat 的本轮候选工作树；0 个已提交功能提交，32 个源码/配置/测试文件，具体 SHA-256 见 source-state.json |
| 排除 | 主仓库、其他设备、.superpowers 草稿、生成输出、二进制；报告与截图不计入32个源码文件 |
| 技能源 | 用户指定 jakubkrehel/skills，固定 6c2120103600407a1a9986afd834e73144ef8998；MIT；本地读取，无全局安装/新增应用依赖 |
| 展开界面 | ConversationFlow/错误卡、主输入区及模型弹层、设置主页、个性化页、共享子会话表面；真实在线子会话不在本轮设备验收范围 |

使用 interface-review → better-interface 及 accessibility/layout/writing/typography/colors/ui 六领域规则，转换为 Flutter 等价检查，不照搬 CSS 或重设用户已经选定的视觉方向。固定快照及文件 blob/SHA 见 [skill-sources.json](assets/2026-11-17-display-errors/skill-sources.json)。

独立复核使用 opencode-go-2 / opencode-2-glm-5.3-flash。原会话 177b8f9e-9663-41e0-a883-851ddb8eec89（最终 assistant seq867）及 e8b77fd0-a83f-418e-9907-dcd6359d78ce（seq573）均已 completed。原报告保留在资产目录；它们是审查意见，以下为 Lead 对照代码、测试及设备证据后的最终处置。前台超时后曾重复派发一次，恢复原会话后未再发新审查请求。

## Coverage

| Domain | 证据 | 结果及边界 |
| --- | --- | --- |
| Accessibility | 完整模型名 Tooltip/Semantics、真实132px/48dp按钮、紧凑主题行、200%系统字号截图与320dp widget测试 | 触控下限已修复；TalkBack朗读、完整键盘遍历未专项实测 |
| Layout | 统一画布及 MediaQuery 换算；三档真实行高；已打开路由/弹层的坐标测试；两档真实键盘截图 | 文字和几何统一缩放，IME/安全区检查通过；物理320dp设备未测 |
| Writing | 真实历史错误卡、认证恢复文案、签出/中断区分、显示大小说明 | 恢复路径和说明已明确；服务端任意未知错误文案不保证本地化 |
| Typography | 新增字号、完整名入口、系统字号透传 | 新增11/11.5sp改为12sp；模型胶囊系统缩放上限1.2为既有取舍 |
| Colors | 复用 bad/warn/ink/inkSoft 语义色，实际暖砂浅色截图 | 未发现本轮语义色误用；GLM计算值不视为全部8套主题的渲染像素验收 |
| UI | 独立错误行、保留玻璃/浮层风格、紧凑设置、状态图标、草稿与路线稳定 | 已查看本轮真实PNG；帧率、真实GPU性能与全套动效未专项验证 |

## UI findings 与处置

| Severity | Domain | Status | 位置 | Before → After | 证据/结果 |
| --- | --- | --- | --- | --- | --- |
| MEDIUM | Layout | Introduced，已修复 | display_scale.dart / main.dart | 只缩全局文字、几何仅输入区 → Navigator之上的统一逻辑画布 | 三档设置行、弹层及文字的坐标测试；真实三档节点测量 |
| MEDIUM | Accessibility | Introduced，已修复 | settings_detail_pages.dart:29,63 | minTileHeight=52，compact下46.8dp → atLeastTarget(context,52) | 有效红/负对照由实现阶段执行；Lead终态396测试及设备compact主题行48dp |
| LOW | Typography | Introduced，已修复 | turn_notice_card.dart:145；settings_page.dart:800,894 | 新增11/11.5sp → 12sp | 设置可读性与错误码测试；搜索输入提示本来继承15sp，未捏造其缺陷 |
| LOW | Writing | Introduced，已修复 | turn_notice.dart:44-60；settings_detail_pages.dart:50-53 | 签出/中断共用“停止”标题、技术说明/禁用状态误导 → 具体原因及下一步、简明效果说明 | 标题/恢复提示/无存储环境测试 |

GLM的两项未采纳意见：

- DeepSeek子串替换：用户明确允许名称包含 deepseek 时缩写为 ds，完整名仍在模型目录/Tooltip/Semantics，模型 id/provider/wire 未改，不收窄为新的词边界规格。
- activity空行残留：对照 dsh_session_projection.dart:465-500，消息视图每次从事件重建，该辅助行只在 running 时补入；结束后不会由这条分支保留。没有复现支持其“结束后固定留白”的结论，不据此删流式归属逻辑。

## 普通代码复核（与UI领域分开）

P2：原始认证 failure JSON 经详情/复制绕过可读文案脱敏，已修复。dsh_session_projection.dart:666-682、730 将六类凭据失败的详情缩成 code-only，正文仍有恢复说明，非认证未知错误保留诊断。测试用假 SENTINEL 验证正文、嵌套详情和实际复制回调均不带原值。上游依据已核读 rc.2 的 dsh-client-ui-chat/src/client/conversation-nodes/event-projection.ts:149-155；不把原始 Session 日志删改。

没有剩余已确认P1/P2。本结论针对本轮变更，不代表完整在线功能验收。

## Pre-existing / 保留边界

- 模型胶囊对系统TextScaler上限1.2的既有策略保留；长名的完整值仍可访问。正文遵循系统缩放，已查看200%系统字号对话截图。
- 子会话 transport 对部分 turn/end 的既有投影差异未在本轮扩展；共享错误卡能够渲染不证明真实子会话已接入全部失败事件。
- 设置首屏保留账号和完整Token概览的既有信息结构；本轮收紧选项、字体与留白，没有借审查重构统计卡。

## Verification

- Lead终态实跑：flutter test --concurrency=1 --reporter expanded → 396 passed，01:11，EXIT=0；flutter analyze → No issues found，1.2s，EXIT=0。
- Gradle assembleDebug → BUILD SUCCESSFUL in 3m 9s，退出0；beta.36/code411，x86_64 full debug，包/内嵌runtime身份见 artifact。
- emulator-5570：真实Main入口→原生列表→保留会话；标准/紧凑/宽松、详情/复制、模型弹层、草稿保留、两档键盘、200%系统字号及冷启动恢复均有PNG/XML。未发新的模型请求。
- 设备/页面操作详情见 [验收报告](2026-11-17-display-errors-validation.md)。日志归档副本只统一行尾并去行尾空白，原始日志未动，双份hash见 log-manifest.json。

## Verdict

**Approve（本轮已检查范围）**：没有剩余已确认HIGH界面问题；两项实质修补及字号/文案问题均已处理并验证。TalkBack、完整硬件键盘遍历、全部主题的像素对比、真实GPU性能、ARM真机与在线工具/审批/子会话全流程仍为 Not verified，不包含在本结论内。

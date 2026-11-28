# WebView 结构化错误接线独立只读核查（Sol，2026-11-18）

结论：真实的 turn-error 接线在源码及离线调用层成立；当前实现不满足认证脱敏、双端分类一致与展开详情保全要求，不应把 INTERRUPTED 的一次设备演示作为整项验收。发现 **1 项 P1、4 项 P2**。本轮未修改产品源码或第三方文件，未执行 Git 写操作，未构建、未碰设备、未读取凭据、未发模型请求、未派子代理。

## 审查对象与证据边界

- 固定仓库：E:/tmp/wt-flutter-native-chat；HEAD 完整 SHA：7d9b26dfd42391fbed9c776a21c3f97f97a67b05；比较基线：c93e187f。开始时已跟踪文件工作树干净；随后对本报告引用的客户端、现有测试、交接文档和 Flutter 分类文件执行 git diff --exit-code 7d9b26df，退出 0。
- 先读取 [AI_CONTEXT.md 前 20 行](/e/tmp/wt-flutter-native-chat/AI_CONTEXT.md#L1)、[输入区交接报告](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md#L1) 和 [现有客户端测试](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs#L1)，再追查实现。没有把交接结论当作事实前提。
- 项目源码以 git show 固定提交为复现输入。现有 runtime 自有 SDK 源码来自 E:/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/；[包版本](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/package.json#L4) 为 0.2.2-rc.2。该来源不是 Android 当前进程内存或 APK 字节；未核实二者是否同源。
- 复现直接执行该 SDK 的 displayFailure、turnErrorDefinition、chatNode 真实函数。Node v24.15.0 禁止直接剥离 node_modules 下 TS，因此用 node:module.stripTypeScriptTypes 在内存去类型并连接两个本地导入；未改 runtime。React 元素与 Hook、clipboard 为窄桩：能核对传入渲染树/复制 API 的值，不能证明浏览器 CSS 布局、系统剪贴板、TalkBack 或实际 Cordis 插件卸载。
- 合成标记只有 SOL_SYNTHETIC_TOKEN_20261118，均为本轮创建，不是真实 token。缺失 message 的 JSON 载荷属于非标准/历史或扩展输入边界；正式 LlmFailure 要求 message:string，见 [LlmFailure](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-llm/src/types.ts#L48)。正式字段内的原始 message 泄漏已足以独立成立，不依赖这个边界用例。

执行范围注记：首次运行仓库原有 node --test 时，既有 core 测试的 mkdtemp 沿用了系统默认 os.tmpdir，因此由测试自身创建了系统临时测试目录；这是本轮临时目录约束执行上的遗漏，未清理或继续改动那些目录。保存完整日志的重跑已把 TMP/TEMP 显式定向获准临时目录。被审产品源码、Git 元数据和 runtime 源码始终未改。

## 验证结果

| 检查 | 本轮实际结果 | 证据/边界 |
| --- | --- | --- |
| 原有插件 Node 全量 | 46 tests / 46 pass / 0 fail，退出 0 | [完整汇总](/e/tmp/sol-web-review-20261118/existing.tap#L47)；与交接数字一致。保存日志时 TMP/TEMP 定向获准临时目录 |
| 客户端语法 | node --check，退出 0 | 不代表安全或 UI 验收 |
| 独立需求门禁 | **16 tests / 7 pass / 9 fail，退出 1** | [失败项与汇总](/e/tmp/sol-web-review-20261118/requirements.tap#L4) |
| 缺陷行为表征 | 16 tests / 16 pass / 0 fail，退出 0 | [逐项确认已观察缺陷](/e/tmp/sol-web-review-20261118/characterization.tap#L4)；此模式断言泄漏/丢失确实存在，不能当产品通过 |
| 产品源码只读复核 | git diff --exit-code 7d9b26df -- 指定被审文件，退出 0 | 报告和临时复现是本轮交付物 |

复现脚本：[review.test.mjs](/e/tmp/sol-web-review-20261118/review.test.mjs#L1)。命令：

```sh
cd /e/tmp/sol-web-review-20261118
node --test review.test.mjs
node review.test.mjs --characterize
```

第二条用来复现需求失败，第三条继续执行各个断言，以确认包括 clipboard 在内的完整缺陷链。需求门禁中安全断言会在默认行第一次发现泄漏时终止该用例；后续展开和复制由表征模式实际验证。

固定输入 SHA-256：

| 输入 | SHA-256 |
| --- | --- |
| git show 7d9b26df 的客户端内容 | d949b638f7796f73c2414ebde9267a78a6c9377f70b2228b27db5df347f25791 |
| [上游 event-projection.ts](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/conversation-nodes/event-projection.ts#L149) | 9b112628ca6b6678504d373a8a017a9cc3c6f89189abd3fa1d38fe035b796a6c |
| [上游 turn-error.ts](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/conversation-nodes/turn-error.ts#L36) | 32a1db551de873ce64a63f2513d4a045ce7d85bc02e3de5a7494417183003054 |

## 问题 1 · P1：把“仅 AUTH 清空”当成通用脱敏，原始错误进入默认行及剪贴板

**证据强度：已离线复现；上游及本层控制流静态确定。未声称设备上已有真实泄漏事件。**

位置：[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:915](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L915)、[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:961](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L961)、[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:1028](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L1028)、[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:1095](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L1095)；上游 [E:/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/conversation-nodes/event-projection.ts:149](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/conversation-nodes/event-projection.ts#L149)。

触发载荷：turn/end.data.reason.kind=error，error={code:"INVALID_CREDENTIAL",message:"Authorization: Bearer SOL_SYNTHETIC_TOKEN_20261118"}。替换为 MISSING_CREDENTIAL、NO_CREDENTIAL_STORE、ACCOUNT_TOKEN_INVALID 或一个未收录的字符串 code，仍可复现。

实际机制：displayFailure 只有 code==='AUTH' 分支清空消息；其他 code 的 message 原样返回，没有 token/Authorization 清洗。插件字典没有覆盖四种已知凭据码，describeTurnError 对非字典码执行 reason=message，摘要也直接返回该 reason；复制文本又拼入同一个 reason。合成 token 因此进入折叠行、展开内容和 clipboard.writeText 的实参。[四种凭据码和未知消息表征](/e/tmp/sol-web-review-20261118/characterization.tap#L5) 全部确认了链路。精确 AUTH 对照在 UI 和剪贴板均不含标记，说明不是测试桩把所有消息都判泄漏。

补充边界：error={code:"UPSTREAM_HTTP",authorization:"Bearer SOL_SYNTHETIC_TOKEN_20261118"} 在缺失 message 时被上游 JSON.stringify，整个对象转成了 node.message；[非标准载荷测试](/e/tmp/sol-web-review-20261118/review.test.mjs#L113) 已复现。它反驳“节点只留几个字段，所以原始 payload 不可能进 UI”的推理，但不把正式生产路径接受畸形对象当成已证明。相反，正式 provider message 风险有真实生产者依据：[providerError](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-llm-deepseek/src/transport.ts#L23) 对 SERVER、HTTP_* 等直接取 error.message；[normalizeLlmFailure](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-llm/src/adapter-failure.ts#L17) 只正规化字段，不移除文本中的秘密。

影响：无需展开就可能暴露原文；用户点击“复制详情”将同样的内容导出。四种凭据码与 Flutter 的 [凭据码集合](/e/tmp/wt-flutter-native-chat/flutter_ui/lib/src/transport/dsh_session_projection.dart#L673) 及其安全本地文案也不一致。上游通用 message 风险在基线已存在；beta.78 新增的本地复制路径扩大了影响，“不可能带出凭据”的新增承诺不成立。不能将上游遗留风险全部归为此次新引入，也不能据此放行新增出口。

最窄修法：在自有客户端边界先生成唯一的安全展示对象；六种凭据码至少与 Flutter 的集合一致，只使用本地通用文案及原 code，禁止原 payload 进入摘要、详情或复制。对所有非凭据码的 provider/tool message 仍须做统一敏感字段/值清洗，不能仅加四个字典项后继续声称通用安全。摘要、展开和复制必须消费同一份经过审查的安全对象；原始诊断留在权威日志，不能盲目拼进 UI。先把本复现的六个安全失败变绿，再验收声明。无需改历史或第三方缓存。

## 问题 2 · P2：signedOut 与 interrupted 被误标为中性，与 Flutter 及用户分类要求相反

**证据强度：已离线复现；静态确定；本轮引入。**

位置：[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:947](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L947)、[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:978](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L978)、[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:1043](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L1043)、[E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs:855](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs#L855)。对照 [E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/model/turn_notice.dart:34](/e/tmp/wt-flutter-native-chat/flutter_ui/lib/src/model/turn_notice.dart#L34) 与 [E:/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/turn_notice_card.dart:24](/e/tmp/wt-flutter-native-chat/flutter_ui/lib/src/ui/turn_notice_card.dart#L24)。

触发载荷：reason={kind:"aborted",reason:{kind:"hook",reason:"deepseek-account/signed-out"}}，或 reason={kind:"interrupted"}。官方投影生成 ACCOUNT_SIGNED_OUT / INTERRUPTED；插件返回 neutral=true 并写 data-neutral=""，图标转成灰色状态图标。Flutter 两者 isFailure=true，signedOut 使用 colors.bad，interrupted 使用 colors.warn，并各自使用退出登录/电源图标。

影响：用户主动停止/父会话停止与需要恢复的异常中断被混成中性；现有客户端测试甚至把此差异固化为通过条件。[两项独立分类门禁](/e/tmp/sol-web-review-20261118/requirements.tap#L11) 均失败；不是用普通正文包含 error 来推断。

最窄修法：取消这两个失败码的 neutral 标记，按结构化 code 映射 signedOut 失败、interrupted 警示；neutral 仅用于明确的 user/parent stop 及 forked 等普通结束。同步修正既有测试的错误期望，保留各自标题与恢复建议。

## 问题 3 · P2：已知码的安全原始说明被丢弃，没有承诺的展开详情

**证据强度：已离线复现；静态确定；本轮新增的“展开详情/复制”承诺未兑现。官方既有展示也会对 QUOTA 采用本地摘要，不把它误记为本轮导致的原始诊断展示回退。**

位置：[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:963](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L963)、[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:1028](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L1028)、[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:1082](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L1082)；协议边界 [E:/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-conversation/src/client/contract/records.ts:133](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-conversation/src/client/contract/records.ts#L133)；承诺 [E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md:62](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md#L62)。

触发载荷：error={code:"QUOTA",message:"billing request=REQ-SOL-42 reset=2026-11-19"}。这是不含秘密的诊断。实际展开及复制都只有本地“当前请求的额度已用尽”与 code，request=REQ-SOL-42 不出现；[详情保全测试](/e/tmp/sol-web-review-20261118/review.test.mjs#L131) 和 [失败证据](/e/tmp/sol-web-review-20261118/requirements.tap#L205) 可复现。

影响：用户无法从“复制详情”得到安全的原始诊断，无法区分额度策略/请求编号等线索。源码注释说 message 留作补充证据，实际渲染树和 copyText 都未使用它。本地 TURN_NOTICE 没有 detail 字段；官方 TurnErrorNode 也仅有 message/code 等字段，不能凭组件名称或报告文字就声称完整 structured detail 已接好。官方 [MessageItem.tsx:51](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/chat/MessageItem.tsx#L51) 也对 QUOTA 使用本地文案，所以这是未满足新增详情要求，并非旧展示回归。上游早已丢弃的 requestId/status 也不是本层新增缺陷，需单独承认协议能力边界。

最窄修法：保留本地短摘要，并将**经过问题 1 同一清洗器处理**且确实存在的 node.message 单独放进展开详情与复制；认证原始 message 仍不得保留。需要完整结构化 detail 时，先定义自有安全投影契约并接线；在此之前把报告限定为“安全消息与 code”，不能假造上游不存在的 detail。

## 问题 4 · P2：AUTH 被断言成“API 密钥无效或过期”，证据不足且可能给错恢复路径

**证据强度：静态确定；本轮新增文案。**

位置：[E:/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js:923](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L923)。真实生产者 [E:/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-llm-deepseek/src/transport.ts:30](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-llm-deepseek/src/transport.ts#L30) 把 HTTP 401、HTTP 403、authentication_error、permission_error 全部归为 AUTH。

触发载荷：上游 HTTP 403 permission_error → code=AUTH；displayFailure 已移除原消息，插件只剩 code。实际却肯定地说“API 密钥无效或已过期”，提示仅检查模型密钥。403 权限拒绝并不证明密钥失效或过期，AUTH 也不携带“这是 API Key 而非账号授权”的区分。

影响：对权限不足或账号授权问题给出过窄的确定原因与操作建议，不符合“不确定原因标可能”。这不是要求回显原认证 payload。

最窄修法：改成与 Flutter 一致的宽泛授权失败文案，或明确“可能未登录、授权失效或权限不足”；恢复建议覆盖登录/授权检查。只有新增安全的结构化细分类才能用确定的“密钥已过期”。

## 问题 5 · P2：复制与分类测试存在假绿断言，46 通过不能支撑交接中的认证复制承诺

**证据强度：静态确定，并由独立安全门禁反证；本轮新增测试。**

位置：[E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs:812](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs#L812)、[E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs:826](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs#L826)、[E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs:855](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-17-conversation-parity-plugin-test/client.test.mjs#L855)；报告声明 [E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md:62](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md#L62)、[E:/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md:70](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md#L70)。

触发条件：copyText2 没完成/复制失败/onCopy 永远不调用。原用例触发异步 onClick 后立即断言 copies>=0，初值就是 0，因此没复制也通过。名为“never shows or copies”的 AUTH 用例只检查 describeTurnError 和元素树，未点击复制、未等异步、未抓 clipboard。分类用例还明确要求异常退出呈中性。四种凭据码、未知消息中的 token、真实上游 JSON fallback、安全诊断丢失均不在原测试保护中。

影响：实际 46/46 数字正确，但测试覆盖不足且两条关键判据不成立。[独立门禁汇总](/e/tmp/sol-web-review-20261118/requirements.tap#L20) 显示九个门禁用例失败，其中一项为非标准载荷防御边界；不能外推为“认证原始 payload 不进 clipboard 已验证”。

最窄修法：用明确可等待的异步动作，分别覆盖 clipboard.writeText 和 legacy execCommand 的实际文本与成功回调；要求 copies===1 并断言复制内容。采用真正经过上游投影的凭据码/未知 message 载荷，加上详情与正确分类测试。任何“上游全已脱敏”声明先改为准确的精确 AUTH 边界，并区分 Node 证明与设备未验证。

## 已确认的接线、分类与状态边界

- **真实入口静态/离线成立**：[failureFrom](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/conversation-nodes/turn-error.ts#L53) 把 durable turn/end 交给 displayFailure；[buildViewNode](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/conversation-nodes/turn-error.ts#L97) 产出 turn-error。[实际 seat](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/chat/ChatNodeSeat.tsx#L150) 传入 node:routedNode、entryKey:routedNode.kind。[插件取值](/e/tmp/wt-flutter-native-chat/app/src/main/assets/android-plugins/turn-rewind/client.js#L998) 读取 props.node.data；不是只搜到字符串或把 toast 当回合提示。
- **优先级匹配**：[官方注册](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/chat/register-node-renderers.ts#L82) 占用相同 key；插件 priority=-10，不声明重复 children。[SDK 优先级契约](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh/docs/subsystems/slots.md#L69) 明确低值胜出。离线 apply 捕获到且只捕获到一份 turn-error 注册。没有执行真实浏览器中的冲突/覆盖测试，官方默认优先级 0 由省略设置与 SDK 契约支持，未在本轮获得设备注册表。
- **卸载方式静态正确**：插件的 slots.inject 回调返回 register disposer；[inject 实现](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-renderer/src/client/registry.ts#L209) 订阅声明期，stop 释放 contribution；[register 实现](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-renderer/src/client/registry.ts#L740) 归入调用方 ctx.effect，[disposer](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-renderer/src/client/registry.ts#L495) 幂等。CSS/Layers 也分别有清理。真实 Cordis 卸载后恢复官方 occupant **未运行验证**，不得从注入桩推导已实测。
- **key 与默认折叠**：官方 turn-error 按 String(turn) 匹配；[context key](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-conversation/src/client/contract/conversation.ts#L341) 以 kind+id 生成稳定键，[chatNode](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/conversation-nodes/common.ts#L64) 保留该键，[chatRenderKey](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/chat/render-entry.ts#L10) 为 React 区分 node/group，[渲染列表](/e/tmp/dsh-flutter-runtime-20261116/runtime/lib/node_modules/@deepseek-ai/dsh-client-ui-chat/src/client/chat/ChatFlow.tsx#L55) 使用此键。插件 useState(false) 初始折叠；Hook 窄桩验证一个键展开后另一个新 turn 仍折叠，原键可再收起。真实 React 重连、窗口分页、跨 session binding 的端到端行为 **未验证**；未发现足以认定串状态的静态证据。

| 结构化终态 | 实际 Web turn-error 路由 | Flutter 分类 | 本轮判断 |
| --- | --- | --- | --- |
| error（普通失败） | 渲染失败行 | failed | 分类方向一致；原文安全见问题 1 |
| error/AUTH | 渲染失败行，原文清空 | failed | 秘密对照通过；具体原因过断言见问题 4 |
| 特定 signed-out hook | ACCOUNT_SIGNED_OUT，插件 neutral | signedOut / failure | 问题 2 |
| interrupted | INTERRUPTED，插件 neutral | interrupted / failure-warning | 问题 2 |
| aborted/user、aborted/parent | 不生成 turn-error，走官方 stopped 呈现 | stopped / neutral | 不会被此插件变成失败卡；没有证明同一折叠详情 UI 覆盖它们 |
| forked | 不生成 turn-error | forked / neutral | 非失败方向一致；Web 无此插件独立详情行 |
| completed | 不生成 turn-error | 无 notice | 一致 |

## 视觉与报告数字复核

- 源码确有 12.5px 字号、行/复制按钮 min-height:48px、摘要 nowrap+ellipsis、原因/code 的 overflow-wrap:anywhere；展开体条件渲染，整条摘要 button 可点，hover/focus-visible 明确。10,000 字符长原因在离线展开树和复制中保全。
- **布局对齐、间距节奏、配色协调、字体层级、响应式**：仅核对上述 CSS；无浏览器布局引擎，未判“窄屏/大字体实测通过”。原因和 code 可断行，但恢复建议/title 未统一设置 overflow-wrap，须真实窄屏载荷检验；当前中文固定短文案不足以认定阻塞。
- **交互状态**：折叠/展开、行按钮 focus 样式、copy 成功值及现代/legacy 路径有上述证据；系统 clipboard 失败 Toast、TalkBack/live region、字体放大、禁用/加载状态和减少动效未设备验证。turn-error 无发请求动作，loading/disabled 不是本组件常态，不据此虚构缺陷。
- [设备数据段](/e/tmp/wt-flutter-native-chat/docs/reports/2026-11-18-floating-composer-handoff.md#L65) 的 height=48、91.45x48 与源码 minimum 方向相容，但 CSS px 不自动证明任意 Android 缩放下的实际 dp；本轮未重新采集 CDP、截图或设备密度。原有 46/46 数字复核一致。Flutter 434、APK 字节/哈希、视觉按钮 dp 由其他审查范围覆盖，本报告不复核、不背书。
- 交接“上游已脱敏 detail / 认证原始 payload 不进 clipboard”与实际边界不符，已按问题 1、3、5 给证据；设备 INTERRUPTED 演示只能证明那一类可展开/收起，不能覆盖认证、未知错误或详情保全。

## 交回主代理的最小处置顺序

1. 先修问题 1 的唯一安全展示/复制对象；用当前临时需求门禁中的六项安全失败验证，禁止只补 AUTH 对照。
2. 修 signedOut/interrupted 分类、AUTH 不确定性与安全详情保全；同步移除现有测试的错误期望和 copies>=0。
3. 更新交接承诺的实际范围，再由授权执行者在 Android WebView 对同一份最终产物验收认证/未知/长文本/新错误/复制/卸载。当前任务范围不包含代修、构建或设备验收。

本报告只读核查已完成，无等待其他 Sol 的依赖。回滚本轮交付仅需撤去本新增报告及指定临时复现目录；不要回退产品源码或其他代理的输出。

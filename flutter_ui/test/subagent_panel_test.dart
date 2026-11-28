import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/subagent_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/subagent_panel.dart';
import 'package:dsh_native_chat/src/ui/composer_surface.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:dsh_native_chat/src/ui/tool_run_card.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// Fixture-only parent transport: it publishes a demo agent catalog and has
/// no subagent capability, so the panel must refuse to fake a child session.
class _CatalogTransport implements DshTransport {
  final _events = EventStreamController();
  int sequence = 0;

  @override
  Stream<ChatEvent> get events => _events.stream;
  @override
  bool get isFixture => true;

  @override
  Future<void> connect() async {
    _events.add(
      const ChatEvent.snapshot(generation: 1, sequence: 0, messages: []),
    );
    sequence = 1;
  }

  void publish(List<AgentTask> agents) {
    _events.add(
      ChatEvent.toolStarted(
        generation: 1,
        sequence: sequence,
        toolId: 'fixture-tool-' + sequence.toString(),
        name: '子智能体',
        content: ToolContent(title: '演示目录', agents: agents),
      ),
    );
    sequence++;
  }

  @override
  Future<void> reconnect() async {}
  @override
  Future<void> sendPrompt(String text) async {}
  @override
  Future<void> stop() async {}
  @override
  Future<void> approve(String requestId) async {}
  @override
  Future<void> answer(String requestId, String answer) async {}
  @override
  Future<void> close() => _events.close();
}

/// Fixture child service. Replies are only what the test explicitly returns.
class _ChildService implements SubagentTransport, DshTransport {
  _ChildService(this.initial) {
    addTearDown(changes.close);
  }
  SubagentConversation initial;
  final changes = StreamController<SubagentConversation>.broadcast(sync: true);
  Completer<void> gate = Completer<void>();
  Completer<void>? readGate;
  final sent = <String>[];
  int watches = 0, reads = 0, cancels = 0;
  bool failRead = false, failSend = false;
  String delivery = 'queue';

  @override
  Future<SubagentConversation> readSubagent(SubagentAddress address) async {
    reads++;
    await readGate?.future;
    if (failRead) throw StateError('child offline');
    return SubagentConversation(
      address: address,
      messages: initial.messages,
      tools: initial.tools,
      revision: initial.revision,
      running: initial.running,
      modelLabel: initial.modelLabel,
    );
  }

  @override
  Stream<SubagentConversation> watchSubagent(SubagentAddress address) {
    watches++;
    changes.onCancel = () => cancels++;
    return changes.stream;
  }

  @override
  Future<SubagentReceipt> sendSubagent(
    SubagentAddress address,
    String text, {
    required String requestId,
  }) async {
    sent.add(text);
    await gate.future;
    if (failSend) throw StateError('send offline');
    return SubagentReceipt(requestId: requestId, delivery: delivery);
  }

  @override
  Stream<ChatEvent> get events => const Stream.empty();
  @override
  bool get isFixture => true;
  @override
  Future<void> connect() async {}
  @override
  Future<void> reconnect() async {}
  @override
  Future<void> sendPrompt(String text) async {}
  @override
  Future<void> stop() async {}
  @override
  Future<void> approve(String requestId) async {}
  @override
  Future<void> answer(String requestId, String answer) async {}
  @override
  Future<void> close() async {}
}

class _InteractiveCatalogTransport extends _CatalogTransport
    implements SubagentTransport {
  _InteractiveCatalogTransport(this.child);

  final _ChildService child;

  @override
  Future<SubagentConversation> readSubagent(SubagentAddress address) =>
      child.readSubagent(address);

  @override
  Stream<SubagentConversation> watchSubagent(SubagentAddress address) =>
      child.watchSubagent(address);

  @override
  Future<SubagentReceipt> sendSubagent(
    SubagentAddress address,
    String text, {
    required String requestId,
  }) => child.sendSubagent(address, text, requestId: requestId);
}

const _child = SubagentAddress(
  parentSessionId: 'parent-1',
  childSessionId: 'child-1',
  mode: SubagentMode.continuable,
);

AgentTask _agent(
  String id,
  String name, {
  AgentState status = AgentState.running,
  String detail = '演示摘要',
  SubagentAddress? address,
}) => AgentTask(
  id: id,
  name: name,
  task: '整理验证证据',
  status: status,
  detail: detail,
  address: address,
);

SubagentConversation _conversation(
  List<SubagentMessage> messages, {
  int revision = 1,
  bool running = false,
  String? modelLabel,
}) => SubagentConversation(
  address: _child,
  messages: messages,
  revision: revision,
  running: running,
  modelLabel: modelLabel,
);

Future<void> _pumpPage(
  WidgetTester tester,
  AgentTask agent,
  _ChildService service,
) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: SubagentChatPage(
        key: const ValueKey('child-page'),
        agent: agent,
        service: service,
      ),
    ),
  );
  await tester.pump();
  await tester.pump();
}

String _draft(WidgetTester tester) => tester
    .widget<TextField>(find.byKey(const ValueKey('subagent-input')))
    .controller!
    .text;

Future<ChatController> _openPanel(
  WidgetTester tester,
  _CatalogTransport transport,
) async {
  final controller = ChatController(transport);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            key: const ValueKey('open-subagents'),
            onPressed: () => showSubagentPanel(context, controller: controller),
            child: const Text('母会话'),
          ),
        ),
      ),
    ),
  );
  await controller.start();
  await tester.pump();
  await tester.tap(find.byKey(const ValueKey('open-subagents')));
  await tester.pumpAndSettle();
  return controller;
}

void main() {
  testWidgets('child guard hides input for a self-address', (tester) async {
    final service = FixtureTransport();
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: SubagentChatPage(
          agent: _agent(
            'self',
            'invalid',
            address: const SubagentAddress(
              parentSessionId: 'same',
              childSessionId: 'same',
              mode: SubagentMode.continuable,
            ),
          ),
          service: service,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('subagent-input')), findsNothing);
    expect(find.textContaining('不可用'), findsWidgets);
    await tester.pumpWidget(const SizedBox());
    await service.close();
  });

  testWidgets(
    'child uses the shared flow, selectable rows, disclosure and floating surface',
    (tester) async {
      final service = _ChildService(
        SubagentConversation(
          address: _child,
          revision: 1,
          messages: const [
            SubagentMessage(id: 'answer', role: 'assistant', text: '真实子会话正文'),
          ],
          // Two tools are what make the child trace collapsible; a single tool
          // renders its own row, which the child page must do the same way.
          tools: [
            ToolRun(
              id: 'child-command',
              name: 'bash',
              messageId: 'answer',
              status: ToolStatus.complete,
              content: ToolContent(
                title: 'bash',
                command: const CommandResult(
                  command: 'pwd',
                  stdout: 'CHILD OUTPUT',
                  exitCode: 0,
                ),
              ),
            ),
            const ToolRun(
              id: 'child-thought',
              name: '思考过程',
              messageId: 'answer',
              status: ToolStatus.complete,
              content: null,
            ),
          ],
        ),
      );
      await _pumpPage(
        tester,
        _agent('review', '代码检查', address: _child),
        service,
      );
      expect(find.byType(ConversationFlow), findsOneWidget);
      expect(find.byType(ConversationMessage), findsOneWidget);
      expect(find.byType(ComposerSurface), findsOneWidget);
      expect(find.byType(ComposerActionButton), findsOneWidget);
      expect(
        find.descendant(
          of: find.byType(ConversationMessage),
          matching: find.byType(SelectableText),
        ),
        findsOneWidget,
      );
      expect(find.byType(ToolRunCard), findsNothing);
      final toggle = find.byKey(
        const ValueKey('execution-toggle-message-answer'),
      );
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      // Expanding the trace reveals every tool it holds, not a fixed count.
      expect(find.byType(ToolRunCard), findsWidgets);
      expect(
        find.byWidgetPredicate(
          (widget) => widget is ToolRunCard && widget.tool.id == 'child-command',
        ),
        findsOneWidget,
      );
      await tester.ensureVisible(
        find.byKey(const ValueKey('process-command-child-command')),
      );
      await tester.tap(
        find.byKey(const ValueKey('process-command-child-command')),
      );
      await tester.pumpAndSettle();
      expect(find.text('CHILD OUTPUT'), findsOneWidget);
      // A child conversation never offers model selection: no picker entry,
      // no model sheet, and no selectModel call path exists on this page.
      expect(find.byKey(const ValueKey('composer-model')), findsNothing);
      expect(find.byKey(const ValueKey('composer-attach')), findsNothing);
      expect(find.byKey(const ValueKey('composer-permission')), findsNothing);
      expect(find.byKey(const ValueKey('subagent-stop')), findsNothing);
    },
  );

  testWidgets(
    'running continuable child still accepts one queued send without fabricated messages',
    (tester) async {
      final service = _ChildService(_conversation(const [], running: true));
      await _pumpPage(
        tester,
        _agent('review', '代码检查', address: _child),
        service,
      );
      await tester.enterText(
        find.byKey(const ValueKey('subagent-input')),
        '运行中补充',
      );
      await tester.pump();
      expect(find.byTooltip('排队到下一轮'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('subagent-send')));
      await tester.pump();
      expect(service.sent, ['运行中补充']);
      expect(find.byType(ConversationMessage), findsNothing);
      service.gate.complete();
      await tester.pump();
      await tester.pump();
      expect(find.text('已排队'), findsOneWidget);
      expect(find.byType(ConversationMessage), findsNothing);
    },
  );

  testWidgets('fixture 目录实时聚合、按 id 与父子地址去重并可展开', (tester) async {
    final transport = _CatalogTransport();
    final controller = await _openPanel(tester, transport);
    expect(find.text('演示：当前会话还没有子智能体'), findsOneWidget);
    expect(find.textContaining('fixture 演示'), findsOneWidget);

    transport.publish([
      _agent('review', '旧检查', detail: '旧摘要'),
      _agent('docs', '文档整理', status: AgentState.completed),
    ]);
    transport.publish([
      _agent('review', '代码检查', address: _child),
      // Same parent/child under another id and mode: replaces the row.
      _agent(
        'review-latest',
        '代码检查·最新',
        status: AgentState.waiting,
        detail: '新摘要：保留现有接口。',
        address: const SubagentAddress(
          parentSessionId: 'parent-1',
          childSessionId: 'child-1',
          mode: SubagentMode.oneShot,
        ),
      ),
    ]);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('subagent-review')), findsNothing);
    expect(
      find.byKey(const ValueKey('subagent-review-latest')),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('subagent-docs')), findsOneWidget);
    expect(find.text('旧检查'), findsNothing);
    expect(find.text('旧摘要'), findsNothing);
    expect(find.text('代码检查·最新'), findsOneWidget);
    expect(find.text('等待中'), findsOneWidget);
    expect(find.text('新摘要：保留现有接口。'), findsOneWidget);
    expect(
      tester
          .getTopLeft(find.byKey(const ValueKey('subagent-review-latest')))
          .dy,
      lessThan(
        tester.getTopLeft(find.byKey(const ValueKey('subagent-docs'))).dy,
      ),
    );

    final latest = find.byKey(const ValueKey('subagent-review-latest'));
    expect(
      find.descendant(of: latest, matching: find.text('整理验证证据')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('subagent-open-review-latest')),
      findsOneWidget,
    );
    expect(
      tester
          .widget<TextButton>(
            find.byKey(const ValueKey('subagent-open-review-latest')),
          )
          .onPressed,
      isNull,
    );
    await tester.tap(
      find.byKey(const ValueKey('subagent-expand-review-latest')),
    );
    await tester.pumpAndSettle();
    expect(
      find.descendant(of: latest, matching: find.text('新摘要：保留现有接口。')),
      findsNWidgets(2),
    );

    final sheetHeight = tester
        .getSize(find.byKey(const ValueKey('subagent-panel')))
        .height;
    expect(sheetHeight, lessThanOrEqualTo(600 * 0.85 + 0.5));

    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('不支持子会话时入口直接显示原因并禁用', (tester) async {
    final transport = _CatalogTransport();
    final controller = await _openPanel(tester, transport);
    transport.publish([_agent('review', '代码检查', address: _child)]);
    await tester.pumpAndSettle();
    expect(find.textContaining('不支持子会话'), findsOneWidget);
    final entry = find.byKey(const ValueKey('subagent-open-review'));
    expect(tester.widget<TextButton>(entry).onPressed, isNull);
    expect(find.byKey(const ValueKey('subagent-input')), findsNothing);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('可用入口真实订阅子会话，返回直接回到母会话', (tester) async {
    final service = _ChildService(
      _conversation(const [
        SubagentMessage(id: 'real', role: 'assistant', text: '订阅读取的正文'),
      ]),
    );
    final transport = _InteractiveCatalogTransport(service);
    final controller = await _openPanel(tester, transport);
    transport.publish([_agent('review', '代码检查', address: _child)]);
    await tester.pumpAndSettle();
    final entry = find.byKey(const ValueKey('subagent-open-review'));
    expect(tester.widget<TextButton>(entry).onPressed, isNotNull);
    await tester.tap(entry);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('subagent-panel')), findsNothing);
    expect(find.text('订阅读取的正文'), findsOneWidget);
    expect(service.reads, 1);
    expect(service.watches, 1);
    await tester.tap(find.byKey(const ValueKey('subagent-back')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('open-subagents')), findsOneWidget);
    expect(find.byKey(const ValueKey('subagent-panel')), findsNothing);
    expect(service.cancels, 1);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('缺少地址时列表说明不可用，不读取也不发送', (tester) async {
    final service = _ChildService(_conversation(const []));
    final controller = await _openPanel(
      tester,
      _InteractiveCatalogTransport(service),
    );
    final transport = controller.transport as _InteractiveCatalogTransport;
    transport.publish([_agent('missing', '缺少地址')]);
    await tester.pumpAndSettle();
    expect(find.textContaining('缺少完整子会话地址'), findsOneWidget);
    expect(
      tester
          .widget<TextButton>(
            find.byKey(const ValueKey('subagent-open-missing')),
          )
          .onPressed,
      isNull,
    );
    expect(service.reads, 0);
    expect(service.watches, 0);
    expect(service.sent, isEmpty);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('同一更新同时连接已有 id 和地址时只保留最新一条', (tester) async {
    final transport = _CatalogTransport();
    final controller = await _openPanel(tester, transport);
    transport.publish([
      _agent('a', '第一条'),
      _agent('b', '另一条', address: _child),
      _agent('a', '合并后的最新状态', status: AgentState.completed, address: _child),
    ]);
    await tester.pump();
    expect(collectSubagents(controller).length, 1);
    expect(collectSubagents(controller).single.name, '合并后的最新状态');
    expect(collectSubagents(controller).single.status, AgentState.completed);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('读取期间显示 loading，正文只来自 read 结果', (tester) async {
    final service = _ChildService(
      _conversation(const [
        SubagentMessage(id: 'u1', role: 'user', text: '请检查边界'),
        SubagentMessage(id: 'a1', role: 'assistant', text: '我在检查边界。'),
      ], running: true),
    )..readGate = Completer<void>();
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    expect(find.byKey(const ValueKey('subagent-loading')), findsOneWidget);
    expect(find.text('我在检查边界。'), findsNothing);

    service.readGate!.complete();
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('subagent-loading')), findsNothing);
    expect(find.text('请检查边界'), findsOneWidget);
    expect(find.text('我在检查边界。'), findsOneWidget);
    expect(find.text('代码检查', findRichText: true), findsOneWidget);
    expect(find.byKey(const ValueKey('subagent-busy')), findsOneWidget);
    expect(find.textContaining('fixture 演示'), findsOneWidget);
    expect(service.reads, 1);
    expect(service.watches, 1);
  });

  testWidgets('continuable 只发送一次，排队回执独立显示且不伪造回复', (tester) async {
    final service = _ChildService(_conversation(const []))..delivery = 'queued';
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);

    await tester.enterText(
      find.byKey(const ValueKey('subagent-input')),
      '请只读核对\n不要改文件',
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('subagent-send')));
    await tester.pump();
    expect(find.byTooltip('发送中'), findsOneWidget);
    expect(
      tester
          .widget<IconButton>(find.byKey(const ValueKey('subagent-send')))
          .onPressed,
      isNull,
    );
    await tester.tap(find.byKey(const ValueKey('subagent-send')));
    await tester.pump();
    expect(service.sent, ['请只读核对\n不要改文件']);

    service.gate.complete();
    await tester.pump();
    await tester.pump();
    expect(_draft(tester), isEmpty);
    expect(find.text('已排队'), findsOneWidget);
    expect(find.text('queued'), findsNothing);
    expect(find.text('请只读核对\n不要改文件'), findsNothing);
    expect(find.byTooltip('发送'), findsOneWidget);
  });

  testWidgets('child shows the inherited model read only and cannot change it', (
    tester,
  ) async {
    final service = _ChildService(
      _conversation(const [], modelLabel: 'deepseek-v4.1-flash'),
    );
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    // The capsule paints the shortened brand token, while the value the Host
    // reported for that child stays available as the tooltip/semantics label.
    final chip = find.byKey(const ValueKey('subagent-model-readonly'));
    expect(chip, findsOneWidget);
    expect(
      find.descendant(of: chip, matching: find.text('ds-v4.1-flash')),
      findsOneWidget,
    );
    final pill = tester.widget<ComposerModelPill>(
      find.descendant(of: chip, matching: find.byType(ComposerModelPill)),
    );
    expect(pill.semanticLabel, 'deepseek-v4.1-flash');
    // It is inert: tapping it opens no sheet and the model selector entry that
    // the parent composer has does not exist here at all.
    await tester.tap(find.byType(ComposerModelPill));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));
    expect(find.text('选择模型'), findsNothing);
    expect(find.byKey(const ValueKey('model-option-m-one')), findsNothing);
    expect(find.byKey(const ValueKey('composer-model')), findsNothing);
  });

  testWidgets('一次性、未知模式只读；缺地址和空地址不可用；均不发送', (tester) async {
    final cases = <(AgentTask, String, int)>[
      (
        _agent(
          'once',
          '一次检查',
          address: const SubagentAddress(
            parentSessionId: 'p',
            childSessionId: 'c',
            mode: SubagentMode.oneShot,
          ),
        ),
        '只读',
        1,
      ),
      (
        _agent(
          'unknown',
          '未知模式',
          address: const SubagentAddress(
            parentSessionId: 'p',
            childSessionId: 'c',
            mode: SubagentMode.unknown,
          ),
        ),
        '只读',
        1,
      ),
      (_agent('missing', '缺地址'), '不可用', 0),
      (
        _agent(
          'blank',
          '空地址',
          address: const SubagentAddress(
            parentSessionId: '',
            childSessionId: 'c',
            mode: SubagentMode.continuable,
          ),
        ),
        '不可用',
        0,
      ),
    ];
    for (final (agent, label, reads) in cases) {
      final service = _ChildService(
        _conversation(const [
          SubagentMessage(id: 'a', role: 'assistant', text: '历史正文'),
        ]),
      );
      await _pumpPage(tester, agent, service);
      expect(find.textContaining(label), findsOneWidget, reason: agent.id);
      expect(find.byKey(const ValueKey('subagent-input')), findsNothing);
      expect(find.byKey(const ValueKey('subagent-send')), findsNothing);
      expect(service.reads, reads, reason: agent.id);
      expect(find.text('历史正文'), reads == 1 ? findsOneWidget : findsNothing);
      expect(service.sent, isEmpty);
    }
  });

  testWidgets('发送失败保留草稿并可重发', (tester) async {
    final service = _ChildService(_conversation(const []))..failSend = true;
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    await tester.enterText(find.byKey(const ValueKey('subagent-input')), '保留我');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('subagent-send')));
    await tester.pump();
    service.gate.complete();
    await tester.pump();
    await tester.pump();
    expect(_draft(tester), '保留我');
    expect(find.textContaining('send offline'), findsOneWidget);
    expect(find.text('已送达'), findsNothing);
    expect(
      tester
          .widget<IconButton>(find.byKey(const ValueKey('subagent-send')))
          .onPressed,
      isNotNull,
    );
  });

  testWidgets('发送中编辑过的草稿不被成功结果清空，即使改回原文', (tester) async {
    final service = _ChildService(_conversation(const []))
      ..delivery = 'delivered';
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    await tester.enterText(find.byKey(const ValueKey('subagent-input')), '第一条');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('subagent-send')));
    await tester.pump();
    await tester.enterText(
      find.byKey(const ValueKey('subagent-input')),
      '第一条补充',
    );
    await tester.enterText(find.byKey(const ValueKey('subagent-input')), '第一条');
    await tester.pump();
    service.gate.complete();
    await tester.pump();
    await tester.pump();
    expect(_draft(tester), '第一条');
    expect(find.text('已送达'), findsOneWidget);
    expect(service.sent, ['第一条']);
  });

  testWidgets('读取失败可重试并重新订阅，退出页面取消订阅', (tester) async {
    final service = _ChildService(
      _conversation(const [
        SubagentMessage(id: 'a', role: 'assistant', text: '重试后的正文'),
      ]),
    )..failRead = true;
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    expect(find.textContaining('child offline'), findsOneWidget);
    expect(find.text('重试后的正文'), findsNothing);

    service.failRead = false;
    await tester.tap(find.byKey(const ValueKey('subagent-retry')));
    await tester.runAsync(() async {
      await Future<void>.delayed(Duration.zero);
    });
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 1));
    }
    expect(service.cancels, 1, reason: 'old watch must cancel');
    expect(service.watches, 2, reason: 'retry watch must restart');
    expect(
      service.reads,
      2,
      reason: 'retry must reach its second read after cancel',
    );
    expect(find.text('重试后的正文'), findsOneWidget);
    expect(find.byKey(const ValueKey('subagent-retry')), findsNothing);
    expect(service.watches, 2);
    expect(service.cancels, 1);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(service.cancels, 2);
    service.changes.add(_conversation(const [], revision: 9));
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('退出后晚到的发送结果安全丢弃', (tester) async {
    final service = _ChildService(_conversation(const []));
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    await tester.enterText(find.byKey(const ValueKey('subagent-input')), '晚到');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('subagent-send')));
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
    service.gate.complete();
    await tester.pump();
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(service.sent, ['晚到']);
  });

  testWidgets('退出后晚到的真实挂起读取安全丢弃', (tester) async {
    final service = _ChildService(
      _conversation(const [
        SubagentMessage(id: 'late', role: 'assistant', text: '不应在退出后出现'),
      ]),
    )..readGate = Completer<void>();
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    expect(service.reads, 1);
    expect(find.byKey(const ValueKey('subagent-loading')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    service.readGate!.complete();
    await tester.pump();
    await tester.pump();
    expect(service.cancels, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('同地址元数据更新保留草稿且不重复读取', (tester) async {
    final service = _ChildService(_conversation(const []));
    await _pumpPage(tester, _agent('review', '检查中', address: _child), service);
    await tester.enterText(
      find.byKey(const ValueKey('subagent-input')),
      '待发草稿',
    );
    await _pumpPage(
      tester,
      _agent('review-new', '已完成检查', address: _child),
      service,
    );
    expect(_draft(tester), '待发草稿');
    expect(service.reads, 1);
    expect(service.cancels, 0);
  });

  testWidgets('切换子会话清旧稿，旧发送完成不影响新稿', (tester) async {
    final service = _ChildService(_conversation(const []));
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    await tester.enterText(
      find.byKey(const ValueKey('subagent-input')),
      '给旧会话',
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('subagent-send')));
    await tester.pump();
    const nextAddress = SubagentAddress(
      parentSessionId: 'parent-1',
      childSessionId: 'child-2',
      mode: SubagentMode.continuable,
    );
    await _pumpPage(
      tester,
      _agent('review-next', '新检查', address: nextAddress),
      service,
    );
    expect(_draft(tester), isEmpty);
    await tester.enterText(
      find.byKey(const ValueKey('subagent-input')),
      '给新会话',
    );
    service.gate.complete();
    await tester.pump();
    await tester.pump();
    expect(_draft(tester), '给新会话');
    expect(service.sent, ['给旧会话']);
    expect(service.reads, 2);
    expect(service.cancels, 1);
    expect(find.byKey(const ValueKey('subagent-receipt')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('watch 的真实增量显示为助手正文', (tester) async {
    final service = _ChildService(_conversation(const []));
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    service.changes.add(
      _conversation(const [
        SubagentMessage(id: 'stream', role: 'assistant', text: '订阅产生的真实回复'),
      ], revision: 2),
    );
    await tester.pump();
    expect(find.text('订阅产生的真实回复'), findsOneWidget);
    expect(find.byKey(const ValueKey('subagent-receipt')), findsNothing);
  });

  testWidgets('未知送达状态不会显示为已送达', (tester) async {
    final service = _ChildService(_conversation(const []))
      ..delivery = 'pending';
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    await tester.enterText(find.byKey(const ValueKey('subagent-input')), '请检查');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('subagent-send')));
    service.gate.complete();
    await tester.pump();
    await tester.pump();
    expect(find.text('已送达'), findsNothing);
    expect(find.text('回执状态：pending'), findsOneWidget);
  });

  testWidgets('空白草稿不可发送', (tester) async {
    final service = _ChildService(_conversation(const []));
    await _pumpPage(tester, _agent('review', '代码检查', address: _child), service);
    await tester.enterText(
      find.byKey(const ValueKey('subagent-input')),
      '   \n  ',
    );
    await tester.pump();
    expect(
      tester
          .widget<IconButton>(find.byKey(const ValueKey('subagent-send')))
          .onPressed,
      isNull,
    );
    expect(service.sent, isEmpty);
  });

  testWidgets('360 宽、两倍文字与键盘弹出时可滚动且无溢出', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final service = _ChildService(
      _conversation([
        for (var i = 0; i < 12; i++)
          SubagentMessage(
            id: 'm' + i.toString(),
            role: i.isEven ? 'user' : 'assistant',
            text: '演示正文 ' + i.toString() + '：窄屏和放大文字时也要完整换行并可滚动阅读。',
          ),
      ]),
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: const TextScaler.linear(2),
            viewInsets: const EdgeInsets.only(bottom: 260),
          ),
          child: child!,
        ),
        home: SubagentChatPage(
          agent: _agent('review', '代码检查·长名称也只占一行', address: _child),
          service: service,
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    expect(tester.takeException(), isNull);
    await tester.enterText(
      find.byKey(const ValueKey('subagent-input')),
      ['第一行', '第二行', '第三行', '第四行', '第五行', '第六行'].join('\n'),
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
    final sendRect = tester.getRect(
      find.byKey(const ValueKey('subagent-send')),
    );
    expect(sendRect.bottom, lessThanOrEqualTo(640 - 260));
    expect(sendRect.left, greaterThanOrEqualTo(0));
    expect(sendRect.right, lessThanOrEqualTo(360));
    final send = sendRect.size;
    expect(send.width, greaterThanOrEqualTo(48));
    expect(send.height, greaterThanOrEqualTo(48));
    final scrollable = find.ancestor(
      of: find.descendant(
        of: find.byKey(const ValueKey('subagent-messages')),
        matching: find.byType(SliverList),
      ),
      matching: find.byType(Scrollable),
    );
    // The floating composer covers the scrollable centre, so a drag-based
    // scrollUntilVisible starts its gesture on chrome and never moves the list.
    // Drive the position directly instead; the assertion below still requires
    // the last row to become reachable on a 360x640 screen with 2x text.
    final pos = tester.state<ScrollableState>(scrollable).position;
    // Lazy slivers revise their estimated extent while scrolling, so a single
    // jump to maxScrollExtent lands short of the real end. Settle it first.
    for (var i = 0; i < 8; i++) {
      await tester.pump();
      pos.jumpTo(pos.maxScrollExtent);
    }
    await tester.pump();
    // The overflowing title intentionally keeps animating.
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(tester.takeException(), isNull);
    expect(find.textContaining('演示正文 11'), findsOneWidget);
  });
}

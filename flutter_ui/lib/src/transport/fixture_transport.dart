import 'dart:async';

import '../model/subagent_model.dart';
import '../model/chat_model.dart';
import '../model/tool_content.dart';
import '../model/composer_model.dart';
import 'dsh_transport.dart';

class FixtureTransport
    implements DshTransport, ComposerTransport, SubagentTransport {
  FixtureTransport();
  @override
  List<ComposerModel> get models => const [
    ComposerModel('fixture-balanced', '标准 · 演示', '确定性流程演示，不调用付费模型'),
    ComposerModel('fixture-fast', '快速 · 演示', '简短回复演示，不调用付费模型'),
  ];
  final List<PromptSubmission> _submissions = [];
  List<PromptSubmission> get submissions => List.unmodifiable(_submissions);
  bool _autoRead = false;
  String? _replyOverride;
  @override
  Future<void> sendSubmission(PromptSubmission submission) async {
    _ensureConnected();
    if (!models.any((m) => m.id == submission.modelId))
      throw const TransportException('演示模型不可用');
    _submissions.add(submission);
    _autoRead = submission.permission == ComposerPermission.readOnly;
    _replyOverride = submission.modelId == 'fixture-fast' ? '收到，开始只读检查。' : null;
    await sendPrompt(submission.displayText);
  }

  final EventStreamController _events = EventStreamController();
  int _generation = 1;
  int _sequence = 0;
  bool _connected = false;
  String? _lastToolId;
  String? _lastActionId;

  @override
  Stream<ChatEvent> get events => _events.stream;

  @override
  bool get isFixture => true;

  @override
  Future<void> connect() async {
    if (_connected) return;
    _connected = true;
    _events.add(
      ChatEvent.snapshot(
        generation: _generation,
        sequence: _sequence,
        messages: const [
          ChatMessage(
            id: 'fixture-history-user',
            role: ChatRole.user,
            text: '帮我检查一下项目状态，并说明接下来该做什么。',
          ),
          ChatMessage(
            id: 'fixture-history-assistant',
            role: ChatRole.assistant,
            text: '我会先读取工作区状态，再给出下一步建议。',
          ),
        ],
      ),
    );
    _sequence++;
  }

  @override
  Future<void> sendPrompt(String text) async {
    _ensureConnected();
    final userId = 'fixture-user-' + _sequence.toString();
    final assistantId = 'fixture-assistant-' + _sequence.toString();
    _emit(
      ChatEvent.delta(
        generation: _generation,
        sequence: _sequence,
        messageId: userId,
        role: ChatRole.user,
        text: text,
        done: true,
      ),
    );
    _emit(
      ChatEvent.delta(
        generation: _generation,
        sequence: _sequence,
        messageId: assistantId,
        role: ChatRole.assistant,
        text: _replyOverride ?? '收到。我先执行一项只读检查。',
        done: false,
      ),
    );
    _lastToolId = 'fixture-tool-' + _sequence.toString();
    _emit(
      ChatEvent.toolStarted(
        generation: _generation,
        sequence: _sequence,
        toolId: _lastToolId!,
        name: '读取项目状态',
      ),
    );
    if (_autoRead) {
      await approve(_lastToolId!);
      _autoRead = false;
      return;
    }
    _lastActionId = 'fixture-approval-' + _sequence.toString();
    _emit(
      ChatEvent.approval(
        generation: _generation,
        sequence: _sequence,
        requestId: _lastActionId!,
        title: '允许继续读取吗？',
        detail: '这是一个只读检查，不会修改项目文件。',
      ),
    );
  }

  @override
  Future<void> approve(String requestId) async {
    _ensureConnected();
    _emit(
      ChatEvent.toolFinished(
        generation: _generation,
        sequence: _sequence,
        toolId: _lastToolId ?? requestId,
        status: ToolStatus.complete,
        detail: '工作区干净度和引擎状态已读取。',
        elapsedMs: 184,
      ),
    );
    _emit(
      ChatEvent.delta(
        generation: _generation,
        sequence: _sequence,
        messageId: 'fixture-assistant-result-' + _sequence.toString(),
        role: ChatRole.assistant,
        text: '检查完成：当前可以继续做 Flutter 聊天页的协议接线。',
        done: true,
      ),
    );
    _emit(ChatEvent.turnComplete(generation: _generation, sequence: _sequence));
  }

  @override
  Future<void> answer(String requestId, String answer) async {
    _ensureConnected();
    _emit(
      ChatEvent.delta(
        generation: _generation,
        sequence: _sequence,
        messageId: 'fixture-answer-' + _sequence.toString(),
        role: ChatRole.assistant,
        text: '已记录你的回答：' + answer,
        done: true,
      ),
    );
    _emit(ChatEvent.turnComplete(generation: _generation, sequence: _sequence));
  }

  @override
  Future<void> stop() async {
    if (!_connected) return;
    _emit(
      ChatEvent.toolFinished(
        generation: _generation,
        sequence: _sequence,
        toolId: _lastToolId ?? 'fixture-tool-stopped',
        status: ToolStatus.cancelled,
        detail: '已由用户停止。',
      ),
    );
    _emit(ChatEvent.turnComplete(generation: _generation, sequence: _sequence));
  }

  @override
  Future<void> close() async {
    _connected = false;
    await _childUpdates.close();
    await _events.close();
  }

  /// Deterministic, explicitly labeled UI coverage. No real work is executed.
  void emitShowcase() {
    _ensureConnected();
    final messageId = 'fixture-showcase-' + _sequence.toString();
    _emit(
      ChatEvent.delta(
        generation: _generation,
        sequence: _sequence,
        messageId: messageId,
        role: ChatRole.assistant,
        text: '工具展示演示。计划、协作进度和交付物可按需展开；此处没有执行真实任务。',
        done: true,
      ),
    );
    final outputs = <(String, String, ToolContent)>[
      (
        'thinking',
        '思考过程',
        ToolContent(
          title: '检查思路',
          reasoning: '先核对数据和调用边界，再分别检查界面和状态更新。这里展示的是公开的过程摘要。',
        ),
      ),
      (
        'command',
        '执行命令',
        ToolContent(
          title: '运行检查',
          command: const CommandResult(
            command: 'flutter analyze',
            cwd: '/workspace/dsh-mobile',
            stdout: 'Analyzing dsh_native_chat…\nNo issues found!',
            exitCode: 0,
          ),
        ),
      ),
      (
        'code',
        '修改代码',
        ToolContent(
          title: '更新界面',
          edits: const [
            CodeEdit(
              path: 'lib/chat/composer.dart',
              language: 'dart',
              before: 'const gap = 12;',
              after: 'const gap = 8;',
              unifiedDiff: '@@ -1 +1 @@\n-const gap = 12;\n+const gap = 8;',
            ),
          ],
        ),
      ),
      (
        'plan',
        '检查计划',
        ToolContent(
          title: '整理工作区',
          steps: const [
            PlanStep(id: 'read', title: '读取项目状态', status: StepState.completed),
            PlanStep(id: 'check', title: '核对关键配置', status: StepState.completed),
            PlanStep(
              id: 'summarize',
              title: '整理验证报告',
              status: StepState.completed,
            ),
          ],
        ),
      ),
      (
        'agents',
        '子智能体 · 2 项已完成',
        ToolContent(
          title: '协作结果',
          agents: const [
            AgentTask(
              id: 'review',
              address: SubagentAddress(
                parentSessionId: 'fixture-parent',
                childSessionId: 'review',
                mode: SubagentMode.continuable,
              ),
              name: '代码检查',
              task: '检查变更的边界与回归风险',
              status: AgentState.completed,
              detail: '演示结果：保留已有接口，未发现阻塞项。',
            ),
            AgentTask(
              id: 'docs',
              address: SubagentAddress(
                parentSessionId: 'fixture-parent',
                childSessionId: 'docs',
                mode: SubagentMode.oneShot,
              ),
              name: '文档整理',
              task: '整理本轮验证证据',
              status: AgentState.completed,
              detail: '演示报告与图片已列为结构化交付物。',
            ),
          ],
        ),
      ),
      (
        'resources',
        '交付物 · 图片 / 文件 / 链接',
        ToolContent(
          title: '输出文件',
          resources: [
            OutputResource(
              id: 'preview',
              title: '界面预览.png',
              uri: Uri.base
                  .resolve(
                    'assets/packages/dsh_native_chat/assets/tool-demo.png',
                  )
                  .toString(),
              kind: ResourceKind.image,
              mediaType: 'image/png',
              assetPath: 'assets/tool-demo.png',
            ),
            OutputResource(
              id: 'report',
              title: '验证报告.md',
              uri: Uri.base
                  .resolve(
                    'assets/packages/dsh_native_chat/assets/tool-report.md',
                  )
                  .toString(),
              kind: ResourceKind.file,
              mediaType: 'text/markdown',
              description: '演示交付物，可在浏览器打开',
            ),
            const OutputResource(
              id: 'docs',
              title: 'Flutter 官方文档',
              uri: 'https://docs.flutter.dev/',
              kind: ResourceKind.link,
            ),
          ],
        ),
      ),
    ];
    for (final item in outputs) {
      final id = 'showcase-' + item.$1 + '-' + _sequence.toString();
      _emit(
        ChatEvent.toolStarted(
          generation: _generation,
          sequence: _sequence,
          toolId: id,
          name: item.$2,
          messageId: messageId,
          content: item.$3,
        ),
      );
      _emit(
        ChatEvent.toolFinished(
          generation: _generation,
          sequence: _sequence,
          toolId: id,
          status: ToolStatus.complete,
          elapsedMs: 0,
        ),
      );
    }
    emitQuestion();
  }

  void emitQuestion() {
    _ensureConnected();
    _lastActionId = 'fixture-question-' + _sequence.toString();
    _emit(
      ChatEvent.question(
        generation: _generation,
        sequence: _sequence,
        requestId: _lastActionId!,
        title: '接下来想看什么？',
        detail: '可多选，也可以补充说明。',
        choices: const ['查看验证报告', '继续检查项目'],
        multiSelect: true,
      ),
    );
  }

  final _childUpdates = StreamController<SubagentConversation>.broadcast();
  final Map<String, SubagentConversation> _children = {};
  final Set<String> _deliveredRequests = {};
  @override
  Future<SubagentConversation> readSubagent(SubagentAddress address) async {
    _ensureConnected();
    if (address.parentSessionId != 'fixture-parent' ||
        !['review', 'docs'].contains(address.childSessionId))
      throw const TransportException('演示子会话不存在');
    return _children.putIfAbsent(
      address.childSessionId,
      () => SubagentConversation(
        address: address,
        revision: 0,
        messages: [
          SubagentMessage(
            id: 'task',
            role: 'user',
            text: address.childSessionId == 'review'
                ? '检查变更的边界与回归风险'
                : '整理本轮验证证据',
          ),
          const SubagentMessage(
            id: 'result',
            role: 'assistant',
            text: '演示子会话：已核对任务范围与现有结果。此页面不会执行真实工作。',
          ),
        ],
      ),
    );
  }

  @override
  Stream<SubagentConversation> watchSubagent(SubagentAddress address) =>
      _childUpdates.stream.where((value) => value.address == address);
  @override
  Future<SubagentReceipt> sendSubagent(
    SubagentAddress address,
    String text, {
    required String requestId,
  }) async {
    if (!address.canSend) throw const TransportException('一次性子代理为只读');
    final old = await readSubagent(address);
    if (_deliveredRequests.add(requestId)) {
      final next = SubagentConversation(
        address: address,
        revision: old.revision + 1,
        messages: [
          ...old.messages,
          SubagentMessage(id: requestId, role: 'user', text: text),
          SubagentMessage(
            id: requestId + '-demo',
            role: 'assistant',
            text: '演示回执：已收到补充要求。真实模式将把消息交给该子代理处理。',
          ),
        ],
      );
      _children[address.childSessionId] = next;
      _childUpdates.add(next);
    }
    return SubagentReceipt(requestId: requestId, delivery: 'queue');
  }

  void emitDisconnect() {
    if (!_connected) return;
    _events.add(
      ChatEvent.disconnected(generation: _generation, sequence: _sequence),
    );
    _sequence++;
    _connected = false;
  }

  void emitGap() {
    _ensureConnected();
    _events.add(
      ChatEvent.delta(
        generation: _generation,
        sequence: _sequence + 1,
        messageId: 'fixture-gap',
        role: ChatRole.assistant,
        text: '缺帧',
        done: false,
      ),
    );
  }

  void emitError() {
    _ensureConnected();
    _emit(
      ChatEvent.error(
        generation: _generation,
        sequence: _sequence,
        detail: 'fixture error',
      ),
    );
  }

  @override
  Future<void> reconnect() async {
    _generation++;
    _sequence = 0;
    _connected = false;
    await connect();
  }

  void _emit(ChatEvent event) {
    _events.add(event);
    _sequence++;
  }

  void _ensureConnected() {
    if (!_connected)
      throw const TransportException('fixture transport is disconnected');
  }
}

import '../model/chat_model.dart';
import 'dsh_transport.dart';

class FixtureTransport implements DshTransport {
  FixtureTransport();

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
            id: 'fixture-user-1',
            role: ChatRole.user,
            text: '帮我检查一下项目状态，并说明接下来该做什么。',
          ),
          ChatMessage(
            id: 'fixture-assistant-1',
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
        text: '收到。我先执行一项只读检查。',
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
    await _events.close();
  }

  void emitQuestion() {
    _ensureConnected();
    _lastActionId = 'fixture-question-' + _sequence.toString();
    _emit(
      ChatEvent.question(
        generation: _generation,
        sequence: _sequence,
        requestId: _lastActionId!,
        title: '下一步怎么做？',
        detail: '选择继续读取，或先停下来查看当前结果。',
      ),
    );
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

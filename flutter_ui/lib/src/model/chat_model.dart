enum ChatRole { user, assistant, system }

enum ToolStatus { running, complete, failed, cancelled }

enum ChatStatus {
  connecting,
  ready,
  streaming,
  waitingAction,
  reconnecting,
  error,
}

enum ChatEventKind {
  snapshot,
  delta,
  toolStarted,
  toolFinished,
  approval,
  question,
  turnComplete,
  disconnected,
  error,
}

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.role,
    required this.text,
    this.isStreaming = false,
  });

  final String id;
  final ChatRole role;
  final String text;
  final bool isStreaming;

  ChatMessage copyWith({String? text, bool? isStreaming}) => ChatMessage(
    id: id,
    role: role,
    text: text ?? this.text,
    isStreaming: isStreaming ?? this.isStreaming,
  );

  @override
  bool operator ==(Object other) =>
      other is ChatMessage &&
      other.id == id &&
      other.role == role &&
      other.text == text &&
      other.isStreaming == isStreaming;

  @override
  int get hashCode => Object.hash(id, role, text, isStreaming);
}

class ToolRun {
  const ToolRun({
    required this.id,
    required this.name,
    this.status = ToolStatus.running,
    this.detail = '',
    this.elapsedMs,
  });

  final String id;
  final String name;
  final ToolStatus status;
  final String detail;
  final int? elapsedMs;

  ToolRun copyWith({ToolStatus? status, String? detail, int? elapsedMs}) =>
      ToolRun(
        id: id,
        name: name,
        status: status ?? this.status,
        detail: detail ?? this.detail,
        elapsedMs: elapsedMs ?? this.elapsedMs,
      );
}

class ActionRequest {
  const ActionRequest({
    required this.requestId,
    required this.title,
    required this.detail,
  });

  final String requestId;
  final String title;
  final String detail;
}

class ChatEvent {
  const ChatEvent._({
    required this.kind,
    required this.generation,
    required this.sequence,
    this.messages = const [],
    this.messageId,
    this.role,
    this.text = '',
    this.done = false,
    this.toolId,
    this.toolName,
    this.toolStatus,
    this.elapsedMs,
    this.requestId,
    this.title,
    this.detail,
  });

  const ChatEvent.snapshot({
    required int generation,
    required int sequence,
    required List<ChatMessage> messages,
  }) : this._(
         kind: ChatEventKind.snapshot,
         generation: generation,
         sequence: sequence,
         messages: messages,
       );

  const ChatEvent.delta({
    required int generation,
    required int sequence,
    required String messageId,
    required ChatRole role,
    required String text,
    required bool done,
  }) : this._(
         kind: ChatEventKind.delta,
         generation: generation,
         sequence: sequence,
         messageId: messageId,
         role: role,
         text: text,
         done: done,
       );

  const ChatEvent.toolStarted({
    required int generation,
    required int sequence,
    required String toolId,
    required String name,
  }) : this._(
         kind: ChatEventKind.toolStarted,
         generation: generation,
         sequence: sequence,
         toolId: toolId,
         toolName: name,
       );

  const ChatEvent.toolFinished({
    required int generation,
    required int sequence,
    required String toolId,
    required ToolStatus status,
    String detail = '',
    int? elapsedMs,
  }) : this._(
         kind: ChatEventKind.toolFinished,
         generation: generation,
         sequence: sequence,
         toolId: toolId,
         toolStatus: status,
         detail: detail,
         elapsedMs: elapsedMs,
       );

  const ChatEvent.approval({
    required int generation,
    required int sequence,
    required String requestId,
    required String title,
    required String detail,
  }) : this._(
         kind: ChatEventKind.approval,
         generation: generation,
         sequence: sequence,
         requestId: requestId,
         title: title,
         detail: detail,
       );

  const ChatEvent.question({
    required int generation,
    required int sequence,
    required String requestId,
    required String title,
    required String detail,
  }) : this._(
         kind: ChatEventKind.question,
         generation: generation,
         sequence: sequence,
         requestId: requestId,
         title: title,
         detail: detail,
       );

  const ChatEvent.turnComplete({required int generation, required int sequence})
    : this._(
        kind: ChatEventKind.turnComplete,
        generation: generation,
        sequence: sequence,
      );

  const ChatEvent.disconnected({required int generation, required int sequence})
    : this._(
        kind: ChatEventKind.disconnected,
        generation: generation,
        sequence: sequence,
      );

  const ChatEvent.error({
    required int generation,
    required int sequence,
    required String detail,
  }) : this._(
         kind: ChatEventKind.error,
         generation: generation,
         sequence: sequence,
         detail: detail,
       );

  final ChatEventKind kind;
  final int generation;
  final int sequence;
  final List<ChatMessage> messages;
  final String? messageId;
  final ChatRole? role;
  final String text;
  final bool done;
  final String? toolId;
  final String? toolName;
  final ToolStatus? toolStatus;
  final int? elapsedMs;
  final String? requestId;
  final String? title;
  final String? detail;
}

import 'tool_content.dart';
import 'turn_notice.dart';

/// [notice] is a device-rendered turn card, not model output: the text of such
/// a row is never shown as speech.
enum ChatRole { user, assistant, system, notice }

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
  toolUpdated,
  approval,
  question,
  actionWithdrawn,
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
    this.notice,
  });

  final String id;
  final ChatRole role;
  final String text;
  final bool isStreaming;

  /// Structured turn ending shown as its own card. Only [ChatRole.notice] rows
  /// carry one; the label behind it comes from durable journal metadata.
  final TurnNotice? notice;

  ChatMessage copyWith({String? text, bool? isStreaming}) => ChatMessage(
    id: id,
    role: role,
    text: text ?? this.text,
    isStreaming: isStreaming ?? this.isStreaming,
    notice: notice,
  );

  @override
  bool operator ==(Object other) =>
      other is ChatMessage &&
      other.id == id &&
      other.role == role &&
      other.text == text &&
      other.isStreaming == isStreaming &&
      other.notice == notice;

  @override
  int get hashCode => Object.hash(id, role, text, isStreaming, notice);
}

class ToolRun {
  const ToolRun({
    required this.id,
    required this.name,
    this.status = ToolStatus.running,
    this.detail = '',
    this.elapsedMs,
    this.messageId,
    this.content,
  });

  final String id;
  final String name;
  final ToolStatus status;
  final String detail;
  final int? elapsedMs;
  final String? messageId;
  final ToolContent? content;

  ToolRun copyWith({
    ToolStatus? status,
    String? detail,
    int? elapsedMs,
    ToolContent? content,
  }) => ToolRun(
    id: id,
    name: name,
    status: status ?? this.status,
    detail: detail ?? this.detail,
    elapsedMs: elapsedMs ?? this.elapsedMs,
    messageId: messageId,
    content: content ?? this.content,
  );
}

class QuestionAnswer {
  QuestionAnswer({List<String> selected = const [], this.custom = ''})
    : selected = List.unmodifiable(selected);
  final List<String> selected;
  final String custom;
  String get plainText => [
    ...selected,
    if (custom.isNotEmpty) custom,
  ].join(String.fromCharCode(10));
}

class ActionRequest {
  const ActionRequest({
    required this.requestId,
    required this.title,
    required this.detail,
    this.choices = const [],
    this.multiSelect = false,
    this.draftKey,
  });

  final String requestId;
  final String title;
  final String detail;
  final List<String> choices;
  final bool multiSelect;
  final String? draftKey;
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
    this.content,
    this.choices = const [],
    this.multiSelect = false,
    this.draftKey,
    this.notice,
  });

  const ChatEvent.actionWithdrawn({
    required int generation,
    required int sequence,
    required String requestId,
  }) : this._(
         kind: ChatEventKind.actionWithdrawn,
         generation: generation,
         sequence: sequence,
         requestId: requestId,
       );

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
    TurnNotice? notice,
  }) : this._(
         kind: ChatEventKind.delta,
         generation: generation,
         sequence: sequence,
         messageId: messageId,
         role: role,
         text: text,
         done: done,
         notice: notice,
       );

  const ChatEvent.toolStarted({
    required int generation,
    required int sequence,
    required String toolId,
    required String name,
    String? messageId,
    ToolContent? content,
  }) : this._(
         kind: ChatEventKind.toolStarted,
         generation: generation,
         sequence: sequence,
         toolId: toolId,
         toolName: name,
         messageId: messageId,
         content: content,
       );

  const ChatEvent.toolUpdated({
    required int generation,
    required int sequence,
    required String toolId,
    required ToolContent content,
  }) : this._(
         kind: ChatEventKind.toolUpdated,
         generation: generation,
         sequence: sequence,
         toolId: toolId,
         content: content,
       );

  const ChatEvent.toolFinished({
    required int generation,
    required int sequence,
    required String toolId,
    required ToolStatus status,
    String detail = '',
    int? elapsedMs,
    ToolContent? content,
  }) : this._(
         kind: ChatEventKind.toolFinished,
         generation: generation,
         sequence: sequence,
         toolId: toolId,
         toolStatus: status,
         detail: detail,
         elapsedMs: elapsedMs,
         content: content,
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
    List<String> choices = const [],
    bool multiSelect = false,
    String? draftKey,
  }) : this._(
         kind: ChatEventKind.question,
         choices: choices,
         multiSelect: multiSelect,
         draftKey: draftKey,
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
  final ToolContent? content;
  final List<String> choices;
  final bool multiSelect;
  final String? draftKey;

  /// Structured turn ending carried by the delta that introduces the card.
  final TurnNotice? notice;
}

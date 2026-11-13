import '../model/chat_model.dart';

class ChatState {
  ChatState({
    required this.status,
    required this.generation,
    required this.nextSequence,
    required List<ChatMessage> messages,
    required List<ToolRun> tools,
    this.approval,
    this.question,
    this.error,
    List<String> queuedPrompts = const [],
    this.needsResync = false,
  }) : messages = List.unmodifiable(messages),
       tools = List.unmodifiable(tools),
       queuedPrompts = List.unmodifiable(queuedPrompts);

  factory ChatState.empty() => ChatState(
    status: ChatStatus.connecting,
    generation: -1,
    nextSequence: 0,
    messages: [],
    tools: [],
  );

  final ChatStatus status;
  final int generation;
  final int nextSequence;
  final List<ChatMessage> messages;
  final List<ToolRun> tools;
  final ActionRequest? approval;
  final ActionRequest? question;
  final String? error;
  final List<String> queuedPrompts;
  final bool needsResync;

  ChatState copyWith({
    ChatStatus? status,
    int? generation,
    int? nextSequence,
    List<ChatMessage>? messages,
    List<ToolRun>? tools,
    ActionRequest? approval,
    ActionRequest? question,
    String? error,
    List<String>? queuedPrompts,
    bool? needsResync,
    bool clearApproval = false,
    bool clearQuestion = false,
    bool clearError = false,
  }) => ChatState(
    status: status ?? this.status,
    generation: generation ?? this.generation,
    nextSequence: nextSequence ?? this.nextSequence,
    messages: List.unmodifiable(messages ?? this.messages),
    tools: List.unmodifiable(tools ?? this.tools),
    approval: clearApproval ? null : approval ?? this.approval,
    question: clearQuestion ? null : question ?? this.question,
    error: clearError ? null : error ?? this.error,
    queuedPrompts: List.unmodifiable(queuedPrompts ?? this.queuedPrompts),
    needsResync: needsResync ?? this.needsResync,
  );
}

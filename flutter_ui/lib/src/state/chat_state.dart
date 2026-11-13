import '../model/chat_model.dart';

class ChatState {
  const ChatState({
    required this.status,
    required this.generation,
    required this.nextSequence,
    required this.messages,
    required this.tools,
    this.approval,
    this.question,
    this.error,
    this.needsResync = false,
  });

  factory ChatState.empty() => const ChatState(
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
    bool? needsResync,
  }) => ChatState(
    status: status ?? this.status,
    generation: generation ?? this.generation,
    nextSequence: nextSequence ?? this.nextSequence,
    messages: List.unmodifiable(messages ?? this.messages),
    tools: List.unmodifiable(tools ?? this.tools),
    approval: approval ?? this.approval,
    question: question ?? this.question,
    error: error ?? this.error,
    needsResync: needsResync ?? this.needsResync,
  );
}

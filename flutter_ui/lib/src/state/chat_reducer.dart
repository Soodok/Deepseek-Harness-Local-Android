import '../model/chat_model.dart';
import 'chat_state.dart';

class ChatReducer {
  const ChatReducer._();

  static ChatState apply(ChatState state, ChatEvent event) {
    if (event.kind == ChatEventKind.snapshot) {
      if (event.generation < state.generation) return state;
      if (event.generation == state.generation) {
        if (event.sequence < state.nextSequence) return state;
        if (event.sequence > state.nextSequence) {
          return state.copyWith(
            status: ChatStatus.reconnecting,
            needsResync: true,
          );
        }
      }
      return ChatState(
        status: ChatStatus.ready,
        generation: event.generation,
        nextSequence: event.sequence + 1,
        messages: event.messages,
        tools: const [],
        queuedPrompts: state.queuedPrompts,
        needsResync: false,
      );
    }

    if (event.generation < state.generation) return state;
    if (event.generation > state.generation ||
        event.sequence > state.nextSequence) {
      return state.copyWith(status: ChatStatus.reconnecting, needsResync: true);
    }
    if (event.sequence < state.nextSequence) return state;

    var next = state.copyWith(
      nextSequence: state.nextSequence + 1,
      clearError: true,
    );
    switch (event.kind) {
      case ChatEventKind.delta:
        next = _applyDelta(next, event);
      case ChatEventKind.toolStarted:
        next = next.copyWith(
          status: ChatStatus.streaming,
          tools: [
            ...next.tools,
            ToolRun(id: event.toolId!, name: event.toolName!),
          ],
        );
      case ChatEventKind.toolFinished:
        next = next.copyWith(
          clearApproval: true,
          tools: next.tools
              .map(
                (tool) => tool.id == event.toolId
                    ? tool.copyWith(
                        status: event.toolStatus,
                        detail: event.detail,
                        elapsedMs: event.elapsedMs,
                      )
                    : tool,
              )
              .toList(),
        );
      case ChatEventKind.approval:
        next = next.copyWith(
          status: ChatStatus.waitingAction,
          approval: ActionRequest(
            requestId: event.requestId!,
            title: event.title!,
            detail: event.detail!,
          ),
          clearQuestion: true,
        );
      case ChatEventKind.question:
        next = next.copyWith(
          status: ChatStatus.waitingAction,
          question: ActionRequest(
            requestId: event.requestId!,
            title: event.title!,
            detail: event.detail!,
          ),
          clearApproval: true,
        );
      case ChatEventKind.turnComplete:
        next = next.copyWith(
          messages: next.messages
              .map((m) => m.isStreaming ? m.copyWith(isStreaming: false) : m)
              .toList(),
          status: ChatStatus.ready,
          clearApproval: true,
          clearQuestion: true,
        );
      case ChatEventKind.disconnected:
        next = next.copyWith(status: ChatStatus.reconnecting);
      case ChatEventKind.error:
        next = next.copyWith(status: ChatStatus.error, error: event.detail);
      case ChatEventKind.snapshot:
        break;
    }
    return next;
  }

  static ChatState _applyDelta(ChatState state, ChatEvent event) {
    final index = state.messages.indexWhere((m) => m.id == event.messageId);
    if (index < 0) {
      return state.copyWith(
        status: event.done ? ChatStatus.ready : ChatStatus.streaming,
        messages: [
          ...state.messages,
          ChatMessage(
            id: event.messageId!,
            role: event.role!,
            text: event.text,
            isStreaming: !event.done,
          ),
        ],
      );
    }
    final current = state.messages[index];
    final messages = [...state.messages]
      ..[index] = current.copyWith(
        text: current.text + event.text,
        isStreaming: !event.done,
      );
    return state.copyWith(
      status: event.done ? ChatStatus.ready : ChatStatus.streaming,
      messages: messages,
    );
  }
}

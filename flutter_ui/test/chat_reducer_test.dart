import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_reducer.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';

void main() {
  test('applies snapshot and assistant deltas in sequence order', () {
    var state = ChatState.empty();
    state = ChatReducer.apply(
      state,
      ChatEvent.snapshot(
        generation: 7,
        sequence: 0,
        messages: const [
          ChatMessage(id: 'u1', role: ChatRole.user, text: '检查构建'),
        ],
      ),
    );
    state = ChatReducer.apply(
      state,
      const ChatEvent.delta(
        generation: 7,
        sequence: 1,
        messageId: 'a1',
        role: ChatRole.assistant,
        text: '我先',
        done: false,
      ),
    );
    state = ChatReducer.apply(
      state,
      const ChatEvent.delta(
        generation: 7,
        sequence: 2,
        messageId: 'a1',
        role: ChatRole.assistant,
        text: '检查状态。',
        done: true,
      ),
    );

    expect(state.generation, 7);
    expect(state.messages.map((message) => message.text), ['检查构建', '我先检查状态。']);
    expect(state.messages.last.isStreaming, isFalse);
    expect(state.needsResync, isFalse);
  });

  test('ignores stale generations and stops on sequence gaps', () {
    var state = ChatReducer.apply(
      ChatState.empty(),
      const ChatEvent.snapshot(generation: 4, sequence: 0, messages: []),
    );
    state = ChatReducer.apply(
      state,
      const ChatEvent.delta(
        generation: 3,
        sequence: 1,
        messageId: 'old',
        role: ChatRole.assistant,
        text: '旧事件',
        done: true,
      ),
    );
    expect(state.messages, isEmpty);

    state = ChatReducer.apply(
      state,
      const ChatEvent.delta(
        generation: 4,
        sequence: 3,
        messageId: 'gap',
        role: ChatRole.assistant,
        text: '缺帧',
        done: false,
      ),
    );
    expect(state.needsResync, isTrue);
    expect(state.messages, isEmpty);
  });

  test('keeps tool and user action cards in state', () {
    var state = ChatReducer.apply(
      ChatState.empty(),
      const ChatEvent.snapshot(generation: 2, sequence: 0, messages: []),
    );
    state = ChatReducer.apply(
      state,
      const ChatEvent.toolStarted(
        generation: 2,
        sequence: 1,
        toolId: 'tool-1',
        name: '读取项目状态',
      ),
    );
    state = ChatReducer.apply(
      state,
      const ChatEvent.approval(
        generation: 2,
        sequence: 2,
        requestId: 'approval-1',
        title: '允许执行命令？',
        detail: '只读检查工作树',
      ),
    );

    expect(state.tools.single.id, 'tool-1');
    expect(state.tools.single.status, ToolStatus.running);
    expect(state.approval?.requestId, 'approval-1');
    expect(state.approval?.detail, '只读检查工作树');
  });
}

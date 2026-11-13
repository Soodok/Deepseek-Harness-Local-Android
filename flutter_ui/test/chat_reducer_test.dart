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

  test('keeps approval and question cards mutually exclusive', () {
    var state = ChatReducer.apply(
      ChatReducer.apply(
        ChatState.empty(),
        const ChatEvent.snapshot(generation: 1, sequence: 0, messages: []),
      ),
      const ChatEvent.approval(
        generation: 1,
        sequence: 1,
        requestId: 'approval-1',
        title: '执行前确认',
        detail: '需要确认一次',
      ),
    );
    expect(state.approval, isNotNull);

    state = ChatReducer.apply(
      state,
      const ChatEvent.question(
        generation: 1,
        sequence: 2,
        requestId: 'question-1',
        title: '需要补充信息',
        detail: '请选择目标',
      ),
    );

    expect(state.approval, isNull);
    expect(state.question?.requestId, 'question-1');
  });

  test('preserves queued prompts through a resync snapshot', () {
    var state = ChatReducer.apply(
      ChatState.empty(),
      const ChatEvent.snapshot(generation: 1, sequence: 0, messages: []),
    );
    state = state.copyWith(queuedPrompts: const ['稍后执行']);

    state = ChatReducer.apply(
      state,
      const ChatEvent.snapshot(generation: 2, sequence: 0, messages: []),
    );

    expect(state.queuedPrompts, ['稍后执行']);
  });

  test('guards stale and gapped snapshots', () {
    var state = ChatReducer.apply(
      ChatState.empty(),
      const ChatEvent.snapshot(
        generation: 4,
        sequence: 0,
        messages: [
          ChatMessage(id: 'current', role: ChatRole.assistant, text: '当前'),
        ],
      ),
    );
    state = ChatReducer.apply(
      state,
      const ChatEvent.snapshot(
        generation: 3,
        sequence: 0,
        messages: [ChatMessage(id: 'old', role: ChatRole.assistant, text: '旧')],
      ),
    );
    expect(state.messages.single.id, 'current');

    state = ChatReducer.apply(
      state,
      const ChatEvent.snapshot(generation: 4, sequence: 3, messages: []),
    );
    expect(state.needsResync, isTrue);
    expect(state.messages.single.id, 'current');
  });

  test('copies snapshot lists before exposing state', () {
    final source = <ChatMessage>[
      const ChatMessage(id: 'm1', role: ChatRole.user, text: '原始'),
    ];
    final state = ChatReducer.apply(
      ChatState.empty(),
      ChatEvent.snapshot(generation: 1, sequence: 0, messages: source),
    );

    source.add(const ChatMessage(id: 'm2', role: ChatRole.user, text: '外部'));

    expect(state.messages, hasLength(1));
    expect(() => state.messages.add(source.first), throwsUnsupportedError);
  });
}

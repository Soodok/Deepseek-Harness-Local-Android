import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/state/chat_reducer.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';

void main() {
  test(
    'tool progress replaces one stable ID and preserves anchor and content',
    () {
      var s = ChatReducer.apply(
        ChatState.empty(),
        const ChatEvent.snapshot(
          generation: 1,
          sequence: 0,
          messages: [
            ChatMessage(id: 'm1', role: ChatRole.assistant, text: '先检查'),
          ],
        ),
      );
      final items = [
        const PlanStep(id: 's1', title: '读取项目', status: StepState.running),
      ];
      final content = ToolContent(title: '检查计划', steps: items);
      items.clear();
      expect(content.steps.length, 1);
      expect(() => content.steps.clear(), throwsUnsupportedError);
      s = ChatReducer.apply(
        s,
        ChatEvent.toolStarted(
          generation: 1,
          sequence: 1,
          toolId: 'plan',
          name: '计划',
          content: content,
        ),
      );
      expect(s.tools.single.messageId, 'm1');
      s = ChatReducer.apply(
        s,
        ChatEvent.toolUpdated(
          generation: 1,
          sequence: 2,
          toolId: 'plan',
          content: ToolContent(
            title: '检查计划',
            steps: [
              const PlanStep(
                id: 's1',
                title: '读取项目',
                status: StepState.completed,
              ),
            ],
          ),
        ),
      );
      expect(s.tools.length, 1);
      expect(s.tools.single.content!.steps.single.status, StepState.completed);
      s = ChatReducer.apply(
        s,
        const ChatEvent.toolFinished(
          generation: 1,
          sequence: 3,
          toolId: 'plan',
          status: ToolStatus.complete,
        ),
      );
      expect(s.tools.single.content!.steps.single.status, StepState.completed);
      expect(s.tools.single.messageId, 'm1');
      final stale = ChatReducer.apply(
        s,
        ChatEvent.toolUpdated(
          generation: 0,
          sequence: 4,
          toolId: 'plan',
          content: ToolContent(title: '旧内容'),
        ),
      );
      expect(identical(s, stale), isTrue);
    },
  );
  test(
    'output links allow only explicit web URIs and retain file metadata',
    () {
      const file = OutputResource(
        id: 'r',
        title: 'report.md',
        uri: 'https://example.test/report.md',
        kind: ResourceKind.file,
        byteSize: 42,
      );
      expect(file.openableUri!.scheme, 'https');
      expect(file.byteSize, 42);
      expect(
        const OutputResource(
          id: 'x',
          title: 'x',
          uri: 'javascript:alert(1)',
          kind: ResourceKind.link,
        ).openableUri,
        isNull,
      );
    },
  );
}

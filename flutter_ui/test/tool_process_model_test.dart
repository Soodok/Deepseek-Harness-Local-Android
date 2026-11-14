import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/state/chat_reducer.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';

void main() {
  test('streaming tool snapshots retain typed command and code output at completion', () {
    var state = ChatReducer.apply(
      ChatState.empty(),
      const ChatEvent.snapshot(generation: 1, sequence: 0, messages: []),
    );
    state = ChatReducer.apply(
      state,
      ChatEvent.toolStarted(
        generation: 1,
        sequence: 1,
        toolId: 'cmd',
        name: 'bash',
        content: ToolContent(
          title: 'test',
          command: const CommandResult(
            command: 'flutter test',
            stdout: 'running',
          ),
        ),
      ),
    );
    state = ChatReducer.apply(
      state,
      ChatEvent.toolUpdated(
        generation: 1,
        sequence: 2,
        toolId: 'cmd',
        content: ToolContent(
          title: 'test',
          reasoning: 'Inspect the result',
          command: const CommandResult(
            command: 'flutter test',
            stdout: 'FAILED',
            stderr: 'expected 2',
            exitCode: 7,
          ),
          edits: const [
            CodeEdit(path: 'lib/main.dart', before: 'old', after: 'new'),
          ],
        ),
      ),
    );
    state = ChatReducer.apply(
      state,
      const ChatEvent.toolFinished(
        generation: 1,
        sequence: 3,
        toolId: 'cmd',
        status: ToolStatus.failed,
      ),
    );
    expect(state.tools.single.content!.command!.exitCode, 7);
    expect(state.tools.single.content!.edits.single.after, 'new');
    expect(state.tools.single.content!.reasoning, 'Inspect the result');
    expect(
      () => state.tools.single.content!.edits.clear(),
      throwsUnsupportedError,
    );
  });
}

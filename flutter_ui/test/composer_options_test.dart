import 'dart:typed_data';
import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';

Future<void> flush() => Future<void>.delayed(Duration.zero);

class DelayedFixture extends FixtureTransport {
  final gate = Completer<void>();
  int calls = 0;
  @override
  Future<void> sendSubmission(PromptSubmission submission) async {
    calls++;
    await gate.future;
    await super.sendSubmission(submission);
  }
}

void main() {
  test('double queue dispatch sends first once and preserves second', () async {
    final transport = DelayedFixture();
    final controller = ChatController(transport);
    await controller.start();
    await flush();
    await controller.queuePrompt('第一条');
    await controller.queuePrompt('第二条');
    final first = controller.sendNextQueued();
    final second = controller.sendNextQueued();
    expect(transport.calls, 1);
    transport.gate.complete();
    await first;
    await second;
    await flush();
    expect(controller.state.queuedPrompts, ['第二条']);
    expect(transport.submissions.map((s) => s.text), ['第一条']);
    controller.dispose();
  });
  test(
    'submission includes model permission and actual attachment bytes',
    () async {
      final fixture = FixtureTransport();
      final controller = ChatController(fixture);
      await controller.start();
      await flush();
      controller.selectModel('fixture-fast');
      controller.selectPermission(ComposerPermission.readOnly);
      controller.addAttachments([
        PromptAttachment(
          id: 'a',
          name: 'note.txt',
          bytes: Uint8List.fromList([65, 66]),
        ),
      ]);
      expect(await controller.sendPrompt('读取附件'), isTrue);
      await flush();
      final submitted = fixture.submissions.single;
      expect(submitted.modelId, 'fixture-fast');
      expect(submitted.permission, ComposerPermission.readOnly);
      expect(submitted.attachments.single.bytes, [65, 66]);
      expect(controller.attachments, isEmpty);
      expect(controller.state.approval, isNull);
      expect(controller.state.messages.any((m) => m.isStreaming), isFalse);
      controller.dispose();
    },
  );
  test(
    'queued submission freezes model permission and attachments until dispatch',
    () async {
      final fixture = FixtureTransport();
      final controller = ChatController(fixture);
      await controller.start();
      await flush();
      await controller.sendPrompt('启动');
      await flush();
      controller.selectModel('fixture-fast');
      controller.addAttachments([
        PromptAttachment(
          id: 'q',
          name: 'queued.txt',
          bytes: Uint8List.fromList([7]),
        ),
      ]);
      expect(await controller.sendPrompt('下一条'), isTrue);
      await flush();
      controller.selectModel('fixture-balanced');
      controller.selectPermission(ComposerPermission.readOnly);
      await controller.stop();
      await flush();
      await controller.sendNextQueued();
      await flush();
      final queued = fixture.submissions.last;
      expect(queued.modelId, 'fixture-fast');
      expect(queued.permission, ComposerPermission.workspaceWrite);
      expect(queued.attachments.single.name, 'queued.txt');
      expect(queued.attachments.single.bytes, [7]);
      expect(controller.state.queuedPrompts, isEmpty);
      controller.dispose();
    },
  );
  test('attachment-only submit works and model IDs are validated', () async {
    final fixture = FixtureTransport();
    final controller = ChatController(fixture);
    await controller.start();
    await flush();
    expect(() => controller.selectModel('made-up-model'), throwsArgumentError);
    controller.addAttachments([
      PromptAttachment(
        id: 'only',
        name: 'only.txt',
        bytes: Uint8List.fromList([1]),
      ),
    ]);
    expect(await controller.sendPrompt(''), isTrue);
    await flush();
    expect(fixture.submissions.single.attachments.single.name, 'only.txt');
    controller.dispose();
  });
}

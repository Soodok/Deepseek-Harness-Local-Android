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

class SessionFixture extends FixtureTransport
    implements SessionComposerTransport {
  ComposerConfiguration _configuration = const ComposerConfiguration(
    modelId: 'fixture-fast',
    permissionPreset: 'read-only',
  );
  final _configurationChanges =
      StreamController<ComposerConfiguration>.broadcast(sync: true);
  bool failSubmission = false;

  @override
  ComposerConfiguration get composerConfiguration => _configuration;
  @override
  Stream<ComposerConfiguration> get composerConfigurationChanges =>
      _configurationChanges.stream;

  void publish(ComposerConfiguration value) {
    _configuration = value;
    _configurationChanges.add(value);
  }

  @override
  Future<void> sendSubmission(PromptSubmission submission) async {
    if (failSubmission) throw StateError('submission rejected');
    publish(
      ComposerConfiguration(
        modelId: submission.applyModel
            ? submission.modelId
            : _configuration.modelId,
        permissionPreset: submission.applyPermission
            ? submission.permission.wireValue
            : _configuration.permissionPreset,
      ),
    );
    await super.sendSubmission(submission);
  }

  @override
  Future<void> close() async {
    await super.close();
    await _configurationChanges.close();
  }
}

void main() {
  test(
    'new draft permission never authorizes an inherited unknown queue head',
    () async {
      final transport = SessionFixture();
      final controller = ChatController(transport);
      addTearDown(controller.dispose);
      await controller.start();
      await flush();
      await controller.queuePrompt('inherited queue');
      transport.publish(
        const ComposerConfiguration(
          modelId: 'fixture-fast',
          permissionPreset: 'custom',
        ),
      );
      expect(controller.canDispatchQueued, isFalse);
      controller.selectPermission(ComposerPermission.readOnly);
      expect(controller.canSend, isTrue);
      expect(controller.canDispatchQueued, isFalse);
      await controller.sendNextQueued();
      expect(transport.submissions, isEmpty);
      expect(controller.state.queuedPrompts, ['inherited queue']);
      transport.publish(
        const ComposerConfiguration(
          modelId: 'fixture-fast',
          permissionPreset: 'read-only',
        ),
      );
      expect(controller.canDispatchQueued, isTrue);
      await controller.sendNextQueued();
      expect(transport.submissions.single.applyPermission, isFalse);
    },
  );
  test(
    'queued acknowledgements never consume a newer A B A draft selection',
    () async {
      final transport = SessionFixture();
      final controller = ChatController(transport);
      addTearDown(controller.dispose);
      await controller.start();
      await flush();
      controller.selectPermission(ComposerPermission.readOnly);
      controller.selectModel('fixture-fast');
      await controller.queuePrompt('queued A');
      controller.selectPermission(ComposerPermission.fullAccess);
      controller.selectModel('fixture-balanced');
      await controller.queuePrompt('queued B');
      controller.selectPermission(ComposerPermission.readOnly);
      controller.selectModel('fixture-fast');
      await controller.sendNextQueued();
      await controller.stop();
      await flush();
      await controller.sendNextQueued();
      await controller.stop();
      await flush();
      expect(controller.permission, ComposerPermission.readOnly);
      expect(controller.selectedModelId, 'fixture-fast');
      expect(await controller.sendPrompt('new draft A'), isTrue);
      final sent = transport.submissions.last;
      expect(sent.applyPermission, isTrue);
      expect(sent.applyModel, isTrue);
      expect(sent.permission, ComposerPermission.readOnly);
      expect(sent.modelId, 'fixture-fast');
    },
  );
  test(
    'session configuration restores before sending without implicit overrides',
    () async {
      final transport = SessionFixture();
      final controller = ChatController(transport);
      addTearDown(controller.dispose);
      expect(controller.selectedModelId, 'fixture-fast');
      expect(controller.permission, ComposerPermission.readOnly);
      await controller.start();
      await flush();
      expect(await controller.sendPrompt('沿用会话配置'), isTrue);
      final submission = transport.submissions.single;
      expect(submission.modelId, 'fixture-fast');
      expect(submission.permission, ComposerPermission.readOnly);
      expect(submission.applyModel, isFalse);
      expect(submission.applyPermission, isFalse);
    },
  );

  test('host updates refresh untouched choices but preserve explicit draft choices', () async {
    final transport = SessionFixture();
    final controller = ChatController(transport);
    addTearDown(controller.dispose);
    await controller.start();
    await flush();
    transport.publish(
      const ComposerConfiguration(
        modelId: 'fixture-balanced',
        permissionPreset: 'danger-full-access',
      ),
    );
    expect(controller.selectedModelId, 'fixture-balanced');
    expect(controller.permission, ComposerPermission.fullAccess);
    controller.selectModel('fixture-fast');
    controller.selectPermission(ComposerPermission.workspaceWrite);
    transport.publish(
      const ComposerConfiguration(
        modelId: 'fixture-balanced',
        permissionPreset: 'read-only',
      ),
    );
    expect(controller.selectedModelId, 'fixture-fast');
    expect(controller.permission, ComposerPermission.workspaceWrite);
    expect(await controller.sendPrompt('显式配置'), isTrue);
    expect(transport.submissions.single.applyModel, isTrue);
    expect(transport.submissions.single.applyPermission, isTrue);
    transport.publish(
      const ComposerConfiguration(
        modelId: 'fixture-balanced',
        permissionPreset: 'read-only',
      ),
    );
    expect(controller.selectedModelId, 'fixture-balanced');
    expect(controller.permission, ComposerPermission.readOnly);
  });

  test(
    'queued inherited choices never become permission or model writes',
    () async {
      final transport = SessionFixture();
      final controller = ChatController(transport);
      addTearDown(controller.dispose);
      await controller.start();
      await flush();
      transport.publish(
        const ComposerConfiguration(
          modelId: 'fixture-balanced',
          permissionPreset: 'danger-full-access',
        ),
      );
      await controller.queuePrompt('排队期间允许 Host 改成只读');
      transport.publish(
        const ComposerConfiguration(
          modelId: 'fixture-fast',
          permissionPreset: 'read-only',
        ),
      );
      await controller.sendNextQueued();
      final submission = transport.submissions.single;
      expect(submission.applyModel, isFalse);
      expect(submission.applyPermission, isFalse);
      expect(transport.composerConfiguration.permissionPreset, 'read-only');
      expect(controller.permission, ComposerPermission.readOnly);
    },
  );

  test(
    'unknown host permission is explicit and never submitted as a default',
    () async {
      final transport = SessionFixture();
      final controller = ChatController(transport);
      addTearDown(controller.dispose);
      await controller.start();
      await flush();
      transport.publish(
        const ComposerConfiguration(
          modelId: 'fixture-fast',
          permissionPreset: 'custom',
        ),
      );
      expect(controller.canSend, isFalse);
      expect(controller.state.error, contains('custom'));
      expect(await controller.sendPrompt('不可静默升权'), isFalse);
      expect(transport.submissions, isEmpty);
      controller.selectPermission(ComposerPermission.readOnly);
      expect(controller.canSend, isTrue);
      expect(await controller.sendPrompt('显式改为只读'), isTrue);
      expect(transport.submissions.single.applyPermission, isTrue);
    },
  );

  test('failed dispatch retains explicit choices for retry', () async {
    final transport = SessionFixture()..failSubmission = true;
    final controller = ChatController(transport);
    addTearDown(controller.dispose);
    await controller.start();
    await flush();
    controller.selectPermission(ComposerPermission.workspaceWrite);
    expect(await controller.sendPrompt('重试'), isFalse);
    transport.publish(
      const ComposerConfiguration(
        modelId: 'fixture-fast',
        permissionPreset: 'read-only',
      ),
    );
    expect(controller.permission, ComposerPermission.workspaceWrite);
    transport.failSubmission = false;
    await controller.reconnect();
    await flush();
    expect(await controller.sendPrompt('重试'), isTrue);
    expect(transport.submissions.single.applyPermission, isTrue);
  });
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

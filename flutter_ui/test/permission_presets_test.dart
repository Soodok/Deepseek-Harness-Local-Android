import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';

void main() {
  test('permission presets use the three actual DSH machine values', () {
    expect(ComposerPermission.values.map((p) => p.wireValue).toList(), [
      'read-only',
      'workspace-write',
      'danger-full-access',
    ]);
  });
  test('full access is submitted and frozen when queued', () async {
    final transport = FixtureTransport();
    final controller = ChatController(transport);
    await controller.start();
    await Future<void>.delayed(Duration.zero);
    await controller.sendPrompt('first');
    await Future<void>.delayed(Duration.zero);
    controller.selectPermission(ComposerPermission.fullAccess);
    await controller.sendPrompt('queued');
    controller.selectPermission(ComposerPermission.readOnly);
    await controller.stop();
    await Future<void>.delayed(Duration.zero);
    await controller.sendNextQueued();
    expect(
      transport.submissions.last.permission,
      ComposerPermission.fullAccess,
    );
    controller.dispose();
  });
}

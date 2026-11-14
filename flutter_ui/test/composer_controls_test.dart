import 'dart:typed_data';

import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:file_selector_platform_interface/file_selector_platform_interface.dart';
import 'package:dsh_native_chat/src/bridge/attachment_picker.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';

class Picker extends FileSelectorPlatform {
  List<XFile> result = [];
  int calls = 0;
  @override
  Future<List<XFile>> openFiles({
    List<XTypeGroup>? acceptedTypeGroups,
    String? initialDirectory,
    String? confirmButtonText,
  }) async {
    calls++;
    return result;
  }
}

class FailingOnceFixture extends FixtureTransport {
  bool fail = true;
  @override
  Future<void> sendSubmission(PromptSubmission submission) async {
    if (fail) {
      fail = false;
      throw const TransportException('模拟发送失败');
    }
    await super.sendSubmission(submission);
  }
}

void main() {
  testWidgets(
    'failed send keeps editable draft and files through reconnect retry',
    (tester) async {
      final transport = FailingOnceFixture();
      final controller = ChatController(transport);
      controller.selectPermission(ComposerPermission.readOnly);
      controller.addAttachments([
        PromptAttachment(
          id: 'retry',
          name: 'retry.txt',
          bytes: Uint8List.fromList([42]),
        ),
      ]);
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: ChatShell(controller: controller),
        ),
      );
      await tester.pump();
      final input = find.byKey(const ValueKey('composer-input'));
      await tester.enterText(input, '失败草稿');
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('composer-send')));
      await tester.pump();
      expect(find.text('失败草稿'), findsOneWidget);
      expect(controller.attachments.single.bytes, [42]);
      expect(tester.widget<TextField>(input).enabled, isTrue);
      await tester.enterText(input, '修改后的草稿');
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('connection-retry')));
      await tester.pump();
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('composer-send')));
      await tester.pumpAndSettle();
      expect(transport.submissions.single.text, '修改后的草稿');
      expect(transport.submissions.single.attachments.single.bytes, [42]);
      expect(controller.attachments, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );
  test(
    'file bytes are read, limits reject, and attachment bytes are immutable',
    () async {
      final input = Uint8List.fromList([0, 1, 255]);
      final files = await AttachmentPicker.readSelection(
        [XFile.fromData(input, name: '中文.bin', path: '中文.bin')],
        remainingFiles: 5,
        remainingBytes: 3,
      );
      expect(files.single.name, '中文.bin');
      expect(files.single.bytes, [0, 1, 255]);
      expect(() => files.single.bytes[0] = 7, throwsUnsupportedError);
      await expectLater(
        AttachmentPicker.readSelection(
          [XFile.fromData(input, name: 'over.bin', path: 'over.bin')],
          remainingFiles: 5,
          remainingBytes: 2,
        ),
        throwsA(isA<TransportException>()),
      );
      await expectLater(
        AttachmentPicker.readSelection(
          [XFile.fromData(input, name: 'over.bin', path: 'over.bin')],
          remainingFiles: 0,
          remainingBytes: 100,
        ),
        throwsA(isA<TransportException>()),
      );
    },
  );
  for (final width in [360.0, 390.0, 432.0]) {
    testWidgets(
      'model permission and system picker work at width ' + width.toString(),
      (tester) async {
        tester.view.physicalSize = Size(width, 800);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final prior = FileSelectorPlatform.instance;
        final picker = Picker();
        FileSelectorPlatform.instance = picker;
        addTearDown(() => FileSelectorPlatform.instance = prior);
        final transport = FixtureTransport();
        final controller = ChatController(transport);
        await tester.pumpWidget(
          MaterialApp(
            theme: DshTheme.light(),
            home: ChatShell(controller: controller),
          ),
        );
        await tester.pump();
        for (final key in [
          'composer-attach',
          'composer-model',
          'composer-permission',
        ]) {
          final finder = find.byKey(ValueKey(key));
          expect(finder.hitTestable(), findsOneWidget);
          expect(tester.getSize(finder).height, greaterThanOrEqualTo(48));
          expect(tester.getSize(finder).width, greaterThanOrEqualTo(48));
        }
        await tester.tap(find.byKey(const ValueKey('composer-model')));
        await tester.pumpAndSettle();
        await tester.tap(
          find.byKey(const ValueKey('model-option-fixture-fast')),
        );
        await tester.pumpAndSettle();
        expect(controller.selectedModelId, 'fixture-fast');
        expect(find.text('快速 · 演示'), findsOneWidget);
        await tester.tap(find.byKey(const ValueKey('composer-permission')));
        await tester.pumpAndSettle();
        await tester.tap(
          find.byKey(const ValueKey('permission-option-readOnly')),
        );
        await tester.pumpAndSettle();
        expect(controller.permission, ComposerPermission.readOnly);
        // Cancellation leaves the draft unchanged.
        await tester.enterText(
          find.byKey(const ValueKey('composer-input')),
          '保留草稿',
        );
        await tester.tap(find.byKey(const ValueKey('composer-attach')));
        await tester.pumpAndSettle();
        expect(controller.attachments, isEmpty);
        expect(find.text('保留草稿'), findsOneWidget);
        picker.result = [
          XFile.fromData(
            Uint8List.fromList([3, 4, 5]),
            name: '需求.txt',
            path: '需求.txt',
          ),
        ];
        await tester.tap(find.byKey(const ValueKey('composer-attach')));
        await tester.pumpAndSettle();
        expect(find.text('需求.txt'), findsOneWidget);
        expect(picker.calls, 2);
        final remove = find.byKey(
          ValueKey('attachment-remove-' + controller.attachments.single.id),
        );
        await tester.tap(remove);
        await tester.pumpAndSettle();
        expect(controller.attachments, isEmpty);
        await tester.tap(find.byKey(const ValueKey('composer-attach')));
        await tester.pumpAndSettle();
        await tester.enterText(
          find.byKey(const ValueKey('composer-input')),
          '',
        );
        await tester.pump();
        await tester.tap(find.byKey(const ValueKey('composer-send')));
        await tester.pumpAndSettle();
        expect(transport.submissions.single.attachments.single.bytes, [
          3,
          4,
          5,
        ]);
        expect(transport.submissions.single.modelId, 'fixture-fast');
        expect(
          transport.submissions.single.permission,
          ComposerPermission.readOnly,
        );
        expect(controller.attachments, isEmpty);
        expect(controller.state.approval, isNull);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        controller.dispose();
      },
    );
  }
}

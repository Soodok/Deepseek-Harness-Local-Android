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
import 'package:dsh_native_chat/src/ui/composer.dart';
import 'package:dsh_native_chat/src/ui/composer_surface.dart';
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

/// 顶栏标题溢出时 OverflowTitle 会持续平移，pumpAndSettle 永远等不到静止；
/// 这里只推进固定帧数，让弹层动画走完就断言。
Future<void> frames(WidgetTester tester, [int count = 8]) async {
  for (var i = 0; i < count; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  testWidgets('long draft scrolls inside a complete glass panel with IME', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 720);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 230);
    addTearDown(tester.view.reset);
    final controller = ChatController(FixtureTransport());
    await tester.pumpWidget(
      MaterialApp(home: ChatShell(controller: controller)),
    );
    await tester.pump();
    final input = find.byKey(const ValueKey('composer-input'));
    await tester.enterText(input, List.filled(20, '连续输入的草稿').join('\n'));
    await tester.pump();
    await tester.pump();
    final glass = find.descendant(
      of: find.byType(ComposerSurface),
      matching: find.byKey(const ValueKey('glass-panel')),
    );
    expect(
      tester.getTopLeft(glass).dy,
      greaterThanOrEqualTo(
        tester
            .getTopLeft(find.byKey(const ValueKey('floating-composer-extent')))
            .dy,
      ),
    );
    expect(tester.widget<TextField>(input).maxLines, lessThan(6));
    expect(
      find.byKey(const ValueKey('composer-send')).hitTestable(),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('editing is inline without growing the composer', (tester) async {
    tester.view.physicalSize = const Size(390, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final controller = ChatController(FixtureTransport());
    var editing = false;
    var cancelled = 0;
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return Align(
                alignment: Alignment.bottomCenter,
                child: Composer(
                  controller: controller,
                  editing: editing,
                  onCancelEdit: () => setState(() {
                    editing = false;
                    cancelled++;
                  }),
                ),
              );
            },
          ),
        ),
      ),
    );
    await tester.pump();
    final surface = find.byType(ComposerSurface);
    final initial = tester.getRect(surface);
    final send = tester.getRect(find.byKey(const ValueKey('composer-send')));
    final attach = tester.getRect(
      find.byKey(const ValueKey('composer-attach')),
    );
    final model = tester.getRect(find.byKey(const ValueKey('composer-model')));
    final permission = tester.getRect(
      find.byKey(const ValueKey('composer-permission')),
    );
    update(() => editing = true);
    await tester.pump();
    expect(find.text('正在编辑这条消息，发送会从这里继续'), findsNothing);
    expect(tester.getRect(surface).height, closeTo(initial.height, .01));
    expect(initial.height, lessThanOrEqualTo(110));
    expect(attach.left - initial.left, closeTo(12, .01));
    expect(tester.getRect(find.byKey(const ValueKey('composer-attach'))).center, attach.center);
    expect(model.left - attach.right, lessThanOrEqualTo(4));
    expect(send.left - permission.right, closeTo(0, .01));
    expect(
      tester.getRect(find.byKey(const ValueKey('composer-send'))).center,
      send.center,
    );
    expect(
      find.byKey(const ValueKey('composer-cancel-edit')).hitTestable(),
      findsOneWidget,
    );
    final cancel = tester.getRect(
      find.byKey(const ValueKey('composer-cancel-edit')),
    );
    expect(cancel.size.width, greaterThanOrEqualTo(48));
    expect(cancel.size.height, greaterThanOrEqualTo(48));
    await tester.tapAt(cancel.topCenter + const Offset(0, 2));
    await tester.pump();
    expect(cancelled, 1);
    expect(find.byKey(const ValueKey('composer-editing')), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
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
      await tester.pump(const Duration(seconds: 1));
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
        await frames(tester);
        await tester.tap(
          find.byKey(const ValueKey('model-option-fixture-fast')),
        );
        await frames(tester);
        expect(controller.selectedModelId, 'fixture-fast');
        expect(find.text('快速 · 演示'), findsOneWidget);
        await tester.tap(find.byKey(const ValueKey('composer-permission')));
        await frames(tester);
        await tester.tap(
          find.byKey(const ValueKey('permission-option-readOnly')),
        );
        await frames(tester);
        expect(controller.permission, ComposerPermission.readOnly);
        // Cancellation leaves the draft unchanged.
        await tester.enterText(
          find.byKey(const ValueKey('composer-input')),
          '保留草稿',
        );
        await tester.tap(find.byKey(const ValueKey('composer-attach')));
        await frames(tester);
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
        await frames(tester);
        expect(find.text('需求.txt'), findsOneWidget);
        expect(picker.calls, 2);
        final remove = find.byKey(
          ValueKey('attachment-remove-' + controller.attachments.single.id),
        );
        await tester.tap(remove);
        await frames(tester);
        expect(controller.attachments, isEmpty);
        await tester.tap(find.byKey(const ValueKey('composer-attach')));
        await frames(tester);
        await tester.enterText(
          find.byKey(const ValueKey('composer-input')),
          '',
        );
        await tester.pump();
        await tester.tap(find.byKey(const ValueKey('composer-send')));
        await frames(tester);
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

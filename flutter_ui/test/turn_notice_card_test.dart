import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/turn_notice.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

ChatMessage _notice(TurnNotice notice) => ChatMessage(
  id: 'notice-1',
  role: ChatRole.notice,
  text: notice.message,
  notice: notice,
);

Future<void> _pump(WidgetTester tester, List<ChatMessage> messages) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: Scaffold(
        body: ConversationFlow(
          conversationIdentity: 'parent',
          messages: messages,
        ),
      ),
    ),
  );
  await tester.pump();
}

/// Opens the collapsed row when it is still closed. The row keeps its state
/// across a rebuild, so the helper checks the painted result instead of
/// assuming either state.
Future<void> _expand(WidgetTester tester) async {
  if (find.byKey(const ValueKey('turn-notice-copy')).evaluate().isNotEmpty) {
    return;
  }
  await tester.tap(find.byKey(const ValueKey('turn-notice')));
  await tester.pump();
}

void main() {
  testWidgets('error disclosure confines its ink to the notice surface', (tester) async {
    await _pump(tester, [
      _notice(const TurnNotice(kind: TurnNoticeKind.failed, message: '请求失败', detail: '详细原因')),
    ]);
    final notice = find.byKey(const ValueKey('turn-notice'));
    final surface = find.ancestor(of: notice, matching: find.byType(Material)).first;
    final ink = tester.widget<Material>(surface);
    expect(ink.clipBehavior, isNot(Clip.none));
    expect(ink.shape, isA<RoundedRectangleBorder>());
    expect(tester.getRect(surface), tester.getRect(notice));
    await _expand(tester);
    expect(tester.getRect(surface), tester.getRect(notice));
    expect(find.byKey(const ValueKey('turn-notice-detail')), findsOneWidget);
  });

  testWidgets('a failed turn renders its own card with code and copy', (
    tester,
  ) async {
    final copied = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied.add((call.arguments as Map)['text'] as String);
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await _pump(tester, [
      _notice(
        const TurnNotice(
          kind: TurnNoticeKind.failed,
          message: '模型尚未配置：当前模型提供方没有可用凭据。',
          code: 'MISSING_CREDENTIAL',
          detail: '{"code":"MISSING_CREDENTIAL"}',
        ),
      ),
    ]);
    expect(find.byKey(const ValueKey('turn-notice')), findsOneWidget);
    expect(find.byIcon(Icons.error_outline_rounded), findsOneWidget);
    expect(find.textContaining('模型尚未配置'), findsOneWidget);
    expect(find.byKey(const ValueKey('turn-notice-code')), findsNothing);
    expect(find.byKey(const ValueKey('turn-notice-copy')), findsNothing);
    expect(find.byType(SelectableText), findsNothing);
    expect(find.byKey(const ValueKey('turn-notice-detail')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    expect(find.byKey(const ValueKey('turn-notice-code')), findsOneWidget);
    expect(find.byKey(const ValueKey('turn-notice-detail')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('turn-notice-copy')));
    await tester.pump();
    expect(copied.single, contains('MISSING_CREDENTIAL'));
    expect(copied.single, contains('模型尚未配置'));
  });

  testWidgets('a stopped turn is neutral and never uses the error tone', (
    tester,
  ) async {
    await _pump(tester, [
      _notice(
        const TurnNotice(kind: TurnNoticeKind.stopped, message: '你停止了这一轮。'),
      ),
    ]);
    expect(find.byKey(const ValueKey('turn-notice')), findsOneWidget);
    // Collapsed, the row carries the readable reason; the state name follows
    // once the row is opened.
    expect(find.text('你停止了这一轮。'), findsOneWidget);
    final icon = tester.widget<Icon>(find.byIcon(Icons.stop_circle_outlined));
    expect(
      icon.color,
      DshPalette.colorsFor(DshMood.mist, Brightness.light).inkSoft,
    );
    expect(find.byIcon(Icons.error_outline_rounded), findsNothing);
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    expect(find.text('任务已停止'), findsOneWidget);
  });

  testWidgets('a long reason stays readable at 360dp and 2x system text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, [
      _notice(
        const TurnNotice(
          kind: TurnNoticeKind.failed,
          message:
              '模型尚未配置：当前模型提供方没有可用凭据。请登录账号或添加 API Key 后重试，'
              '如果仍然失败请检查模型配置页里的服务提供商与密钥名称。',
          code: 'MISSING_CREDENTIAL_LONG_NAME',
          detail: '{"code":"MISSING_CREDENTIAL_LONG_NAME","message":"x"}',
        ),
      ),
    ]);
    expect(tester.takeException(), isNull);
    // Actions stay reachable with a real 48dp target under large text.
    final toggle = find.byKey(const ValueKey('turn-notice'));
    await tester.ensureVisible(toggle);
    await tester.pump();
    await tester.tap(toggle);
    await tester.pump();
    final copy = find.byKey(const ValueKey('turn-notice-copy'));
    expect(tester.getSize(copy).width, greaterThanOrEqualTo(48));
    expect(tester.getSize(copy).height, greaterThanOrEqualTo(48));
    expect(find.byKey(const ValueKey('turn-notice-detail')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('sign-out and interruption name their own ending', (
    tester,
  ) async {
    await _pump(tester, [
      _notice(
        const TurnNotice(
          kind: TurnNoticeKind.signedOut,
          message: '账号已退出登录，这一轮已停止。',
          code: 'ACCOUNT_SIGNED_OUT',
        ),
      ),
    ]);
    // A stop is not the reason here: the title must say the account signed out
    // and the body must say what to do next.
    expect(find.text('任务已停止'), findsNothing);
    expect(find.textContaining('账号已退出'), findsWidgets);
    await _expand(tester);
    expect(find.textContaining('重新登录后重试'), findsOneWidget);

    await _pump(tester, [
      _notice(
        const TurnNotice(
          kind: TurnNoticeKind.interrupted,
          message: 'DSH 进程在这一轮结束前退出。',
          code: 'INTERRUPTED',
        ),
      ),
    ]);
    expect(find.text('任务已停止'), findsNothing);
    await _expand(tester);
    expect(find.textContaining('运行已中断'), findsWidgets);
    expect(find.textContaining('恢复后重试'), findsOneWidget);
  });

  testWidgets('a user stop stays neutral and offers no recovery step', (
    tester,
  ) async {
    await _pump(tester, [
      _notice(
        const TurnNotice(kind: TurnNoticeKind.stopped, message: '你停止了这一轮。'),
      ),
    ]);
    expect(find.textContaining('重新登录'), findsNothing);
    expect(find.textContaining('恢复后'), findsNothing);
  });

  testWidgets('the failure code chip is readable at 12sp or more', (
    tester,
  ) async {
    await _pump(tester, [
      _notice(
        const TurnNotice(
          kind: TurnNoticeKind.failed,
          message: '模型尚未配置：当前模型提供方没有可用凭据。',
          code: 'MISSING_CREDENTIAL',
        ),
      ),
    ]);
    await _expand(tester);
    final chip = tester.widget<Text>(
      find.byKey(const ValueKey('turn-notice-code')),
    );
    expect(chip.data, contains('MISSING_CREDENTIAL'));
    expect(chip.style?.fontSize, greaterThanOrEqualTo(12));
  });

  testWidgets('a card without a code hides the empty code chip', (
    tester,
  ) async {
    await _pump(tester, [
      _notice(
        const TurnNotice(
          kind: TurnNoticeKind.maxTokens,
          message: '已达到本次输出的令牌上限，可以继续输入。',
        ),
      ),
    ]);
    expect(find.byKey(const ValueKey('turn-notice-code')), findsNothing);
    expect(find.byKey(const ValueKey('turn-notice')), findsOneWidget);
    expect(find.byIcon(Icons.warning_amber_rounded), findsOneWidget);
  });
}

import 'dart:async';

import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/composer_model.dart';
import 'package:dsh_native_chat/src/model/turn_notice.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/state/composer_draft.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// The native host channel the recovery actions route through.
const _hostChannel = MethodChannel('app.dsh.mobile/native_chat');

/// Records which settings section the shell asked the host to open.
///
/// Returning true stands for a host that accepted the request: that receipt is
/// what the banner and the turn card must report as "opened". A surface without
/// this channel is covered by connection_recovery_test instead.
class _HostRecorder {
  _HostRecorder({required this.accepts});

  final bool accepts;
  final List<String?> sections = [];

  void install(WidgetTester tester) {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      _hostChannel,
      (call) async {
        if (call.method == 'openSettingsSection') {
          sections.add((call.arguments as Map)['section'] as String?);
          return accepts;
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        _hostChannel,
        null,
      ),
    );
  }
}

/// A link the test drops on purpose, publishes notice rows on, and heals only
/// when a reconnect is asked for.
///
/// It also owns the Host-side composer configuration, so one transport can put
/// the shell in either failure mode: a dead link, or a live link with a tool
/// permission the native surface cannot echo.
class _RecoveryTransport implements DshTransport, SessionComposerTransport {
  final _events = EventStreamController();
  int _generation = 1;
  int _sequence = 0;
  bool _connected = false;
  List<ChatMessage> _messages = const [];

  int reconnects = 0;
  final List<String> sent = [];

  ComposerConfiguration _configuration = const ComposerConfiguration(
    modelId: 'fixture-fast',
    permissionPreset: 'read-only',
  );
  final _configurationChanges =
      StreamController<ComposerConfiguration>.broadcast(sync: true);

  @override
  ComposerConfiguration get composerConfiguration => _configuration;

  @override
  Stream<ComposerConfiguration> get composerConfigurationChanges =>
      _configurationChanges.stream;

  void publish(ComposerConfiguration value) {
    _configuration = value;
    _configurationChanges.add(value);
  }

  /// Puts a turn-ending row in the transcript, as a finished turn would.
  void showNotice(TurnNotice notice) {
    _messages = [
      ChatMessage(
        id: 'notice-1',
        role: ChatRole.notice,
        text: notice.message,
        notice: notice,
      ),
    ];
    _events.add(
      ChatEvent.snapshot(
        generation: _generation,
        sequence: _sequence,
        messages: _messages,
      ),
    );
    _sequence++;
  }

  @override
  Stream<ChatEvent> get events => _events.stream;

  @override
  bool get isFixture => false;

  @override
  Future<void> connect() async {
    if (_connected) return;
    _connected = true;
    _sequence = 0;
    _events.add(
      ChatEvent.snapshot(
        generation: _generation,
        sequence: _sequence,
        messages: _messages,
      ),
    );
    _sequence++;
  }

  void drop() {
    if (!_connected) return;
    _connected = false;
    _events.add(
      ChatEvent.disconnected(generation: _generation, sequence: _sequence++),
    );
  }

  @override
  Future<void> reconnect() async {
    reconnects++;
    _connected = true;
    _generation++;
    _sequence = 0;
    _events.add(
      ChatEvent.snapshot(
        generation: _generation,
        sequence: _sequence,
        messages: _messages,
      ),
    );
    _sequence++;
  }

  @override
  Future<void> sendPrompt(String text) async => sent.add(text);

  @override
  Future<void> stop() async {}

  @override
  Future<void> approve(String requestId) async {}

  @override
  Future<void> answer(String requestId, String answer) async {}

  @override
  Future<void> close() async {
    await _configurationChanges.close();
    await _events.close();
  }
}

Future<void> _pumpShell(
  WidgetTester tester,
  ChatController controller, {
  ComposerDraft? draft,
}) async {
  tester.view.physicalSize = const Size(400, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: ChatShell(controller: controller, draft: draft),
    ),
  );
  await tester.pump();
  await tester.pump();
}

Future<void> _settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 16));
}

/// The host channel is a real messenger round trip; the widget tester's fake
/// clock never delivers it, so without this the answer would never be read.
Future<void> _hostRoundTrip(WidgetTester tester) async {
  await tester.runAsync(
    () => Future<void>.delayed(const Duration(milliseconds: 20)),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 16));
}

String _feedback(WidgetTester tester, String key) {
  final row = find.byKey(ValueKey(key));
  return tester
      .widget<Text>(find.descendant(of: row, matching: find.byType(Text)).first)
      .data!;
}

String _composerText(WidgetTester tester) => tester
    .widget<EditableText>(
      find.descendant(
        of: find.byKey(const ValueKey('composer-input')),
        matching: find.byType(EditableText),
      ),
    )
    .controller
    .text;

void main() {
  testWidgets(
    'unreadable permission keeps sending disabled without reconnect action',
    (tester) async {
      final transport = _RecoveryTransport();
      final controller = ChatController(transport);
      await _pumpShell(tester, controller);
      // The transport is up; only the tool permission cannot be echoed.
      // This banner must route to permission settings without offering a
      // reconnect action that cannot restore the missing authority.
      transport.publish(
        const ComposerConfiguration(
          modelId: 'fixture-fast',
          permissionPreset: 'custom',
        ),
      );
      await _settle(tester);
      expect(find.text('请确认会话权限'), findsOneWidget);
      expect(controller.canSend, isFalse);
      expect(
        find.byKey(const ValueKey('connection-action-retry')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('connection-action-settings')),
        findsOneWidget,
      );
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets('a dead link opens the connection page, not another one', (
    tester,
  ) async {
    final host = _HostRecorder(accepts: true)..install(tester);
    final transport = _RecoveryTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.drop();
    await _settle(tester);
    expect(find.text('连接已中断'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('connection-action-settings')));
    await tester.pump();
    await _hostRoundTrip(tester);
    expect(host.sections.single, 'connections');
    expect(
      _feedback(tester, 'connection-action-feedback'),
      contains('已打开连接与远程'),
    );
    expect(transport.sent, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('an unreadable permission opens the permission page', (
    tester,
  ) async {
    final host = _HostRecorder(accepts: true)..install(tester);
    final transport = _RecoveryTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    transport.publish(
      const ComposerConfiguration(
        modelId: 'fixture-fast',
        permissionPreset: 'custom',
      ),
    );
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('connection-action-settings')));
    await tester.pump();
    await _hostRoundTrip(tester);
    expect(host.sections.single, 'permissions');
    expect(
      _feedback(tester, 'connection-action-feedback'),
      contains('已打开权限管理'),
    );
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a recovered draft is appended to newer typing and never sent', (
    tester,
  ) async {
    final transport = _RecoveryTransport();
    final controller = ChatController(transport);
    final draft = ComposerDraft();
    addTearDown(draft.dispose);
    await _pumpShell(tester, controller, draft: draft);
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '断线前的话',
    );
    await tester.pump();

    transport.drop();
    await _settle(tester);
    // The page rebuilt empty: this is the loss the offer exists for.
    draft.clear();
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('connection-action-retry')));
    await tester.pump();
    await _settle(tester);
    await tester.pump(const Duration(milliseconds: 16));
    expect(find.byKey(const ValueKey('draft-recovery')), findsOneWidget);

    // Typing continues while the offer is showing: filling back must not
    // overwrite what was typed after the reconnect.
    await tester.enterText(
      find.byKey(const ValueKey('composer-input')),
      '新写的一句',
    );
    await tester.pump();
    expect(find.textContaining('会接在现有内容后面'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('draft-recovery-restore')));
    await tester.pump();
    expect(
      draft.text,
      '新写的一句' + String.fromCharCode(10) + String.fromCharCode(10) + '断线前的话',
    );
    expect(_composerText(tester), draft.text);
    // Restoring is not sending: nothing went out.
    expect(transport.sent, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets('a turn card opens the page its ending belongs to', (
    tester,
  ) async {
    final host = _HostRecorder(accepts: true)..install(tester);
    final transport = _RecoveryTransport();
    final controller = ChatController(transport);
    await _pumpShell(tester, controller);
    // A missing credential is fixed on the model page, so the card must route
    // there instead of a generic settings home.
    transport.showNotice(
      const TurnNotice(
        kind: TurnNoticeKind.failed,
        message: '模型尚未配置：当前模型提供方没有可用凭据。',
        code: 'MISSING_CREDENTIAL',
      ),
    );
    await _settle(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice-action-settings')));
    await tester.pump();
    await _hostRoundTrip(tester);
    expect(host.sections.single, 'models');
    expect(
      _feedback(tester, 'turn-notice-action-feedback'),
      contains('已打开模型配置'),
    );
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
}

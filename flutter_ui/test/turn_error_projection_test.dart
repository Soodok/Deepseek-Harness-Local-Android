import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/turn_notice.dart';
import 'package:dsh_native_chat/src/state/chat_reducer.dart';
import 'package:dsh_native_chat/src/state/chat_state.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/transport/dsh_session_projection.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// Behavioural guardrails for terminal turn endings: they are durable content
/// with structured metadata, never transport failures or model speech.

Map<String, Object?> record(int seq, String type, Map<String, Object?> data) =>
    {
      'type': 'event',
      'event': {
        'seq': seq,
        'time': seq * 10,
        'type': type,
        'data': data,
        'surfaceOp': 'append',
      },
    };
Map<String, Object?> user(int seq, String text) => record(seq, 'user/message', {
  'id': 'u$seq',
  'source': {'kind': 'user'},
  'content': [
    {'type': 'text', 'text': text},
  ],
});
Map<String, Object?> snapshot(
  int cursor,
  List<Map<String, Object?>> records,
) => {
  'type': 'snapshot',
  'header': {'id': 'parent', 'version': 2, 'createdAt': 0, 'isSeeded': false},
  'cursor': cursor,
  'records': records,
  'hasMore': false,
  'projections': {'asOfSeq': cursor, 'values': <String, Object?>{}},
  'assistantStream': {'revision': 0},
};

ChatState apply(ChatState state, List<ChatEvent> events) {
  for (final event in events) {
    state = ChatReducer.apply(state, event);
  }
  return state;
}

void main() {
  late DshSessionProjection projection;
  late ChatState state;
  late List<ChatEvent> seen;
  setUp(() {
    projection = DshSessionProjection(sessionId: 'parent', generation: 1);
    state = ChatState.empty();
    seen = [];
  });
  void accept(List<ChatEvent> events) {
    seen.addAll(events);
    state = apply(state, events);
  }

  List<ChatMessage> nonHuman() => state.messages
      .where((m) => m.role != ChatRole.user)
      .toList(growable: false);

  test('a user cancellation is a neutral stop, never a failure', () {
    accept(projection.accept(snapshot(0, [user(0, 'prompt')])));
    accept(
      projection.accept(
        record(1, 'turn/end', {
          'turn': 1,
          'reason': {
            'kind': 'aborted',
            'reason': {'kind': 'user'},
          },
        }),
      ),
    );
    final rows = nonHuman();
    expect(rows, hasLength(1));
    expect(rows.single.role, ChatRole.notice);
    expect(rows.single.notice!.kind, TurnNoticeKind.stopped);
    expect(rows.single.notice!.isFailure, isFalse);
    expect(seen.where((e) => e.kind == ChatEventKind.error), isEmpty);
    expect(state.error, isNull);
    expect(state.status, ChatStatus.ready);
  });

  test('a sign-out cancellation keeps the DSH failure card', () {
    accept(projection.accept(snapshot(0, [user(0, 'prompt')])));
    accept(
      projection.accept(
        record(1, 'turn/end', {
          'turn': 1,
          'reason': {
            'kind': 'aborted',
            'reason': {'kind': 'hook', 'reason': 'deepseek-account/signed-out'},
          },
        }),
      ),
    );
    final notice = nonHuman().single.notice!;
    expect(notice.kind, TurnNoticeKind.signedOut);
    expect(notice.code, 'ACCOUNT_SIGNED_OUT');
    expect(notice.isFailure, isTrue);
  });

  test('a parent cancellation is neutral and keeps the real cause', () {
    accept(projection.accept(snapshot(0, [user(0, 'prompt')])));
    accept(
      projection.accept(
        record(1, 'turn/end', {
          'turn': 1,
          'reason': {
            'kind': 'aborted',
            'reason': {'kind': 'parent'},
          },
        }),
      ),
    );
    final notice = nonHuman().single.notice!;
    expect(notice.kind, TurnNoticeKind.stopped);
    expect(notice.isFailure, isFalse);
    expect(notice.message, contains('父会话'));
    expect(notice.detail, contains('parent'));
  });

  test(
    'a terminal model failure is a typed notice, not model or system text',
    () {
      accept(projection.accept(snapshot(0, [user(0, 'prompt')])));
      accept(
        projection.accept(
          record(1, 'turn/end', {
            'turn': 1,
            'reason': {
              'kind': 'error',
              'error': {'code': 'PROVIDER', 'message': 'provider failed'},
            },
          }),
        ),
      );
      // A turn failure must not masquerade as a connection failure: the retry
      // banner is for transport problems, and the composer stays usable.
      expect(seen.where((e) => e.kind == ChatEventKind.error), isEmpty);
      expect(state.error, isNull);
      expect(state.status, ChatStatus.ready);
      final rows = nonHuman();
      expect(rows, hasLength(1));
      expect(rows.single.role, ChatRole.notice);
      final notice = rows.single.notice!;
      expect(notice.kind, TurnNoticeKind.failed);
      expect(notice.code, 'PROVIDER');
      expect(notice.isFailure, isTrue);
      expect(notice.message, contains('provider failed'));
      // The row text is the card's own reason, never model speech content.
      expect(rows.single.text, notice.message);
      expect(notice.detail, contains('provider failed'));
    },
  );

  test('an unfinished turn never puts the live cursor on a closed card', () {
    // A surface where a previous turn already ended with a card, and a new turn
    // has started without any visible model output yet.
    accept(
      projection.accept(
        snapshot(2, [
          user(0, 'prompt'),
          record(1, 'turn/end', {
            'turn': 1,
            'reason': {
              'kind': 'error',
              'error': {'code': 'PROVIDER', 'message': 'provider failed'},
            },
          }),
          record(2, 'turn/start', {'turn': 2}),
        ]),
      ),
    );
    final card = state.messages.singleWhere((m) => m.role == ChatRole.notice);
    // The card is a closed record: the spinner belongs to the new turn, which
    // still owns the run through its activity row.
    expect(card.isStreaming, isFalse);
    final live = state.messages.where((m) => m.isStreaming).toList();
    expect(live, hasLength(1));
    expect(live.single.id, 'parent:activity');
    expect(live.single.role, ChatRole.system);
  });

  test('history and live projection publish the same single failure row', () {
    accept(
      projection.accept(
        snapshot(1, [
          user(0, 'prompt'),
          record(1, 'turn/end', {
            'turn': 1,
            'reason': {
              'kind': 'error',
              'error': {'code': 'MISSING_CREDENTIAL', 'message': 'no API key'},
            },
          }),
        ]),
      ),
    );
    expect(nonHuman(), hasLength(1));
    final text = nonHuman().single.text;
    expect(text, contains('模型尚未配置'));
    // Republishing the same journal never duplicates the row.
    accept(projection.idle(throughSeq: 1));
    expect(nonHuman(), hasLength(1));
  });

  // Auth diagnostics can carry a masked credential or a raw account token, so
  // neither the readable reason, the expandable payload, nor the copy text may
  // echo them. The sentinel stands in for a real secret in a fake payload; no
  // real credential is read anywhere in this file.
  const sentinel = 'SENTINEL-9f3a-token';
  Map<String, Object?> authEnd(String code, {bool nestedOnly = false}) =>
      record(1, 'turn/end', {
        'turn': 1,
        'reason': {
          'kind': 'error',
          'error': {
            'code': code,
            if (!nestedOnly) 'message': 'provider rejected ' + sentinel,
            'data': {'accessToken': sentinel, 'masked': sentinel},
          },
        },
      });

  test('an AUTH failure keeps the code and reason, never the payload', () {
    accept(projection.accept(snapshot(0, [user(0, 'prompt')])));
    accept(projection.accept(authEnd('AUTH')));
    final notice = nonHuman().single.notice!;
    expect(notice.kind, TurnNoticeKind.failed);
    expect(notice.code, 'AUTH');
    expect(notice.message, contains('账号未登录'));
    expect(notice.message, isNot(contains(sentinel)));
    expect(notice.detail, contains('AUTH'));
    expect(notice.detail, isNot(contains(sentinel)));
  });

  test('a sign-in-required failure hides a token nested in the payload', () {
    accept(projection.accept(snapshot(0, [user(0, 'prompt')])));
    accept(
      projection.accept(authEnd('ACCOUNT_SIGN_IN_REQUIRED', nestedOnly: true)),
    );
    final notice = nonHuman().single.notice!;
    expect(notice.code, 'ACCOUNT_SIGN_IN_REQUIRED');
    expect(notice.message, contains('账号未登录'));
    expect(notice.detail, contains('ACCOUNT_SIGN_IN_REQUIRED'));
    expect(notice.detail, isNot(contains(sentinel)));
  });

  test('an unknown non-auth failure keeps its usable diagnostics', () {
    accept(projection.accept(snapshot(0, [user(0, 'prompt')])));
    accept(
      projection.accept(
        record(1, 'turn/end', {
          'turn': 1,
          'reason': {
            'kind': 'error',
            'error': {'code': 'PROVIDER_OVERLOADED', 'message': 'queue full'},
          },
        }),
      ),
    );
    final notice = nonHuman().single.notice!;
    expect(notice.code, 'PROVIDER_OVERLOADED');
    expect(notice.message, contains('queue full'));
    expect(notice.detail, contains('queue full'));
  });

  testWidgets('the card copy action never carries a redacted diagnostic', (
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
    accept(projection.accept(snapshot(0, [user(0, 'prompt')])));
    accept(projection.accept(authEnd('AUTH')));
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: ConversationFlow(
            conversationIdentity: 'parent',
            messages: state.messages,
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('turn-notice-copy')));
    await tester.pump();
    expect(copied.single, contains('AUTH'));
    expect(copied.single, contains('账号未登录'));
    expect(copied.single, isNot(contains(sentinel)));
  });
}

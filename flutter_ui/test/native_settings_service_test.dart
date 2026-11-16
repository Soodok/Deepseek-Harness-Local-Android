import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_settings_service.dart';

class FakeRpc extends DshRpcConnection {
  FakeRpc() : super(webUrl: 'http://127.0.0.1:1234/?token=test');
  final updates = StreamController<Map<String, Object?>>.broadcast();
  final calls = <({String endpoint, Map<String, Object?> args})>[];
  Map<String, Object?> state = {
    'status': 'signed-out',
    'links': {
      'usageUrl': 'https://platform.deepseek.com/usage',
      'topUpUrl': 'https://platform.deepseek.com/top_up',
    },
    'attempt': null,
  };
  Object? profile;
  int closes = 0;
  Completer<Object?>? startGate;
  @override
  Future<Object?> call(String endpoint, Map<String, Object?> args) async {
    calls.add((endpoint: endpoint, args: args));
    if (endpoint == 'account/startSignIn') {
      if (startGate != null) return startGate!.future;
      state = {
        ...state,
        'attempt': {'id': 'attempt-a', 'phase': 'initializing'},
      };
      return state;
    }
    if (endpoint == 'account/getProfile') return profile;
    if (endpoint == 'account/hasRunningAccountTasks') return false;
    if (endpoint == 'account/signOut') {
      state = {...state, 'status': 'signed-out', 'attempt': null};
      return state;
    }
    return state;
  }

  @override
  Stream<Map<String, Object?>> follow(
    String endpoint,
    Map<String, Object?> args,
  ) => updates.stream;
  void emit(
    String phase, {
    String? url,
    String id = 'attempt-a',
    bool signedIn = false,
  }) {
    state = {
      ...state,
      'status': signedIn ? 'credential-stored' : 'signed-out',
      'attempt': {'id': id, 'phase': phase, 'authorizeUrl': ?url},
    };
    updates.add({'type': 'snapshot', 'state': state});
  }

  @override
  Future<void> close() async {
    closes++;
    await updates.close();
  }
}

void main() {
  test(
    'cancellation during initialization cancels the eventual named attempt',
    () async {
      final rpc = FakeRpc()..startGate = Completer<Object?>();
      final service = NativeSettingsService(
        rpc: rpc,
        callbackOrigin: Uri.parse('http://127.0.0.1:1234'),
        openBrowser: (_) async => throw StateError('must not open'),
      );
      final operation = service.signIn();
      final assertion = expectLater(operation, throwsStateError);
      final cancel = service.cancelSignIn();
      rpc.startGate!.complete({
        ...rpc.state,
        'attempt': {'id': 'late-attempt', 'phase': 'initializing'},
      });
      await cancel;
      await assertion;
      expect(
        rpc.calls
            .where((c) => c.endpoint == 'account/cancelSignIn')
            .map((c) => c.args['attemptId']),
        ['late-attempt'],
      );
      await service.close();
    },
  );
  test(
    'profile maps only sanitized fields and rejects unsafe avatar URL',
    () async {
      final rpc = FakeRpc();
      rpc.state = {...rpc.state, 'status': 'credential-stored'};
      rpc.profile = {
        'status': 'ready',
        'value': {
          'id': 'u1',
          'name': '昵称',
          'contact': 'a***@mail.com',
          'avatarUrl': 'javascript:bad',
        },
      };
      final s = NativeSettingsService(
        rpc: rpc,
        callbackOrigin: Uri.parse('http://127.0.0.1:1234'),
        openBrowser: (_) async => true,
      );
      final p = await s.loadProfile();
      expect(p?.displayName, '昵称');
      expect(p?.email, 'a***@mail.com');
      expect(p?.avatarUrl, isNull);
      expect(rpc.calls.last.args['client'], containsPair('locale', 'zh-CN'));
      await s.close();
    },
  );
  test(
    'browser flow uses host callback and ignores unrelated attempt completion',
    () async {
      final rpc = FakeRpc();
      final opened = <Uri>[];
      final s = NativeSettingsService(
        rpc: rpc,
        callbackOrigin: Uri.parse('http://127.0.0.1:1234'),
        openBrowser: (uri) async {
          opened.add(uri);
          return true;
        },
      );
      final login = s.signIn();
      await Future<void>.delayed(Duration.zero);
      rpc.emit(
        'waiting-browser',
        url: 'https://platform.deepseek.com/dsh/authorize?id=challenge',
      );
      await Future<void>.delayed(Duration.zero);
      rpc.emit('succeeded', id: 'old', signedIn: true);
      bool finished = false;
      unawaited(
        login.then((_) {
          finished = true;
        }),
      );
      await Future<void>.delayed(Duration.zero);
      expect(finished, isFalse);
      rpc.emit('succeeded', signedIn: true);
      await login;
      expect(opened.single.queryParameters['token'], isNull);
      final start = rpc.calls.singleWhere(
        (c) => c.endpoint == 'account/startSignIn',
      );
      expect(start.args['callbackOrigin'], 'http://127.0.0.1:1234');
      expect(start.args['loginSource'], 'web');
      await s.close();
    },
  );
  test(
    'malicious authorize destination is rejected and named attempt cancelled',
    () async {
      final rpc = FakeRpc();
      int opened = 0;
      final s = NativeSettingsService(
        rpc: rpc,
        callbackOrigin: Uri.parse('http://127.0.0.1:1234'),
        openBrowser: (_) async {
          opened++;
          return true;
        },
      );
      final login = s.signIn();
      final assertion = expectLater(login, throwsA(isA<FormatException>()));
      await Future<void>.delayed(Duration.zero);
      rpc.emit('waiting-browser', url: 'https://other.example/dsh/authorize');
      await assertion;
      expect(opened, 0);
      expect(
        rpc.calls
            .where((c) => c.endpoint == 'account/cancelSignIn')
            .single
            .args,
        {'attemptId': 'attempt-a'},
      );
      await s.close();
    },
  );
  test('unsupported callback is refused before any account mutation', () async {
    final rpc = FakeRpc();
    final s = NativeSettingsService(
      rpc: rpc,
      callbackOrigin: Uri.parse('https://remote.example'),
      openBrowser: (_) async => true,
    );
    await expectLater(s.signIn(), throwsA(isA<ArgumentError>()));
    expect(rpc.calls, isEmpty);
    await s.close();
  });
  test('logout checks account tasks and passes client metadata', () async {
    final rpc = FakeRpc();
    final s = NativeSettingsService(
      rpc: rpc,
      callbackOrigin: Uri.parse('http://127.0.0.1:1234'),
      openBrowser: (_) async => true,
    );
    await s.signOut();
    expect(rpc.calls.map((c) => c.endpoint), [
      'account/hasRunningAccountTasks',
      'account/signOut',
    ]);
    expect(
      (rpc.calls.last.args['client'] as Map)['timezoneOffsetSeconds'],
      DateTime.now().timeZoneOffset.inSeconds,
    );
    await s.close();
  });
  test(
    'cancel closes pending stream without accepting a later successful attempt',
    () async {
      final rpc = FakeRpc();
      final s = NativeSettingsService(
        rpc: rpc,
        callbackOrigin: Uri.parse('http://127.0.0.1:1234'),
        openBrowser: (_) async => true,
      );
      final login = s.signIn();
      final assertion = expectLater(login, throwsA(isA<StateError>()));
      await Future<void>.delayed(Duration.zero);
      await s.cancelSignIn();
      await assertion;
      rpc.emit('succeeded', signedIn: true);
      await s.close();
    },
  );
}

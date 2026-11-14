import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:flutter_test/flutter_test.dart';

typedef RpcRequest = ({
  String endpoint,
  Map<String, Object?> envelope,
  String? cookie,
});
typedef MuxRequest = ({WebSocket socket, Map<String, Object?> frame});

/// Real loopback carrier shared by the two transport suites.
class LoopbackDshHost {
  late final HttpServer server;
  final requests = <RpcRequest>[];
  final exchanges = <Uri>[];
  final upgrades = <({Uri uri, String? cookie})>[];
  final sockets = <WebSocket>[];
  final frames = <MuxRequest>[];
  final _arrivals = StreamController<MuxRequest>.broadcast(sync: true);
  Future<Object?> Function(RpcRequest)? onCall;
  void Function(MuxRequest)? onFrame;
  Future<void> Function(HttpRequest)? onExchange;
  bool wrongRpcId = false;
  Map<String, Object?>? failure;
  String location = './api/remote.mux';
  String get webUrl =>
      'http://127.0.0.1:${server.port}/mount/?token=launch-secret';

  Future<void> start() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) {
      unawaited(_handle(request));
    });
  }

  Future<void> _handle(HttpRequest request) async {
    try {
      if (request.uri.path == '/mount/connect') {
        exchanges.add(request.uri);
        if (onExchange != null) await onExchange!(request);
        request.response.statusCode = HttpStatus.found;
        request.response.headers.set(HttpHeaders.locationHeader, location);
        request.response.cookies.add(
          Cookie('dsh-peer', 'peer-${exchanges.length}')
            ..httpOnly = true
            ..path = '/mount/',
        );
        await request.response.close();
        return;
      }
      if (request.uri.path == '/mount/api/remote.mux' &&
          WebSocketTransformer.isUpgradeRequest(request)) {
        upgrades.add((
          uri: request.uri,
          cookie: request.headers.value(HttpHeaders.cookieHeader),
        ));
        final socket = await WebSocketTransformer.upgrade(request);
        sockets.add(socket);
        socket.listen((raw) {
          final frame = (
            socket: socket,
            frame: Map<String, Object?>.from(jsonDecode(raw as String) as Map),
          );
          frames.add(frame);
          _arrivals.add(frame);
          onFrame?.call(frame);
        }, onError: (Object _) {});
        return;
      }
      if (request.method == 'POST' &&
          request.uri.path.startsWith('/mount/api/')) {
        final envelope = Map<String, Object?>.from(
          jsonDecode(await utf8.decoder.bind(request).join()) as Map,
        );
        final call = (
          endpoint: request.uri.path.substring('/mount/api/'.length),
          envelope: envelope,
          cookie: request.headers.value(HttpHeaders.cookieHeader),
        );
        requests.add(call);
        final value = onCall == null
            ? <String, Object?>{'accepted': true}
            : await onCall!(call);
        request.response.headers.contentType = ContentType.json;
        request.response.write(
          jsonEncode({
            'type': 'server-response',
            'rpcId': wrongRpcId ? 'wrong-id' : envelope['rpcId'],
            'result': failure == null
                ? {'ok': true, 'value': value}
                : {'ok': false, 'error': failure},
          }),
        );
        await request.response.close();
        return;
      }
      request.response.statusCode = HttpStatus.notFound;
      await request.response.close();
    } on HttpException {
      // Close-during-response cases deliberately tear down the carrier.
    } on SocketException {
      // The client may close before a delayed server response.
    }
  }

  Future<MuxRequest> waitFrame(bool Function(MuxRequest) match) {
    for (final frame in frames) {
      if (match(frame)) return Future.value(frame);
    }
    return _arrivals.stream
        .firstWhere(match)
        .timeout(const Duration(seconds: 3));
  }

  void item(MuxRequest r, Object? value) => r.socket.add(
    jsonEncode({
      'type': 'item',
      'streamId': r.frame['streamId'],
      'value': value,
    }),
  );
  void end(MuxRequest r) => r.socket.add(
    jsonEncode({'type': 'end', 'streamId': r.frame['streamId']}),
  );
  Future<void> close() async {
    for (final socket in sockets) {
      unawaited(socket.close());
    }
    await server.close(force: true);
    await _arrivals.close();
  }
}

void main() {
  late LoopbackDshHost host;
  late DshRpcConnection rpc;
  setUp(() async {
    host = LoopbackDshHost();
    await host.start();
    rpc = DshRpcConnection(webUrl: host.webUrl);
  });
  tearDown(() async {
    await rpc.close();
    await host.close();
  });

  test('exchange preserves mount, peer Cookie and a single socket', () async {
    await Future.wait([rpc.connect(), rpc.connect()]);
    expect(
      await rpc.call('session/state', {
        'request': {'sessionId': 'child'},
      }),
      {'accepted': true},
    );
    expect(host.exchanges.single.path, '/mount/connect');
    expect(host.exchanges.single.queryParameters, {
      'token': 'launch-secret',
      'interactions': 'answer',
    });
    expect(host.upgrades.single.uri.toString(), '/mount/api/remote.mux');
    expect(host.upgrades.single.cookie, 'dsh-peer=peer-1');
    final request = host.requests.single;
    expect(request.cookie, 'dsh-peer=peer-1');
    expect(request.envelope, {
      'type': 'client-request',
      'rpcId': isA<String>(),
      'method': 'session/state',
      'payload': {
        'args': {
          'request': {'sessionId': 'child'},
        },
      },
    });
    expect(host.sockets, hasLength(1));
  });

  test(
    'HTTP 200 business failure preserves code, message and details',
    () async {
      host.failure = {
        'code': 'subagent/not-resumable',
        'message': 'read only',
        'details': {'childSessionId': 'child'},
      };
      await expectLater(
        rpc.call('subagents/prompt', {}),
        throwsA(
          isA<DshRpcException>()
              .having((e) => e.code, 'code', 'subagent/not-resumable')
              .having((e) => e.message, 'message', 'read only')
              .having((e) => e.details, 'details', {'childSessionId': 'child'}),
        ),
      );
    },
  );

  test('rpcId mismatch fails even when result is successful', () async {
    host.wrongRpcId = true;
    await expectLater(
      rpc.call('session/state', {}),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'connection/protocol',
        ),
      ),
    );
  });

  test('mux dispatches immediate items, errors and ends by streamId', () async {
    host.onFrame = (r) {
      if (r.frame['type'] != 'open') return;
      if (r.frame['endpoint'] == 'one') {
        host.item(r, {'text': 'first'});
        host.end(r);
      } else {
        r.socket.add(
          jsonEncode({
            'type': 'error',
            'streamId': r.frame['streamId'],
            'error': {
              'code': 'child/failed',
              'message': 'failed',
              'details': {'id': 2},
            },
          }),
        );
      }
    };
    final one = rpc.follow('one', {'n': 1}).toList();
    final two = expectLater(
      rpc.follow('two', {'n': 2}),
      emitsError(
        isA<DshRpcException>().having((e) => e.code, 'code', 'child/failed'),
      ),
    );
    expect(await one, [
      {'text': 'first'},
    ]);
    await two;
    final opens = host.frames.where((r) => r.frame['type'] == 'open').toList();
    expect(opens, hasLength(2));
    expect(opens.map((r) => r.frame['streamId']).toSet(), hasLength(2));
    expect(opens.first.frame['payload'], {
      'args': {'n': 1},
    });
    expect(host.sockets, hasLength(1));
  });

  test('cancel only unsubscribes its stream and ignores late items', () async {
    final seen = <Map<String, Object?>>[];
    final a = rpc.follow('session/follow', {'child': 'a'}).listen(seen.add);
    final b = StreamIterator(rpc.follow('session/follow', {'child': 'b'}));
    final nextB = b.moveNext();
    final first = await host.waitFrame(
      (r) =>
          r.frame['type'] == 'open' &&
          ((r.frame['payload'] as Map)['args'] as Map)['child'] == 'a',
    );
    final second = await host.waitFrame(
      (r) =>
          r.frame['type'] == 'open' &&
          ((r.frame['payload'] as Map)['args'] as Map)['child'] == 'b',
    );
    await a.cancel();
    final cancel = await host.waitFrame(
      (r) =>
          r.frame['type'] == 'cancel' &&
          r.frame['streamId'] == first.frame['streamId'],
    );
    expect(cancel.frame.keys, unorderedEquals(['type', 'streamId']));
    host.item(first, {'late': true});
    host.item(second, {'alive': true});
    expect(await nextB, isTrue);
    expect(b.current, {'alive': true});
    expect(seen, isEmpty);
    expect(host.requests, isEmpty);
    expect(
      host.frames.every((r) => ['open', 'cancel'].contains(r.frame['type'])),
      isTrue,
    );
    await b.cancel();
  });

  test(
    'disconnect closes streams and reconnect exchanges a fresh Cookie',
    () async {
      final old = expectLater(
        rpc.follow('session/follow', {}),
        emitsInOrder([emitsError(isA<DshRpcException>()), emitsDone]),
      );
      final first = await host.waitFrame((r) => r.frame['type'] == 'open');
      await first.socket.close(WebSocketStatus.goingAway);
      await old;
      await rpc.connect();
      await rpc.call('session/state', {});
      expect(host.exchanges, hasLength(2));
      expect(host.upgrades.map((u) => u.cookie), [
        'dsh-peer=peer-1',
        'dsh-peer=peer-2',
      ]);
      expect(host.requests.single.cookie, 'dsh-peer=peer-2');
      final fresh = rpc.follow('new', {}).toList();
      final open = await host.waitFrame(
        (r) => r.frame['type'] == 'open' && r.frame['endpoint'] == 'new',
      );
      host.item(open, {'fresh': true});
      host.end(open);
      expect(await fresh, [
        {'fresh': true},
      ]);
    },
  );

  test('close during exchange makes a late handshake inert', () async {
    final entered = Completer<void>(), release = Completer<void>();
    host.onExchange = (_) async {
      entered.complete();
      await release.future;
    };
    final pending = expectLater(rpc.connect(), throwsA(isA<DshRpcException>()));
    await entered.future;
    await rpc.close();
    release.complete();
    await pending;
    expect(host.upgrades, isEmpty);
    await expectLater(
      rpc.connect(),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'connection/closed',
        ),
      ),
    );
  });

  test('close completes even a follow that is still connecting', () async {
    final entered = Completer<void>(), release = Completer<void>();
    host.onExchange = (_) async {
      entered.complete();
      await release.future;
    };
    final done = expectLater(rpc.follow('session/follow', {}), emitsDone);
    await entered.future;
    await rpc.close();
    release.complete();
    await done;
    expect(host.frames, isEmpty);
  });

  test('foreign redirect is rejected without following or leaking token', () async {
    host.location =
        'http://user:pass@localhost:${host.server.port}/steal?token=launch-secret';
    Object? failure;
    try {
      await rpc.connect();
    } on Object catch (error) {
      failure = error;
    }
    expect(failure, isA<DshRpcException>());
    expect(failure.toString(), isNot(contains('launch-secret')));
    expect(failure.toString(), isNot(contains('http://')));
    expect(host.upgrades, isEmpty);
  });

  test(
    'invalid URLs and endpoint traversal fail before network access',
    () async {
      for (final url in [
        'ftp://host/?token=secret',
        'http://user:password@host/?token=secret',
        'http://host/?token=a&token=b',
      ]) {
        expect(
          () => DshRpcConnection(webUrl: url),
          throwsA(isA<DshRpcException>()),
        );
      }
      for (final endpoint in [
        '../interrupt',
        '/session/state',
        'session//state',
        'https://foreign/state',
        'session/state?token=secret',
      ]) {
        await expectLater(
          rpc.call(endpoint, {}),
          throwsA(isA<DshRpcException>()),
        );
      }
      expect(host.exchanges, isEmpty);
    },
  );
}

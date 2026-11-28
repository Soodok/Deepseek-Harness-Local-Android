import 'dart:async';

import 'package:dsh_native_chat/src/ui/launch_fade.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

final Uint8List _onePixelPng = Uint8List.fromList(<int>[
  137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
  0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, 196, 137, 0,
  0, 0, 13, 73, 68, 65, 84, 120, 218, 99, 252, 207, 192, 80,
  15, 0, 4, 133, 1, 128, 132, 169, 140, 33, 0, 0, 0, 0, 73, 69,
  78, 68, 174, 66, 96, 130,
]);
const _contentKey = Key('launch-content');
const _channel = MethodChannel('app.dsh.mobile/native_chat');

BuildContext _contentContext(WidgetTester tester) =>
    tester.element(find.byKey(_contentKey));

double _opacity(WidgetTester tester) => tester.widget<Opacity>(
  find.descendant(of: find.byType(LaunchFade), matching: find.byType(Opacity)),
).opacity;

Future<void> _pumpCover(
  WidgetTester tester, {
  Uint8List? backdrop,
  bool disableAnimations = false,
  bool withoutBackdrop = false,
}) => tester.pumpWidget(
  MediaQuery(
    data: MediaQueryData(disableAnimations: disableAnimations),
    child: LaunchFade(
      backdrop: withoutBackdrop ? null : backdrop ?? _onePixelPng,
      child: const SizedBox.expand(key: _contentKey),
    ),
  ),
);

Future<void> _finishDecode(WidgetTester tester, [Uint8List? backdrop]) async {
  // Decode outside fake time; pumping seconds alone can turn an undecoded PNG
  // into a timeout, which would test a different branch and hide a regression.
  await tester.runAsync(() async {
    await precacheImage(
      MemoryImage(backdrop ?? _onePixelPng),
      _contentContext(tester),
      onError: (error, stack) {},
    );
  });
  await tester.pump();
  await tester.pump();
}

Future<void> _unmount(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump();
}

void main() {
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  testWidgets('decoded cover waits for content ready and ignores duplicate ready', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      return true;
    });
    await _pumpCover(tester);
    await _finishDecode(tester);
    await tester.pump(const Duration(seconds: 2));
    expect(_opacity(tester), 1);
    expect(calls, isEmpty);

    expect(LaunchFade.markContentReady(_contentContext(tester)), isTrue);
    expect(LaunchFade.markContentReady(_contentContext(tester)), isFalse);
    await tester.pump();
    expect(calls.map((call) => call.method), ['releaseBackdrop']);
    await _unmount(tester);
  });

  testWidgets('ready before decode cannot release the native panel', (tester) async {
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call.method);
      return true;
    });
    await _pumpCover(tester);
    expect(LaunchFade.markContentReady(_contentContext(tester)), isTrue);
    expect(calls, isEmpty);
    await _finishDecode(tester);
    expect(calls, ['releaseBackdrop']);
    await _unmount(tester);
  });

  testWidgets('pending host acknowledgement keeps a fully opaque cover', (
    tester,
  ) async {
    final acknowledgement = Completer<Object?>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) {
      return acknowledgement.future;
    });
    await _pumpCover(tester);
    await _finishDecode(tester);
    LaunchFade.markContentReady(_contentContext(tester));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(_opacity(tester), 1);
    acknowledgement.complete(true);
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(_opacity(tester), lessThan(1));
    await _unmount(tester);
  });

  testWidgets('normal frames take 1000ms and a stalled frame advances at most one step', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async => true);
    await _pumpCover(tester);
    await _finishDecode(tester);
    LaunchFade.markContentReady(_contentContext(tester));
    await tester.pump();
    await tester.pump();
    expect(_opacity(tester), 1);
    for (var frame = 0; frame < 59; frame++) {
      await tester.pump(const Duration(microseconds: 16667));
    }
    expect(_opacity(tester), greaterThan(0));
    expect(_opacity(tester), lessThan(0.02));
    await tester.pump(const Duration(microseconds: 16667));
    expect(_opacity(tester), 0);
    await _unmount(tester);

    await _pumpCover(tester);
    await _finishDecode(tester);
    LaunchFade.markContentReady(_contentContext(tester));
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(seconds: 5));
    expect(_opacity(tester), closeTo(11 / 12, 0.0001));
    for (var frame = 0; frame < 11; frame++) {
      await tester.pump(const Duration(milliseconds: 84));
    }
    expect(_opacity(tester), 0);
    await _unmount(tester);
  });

  testWidgets('slow foreground frames complete the fade in one second', (
    tester,
  ) async {
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call.method);
      return true;
    });
    await _pumpCover(tester);
    await _finishDecode(tester);
    expect(LaunchFade.markContentReady(_contentContext(tester)), isTrue);
    await tester.pump();
    await tester.pump();
    expect(_opacity(tester), 1);

    const intervals = [140, 240, 180, 220, 220];
    const expected = [.86, .62, .44, .22, 0.0];
    for (var i = 0; i < intervals.length; i++) {
      await tester.pump(Duration(milliseconds: intervals[i]));
      expect(_opacity(tester), closeTo(expected[i], 0.000001));
    }
    expect(calls, ['releaseBackdrop']);
    await _unmount(tester);
  });

  testWidgets('brief lifecycle interruption limits the first resumed frame', (
    tester,
  ) async {
    final binding = tester.binding;
    binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    addTearDown(() => binding.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    ));
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (_) async => true);
    await _pumpCover(tester);
    await _finishDecode(tester);
    expect(LaunchFade.markContentReady(_contentContext(tester)), isTrue);
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(_opacity(tester), closeTo(.8, 0.000001));

    binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump(const Duration(milliseconds: 100));
    expect(_opacity(tester), closeTo(.8, 0.000001));
    binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump(const Duration(milliseconds: 100));
    expect(_opacity(tester), closeTo(.716666, 0.000001));
    await tester.pump(const Duration(milliseconds: 200));
    expect(_opacity(tester), closeTo(.516666, 0.000001));
    await _unmount(tester);
  });

  testWidgets('false or null host acknowledgement fails instead of playing', (
    tester,
  ) async {
    for (final acknowledgement in <bool?>[false, null]) {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(_channel, (call) async {
        calls.add(call);
        return call.method == 'releaseBackdrop' ? acknowledgement : true;
      });
      await _pumpCover(tester);
      await _finishDecode(tester);
      LaunchFade.markContentReady(_contentContext(tester));
      await tester.pump();
      expect(calls.map((call) => call.method), [
        'releaseBackdrop',
        'launchFadeFailed',
      ]);
      expect((calls.last.arguments as Map)['reason'], 'host-release-not-acknowledged');
      await tester.pump();
      expect(_opacity(tester), 0);
      expect(LaunchFade.markContentReady(_contentContext(tester)), isFalse);
      await _unmount(tester);
    }
  });

  testWidgets('host timeout rejects a late successful acknowledgement', (tester) async {
    final acknowledgement = Completer<Object?>();
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) {
      calls.add(call);
      return call.method == 'releaseBackdrop'
          ? acknowledgement.future
          : Future<Object?>.value(true);
    });
    await _pumpCover(tester);
    await _finishDecode(tester);
    LaunchFade.markContentReady(_contentContext(tester));
    await tester.pump();
    await tester.pump(LaunchFade.hostAckTimeout);
    expect(calls.last.method, 'launchFadeFailed');
    expect(_opacity(tester), 0);
    acknowledgement.complete(true);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 125));
    expect(_opacity(tester), 0);
    await _unmount(tester);
  });

  testWidgets('content timeout fails without sending an early release', (tester) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      return true;
    });
    await _pumpCover(tester);
    await _finishDecode(tester);
    await tester.pump(LaunchFade.readyTimeout);
    expect(calls.map((call) => call.method), ['launchFadeFailed']);
    expect((calls.single.arguments as Map)['reason'], 'content-ready-timeout');
    expect(_opacity(tester), 0);
    expect(LaunchFade.markContentReady(_contentContext(tester)), isFalse);
    await _unmount(tester);
  });

  testWidgets('decode errors and explicit content errors use one failure route', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      return true;
    });
    final invalidPng = Uint8List.fromList([1, 2, 3]);
    await _pumpCover(tester, backdrop: invalidPng);
    await _finishDecode(tester, invalidPng);
    expect(calls.map((call) => call.method), ['launchFadeFailed']);
    expect((calls.single.arguments as Map)['reason'], 'backdrop-decode-failed');
    expect(_opacity(tester), 0);
    expect(tester.takeException(), isNull);
    await _unmount(tester);

    calls.clear();
    await _pumpCover(tester);
    await _finishDecode(tester);
    expect(LaunchFade.markContentFailed(_contentContext(tester), reason: 'session-error'), isTrue);
    expect(LaunchFade.markContentFailed(_contentContext(tester), reason: 'duplicate'), isFalse);
    await tester.pump();
    expect(calls.map((call) => call.method), ['launchFadeFailed']);
    expect((calls.single.arguments as Map)['reason'], 'session-error');
    await _unmount(tester);
  });

  testWidgets('reduced motion still waits for ready and host acknowledgement', (
    tester,
  ) async {
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call.method);
      return true;
    });
    await _pumpCover(tester, disableAnimations: true);
    await _finishDecode(tester);
    expect(_opacity(tester), 1);
    expect(calls, isEmpty);
    LaunchFade.markContentReady(_contentContext(tester));
    await tester.pump();
    expect(calls, ['releaseBackdrop']);
    await tester.pump();
    expect(_opacity(tester), 0);
    await _unmount(tester);
  });

  testWidgets('a stalled fade uses the bounded failure route', (tester) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      return true;
    });
    await _pumpCover(tester);
    await _finishDecode(tester);
    LaunchFade.markContentReady(_contentContext(tester));
    await tester.pump();
    await tester.pump();
    await tester.pump(LaunchFade.fadeTimeout);
    expect(calls.map((call) => call.method), [
      'releaseBackdrop',
      'launchFadeFailed',
    ]);
    expect((calls.last.arguments as Map)['reason'], 'fade-frame-timeout');
    expect(_opacity(tester), 0);
    await _unmount(tester);
  });

  testWidgets('disposing during host acknowledgement ignores the late reply', (
    tester,
  ) async {
    final acknowledgement = Completer<Object?>();
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) {
      calls.add(call.method);
      return acknowledgement.future;
    });
    await _pumpCover(tester);
    await _finishDecode(tester);
    LaunchFade.markContentReady(_contentContext(tester));
    await tester.pump();
    await _unmount(tester);
    acknowledgement.complete(true);
    await tester.pump();
    await tester.pump(LaunchFade.readyTimeout);
    expect(calls, ['releaseBackdrop']);
    expect(tester.takeException(), isNull);
  });

  testWidgets('no backdrop leaves the page and readiness signals alone', (
    tester,
  ) async {
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call.method);
      return true;
    });
    await _pumpCover(tester, withoutBackdrop: true);
    expect(find.byType(Opacity), findsNothing);
    expect(LaunchFade.markContentReady(_contentContext(tester)), isFalse);
    expect(LaunchFade.markContentFailed(_contentContext(tester)), isFalse);
    await tester.pump(LaunchFade.readyTimeout);
    expect(calls, isEmpty);
    await _unmount(tester);
  });
}

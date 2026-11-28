import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/bridge/host_bridge.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/runtime_panel.dart';

/// 打开真实的面板（不是复制一份 UI）：被测对象必须就是用户点到的那个。
Future<void> _openPanel(WidgetTester tester, HostInfo host) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: TextButton(
              key: const ValueKey('open-panel'),
              onPressed: () =>
                  showRuntimePanel(context, hostInfo: host, isFixture: false),
              child: const Text('打开'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.byKey(const ValueKey('open-panel')));
  await tester.pumpAndSettle();
}

const _host = HostInfo(
  webUrl: 'http://127.0.0.1:3080/?token=a',
  state: 'Healthy',
  available: true,
  runtimeMode: 'native',
  runtimeResolved: true,
  distro: '',
);

void main() {
  late List<MethodCall> calls;

  void mockChannel(Future<Object?> Function(MethodCall call) handler) {
    final channel = const MethodChannel('app.dsh.mobile/native_chat');
    calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) {
          calls.add(call);
          return handler(call);
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
  }

  testWidgets('tapping a mode switches inside the panel instead of opening '
      'the settings page', (tester) async {
    mockChannel((call) async {
      if (call.method == 'selectRuntimeMode') {
        return {
          'ok': true,
          'target': 'linux',
          'applied': 'linux',
          'previous': 'native',
          'restartRequired': true,
          'reason': '',
        };
      }
      return true;
    });
    await _openPanel(tester, _host);
    await tester.tap(find.byKey(const ValueKey('runtime-linux')));
    await tester.pumpAndSettle();

    expect(
      calls.map((c) => c.method),
      contains('selectRuntimeMode'),
      reason: '面板内切换必须走真实宿主调用',
    );
    expect(
      calls.where((c) => c.method == 'openRuntimeSettings'),
      isEmpty,
      reason: '不该再跳外部设置页',
    );
    expect(calls.firstWhere((c) => c.method == 'selectRuntimeMode').arguments, {
      'mode': 'linux',
    });
    // 回执 ok 且 restartRequired：必须如实说「重启后生效」，不能说成已生效。
    expect(find.text('已切换到 DSH Alpine，重启引擎后生效。'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('runtime-restart-pending')),
      findsOneWidget,
    );
    // 高亮跟着权威回读走。
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('runtime-linux')),
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsOneWidget,
    );
  });

  testWidgets('a pending switch shows progress and locks the other entry', (
    tester,
  ) async {
    final gate = Completer<Object?>();
    mockChannel((call) async {
      if (call.method == 'selectRuntimeMode') return gate.future;
      return true;
    });
    await _openPanel(tester, _host);
    await tester.tap(find.byKey(const ValueKey('runtime-linux')));
    await tester.pump();

    expect(
      find.byKey(const ValueKey('runtime-linux-pending')),
      findsOneWidget,
      reason: '在途时必须显示切换中，而不是静默等待',
    );
    expect(find.text('切换中…'), findsOneWidget);
    // 第二个入口在途期间不能再发起一次切换。
    await tester.tap(find.byKey(const ValueKey('runtime-native')));
    await tester.pump();
    expect(calls.where((c) => c.method == 'selectRuntimeMode').length, 1);

    gate.complete({
      'ok': true,
      'target': 'linux',
      'applied': 'linux',
      'previous': 'native',
      'restartRequired': true,
      'reason': '',
    });
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('runtime-linux-pending')), findsNothing);
  });

  testWidgets('a refusal keeps the original selection and shows why', (
    tester,
  ) async {
    mockChannel((call) async {
      if (call.method == 'selectRuntimeMode') {
        return {
          'ok': false,
          'target': 'linux',
          'applied': 'native',
          'previous': 'native',
          'restartRequired': false,
          'reason': 'needs-deploy',
        };
      }
      return true;
    });
    await _openPanel(tester, _host);
    await tester.tap(find.byKey(const ValueKey('runtime-linux')));
    await tester.pumpAndSettle();

    expect(find.text('容器环境尚未部署，请先在「DSH Alpine」管理页部署'), findsOneWidget);
    // 选择必须留在原处：原生仍是当前模式，Alpine 不能被点亮。
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('runtime-native')),
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('runtime-linux')),
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsNothing,
    );
    expect(find.byKey(const ValueKey('runtime-restart-pending')), findsNothing);
    // 失败提示用错误色，不是普通说明色 —— 否则和「已切换」长得一样。
    final feedback = tester.widget<Text>(
      find.byKey(const ValueKey('runtime-feedback')),
    );
    expect(feedback.style?.color, DshPalette.light.bad);
  });

  testWidgets('a readback that does not match the request is not success', (
    tester,
  ) async {
    mockChannel((call) async {
      if (call.method == 'selectRuntimeMode') {
        return {
          'ok': false,
          'target': 'linux',
          'applied': 'native_fallback',
          'previous': 'native',
          'restartRequired': false,
          'reason': 'linux rootfs not deployed',
        };
      }
      return true;
    });
    await _openPanel(tester, _host);
    await tester.tap(find.byKey(const ValueKey('runtime-linux')));
    await tester.pumpAndSettle();

    expect(find.textContaining('切换失败'), findsOneWidget);
    expect(find.textContaining('linux rootfs not deployed'), findsOneWidget);
    expect(find.textContaining('已切换'), findsNothing);
    // native_fallback 仍属于原生：高亮不能被挪到 Alpine 上。
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('runtime-native')),
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsOneWidget,
    );
  });

  testWidgets('no host channel falls back to the old settings entry', (
    tester,
  ) async {
    mockChannel((call) async {
      if (call.method == 'selectRuntimeMode') return true; // 老包：不是 Map
      return true;
    });
    await _openPanel(tester, _host);
    await tester.tap(find.byKey(const ValueKey('runtime-linux')));
    await tester.pumpAndSettle();

    expect(
      calls.map((c) => c.method),
      contains('openRuntimeSettings'),
      reason: '没有切换通道时退回原入口，不假装切换成功',
    );
    expect(find.textContaining('已切换到'), findsNothing);
  });

  testWidgets('a host rejection is a failure, not "open the settings page"', (
    tester,
  ) async {
    // 宿主收到了请求但拒绝（值域校验 / 通道内部错）。这和「没有这条通道」是两件事：
    // 后者退回外部页面，前者必须如实报失败，否则用户会以为跳个页面就切好了。
    mockChannel((call) async {
      if (call.method == 'selectRuntimeMode') {
        throw PlatformException(code: 'runtime/invalid');
      }
      return true;
    });
    await _openPanel(tester, _host);
    await tester.tap(find.byKey(const ValueKey('runtime-linux')));
    await tester.pumpAndSettle();

    expect(
      calls.where((c) => c.method == 'openRuntimeSettings'),
      isEmpty,
      reason: '宿主明确拒绝时不能再跳设置页',
    );
    expect(find.textContaining('切换失败'), findsOneWidget);
    expect(find.textContaining('runtime/invalid'), findsOneWidget);
    expect(find.textContaining('已切换到'), findsNothing);
  });

  testWidgets('an undecided host does not erase the current selection', (
    tester,
  ) async {
    // 宿主还没判定过（runtimeResolved=false）时 applied 会是 "unknown"。
    // 那不是「当前模式叫 unknown」——照抄进面板会把两个勾全抹掉。
    mockChannel((call) async {
      if (call.method == 'selectRuntimeMode') {
        return {
          'ok': false,
          'target': 'linux',
          'applied': 'unknown',
          'previous': 'native',
          'restartRequired': false,
          'reason': 'engine state unknown',
        };
      }
      return true;
    });
    await _openPanel(tester, _host);
    await tester.tap(find.byKey(const ValueKey('runtime-linux')));
    await tester.pumpAndSettle();

    expect(find.textContaining('engine state unknown'), findsOneWidget);
    expect(find.text('当前环境：Bionic Node'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('runtime-native')),
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsOneWidget,
      reason: '回读没给结论时，选择必须留在原处',
    );
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('runtime-linux')),
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsNothing,
    );
  });

  testWidgets('a successful readback lights the entry even on a stale snapshot', (
    tester,
  ) async {
    // 冷启早期打开的面板拿着 runtimeResolved=false 的快照，而弹层不会随父页刷新。
    // 一次成功的权威回读必须能点亮勾，否则面板会说「已切换到 DSH Alpine」
    // 却一条勾都不打。
    mockChannel((call) async {
      if (call.method == 'selectRuntimeMode') {
        return {
          'ok': true,
          'target': 'linux',
          'applied': 'linux',
          'previous': 'native',
          'restartRequired': true,
          'reason': '',
        };
      }
      return true;
    });
    await _openPanel(
      tester,
      const HostInfo(
        webUrl: '',
        state: 'unknown',
        available: true,
        runtimeMode: '',
        runtimeResolved: false,
      ),
    );
    await tester.tap(find.byKey(const ValueKey('runtime-linux')));
    await tester.pumpAndSettle();

    expect(find.text('已切换到 DSH Alpine，重启引擎后生效。'), findsOneWidget);
    expect(find.text('当前环境：DSH Alpine'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('runtime-linux')),
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsOneWidget,
    );
  });

  testWidgets('restart pending hint from the host survives the first frame', (
    tester,
  ) async {
    mockChannel((call) async => true);
    await _openPanel(
      tester,
      const HostInfo(
        webUrl: 'http://127.0.0.1:3080/?token=a',
        state: 'Healthy',
        available: true,
        runtimeMode: 'linux',
        runtimeResolved: true,
        runtimeRestartPending: true,
      ),
    );
    expect(
      find.byKey(const ValueKey('runtime-restart-pending')),
      findsOneWidget,
    );
  });
}

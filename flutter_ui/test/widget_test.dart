import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/bridge/engine_sessions_native.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/chat_strings.dart';
import 'package:dsh_native_chat/src/ui/launch_fade.dart';
import 'package:dsh_native_chat/src/ui/overflow_title.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_scope.dart';

// 1x1 透明 PNG：入场底图用例只需要一张能解码的图。
final Uint8List _onePixelPng = Uint8List.fromList(<int>[
  137,
  80,
  78,
  71,
  13,
  10,
  26,
  10,
  0,
  0,
  0,
  13,
  73,
  72,
  68,
  82,
  0,
  0,
  0,
  1,
  0,
  0,
  0,
  1,
  8,
  6,
  0,
  0,
  0,
  31,
  21,
  196,
  137,
  0,
  0,
  0,
  13,
  73,
  68,
  65,
  84,
  120,
  218,
  99,
  252,
  207,
  192,
  80,
  15,
  0,
  4,
  133,
  1,
  128,
  132,
  169,
  140,
  33,
  0,
  0,
  0,
  0,
  73,
  69,
  78,
  68,
  174,
  66,
  96,
  130,
]);

void main() {
  testWidgets('identical Host polls leave the chat tree untouched', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    var reads = 0;
    var hostState = 'Starting';
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method != 'engineInfo') return null;
      reads++;
      return {'state': hostState, 'available': false, 'webUrl': ''};
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    await tester.pumpWidget(const DshApp());
    await tester.pump();
    EngineHome home() => tester.widget<EngineHome>(find.byType(EngineHome));
    final first = home();
    expect(first.host.state, 'Starting');
    expect(reads, 1);

    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(reads, 2);
    expect(home(), same(first), reason: 'unchanged poll rebuilt the chat');

    hostState = 'Unavailable';
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    expect(reads, 3);
    expect(home().host.state, 'Unavailable');
    expect(home(), isNot(same(first)));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'host loss replaces the old account service and late results stay inert',
    (tester) async {
      const channel = MethodChannel('app.dsh.mobile/native_chat');
      var available = true;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            channel,
            (call) async => call.method == 'engineInfo'
                ? {
                    'state': 'Healthy',
                    'available': available,
                    'webUrl': 'http://127.0.0.1:1/?token=test',
                    'runtimeMode': 'native',
                    'runtimeResolved': true,
                  }
                : true,
          );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      await tester.pumpWidget(const DshApp());
      await tester.pump();
      final old = SettingsScope.of(tester.element(find.byType(EngineHome)));
      expect(old.isDemo, isFalse);
      available = false;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      final next = SettingsScope.of(tester.element(find.byType(EngineHome)));
      expect(identical(old, next), isFalse);
      expect(next.profile, isNull);
      expect(next.usage, isNull);
      expect(next.isDemo, isFalse);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
  testWidgets('host runtime refreshes after returning from native settings', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    var runtime = 'native';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (call) async => call.method == 'engineInfo'
              ? {
                  'runtimeMode': runtime,
                  'runtimeResolved': true,
                  'state': 'Healthy',
                  'available': true,
                  'distro': 'Alpine 3.22',
                }
              : true,
        );
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    await tester.pumpWidget(const DshApp());
    await tester.pump();
    expect(
      tester.widget<EngineHome>(find.byType(EngineHome)).host.runtimeLabel,
      'Bionic Node',
    );
    runtime = 'linux';
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(
      tester.widget<EngineHome>(find.byType(EngineHome)).host.runtimeLabel,
      'DSH Alpine',
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('returning from Android settings refreshes locale in place', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    String? locale = 'de';
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'readLocale') return locale;
      if (call.method == 'engineInfo') {
        return {'state': 'Starting', 'available': false, 'webUrl': ''};
      }
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    await tester.pumpWidget(const DshApp(initialLocale: 'zh'));
    await tester.pump();
    final home = tester.element(find.byType(EngineHome));
    String language() =>
        tester.widget<DshChatLocale>(find.byType(DshChatLocale)).languageCode;
    expect(language(), 'zh');

    Future<void> resume() async {
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
    }

    await resume();
    expect(language(), 'de');
    expect(tester.element(find.byType(EngineHome)), same(home));
    locale = null;
    await resume();
    expect(language(), 'de');
    locale = 'zh';
    await resume();
    expect(language(), 'zh');
    expect(tester.element(find.byType(EngineHome)), same(home));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('the host backdrop covers the first frame and then dissolves', (
    tester,
  ) async {
    // 新的入场契约（beta.71）：宿主拍下的那一屏由 Flutter 画在自己这一层的最上面，
    // 再由同一个合成器把它淡掉。这里守住三件事：
    //  ① 一上来底图是满不透明的（挡住冷启动那段黑）；
    //  ② 底图画上之后要立刻让宿主撤掉它的覆盖层（两层同一像素内容，撤掉看不出跳变）；
    //  ③ 淡完之后底图必须完全不透明为 0 —— 否则会永远盖住会话页。
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          return true;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    await tester.pumpWidget(
      MediaQuery(
        // 显式关掉「减少动效」：这个用例要验证的正是淡化本身。
        data: const MediaQueryData(disableAnimations: false),
        child: LaunchFade(
          backdrop: _onePixelPng,
          child: const SizedBox.expand(key: Key('content')),
        ),
      ),
    );
    final layer = find.descendant(
      of: find.byType(LaunchFade),
      matching: find.byType(Opacity),
    );
    expect(layer, findsOneWidget);
    double opacityOf() => tester.widget<Opacity>(layer).opacity;
    // 第一帧：底图满不透明，宿主覆盖层还没撤。
    expect(opacityOf(), 1.0, reason: '首帧必须先撑住底图，否则就是先黑一下');
    final content = tester.element(find.byKey(const Key('content')));
    await tester.runAsync(
      () => precacheImage(MemoryImage(_onePixelPng), content),
    );
    await tester.pump();
    await tester.pump();
    expect(calls, isEmpty);
    expect(LaunchFade.markContentReady(content), isTrue);
    await tester.pump();
    await tester.pump();
    expect(calls, contains('releaseBackdrop'), reason: '同一像素内容画上去之后要撤掉宿主覆盖层');
    // 淡化按**帧**推进，不按墙钟：冷启动只有 3～5 帧/秒，按时间折算的动画在第一个
    // tick 就到终点，屏幕上是一帧硬切（beta.73 k25.mp4 / beta.75 k27.mp4）。
    // 所以这里故意一次跳 5 秒 —— 真按时间走的话 opacity 会直接变 0。
    await tester.pump(const Duration(seconds: 5));
    final afterFirstStep = opacityOf();
    expect(afterFirstStep, greaterThan(0.0), reason: '一次长跳只该退一步，不能按墙钟一步到底');
    expect(afterFirstStep, lessThan(1.0), reason: '每帧都得真的退一步');
    // 继续逐帧推动：必须跨过好几帧才淡完，中途始终可见半透明。
    var framesToClear = 0;
    var sawPartial = false;
    while (opacityOf() > 0 && framesToClear < 100) {
      final value = opacityOf();
      if (value > 0 && value < 1) sawPartial = true;
      await tester.pump(const Duration(milliseconds: 16));
      framesToClear++;
    }
    expect(sawPartial, isTrue, reason: '中途必须真的出现过半透明');
    expect(
      framesToClear,
      greaterThanOrEqualTo(4),
      reason: '淡化要跨过好几帧才看得见，一帧到底就是硬切',
    );
    expect(opacityOf(), 0.0, reason: '淡完必须彻底让开，不能留一层盖住会话页');
  });

  testWidgets('a reduced-motion launch fade still hands the backdrop over', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          return true;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: LaunchFade(
          backdrop: _onePixelPng,
          child: const SizedBox.expand(key: Key('content')),
        ),
      ),
    );
    final content = tester.element(find.byKey(const Key('content')));
    await tester.runAsync(
      () => precacheImage(MemoryImage(_onePixelPng), content),
    );
    await tester.pump();
    await tester.pump();
    expect(calls, isEmpty);
    expect(LaunchFade.markContentReady(content), isTrue);
    await tester.pump();
    await tester.pump();
    final fade = find.descendant(
      of: find.byType(LaunchFade),
      matching: find.byType(FadeTransition),
    );
    // 淡出按帧推进时用 Opacity 而不是 FadeTransition，两种实现都只允许终态 0。
    final opacity = find.descendant(
      of: find.byType(LaunchFade),
      matching: find.byType(Opacity),
    );
    if (fade.evaluate().isNotEmpty) {
      expect(tester.widget<FadeTransition>(fade).opacity.value, 0.0);
    }
    if (opacity.evaluate().isNotEmpty) {
      expect(tester.widget<Opacity>(opacity).opacity, 0.0);
    }
    // 减少动效不等于跳过交接：宿主的覆盖层仍然要被撤，否则它会一直盖着会话页。
    expect(calls, contains('releaseBackdrop'));
    expect(find.byKey(const Key('content')), findsOneWidget);
  });

  testWidgets('without a host backdrop the launch fade stays out of the way', (
    tester,
  ) async {
    // Web 预览/拍图失败时没有底图：不许凭空插一层遮罩，也不许去调宿主的接口。
    await tester.pumpWidget(
      const MediaQuery(
        data: MediaQueryData(),
        child: LaunchFade(child: SizedBox.expand(key: Key('content'))),
      ),
    );
    expect(
      find.descendant(
        of: find.byType(LaunchFade),
        matching: find.byType(FadeTransition),
      ),
      findsNothing,
    );
    expect(find.byKey(const Key('content')), findsOneWidget);
  });

  testWidgets('a launch fade never fights the system reduced motion setting', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MediaQuery(
        data: MediaQueryData(disableAnimations: true),
        child: LaunchFade(child: SizedBox.expand(key: Key('content'))),
      ),
    );
    // 减少动效时不包 FadeTransition，直接给终态。
    expect(
      find.descendant(
        of: find.byType(LaunchFade),
        matching: find.byType(FadeTransition),
      ),
      findsNothing,
    );
    expect(find.byKey(const Key('content')), findsOneWidget);
  });

  testWidgets('explicit web preview shows the independent fixture shell', (
    tester,
  ) async {
    await tester.pumpWidget(const DshApp(preview: true));
    await tester.pump();
    // 标题显示的是这次对话，不是产品名：演示数据里第一条用户消息就是它。
    expect(
      tester
          .widget<OverflowTitle>(
            find.byKey(const ValueKey('conversation-title')),
          )
          .text,
      '帮我检查一下项目状态，并说明接下来该做什么。',
    );
    expect(find.text('DSH', findRichText: true), findsNothing);
    expect(find.byTooltip('fixture 模式'), findsOneWidget);
    // 真实 App 必须真的把内容包进入场淡化里：单元测试单测 LaunchFade 只能证明
    // 这个组件本身会淡，证明不了它被挂上去了。
    expect(find.byType(LaunchFade), findsOneWidget);
  });

  testWidgets(
    'a saved appearance is already in force on the first frame, not after a read',
    (tester) async {
      // 冷启动的第一帧必须是用户保存的外观。原先 main() 直接 runApp，_themeMode
      // 的初值是 ThemeMode.dark，保存的 light 要等一个 MethodChannel 往返回来才生效
      // —— 从 Web 切过来时这段深色正好落在引擎冷启的空窗里，看起来就是「先黑一下」。
      const channel = MethodChannel('app.dsh.mobile/native_chat');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            channel,
            (call) async => call.method == 'engineInfo'
                ? {'state': 'Healthy', 'available': false, 'webUrl': ''}
                : true,
          );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );

      // 只 pump 一帧，不给 _readAppearance 的异步结果任何机会。
      await tester.pumpWidget(
        const DshApp(initialAppearance: {'mode': 'light', 'mood': 'sand'}),
      );
      // 首帧还没跑完引擎信息（_hostResolved 仍是 false），所以这里断言外观本身，
      // 而不是断言某一个页面：它必须已经在树上了。
      final first = tester.widget<DshAppearance>(find.byType(DshAppearance));
      expect(first.mode, ThemeMode.light, reason: '第一帧就该是用户保存的浅色');
      expect(first.mood, DshMood.sand);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}

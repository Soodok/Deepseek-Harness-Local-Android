import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:material_ui/material_ui.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/glass_panel.dart';
import 'package:dsh_native_chat/src/ui/composer_surface.dart';

double contrast(Color a, Color b) {
  final x = a.computeLuminance();
  final y = b.computeLuminance();
  return (x > y ? (x + .05) / (y + .05) : (y + .05) / (x + .05));
}

class _GlassProbe extends StatefulWidget {
  const _GlassProbe();
  @override
  State<_GlassProbe> createState() => _GlassProbeState();
}

class _GlassProbeState extends State<_GlassProbe> {
  final text = TextEditingController();
  final scroll = ScrollController();
  @override
  void dispose() {
    text.dispose();
    scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 160,
    child: ListView(
      controller: scroll,
      children: [
        TextField(controller: text),
        const SizedBox(height: 800),
      ],
    ),
  );
}

void main() {
  testWidgets('shared disabled send icon stays visible in every palette', (
    tester,
  ) async {
    for (final mood in DshMood.values) {
      for (final brightness in Brightness.values) {
        await tester.pumpWidget(
          MaterialApp(
            themeAnimationDuration: Duration.zero,
            theme: brightness == Brightness.light
                ? DshTheme.light(mood: mood)
                : DshTheme.dark(mood: mood),
            home: const Scaffold(
              body: ComposerSendButton(
                buttonKey: ValueKey('contrast-send'),
                tooltip: '发送',
                onPressed: null,
              ),
            ),
          ),
        );
        await tester.pump();
        final button = tester.widget<IconButton>(
          find.byKey(const ValueKey('contrast-send')),
        );
        final fg = button.style!.foregroundColor!.resolve({
          WidgetState.disabled,
        })!;
        final bg = button.style!.backgroundColor!.resolve({
          WidgetState.disabled,
        })!;
        expect(
          contrast(fg, bg),
          greaterThanOrEqualTo(3),
          reason: '$mood $brightness disabled send',
        );
      }
    }
  });

  testWidgets(
    'accessibility changes preserve editable and scrollable glass children',
    (tester) async {
      Widget app(bool calm) => MaterialApp(
        theme: DshTheme.light(),
        home: MediaQuery(
          data: MediaQueryData(disableAnimations: calm, highContrast: calm),
          child: const Scaffold(body: GlassPanel(child: _GlassProbe())),
        ),
      );
      await tester.pumpWidget(app(false));
      await tester.pump();
      final state = tester.state<_GlassProbeState>(find.byType(_GlassProbe));
      state.text.text = 'KEEP DRAFT';
      state.scroll.jumpTo(120);
      await tester.pump();
      await tester.pumpWidget(app(true));
      await tester.pump();
      expect(tester.state(find.byType(_GlassProbe)), same(state));
      expect(state.text.text, 'KEEP DRAFT');
      expect(state.scroll.offset, 120);
      await tester.pumpWidget(app(false));
      await tester.pump();
      expect(tester.state(find.byType(_GlassProbe)), same(state));
    },
  );

  testWidgets(
    'palette menu repaints all moods while preserving the shell and draft',
    (tester) async {
      Future<void> frame() async {
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 450));
      }

      await tester.pumpWidget(const DshApp());
      await frame();
      final shell = tester.state(find.byType(ChatShell));
      await tester.enterText(
        find.byKey(const ValueKey('composer-input')),
        '保留我的草稿',
      );
      await tester.tap(find.byTooltip('打开会话抽屉'));
      await frame();
      for (final mood in DshMood.values) {
        await tester.tap(find.byKey(const ValueKey('mood-selector')));
        await frame();
        await tester.tap(find.byKey(ValueKey('mood-' + mood.name)));
        await frame();
        expect(
          DshPalette.of(
            tester.element(find.byKey(const ValueKey('floating-header'))),
          ).paper,
          DshPalette.colorsFor(mood, Brightness.dark).paper,
        );
        expect(tester.state(find.byType(ChatShell)), same(shell));
        expect(
          tester
              .widget<TextField>(find.byKey(const ValueKey('composer-input')))
              .controller!
              .text,
          '保留我的草稿',
        );
      }
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  test(
    'four moods have distinct light and dark surfaces with readable text',
    () {
      for (final brightness in Brightness.values) {
        final papers = <Color>{};
        for (final mood in DshMood.values) {
          final theme = brightness == Brightness.light
              ? DshTheme.light(mood: mood)
              : DshTheme.dark(mood: mood);
          final c = theme.extension<DshColorTheme>()!.colors;
          papers.add(c.paper);
          expect(theme.scaffoldBackgroundColor, c.paper);
          for (final surface in [
            c.paper,
            c.surface,
            c.surfaceRaised,
            c.userWash,
          ]) {
            expect(
              contrast(c.ink, surface),
              greaterThanOrEqualTo(7),
              reason: '$mood $brightness ink',
            );
            expect(
              contrast(c.inkFaint, surface),
              greaterThanOrEqualTo(4.5),
              reason: '$mood $brightness secondary',
            );
          }
        }
        expect(papers.length, 4);
      }
    },
  );
  testWidgets('glass uses package material and solid accessiblity fallback', (
    tester,
  ) async {
    Widget app(bool calm) => MaterialApp(
      theme: DshTheme.light(mood: DshMood.sand),
      home: MediaQuery(
        data: MediaQueryData(disableAnimations: calm, highContrast: calm),
        child: const Scaffold(body: GlassPanel(child: Text('readable glass'))),
      ),
    );
    await tester.pumpWidget(app(false));
    await tester.pump();
    final glass = tester.widget<GlassContainer>(find.byType(GlassContainer));
    expect(glass.quality, GlassQuality.standard);
    expect(glass.settings!.chromaticAberration, 0);
    expect(glass.settings!.blur, lessThanOrEqualTo(10));
    await tester.pumpWidget(app(true));
    await tester.pump();
    expect(find.text('readable glass'), findsOneWidget);
    expect(find.byType(GlassContainer), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

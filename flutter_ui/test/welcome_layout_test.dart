import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/engine_agent_preset.dart';
import 'package:dsh_native_chat/src/model/engine_workspace.dart';
import 'package:dsh_native_chat/src/state/chat_controller.dart';
import 'package:dsh_native_chat/src/transport/fixture_transport.dart';
import 'package:dsh_native_chat/src/ui/chat_shell.dart';
import 'package:dsh_native_chat/src/ui/message_list.dart';
import 'package:dsh_native_chat/src/ui/chat_strings.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';

class _EmptyWelcomeFixture extends FixtureTransport {
  int sent = 0;

  @override
  Stream<ChatEvent> get events => Stream.value(
    const ChatEvent.snapshot(generation: 1, sequence: 0, messages: []),
  );

  @override
  Future<void> sendPrompt(String text) async => sent++;
}

const _presets = [
  EngineAgentPreset(id: 'standard', isDefault: true, name: 'standard'),
  EngineAgentPreset(id: 'ptc', isDefault: false, name: 'ptc'),
  EngineAgentPreset(id: 'minimal', isDefault: false, name: 'minimal'),
  EngineAgentPreset(id: 'cordis', isDefault: false, name: 'cordis'),
];

Future<void> _pumpWelcome(
  WidgetTester tester, {
  required Size logicalSize,
  required ChatController controller,
  double textScale = 1,
  String languageCode = 'zh',
}) async {
  tester.view.physicalSize = logicalSize;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      builder: (context, child) => DshChatLocale(
        languageCode: languageCode,
        child: MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
      ),
      home: ChatShell(
        controller: controller,
        agentPresets: _presets,
        selectedAgentPresetId: 'standard',
        agentPresetLocked: false,
        onSelectAgentPreset: (id) async => true,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'a two-line greeting keeps the welcome mode on the first screen',
    (tester) async {
      tester.view.physicalSize = const Size(360, 396);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final controller = ChatController(_EmptyWelcomeFixture());
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: Scaffold(
            body: MessageList(
              controller: controller,
              onStarter: (_) {},
              agentPresets: _presets,
              selectedAgentPresetId: 'standard',
              now: () => DateTime(2026, 11, 25, 19),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final greeting = find.byKey(const ValueKey('welcome-greeting'));
      expect(find.text('想把今天的点子整理一下吗？'), findsOneWidget);
      expect(tester.getSize(greeting).height, greaterThan(30));
      final viewport = find.byKey(const ValueKey('welcome-scroll-viewport'));
      final mode = find.byKey(const ValueKey('welcome-agent-capsule'));
      expect(
        tester.getRect(mode).bottom,
        lessThanOrEqualTo(tester.getRect(viewport).bottom),
      );
      final scroll = find.descendant(
        of: viewport,
        matching: find.byType(Scrollable),
      );
      expect(tester.state<ScrollableState>(scroll).position.maxScrollExtent, 0);
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets('welcome content fits 393x851 above the real composer chrome', (
    tester,
  ) async {
    final fixture = _EmptyWelcomeFixture();
    final controller = ChatController(fixture);
    await _pumpWelcome(
      tester,
      logicalSize: const Size(393, 851),
      controller: controller,
    );

    final brand = find.byKey(const ValueKey('welcome-brand'));
    final greeting = find.byKey(const ValueKey('welcome-greeting'));
    final cards = find.byWidgetPredicate(
      (widget) =>
          widget.key is ValueKey<String> &&
          (widget.key! as ValueKey<String>).value.startsWith(
            'welcome-suggestion-',
          ),
    );
    final mode = find.byKey(const ValueKey('welcome-agent-capsule'));
    final composer = find.byKey(const ValueKey('composer-input'));
    final composerExtent = find.byKey(
      const ValueKey('floating-composer-extent'),
    );

    expect(brand, findsOneWidget);
    expect(greeting, findsOneWidget);
    expect(cards, findsNWidgets(4));
    expect(mode, findsOneWidget);
    expect(composer, findsOneWidget);
    expect(composerExtent, findsOneWidget);
    expect(
      tester
          .getRect(find.byKey(const ValueKey('welcome-scroll-viewport')))
          .bottom,
      lessThanOrEqualTo(tester.getRect(composerExtent).top + .01),
    );
    expect(
      tester.getRect(mode).bottom,
      lessThanOrEqualTo(tester.getRect(composerExtent).top + .01),
    );
    final scroll = find
        .descendant(
          of: find.byKey(const ValueKey('welcome-scroll-viewport')),
          matching: find.byType(Scrollable),
        )
        .first;
    expect(
      tester.state<ScrollableState>(scroll).position.maxScrollExtent,
      closeTo(0, .01),
    );
    final viewport = tester.getRect(
      find.byKey(const ValueKey('welcome-scroll-viewport')),
    );
    final header = tester.getRect(
      find.byKey(const ValueKey('floating-header-extent')),
    );
    expect(viewport.top, greaterThanOrEqualTo(header.bottom - .01));
    expect(tester.getRect(brand).top, greaterThanOrEqualTo(viewport.top - .01));
    expect(
      tester.getRect(brand).bottom,
      lessThan(tester.getRect(greeting).top),
    );
    expect(
      find.descendant(of: brand, matching: find.byType(CustomPaint)),
      findsOneWidget,
    );
    expect(tester.getSize(brand), const Size(60, 45));
    expect(
      find.descendant(of: brand, matching: find.text('deepseek')),
      findsNothing,
    );

    for (var i = 0; i < 4; i++) {
      final card = cards.at(i);
      final frame = tester.getRect(card);
      expect(frame.width, closeTo(frame.height, .05), reason: 'card $i');
      expect(frame.width, closeTo(132, .01), reason: 'card $i');
      final button = tester.widget<OutlinedButton>(card);
      final shape = button.style!.shape!.resolve({})! as RoundedRectangleBorder;
      expect(shape.borderRadius, BorderRadius.circular(18), reason: 'card $i');
      expect(frame.top, greaterThanOrEqualTo(viewport.top - .01));
      expect(frame.bottom, lessThanOrEqualTo(viewport.bottom + .01));
      final icon = find.descendant(of: card, matching: find.byType(Icon));
      final label = find.descendant(of: card, matching: find.byType(Text));
      expect(tester.getRect(icon).center.dx, closeTo(frame.center.dx, .5));
      expect(tester.getRect(label).center.dx, closeTo(frame.center.dx, .5));
      expect(
        tester.widget<Text>(label).style!.fontSize,
        greaterThanOrEqualTo(16),
      );
      expect(tester.getRect(icon).bottom, lessThan(tester.getRect(label).top));
    }

    await tester.tap(mode);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('welcome-agent-glass')), findsOneWidget);
    // The pill holds the short value; the menu speaks the full name so a
    // first-time reader learns what the entry actually is.
    expect(find.text('标准模式'), findsOneWidget);
    await tester.tap(find.text('PTC 模式'));
    await tester.pumpAndSettle();

    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets(
    'built-in modes follow app language while custom names stay intact',
    (tester) async {
      final fixture = _EmptyWelcomeFixture();
      final controller = ChatController(fixture);
      final selected = <String>[];
      final presets = [
        ..._presets,
        const EngineAgentPreset(
          id: 'team-review',
          isDefault: false,
          name: 'My Review Mode',
        ),
      ];
      tester.view.physicalSize = const Size(393, 851);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: Builder(
            builder: (context) => DshChatLocale(
              languageCode: 'en',
              child: Scaffold(
                body: MessageList(
                  controller: controller,
                  onStarter: (_) {},
                  agentPresets: presets,
                  selectedAgentPresetId: 'standard',
                  onSelectAgentPreset: (id) async {
                    selected.add(id);
                    return true;
                  },
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Mode: Standard'), findsOneWidget);
      expect(find.byTooltip('Select chat mode'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('welcome-agent-capsule')));
      await tester.pumpAndSettle();
      expect(find.text('PTC mode'), findsOneWidget);
      expect(find.text('Minimal mode'), findsOneWidget);
      expect(find.text('Creator mode'), findsOneWidget);
      expect(find.text('My Review Mode'), findsOneWidget);
      await tester.tap(find.text('Creator mode'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Select chat mode'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('My Review Mode'));
      await tester.pumpAndSettle();
      expect(selected, ['cordis', 'team-review']);
      expect(tester.takeException(), isNull);

      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets('unselected mode and selection failure use the app language', (
    tester,
  ) async {
    final controller = ChatController(_EmptyWelcomeFixture());
    await tester.pumpWidget(
      MaterialApp(
        home: DshChatLocale(
          languageCode: 'en',
          child: Scaffold(
            body: MessageList(
              controller: controller,
              agentPresets: _presets,
              onSelectAgentPreset: (_) async => false,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Mode: Not selected'), findsOneWidget);
    await tester.tap(find.byTooltip('Select chat mode'));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.check_rounded), findsNothing);
    await tester.tap(find.text('Minimal mode'));
    await tester.pumpAndSettle();
    expect(find.text('Could not switch chat mode'), findsOneWidget);
    expect(find.text('Mode: Not selected'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });

  testWidgets(
    'keyboard inset preserves complete 16sp starter labels in square cards',
    (tester) async {
      final fixture = _EmptyWelcomeFixture();
      final controller = ChatController(fixture);
      await _pumpWelcome(
        tester,
        logicalSize: const Size(360, 640),
        controller: controller,
      );
      tester.view.viewInsets = const FakeViewPadding(bottom: 280);
      await tester.pumpAndSettle();

      final viewport = find.byKey(const ValueKey('welcome-scroll-viewport'));
      final composer = find.byKey(const ValueKey('floating-composer-extent'));
      expect(tester.getRect(composer).bottom, closeTo(360, .1));
      expect(
        tester.getRect(viewport).bottom,
        lessThanOrEqualTo(tester.getRect(composer).top + .01),
      );
      for (final id in ['code', 'chat', 'plan', 'access']) {
        final card = find.byKey(ValueKey('welcome-suggestion-$id'));
        final frame = tester.getRect(card);
        final label = find.descendant(of: card, matching: find.byType(Text));
        final paragraph = tester.renderObject<RenderParagraph>(label);
        final text = tester.widget<Text>(label).data!;
        final boxes = paragraph.getBoxesForSelection(
          TextSelection(baseOffset: 0, extentOffset: text.length),
        );
        final intrinsicHeight = paragraph.getMaxIntrinsicHeight(
          paragraph.size.width,
        );
        expect(frame.width, closeTo(frame.height, .05), reason: id);
        expect(paragraph.text.style!.fontSize, 16, reason: id);
        expect(
          paragraph.size.height,
          greaterThanOrEqualTo(intrinsicHeight - .01),
          reason: '$id must fit its rendered text height',
        );
        expect(paragraph.didExceedMaxLines, isFalse, reason: id);
        expect(boxes, isNotEmpty, reason: id);
        for (final box in boxes) {
          expect(box.top, greaterThanOrEqualTo(-.01), reason: id);
          expect(
            box.bottom,
            lessThanOrEqualTo(paragraph.size.height + .01),
            reason: '$id text must not be vertically clipped',
          );
        }
      }
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets(
    'keyboard inset keeps four starter labels complete by yielding the icon',
    (tester) async {
      final fixture = _EmptyWelcomeFixture();
      final controller = ChatController(fixture);
      await _pumpWelcome(
        tester,
        logicalSize: const Size(360, 640),
        controller: controller,
      );
      tester.view.viewInsets = const FakeViewPadding(bottom: 280);
      await tester.pumpAndSettle();

      final labels = <String>{'代码', '聊天', '计划', '操作'};
      var found = 0;
      var yielded = 0;
      for (final id in ['code', 'chat', 'plan', 'access']) {
        final card = find.byKey(ValueKey('welcome-suggestion-$id'));
        final label = find.descendant(of: card, matching: find.byType(Text));
        final paragraph = tester.renderObject<RenderParagraph>(label);
        final text = tester.widget<Text>(label).data!;
        expect(paragraph.didExceedMaxLines, isFalse, reason: id);
        final height = paragraph.getMaxIntrinsicHeight(paragraph.size.width);
        expect(
          paragraph.size.height,
          greaterThanOrEqualTo(height - .01),
          reason: '$id label must fit inside its compressed card',
        );
        if (labels.any((word) => text.contains(word))) found++;
        // No icon may be squeezed against the label: either both fit, or the
        // decorative icon is dropped entirely.
        final icon = find.descendant(of: card, matching: find.byType(Icon));
        if (icon.evaluate().isNotEmpty) {
          final iconRect = tester.getRect(icon.first);
          final labelRect = tester.getRect(label);
          expect(
            iconRect.bottom,
            lessThanOrEqualTo(labelRect.top + .01),
            reason: '$id icon must not overlap its label',
          );
        } else {
          yielded++;
        }
      }
      expect(found, 4);
      expect(
        yielded,
        greaterThan(0),
        reason:
            'a compressed card must drop the decorative icon, not clip text',
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets(
    'welcome content remains reachable at 360x640 and starter only fills draft',
    (tester) async {
      final fixture = _EmptyWelcomeFixture();
      final controller = ChatController(fixture);
      await _pumpWelcome(
        tester,
        logicalSize: const Size(360, 640),
        controller: controller,
      );

      final list = find.byKey(const ValueKey('welcome-scroll-viewport'));
      final scrollable = find
          .descendant(of: list, matching: find.byType(Scrollable))
          .first;
      final viewport = tester.getRect(list);
      final composerExtent = tester.getRect(
        find.byKey(const ValueKey('floating-composer-extent')),
      );
      expect(viewport.bottom, lessThanOrEqualTo(composerExtent.top + .01));
      for (final key in [
        'welcome-brand',
        'welcome-greeting',
        'welcome-agent-capsule',
      ]) {
        final rect = tester.getRect(find.byKey(ValueKey(key)));
        expect(rect.top, greaterThanOrEqualTo(viewport.top - .01));
        expect(rect.bottom, lessThanOrEqualTo(viewport.bottom + .01));
      }
      expect(
        tester.state<ScrollableState>(scrollable).position.maxScrollExtent,
        closeTo(0, .01),
      );
      for (final id in ['code', 'chat', 'plan', 'access']) {
        final card = find.byKey(ValueKey('welcome-suggestion-$id'));
        final frame = tester.getRect(card);
        expect(frame.width, closeTo(frame.height, .05), reason: id);
        expect(frame.top, greaterThanOrEqualTo(viewport.top - .01));
        expect(frame.bottom, lessThanOrEqualTo(viewport.bottom + .01));
        final icon = find.descendant(of: card, matching: find.byType(Icon));
        final label = find.descendant(of: card, matching: find.byType(Text));
        expect(tester.getRect(icon).center.dx, closeTo(frame.center.dx, .5));
        expect(tester.getRect(label).center.dx, closeTo(frame.center.dx, .5));
        expect(
          tester.widget<Text>(label).style!.fontSize,
          greaterThanOrEqualTo(16),
        );
      }

      final input = find.byKey(const ValueKey('composer-input'));
      await tester.enterText(input, '已有草稿');
      await tester.ensureVisible(
        find.byKey(const ValueKey('welcome-suggestion-code')),
      );
      await tester.tap(find.byKey(const ValueKey('welcome-suggestion-code')));
      await tester.pump();
      final editable = find.descendant(
        of: input,
        matching: find.byType(EditableText),
      );
      expect(
        tester.widget<EditableText>(editable).controller.text,
        startsWith('已有草稿\n\n'),
      );
      expect(
        tester.widget<EditableText>(editable).controller.text,
        contains('代码'),
      );
      expect(fixture.sent, 0);
      expect(fixture.submissions, isEmpty);

      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets('large text keeps all welcome actions reachable by scrolling', (
    tester,
  ) async {
    final fixture = _EmptyWelcomeFixture();
    final controller = ChatController(fixture);
    await _pumpWelcome(
      tester,
      logicalSize: const Size(360, 640),
      controller: controller,
      textScale: 2,
    );
    final list = find.byKey(const ValueKey('welcome-scroll-viewport'));
    final scrollable = find
        .descendant(of: list, matching: find.byType(Scrollable))
        .first;
    expect(
      tester.state<ScrollableState>(scrollable).position.maxScrollExtent,
      greaterThan(0),
    );
    for (final id in ['code', 'chat', 'plan', 'access']) {
      final card = find.byKey(ValueKey('welcome-suggestion-$id'));
      await tester.ensureVisible(card);
      await tester.pump();
      expect(card, findsOneWidget);
      final viewport = tester.getRect(list);
      final frame = tester.getRect(card);
      expect(frame.top, greaterThanOrEqualTo(viewport.top - .01));
      expect(frame.bottom, lessThanOrEqualTo(viewport.bottom + .01));
    }
    await tester.ensureVisible(
      find.byKey(const ValueKey('welcome-agent-capsule')),
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
  testWidgets(
    'welcome exposes Home and Host workspaces beside the mode picker',
    (tester) async {
      final controller = ChatController(_EmptyWelcomeFixture());
      String? selected;
      final workspaces = [
        const EngineWorkspace(
          id: 'alpha',
          title: 'Alpha',
          path: '/alpha',
          sessionIds: [],
        ),
      ];
      await tester.pumpWidget(
        MaterialApp(
          theme: DshTheme.light(),
          home: Scaffold(
            body: MessageList(
              controller: controller,
              onStarter: (_) {},
              agentPresets: _presets,
              selectedAgentPresetId: 'standard',
              onSelectAgentPreset: (_) async => true,
              workspaceOptions: workspaces,
              selectedWorkspaceId: null,
              onSelectWorkspace: (id) async {
                selected = id;
                return true;
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('welcome-workspace-capsule')),
        findsOneWidget,
      );
      expect(find.text('工作区：Home'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('welcome-workspace-capsule')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('welcome-workspace-glass')),
        findsOneWidget,
      );
      expect(find.text('Home'), findsOneWidget);
      expect(find.text('Alpha'), findsOneWidget);
      await tester.tap(find.text('Alpha'));
      await tester.pumpAndSettle();
      expect(selected, 'alpha');
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
    },
  );

  testWidgets('long workspace name keeps both selectors in the first screen', (
    tester,
  ) async {
    final controller = ChatController(_EmptyWelcomeFixture());
    final selections = <String?>[];
    const longTitle = 'dsh-qa-beta98-alpha-with-a-long-workspace-name';
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 393,
              height: 570,
              child: MessageList(
                controller: controller,
                onStarter: (_) {},
                agentPresets: _presets,
                selectedAgentPresetId: 'standard',
                onSelectAgentPreset: (_) async => true,
                workspaceOptions: const [
                  EngineWorkspace(
                    id: 'alpha',
                    title: longTitle,
                    path: '/alpha',
                    sessionIds: [],
                  ),
                ],
                selectedWorkspaceId: 'alpha',
                onSelectWorkspace: (id) async {
                  selections.add(id);
                  return true;
                },
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final mode = find.byKey(const ValueKey('welcome-agent-capsule'));
    final workspace = find.byKey(const ValueKey('welcome-workspace-capsule'));
    final viewport = find.byKey(const ValueKey('welcome-scroll-viewport'));
    expect(
      tester.getCenter(workspace).dy,
      closeTo(tester.getCenter(mode).dy, .01),
    );
    expect(
      tester.getRect(workspace).bottom,
      lessThanOrEqualTo(tester.getRect(viewport).bottom),
    );
    expect(workspace.hitTestable(), findsOneWidget);
    await tester.tap(workspace);
    await tester.pumpAndSettle();
    expect(find.text(longTitle), findsOneWidget);
    await tester.tap(find.text('Home'));
    await tester.pumpAndSettle();
    expect(selections, [null]);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
  });
}

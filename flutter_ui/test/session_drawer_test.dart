import 'package:dsh_native_chat/src/model/engine_session.dart';
import 'package:dsh_native_chat/src/model/engine_workspace.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/overflow_title.dart';
import 'package:dsh_native_chat/src/ui/session_drawer.dart';
import 'package:dsh_native_chat/src/ui/settings/account_footer.dart';
import 'package:dsh_native_chat/src/ui/settings/settings_scope.dart';
import 'package:dsh_native_chat/src/state/settings_controller.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';


void main() {
  testWidgets('workspace titles and members keep host order and create by id', (
    tester,
  ) async {
    const sessions = [
      EngineSession(id: 'recent', updatedAt: 44, title: 'Recent session'),
      EngineSession(id: 'archive', updatedAt: 43, title: 'Archived session'),
      EngineSession(id: 'earlier', updatedAt: 12, title: 'Earlier session'),
    ];
    final catalog = EngineWorkspaceCatalog()
      ..accept({
        'type': 'baseline',
        'value': {
          'items': [
            {
              'workspaceId': 'z',
              'title': 'Zulu',
              'path': '/z',
              'sessionIds': ['earlier', 'recent', 'archive'],
            },
            {
              'workspaceId': 'a',
              'title': 'Alpha',
              'path': '/a',
              'sessionIds': [],
            },
          ],
          'archivedSessionIds': ['archive'],
          'pinnedSessionIds': [],
        },
      });
    final created = <String?>[];
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: SessionWorkspaceScope(
          catalog: catalog,
          online: true,
          error: null,
          onCreate: (workspace) => created.add(workspace?.id),
          child: Scaffold(
            drawer: const SessionDrawer(sessions: sessions),
            body: Builder(
              builder: (context) => IconButton(
                icon: const Icon(Icons.menu),
                onPressed: () => Scaffold.of(context).openDrawer(),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.byIcon(Icons.menu));
    await tester.pumpAndSettle();
    expect(
      tester.getTopLeft(find.text('Zulu')).dy,
      lessThan(tester.getTopLeft(find.text('Alpha')).dy),
    );
    final earlier = find.byKey(const ValueKey('session-row-earlier'));
    final recent = find.byKey(const ValueKey('session-row-recent'));
    expect(
      tester.getTopLeft(earlier).dy,
      lessThan(tester.getTopLeft(recent).dy),
    );
    expect(find.byKey(const ValueKey('session-row-archive')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('workspace-collapse-z')));
    await tester.pump();
    expect(find.byKey(const ValueKey('session-row-earlier')), findsNothing);
    expect(find.byKey(const ValueKey('session-row-recent')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('workspace-collapse-z')));
    await tester.pump();
    expect(find.byKey(const ValueKey('session-row-earlier')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('new-session-workspace-a')));
    await tester.pumpAndSettle();
    expect(created, ['a']);
  });

  testWidgets('drawer fills the account space and keeps the current row name', (
    tester,
  ) async {
    final sessions = List.generate(
      30,
      (index) => EngineSession(
        id: '$index',
        updatedAt: index,
        title: index == 0 ? null : 'Session $index',
      ),
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(
          body: SessionDrawer(
            currentSessionId: '0',
            currentTitle: 'Current conversation',
            sessions: sessions,
            onOpenSettings: () {},
          ),
        ),
      ),
    );
    await tester.pump();

    final title = tester.widget<OverflowTitle>(
      find.byKey(const ValueKey('drawer-title')),
    );
    expect(title.text, 'DSH Mobile');
    expect(title.style!.fontSize, lessThanOrEqualTo(14));
    final row = find.byKey(const ValueKey('session-row-0'));
    final rowTitle = tester.widget<OverflowTitle>(
      find.descendant(of: row, matching: find.byType(OverflowTitle)),
    );
    expect(rowTitle.text, 'Current conversation');
    final list = tester.getRect(find.byKey(const ValueKey('session-rows')));
    final footer = tester.getRect(find.byType(AccountFooter));
    expect(list.bottom, closeTo(footer.top, 0.1));
    expect(list.height, greaterThan(300));
    expect(find.text('会话来自本机引擎'), findsNothing);
    final drawer = tester.getRect(find.byType(Drawer));
    expect(drawer.bottom - footer.bottom, lessThanOrEqualTo(4));
    final newButton = tester.getRect(find.widgetWithText(TextButton, '新建会话'));
    final heading = tester.getRect(find.text('会话'));
    expect(heading.top - newButton.bottom, lessThanOrEqualTo(12));
    expect(tester.takeException(), isNull);
  });

  testWidgets('drawer footer stays above the system navigation inset', (
    tester,
  ) async {
    // L03/L05: the account footer must not sit under the gesture bar or the
    // three-button bar. Android reports that area as MediaQuery padding.bottom
    // (viewInsets is the keyboard), and the drawer's SafeArea must consume it.
    for (final navHeight in <double>[66, 132]) {
      tester.view.physicalSize = const Size(1080, 2340);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final settings = SettingsController(EngineUnavailableSettingsService());
      await tester.pumpWidget(
        SettingsScope(
          controller: settings,
          child: MaterialApp(
            theme: DshTheme.dark(),
            home: MediaQuery(
              data: MediaQueryData(
                size: const Size(1080, 2340),
                padding: EdgeInsets.only(bottom: navHeight),
                viewPadding: EdgeInsets.only(bottom: navHeight),
              ),
              child: Scaffold(
                drawer: SessionDrawer(
                  sessions: const [
                    EngineSession(id: 'one', updatedAt: 5, title: 'Session one'),
                  ],
                  onOpenSettings: () {},
                ),
                body: Builder(
                  builder: (context) => IconButton(
                    icon: const Icon(Icons.menu),
                    onPressed: () => Scaffold.of(context).openDrawer(),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pumpAndSettle();
      final footer = tester.getRect(find.byType(AccountFooter));
      expect(
        footer.bottom,
        lessThanOrEqualTo(2340 - navHeight + .01),
        reason: 'footer must clear a ' + navHeight.toString() + 'px nav inset',
      );
      await tester.pumpWidget(const SizedBox.shrink());
      settings.dispose();
    }
  });
}

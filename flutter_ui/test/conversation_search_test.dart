import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/conversation_flow.dart';
import 'package:dsh_native_chat/src/ui/conversation_search.dart';
import 'package:dsh_native_chat/src/ui/tool_run_card.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Widget _app(
  List<ChatMessage> messages, {
  Object identity = 'search',
  List<ToolRun> tools = const [],
  int? pageSize,
  (int, String)? approval,
  EdgeInsets? padding,
}) => MaterialApp(
  theme: DshTheme.light(),
  home: Scaffold(
    body: ConversationFlow(
      conversationIdentity: identity,
      messages: messages,
      tools: tools,
      historyPageSize: pageSize,
      pendingApproval: approval,
      padding: padding,
    ),
  ),
);

Future<void> _search(WidgetTester tester, String query) async {
  await tester.tap(find.byKey(const ValueKey('conversation-search-open')));
  await tester.pump();
  await tester.enterText(
    find.byKey(const ValueKey('conversation-search-input')),
    query,
  );
  await tester.pumpAndSettle();
}

List<ChatMessage> _history(int count) => [
  for (var i = 0; i < count; i++)
    ChatMessage(
      id: 'm$i',
      role: ChatRole.assistant,
      text: 'Entry $i ' + ('variable height line\n' * (i % 4 + 1)),
    ),
];

// Query the production lazy delegate's row index without building offscreen
// messages. Mounted widgets alone cannot prove the full loaded slice.
void _expectSlice(WidgetTester tester, int start, int end, int total) {
  final list = tester.widget<ListView>(
    find.byKey(const ValueKey('conversation-list')),
  );
  final delegate = list.childrenDelegate as SliverChildBuilderDelegate;
  final indexOf = delegate.findChildIndexCallback!;
  expect(delegate.childCount, end - start + 1); // Includes history navigation.
  for (var i = 0; i < total; i++) {
    expect(
      indexOf(ValueKey('message-m$i')),
      i >= start && i < end
          ? (list.reverse ? end - 1 - i : i - start + 1)
          : isNull,
      reason:
          'Message m$i must belong only to the requested [$start, $end) slice',
    );
  }
}

void main() {
  test(
    'search uses literal case-insensitive matches in body and reasoning',
    () {
      const message = ChatMessage(
        id: 'm',
        role: ChatRole.assistant,
        text: 'A [Needle] answer',
        reasoning: 'Explain a+b before answering',
      );
      expect(
        ConversationSearchHit.forMessage(message, '[needle]')?.rowKey,
        const ValueKey('message-m'),
      );
      expect(
        ConversationSearchHit.forMessage(message, 'a+b')?.text,
        contains('a+b'),
      );
      expect(ConversationSearchHit.forMessage(message, '.*'), isNull);
      expect(ConversationSearchHit.forMessage(message, '正文'), isNull);
      expect(ConversationSearchHit.forMessage(message, '  '), isNull);
    },
  );

  test(
    'tool search includes command and summary but not full command output',
    () {
      final tool = ToolRun(
        id: 't',
        name: 'bash',
        detail: 'checked source',
        content: ToolContent(
          title: 'Inspect',
          command: const CommandResult(
            command: 'git status',
            stdout: 'unindexed-output',
          ),
        ),
      );
      expect(
        ConversationSearchHit.forTool(tool, 'STATUS')?.rowKey,
        const ValueKey('row-tool-t'),
      );
      expect(ConversationSearchHit.forTool(tool, 'checked'), isNotNull);
      expect(ConversationSearchHit.forTool(tool, 'unindexed-output'), isNull);
    },
  );

  testWidgets('closed search returns the full floating transcript viewport', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    const padding = EdgeInsets.only(top: 58, bottom: 148);
    await tester.pumpWidget(_app(_history(12), padding: padding));
    await tester.pumpAndSettle();
    final flow = find.byType(ConversationFlow);
    final listFinder = find.byType(ListView);
    final viewport = tester.getRect(flow);
    final list = tester.widget<ListView>(listFinder);
    expect(tester.getRect(listFinder), viewport);
    expect(list.padding, const EdgeInsets.fromLTRB(18, 66, 18, 172));
    expect(
      find.byKey(const ValueKey('conversation-search-input')),
      findsNothing,
    );
    final opener = find.byKey(const ValueKey('conversation-search-open'));
    expect(opener.hitTestable(), findsOneWidget);
    expect(tester.getSize(opener).shortestSide, greaterThanOrEqualTo(48));

    await _search(tester, 'missing');
    expect(tester.getRect(listFinder).top, greaterThan(viewport.top));
    expect(tester.getRect(listFinder).bottom, viewport.bottom);
    expect(
      tester.widget<ListView>(listFinder).controller,
      same(list.controller),
    );
    await tester.tap(find.byKey(const ValueKey('conversation-search-close')));
    await tester.pumpAndSettle();
    expect(tester.getRect(listFinder), viewport);
    expect(tester.widget<ListView>(listFinder).padding, list.padding);
    expect(
      tester.widget<ListView>(listFinder).controller,
      same(list.controller),
    );
    expect(opener.hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('search stays within the loaded page and refreshes on paging', (
    tester,
  ) async {
    await tester.pumpWidget(_app(_history(96), pageSize: 48));
    await _search(tester, 'Entry 0');
    expect(find.text('0/0'), findsOneWidget);
    expect(
      tester
          .widget<IconButton>(
            find.byKey(const ValueKey('conversation-search-next')),
          )
          .onPressed,
      isNull,
    );
    expect(find.text('仅搜索当前加载的 48 条消息'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('history-older')));
    await tester.pumpAndSettle();
    expect(find.text('1/1'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('search-match-message-m0')),
      findsOneWidget,
    );
  });

  testWidgets('a short transcript keeps appended messages in the loaded page', (
    tester,
  ) async {
    final messages = _history(12);
    await tester.pumpWidget(_app(messages, pageSize: 48));
    await tester.pumpAndSettle();
    final list = tester.widget<ListView>(find.byType(ListView));
    final offset = list.controller!.offset;
    await tester.pumpWidget(
      _app([
        ...messages,
        const ChatMessage(
          id: 'delivered',
          role: ChatRole.assistant,
          text: 'just delivered',
        ),
      ], pageSize: 48),
    );
    await tester.pumpAndSettle();
    expect(tester.widget<ListView>(find.byType(ListView)).reverse, isFalse);
    expect(list.controller!.offset, offset);
    await _search(tester, 'just delivered');
    expect(find.text('1/1'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('search-match-message-delivered')),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('an initial snapshot preserves chronological scrolling', (
    tester,
  ) async {
    await tester.pumpWidget(_app(const []));
    await tester.pump();
    await tester.pumpWidget(_app(_history(12)));
    await tester.pumpAndSettle();
    final list = tester.widget<ListView>(find.byType(ListView));
    expect(list.reverse, isFalse);
    for (var i = 0; i < 8; i++) {
      list.controller!.jumpTo(list.controller!.position.maxScrollExtent);
      await tester.pump();
    }
    expect(find.textContaining('Entry 11'), findsOneWidget);
    expect(find.text('有新消息'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('next and previous locate distant variable-height rows lazily', (
    tester,
  ) async {
    final messages = _history(48);
    messages[0] = messages[0].copyWith(text: 'NEEDLE first');
    messages[47] = messages[47].copyWith(text: 'needle final');
    await tester.pumpWidget(_app(messages));
    await _search(tester, 'needle');
    expect(find.text('1/2'), findsOneWidget);
    expect(find.byType(ConversationMessage).evaluate().length, lessThan(20));
    await tester.tap(find.byKey(const ValueKey('conversation-search-next')));
    await tester.pumpAndSettle();
    expect(find.text('2/2'), findsOneWidget);
    final target = find.byKey(const ValueKey('search-match-message-m47'));
    expect(target, findsOneWidget);
    expect(
      tester.getRect(target).overlaps(tester.getRect(find.byType(ListView))),
      isTrue,
    );
    expect(find.byType(ConversationMessage).evaluate().length, lessThan(20));
    await tester.tap(
      find.byKey(const ValueKey('conversation-search-previous')),
    );
    await tester.pumpAndSettle();
    expect(find.text('1/2'), findsOneWidget);
    expect(
      tester
          .getRect(find.byKey(const ValueKey('search-match-message-m0')))
          .overlaps(tester.getRect(find.byType(ListView))),
      isTrue,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'reasoning and collapsed tool summaries have visible keyword highlights',
    (tester) async {
      await tester.pumpWidget(
        _app(
          const [
            ChatMessage(
              id: 'r',
              role: ChatRole.assistant,
              text: '',
              reasoning: 'hidden TOKEN reasoning',
            ),
          ],
          // Two tools keep this a disclosure: the collapsed summary is what the
          // search hit has to reveal. A lone tool renders in place instead.
          tools: const [
            ToolRun(
              id: 't',
              name: 'bash',
              messageId: 'r',
              status: ToolStatus.complete,
              detail: 'TOKEN summary',
            ),
            ToolRun(
              id: 'peer',
              name: 'read',
              messageId: 'r',
              status: ToolStatus.complete,
              detail: 'PEER summary',
            ),
          ],
        ),
      );
      await _search(tester, 'token');
      expect(find.text('1/2'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('search-match-message-r')),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const ValueKey('conversation-search-next')));
      await tester.pumpAndSettle();
      final highlight = find.byKey(const ValueKey('search-match-row-tool-t'));
      expect(highlight, findsOneWidget);
      expect(find.byType(ToolRunCard), findsOneWidget);
      final texts = tester.widgetList<RichText>(
        find.descendant(of: highlight, matching: find.byType(RichText)),
      );
      var highlighted = false;
      for (final text in texts) {
        text.text.visitChildren((span) {
          if (span is TextSpan &&
              span.text?.toLowerCase() == 'token' &&
              span.style?.backgroundColor != null)
            highlighted = true;
          return true;
        });
      }
      expect(highlighted, isTrue);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const ValueKey('conversation-search-close')));
      await tester.pumpAndSettle();
      expect(find.byType(ToolRunCard), findsNothing);
    },
  );

  testWidgets(
    'new messages notify without stealing scroll and latest uses the exact tail',
    (tester) async {
      final messages = _history(60);
      await tester.pumpWidget(_app(messages, pageSize: 48));
      await tester.pumpAndSettle();
      final list = tester.widget<ListView>(find.byType(ListView));
      final oldOffset = list.controller!.offset;
      final firstRow = find.byKey(const ValueKey('message-m12'));
      final firstPosition = tester.getTopLeft(firstRow);
      final updated = [
        ...messages,
        const ChatMessage(
          id: 'new',
          role: ChatRole.assistant,
          text: 'EXACT NEW MESSAGE',
        ),
      ];
      await tester.pumpWidget(_app(updated, pageSize: 48));
      await tester.pumpAndSettle();
      expect(find.text('有新消息'), findsOneWidget);
      expect(tester.getTopLeft(firstRow), firstPosition);
      expect(
        tester.widget<ListView>(find.byType(ListView)).controller!.offset,
        oldOffset,
      );
      await tester.tap(find.byKey(const ValueKey('conversation-new-messages')));
      await tester.pumpAndSettle();
      final tailList = tester.widget<ListView>(find.byType(ListView));
      expect(tailList.reverse, isTrue);
      expect(tailList.controller!.offset, 0);
      expect(find.text('EXACT NEW MESSAGE'), findsOneWidget);
      expect(find.text('有新消息'), findsNothing);
    },
  );

  for (final appended in [48, 49]) {
    testWidgets(
      'reading page stays anchored and pages relatively after $appended appends',
      (tester) async {
        await tester.pumpWidget(_app(_history(96), pageSize: 48));
        await tester.pumpAndSettle();
        final listFinder = find.byKey(const ValueKey('conversation-list'));
        final first = find.byKey(const ValueKey('message-m48'));
        final before = tester.getTopLeft(first);
        final controller = tester.widget<ListView>(listFinder).controller!;
        final offset = controller.offset;
        _expectSlice(tester, 48, 96, 96);
        expect(controller.position.extentAfter, greaterThan(80));
        await tester.pumpWidget(_app(_history(96 + appended), pageSize: 48));
        await tester.pumpAndSettle();
        _expectSlice(tester, 48, 96, 96 + appended);
        // The initial and appended reading slice is the real 49–96 window:
        // m48 is its first message; appends must not slide it to the new tail.
        expect(first, findsOneWidget);
        expect(find.byKey(const ValueKey('message-m0')), findsNothing);
        expect(tester.getTopLeft(first), before);
        expect(controller.offset, offset);
        expect(find.text('有新消息'), findsOneWidget);

        await tester.tap(find.byKey(const ValueKey('history-older')));
        await tester.pumpAndSettle();
        _expectSlice(tester, 0, 48, 96 + appended);
        // Relative older navigation requests the real 1–48 slice.
        expect(find.byKey(const ValueKey('message-m0')), findsOneWidget);
        expect(find.textContaining('Entry 0'), findsOneWidget);
        expect(first, findsNothing);
        expect(tester.widget<ListView>(listFinder).controller!.offset, 0);
        expect(find.byKey(const ValueKey('history-older')), findsNothing);
        expect(find.text('有新消息'), findsOneWidget);

        await tester.tap(find.byKey(const ValueKey('history-newer')));
        await tester.pumpAndSettle();
        _expectSlice(tester, 48, 96, 96 + appended);
        // Newer navigation returns to the frozen 49–96 slice, not the tail.
        expect(first, findsOneWidget);
        expect(find.textContaining('Entry 48'), findsOneWidget);
        expect(find.byKey(const ValueKey('message-m0')), findsNothing);
        expect(tester.widget<ListView>(listFinder).controller!.offset, 0);
        expect(tester.widget<ListView>(listFinder).reverse, isFalse);
        expect(find.text('有新消息'), findsOneWidget);

        await tester.tap(find.byKey(const ValueKey('history-newer')));
        await tester.pumpAndSettle();
        _expectSlice(tester, 96, 144, 96 + appended);
        final nextPage = find.byKey(const ValueKey('message-m96'));
        expect(nextPage, findsOneWidget);
        expect(first, findsNothing);
        final nextPosition = tester.getTopLeft(nextPage);
        await tester.pumpWidget(_app(_history(97 + appended), pageSize: 48));
        await tester.pumpAndSettle();
        expect(nextPage, findsOneWidget);
        _expectSlice(tester, 96, 144, 97 + appended);
        expect(tester.getTopLeft(nextPage), nextPosition);
        expect(tester.widget<ListView>(listFinder).reverse, isFalse);
        expect(find.text('有新消息'), findsOneWidget);

        await tester.tap(
          find.byKey(const ValueKey('conversation-new-messages')),
        );
        await tester.pumpAndSettle();
        expect(tester.widget<ListView>(listFinder).reverse, isTrue);
        expect(controller.offset, 0);
        expect(find.textContaining('Entry ${96 + appended}'), findsOneWidget);
        _expectSlice(tester, 49 + appended, 97 + appended, 97 + appended);
        expect(find.text('有新消息'), findsNothing);

        // Explicit latest releases the held boundary; the next append follows
        // the live tail instead of freezing the page that was just visited.
        await tester.pumpWidget(_app(_history(98 + appended), pageSize: 48));
        await tester.pumpAndSettle();
        _expectSlice(tester, 50 + appended, 98 + appended, 98 + appended);
        expect(
          find.byKey(ValueKey('message-m${97 + appended}')),
          findsOneWidget,
        );
        expect(controller.offset, 0);
        expect(find.text('有新消息'), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('a partial newer slice remains disjoint and frozen on append', (
    tester,
  ) async {
    await tester.pumpWidget(_app(_history(96), pageSize: 48));
    await tester.pumpAndSettle();
    await tester.pumpWidget(_app(_history(145), pageSize: 48));
    await tester.pumpAndSettle();
    _expectSlice(tester, 48, 96, 145);
    await tester.tap(find.byKey(const ValueKey('history-newer')));
    await tester.pumpAndSettle();
    _expectSlice(tester, 96, 144, 145);
    await tester.tap(find.byKey(const ValueKey('history-newer')));
    await tester.pumpAndSettle();
    _expectSlice(tester, 144, 145, 145);
    expect(find.byKey(const ValueKey('message-m144')), findsOneWidget);

    await tester.pumpWidget(_app(_history(146), pageSize: 48));
    await tester.pumpAndSettle();
    _expectSlice(tester, 144, 145, 146);
    expect(find.byKey(const ValueKey('message-m145')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('history-older')));
    await tester.pumpAndSettle();
    _expectSlice(tester, 96, 144, 146);
    expect(find.byKey(const ValueKey('message-m96')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'back to latest leaves older pages and clears the unread indicator',
    (tester) async {
      await tester.pumpWidget(_app(_history(96), pageSize: 48));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('history-older')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey('conversation-scroll-bottom')),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('Entry 95'), findsOneWidget);
      expect(
        tester.widget<ListView>(find.byType(ListView)).controller!.offset,
        0,
      );
    },
  );

  testWidgets(
    'conversation changes clear search and pending approvals preempt a seek',
    (tester) async {
      final messages = _history(48);
      await tester.pumpWidget(_app(messages));
      await _search(tester, 'Entry');
      await tester.pumpWidget(_app(messages, identity: 'other'));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('conversation-search-input')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('conversation-search-open')),
        findsOneWidget,
      );
      await _search(tester, 'Entry');
      await tester.tap(find.byKey(const ValueKey('conversation-search-next')));
      await tester.pumpWidget(
        _app(messages, identity: 'other', approval: (1, 'request')),
      );
      await tester.pumpAndSettle();
      expect(
        tester.widget<ListView>(find.byType(ListView)).controller!.offset,
        0,
      );
      expect(
        find.byKey(const ValueKey('conversation-search-input')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('search preserves an already expanded structured message', (
    tester,
  ) async {
    await tester.pumpWidget(
      _app(const [
        ChatMessage(
          id: 'json',
          role: ChatRole.assistant,
          text: '{"token":"needle"}',
        ),
      ]),
    );
    await tester.tap(
      find.byKey(const ValueKey('message-structured-toggle-json')),
    );
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('message-structured-full-json')),
      findsOneWidget,
    );
    await _search(tester, 'needle');
    expect(
      find.byKey(const ValueKey('message-structured-full-json')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('conversation-search-close')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('message-structured-full-json')),
      findsOneWidget,
    );
  });

  testWidgets('search locates chronological hits from the reversed live tail', (
    tester,
  ) async {
    final messages = _history(48);
    messages[0] = messages[0].copyWith(text: 'needle oldest');
    messages[47] = messages[47].copyWith(text: 'needle latest');
    await tester.pumpWidget(_app(messages));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('conversation-scroll-bottom')));
    await tester.pumpAndSettle();
    await _search(tester, 'needle');
    expect(tester.widget<ListView>(find.byType(ListView)).reverse, isTrue);
    expect(
      tester
          .getRect(find.byKey(const ValueKey('search-match-message-m0')))
          .overlaps(tester.getRect(find.byType(ListView))),
      isTrue,
    );
    await tester.tap(
      find.byKey(const ValueKey('conversation-search-previous')),
    );
    await tester.pumpAndSettle();
    expect(find.text('2/2'), findsOneWidget);
    expect(
      tester
          .getRect(find.byKey(const ValueKey('search-match-message-m47')))
          .overlaps(tester.getRect(find.byType(ListView))),
      isTrue,
    );
  });

  testWidgets('search crosses a very tall row without building the full page', (
    tester,
  ) async {
    final messages = [
      ChatMessage(
        id: 'tall',
        role: ChatRole.user,
        text: 'very tall line\n' * 3000,
      ),
      ..._history(46),
      const ChatMessage(
        id: 'target',
        role: ChatRole.assistant,
        text: 'needle after tall row',
      ),
    ];
    await tester.pumpWidget(_app(messages));
    await _search(tester, 'needle');
    expect(
      tester
          .getRect(find.byKey(const ValueKey('search-match-message-target')))
          .overlaps(tester.getRect(find.byType(ListView))),
      isTrue,
    );
    expect(find.byType(ConversationMessage).evaluate().length, lessThan(20));
    expect(tester.takeException(), isNull);
  });

  testWidgets('disposing during a distant seek cancels pending scroll work', (
    tester,
  ) async {
    final messages = _history(300);
    messages.last = messages.last.copyWith(text: 'needle far away');
    await tester.pumpWidget(_app(messages));
    await tester.tap(find.byKey(const ValueKey('conversation-search-open')));
    await tester.pump();
    await tester.enterText(
      find.byKey(const ValueKey('conversation-search-input')),
      'needle',
    );
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('search controls fit a narrow large-text surface', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: MediaQuery(
          data: const MediaQueryData(
            size: Size(320, 700),
            textScaler: TextScaler.linear(1.6),
          ),
          child: Scaffold(
            body: ConversationFlow(
              conversationIdentity: 'narrow',
              messages: _history(8),
            ),
          ),
        ),
      ),
    );
    await _search(tester, 'missing');
    for (final key in [
      'conversation-search-previous',
      'conversation-search-next',
      'conversation-search-close',
    ]) {
      expect(
        tester.getSize(find.byKey(ValueKey(key))).shortestSide,
        greaterThanOrEqualTo(48),
      );
    }
    expect(tester.takeException(), isNull);
  });
}

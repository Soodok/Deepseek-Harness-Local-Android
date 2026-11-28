import 'dart:async';
import 'dart:convert';

import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../theme/display_scale.dart';

import '../model/chat_model.dart';
import '../state/message_actions.dart';
import '../theme/dsh_theme.dart';
import 'message_action_bar.dart';
import 'chat_shell.dart' show ChatLayout;
import 'execution_group.dart';
import 'conversation_search.dart';
import 'conversation_edge_blur.dart';
import 'file_delivery_card.dart';
import 'tool_run_card.dart';
import 'turn_notice_card.dart';
import 'assistant_rich_text.dart';

/// The same lazy message and execution flow for parent and child conversations.
/// [conversationIdentity] resets disclosure only when the actual source changes.
class ConversationFlow extends StatefulWidget {
  const ConversationFlow({
    super.key,
    required this.conversationIdentity,
    required this.messages,
    this.tools = const [],
    this.leading = const [],
    this.trailing = const [],
    this.empty,
    this.hideDockContent = false,
    this.listKey = const ValueKey('conversation-list'),
    this.actions,
    this.editingMessageId,
    this.onFileTap,
    this.onFileShare,
    this.padding,
    this.edgeBlurInsets,
    this.historyPageSize,
    this.pendingApproval,
  });

  final Object? conversationIdentity;
  final List<ChatMessage> messages;
  final List<ToolRun> tools;
  final List<Widget> leading, trailing;
  final Widget? empty;
  final bool hideDockContent;
  final Key listKey;
  final int? historyPageSize;

  /// A newly pending approval opens the live end of this conversation.
  final (int, String)? pendingApproval;

  /// Bubble actions for this transcript. Null renders the rows without them,
  /// which is what a read-only surface (a subagent view, a preview) wants.
  final MessageActionBindings? actions;
  final FileDeliveryTap? onFileTap;
  final FileDeliveryShare? onFileShare;

  /// The message currently open for editing, so exactly one bubble is marked.
  final String? editingMessageId;

  /// Room reserved for floating chrome above and below the transcript. The
  /// horizontal gutter stays whatever this layout computes.
  final EdgeInsets? padding;

  /// Insets reserved for floating chrome; the edge effect stays below controls.
  final EdgeInsets? edgeBlurInsets;

  @override
  State<ConversationFlow> createState() => _ConversationFlowState();
}

class _ConversationFlowState extends State<ConversationFlow> {
  final Set<String> _expanded = {};
  int _olderPages = 0;
  List<ChatMessage>? _indexedMessages;
  Set<String> _messageIds = const {};
  // The live tail is offset zero. A lazy list cannot reliably jump to an
  // estimated maxScrollExtent when the preceding rows have variable height.
  final _scroll = ScrollController();
  late bool _tailAnchored = widget.pendingApproval != null;
  final _search = TextEditingController();
  bool _searchOpen = false;
  String _query = '';
  List<ConversationSearchHit> _hits = const [];
  Key? _selectedHit;
  final _rowAnchors = <Key, GlobalKey>{};
  Map<Key, int> _rowIndices = const {};
  bool _awayFromLatest = false;
  bool _hasNewMessages = false;
  bool _metricsScheduled = false;
  bool _seeking = false;
  int _navigation = 0;
  String? _heldPageStartId;
  String? _heldPageEndId;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_scheduleReadingPosition);
  }

  bool get _atLatest =>
      _olderPages == 0 &&
      _heldPageEndId == null &&
      _scroll.hasClients &&
      _scroll.position.hasContentDimensions &&
      (_tailAnchored
              ? _scroll.position.extentBefore
              : _scroll.position.extentAfter) <=
          80;

  void _scheduleReadingPosition() {
    if (_metricsScheduled) return;
    _metricsScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _metricsScheduled = false;
      if (!mounted || !_scroll.hasClients) return;
      final away = !_atLatest;
      if (away != _awayFromLatest || (!away && _hasNewMessages)) {
        setState(() {
          _awayFromLatest = away;
          if (!away) _hasNewMessages = false;
        });
      }
    });
  }

  void _resetSearch() {
    _navigation++;
    _seeking = false;
    _searchOpen = false;
    _query = '';
    _search.clear();
    _hits = const [];
    _selectedHit = null;
  }

  void _searchChanged(String value) {
    setState(() {
      _query = value.trim();
      _selectedHit = null;
      _seeking = false;
    });
    _queueSelectedHit();
  }

  void _queueSelectedHit() {
    final navigation = ++_navigation;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && navigation == _navigation && _selectedHit != null) {
        unawaited(_locateHit(_selectedHit!));
      }
    });
  }

  void _moveHit(int direction) {
    if (_hits.isEmpty) return;
    final current = _hits.indexWhere((hit) => hit.rowKey == _selectedHit);
    final next = (current + direction) % _hits.length;
    setState(() => _selectedHit = _hits[next].rowKey);
    unawaited(_locateHit(_selectedHit!));
  }

  Future<void> _locateHit(Key key) async {
    final navigation = ++_navigation;
    setState(() => _seeking = true);
    await WidgetsBinding.instance.endOfFrame;
    // Walk overlapping viewport windows instead of estimating an absolute
    // offset from variable-height rows. Only ListView's visible/cache window
    // is built. A new query, drag, page, approval or conversation cancels it.
    while (true) {
      if (!mounted || navigation != _navigation || !_scroll.hasClients) return;
      final target = _rowAnchors[key]?.currentContext;
      if (target != null && target.mounted) {
        await Scrollable.ensureVisible(target, alignment: .15);
        if (mounted && navigation == _navigation)
          setState(() => _seeking = false);
        return;
      }
      final index = _rowIndices[key];
      final mountedIndices = [
        for (final entry in _rowAnchors.entries)
          if (entry.value.currentContext != null &&
              _rowIndices.containsKey(entry.key))
            _rowIndices[entry.key]!,
      ]..sort();
      if (index == null || mountedIndices.isEmpty) break;
      final position = _scroll.position;
      final direction = index < mountedIndices.first ? -1 : 1;
      final distance = position.viewportDimension * .8;
      var next = position.pixels + direction * distance;
      final boundaryIndex = direction < 0
          ? mountedIndices.first
          : mountedIndices.last;
      final boundaryKey = _rowIndices.entries
          .firstWhere((entry) => entry.value == boundaryIndex)
          .key;
      final boundary = _rowAnchors[boundaryKey]?.currentContext
          ?.findRenderObject();
      if (boundary != null && boundary.attached) {
        // Cross a very tall mounted row in one step, retaining an overlapping
        // window at its far edge so a small following hit cannot be skipped.
        final edge = RenderAbstractViewport.of(boundary)
            .getOffsetToReveal(boundary, direction > 0 ? 1 : 0)
            .offset;
        final beyond = edge + direction * distance;
        if (direction > 0 ? beyond > next : beyond < next) next = beyond;
      }
      final offset = next.clamp(
        position.minScrollExtent,
        position.maxScrollExtent,
      );
      if ((offset - position.pixels).abs() < .5) break;
      _scroll.jumpTo(offset);
      await WidgetsBinding.instance.endOfFrame;
    }
    if (mounted && navigation == _navigation) setState(() => _seeking = false);
  }

  void _visitPage(int page) {
    final size = widget.historyPageSize;
    final allMessages = widget.messages;
    var nextHeldPageStartId = _heldPageStartId;
    var nextHeldPageEndId = _heldPageEndId;
    if (size != null && size > 0 && allMessages.isNotEmpty) {
      // Move one window from the boundary currently being read. Do not derive
      // the target from the current total: new tail messages may have arrived
      // since this window was frozen.
      final heldEnd = _heldPageEndId == null
          ? allMessages.length
          : allMessages.indexWhere((message) => message.id == _heldPageEndId) +
                1;
      final currentEnd = heldEnd > 0 ? heldEnd : allMessages.length;
      final heldStart = allMessages.indexWhere(
        (message) => message.id == _heldPageStartId,
      );
      final currentStart = heldStart >= 0
          ? heldStart
          : (currentEnd - size).clamp(0, currentEnd);
      final older = page > _olderPages;
      final nextEnd = older
          ? currentStart
          : (currentEnd + size).clamp(0, allMessages.length);
      final nextStart = older ? (nextEnd - size).clamp(0, nextEnd) : currentEnd;
      if (nextEnd > nextStart) {
        nextHeldPageStartId = allMessages[nextStart].id;
        nextHeldPageEndId = allMessages[nextEnd - 1].id;
      }
    }
    final navigation = ++_navigation;
    setState(() {
      _seeking = false;
      _olderPages = page;
      _heldPageStartId = nextHeldPageStartId;
      _heldPageEndId = nextHeldPageEndId;
      _awayFromLatest = true;
      _selectedHit = null;
    });
    // A page is a new chronological slice. Reset its viewport to the slice
    // start; otherwise ListView retains the old page's offset and may never
    // build the first message of the requested window.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted &&
          navigation == _navigation &&
          _scroll.hasClients &&
          !_tailAnchored) {
        _scroll.jumpTo(_scroll.position.minScrollExtent);
      }
    });
    if (_query.isNotEmpty) _queueSelectedHit();
  }

  void _goToLatest() {
    final navigation = ++_navigation;
    setState(() {
      _olderPages = 0;
      _tailAnchored = true;
      _heldPageStartId = null;
      _heldPageEndId = null;
      _awayFromLatest = false;
      _hasNewMessages = false;
      _seeking = false;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && navigation == _navigation && _scroll.hasClients)
        _scroll.jumpTo(0);
    });
  }

  @override
  void didUpdateWidget(covariant ConversationFlow oldWidget) {
    super.didUpdateWidget(oldWidget);
    final changedConversation =
        oldWidget.conversationIdentity != widget.conversationIdentity;
    if (changedConversation) {
      _expanded.clear();
      _olderPages = 0;
      _tailAnchored = false;
      _heldPageStartId = null;
      _heldPageEndId = null;
      _hasNewMessages = false;
      _awayFromLatest = false;
      _resetSearch();
      _rowAnchors.clear();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _scroll.hasClients) _scroll.jumpTo(0);
      });
    } else {
      final oldTail = oldWidget.messages.lastOrNull;
      final tail = widget.messages.lastOrNull;
      final oldTool = oldWidget.tools.lastOrNull;
      final tool = widget.tools.lastOrNull;
      final changedTail =
          tail != null &&
          tail != oldTail &&
          widget.messages.length >= oldWidget.messages.length;
      final changedTool =
          tool != null &&
          (tool.id != oldTool?.id ||
              tool.status != oldTool?.status ||
              tool.detail != oldTool?.detail);
      final hadContent =
          oldWidget.messages.isNotEmpty || oldWidget.tools.isNotEmpty;
      if (hadContent && (changedTail || changedTool)) {
        if (_atLatest && !_searchOpen) {
          // Ordinary updates keep the current scroll direction. Only an
          // explicit jump to latest or a pending approval changes the anchor.
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted && _scroll.hasClients && !_seeking) {
              _scroll.jumpTo(
                _tailAnchored ? 0 : _scroll.position.maxScrollExtent,
              );
            }
          });
        } else {
          _hasNewMessages = true;
          // Freeze the page boundary while reading: appended messages must not
          // evict the first visible row from a 48-message window.
          final size = widget.historyPageSize;
          if (_heldPageEndId == null &&
              size != null &&
              widget.messages.length > size &&
              widget.messages.length > oldWidget.messages.length &&
              oldWidget.messages.isNotEmpty) {
            final end = oldWidget.messages.length;
            final start = (end - size).clamp(0, end);
            _heldPageStartId = oldWidget.messages[start].id;
            _heldPageEndId = oldWidget.messages[end - 1].id;
          }
        }
      }
    }
    final approval = widget.pendingApproval;
    if (approval != null &&
        (changedConversation || oldWidget.pendingApproval != approval)) {
      _olderPages = 0;
      _tailAnchored = true;
      _heldPageStartId = null;
      _heldPageEndId = null;
      _hasNewMessages = false;
      _resetSearch();
      final conversation = widget.conversationIdentity;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted &&
            _scroll.hasClients &&
            widget.conversationIdentity == conversation &&
            widget.pendingApproval == approval &&
            _scroll.offset != 0) {
          _scroll.jumpTo(0);
        }
      });
    }
  }

  @override
  void dispose() {
    _navigation++;
    _search.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.messages.isEmpty &&
        widget.tools.isEmpty &&
        widget.leading.isEmpty &&
        widget.trailing.isEmpty &&
        widget.empty != null) {
      final empty = widget.empty!;
      final insets = widget.edgeBlurInsets;
      if (insets == null) return empty;
      return ConversationEdgeBlur(
        contentInsets: insets,
        child: GestureDetector(
          behavior: HitTestBehavior.translucent,
          onTap: () => FocusScope.of(context).unfocus(),
          child: empty,
        ),
      );
    }
    final activePlanId = widget.hideDockContent
        ? widget.tools
              .where((t) => t.content?.steps.isNotEmpty ?? false)
              .lastOrNull
              ?.id
        : null;
    final allMessages = widget.messages;
    final size = widget.historyPageSize;
    // The page counter records navigation intent; the held end ID is the
    // authoritative visible boundary. Never infer a reading page from the
    // current total, because appends may have changed it since the last frame.
    final page = _olderPages;
    final heldEnd = _heldPageEndId == null
        ? -1
        : allMessages.indexWhere((message) => message.id == _heldPageEndId);
    final end = heldEnd >= 0 ? heldEnd + 1 : allMessages.length;
    final heldStart = _heldPageStartId == null
        ? -1
        : allMessages.indexWhere((message) => message.id == _heldPageStartId);
    final start = heldStart >= 0 && heldStart < end
        ? heldStart
        : (size == null ? 0 : (end - size).clamp(0, end));
    final messages = start == 0 && end == allMessages.length
        ? allMessages
        : allMessages.sublist(start, end);
    if (!identical(_indexedMessages, allMessages)) {
      _indexedMessages = allMessages;
      _messageIds = allMessages.map((message) => message.id).toSet();
    }
    final ids = messages.map((m) => m.id).toSet();
    final byMessage = <String, List<ToolRun>>{};
    final unbound = <ToolRun>[];
    for (final tool in widget.tools) {
      final content = tool.content;
      if (widget.hideDockContent &&
          content != null &&
          (content.steps.isNotEmpty || content.agents.isNotEmpty) &&
          (content.steps.isEmpty || tool.id == activePlanId) &&
          tool.detail.isEmpty &&
          content.resources.isEmpty &&
          content.command == null &&
          content.edits.isEmpty &&
          content.reasoning.isEmpty) {
        continue;
      }
      if (tool.messageId != null && ids.contains(tool.messageId)) {
        byMessage.putIfAbsent(tool.messageId!, () => []).add(tool);
      } else if (end == allMessages.length &&
          !_messageIds.contains(tool.messageId)) {
        unbound.add(tool);
      }
    }

    // Derive identity before slicing, solely from complete message-order
    // boundaries. Tool arrival, filtering and the first visible tool cannot
    // change the identity of a surviving execution trace.
    final groupIdByMessage = <String, String>{};
    String? groupBoundaryId;
    for (final message in allMessages) {
      final fragment =
          message.role == ChatRole.assistant &&
          message.text.trim().isEmpty &&
          !message.isFinalAnswer;
      if (!fragment || groupBoundaryId == null) {
        groupBoundaryId = message.id;
      }
      groupIdByMessage[message.id] = 'message-' + groupBoundaryId;
    }

    final searchHits = <Key, ConversationSearchHit>{};
    final matchingTools = <String>{};
    if (_query.isNotEmpty) {
      for (final message in messages) {
        final hit = ConversationSearchHit.forMessage(message, _query);
        if (hit != null) searchHits[hit.rowKey] = hit;
      }
      for (final tool in [
        ...byMessage.values.expand((tools) => tools),
        ...unbound,
      ]) {
        final hit = ConversationSearchHit.forTool(tool, _query);
        if (hit != null) {
          searchHits[hit.rowKey] = hit;
          matchingTools.add(tool.id);
        }
      }
    }

    // Descriptors are cheap; a widget is created only when its row is requested.
    final rows = <({Key? key, WidgetBuilder build})>[];
    for (final child in widget.leading) {
      rows.add((
        key: child.key == null ? null : ValueKey(('flow', child.key)),
        build: (_) => child,
      ));
    }
    void addMessage(ChatMessage message, {bool compactExecution = false}) {
      if (message.role == ChatRole.assistant &&
          message.text.trim().isEmpty &&
          message.reasoning.trim().isEmpty) {
        return;
      }
      rows.add((
        key: ValueKey('message-' + message.id),
        build: (_) => Opacity(
          opacity:
              widget.editingMessageId == null ||
                  widget.editingMessageId == message.id
              ? 1
              : .45,
          child: ConversationMessage(
            key: ValueKey((widget.conversationIdentity, message.id)),
            message: message,
            actions: widget.actions,
            onFileTap: widget.onFileTap,
            onFileShare: widget.onFileShare,
            editing: widget.editingMessageId == message.id,
            compactExecution: compactExecution,
          ),
        ),
      ));
    }

    void addTools(List<ToolRun> tools) {
      for (final tool in tools) {
        rows.add((
          key: ValueKey('row-tool-' + tool.id),
          build: (context) => Padding(
            padding: const EdgeInsets.only(left: 8),
            child: DecoratedBox(
              decoration: BoxDecoration(
                border: Border(
                  left: BorderSide(color: DshPalette.of(context).line),
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.only(left: 8),
                child: ToolRunCard(
                  tool: tool,
                  showPlan: tool.id != activePlanId,
                  compact: true,
                ),
              ),
            ),
          ),
        ));
      }
    }

    void addGroup(
      String id,
      List<ToolRun> tools, {
      List<ToolRun> leadingTools = const [],
      List<ChatMessage> fragments = const [],
    }) {
      final hasDisclosure = tools.length > 1;
      if (hasDisclosure) {
        rows.add((
          key: ValueKey('execution-group-' + id),
          build: (_) => ExecutionGroupHeader(
            groupId: id,
            tools: tools,
            expanded: _expanded.contains(id),
            onToggle: () => setState(() {
              if (!_expanded.remove(id)) _expanded.add(id);
            }),
          ),
        ));
      }
      final expanded = !hasDisclosure || _expanded.contains(id);
      // A call-only assistant creates no transcript row: its tools can belong
      // to the preceding user or prose. Keep those before later step thoughts.
      addTools(
        leadingTools
            .where((tool) => expanded || matchingTools.contains(tool.id))
            .toList(),
      );
      for (final message in fragments) {
        // The outer disclosure owns the entire execution trace; each visible
        // thought still has its own disclosure and copy action. Search hits
        // remain reachable without changing the user's stored expansion.
        if (tools.isEmpty ||
            expanded ||
            searchHits.containsKey(ValueKey('message-' + message.id))) {
          addMessage(message, compactExecution: true);
        }
        addTools(
          (byMessage[message.id] ?? const [])
              .where((tool) => expanded || matchingTools.contains(tool.id))
              .toList(),
        );
      }
    }

    if (start > 0 || page > 0 || heldEnd >= 0) {
      rows.add((
        key: const ValueKey('history-navigation'),
        build: (_) => Wrap(
          alignment: WrapAlignment.center,
          spacing: 8,
          children: [
            if (start > 0)
              TextButton.icon(
                key: const ValueKey('history-older'),
                onPressed: () => _visitPage(page + 1),
                icon: const Icon(Icons.expand_less),
                label: const Text('更早消息'),
              ),
            if (page > 0 || heldEnd >= 0)
              TextButton.icon(
                key: const ValueKey('history-newer'),
                onPressed: () {
                  if (page > 0) {
                    _visitPage(page - 1);
                  } else if (heldEnd >= 0 && heldEnd + 1 < allMessages.length) {
                    // Advance one relative frozen slice before returning to
                    // the live tail; appends must not be skipped.
                    _visitPage(page);
                  } else {
                    _goToLatest();
                  }
                },
                icon: const Icon(Icons.expand_more),
                label: const Text('更新消息'),
              ),
          ],
        ),
      ));
    }
    final fragments = <ChatMessage>[];
    final leadingTools = <ToolRun>[];
    String stableGroupId(List<ToolRun> tools, List<ChatMessage> fragments) {
      for (final tool in tools) {
        final messageId = tool.messageId;
        final id = messageId == null ? null : groupIdByMessage[messageId];
        if (id != null) return id;
      }
      for (final message in fragments) {
        final id = groupIdByMessage[message.id];
        if (id != null) return id;
      }
      // This fallback only covers an unindexed transient row; normal projected
      // messages are assigned above from the complete chronological sequence.
      final firstId = tools.isEmpty
          ? fragments.first.id
          : tools.first.messageId!;
      return 'message-' + firstId;
    }

    void flushExecution() {
      if (fragments.isEmpty && leadingTools.isEmpty) return;
      final tools = <ToolRun>[
        ...leadingTools,
        for (final message in fragments) ...?byMessage[message.id],
      ];
      addGroup(
        stableGroupId(tools, fragments),
        tools,
        leadingTools: leadingTools,
        fragments: fragments,
      );
      leadingTools.clear();
      fragments.clear();
    }

    for (final message in messages) {
      if (message.role == ChatRole.assistant &&
          message.text.trim().isEmpty &&
          !message.isFinalAnswer) {
        fragments.add(message);
        continue;
      }
      // Prose/final answers and every non-assistant role end the prior stack.
      // Their bound calls begin the next stack and may continue in later steps.
      flushExecution();
      addMessage(message);
      leadingTools.addAll(byMessage[message.id] ?? const []);
    }
    flushExecution();
    if (unbound.isNotEmpty) {
      addGroup('unbound-' + unbound.first.id, unbound, leadingTools: unbound);
    }
    for (final child in widget.trailing) {
      rows.add((
        key: child.key == null ? null : ValueKey(('flow', child.key)),
        build: (_) => child,
      ));
    }
    _hits = [
      for (final row in rows)
        if (searchHits[row.key] != null) searchHits[row.key]!,
    ];
    if (!_hits.any((hit) => hit.rowKey == _selectedHit)) {
      _selectedHit = _hits.firstOrNull?.rowKey;
    }
    // Keep the tail anchor after settlement so withdrawing the card doesn't
    // send the reader back to the beginning of the visible page.
    final orderedRows = _tailAnchored ? rows.reversed.toList() : rows;
    final indices = <Key, int>{
      for (var i = 0; i < orderedRows.length; i++)
        if (orderedRows[i].key != null) orderedRows[i].key!: i,
    };
    _rowIndices = indices;
    _rowAnchors.removeWhere((key, _) => !indices.containsKey(key));
    final gutter = ChatLayout.gutter(MediaQuery.sizeOf(context).width);
    final list = NotificationListener<ScrollMetricsNotification>(
      onNotification: (_) {
        _scheduleReadingPosition();
        return false;
      },
      child: NotificationListener<ScrollStartNotification>(
        onNotification: (notification) {
          if (notification.dragDetails != null) {
            _navigation++;
            if (_seeking) setState(() => _seeking = false);
          }
          return false;
        },
        child: ListView.builder(
          key: widget.listKey,
          controller: _scroll,
          reverse: _tailAnchored,
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          padding: EdgeInsets.fromLTRB(
            gutter,
            8 + (widget.padding?.top ?? 0),
            gutter,
            24 + (widget.padding?.bottom ?? 0),
          ),
          itemCount: orderedRows.length,
          findChildIndexCallback: (key) => indices[key],
          itemBuilder: (context, index) => Center(
            key: orderedRows[index].key,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
              child: KeyedSubtree(
                key: orderedRows[index].key == null
                    ? null
                    : _rowAnchors.putIfAbsent(
                        orderedRows[index].key!,
                        () => GlobalKey(),
                      ),
                child: SizedBox(
                  width: double.infinity,
                  child: ConversationSearchHighlight(
                    hit: searchHits[orderedRows[index].key],
                    query: _query,
                    active: orderedRows[index].key == _selectedHit,
                    child: orderedRows[index].build(context),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    final transcriptContent = GestureDetector(
      behavior: HitTestBehavior.translucent,
      onTap: () => FocusScope.of(context).unfocus(),
      child: widget.edgeBlurInsets == null
          ? list
          : ConversationEdgeBlur(
              contentInsets: widget.edgeBlurInsets!,
              child: list,
            ),
    );
    final overlay = <Widget>[
      Positioned.fill(child: transcriptContent),
      // The collapsed opener sits above the transcript without claiming a
      // layout strip of its own, so a closed search costs no vertical space.
      if (!_searchOpen)
        Positioned(
          top: (widget.padding?.top ?? 0) + 4,
          right: gutter,
          child: IconButton(
            key: const ValueKey('conversation-search-open'),
            tooltip: '搜索已加载消息',
            constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
            onPressed: () => setState(() => _searchOpen = true),
            icon: const Icon(Icons.search),
          ),
        ),
      if (_awayFromLatest || _hasNewMessages || page > 0 || heldEnd >= 0)
        Positioned(
          bottom: (widget.padding?.bottom ?? 0) + 12,
          left: gutter,
          right: gutter,
          child: Center(
            child: ConversationLatestButton(
              hasNewMessages: _hasNewMessages,
              onPressed: _goToLatest,
            ),
          ),
        ),
    ];
    if (!_searchOpen) {
      return Stack(fit: StackFit.expand, children: overlay);
    }
    // An open search owns its own strip above the list instead of covering
    // transcript rows, and short screens scroll the bar inside that strip.
    return Column(
      children: [
        Padding(
          padding: EdgeInsets.fromLTRB(
            gutter,
            (widget.padding?.top ?? 0) + 4,
            gutter,
            4,
          ),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: (MediaQuery.sizeOf(context).height / 3).clamp(
                72,
                double.infinity,
              ),
            ),
            child: SingleChildScrollView(
              child: ConversationSearchBar(
                controller: _search,
                loadedCount: messages.length,
                hitCount: _hits.length,
                selectedIndex: _hits.indexWhere(
                  (hit) => hit.rowKey == _selectedHit,
                ),
                seeking: _seeking,
                onChanged: _searchChanged,
                onPrevious: () => _moveHit(-1),
                onNext: () => _moveHit(1),
                onClose: () {
                  FocusScope.of(context).unfocus();
                  setState(_resetSearch);
                },
              ),
            ),
          ),
        ),
        Expanded(
          child: Stack(fit: StackFit.expand, children: overlay),
        ),
      ],
    );
  }
}

class ConversationMessage extends StatelessWidget {
  const ConversationMessage({
    super.key,
    required this.message,
    this.actions,
    this.onFileTap,
    this.onFileShare,
    this.editing = false,
    this.compactExecution = false,
  });

  final ChatMessage message;
  final MessageActionBindings? actions;
  final FileDeliveryTap? onFileTap;
  final FileDeliveryShare? onFileShare;

  /// True for the message currently opened in the composer.
  final bool editing;

  /// Reasoning inside an execution stack has no ordinary answer margins.
  final bool compactExecution;

  @override
  Widget build(BuildContext context) {
    final notice = message.notice;
    if (message.role == ChatRole.notice && notice != null) {
      return TurnNoticeCard(notice: notice);
    }
    if (message.role == ChatRole.user) return _userBubble(context);
    return _answer(context);
  }

  /// A person's own message: tapping the bubble edits it, and the two icons
  /// below it copy or retract the whole Turn from here.
  Widget _userBubble(BuildContext context) {
    final palette = DshPalette.of(context);
    final bindings = actions;
    // Editing is only meaningful for a durable message: a row the Host has not
    // recorded cannot be rewound, so tapping it must not open a dead end.
    final canEdit = bindings != null && message.seq != null && !editing;
    final bubble = Container(
      key: ValueKey('message-bubble-' + message.id),
      constraints: const BoxConstraints(maxWidth: 500),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      decoration: BoxDecoration(
        color: editing
            ? palette.userWash.withValues(alpha: .55)
            : palette.userWash,
        borderRadius: BorderRadius.circular(20),
        border: editing
            ? Border.all(color: palette.ice.withValues(alpha: .55))
            : null,
      ),
      child: Text(
        message.text,
        style: TextStyle(color: palette.ink, fontSize: 16.5, height: 1.55),
      ),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Align(
          alignment: Alignment.centerRight,
          child: Padding(
            padding: const EdgeInsets.only(top: 18, bottom: 6, left: 32),
            child: canEdit
                ? Semantics(
                    button: true,
                    hint: '编辑消息',
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () => bindings.edit(message),
                      child: bubble,
                    ),
                  )
                : bubble,
          ),
        ),
        if (bindings != null)
          Padding(
            padding: const EdgeInsets.only(right: 10),
            child: UserMessageActions(
              keySuffix: '-' + message.id,
              copied: bindings.wasCopied(message.id),
              onCopy: () => bindings.copy(message),
              // Copy needs only the text; retracting needs the Host's seq.
              onRetract: message.seq == null || bindings.isBusy(message.id)
                  ? null
                  : () => bindings.retract(message),
            ),
          ),
      ],
    );
  }

  /// An answer: the five actions appear once the Turn has actually finished, so
  /// a running answer never shows a button that would act on a partial result.
  Widget _answer(BuildContext context) {
    final bindings = actions;
    final files = message.role == ChatRole.assistant
        ? extractFileDeliveries(message.text)
        : <FileDelivery>[];
    final settled =
        bindings != null &&
        message.isFinalAnswer &&
        !message.isStreaming &&
        (message.messageId ?? '').isNotEmpty;
    return Padding(
      padding: compactExecution
          ? const EdgeInsets.only(bottom: 2)
          : const EdgeInsets.only(top: 22, bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (message.reasoning.trim().isNotEmpty)
            _MessageBlock(
              key: ValueKey(('reasoning', message.id)),
              part: (
                text: message.reasoning,
                label: message.isStreaming && message.text.trim().isEmpty
                    ? '正在思考'
                    : '思考过程',
                id: 'message-reasoning-' + message.id,
              ),
              compactExecution: compactExecution,
            ),
          if (message.text.trim().isNotEmpty ||
              message.reasoning.trim().isEmpty)
            _MessageText(
              key: ValueKey(('body', message.id)),
              message: message,
              compactExecution: compactExecution,
            ),
          for (final file in files)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: FileDeliveryCard(
                file: file,
                onFileTap: onFileTap,
                onFileShare: onFileShare,
              ),
            ),
          if (message.isStreaming && !compactExecution)
            const Padding(
              padding: EdgeInsets.only(top: 10),
              child: RepaintBoundary(child: _StreamingMark()),
            ),
          if (settled)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: AssistantMessageActions(
                keySuffix: '-' + message.id,
                copied: bindings.wasCopied(message.id),
                rating: bindings.ratingOf(message.messageId),
                busy: bindings.isBusy(message.id),
                onCopy: () => bindings.copy(message),
                onLike: () => bindings.like(message),
                onDislike: () => bindings.dislike(message),
                onRegenerate: bindings.regenerate == null
                    ? null
                    : () => bindings.regenerate!(message),
                onShare: () => bindings.share(message),
              ),
            ),
        ],
      ),
    );
  }
}

typedef _MessagePart = ({String text, String? label, String? id});

/// Parsing is retained when tool/status updates rebuild an unchanged message.
class _MessageText extends StatefulWidget {
  const _MessageText({
    super.key,
    required this.message,
    this.compactExecution = false,
  });
  final ChatMessage message;
  final bool compactExecution;
  @override
  State<_MessageText> createState() => _MessageTextState();
}

class _MessageTextState extends State<_MessageText> {
  late List<_MessagePart> _parts;
  late Widget _body;

  @override
  void initState() {
    super.initState();
    _parts = _parseMessage(widget.message);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _body = _buildBody(context);
  }

  @override
  void didUpdateWidget(covariant _MessageText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.message.text != widget.message.text ||
        oldWidget.message.role != widget.message.role ||
        oldWidget.message.isStreaming != widget.message.isStreaming ||
        oldWidget.compactExecution != widget.compactExecution) {
      _parts = _parseMessage(widget.message);
      _body = _buildBody(context);
    }
  }

  @override
  Widget build(BuildContext context) => _body;

  Widget _buildBody(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    mainAxisSize: MainAxisSize.min,
    children: [
      for (final part in _parts)
        if (part.label == null)
          AssistantRichText(
            markdown: part.text.isEmpty && widget.message.isStreaming
                ? '…'
                : part.text,
          )
        else
          _MessageBlock(
            key: ValueKey(part.id),
            part: part,
            compactExecution: widget.compactExecution,
          ),
    ],
  );
}

List<_MessagePart> _parseMessage(ChatMessage message) {
  final text = message.text;
  if (message.role != ChatRole.assistant) {
    return [(text: text, label: null, id: null)];
  }
  final trimmed = text.trim();
  if (message.isStreaming &&
      RegExp(r'^\s*(?:\{\s*"|\[\s*(?:\{|\[|"|-?\d|true|false|null))')
          .hasMatch(trimmed)) {
    return [
      (text: text, label: 'JSON', id: 'message-structured-' + message.id),
    ];
  }
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) ||
      (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    try {
      final value = jsonDecode(trimmed);
      if (value is Map || value is List) {
        return [
          (text: text, label: 'JSON', id: 'message-structured-' + message.id),
        ];
      }
    } on FormatException {
      // Bracketed prose and incomplete JSON remain readable as ordinary text.
    }
  }
  final tick = String.fromCharCode(96);
  final opening = RegExp(
    '^ {0,3}(' + tick + r'{3,}|~{3,})([^\r\n]*)\r?\n',
    multiLine: true,
  );
  final parts = <_MessagePart>[];
  var position = 0;
  var index = 0;
  while (position < text.length) {
    final matches = opening.allMatches(text, position);
    if (matches.isEmpty) break;
    final start = matches.first;
    final marker = start.group(1)!;
    // Mixed marker characters cannot form one fenced block.
    final closing = RegExp(
      '^ {0,3}' +
          RegExp.escape(marker[0]) +
          '{' +
          marker.length.toString() +
          r',}[\t ]*\r?$',
      multiLine: true,
    );
    final ends = closing.allMatches(text, start.end);
    final end = ends.isEmpty ? null : ends.first;
    if (start.start > position) {
      var prose = text.substring(position, start.start).trimRight();
      if (index > 0) prose = prose.trimLeft();
      if (prose.isNotEmpty) {
        parts.add((text: prose, label: null, id: null));
      }
    }
    final language = start.group(2)!.trim();
    if (language == 'chart') {
      final chartEnd = end?.end;
      if (chartEnd == null) {
        parts.add((text: text.substring(start.start), label: null, id: null));
        position = text.length;
        break;
      }
      parts.add((
        text: text.substring(start.start, chartEnd),
        label: null,
        id: null,
      ));
      position = chartEnd;
      continue;
    }
    parts.add((
      text: text.substring(start.end, end?.start ?? text.length),
      label: language.isEmpty ? '代码' : '代码 · ' + language,
      id: 'message-code-' + message.id + '-' + index.toString(),
    ));
    index++;
    position = end?.end ?? text.length;
  }
  if (position < text.length) {
    final prose = index > 0
        ? text.substring(position).trimLeft()
        : text.substring(position);
    if (prose.isNotEmpty) parts.add((text: prose, label: null, id: null));
  }
  return parts.isEmpty ? [(text: text, label: null, id: null)] : parts;
}

class _MessageBlock extends StatefulWidget {
  const _MessageBlock({
    super.key,
    required this.part,
    this.compactExecution = false,
  });
  final _MessagePart part;
  final bool compactExecution;
  @override
  State<_MessageBlock> createState() => _MessageBlockState();
}

class _MessageBlockState extends State<_MessageBlock> {
  bool _expanded = false;
  bool _copying = false;

  Future<void> _copy() async {
    if (_copying) return;
    setState(() => _copying = true);
    var label = '已复制';
    try {
      await Clipboard.setData(ClipboardData(text: widget.part.text));
    } catch (_) {
      label = '复制失败，请重试。';
    }
    if (!mounted) return;
    setState(() => _copying = false);
    ScaffoldMessenger.maybeOf(context)
        ?.showSnackBar(SnackBar(content: Text(label)));
  }

  @override
  Widget build(BuildContext context) {
    final part = widget.part;
    final isReasoning = part.id!.startsWith('message-reasoning-');
    final isJson = part.id!.startsWith('message-structured-');
    final prefix = isReasoning
        ? 'message-reasoning'
        : isJson
        ? 'message-structured'
        : 'message-code';
    final suffix = part.id!.substring(prefix.length + 1);
    final colors = DshPalette.of(context);
    final compactReasoning = widget.compactExecution && isReasoning;
    final preview = _messagePreview(
      part.text,
      maxLines: compactReasoning
          ? 3
          : isReasoning
          ? 6
          : 12,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          children: [
            Expanded(
              child: Semantics(
                expanded: _expanded,
                child: TextButton.icon(
                  key: ValueKey(prefix + '-toggle-' + suffix),
                  onPressed: () => setState(() => _expanded = !_expanded),
                  style: TextButton.styleFrom(
                    minimumSize: DshDisplayScope.targetSize(context, width: 0),
                    alignment: Alignment.centerLeft,
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    foregroundColor: colors.inkSoft,
                  ),
                  icon: Icon(
                    _expanded
                        ? Icons.expand_less_rounded
                        : Icons.expand_more_rounded,
                    size: 17,
                  ),
                  label: Text(
                    part.label!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: compactReasoning ? 12 : 13),
                  ),
                ),
              ),
            ),
            IconButton(
              key: ValueKey(prefix + '-copy-' + suffix),
              tooltip: '复制完整内容',
              constraints: BoxConstraints.tight(
                DshDisplayScope.targetSize(context),
              ),
              onPressed: _copying ? null : _copy,
              icon: const Icon(Icons.copy_rounded, size: 16),
            ),
          ],
        ),
        if (_expanded)
          Padding(
            padding: EdgeInsets.only(
              left: compactReasoning ? 6 : 8,
              bottom: compactReasoning ? 0 : 4,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SelectableText(
                  preview,
                  maxLines: compactReasoning
                      ? 3
                      : isReasoning
                      ? 6
                      : 12,
                  style: TextStyle(
                    color: colors.inkSoft,
                    fontFamily: isReasoning ? null : 'monospace',
                    fontSize: compactReasoning
                        ? 12
                        : isReasoning
                        ? 12.5
                        : 13,
                    height: compactReasoning ? 1.3 : 1.45,
                  ),
                ),
                if (isJson || preview.length < part.text.length)
                  TextButton(
                    key: ValueKey(prefix + '-full-' + suffix),
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => _MessageContentPage(part: part),
                      ),
                    ),
                    style: TextButton.styleFrom(
                      minimumSize: DshDisplayScope.targetSize(context),
                    ),
                    child: const Text('查看完整内容'),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}

String _messagePreview(String text, {int maxLines = 12}) {
  var end = text.length.clamp(0, 1200);
  var lines = 0;
  for (var i = 0; i < end; i++) {
    if (text.codeUnitAt(i) == 10 && ++lines == maxLines) {
      end = i;
      break;
    }
  }
  if (end > 0 &&
      end < text.length &&
      (text.codeUnitAt(end - 1) & 0xfc00) == 0xd800)
    end--;
  return text.substring(0, end);
}

class _MessageContentPage extends StatelessWidget {
  const _MessageContentPage({required this.part});
  final _MessagePart part;
  @override
  Widget build(BuildContext context) {
    final isReasoning = part.id!.startsWith('message-reasoning-');
    return Scaffold(
      appBar: AppBar(title: Text(part.label!)),
      body: ListView.builder(
        padding: const EdgeInsets.all(16),
        itemCount: (part.text.length / 1200).ceil(),
        itemBuilder: (context, index) {
          var start = index * 1200;
          var end = ((index + 1) * 1200).clamp(0, part.text.length);
          if (start > 0 && (part.text.codeUnitAt(start - 1) & 0xfc00) == 0xd800)
            start--;
          if (end < part.text.length &&
              (part.text.codeUnitAt(end - 1) & 0xfc00) == 0xd800)
            end--;
          return SelectableText(
            part.text.substring(start, end),
            style: TextStyle(
              fontFamily: isReasoning ? null : 'monospace',
              fontSize: 13,
              height: 1.5,
            ),
          );
        },
      ),
    );
  }
}

class _StreamingMark extends StatelessWidget {
  const _StreamingMark();

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 11,
      height: 11,
      child: CircularProgressIndicator(
        strokeWidth: 1.4,
        color: DshPalette.of(context).ice,
      ),
    );
  }
}

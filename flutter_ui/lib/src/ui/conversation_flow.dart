import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../state/message_actions.dart';
import '../theme/dsh_theme.dart';
import 'message_action_bar.dart';
import 'chat_shell.dart' show ChatLayout;
import 'execution_group.dart';
import 'tool_run_card.dart';
import 'turn_notice_card.dart';

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
  });

  final Object? conversationIdentity;
  final List<ChatMessage> messages;
  final List<ToolRun> tools;
  final List<Widget> leading, trailing;
  final Widget? empty;
  final bool hideDockContent;
  final Key listKey;

  /// Bubble actions for this transcript. Null renders the rows without them,
  /// which is what a read-only surface (a subagent view, a preview) wants.
  final MessageActionBindings? actions;

  /// The message currently open for editing, so exactly one bubble is marked.
  final String? editingMessageId;

  @override
  State<ConversationFlow> createState() => _ConversationFlowState();
}

class _ConversationFlowState extends State<ConversationFlow> {
  final Set<String> _expanded = {};

  @override
  void didUpdateWidget(covariant ConversationFlow oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.conversationIdentity != widget.conversationIdentity) {
      _expanded.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.messages.isEmpty &&
        widget.tools.isEmpty &&
        widget.leading.isEmpty &&
        widget.trailing.isEmpty &&
        widget.empty != null) {
      return widget.empty!;
    }
    final activePlanId = widget.hideDockContent
        ? widget.tools
              .where((t) => t.content?.steps.isNotEmpty ?? false)
              .lastOrNull
              ?.id
        : null;
    final ids = widget.messages.map((m) => m.id).toSet();
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
      } else {
        unbound.add(tool);
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
    void addGroup(String id, List<ToolRun> tools) {
      if (tools.isEmpty) return;
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
      if (_expanded.contains(id)) {
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
    }

    for (final message in widget.messages) {
      rows.add((
        key: ValueKey('message-' + message.id),
        build: (_) => ConversationMessage(
          message: message,
          actions: widget.actions,
          editing: widget.editingMessageId != null &&
              widget.editingMessageId == message.id,
        ),
      ));
      addGroup('message-' + message.id, byMessage[message.id] ?? const []);
    }
    if (unbound.isNotEmpty) addGroup('unbound-' + unbound.first.id, unbound);
    for (final child in widget.trailing) {
      rows.add((
        key: child.key == null ? null : ValueKey(('flow', child.key)),
        build: (_) => child,
      ));
    }
    final indices = <Key, int>{
      for (var i = 0; i < rows.length; i++)
        if (rows[i].key != null) rows[i].key!: i,
    };
    final gutter = ChatLayout.gutter(MediaQuery.sizeOf(context).width);
    return ListView.builder(
      key: widget.listKey,
      keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
      padding: EdgeInsets.fromLTRB(gutter, 8, gutter, 24),
      itemCount: rows.length,
      findChildIndexCallback: (key) => indices[key],
      itemBuilder: (context, index) => Center(
        key: rows[index].key,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
          child: SizedBox(
            width: double.infinity,
            child: rows[index].build(context),
          ),
        ),
      ),
    );
  }
}

class ConversationMessage extends StatelessWidget {
  const ConversationMessage({
    super.key,
    required this.message,
    this.actions,
    this.editing = false,
  });

  final ChatMessage message;
  final MessageActionBindings? actions;

  /// True for the message currently opened in the composer.
  final bool editing;

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
      child: SelectableText(
        message.text,
        // The text field owns tap handling for selection, so the edit request
        // rides on the field's own callback instead of a parent detector.
        onTap: canEdit ? () => bindings.edit(message) : null,
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
            child: bubble,
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
    final palette = DshPalette.of(context);
    final bindings = actions;
    final settled = bindings != null &&
        !message.isStreaming &&
        (message.messageId ?? '').isNotEmpty;
    return Padding(
      padding: const EdgeInsets.only(top: 22, bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SelectableText(
            message.text.isEmpty && message.isStreaming ? '…' : message.text,
            style: TextStyle(color: palette.ink, fontSize: 17, height: 1.7),
          ),
          if (message.isStreaming)
            const Padding(
              padding: EdgeInsets.only(top: 10),
              child: _StreamingMark(),
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
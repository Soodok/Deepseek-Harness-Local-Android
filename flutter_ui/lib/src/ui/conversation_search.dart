import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../theme/dsh_theme.dart';

/// A row-level hit. Only the loaded page is passed here; no widgets are built
/// to search a message, a folded reasoning block, or a tool summary.
class ConversationSearchHit {
  const ConversationSearchHit({required this.rowKey, required this.text});

  final ValueKey<String> rowKey;
  final String text;

  static RegExp? pattern(String query) {
    final literal = query.trim();
    return literal.isEmpty
        ? null
        : RegExp(RegExp.escape(literal), caseSensitive: false, unicode: true);
  }

  static ConversationSearchHit? forMessage(ChatMessage message, String query) {
    final text = [
      if (message.text.isNotEmpty) message.text,
      if (message.reasoning.isNotEmpty) message.reasoning,
    ].join('\n');
    return _match(ValueKey('message-' + message.id), text, query);
  }

  static ConversationSearchHit? forTool(ToolRun tool, String query) {
    final content = tool.content;
    // Full stdout/stderr, diffs, and resource payloads are intentionally not
    // indexed: this is summary search, not an eager full-output viewer.
    final text = [
      tool.name,
      tool.detail,
      if (content != null) ...[
        content.title,
        content.reasoning,
        if (content.command != null) content.command!.command,
        for (final edit in content.edits) edit.path,
        for (final step in content.steps) step.title,
        for (final agent in content.agents) agent.name + ' · ' + agent.task,
        for (final resource in content.resources) resource.title,
      ],
    ].where((part) => part.isNotEmpty).join('\n');
    return _match(ValueKey('row-tool-' + tool.id), text, query);
  }

  static ConversationSearchHit? _match(
    ValueKey<String> key,
    String text,
    String query,
  ) {
    final match = pattern(query)?.firstMatch(text);
    if (match == null) return null;
    // Keep the first match visible even when it lives deep in a folded block.
    final start = (match.start - 36).clamp(0, text.length);
    final end = (match.end + 64).clamp(start, text.length);
    return ConversationSearchHit(
      rowKey: key,
      text:
          (start > 0 ? '…' : '') +
          text.substring(start, end) +
          (end < text.length ? '…' : ''),
    );
  }
}

/// Search snippets preserve the original rich message/card, including its
/// copy actions and disclosure state, while exposing matches in folded text.
class ConversationSearchHighlight extends StatelessWidget {
  const ConversationSearchHighlight({
    super.key,
    required this.hit,
    required this.query,
    required this.active,
    required this.child,
  });

  final ConversationSearchHit? hit;
  final String query;
  final bool active;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final result = hit;
    final text = result?.text ?? '';
    final spans = <InlineSpan>[];
    var end = 0;
    for (final match
        in ConversationSearchHit.pattern(query)?.allMatches(text) ??
            const <RegExpMatch>[]) {
      if (end < match.start)
        spans.add(TextSpan(text: text.substring(end, match.start)));
      spans.add(
        TextSpan(
          text: text.substring(match.start, match.end),
          style: TextStyle(
            backgroundColor: colors.ice.withValues(alpha: .32),
            color: colors.ink,
            fontWeight: FontWeight.w700,
          ),
        ),
      );
      end = match.end;
    }
    if (end < text.length) spans.add(TextSpan(text: text.substring(end)));
    return Material(
      color: result == null
          ? Colors.transparent
          : colors.ice.withValues(alpha: active ? .10 : .04),
      shape: result == null
          ? null
          : RoundedRectangleBorder(
              side: BorderSide(
                color: colors.ice.withValues(alpha: active ? .8 : .25),
              ),
              borderRadius: BorderRadius.circular(12),
            ),
      child: Padding(
        padding: result == null ? EdgeInsets.zero : const EdgeInsets.all(6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (result != null)
              Semantics(
                key: ValueKey('search-match-' + result.rowKey.value),
                label: active ? '当前搜索命中' : '搜索命中',
                child: Text.rich(
                  TextSpan(children: spans),
                  style: TextStyle(
                    color: colors.inkSoft,
                    fontSize: 13,
                    height: 1.4,
                  ),
                ),
              ),
            KeyedSubtree(
              key: const ValueKey('search-original-row'),
              child: child,
            ),
          ],
        ),
      ),
    );
  }
}

class ConversationSearchBar extends StatelessWidget {
  const ConversationSearchBar({
    super.key,
    required this.controller,
    required this.loadedCount,
    required this.hitCount,
    required this.selectedIndex,
    required this.seeking,
    required this.onChanged,
    required this.onPrevious,
    required this.onNext,
    required this.onClose,
  });

  final TextEditingController controller;
  final int loadedCount, hitCount, selectedIndex;
  final bool seeking;
  final ValueChanged<String> onChanged;
  final VoidCallback onPrevious, onNext, onClose;

  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    return Material(
      color: colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: colors.line),
      ),
      child: Padding(
        padding: const EdgeInsets.only(left: 12, right: 4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Expanded(
                  child: TextField(
                    key: const ValueKey('conversation-search-input'),
                    controller: controller,
                    autofocus: true,
                    textInputAction: TextInputAction.search,
                    onChanged: onChanged,
                    onSubmitted: (_) => onNext(),
                    decoration: const InputDecoration(
                      hintText: '搜索消息、思考和工具摘要',
                      border: InputBorder.none,
                    ),
                  ),
                ),
                IconButton(
                  key: const ValueKey('conversation-search-close'),
                  constraints: const BoxConstraints(
                    minWidth: 48,
                    minHeight: 48,
                  ),
                  tooltip: '关闭搜索',
                  onPressed: onClose,
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
            Row(
              children: [
                Expanded(
                  child: Text(
                    '仅搜索当前加载的 $loadedCount 条消息',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: colors.inkSoft, fontSize: 11),
                  ),
                ),
                const SizedBox(width: 4),
                Semantics(
                  liveRegion: true,
                  child: Text(
                    hitCount == 0 ? '0/0' : '${selectedIndex + 1}/$hitCount',
                    style: TextStyle(color: colors.ink, fontSize: 12),
                  ),
                ),
                IconButton(
                  key: const ValueKey('conversation-search-previous'),
                  constraints: const BoxConstraints(
                    minWidth: 48,
                    minHeight: 48,
                  ),
                  tooltip: '上一条命中',
                  onPressed: hitCount == 0 ? null : onPrevious,
                  icon: const Icon(Icons.keyboard_arrow_up),
                ),
                IconButton(
                  key: const ValueKey('conversation-search-next'),
                  constraints: const BoxConstraints(
                    minWidth: 48,
                    minHeight: 48,
                  ),
                  tooltip: '下一条命中',
                  onPressed: hitCount == 0 ? null : onNext,
                  icon: const Icon(Icons.keyboard_arrow_down),
                ),
              ],
            ),
            if (seeking)
              LinearProgressIndicator(minHeight: 2, color: colors.ice),
          ],
        ),
      ),
    );
  }
}

class ConversationLatestButton extends StatelessWidget {
  const ConversationLatestButton({
    super.key,
    required this.hasNewMessages,
    required this.onPressed,
  });
  final bool hasNewMessages;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    return Material(
      color: colors.surfaceRaised,
      elevation: 3,
      shape: StadiumBorder(side: BorderSide(color: colors.line)),
      child: TextButton.icon(
        key: ValueKey(
          hasNewMessages
              ? 'conversation-new-messages'
              : 'conversation-scroll-bottom',
        ),
        onPressed: onPressed,
        style: TextButton.styleFrom(
          minimumSize: const Size(48, 48),
          foregroundColor: colors.ink,
          padding: const EdgeInsets.symmetric(horizontal: 16),
        ),
        icon: const Icon(Icons.arrow_downward_rounded, size: 18),
        label: Text(hasNewMessages ? '有新消息' : '回到底部'),
      ),
    );
  }
}

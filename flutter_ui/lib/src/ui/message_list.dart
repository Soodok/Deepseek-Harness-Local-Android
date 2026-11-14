import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../state/chat_controller.dart';
import '../theme/dsh_theme.dart';
import 'chat_shell.dart' show ChatLayout;
import 'tool_run_card.dart';
import 'execution_group.dart';

class MessageList extends StatefulWidget {
  const MessageList({super.key, required this.controller});

  final ChatController controller;
  @override
  State<MessageList> createState() => _MessageListState();
}

class _MessageListState extends State<MessageList> {
  final Set<String> _expanded = {};
  @override
  void didUpdateWidget(covariant MessageList oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) _expanded.clear();
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.controller.state;
    final width = MediaQuery.sizeOf(context).width;
    final gutter = ChatLayout.gutter(width);
    final children = <Widget>[];

    if (state.messages.isEmpty &&
        state.tools.isEmpty &&
        state.approval == null &&
        state.question == null &&
        !state.needsResync) {
      return const _EmptyConversation();
    }

    final messages = state.messages;
    final activePlanId = state.tools
        .where((t) => t.content?.steps.isNotEmpty ?? false)
        .lastOrNull
        ?.id;
    final byMessage = <String, List<ToolRun>>{};
    final unbound = <ToolRun>[];
    final ids = messages.map((m) => m.id).toSet();
    for (final tool in state.tools) {
      final content = tool.content;
      if (content != null &&
          (content.steps.isNotEmpty || content.agents.isNotEmpty) &&
          (content.steps.isEmpty || tool.id == activePlanId) &&
          tool.detail.isEmpty &&
          content.resources.isEmpty &&
          content.command == null &&
          content.edits.isEmpty &&
          content.reasoning.isEmpty)
        continue;
      if (tool.messageId != null && ids.contains(tool.messageId)) {
        byMessage.putIfAbsent(tool.messageId!, () => []).add(tool);
      } else {
        unbound.add(tool);
      }
    }
    Widget toolRow(ToolRun tool) => Padding(
      key: ValueKey('row-tool-' + tool.id),
      padding: const EdgeInsets.only(left: 8),
      child: DecoratedBox(
        decoration: const BoxDecoration(
          border: Border(left: BorderSide(color: DshPalette.line)),
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
    );
    void addGroup(String id, List<ToolRun> tools) {
      if (tools.isEmpty) return;
      children.add(
        ExecutionGroupHeader(
          key: ValueKey('execution-group-' + id),
          groupId: id,
          tools: tools,
          expanded: _expanded.contains(id),
          onToggle: () => setState(() {
            if (!_expanded.remove(id)) _expanded.add(id);
          }),
        ),
      );
      if (_expanded.contains(id)) children.addAll(tools.map(toolRow));
    }

    for (final message in messages) {
      children.add(
        _MessageBlock(key: ValueKey('message-' + message.id), message: message),
      );
      addGroup(
        'message-' + message.id,
        byMessage[message.id] ?? const <ToolRun>[],
      );
    }
    if (unbound.isNotEmpty) addGroup('unbound-' + unbound.first.id, unbound);

    if (state.needsResync) {
      children.add(
        const Padding(
          padding: EdgeInsets.only(top: 16),
          child: Text(
            '正在重新同步消息…',
            style: TextStyle(color: DshPalette.inkSoft, fontSize: 13),
          ),
        ),
      );
    }

    final indices = <Key, int>{
      for (var i = 0; i < children.length; i++)
        if (children[i].key != null) children[i].key!: i,
    };
    return ListView.builder(
      key: const ValueKey('conversation-list'),
      padding: EdgeInsets.fromLTRB(gutter, 8, gutter, 24),
      itemCount: children.length,
      findChildIndexCallback: (key) => indices[key],
      itemBuilder: (context, index) => Center(
        key: children[index].key,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
          child: SizedBox(width: double.infinity, child: children[index]),
        ),
      ),
    );
  }
}

class _EmptyConversation extends StatelessWidget {
  const _EmptyConversation();
  @override
  Widget build(BuildContext context) => const Center(
    child: Padding(
      padding: EdgeInsets.all(28),
      child: Text(
        '今天想聊些什么？',
        textAlign: TextAlign.center,
        style: TextStyle(
          fontSize: 28,
          height: 1.4,
          fontWeight: FontWeight.w400,
          color: DshPalette.ink,
        ),
      ),
    ),
  );
}

class _MessageBlock extends StatelessWidget {
  const _MessageBlock({super.key, required this.message});
  final ChatMessage message;
  @override
  Widget build(BuildContext context) {
    if (message.role == ChatRole.user) {
      return Align(
        alignment: Alignment.centerRight,
        child: Container(
          constraints: const BoxConstraints(maxWidth: 500),
          margin: const EdgeInsets.only(top: 18, bottom: 6, left: 32),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
          decoration: BoxDecoration(
            color: DshPalette.userWash,
            borderRadius: BorderRadius.circular(20),
          ),
          child: SelectableText(
            message.text,
            style: const TextStyle(
              color: DshPalette.ink,
              fontSize: 16.5,
              height: 1.55,
            ),
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: 22, bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SelectableText(
            message.text.isEmpty && message.isStreaming ? '…' : message.text,
            style: const TextStyle(
              color: DshPalette.ink,
              fontSize: 17,
              height: 1.7,
            ),
          ),
          if (message.isStreaming)
            const Padding(
              padding: EdgeInsets.only(top: 10),
              child: _StreamingMark(),
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
    return const SizedBox(
      width: 11,
      height: 11,
      child: CircularProgressIndicator(strokeWidth: 1.4, color: DshPalette.ice),
    );
  }
}

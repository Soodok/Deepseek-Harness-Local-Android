import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../state/chat_controller.dart';
import '../theme/dsh_theme.dart';
import 'action_cards.dart';
import 'chat_shell.dart' show ChatLayout;
import 'tool_run_card.dart';

class MessageList extends StatelessWidget {
  const MessageList({super.key, required this.controller});

  final ChatController controller;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
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

    // ChatState keeps tools and messages in separate lists with no link
    // between them. Reading order heuristic: when the latest turn already has
    // an acknowledgement and a later reply, the process sits between them;
    // otherwise it follows the conversation.
    final messages = state.messages;
    final lastUser = messages.lastIndexWhere((m) => m.role == ChatRole.user);
    final replies = <int>[
      for (var i = lastUser + 1; i < messages.length; i++)
        if (messages[i].role == ChatRole.assistant) i,
    ];
    final processAfter = state.tools.isEmpty
        ? -2
        : (lastUser >= 0 && replies.length >= 2
              ? replies.first
              : messages.length - 1);
    final process = _ProcessSection(tools: state.tools);
    if (processAfter == -1) children.add(process);
    for (var i = 0; i < messages.length; i++) {
      children.add(_MessageBlock(message: messages[i]));
      if (i == processAfter) children.add(process);
    }

    if (state.approval != null) {
      children.add(
        ApprovalCard(
          request: state.approval!,
          onApprove: () => controller.approve(state.approval!.requestId),
          onStop: controller.stop,
        ),
      );
    }
    if (state.question != null) {
      children.add(
        QuestionCard(
          request: state.question!,
          onSubmit: (answer) =>
              controller.answer(state.question!.requestId, answer),
        ),
      );
    }
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

    return ListView(
      padding: EdgeInsets.fromLTRB(gutter, 8, gutter, 24),
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: children,
            ),
          ),
        ),
      ],
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
  const _MessageBlock({required this.message});
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

/// Tool activity is one quiet section under the conversation, not a stack of
/// independent cards fighting the prose for attention.
class _ProcessSection extends StatelessWidget {
  const _ProcessSection({required this.tools});

  final List<ToolRun> tools;

  @override
  Widget build(BuildContext context) {
    final running = tools.where((t) => t.status == ToolStatus.running).length;
    final heading = running > 0
        ? '进行中 · ' + running.toString() + ' / ' + tools.length.toString()
        : '过程 · ' + tools.length.toString() + ' 步';
    return Padding(
      padding: const EdgeInsets.only(top: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 2, bottom: 6),
            child: Text(
              heading,
              style: const TextStyle(
                color: DshPalette.inkFaint,
                fontSize: 11.5,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.6,
              ),
            ),
          ),
          for (final tool in tools)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: ToolRunCard(tool: tool),
            ),
        ],
      ),
    );
  }
}

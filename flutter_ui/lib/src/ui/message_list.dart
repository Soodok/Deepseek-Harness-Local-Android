import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../state/chat_controller.dart';

import 'action_cards.dart';
import 'tool_run_card.dart';

class MessageList extends StatelessWidget {
  const MessageList({super.key, required this.controller});

  final ChatController controller;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final children = <Widget>[];
    if (state.messages.isEmpty) {
      children.add(const _EmptyConversation());
    } else {
      for (final message in state.messages) {
        children.add(_MessageBubble(message: message));
      }
    }
    for (final tool in state.tools) {
      children.add(ToolRunCard(tool: tool));
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
    if (state.queuedPrompts.isNotEmpty) {
      children.add(
        Card(
          key: const ValueKey('queue-card'),
          margin: const EdgeInsets.only(top: 14),
          child: ListTile(
            leading: const Icon(Icons.schedule_outlined),
            title: Text('排队中 ' + state.queuedPrompts.length.toString() + ' 条'),
            subtitle: Text(state.queuedPrompts.first),
            trailing: TextButton(
              onPressed: controller.canDispatchQueued
                  ? controller.sendNextQueued
                  : null,
              child: const Text('发送下一条'),
            ),
          ),
        ),
      );
    }
    if (state.needsResync) {
      children.add(
        const Padding(
          padding: EdgeInsets.only(top: 14),
          child: Text('消息出现缺口，正在重新同步…'),
        ),
      );
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 18, 18, 24),
      children: children,
    );
  }
}

class _EmptyConversation extends StatelessWidget {
  const _EmptyConversation();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 80, horizontal: 22),
      child: Column(
        children: [
          Icon(
            Icons.auto_awesome,
            size: 42,
            color: Theme.of(context).colorScheme.primary,
          ),
          const SizedBox(height: 18),
          const Text(
            '从一个具体问题开始',
            style: TextStyle(fontWeight: FontWeight.w700, fontSize: 20),
          ),
          const SizedBox(height: 8),
          const Text(
            'DSH 会把思考、工具和结果放在同一条可追踪的执行流里。',
            textAlign: TextAlign.center,
            style: TextStyle(color: Color(0xFF757180), height: 1.5),
          ),
        ],
      ),
    );
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message});

  final ChatMessage message;

  @override
  Widget build(BuildContext context) {
    final isUser = message.role == ChatRole.user;
    final body = SelectableText(
      message.text,
      style: TextStyle(
        color: isUser ? const Color(0xFF302C54) : const Color(0xFF20212B),
        height: 1.55,
        fontSize: 15,
      ),
    );
    if (isUser) {
      return Align(
        alignment: Alignment.centerRight,
        child: Container(
          constraints: const BoxConstraints(maxWidth: 620),
          margin: const EdgeInsets.only(bottom: 16, left: 42),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: const Color(0xFFEAE7FC),
            borderRadius: BorderRadius.circular(18),
          ),
          child: body,
        ),
      );
    }
    return Container(
      margin: const EdgeInsets.only(bottom: 18, right: 24),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 28,
            height: 28,
            margin: const EdgeInsets.only(top: 2, right: 10),
            decoration: BoxDecoration(
              color: const Color(0xFF6557D8),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Icon(
              Icons.auto_awesome,
              size: 16,
              color: Colors.white,
            ),
          ),
          Expanded(child: body),
        ],
      ),
    );
  }
}

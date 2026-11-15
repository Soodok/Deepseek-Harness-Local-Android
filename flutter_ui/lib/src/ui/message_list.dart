import 'package:material_ui/material_ui.dart';

import '../state/chat_controller.dart';
import '../theme/dsh_theme.dart';
import 'conversation_flow.dart';

class MessageList extends StatelessWidget {
  const MessageList({super.key, required this.controller});
  final ChatController controller;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    return ConversationFlow(
      conversationIdentity: controller,
      messages: state.messages,
      tools: state.tools,
      hideDockContent: true,
      empty:
          state.approval == null && state.question == null && !state.needsResync
          ? const _EmptyConversation()
          : null,
      trailing: [
        if (state.needsResync)
          Padding(
            padding: const EdgeInsets.only(top: 16),
            child: Text(
              '正在重新同步消息…',
              style: TextStyle(
                color: DshPalette.of(context).inkSoft,
                fontSize: 13,
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
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: EdgeInsets.all(28),
      child: Text(
        '今天想聊些什么？',
        textAlign: TextAlign.center,
        style: TextStyle(
          fontSize: 28,
          height: 1.4,
          fontWeight: FontWeight.w400,
          color: DshPalette.of(context).ink,
        ),
      ),
    ),
  );
}

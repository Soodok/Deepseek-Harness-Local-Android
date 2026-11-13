import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../state/chat_controller.dart';

class Composer extends StatefulWidget {
  const Composer({super.key, required this.controller});

  final ChatController controller;

  @override
  State<Composer> createState() => _ComposerState();
}

class _ComposerState extends State<Composer> {
  late final TextEditingController _textController;
  late final FocusNode _focusNode;

  @override
  void initState() {
    super.initState();
    _textController = TextEditingController();
    _focusNode = FocusNode();
  }

  @override
  void dispose() {
    _textController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _send() {
    final value = _textController.text;
    if (value.trim().isEmpty) return;
    _textController.clear();
    widget.controller.sendPrompt(value);
    _focusNode.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final status = widget.controller.state.status;
        final isBusy =
            status == ChatStatus.streaming ||
            status == ChatStatus.waitingAction;
        final canSend = widget.controller.canSend;
        return Container(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 14),
          decoration: const BoxDecoration(
            color: Color(0xFFF8F8FB),
            border: Border(top: BorderSide(color: Color(0xFFE9E7F0))),
          ),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: TextField(
                    key: const ValueKey('composer-input'),
                    controller: _textController,
                    focusNode: _focusNode,
                    enabled: canSend,
                    minLines: 1,
                    maxLines: 5,
                    textInputAction: TextInputAction.newline,
                    decoration: InputDecoration(
                      hintText: status == ChatStatus.connecting
                          ? '正在连接引擎…'
                          : '描述你想完成的事情',
                      prefixIcon: const Icon(Icons.edit_outlined, size: 20),
                    ),
                    onSubmitted: (_) => _send(),
                  ),
                ),
                const SizedBox(width: 8),
                if (isBusy)
                  IconButton.filledTonal(
                    key: const ValueKey('composer-stop'),
                    tooltip: '停止执行',
                    onPressed: widget.controller.stop,
                    icon: const Icon(Icons.stop_rounded),
                  ),
                IconButton.filled(
                  key: const ValueKey('composer-send'),
                  tooltip: isBusy ? '排队或插话' : '发送',
                  onPressed: canSend ? _send : null,
                  icon: const Icon(Icons.arrow_upward),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

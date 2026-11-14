import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../model/composer_model.dart';
import '../state/chat_controller.dart';
import '../bridge/attachment_picker.dart';
import '../theme/dsh_theme.dart';
import 'chat_shell.dart' show ChatLayout;
import 'composer_sheets.dart';
import 'glass_panel.dart';

class Composer extends StatefulWidget {
  const Composer({super.key, required this.controller});
  final ChatController controller;
  @override
  State<Composer> createState() => _ComposerState();
}

class _ComposerState extends State<Composer> {
  final _textController = TextEditingController();
  final _focusNode = FocusNode();
  bool _picking = false;
  bool _sending = false;
  @override
  void dispose() {
    _textController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final value = _textController.text;
    if (_sending ||
        !widget.controller.canSend ||
        (value.trim().isEmpty && widget.controller.attachments.isEmpty))
      return;
    setState(() => _sending = true);
    final sent = await widget.controller.sendPrompt(value);
    if (!mounted) return;
    if (sent && _textController.text == value) _textController.clear();
    setState(() => _sending = false);
    _focusNode.requestFocus();
  }

  Future<void> _pick() async {
    if (_picking) return;
    setState(() => _picking = true);
    try {
      final current = widget.controller.attachments;
      final files = await AttachmentPicker.pick(
        remainingFiles: ChatController.maxFiles - current.length,
        remainingBytes: AttachmentPicker.remainingBytes(current),
      );
      if (mounted) widget.controller.addAttachments(files);
    } on Object catch (error) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('文件未添加：' + error.toString())));
    } finally {
      if (mounted) setState(() => _picking = false);
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final controller = widget.controller;
      final state = controller.state;
      final busy =
          state.status == ChatStatus.streaming ||
          state.status == ChatStatus.waitingAction;
      final gutter = ChatLayout.gutter(MediaQuery.sizeOf(context).width);
      return Padding(
        padding: EdgeInsets.fromLTRB(gutter - 2, 8, gutter - 2, 12),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (state.queuedPrompts.isNotEmpty)
                  _QueueTray(
                    prompts: state.queuedPrompts,
                    onSend: controller.canDispatchQueued
                        ? controller.sendNextQueued
                        : null,
                  ),
                GlassPanel(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(14, 12, 8, 8),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        if (controller.attachments.isNotEmpty)
                          ConstrainedBox(
                            constraints: const BoxConstraints(maxHeight: 130),
                            child: SingleChildScrollView(
                              child: Wrap(
                                spacing: 6,
                                runSpacing: 6,
                                children: [
                                  for (final file in controller.attachments)
                                    Container(
                                      key: ValueKey('attachment-' + file.id),
                                      constraints: const BoxConstraints(
                                        maxWidth: 250,
                                      ),
                                      decoration: BoxDecoration(
                                        color: DshPalette.paperDeep.withValues(
                                          alpha: .65,
                                        ),
                                        borderRadius: BorderRadius.circular(14),
                                        border: Border.all(
                                          color: DshPalette.line,
                                        ),
                                      ),
                                      child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          const Padding(
                                            padding: EdgeInsets.only(left: 10),
                                            child: Icon(
                                              Icons.insert_drive_file_outlined,
                                              size: 18,
                                            ),
                                          ),
                                          const SizedBox(width: 6),
                                          Flexible(
                                            child: Tooltip(
                                              message: file.name,
                                              child: Text(
                                                file.name,
                                                maxLines: 1,
                                                overflow: TextOverflow.ellipsis,
                                                style: const TextStyle(
                                                  fontSize: 13,
                                                ),
                                              ),
                                            ),
                                          ),
                                          IconButton(
                                            key: ValueKey(
                                              'attachment-remove-' + file.id,
                                            ),
                                            tooltip: '移除 ' + file.name,
                                            onPressed: () => controller
                                                .removeAttachment(file.id),
                                            icon: const Icon(
                                              Icons.close_rounded,
                                              size: 16,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                ],
                              ),
                            ),
                          ),
                        TextField(
                          key: const ValueKey('composer-input'),
                          controller: _textController,
                          focusNode: _focusNode,
                          enabled: true,
                          minLines: 1,
                          maxLines: 6,
                          cursorColor: DshPalette.ink,
                          style: const TextStyle(
                            fontSize: 17,
                            height: 1.5,
                            color: DshPalette.ink,
                          ),
                          decoration: const InputDecoration(
                            hintText: '给 DSH 发消息',
                            hintStyle: TextStyle(color: DshPalette.inkFaint),
                            border: InputBorder.none,
                            enabledBorder: InputBorder.none,
                            focusedBorder: InputBorder.none,
                            disabledBorder: InputBorder.none,
                            filled: false,
                            isDense: true,
                            contentPadding: EdgeInsets.fromLTRB(4, 4, 4, 4),
                          ),
                          onSubmitted: (_) => _send(),
                        ),
                        const SizedBox(height: 14),
                        Row(
                          children: [
                            IconButton(
                              key: const ValueKey('composer-attach'),
                              tooltip: _picking ? '正在选择文件' : '添加文件',
                              onPressed:
                                  _picking ||
                                      !controller.supportsComposerOptions
                                  ? null
                                  : _pick,
                              style: IconButton.styleFrom(
                                backgroundColor: DshPalette.ink.withValues(
                                  alpha: .06,
                                ),
                              ),
                              icon: Icon(
                                _picking
                                    ? Icons.more_horiz_rounded
                                    : Icons.add_rounded,
                                size: 27,
                              ),
                            ),
                            Expanded(
                              child: TextButton(
                                key: const ValueKey('composer-model'),
                                onPressed: () =>
                                    showModelSelector(context, controller),
                                style: TextButton.styleFrom(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 8,
                                  ),
                                  minimumSize: const Size(0, 48),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Flexible(
                                      child: Text(
                                        controller.modelLabel,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: const TextStyle(
                                          fontSize: 13,
                                          color: DshPalette.ink,
                                          fontWeight: FontWeight.w500,
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 3),
                                    const Icon(
                                      Icons.expand_more_rounded,
                                      size: 16,
                                      color: DshPalette.inkSoft,
                                    ),
                                  ],
                                ),
                              ),
                            ),
                            IconButton(
                              key: const ValueKey('composer-permission'),
                              tooltip: '工具权限：' + controller.permission.label,
                              onPressed: () =>
                                  showPermissionSelector(context, controller),
                              style: IconButton.styleFrom(
                                backgroundColor: DshPalette.ink.withValues(
                                  alpha: .06,
                                ),
                              ),
                              icon: Icon(
                                controller.permission ==
                                        ComposerPermission.readOnly
                                    ? Icons.lock_outline_rounded
                                    : Icons.tune_rounded,
                                size: 22,
                              ),
                            ),
                            if (busy)
                              IconButton(
                                key: const ValueKey('composer-stop'),
                                tooltip: '停止执行',
                                onPressed: controller.stop,
                                icon: const Icon(
                                  Icons.stop_circle_outlined,
                                  size: 28,
                                ),
                              ),
                            ValueListenableBuilder<TextEditingValue>(
                              valueListenable: _textController,
                              builder: (context, value, _) => IconButton.filled(
                                key: const ValueKey('composer-send'),
                                tooltip: busy ? '排队到下一轮' : '发送',
                                style: IconButton.styleFrom(
                                  backgroundColor: DshPalette.ink,
                                  foregroundColor: DshPalette.paper,
                                  disabledBackgroundColor:
                                      DshPalette.surfaceRaised,
                                  disabledForegroundColor: DshPalette.inkFaint,
                                  minimumSize: const Size(48, 48),
                                ),
                                onPressed:
                                    !_sending &&
                                        controller.canSend &&
                                        (value.text.trim().isNotEmpty ||
                                            controller.attachments.isNotEmpty)
                                    ? _send
                                    : null,
                                icon: const Icon(
                                  Icons.arrow_upward_rounded,
                                  size: 26,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

class _QueueTray extends StatelessWidget {
  const _QueueTray({required this.prompts, this.onSend});

  final List<String> prompts;
  final VoidCallback? onSend;

  @override
  Widget build(BuildContext context) {
    final visible = prompts.take(2).toList();
    final hidden = prompts.length - visible.length;
    return Container(
      key: const ValueKey('queue-card'),
      margin: const EdgeInsets.fromLTRB(14, 0, 14, 0),
      padding: const EdgeInsets.fromLTRB(14, 8, 4, 8),
      decoration: const BoxDecoration(
        color: DshPalette.paperDeep,
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
        border: Border(
          top: BorderSide(color: DshPalette.line),
          left: BorderSide(color: DshPalette.line),
          right: BorderSide(color: DshPalette.line),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '排队中 ' + prompts.length.toString() + ' 条',
                  style: const TextStyle(
                    color: DshPalette.inkSoft,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0.2,
                  ),
                ),
                const SizedBox(height: 3),
                for (final prompt in visible)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(
                      prompt,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: DshPalette.ink,
                        fontSize: 13.5,
                        height: 1.35,
                      ),
                    ),
                  ),
                if (hidden > 0)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(
                      '另有 ' + hidden.toString() + ' 条',
                      style: const TextStyle(
                        color: DshPalette.inkFaint,
                        fontSize: 12,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          Tooltip(
            message: onSend == null ? '当前轮结束后可发送' : '立即发送队首消息',
            child: TextButton(
              onPressed: onSend,
              style: TextButton.styleFrom(minimumSize: const Size(64, 48)),
              child: const Text('发送下一条'),
            ),
          ),
        ],
      ),
    );
  }
}

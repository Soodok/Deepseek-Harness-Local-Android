import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../model/composer_model.dart';
import '../model/model_display.dart';
import '../state/chat_controller.dart';
import '../state/composer_draft.dart';
import '../bridge/attachment_picker.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import 'chat_strings.dart';
import 'composer_surface.dart';
import 'composer_sheets.dart';
import 'interaction_haptics.dart';
import 'composer_dock.dart';
import 'permission_icon.dart';

class Composer extends StatefulWidget {
  const Composer({
    super.key,
    required this.controller,
    this.availableHeight,
    this.draft,
    this.editing = false,
    this.onCancelEdit,
    this.onSubmitEdit,
    this.onEditChanged,
    this.editText,
    this.inlineApproval = false,
  });
  final ChatController controller;
  final double? availableHeight;

  /// The shell-owned draft. When given, the field is a view of it: an action
  /// that fills the composer (retract, edit, restore) reaches this field through
  /// the draft's revision, and typing writes back without a round trip.
  final ComposerDraft? draft;

  /// True while an existing message is being edited; the input shows the
  /// cancel affordance instead of pretending this is a fresh prompt.
  final bool editing;
  final VoidCallback? onCancelEdit;
  final Future<void> Function(String text)? onSubmitEdit;
  final ValueChanged<String>? onEditChanged;
  final String? editText;
  final bool inlineApproval;
  @override
  State<Composer> createState() => _ComposerState();
}

class _ComposerState extends State<Composer> {
  final _textController = TextEditingController();
  final _focusNode = FocusNode();
  bool _picking = false;
  bool _sending = false;

  /// The draft revision this field has already adopted. An action that writes
  /// the draft bumps it; until the field adopts that revision, text typed in
  /// between must not be overwritten.
  int _adoptedRevision = 0;

  @override
  void initState() {
    super.initState();
    widget.draft?.addListener(_adoptDraft);
    _adoptDraft();
  }

  @override
  void didUpdateWidget(covariant Composer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.draft != widget.draft) {
      oldWidget.draft?.removeListener(_adoptDraft);
      widget.draft?.addListener(_adoptDraft);
    }
    if (oldWidget.draft != widget.draft ||
        oldWidget.editing != widget.editing ||
        oldWidget.editText != widget.editText) {
      _adoptDraft();
    }
    if (!oldWidget.editing && widget.editing) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && widget.editing) _focusNode.requestFocus();
      });
    }
  }

  void _adoptDraft() {
    final target = widget.editing
        ? (widget.editText ?? '')
        : (widget.draft?.text ?? '');
    final revision = widget.draft?.revision ?? 0;
    if (revision == _adoptedRevision && _textController.text == target) return;
    _adoptedRevision = revision;
    if (_textController.text == target) return;
    _textController.value = TextEditingValue(
      text: target,
      selection: TextSelection.collapsed(offset: target.length),
    );
  }

  @override
  void dispose() {
    widget.draft?.removeListener(_adoptDraft);
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
    var accepted = false;
    try {
      if (widget.editing) {
        await widget.onSubmitEdit?.call(value);
        return;
      }
      final sent = await widget.controller.sendPrompt(value);
      if (!mounted) return;
      // Only a submission the controller itself accepted, queued ones included,
      // gets a tick: a refused, empty, repeated or failed send returns false and
      // stays silent. The button and the keyboard both arrive here.
      if (sent) InteractionHaptics.sendAccepted();
      accepted = sent;
      if (sent && _textController.text == value) {
        final draft = widget.draft;
        if (draft != null) {
          draft.clear();
        } else {
          _textController.clear();
        }
      }
      if (sent && _focusNode.hasFocus) _focusNode.unfocus();
    } finally {
      if (mounted) {
        setState(() => _sending = false);
        if (!accepted || widget.editing) _focusNode.requestFocus();
      }
    }
  }

  Future<void> _stop() async {
    if (!widget.controller.canStop) return;
    await widget.controller.stop();
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
  Widget build(BuildContext context) => LayoutBuilder(builder: _buildComposer);

  Widget _buildComposer(
    BuildContext context,
    BoxConstraints constraints,
  ) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final controller = widget.controller;
      final state = controller.state;
      final busy =
          state.status == ChatStatus.streaming ||
          state.status == ChatStatus.waitingAction;
      // Keep the glass shell and controls visible; the TextField scrolls
      // its own content once its line budget is reached.
      final viewportHeight = math.min(
        constraints.maxHeight,
        (widget.availableHeight ?? MediaQuery.sizeOf(context).height) * .75,
      );
      final controlsHeight = DshDisplayScope.minTargetOf(context);
      final lineHeight = MediaQuery.textScalerOf(context).scale(17) * 1.5;
      final maxInputLines =
          ((viewportHeight - controlsHeight - 48) / lineHeight).floor().clamp(
            1,
            6,
          );
      return ComposerViewport(
        availableHeight: widget.availableHeight,
        scrollKey: const ValueKey('composer-scroll'),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ComposerDock(
              controller: controller,
              availableHeight: widget.availableHeight,
              showApproval: !widget.inlineApproval,
            ),
            const SizedBox(height: 6),
            if (state.queuedPrompts.isNotEmpty)
              _QueueTray(
                prompts: state.queuedPrompts,
                error: controller.queuedDispatchError,
                onSend: controller.canDispatchQueued
                    ? controller.sendNextQueued
                    : null,
              ),
            ComposerSurface(
              maxInputLines: maxInputLines,
              inputTrailing: widget.editing
                  ? _EditingBar(
                      key: const ValueKey('composer-editing'),
                      onCancel: widget.onCancelEdit,
                    )
                  : null,
              textController: _textController,
              focusNode: _focusNode,
              inputKey: const ValueKey('composer-input'),
              hintText: widget.editing
                  ? DshChatStrings.of(context).editHint
                  : DshChatStrings.of(context).promptHint,
              onSubmitted: _send,
              onChanged: (value) {
                if (widget.editing) {
                  widget.onEditChanged?.call(value);
                } else {
                  widget.draft?.record(value);
                }
              },
              leading: [
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
                              constraints: const BoxConstraints(maxWidth: 250),
                              decoration: BoxDecoration(
                                color: DshPalette.of(context).paperDeep
                                    .withValues(alpha: .65),
                                borderRadius: BorderRadius.circular(14),
                                border: Border.all(
                                  color: DshPalette.of(context).line,
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
                                        style: const TextStyle(fontSize: 13),
                                      ),
                                    ),
                                  ),
                                  IconButton(
                                    key: ValueKey(
                                      'attachment-remove-' + file.id,
                                    ),
                                    tooltip: '移除 ' + file.name,
                                    onPressed: () =>
                                        controller.removeAttachment(file.id),
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
              ],
              controls: [
                IconButton(
                  key: const ValueKey('composer-attach'),
                  tooltip: !controller.supportsAttachments
                      ? '当前引擎尚未接入附件'
                      : (_picking ? '正在选择文件' : '添加文件'),
                  onPressed: _picking || !controller.supportsAttachments
                      ? null
                      : _pick,
                  style: IconButton.styleFrom(
                    backgroundColor: Colors.transparent,
                    padding: EdgeInsets.zero,
                    minimumSize: DshDisplayScope.targetSize(context),
                  ),
                  icon: Container(
                    width: 34,
                    height: 34,
                    decoration: BoxDecoration(
                      color: DshPalette.of(context).ink.withValues(alpha: .06),
                      shape: BoxShape.circle,
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      _picking ? Icons.more_horiz_rounded : Icons.add_rounded,
                      size: 20,
                    ),
                  ),
                ),
                const SizedBox(width: 2),
                Expanded(
                  child: ComposerModelPill(
                    pillKey: const ValueKey('composer-model'),
                    // The capsule shows a shortened brand token; the full
                    // name stays available as the semantic/tooltip label and
                    // the identifier behind the selection is unchanged.
                    label: displayModelLabel(controller.modelLabel),
                    semanticLabel: controller.modelLabel,
                    onPressed: () => showModelSelector(context, controller),
                  ),
                ),
                const SizedBox(width: 4),
                IconButton(
                  key: const ValueKey('composer-permission'),
                  tooltip: DshChatStrings.of(context)
                      .toolPermission(controller.permissionLabel),
                  onPressed: () => showPermissionSelector(context, controller),
                  style: IconButton.styleFrom(
                    backgroundColor: Colors.transparent,
                    padding: EdgeInsets.zero,
                    minimumSize: DshDisplayScope.targetSize(context),
                  ),
                  icon: Container(
                    width: 38,
                    height: 38,
                    decoration: BoxDecoration(
                      color: DshPalette.of(context).ink.withValues(alpha: .06),
                      shape: BoxShape.circle,
                    ),
                    alignment: Alignment.center,
                    child: !controller.permissionKnown
                        ? const Icon(Icons.help_outline, size: 20)
                        : DshPermissionIcon(
                            glyph: switch (controller.permission) {
                              ComposerPermission.readOnly =>
                                DshPermissionGlyph.readOnly,
                              ComposerPermission.workspaceWrite =>
                                DshPermissionGlyph.workspaceWrite,
                              ComposerPermission.fullAccess =>
                                DshPermissionGlyph.fullAccess,
                            },
                            size: 20,
                          ),
                  ),
                ),
                ValueListenableBuilder<TextEditingValue>(
                  valueListenable: _textController,
                  builder: (context, value, _) {
                    final hasContent =
                        value.text.trim().isNotEmpty ||
                        controller.attachments.isNotEmpty;
                    return ComposerActionButton(
                      sendKey: const ValueKey('composer-send'),
                      stopKey: const ValueKey('composer-stop'),
                      running: busy,
                      canStop: controller.canStop && !_sending,
                      stopInFlight: controller.stopInFlight,
                      canSend: controller.canSend && !_sending,
                      sendInFlight: _sending && !widget.editing,
                      hasContent: hasContent,
                      onSend: _send,
                      onStop: _stop,
                    );
                  },
                ),
              ],
            ),
          ],
        ),
      );
    },
  );
}

class _QueueTray extends StatelessWidget {
  const _QueueTray({required this.prompts, this.onSend, this.error});

  final List<String> prompts;
  final VoidCallback? onSend;
  final String? error;

  @override
  Widget build(BuildContext context) {
    final visible = prompts.take(2).toList();
    final hidden = prompts.length - visible.length;
    return Container(
      key: const ValueKey('queue-card'),
      margin: const EdgeInsets.fromLTRB(14, 0, 14, 0),
      padding: const EdgeInsets.fromLTRB(14, 8, 4, 8),
      decoration: BoxDecoration(
        color: DshPalette.of(context).paperDeep,
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
        border: Border(
          top: BorderSide(color: DshPalette.of(context).line),
          left: BorderSide(color: DshPalette.of(context).line),
          right: BorderSide(color: DshPalette.of(context).line),
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
                  style: TextStyle(
                    color: DshPalette.of(context).inkSoft,
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
                      style: TextStyle(
                        color: DshPalette.of(context).ink,
                        fontSize: 13.5,
                        height: 1.35,
                      ),
                    ),
                  ),
                if (error != null)
                  Text(
                    error!,
                    style: TextStyle(
                      color: DshPalette.of(context).bad,
                      fontSize: 12,
                    ),
                  ),
                if (hidden > 0)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(
                      '另有 ' + hidden.toString() + ' 条',
                      style: TextStyle(
                        color: DshPalette.of(context).inkFaint,
                        fontSize: 12,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          Tooltip(
            message: error ?? (onSend == null ? '当前轮结束后可发送' : '立即发送队首消息'),
            child: TextButton(
              onPressed: onSend,
              style: TextButton.styleFrom(
                minimumSize: DshDisplayScope.targetSize(context, width: 64),
              ),
              child: const Text('发送下一条'),
            ),
          ),
        ],
      ),
    );
  }
}

class _EditingBar extends StatelessWidget {
  const _EditingBar({super.key, required this.onCancel});

  final VoidCallback? onCancel;

  @override
  Widget build(BuildContext context) => IconButton(
    key: const ValueKey('composer-cancel-edit'),
    tooltip: '取消编辑',
    onPressed: onCancel,
    style: IconButton.styleFrom(
      minimumSize: DshDisplayScope.targetSize(context),
      padding: EdgeInsets.zero,
    ),
    icon: Icon(
      Icons.close_rounded,
      size: 18,
      color: DshPalette.of(context).inkSoft,
    ),
  );
}

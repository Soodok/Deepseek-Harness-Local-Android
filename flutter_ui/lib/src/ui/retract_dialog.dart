import 'package:material_ui/material_ui.dart';

import '../state/turn_plan.dart';
import '../theme/dsh_theme.dart';

/// What a retraction or an edited send should do, chosen by the person.
class RetractChoice {
  const RetractChoice({required this.includeFiles, required this.replaceDraft});

  /// Also restore the files those Turns changed.
  final bool includeFiles;

  /// Replace the current draft instead of keeping it and appending the text.
  final bool replaceDraft;
}

/// Ask before a retraction changes anything.
///
/// Nothing is assumed on the person's behalf: whether files are rolled back and
/// what happens to a draft already in the composer are both explicit choices,
/// and cancelling leaves the conversation untouched.
/// @param context - build context for the dialog.
/// @param plan - the Host's recorded changes for the addressed Turn.
/// @param hasDraft - whether the composer already holds text.
/// @param title - one line naming what is about to happen.
/// @param confirmLabel - label of the confirming action.
/// @returns the choice, or null when the person cancelled.
Future<RetractChoice?> showRetractDialog(
  BuildContext context, {
  required TurnPlan plan,
  required bool hasDraft,
  String title = '撤回这条消息？',
  String confirmLabel = '撤回',
}) {
  return showDialog<RetractChoice>(
    context: context,
    // The nearest Navigator, not the application root: this dialog belongs to
    // the transcript that asked. When the host replaces that page (engine
    // restart, Session switch) the question goes with it instead of hovering
    // over a screen that no longer exists.
    useRootNavigator: false,
    builder: (context) => _RetractDialog(
      plan: plan,
      hasDraft: hasDraft,
      title: title,
      confirmLabel: confirmLabel,
    ),
  );
}

class _RetractDialog extends StatefulWidget {
  const _RetractDialog({
    required this.plan,
    required this.hasDraft,
    required this.title,
    required this.confirmLabel,
  });

  final TurnPlan plan;
  final bool hasDraft;
  final String title;
  final String confirmLabel;

  @override
  State<_RetractDialog> createState() => _RetractDialogState();
}

class _RetractDialogState extends State<_RetractDialog> {
  late bool _includeFiles = widget.plan.canRestoreFiles;
  late bool _replaceDraft = true;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    return AlertDialog(
      title: Text(widget.title),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              describeChanges(widget.plan),
              key: const ValueKey('retract-summary'),
              style: TextStyle(color: palette.inkSoft, fontSize: 13.5, height: 1.5),
            ),
            const SizedBox(height: 12),
            if (widget.plan.canRestoreFiles)
              CheckboxListTile(
                key: const ValueKey('retract-include-files'),
                value: _includeFiles,
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                onChanged: (value) => setState(() => _includeFiles = value ?? false),
                title: const Text('同时回滚文件改动'),
                subtitle: Text(
                  '只恢复这一段记录里改过的文件；之后被外部改动的文件不会覆盖',
                  style: TextStyle(color: palette.inkFaint, fontSize: 12),
                ),
              ),
            if (widget.hasDraft) ...[
              const SizedBox(height: 6),
              Text(
                '输入框里已经有草稿',
                style: TextStyle(color: palette.inkSoft, fontSize: 13),
              ),
              _Choice(
                choiceKey: const ValueKey('retract-draft-replace'),
                label: '用这条消息替换草稿',
                selected: _replaceDraft,
                onSelected: () => setState(() => _replaceDraft = true),
              ),
              _Choice(
                choiceKey: const ValueKey('retract-draft-keep'),
                label: '保留草稿，把这条消息接在后面',
                selected: !_replaceDraft,
                onSelected: () => setState(() => _replaceDraft = false),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          key: const ValueKey('retract-cancel'),
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: const ValueKey('retract-confirm'),
          onPressed: () => Navigator.of(context).pop(
            RetractChoice(includeFiles: _includeFiles, replaceDraft: _replaceDraft),
          ),
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}
/// One exclusive choice in the dialog. A plain row keeps the current Flutter
/// release free of the deprecated Radio group API while staying a real target.
class _Choice extends StatelessWidget {
  const _Choice({
    required this.choiceKey,
    required this.label,
    required this.selected,
    required this.onSelected,
  });

  final Key choiceKey;
  final String label;
  final bool selected;
  final VoidCallback onSelected;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    return InkWell(
      key: choiceKey,
      borderRadius: BorderRadius.circular(10),
      onTap: onSelected,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 2),
        child: Row(
          children: [
            Icon(
              selected
                  ? Icons.radio_button_checked_rounded
                  : Icons.radio_button_unchecked_rounded,
              size: 19,
              color: selected ? palette.ice : palette.inkFaint,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(label, style: TextStyle(color: palette.ink, fontSize: 13.5)),
            ),
          ],
        ),
      ),
    );
  }
}

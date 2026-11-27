import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import 'chat_strings.dart';

class ApprovalCard extends StatelessWidget {
  const ApprovalCard({
    super.key,
    required this.request,
    required this.onApprove,
    required this.onStop,
    this.onReject,
    this.busy = false,
  });

  final ActionRequest request;
  final VoidCallback onApprove;
  final VoidCallback onStop;
  final VoidCallback? onReject;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('approval-card'),
      padding: const EdgeInsets.fromLTRB(12, 6, 12, 12),
      decoration: BoxDecoration(
        color: DshPalette.of(context).surface,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            DshChatStrings.of(context).approvalTitle,
            style: TextStyle(
              color: DshPalette.of(context).ice,
              fontSize: 12,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.6,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            request.title,
            style: TextStyle(
              color: DshPalette.of(context).ink,
              fontSize: 14.5,
              fontWeight: FontWeight.w600,
              height: 1.35,
            ),
          ),
          if (request.detail.trim().isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              request.detail,
              style: TextStyle(
                color: DshPalette.of(context).inkSoft,
                fontSize: 13,
                height: 1.45,
              ),
            ),
          ],
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton(
                key: const ValueKey('approval-approve'),
                style: FilledButton.styleFrom(
                  minimumSize: DshDisplayScope.targetSize(context),
                ),
                onPressed: busy ? null : onApprove,
                child: Text(
                  onReject == null
                      ? DshChatStrings.of(context).allowContinue
                      : DshChatStrings.of(context).allowOnce,
                ),
              ),
              if (onReject != null)
                OutlinedButton(
                  key: const ValueKey('approval-reject'),
                  style: OutlinedButton.styleFrom(
                    minimumSize: DshDisplayScope.targetSize(context),
                  ),
                  onPressed: busy ? null : onReject,
                  child: Text(DshChatStrings.of(context).rejectOnce),
                ),
              OutlinedButton(
                key: const ValueKey('approval-stop'),
                style: OutlinedButton.styleFrom(
                  minimumSize: DshDisplayScope.targetSize(context),
                ),
                onPressed: busy ? null : onStop,
                child: Text(DshChatStrings.of(context).stop),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class QuestionCard extends StatefulWidget {
  const QuestionCard({
    super.key,
    required this.request,
    required this.onSubmit,
    this.onStructuredSubmit,
    this.initialDraft,
    this.onDraftChanged,
    this.busy = false,
  });

  final ActionRequest request;
  final ValueChanged<String> onSubmit;
  final ValueChanged<QuestionAnswer>? onStructuredSubmit;
  final QuestionAnswer? initialDraft;
  final ValueChanged<QuestionAnswer>? onDraftChanged;
  final bool busy;

  @override
  State<QuestionCard> createState() => _QuestionCardState();
}

class _QuestionCardState extends State<QuestionCard>
    with AutomaticKeepAliveClientMixin<QuestionCard> {
  @override
  bool get wantKeepAlive => true;
  late final TextEditingController _answerController;
  final Set<int> _selectedChoices = {};

  @override
  void initState() {
    super.initState();
    _answerController = TextEditingController();
    _restoreDraft();
  }

  @override
  void didUpdateWidget(covariant QuestionCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.request.requestId != widget.request.requestId) {
      _selectedChoices.clear();
      _answerController.clear();
      _restoreDraft();
    }
  }

  @override
  void dispose() {
    _answerController.dispose();
    super.dispose();
  }

  void _restoreDraft() {
    final draft = widget.initialDraft;
    if (draft == null) return;
    _answerController.text = draft.custom;
    for (var i = 0; i < widget.request.choices.length; i++) {
      if (draft.selected.contains(widget.request.choices[i]))
        _selectedChoices.add(i);
    }
  }

  QuestionAnswer get _draft => QuestionAnswer(
    selected: [
      for (var i = 0; i < widget.request.choices.length; i++)
        if (_selectedChoices.contains(i)) widget.request.choices[i],
    ],
    custom: _answerController.text,
  );
  void _saveDraft() => widget.onDraftChanged?.call(_draft);

  String get _answer {
    final parts = <String>[
      for (var i = 0; i < widget.request.choices.length; i++)
        if (_selectedChoices.contains(i)) widget.request.choices[i].trim(),
      _answerController.text.trim(),
    ];
    return parts.where((part) => part.isNotEmpty).join('\n');
  }

  bool get _exclusiveAnswer =>
      widget.onStructuredSubmit != null &&
      !widget.request.multiSelect &&
      widget.request.choices.isNotEmpty;

  void _toggleChoice(int index) {
    if (widget.busy) return;
    setState(() {
      if (!_selectedChoices.remove(index)) {
        if (!widget.request.multiSelect) _selectedChoices.clear();
        _selectedChoices.add(index);
        if (_exclusiveAnswer) _answerController.clear();
      }
    });
    _saveDraft();
  }

  void _submit() {
    if (widget.busy) return;
    final answer = _answer;
    if (answer.isEmpty) return;
    final structured = widget.onStructuredSubmit;
    if (structured != null) {
      structured(
        QuestionAnswer(
          selected: [
            for (var i = 0; i < widget.request.choices.length; i++)
              if (_selectedChoices.contains(i)) widget.request.choices[i],
          ],
          custom: _answerController.text,
        ),
      );
    } else {
      widget.onSubmit(answer);
    }
  }

  void _skip() {
    if (widget.busy) return;
    widget.onStructuredSubmit?.call(QuestionAnswer());
  }

  Widget _choice(int index) {
    final selected = _selectedChoices.contains(index);
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: SizedBox(
        width: double.infinity,
        child: Semantics(
          selected: selected,
          inMutuallyExclusiveGroup: !widget.request.multiSelect,
          child: OutlinedButton(
            key: ValueKey('question-choice-$index'),
            onPressed: widget.busy ? null : () => _toggleChoice(index),
            style: OutlinedButton.styleFrom(
              minimumSize: DshDisplayScope.targetSize(context),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              alignment: Alignment.centerLeft,
              foregroundColor: DshPalette.of(context).ink,
              backgroundColor: selected
                  ? DshPalette.of(context).surfaceRaised
                  : Colors.transparent,
              side: BorderSide(
                color: selected
                    ? DshPalette.of(context).iceDeep
                    : DshPalette.of(context).line,
              ),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
            child: Row(
              children: [
                Icon(
                  widget.request.multiSelect
                      ? (selected
                            ? Icons.check_box_rounded
                            : Icons.check_box_outline_blank_rounded)
                      : (selected
                            ? Icons.radio_button_checked_rounded
                            : Icons.radio_button_unchecked_rounded),
                  size: 20,
                ),
                const SizedBox(width: 10),
                Expanded(child: Text(widget.request.choices[index])),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Container(
      key: const ValueKey('question-card'),
      padding: const EdgeInsets.fromLTRB(12, 6, 12, 12),
      decoration: BoxDecoration(
        color: DshPalette.of(context).surface,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            widget.request.multiSelect && widget.request.choices.isNotEmpty
                ? '想确认一下 · 可多选'
                : '想确认一下',
            style: TextStyle(
              color: DshPalette.of(context).ice,
              fontSize: 12,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.6,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            widget.request.title,
            style: TextStyle(
              color: DshPalette.of(context).ink,
              fontSize: 14.5,
              fontWeight: FontWeight.w600,
              height: 1.35,
            ),
          ),
          if (widget.request.detail.trim().isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              widget.request.detail,
              style: TextStyle(
                color: DshPalette.of(context).inkSoft,
                fontSize: 13,
                height: 1.45,
              ),
            ),
          ],
          const SizedBox(height: 10),
          for (var i = 0; i < widget.request.choices.length; i++) _choice(i),
          if (widget.request.choices.isNotEmpty) const SizedBox(height: 6),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minHeight: DshDisplayScope.minTargetOf(context),
                  ),
                  child: TextField(
                    key: const ValueKey('question-input'),
                    controller: _answerController,
                    enabled: !widget.busy,
                    minLines: 1,
                    maxLines: 3,
                    cursorColor: DshPalette.of(context).ice,
                    style: TextStyle(
                      fontSize: 15,
                      color: DshPalette.of(context).ink,
                    ),
                    decoration: InputDecoration(
                      hintText: widget.request.choices.isEmpty
                          ? '写下你的回答'
                          : _exclusiveAnswer
                          ? '或填写其他答案'
                          : '补充想法（可选）',
                    ),
                    onChanged: (value) {
                      setState(() {
                        if (_exclusiveAnswer && value.isNotEmpty)
                          _selectedChoices.clear();
                      });
                      _saveDraft();
                    },
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filled(
                key: const ValueKey('question-submit'),
                tooltip: '提交回答',
                style: IconButton.styleFrom(
                  backgroundColor: DshPalette.of(context).ice,
                  foregroundColor: DshPalette.of(context).paper,
                  disabledBackgroundColor: DshPalette.of(context).surfaceRaised,
                  disabledForegroundColor: DshPalette.of(context).inkFaint,
                  minimumSize: DshDisplayScope.targetSize(context),
                ),
                onPressed: widget.busy || _answer.isEmpty ? null : _submit,
                icon: const Icon(Icons.arrow_upward_rounded),
              ),
            ],
          ),
          if (widget.onStructuredSubmit != null)
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                key: const ValueKey('question-skip'),
                style: TextButton.styleFrom(
                  minimumSize: DshDisplayScope.targetSize(context),
                ),
                onPressed: widget.busy ? null : _skip,
                child: const Text('跳过'),
              ),
            ),
        ],
      ),
    );
  }
}

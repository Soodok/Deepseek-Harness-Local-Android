import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../theme/dsh_theme.dart';

class ApprovalCard extends StatelessWidget {
  const ApprovalCard({
    super.key,
    required this.request,
    required this.onApprove,
    required this.onStop,
  });

  final ActionRequest request;
  final VoidCallback onApprove;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('approval-card'),
      margin: const EdgeInsets.only(top: 16),
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      decoration: BoxDecoration(
        color: DshPalette.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: DshPalette.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '需要你确认',
            style: TextStyle(
              color: DshPalette.ice,
              fontSize: 12,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.6,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            request.title,
            style: const TextStyle(
              color: DshPalette.ink,
              fontSize: 16,
              fontWeight: FontWeight.w600,
              height: 1.35,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            request.detail,
            style: const TextStyle(
              color: DshPalette.inkSoft,
              fontSize: 14.5,
              height: 1.55,
            ),
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton(
                key: const ValueKey('approval-approve'),
                onPressed: onApprove,
                child: const Text('允许继续'),
              ),
              OutlinedButton(
                key: const ValueKey('approval-stop'),
                onPressed: onStop,
                child: const Text('停止'),
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
  });

  final ActionRequest request;
  final ValueChanged<String> onSubmit;

  @override
  State<QuestionCard> createState() => _QuestionCardState();
}

class _QuestionCardState extends State<QuestionCard> {
  late final TextEditingController _answerController;

  @override
  void initState() {
    super.initState();
    _answerController = TextEditingController();
  }

  @override
  void dispose() {
    _answerController.dispose();
    super.dispose();
  }

  void _submit() {
    final answer = _answerController.text.trim();
    if (answer.isEmpty) return;
    widget.onSubmit(answer);
    _answerController.clear();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('question-card'),
      margin: const EdgeInsets.only(top: 16),
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      decoration: BoxDecoration(
        color: DshPalette.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: DshPalette.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '想确认一下',
            style: TextStyle(
              color: DshPalette.ice,
              fontSize: 12,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.6,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            widget.request.title,
            style: const TextStyle(
              color: DshPalette.ink,
              fontSize: 16,
              fontWeight: FontWeight.w600,
              height: 1.35,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            widget.request.detail,
            style: const TextStyle(
              color: DshPalette.inkSoft,
              fontSize: 14.5,
              height: 1.55,
            ),
          ),
          const SizedBox(height: 12),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: TextField(
                  key: const ValueKey('question-input'),
                  controller: _answerController,
                  minLines: 1,
                  maxLines: 3,
                  cursorColor: DshPalette.ice,
                  style: const TextStyle(fontSize: 15, color: DshPalette.ink),
                  decoration: const InputDecoration(hintText: '写下你的回答'),
                  onSubmitted: (_) => _submit(),
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filled(
                key: const ValueKey('question-submit'),
                tooltip: '提交回答',
                style: IconButton.styleFrom(
                  backgroundColor: DshPalette.ice,
                  foregroundColor: const Color(0xFF07111F),
                ),
                onPressed: _submit,
                icon: const Icon(Icons.arrow_upward_rounded),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';

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
    return Card(
      key: const ValueKey('approval-card'),
      margin: const EdgeInsets.only(top: 14),
      color: const Color(0xFFF2F0FF),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.shield_outlined, color: Color(0xFF6557D8)),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    request.title,
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 16,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(request.detail, style: const TextStyle(height: 1.45)),
            const SizedBox(height: 16),
            Wrap(
              spacing: 10,
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
    return Card(
      key: const ValueKey('question-card'),
      margin: const EdgeInsets.only(top: 14),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.help_outline, color: Color(0xFF6557D8)),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    widget.request.title,
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 16,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(widget.request.detail, style: const TextStyle(height: 1.45)),
            const SizedBox(height: 14),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextField(
                    key: const ValueKey('question-input'),
                    controller: _answerController,
                    minLines: 1,
                    maxLines: 3,
                    decoration: const InputDecoration(hintText: '写下你的回答'),
                    onSubmitted: (_) => _submit(),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  key: const ValueKey('question-submit'),
                  tooltip: '提交回答',
                  onPressed: _submit,
                  icon: const Icon(Icons.arrow_upward),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

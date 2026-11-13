import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';

class ToolRunCard extends StatelessWidget {
  const ToolRunCard({super.key, required this.tool});

  final ToolRun tool;

  @override
  Widget build(BuildContext context) {
    final isRunning = tool.status == ToolStatus.running;
    final icon = switch (tool.status) {
      ToolStatus.running => Icons.sync,
      ToolStatus.complete => Icons.check_circle_outline,
      ToolStatus.failed => Icons.error_outline,
      ToolStatus.cancelled => Icons.stop_circle_outlined,
    };
    final color = switch (tool.status) {
      ToolStatus.running => const Color(0xFF6557D8),
      ToolStatus.complete => const Color(0xFF2B8064),
      ToolStatus.failed => const Color(0xFFB34B55),
      ToolStatus.cancelled => const Color(0xFF757180),
    };
    return Card(
      key: ValueKey('tool-card-' + tool.id),
      margin: const EdgeInsets.only(top: 12),
      child: ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        leading: isRunning
            ? SizedBox(
                width: 22,
                height: 22,
                child: CircularProgressIndicator(strokeWidth: 2, color: color),
              )
            : Icon(icon, color: color),
        title: Text(
          tool.name,
          style: const TextStyle(fontWeight: FontWeight.w600),
        ),
        subtitle: Text(_statusLabel(tool.status)),
        children: [
          Align(
            alignment: Alignment.centerLeft,
            child: Text(
              tool.detail.isEmpty ? '工具已启动，等待结果。' : tool.detail,
              style: TextStyle(color: Colors.grey.shade700, height: 1.45),
            ),
          ),
          if (tool.elapsedMs != null)
            Align(
              alignment: Alignment.centerLeft,
              child: Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  tool.elapsedMs.toString() + ' ms',
                  style: TextStyle(color: Colors.grey.shade500, fontSize: 12),
                ),
              ),
            ),
        ],
      ),
    );
  }

  String _statusLabel(ToolStatus status) => switch (status) {
    ToolStatus.running => '执行中',
    ToolStatus.complete => '已完成',
    ToolStatus.failed => '失败',
    ToolStatus.cancelled => '已停止',
  };
}

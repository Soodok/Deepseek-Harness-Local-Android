import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';

/// One disclosure for a contiguous set of tool records belonging to a message.
/// Rows are inserted into the parent ListView so long traces remain lazy.
class ExecutionGroupHeader extends StatelessWidget {
  const ExecutionGroupHeader({
    super.key,
    required this.groupId,
    required this.tools,
    required this.expanded,
    required this.onToggle,
  });
  final String groupId;
  final List<ToolRun> tools;
  final bool expanded;
  final VoidCallback onToggle;
  String get _summary {
    final running = tools.any((tool) => tool.status == ToolStatus.running);
    final failed = tools
        .where((tool) => tool.status == ToolStatus.failed)
        .length;
    final cancelled = tools
        .where((tool) => tool.status == ToolStatus.cancelled)
        .length;
    final parts = <String>[running ? '正在调用工具' : '已调用工具'];
    if (tools.any((tool) => tool.content?.command != null)) parts.add('已运行命令');
    if (tools.any((tool) => tool.content?.edits.isNotEmpty ?? false))
      parts.add('已修改文件');
    final prefix = failed > 0
        ? '$failed项失败 · '
        : cancelled > 0
        ? '$cancelled项取消 · '
        : '';
    return prefix + parts.join('，') + (parts.length > 2 ? '等' : '');
  }

  @override
  Widget build(BuildContext context) {
    final failed = tools.any((tool) => tool.status == ToolStatus.failed);
    return Semantics(
      expanded: expanded,
      child: TextButton(
        key: ValueKey('execution-toggle-' + groupId),
        onPressed: onToggle,
        style: TextButton.styleFrom(
          alignment: Alignment.centerLeft,
          padding: const EdgeInsets.symmetric(vertical: 8),
          // Full row width, but never below a real 48dp target height.
          minimumSize: DshDisplayScope.targetSize(context, width: 0),
          foregroundColor: DshPalette.of(context).inkSoft,
        ),
        child: Row(
          children: [
            AnimatedRotation(
              turns: expanded ? 0.5 : 0.0,
              duration: MediaQuery.disableAnimationsOf(context)
                  ? Duration.zero
                  : const Duration(milliseconds: 180),
              child: const Icon(Icons.expand_more_rounded, size: 16),
            ),
            const SizedBox(width: 7),
            Expanded(
              child: Text(
                _summary,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 13.5,
                  fontWeight: FontWeight.w400,
                  color: failed
                      ? DshPalette.of(context).bad
                      : DshPalette.of(context).inkSoft,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

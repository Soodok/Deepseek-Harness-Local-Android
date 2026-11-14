import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../theme/dsh_theme.dart';

class ToolRunCard extends StatelessWidget {
  const ToolRunCard({super.key, required this.tool, this.showDivider = false});

  final ToolRun tool;
  final bool showDivider;

  @override
  Widget build(BuildContext context) {
    final isRunning = tool.status == ToolStatus.running;
    final color = switch (tool.status) {
      ToolStatus.running => DshPalette.ice,
      ToolStatus.complete => DshPalette.good,
      ToolStatus.failed => DshPalette.bad,
      ToolStatus.cancelled => DshPalette.inkFaint,
    };
    final hasDetail = tool.detail.isNotEmpty || tool.elapsedMs != null;
    return Theme(
      data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(12)),
          child: ExpansionTile(
            key: ValueKey('tool-card-' + tool.id),
            tilePadding: const EdgeInsets.fromLTRB(12, 0, 6, 0),
            childrenPadding: const EdgeInsets.fromLTRB(38, 0, 14, 12),
            leading: isRunning
                ? SizedBox(
                    width: 15,
                    height: 15,
                    child: CircularProgressIndicator(
                      strokeWidth: 1.6,
                      color: color,
                    ),
                  )
                : Icon(_icon(tool.status), size: 15, color: color),
            title: Text(
              tool.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: DshPalette.ink,
                fontSize: 13.5,
                fontWeight: FontWeight.w600,
              ),
            ),
            subtitle: Text(
              _meta(tool),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: DshPalette.inkSoft, fontSize: 12),
            ),
            children: [
              if (!hasDetail)
                const Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    '工具已启动，等待结果。',
                    style: TextStyle(
                      color: DshPalette.inkSoft,
                      fontSize: 13,
                      height: 1.5,
                    ),
                  ),
                ),
              if (tool.detail.isNotEmpty)
                Align(
                  alignment: Alignment.centerLeft,
                  child: SelectableText(
                    tool.detail,
                    style: const TextStyle(
                      color: DshPalette.inkSoft,
                      fontSize: 13,
                      height: 1.5,
                    ),
                  ),
                ),
              if (tool.elapsedMs != null)
                Align(
                  alignment: Alignment.centerLeft,
                  child: Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      _elapsed(tool.elapsedMs!),
                      style: const TextStyle(
                        color: DshPalette.inkFaint,
                        fontSize: 12,
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  static IconData _icon(ToolStatus status) => switch (status) {
    ToolStatus.running => Icons.sync_rounded,
    ToolStatus.complete => Icons.check_rounded,
    ToolStatus.failed => Icons.close_rounded,
    ToolStatus.cancelled => Icons.stop_rounded,
  };

  static String _meta(ToolRun tool) {
    final label = _statusLabel(tool.status);
    if (tool.elapsedMs == null) return label;
    return label + ' · ' + _elapsed(tool.elapsedMs!);
  }

  static String _statusLabel(ToolStatus status) => switch (status) {
    ToolStatus.running => '执行中',
    ToolStatus.complete => '已完成',
    ToolStatus.failed => '失败',
    ToolStatus.cancelled => '已停止',
  };

  static String _elapsed(int ms) =>
      ms < 1000 ? ms.toString() + ' 毫秒' : (ms / 1000).toStringAsFixed(1) + ' 秒';
}

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import '../bridge/host_bridge.dart';
import 'tool_content_view.dart';
import 'tool_process_card.dart';

class ToolRunCard extends StatefulWidget {
  const ToolRunCard({
    super.key,
    required this.tool,
    this.showDivider = false,
    this.showPlan = true,
    this.compact = false,
  });
  final ToolRun tool;
  final bool showDivider;
  final bool showPlan;
  final bool compact;
  @override
  State<ToolRunCard> createState() => _ToolRunCardState();
}

class _ToolRunCardState extends State<ToolRunCard> {
  bool _expanded = false;
  ToolRun get tool => widget.tool;
  @override
  Widget build(BuildContext context) {
    final content = tool.content;
    if (content != null &&
        (content.reasoning.isNotEmpty ||
            content.command != null ||
            content.edits.isNotEmpty))
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ToolProcessCard(tool: tool, compact: widget.compact),
          if (content.resources.isNotEmpty ||
              (widget.showPlan && content.steps.isNotEmpty))
            ToolContentView(
              content: content,
              showPlan: widget.showPlan,
              showAgents: false,
              onOpenResource: HostBridge.openResource,
            ),
        ],
      );
    final running = tool.status == ToolStatus.running;
    final color = switch (tool.status) {
      ToolStatus.running => DshPalette.of(context).ice,
      ToolStatus.complete => DshPalette.of(context).good,
      ToolStatus.failed => DshPalette.of(context).bad,
      ToolStatus.cancelled => DshPalette.of(context).inkFaint,
    };
    final detail = tool.detail;
    final detailPreview = toolOutputPreview(
      detail,
      maxLines: widget.compact ? 3 : 12,
      maxCharacters: widget.compact ? 480 : 1200,
    );
    return Theme(
      data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
      child: ExpansionTile(
        key: ValueKey('tool-card-${tool.id}'),
        onExpansionChanged: (value) => setState(() => _expanded = value),
        tilePadding: widget.compact
            ? const EdgeInsets.only(right: 4)
            : const EdgeInsets.fromLTRB(10, 0, 6, 0),
        minTileHeight: widget.compact
            ? DshDisplayScope.minTargetOf(context)
            : null,
        shape: widget.compact ? const Border() : null,
        collapsedShape: widget.compact ? const Border() : null,
        childrenPadding: EdgeInsets.fromLTRB(
          widget.compact ? 8 : 10,
          0,
          widget.compact ? 8 : 10,
          widget.compact ? 0 : 12,
        ),
        leading: running
            ? SizedBox(
                width: 15,
                height: 15,
                child: CircularProgressIndicator(
                  strokeWidth: 1.6,
                  color: color,
                ),
              )
            : Icon(_icon(tool.status), size: 15, color: color),
        title: Row(
          children: [
            Expanded(
              child: Text(
                tool.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: DshPalette.of(context).inkSoft,
                  fontSize: 12,
                  fontWeight: FontWeight.w400,
                ),
              ),
            ),
            if (widget.compact &&
                (tool.status == ToolStatus.failed ||
                    tool.status == ToolStatus.cancelled)) ...[
              const SizedBox(width: 8),
              Text(
                _statusLabel(tool.status),
                style: TextStyle(color: color, fontSize: 12.5),
              ),
            ],
          ],
        ),
        subtitle: widget.compact
            ? null
            : Text(
                _meta(tool),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: DshPalette.of(context).inkSoft,
                  fontSize: 12,
                ),
              ),
        children: !_expanded
            ? const []
            : [
                if (tool.content != null)
                  ToolContentView(
                    content: tool.content!,
                    showPlan: widget.showPlan,
                    showAgents: false,
                    onOpenResource: HostBridge.openResource,
                  ),
                if (detail.isNotEmpty)
                  Align(
                    alignment: Alignment.centerLeft,
                    child: SelectableText(
                      detailPreview,
                      maxLines: widget.compact ? 3 : 12,
                      style: TextStyle(
                        color: DshPalette.of(context).inkSoft,
                        fontSize: widget.compact ? 12 : 13,
                        height: widget.compact ? 1.35 : 1.5,
                      ),
                    ),
                  ),
                if (detail.isNotEmpty &&
                    (!widget.compact || detailPreview.length < detail.length))
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton(
                      onPressed: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => ToolFullOutputPage(
                            id: 'detail-' + tool.id,
                            title: tool.name,
                            text: detail,
                            monospace: false,
                          ),
                        ),
                      ),
                      child: const Text('查看完整输出'),
                    ),
                  ),
                if (detail.isEmpty && tool.content == null)
                  Text(
                    running ? '等待结果…' : '没有附加输出',
                    style: TextStyle(
                      color: DshPalette.of(context).inkSoft,
                      fontSize: 13,
                    ),
                  ),
              ],
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

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../theme/dsh_theme.dart';
import '../bridge/host_bridge.dart';
import 'tool_content_view.dart';
import 'tool_process_card.dart';

import 'package:flutter/services.dart';

class ToolRunCard extends StatefulWidget {
  const ToolRunCard({
    super.key,
    required this.tool,
    this.showDivider = false,
    this.showPlan = true,
  });
  final ToolRun tool;
  final bool showDivider;
  final bool showPlan;
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
          ToolProcessCard(tool: tool),
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
      ToolStatus.running => DshPalette.ice,
      ToolStatus.complete => DshPalette.good,
      ToolStatus.failed => DshPalette.bad,
      ToolStatus.cancelled => DshPalette.inkFaint,
    };
    final detail = tool.detail;
    return Theme(
      data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
      child: ExpansionTile(
        key: ValueKey('tool-card-${tool.id}'),
        onExpansionChanged: (value) => setState(() => _expanded = value),
        tilePadding: const EdgeInsets.fromLTRB(10, 0, 6, 0),
        childrenPadding: const EdgeInsets.fromLTRB(10, 0, 10, 12),
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
                      detail.length > 1600
                          ? detail.substring(0, 1600) + '…'
                          : detail,
                      style: const TextStyle(
                        color: DshPalette.inkSoft,
                        fontSize: 13,
                        height: 1.5,
                      ),
                    ),
                  ),
                if (detail.length > 1600)
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton(
                      onPressed: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) =>
                              _FullOutput(title: tool.name, text: detail),
                        ),
                      ),
                      child: const Text('查看完整输出'),
                    ),
                  ),
                if (detail.isEmpty && tool.content == null)
                  Text(
                    running ? '等待结果…' : '没有附加输出',
                    style: const TextStyle(
                      color: DshPalette.inkSoft,
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

class _FullOutput extends StatelessWidget {
  const _FullOutput({required this.title, required this.text});
  final String title;
  final String text;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(title),
      actions: [
        TextButton(
          onPressed: () async {
            await Clipboard.setData(ClipboardData(text: text));
            if (context.mounted)
              ScaffoldMessenger.of(context)
                  .showSnackBar(const SnackBar(content: Text('已复制完整输出')));
          },
          child: const Text('复制全部'),
        ),
      ],
    ),
    body: ListView.builder(
      padding: const EdgeInsets.all(18),
      itemCount: (text.length / 1600).ceil(),
      itemBuilder: (_, i) => SelectableText(
        text.substring(
          i * 1600,
          (i + 1) * 1600 < text.length ? (i + 1) * 1600 : text.length,
        ),
        style: const TextStyle(fontSize: 13, height: 1.5),
      ),
    ),
  );
}

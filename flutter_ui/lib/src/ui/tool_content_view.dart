import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart' hide StepState;

import '../model/tool_content.dart';
import '../theme/dsh_theme.dart';

/// Presents only explicitly structured tool output; prose is never inspected.
class ToolContentView extends StatelessWidget {
  const ToolContentView({
    super.key,
    required this.content,
    this.onOpenResource,
    this.showPlan = true,
    this.showAgents = true,
  });

  final ToolContent content;
  final bool showPlan;
  final bool showAgents;
  final Future<bool> Function(OutputResource)? onOpenResource;

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (content.title.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(content.title, style: _titleStyle),
            ),
          if (showPlan && content.steps.isNotEmpty)
            _PlanView(steps: content.steps),
          for (final agent in showAgents ? content.agents : const <AgentTask>[])
            _AgentView(key: ValueKey('agent-' + agent.id), agent: agent),
          for (final resource in content.resources)
            _ResourceView(
              key: ValueKey('resource-' + resource.id),
              resource: resource,
              onOpenResource: onOpenResource,
            ),
        ],
      ),
    );
  }
}

const _titleStyle = TextStyle(
  color: DshPalette.ink,
  fontSize: 13.5,
  fontWeight: FontWeight.w600,
  height: 1.5,
);
const _bodyStyle = TextStyle(
  color: DshPalette.inkSoft,
  fontSize: 13,
  height: 1.5,
);
const _metaStyle = TextStyle(
  color: DshPalette.inkFaint,
  fontSize: 12,
  height: 1.5,
);

class _PlanView extends StatelessWidget {
  const _PlanView({required this.steps});
  final List<PlanStep> steps;

  @override
  Widget build(BuildContext context) {
    final completed = steps
        .where((step) => step.status == StepState.completed)
        .length;
    final running = steps.where((step) => step.status == StepState.running);
    final current = running.isEmpty ? null : running.first;
    final failed = steps
        .where((step) => step.status == StepState.failed)
        .length;
    return ExpansionTile(
      key: const ValueKey('tool-plan'),
      tilePadding: EdgeInsets.zero,
      childrenPadding: const EdgeInsets.only(left: 4, bottom: 8),
      title: Text(
        '计划 · ' + completed.toString() + '/' + steps.length.toString() + ' 已完成',
        style: _titleStyle,
      ),
      subtitle: Text(
        current != null
            ? '当前：' + current.title
            : failed > 0
            ? '$failed 步失败'
            : '展开查看完整计划',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: _metaStyle,
      ),
      children: [
        for (final step in steps)
          Padding(
            key: ValueKey('plan-step-' + step.id),
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _StatusIcon(presentation: _stepState(step.status)),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(step.title, style: _bodyStyle),
                      Text(_stepState(step.status).label, style: _metaStyle),
                    ],
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _AgentView extends StatelessWidget {
  const _AgentView({super.key, required this.agent});
  final AgentTask agent;

  @override
  Widget build(BuildContext context) {
    final presentation = _agentState(agent.status);
    return ExpansionTile(
      key: ValueKey('tool-agent-' + agent.id),
      tilePadding: EdgeInsets.zero,
      childrenPadding: const EdgeInsets.only(left: 26, bottom: 10),
      leading: _StatusIcon(presentation: presentation),
      title: Text(
        agent.name,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: _titleStyle,
      ),
      subtitle: Text(presentation.label, style: _metaStyle),
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('任务', style: _metaStyle),
              SelectableText(agent.task, style: _bodyStyle),
              if (agent.detail.isNotEmpty) ...[
                const SizedBox(height: 8),
                const Text('结果', style: _metaStyle),
                SelectableText(agent.detail, style: _bodyStyle),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _ResourceView extends StatefulWidget {
  const _ResourceView({super.key, required this.resource, this.onOpenResource});
  final OutputResource resource;
  final Future<bool> Function(OutputResource)? onOpenResource;

  @override
  State<_ResourceView> createState() => _ResourceViewState();
}

class _ResourceViewState extends State<_ResourceView> {
  bool _opening = false;
  bool _copying = false;
  bool _copied = false;
  bool _previewing = false;
  String? _openError;
  String? _copyError;
  int _revision = 0;

  @override
  void didUpdateWidget(covariant _ResourceView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.resource.uri != widget.resource.uri ||
        oldWidget.resource.assetPath != widget.resource.assetPath ||
        oldWidget.resource.kind != widget.resource.kind ||
        oldWidget.resource.id != widget.resource.id) {
      _revision++;
      _opening = false;
      _copying = false;
      _copied = false;
      _previewing = false;
      _openError = null;
      _copyError = null;
    }
  }

  Future<void> _open() async {
    final callback = widget.onOpenResource;
    if (_opening || callback == null || widget.resource.openableUri == null)
      return;
    final revision = _revision;
    final resource = widget.resource;
    setState(() {
      _opening = true;
      _openError = null;
    });
    var opened = false;
    try {
      opened = await callback(resource);
    } catch (_) {
      opened = false;
    }
    if (!mounted || revision != _revision) return;
    setState(() {
      _opening = false;
      _openError = opened ? null : '打开失败，请复制链接后重试。';
    });
  }

  Future<void> _copy() async {
    final uri = widget.resource.openableUri;
    if (_copying || uri == null) return;
    final revision = _revision;
    setState(() {
      _copying = true;
      _copied = false;
      _copyError = null;
    });
    var copied = false;
    try {
      await Clipboard.setData(ClipboardData(text: uri.toString()));
      copied = true;
    } catch (_) {
      copied = false;
    }
    if (!mounted || revision != _revision) return;
    setState(() {
      _copying = false;
      _copied = copied;
      _copyError = copied ? null : '复制失败，请重试。';
    });
  }

  @override
  Widget build(BuildContext context) {
    final resource = widget.resource;
    final uri = resource.openableUri;
    final assetPath = _trustedAssetPath(resource.assetPath);
    final decodeLimit =
        (MediaQuery.sizeOf(context).width *
                MediaQuery.devicePixelRatioOf(context) *
                2)
            .ceil()
            .clamp(1, 1600);
    final canPreview =
        resource.kind == ResourceKind.image &&
        (assetPath != null || uri != null);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Icon(
                  switch (resource.kind) {
                    ResourceKind.image => Icons.image_outlined,
                    ResourceKind.link => Icons.link,
                    ResourceKind.file => Icons.insert_drive_file_outlined,
                  },
                  size: 18,
                  color: DshPalette.inkSoft,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      resource.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: _titleStyle,
                    ),
                    Text(
                      _resourceMeta(resource, assetPath != null),
                      style: _metaStyle,
                    ),
                  ],
                ),
              ),
            ],
          ),
          Wrap(
            spacing: 4,
            runSpacing: 2,
            children: [
              if (resource.kind == ResourceKind.image)
                _action(
                  key: 'resource-preview-' + resource.id,
                  label: _previewing ? '预览中' : '预览',
                  icon: Icons.zoom_in,
                  onPressed: canPreview
                      ? () => setState(() => _previewing = !_previewing)
                      : null,
                ),
              _action(
                key: 'resource-open-' + resource.id,
                label: _opening ? '打开中…' : '打开',
                icon: Icons.open_in_new,
                onPressed:
                    uri != null && widget.onOpenResource != null && !_opening
                    ? _open
                    : null,
              ),
              _action(
                key: 'resource-copy-' + resource.id,
                label: _copied ? '已复制' : '复制链接',
                icon: Icons.copy_outlined,
                onPressed: uri != null && !_copying ? _copy : null,
              ),
            ],
          ),
          if (uri == null && assetPath == null)
            const Text('链接不可用', style: _metaStyle),
          if (uri != null && widget.onOpenResource == null)
            const Text('此环境暂不支持打开，请复制链接。', style: _metaStyle),
          if (_openError != null) _FailureText(_openError!),
          if (_copyError != null) _FailureText(_copyError!),
          if (_previewing && canPreview) ...[
            const SizedBox(height: 4),
            AspectRatio(
              aspectRatio: 4 / 3,
              child: ClipRect(
                child: InteractiveViewer(
                  minScale: 1,
                  maxScale: 5,
                  child: assetPath != null
                      ? Image(
                          image: ResizeImage(
                            AssetImage(assetPath, package: 'dsh_native_chat'),
                            width: decodeLimit,
                            height: decodeLimit,
                            policy: ResizeImagePolicy.fit,
                          ),
                          key: ValueKey('resource-image-' + resource.id),
                          fit: BoxFit.contain,
                          frameBuilder: _imageFrame,
                          errorBuilder: _imageError,
                        )
                      : Image(
                          image: ResizeImage(
                            NetworkImage(uri!.toString()),
                            width: decodeLimit,
                            height: decodeLimit,
                            policy: ResizeImagePolicy.fit,
                          ),
                          key: ValueKey('resource-image-' + resource.id),
                          fit: BoxFit.contain,
                          frameBuilder: _imageFrame,
                          loadingBuilder: (context, child, progress) =>
                              progress == null
                              ? child
                              : const _ImageNotice('正在加载图片…'),
                          errorBuilder: _imageError,
                        ),
                ),
              ),
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: _action(
                key: 'resource-preview-close-' + resource.id,
                label: '关闭预览',
                icon: Icons.close,
                onPressed: () => setState(() => _previewing = false),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

Widget _action({
  required String key,
  required String label,
  required IconData icon,
  VoidCallback? onPressed,
}) {
  return TextButton.icon(
    key: ValueKey(key),
    onPressed: onPressed,
    style: TextButton.styleFrom(
      minimumSize: const Size(48, 48),
      padding: const EdgeInsets.symmetric(horizontal: 10),
      foregroundColor: DshPalette.inkSoft,
      textStyle: const TextStyle(fontSize: 12.5),
    ),
    icon: Icon(icon, size: 16),
    label: Text(label),
  );
}

Widget _imageFrame(
  BuildContext context,
  Widget child,
  int? frame,
  bool synchronous,
) {
  return synchronous || frame != null ? child : const _ImageNotice('正在加载图片…');
}

Widget _imageError(BuildContext context, Object error, StackTrace? stackTrace) {
  return const _ImageNotice('图片加载失败，请关闭预览后重试或复制链接。');
}

class _ImageNotice extends StatelessWidget {
  const _ImageNotice(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(12),
      child: Text(text, textAlign: TextAlign.center, style: _bodyStyle),
    ),
  );
}

class _FailureText extends StatelessWidget {
  const _FailureText(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Text(text, style: _metaStyle.copyWith(color: DshPalette.bad)),
  );
}

String? _trustedAssetPath(String? value) {
  if (value == null ||
      value.isEmpty ||
      value != value.trim() ||
      value.contains('\\'))
    return null;
  final path = Uri.tryParse(value);
  if (path == null ||
      path.hasScheme ||
      path.hasAuthority ||
      path.hasQuery ||
      path.hasFragment ||
      path.path.startsWith('/') ||
      path.pathSegments.any(
        (part) => part.isEmpty || part == '.' || part == '..',
      ))
    return null;
  return value;
}

String _resourceMeta(OutputResource resource, bool asset) {
  final kind = switch (resource.kind) {
    ResourceKind.image => asset ? '包内演示图' : '图片',
    ResourceKind.link => '链接',
    ResourceKind.file => '文件',
  };
  return [
    kind,
    if (resource.mediaType.isNotEmpty) resource.mediaType,
    if (resource.byteSize != null && resource.byteSize! >= 0)
      _fileSize(resource.byteSize!),
  ].join(' · ');
}

String _fileSize(int bytes) {
  if (bytes < 1024) return '$bytes B';
  final value = bytes < 1024 * 1024 ? bytes / 1024 : bytes / (1024 * 1024);
  final unit = bytes < 1024 * 1024 ? 'KB' : 'MB';
  return value.toStringAsFixed(value == value.roundToDouble() ? 0 : 1) +
      ' ' +
      unit;
}

class _StatusPresentation {
  const _StatusPresentation(this.icon, this.label, this.color);
  final IconData icon;
  final String label;
  final Color color;
}

_StatusPresentation _stepState(StepState state) => switch (state) {
  StepState.pending => const _StatusPresentation(
    Icons.radio_button_unchecked,
    '待处理',
    DshPalette.inkFaint,
  ),
  StepState.running => const _StatusPresentation(
    Icons.more_horiz,
    '进行中',
    DshPalette.inkSoft,
  ),
  StepState.completed => const _StatusPresentation(
    Icons.check_circle_outline,
    '已完成',
    DshPalette.good,
  ),
  StepState.failed => const _StatusPresentation(
    Icons.error_outline,
    '失败',
    DshPalette.bad,
  ),
  StepState.cancelled => const _StatusPresentation(
    Icons.cancel_outlined,
    '已取消',
    DshPalette.inkFaint,
  ),
};

_StatusPresentation _agentState(AgentState state) => switch (state) {
  AgentState.running => const _StatusPresentation(
    Icons.more_horiz,
    '运行中',
    DshPalette.inkSoft,
  ),
  AgentState.waiting => const _StatusPresentation(
    Icons.schedule,
    '等待中',
    DshPalette.inkFaint,
  ),
  AgentState.completed => const _StatusPresentation(
    Icons.check_circle_outline,
    '已完成',
    DshPalette.good,
  ),
  AgentState.failed => const _StatusPresentation(
    Icons.error_outline,
    '失败',
    DshPalette.bad,
  ),
  AgentState.cancelled => const _StatusPresentation(
    Icons.cancel_outlined,
    '已取消',
    DshPalette.inkFaint,
  ),
};

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.presentation});
  final _StatusPresentation presentation;

  @override
  Widget build(BuildContext context) => Icon(
    presentation.icon,
    size: 16,
    color: presentation.color,
    semanticLabel: presentation.label,
  );
}

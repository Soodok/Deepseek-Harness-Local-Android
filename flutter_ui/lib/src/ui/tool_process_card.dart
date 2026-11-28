import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../model/tool_content.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';

Duration _motionFor(BuildContext context) =>
    MediaQuery.disableAnimationsOf(context)
    ? Duration.zero
    : const Duration(milliseconds: 200);
TextStyle _bodyStyle(BuildContext context) => TextStyle(
  color: DshPalette.of(context).inkSoft,
  fontSize: 13,
  height: 1.55,
);
TextStyle _codeStyle(BuildContext context) => TextStyle(
  color: DshPalette.of(context).ink,
  fontFamily: 'monospace',
  fontSize: 13,
  height: 1.55,
);

/// Compact presentation of explicit reasoning, command results and code edits.
/// Tool status and the command's exit code are independent observations.
class ToolProcessCard extends StatefulWidget {
  const ToolProcessCard({
    super.key,
    required this.tool,
    this.compact = false,
    this.executionReasoning,
  });

  final ToolRun tool;
  final bool compact;
  final String? executionReasoning;

  @override
  State<ToolProcessCard> createState() => _ToolProcessCardState();
}

class _ToolProcessCardState extends State<ToolProcessCard> {
  final Set<String> _expanded = {};

  @override
  void didUpdateWidget(covariant ToolProcessCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.tool.id != widget.tool.id) _expanded.clear();
  }

  void _toggle(String section) => setState(() {
    if (!_expanded.remove(section)) _expanded.add(section);
  });

  @override
  Widget build(BuildContext context) {
    final tool = widget.tool;
    final content = tool.content;
    final command = content?.command;
    final executionReasoning = widget.executionReasoning ?? '';
    final running = tool.status == ToolStatus.running;
    final statusColor = switch (tool.status) {
      ToolStatus.running => DshPalette.of(context).inkSoft,
      ToolStatus.complete => DshPalette.of(context).good,
      ToolStatus.failed => DshPalette.of(context).bad,
      ToolStatus.cancelled => DshPalette.of(context).inkFaint,
    };
    final statusLabel = switch (tool.status) {
      ToolStatus.running => '执行中',
      ToolStatus.complete => '已完成',
      ToolStatus.failed => '失败',
      ToolStatus.cancelled => '已取消',
    };
    final edits = content?.edits ?? const <CodeEdit>[];
    final generatedCommandDetail =
        command != null &&
        content != null &&
        _isGeneratedToolDetail(content.reasoning);
    final compactStandaloneDetail =
        widget.compact && command == null && edits.isEmpty;
    final compactStatus = widget.compact
        ? Tooltip(
            message:
                statusLabel +
                (tool.elapsedMs == null
                    ? ''
                    : ' · ' +
                          (tool.elapsedMs!.clamp(0, 1 << 53) / 1000)
                              .toStringAsFixed(1) +
                          ' 秒'),
            child: running
                ? Semantics(
                    label: statusLabel,
                    child: _RunningMark(color: statusColor),
                  )
                : Icon(
                    switch (tool.status) {
                      ToolStatus.running => Icons.sync_rounded,
                      ToolStatus.complete => Icons.check_rounded,
                      ToolStatus.failed => Icons.close_rounded,
                      ToolStatus.cancelled => Icons.stop_rounded,
                    },
                    size: 15,
                    color: statusColor,
                    semanticLabel: statusLabel,
                  ),
          )
        : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (!widget.compact)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    tool.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: DshPalette.of(context).ink,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                if (running) ...[
                  _RunningMark(color: statusColor),
                  const SizedBox(width: 6),
                ],
                AnimatedSwitcher(
                  duration: _motionFor(context),
                  child: Text(
                    statusLabel,
                    key: ValueKey(tool.status),
                    style: TextStyle(color: statusColor, fontSize: 12.5),
                  ),
                ),
                if (tool.elapsedMs != null) ...[
                  const SizedBox(width: 8),
                  Text(
                    (tool.elapsedMs!.clamp(0, 1 << 53) / 1000).toStringAsFixed(
                          1,
                        ) +
                        ' 秒',
                    style: TextStyle(
                      color: DshPalette.of(context).inkFaint,
                      fontSize: 12.5,
                    ),
                  ),
                ],
              ],
            ),
          ),
        if (content != null &&
            (content.reasoning.isNotEmpty || executionReasoning.isNotEmpty) &&
            !(widget.compact && command != null) &&
            !generatedCommandDetail)
          _ProcessSection(
            key: ValueKey('thought-' + tool.id),
            headerKey: ValueKey('process-reasoning-' + tool.id),
            label: compactStandaloneDetail ? tool.name : '思考',
            compact: widget.compact,
            summary: widget.compact || _expanded.contains('reasoning')
                ? ''
                : _summary(content.reasoning),
            icon: Icons.auto_awesome_outlined,
            accent: DshPalette.of(context).thought,
            expanded: _expanded.contains('reasoning'),
            trailing: widget.compact
                ? compactStatus
                : running
                ? _RunningMark(color: DshPalette.of(context).thought)
                : null,
            menu: widget.compact
                ? _ProcessMenu(
                    id: tool.id + '-reasoning',
                    label: '参数与结果操作',
                    entries: [
                      if (content.reasoning.isNotEmpty)
                        _ProcessMenuEntry(
                          value: 'copy-reasoning',
                          label: '复制参数与结果',
                          onSelected: () =>
                              _copyText(context, content.reasoning),
                        ),
                      ..._executionReasoningActions(
                        context,
                        tool.id,
                        executionReasoning,
                      ),
                      if (_previewTruncated(content.reasoning, lines: 12))
                        _ProcessMenuEntry(
                          value: 'full-reasoning',
                          label: '查看完整内容',
                          onSelected: () => _openFullOutput(
                            context,
                            id: tool.id + '-reasoning',
                            label: compactStandaloneDetail ? '参数与结果' : '思考过程',
                            text: content.reasoning,
                            monospace: false,
                          ),
                        ),
                    ],
                  )
                : null,
            onToggle: () => _toggle('reasoning'),
            body: () => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (content.reasoning.isNotEmpty)
                  _TextOutput(
                    id: tool.id + '-reasoning',
                    label: compactStandaloneDetail
                        ? '参数与结果'
                        : widget.compact
                        ? '思考内容'
                        : '思考过程',
                    compact: widget.compact,
                    showCopy: !widget.compact,
                    text: content.reasoning,
                    accent: widget.compact
                        ? DshPalette.of(context).terminal
                        : DshPalette.of(context).thought,
                    monospace: false,
                  ),
                if (executionReasoning.isNotEmpty)
                  ToolExecutionReasoningDetail(
                    id: tool.id + '-execution-reasoning',
                    text: executionReasoning,
                    compact: widget.compact,
                    actionsInMenu: widget.compact,
                  ),
              ],
            ),
          ),
        if (command != null)
          _ProcessSection(
            key: ValueKey('command-' + tool.id),
            headerKey: ValueKey('process-command-' + tool.id),
            label: widget.compact ? tool.name : '命令',
            compact: widget.compact,
            summary: _summary(command.command),
            icon: Icons.terminal_rounded,
            accent: DshPalette.of(context).terminal,
            expanded: _expanded.contains('command'),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                ?compactStatus,
                if (command.exitCode != null) ...[
                  if (compactStatus != null) const SizedBox(width: 6),
                  Text(
                    '退出码 ' + command.exitCode.toString(),
                    style: TextStyle(
                      color: DshPalette.of(context).inkSoft,
                      fontSize: 12.5,
                    ),
                  ),
                ] else if (!widget.compact && running)
                  _RunningMark(color: DshPalette.of(context).terminal),
              ],
            ),
            menu: widget.compact
                ? _ProcessMenu(
                    id: tool.id + '-command',
                    label: '命令操作',
                    entries: [
                      _ProcessMenuEntry(
                        value: 'copy-command',
                        label: '复制命令',
                        onSelected: () => _copyText(context, command.command),
                      ),
                      if (content?.reasoning.isNotEmpty == true &&
                          !generatedCommandDetail)
                        _ProcessMenuEntry(
                          value: 'copy-reasoning',
                          label: '复制思考内容',
                          onSelected: () =>
                              _copyText(context, content!.reasoning),
                        ),
                      ..._executionReasoningActions(
                        context,
                        tool.id,
                        executionReasoning,
                      ),
                      if (content?.reasoning.isNotEmpty == true &&
                          !generatedCommandDetail &&
                          _previewTruncated(
                            content!.reasoning,
                            lines: 1,
                            maxCharacters: 280,
                          ))
                        _ProcessMenuEntry(
                          value: 'full-reasoning',
                          label: '查看完整思考内容',
                          onSelected: () => _openFullOutput(
                            context,
                            id: tool.id + '-reasoning',
                            label: '思考过程',
                            text: content.reasoning,
                            monospace: false,
                          ),
                        ),
                      if (command.cwd.isNotEmpty)
                        _ProcessMenuEntry(
                          value: 'copy-cwd',
                          label: '复制 cwd',
                          onSelected: () => _copyText(context, command.cwd),
                        ),
                      if (command.stdout.isNotEmpty)
                        _ProcessMenuEntry(
                          value: 'copy-stdout',
                          label: '复制输出',
                          onSelected: () => _copyText(context, command.stdout),
                        ),
                      if (command.stderr.isNotEmpty)
                        _ProcessMenuEntry(
                          value: 'copy-stderr',
                          label: '复制错误',
                          onSelected: () => _copyText(context, command.stderr),
                        ),
                      _ProcessMenuEntry(
                        value: 'copy-all',
                        label: '复制全部',
                        onSelected: () => _copyText(
                          context,
                          _commandDigest(
                                command,
                                extraDetail: content?.reasoning ?? '',
                              ) +
                              (executionReasoning.isEmpty
                                  ? ''
                                  : '\n\n[思考过程]\n' + executionReasoning),
                        ),
                      ),
                      if (_previewTruncated(
                            command.stdout,
                            lines: 2,
                            maxCharacters: 240,
                          ) ||
                          _previewTruncated(
                            command.stderr,
                            lines: 2,
                            accentLines: 1,
                            maxCharacters: 240,
                          ) ||
                          _previewTruncated(
                            command.command,
                            lines: 1,
                            maxCharacters: 120,
                          ) ||
                          _previewTruncated(
                            command.cwd,
                            lines: 1,
                            maxCharacters: 120,
                          ) ||
                          (content!.reasoning.isNotEmpty &&
                              _previewTruncated(
                                content.reasoning,
                                lines: 4,
                                maxCharacters: 240,
                              )))
                        _ProcessMenuEntry(
                          value: 'full',
                          label: '查看完整输出',
                          onSelected: () => _openCommandFullOutput(
                            context,
                            tool: tool,
                            command: command,
                            executionReasoning: executionReasoning,
                          ),
                        ),
                    ],
                  )
                : null,
            onToggle: () => _toggle('command'),
            body: () => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (widget.compact)
                  _CompactCommandFields(
                    tool: tool,
                    command: command,
                    reasoning: generatedCommandDetail
                        ? null
                        : content?.reasoning,
                    executionReasoning: executionReasoning,
                    structuredDetail:
                        generatedCommandDetail &&
                            command.stdout.isEmpty &&
                            command.stderr.isEmpty
                        ? content.reasoning
                        : null,
                  )
                else ...[
                  if (executionReasoning.isNotEmpty)
                    ToolExecutionReasoningDetail(
                      id: tool.id + '-execution-reasoning',
                      text: executionReasoning,
                    ),
                  _TextOutput(
                    id: tool.id + '-command',
                    label: 'command',
                    compact: false,
                    text: command.command,
                    accent: DshPalette.of(context).terminal,
                  ),
                  if (command.cwd.isNotEmpty)
                    _TextOutput(
                      id: tool.id + '-cwd',
                      label: 'cwd',
                      compact: false,
                      text: command.cwd,
                      accent: DshPalette.of(context).terminal,
                    ),
                  if (command.stderr.isNotEmpty)
                    _TextOutput(
                      id: tool.id + '-stderr',
                      label: 'stderr',
                      compact: false,
                      text: command.stderr,
                      accent: DshPalette.of(context).bad,
                    ),
                  if (command.stdout.isNotEmpty)
                    _TextOutput(
                      key: ValueKey(
                        'process-output-section-' + tool.id + '-stdout',
                      ),
                      id: tool.id + '-stdout',
                      label: 'stdout',
                      compact: false,
                      text: command.stdout,
                      accent: DshPalette.of(context).terminal,
                    ),
                  if (command.stdout.isEmpty &&
                      command.stderr.isEmpty &&
                      !generatedCommandDetail)
                    if (!widget.compact)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: Text('暂无输出', style: _bodyStyle(context)),
                      ),
                ],
              ],
            ),
          ),
        for (final entry in _editEntries(edits))
          _EditSection(
            // Keep disclosure and parsed diffs with their path when earlier
            // files are inserted; repeated paths get separate ordinals.
            key: ValueKey(tool.id + '-edit-' + entry.stableId),
            id: tool.id + '-' + entry.index.toString(),
            headerKey: ValueKey(
              'process-diff-' + tool.id + '-' + entry.index.toString(),
            ),
            edit: entry.edit,
            compact: widget.compact,
            expanded: _expanded.contains('edit-' + entry.stableId),
            onToggle: () => _toggle('edit-' + entry.stableId),
          ),
      ],
    );
  }
}

String _summary(String text) {
  final end = text.length < 160 ? text.length : 160;
  return text.substring(0, _safeEnd(text, end)).split('\n').first.trim();
}

bool _isGeneratedToolDetail(String text) {
  final trimmed = text.trimLeft();
  if (trimmed.isEmpty) return false;
  return RegExp(r'^(?:参数|结果|错误|元数据)\s*(?:\r?\n|$)').hasMatch(trimmed);
}

typedef _EditEntry = ({CodeEdit edit, int index, String stableId});

List<_EditEntry> _editEntries(List<CodeEdit> edits) {
  final occurrences = <String, int>{};
  return [
    for (var index = 0; index < edits.length; index++)
      (
        edit: edits[index],
        index: index,
        stableId:
            edits[index].path +
            '-' +
            (occurrences.update(
              edits[index].path,
              (value) => value + 1,
              ifAbsent: () => 0,
            )).toString(),
      ),
  ];
}

class _RunningMark extends StatelessWidget {
  const _RunningMark({required this.color});
  final Color color;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 12,
    height: 12,
    child: CircularProgressIndicator(strokeWidth: 1.4, color: color),
  );
}

class _ProcessSection extends StatelessWidget {
  const _ProcessSection({
    super.key,
    required this.headerKey,
    required this.label,
    this.summary = '',
    required this.icon,
    required this.accent,
    required this.expanded,
    required this.onToggle,
    required this.body,
    this.trailing,
    this.menu,
    this.compact = false,
  });

  final Key headerKey;
  final String label;
  final String summary;
  final IconData icon;
  final Color accent;
  final bool expanded;
  final VoidCallback onToggle;
  final Widget Function() body;
  final Widget? trailing;

  /// Compact trailing actions (the per-field copy menu). It sits beside the
  /// header, not inside it, so one tap cannot both expand and open the menu.
  final Widget? menu;
  final bool compact;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Semantics(
              button: true,
              expanded: expanded,
              child: Material(
                type: MaterialType.transparency,
                child: InkWell(
                  key: headerKey,
                  onTap: onToggle,
                  borderRadius: BorderRadius.circular(8),
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      minHeight: DshDisplayScope.minTargetOf(context),
                    ),
                    child: Padding(
                      padding: EdgeInsets.symmetric(
                        horizontal: compact ? 8 : 10,
                        vertical: compact ? 4 : 8,
                      ),
                      child: Row(
                        children: [
                          Icon(icon, size: 17, color: accent),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Row(
                              children: [
                                Flexible(
                                  flex: summary.isEmpty ? 1 : 0,
                                  child: Text(
                                    label,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: DshPalette.of(context).inkSoft,
                                      fontSize: 12,
                                      fontWeight: FontWeight.w500,
                                    ),
                                  ),
                                ),
                                if (summary.isNotEmpty) ...[
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      summary,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        color: DshPalette.of(context).inkFaint,
                                        fontFamily: 'monospace',
                                        fontSize: 12,
                                      ),
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                          if (trailing != null) ...[
                            const SizedBox(width: 8),
                            trailing!,
                          ],
                          const SizedBox(width: 6),
                          AnimatedRotation(
                            turns: expanded ? .25 : 0,
                            duration: _motionFor(context),
                            child: Icon(
                              Icons.chevron_right_rounded,
                              size: 17,
                              color: DshPalette.of(context).inkFaint,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
          // The menu is a sibling of the disclosure, never a descendant: one
          // tap can never both expand the section and open its actions.
          ?menu,
        ],
      ),
      AnimatedSize(
        duration: _motionFor(context),
        curve: Curves.easeOutCubic,
        alignment: Alignment.topLeft,
        child: expanded
            ? Padding(
                padding: EdgeInsets.fromLTRB(
                  compact ? 18 : 34,
                  0,
                  compact ? 4 : 10,
                  compact ? 0 : 12,
                ),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border(
                      left: BorderSide(color: accent.withValues(alpha: .24)),
                    ),
                  ),
                  child: Padding(
                    padding: EdgeInsets.only(left: compact ? 4 : 12),
                    child: body(),
                  ),
                ),
              )
            : const SizedBox.shrink(),
      ),
    ],
  );
}

class _TextOutput extends StatelessWidget {
  const _TextOutput({
    super.key,
    required this.id,
    required this.label,
    required this.text,
    required this.accent,
    this.monospace = true,
    this.compact = false,
    this.showCopy = true,
    this.compactMaxLines,
  });
  final String id;
  final String label;
  final String text;
  final Color accent;
  final bool monospace;
  final bool compact;

  /// Compact rows move every per-field action into one header menu, so the
  /// field itself renders text only.
  final bool showCopy;
  final int? compactMaxLines;

  @override
  Widget build(BuildContext context) {
    final source = compact && !monospace
        ? text.replaceAll(RegExp(r'\n{2,}'), '\n')
        : text;
    final preview = toolOutputPreview(
      source,
      // Projected parameters, result and metadata include their own headings.
      maxLines: compact ? (compactMaxLines ?? (monospace ? 2 : 4)) : 60,
      maxCharacters: compact ? (monospace ? 240 : 280) : 1600,
    );
    final heading = Text(label, style: TextStyle(color: accent, fontSize: 12));
    final body = RepaintBoundary(
      key: ValueKey('process-preview-boundary-' + id),
      child: compact
          ? Text(
              preview,
              key: ValueKey('process-output-' + id),
              maxLines: compact
                  ? (compactMaxLines ?? (monospace ? 1 : 4))
                  : monospace
                  ? 1
                  : 4,
              overflow: TextOverflow.ellipsis,
              style: (monospace ? _codeStyle(context) : _bodyStyle(context))
                  .copyWith(fontSize: 12, height: 1.35),
            )
          : SelectableText(
              preview,
              key: ValueKey('process-output-' + id),
              style: (monospace ? _codeStyle(context) : _bodyStyle(context))
                  .copyWith(fontSize: 13, height: null),
            ),
    );
    final copy = showCopy
        ? _CopyAction(id: id, text: text, compact: compact)
        : null;
    return Padding(
      padding: EdgeInsets.only(bottom: compact ? 0 : 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (compact)
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                heading,
                const SizedBox(width: 6),
                Expanded(child: body),
                ?copy,
              ],
            )
          else ...[
            Row(
              children: [
                Expanded(child: heading),
                ?copy,
              ],
            ),
            body,
          ],
          if (!compact && preview.length < text.length) ...[
            Padding(
              padding: EdgeInsets.only(top: 6),
              child: Text('预览已截断 · 完整内容仍可查看或复制', style: _bodyStyle(context)),
            ),
            // Truncation is the one case where the field keeps an inline
            // action: a reader who reached the cut needs the rest immediately,
            // not two taps into a menu.
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                key: ValueKey('process-full-' + id),
                style: _actionStyle(context),
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => ToolFullOutputPage(
                      id: id,
                      title: '完整输出',
                      label: label,
                      text: text,
                      monospace: monospace,
                    ),
                  ),
                ),
                child: const Text('查看完整内容'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

ButtonStyle _actionStyle(BuildContext context) => TextButton.styleFrom(
  minimumSize: DshDisplayScope.targetSize(context),
  padding: const EdgeInsets.symmetric(horizontal: 8),
  foregroundColor: DshPalette.of(context).inkSoft,
  textStyle: const TextStyle(fontSize: 12.5),
);

/// Compact fields stay text-only; their actions live in the section header
/// menu. These helpers keep the menu entries one line each.
class _ProcessMenuEntry {
  const _ProcessMenuEntry({
    required this.value,
    required this.label,
    required this.onSelected,
  });

  final String value;
  final String label;
  final VoidCallback onSelected;
}

/// One 48dp target carrying every per-field action of a compact section.
///
/// It sits beside the disclosure header, never inside it, so one tap can never
/// expand the section and open its menu at the same time.
class _ProcessMenu extends StatelessWidget {
  const _ProcessMenu({
    required this.id,
    required this.label,
    required this.entries,
  });

  final String id;
  final String label;
  final List<_ProcessMenuEntry> entries;

  @override
  Widget build(BuildContext context) {
    if (entries.isEmpty) return const SizedBox.shrink();
    final size = DshDisplayScope.minTargetOf(context);
    return SizedBox(
      width: size,
      height: size,
      child: PopupMenuButton<String>(
        key: ValueKey('process-menu-' + id),
        tooltip: label,
        padding: EdgeInsets.zero,
        position: PopupMenuPosition.under,
        color: DshPalette.of(context).surface,
        onSelected: (value) {
          for (final entry in entries) {
            if (entry.value == value) {
              entry.onSelected();
              return;
            }
          }
        },
        itemBuilder: (_) => [
          for (final entry in entries)
            PopupMenuItem<String>(
              value: entry.value,
              height: size,
              child: Text(entry.label, style: const TextStyle(fontSize: 13)),
            ),
        ],
        icon: const Icon(Icons.more_horiz_rounded, size: 18),
      ),
    );
  }
}

Future<void> _copyText(BuildContext context, String text) async {
  var success = false;
  try {
    await Clipboard.setData(ClipboardData(text: text));
    success = true;
  } catch (_) {
    success = false;
  }
  if (!context.mounted) return;
  final messenger = ScaffoldMessenger.maybeOf(context);
  messenger?.hideCurrentSnackBar();
  messenger?.showSnackBar(
    SnackBar(
      duration: const Duration(seconds: 1),
      content: Text(success ? '已复制' : '复制失败，请重试'),
    ),
  );
}

/// Everything a compact command can carry in one copy: the invocation, its
/// environment, the exit code and both streams.
String _commandDigest(CommandResult command, {String extraDetail = ''}) {
  final buffer = StringBuffer(r'$ ' + command.command);
  if (command.cwd.isNotEmpty) buffer.write('\ncwd: ' + command.cwd);
  if (command.exitCode != null) {
    buffer.write('\nexit: ' + command.exitCode.toString());
  }
  if (command.stdout.isNotEmpty) buffer.write('\n\n' + command.stdout);
  if (command.stderr.isNotEmpty)
    buffer.write('\n\n[stderr]\n' + command.stderr);
  if (extraDetail.isNotEmpty) buffer.write('\n\n[details]\n' + extraDetail);
  return buffer.toString();
}

/// Whether the compact preview hides part of [text], so the menu only offers
/// "full content" when there is something left to read.
bool _previewTruncated(
  String text, {
  int lines = 3,
  int accentLines = 0,
  int maxCharacters = 480,
}) {
  if (text.isEmpty) return false;
  final limit = lines + accentLines;
  var seen = 1;
  for (var i = 0; i < text.length; i++) {
    if (i >= maxCharacters) return true;
    if (text.codeUnitAt(i) == 10 && ++seen > limit) return true;
  }
  return false;
}

List<_ProcessMenuEntry> _executionReasoningActions(
  BuildContext context,
  String toolId,
  String text,
) => [
  if (text.isNotEmpty) ...[
    _ProcessMenuEntry(
      value: 'copy-execution-reasoning',
      label: '复制思考过程',
      onSelected: () => _copyText(context, text),
    ),
    _ProcessMenuEntry(
      value: 'full-execution-reasoning',
      label: '查看完整思考过程',
      onSelected: () => _openFullOutput(
        context,
        id: toolId + '-execution-reasoning',
        label: '思考过程',
        text: text,
        monospace: false,
      ),
    ),
  ],
];

class ToolExecutionReasoningDetail extends StatelessWidget {
  const ToolExecutionReasoningDetail({
    super.key,
    required this.id,
    required this.text,
    this.compact = false,
    this.actionsInMenu = false,
  });

  final String id;
  final String text;
  final bool compact;
  final bool actionsInMenu;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _TextOutput(
          id: id,
          label: '思考过程',
          text: text,
          accent: DshPalette.of(context).thought,
          monospace: false,
          compact: compact,
          compactMaxLines: 3,
          showCopy: !actionsInMenu,
        ),
        if (compact &&
            !actionsInMenu &&
            _previewTruncated(text, lines: 3, maxCharacters: 280))
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              key: ValueKey('execution-reasoning-full-' + id),
              style: _actionStyle(context),
              onPressed: () => _openFullOutput(
                context,
                id: id,
                label: '思考过程',
                text: text,
                monospace: false,
              ),
              child: const Text('查看完整思考过程'),
            ),
          ),
      ],
    );
  }
}

void _openFullOutput(
  BuildContext context, {
  required String id,
  required String label,
  required String text,
  bool monospace = true,
}) => Navigator.of(context).push(
  MaterialPageRoute<void>(
    builder: (_) => ToolFullOutputPage(
      id: id,
      title: '完整输出',
      label: label,
      text: text,
      monospace: monospace,
    ),
  ),
);

void _openCommandFullOutput(
  BuildContext context, {
  required ToolRun tool,
  required CommandResult command,
  String executionReasoning = '',
}) {
  final reasoning = tool.content?.reasoning ?? '';
  final digest = _commandDigest(command);
  final fullText = [
    digest,
    if (reasoning.isNotEmpty) '\n\n[参数与结果]\n' + reasoning,
    if (executionReasoning.isNotEmpty) '\n\n[思考过程]\n' + executionReasoning,
  ].join();
  _openFullOutput(
    context,
    id: tool.id + '-command-full',
    label: tool.name,
    text: fullText,
  );
}

/// Compact command body: every field is a "label + value" pair read in place.
///
/// Order is deliberate. A failure shows its error first, the invocation second,
/// and the streams last, so the first line after the header answers "what ran".
/// The header menu shares one touch target for copy and full-content actions.
class _CompactCommandFields extends StatelessWidget {
  const _CompactCommandFields({
    required this.tool,
    required this.command,
    this.reasoning,
    this.executionReasoning = '',
    this.structuredDetail,
  });

  final ToolRun tool;
  final CommandResult command;
  final String? reasoning;
  final String executionReasoning;
  final String? structuredDetail;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    final failed = tool.status == ToolStatus.failed;
    final hasStderr = command.stderr.isNotEmpty;
    final stderrField = hasStderr
        ? _TextOutput(
            id: tool.id + '-stderr',
            label: 'stderr',
            compact: true,
            text: command.stderr,
            accent: palette.bad,
            showCopy: false,
          )
        : null;
    final commandField = _TextOutput(
      id: tool.id + '-command',
      label: 'command',
      compact: true,
      text: command.command,
      accent: palette.terminal,
      showCopy: false,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (failed && stderrField != null) stderrField,
        if (executionReasoning.isNotEmpty) ...[
          ToolExecutionReasoningDetail(
            id: tool.id + '-execution-reasoning',
            text: executionReasoning,
            compact: true,
            actionsInMenu: true,
          ),
          const SizedBox(height: 2),
        ],
        commandField,
        if (reasoning?.isNotEmpty == true) ...[
          const SizedBox(height: 2),
          _TextOutput(
            id: tool.id + '-reasoning',
            label: '思考',
            compact: true,
            compactMaxLines: 1,
            text: reasoning!,
            accent: palette.thought,
            monospace: false,
            showCopy: false,
          ),
        ],
        if (command.stdout.isNotEmpty) ...[
          const SizedBox(height: 2),
          _TextOutput(
            key: ValueKey('process-output-section-' + tool.id + '-stdout'),
            id: tool.id + '-stdout',
            label: 'stdout',
            compact: true,
            text: command.stdout,
            accent: palette.terminal,
            showCopy: false,
          ),
        ],
        if (structuredDetail case final detail?)
          _TextOutput(
            key: ValueKey(
              'process-output-section-' + tool.id + '-generated-detail',
            ),
            id: tool.id + '-generated-detail',
            label: '参数与结果',
            compact: true,
            text: _compactGeneratedDetail(detail),
            accent: palette.thought,
            monospace: false,
            showCopy: false,
            compactMaxLines: 1,
          ),
        if (!failed && stderrField != null) ...[
          const SizedBox(height: 2),
          stderrField,
        ],
      ],
    );
  }
}

String _compactGeneratedDetail(String text) {
  final lines = text
      .split(RegExp(r'\r?\n'))
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .where((line) => !RegExp(r'^(?:参数|结果|错误|元数据)$').hasMatch(line))
      .map((line) => line.replaceAll(RegExp(r'\s+'), ' '))
      .toList();
  final summary = lines.join(' · ');
  if (summary.length <= 180) return summary;
  return summary.substring(0, 177).trimRight() + '...';
}

class _CopyAction extends StatefulWidget {
  const _CopyAction({
    required this.id,
    required this.text,
    this.label = '复制全部',
    this.compact = false,
  });
  final String id;
  final String text;
  final String label;
  final bool compact;

  @override
  State<_CopyAction> createState() => _CopyActionState();
}

class _CopyActionState extends State<_CopyAction> {
  bool _busy = false;
  int _generation = 0;

  @override
  void didUpdateWidget(covariant _CopyAction oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.id != widget.id || oldWidget.text != widget.text) {
      _generation++;
      _busy = false;
    }
  }

  @override
  void dispose() {
    _generation++;
    super.dispose();
  }

  Future<void> _copy() async {
    final generation = ++_generation;
    setState(() => _busy = true);
    var success = false;
    try {
      await Clipboard.setData(ClipboardData(text: widget.text));
      success = true;
    } catch (_) {
      success = false;
    }
    if (!mounted || generation != _generation) return;
    setState(() => _busy = false);
    final messenger = ScaffoldMessenger.maybeOf(context);
    messenger?.hideCurrentSnackBar();
    messenger?.showSnackBar(
      SnackBar(
        duration: const Duration(seconds: 1),
        content: Text(success ? '已复制完整内容' : '复制失败，请重试'),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (widget.compact) {
      return IconButton(
        key: ValueKey('process-copy-' + widget.id),
        tooltip: widget.label,
        constraints: BoxConstraints.tight(DshDisplayScope.targetSize(context)),
        visualDensity: VisualDensity.standard,
        padding: EdgeInsets.zero,
        onPressed: _busy ? null : _copy,
        icon: const Icon(Icons.copy_outlined, size: 16),
      );
    }
    return TextButton.icon(
      key: ValueKey('process-copy-' + widget.id),
      style: _actionStyle(context),
      onPressed: _busy ? null : _copy,
      icon: const Icon(Icons.copy_outlined, size: 15),
      label: Text(widget.label),
    );
  }
}

/// Limits layout work only; callers retain the untouched source for full access.
String toolOutputPreview(
  String text, {
  int maxLines = 60,
  int maxCharacters = 1600,
}) {
  var end = text.length < maxCharacters ? text.length : maxCharacters;
  var lines = 1;
  for (var i = 0; i < end; i++) {
    if (text.codeUnitAt(i) == 10 && ++lines > maxLines) {
      end = i;
      break;
    }
  }
  return text.substring(0, _safeEnd(text, end));
}

int _safeEnd(String text, int end) {
  if (end > 0 && end < text.length) {
    final unit = text.codeUnitAt(end - 1);
    if (unit >= 0xD800 && unit <= 0xDBFF) return end - 1;
  }
  return end;
}

/// Indexes logical lines and bounds a single very long line to small widgets.
class _TextSlices {
  _TextSlices(this.text) {
    var start = 0;
    for (var i = 0; i < text.length; i++) {
      if (text.codeUnitAt(i) == 10 || i - start + 1 >= 1200) {
        final newline = text.codeUnitAt(i) == 10;
        final end = newline ? i : _safeEnd(text, i + 1);
        ranges.add((start, end));
        start = newline ? i + 1 : end;
        i = start - 1;
      }
    }
    if (start < text.length) ranges.add((start, text.length));
  }
  final String text;
  final List<(int, int)> ranges = [];
  String at(int index) => text.substring(ranges[index].$1, ranges[index].$2);
}

class ToolFullOutputPage extends StatefulWidget {
  const ToolFullOutputPage({
    super.key,
    required this.id,
    required this.title,
    required this.text,
    this.label = '',
    this.monospace = true,
  });
  final String id;
  final String title;
  final String label;
  final String text;
  final bool monospace;

  @override
  State<ToolFullOutputPage> createState() => _ToolFullOutputPageState();
}

class _ToolFullOutputPageState extends State<ToolFullOutputPage> {
  late final _slices = _TextSlices(widget.text);
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.title, style: const TextStyle(fontSize: 16)),
      actions: [_CopyAction(id: 'full-' + widget.id, text: widget.text)],
    ),
    body: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.label.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: Text(widget.label, style: _bodyStyle(context)),
          ),
        Expanded(
          child: Scrollbar(
            controller: _scroll,
            child: SelectionArea(
              child: ListView.builder(
                key: ValueKey('process-full-list-' + widget.id),
                controller: _scroll,
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                itemCount: _slices.ranges.length,
                itemBuilder: (context, index) => Text(
                  _slices.at(index),
                  key: ValueKey(
                    'process-full-line-' + widget.id + '-' + index.toString(),
                  ),
                  style: widget.monospace
                      ? _codeStyle(context)
                      : _bodyStyle(context),
                ),
              ),
            ),
          ),
        ),
      ],
    ),
  );
}

class _EditSection extends StatefulWidget {
  const _EditSection({
    super.key,
    required this.id,
    required this.headerKey,
    required this.edit,
    required this.expanded,
    required this.onToggle,
    this.compact = false,
  });
  final String id;
  final Key headerKey;
  final CodeEdit edit;
  final bool expanded;
  final VoidCallback onToggle;
  final bool compact;

  @override
  State<_EditSection> createState() => _EditSectionState();
}

class _EditSectionState extends State<_EditSection> {
  _DiffDocument? _document;

  @override
  void didUpdateWidget(covariant _EditSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    final previous = oldWidget.edit;
    final current = widget.edit;
    if (previous.before != current.before ||
        previous.after != current.after ||
        previous.unifiedDiff != current.unifiedDiff) {
      _document = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final edit = widget.edit;
    final hasSource =
        edit.unifiedDiff.isNotEmpty ||
        edit.before.isNotEmpty ||
        edit.after.isNotEmpty;
    final document = hasSource ? (_document ??= _DiffDocument(edit)) : null;
    return _ProcessSection(
      headerKey: widget.headerKey,
      label: edit.path,
      compact: widget.compact,
      icon: Icons.code_rounded,
      accent: DshPalette.of(context).edit,
      expanded: widget.expanded,
      onToggle: widget.onToggle,
      trailing: document == null
          ? null
          : Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  '+' + document.added.toString(),
                  style: TextStyle(
                    color: DshPalette.of(context).good,
                    fontSize: 12.5,
                  ),
                ),
                const SizedBox(width: 6),
                Text(
                  '-' + document.removed.toString(),
                  style: TextStyle(
                    color: DshPalette.of(context).bad,
                    fontSize: 12.5,
                  ),
                ),
              ],
            ),
      body: () {
        if (document == null) return Text('尚无改动内容', style: _bodyStyle(context));
        final preview = document.preview;
        final truncated =
            preview.length < document.lines.length ||
            (preview.isNotEmpty &&
                preview.last.text != document.lines[preview.length - 1].text);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (edit.language.isNotEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Text(edit.language, style: _bodyStyle(context)),
              ),
            RepaintBoundary(
              key: ValueKey('process-diff-preview-boundary-' + widget.id),
              child: SelectionArea(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (final line in preview)
                      _DiffRow(line: line, compact: widget.compact),
                  ],
                ),
              ),
            ),
            if (truncated) ...[
              Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  '差异预览已截断 · 完整改动仍可查看或复制',
                  style: _bodyStyle(context),
                ),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  key: ValueKey('process-full-diff-' + widget.id),
                  style: _actionStyle(context),
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => _FullDiffPage(
                        id: widget.id,
                        edit: edit,
                        document: document,
                      ),
                    ),
                  ),
                  child: const Text('查看完整改动'),
                ),
              ),
            ],
            Wrap(
              spacing: 4,
              children: [
                if (edit.unifiedDiff.isNotEmpty)
                  _CopyAction(
                    id: widget.id + '-diff',
                    text: edit.unifiedDiff,
                    label: '复制 Diff',
                  ),
                if (edit.before.isNotEmpty || edit.after.isNotEmpty) ...[
                  _CopyAction(
                    id: widget.id + '-before',
                    text: edit.before,
                    label: '复制修改前',
                  ),
                  _CopyAction(
                    id: widget.id + '-after',
                    text: edit.after,
                    label: '复制修改后',
                  ),
                ],
              ],
            ),
          ],
        );
      },
    );
  }
}

class _DiffLine {
  const _DiffLine(this.text, this.kind, {this.oldLine, this.newLine});
  final String text;
  // -1 removal, 1 addition, 0 context, 2 metadata.
  final int kind;
  final int? oldLine;
  final int? newLine;
}

class _DiffDocument {
  _DiffDocument(CodeEdit edit) {
    if (edit.unifiedDiff.isNotEmpty) {
      var oldLine = 1;
      var newLine = 1;
      var inHunk = false;
      final hunk = RegExp(r'^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@');
      for (final raw in _sourceLines(edit.unifiedDiff)) {
        final match = hunk.firstMatch(raw);
        if (match != null) {
          oldLine = int.parse(match.group(1)!);
          newLine = int.parse(match.group(2)!);
          inHunk = true;
          _append(raw, 2);
        } else if (raw.startsWith('diff ') ||
            raw.startsWith('index ') ||
            raw.startsWith('new file ') ||
            raw.startsWith('deleted file ') ||
            raw.startsWith('rename ') ||
            raw.startsWith('similarity ')) {
          inHunk = false;
          _append(raw, 2);
        } else if (!inHunk &&
            (raw.startsWith('--- ') || raw.startsWith('+++ '))) {
          _append(raw, 2);
        } else if (raw.startsWith('+')) {
          added++;
          _append(raw.substring(1), 1, newLine: newLine++);
        } else if (raw.startsWith('-')) {
          removed++;
          _append(raw.substring(1), -1, oldLine: oldLine++);
        } else if (raw.startsWith(' ')) {
          _append(raw.substring(1), 0, oldLine: oldLine++, newLine: newLine++);
        } else {
          _append(raw, 2);
        }
      }
    } else {
      final before = _sourceLines(edit.before);
      final after = _sourceLines(edit.after);
      var prefix = 0;
      while (prefix < before.length &&
          prefix < after.length &&
          before[prefix] == after[prefix]) {
        prefix++;
      }
      var oldEnd = before.length;
      var newEnd = after.length;
      while (oldEnd > prefix &&
          newEnd > prefix &&
          before[oldEnd - 1] == after[newEnd - 1]) {
        oldEnd--;
        newEnd--;
      }
      for (var i = 0; i < prefix; i++)
        _append(before[i], 0, oldLine: i + 1, newLine: i + 1);
      for (var i = prefix; i < oldEnd; i++) {
        removed++;
        _append(before[i], -1, oldLine: i + 1);
      }
      for (var i = prefix; i < newEnd; i++) {
        added++;
        _append(after[i], 1, newLine: i + 1);
      }
      for (var i = oldEnd; i < before.length; i++) {
        _append(before[i], 0, oldLine: i + 1, newLine: newEnd + i - oldEnd + 1);
      }
      if (edit.before != edit.after && added == 0 && removed == 0) {
        _append('行结束符已变更；复制可保留原始内容', 2);
      }
    }
  }

  final List<_DiffLine> lines = [];
  var added = 0;
  var removed = 0;

  void _append(String text, int kind, {int? oldLine, int? newLine}) {
    if (text.isEmpty) {
      lines.add(_DiffLine('', kind, oldLine: oldLine, newLine: newLine));
      return;
    }
    final slices = _TextSlices(text);
    for (var i = 0; i < slices.ranges.length; i++) {
      lines.add(
        _DiffLine(
          slices.at(i),
          kind,
          oldLine: i == 0 ? oldLine : null,
          newLine: i == 0 ? newLine : null,
        ),
      );
    }
  }

  List<_DiffLine> get preview {
    final result = <_DiffLine>[];
    var length = 0;
    for (final line in lines) {
      if (result.length >= 60 || length >= 1600) break;
      final available = 1600 - length;
      if (line.text.length > available) {
        result.add(
          _DiffLine(
            line.text.substring(0, _safeEnd(line.text, available)),
            line.kind,
            oldLine: line.oldLine,
            newLine: line.newLine,
          ),
        );
        break;
      }
      result.add(line);
      length += line.text.length + 1;
    }
    return result;
  }
}

List<String> _sourceLines(String text) {
  if (text.isEmpty) return const [];
  final lines = text.split('\n');
  if (lines.last.isEmpty) lines.removeLast();
  return [
    for (final line in lines)
      line.endsWith('\r') ? line.substring(0, line.length - 1) : line,
  ];
}

class _DiffRow extends StatelessWidget {
  const _DiffRow({super.key, required this.line, this.compact = false});
  final _DiffLine line;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final color = line.kind == 1
        ? DshPalette.of(context).good
        : line.kind == -1
        ? DshPalette.of(context).bad
        : line.kind == 2
        ? DshPalette.of(context).terminal
        : DshPalette.of(context).inkSoft;
    return ColoredBox(
      color: line.kind == 1 || line.kind == -1
          ? color.withValues(alpha: .065)
          : Colors.transparent,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 2, horizontal: 2),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 34,
              child: Text(
                line.oldLine?.toString() ?? '',
                textAlign: TextAlign.right,
                style: _codeStyle(context).copyWith(
                  color: DshPalette.of(context).inkFaint,
                  fontSize: compact ? 12 : 12.5,
                ),
              ),
            ),
            const SizedBox(width: 4),
            SizedBox(
              width: 34,
              child: Text(
                line.newLine?.toString() ?? '',
                textAlign: TextAlign.right,
                style: _codeStyle(context).copyWith(
                  color: DshPalette.of(context).inkFaint,
                  fontSize: compact ? 12 : 12.5,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                (line.kind == 1
                        ? '+ '
                        : line.kind == -1
                        ? '- '
                        : '') +
                    line.text,
                style: _codeStyle(context)
                    .copyWith(color: color, fontSize: compact ? 12 : null),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FullDiffPage extends StatefulWidget {
  const _FullDiffPage({
    required this.id,
    required this.edit,
    required this.document,
  });
  final String id;
  final CodeEdit edit;
  final _DiffDocument document;

  @override
  State<_FullDiffPage> createState() => _FullDiffPageState();
}

class _FullDiffPageState extends State<_FullDiffPage> {
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('完整改动', style: TextStyle(fontSize: 16))),
    body: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
          child: SelectableText(widget.edit.path, style: _bodyStyle(context)),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Wrap(
            children: [
              if (widget.edit.unifiedDiff.isNotEmpty)
                _CopyAction(
                  id: 'full-' + widget.id + '-diff',
                  text: widget.edit.unifiedDiff,
                  label: '复制 Diff',
                ),
              if (widget.edit.before.isNotEmpty ||
                  widget.edit.after.isNotEmpty) ...[
                _CopyAction(
                  id: 'full-' + widget.id + '-before',
                  text: widget.edit.before,
                  label: '复制修改前',
                ),
                _CopyAction(
                  id: 'full-' + widget.id + '-after',
                  text: widget.edit.after,
                  label: '复制修改后',
                ),
              ],
            ],
          ),
        ),
        Expanded(
          child: Scrollbar(
            controller: _scroll,
            child: SelectionArea(
              child: ListView.builder(
                key: ValueKey('process-full-diff-list-' + widget.id),
                controller: _scroll,
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 24),
                itemCount: widget.document.lines.length,
                itemBuilder: (context, index) => _DiffRow(
                  key: ValueKey(
                    'process-full-diff-line-' +
                        widget.id +
                        '-' +
                        index.toString(),
                  ),
                  line: widget.document.lines[index],
                ),
              ),
            ),
          ),
        ),
      ],
    ),
  );
}

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../model/tool_content.dart';
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
  const ToolProcessCard({super.key, required this.tool, this.compact = false});

  final ToolRun tool;
  final bool compact;

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
                      fontSize: 13.5,
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
        if (content != null && content.reasoning.isNotEmpty)
          _ProcessSection(
            key: ValueKey('thought-' + tool.id),
            headerKey: ValueKey('process-reasoning-' + tool.id),
            label: '思考',
            summary: _expanded.contains('reasoning')
                ? ''
                : _summary(content.reasoning),
            icon: Icons.auto_awesome_outlined,
            accent: DshPalette.of(context).thought,
            expanded: _expanded.contains('reasoning'),
            trailing: running
                ? _RunningMark(color: DshPalette.of(context).thought)
                : null,
            onToggle: () => _toggle('reasoning'),
            body: () => _TextOutput(
              id: tool.id + '-reasoning',
              label: '思考过程',
              text: content.reasoning,
              accent: DshPalette.of(context).thought,
              monospace: false,
            ),
          ),
        if (command != null)
          _ProcessSection(
            key: ValueKey('command-' + tool.id),
            headerKey: ValueKey('process-command-' + tool.id),
            label: '命令',
            summary: _summary(command.command),
            icon: Icons.terminal_rounded,
            accent: DshPalette.of(context).terminal,
            expanded: _expanded.contains('command'),
            trailing: command.exitCode != null
                ? Text(
                    '退出码 ' + command.exitCode.toString(),
                    style: TextStyle(
                      color: DshPalette.of(context).inkSoft,
                      fontSize: 12.5,
                    ),
                  )
                : running
                ? _RunningMark(color: DshPalette.of(context).terminal)
                : null,
            onToggle: () => _toggle('command'),
            body: () => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _TextOutput(
                  id: tool.id + '-command',
                  label: 'command',
                  text: command.command,
                  accent: DshPalette.of(context).terminal,
                ),
                if (command.cwd.isNotEmpty)
                  _TextOutput(
                    id: tool.id + '-cwd',
                    label: 'cwd',
                    text: command.cwd,
                    accent: DshPalette.of(context).terminal,
                  ),
                if (command.stdout.isNotEmpty)
                  _TextOutput(
                    id: tool.id + '-stdout',
                    label: 'stdout',
                    text: command.stdout,
                    accent: DshPalette.of(context).terminal,
                  ),
                if (command.stderr.isNotEmpty)
                  _TextOutput(
                    id: tool.id + '-stderr',
                    label: 'stderr',
                    text: command.stderr,
                    accent: DshPalette.of(context).bad,
                  ),
                if (command.stdout.isEmpty && command.stderr.isEmpty)
                  Padding(
                    padding: EdgeInsets.symmetric(vertical: 8),
                    child: Text('暂无输出', style: _bodyStyle(context)),
                  ),
              ],
            ),
          ),
        for (var i = 0; i < edits.length; i++)
          _EditSection(
            key: ValueKey(
              tool.id + '-edit-' + i.toString() + '-' + edits[i].path,
            ),
            id: tool.id + '-' + i.toString(),
            headerKey: ValueKey('process-diff-' + tool.id + '-' + i.toString()),
            edit: edits[i],
            expanded: _expanded.contains(
              'edit-' + i.toString() + '-' + edits[i].path,
            ),
            onToggle: () =>
                _toggle('edit-' + i.toString() + '-' + edits[i].path),
          ),
      ],
    );
  }
}

String _summary(String text) {
  final end = text.length < 160 ? text.length : 160;
  return text.substring(0, _safeEnd(text, end)).split('\n').first.trim();
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

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Semantics(
        button: true,
        expanded: expanded,
        child: Material(
          type: MaterialType.transparency,
          child: InkWell(
            key: headerKey,
            onTap: onToggle,
            borderRadius: BorderRadius.circular(8),
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 48),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 8,
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
                                fontSize: 13,
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
                                  fontSize: 12.5,
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
      AnimatedSize(
        duration: _motionFor(context),
        curve: Curves.easeOutCubic,
        alignment: Alignment.topLeft,
        child: expanded
            ? Padding(
                padding: const EdgeInsets.fromLTRB(34, 0, 10, 12),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border(
                      left: BorderSide(color: accent.withValues(alpha: .24)),
                    ),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.only(left: 12),
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
    required this.id,
    required this.label,
    required this.text,
    required this.accent,
    this.monospace = true,
  });
  final String id;
  final String label;
  final String text;
  final Color accent;
  final bool monospace;

  @override
  Widget build(BuildContext context) {
    final preview = _preview(text);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  style: TextStyle(color: accent, fontSize: 12.5),
                ),
              ),
              _CopyAction(id: id, text: text),
            ],
          ),
          SelectableText(
            preview,
            key: ValueKey('process-output-' + id),
            style: monospace ? _codeStyle(context) : _bodyStyle(context),
          ),
          if (preview.length < text.length) ...[
            Padding(
              padding: EdgeInsets.only(top: 6),
              child: Text('预览已截断 · 完整内容仍可查看或复制', style: _bodyStyle(context)),
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                key: ValueKey('process-full-' + id),
                style: _actionStyle(context),
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => _FullTextPage(
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
  minimumSize: const Size(48, 48),
  padding: const EdgeInsets.symmetric(horizontal: 8),
  foregroundColor: DshPalette.of(context).inkSoft,
  textStyle: const TextStyle(fontSize: 12.5),
);

class _CopyAction extends StatefulWidget {
  const _CopyAction({
    required this.id,
    required this.text,
    this.label = '复制全部',
  });
  final String id;
  final String text;
  final String label;

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
  Widget build(BuildContext context) => TextButton.icon(
    key: ValueKey('process-copy-' + widget.id),
    style: _actionStyle(context),
    onPressed: _busy ? null : _copy,
    icon: const Icon(Icons.copy_outlined, size: 15),
    label: Text(widget.label),
  );
}

/// Limits layout work only; callers retain the untouched source for full access.
String _preview(String text) {
  var end = text.length < 1600 ? text.length : 1600;
  var lines = 1;
  for (var i = 0; i < end; i++) {
    if (text.codeUnitAt(i) == 10 && ++lines > 60) {
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

class _FullTextPage extends StatefulWidget {
  const _FullTextPage({
    required this.id,
    required this.title,
    required this.label,
    required this.text,
    this.monospace = true,
  });
  final String id;
  final String title;
  final String label;
  final String text;
  final bool monospace;

  @override
  State<_FullTextPage> createState() => _FullTextPageState();
}

class _FullTextPageState extends State<_FullTextPage> {
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
  });
  final String id;
  final Key headerKey;
  final CodeEdit edit;
  final bool expanded;
  final VoidCallback onToggle;

  @override
  State<_EditSection> createState() => _EditSectionState();
}

class _EditSectionState extends State<_EditSection> {
  _DiffDocument? _document;

  @override
  void didUpdateWidget(covariant _EditSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.edit, widget.edit)) _document = null;
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
            SelectionArea(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [for (final line in preview) _DiffRow(line: line)],
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
  const _DiffRow({required this.line});
  final _DiffLine line;

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
                  fontSize: 12.5,
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
                  fontSize: 12.5,
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
                style: _codeStyle(context).copyWith(color: color),
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
                itemBuilder: (context, index) =>
                    _DiffRow(line: widget.document.lines[index]),
              ),
            ),
          ),
        ),
      ],
    ),
  );
}

import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';

/// Ready-to-wire assistant Markdown renderer with explicit data charts.
class AssistantRichText extends StatelessWidget {
  const AssistantRichText({super.key, required this.markdown});
  final String markdown;
  @override
  Widget build(BuildContext context) {
    final blocks = _parse(markdown);
    return Column(
      key: const ValueKey('assistant-rich-text'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < blocks.length; i++)
          _buildBlock(context, blocks[i], i),
      ],
    );
  }
}

Widget _buildBlock(BuildContext context, _Block block, int index) {
  if (block is _ChartBlock)
    return _ChartView(
      key: ValueKey('assistant-data-chart-' + block.type),
      block: block,
    );
  if (block is _TableBlock) return _TableView(block: block);
  final textBlock = block as _TextBlock;
  final text = textBlock.text;
  if (textBlock.literal) {
    return Padding(
      padding: EdgeInsets.only(top: index == 0 ? 0 : 8),
      child: SelectableText(text),
    );
  }
  if (text.trim().isEmpty) return const SizedBox(height: 8);
  final heading = RegExp(r'^\s*(#{1,3})\s+(.+)$').firstMatch(text);
  final quote = text.trimLeft().startsWith('> ');
  final bullet = RegExp(r'^\s*(?:[-*]|\d+[.)])\s+(.+)$').firstMatch(text);
  final colors = DshPalette.of(context);
  final style = TextStyle(
    color: colors.ink,
    fontSize: heading == null ? 16.0 : 20.0 - heading.group(1)!.length,
    fontWeight: heading != null ? FontWeight.w700 : FontWeight.w400,
    height: 1.55,
  );
  final content =
      heading?.group(2) ??
      (quote ? text.trim().substring(2) : bullet?.group(1) ?? text);
  final child = spansFor(content, style, colors);
  return Padding(
    key: ValueKey('assistant-markdown-block-' + index.toString()),
    padding: EdgeInsets.only(top: index == 0 ? 0 : 8),
    child: quote
        ? Container(
            padding: const EdgeInsets.only(left: 12),
            decoration: BoxDecoration(
              border: Border(left: BorderSide(color: colors.ice, width: 3)),
            ),
            child: child,
          )
        : bullet != null
        ? Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('•  ', style: style),
              Expanded(child: child),
            ],
          )
        : child,
  );
}

Widget spansFor(String text, TextStyle style, DshColors colors) {
  if (text.contains('<script')) return Text(text, style: style);
  if (!text.contains('**') && !text.contains('__') && !text.contains('`')) {
    return SelectableText(text, style: style);
  }
  return SelectableText.rich(_inline(text, style, colors));
}

TextSpan _inline(String text, TextStyle style, DshColors colors) {
  final spans = <TextSpan>[];
  final re = RegExp(r'(\*\*|__)(.+?)\1|`([^`]+)`');
  var cursor = 0;
  for (final match in re.allMatches(text)) {
    if (match.start > cursor)
      spans.add(
        TextSpan(text: text.substring(cursor, match.start), style: style),
      );
    spans.add(
      TextSpan(
        text: match.group(2) ?? match.group(3),
        style: style.copyWith(
          fontWeight: match.group(2) != null
              ? FontWeight.w700
              : FontWeight.w500,
          fontFamily: match.group(3) != null ? 'monospace' : null,
          color: match.group(3) != null ? colors.terminal : null,
        ),
      ),
    );
    cursor = match.end;
  }
  if (cursor < text.length)
    spans.add(TextSpan(text: text.substring(cursor), style: style));
  return TextSpan(children: spans);
}

List<_Block> _parse(String source) {
  final lines = source.replaceAll('\r\n', '\n').split('\n');
  final out = <_Block>[];
  var i = 0;
  var prose = <String>[];
  void flush() {
    if (prose.isNotEmpty) {
      out.add(_TextBlock(prose.join('\n')));
      prose = [];
    }
  }

  while (i < lines.length) {
    if (lines[i].trim() == '```chart') {
      flush();
      final start = i++;
      final data = <String, double>{};
      String? type;
      var valid = true;
      while (i < lines.length && lines[i].trim() != '```') {
        final line = lines[i++].trim();
        if (line.isEmpty) continue;
        final split = line.indexOf(':');
        if (split <= 0) {
          valid = false;
          continue;
        }
        final key = line.substring(0, split).trim();
        final valueText = line.substring(split + 1).trim();
        if (key == 'type') {
          if (type != null) valid = false;
          type = valueText;
        } else {
          final value = double.tryParse(valueText);
          if (value == null ||
              !value.isFinite ||
              value < 0 ||
              data.length >= 12 ||
              data.containsKey(key)) {
            valid = false;
          } else {
            data[key] = value;
          }
        }
      }
      final closed = i < lines.length;
      if (closed) i++;
      if (closed &&
          valid &&
          (type == 'comparison' || type == 'category') &&
          data.isNotEmpty) {
        out.add(_ChartBlock(type!, data));
      } else {
        out.add(_TextBlock(lines.sublist(start, i).join('\n'), literal: true));
      }
      continue;
    }
    if (i + 1 < lines.length &&
        lines[i].contains('|') &&
        RegExp(r'^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$')
            .hasMatch(lines[i + 1])) {
      flush();
      final rows = <List<String>>[_cells(lines[i])];
      i += 2;
      while (i < lines.length &&
          lines[i].contains('|') &&
          lines[i].trim().isNotEmpty) {
        rows.add(_cells(lines[i]));
        i++;
      }
      out.add(_TableBlock(rows));
      continue;
    }
    if (lines[i].trim().isEmpty) {
      flush();
      i++;
    } else {
      prose.add(lines[i]);
      i++;
    }
  }
  flush();
  return out;
}

List<String> _cells(String line) {
  final source = line.trim();
  final cells = <String>[];
  final cell = StringBuffer();
  var endsWithSeparator = false;
  for (var i = 0; i < source.length; i++) {
    final char = source[i];
    endsWithSeparator = false;
    if (char == r'\' &&
        i + 1 < source.length &&
        (source[i + 1] == '|' || source[i + 1] == r'\')) {
      cell.write(source[++i]);
    } else if (char == '|') {
      endsWithSeparator = true;
      if (i != 0) cells.add(cell.toString().trim());
      cell.clear();
    } else {
      cell.write(char);
    }
  }
  if (!endsWithSeparator) cells.add(cell.toString().trim());
  return cells;
}

abstract class _Block {
  const _Block();
}

class _TextBlock extends _Block {
  const _TextBlock(this.text, {this.literal = false});
  final String text;
  final bool literal;
}

class _TableBlock extends _Block {
  const _TableBlock(this.rows);
  final List<List<String>> rows;
}

class _ChartBlock extends _Block {
  const _ChartBlock(this.type, this.values);
  final String type;
  final Map<String, double> values;
}

class _TableView extends StatelessWidget {
  const _TableView({required this.block});
  final _TableBlock block;
  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final width = block.rows.first.length;
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: DecoratedBox(
          key: const ValueKey('assistant-markdown-table'),
          decoration: BoxDecoration(
            border: Border.all(color: colors.line),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Table(
            defaultColumnWidth: const IntrinsicColumnWidth(),
            border: TableBorder.symmetric(
              inside: BorderSide(color: colors.line),
            ),
            children: [
              for (var row = 0; row < block.rows.length; row++)
                TableRow(
                  decoration: BoxDecoration(
                    color: row == 0 ? colors.surfaceRaised : null,
                  ),
                  children: [
                    for (var cell = 0; cell < width; cell++)
                      Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 8,
                        ),
                        child: spansFor(
                          cell < block.rows[row].length
                              ? block.rows[row][cell]
                              : '',
                          TextStyle(
                            color: colors.ink,
                            fontSize: 14,
                            fontWeight: row == 0
                                ? FontWeight.w700
                                : FontWeight.w400,
                          ),
                          colors,
                        ),
                      ),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ChartView extends StatelessWidget {
  const _ChartView({super.key, required this.block});
  final _ChartBlock block;
  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final max = block.values.values.reduce((a, b) => a > b ? a : b);
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: colors.surface.withValues(alpha: .7),
          border: Border.all(color: colors.line),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final entry in block.values.entries)
              Padding(
                key: ValueKey('assistant-chart-bar-' + entry.key),
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  children: [
                    SizedBox(
                      width: 64,
                      child: Text(
                        entry.key,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: colors.inkSoft, fontSize: 13),
                      ),
                    ),
                    Expanded(
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(3),
                        child: LinearProgressIndicator(
                          value: max == 0 ? 0 : entry.value / max,
                          minHeight: 10,
                          backgroundColor: colors.surfaceRaised,
                          color: block.type == 'comparison'
                              ? colors.ice
                              : colors.terminal,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      _number(entry.value),
                      style: TextStyle(
                        color: colors.ink,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}

String _number(double value) => value == value.roundToDouble()
    ? value.toInt().toString()
    : value.toString();

import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';
import '../transport/native_file_delivery_service.dart';

Future<void> showFilePreviewSheet(
  BuildContext context,
  FilePreviewData data, {
  required VoidCallback onOpen,
  required VoidCallback onShare,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: DshPalette.of(context).paperDeep,
    constraints: BoxConstraints(
      maxHeight: MediaQuery.sizeOf(context).height * .88,
    ),
    builder: (sheetContext) {
      final palette = DshPalette.of(sheetContext);
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: palette.inkFaint,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  Icon(
                    data.isImage
                        ? Icons.image_outlined
                        : Icons.description_outlined,
                    color: palette.ice,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          data.displayName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.ink,
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        Text(
                          data.truncated ? 'DSH 预览 · 已截取安全范围' : 'DSH 预览',
                          style: TextStyle(
                            color: palette.inkSoft,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    key: const ValueKey('file-preview-open'),
                    tooltip: '用其他应用打开',
                    onPressed: () {
                      Navigator.of(sheetContext).pop();
                      onOpen();
                    },
                    icon: const Icon(Icons.open_in_new_rounded),
                  ),
                  IconButton(
                    key: const ValueKey('file-preview-share'),
                    tooltip: '分享文件',
                    onPressed: () {
                      Navigator.of(sheetContext).pop();
                      onShare();
                    },
                    icon: const Icon(Icons.ios_share_rounded),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Flexible(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: palette.terminal.withValues(alpha: .12),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: palette.line),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(14),
                    child: data.isImage
                        ? InteractiveViewer(
                            minScale: .8,
                            maxScale: 4,
                            child: Image.memory(
                              data.bytes!,
                              key: const ValueKey('file-preview-image'),
                              fit: BoxFit.contain,
                              width: double.infinity,
                              errorBuilder: (_, _, _) =>
                                  const Center(child: Text('图片无法解码')),
                            ),
                          )
                        : SingleChildScrollView(
                            padding: const EdgeInsets.all(14),
                            child: SelectionArea(
                              child: Text.rich(
                                _highlight(data.text ?? '', palette),
                                key: const ValueKey('file-preview-text'),
                                style: TextStyle(
                                  color: palette.ink,
                                  fontFamily: 'monospace',
                                  fontSize: 12.5,
                                  height: 1.55,
                                ),
                              ),
                            ),
                          ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    },
  );
}

TextSpan _highlight(String source, DshColors palette) {
  final tokens = RegExp(
    r'''("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|//[^\n]*|#[^\n]*|\b(?:true|false|null|class|final|const|return|import|if|else|for|while)\b|\b\d+(?:\.\d+)?\b)''',
  );
  final children = <TextSpan>[];
  var cursor = 0;
  for (final match in tokens.allMatches(source)) {
    if (match.start > cursor)
      children.add(TextSpan(text: source.substring(cursor, match.start)));
    final token = match.group(0)!;
    final color = token.startsWith('//') || token.startsWith('#')
        ? palette.good
        : token.startsWith('"') || token.startsWith("'")
        ? palette.warn
        : palette.ice;
    children.add(
      TextSpan(
        text: token,
        style: TextStyle(color: color),
      ),
    );
    cursor = match.end;
  }
  if (cursor < source.length)
    children.add(TextSpan(text: source.substring(cursor)));
  return TextSpan(children: children);
}

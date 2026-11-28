import 'package:material_ui/material_ui.dart';

import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import 'staggered_reveal.dart';

class FileDelivery {
  const FileDelivery({required this.displayName, required this.target});

  final String displayName;
  final String target;

  String get type {
    final path = target.split(RegExp(r'[?#]')).first;
    final name = path.split(RegExp(r'[/\\]')).last;
    final dot = name.lastIndexOf('.');
    return dot > 0 && dot < name.length - 1
        ? name.substring(dot + 1).toUpperCase()
        : '文件';
  }
}

enum FileDeliveryAction { preview, openExternal }

typedef FileDeliveryTap = void Function(
  FileDelivery file,
  FileDeliveryAction action,
);
typedef FileDeliveryShare = void Function(FileDelivery file);

/// Finds explicit inline file links outside fenced/inline code samples.
List<FileDelivery> extractFileDeliveries(String text) {
  final result = <FileDelivery>[];
  final seen = <String>{};
  final link = RegExp(
    r'(?<!!)\[([^\]\n]+)\]\((<[^>\n]+>|(?:[^()\n]|\([^()\n]*\))+)\)',
  );
  final extension = RegExp(r'\.[A-Za-z0-9][A-Za-z0-9_-]*$');
  String? fence;
  for (var line in text.split('\n')) {
    final marker = RegExp(
      r'^ {0,3}('
      r'\x60'
      r'{3,}|~{3,})',
    ).firstMatch(line);
    if (marker != null) {
      final opening = marker.group(1)!;
      if (fence == null) {
        fence = opening;
      } else if (opening[0] == fence[0] && opening.length >= fence.length) {
        fence = null;
      }
      continue;
    }
    if (fence != null || line.startsWith('    ') || line.startsWith('\t'))
      continue;
    line = line.replaceAll(RegExp(r'\x60+[^\x60\n]*\x60+'), '');
    for (final match in link.allMatches(line)) {
      var target = match.group(2)!.trim();
      if (target.startsWith('<') && target.endsWith('>'))
        target = target.substring(1, target.length - 1);
      final resource = target.startsWith('dsh-resource://file/');
      final fileUri = target.startsWith('file:');
      final windows = RegExp(r'^[A-Za-z]:[/\\]').hasMatch(target);
      final uri = Uri.tryParse(target);
      if (!resource &&
          !fileUri &&
          !windows &&
          (uri == null || uri.hasScheme || target.startsWith('//')))
        continue;
      final name = target
          .split(RegExp(r'[?#]'))
          .first
          .split(RegExp(r'[/\\]'))
          .last;
      if (!resource && !extension.hasMatch(name)) continue;
      if (seen.add(target))
        result.add(FileDelivery(displayName: match.group(1)!, target: target));
    }
  }
  return result;
}

class FileDeliveryCard extends StatelessWidget {
  const FileDeliveryCard({
    super.key,
    required this.file,
    this.onFileTap,
    this.onFileShare,
  });

  final FileDelivery file;
  final FileDeliveryTap? onFileTap;
  final FileDeliveryShare? onFileShare;

  Future<void> _showActions(BuildContext context) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      builder: (sheetContext) {
        final palette = DshPalette.of(sheetContext);
        return Material(
          color: palette.paper,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
          clipBehavior: Clip.antiAlias,
          child: SafeArea(
            top: false,
            child: SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
                      child: Text(
                        file.displayName,
                        style: TextStyle(
                          color: palette.ink,
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    StaggeredReveal(
                      children: [
                        ListTile(
                          key: const ValueKey('file-delivery-preview'),
                          leading: const Icon(Icons.visibility_outlined),
                          title: const Text('在 DSH 中预览'),
                          onTap: onFileTap == null
                              ? null
                              : () {
                                  Navigator.of(sheetContext).pop();
                                  onFileTap!(file, FileDeliveryAction.preview);
                                },
                        ),
                        ListTile(
                          key: const ValueKey('file-delivery-open-external'),
                          leading: const Icon(Icons.open_in_new_rounded),
                          title: const Text('用其他应用打开'),
                          onTap: onFileTap == null
                              ? null
                              : () {
                                  Navigator.of(sheetContext).pop();
                                  onFileTap!(
                                    file,
                                    FileDeliveryAction.openExternal,
                                  );
                                },
                        ),
                        ListTile(
                          key: const ValueKey('file-delivery-share'),
                          leading: const Icon(Icons.share_outlined),
                          title: const Text('分享文件'),
                          onTap: onFileShare == null
                              ? null
                              : () {
                                  Navigator.of(sheetContext).pop();
                                  onFileShare!(file);
                                },
                        ),
                      ],
                    ),
                    TextButton(
                      key: const ValueKey('file-delivery-cancel'),
                      onPressed: () => Navigator.of(sheetContext).pop(),
                      child: const Text('取消'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    return Material(
      color: palette.paperDeep,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: palette.line),
        borderRadius: BorderRadius.circular(8),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: ValueKey('file-delivery-open-${file.target}'),
        onTap: () => _showActions(context),
        child: ConstrainedBox(
          constraints: BoxConstraints(
            minHeight: DshDisplayScope.minTargetOf(context),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              children: [
                Icon(
                  Icons.insert_drive_file_outlined,
                  size: 20,
                  color: palette.inkSoft,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        file.displayName,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.ink,
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Text(
                        '${file.type} · 打开选项',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: palette.inkSoft, fontSize: 12),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Icon(
                  Icons.more_horiz_rounded,
                  size: 20,
                  color: palette.inkSoft,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

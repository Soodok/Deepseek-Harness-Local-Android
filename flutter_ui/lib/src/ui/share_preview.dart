import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../bridge/host_bridge.dart';
import '../theme/dsh_theme.dart';

/// Shows what would leave the app before anything does.
///
/// Nothing is uploaded and no chooser opens by itself: the person reads the
/// exact text here, then copies it, saves it through the platform file picker,
/// or hands it to the system share sheet.
/// @param context - build context.
/// @param text - the transcript text to preview.
/// @param title - subject line offered to the share sheet and file name hint.
Future<void> showSharePreview(
  BuildContext context, {
  required String text,
  String title = '对话内容',
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (context) => FractionallySizedBox(
      heightFactor: 0.86,
      child: _SharePreview(text: text, title: title),
    ),
  );
}

class _SharePreview extends StatelessWidget {
  const _SharePreview({required this.text, required this.title});

  final String text;
  final String title;

  Future<void> _share(BuildContext context) async {
    final ok = await HostBridge.shareText(text, subject: title);
    if (!context.mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('没有打开分享面板，可以先复制或保存到文件')),
      );
    }
  }

  Future<void> _save(BuildContext context) async {
    final ok = await HostBridge.saveText(
      text,
      suggestedName: title.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_') + '.txt',
    );
    if (!context.mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('没有打开保存位置选择器')),
      );
    }
  }

  Future<void> _copy(BuildContext context) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已复制')));
  }

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    return Container(
      decoration: BoxDecoration(
        color: palette.paper,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 14, 8, 6),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      title,
                      key: const ValueKey('share-title'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.ink,
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  IconButton(
                    key: const ValueKey('share-close'),
                    tooltip: '关闭',
                    onPressed: () => Navigator.of(context).pop(),
                    icon: Icon(Icons.close_rounded, color: palette.inkSoft),
                  ),
                ],
              ),
            ),
            Expanded(
              child: Container(
                margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: palette.paperDeep,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: palette.line),
                ),
                child: SingleChildScrollView(
                  child: SelectableText(
                    text,
                    key: const ValueKey('share-body'),
                    style: TextStyle(
                      color: palette.ink,
                      fontSize: 14.5,
                      height: 1.55,
                    ),
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: Row(
                children: [
                  Expanded(
                    child: TextButton.icon(
                      key: const ValueKey('share-copy'),
                      onPressed: () => _copy(context),
                      icon: const Icon(Icons.copy_rounded, size: 18),
                      label: const Text('复制'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextButton.icon(
                      key: const ValueKey('share-save'),
                      onPressed: () => _save(context),
                      icon: const Icon(Icons.download_rounded, size: 18),
                      label: const Text('保存'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton.icon(
                      key: const ValueKey('share-system'),
                      onPressed: () => _share(context),
                      icon: const Icon(Icons.ios_share_rounded, size: 18),
                      label: const Text('分享'),
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
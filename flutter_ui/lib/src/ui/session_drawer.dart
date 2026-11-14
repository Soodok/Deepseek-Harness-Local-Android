import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';

/// Session navigation. Multi-session listing and creation are not wired to a
/// backend yet, so "新建会话" stays disabled unless [onNewSession] is provided
/// and only the current session is shown.
class SessionDrawer extends StatelessWidget {
  const SessionDrawer({
    super.key,
    this.onNewSession,
    this.onShowcase,
    this.currentTitle,
    this.isFixture = false,
  });

  final VoidCallback? onNewSession;
  final VoidCallback? onShowcase;
  final String? currentTitle;
  final bool isFixture;

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final title = (currentTitle == null || currentTitle!.trim().isEmpty)
        ? '新的对话'
        : currentTitle!.trim();
    return Drawer(
      width: width < 420 ? width * 0.84 : 320,
      backgroundColor: DshPalette.paperDeep,
      shape: const RoundedRectangleBorder(),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 8, 6),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'DSH',
                      style: TextStyle(
                        color: DshPalette.ink,
                        fontSize: 17,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 1.2,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭会话抽屉',
                    icon: const Icon(Icons.close_rounded, size: 20),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 0, 22, 10),
              child: Text(
                isFixture ? '本机演示 · 尚未连接真实引擎' : '本机引擎',
                style: const TextStyle(
                  color: DshPalette.inkFaint,
                  fontSize: 12,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: TextButton.icon(
                onPressed: onNewSession,
                style: TextButton.styleFrom(
                  alignment: Alignment.centerLeft,
                  foregroundColor: DshPalette.ink,
                  disabledForegroundColor: DshPalette.inkFaint,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 12,
                  ),
                  textStyle: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                icon: const Icon(Icons.edit_note_rounded, size: 21),
                label: const Text('新建会话'),
              ),
            ),
            const Padding(
              padding: EdgeInsets.fromLTRB(22, 18, 22, 8),
              child: Text(
                '当前',
                style: TextStyle(
                  color: DshPalette.inkFaint,
                  fontSize: 12,
                  letterSpacing: 0.4,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Material(
                color: DshPalette.surface,
                borderRadius: BorderRadius.circular(10),
                child: InkWell(
                  borderRadius: BorderRadius.circular(10),
                  onTap: () => Navigator.of(context).pop(),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 11,
                    ),
                    child: Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: DshPalette.ink,
                        fontSize: 14.5,
                      ),
                    ),
                  ),
                ),
              ),
            ),
            if (onShowcase != null)
              ListTile(
                key: const ValueKey('tool-showcase'),
                leading: const Icon(Icons.widgets_outlined, size: 20),
                title: const Text('工具展示演示', style: TextStyle(fontSize: 14)),
                onTap: () {
                  Navigator.of(context).pop();
                  onShowcase!();
                },
              ),
            const Spacer(),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 14, 22, 18),
              child: Text(
                isFixture ? '演示数据 · 尚未连接真实引擎，会话列表暂不可用' : '会话列表暂不可用',
                style: const TextStyle(
                  color: DshPalette.inkFaint,
                  fontSize: 12,
                  height: 1.5,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

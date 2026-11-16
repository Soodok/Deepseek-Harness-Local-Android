import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';
import 'overflow_title.dart';
import 'settings/account_footer.dart';

/// Session navigation. Multi-session listing and creation are not wired to a
/// backend yet, so "新建会话" stays disabled unless [onNewSession] is provided
/// and only the current session is shown.
class SessionDrawer extends StatelessWidget {
  const SessionDrawer({
    super.key,
    this.onNewSession,
    this.onOpenSettings,
    this.onShowcase,
    this.onOpenEngineSessions,
    this.currentTitle,
    this.isFixture = false,
  });

  final VoidCallback? onNewSession;
  final VoidCallback? onOpenSettings;
  final VoidCallback? onShowcase;
  final VoidCallback? onOpenEngineSessions;
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
      backgroundColor: DshPalette.of(context).paperDeep,
      shape: const RoundedRectangleBorder(),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 8, 6),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      'DSH',
                      style: TextStyle(
                        color: DshPalette.of(context).ink,
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
                style: TextStyle(
                  color: DshPalette.of(context).inkFaint,
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
                  foregroundColor: DshPalette.of(context).ink,
                  disabledForegroundColor: DshPalette.of(context).inkFaint,
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
            Padding(
              padding: EdgeInsets.fromLTRB(22, 18, 22, 8),
              child: Text(
                '当前',
                style: TextStyle(
                  color: DshPalette.of(context).inkFaint,
                  fontSize: 12,
                  letterSpacing: 0.4,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Material(
                color: DshPalette.of(context).surface,
                borderRadius: BorderRadius.circular(10),
                child: InkWell(
                  borderRadius: BorderRadius.circular(10),
                  onTap: () => Navigator.of(context).pop(),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 11,
                    ),
                    child: OverflowTitle(
                      text: title,
                      style: TextStyle(
                        color: DshPalette.of(context).ink,
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
            if (onOpenEngineSessions != null)
              ListTile(
                key: const ValueKey('engine-sessions'),
                leading: const Icon(Icons.hub_outlined),
                title: const Text('连接本机引擎'),
                onTap: () {
                  Navigator.of(context).pop();
                  onOpenEngineSessions!();
                },
              ),
            const Spacer(),
            if (DshAppearance.maybeOf(context) case final appearance?)
              PopupMenuButton<ThemeMode>(
                key: const ValueKey('theme-selector'),
                tooltip: '外观',
                onSelected: appearance.onChanged,
                itemBuilder: (context) => [
                  for (final mode in ThemeMode.values)
                    PopupMenuItem<ThemeMode>(
                      key: ValueKey('theme-' + mode.name),
                      value: mode,
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(switch (mode) {
                              ThemeMode.light => '浅色',
                              ThemeMode.dark => '深色',
                              ThemeMode.system => '跟随系统',
                            }),
                          ),
                          if (appearance.mode == mode)
                            const Icon(Icons.check_rounded, size: 18),
                        ],
                      ),
                    ),
                ],
                child: ListTile(
                  leading: const Icon(Icons.palette_outlined),
                  title: const Text('外观'),
                  subtitle: Text(switch (appearance.mode) {
                    ThemeMode.light => '浅色',
                    ThemeMode.dark => '深色',
                    ThemeMode.system => '跟随系统',
                  }),
                  trailing: const Icon(Icons.unfold_more_rounded, size: 18),
                ),
              ),
            if (DshAppearance.maybeOf(context) case final appearance?)
              PopupMenuButton<DshMood>(
                key: const ValueKey('mood-selector'),
                tooltip: '配色',
                onSelected: appearance.onMoodChanged,
                itemBuilder: (context) => [
                  for (final mood in DshMood.values)
                    PopupMenuItem<DshMood>(
                      key: ValueKey('mood-' + mood.name),
                      value: mood,
                      child: Row(
                        children: [
                          Container(
                            width: 18,
                            height: 18,
                            decoration: BoxDecoration(
                              color: DshPalette.colorsFor(
                                mood,
                                Theme.of(context).brightness,
                              ).userWash,
                              shape: BoxShape.circle,
                              border: Border.all(
                                color: DshPalette.colorsFor(
                                  mood,
                                  Theme.of(context).brightness,
                                ).ice,
                              ),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(child: Text(mood.label)),
                          if (appearance.mood == mood)
                            const Icon(Icons.check_rounded, size: 18),
                        ],
                      ),
                    ),
                ],
                child: ListTile(
                  leading: const Icon(Icons.color_lens_outlined),
                  title: const Text('自然配色'),
                  subtitle: Text(appearance.mood.label),
                  trailing: const Icon(Icons.unfold_more_rounded, size: 18),
                ),
              ),
            if (onOpenSettings != null)
              AccountFooter(onOpenSettings: onOpenSettings!),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 14, 22, 18),
              child: Text(
                isFixture ? '演示数据 · 尚未连接真实引擎，会话列表暂不可用' : '会话列表暂不可用',
                style: TextStyle(
                  color: DshPalette.of(context).inkFaint,
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

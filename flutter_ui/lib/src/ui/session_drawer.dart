import 'package:material_ui/material_ui.dart';

import '../model/engine_session.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import 'overflow_title.dart';
import 'settings/account_footer.dart';

/// Session navigation over the Host's real Session catalogue.
///
/// Rows carry a human title only — a Session identity or a working directory is
/// never rendered, because a person should not have to pick a technical handle.
class SessionDrawer extends StatelessWidget {
  const SessionDrawer({
    super.key,
    this.onNewSession,
    this.onOpenSettings,
    this.onShowcase,
    this.onOpenEngineSessions,
    this.onOpenSession,
    this.currentTitle,
    this.currentSessionId,
    this.sessions = const [],
    this.sessionsLoading = false,
    this.sessionsError,
    this.onRefreshSessions,
    this.isFixture = false,
    this.sessionsLabel = '连接本机引擎',
  });

  final VoidCallback? onNewSession;
  final VoidCallback? onOpenSettings;
  final VoidCallback? onShowcase;
  final VoidCallback? onOpenEngineSessions;

  /// Opens one catalogue row. Null keeps the rows read-only.
  final ValueChanged<EngineSession>? onOpenSession;
  final String? currentTitle;

  /// Identity of the open Session, used only to mark the current row.
  final String? currentSessionId;
  final List<EngineSession> sessions;
  final bool sessionsLoading;
  final String? sessionsError;
  final VoidCallback? onRefreshSessions;
  final bool isFixture;

  /// A hosted Android conversation lists sessions in place, so the entry keeps
  /// its identity as the session list. The fixture preview keeps the older
  /// wording, where it really links to the host engine.
  final String sessionsLabel;

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
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      '会话',
                      style: TextStyle(
                        color: DshPalette.of(context).inkFaint,
                        fontSize: 12,
                        letterSpacing: 0.4,
                      ),
                    ),
                  ),
                  if (onRefreshSessions != null)
                    SizedBox(
                      height: DshDisplayScope.atLeastTarget(context, 30),
                      child: TextButton(
                        key: const ValueKey('sessions-refresh'),
                        onPressed: sessionsLoading ? null : onRefreshSessions,
                        style: TextButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          minimumSize: Size.zero,
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                        child: Text(
                          sessionsLoading ? '刷新中…' : '刷新',
                          style: const TextStyle(fontSize: 12),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            Flexible(
              flex: 2,
              child: _SessionList(
                sessions: sessions,
                currentSessionId: currentSessionId,
                currentTitle: title,
                loading: sessionsLoading,
                error: sessionsError,
                onOpen: onOpenSession,
                onRetry: onRefreshSessions,
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
                title: Text(sessionsLabel),
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
                isFixture
                    ? '演示数据 · 尚未连接真实引擎'
                    : (sessionsError == null ? '会话来自本机引擎' : '会话列表读取失败'),
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
/// The catalogue rows plus their loading, empty, and failure states.
///
/// A failure keeps the retry entry instead of rendering an empty success: an
/// unreadable catalogue is reported as such.
class _SessionList extends StatelessWidget {
  const _SessionList({
    required this.sessions,
    required this.currentSessionId,
    required this.currentTitle,
    required this.loading,
    required this.error,
    required this.onOpen,
    required this.onRetry,
  });

  final List<EngineSession> sessions;
  final String? currentSessionId;
  final String currentTitle;
  final bool loading;
  final String? error;
  final ValueChanged<EngineSession>? onOpen;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    if (error != null && sessions.isEmpty) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(22, 4, 22, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              error!,
              key: const ValueKey('sessions-error'),
              style: TextStyle(color: palette.bad, fontSize: 12.5, height: 1.5),
            ),
            if (onRetry != null)
              TextButton(
                key: const ValueKey('sessions-retry'),
                onPressed: loading ? null : onRetry,
                child: const Text('重试'),
              ),
          ],
        ),
      );
    }
    if (sessions.isEmpty) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(22, 4, 22, 8),
        child: Text(
          loading ? '正在读取会话…' : '还没有会话，新建一个就开始吧',
          style: TextStyle(color: palette.inkFaint, fontSize: 12.5),
        ),
      );
    }
    return ListView.builder(
      key: const ValueKey('session-rows'),
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
      itemCount: sessions.length,
      itemBuilder: (context, index) {
        final row = sessions[index];
        final current = row.id == currentSessionId;
        final name = current && (row.title ?? '').trim().isEmpty
            ? currentTitle
            : row.displayName;
        return Padding(
          padding: const EdgeInsets.only(bottom: 2),
          child: Material(
            color: current ? palette.surface : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
            child: InkWell(
              key: ValueKey('session-row-' + row.id),
              borderRadius: BorderRadius.circular(9),
              onTap: onOpen == null
                  ? null
                  : () {
                      Navigator.of(context).pop();
                      onOpen!(row);
                    },
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 10,
                ),
                child: Row(
                  children: [
                    if (row.running)
                      Padding(
                        padding: const EdgeInsets.only(right: 6),
                        child: SizedBox(
                          width: 8,
                          height: 8,
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: palette.ice,
                              shape: BoxShape.circle,
                            ),
                          ),
                        ),
                      ),
                    Expanded(
                      child: OverflowTitle(
                        text: name,
                        style: TextStyle(
                          color: current ? palette.ink : palette.inkSoft,
                          fontSize: 14,
                          fontWeight: current ? FontWeight.w600 : FontWeight.w400,
                        ),
                      ),
                    ),
                    if (!row.openable)
                      Tooltip(
                        message: '该会话需要先在 Web 完成迁移',
                        child: Icon(
                          Icons.lock_clock_outlined,
                          size: 15,
                          color: palette.inkFaint,
                        ),
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
}

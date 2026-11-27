import 'package:material_ui/material_ui.dart';

import '../model/engine_session.dart';
import '../model/engine_workspace.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import 'overflow_title.dart';
import 'settings/account_footer.dart';

/// The hosted drawer receives live grouping without coupling the ChatShell
/// to the workspace feed or its connection lifecycle.
class SessionWorkspaceScope extends InheritedWidget {
  const SessionWorkspaceScope({
    super.key,
    required this.catalog,
    required this.online,
    required this.error,
    required this.onCreate,
    required super.child,
  });

  final EngineWorkspaceCatalog catalog;
  final bool online;
  final String? error;
  final ValueChanged<EngineWorkspace?>? onCreate;

  static SessionWorkspaceScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<SessionWorkspaceScope>();

  @override
  bool updateShouldNotify(SessionWorkspaceScope oldWidget) => true;
}

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
    final workspaces = SessionWorkspaceScope.maybeOf(context);
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
            // 品牌标题固定且克制；当前会话名称保留在列表首行，避免重复占据顶部。
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 14, 8, 4),
              child: Row(
                children: [
                  Expanded(
                    child: OverflowTitle(
                      key: const ValueKey('drawer-title'),
                      text: 'DSH Mobile',
                      style: TextStyle(
                        color: DshPalette.of(context).ink,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
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
            if (isFixture)
              Padding(
                padding: const EdgeInsets.fromLTRB(22, 0, 22, 8),
                child: Text(
                  '本机演示 · 尚未连接真实引擎',
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
              padding: const EdgeInsets.fromLTRB(22, 4, 22, 4),
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
            if (workspaces?.error case final error?)
              Padding(
                padding: const EdgeInsets.fromLTRB(22, 0, 22, 4),
                child: Text(
                  error,
                  style: TextStyle(
                    color: DshPalette.of(context).bad,
                    fontSize: 12,
                  ),
                ),
              ),
            Expanded(
              child: _SessionList(
                sessions: sessions,
                currentSessionId: currentSessionId,
                currentTitle: title,
                loading: sessionsLoading,
                error: sessionsError,
                onOpen: onOpenSession,
                onRetry: onRefreshSessions,
                workspaceScope: workspaces,
              ),
            ),
            // 抽屉只留「新建会话 + 会话列表」：工具展示、引擎跳转这类入口
            // 不属于「我在哪个会话」，混进来只会让列表变矮。
            if (onShowcase != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(22, 4, 22, 0),
                child: TextButton(
                  key: const ValueKey('tool-showcase'),
                  onPressed: () {
                    Navigator.of(context).pop();
                    onShowcase!();
                  },
                  child: const Text('工具展示演示'),
                ),
              ),
            if (onOpenEngineSessions != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(22, 0, 22, 4),
                child: TextButton(
                  key: const ValueKey('engine-sessions'),
                  onPressed: () {
                    Navigator.of(context).pop();
                    onOpenEngineSessions!();
                  },
                  child: Text(sessionsLabel),
                ),
              ),
            // 外观与自然配色已经搬进「设置 · 个性化主题」，这里不再重复一份：
            // 同一个开关有两个入口，两边显示的当前值迟早会不一致。
            if (onOpenSettings != null)
              AccountFooter(onOpenSettings: onOpenSettings!),
            const Divider(height: 1),
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
class _SessionList extends StatefulWidget {
  const _SessionList({
    required this.sessions,
    required this.currentSessionId,
    required this.currentTitle,
    required this.loading,
    required this.error,
    required this.onOpen,
    required this.onRetry,
    required this.workspaceScope,
  });

  final List<EngineSession> sessions;
  final String? currentSessionId;
  final String currentTitle;
  final bool loading;
  final String? error;
  final ValueChanged<EngineSession>? onOpen;
  final VoidCallback? onRetry;
  final SessionWorkspaceScope? workspaceScope;

  @override
  State<_SessionList> createState() => _SessionListState();
}

class _SessionListState extends State<_SessionList> {
  final _collapsed = <String>{};

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    final error = widget.error;
    if (error != null && widget.sessions.isEmpty) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(22, 4, 22, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              error,
              key: const ValueKey('sessions-error'),
              style: TextStyle(color: palette.bad, fontSize: 12.5, height: 1.5),
            ),
            if (widget.onRetry != null)
              TextButton(
                key: const ValueKey('sessions-retry'),
                onPressed: widget.loading ? null : widget.onRetry,
                child: const Text('重试'),
              ),
          ],
        ),
      );
    }
    final scope = widget.workspaceScope;
    final workspaces = scope?.catalog.workspaces ?? const <EngineWorkspace>[];
    if (widget.sessions.isEmpty && workspaces.isEmpty) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(22, 4, 22, 8),
        child: Text(
          widget.loading ? '正在读取会话…' : '还没有会话，新建一个就开始吧',
          style: TextStyle(color: palette.inkFaint, fontSize: 12.5),
        ),
      );
    }
    final byId = {for (final row in widget.sessions) row.id: row};
    final archived = scope?.catalog.archived ?? const <String>{};
    final listed = <String>{};
    final entries = <({EngineWorkspace? workspace, EngineSession? session})>[];
    for (final workspace in workspaces) {
      entries.add((workspace: workspace, session: null));
      final collapsed = _collapsed.contains(workspace.id);
      for (final id in workspace.sessionIds) {
        final session = byId[id];
        if (session != null && !archived.contains(id) && listed.add(id)) {
          if (!collapsed) entries.add((workspace: null, session: session));
        }
      }
    }
    final ungrouped = widget.sessions.where(
      (row) => !archived.contains(row.id) && !listed.contains(row.id),
    );
    if (workspaces.isNotEmpty && ungrouped.isNotEmpty) {
      entries.add((workspace: null, session: null));
    }
    entries.addAll(ungrouped.map((row) => (workspace: null, session: row)));
    return ListView.builder(
      key: const ValueKey('session-rows'),
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
      itemCount: entries.length,
      itemBuilder: (context, index) {
        final entry = entries[index];
        final row = entry.session;
        if (row == null) {
          final workspace = entry.workspace;
          final label = workspace?.title ?? '未分组';
          final create = scope?.online == true ? scope?.onCreate : null;
          final workspaceId = workspace?.id ?? 'ungrouped';
          final collapsed =
              workspace != null && _collapsed.contains(workspace.id);
          return Material(
            color: Colors.transparent,
            child: InkWell(
              key: ValueKey('workspace-collapse-' + workspaceId),
              onTap: workspace == null
                  ? null
                  : () => setState(() {
                      if (collapsed) {
                        _collapsed.remove(workspace.id);
                      } else {
                        _collapsed.add(workspace.id);
                      }
                    }),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                child: Row(
                  children: [
                    Icon(
                      collapsed
                          ? Icons.chevron_right_rounded
                          : Icons.expand_more_rounded,
                      size: 18,
                      color: palette.inkFaint,
                    ),
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 4),
                        child: Text(
                          label,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.inkFaint,
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                    IconButton(
                      key: ValueKey('new-session-workspace-' + workspaceId),
                      tooltip: '在「' + label + '」新建会话',
                      icon: const Icon(Icons.add_rounded, size: 18),
                      onPressed: create == null
                          ? null
                          : () {
                              Navigator.of(context).pop();
                              create(workspace);
                            },
                    ),
                  ],
                ),
              ),
            ),
          );
        }
        final current = row.id == widget.currentSessionId;
        final name = current && (row.title ?? '').trim().isEmpty
            ? widget.currentTitle
            : row.displayName;
        return Padding(
          padding: const EdgeInsets.only(bottom: 2),
          child: Material(
            color: current ? palette.surface : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
            child: InkWell(
              key: ValueKey('session-row-' + row.id),
              borderRadius: BorderRadius.circular(9),
              onTap: widget.onOpen == null || !row.openable
                  ? null
                  : () {
                      Navigator.of(context).pop();
                      widget.onOpen!(row);
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
                          fontWeight: current
                              ? FontWeight.w600
                              : FontWeight.w400,
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

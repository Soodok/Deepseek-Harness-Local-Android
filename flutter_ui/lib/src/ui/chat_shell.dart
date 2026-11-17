import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../model/engine_session.dart';
import '../bridge/host_bridge.dart';
import 'glass_panel.dart';
import 'overflow_title.dart';
import 'runtime_panel.dart';
import 'subagent_panel.dart';
import '../state/chat_controller.dart';
import '../state/composer_draft.dart';
import '../state/conversation_actions.dart';
import '../state/message_actions.dart';
import '../state/turn_plan.dart';
import 'retract_dialog.dart';
import 'share_preview.dart';
import 'settings/settings_home.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import 'composer.dart';
import 'message_list.dart';
import 'session_drawer.dart';
import '../transport/fixture_transport.dart';

/// Horizontal geometry shared by the message column and the composer so the
/// reading edge and the input edge line up at every width.
class ChatLayout {
  const ChatLayout._();

  static const double maxColumn = 720;

  static double gutter(double width) {
    if (width < 380) return 14;
    if (width < 600) return 18;
    return 28;
  }
}

class ChatShell extends StatefulWidget {
  const ChatShell({
    super.key,
    required this.controller,
    this.onOpenWebFallback,
    this.onOpenEngineSessions,
    this.onNewSession,
    this.onOpenSession,
    this.onRefreshSessions,
    this.sessions = const [],
    this.sessionsLoading = false,
    this.sessionsError,
    this.currentSessionId,
    this.hostInfo,
    this.disposeController = false,
    this.actions,
    this.onOpenForkSession,
    this.draft,
  });

  final ChatController controller;
  final VoidCallback? onOpenWebFallback;
  final VoidCallback? onOpenEngineSessions;
  final VoidCallback? onNewSession;

  /// Switches the open Session from a drawer row. Null leaves rows read-only.
  final ValueChanged<EngineSession>? onOpenSession;
  final VoidCallback? onRefreshSessions;
  final List<EngineSession> sessions;
  final bool sessionsLoading;
  final String? sessionsError;
  final String? currentSessionId;
  final bool disposeController;
  final HostInfo? hostInfo;

  /// Host-backed answers for this conversation. Null leaves the bubbles without
  /// actions, which is the honest state for a preview or a transport that has no
  /// rewind/feedback records behind it.
  final ConversationActions? actions;

  /// Opens the Session a rewind produced and, when not empty, sends [prompt]
  /// there once it can accept one.
  final Future<bool> Function(String sessionId, String prompt)?
  onOpenForkSession;

  /// The composer text owned by the screen that hosts this shell.
  ///
  /// A rewind fills the composer and then opens another Session, which replaces
  /// this shell. Sharing the draft keeps that text where the person can see it
  /// instead of losing it with the old page. Null means this shell owns one.
  final ComposerDraft? draft;

  @override
  State<ChatShell> createState() => _ChatShellState();
}

class _ChatShellState extends State<ChatShell> {
  /// One draft for the whole screen: a retraction, an edit and the person's own
  /// typing all write the same text, so no action can silently lose a draft.
  late ComposerDraft _draft;
  bool _ownsDraft = false;
  MessageActions? _actions;

  @override
  void initState() {
    super.initState();
    _adoptDraft();
    unawaited(widget.controller.start());
    _bindActions();
  }

  void _adoptDraft() {
    final shared = widget.draft;
    _ownsDraft = shared == null;
    _draft = shared ?? ComposerDraft();
  }

  @override
  void didUpdateWidget(covariant ChatShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      unawaited(widget.controller.start());
    }
    if (oldWidget.actions != widget.actions) _bindActions();
    if (oldWidget.draft != widget.draft) {
      if (_ownsDraft) _draft.dispose();
      _adoptDraft();
      // The action layer holds the draft it was built with.
      _actions?.dispose();
      _bindActions();
    }
  }

  /// Rebuild the action layer only when the backend identity changes, so a
  /// snapshot list is not refetched on every unrelated rebuild.
  void _bindActions() {
    final backend = widget.actions;
    if (backend == null) {
      _actions = null;
      return;
    }
    final actions = MessageActions(
      backend: backend,
      draft: _draft,
      onOpenFork: _openFork,
      onNotice: _notice,
    );
    _actions = actions;
    unawaited(actions.refresh());
  }

  Future<bool> _openFork(String sessionId, String prompt) async {
    final handler = widget.onOpenForkSession;
    if (handler == null) {
      _notice('这个入口还不能切换到新会话，请到本机引擎的会话列表打开', isError: true);
      return false;
    }
    return handler(sessionId, prompt);
  }

  void _notice(String text, {bool isError = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(text),
        backgroundColor: isError ? DshPalette.of(context).bad : null,
      ),
    );
  }

  /// The Turn plan for [message], read from the Host at the moment it is
  /// needed.
  ///
  /// Reading only when the page was built is wrong for every Turn that finishes
  /// afterwards: the Host writes a Turn's snapshot when the Turn ends, so the
  /// list captured earlier cannot contain it and the dialog would claim there
  /// are no files while the Host holds one. When the Host cannot answer, the
  /// dialog is not shown at all: promising "只撤回文本" unverified is worse than
  /// saying nothing happened.
  Future<TurnPlan?> _freshPlan(MessageActions actions, ChatMessage message) async {
    try {
      return await actions.planForFresh(message);
    } on Object catch (error) {
      _notice('读不到这一段的文件记录，先不改动：' + error.toString(), isError: true);
      return null;
    }
  }

  Future<void> _confirmRetract(ChatMessage message) async {
    final actions = _actions;
    if (actions == null) return;
    final plan = await _freshPlan(actions, message);
    if (plan == null || !mounted) return;
    final choice = await showRetractDialog(
      context,
      plan: plan,
      hasDraft: !_draft.isBlank,
    );
    // The dialog lives on the root navigator, so it outlives a rebuilt page: the
    // screen that asked may be gone by the time a choice comes back.
    if (choice == null || !mounted) return;
    await actions.retract(
      message,
      includeFiles: choice.includeFiles,
      replaceDraft: choice.replaceDraft,
    );
  }

  Future<void> _confirmEdit(ChatMessage message) async {
    final actions = _actions;
    if (actions == null) return;
    if (!actions.isEditing) {
      actions.beginEdit(message);
      _notice('已把这条消息放进输入框，改完发送会从这里继续');
      return;
    }
    if (actions.editing?.messageId == message.id) return;
    final plan = await _freshPlan(actions, message);
    if (plan == null || !mounted) return;
    final choice = await showRetractDialog(
      context,
      plan: plan,
      hasDraft: false,
      title: '改成编辑这条消息？',
      confirmLabel: '编辑这条',
    );
    if (choice == null || !mounted) return;
    actions.cancelEdit();
    actions.beginEdit(message);
  }

  Future<void> _shareMessage(ChatMessage message, List<ChatMessage> all) async {
    final actions = _actions;
    if (actions == null) return;
    final index = all.indexWhere((row) => row.id == message.id);
    final prompt = index <= 0
        ? null
        : all
              .take(index)
              .where((row) => row.role == ChatRole.user)
              .lastOrNull;
    final body = StringBuffer();
    if (prompt != null) body.writeln('问：' + prompt.text + String.fromCharCode(10));
    body.write('答：' + message.text);
    if (!mounted) return;
    await showSharePreview(
      context,
      text: body.toString(),
      title: prompt?.text.trim().isNotEmpty == true
          ? _shortTitle(prompt!.text)
          : '对话内容',
    );
  }

  static String _shortTitle(String text) {
    final line = text.trim().split(String.fromCharCode(10)).first;
    return line.length <= 24 ? line : line.substring(0, 24) + '…';
  }

  @override
  void dispose() {
    if (_ownsDraft) _draft.dispose();
    _actions?.dispose();
    if (widget.disposeController) widget.controller.dispose();
    super.dispose();
  }

  /// The callbacks the transcript rows receive, or null with no backend.
  MessageActionBindings? get _bindings {
    final actions = _actions;
    if (actions == null) return null;
    final messages = widget.controller.state.messages;
    return MessageActionBindings(
      copy: (message) => unawaited(
        actions.copy(
          message.text,
          messageId: message.role == ChatRole.assistant ? message.id : null,
        ),
      ),
      retract: (message) => unawaited(_confirmRetract(message)),
      edit: (message) => unawaited(_confirmEdit(message)),
      like: (message) => unawaited(
        actions.rate(
          message.messageId!,
          actions.ratingOf(message.messageId) == true ? null : true,
        ),
      ),
      dislike: (message) => unawaited(
        actions.rate(
          message.messageId!,
          actions.ratingOf(message.messageId) == false ? null : false,
        ),
      ),
      regenerate: (message) => unawaited(_regenerate(message)),
      share: (message) => unawaited(_shareMessage(message, messages)),
      ratingOf: actions.ratingOf,
      isBusy: (messageId) => actions.busyMessageId == messageId,
      wasCopied: (messageId) => actions.lastCopiedId == messageId,
      editingMessageId: actions.editing?.messageId,
      onCancelEdit: actions.cancelEdit,
    );
  }

  /// Re-runs one Turn with the prompt that produced it.
  Future<void> _regenerate(ChatMessage answer) async {
    final actions = _actions;
    if (actions == null) return;
    final messages = widget.controller.state.messages;
    final index = messages.indexWhere((row) => row.id == answer.id);
    final prompt = index <= 0
        ? null
        : messages
              .take(index)
              .where((row) => row.role == ChatRole.user)
              .lastOrNull;
    if (prompt == null) {
      _notice('找不到这一轮的问题，无法重新输出', isError: true);
      return;
    }
    final plan = await _freshPlan(actions, answer);
    if (plan == null || !mounted) return;
    final choice = await showRetractDialog(
      context,
      plan: plan,
      hasDraft: !_draft.isBlank,
      title: '重新输出这一轮？',
      confirmLabel: '重新输出',
    );
    if (choice == null || !mounted) return;
    await actions.regenerate(answer, prompt.text);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([widget.controller, _draft, _actions]),
      builder: (context, _) {
        final state = widget.controller.state;
        final firstPrompt = state.messages
            .where((m) => m.role == ChatRole.user)
            .map((m) => m.text)
            .firstOrNull;
        return Scaffold(
          backgroundColor: DshPalette.of(context).paper,
          drawer: SessionDrawer(
            currentTitle: firstPrompt,
            currentSessionId: widget.currentSessionId,
            sessions: widget.sessions,
            sessionsLoading: widget.sessionsLoading,
            sessionsError: widget.sessionsError,
            onRefreshSessions: widget.onRefreshSessions,
            onOpenSession: widget.onOpenSession,
            sessionsLabel: '会话列表',
            onNewSession: widget.onNewSession,
            onOpenSettings: () => showSettingsHome(context, widget.controller),
            onOpenEngineSessions: widget.onOpenEngineSessions,
            onShowcase:
                widget.controller.transport is FixtureTransport &&
                    state.status == ChatStatus.ready
                ? (widget.controller.transport as FixtureTransport).emitShowcase
                : null,
            isFixture: widget.controller.transport.isFixture,
          ),
          body: DshBackdrop(
            child: SafeArea(
              child: LayoutBuilder(
                builder: (context, viewport) => Column(
                  children: [
                    _TopBar(
                      controller: widget.controller,
                      hostInfo: widget.hostInfo,
                      onOpenWebFallback: widget.onOpenWebFallback,
                    ),
                    if (state.status == ChatStatus.reconnecting ||
                        state.status == ChatStatus.error ||
                        state.error != null)
                      _ConnectionBanner(
                        controller: widget.controller,
                        status: state.status,
                        detail: state.error,
                      ),
                    Expanded(
                      child: LayoutBuilder(
                        builder: (context, remaining) => Column(
                          children: [
                            Expanded(
                              child: MessageList(
                                controller: widget.controller,
                                actions: _bindings,
                                editingMessageId: _actions?.editing?.messageId,
                              ),
                            ),
                            Composer(
                              controller: widget.controller,
                              availableHeight: remaining.maxHeight,
                              draft: _draft,
                              editing: _actions?.isEditing ?? false,
                              onCancelEdit: () {
                                _actions?.cancelEdit();
                                _notice('已取消编辑，原来的草稿也还回来了');
                              },
                            ),
                          ],
                        ),
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

class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.controller,
    this.hostInfo,
    this.onOpenWebFallback,
  });
  final ChatController controller;
  final HostInfo? hostInfo;
  final VoidCallback? onOpenWebFallback;
  @override
  Widget build(BuildContext context) {
    final host = hostInfo ?? HostInfo.preview;
    // The catalog is the only source of truth: an icon that leads to an empty
    // sheet would be a dead end, so it is not laid out at all until the parent
    // conversation has actually observed a child.
    final hasAgents = collectSubagents(controller).isNotEmpty;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
          child: GlassPanel(
            key: const ValueKey('floating-header'),
            radius: 30,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 5),
              child: Row(
                children: [
                  // A top-level conversation opens its drawer; only a real
                  // child conversation uses a back arrow (SubagentChatPage).
                  IconButton(
                    key: const ValueKey('session-menu'),
                    tooltip: '打开会话抽屉',
                    onPressed: () => Scaffold.of(context).openDrawer(),
                    icon: Icon(
                      Icons.menu_rounded,
                      size: 23,
                      color: DshPalette.of(context).inkSoft,
                    ),
                  ),
                  const SizedBox(width: 4),
                  const Expanded(
                    child: OverflowTitle(
                      text: 'DSH',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                  if (hasAgents)
                    IconButton(
                      key: const ValueKey('subagent-selector'),
                      tooltip: '子代理',
                      onPressed: () =>
                          showSubagentPanel(context, controller: controller),
                      icon: Icon(
                        Icons.people_alt_outlined,
                        size: 21,
                        color: DshPalette.of(context).ice,
                      ),
                    ),
                  ConstrainedBox(
                    constraints: BoxConstraints(
                      maxWidth: MediaQuery.sizeOf(context).width * .48,
                    ),
                    child: Tooltip(
                      message: controller.transport.isFixture
                          ? 'fixture 模式'
                          : 'engine',
                      child: KeyedSubtree(
                        key: const ValueKey('web-fallback'),
                        child: TextButton(
                          key: const ValueKey('runtime-selector'),
                          onPressed: () => showRuntimePanel(
                            context,
                            hostInfo: hostInfo,
                            isFixture: controller.transport.isFixture,
                            onOpenWebFallback: onOpenWebFallback,
                          ),
                          style: TextButton.styleFrom(
                            padding: const EdgeInsets.symmetric(horizontal: 10),
                            // Width follows the row; the height stays a real
                            // 48dp target at every display size.
                            minimumSize: DshDisplayScope.targetSize(
                              context,
                              width: 0,
                            ),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                host.runtimeMode.startsWith('ssh_')
                                    ? Icons.dns_outlined
                                    : Icons.terminal_rounded,
                                size: 18,
                                color: DshPalette.of(context).inkSoft,
                              ),
                              const SizedBox(width: 8),
                              Flexible(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      host.runtimeLabel,
                                      textWidthBasis:
                                          TextWidthBasis.longestLine,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontSize: 13,
                                        color: DshPalette.of(context).ink,
                                      ),
                                    ),
                                    Text(
                                      (controller.transport.isFixture
                                              ? '演示 · '
                                              : '') +
                                          _statusLabel(controller.state.status),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontSize: 10,
                                        color: DshPalette.of(context).inkFaint,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(width: 5),
                              Icon(
                                Icons.expand_more_rounded,
                                size: 15,
                                color: DshPalette.of(context).inkSoft,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  static String _statusLabel(ChatStatus status) => switch (status) {
    ChatStatus.connecting => '连接中',
    ChatStatus.ready => '已连接',
    ChatStatus.streaming => '正在回复',
    ChatStatus.waitingAction => '等待确认',
    ChatStatus.reconnecting => '重连中',
    ChatStatus.error => '连接异常',
  };
}

class _ConnectionBanner extends StatelessWidget {
  const _ConnectionBanner({
    required this.controller,
    required this.status,
    this.detail,
  });

  final ChatController controller;
  final ChatStatus status;
  final String? detail;

  @override
  Widget build(BuildContext context) {
    final isConfiguration = controller.configurationError != null;
    final isError = status == ChatStatus.error || isConfiguration;
    final tone = isError
        ? DshPalette.of(context).bad
        : DshPalette.of(context).warn;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 2, 12, 4),
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        decoration: BoxDecoration(
          color: tone.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: tone.withValues(alpha: 0.45)),
        ),
        child: Row(
          children: [
            Icon(
              isError ? Icons.error_outline_rounded : Icons.wifi_off_rounded,
              size: 15,
              color: tone,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    isConfiguration
                        ? '请确认会话权限'
                        : isError
                        ? '引擎连接失败'
                        : (status == ChatStatus.streaming ||
                                  status == ChatStatus.waitingAction) &&
                              detail != null &&
                              detail!.isNotEmpty
                        ? '操作未完成'
                        : '连接已中断',
                    style: TextStyle(
                      color: DshPalette.of(context).ink,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (detail != null && detail!.isNotEmpty)
                    Text(
                      detail!,
                      maxLines: isConfiguration ? 4 : 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: DshPalette.of(context).inkSoft,
                        fontSize: 12,
                      ),
                    ),
                ],
              ),
            ),
            TextButton(
              key: const ValueKey('connection-retry'),
              onPressed: controller.reconnect,
              child: const Text('重试'),
            ),
          ],
        ),
      ),
    );
  }
}
import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../model/engine_agent_preset.dart';
import '../model/engine_workspace.dart';
import '../model/engine_session.dart';
import '../model/settings_model.dart';
import '../bridge/host_bridge.dart';
import 'floating_conversation_layout.dart';
import 'chat_strings.dart';
import 'launch_fade.dart';
import 'glass_panel.dart';
import 'overflow_title.dart';
import 'runtime_panel.dart';
import 'subagent_panel.dart';
import '../state/chat_controller.dart';
import '../state/composer_draft.dart';
import '../state/chat_state.dart';
import '../state/conversation_actions.dart';
import '../state/message_actions.dart';
import '../state/turn_plan.dart';
import 'retract_dialog.dart';
import 'share_preview.dart';
import 'file_delivery_card.dart';
import 'file_preview_sheet.dart';
import 'settings/settings_home.dart';
import 'settings/settings_scope.dart';
import '../model/turn_notice.dart';
import 'turn_notice_card.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import 'composer.dart';
import 'conversation_edge_blur.dart';
import 'interaction_haptics.dart';
import 'message_list.dart';
import 'session_drawer.dart';
import '../transport/fixture_transport.dart';
import '../transport/native_file_delivery_service.dart';

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
    this.fileDelivery,
    this.onOpenForkSession,
    this.draft,
    this.sessionTitle,
    this.agentPresets = const [],
    this.selectedAgentPresetId,
    this.agentPresetLocked = true,
    this.onSelectAgentPreset,
    this.workspaceOptions = const [],
    this.selectedWorkspaceId,
    this.onSelectWorkspace,
    this.onSaveDefaultWorkspace,
    this.onClearDefaultWorkspace,
    this.onRetryWorkspace,
    this.workspaceStatus,
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

  /// DSH-backed file access for assistant delivery links. Null keeps a preview
  /// or fixture surface honest and leaves the card actions disabled.
  final NativeFileDeliveryService? fileDelivery;

  /// Opens the Session a rewind produced and, when not empty, sends [prompt]
  /// there once it can accept one.
  final Future<bool> Function(String sessionId, String prompt)?
  onOpenForkSession;

  /// Title of the open Session, as the Host reports it.
  ///
  /// The header names the conversation the person is actually in. When the
  /// catalogue has no title yet, the first prompt stands in for it; nothing on
  /// this screen falls back to a product name.
  final String? sessionTitle;
  final List<EngineAgentPreset> agentPresets;
  final String? selectedAgentPresetId;
  final bool agentPresetLocked;
  final Future<bool> Function(String)? onSelectAgentPreset;
  final List<EngineWorkspace> workspaceOptions;
  final String? selectedWorkspaceId;
  final Future<bool> Function(String?)? onSelectWorkspace;
  final Future<bool> Function()? onSaveDefaultWorkspace,
      onClearDefaultWorkspace;
  final VoidCallback? onRetryWorkspace;
  final String? workspaceStatus;

  /// The composer text owned by the screen that hosts this shell.
  ///
  /// A rewind fills the composer and then opens another Session, which replaces
  /// this shell. Sharing the draft keeps that text where the person can see it
  /// instead of losing it with the old page. Null means this shell owns one.
  final ComposerDraft? draft;

  @override
  State<ChatShell> createState() => _ChatShellState();
}

class _ChatShellState extends State<ChatShell> with WidgetsBindingObserver {
  /// One draft for the whole screen: a retraction, an edit and the person's own
  /// typing all write the same text, so no action can silently lose a draft.
  late ComposerDraft _draft;
  bool _ownsDraft = false;
  MessageActions? _actions;
  bool _reportedLaunchReady = false;

  void _reportLaunchReady() {
    if (!mounted || _reportedLaunchReady) return;
    final state = widget.controller.state;
    if (state.generation < 0 || state.needsResync || _linkBroken) return;
    _reportedLaunchReady = true;
    LaunchFade.markContentReady(context);
  }

  /// Auto-reconnect bookkeeping.
  ///
  /// A dropped RPC keepalive is the normal way an Android session loses its
  /// link: the app was backgrounded, the socket idled out, the engine restarted.
  /// Asking the person to find a button and tap it is the wrong answer, so the
  /// shell retries on its own when it comes back to the foreground, and again
  /// while a link stays broken — with a bounded backoff, never an endless hot
  /// loop against a dead engine.
  Timer? _retryTimer;
  int _retryAttempt = 0;
  bool _sawBackground = false;
  ChatController? _retryingController;

  /// A draft that existed when the link dropped, kept so a successful
  /// reconnect can hand it back. It is never sent: restoring only puts the
  /// text back where the person can see and edit it.
  String? _parkedDraft;

  /// The parked text offered after a reconnect.
  ///
  /// Only set once the composer has really lost that text: when the field kept
  /// it there is nothing to recover, and a strip claiming otherwise would be
  /// noise on every ordinary reconnect.
  String? _recoveredDraft;

  static const _retryDelays = <Duration>[
    Duration(seconds: 1),
    Duration(seconds: 2),
    Duration(seconds: 4),
    Duration(seconds: 8),
    Duration(seconds: 16),
  ];

  @override
  void initState() {
    super.initState();
    _adoptDraft();
    WidgetsBinding.instance.addObserver(this);
    widget.controller.addListener(_onControllerChanged);
    unawaited(widget.controller.start());
    _bindActions();
  }

  /// A healthy connection clears the backoff, so a later drop retries at once.
  void _onControllerChanged() {
    if (widget.controller.state.status == ChatStatus.ready) {
      _retryAttempt = 0;
      _retryTimer?.cancel();
      _retryTimer = null;
      _offerParkedDraft();
      return;
    }
    // Text typed before the drop is captured by value: the field is a view of
    // the draft and a resync can rebuild this page, so the promise to hand it
    // back must not depend on that view surviving.
    if (_linkBroken) _parkDraft();
    // A link that broke on its own gets the same help as one that broke while
    // the app was away: retry in the background instead of waiting for a tap.
    _scheduleRetry();
  }

  /// Keeps the newest unsent text seen while the link was broken.
  void _parkDraft() {
    final text = _draft.text;
    if (text.trim().isEmpty) return;
    _parkedDraft = text;
  }

  /// After a reconnect, offers the parked text back when the composer no longer
  /// holds it. Nothing is sent: the text only returns to the input field.
  void _offerParkedDraft() {
    final parked = _parkedDraft;
    if (parked == null || !mounted) return;
    // The listener can fire inside a frame (a resync that answers synchronously
    // on the first pump), and a setState there would be a build-phase write.
    final phase = SchedulerBinding.instance.schedulerPhase;
    if (phase == SchedulerPhase.persistentCallbacks ||
        phase == SchedulerPhase.midFrameMicrotasks) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _offerParkedDraft();
      });
      return;
    }
    _parkedDraft = null;
    if (parked.trim().isEmpty) return;
    // Still in the field: the draft was never lost, so nothing is offered.
    //
    // "Still there" means the field holds the parked text, not that the two are
    // byte-equal: typing while the link was down extends the same draft, and a
    // strip offering to add it back would append a copy of what is already on
    // screen. Only a field that lost the text — rebuilt empty, or replaced by
    // other text — gets the offer.
    final live = _draft.text;
    if (live.contains(parked.trim())) return;
    setState(() => _recoveredDraft = parked);
  }

  void _restoreParkedDraft() {
    final text = _recoveredDraft;
    if (text == null) return;
    // Decided from the live field, not from the moment the strip appeared: text
    // typed while the offer was showing would otherwise be overwritten by a
    // filler that believed the field was still empty.
    final append = !_draft.isBlank;
    final expected = !append
        ? text
        : _draft.text.trimRight() +
              String.fromCharCode(10) +
              String.fromCharCode(10) +
              text.trim();
    setState(() => _recoveredDraft = null);
    if (append) {
      _draft.append(text);
    } else {
      _draft.replace(text);
    }
    // Read the field back before claiming it worked: another write (a rewind,
    // an edit) can land in the same frame, and a swallowed draft is exactly the
    // loss this offer exists to prevent.
    if (_draft.text != expected) {
      setState(() => _recoveredDraft = text);
      _notice('草稿没能填回输入框，可以再试一次', isError: true);
      return;
    }
    // The text only returns to the field: sending stays a separate act.
    _notice('草稿已填回输入框，不会自动发送');
  }

  void _discardParkedDraft() {
    if (_recoveredDraft == null) return;
    setState(() => _recoveredDraft = null);
    _notice('已放弃这份草稿');
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      if (_sawBackground) {
        _sawBackground = false;
        // Coming back is exactly the moment the old socket is most likely dead.
        // Reconnecting immediately (not after the first failed poll) keeps the
        // window where the person sees "已连接" but nothing sends as short as
        // possible. A fresh foreground also resets the backoff budget: five
        // attempts is a budget for one broken stretch, not for the session.
        _retryTimer?.cancel();
        _retryTimer = null;
        _retryAttempt = 0;
        _scheduleRetry(immediate: true);
      }
      return;
    }
    _sawBackground = true;
    // No point retrying while the app cannot draw the result.
    _retryTimer?.cancel();
    _retryTimer = null;
  }

  bool get _linkBroken => switch (widget.controller.state.status) {
    ChatStatus.error || ChatStatus.reconnecting => true,
    _ => false,
  };

  /// Reconnect now, or once the backoff for this attempt elapses.
  void _scheduleRetry({bool immediate = false}) {
    if (!mounted ||
        !_linkBroken ||
        _retryTimer != null ||
        identical(_retryingController, widget.controller)) {
      return;
    }
    // 退到后台就别再排了：回来的那一刻会立刻重连，中间这几次只会把
    // 退避预算白白花在看不清结果的帧上。
    if (_sawBackground) return;
    if (widget.controller.state.status == ChatStatus.streaming ||
        widget.controller.state.status == ChatStatus.waitingAction) {
      // A running turn owns the link; resyncing under it would drop the stream.
      return;
    }
    if (immediate) {
      unawaited(_retryNow());
      return;
    }
    if (_retryAttempt >= _retryDelays.length) return;
    final delay = _retryDelays[_retryAttempt];
    _retryTimer = Timer(delay, () {
      _retryTimer = null;
      unawaited(_retryNow());
    });
  }

  Future<void> _retryNow() async {
    if (!mounted || _sawBackground || !_linkBroken) return;
    final controller = widget.controller;
    _retryingController = controller;
    _retryAttempt++;
    try {
      await controller.reconnect();
    } finally {
      if (identical(_retryingController, controller))
        _retryingController = null;
    }
    if (!mounted || !identical(widget.controller, controller)) return;
    // reconnect() surfaces a failure as an error state; keep trying while the
    // app is in front and the attempt budget lasts.
    if (_linkBroken && !_sawBackground) _scheduleRetry();
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
      oldWidget.controller.removeListener(_onControllerChanged);
      widget.controller.addListener(_onControllerChanged);
      _retryAttempt = 0;
      _retryTimer?.cancel();
      _retryTimer = null;
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
  Future<TurnPlan?> _freshPlan(
    MessageActions actions,
    ChatMessage message,
  ) async {
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
      // The composer really moved into this message: that is the only edit
      // entry that ticks on this path. A row already open returns earlier, and
      // the tick sits after the state change, never in a build.
      InteractionHaptics.editEntered();
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
    // Cancelling the dialog returns above, so this is exactly one tick for the
    // one confirmed switch - not one for the cancel and another for the entry.
    InteractionHaptics.editEntered();
  }

  Future<void> _submitEdit(String text) async {
    final actions = _actions;
    actions?.recordEditText(text);
    final edit = actions?.editing;
    if (actions == null || edit == null || !(actions.hasUnsavedEdit)) return;
    final message = widget.controller.state.messages
        .where(
          (message) => message.id == edit.messageId && message.seq == edit.seq,
        )
        .firstOrNull;
    if (message == null) {
      _notice('找不到要编辑的原消息，未发送', isError: true);
      return;
    }
    final plan = await _freshPlan(actions, message);
    if (plan == null || !mounted || !identical(actions.editing, edit)) return;
    final choice = await showRetractDialog(
      context,
      plan: plan,
      hasDraft: false,
      title: '从这条消息重新发送？',
      confirmLabel: '重新发送',
    );
    if (choice == null || !mounted || !identical(actions.editing, edit)) return;
    await actions.submitEdit(
      includeFiles: choice.includeFiles,
      replaceDraft: false,
    );
  }

  Future<void> _shareMessage(ChatMessage message, List<ChatMessage> all) async {
    final actions = _actions;
    if (actions == null) return;
    final index = all.indexWhere((row) => row.id == message.id);
    final prompt = index <= 0
        ? null
        : all.take(index).where((row) => row.role == ChatRole.user).lastOrNull;
    final body = StringBuffer();
    if (prompt != null)
      body.writeln('问：' + prompt.text + String.fromCharCode(10));
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

  /// What the header and the drawer call this conversation.
  ///
  /// The Host's title wins, because that is the name the catalogue and the Web
  /// shell show. Before the first Turn completes there is no title, and the
  /// person's own first prompt is the honest stand-in; with neither, the
  /// conversation is simply new.
  String _titleOf(String? firstPrompt) {
    final name = (widget.sessionTitle ?? '').trim();
    if (name.isNotEmpty) return name;
    final prompt = (firstPrompt ?? '').trim();
    if (prompt.isEmpty) return '新的对话';
    return prompt.split(String.fromCharCode(10)).first.trimRight();
  }

  static String _shortTitle(String text) {
    final line = text.trim().split(String.fromCharCode(10)).first;
    return line.length <= 24 ? line : line.substring(0, 24) + '…';
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.controller.removeListener(_onControllerChanged);
    _retryTimer?.cancel();
    _retryTimer = null;
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

  bool _fileBusy = false;

  Future<void> _handleFileTap(
    FileDelivery file,
    FileDeliveryAction action,
  ) async {
    if (action == FileDeliveryAction.openExternal) return _openFile(file);
    final service = widget.fileDelivery;
    if (service == null || _fileBusy) return;
    _fileBusy = true;
    FilePreviewData? data;
    var external = false;
    try {
      data = await service.preview(
        displayName: file.displayName,
        target: file.target,
      );
    } on UnsupportedError {
      external = true;
    } on Object catch (error) {
      if (mounted && identical(widget.fileDelivery, service)) {
        _notice('DSH 预览失败：$error', isError: true);
      }
    } finally {
      _fileBusy = false;
    }
    if (!mounted || !identical(widget.fileDelivery, service)) return;
    if (external) return _openFile(file);
    if (data == null) return;
    await showFilePreviewSheet(
      context,
      data,
      onOpen: () {
        if (identical(widget.fileDelivery, service)) unawaited(_openFile(file));
      },
      onShare: () {
        if (identical(widget.fileDelivery, service))
          unawaited(_shareFile(file));
      },
    );
  }

  Future<void> _openFile(FileDelivery file) =>
      _dispatchFile(file, share: false);
  Future<void> _shareFile(FileDelivery file) =>
      _dispatchFile(file, share: true);

  Future<void> _dispatchFile(FileDelivery file, {required bool share}) async {
    final service = widget.fileDelivery;
    if (service == null || _fileBusy) return;
    _fileBusy = true;
    try {
      final mime = NativeFileDeliveryService.mimeFor(file.target);
      final success = share
          ? await service.share(target: file.target, mime: mime)
          : await service.open(target: file.target, mime: mime);
      if (!success && mounted && identical(widget.fileDelivery, service)) {
        _notice(share ? '没有可用的分享应用' : '没有可打开此文件的应用', isError: true);
      }
    } on Object catch (error) {
      if (mounted && identical(widget.fileDelivery, service)) {
        _notice('文件打开或分享失败：$error', isError: true);
      }
    } finally {
      _fileBusy = false;
    }
  }

  // ---------------------------------------------------------------------
  // Recovery actions. The banner and the turn-ending cards offer the same
  // set, so both go through these helpers instead of building their own
  // wording: one place decides what "we succeeded" is allowed to mean.

  /// Reconnects now and reports what the connection actually did.
  ///
  /// The answer is read from the controller after the attempt, never from "the
  /// tap happened": a reconnect that leaves the link broken must read as a
  /// failure, otherwise the person waits for a connection that is not there.
  Future<ChatActionReport> _retryConnection() async {
    if (!mounted) return const ChatActionReport(ok: false, message: '页面已经关闭');
    if (!_linkBroken) {
      // Nothing to fix: the transport is up, so a reconnect here would tear
      // down a working stream. Say what the person can actually do instead.
      return const ChatActionReport(ok: true, message: '连接是通的，可以直接重新发送');
    }
    if (identical(_retryingController, widget.controller)) {
      return const ChatActionReport(ok: false, message: '正在重连，请稍候');
    }
    _retryTimer?.cancel();
    _retryTimer = null;
    _retryAttempt = 0;
    await _retryNow();
    if (!mounted) return const ChatActionReport(ok: false, message: '页面已经关闭');
    final state = widget.controller.state;
    if (state.status == ChatStatus.ready &&
        !state.needsResync &&
        state.error == null) {
      return const ChatActionReport(ok: true, message: '已重新连接');
    }
    final reason = state.error;
    if (reason == null || reason.isEmpty) {
      return const ChatActionReport(ok: false, message: '仍然无法连接，请切换运行环境或查看诊断');
    }
    // A message while the status is already usable is a failed operation, not
    // a dead link: saying "仍然无法连接" there would contradict the header.
    return state.status == ChatStatus.ready
        ? ChatActionReport(ok: false, message: '操作仍未完成：' + reason)
        : ChatActionReport(ok: false, message: '仍然无法连接：' + reason);
  }

  /// The settings page that owns this ending, or null when the ending has no
  /// setting behind it (a user stop, a forked turn, a token budget).
  static SettingsDestination? _destinationFor(TurnNotice notice) {
    switch (notice.kind) {
      case TurnNoticeKind.signedOut:
        return SettingsDestination.models;
      case TurnNoticeKind.interrupted:
        return SettingsDestination.connections;
      case TurnNoticeKind.blocked:
        return SettingsDestination.permissions;
      case TurnNoticeKind.failed:
        return switch (notice.code) {
          'MISSING_CREDENTIAL' ||
          'NO_CREDENTIAL_STORE' ||
          'INVALID_CREDENTIAL' ||
          'ACCOUNT_TOKEN_INVALID' ||
          'AUTH' ||
          'ACCOUNT_SIGN_IN_REQUIRED' ||
          'QUOTA' ||
          'ACCOUNT_QUOTA' ||
          'RATE_LIMIT' ||
          'MODEL_UNSUPPORTED' ||
          'UNKNOWN_MODEL' => SettingsDestination.models,
          'CONTEXT_WINDOW_EXCEEDED' => null,
          _ => SettingsDestination.diagnostics,
        };
      case TurnNoticeKind.maxTokens:
      case TurnNoticeKind.forked:
      case TurnNoticeKind.stopped:
      case TurnNoticeKind.other:
        return null;
    }
  }

  /// Opens the page that owns [notice]. The host channel goes first (it can
  /// reach sections this Flutter surface does not draw); the in-app page is the
  /// fallback, and with neither the answer is a failure, not a silent no-op.
  Future<ChatActionReport> _openSettingsFor(TurnNotice notice) async {
    final destination = _destinationFor(notice);
    if (destination == null) {
      return const ChatActionReport(ok: false, message: '这一轮没有对应的设置项');
    }
    if (!mounted) return const ChatActionReport(ok: false, message: '页面已经关闭');
    try {
      if (await HostBridge.openSettingsSection(destination)) {
        return ChatActionReport(ok: true, message: '已打开' + destination.title);
      }
    } on Object {
      // No host channel on this surface; fall through to the in-app page.
    }
    if (!mounted) return const ChatActionReport(ok: false, message: '页面已经关闭');
    if (SettingsScope.maybeOf(context) == null) {
      return ChatActionReport(
        ok: false,
        message: '这个入口打不开设置，请在 Android 应用里打开' + destination.title,
      );
    }
    await showSettingsHome(context, widget.controller);
    return ChatActionReport(ok: true, message: '已打开设置');
  }

  /// One copy-ready block with the facts a support answer needs: what ended,
  /// and what the link looked like at that moment.
  String _diagnosticsFor(TurnNotice? notice) {
    final state = widget.controller.state;
    final host = widget.hostInfo ?? HostInfo.preview;
    final transport = widget.controller.transport;
    String status = switch (state.status) {
      ChatStatus.connecting => '连接中',
      ChatStatus.ready => '已连接',
      ChatStatus.streaming => '正在回复',
      ChatStatus.waitingAction => '等待确认',
      ChatStatus.reconnecting => '重连中',
      ChatStatus.error => '连接异常',
    };
    final retry =
        '自动重连：第 ' +
        _retryAttempt.toString() +
        ' 次尝试 / 预算 ' +
        _retryDelays.length.toString();
    final lines = <String>[
      'DSH 原生聊天诊断',
      '时间：' + DateTime.now().toIso8601String(),
      '连接：' + status + (state.needsResync ? '（需要重新同步）' : ''),
      '运行环境：' + host.runtimeLabel + ' · ' + host.state,
      '传输：' + (transport.isFixture ? '演示（fixture）' : '本机引擎'),
      '代次：' +
          state.generation.toString() +
          ' · 下一序号：' +
          state.nextSequence.toString(),
      retry,
      if (state.error != null && state.error!.isNotEmpty)
        '连接错误：' + state.error!,
      if (notice != null) ...[
        '本轮结束：' + notice.title,
        if (notice.message.isNotEmpty) '原因：' + notice.message,
        if (notice.code != null) '代码：' + notice.code!,
        if (notice.detail.isNotEmpty) '详情：' + notice.detail,
      ],
    ];
    return lines.join(String.fromCharCode(10));
  }

  /// Whether a reconnect would do anything for this ending.
  ///
  /// Every real failure keeps the button: a failed run, a sign-out and an
  /// interrupted run all end with a link worth re-establishing, and a failed
  /// turn whose link is still down is exactly when the person needs it. A
  /// deliberate stop, a fork or a token ceiling is not a link problem — a
  /// retry there could only answer "连接是通的", so the card leaves it out
  /// instead of offering a button that does nothing.
  bool _retryHelpsFor(TurnNotice notice) => notice.isFailure || _linkBroken;

  /// Whether a settings page owns this ending.
  bool _settingsHelpFor(TurnNotice notice) => _destinationFor(notice) != null;

  TurnNoticeActions get _noticeActions => TurnNoticeActions(
    retry: _retryConnection,
    openSettings: _openSettingsFor,
    diagnostics: (notice) => _diagnosticsFor(notice),
    offersRetry: _retryHelpsFor,
    offersSettings: _settingsHelpFor,
  );

  /// Opens the runtime panel and says whether it really opened.
  ///
  /// The panel is a route this surface pushes, so the honest answer is "the
  /// route was pushed": with no Navigator behind this widget there is nothing
  /// to push and the person is told to use the Android app instead of watching
  /// a button that quietly does nothing. Whatever happens inside the panel is
  /// reported by the panel's own controls, not by this receipt.
  Future<ChatActionReport> _openRuntimePanel() async {
    if (!mounted) return const ChatActionReport(ok: false, message: '页面已经关闭');
    if (Navigator.maybeOf(context) == null) {
      return const ChatActionReport(
        ok: false,
        message: '这个入口打不开运行环境，请在 Android 应用里打开',
      );
    }
    final Future<void> opening;
    try {
      opening = showRuntimePanel(
        context,
        hostInfo: widget.hostInfo,
        isFixture: widget.controller.transport.isFixture,
        onOpenWebFallback: widget.onOpenWebFallback,
      );
    } on Object catch (error) {
      return ChatActionReport(
        ok: false,
        message: '打不开运行环境：' + error.toString(),
      );
    }
    // The route is pushed now; the panel's own controls report whatever happens
    // inside it. A panel that fails while opening still gets a line here, so a
    // broken sheet is not silent.
    unawaited(
      opening.catchError((Object error) {
        if (!mounted) return;
        _notice('运行环境面板出错了：' + error.toString(), isError: true);
      }),
    );
    return const ChatActionReport(ok: true, message: '已打开运行环境面板');
  }

  /// What the connection banner's settings button should open. An unknown
  /// tool permission is fixed on the permission page; every other link failure
  /// is about how the engine is reached.
  static SettingsDestination _bannerDestination(ChatState state) =>
      state.error != null && state.error!.contains('工具权限')
      ? SettingsDestination.permissions
      : SettingsDestination.connections;

  Future<ChatActionReport> _openBannerSettings(ChatState state) async {
    final destination = _bannerDestination(state);
    if (!mounted) return const ChatActionReport(ok: false, message: '页面已经关闭');
    try {
      if (await HostBridge.openSettingsSection(destination)) {
        return ChatActionReport(ok: true, message: '已打开' + destination.title);
      }
    } on Object {
      // No host channel on this surface; fall through to the in-app page.
    }
    if (!mounted) return const ChatActionReport(ok: false, message: '页面已经关闭');
    if (SettingsScope.maybeOf(context) == null) {
      return ChatActionReport(
        ok: false,
        message: '这个入口打不开设置，请在 Android 应用里打开' + destination.title,
      );
    }
    await showSettingsHome(context, widget.controller);
    return const ChatActionReport(ok: true, message: '已打开设置');
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
        final title = _titleOf(firstPrompt);
        final editing = _actions?.isEditing ?? false;
        final bottomInset = math.max(
          MediaQuery.viewInsetsOf(context).bottom,
          MediaQuery.viewPaddingOf(context).bottom,
        );
        return Scaffold(
          resizeToAvoidBottomInset: false,
          backgroundColor: DshPalette.of(context).paper,
          drawer: SessionDrawer(
            currentTitle: _titleOf(firstPrompt),
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
              bottom: false,
              child: Padding(
                padding: EdgeInsets.only(bottom: bottomInset),
                child: LayoutBuilder(
                  builder: (context, viewport) => FloatingConversationLayout(
                    onContentLaidOut:
                        !_reportedLaunchReady &&
                            state.generation >= 0 &&
                            !state.needsResync &&
                            !_linkBroken
                        ? _reportLaunchReady
                        : null,
                    header: Opacity(
                      key: editing
                          ? const ValueKey('edit-dimmed-chrome')
                          : null,
                      opacity: editing ? .45 : 1,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _TopBar(
                            controller: widget.controller,
                            title: title,
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
                              destination: _bannerDestination(state),
                              retry: _linkBroken ? _retryConnection : null,
                              diagnostics: () => _diagnosticsFor(null),
                              onOpenRuntime: _openRuntimePanel,
                              onOpenSettings: () => _openBannerSettings(state),
                            ),
                          if (_recoveredDraft != null)
                            _RecoveredDraftBanner(
                              text: _recoveredDraft!,
                              // Read live: text typed while the offer is
                              // showing decides whether the draft is appended
                              // or replaces an empty field.
                              appends: !_draft.isBlank,
                              onRestore: _restoreParkedDraft,
                              onDiscard: _discardParkedDraft,
                            ),
                        ],
                      ),
                    ),
                    composer: Composer(
                      controller: widget.controller,
                      availableHeight: viewport.maxHeight,
                      draft: _draft,
                      editing: _actions?.isEditing ?? false,
                      editText: _actions?.editingText,
                      inlineApproval: true,
                      onEditChanged: _actions?.recordEditText,
                      onCancelEdit: () => _actions?.cancelEdit(),
                      onSubmitEdit: _submitEdit,
                    ),
                    transcriptBuilder: (context, insets) =>
                        ConversationEdgeBlur(
                          contentInsets: insets,
                          child: TurnNoticeActionScope(
                            actions: _noticeActions,
                            child: MessageList(
                              controller: widget.controller,
                              actions: _bindings,
                              onFileTap: widget.fileDelivery == null
                                  ? null
                                  : (file, action) =>
                                        unawaited(_handleFileTap(file, action)),
                              onFileShare: widget.fileDelivery == null
                                  ? null
                                  : (file) => unawaited(_shareFile(file)),
                              editingMessageId: _actions?.editing?.messageId,
                              padding: insets,
                              onStarter: _draft.append,
                              agentPresets: widget.agentPresets,
                              selectedAgentPresetId:
                                  widget.selectedAgentPresetId,
                              onSelectAgentPreset: widget.agentPresetLocked
                                  ? null
                                  : widget.onSelectAgentPreset,
                              workspaceOptions: widget.workspaceOptions,
                              selectedWorkspaceId: widget.selectedWorkspaceId,
                              onSelectWorkspace: widget.onSelectWorkspace,
                              onSaveDefaultWorkspace:
                                  widget.onSaveDefaultWorkspace,
                              onClearDefaultWorkspace:
                                  widget.onClearDefaultWorkspace,
                              onRetryWorkspace: widget.onRetryWorkspace,
                              workspaceStatus: widget.workspaceStatus,
                            ),
                          ),
                        ),
                  ),
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
    required this.title,
    this.hostInfo,
    this.onOpenWebFallback,
  });
  final ChatController controller;

  /// The open Session's name. Never a product name: if the Host has no title
  /// yet, the shell hands over the first prompt instead.
  final String title;
  final HostInfo? hostInfo;
  final VoidCallback? onOpenWebFallback;
  @override
  Widget build(BuildContext context) {
    final host = hostInfo ?? HostInfo.preview;
    final strings = DshChatStrings.of(context);
    // The catalog is the only source of truth: an icon that leads to an empty
    // sheet would be a dead end, so it is not laid out at all until the parent
    // conversation has actually observed a child.
    final hasAgents = collectSubagents(controller).isNotEmpty;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 5, 12, 5),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
          child: GlassPanel(
            key: const ValueKey('floating-header'),
            radius: 25,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6),
              child: Row(
                children: [
                  // A top-level conversation opens its drawer; only a real
                  // child conversation uses a back arrow (SubagentChatPage).
                  IconButton(
                    key: const ValueKey('session-menu'),
                    tooltip: strings.openSessionDrawer,
                    onPressed: () => Scaffold.of(context).openDrawer(),
                    icon: Icon(
                      Icons.menu_rounded,
                      size: 23,
                      color: DshPalette.of(context).inkSoft,
                    ),
                  ),
                  const SizedBox(width: 4),
                  Expanded(
                    child: OverflowTitle(
                      key: const ValueKey('conversation-title'),
                      text: title,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                  if (hasAgents)
                    IconButton(
                      key: const ValueKey('subagent-selector'),
                      tooltip: strings.subagents,
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
                                          strings.status(
                                            _statusLabel(
                                              controller.state.status,
                                            ),
                                          ),
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

/// The link-failure strip above the transcript.
///
/// It used to be a sentence only: the shell retried on its own and the strip
/// told nobody what to do when those attempts ran out. Every action below is a
/// real attempt with its own answer — a retry that leaves the link broken says
/// so, and a settings page this surface cannot open says that instead of
/// closing quietly.
class _ConnectionBanner extends StatefulWidget {
  const _ConnectionBanner({
    required this.controller,
    required this.status,
    required this.destination,
    required this.retry,
    required this.diagnostics,
    required this.onOpenRuntime,
    required this.onOpenSettings,
    this.detail,
  });

  final ChatController controller;
  final ChatStatus status;
  final String? detail;

  /// Where "打开设置" leads for this failure.
  final SettingsDestination destination;

  /// Reconnects now. Null only on a surface with no transport to retry.
  final Future<ChatActionReport> Function()? retry;

  /// Copy-ready diagnostics for this connection state.
  final String Function()? diagnostics;

  /// Opens the runtime panel and reports whether it really opened: a surface
  /// with no Navigator behind it must say so, not print a success line.
  final Future<ChatActionReport> Function() onOpenRuntime;
  final Future<ChatActionReport> Function() onOpenSettings;

  @override
  State<_ConnectionBanner> createState() => _ConnectionBannerState();
}

class _ConnectionBannerState extends State<_ConnectionBanner> {
  String? _pending;
  String? _feedback;
  bool _feedbackOk = true;

  Future<void> _run(
    String id,
    Future<ChatActionReport> Function() action,
  ) async {
    if (_pending != null) return;
    setState(() {
      _pending = id;
      _feedback = null;
    });
    ChatActionReport report;
    try {
      report = await action();
    } on Object catch (error) {
      report = ChatActionReport(
        ok: false,
        message: '操作没有完成：' + error.toString(),
      );
    }
    if (!mounted) return;
    setState(() {
      _pending = null;
      _feedback = report.message.isEmpty ? null : report.message;
      _feedbackOk = report.ok;
    });
  }

  Future<void> _copyDiagnostics() async {
    final build = widget.diagnostics;
    if (build == null) return;
    await _run('diagnostics', () async {
      await Clipboard.setData(ClipboardData(text: build()));
      if (mounted) {
        ScaffoldMessenger.maybeOf(context)
            ?.showSnackBar(const SnackBar(content: Text('已复制连接诊断')));
      }
      return const ChatActionReport(ok: true, message: '诊断信息已复制');
    });
  }

  Widget _action({
    required String id,
    required String label,
    required IconData icon,
    required Future<ChatActionReport> Function() run,
    String? tooltip,
  }) {
    final button = TextButton.icon(
      key: ValueKey('connection-action-' + id),
      // One request at a time: the siblings disable while an attempt runs.
      onPressed: _pending == null ? () => _run(id, run) : null,
      style: TextButton.styleFrom(
        minimumSize: DshDisplayScope.targetSize(context, width: 0),
        padding: const EdgeInsets.symmetric(horizontal: 8),
        visualDensity: VisualDensity.compact,
      ),
      icon: _pending == id
          ? const SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : Icon(icon, size: 16),
      label: Text(label),
    );
    // Says where the tap leads before it happens: "打开设置" alone does not tell
    // whether this failure is fixed on the permission page or the connection one.
    if (tooltip == null) return button;
    return Tooltip(message: tooltip, child: button);
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final status = widget.status;
    final detail = widget.detail;
    final palette = DshPalette.of(context);
    final isConfiguration =
        detail != null && detail == controller.configurationError;
    final isError = status == ChatStatus.error || isConfiguration;
    final tone = isError ? palette.bad : palette.warn;
    final retry = widget.retry;
    final diagnostics = widget.diagnostics;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 2, 12, 4),
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 4),
        decoration: BoxDecoration(
          color: tone.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: tone.withValues(alpha: 0.45)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Icon(
                    isError
                        ? Icons.error_outline_rounded
                        : Icons.wifi_off_rounded,
                    size: 15,
                    color: tone,
                  ),
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
                                  detail.isNotEmpty
                            ? '操作未完成'
                            : '连接已中断',
                        style: TextStyle(
                          color: palette.ink,
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      if (detail != null && detail.isNotEmpty)
                        Text(
                          detail,
                          maxLines: isConfiguration ? 4 : 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.inkSoft,
                            fontSize: 12,
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
            Wrap(
              spacing: 2,
              runSpacing: 0,
              children: [
                if (retry case final run?)
                  _action(
                    id: 'retry',
                    label: '重试连接',
                    icon: Icons.refresh_rounded,
                    run: run,
                  ),
                _action(
                  id: 'runtime',
                  label: '切换运行环境',
                  icon: Icons.terminal_rounded,
                  run: widget.onOpenRuntime,
                ),
                _action(
                  id: 'settings',
                  label: '打开设置',
                  // The banner already knows which page owns this failure; the
                  // button says it up front instead of making the tap the first
                  // time the person learns where they are going.
                  tooltip: '打开' + widget.destination.title,
                  icon: Icons.tune_rounded,
                  run: widget.onOpenSettings,
                ),
                if (diagnostics != null)
                  TextButton.icon(
                    key: const ValueKey('connection-action-diagnostics'),
                    onPressed: _pending == null ? _copyDiagnostics : null,
                    style: TextButton.styleFrom(
                      minimumSize: DshDisplayScope.targetSize(
                        context,
                        width: 0,
                      ),
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      visualDensity: VisualDensity.compact,
                    ),
                    icon: _pending == 'diagnostics'
                        ? const SizedBox(
                            width: 14,
                            height: 14,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.description_outlined, size: 16),
                    label: const Text('复制诊断'),
                  ),
              ],
            ),
            if (_feedback case final line?)
              Padding(
                key: const ValueKey('connection-action-feedback'),
                padding: const EdgeInsets.only(left: 4, bottom: 4, right: 8),
                child: Text(
                  line,
                  style: TextStyle(
                    color: _feedbackOk ? palette.inkSoft : palette.bad,
                    fontSize: 12,
                    height: 1.35,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// Offered after a reconnect when an unsent draft had to be set aside.
///
/// It restores text only; sending stays a separate, explicit act.
class _RecoveredDraftBanner extends StatelessWidget {
  const _RecoveredDraftBanner({
    required this.text,
    required this.appends,
    required this.onRestore,
    required this.onDiscard,
  });

  final String text;
  final bool appends;
  final VoidCallback onRestore;
  final VoidCallback onDiscard;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    final preview = text.trim().split(String.fromCharCode(10)).first.trim();
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 2, 12, 4),
      child: Container(
        key: const ValueKey('draft-recovery'),
        padding: const EdgeInsets.fromLTRB(12, 6, 4, 4),
        decoration: BoxDecoration(
          color: palette.ice.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: palette.ice.withValues(alpha: 0.40)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              (appends
                      ? '断线前有未发送的草稿，可以填回输入框（会接在现有内容后面）：'
                      : '断线前有未发送的草稿，可以填回输入框：') +
                  (preview.length <= 40
                      ? preview
                      : preview.substring(0, 40) + '…'),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.ink,
                fontSize: 12.5,
                height: 1.35,
              ),
            ),
            Wrap(
              spacing: 2,
              runSpacing: 0,
              children: [
                TextButton.icon(
                  key: const ValueKey('draft-recovery-restore'),
                  onPressed: onRestore,
                  style: TextButton.styleFrom(
                    minimumSize: DshDisplayScope.targetSize(context, width: 0),
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    visualDensity: VisualDensity.compact,
                  ),
                  icon: const Icon(Icons.restore_rounded, size: 16),
                  label: const Text('填回草稿'),
                ),
                TextButton(
                  key: const ValueKey('draft-recovery-discard'),
                  onPressed: onDiscard,
                  style: TextButton.styleFrom(
                    minimumSize: DshDisplayScope.targetSize(context, width: 0),
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    visualDensity: VisualDensity.compact,
                  ),
                  child: const Text('放弃'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

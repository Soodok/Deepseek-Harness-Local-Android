import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../model/turn_notice.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';

/// What the screen reports after one recovery action actually ran.
///
/// A control never assumes its own tap worked: the caller answers with the
/// outcome it observed (a reconnect that still fails, a settings page this
/// surface cannot open) and the card prints that sentence instead of a tick.
class ChatActionReport {
  const ChatActionReport({required this.ok, required this.message});

  final bool ok;

  /// One line for the reader. Empty hides the feedback row.
  final String message;
}

/// The actions a turn-ending card may offer, supplied by the screen that owns
/// the engine connection.
///
/// Everything here is optional: a bare [ConversationFlow] in a test or a
/// preview has no connection, no settings service and no host facts, and the
/// card then offers only the details it can honestly show. A member is null
/// when the action would be a no-op — a retry while the link is healthy would
/// tear down a working stream, so the shell leaves it out instead of showing a
/// button that lies.
class TurnNoticeActions {
  const TurnNoticeActions({
    this.retry,
    this.openSettings,
    this.diagnostics,
    this.offersRetry,
    this.offersSettings,
  });

  /// Re-establish the link and re-read the conversation. Never sends a prompt.
  final Future<ChatActionReport> Function()? retry;

  /// Open the settings page that owns this ending (account, model, permission).
  final Future<ChatActionReport> Function(TurnNotice notice)? openSettings;

  /// A copy-ready diagnostic report for this ending, including the live
  /// connection facts a card cannot see.
  final String Function(TurnNotice notice)? diagnostics;

  /// Whether [retry] would do anything for this particular ending.
  ///
  /// A deliberate stop, a fork or a token ceiling is not a link failure, so a
  /// retry there could only answer "the connection is fine" — the shell says so
  /// instead of the card offering a button that does nothing. Null keeps the
  /// button wherever [retry] exists, for a surface that cannot tell endings
  /// apart.
  final bool Function(TurnNotice notice)? offersRetry;

  /// Whether [openSettings] has a page behind it for this particular ending.
  final bool Function(TurnNotice notice)? offersSettings;

  /// The retry this ending can actually honour, or null to hide the button.
  Future<ChatActionReport> Function()? retryFor(TurnNotice notice) =>
      retry == null || !(offersRetry?.call(notice) ?? true) ? null : retry;

  /// The settings entry this ending can actually honour, or null.
  Future<ChatActionReport> Function(TurnNotice notice)? settingsFor(
    TurnNotice notice,
  ) => openSettings == null || !(offersSettings?.call(notice) ?? true)
      ? null
      : openSettings;
}

/// Publishes [TurnNoticeActions] to every turn-ending card below it.
class TurnNoticeActionScope extends InheritedWidget {
  const TurnNoticeActionScope({
    super.key,
    required this.actions,
    required super.child,
  });

  final TurnNoticeActions actions;

  static TurnNoticeActions? maybeOf(BuildContext context) => context
      .dependOnInheritedWidgetOfExactType<TurnNoticeActionScope>()
      ?.actions;

  @override
  bool updateShouldNotify(TurnNoticeActionScope oldWidget) =>
      oldWidget.actions != actions;
}

/// Compact, structured turn status. Details stay collapsed until requested so
/// routine failures do not interrupt the transcript with a large red panel.
class TurnNoticeCard extends StatefulWidget {
  const TurnNoticeCard({super.key, required this.notice});

  final TurnNotice notice;

  @override
  State<TurnNoticeCard> createState() => _TurnNoticeCardState();
}

class _TurnNoticeCardState extends State<TurnNoticeCard> {
  bool _expanded = false;

  /// Which action is still waiting for its answer; null means idle. One at a
  /// time, so a second tap cannot race the first request.
  String? _pending;

  /// The last answer this card received, printed under the buttons.
  String? _feedback;
  bool _feedbackOk = true;

  TurnNotice get _notice => widget.notice;

  (IconData, Color) _state(DshColors colors) => switch (_notice.kind) {
    TurnNoticeKind.failed => (Icons.error_outline_rounded, colors.bad),
    TurnNoticeKind.signedOut => (Icons.person_off_outlined, colors.bad),
    TurnNoticeKind.interrupted => (
      Icons.power_settings_new_rounded,
      colors.warn,
    ),
    TurnNoticeKind.maxTokens => (Icons.warning_amber_rounded, colors.warn),
    TurnNoticeKind.blocked => (Icons.block_rounded, colors.warn),
    TurnNoticeKind.forked => (Icons.call_split_rounded, colors.inkSoft),
    TurnNoticeKind.stopped => (Icons.stop_circle_outlined, colors.inkSoft),
    TurnNoticeKind.other => (Icons.info_outline_rounded, colors.inkSoft),
  };

  String get _copyText => [
    _notice.title,
    _notice.message,
    if (_notice.nextStep.isNotEmpty) _notice.nextStep,
    if (_notice.code != null) '代码：' + _notice.code!,
    if (_notice.detail.isNotEmpty) '详情：' + _notice.detail,
  ].join(String.fromCharCode(10));

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _copyText));
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)
        ?.showSnackBar(const SnackBar(content: Text('已复制这一轮的结束原因')));
  }

  /// Runs one action and reports what came back.
  ///
  /// The answer comes from the caller, never from "the tap happened": a retry
  /// that leaves the link broken, or a settings page this surface cannot open,
  /// must read as a failure here.
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

  Future<void> _copyDiagnostics(TurnNoticeActions actions) async {
    final build = actions.diagnostics;
    if (build == null) {
      await _run(
        'diagnostics',
        () async =>
            const ChatActionReport(ok: false, message: '这个入口没有连接信息，无法生成诊断'),
      );
      return;
    }
    await _run('diagnostics', () async {
      await Clipboard.setData(ClipboardData(text: build(_notice)));
      if (mounted) {
        ScaffoldMessenger.maybeOf(context)
            ?.showSnackBar(const SnackBar(content: Text('已复制诊断信息')));
      }
      return const ChatActionReport(ok: true, message: '诊断信息已复制');
    });
  }

  void _toggle() => setState(() => _expanded = !_expanded);

  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final (icon, tone) = _state(colors);
    final expanded = _expanded;
    final actions = TurnNoticeActionScope.maybeOf(context);
    // Asked per ending, not per scope: a stop or a fork has nothing for a
    // reconnect to fix, and offering it there would be a button that lies.
    final retry = actions?.retryFor(_notice);
    final openSettings = actions?.settingsFor(_notice);
    final diagnostics = actions?.diagnostics;
    final hasDetails =
        _notice.message.isNotEmpty ||
        _notice.nextStep.isNotEmpty ||
        _notice.code != null ||
        _notice.detail.isNotEmpty;
    return Padding(
      padding: const EdgeInsets.only(top: 10, bottom: 4),
      child: Semantics(
        container: true,
        label: _notice.message.isEmpty
            ? _notice.title
            : _notice.title + '：' + _notice.message,
        expanded: expanded,
        button: hasDetails,
        child: Material(
          color: Colors.transparent,
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(10)),
          ),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: const ValueKey('turn-notice'),
            onTap: hasDetails ? _toggle : null,
            borderRadius: BorderRadius.circular(10),
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: DshDisplayScope.minTargetOf(context),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 5),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(top: 1),
                      child: Icon(icon, size: 16, color: tone),
                    ),
                    const SizedBox(width: 7),
                    Expanded(
                      child: expanded
                          ? _ExpandedNotice(
                              notice: _notice,
                              colors: colors,
                              onCopy: _copy,
                              retry: retry,
                              openSettings: openSettings,
                              onCopyDiagnostics: diagnostics == null
                                  ? null
                                  : () => _copyDiagnostics(actions!),
                              pending: _pending,
                              feedback: _feedback,
                              feedbackOk: _feedbackOk,
                              onRun: _run,
                            )
                          : Text(
                              _notice.message.isEmpty
                                  ? _notice.title
                                  : _notice.message,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: colors.inkSoft,
                                fontSize: 12.5,
                                height: 1.3,
                              ),
                            ),
                    ),
                    if (hasDetails)
                      Padding(
                        padding: const EdgeInsets.only(left: 5, top: 1),
                        child: Icon(
                          expanded
                              ? Icons.expand_less_rounded
                              : Icons.expand_more_rounded,
                          size: 17,
                          color: colors.inkFaint,
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ExpandedNotice extends StatelessWidget {
  const _ExpandedNotice({
    required this.notice,
    required this.colors,
    required this.onCopy,
    required this.retry,
    required this.openSettings,
    required this.onCopyDiagnostics,
    required this.pending,
    required this.feedback,
    required this.feedbackOk,
    required this.onRun,
  });

  final TurnNotice notice;
  final DshColors colors;
  final VoidCallback onCopy;
  final Future<ChatActionReport> Function()? retry;
  final Future<ChatActionReport> Function(TurnNotice notice)? openSettings;
  final VoidCallback? onCopyDiagnostics;
  final String? pending;
  final String? feedback;
  final bool feedbackOk;
  final Future<void> Function(
    String id,
    Future<ChatActionReport> Function() action,
  )
  onRun;

  bool get _hasActions =>
      retry != null || openSettings != null || onCopyDiagnostics != null;

  Widget _action({
    required BuildContext context,
    required String id,
    required String label,
    required IconData icon,
    required Future<ChatActionReport> Function() run,
  }) {
    final busy = pending == id;
    return TextButton.icon(
      key: ValueKey('turn-notice-action-' + id),
      // Every sibling disables while one action runs, so a second tap cannot
      // race the first request.
      onPressed: pending == null ? () => onRun(id, run) : null,
      style: TextButton.styleFrom(
        minimumSize: DshDisplayScope.targetSize(context, width: 0),
        padding: const EdgeInsets.symmetric(horizontal: 8),
      ),
      icon: busy
          ? const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : Icon(icon, size: 16),
      label: Text(label),
    );
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      // The state name stays with the details: the collapsed row only carries
      // the reason, so the ending itself must still be readable once opened.
      Text(
        notice.title,
        key: const ValueKey('turn-notice-title'),
        style: TextStyle(
          color: colors.ink,
          fontSize: 13,
          height: 1.35,
          fontWeight: FontWeight.w600,
        ),
      ),
      if (notice.message.isNotEmpty) ...[
        const SizedBox(height: 3),
        Text(
          notice.message,
          style: TextStyle(color: colors.inkSoft, fontSize: 13, height: 1.45),
        ),
      ],
      if (notice.nextStep.isNotEmpty) ...[
        const SizedBox(height: 5),
        Text(
          notice.nextStep,
          key: const ValueKey('turn-notice-next-step'),
          style: TextStyle(
            color: colors.ink,
            fontSize: 13,
            height: 1.4,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
      if (notice.code != null) ...[
        const SizedBox(height: 5),
        Text(
          '代码：' + notice.code!,
          key: const ValueKey('turn-notice-code'),
          style: TextStyle(
            color: colors.inkFaint,
            fontSize: 12,
            height: 1.3,
            fontFamily: 'monospace',
          ),
        ),
      ],
      if (notice.detail.isNotEmpty) ...[
        const SizedBox(height: 5),
        Container(
          key: const ValueKey('turn-notice-detail'),
          width: double.infinity,
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: colors.paper.withValues(alpha: .38),
            borderRadius: BorderRadius.circular(8),
          ),
          child: SelectableText(
            notice.detail,
            style: TextStyle(
              color: colors.inkSoft,
              fontSize: 12,
              height: 1.45,
              fontFamily: 'monospace',
            ),
          ),
        ),
      ],
      if (_hasActions) ...[
        const SizedBox(height: 4),
        Wrap(
          spacing: 2,
          runSpacing: 0,
          children: [
            if (retry case final run?)
              _action(
                context: context,
                id: 'retry',
                label: '重试',
                icon: Icons.refresh_rounded,
                run: run,
              ),
            if (openSettings case final open?)
              _action(
                context: context,
                id: 'settings',
                label: '打开相关设置',
                icon: Icons.tune_rounded,
                run: () => open(notice),
              ),
            if (onCopyDiagnostics case final copy?)
              TextButton.icon(
                key: const ValueKey('turn-notice-action-diagnostics'),
                onPressed: pending == null ? copy : null,
                style: TextButton.styleFrom(
                  minimumSize: DshDisplayScope.targetSize(context, width: 0),
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                ),
                icon: pending == 'diagnostics'
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.description_outlined, size: 16),
                label: const Text('复制诊断'),
              ),
          ],
        ),
      ],
      if (feedback case final line?)
        Padding(
          key: const ValueKey('turn-notice-action-feedback'),
          padding: const EdgeInsets.only(top: 2, left: 4),
          child: Text(
            line,
            style: TextStyle(
              color: feedbackOk ? colors.inkSoft : colors.bad,
              fontSize: 12,
              height: 1.35,
            ),
          ),
        ),
      Align(
        alignment: AlignmentDirectional.centerEnd,
        child: TextButton.icon(
          key: const ValueKey('turn-notice-copy'),
          onPressed: onCopy,
          style: TextButton.styleFrom(
            minimumSize: DshDisplayScope.targetSize(context),
            padding: const EdgeInsets.symmetric(horizontal: 8),
          ),
          icon: const Icon(Icons.copy_rounded, size: 16),
          label: const Text('复制详情'),
        ),
      ),
    ],
  );
}

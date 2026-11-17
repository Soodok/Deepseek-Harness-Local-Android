import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../model/chat_model.dart';
import '../transport/native_action_transport.dart';
import 'composer_draft.dart';
import 'conversation_actions.dart';
import 'turn_plan.dart';

/// What the transcript asks the surrounding screen to do for one message.
///
/// The screen owns dialogs and navigation; this object owns the call sequence
/// and the state that makes it idempotent (one busy id, one cached snapshot
/// list, one remembered edit target).
class MessageActions extends ChangeNotifier {
  MessageActions({
    required this.backend,
    required this.draft,
    required this.onOpenFork,
    required this.onNotice,
  });

  final ConversationActions backend;
  final ComposerDraft draft;

  /// Open the Session a rewind produced, optionally sending [prompt] at once.
  final Future<bool> Function(String sessionId, String prompt) onOpenFork;

  /// Show one line to the person (snackbar or inline notice).
  final void Function(String text, {bool isError}) onNotice;

  /// True once the owning screen is gone. A Host answer that arrives later must
  /// not touch a disposed notifier, a dead composer or a dead context: the
  /// request is finished honestly and the screen that replaced this one shows
  /// the real state anyway.
  bool _disposed = false;

  bool get isDisposed => _disposed;

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  /// notifyListeners that tolerates a late answer after dispose.
  void _notify() {
    if (_disposed) return;
    notifyListeners();
  }

  /// onNotice that tolerates a late answer after dispose: the context that would
  /// have shown it no longer exists, and the replacing screen shows real state.
  void _say(String text, {bool isError = false}) {
    if (_disposed) return;
    onNotice(text, isError: isError);
  }

  List<TurnChangeSummary> _turns = const [];
  Map<String, bool> _ratings = const {};
  String? _busyMessageId;
  bool _loading = false;
  String? _lastCopiedId;
  EditTarget? _edit;
  String? _stashedDraft;

  bool get loading => _loading;
  String? get busyMessageId => _busyMessageId;
  String? get lastCopiedId => _lastCopiedId;
  EditTarget? get editing => _edit;
  bool get isEditing => _edit != null;

  bool? ratingOf(String? messageId) =>
      messageId == null ? null : _ratings[messageId];

  /// Read the Host once: snapshot rows per Turn and current ratings.
  Future<void> refresh() async {
    if (_disposed || _loading) return;
    _loading = true;
    _notify();
    try {
      final results = await Future.wait<Object>([
        backend.turnChanges(),
        backend.ratings(),
      ]);
      _turns = results[0] as List<TurnChangeSummary>;
      _ratings = results[1] as Map<String, bool>;
    } on Object catch (error) {
      _say('读取这一段的记录失败：' + error.toString(), isError: true);
    } finally {
      _loading = false;
      _notify();
    }
  }

  /// The Turn that owns [seq], for callers that hold a seq rather than a row.
  TurnPlan planForSeqValue(int seq) => planForSeq(_turns, seq);

  /// The Turn a message belongs to, asked of the Host right now.
  ///
  /// The snapshot list read when the page opened is not enough: the Host only
  /// writes a Turn's manifest once that Turn ends, so every Turn completed
  /// after the page opened is missing from it. A dialog that says "这一段没有
  /// 改动文件" while the Host holds a snapshot of that Turn's files would lie
  /// about what the confirm button does.
  ///
  /// Failures propagate: the caller must not show a reassuring "no files"
  /// plan it could not verify.
  Future<TurnPlan> planForFresh(ChatMessage message) async {
    final rows = await backend.turnChanges();
    if (_disposed) return planFor(message);
    _turns = rows;
    _notify();
    return planFor(message);
  }

  /// The Turn a message belongs to and what retracting it would restore.
  TurnPlan planFor(ChatMessage message) {
    final seq = message.seq;
    if (seq == null) {
      return const TurnPlan(turn: null, files: 0, incomplete: false);
    }
    return planForSeq(_turns, seq);
  }

  Future<void> copy(String text, {String? messageId}) async {
    if (_disposed) return;
    try {
      await Clipboard.setData(ClipboardData(text: text));
      if (messageId != null) {
        _lastCopiedId = messageId;
        _notify();
        // The tick is a receipt, not a state: it clears itself.
        await Future<void>.delayed(const Duration(milliseconds: 1400));
        if (_lastCopiedId == messageId) {
          _lastCopiedId = null;
          _notify();
        }
      }
      _say('已复制');
    } on Object catch (error) {
      _say('复制失败：' + error.toString(), isError: true);
    }
  }

  /// Retract [message] and everything after it.
  ///
  /// [includeFiles] additionally restores the files those Turns changed. The
  /// message's own text goes back into the composer; an existing draft is
  /// either kept or replaced by an explicit choice made by the caller.
  Future<void> retract(
    ChatMessage message, {
    required bool includeFiles,
    required bool replaceDraft,
  }) async {
    if (_disposed) return;
    final seq = message.seq;
    if (seq == null || _busyMessageId != null) return;
    _busyMessageId = message.id;
    _notify();
    try {
      final outcome = await backend.rewind(
        seq: seq,
        includeFiles: includeFiles,
        requestId: NativeMessageActions.newRequestId('retract'),
      );
      // The screen may have been replaced while the Host was answering. The
      // records are already correct, so nothing here may touch a dead composer.
      if (_disposed) return;
      if (outcome.status == 'conflict') {
        _say(
          '这些文件在这一轮之后被改过，没有覆盖：' + outcome.conflicts.take(3).join('、'),
          isError: true,
        );
        return;
      }
      if (!outcome.rewound) {
        _say('撤回没有被接受，请重试', isError: true);
        return;
      }
      _applyDraft(message.text, replace: replaceDraft);
      _say(
        outcome.filesApplied
            ? '已撤回，并回滚 ' + outcome.restored.length.toString() + ' 个文件'
            : '已撤回对话文本',
      );
      await onOpenFork(outcome.childSessionId!, '');
    } on Object catch (error) {
      _say('撤回失败：' + error.toString(), isError: true);
    } finally {
      _busyMessageId = null;
      _notify();
    }
  }

  /// Begin editing [message]: its text moves into the composer and the current
  /// draft is kept aside until the edit ends.
  void beginEdit(ChatMessage message) {
    if (_disposed || message.seq == null) return;
    _stashedDraft = draft.text;
    _edit = EditTarget(
      messageId: message.id,
      seq: message.seq!,
      text: message.text,
    );
    draft.replace(message.text);
    _notify();
  }

  /// Leave the edit and give the stashed draft back.
  void cancelEdit() {
    if (_disposed || _edit == null) return;
    _edit = null;
    final stashed = _stashedDraft;
    _stashedDraft = null;
    draft.replace(stashed ?? '');
    _notify();
  }

  /// Send the edited text: rewind the original Turn, then continue from there.
  Future<void> submitEdit({
    required bool includeFiles,
    required bool replaceDraft,
  }) async {
    if (_disposed) return;
    final edit = _edit;
    if (edit == null || _busyMessageId != null) return;
    _busyMessageId = edit.messageId;
    _notify();
    try {
      final outcome = await backend.rewind(
        seq: edit.seq,
        includeFiles: includeFiles,
        requestId: NativeMessageActions.newRequestId('edit'),
      );
      // A screen replaced while the Host answered keeps its records; the edit
      // text stays in the dead editor, which no longer exists.
      if (_disposed) return;
      if (outcome.status == 'conflict') {
        _say(
          '这些文件在这一轮之后被改过，没有覆盖：' + outcome.conflicts.take(3).join('、'),
          isError: true,
        );
        return;
      }
      if (!outcome.rewound) {
        _say('编辑后的发送没有被接受，文本仍然保留', isError: true);
        return;
      }
      final text = draft.text;
      _edit = null;
      _stashedDraft = null;
      draft.replace('');
      await onOpenFork(outcome.childSessionId!, text);
    } on Object catch (error) {
      // The edit stays open with the text intact: the person can retry or cancel.
      _say('编辑后的发送失败：' + error.toString(), isError: true);
    } finally {
      _busyMessageId = null;
      _notify();
    }
  }

  /// Re-run one answered Turn: restore its file changes, then send it again.
  Future<void> regenerate(ChatMessage answer, String prompt) async {
    if (_disposed) return;
    final seq = answer.seq;
    if (seq == null || _busyMessageId != null) return;
    // Ask the Host again: the Turn may have finished after this page opened,
    // and refusing to regenerate on a stale list would be a false negative.
    final TurnPlan plan;
    try {
      plan = await planForFresh(answer);
    } on Object catch (error) {
      _say('读不到这一段的文件记录，先不重做：' + error.toString(), isError: true);
      return;
    }
    if (_disposed) return;
    if (!plan.hasSnapshot) {
      _say('这一轮没有留下回合记录，无法重新输出', isError: true);
      return;
    }
    final startSeq = startSeqOfTurn(_turns, plan.turn!);
    if (startSeq == null) {
      _say('找不到这一轮的开头，无法重新输出', isError: true);
      return;
    }
    _busyMessageId = answer.id;
    _notify();
    try {
      final outcome = await backend.rewind(
        seq: startSeq,
        includeFiles: true,
        requestId: NativeMessageActions.newRequestId('regenerate'),
      );
      if (_disposed) return;
      if (outcome.status == 'conflict') {
        _say(
          '这些文件在这一轮之后被改过，没有覆盖：' + outcome.conflicts.take(3).join('、'),
          isError: true,
        );
        return;
      }
      if (!outcome.rewound) {
        _say('重新输出没有被接受，请重试', isError: true);
        return;
      }
      await onOpenFork(outcome.childSessionId!, prompt);
    } on Object catch (error) {
      _say('重新输出失败：' + error.toString(), isError: true);
    } finally {
      _busyMessageId = null;
      _notify();
    }
  }

  Future<void> rate(String messageId, bool? positive) async {
    if (_disposed || _busyMessageId != null) return;
    _busyMessageId = messageId;
    _notify();
    try {
      await backend.rate(messageId, positive);
      _ratings = {..._ratings};
      if (positive == null) {
        _ratings.remove(messageId);
      } else {
        _ratings[messageId] = positive;
      }
      _say(positive == null ? '已取消评价' : (positive ? '已点赞' : '已标记没帮助'));
    } on Object catch (error) {
      _say('评价没有保存：' + error.toString(), isError: true);
    } finally {
      _busyMessageId = null;
      _notify();
    }
  }

  Future<void> share(String text) async {
    if (_disposed) return;
    try {
      final ok = await backend.share(text);
      if (!ok) _say('系统分享没有打开', isError: true);
    } on Object catch (error) {
      _say('分享失败：' + error.toString(), isError: true);
    }
  }

  void _applyDraft(String text, {required bool replace}) {
    if (replace) {
      draft.replace(text);
    } else {
      draft.append(text);
    }
  }
}
/// The callbacks one transcript row may use.
///
/// The screen owns dialogs, navigation and the composer; a row only reports
/// which message was touched, so no widget has to know how a retraction is
/// confirmed or where a fork opens.
class MessageActionBindings {
  const MessageActionBindings({
    required this.copy,
    required this.retract,
    required this.edit,
    required this.like,
    required this.dislike,
    required this.regenerate,
    required this.share,
    required this.ratingOf,
    required this.isBusy,
    required this.wasCopied,
    this.editingMessageId,
    this.onCancelEdit,
  });

  final void Function(ChatMessage message) copy;
  final void Function(ChatMessage message) retract;
  final void Function(ChatMessage message) edit;
  final void Function(ChatMessage message) like;
  final void Function(ChatMessage message) dislike;

  /// Re-runs the Turn that produced [message]; null when it cannot be located.
  final void Function(ChatMessage message)? regenerate;
  final void Function(ChatMessage message) share;
  final bool? Function(String? messageId) ratingOf;
  final bool Function(String messageId) isBusy;
  final bool Function(String messageId) wasCopied;
  final String? editingMessageId;
  final VoidCallback? onCancelEdit;
}
import '../transport/native_action_transport.dart';

/// One concrete answer to "which Turn does this message belong to, and what
/// would retracting it change?".
///
/// The Host records one row per Turn; a message addresses a Turn by its seq.
/// Keeping this arithmetic out of the widget layer makes it checkable.
class TurnPlan {
  const TurnPlan({required this.turn, required this.files, required this.incomplete});

  /// The Turn that owns the addressed message, or null when the Host has no
  /// record of it (a Turn older than the snapshot store).
  final int? turn;

  /// Files that retracting this message would restore: every recorded change
  /// from this Turn onward, which is exactly what the rewind restores.
  final int files;

  /// At least one of those Turns has an incomplete snapshot, so a restore may
  /// not cover every file. The UI says so instead of promising a full rollback.
  final bool incomplete;

  bool get hasSnapshot => turn != null;
  bool get canRestoreFiles => files > 0;
}

/// The Turn that owns [seq], and the total file changes from that Turn onward.
///
/// [turns] must be ordered by turn. A Turn without a recorded start seq cannot
/// be addressed by a message, so it is skipped rather than guessed.
TurnPlan planForSeq(List<TurnChangeSummary> turns, int seq) {
  int? owner;
  for (final row in turns) {
    if (row.startSeq == null) continue;
    if (row.startSeq! <= seq) {
      owner = owner == null || row.turn > owner ? row.turn : owner;
    }
  }
  if (owner == null) {
    return const TurnPlan(turn: null, files: 0, incomplete: false);
  }
  var files = 0;
  var incomplete = false;
  for (final row in turns) {
    if (row.turn < owner) continue;
    files += row.files;
    incomplete = incomplete || row.incomplete || row.truncated;
  }
  return TurnPlan(turn: owner, files: files, incomplete: incomplete);
}

/// The first user-message seq of [turn], or null when it was not recorded.
int? startSeqOfTurn(List<TurnChangeSummary> turns, int turn) {
  for (final row in turns) {
    if (row.turn == turn) return row.startSeq;
  }
  return null;
}

/// One line a person can read before confirming: what happens to files.
String describeChanges(TurnPlan plan) {
  if (!plan.hasSnapshot) {
    return '这一段没有文件快照，只能撤回对话文本';
  }
  if (!plan.canRestoreFiles) return '这一段没有改动文件，只撤回对话文本';
  final base = '将回滚 ' + plan.files.toString() + ' 个文件的改动';
  return plan.incomplete ? base + '（其中有文件没留下快照，可能恢复不全）' : base;
}

/// Structured description of how a turn ended. The projection builds it from
/// the durable turn/end reason, so the UI never guesses a failure from text.
enum TurnNoticeKind {
  failed,
  stopped,
  signedOut,
  interrupted,
  maxTokens,
  blocked,
  forked,
  other,
}

class TurnNotice {
  const TurnNotice({
    required this.kind,
    required this.message,
    this.code,
    this.detail = '',
  });

  final TurnNoticeKind kind;

  /// Readable reason stated in the user's terms. Empty when the source carried
  /// no message at all.
  final String message;

  /// Verbatim DSH failure code (for example MISSING_CREDENTIAL) when present.
  final String? code;

  /// Original structured payload, kept for the expandable details and copy.
  final String detail;

  /// A red error tone is reserved for real failures. A user or parent stop is
  /// never one, and neither are the ordinary non-completion endings.
  bool get isFailure =>
      kind == TurnNoticeKind.failed ||
      kind == TurnNoticeKind.signedOut ||
      kind == TurnNoticeKind.interrupted;

  /// Each ending names what actually happened. A sign-out and a crashed run are
  /// distinct failures, so they must not share the neutral "task stopped" title
  /// that a user or parent cancellation owns.
  String get title => switch (kind) {
    TurnNoticeKind.failed => '本轮未能完成',
    TurnNoticeKind.stopped => '任务已停止',
    TurnNoticeKind.signedOut => '账号已退出，本轮已停止',
    TurnNoticeKind.interrupted => '运行已中断',
    TurnNoticeKind.maxTokens => '已达到输出上限',
    TurnNoticeKind.blocked => '回合被阻止',
    TurnNoticeKind.forked => '回合已分叉',
    TurnNoticeKind.other => '回合已结束',
  };

  /// What the reader can do next. Only the recoverable failures have one: a
  /// user stop is deliberate and a completed turn needs no instruction.
  String get nextStep => switch (kind) {
    TurnNoticeKind.signedOut => '重新登录后重试',
    TurnNoticeKind.interrupted => '恢复后重试',
    _ => '',
  };

  /// Stable identity for widget keys and tests; never a localized label.
  String get keyName => kind.name + (code == null ? '' : ':' + code!);

  @override
  bool operator ==(Object other) =>
      other is TurnNotice &&
      other.kind == kind &&
      other.message == message &&
      other.code == code &&
      other.detail == detail;

  @override
  int get hashCode => Object.hash(kind, message, code, detail);
}

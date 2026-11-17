import '../transport/dsh_transport.dart';
import '../transport/native_action_transport.dart';

/// What one open conversation can do to the Host records behind it.
///
/// Every method is answered by the Host (turn-rewind snapshots, message
/// feedback, the Session log). A client never invents the result: a refused
/// rewind or an unwritable rating surfaces as an exception the UI shows.
abstract interface class ConversationActions {
  /// Recorded file changes per Turn of the open Session, oldest first.
  Future<List<TurnChangeSummary>> turnChanges();

  /// Fork the open Session just before [seq] and restore that Turn's files
  /// when [includeFiles] is set. [force] overrides a detected conflict.
  Future<RewindOutcome> rewind({
    required int seq,
    required bool includeFiles,
    required String requestId,
    bool force = false,
  });

  /// Show the Session produced by [rewind], carrying [draft] into its composer.
  Future<void> openFork(String childSessionId, {String draft = ''});

  /// Ratings already recorded for this Session, keyed by assistant message id.
  Future<Map<String, bool>> ratings();

  /// Record or clear one rating. Null clears it.
  Future<void> rate(String messageId, bool? positive);

  /// Hand the transcript text to the platform share sheet.
  Future<bool> share(String text, {String? subject});
}

/// A conversation whose backend is a fixed answer set (tests, previews).
class StaticConversationActions implements ConversationActions {
  StaticConversationActions({
    this.changes = const [],
    this.ratingsByMessage = const {},
    this.rewindResult,
    this.shareResult = true,
    this.rewindError,
  });

  List<TurnChangeSummary> changes;
  Map<String, bool> ratingsByMessage;
  RewindOutcome? rewindResult;
  bool shareResult;

  /// Makes [turnChanges] fail, so a test can prove the caller does not quietly
  /// fall back to a stale "no files" answer.
  bool failTurnChanges = false;

  /// When set, [rewind] throws it: the Host refuses the rewind for that reason.
  Object? rewindError;


  final rewinds = <({int seq, bool includeFiles, String requestId, bool force})>[];
  final ratingCalls = <({String messageId, bool? positive})>[];
  final opened = <({String sessionId, String draft})>[];
  final shares = <String>[];

  @override
  Future<List<TurnChangeSummary>> turnChanges() async {
    if (failTurnChanges) {
      throw const TransportException('turn-rewind 暂时读不到');
    }
    return changes;
  }

  @override
  Future<RewindOutcome> rewind({
    required int seq,
    required bool includeFiles,
    required String requestId,
    bool force = false,
  }) async {
    rewinds.add((seq: seq, includeFiles: includeFiles, requestId: requestId, force: force));
    final refused = rewindError;
    if (refused != null) throw refused;
    return rewindResult ??
        const RewindOutcome(status: 'rewound', childSessionId: 'fork', turn: 0);
  }

  @override
  Future<void> openFork(String childSessionId, {String draft = ''}) async {
    opened.add((sessionId: childSessionId, draft: draft));
  }

  @override
  Future<Map<String, bool>> ratings() async => ratingsByMessage;

  @override
  Future<void> rate(String messageId, bool? positive) async {
    ratingCalls.add((messageId: messageId, positive: positive));
    if (positive == null) {
      ratingsByMessage = Map.of(ratingsByMessage)..remove(messageId);
    } else {
      ratingsByMessage = {...ratingsByMessage, messageId: positive};
    }
  }

  @override
  Future<bool> share(String text, {String? subject}) async {
    shares.add(text);
    return shareResult;
  }
}

/// The edit that is currently in progress, if any.
///
/// [text] is the message being edited, so the composer can show it and the
/// send action can rewind exactly that Turn.
class EditTarget {
  const EditTarget({
    required this.messageId,
    required this.seq,
    required this.text,
  });

  final String messageId;
  final int seq;
  final String text;
}

/// Text a finished action wants placed in the composer.
class DraftIntent {
  const DraftIntent({
    required this.id,
    required this.text,
    required this.replaceExisting,
  });

  final int id;
  final String text;

  /// True replaces the whole draft; false appends it after the current text.
  final bool replaceExisting;
}

/// One user-visible outcome of an action, ready to show as a message.
class ActionNotice {
  const ActionNotice({required this.text, required this.isError});

  final String text;
  final bool isError;
}
import '../bridge/host_bridge.dart';
import '../transport/native_action_transport.dart';
import 'conversation_actions.dart';

/// The Host-backed answers for one open Session.
///
/// Both clients call the same two namespaces (turnRewind for the recorded Turns
/// and their snapshots, messageFeedback for the authoritative rating), so a
/// retraction or a thumbs up made here is the same record the Web client reads.
class NativeConversationActions implements ConversationActions {
  NativeConversationActions({
    required this.transport,
    required this.openForkHandler,
    this.shareHandler,
  });

  final MessageActionTransport transport;

  /// Opens the Session a rewind produced, carrying an optional prompt.
  final Future<void> Function(String sessionId, String draft) openForkHandler;

  /// Platform share; when absent the Android HostBridge is asked directly.
  final Future<bool> Function(String text)? shareHandler;


  @override
  Future<List<TurnChangeSummary>> turnChanges() => transport.listTurnChanges();

  @override
  Future<RewindOutcome> rewind({
    required int seq,
    required bool includeFiles,
    required String requestId,
    bool force = false,
  }) => transport.rewindTo(
    seq: seq,
    includeFiles: includeFiles,
    requestId: requestId,
    force: force,
  );

  @override
  Future<void> openFork(String childSessionId, {String draft = ''}) =>
      openForkHandler(childSessionId, draft);

  @override
  Future<Map<String, bool>> ratings() => transport.readRatings();

  @override
  Future<void> rate(String messageId, bool? positive) async {
    if (positive == null) {
      await transport.clearRating(messageId);
    } else {
      await transport.rateMessage(messageId: messageId, positive: positive);
    }
  }

  @override
  Future<bool> share(String text, {String? subject}) async {
    final handler = shareHandler;
    if (handler != null) return handler(text);
    return HostBridge.shareText(text, subject: subject);
  }
}
import 'dart:async';
import 'dart:convert';

import 'dsh_rpc_connection.dart';

/// One Turn's recorded file changes, from the turn-rewind snapshot store.
///
/// Counts come from the Host's own manifest; a Turn recorded before the plugin
/// was enabled simply has no row, which is different from "changed nothing".
class TurnChangeSummary {
  const TurnChangeSummary({
    required this.turn,
    required this.files,
    required this.created,
    required this.modified,
    required this.deleted,
    required this.incomplete,
    required this.truncated,
    this.startSeq,
  });

  final int turn;

  /// The user-message seq that opens this Turn, or null when the Host could
  /// not attribute one (the Turn then cannot be addressed by a message).
  final int? startSeq;
  final int files, created, modified, deleted;
  final bool incomplete, truncated;

  bool get hasChanges => files > 0;

  static TurnChangeSummary? fromJson(Object? value) {
    if (value is! Map || value['turn'] is! num) return null;
    int count(String key) => value[key] is num ? (value[key] as num).toInt() : 0;
    return TurnChangeSummary(
      turn: (value['turn'] as num).toInt(),
      startSeq: value['startSeq'] is num ? (value['startSeq'] as num).toInt() : null,
      files: count('files'),
      created: count('created'),
      modified: count('modified'),
      deleted: count('deleted'),
      incomplete: value['incomplete'] == true,
      truncated: value['truncated'] == true,
    );
  }
}

/// Outcome of one rewind request.
///
/// [status] is either 'rewound' (a child Session now holds the shortened
/// transcript) or 'conflict' (files changed outside the recorded Turn, so the
/// caller must decide). Anything else is a protocol failure.
class RewindOutcome {
  const RewindOutcome({
    required this.status,
    this.childSessionId,
    this.turn,
    this.restored = const [],
    this.skipped = const [],
    this.conflicts = const [],
    this.filesApplied = false,
  });

  final String status;
  final String? childSessionId;
  final int? turn;
  final List<String> restored;
  final List<({String path, String reason})> skipped;
  final List<String> conflicts;
  final bool filesApplied;

  bool get rewound => status == 'rewound' && (childSessionId ?? '').isNotEmpty;

  static RewindOutcome parse(Object? value) {
    if (value is! Map || value['status'] is! String) {
      throw const FormatException('Invalid rewind result');
    }
    List<String> paths(Object? files, String key) {
      if (files is! Map || files[key] is! List) return const [];
      return List.unmodifiable(
        (files[key] as List).whereType<Map>().map((item) => item['path']).whereType<String>(),
      );
    }

    final files = value['files'];
    // A conflict answer carries its paths at the top level
    // ({status:"conflict", turn, conflicts:[{path, reason}]}) because no file
    // was touched; a rewound answer nests them under "files". Reading only the
    // nested form rendered "没有覆盖：" with every name missing.
    List<String> conflictPaths() {
      final nested = paths(files, 'conflicts');
      if (nested.isNotEmpty) return nested;
      final top = value['conflicts'];
      if (top is! List) return const [];
      return List.unmodifiable(
        top
            .whereType<Map>()
            .map((item) => item['path'])
            .whereType<String>(),
      );
    }

    final skipped = <({String path, String reason})>[];
    if (files is Map && files['skipped'] is List) {
      for (final item in files['skipped'] as List) {
        if (item is Map && item['path'] is String) {
          skipped.add((
            path: item['path'] as String,
            reason: item['reason'] is String ? item['reason'] as String : 'skipped',
          ));
        }
      }
    }
    return RewindOutcome(
      status: value['status'] as String,
      childSessionId: value['childSessionId'] is String
          ? value['childSessionId'] as String
          : null,
      turn: value['turn'] is num ? (value['turn'] as num).toInt() : null,
      restored: paths(files, 'restored'),
      skipped: List.unmodifiable(skipped),
      conflicts: conflictPaths(),
      filesApplied: files is Map && files['applied'] == true,
    );
  }
}

/// What one bubble can do, backed by the same Host records on both clients.
abstract interface class MessageActionTransport {
  /// Turn snapshots recorded for this Session, oldest first.
  Future<List<TurnChangeSummary>> listTurnChanges();

  /// Fork the Session just before [seq] and optionally restore that Turn's files.
  Future<RewindOutcome> rewindTo({
    required int seq,
    required bool includeFiles,
    required String requestId,
    bool force,
  });

  /// Record the Host's authoritative thumbs up/down for one assistant message.
  Future<void> rateMessage({required String messageId, required bool positive});

  /// Remove a rating, leaving the message unrated.
  Future<void> clearRating(String messageId);

  /// Current ratings keyed by assistant message id.
  Future<Map<String, bool>> readRatings();
}

/// Bridge to the Host's own turn-rewind and message-feedback namespaces.
class NativeMessageActions implements MessageActionTransport {
  NativeMessageActions(this.rpc, {required this.sessionId});

  final DshRpcConnection rpc;
  final String sessionId;

  static const _invalid = FormatException('Invalid DSH response');

  Map<String, Object?> _value(Object? raw) {
    if (raw is! Map) throw _invalid;
    if (raw['ok'] != true) {
      final error = raw['error'];
      final code = error is Map && error['code'] is String
          ? error['code'] as String
          : 'unknown';
      final message = error is Map && error['message'] is String
          ? error['message'] as String
          : code;
      throw DshRpcException('rewind/' + code, message);
    }
    final value = raw['value'];
    if (value is! Map) throw _invalid;
    return Map<String, Object?>.from(value);
  }

  @override
  Future<List<TurnChangeSummary>> listTurnChanges() async {
    final value = _value(
      await rpc.call('turnRewind/list', {
        'request': {'sessionId': sessionId},
      }),
    );
    final turns = value['turns'];
    if (turns is! List) throw _invalid;
    return List.unmodifiable(
      turns.map(TurnChangeSummary.fromJson).whereType<TurnChangeSummary>(),
    );
  }

  @override
  Future<RewindOutcome> rewindTo({
    required int seq,
    required bool includeFiles,
    required String requestId,
    bool force = false,
  }) async {
    final value = _value(
      await rpc.call('turnRewind/rewind', {
        'request': {
          'sessionId': sessionId,
          'requestId': requestId,
          'seq': seq,
          'includeFiles': includeFiles,
          'force': force,
        },
      }),
    );
    return RewindOutcome.parse(value);
  }

  @override
  Future<void> rateMessage({
    required String messageId,
    required bool positive,
  }) async {
    final current = await readRatings();
    final version = await _versionOf(messageId);
    final value = await rpc.call('messageFeedback/put', {
      'request': {
        'sessionId': sessionId,
        'messageId': messageId,
        'rating': positive ? 'positive' : 'negative',
        'ifVersion': version,
      },
    });
    _requireOk(value);
    if (current[messageId] == positive) return;
  }

  @override
  Future<void> clearRating(String messageId) async {
    final version = await _versionOf(messageId);
    if (version == null) return;
    _requireOk(
      await rpc.call('messageFeedback/delete', {
        'request': {
          'sessionId': sessionId,
          'messageId': messageId,
          'ifVersion': version,
        },
      }),
    );
  }

  @override
  Future<Map<String, bool>> readRatings() async {
    final raw = await rpc.call('messageFeedback/list', {
      'request': {'sessionId': sessionId},
    });
    final value = raw is Map && raw['ok'] == true ? raw['value'] : null;
    if (value is! Map || value['items'] is! List) return const {};
    final ratings = <String, bool>{};
    for (final item in value['items'] as List) {
      if (item is Map && item['messageId'] is String) {
        ratings[item['messageId'] as String] = item['rating'] == 'positive';
      }
    }
    return Map.unmodifiable(ratings);
  }

  /// The opaque compare-and-set token for one message, or null when unrated.
  Future<String?> _versionOf(String messageId) async {
    final raw = await rpc.call('messageFeedback/list', {
      'request': {'sessionId': sessionId},
    });
    final value = raw is Map && raw['ok'] == true ? raw['value'] : null;
    if (value is! Map || value['items'] is! List) return null;
    for (final item in value['items'] as List) {
      if (item is Map && item['messageId'] == messageId && item['version'] is String) {
        return item['version'] as String;
      }
    }
    return null;
  }

  void _requireOk(Object? raw) {
    if (raw is! Map) throw _invalid;
    if (raw['ok'] == true) return;
    final error = raw['error'];
    final code = error is Map && error['code'] is String
        ? error['code'] as String
        : 'unknown';
    throw DshRpcException('feedback/' + code, code);
  }

  /// Stable request identity so a retried rewind cannot fork twice.
  static String newRequestId(String seed) =>
      seed + '-' + DateTime.now().microsecondsSinceEpoch.toRadixString(36);

  /// JSON a share sheet or download can carry verbatim.
  static String transcriptJson(List<Map<String, Object?>> rows) =>
      const JsonEncoder.withIndent('  ').convert(rows);
}
/// One real DSH Session row.
///
/// The UI shows [displayName] only: a Session identity or a filesystem path is
/// an implementation detail a person should never have to pick from. The
/// identity stays available for routing.
class EngineSession {
  const EngineSession({
    required this.id,
    required this.updatedAt,
    this.cwd = '',
    this.title,
    this.running = false,
    this.openable = true,
    this.blank = false,
  });

  final String id;
  final num updatedAt;
  final String cwd;

  /// Human-readable title from the durable `title` projection, or null while
  /// the Session has none yet.
  final String? title;
  final bool running, openable;

  /// Whether the Session has no completed turn yet.
  final bool blank;

  /// What a person sees in the drawer. Never an id and never a path.
  String get displayName {
    final text = (title ?? '').trim();
    if (text.isNotEmpty) return text.length <= 60 ? text : text.substring(0, 59) + '…';
    if (blank) return '新的对话';
    return '未命名对话';
  }

  /// Whether this row can be told apart from [other] by name alone.
  bool sameNameAs(EngineSession other) => displayName == other.displayName;

  static List<EngineSession> fromResponse(Object? value) {
    if (value is! Map || value['items'] is! List) {
      throw const FormatException('Invalid DSH session catalog');
    }
    final rows = <EngineSession>[];
    for (final raw in value['items'] as List) {
      if (raw is! Map ||
          raw['sessionId'] is! String ||
          (raw['sessionId'] as String).isEmpty) {
        throw const FormatException('Invalid DSH session identity');
      }
      if (raw['origin'] == 'subagent' || raw['parentSessionId'] != null) {
        continue;
      }
      rows.add(
        EngineSession(
          id: raw['sessionId'] as String,
          updatedAt: raw['updatedAt'] is num ? raw['updatedAt'] as num : 0,
          cwd: raw['cwd'] is String ? raw['cwd'] as String : '',
          title: readTitle(raw['projections']),
          running: raw['running'] == true,
          openable: raw['formatStatus'] != 'migration-required',
          blank: raw['blank'] == true,
        ),
      );
    }
    rows.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return List.unmodifiable(rows);
  }

  /// Read the `title` projection value out of a Session summary.
  ///
  /// A summary carries `projections: {kind, asOfSeq, values: {title: ...}}`.
  /// Anything unrecognized yields null instead of inventing a name.
  static String? readTitle(Object? projections) {
    if (projections is! Map) return null;
    final values = projections['values'];
    if (values is! Map) return null;
    final title = values['title'];
    if (title is! String) return null;
    final trimmed = title.trim();
    return trimmed.isEmpty ? null : trimmed;
  }
}

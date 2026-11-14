class EngineSession {
  const EngineSession({
    required this.id,
    required this.updatedAt,
    this.cwd = '',
    this.running = false,
    this.openable = true,
  });
  final String id;
  final num updatedAt;
  final String cwd;
  final bool running, openable;
  static List<EngineSession> fromResponse(Object? value) {
    if (value is! Map || value['items'] is! List)
      throw const FormatException('Invalid DSH session catalog');
    final rows = <EngineSession>[];
    for (final raw in value['items'] as List) {
      if (raw is! Map ||
          raw['sessionId'] is! String ||
          (raw['sessionId'] as String).isEmpty)
        throw const FormatException('Invalid DSH session identity');
      if (raw['origin'] == 'subagent' || raw['parentSessionId'] != null)
        continue;
      rows.add(
        EngineSession(
          id: raw['sessionId'] as String,
          updatedAt: raw['updatedAt'] is num ? raw['updatedAt'] as num : 0,
          cwd: raw['cwd'] is String ? raw['cwd'] as String : '',
          running: raw['running'] == true,
          openable: raw['formatStatus'] != 'migration-required',
        ),
      );
    }
    rows.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    return List.unmodifiable(rows);
  }
}

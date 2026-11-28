class EngineWorkspace {
  const EngineWorkspace({
    required this.id,
    required this.title,
    required this.path,
    required this.sessionIds,
  });

  final String id, title, path;
  final List<String> sessionIds;

  factory EngineWorkspace.fromWire(Object? value) {
    if (value is! Map ||
        value['workspaceId'] is! String ||
        value['title'] is! String ||
        value['path'] is! String) {
      throw const FormatException('Invalid DSH Workspace');
    }
    return EngineWorkspace(
      id: value['workspaceId'] as String,
      title: value['title'] as String,
      path: value['path'] as String,
      sessionIds: _ids(value['sessionIds']),
    );
  }
}

/// A generation of workspace/follow, independent from session summaries.
class EngineWorkspaceCatalog {
  final _rows = <String, EngineWorkspace>{};
  var _order = <String>[];
  var _archived = <String>{};
  var _pinned = <String>[];
  bool _ready = false;

  bool get ready => _ready;
  List<EngineWorkspace> get workspaces => List.unmodifiable(
    _order.map((id) => _rows[id]).whereType<EngineWorkspace>(),
  );
  Set<String> get archived => Set.unmodifiable(_archived);
  List<String> get pinned => List.unmodifiable(_pinned);

  void accept(Map<String, Object?> frame) {
    final kind = frame['type'];
    if (!_ready && kind != 'baseline') {
      throw const FormatException('Missing DSH Workspace baseline');
    }
    switch (kind) {
      case 'baseline':
      case 'replace':
        final value = frame['value'];
        if (value is! Map || value['items'] is! List) {
          throw const FormatException('Invalid DSH Workspace catalogue');
        }
        final rows = (value['items'] as List)
            .map(EngineWorkspace.fromWire)
            .toList();
        final archived = _ids(value['archivedSessionIds']).toSet();
        final pinned = _ids(value['pinnedSessionIds']);
        _rows
          ..clear()
          ..addEntries(rows.map((row) => MapEntry(row.id, row)));
        _order = rows.map((row) => row.id).toList();
        _archived = archived;
        _pinned = pinned;
        _ready = true;
      case 'upsert':
        final row = EngineWorkspace.fromWire(frame['workspace']);
        _rows[row.id] = row;
      case 'order':
        _order = _ids(frame['workspaceIds']);
      case 'remove':
        final id = frame['workspaceId'];
        if (id is! String)
          throw const FormatException('Invalid DSH Workspace id');
        _rows.remove(id);
      case 'archived':
        _archived = _ids(frame['archivedSessionIds']).toSet();
      case 'pinned':
        _pinned = _ids(frame['pinnedSessionIds']);
      default:
        throw const FormatException('Unknown DSH Workspace frame');
    }
  }
}

List<String> _ids(Object? value) {
  if (value is! List || value.any((id) => id is! String || id.isEmpty)) {
    throw const FormatException('Invalid DSH identity list');
  }
  return List.unmodifiable(value.cast<String>());
}

import 'package:dsh_native_chat/src/model/engine_workspace.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, Object?> _workspace(String id, List<String> sessions) => {
  'workspaceId': id,
  'path': '/engine/$id',
  'title': 'Workspace $id',
  'sessionIds': sessions,
  'createdAt': '2026-11-22T00:00:00Z',
  'updatedAt': '2026-11-22T00:00:00Z',
};

Map<String, Object?> _snapshot(
  String type,
  List<Map<String, Object?>> items,
) => {
  'type': type,
  'value': {'items': items, 'archivedSessionIds': [], 'pinnedSessionIds': []},
};

void main() {
  test('Workspace feed preserves baseline and incremental registry order', () {
    final catalog = EngineWorkspaceCatalog();
    final upsert = {
      'type': 'upsert',
      'workspace': _workspace('c', ['old', 'new']),
    };
    List<String> ids() =>
        catalog.workspaces.map((workspace) => workspace.id).toList();
    expect(() => catalog.accept(upsert), throwsFormatException);
    catalog.accept(
      _snapshot('baseline', [_workspace('b', []), _workspace('a', [])]),
    );
    expect(ids(), ['b', 'a']);
    catalog.accept(upsert);
    expect(ids(), ['b', 'a']);
    catalog.accept({
      'type': 'order',
      'workspaceIds': ['c', 'a', 'b'],
    });
    expect(ids(), ['c', 'a', 'b']);
    expect(catalog.workspaces.first.sessionIds, ['old', 'new']);
    catalog.accept({
      'type': 'archived',
      'archivedSessionIds': ['old'],
    });
    catalog.accept({
      'type': 'pinned',
      'pinnedSessionIds': ['new'],
    });
    expect(catalog.archived, {'old'});
    expect(catalog.pinned, ['new']);
    catalog.accept({'type': 'remove', 'workspaceId': 'a'});
    expect(ids(), ['c', 'b']);
    catalog.accept(
      _snapshot('replace', [
        _workspace('b', ['b-session']),
      ]),
    );
    expect(ids(), ['b']);
    expect(catalog.workspaces.single.sessionIds, ['b-session']);
    expect(catalog.archived, isEmpty);
    expect(catalog.pinned, isEmpty);
  });
}

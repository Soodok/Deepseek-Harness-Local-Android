import '../model/engine_workspace.dart';

/// A null override means no override; Home is an explicit, consumable target.
class WorkspaceTarget {
  const WorkspaceTarget.home() : id = null, path = null;
  const WorkspaceTarget.workspace(String this.id, String this.path);
  final String? id, path;
  Map<String, Object?> toWire() =>
      id == null ? {'home': true} : {'workspaceId': id, 'path': path};
  factory WorkspaceTarget.fromWire(Object? raw) {
    if (raw is Map && raw.length == 1 && raw['home'] == true) {
      return const WorkspaceTarget.home();
    }
    if (raw is! Map ||
        raw['workspaceId'] is! String ||
        raw['path'] is! String ||
        (raw['workspaceId'] as String).trim().isEmpty ||
        !(raw['path'] as String).startsWith('/') ||
        (raw['path'] as String).codeUnits.any((c) => c < 32 || c == 127)) {
      throw const FormatException('Invalid native workspace preference');
    }
    return WorkspaceTarget.workspace(
      raw['workspaceId'] as String,
      raw['path'] as String,
    );
  }
  @override
  bool operator ==(Object other) =>
      other is WorkspaceTarget && other.id == id && other.path == path;
  @override
  int get hashCode => Object.hash(id, path);
}

class WorkspaceResolution {
  const WorkspaceResolution(this.workspace, this.usedOverride);
  final EngineWorkspace? workspace;
  final WorkspaceTarget? usedOverride;
}

/// Native-only policy. It never mutates the Host registry or existing Sessions.
class NativeWorkspacePolicy {
  NativeWorkspacePolicy({
    required this.domain,
    required this.generation,
    required this.read,
    required this.write,
    required this.remove,
  });
  final String domain, generation;
  final Future<Object?> Function() read;
  final Future<bool> Function(WorkspaceTarget) write;
  final Future<bool> Function() remove;
  WorkspaceTarget? override;
  WorkspaceTarget? defaultTarget;
  bool known = false;
  String? error;
  int _revision = 0;
  bool get trusted => domain.isNotEmpty && generation.isNotEmpty;

  WorkspaceTarget? _receipt(Object? raw) {
    if (raw is! Map ||
        raw['domain'] != domain ||
        raw['generation'] != generation ||
        !raw.containsKey('target')) {
      throw StateError('默认工作区回执不属于当前本机引擎，请重试');
    }
    return raw['target'] == null
        ? null
        : WorkspaceTarget.fromWire(raw['target']);
  }

  Future<void> refresh() async {
    final revision = ++_revision;
    known = false;
    error = null;
    try {
      if (!trusted) throw StateError('无法确认当前引擎的数据域，不能读取持久默认');
      final target = _receipt(await read().timeout(const Duration(seconds: 2)));
      if (revision != _revision) return;
      defaultTarget = target;
      known = true;
    } on Object catch (e) {
      if (revision == _revision) error = e.toString();
    }
  }

  Future<bool> save(WorkspaceTarget target) =>
      _persist(target, () => write(target));

  Future<bool> clear() => _persist(null, remove);

  Future<bool> _persist(
    WorkspaceTarget? target,
    Future<bool> Function() mutate,
  ) async {
    final revision = ++_revision;
    known = false;
    error = null;
    try {
      if (!trusted) throw StateError('无法确认当前引擎的数据域，拒绝修改默认工作区');
      if (!await mutate().timeout(const Duration(seconds: 2))) {
        throw StateError(target == null ? '默认工作区清除失败，请重试' : '默认工作区保存失败，请重试');
      }
      if (revision != _revision) return false;
      final actual = _receipt(await read().timeout(const Duration(seconds: 2)));
      if (revision != _revision) return false;
      if (actual != target) throw StateError('默认工作区回读不一致，未确认修改');
      defaultTarget = actual;
      known = true;
      return true;
    } on Object catch (e) {
      if (revision == _revision) error = e.toString();
      return false;
    }
  }

  WorkspaceResolution resolve({
    required List<EngineWorkspace> rows,
    required bool online,
    WorkspaceTarget? explicit,
  }) {
    final next = explicit == null ? override : null;
    final target = explicit ?? next;
    // Explicit Home is the recovery path even with unknown prefs/catalogue.
    if (target != null && target.id == null)
      return WorkspaceResolution(null, next);
    if (target == null && !known)
      throw StateError(error ?? '默认工作区尚未读取，请重试或明确仅本次 Home');
    if (!online) throw StateError('工作区目录尚未就绪，请重试或明确仅本次 Home');
    final selected = target ?? defaultTarget;
    if (selected?.id == null) return WorkspaceResolution(null, next);
    final match = rows
        .where((row) => row.id == selected!.id && row.path == selected.path)
        .firstOrNull;
    if (match == null) throw StateError('所选工作区已失效或路径已变化，请重新选择');
    return WorkspaceResolution(match, next);
  }

  void consume(WorkspaceResolution resolution) {
    if (resolution.usedOverride != null &&
        identical(override, resolution.usedOverride))
      override = null;
  }
}

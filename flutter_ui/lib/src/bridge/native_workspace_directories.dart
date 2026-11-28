import 'package:flutter/services.dart';

import 'host_bridge.dart';

bool _validPath(Object? value) => value is String &&
    value.startsWith('/') && !value.startsWith('//') &&
    !value.codeUnits.any((c) => c < 32 || c == 127) &&
    !value.split('/').any((part) => part == '.' || part == '..');

class NativeWorkspaceDirectory {
  const NativeWorkspaceDirectory({required this.path, required this.name,
    required this.kind, this.uri});
  final String path, name, kind;
  final String? uri;

  factory NativeWorkspaceDirectory.fromWire(Object? value) {
    if (value is! Map || !_validPath(value['path']) || value['name'] is! String ||
        !const ['private', 'shared'].contains(value['kind']) ||
        (value['kind'] == 'shared' && (value['uri'] is! String ||
          Uri.tryParse(value['uri'] as String)?.scheme != 'content'))) {
      throw const FormatException('目录回执无效，不能作为引擎工作区');
    }
    return NativeWorkspaceDirectory(path: value['path'] as String,
      name: value['name'] as String, kind: value['kind'] as String,
      uri: value['uri'] as String?);
  }
}

class NativePrivateWorkspaceListing {
  const NativePrivateWorkspaceListing({required this.root, required this.path,
    required this.directories});
  final String root, path;
  final List<NativeWorkspaceDirectory> directories;
}

/// Directory authorization is independent from session tool permissions.
class NativeWorkspaceDirectories {
  const NativeWorkspaceDirectories(this.host);
  final HostInfo host;
  static const _channel = MethodChannel('app.dsh.mobile/native_workspace');

  Future<Object?> _call(String method, [Map<String, Object?> args = const {}]) {
    if (!host.available || host.workspaceDomain.isEmpty || host.workspaceGeneration.isEmpty) {
      throw StateError('本机引擎尚未就绪，无法确认工作区目录');
    }
    return _channel.invokeMethod<Object?>(method, {
      'domain': host.workspaceDomain, 'generation': host.workspaceGeneration, ...args,
    });
  }

  Map _receipt(Object? value) {
    if (value is! Map || value['domain'] != host.workspaceDomain ||
        value['generation'] != host.workspaceGeneration) {
      throw const FormatException('目录回执不属于当前本机引擎，请重新选择');
    }
    return value;
  }

  Future<NativePrivateWorkspaceListing> listPrivate([String? path]) async {
    final raw = _receipt(await _call('listPrivateWorkspaces', {'path': ?path}));
    if (!_validPath(raw['root']) || !_validPath(raw['path']) || raw['directories'] is! List) {
      throw const FormatException('应用私有目录列表无效');
    }
    final root = raw['root'] as String;
    bool inside(String value) => value == root || value.startsWith('$root/');
    if (!inside(raw['path'] as String)) throw const FormatException('应用私有目录越界');
    final rows = (raw['directories'] as List).map((value) {
      if (value is! Map) throw const FormatException('应用私有目录条目无效');
      final row = NativeWorkspaceDirectory.fromWire({...value, 'kind': 'private'});
      if (!inside(row.path)) throw const FormatException('应用私有目录条目越界');
      return row;
    }).toList(growable: false);
    return NativePrivateWorkspaceListing(root: root, path: raw['path'] as String,
      directories: rows);
  }

  Future<NativeWorkspaceDirectory> createPrivate(String parent, String name) async =>
      NativeWorkspaceDirectory.fromWire(_receipt(await _call('createPrivateWorkspace',
        {'parent': parent, 'name': name})));

  Future<NativeWorkspaceDirectory> selectPrivate(String path) async =>
      NativeWorkspaceDirectory.fromWire(_receipt(await _call('selectPrivateWorkspace', {'path': path})));

  Future<NativeWorkspaceDirectory?> pickShared() async {
    final value = await _call('pickSharedWorkspace');
    return value == null ? null : NativeWorkspaceDirectory.fromWire(_receipt(value));
  }

  Future<NativeWorkspaceDirectory> validate(NativeWorkspaceDirectory selected) async {
    final actual = NativeWorkspaceDirectory.fromWire(_receipt(await _call('validateWorkspaceDirectory', {
      'path': selected.path, 'kind': selected.kind, 'uri': ?selected.uri,
    })));
    if (actual.path != selected.path || actual.kind != selected.kind || actual.uri != selected.uri) {
      throw const FormatException('工作区目录已变化，请重新选择');
    }
    return actual;
  }

  Future<void> remember(NativeWorkspaceDirectory directory) async {
    if (await _call('rememberWorkspaceDirectory', {
          'path': directory.path,
          'kind': directory.kind,
          'uri': ?directory.uri,
        }) != true) {
      throw const FormatException('目录授权未确认，请重新选择');
    }
  }

  Future<void> validateRegistered(String path) async {
    if (await _call('validateRegisteredWorkspaceDirectory', {'path': path}) != true) {
      throw const FormatException('工作区目录已失效，请重新选择');
    }
  }
}

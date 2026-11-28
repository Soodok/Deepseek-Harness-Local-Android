import 'dart:typed_data';

import 'package:mime/mime.dart';
import 'package:path/path.dart' as p;

import '../bridge/host_bridge.dart';
import 'dsh_rpc_connection.dart';

class FilePreviewData {
  const FilePreviewData({
    required this.displayName,
    required this.target,
    required this.mime,
    required this.absolutePath,
    this.text,
    this.bytes,
    this.byteLength = 0,
    this.version = '',
    this.truncated = false,
  });
  final String displayName;
  final String target;
  final String mime;
  final String absolutePath;
  final String? text;
  final Uint8List? bytes;
  final int byteLength;
  final String version;
  final bool truncated;
  bool get isImage => bytes != null && mime.startsWith('image/');
  bool get isText => text != null;
}

/// Uses the current Session's DSH workspace API for previews and share copies.
class NativeFileDeliveryService {
  NativeFileDeliveryService({
    required this.rpc,
    required this.sessionId,
    required this.workspaceRoot,
  });
  final DshRpcConnection rpc;
  final String sessionId;
  final String workspaceRoot;
  static const maxPreviewBytes = 8 * 1024 * 1024;
  static const maxShareBytes = 32 * 1024 * 1024;
  static const maxPreviewLines = 800;

  bool get _windows =>
      !workspaceRoot.startsWith('/') || workspaceRoot.startsWith('//');
  p.Context get _paths =>
      p.Context(style: _windows ? p.Style.windows : p.Style.posix);

  Future<FilePreviewData> preview({
    required String displayName,
    required String target,
  }) async {
    final path = _resolve(target);
    final mime = mimeFor(path);
    if (isTextMime(mime)) {
      final page = await _call('read', path, {
        'range': {'offset': 1, 'limit': maxPreviewLines},
      });
      final text = page['text'];
      if (text is! String) throw const FormatException('DSH 返回的内容不是文本');
      return FilePreviewData(
        displayName: displayName,
        target: target,
        mime: mime,
        absolutePath: page['absolutePath'] as String,
        text: text,
        byteLength: _size(page),
        version: page['version']?.toString() ?? '',
        truncated: page['eof'] != true,
      );
    }
    if (mime.startsWith('image/')) {
      final file = await _bytes(path, maxPreviewBytes);
      return FilePreviewData(
        displayName: displayName,
        target: target,
        mime: mime,
        absolutePath: file['absolutePath'] as String,
        bytes: file['data'] as Uint8List,
        byteLength: _size(file),
        version: file['version']?.toString() ?? '',
      );
    }
    throw UnsupportedError('此格式请用其他应用打开');
  }

  Future<bool> open({required String target, required String mime}) async {
    final path = _resolve(target);
    final file = await _bytes(path, maxShareBytes);
    return HostBridge.openFileBytes(
      file['data'] as Uint8List,
      name: _paths.basename(file['absolutePath'] as String),
      mime: mimeFor(path, fallback: mime),
    );
  }

  Future<bool> share({required String target, required String mime}) async {
    final path = _resolve(target);
    final file = await _bytes(path, maxShareBytes);
    return HostBridge.shareFileBytes(
      file['data'] as Uint8List,
      name: _paths.basename(file['absolutePath'] as String),
      mime: mimeFor(path, fallback: mime),
    );
  }

  Future<Map<String, Object?>> _bytes(String path, int limit) async {
    final info = await _call('stat', path);
    if (_size(info) > limit)
      throw const DshRpcException('file/too-large', '文件超过本次操作的大小上限');
    // No byte range: DSH's per-range limit is 2 MiB. The complete-file endpoint
    // returns one snapshot (up to its 32 MiB cap), with no partial-image decode.
    final file = await _call('readBytes', path, {
      'options': <String, Object?>{},
    });
    final bytes = file['data'];
    if (bytes is! Uint8List ||
        file['eof'] != true ||
        bytes.length > limit ||
        (info['version'] != null && file['version'] != info['version']) ||
        (file['bytes'] is num && _size(file) != bytes.length)) {
      throw const DshRpcException('file/incomplete', '文件未读完整或在读取时发生变化，请重试');
    }
    return file;
  }

  Future<Map<String, Object?>> _call(
    String method,
    String path, [
    Map<String, Object?> extra = const {},
  ]) async {
    final value = await rpc.call('workspaceFiles/$method', {
      'workspaceFileScopeId': sessionId,
      'path': path,
      ...extra,
    });
    if (value is! Map<String, Object?> || value['absolutePath'] is! String) {
      throw const FormatException('DSH 文件响应无效');
    }
    final absolute = _paths.normalize(value['absolutePath'] as String);
    _checkInside(absolute);
    return {...value, 'absolutePath': absolute.replaceAll('\\', '/')};
  }

  String _resolve(String target) {
    if (workspaceRoot.isEmpty || !_paths.isAbsolute(workspaceRoot)) {
      throw const FormatException('当前会话没有可用工作目录');
    }
    var raw = target.trim();
    if (raw.startsWith('dsh-resource://file/')) {
      final uri = Uri.parse(raw);
      final pieces = uri.pathSegments;
      if (pieces.isEmpty) throw const FormatException('文件资源地址无效');
      if (pieces.first == 'session' && pieces.length >= 3) {
        if (pieces[1] != sessionId) throw const FormatException('文件属于其他会话');
        raw = pieces.skip(2).join('/');
      } else if (pieces.first == 'absolute' && pieces.length >= 2) {
        raw = pieces.skip(1).join('/');
        if (!RegExp(r'^[A-Za-z]:/').hasMatch(raw)) raw = '/$raw';
      } else {
        throw const FormatException('文件资源地址无效');
      }
    } else if (raw.startsWith('file:')) {
      final uri = Uri.parse(raw);
      if (uri.host.isNotEmpty && uri.host != 'localhost')
        throw const FormatException('不支持远程文件地址');
      raw = uri.toFilePath(windows: _windows);
    } else {
      if (!RegExp(r'^[A-Za-z]:[/\\]').hasMatch(raw) &&
          Uri.parse(raw).hasScheme) {
        throw const FormatException('不支持此文件链接');
      }
      final literal = raw.split(RegExp(r'[?#]')).first;
      try {
        raw = Uri.decodeComponent(literal);
      } on FormatException {
        raw = literal;
      } on ArgumentError {
        raw = literal;
      }
    }
    if (raw.isEmpty || raw.codeUnits.any((unit) => unit < 32))
      throw const FormatException('文件链接无效');
    raw = raw.replaceAll('\\', '/');
    final absolute = _paths.normalize(
      _paths.isAbsolute(raw) ? raw : _paths.join(workspaceRoot, raw),
    );
    _checkInside(absolute);
    return _paths
        .relative(absolute, from: _paths.normalize(workspaceRoot))
        .replaceAll('\\', '/');
  }

  void _checkInside(String absolute) {
    final root = _paths.normalize(workspaceRoot);
    if (!_paths.isAbsolute(absolute) || !_paths.isWithin(root, absolute)) {
      throw const FormatException('文件不在当前会话工作区内');
    }
  }

  static int _size(Map<String, Object?> file) =>
      file['bytes'] is num ? (file['bytes'] as num).toInt() : 0;

  static String mimeFor(
    String path, {
    String fallback = 'application/octet-stream',
  }) {
    path = path.split(RegExp(r'[?#]')).first;
    final extension = extensionOf(path).toLowerCase();
    if (_codeExtensions.contains(extension)) return 'text/plain';
    if (extension == 'svg') return 'image/svg+xml';
    if (extension == 'apk') return 'application/vnd.android.package-archive';
    return lookupMimeType(path) ?? fallback;
  }

  static bool isTextMime(String mime) =>
      mime.startsWith('text/') ||
      const {
        'application/json',
        'application/xml',
        'application/javascript',
        'application/x-sh',
        'image/svg+xml',
      }.contains(mime);
  static String extensionOf(String path) =>
      p.posix.extension(path.replaceAll('\\', '/')).replaceFirst('.', '');
  static const _codeExtensions = {
    'dart',
    'py',
    'kt',
    'kts',
    'java',
    'c',
    'h',
    'cc',
    'cpp',
    'rs',
    'go',
    'js',
    'mjs',
    'cjs',
    'ts',
    'tsx',
    'jsx',
    'sh',
    'bash',
    'ps1',
    'css',
    'scss',
    'html',
    'vue',
    'svelte',
    'yaml',
    'yml',
    'toml',
    'ini',
    'sql',
    'csv',
    'md',
    'txt',
    'log',
  };
}

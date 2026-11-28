import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:mime/mime.dart';

/// DSH Connection returns native byte values as FormData parts. Metadata paths
/// refer to null placeholders inside result.value, never to URLs to be fetched.
Future<Map<String, Object?>> decodeRpcBody(
  List<int> bytes,
  ContentType? type,
) async {
  if (type?.mimeType != 'multipart/form-data') {
    if (type?.mimeType != 'application/json') {
      throw const FormatException('Unexpected RPC content type');
    }
    return _object(jsonDecode(utf8.decode(bytes)));
  }
  final boundary = type!.parameters['boundary'];
  if (boundary == null || boundary.isEmpty || boundary.length > 200) {
    throw const FormatException('Invalid RPC multipart boundary');
  }
  final parts = <String, Uint8List>{};
  await for (final part in MimeMultipartTransformer(
    boundary,
  ).bind(Stream.value(bytes))) {
    final disposition = part.headers['content-disposition'];
    final name = disposition == null
        ? null
        : HeaderValue.parse(disposition).parameters['name'];
    if (name == null ||
        name.isEmpty ||
        parts.containsKey(name) ||
        parts.length >= 32) {
      throw const FormatException('Invalid RPC multipart part');
    }
    final collected = BytesBuilder(copy: false);
    await for (final chunk in part) {
      collected.add(chunk);
    }
    parts[name] = collected.takeBytes();
  }
  final metadata = parts.remove('metadata');
  if (metadata == null) throw const FormatException('Missing RPC metadata');
  final envelope = _object(jsonDecode(utf8.decode(metadata)));
  final result = _object(envelope['result']);
  final attachments = envelope['attachments'];
  if (result['ok'] != true || attachments is! List || attachments.isEmpty) {
    throw const FormatException('Invalid RPC attachments');
  }
  for (final item in attachments) {
    final attachment = _object(item);
    final path = attachment['path'];
    final part = attachment['part'];
    if (attachment['codec'] != 'bytes' ||
        path is! List ||
        part is! String ||
        !parts.containsKey(part)) {
      throw const FormatException('Invalid RPC byte attachment');
    }
    final data = parts.remove(part)!;
    Object? parent = result;
    final keys = <Object?>['value', ...path];
    for (var index = 0; index < keys.length; index++) {
      final key = keys[index];
      final last = index == keys.length - 1;
      if (parent is Map<String, Object?> &&
          key is String &&
          parent.containsKey(key)) {
        if (last) {
          if (parent[key] != null)
            throw const FormatException('RPC byte slot is not null');
          parent[key] = data;
        } else {
          parent = parent[key];
        }
      } else if (parent is List &&
          key is int &&
          key >= 0 &&
          key < parent.length) {
        if (last) {
          if (parent[key] != null)
            throw const FormatException('RPC byte slot is not null');
          parent[key] = data;
        } else {
          parent = parent[key];
        }
      } else {
        throw const FormatException('Invalid RPC attachment path');
      }
    }
  }
  if (parts.isNotEmpty)
    throw const FormatException('Unreferenced RPC byte part');
  envelope['result'] = result;
  return envelope;
}

Map<String, Object?> _object(Object? value) {
  if (value is! Map<String, Object?>)
    throw const FormatException('Invalid RPC metadata object');
  return value;
}

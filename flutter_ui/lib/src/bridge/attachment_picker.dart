import 'dart:typed_data';

import 'package:file_selector/file_selector.dart';

import '../model/composer_model.dart';
import '../state/chat_controller.dart';
import '../transport/dsh_transport.dart';

class AttachmentPicker {
  static int _nextId = 0;
  static Future<List<PromptAttachment>> pick({
    required int remainingFiles,
    required int remainingBytes,
  }) async {
    final files = await openFiles();
    return readSelection(
      files,
      remainingFiles: remainingFiles,
      remainingBytes: remainingBytes,
    );
  }

  static Future<List<PromptAttachment>> readSelection(
    List<XFile> files, {
    required int remainingFiles,
    required int remainingBytes,
  }) async {
    if (files.length > remainingFiles)
      throw const TransportException('最多选择5个文件，请减少数量');
    final result = <PromptAttachment>[];
    var budget = remainingBytes;
    for (final file in files) {
      if (await file.length() > budget)
        throw const TransportException('附件总大小不能超过20MB');
      final bytes = BytesBuilder(copy: false);
      await for (final chunk in file.openRead()) {
        budget -= chunk.length;
        if (budget < 0) throw const TransportException('附件总大小不能超过20MB');
        bytes.add(chunk);
      }
      result.add(
        PromptAttachment(
          id: 'attachment-' + (_nextId++).toString(),
          name: file.name,
          bytes: bytes.takeBytes(),
        ),
      );
    }
    return result;
  }

  static int remainingBytes(List<PromptAttachment> files) =>
      ChatController.maxAttachmentBytes -
      files.fold<int>(0, (sum, file) => sum + file.bytes.length);
}

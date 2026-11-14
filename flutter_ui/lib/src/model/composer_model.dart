import 'dart:typed_data';

enum ComposerPermission {
  ask('逐次确认', '每次工具执行前确认'),
  readOnly('只读', '仅允许读取；禁止修改和执行命令');

  const ComposerPermission(this.label, this.description);
  final String label;
  final String description;
}

class ComposerModel {
  const ComposerModel(this.id, this.label, this.description);
  final String id;
  final String label;
  final String description;
}

class PromptAttachment {
  PromptAttachment({
    required this.id,
    required this.name,
    required Uint8List bytes,
  }) : bytes = Uint8List.fromList(bytes).asUnmodifiableView();
  final String id;
  final String name;
  final Uint8List bytes;
}

class PromptSubmission {
  PromptSubmission({
    required this.text,
    required this.modelId,
    required this.permission,
    List<PromptAttachment> attachments = const [],
  }) : attachments = List.unmodifiable(attachments);
  final String text;
  final String? modelId;
  final ComposerPermission permission;
  final List<PromptAttachment> attachments;
  String get displayText => [
    if (text.isNotEmpty) text,
    for (final file in attachments) '附件：' + file.name,
  ].join(String.fromCharCode(10));
}

/// Optional transport capability. A transport must implement this before the
/// composer enables model selection, tool policy or binary attachments.
abstract interface class ComposerTransport {
  List<ComposerModel> get models;
  Future<void> sendSubmission(PromptSubmission submission);
}

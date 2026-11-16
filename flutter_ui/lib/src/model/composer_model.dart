import 'dart:async';
import 'dart:typed_data';

enum ComposerPermission {
  readOnly('read-only', '只读', '读取项目；写入与受限操作需要确认'),
  workspaceWrite('workspace-write', '工作区写入', '允许修改当前工作区，范围外操作需要确认'),
  fullAccess('danger-full-access', '完全访问', '允许访问工作区之外的文件和执行命令');

  const ComposerPermission(this.wireValue, this.label, this.description);
  final String wireValue;
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
    this.applyModel = true,
    this.applyPermission = true,
    this.modelRevision = 0,
    this.permissionRevision = 0,
    List<PromptAttachment> attachments = const [],
  }) : attachments = List.unmodifiable(attachments);
  final String text;
  final String? modelId;
  final ComposerPermission permission;

  /// False means inherit the Host value, even when a display value was captured.
  final bool applyModel;
  final bool applyPermission;
  final int modelRevision;
  final int permissionRevision;
  final List<PromptAttachment> attachments;
  String get displayText => [
    if (text.isNotEmpty) text,
    for (final file in attachments) '附件：' + file.name,
  ].join(String.fromCharCode(10));
}

/// Session values are independent of catalog defaults and explicit draft overrides.
class ComposerConfiguration {
  const ComposerConfiguration({
    this.modelId,
    this.modelLabel,
    this.permissionPreset,
  });

  final String? modelId;
  final String? modelLabel;
  final String? permissionPreset;
  ComposerPermission? get permission => ComposerPermission.values
      .where((mode) => mode.wireValue == permissionPreset)
      .firstOrNull;
}

/// Optional read capability for transports whose configuration is owned by a Host.
abstract interface class SessionComposerTransport {
  ComposerConfiguration get composerConfiguration;
  Stream<ComposerConfiguration> get composerConfigurationChanges;
}

/// Explicit restrictions advertised by transports with partial composer support.
abstract interface class ComposerCapabilities {
  bool get supportsAttachments;
  List<ComposerPermission> get permissions;
}

/// Optional transport capability. A transport must implement this before the
/// composer enables model selection, tool policy or binary attachments.
abstract interface class ComposerTransport {
  List<ComposerModel> get models;
  Future<void> sendSubmission(PromptSubmission submission);
}

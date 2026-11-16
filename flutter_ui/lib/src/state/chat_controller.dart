import 'dart:async';

import 'package:flutter/foundation.dart';

import '../model/chat_model.dart';
import '../model/composer_model.dart';
import '../transport/dsh_transport.dart';
import 'chat_reducer.dart';
import 'chat_state.dart';

class ChatController extends ChangeNotifier {
  ChatController(this.transport) {
    if (transport is SessionComposerTransport) {
      _permission = ComposerPermission.readOnly;
      _restoreComposerConfiguration(
        (transport as SessionComposerTransport).composerConfiguration,
        notify: false,
      );
    } else if (models.isNotEmpty) {
      _modelId = models.first.id;
    }
  }

  final DshTransport transport;
  ChatState _state = ChatState.empty();
  StreamSubscription<ChatEvent>? _eventsSubscription;
  StreamSubscription<ComposerConfiguration>? _configurationSubscription;
  bool _modelChanged = false;
  bool _permissionChanged = false;
  String? _configurationError;
  bool _started = false;
  bool _disposed = false;
  bool _resyncInFlight = false;
  bool _queuedDispatchInFlight = false;
  String? _pendingAction;
  bool _stopInFlight = false;
  bool actionBusy(String requestId) =>
      _pendingAction == requestId || _stopInFlight;

  ChatState get state => _state;
  final List<PromptAttachment> _attachments = [];
  final List<PromptSubmission> _queuedSubmissions = [];
  String? _modelId;
  ComposerPermission _permission = ComposerPermission.workspaceWrite;
  List<ComposerModel> get models => transport is ComposerTransport
      ? List.unmodifiable((transport as ComposerTransport).models)
      : const [];
  bool get supportsComposerOptions => transport is ComposerTransport;
  bool get supportsAttachments =>
      supportsComposerOptions &&
      (transport is! ComposerCapabilities ||
          (transport as ComposerCapabilities).supportsAttachments);
  bool supportsPermission(ComposerPermission mode) =>
      supportsComposerOptions &&
      (transport is! ComposerCapabilities ||
          (transport as ComposerCapabilities).permissions.contains(mode));
  bool get supportsStructuredQuestions =>
      transport is StructuredQuestionTransport;
  String? get selectedModelId => _modelId;
  String get modelLabel =>
      models.where((m) => m.id == _modelId).firstOrNull?.label ??
      (transport is SessionComposerTransport
          ? (transport as SessionComposerTransport)
                .composerConfiguration
                .modelLabel
          : null) ??
      (supportsComposerOptions ? '当前会话模型' : '模型未接入');
  ComposerPermission get permission => _permission;
  String? get configurationError => _configurationError;
  bool get permissionKnown => _configurationError == null;
  String get permissionLabel =>
      permissionKnown ? _permission.label : '未知 · 请确认';
  int _modelRevision = 0;
  int _permissionRevision = 0;
  List<PromptAttachment> get attachments => List.unmodifiable(_attachments);
  static const maxFiles = 5;
  static const maxAttachmentBytes = 20 * 1024 * 1024;
  void selectModel(String id) {
    if (!models.any((m) => m.id == id)) throw ArgumentError.value(id, 'model');
    _modelId = id;
    _modelChanged = true;
    ++_modelRevision;
    if (!_disposed) notifyListeners();
  }

  void selectPermission(ComposerPermission mode) {
    if (!supportsPermission(mode))
      throw const TransportException('当前引擎未提供此权限模式，请使用 Web 版查看');
    _permission = mode;
    _permissionChanged = true;
    ++_permissionRevision;
    if (transport is SessionComposerTransport) {
      _restoreComposerConfiguration(
        (transport as SessionComposerTransport).composerConfiguration,
      );
    } else if (!_disposed) {
      notifyListeners();
    }
  }

  void addAttachments(List<PromptAttachment> files) {
    if (_disposed) return;
    if (!supportsAttachments) throw const TransportException('当前引擎尚未接入附件');
    final next = [..._attachments, ...files];
    if (next.length > maxFiles ||
        next.fold<int>(0, (sum, f) => sum + f.bytes.length) >
            maxAttachmentBytes) {
      throw const TransportException('最多选择5个文件，总大小不超过20MB');
    }
    if (next.map((f) => f.id).toSet().length != next.length)
      throw ArgumentError('附件标识重复');
    _attachments.addAll(files);
    notifyListeners();
  }

  void removeAttachment(String id) {
    _attachments.removeWhere((f) => f.id == id);
    if (!_disposed) notifyListeners();
  }

  bool get canSend =>
      _configurationError == null &&
      (_state.status == ChatStatus.ready ||
          _state.status == ChatStatus.streaming ||
          _state.status == ChatStatus.waitingAction);

  String? get queuedDispatchError {
    if (_queuedSubmissions.isEmpty || transport is! SessionComposerTransport)
      return null;
    final queued = _queuedSubmissions.first;
    if (!queued.applyPermission &&
        (transport as SessionComposerTransport)
                .composerConfiguration
                .permission ==
            null) {
      return '队首消息继承的会话权限未知。请先在 Web 确认权限，或发送当前明确选择权限的新消息。';
    }
    if (queued.applyPermission && !supportsPermission(queued.permission))
      return '队首消息选择的权限暂不可用，请重试连接或打开 Web。';
    return null;
  }

  bool get canDispatchQueued =>
      !_queuedDispatchInFlight &&
      _queuedSubmissions.isNotEmpty &&
      queuedDispatchError == null &&
      _state.status == ChatStatus.ready;

  Future<void> start() async {
    if (_started || _disposed) return;
    _started = true;
    if (transport is SessionComposerTransport) {
      final session = transport as SessionComposerTransport;
      _configurationSubscription = session.composerConfigurationChanges.listen(
        _restoreComposerConfiguration,
      );
      _restoreComposerConfiguration(session.composerConfiguration);
    }
    _eventsSubscription = transport.events.listen(
      _onEvent,
      onError: (Object error, StackTrace stack) => _setError(error.toString()),
    );
    _setState(_state.copyWith(status: ChatStatus.connecting, clearError: true));
    try {
      await transport.connect();
    } on Object catch (error) {
      _setError(error.toString());
    }
  }

  Future<bool> sendPrompt(String text) async {
    if (!canSend || _disposed) return false;
    final submission = PromptSubmission(
      text: text.trim(),
      modelId: _modelId,
      permission: _permission,
      applyModel: transport is! SessionComposerTransport || _modelChanged,
      applyPermission:
          transport is! SessionComposerTransport || _permissionChanged,
      modelRevision: _modelRevision,
      permissionRevision: _permissionRevision,
      attachments: _attachments,
    );
    if (submission.text.isEmpty && submission.attachments.isEmpty) return false;
    final queued =
        _queuedDispatchInFlight ||
        _state.status == ChatStatus.streaming ||
        _state.status == ChatStatus.waitingAction;
    try {
      if (queued) {
        _queuedSubmissions.add(submission);
        _syncQueue();
      } else {
        await _dispatch(submission);
      }
      _attachments.removeWhere((f) => submission.attachments.contains(f));
      if (!_disposed) notifyListeners();
      return true;
    } on Object catch (error) {
      _setError(error.toString());
      return false;
    }
  }

  Future<void> _dispatch(PromptSubmission submission) async {
    if (transport is ComposerTransport) {
      await (transport as ComposerTransport).sendSubmission(submission);
      if (transport is SessionComposerTransport && !_disposed) {
        if (submission.applyModel &&
            _modelRevision == submission.modelRevision) {
          _modelChanged = false;
        }
        if (submission.applyPermission &&
            _permissionRevision == submission.permissionRevision) {
          _permissionChanged = false;
        }
        _restoreComposerConfiguration(
          (transport as SessionComposerTransport).composerConfiguration,
        );
      }
    } else {
      if (submission.attachments.isNotEmpty ||
          submission.modelId != null ||
          submission.permission != ComposerPermission.workspaceWrite) {
        throw const TransportException('当前引擎不支持模型、附件和权限参数');
      }
      await transport.sendPrompt(submission.text);
    }
  }

  void _syncQueue() => _setState(
    _state.copyWith(
      queuedPrompts: _queuedSubmissions.map((s) => s.displayText).toList(),
    ),
  );
  Future<void> queuePrompt(String text) async {
    if (text.trim().isEmpty || _disposed) return;
    _queuedSubmissions.add(
      PromptSubmission(
        text: text.trim(),
        modelId: _modelId,
        permission: _permission,
        applyModel: transport is! SessionComposerTransport || _modelChanged,
        applyPermission:
            transport is! SessionComposerTransport || _permissionChanged,
        modelRevision: _modelRevision,
        permissionRevision: _permissionRevision,
      ),
    );
    _syncQueue();
  }

  Future<void> sendNextQueued() async {
    if (_queuedSubmissions.isEmpty || !canDispatchQueued || _disposed) return;
    final submission = _queuedSubmissions.first;
    _queuedDispatchInFlight = true;
    notifyListeners();
    try {
      await _dispatch(submission);
      _queuedSubmissions.removeAt(0);
      _syncQueue();
    } on Object catch (error) {
      _setError(error.toString());
    } finally {
      _queuedDispatchInFlight = false;
      if (!_disposed) notifyListeners();
    }
  }

  Future<void> stop() async {
    if (_disposed || _stopInFlight) return;
    _stopInFlight = true;
    notifyListeners();
    try {
      await transport.stop();
    } on Object catch (error) {
      _setError(error.toString());
    } finally {
      _stopInFlight = false;
      if (!_disposed) notifyListeners();
    }
  }

  Future<void> approve(String requestId) async {
    if (_state.approval?.requestId != requestId) return;
    await _submitAction(requestId, () => transport.approve(requestId));
  }

  Future<void> answer(String requestId, String answer) async {
    final value = answer.trim();
    if (value.isEmpty || _state.question?.requestId != requestId) return;
    await _submitAction(requestId, () => transport.answer(requestId, value));
  }

  Future<void> answerQuestion(String requestId, QuestionAnswer answer) async {
    if (_state.question?.requestId != requestId ||
        (answer.selected.isEmpty && answer.custom.trim().isEmpty))
      return;
    await _submitAction(
      requestId,
      () => transport is StructuredQuestionTransport
          ? (transport as StructuredQuestionTransport).answerQuestion(
              requestId,
              answer,
            )
          : transport.answer(requestId, answer.plainText),
    );
  }

  Future<void> _submitAction(
    String requestId,
    Future<void> Function() action,
  ) async {
    if (_disposed || _pendingAction != null || _stopInFlight) return;
    final generation = _state.generation;
    _pendingAction = requestId;
    notifyListeners();
    try {
      await action();
    } on Object catch (error) {
      if (!_disposed &&
          generation == _state.generation &&
          _pendingAction == requestId) {
        _pendingAction = null;
        _setError(error.toString());
      }
    }
  }

  Future<void> reconnect() async {
    if (_disposed || _resyncInFlight) return;
    _setState(
      _state.copyWith(status: ChatStatus.reconnecting, clearError: true),
    );
    try {
      await transport.reconnect();
    } on Object catch (error) {
      _setError(error.toString());
    }
  }

  void _restoreComposerConfiguration(
    ComposerConfiguration configuration, {
    bool notify = true,
  }) {
    if (_disposed) return;
    if (!_modelChanged) _modelId = configuration.modelId;
    final previousError = _configurationError;
    if (!_permissionChanged) {
      _permission = configuration.permission ?? ComposerPermission.readOnly;
    }
    _configurationError =
        !_permissionChanged && configuration.permission == null
        ? '当前会话工具权限为 ' +
              (configuration.permissionPreset ?? '未知') +
              '，原生端无法回显；请显式选择权限或使用 Web 版。'
        : null;
    if (_configurationError != null) {
      _state = _state.copyWith(error: _configurationError);
    } else if (previousError != null && _state.error == previousError) {
      _state = _state.copyWith(clearError: true);
    }
    if (notify) notifyListeners();
  }

  void _onEvent(ChatEvent event) {
    final next = ChatReducer.apply(_state, event);
    if (identical(next, _state)) return;
    _setState(next);
    if (next.needsResync) unawaited(_resync());
  }

  Future<void> _resync() async {
    if (_resyncInFlight || _disposed) return;
    _resyncInFlight = true;
    try {
      await transport.reconnect();
    } on Object catch (error) {
      _setError(error.toString());
    } finally {
      _resyncInFlight = false;
    }
  }

  void _setError(String message) {
    _setState(_state.copyWith(status: ChatStatus.error, error: message));
  }

  void _setState(ChatState next) {
    if (_disposed) return;
    if (next.generation != _state.generation ||
        (_pendingAction != next.approval?.requestId &&
            _pendingAction != next.question?.requestId))
      _pendingAction = null;
    _state = next.error == null && _configurationError != null
        ? next.copyWith(error: _configurationError)
        : next;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    unawaited(_eventsSubscription?.cancel());
    unawaited(_configurationSubscription?.cancel());
    unawaited(transport.close());
    super.dispose();
  }
}

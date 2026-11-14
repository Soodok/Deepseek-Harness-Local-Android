import 'dart:async';

import 'package:flutter/foundation.dart';

import '../model/chat_model.dart';
import '../model/composer_model.dart';
import '../transport/dsh_transport.dart';
import 'chat_reducer.dart';
import 'chat_state.dart';

class ChatController extends ChangeNotifier {
  ChatController(this.transport) {
    if (models.isNotEmpty) _modelId = models.first.id;
  }

  final DshTransport transport;
  ChatState _state = ChatState.empty();
  StreamSubscription<ChatEvent>? _eventsSubscription;
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
  ComposerPermission _permission = ComposerPermission.ask;
  List<ComposerModel> get models => transport is ComposerTransport
      ? List.unmodifiable((transport as ComposerTransport).models)
      : const [];
  bool get supportsComposerOptions => transport is ComposerTransport;
  String? get selectedModelId => _modelId;
  String get modelLabel =>
      models.where((m) => m.id == _modelId).firstOrNull?.label ?? '模型未接入';
  ComposerPermission get permission => _permission;
  List<PromptAttachment> get attachments => List.unmodifiable(_attachments);
  static const maxFiles = 5;
  static const maxAttachmentBytes = 20 * 1024 * 1024;
  void selectModel(String id) {
    if (!models.any((m) => m.id == id)) throw ArgumentError.value(id, 'model');
    _modelId = id;
    if (!_disposed) notifyListeners();
  }

  void selectPermission(ComposerPermission mode) {
    if (!supportsComposerOptions)
      throw const TransportException('当前引擎尚未接入权限模式');
    _permission = mode;
    if (!_disposed) notifyListeners();
  }

  void addAttachments(List<PromptAttachment> files) {
    if (_disposed) return;
    if (!supportsComposerOptions) throw const TransportException('当前引擎尚未接入附件');
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
      _state.status == ChatStatus.ready ||
      _state.status == ChatStatus.streaming ||
      _state.status == ChatStatus.waitingAction;

  bool get canDispatchQueued =>
      !_queuedDispatchInFlight && _state.status == ChatStatus.ready;

  Future<void> start() async {
    if (_started || _disposed) return;
    _started = true;
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
    } else {
      if (submission.attachments.isNotEmpty ||
          submission.modelId != null ||
          submission.permission != ComposerPermission.ask) {
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
    _state = next;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    unawaited(_eventsSubscription?.cancel());
    unawaited(transport.close());
    super.dispose();
  }
}

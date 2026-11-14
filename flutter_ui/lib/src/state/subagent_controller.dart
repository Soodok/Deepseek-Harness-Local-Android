import 'dart:async';

import 'package:flutter/foundation.dart';

import '../model/subagent_model.dart';

class SubagentController extends ChangeNotifier {
  SubagentController({required this.service, required this.address});
  final SubagentTransport service;
  final SubagentAddress address;
  SubagentConversation? conversation;
  SubagentReceipt? lastReceipt;
  String? error;
  bool loading = false, sending = false;
  bool _disposed = false;
  int _epoch = 0, _requestNumber = 0;
  String? _pendingText, _requestId;
  StreamSubscription<SubagentConversation>? _subscription;
  bool get canSend =>
      address.canSend && conversation != null && !sending && !loading;

  Future<void> start() async {
    if (_disposed || loading) return;
    final epoch = ++_epoch;
    loading = true;
    error = null;
    notifyListeners();
    try {
      await _subscription?.cancel();
      if (_disposed || epoch != _epoch) return;
      _subscription = service
          .watchSubagent(address)
          .listen(
            _accept,
            onError: (Object value) {
              if (!_disposed && epoch == _epoch) {
                error = value.toString();
                notifyListeners();
              }
            },
          );
      final value = await service.readSubagent(address);
      if (!_disposed && epoch == _epoch) _accept(value);
    } catch (value) {
      if (!_disposed && epoch == _epoch) error = value.toString();
    } finally {
      if (!_disposed && epoch == _epoch) {
        loading = false;
        notifyListeners();
      }
    }
  }

  void _accept(SubagentConversation value) {
    if (_disposed ||
        value.address != address ||
        (conversation != null && value.revision < conversation!.revision))
      return;
    conversation = value;
    notifyListeners();
  }

  Future<bool> send(String text) async {
    final value = text.trim();
    if (_disposed || !canSend || value.isEmpty) return false;
    sending = true;
    error = null;
    lastReceipt = null;
    // Retain the request id on a retry so a lost receipt cannot duplicate delivery.
    if (_pendingText != value || _requestId == null) {
      _pendingText = value;
      _requestId =
          'mobile-${DateTime.now().microsecondsSinceEpoch}-${_requestNumber++}';
    }
    // Read subscription retries keep the same immutable child address.
    notifyListeners();
    try {
      final receipt = await service.sendSubagent(
        address,
        value,
        requestId: _requestId!,
      );
      if (_disposed) return false;
      lastReceipt = receipt;
      _pendingText = null;
      _requestId = null;
      return true;
    } catch (value) {
      if (!_disposed) error = value.toString();
      return false;
    } finally {
      if (!_disposed) {
        sending = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    ++_epoch;
    unawaited(_subscription?.cancel());
    super.dispose();
  }
}

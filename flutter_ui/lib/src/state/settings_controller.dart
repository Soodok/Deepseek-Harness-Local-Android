import 'dart:async';

import 'package:flutter/foundation.dart';

import '../model/settings_model.dart';

abstract interface class SettingsService {
  bool get isDemo;
  Future<AccountProfile?> loadProfile();
  Future<UsageHistory> loadUsage();
  Future<void> signIn();
  Future<void> signOut();
  Future<void> close();
}

/// An unavailable native engine must never impersonate a preview account.
class EngineUnavailableSettingsService implements SettingsService {
  @override
  bool get isDemo => false;
  StateError get _offline => StateError('本机引擎尚未连接，请连接后重试');
  @override
  Future<AccountProfile?> loadProfile() => Future.error(_offline);
  @override
  Future<UsageHistory> loadUsage() => Future.error(_offline);
  @override
  Future<void> signIn() => Future.error(_offline);
  @override
  Future<void> signOut() => Future.error(_offline);
  @override
  Future<void> close() async {}
}

abstract interface class AccountUsageLink {
  Future<Uri> usagePage();
}

abstract interface class CancelableSignIn {
  Future<void> cancelSignIn();
}

class SettingsController extends ChangeNotifier {
  SettingsController(this.service);
  final SettingsService service;
  AccountProfile? profile;
  UsageHistory? usage;
  String? error;
  bool loading = false, busy = false;
  bool _disposed = false;
  int _epoch = 0;
  bool get isDemo => service.isDemo;
  bool _current(int epoch) => !_disposed && _epoch == epoch;

  Future<void> refresh() async {
    if (_disposed || busy) return;
    final epoch = ++_epoch;
    loading = true;
    error = null;
    notifyListeners();
    await _load(epoch);
  }

  Future<void> _load(int epoch) async {
    final errors = <String>[];
    await Future.wait([
      (() async {
        try {
          final next = await service.loadProfile();
          if (_current(epoch)) profile = next;
        } catch (e) {
          if (_current(epoch)) profile = null;
          errors.add('账号资料暂不可用：' + e.toString());
        }
      })(),
      (() async {
        try {
          final next = await service.loadUsage();
          if (_current(epoch)) usage = next;
        } catch (e) {
          if (_current(epoch)) usage = null;
          errors.add('用量暂不可用：' + e.toString());
        }
      })(),
    ]);
    if (!_current(epoch)) return;
    loading = false;
    error = errors.isEmpty ? null : errors.join('\n');
    notifyListeners();
  }

  Future<void> signIn() async {
    if (_disposed || busy) return;
    final epoch = ++_epoch;
    busy = true;
    loading = false;
    error = null;
    notifyListeners();
    try {
      await service.signIn();
      if (_current(epoch)) await _load(epoch);
    } catch (e) {
      if (_current(epoch)) error = '登录未完成：' + e.toString();
    } finally {
      if (_current(epoch)) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> cancelSignIn() async {
    if (_disposed || !busy || service is! CancelableSignIn) return;
    final epoch = ++_epoch;
    loading = false;
    try {
      await (service as CancelableSignIn).cancelSignIn();
    } catch (_) {
      /* The next refresh reconciles the host state. */
    } finally {
      if (_current(epoch)) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> signOut() async {
    if (_disposed || busy) return;
    final epoch = ++_epoch;
    busy = true;
    loading = false;
    error = null;
    notifyListeners();
    try {
      await service.signOut();
      if (_current(epoch)) {
        profile = null;
        usage = null;
      }
    } catch (e) {
      if (_current(epoch)) error = '退出失败：' + e.toString();
    } finally {
      if (_current(epoch)) {
        busy = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    ++_epoch;
    unawaited(service.close());
    super.dispose();
  }
}

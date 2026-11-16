import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/model/settings_model.dart';
import 'package:dsh_native_chat/src/state/settings_controller.dart';

class FakeService implements SettingsService {
  @override
  bool get isDemo => false;
  AccountProfile? profile = const AccountProfile(id: 'a', displayName: '真实资料');
  Completer<AccountProfile?>? readGate;
  Completer<void>? logoutGate;
  int logouts = 0;
  bool failUsage = false, failLogout = false, failProfile = false;
  @override
  Future<AccountProfile?> loadProfile() {
    if (failProfile) return Future.error(StateError('profile unavailable'));
    return readGate?.future ?? Future.value(profile);
  }

  @override
  Future<UsageHistory> loadUsage() async {
    if (failUsage) throw StateError('usage unavailable');
    return UsageHistory(
      days: [
        TokenUsageDay(
          date: DateTime(2026, 11, 16),
          inputTokens: 10,
          outputTokens: 4,
        ),
      ],
      from: DateTime(2026, 8, 25),
      through: DateTime(2026, 11, 16),
    );
  }

  @override
  Future<void> signIn() async {}
  @override
  Future<void> signOut() async {
    logouts++;
    await logoutGate?.future;
    if (failLogout) throw StateError('logout failed');
    profile = null;
  }

  @override
  Future<void> close() async {}
}

void main() {
  test(
    'failed profile refresh clears a previously visible identity only',
    () async {
      final service = FakeService();
      final c = SettingsController(service);
      await c.refresh();
      expect(c.profile?.id, 'a');
      expect(c.usage, isNotNull);
      service.failProfile = true;
      await c.refresh();
      expect(c.profile, isNull);
      expect(c.usage, isNotNull);
      expect(c.error, contains('profile unavailable'));
      c.dispose();
    },
  );
  test(
    'failed usage refresh hides old totals while successful profile survives',
    () async {
      final service = FakeService();
      final c = SettingsController(service);
      await c.refresh();
      expect(c.usage, isNotNull);
      service.failUsage = true;
      await c.refresh();
      expect(c.usage, isNull);
      expect(c.profile?.id, 'a');
      expect(c.error, contains('usage unavailable'));
      c.dispose();
    },
  );
  test('profile remains visible when only usage fails', () async {
    final s = FakeService()..failUsage = true;
    final c = SettingsController(s);
    await c.refresh();
    expect(c.profile?.id, 'a');
    expect(c.error, contains('usage unavailable'));
    expect(c.loading, isFalse);
    c.dispose();
  });
  test('successful logout fences a slow read and duplicate logouts', () async {
    final s = FakeService();
    final c = SettingsController(s);
    await c.refresh();
    s.readGate = Completer();
    final read = c.refresh();
    s.logoutGate = Completer();
    final logout = c.signOut();
    await c.signOut();
    expect(s.logouts, 1);
    s.logoutGate!.complete();
    await logout;
    s.readGate!.complete(const AccountProfile(id: 'old', displayName: '迟到'));
    await read;
    expect(c.profile, isNull);
    expect(c.usage, isNull);
    expect(c.busy, isFalse);
    c.dispose();
  });
  test('failed logout preserves the known profile and allows retry', () async {
    final s = FakeService()..failLogout = true;
    final c = SettingsController(s);
    await c.refresh();
    await c.signOut();
    expect(c.profile?.id, 'a');
    expect(c.error, contains('logout failed'));
    expect(c.busy, isFalse);
    s.failLogout = false;
    await c.signOut();
    expect(c.profile, isNull);
    c.dispose();
  });
  test('dispose ignores late refresh safely', () async {
    final s = FakeService()..readGate = Completer();
    final c = SettingsController(s);
    final read = c.refresh();
    c.dispose();
    s.readGate!.complete(null);
    await read;
  });
}

import '../model/settings_model.dart';
import '../state/settings_controller.dart';

/// Explicit preview data, independent from all engine and Platform credentials.
class FixtureSettingsService implements SettingsService {
  AccountProfile? _profile;
  @override
  bool get isDemo => true;
  @override
  Future<AccountProfile?> loadProfile() async => _profile;
  @override
  Future<void> signIn() async {
    _profile = const AccountProfile(
      id: 'preview',
      displayName: 'DeepSeek 用户',
      email: '本地资料演示 · 未登录真实账号',
    );
  }

  @override
  Future<void> signOut() async {
    _profile = null;
  }

  @override
  Future<UsageHistory> loadUsage() async {
    final now = DateTime.now();
    final end = DateTime(now.year, now.month, now.day);
    final start = end.subtract(const Duration(days: 83));
    return UsageHistory(
      from: start,
      through: end,
      sourceLabel: '本地演示数据 · 非账号账单',
      days: [
        for (int i = 0; i < 84; i++)
          if (i % 7 != 2 && i % 11 != 0)
            TokenUsageDay(
              date: DateTime(start.year, start.month, start.day + i),
              inputTokens: (i % 8) * 1400 + i * 37,
              outputTokens: (i % 6) * 300 + i * 11,
            ),
      ],
    );
  }

  @override
  Future<void> close() async {}
}

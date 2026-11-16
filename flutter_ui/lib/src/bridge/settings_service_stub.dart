import '../state/settings_controller.dart';
import '../transport/fixture_settings_service.dart';
import 'host_bridge.dart';

SettingsService createSettingsService(HostInfo? host) =>
    FixtureSettingsService();

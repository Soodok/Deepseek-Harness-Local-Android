import '../state/settings_controller.dart';
import '../model/tool_content.dart';
import '../transport/dsh_rpc_connection.dart';
import '../transport/fixture_settings_service.dart';
import '../transport/native_settings_service.dart';
import 'host_bridge.dart';

SettingsService createSettingsService(HostInfo? host) {
  if (host == null) return FixtureSettingsService();
  if (!host.available || host.webUrl.isEmpty)
    return EngineUnavailableSettingsService();
  final launch = Uri.parse(host.webUrl);
  return NativeSettingsService(
    rpc: DshRpcConnection(webUrl: host.webUrl),
    callbackOrigin: Uri.parse(launch.origin),
    openBrowser: (uri) => HostBridge.openResource(
      OutputResource(
        id: 'deepseek-authorize',
        title: 'DeepSeek授权',
        uri: uri.toString(),
        kind: ResourceKind.link,
      ),
    ),
  );
}

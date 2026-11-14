import 'package:material_ui/material_ui.dart';

import 'host_bridge.dart';

Future<void> showEngineSessions(BuildContext context, HostInfo host) async {
  ScaffoldMessenger.of(context)
      .showSnackBar(const SnackBar(content: Text('请从 Android 应用连接本机引擎。')));
}

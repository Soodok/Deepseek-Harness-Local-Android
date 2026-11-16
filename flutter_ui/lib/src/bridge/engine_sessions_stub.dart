import 'package:material_ui/material_ui.dart';

import 'host_bridge.dart';

Widget buildEngineHome(HostInfo host) => Scaffold(
  appBar: AppBar(title: const Text('本机引擎')),
  body: const SafeArea(
    child: Center(
      child: Padding(
        padding: EdgeInsets.all(24),
        child: Text('真实本机聊天需要 Android 应用。请在 Android 中启动引擎并打开原生聊天。'),
      ),
    ),
  ),
);

Future<void> showEngineSessions(BuildContext context, HostInfo host) async {
  ScaffoldMessenger.of(context)
      .showSnackBar(const SnackBar(content: Text('请从 Android 应用连接本机引擎。')));
}

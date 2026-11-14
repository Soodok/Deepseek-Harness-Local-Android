import 'package:material_ui/material_ui.dart';

import '../bridge/host_bridge.dart';
import '../theme/dsh_theme.dart';
import 'glass_panel.dart';

Future<void> showRuntimePanel(
  BuildContext context, {
  required HostInfo? hostInfo,
  required bool isFixture,
  VoidCallback? onOpenWebFallback,
}) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  backgroundColor: Colors.transparent,
  builder: (context) => _RuntimePanel(
    host: hostInfo ?? HostInfo.preview,
    isFixture: isFixture,
    onOpenWebFallback: onOpenWebFallback,
  ),
);

class _RuntimePanel extends StatefulWidget {
  const _RuntimePanel({
    required this.host,
    required this.isFixture,
    this.onOpenWebFallback,
  });
  final HostInfo host;
  final bool isFixture;
  final VoidCallback? onOpenWebFallback;
  @override
  State<_RuntimePanel> createState() => _RuntimePanelState();
}

class _RuntimePanelState extends State<_RuntimePanel> {
  bool _busy = false;
  String? _feedback;
  Future<void> _open(
    Future<bool> Function() action, {
    String? successMessage,
  }) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _feedback = null;
    });
    var opened = false;
    try {
      opened = await action();
    } on Object {
      opened = false;
    }
    if (!mounted) return;
    if (opened) {
      Navigator.of(context).pop();
      return;
    }
    setState(() {
      _busy = false;
      _feedback = opened
          ? (successMessage ?? '已打开管理页面，请在那里完成配置。')
          : '未打开管理页面，请在 Android 端使用此入口。';
    });
  }

  @override
  Widget build(BuildContext context) {
    final host = widget.host;
    final known = host.runtimeResolved && host.state != 'preview';
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 14),
      child: GlassPanel(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * .85,
          ),
          child: SingleChildScrollView(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(14, 8, 14, 18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Center(
                    child: Container(
                      width: 34,
                      height: 4,
                      margin: const EdgeInsets.only(top: 4, bottom: 8),
                      decoration: BoxDecoration(
                        color: DshPalette.of(context).inkFaint,
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                  ),
                  Row(
                    children: [
                      const Expanded(
                        child: Padding(
                          padding: EdgeInsets.only(left: 10),
                          child: Text(
                            '运行环境',
                            style: TextStyle(
                              fontSize: 21,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ),
                      IconButton(
                        tooltip: '关闭',
                        onPressed: () => Navigator.of(context).pop(),
                        icon: const Icon(Icons.close_rounded),
                      ),
                    ],
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(10, 0, 10, 14),
                    child: Text(
                      '当前环境：' +
                          host.runtimeLabel +
                          (widget.isFixture ? ' · 对话演示' : ''),
                      style: TextStyle(
                        color: DshPalette.of(context).inkSoft,
                        fontSize: 13,
                      ),
                    ),
                  ),
                  if (known && host.runtimeMode == 'native_fallback')
                    Padding(
                      padding: const EdgeInsets.fromLTRB(10, 0, 10, 12),
                      child: Text(
                        host.runtimeDetail,
                        style: TextStyle(
                          color: DshPalette.of(context).inkSoft,
                          fontSize: 13,
                        ),
                      ),
                    ),
                  const _Section('本机'),
                  _entry(
                    'runtime-native',
                    Icons.phone_android_rounded,
                    'Bionic Node',
                    'Android 原生 Node.js',
                    known &&
                        (host.runtimeMode == 'native' ||
                            host.runtimeMode == 'native_fallback'),
                    () => _open(() => HostBridge.openRuntimeSettings('native')),
                  ),
                  _entry(
                    'runtime-linux',
                    Icons.terminal_rounded,
                    'DSH Alpine',
                    'Linux 容器 · 管理运行环境',
                    known && host.runtimeMode == 'linux',
                    () => _open(() => HostBridge.openRuntimeSettings('linux')),
                  ),
                  const _Section('远程 SSH'),
                  _entry(
                    'runtime-ssh-windows',
                    Icons.desktop_windows_rounded,
                    'Windows 主机',
                    'SSH POSIX 兼容性待验证 · 通过 Web 管理',
                    known && host.runtimeMode == 'ssh_windows',
                    () => _open(
                      () => HostBridge.openSshManager('windows'),
                      successMessage: '已打开 Web 版；Windows SSH 兼容性仍需验证。',
                    ),
                  ),
                  _entry(
                    'runtime-ssh-linux',
                    Icons.dns_outlined,
                    'Linux 主机',
                    '通过 SSH 添加或管理主机',
                    known && host.runtimeMode == 'ssh_linux',
                    () => _open(
                      () => HostBridge.openSshManager('linux'),
                      successMessage: '已打开 Web 版，请在 SSH 工作区管理 Linux 主机。',
                    ),
                  ),
                  if (_feedback != null)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(10, 10, 10, 4),
                      child: Text(
                        _feedback!,
                        style: TextStyle(
                          color: DshPalette.of(context).inkSoft,
                          fontSize: 13,
                        ),
                      ),
                    ),
                  const SizedBox(height: 12),
                  TextButton.icon(
                    key: const ValueKey('runtime-web-fallback'),
                    onPressed: _busy
                        ? null
                        : () {
                            if (widget.onOpenWebFallback != null) {
                              widget.onOpenWebFallback!();
                            } else {
                              _open(HostBridge.openWebFallback);
                            }
                          },
                    icon: const Icon(Icons.open_in_new_rounded, size: 18),
                    label: const Text('打开 Web 版'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _entry(
    String key,
    IconData icon,
    String title,
    String detail,
    bool current,
    VoidCallback onTap,
  ) => Padding(
    padding: const EdgeInsets.only(bottom: 4),
    child: Material(
      color: current
          ? DshPalette.of(context).ink.withValues(alpha: .07)
          : Colors.transparent,
      borderRadius: BorderRadius.circular(16),
      child: ListTile(
        key: ValueKey(key),
        enabled: !_busy,
        contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        onTap: onTap,
        leading: Icon(icon, size: 22, color: DshPalette.of(context).inkSoft),
        title: Text(
          title,
          style: TextStyle(fontSize: 16, color: DshPalette.of(context).ink),
        ),
        subtitle: Text(
          detail,
          style: TextStyle(fontSize: 12, color: DshPalette.of(context).inkSoft),
        ),
        trailing: Icon(
          current ? Icons.check_rounded : Icons.chevron_right_rounded,
          size: 20,
          color: DshPalette.of(context).inkSoft,
        ),
      ),
    ),
  );
}

class _Section extends StatelessWidget {
  const _Section(this.label);
  final String label;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(10, 12, 10, 6),
    child: Text(
      label,
      style: TextStyle(color: DshPalette.of(context).inkFaint, fontSize: 12),
    ),
  );
}

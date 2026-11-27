import 'package:material_ui/material_ui.dart';

import '../bridge/host_bridge.dart';
import '../theme/dsh_theme.dart';
import 'glass_panel.dart';
import 'manual_ssh_page.dart';

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

  /// 面板里最后一次**权威回读**到的模式。切换成功后按回执更新它，
  /// 而不是按点击的目标 —— 回执说没切过去时高亮必须留在原处。
  late String _mode = widget.host.runtimeMode;

  /// 正在切往哪个模式；null = 空闲。用来显示 pending 态并锁住其它入口。
  String? _switching;

  /// 切换成功但引擎进程还在旧模式下跑（重启后才生效）。
  bool _restartPending = false;

  /// 失败提示是否按错误色渲染。
  bool _failed = false;

  /// 面板内是否已经拿到过**宿主回读**的模式。
  ///
  /// 面板是打开那一刻的快照：冷启早期 `runtimeResolved` 还是 false，而这个
  /// 快照不会随父页刷新（弹层自己持有 host）。若只认 widget.host 的字段，
  /// 切换成功后面板会既说「已切换到 DSH Alpine」又一条勾都不打 —— 两句互相
  /// 矛盾。一次成功的回读本身就是权威事实，足以支撑高亮。
  bool _readback = false;

  @override
  void initState() {
    super.initState();
    _restartPending = widget.host.runtimeRestartPending;
  }

  Future<void> _open(Future<bool> Function() action) async {
    if (_busy || _switching != null) return;
    setState(() {
      _busy = true;
      _feedback = null;
      _failed = false;
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
      _feedback = '未打开管理页面，请在 Android 端使用此入口。';
      _failed = true;
    });
  }

  /// 面板内切换运行模式。
  ///
  /// 成败只看宿主回执的 [RuntimeModeSwitchResult.applied]：请求值不算数。
  /// 宿主没有这条通道（Web 预览 / 旧包）时退回原来的「打开运行身份设置」页面，
  /// 不假装切换成功。
  Future<void> _switch(String mode) async {
    if (_busy || _switching != null) return;
    setState(() {
      _switching = mode;
      _feedback = null;
      _failed = false;
    });
    final result = await HostBridge.selectRuntimeMode(mode);
    if (!mounted) return;
    if (result.reason == 'unsupported') {
      // 没有宿主通道（Web 预览 / 旧包）：退回外部页面，行为与改动前一致。
      // 这里既不报成功也不报失败 —— 我们确实什么都没切。
      setState(() => _switching = null);
      await _open(() => HostBridge.openRuntimeSettings(mode));
      return;
    }
    setState(() {
      _switching = null;
      // 「unknown」是宿主还没判定，不是「当前模式叫 unknown」：照抄会把两个勾全抹掉，
      // 表头也会退回「检测中」。这种情况保持面板原有模式，只显示失败原因。
      final applied = result.applied.isNotEmpty && result.applied != 'unknown'
          ? result.applied
          : _mode;
      final changed = applied != _mode;
      _mode = applied;
      if (result.ok ||
          (result.applied.isNotEmpty && result.applied != 'unknown')) {
        _readback = true;
      }
      if (result.ok) {
        _failed = false;
        // 「重启后才生效」只在引擎**正在跑**时成立：没在跑的时候下次启动
        // 自然按新偏好拉起，挂一条重启提醒只会误导。
        final pending = result.restartRequired && widget.host.available;
        if (changed) _restartPending = pending;
        final label = _labelFor(applied);
        _feedback = _restartPending
            ? '已切换到 $label，重启引擎后生效。'
            : (changed ? '已切换到 $label。' : '当前已是 $label。');
      } else {
        // 回执里的 applied 是宿主回滚后的真实模式，高亮已经按它对齐；
        // 待生效标记保持原样（回滚回的就是它切之前的状态）。
        _failed = true;
        _feedback = _reasonText(result.reason);
      }
    });
  }

  static String _labelFor(String mode) => switch (mode) {
    'linux' => 'DSH Alpine',
    'native' || 'native_fallback' => 'Bionic Node',
    _ => mode,
  };

  /// 宿主给的是技术原因（英文、可能是容器层的原话）；能给中文就给中文，
  /// 其余原样透出，不做「切换失败」这种什么都没说的兜底。
  static String _reasonText(String reason) {
    final raw = reason.trim();
    if (raw.isEmpty) return '切换失败：宿主没有给出原因';
    return switch (raw) {
      'busy' => '正在切换中，请稍候再试',
      'needs-deploy' => '容器环境尚未部署，请先在「DSH Alpine」管理页部署',
      _ => '切换失败：' + raw,
    };
  }

  void _openSsh(String platform) {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (context) => ManualSshPage(platform: platform),
      ),
    );
  }

  /// 面板里显示的当前模式名。回读值还没拿到（unknown / 空）时退回宿主给的名字，
  /// 不要自己猜一个。
  ///
  /// Linux 分支按 distro 命名，但刚切过去时宿主还没回填 distro（要等下次启动），
  /// 此时默认按 Alpine —— 与入口标题同名，不能出现「入口叫 Alpine、表头叫 Linux」。
  String get _modeLabel => switch (_mode) {
    'linux' => _linuxLabel,
    'native' || 'native_fallback' => 'Bionic Node',
    'ssh_windows' => 'SSH Windows',
    'ssh_linux' => 'SSH Linux',
    _ => widget.host.runtimeLabel,
  };

  String get _linuxLabel {
    final distro = widget.host.distro.toLowerCase();
    if (distro.isEmpty || distro.contains('alpine')) return 'DSH Alpine';
    return 'DSH Linux';
  }

  /// 当前模式是否已知：宿主打开面板时已判定过，或本面板自己拿到了回读。
  bool get _known =>
      _readback ||
      (widget.host.runtimeResolved && widget.host.state != 'preview');

  /// 该入口是不是当前正在运行的模式（native_fallback 也算原生）。
  bool _current(String mode) {
    if (!_known) return false;
    if (mode == 'native') {
      return _mode == 'native' || _mode == 'native_fallback';
    }
    return _mode == mode;
  }

  @override
  Widget build(BuildContext context) {
    final host = widget.host;
    final known = _known;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 14),
      child: GlassPanel(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * .85,
          ),
          child: SingleChildScrollView(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(14, 6, 14, 8),
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
                    padding: const EdgeInsets.fromLTRB(10, 0, 10, 6),
                    child: Text(
                      '当前环境：' +
                          _modeLabel +
                          (widget.isFixture ? ' · 对话演示' : ''),
                      style: TextStyle(
                        color: DshPalette.of(context).inkSoft,
                        fontSize: 13,
                      ),
                    ),
                  ),
                  if (known && _mode == 'native_fallback')
                    Padding(
                      padding: const EdgeInsets.fromLTRB(10, 0, 10, 12),
                      child: Text(
                        widget.host.runtimeReason.isEmpty
                            ? '兼容模式：Linux 容器不可用，当前跑在原生 Node'
                            : '兼容模式 · ' + widget.host.runtimeReason,
                        style: TextStyle(
                          color: DshPalette.of(context).inkSoft,
                          fontSize: 13,
                        ),
                      ),
                    ),
                  if (_restartPending)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(10, 0, 10, 8),
                      child: Text(
                        '模式已切换，重启引擎后生效。',
                        key: const ValueKey('runtime-restart-pending'),
                        style: TextStyle(
                          color: DshPalette.of(context).warn,
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
                    _current('native'),
                    () => _switch('native'),
                    pending: _switching == 'native',
                  ),
                  _entry(
                    'runtime-linux',
                    Icons.terminal_rounded,
                    'DSH Alpine',
                    'Linux 容器 · 运行与管理',
                    _current('linux'),
                    () => _switch('linux'),
                    pending: _switching == 'linux',
                  ),
                  // 切换是「点一下就切」，部署 / 修复 rootfs 仍然只在运行身份设置里。
                  // 这条入口必须留着：切换失败最常见的原因就是容器还没部署，
                  // 失败提示里指的「管理页」得真的点得到。
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                      key: const ValueKey('runtime-manage'),
                      style: TextButton.styleFrom(
                        minimumSize: const Size(0, 44),
                        padding: const EdgeInsets.symmetric(horizontal: 10),
                      ),
                      onPressed: _busy || _switching != null
                          ? null
                          : () => _open(
                              () => HostBridge.openRuntimeSettings(
                                switch (_mode) {
                                  'linux' => 'linux',
                                  'native' || 'native_fallback' => 'native',
                                  _ => null,
                                },
                              ),
                            ),
                      icon: const Icon(Icons.tune_rounded, size: 18),
                      label: const Text('管理运行环境'),
                    ),
                  ),
                  const _Section('远程 SSH'),
                  _entry(
                    'runtime-ssh-windows',
                    Icons.desktop_windows_rounded,
                    'Windows 主机',
                    '验证主机公钥 · 密码连接',
                    known && host.runtimeMode == 'ssh_windows',
                    () => _openSsh('Windows'),
                  ),
                  _entry(
                    'runtime-ssh-linux',
                    Icons.dns_outlined,
                    'Linux 主机',
                    '验证主机公钥 · 密码连接',
                    known && host.runtimeMode == 'ssh_linux',
                    () => _openSsh('Linux'),
                  ),
                  if (_feedback != null)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(10, 10, 10, 4),
                      child: Text(
                        _feedback!,
                        key: const ValueKey('runtime-feedback'),
                        style: TextStyle(
                          color: _failed
                              ? DshPalette.of(context).bad
                              : DshPalette.of(context).inkSoft,
                          fontSize: 13,
                        ),
                      ),
                    ),
                  TextButton.icon(
                    key: const ValueKey('runtime-web-fallback'),
                    style: TextButton.styleFrom(minimumSize: const Size(0, 48)),
                    onPressed: _busy || _switching != null
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

  /// 一条模式入口。
  ///
  /// [pending] 为真时替换 trailing 的图标为进度圈，并把标题颜色压暗 —— 这不是
  /// 装饰：「切换中」和「已选中」在同一行里长得像的话，用户会以为点第二次能撤销。
  Widget _entry(
    String key,
    IconData icon,
    String title,
    String detail,
    bool current,
    VoidCallback onTap, {
    bool pending = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 4),
    child: Material(
      color: current
          ? DshPalette.of(context).ink.withValues(alpha: .07)
          : Colors.transparent,
      borderRadius: BorderRadius.circular(16),
      child: ListTile(
        key: ValueKey(key),
        enabled: !_busy && _switching == null,
        contentPadding: const EdgeInsets.symmetric(horizontal: 10),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        onTap: onTap,
        leading: Icon(icon, size: 22, color: DshPalette.of(context).inkSoft),
        title: Text(
          title,
          style: TextStyle(fontSize: 16, color: DshPalette.of(context).ink),
        ),
        subtitle: Text(
          pending ? '切换中…' : detail,
          key: ValueKey(key + '-detail'),
          style: TextStyle(
            fontSize: 12,
            color: pending
                ? DshPalette.of(context).inkFaint
                : DshPalette.of(context).inkSoft,
          ),
        ),
        trailing: pending
            ? SizedBox(
                key: ValueKey(key + '-pending'),
                width: 18,
                height: 18,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: DshPalette.of(context).inkSoft,
                ),
              )
            : Icon(
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
    padding: const EdgeInsets.fromLTRB(10, 8, 10, 4),
    child: Text(
      label,
      style: TextStyle(color: DshPalette.of(context).inkFaint, fontSize: 12),
    ),
  );
}

import 'package:material_ui/material_ui.dart';

import '../transport/manual_ssh_service.dart';

class ManualSshPage extends StatefulWidget {
  const ManualSshPage({super.key, required this.platform});

  final String platform;

  @override
  State<ManualSshPage> createState() => _ManualSshPageState();
}

class _ManualSshPageState extends State<ManualSshPage> {
  final _form = GlobalKey<FormState>();
  final _host = TextEditingController();
  final _port = TextEditingController(text: '22');
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _fingerprint = TextEditingController();
  final _command = TextEditingController();
  late final _service = ManualSshService(onDisconnected: _peerClosed);
  bool _connecting = false;
  bool _connected = false;
  bool _running = false;
  int _operation = 0;
  String? _feedback;
  ManualSshResult? _result;

  @override
  void dispose() {
    _service.disconnect();
    for (final field in [
      _host,
      _port,
      _username,
      _password,
      _fingerprint,
      _command,
    ]) {
      field.dispose();
    }
    super.dispose();
  }

  Future<void> _connect() async {
    if (_connecting || _connected || !_form.currentState!.validate()) return;
    final operation = ++_operation;
    FocusScope.of(context).unfocus();
    final password = _password.text;
    _password.clear();
    setState(() {
      _feedback = null;
      _connecting = true;
    });
    try {
      await _service.connect(
        host: _host.text.trim(),
        port: parseManualSshPort(_port.text.trim())!,
        username: _username.text.trim(),
        password: password,
        fingerprint: _fingerprint.text.trim(),
      );
      if (!mounted || operation != _operation) return;
      setState(() {
        _connecting = false;
        _connected = true;
      });
    } on ManualSshException catch (error) {
      if (!mounted || operation != _operation) return;
      setState(() {
        _connecting = false;
        _feedback = _message(error.failure);
      });
    }
  }

  Future<void> _execute() async {
    final command = _command.text;
    if (_running || !_connected || command.trim().isEmpty) return;
    final operation = ++_operation;
    FocusScope.of(context).unfocus();
    setState(() {
      _running = true;
      _result = null;
      _feedback = null;
    });
    try {
      final result = await _service.execute(command);
      if (!mounted || operation != _operation) return;
      setState(() {
        _result = result;
        _running = false;
      });
    } on ManualSshException catch (error) {
      if (!mounted || operation != _operation) return;
      setState(() {
        _connected = _service.connected;
        _running = false;
        _feedback = _message(error.failure);
      });
    }
  }

  void _disconnect([String? notice]) {
    _operation++;
    _service.disconnect();
    _password.clear();
    _command.clear();
    setState(() {
      _connecting = _connected = _running = false;
      _result = null;
      _feedback = notice;
    });
  }

  void _peerClosed() {
    if (mounted) _disconnect('SSH 连接已关闭。');
  }

  String _message(ManualSshFailure failure) => switch (failure) {
    ManualSshFailure.invalidInput => '输入格式不正确，请检查后重试。',
    ManualSshFailure.busy => '已有 SSH 操作正在进行。',
    ManualSshFailure.notConnected ||
    ManualSshFailure.disconnected => 'SSH 已断开。',
    ManualSshFailure.hostKeyMismatch => '主机公钥指纹不匹配，已拒绝连接。请向管理员核对。',
    ManualSshFailure.authentication => '密码认证失败。请核对账号、密码和服务器认证设置。',
    ManualSshFailure.timeout => 'SSH 操作已超时，连接已关闭。',
    ManualSshFailure.connection => 'SSH 连接失败。请检查主机地址、端口和网络。',
    ManualSshFailure.command => '远程命令未完成。',
  };

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text('${widget.platform} SSH')),
    body: SafeArea(
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            children: [
              if (_feedback != null) ...[
                Semantics(liveRegion: true, child: Text(_feedback!)),
                const SizedBox(height: 12),
              ],
              if (_connecting) ...[
                const LinearProgressIndicator(semanticsLabel: '正在验证 SSH 连接'),
                const SizedBox(height: 12),
                OutlinedButton(
                  onPressed: _disconnect,
                  child: const Text('取消连接'),
                ),
              ] else if (_connected)
                ..._commandControls(context)
              else
                _connectionForm(),
            ],
          ),
        ),
      ),
    ),
  );

  Widget _connectionForm() => Form(
    key: _form,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _field(
          'ssh-host',
          _host,
          '主机地址',
          maxLength: 253,
          keyboardType: TextInputType.url,
        ),
        _field(
          'ssh-port',
          _port,
          '端口',
          maxLength: 5,
          keyboardType: TextInputType.number,
          validate: (value) =>
              parseManualSshPort(value.trim()) == null ? '端口必须为 1–65535' : null,
        ),
        _field('ssh-username', _username, '用户名', maxLength: 128),
        _field(
          'ssh-fingerprint',
          _fingerprint,
          '主机公钥指纹',
          maxLength: 50,
          hint: 'SHA256:…（从管理员处核实）',
          validate: (value) =>
              isManualSshFingerprint(value.trim()) ? null : '请输入完整的 SHA256 指纹',
        ),
        _field(
          'ssh-password',
          _password,
          'SSH 密码',
          maxLength: 1024,
          secret: true,
        ),
        FilledButton.icon(
          key: const ValueKey('ssh-connect'),
          onPressed: _connect,
          icon: const Icon(Icons.link_rounded),
          label: const Text('连接'),
        ),
      ],
    ),
  );

  Widget _field(
    String key,
    TextEditingController controller,
    String label, {
    required int maxLength,
    bool secret = false,
    String? hint,
    TextInputType? keyboardType,
    String? Function(String)? validate,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: TextFormField(
      key: ValueKey(key),
      controller: controller,
      maxLength: maxLength,
      keyboardType: secret ? TextInputType.visiblePassword : keyboardType,
      obscureText: secret,
      autocorrect: false,
      enableSuggestions: false,
      enableIMEPersonalizedLearning: false,
      textInputAction: secret ? TextInputAction.done : TextInputAction.next,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        counterText: '',
      ),
      validator: (value) {
        final text = value ?? '';
        if ((secret ? text : text.trim()).isEmpty) return '请输入$label';
        return validate?.call(text);
      },
    ),
  );

  List<Widget> _commandControls(BuildContext context) => [
    Semantics(liveRegion: true, child: const Text('已连接，主机公钥指纹已核对')),
    Text(_host.text, maxLines: 2, overflow: TextOverflow.ellipsis),
    const SizedBox(height: 12),
    TextField(
      key: const ValueKey('ssh-command'),
      controller: _command,
      enabled: !_running,
      autocorrect: false,
      enableSuggestions: false,
      enableIMEPersonalizedLearning: false,
      maxLength: ManualSshService.commandLimit,
      decoration: const InputDecoration(labelText: '远程命令', counterText: ''),
    ),
    const SizedBox(height: 8),
    Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        ValueListenableBuilder<TextEditingValue>(
          valueListenable: _command,
          builder: (context, value, _) => FilledButton.icon(
            key: const ValueKey('ssh-execute'),
            onPressed: _running || value.text.trim().isEmpty ? null : _execute,
            icon: const Icon(Icons.play_arrow_rounded),
            label: Text(_running ? '执行中…' : '执行'),
          ),
        ),
        OutlinedButton.icon(
          key: const ValueKey('ssh-disconnect'),
          onPressed: () => _disconnect('已断开 SSH 连接。'),
          icon: const Icon(Icons.link_off_rounded),
          label: const Text('断开'),
        ),
      ],
    ),
    if (_result case final result?) ...[
      const SizedBox(height: 12),
      Semantics(
        liveRegion: true,
        child: Text(
          result.exitCode == null ? '未收到退出码' : '退出码：${result.exitCode}',
        ),
      ),
      if (result.truncated) const Text('输出已截断（每路最多 32 KiB）。'),
      if (result.stdout.isNotEmpty) _output(context, '标准输出', result.stdout),
      if (result.stderr.isNotEmpty) _output(context, '错误输出', result.stderr),
    ],
  ];

  Widget _output(BuildContext context, String label, String output) => Padding(
    padding: const EdgeInsets.only(top: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 4),
        SelectableText(output, style: const TextStyle(fontFamily: 'monospace')),
      ],
    ),
  );
}

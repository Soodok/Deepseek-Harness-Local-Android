import 'dart:async';
import 'dart:math';

import 'package:material_ui/material_ui.dart';

import '../../bridge/host_bridge.dart';
import '../../model/composer_model.dart';
import '../../model/settings_model.dart';
import '../../state/chat_controller.dart';
import '../../state/settings_controller.dart';
import '../../transport/dsh_rpc_connection.dart';
import '../../theme/display_scale.dart';
import '../../theme/dsh_theme.dart';
import '../composer_sheets.dart';
import '../glass_panel.dart';
import '../manual_ssh_page.dart';
import 'settings_page.dart';

class PersonalizationPage extends StatelessWidget {
  const PersonalizationPage({super.key});
  @override
  Widget build(BuildContext context) {
    final appearance = DshAppearance.maybeOf(context);
    final colors = DshPalette.of(context);
    final size = DshDisplayScope.sizeOf(context);
    final onSizeChanged = DshDisplayScope.onChangedOf(context);
    return _SettingsDetail(
      title: '个性化主题',
      children: [
        const _SectionTitle('显示大小'),
        _SettingsGroup(
          children: [
            for (final option in DshDisplaySize.values)
              ListTile(
                key: ValueKey('settings-display-' + option.wireValue),
                // The canvas paints 52 logical units as 46.8dp at the compact
                // size, so the row asks for a real 48dp target instead.
                minTileHeight: DshDisplayScope.atLeastTarget(context, 52),
                title: Text(option.label),
                subtitle: Text(_displayHint(option)),
                trailing: Icon(
                  size == option
                      ? Icons.check_circle_rounded
                      : Icons.radio_button_unchecked_rounded,
                  color: size == option ? colors.ice : colors.inkFaint,
                ),
                onTap: onSizeChanged == null
                    ? null
                    : () => onSizeChanged(option),
              ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(6, 10, 6, 0),
          child: Text(
            // Plain product language: what changes for the reader, not how the
            // canvas is implemented. A disabled group cannot promise a switch
            // that this environment does not have.
            onSizeChanged == null
                ? '当前环境不支持调整显示大小。'
                : '调整文字和控件大小，不影响系统设置；仍遵循系统字体大小。',
            style: TextStyle(color: colors.inkSoft, fontSize: 12, height: 1.5),
          ),
        ),
        const SizedBox(height: 24),
        const _SectionTitle('外观模式'),
        _SettingsGroup(
          children: [
            for (final mode in ThemeMode.values)
              ListTile(
                key: ValueKey('settings-theme-' + mode.name),
                minTileHeight: DshDisplayScope.atLeastTarget(context, 52),
                title: Text(switch (mode) {
                  ThemeMode.system => '跟随系统',
                  ThemeMode.light => '浅色',
                  ThemeMode.dark => '深色',
                }),
                trailing: Icon(
                  appearance?.mode == mode
                      ? Icons.check_circle_rounded
                      : Icons.radio_button_unchecked_rounded,
                  color: appearance?.mode == mode
                      ? colors.ice
                      : colors.inkFaint,
                ),
                onTap: appearance == null
                    ? null
                    : () => appearance.onChanged(mode),
              ),
          ],
        ),
        const SizedBox(height: 24),
        const _SectionTitle('自然配色'),
        LayoutBuilder(
          builder: (context, constraints) => Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              for (final mood in DshMood.values)
                SizedBox(
                  width: (constraints.maxWidth - 12) / 2,
                  child: Material(
                    color: colors.surfaceRaised,
                    borderRadius: BorderRadius.circular(16),
                    child: InkWell(
                      key: ValueKey('settings-mood-' + mood.name),
                      borderRadius: BorderRadius.circular(16),
                      onTap: appearance == null
                          ? null
                          : () => appearance.onMoodChanged?.call(mood),
                      child: Padding(
                        padding: const EdgeInsets.all(14),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                for (final color in [
                                  DshPalette.colorsFor(
                                    mood,
                                    Brightness.light,
                                  ).paper,
                                  DshPalette.colorsFor(
                                    mood,
                                    Brightness.dark,
                                  ).surface,
                                  DshPalette.colorsFor(
                                    mood,
                                    Brightness.light,
                                  ).ice,
                                ])
                                  Container(
                                    width: 24,
                                    height: 24,
                                    margin: const EdgeInsets.only(right: 6),
                                    decoration: BoxDecoration(
                                      color: color,
                                      shape: BoxShape.circle,
                                      border: Border.all(color: colors.line),
                                    ),
                                  ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            Wrap(
                              crossAxisAlignment: WrapCrossAlignment.center,
                              spacing: 8,
                              children: [
                                Text(
                                  mood.label,
                                  style: TextStyle(
                                    color: colors.ink,
                                    fontSize: 16,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                if (appearance?.mood == mood)
                                  Icon(
                                    Icons.check_rounded,
                                    color: colors.ice,
                                    size: 20,
                                  ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        Text(
          '配色会同时应用到对话、子代理和设置。界面会遵循系统的减少动效与高对比度设置。',
          style: TextStyle(color: colors.inkSoft, fontSize: 13, height: 1.6),
        ),
      ],
    );
  }
}

class ModelConfigurationPage extends StatelessWidget {
  const ModelConfigurationPage({
    super.key,
    required this.controller,
    this.providers,
  });
  final ChatController controller;
  final ModelProviderSettings? providers;
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) => _SettingsDetail(
      title: '模型配置',
      children: [
        _SectionTitle(
          controller.transport.isFixture ? '当前会话 · 演示模型' : '当前会话模型',
        ),
        _SettingsGroup(
          children: [
            for (final model in controller.models)
              ListTile(
                key: ValueKey('settings-model-' + model.id),
                minTileHeight: 58,
                title: Text(model.label),
                subtitle: Text(model.id),
                trailing: controller.selectedModelId == model.id
                    ? Icon(
                        Icons.check_rounded,
                        color: DshPalette.of(context).ice,
                      )
                    : null,
                onTap: () => controller.selectModel(model.id),
              ),
            if (controller.models.isEmpty)
              const ListTile(
                title: Text('引擎尚未提供可选择的模型'),
                subtitle: Text('模型目录由引擎提供。'),
              ),
          ],
        ),
        const SizedBox(height: 24),
        const _SectionTitle('服务提供商与 API Key'),
        if (providers case final service?)
          _ProviderSettings(service: service)
        else
          const ListTile(title: Text('当前引擎不提供模型配置接口')),
      ],
    ),
  );
}

class _ProviderSettings extends StatefulWidget {
  const _ProviderSettings({required this.service});
  final ModelProviderSettings service;

  @override
  State<_ProviderSettings> createState() => _ProviderSettingsState();
}

class _ProviderSettingsState extends State<_ProviderSettings> {
  late Future<ProviderSettingsSnapshot?> _snapshot;

  @override
  void initState() {
    super.initState();
    _snapshot = widget.service.loadModelProviders();
  }

  bool _removing = false;
  String? _removeError;

  void _reload() {
    final next = widget.service.loadModelProviders();
    setState(() {
      _snapshot = next;
    });
  }

  Future<void> _openForm(
    ProviderSettingsSnapshot snapshot, [
    ModelProviderProfile? profile,
  ]) async {
    final changed = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _ProviderForm(
        service: widget.service,
        snapshot: snapshot,
        profile: profile,
      ),
    );
    if (mounted && changed == true) _reload();
  }

  Future<void> _remove(ProviderSettingsSnapshot snapshot, String route) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (_) => SettingsConfirmDialog(
        title: '移除服务提供商？',
        message: '将从模型目录移除「' + route + '」，保留已保存的 API Key。',
        confirmKey: const ValueKey('provider-confirm-remove'),
        confirmLabel: '移除',
      ),
    );
    if (!mounted || confirmed != true || _removing) return;
    setState(() {
      _removing = true;
      _removeError = null;
    });
    try {
      await widget.service.removeModelProvider(snapshot, route);
    } on Object catch (error) {
      if (!mounted) return;
      _removeError =
          error is DshRpcException && error.code == 'settings/conflict'
          ? '配置已在其他端更新，未移除服务提供商。'
          : '引擎未确认移除，请核对刷新后的列表。';
    } finally {
      if (mounted) {
        _removing = false;
        _reload();
      }
    }
  }

  @override
  Widget build(
    BuildContext context,
  ) => FutureBuilder<ProviderSettingsSnapshot?>(
    future: _snapshot,
    builder: (context, state) {
      if (state.connectionState != ConnectionState.done) {
        return const LinearProgressIndicator();
      }
      if (state.hasError) {
        return ListTile(
          title: const Text('未能读取服务提供商'),
          trailing: IconButton(
            tooltip: '重新读取',
            icon: const Icon(Icons.refresh),
            onPressed: _reload,
          ),
        );
      }
      final snapshot = state.data;
      if (snapshot == null) return const ListTile(title: Text('引擎未启用自定义提供商'));
      return _SettingsGroup(
        children: [
          if (_removeError case final error?)
            Semantics(liveRegion: true, child: ListTile(title: Text(error))),
          for (final profile in snapshot.providers)
            ListTile(
              key: ValueKey('provider-edit-' + profile.route),
              title: Text(
                profile.route,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: profile.models.isEmpty
                  ? null
                  : Text(
                      profile.models.join('、'),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
              onTap: !snapshot.writable || _removing
                  ? null
                  : () => _openForm(snapshot, profile),
              trailing: !snapshot.writable
                  ? null
                  : IconButton(
                      key: ValueKey('provider-remove-' + profile.route),
                      tooltip: '移除 ' + profile.route,
                      icon: const Icon(Icons.delete_outline_rounded),
                      onPressed: _removing
                          ? null
                          : () => _remove(snapshot, profile.route),
                    ),
            ),
          if (!snapshot.writable)
            const ListTile(title: Text('引擎配置为只读'))
          else
            ListTile(
              key: const ValueKey('provider-add'),
              leading: const Icon(Icons.add),
              title: const Text('添加服务提供商'),
              onTap: _removing ? null : () => _openForm(snapshot),
            ),
        ],
      );
    },
  );
}

class _ProviderForm extends StatefulWidget {
  const _ProviderForm({
    required this.service,
    required this.snapshot,
    this.profile,
  });
  final ModelProviderSettings service;
  final ProviderSettingsSnapshot snapshot;
  final ModelProviderProfile? profile;

  @override
  State<_ProviderForm> createState() => _ProviderFormState();
}

String _newProviderCredentialRef() {
  final random = Random.secure();
  final suffix = List.generate(
    16,
    (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
  ).join().toUpperCase();
  return 'DSH_NATIVE_${suffix}_API_KEY';
}

class _ProviderFormState extends State<_ProviderForm> {
  final _route = TextEditingController();
  final _url = TextEditingController();
  final _models = TextEditingController();
  final _key = TextEditingController();
  late String _api;
  String? _error, _discoveryStatus, _savedRef;
  bool _saving = false;
  bool _discovering = false;
  bool _conflict = false;

  bool get _busy => _saving || _discovering;

  @override
  void initState() {
    super.initState();
    final profile = widget.profile;
    _api = profile?.api ?? ModelProviderProfile.protocols.first;
    if (profile != null) {
      _route.text = profile.route;
      _url.text = profile.baseUrl;
      _models.text = profile.models.join(', ');
    }
  }

  @override
  void dispose() {
    for (final controller in [_route, _url, _models, _key]) {
      controller.dispose();
    }
    super.dispose();
  }

  List<String> _draftModels() {
    final original = widget.profile?.models;
    if (original != null && _models.text == original.join(', '))
      return original;
    return _models.text
        .split(RegExp(r'[,\r\n，]'))
        .map((id) => id.trim())
        .where((id) => id.isNotEmpty)
        .toList();
  }

  String? _endpointError(String url, String key) {
    final endpoint = Uri.tryParse(url);
    if (endpoint == null ||
        !['https', 'http'].contains(endpoint.scheme) ||
        endpoint.host.isEmpty ||
        endpoint.userInfo.isNotEmpty ||
        endpoint.hasQuery ||
        endpoint.hasFragment) {
      return '请输入有效的服务地址，不包含账号、查询参数或片段。';
    }
    if (key.isNotEmpty &&
        endpoint.scheme == 'http' &&
        !['127.0.0.1', '::1', 'localhost'].contains(endpoint.host)) {
      return '含 API Key 的非回环服务必须使用 HTTPS。';
    }
    return null;
  }

  Future<void> _discover() async {
    if (_busy || _conflict || _savedRef != null) return;
    final url = _url.text.trim(), key = _key.text.trim();
    final error = _endpointError(url, key);
    setState(() {
      _error = error;
      _discoveryStatus = null;
      _discovering = error == null;
    });
    if (error != null) return;
    try {
      final ids = await widget.service.discoverProviderModels(
        baseUrl: url,
        api: _api,
        apiKey: key.isEmpty ? null : key,
      );
      if (!mounted) return;
      final existing = _draftModels();
      final seen = existing.toSet();
      final added = ids.where(seen.add).toList();
      if (added.isNotEmpty) _models.text = [...existing, ...added].join(', ');
      setState(
        () => _discoveryStatus = ids.isEmpty
            ? '服务未返回模型。'
            : '发现 ' +
                  ids.length.toString() +
                  ' 个模型，新增 ' +
                  added.length.toString() +
                  ' 个。',
      );
    } on Object {
      if (mounted) setState(() => _error = '未能发现模型，请核对服务地址、协议和 API Key。');
    } finally {
      if (mounted) setState(() => _discovering = false);
    }
  }

  Future<void> _save() async {
    if (_busy || _conflict) return;
    final previous = widget.profile;
    final route = previous?.route ?? _route.text.trim();
    final url = previous != null && _url.text == previous.baseUrl
        ? previous.baseUrl
        : _url.text.trim();
    final models = _draftModels();
    if (previous == null &&
        (!ModelProviderProfile.routePattern.hasMatch(route) ||
            widget.snapshot.providers.any((p) => p.route == route) ||
            models.isEmpty)) {
      setState(() => _error = '请输入未使用的小写标识和至少一个模型 ID。');
      return;
    }
    if (models.toSet().length != models.length) {
      setState(() => _error = '模型 ID 不能重复。');
      return;
    }
    final key = _key.text.trim();
    final error = previous == null || url != previous.baseUrl || key.isNotEmpty
        ? _endpointError(url, key)
        : null;
    if (error != null) {
      setState(() => _error = error);
      return;
    }
    if (_savedRef != null && key.isEmpty) {
      setState(() => _error = '请输入 API Key，或关闭并保留已保存的提供商。');
      return;
    }
    final newRef =
        _savedRef ?? (key.isEmpty ? null : _newProviderCredentialRef());
    setState(() {
      _saving = true;
      _error = null;
      _discoveryStatus = null;
    });
    try {
      if (_savedRef == null) {
        final profile = ModelProviderProfile(
          route: route,
          api: _api,
          baseUrl: url,
          models: models,
          credentialRef: newRef ?? previous?.credentialRef,
        );
        if (previous == null) {
          await widget.service.addModelProvider(widget.snapshot, profile);
        } else {
          await widget.service.updateModelProvider(widget.snapshot, profile);
        }
        _savedRef = newRef;
      }
      if (newRef != null) await widget.service.setModelProviderKey(newRef, key);
      if (!mounted) return;
      _key.clear();
      Navigator.of(context).pop(true);
    } on Object catch (error) {
      if (!mounted) return;
      final code = error is DshRpcException ? error.code : null;
      setState(() {
        _conflict = code == 'settings/conflict';
        _error = _savedRef != null
            ? '提供商已保存，但 API Key 未确认。可以重试。'
            : switch (code) {
                'settings/conflict' => '配置已在其他端更新，请关闭后重新打开。',
                'settings/provider-destination' =>
                  '原有授权不能用在新地址或协议上。请使用新密钥，或另建服务提供商。',
                _ => '引擎未确认保存，请检查配置后重试。',
              };
      });
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Widget _field(
    String id,
    String label,
    TextEditingController controller, {
    bool secret = false,
    bool locked = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: TextField(
      key: ValueKey('provider-' + id),
      controller: controller,
      enabled: !_busy && !_conflict && !locked && (secret || _savedRef == null),
      obscureText: secret,
      autocorrect: !secret,
      enableSuggestions: !secret,
      decoration: InputDecoration(labelText: label),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final editing = widget.profile != null;
    return PopScope(
      canPop: !_saving && _savedRef == null && !_conflict,
      child: Dialog(
        key: const ValueKey('settings-provider-form'),
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
        child: GlassPanel(
          radius: 24,
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(context).height * .86,
            ),
            child: SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(
                20,
                16,
                20,
                12 + MediaQuery.viewInsetsOf(context).bottom,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          editing ? '编辑服务提供商' : '添加服务提供商',
                          style: const TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                      IconButton(
                        tooltip: '关闭',
                        onPressed: _saving
                            ? null
                            : () =>
                                  Navigator.of(context)
                                      .pop(_savedRef != null || _conflict),
                        icon: const Icon(Icons.close_rounded),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  _field('route', '提供商标识', _route, locked: editing),
                  DropdownButtonFormField<String>(
                    initialValue: _api,
                    decoration: const InputDecoration(labelText: '接口协议'),
                    isExpanded: true,
                    items: [
                      if (!ModelProviderProfile.protocols.contains(_api))
                        DropdownMenuItem(
                          value: _api,
                          child: Text(_api.isEmpty ? '引擎默认' : _api),
                        ),
                      for (final api in ModelProviderProfile.protocols)
                        DropdownMenuItem(value: api, child: Text(api)),
                    ],
                    onChanged: _busy || _conflict || _savedRef != null
                        ? null
                        : (api) {
                            if (api != null) setState(() => _api = api);
                          },
                  ),
                  const SizedBox(height: 10),
                  _field('url', '服务地址', _url),
                  _field(
                    'key',
                    editing ? '新 API Key（留空保留原值）' : 'API Key（可选）',
                    _key,
                    secret: true,
                  ),
                  _field('models', '模型 ID（逗号分隔）', _models),
                  Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: OutlinedButton.icon(
                      key: const ValueKey('provider-discover'),
                      onPressed: _busy || _conflict || _savedRef != null
                          ? null
                          : _discover,
                      icon: const Icon(Icons.search_rounded),
                      label: Text(_discovering ? '发现中…' : '发现模型'),
                    ),
                  ),
                  if (_discoveryStatus case final status?)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Semantics(liveRegion: true, child: Text(status)),
                    ),
                  if (_error case final error?)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Semantics(
                        liveRegion: true,
                        child: Text(
                          error,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                      ),
                    ),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      TextButton(
                        onPressed: _saving
                            ? null
                            : () =>
                                  Navigator.of(context)
                                      .pop(_savedRef != null || _conflict),
                        child: const Text('关闭'),
                      ),
                      const SizedBox(width: 8),
                      FilledButton(
                        key: const ValueKey('provider-save'),
                        onPressed: _busy || _conflict ? null : _save,
                        child: Text(_saving ? '保存中…' : '保存'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The line under「当前会话访问范围」. An unsupported engine must say so rather
/// than show the composer's local default as if the Host held it.
String _sessionPermissionLine(ChatController controller) {
  if (controller.transport is! SessionPermissionTransport)
    return '当前引擎不支持在此修改，请使用 Web 版';
  final current = controller.sessionPermission;
  if (!controller.permissionKnown || current == null) return '宿主当前权限未知，请确认后再修改';
  return current.label;
}

/// A row that cannot write must say why instead of silently doing nothing.
void _explainUnavailablePermission(
  BuildContext context,
  ChatController controller,
) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(
        controller.transport is SessionPermissionTransport
            ? '正在写入权限，请稍候。'
            : '当前引擎不支持在原生设置里立即修改会话权限，请使用 Web 版。',
      ),
    ),
  );
}

class PermissionsSettingsPage extends StatefulWidget {
  const PermissionsSettingsPage({super.key, required this.controller});
  final ChatController controller;
  @override
  State<PermissionsSettingsPage> createState() =>
      _PermissionsSettingsPageState();
}

class _PermissionsSettingsPageState extends State<PermissionsSettingsPage> {
  ChatController get controller => widget.controller;

  @override
  void initState() {
    super.initState();
    // This page answers "what does the Host hold now", so a reopened page asks
    // again instead of repeating whatever this session remembered.
    unawaited(controller.refreshSessionConfiguration());
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) => _SettingsDetail(
      title: '权限管理',
      children: [
        const _SectionTitle('对话工具'),
        _SettingsGroup(
          children: [
            ListTile(
              key: const ValueKey('settings-session-permission'),
              minTileHeight: 60,
              leading: const Icon(Icons.shield_outlined),
              title: const Text('当前会话访问范围'),
              // This row states the value the Host holds now, not the draft the
              // composer would apply on the next send.
              subtitle: Text(_sessionPermissionLine(controller)),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: controller.permissionWriteInFlight
                  ? null
                  : controller.canApplySessionPermission
                  ? () => showSessionPermissionSelector(context, controller)
                  : () => _explainUnavailablePermission(context, controller),
            ),
          ],
        ),
        if (controller.permissionWriteError case final error?)
          Padding(
            padding: const EdgeInsets.fromLTRB(6, 10, 6, 0),
            child: Semantics(
              key: const ValueKey('settings-session-permission-error'),
              container: true,
              liveRegion: true,
              child: Text(
                '上次修改未生效：' + error,
                style: TextStyle(
                  color: DshPalette.of(context).bad,
                  fontSize: 13,
                  height: 1.55,
                ),
              ),
            ),
          ),
        const SizedBox(height: 24),
        const _SectionTitle('Android 权限与能力'),
        _SettingsGroup(
          children: [
            ListTile(
              minTileHeight: 60,
              leading: const Icon(Icons.phonelink_lock_rounded),
              title: const Text('应用权限中心'),
              subtitle: const Text('系统权限、设备能力与引擎权限'),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => _openHost(
                context,
                HostBridge.openSettingsSection(SettingsDestination.permissions),
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        Text(
          '会话工具范围与手机系统权限分别生效。',
          style: TextStyle(
            color: DshPalette.of(context).inkSoft,
            fontSize: 13,
            height: 1.6,
          ),
        ),
      ],
    ),
  );
}

/// 连接与远程。
///
/// 这一页只回答一个问题：现在到底连着什么。它读取宿主报告的引擎信息，
/// 从不代用户发起连接、不碰任何凭据；没有证据的那一侧一律显示「未连接」，
/// 失败时说明为什么没打开，而不是先画成一条已经握手的链路。
class ConnectionsPage extends StatefulWidget {
  const ConnectionsPage({super.key});
  @override
  State<ConnectionsPage> createState() => _ConnectionsPageState();
}

class _ConnectionsPageState extends State<ConnectionsPage> {
  HostInfo? _host;
  bool _read = false;

  @override
  void initState() {
    super.initState();
    _readHost();
  }

  /// One read on open. A tile that needs a fresh answer reads again itself.
  Future<void> _readHost() async {
    final info = await HostBridge.readEngineInfo();
    if (!mounted) return;
    setState(() {
      _host = info;
      _read = true;
    });
  }

  void _openSsh(String platform) {
    Navigator.of(context).push<void>(
      MaterialPageRoute(builder: (_) => ManualSshPage(platform: platform)),
    );
  }

  String get _engineLine {
    final host = _host;
    if (!_read) return '正在读取引擎报告…';
    if (host == null) return '未检测到本机引擎报告。当前为 Flutter 预览，连接状态未知。';
    if (!host.runtimeResolved) {
      return '引擎正在检测运行环境 · 宿主状态：' + host.state;
    }
    return '引擎运行环境：' + host.runtimeLabel + ' · 宿主状态：' + _stateLabel(host.state);
  }

  /// The Host reports a supervisor state class name. Show what it means to a
  /// reader, and keep unknown states unknown instead of guessing.
  static String _stateLabel(String state) => switch (state) {
    'Healthy' => '运行中',
    'Starting' || 'Installing' => '启动中',
    'SafeMode' => '安全模式',
    'Degraded' => '降级运行',
    'Backoff' => '等待重试',
    'Failed' => '启动失败',
    'Stopped' || 'Idle' => '未运行',
    'preview' => '预览',
    _ => state.trim().isEmpty ? '未知' : state,
  };

  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final target = DshDisplayScope.atLeastTarget(context, 60);
    return _SettingsDetail(
      title: '连接与远程',
      children: [
        const _SectionTitle('本机引擎'),
        _SettingsGroup(
          children: [
            ListTile(
              key: const ValueKey('connections-native'),
              minTileHeight: target,
              leading: Icon(Icons.memory_rounded, color: colors.inkSoft),
              title: const Text('本机引擎'),
              subtitle: Text(_host == null ? '尚未收到引擎报告' : _host!.runtimeLabel),
              trailing: IconButton(
                key: const ValueKey('connections-refresh'),
                tooltip: '重新读取引擎状态',
                onPressed: _read ? _readHost : null,
                icon: const Icon(Icons.refresh_rounded),
              ),
              onTap: null,
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(6, 10, 6, 0),
          child: Semantics(
            key: const ValueKey('connections-status'),
            container: true,
            liveRegion: true,
            child: Text(
              _engineLine,
              style: TextStyle(
                color: colors.inkSoft,
                fontSize: 13,
                height: 1.55,
              ),
            ),
          ),
        ),
        const SizedBox(height: 24),
        const _SectionTitle('远程 SSH'),
        _SettingsGroup(
          children: [
            ListTile(
              key: const ValueKey('connections-ssh-windows'),
              minTileHeight: target,
              leading: Icon(
                Icons.desktop_windows_outlined,
                color: colors.inkSoft,
              ),
              title: const Text('Windows 主机'),
              subtitle: const Text('验证主机指纹 · 密码连接'),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => _openSsh('Windows'),
            ),
            ListTile(
              key: const ValueKey('connections-ssh-linux'),
              minTileHeight: target,
              leading: Icon(Icons.dns_outlined, color: colors.inkSoft),
              title: const Text('Linux 主机'),
              subtitle: const Text('验证主机指纹 · 密码连接'),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => _openSsh('Linux'),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(6, 10, 6, 0),
          child: Text(
            '未连接任何 SSH 主机',
            style: TextStyle(color: colors.inkSoft, fontSize: 13, height: 1.6),
          ),
        ),
      ],
    );
  }
}

String _displayHint(DshDisplaySize size) => switch (size) {
  DshDisplaySize.compact => '90%，信息更密，一屏看到更多',
  DshDisplaySize.standard => '100%，默认大小',
  DshDisplaySize.relaxed => '110%，字与控件都更大',
};

Future<void> _openHost(BuildContext context, Future<bool> opening) async {
  final opened = await opening;
  if (!opened && context.mounted)
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('此功能由 Android 应用提供，当前为 Flutter 预览。')),
    );
}

class UnavailableSettingsPage extends StatelessWidget {
  const UnavailableSettingsPage({super.key, required this.destination});

  final SettingsDestination destination;

  @override
  Widget build(BuildContext context) => _SettingsDetail(
    title: destination.title,
    children: [
      Text(
        '此项目的原生管理功能暂不可用。',
        style: TextStyle(
          color: DshPalette.of(context).inkSoft,
          fontSize: 14,
          height: 1.5,
        ),
      ),
    ],
  );
}

class _SettingsDetail extends StatelessWidget {
  const _SettingsDetail({required this.title, required this.children});
  final String title;
  final List<Widget> children;
  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final theme = Theme.of(context);
    return Scaffold(
      backgroundColor: colors.paper,
      appBar: AppBar(
        title: Text(
          title,
          style: theme.textTheme.titleLarge?.copyWith(
            color: colors.ink,
            fontWeight: FontWeight.w700,
          ),
        ),
        backgroundColor: colors.paper,
        foregroundColor: colors.ink,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
      ),
      body: SafeArea(
        child: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
              children: children,
            ),
          ),
        ),
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(6, 0, 6, 6),
    child: Text(
      text,
      style: TextStyle(
        color: DshPalette.of(context).inkSoft,
        fontSize: 13,
        fontWeight: FontWeight.w600,
      ),
    ),
  );
}

class _SettingsGroup extends StatelessWidget {
  const _SettingsGroup({required this.children});
  final List<Widget> children;
  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    return Material(
      color: colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: colors.line.withValues(alpha: .42)),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          for (int i = 0; i < children.length; i++) ...[
            if (i > 0)
              Divider(
                height: 1,
                indent: 20,
                endIndent: 20,
                color: colors.line.withValues(alpha: .5),
              ),
            children[i],
          ],
        ],
      ),
    );
  }
}

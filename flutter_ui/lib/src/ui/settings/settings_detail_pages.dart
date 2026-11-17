import 'package:material_ui/material_ui.dart';

import '../../bridge/host_bridge.dart';
import '../../model/settings_model.dart';
import '../../state/chat_controller.dart';
import '../../theme/display_scale.dart';
import '../../theme/dsh_theme.dart';
import '../composer_sheets.dart';

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
                    color: colors.surface,
                    borderRadius: BorderRadius.circular(20),
                    child: InkWell(
                      key: ValueKey('settings-mood-' + mood.name),
                      borderRadius: BorderRadius.circular(20),
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
  const ModelConfigurationPage({super.key, required this.controller});
  final ChatController controller;
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
                subtitle: Text('请打开完整模型配置'),
              ),
          ],
        ),
        const SizedBox(height: 24),
        const _SectionTitle('服务提供商与 API Key'),
        _SettingsGroup(
          children: [
            ListTile(
              minTileHeight: 58,
              leading: const Icon(Icons.tune_rounded),
              title: const Text('完整模型配置'),
              subtitle: const Text('在 Web UI 管理提供商、API Key 和模型参数'),
              trailing: const Icon(Icons.open_in_new_rounded),
              onTap: () => _openHost(context, HostBridge.openWebFallback()),
            ),
          ],
        ),
      ],
    ),
  );
}

class PermissionsSettingsPage extends StatelessWidget {
  const PermissionsSettingsPage({super.key, required this.controller});
  final ChatController controller;
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
              minTileHeight: 60,
              leading: const Icon(Icons.shield_outlined),
              title: const Text('当前会话访问范围'),
              subtitle: Text(controller.permissionLabel),
              trailing: const Icon(Icons.chevron_right_rounded),
              onTap: () => showPermissionSelector(context, controller),
            ),
          ],
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

class _SettingsDetail extends StatelessWidget {
  const _SettingsDetail({required this.title, required this.children});
  final String title;
  final List<Widget> children;
  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    return Scaffold(
      backgroundColor: colors.paper,
      appBar: AppBar(
        title: Text(title),
        backgroundColor: colors.paper,
        surfaceTintColor: Colors.transparent,
      ),
      body: SafeArea(
        child: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 28),
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
  Widget build(BuildContext context) => Material(
    color: DshPalette.of(context).surface,
    borderRadius: BorderRadius.circular(20),
    clipBehavior: Clip.antiAlias,
    child: Column(
      children: [
        for (int i = 0; i < children.length; i++) ...[
          if (i > 0)
            Divider(
              height: 1,
              indent: 20,
              endIndent: 20,
              color: DshPalette.of(context).line.withValues(alpha: .5),
            ),
          children[i],
        ],
      ],
    ),
  );
}

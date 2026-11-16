import 'package:material_ui/material_ui.dart';

import '../model/composer_model.dart';
import '../bridge/host_bridge.dart';
import '../state/chat_controller.dart';
import '../theme/dsh_theme.dart';
import 'glass_panel.dart';
import 'permission_icon.dart';

Future<void> showModelSelector(
  BuildContext context,
  ChatController controller,
) => _show(context, controller, true);
Future<void> showPermissionSelector(
  BuildContext context,
  ChatController controller,
) => _show(context, controller, false);
Future<void> _show(
  BuildContext context,
  ChatController controller,
  bool models,
) async {
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: Colors.transparent,
    builder: (sheetContext) => Padding(
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 14),
      child: GlassPanel(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(sheetContext).height * .75,
          ),
          child: SingleChildScrollView(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 20),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
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
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.only(left: 12),
                          child: Text(
                            models ? '选择模型' : '工具权限',
                            style: const TextStyle(
                              fontSize: 21,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ),
                      IconButton(
                        tooltip: '关闭',
                        onPressed: () => Navigator.of(sheetContext).pop(),
                        icon: const Icon(Icons.close_rounded),
                      ),
                    ],
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 0, 12, 16),
                    child: Text(
                      controller.transport.isFixture
                          ? (models
                                ? '当前为演示模式，以下选项不会调用在线模型。'
                                : '仅影响当前对话的演示工具，不修改手机系统权限。')
                          : '使用引擎提供的可用选项。',
                      style: TextStyle(
                        color: DshPalette.of(context).inkSoft,
                        fontSize: 13,
                        height: 1.6,
                      ),
                    ),
                  ),
                  if (!controller.supportsComposerOptions)
                    const Padding(
                      padding: EdgeInsets.all(12),
                      child: Text('当前引擎尚未接入此能力，请使用 Web 版。'),
                    )
                  else if (models && controller.models.isEmpty) ...[
                    const Padding(
                      padding: EdgeInsets.all(12),
                      child: Text('还没有可用模型，请先配置模型提供商或登录账号。'),
                    ),
                    TextButton.icon(
                      key: const ValueKey('model-configure'),
                      icon: const Icon(Icons.settings_outlined),
                      label: const Text('配置模型'),
                      onPressed: () {
                        Navigator.of(sheetContext).pop();
                        HostBridge.openWebFallback();
                      },
                    ),
                    TextButton.icon(
                      key: const ValueKey('model-refresh'),
                      icon: const Icon(Icons.refresh),
                      label: const Text('重新读取可用模型'),
                      onPressed: () async {
                        Navigator.of(sheetContext).pop();
                        await controller.reconnect();
                        if (context.mounted)
                          await showModelSelector(context, controller);
                      },
                    ),
                  ] else if (models)
                    ...controller.models.map(
                      (model) => _Choice(
                        key: ValueKey('model-option-' + model.id),
                        title: model.label,
                        detail: model.description,
                        selected: controller.selectedModelId == model.id,
                        onTap: () {
                          controller.selectModel(model.id);
                          Navigator.of(sheetContext).pop();
                        },
                      ),
                    )
                  else
                    ...ComposerPermission.values.map(
                      (mode) => _Choice(
                        key: ValueKey('permission-option-' + mode.name),
                        leading: DshPermissionIcon(
                          glyph: switch (mode) {
                            ComposerPermission.readOnly =>
                              DshPermissionGlyph.readOnly,
                            ComposerPermission.workspaceWrite =>
                              DshPermissionGlyph.workspaceWrite,
                            ComposerPermission.fullAccess =>
                              DshPermissionGlyph.fullAccess,
                          },
                          size: 20,
                        ),
                        title: mode.label,
                        detail: controller.supportsPermission(mode)
                            ? mode.description
                            : '当前引擎未提供此模式，请使用 Web 版查看。',
                        selected:
                            controller.permissionKnown &&
                            controller.permission == mode,
                        onTap: !controller.supportsPermission(mode)
                            ? null
                            : () {
                                controller.selectPermission(mode);
                                Navigator.of(sheetContext).pop();
                              },
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _Choice extends StatelessWidget {
  const _Choice({
    super.key,
    required this.title,
    required this.detail,
    required this.selected,
    required this.onTap,
    this.leading,
  });
  final String title, detail;
  final bool selected;
  final VoidCallback? onTap;
  final Widget? leading;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 4),
    child: Material(
      color: selected
          ? DshPalette.of(context).ink.withValues(alpha: .08)
          : Colors.transparent,
      borderRadius: BorderRadius.circular(16),
      child: ListTile(
        selected: selected,
        enabled: onTap != null,
        leading: leading,
        selectedColor: DshPalette.of(context).ink,
        onTap: onTap,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        title: Text(
          title,
          style: TextStyle(fontSize: 17, color: DshPalette.of(context).ink),
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 3),
          child: Text(
            detail,
            style: TextStyle(
              color: DshPalette.of(context).inkSoft,
              fontSize: 13,
              height: 1.5,
            ),
          ),
        ),
        trailing: Icon(
          selected ? Icons.check_rounded : Icons.circle_outlined,
          size: selected ? 22 : 17,
          color: selected
              ? DshPalette.of(context).ink
              : DshPalette.of(context).inkFaint,
        ),
      ),
    ),
  );
}

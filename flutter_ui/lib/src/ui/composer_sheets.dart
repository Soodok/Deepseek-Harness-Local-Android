import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/composer_model.dart';
import 'settings/settings_home.dart' show showModelConfiguration;
import '../state/chat_controller.dart';
import '../theme/dsh_theme.dart';
import 'glass_panel.dart';
import 'permission_icon.dart';
import 'staggered_reveal.dart';

Future<void> showModelSelector(
  BuildContext context,
  ChatController controller,
) => _show(context, controller, true);
Future<void> showPermissionSelector(
  BuildContext context,
  ChatController controller,
) => _show(context, controller, false);

/// The settings variant: the tap is the whole action, so it writes the bound
/// session's permission right away instead of staging it for the next send.
Future<void> showSessionPermissionSelector(
  BuildContext context,
  ChatController controller,
) {
  controller.clearPermissionWriteError();
  return _show(context, controller, false, persist: true);
}

Future<void> _show(
  BuildContext context,
  ChatController controller,
  bool models, {
  bool persist = false,
}) async {
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
                          : persist && !models
                          ? '立即写入当前会话，选择后即生效，不会发送消息。'
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
                        showModelConfiguration(context, controller);
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
                    StaggeredReveal(
                      children: controller.models
                          .map(
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
                          .toList(),
                    )
                  else
                    AnimatedBuilder(
                      animation: controller,
                      builder: (context, _) => Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          StaggeredReveal(
                            children: ComposerPermission.values
                                .map(
                                  (mode) => _Choice(
                                    key: ValueKey(
                                      'permission-option-' + mode.name,
                                    ),
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
                                    // A settings write names the value the Host
                                    // actually holds; the composer sheet shows
                                    // the draft that the next send applies.
                                    selected: controller.permissionKnown &&
                                        (persist
                                            ? controller.sessionPermission ==
                                                  mode
                                            : controller.permission == mode),
                                    onTap:
                                        !controller.supportsPermission(mode) ||
                                            (persist &&
                                                !controller
                                                    .canApplySessionPermission)
                                        ? null
                                        : () {
                                            if (!persist) {
                                              controller.selectPermission(
                                                mode,
                                              );
                                              Navigator.of(sheetContext).pop();
                                              return;
                                            }
                                            unawaited(
                                              _persist(
                                                sheetContext,
                                                controller,
                                                mode,
                                              ),
                                            );
                                          },
                                  ),
                                )
                                .toList(),
                          ),
                          if (persist) _writeStatus(context, controller),
                        ],
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

/// One settings write. A success closes the sheet; a failure keeps it open so
/// the reader can see which value the Host kept and pick again.
Future<void> _persist(
  BuildContext context,
  ChatController controller,
  ComposerPermission mode,
) async {
  final applied = await controller.applySessionPermission(mode);
  if (applied && context.mounted) Navigator.of(context).pop();
}

Widget _writeStatus(BuildContext context, ChatController controller) {
  final writing = controller.permissionWriteInFlight;
  final error = controller.permissionWriteError;
  if (!writing && error == null) return const SizedBox.shrink();
  final colors = DshPalette.of(context);
  return Padding(
    padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
    child: Semantics(
      key: const ValueKey('session-permission-status'),
      container: true,
      liveRegion: true,
      child: Text(
        writing ? '正在写入当前会话…' : error!,
        style: TextStyle(
          color: writing ? colors.inkSoft : colors.bad,
          fontSize: 13,
          height: 1.5,
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

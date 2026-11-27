import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../../bridge/host_bridge.dart';
import '../../model/settings_model.dart';
import '../../model/tool_content.dart';
import '../../state/chat_controller.dart';
import '../../state/settings_controller.dart';
import 'settings_scope.dart';
import 'settings_page.dart';
import 'settings_detail_pages.dart';

Future<void> showSettingsHome(BuildContext context, ChatController chat) async {
  if (SettingsScope.maybeOf(context) == null) return;
  await Navigator.of(context)
      .push<void>(MaterialPageRoute(builder: (_) => _SettingsHome(chat: chat)));
}

class _SettingsHome extends StatelessWidget {
  const _SettingsHome({required this.chat});
  final ChatController chat;
  @override
  Widget build(BuildContext context) {
    final c = SettingsScope.of(context);
    return SettingsPage(
      profile: c.profile,
      usage: c.usage,
      loading: c.loading,
      busy: c.busy,
      error: c.error,
      isDemo: c.isDemo,
      onLogin: () => startAccountSignIn(context, c),
      onLogout: () => confirmAccountSignOut(context, c),
      onRefresh: c.refresh,
      onOpenUsage: () => openOfficialUsage(context, c),
      onOpen: (destination) async {
        switch (destination) {
          case SettingsDestination.appearance:
            await Navigator.of(context).push<void>(
              MaterialPageRoute(builder: (_) => const PersonalizationPage()),
            );
            return;
          case SettingsDestination.models:
            await showModelConfiguration(context, chat);
            return;
          case SettingsDestination.permissions:
            await Navigator.of(context).push<void>(
              MaterialPageRoute(
                builder: (_) => PermissionsSettingsPage(controller: chat),
              ),
            );
            return;
          case SettingsDestination.connections:
            // 连接状态要能在这里读清楚：原生入口只负责打开，判断"连着什么"
            // 必须留给这一页，而不是交给一个看不到结果的系统弹层。
            await Navigator.of(context).push<void>(
              MaterialPageRoute(builder: (_) => const ConnectionsPage()),
            );
            return;
          case SettingsDestination.webUi:
            await openSettingsWeb(context);
            return;
          default:
            if (!c.isDemo) {
              if (await HostBridge.openSettingsSection(destination)) return;
              if (!context.mounted) return;
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(content: Text('无法打开${destination.title}，请从 Android 设置重试。')),
              );
              return;
            }
            if (!context.mounted) return;
            await Navigator.of(context).push<void>(
              MaterialPageRoute(
                builder: (_) => UnavailableSettingsPage(destination: destination),
              ),
            );
            return;
        }
      },
    );
  }
}

Future<void> showModelConfiguration(BuildContext context, ChatController chat) async {
  final service = SettingsScope.maybeOf(context)?.service;
  final providers = service is ModelProviderSettings ? service as ModelProviderSettings : null;
  await Navigator.of(context).push<void>(MaterialPageRoute(
    builder: (_) => ModelConfigurationPage(controller: chat, providers: providers),
  ));
}

Future<void> openSettingsWeb(BuildContext context) async {
  final opened = await HostBridge.openWebFallback();
  if (!opened && context.mounted)
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('当前为 Flutter 预览。请在 Android 应用中打开完整 Web UI。'),
      ),
    );
}

Future<void> openOfficialUsage(
  BuildContext context,
  SettingsController controller,
) async {
  final service = controller.service;
  if (service is! AccountUsageLink) {
    if (context.mounted)
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('当前为演示记录。账号账单请在连接引擎后查看 DeepSeek 官方用量页。')),
      );
    return;
  }
  try {
    final uri = await (service as AccountUsageLink).usagePage();
    final opened = await HostBridge.openResource(
      OutputResource(
        id: 'official-usage',
        title: 'DeepSeek官方用量',
        uri: uri.toString(),
        kind: ResourceKind.link,
      ),
    );
    if (!opened) throw StateError('无法打开浏览器');
  } catch (_) {
    if (context.mounted)
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('暂时无法打开官方用量页，请稍后重试。')));
  }
}

Future<void> startAccountSignIn(
  BuildContext context,
  SettingsController controller,
) async {
  if (controller.busy) return;
  if (controller.isDemo) {
    final preview = await showDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: const Text('账号资料预览'),
        content: const Text('当前未连接手机引擎。这里仅展示头像和个人资料的界面效果，不会登录真实 DeepSeek 账号。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialog).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialog).pop(true),
            child: const Text('查看演示'),
          ),
        ],
      ),
    );
    if (preview == true) await controller.signIn();
    return;
  }
  final navigator = Navigator.of(context);
  final route = DialogRoute<void>(
    context: context,
    builder: (dialog) => PopScope(
      onPopInvokedWithResult: (didPop, result) {
        if (didPop && controller.busy) unawaited(controller.cancelSignIn());
      },
      child: AlertDialog(
        title: const Text('登录 DeepSeek'),
        content: const Text('请在系统浏览器中完成授权。账号凭据由本机引擎保管，完成后会自动显示个人资料。'),
        actions: [
          TextButton(
            onPressed: () => controller.cancelSignIn(),
            child: const Text('取消登录'),
          ),
        ],
      ),
    ),
  );
  unawaited(navigator.push(route));
  await controller.signIn();
  if (navigator.mounted && route.isActive) navigator.removeRoute(route);
  if (controller.error != null && context.mounted)
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(controller.error!)));
}

Future<void> confirmAccountSignOut(
  BuildContext context,
  SettingsController controller,
) async {
  if (controller.busy || controller.profile == null) return;
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (dialog) => AlertDialog(
      title: const Text('退出 DeepSeek 账号？'),
      content: const Text('使用此账号的运行中任务可能受到影响。API Key 配置会保留，退出不会删除对话记录。'),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(dialog).pop(false),
          child: const Text('取消'),
        ),
        TextButton(
          key: const ValueKey('confirm-signout'),
          onPressed: () => Navigator.of(dialog).pop(true),
          child: const Text('退出登录'),
        ),
      ],
    ),
  );
  if (confirmed == true) await controller.signOut();
  if (controller.error != null && context.mounted)
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(controller.error!)));
}

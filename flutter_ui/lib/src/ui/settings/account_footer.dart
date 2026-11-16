import 'package:material_ui/material_ui.dart';

import '../../model/settings_model.dart';
import '../../theme/dsh_theme.dart';
import 'settings_scope.dart';
import 'settings_home.dart';

class AccountFooter extends StatelessWidget {
  const AccountFooter({super.key, required this.onOpenSettings});
  final VoidCallback onOpenSettings;
  @override
  Widget build(BuildContext context) {
    final controller = SettingsScope.maybeOf(context);
    if (controller == null) return const SizedBox.shrink();
    final profile = controller.profile;
    final colors = DshPalette.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      child: Material(
        color: colors.surface,
        borderRadius: BorderRadius.circular(20),
        child: PopupMenuButton<String>(
          key: const ValueKey('account-menu'),
          tooltip: profile == null ? '账号与设置' : '账号菜单',
          enabled: !controller.busy,
          onSelected: (value) {
            if (value == 'settings') {
              Navigator.of(context).pop();
              onOpenSettings();
            }
            if (value == 'login') startAccountSignIn(context, controller);
            if (value == 'logout') confirmAccountSignOut(context, controller);
          },
          itemBuilder: (_) => [
            const PopupMenuItem(
              value: 'settings',
              child: ListTile(
                leading: Icon(Icons.settings_outlined),
                title: Text('设置'),
                contentPadding: EdgeInsets.zero,
              ),
            ),
            if (profile == null)
              PopupMenuItem(
                value: 'login',
                child: ListTile(
                  leading: const Icon(Icons.login_rounded),
                  title: Text(controller.isDemo ? '查看账号演示' : '登录 DeepSeek'),
                  contentPadding: EdgeInsets.zero,
                ),
              )
            else
              const PopupMenuItem(
                value: 'logout',
                child: ListTile(
                  leading: Icon(Icons.logout_rounded),
                  title: Text('退出登录'),
                  contentPadding: EdgeInsets.zero,
                ),
              ),
          ],
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                AccountAvatar(profile: profile),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        profile?.displayName ?? 'DeepSeek 账号',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: colors.ink,
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        controller.busy
                            ? '正在处理账号…'
                            : profile == null
                            ? (controller.isDemo ? '未登录 · 本地预览' : '登录与设置')
                            : (controller.isDemo ? '演示资料' : '个人账号'),
                        style: TextStyle(color: colors.inkSoft, fontSize: 12),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Icon(Icons.more_horiz_rounded, color: colors.inkSoft),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class AccountAvatar extends StatelessWidget {
  const AccountAvatar({super.key, required this.profile, this.size = 40});
  final AccountProfile? profile;
  final double size;
  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final uri = profile?.avatarUrl;
    final fallback = Icon(
      Icons.person_outline_rounded,
      color: colors.inkSoft,
      size: size * .55,
    );
    return ClipOval(
      child: Container(
        width: size,
        height: size,
        color: colors.userWash,
        child:
            uri != null &&
                ['http', 'https'].contains(uri.scheme) &&
                uri.host.isNotEmpty &&
                uri.userInfo.isEmpty
            ? Image.network(
                uri.toString(),
                fit: BoxFit.cover,
                cacheWidth: (size * MediaQuery.devicePixelRatioOf(context))
                    .ceil(),
                errorBuilder: (_, _, _) => fallback,
              )
            : fallback,
      ),
    );
  }
}

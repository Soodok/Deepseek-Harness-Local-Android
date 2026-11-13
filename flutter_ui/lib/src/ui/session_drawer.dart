import 'package:material_ui/material_ui.dart';

class SessionDrawer extends StatelessWidget {
  const SessionDrawer({super.key, this.onNewSession});

  final VoidCallback? onNewSession;

  @override
  Widget build(BuildContext context) {
    return Drawer(
      backgroundColor: const Color(0xFFF6F5FA),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 20, 16, 16),
              child: Row(
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    decoration: BoxDecoration(
                      color: const Color(0xFF6557D8),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(Icons.auto_awesome, color: Colors.white),
                  ),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Text(
                      '会话',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭会话抽屉',
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: FilledButton.tonalIcon(
                onPressed: onNewSession,
                icon: const Icon(Icons.add),
                label: const Text('新建会话'),
              ),
            ),
            const SizedBox(height: 24),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 22),
              child: Text(
                '最近使用',
                style: TextStyle(color: Color(0xFF757180), fontSize: 12),
              ),
            ),
            const SizedBox(height: 8),
            ListTile(
              selected: true,
              selectedTileColor: Color(0xFFEAE7FC),
              leading: Icon(Icons.chat_bubble_outline, size: 20),
              title: Text('项目状态检查'),
              subtitle: Text('当前工作区', style: TextStyle(fontSize: 12)),
              onTap: () => Navigator.of(context).pop(),
            ),
            const Spacer(),
            const Padding(
              padding: EdgeInsets.all(22),
              child: Text(
                'DSH 独立执行工作台 · fixture 模式可离线体验完整流程',
                style: TextStyle(
                  color: Color(0xFF757180),
                  fontSize: 12,
                  height: 1.5,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

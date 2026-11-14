import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../state/chat_controller.dart';
import '../theme/dsh_theme.dart';
import 'composer.dart';
import 'message_list.dart';
import 'session_drawer.dart';

/// Horizontal geometry shared by the message column and the composer so the
/// reading edge and the input edge line up at every width.
class ChatLayout {
  const ChatLayout._();

  static const double maxColumn = 720;

  static double gutter(double width) {
    if (width < 380) return 14;
    if (width < 600) return 18;
    return 28;
  }
}

class ChatShell extends StatefulWidget {
  const ChatShell({
    super.key,
    required this.controller,
    this.onOpenWebFallback,
    this.disposeController = false,
  });

  final ChatController controller;
  final VoidCallback? onOpenWebFallback;
  final bool disposeController;

  @override
  State<ChatShell> createState() => _ChatShellState();
}

class _ChatShellState extends State<ChatShell> {
  @override
  void initState() {
    super.initState();
    unawaited(widget.controller.start());
  }

  @override
  void didUpdateWidget(covariant ChatShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      unawaited(widget.controller.start());
    }
  }

  @override
  void dispose() {
    if (widget.disposeController) widget.controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final state = widget.controller.state;
        final firstPrompt = state.messages
            .where((m) => m.role == ChatRole.user)
            .map((m) => m.text)
            .firstOrNull;
        return Scaffold(
          backgroundColor: DshPalette.paper,
          drawer: SessionDrawer(
            currentTitle: firstPrompt,
            isFixture: widget.controller.transport.isFixture,
          ),
          body: SafeArea(
            child: Column(
              children: [
                _TopBar(
                  controller: widget.controller,
                  title: firstPrompt,
                  onOpenWebFallback: widget.onOpenWebFallback,
                ),
                if (state.status == ChatStatus.reconnecting ||
                    state.status == ChatStatus.error)
                  _ConnectionBanner(
                    controller: widget.controller,
                    status: state.status,
                    detail: state.error,
                  ),
                Expanded(child: MessageList(controller: widget.controller)),
                Composer(controller: widget.controller),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.controller,
    required this.title,
    this.onOpenWebFallback,
  });
  final ChatController controller;
  final String? title;
  final VoidCallback? onOpenWebFallback;
  @override
  Widget build(BuildContext context) {
    final status = controller.state.status;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 4),
      child: Row(
        children: [
          IconButton(
            tooltip: '打开会话抽屉',
            onPressed: () => Scaffold.of(context).openDrawer(),
            icon: const Icon(
              Icons.menu_rounded,
              size: 24,
              color: DshPalette.inkSoft,
            ),
          ),
          const SizedBox(width: 12),
          const Text(
            'DSH',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
          ),
          const Spacer(),
          Tooltip(
            message: controller.transport.isFixture ? 'fixture 模式' : 'engine',
            child: Text(
              _statusLabel(status),
              style: const TextStyle(color: DshPalette.inkSoft, fontSize: 12),
            ),
          ),
          const SizedBox(width: 8),
          IconButton(
            key: const ValueKey('web-fallback'),
            tooltip: '打开 Web 版',
            onPressed: onOpenWebFallback,
            icon: const Icon(
              Icons.open_in_new_rounded,
              size: 21,
              color: DshPalette.inkSoft,
            ),
          ),
        ],
      ),
    );
  }

  static String _statusLabel(ChatStatus status) => switch (status) {
    ChatStatus.connecting => '连接中',
    ChatStatus.ready => '已连接',
    ChatStatus.streaming => '正在回复',
    ChatStatus.waitingAction => '等待确认',
    ChatStatus.reconnecting => '重连中',
    ChatStatus.error => '连接异常',
  };
}

class _ConnectionBanner extends StatelessWidget {
  const _ConnectionBanner({
    required this.controller,
    required this.status,
    this.detail,
  });

  final ChatController controller;
  final ChatStatus status;
  final String? detail;

  @override
  Widget build(BuildContext context) {
    final isError = status == ChatStatus.error;
    final tone = isError ? DshPalette.bad : DshPalette.warn;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 2, 12, 4),
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        decoration: BoxDecoration(
          color: tone.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: tone.withValues(alpha: 0.45)),
        ),
        child: Row(
          children: [
            Icon(
              isError ? Icons.error_outline_rounded : Icons.wifi_off_rounded,
              size: 15,
              color: tone,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    isError ? '引擎连接失败' : '连接已中断',
                    style: const TextStyle(
                      color: DshPalette.ink,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (isError && detail != null && detail!.isNotEmpty)
                    Text(
                      detail!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: DshPalette.inkSoft,
                        fontSize: 12,
                      ),
                    ),
                ],
              ),
            ),
            TextButton(
              key: const ValueKey('connection-retry'),
              onPressed: controller.reconnect,
              child: const Text('重试'),
            ),
          ],
        ),
      ),
    );
  }
}

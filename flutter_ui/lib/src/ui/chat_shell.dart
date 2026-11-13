import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../state/chat_controller.dart';

import 'message_list.dart';
import 'session_drawer.dart';
import 'composer.dart';

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
        return Scaffold(
          drawer: const SessionDrawer(),
          body: SafeArea(
            child: Column(
              children: [
                _Header(
                  controller: widget.controller,
                  onOpenWebFallback: widget.onOpenWebFallback,
                ),
                if (state.status == ChatStatus.reconnecting ||
                    state.status == ChatStatus.error)
                  _ConnectionBanner(
                    controller: widget.controller,
                    status: state.status,
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

class _Header extends StatelessWidget {
  const _Header({required this.controller, this.onOpenWebFallback});

  final ChatController controller;
  final VoidCallback? onOpenWebFallback;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: const BoxDecoration(
        color: Color(0xFFF8F8FB),
        border: Border(bottom: BorderSide(color: Color(0xFFE9E7F0))),
      ),
      child: Row(
        children: [
          Builder(
            builder: (context) => IconButton(
              tooltip: '打开会话抽屉',
              icon: const Icon(Icons.menu_rounded),
              onPressed: () => Scaffold.of(context).openDrawer(),
            ),
          ),
          Container(
            width: 34,
            height: 34,
            decoration: BoxDecoration(
              color: const Color(0xFF6557D8),
              borderRadius: BorderRadius.circular(11),
            ),
            child: const Icon(
              Icons.auto_awesome,
              color: Colors.white,
              size: 19,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'DSH',
                  style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                ),
                Text(
                  '独立执行工作台 · ' + _statusLabel(state.status),
                  style: const TextStyle(
                    color: Color(0xFF757180),
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          _StatusChip(status: state.status),
          const SizedBox(width: 4),
          Text(
            controller.transport.isFixture ? 'fixture' : 'engine',
            style: const TextStyle(color: Color(0xFF757180), fontSize: 11),
          ),
          IconButton(
            key: const ValueKey('web-fallback'),
            tooltip: '打开 Web 版',
            icon: const Icon(Icons.open_in_browser_outlined),
            onPressed: onOpenWebFallback,
          ),
        ],
      ),
    );
  }

  String _statusLabel(ChatStatus status) => switch (status) {
    ChatStatus.connecting => '连接中',
    ChatStatus.ready => '已连接',
    ChatStatus.streaming => '执行中',
    ChatStatus.waitingAction => '等待确认',
    ChatStatus.reconnecting => '重连中',
    ChatStatus.error => '连接异常',
  };
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status});

  final ChatStatus status;

  @override
  Widget build(BuildContext context) {
    final active = status == ChatStatus.ready || status == ChatStatus.streaming;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: active ? const Color(0xFFE5F3EC) : const Color(0xFFF0EEF6),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(
              color: active ? const Color(0xFF2B8064) : const Color(0xFF8E889D),
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 5),
          Text(
            active ? '在线' : '离线',
            style: TextStyle(
              fontSize: 11,
              color: active ? const Color(0xFF2B8064) : const Color(0xFF757180),
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

class _ConnectionBanner extends StatelessWidget {
  const _ConnectionBanner({required this.controller, required this.status});

  final ChatController controller;
  final ChatStatus status;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0xFFFFF2E7),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 9),
        child: Row(
          children: [
            const Icon(
              Icons.wifi_off_rounded,
              size: 18,
              color: Color(0xFF9B5E30),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                status == ChatStatus.error ? '引擎连接失败' : '连接已中断',
                style: const TextStyle(color: Color(0xFF75421E), fontSize: 13),
              ),
            ),
            TextButton(
              key: const ValueKey('connection-retry'),
              onPressed: controller.reconnect,
              child: const Text('重新连接'),
            ),
          ],
        ),
      ),
    );
  }
}

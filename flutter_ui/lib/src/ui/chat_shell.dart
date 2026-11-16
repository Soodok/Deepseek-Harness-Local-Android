import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/chat_model.dart';
import '../bridge/host_bridge.dart';
import 'glass_panel.dart';
import 'overflow_title.dart';
import 'runtime_panel.dart';
import 'subagent_panel.dart';
import '../state/chat_controller.dart';
import 'settings/settings_home.dart';
import '../theme/dsh_theme.dart';
import 'composer.dart';
import 'message_list.dart';
import 'session_drawer.dart';
import '../transport/fixture_transport.dart';

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
    this.onOpenEngineSessions,
    this.onBack,
    this.onNewSession,
    this.hostInfo,
    this.disposeController = false,
  });

  final ChatController controller;
  final VoidCallback? onOpenWebFallback;
  final VoidCallback? onOpenEngineSessions;
  final VoidCallback? onBack;
  final VoidCallback? onNewSession;
  final bool disposeController;
  final HostInfo? hostInfo;

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
          backgroundColor: DshPalette.of(context).paper,
          drawer: SessionDrawer(
            currentTitle: firstPrompt,
            onNewSession: widget.onNewSession,
            onOpenSettings: () => showSettingsHome(context, widget.controller),
            onOpenEngineSessions: widget.onOpenEngineSessions,
            onShowcase:
                widget.controller.transport is FixtureTransport &&
                    state.status == ChatStatus.ready
                ? (widget.controller.transport as FixtureTransport).emitShowcase
                : null,
            isFixture: widget.controller.transport.isFixture,
          ),
          body: SafeArea(
            child: LayoutBuilder(
              builder: (context, viewport) => Column(
                children: [
                  _TopBar(
                    controller: widget.controller,
                    hostInfo: widget.hostInfo,
                    onBack: widget.onBack,
                    onOpenWebFallback: widget.onOpenWebFallback,
                  ),
                  if (state.status == ChatStatus.reconnecting ||
                      state.status == ChatStatus.error ||
                      state.error != null)
                    _ConnectionBanner(
                      controller: widget.controller,
                      status: state.status,
                      detail: state.error,
                    ),
                  Expanded(
                    child: LayoutBuilder(
                      builder: (context, remaining) => Column(
                        children: [
                          Expanded(
                            child: MessageList(controller: widget.controller),
                          ),
                          Composer(
                            controller: widget.controller,
                            availableHeight: remaining.maxHeight,
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
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
    this.hostInfo,
    this.onBack,
    this.onOpenWebFallback,
  });
  final ChatController controller;
  final HostInfo? hostInfo;
  final VoidCallback? onBack;
  final VoidCallback? onOpenWebFallback;
  @override
  Widget build(BuildContext context) {
    final host = hostInfo ?? HostInfo.preview;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
          child: GlassPanel(
            key: const ValueKey('floating-header'),
            radius: 30,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 5),
              child: Row(
                children: [
                  IconButton(
                    tooltip: onBack == null ? '打开会话抽屉' : '返回会话列表',
                    onPressed:
                        onBack ?? () => Scaffold.of(context).openDrawer(),
                    icon: Icon(
                      onBack == null
                          ? Icons.menu_rounded
                          : Icons.arrow_back_rounded,
                      size: 23,
                      color: DshPalette.of(context).inkSoft,
                    ),
                  ),
                  const SizedBox(width: 4),
                  const Expanded(
                    child: OverflowTitle(
                      text: 'DSH',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                  IconButton(
                    key: const ValueKey('subagent-selector'),
                    tooltip: '子代理',
                    onPressed: () =>
                        showSubagentPanel(context, controller: controller),
                    icon: Icon(
                      Icons.people_alt_outlined,
                      size: 21,
                      color: DshPalette.of(context).ice,
                    ),
                  ),
                  ConstrainedBox(
                    constraints: BoxConstraints(
                      maxWidth: MediaQuery.sizeOf(context).width * .48,
                    ),
                    child: Tooltip(
                      message: controller.transport.isFixture
                          ? 'fixture 模式'
                          : 'engine',
                      child: KeyedSubtree(
                        key: const ValueKey('web-fallback'),
                        child: TextButton(
                          key: const ValueKey('runtime-selector'),
                          onPressed: () => showRuntimePanel(
                            context,
                            hostInfo: hostInfo,
                            isFixture: controller.transport.isFixture,
                            onOpenWebFallback: onOpenWebFallback,
                          ),
                          style: TextButton.styleFrom(
                            padding: const EdgeInsets.symmetric(horizontal: 10),
                            minimumSize: const Size(0, 48),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                host.runtimeMode.startsWith('ssh_')
                                    ? Icons.dns_outlined
                                    : Icons.terminal_rounded,
                                size: 18,
                                color: DshPalette.of(context).inkSoft,
                              ),
                              const SizedBox(width: 8),
                              Flexible(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      host.runtimeLabel,
                                      textWidthBasis:
                                          TextWidthBasis.longestLine,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontSize: 13,
                                        color: DshPalette.of(context).ink,
                                      ),
                                    ),
                                    Text(
                                      (controller.transport.isFixture
                                              ? '演示 · '
                                              : '') +
                                          _statusLabel(controller.state.status),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontSize: 10,
                                        color: DshPalette.of(context).inkFaint,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(width: 5),
                              Icon(
                                Icons.expand_more_rounded,
                                size: 15,
                                color: DshPalette.of(context).inkSoft,
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
          ),
        ),
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
    final isConfiguration = controller.configurationError != null;
    final isError = status == ChatStatus.error || isConfiguration;
    final tone = isError
        ? DshPalette.of(context).bad
        : DshPalette.of(context).warn;
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
                    isConfiguration
                        ? '请确认会话权限'
                        : isError
                        ? '引擎连接失败'
                        : '连接已中断',
                    style: TextStyle(
                      color: DshPalette.of(context).ink,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (isError && detail != null && detail!.isNotEmpty)
                    Text(
                      detail!,
                      maxLines: isConfiguration ? 4 : 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: DshPalette.of(context).inkSoft,
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

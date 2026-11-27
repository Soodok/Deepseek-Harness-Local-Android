import 'dart:async';
import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

import '../model/engine_agent_preset.dart';
import '../model/engine_workspace.dart';
import 'native_workspace_picker.dart';
import '../state/chat_controller.dart';
import '../state/message_actions.dart';
import '../theme/dsh_theme.dart';
import 'action_cards.dart';
import 'conversation_flow.dart';
import 'file_delivery_card.dart';
import 'glass_panel.dart';

class MessageList extends StatelessWidget {
  const MessageList({
    super.key,
    required this.controller,
    this.actions,
    this.editingMessageId,
    this.onFileTap,
    this.onFileShare,
    this.padding,
    this.onStarter,
    this.agentPresets = const [],
    this.selectedAgentPresetId,
    this.onSelectAgentPreset,
    this.workspaceOptions = const [],
    this.selectedWorkspaceId,
    this.onSelectWorkspace,
    this.onSaveDefaultWorkspace,
    this.onClearDefaultWorkspace,
    this.onRetryWorkspace,
    this.workspaceStatus,
    this.now = DateTime.now,
  });
  final ChatController controller;

  /// Bubble actions, or null on a surface with no Host records behind it.
  final MessageActionBindings? actions;
  final String? editingMessageId;
  final FileDeliveryTap? onFileTap;
  final FileDeliveryShare? onFileShare;

  /// Room reserved for floating chrome; forwarded straight to the list.
  final EdgeInsets? padding;
  final ValueChanged<String>? onStarter;
  final List<EngineAgentPreset> agentPresets;
  final String? selectedAgentPresetId;
  final Future<bool> Function(String)? onSelectAgentPreset;
  final List<EngineWorkspace> workspaceOptions;
  final String? selectedWorkspaceId;
  final Future<bool> Function(String?)? onSelectWorkspace;
  final Future<bool> Function()? onSaveDefaultWorkspace,
      onClearDefaultWorkspace;
  final VoidCallback? onRetryWorkspace;
  final String? workspaceStatus;
  final DateTime Function() now;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final approval = state.approval;
    return ConversationFlow(
      conversationIdentity: controller,
      messages: state.messages,
      tools: state.tools,
      hideDockContent: true,
      historyPageSize: 48,
      // Confirmations with a one-shot decision must be immediately reachable.
      pendingApproval: approval != null && controller.canRejectApproval
          ? (state.generation, approval.requestId)
          : null,
      actions: actions,
      editingMessageId: editingMessageId,
      onFileTap: onFileTap,
      onFileShare: onFileShare,
      padding: padding,
      empty:
          state.approval == null && state.question == null && !state.needsResync
          ? _EmptyConversation(
              padding: padding ?? EdgeInsets.zero,
              onStarter: onStarter,
              presets: agentPresets,
              selected: selectedAgentPresetId,
              onSelect: onSelectAgentPreset,
              workspaceOptions: workspaceOptions,
              selectedWorkspaceId: selectedWorkspaceId,
              onSelectWorkspace: onSelectWorkspace,
              onSaveDefaultWorkspace: onSaveDefaultWorkspace,
              onClearDefaultWorkspace: onClearDefaultWorkspace,
              onRetryWorkspace: onRetryWorkspace,
              workspaceStatus: workspaceStatus,
              now: now,
            )
          : null,
      trailing: [
        if (approval != null)
          Padding(
            key: ValueKey((state.generation, approval.requestId)),
            padding: const EdgeInsets.only(top: 12),
            child: ApprovalCard(
              request: approval,
              busy: controller.actionBusy(approval.requestId),
              onApprove: () => controller.approve(approval.requestId),
              onStop: controller.stop,
              onReject: controller.canRejectApproval
                  ? () => controller.rejectApproval(approval.requestId)
                  : null,
            ),
          ),
        if (state.needsResync)
          Padding(
            padding: const EdgeInsets.only(top: 16),
            child: Text(
              '正在重新同步消息…',
              style: TextStyle(
                color: DshPalette.of(context).inkSoft,
                fontSize: 13,
              ),
            ),
          ),
      ],
    );
  }
}

const _greetings = [
  '夜深了，想聊点什么？',
  '把眼前的事理一理',
  '还有什么想弄明白？',
  '有个想法要记下来吗？',
  '安静的时候，适合整理思路',
  '给新的一天起个头',
  '早安，今天想做什么？',
  '从一件小事开始',
  '今天想解决什么？',
  '来推敲一个新想法',
  '现在适合专心做一件事',
  '上午还有什么要处理？',
  '午安，想轻松聊一会儿吗？',
  '下午从哪里开始？',
  '把思路变成一个计划',
  '有个问题值得深挖吗？',
  '离收工还有一会儿',
  '把手头的事收个尾',
  '傍晚好，今天有什么新发现？',
  '想把今天的点子整理一下吗？',
  '晚上好，聊聊接下来的计划',
  '从一个好问题开始',
  '把未完的想法接起来',
  '睡前有件事想弄明白吗？',
];

class _EmptyConversation extends StatelessWidget {
  const _EmptyConversation({
    required this.padding,
    required this.onStarter,
    required this.presets,
    required this.selected,
    required this.onSelect,
    required this.workspaceOptions,
    required this.selectedWorkspaceId,
    required this.onSelectWorkspace,
    required this.onSaveDefaultWorkspace,
    required this.onClearDefaultWorkspace,
    required this.onRetryWorkspace,
    required this.workspaceStatus,
    required this.now,
  });

  final EdgeInsets padding;
  final ValueChanged<String>? onStarter;
  final List<EngineAgentPreset> presets;
  final String? selected;
  final Future<bool> Function(String)? onSelect;
  final List<EngineWorkspace> workspaceOptions;
  final String? selectedWorkspaceId;
  final Future<bool> Function(String?)? onSelectWorkspace;
  final Future<bool> Function()? onSaveDefaultWorkspace,
      onClearDefaultWorkspace;
  final VoidCallback? onRetryWorkspace;
  final String? workspaceStatus;
  final DateTime Function() now;

  static const _starters = [
    ('code', Icons.code_rounded, '编写代码', '帮我编写或修改代码。先确认目标和已有代码，再提出最小可验证的改动。'),
    ('chat', Icons.chat_bubble_outline_rounded, '轻松聊天', '我们轻松聊一会儿吧。'),
    ('plan', Icons.checklist_rounded, '整理计划', '帮我把这件事拆成几个可执行的步骤：'),
    (
      'access',
      Icons.accessibility_new_rounded,
      '无障碍操作',
      '我想用无障碍功能完成手机上的一项操作。先帮我确认目标和所需权限。',
    ),
  ];

  @override
  Widget build(BuildContext context) {
    final activePreset = presets
        .where((preset) => preset.id == selected)
        .firstOrNull;
    return LayoutBuilder(
      builder: (context, bounds) {
        final availableHeight = math.max(
          0.0,
          bounds.maxHeight - padding.vertical,
        );
        final widthLimit = math.max(
          0.0,
          bounds.maxWidth - padding.horizontal - 36,
        );
        final labelSize = MediaQuery.textScalerOf(context).scale(16);
        final largeText = labelSize > 16 * 1.3;
        final hasMode = presets.isNotEmpty || selected != null;
        final hasWorkspace = onSelectWorkspace != null;
        final contentWidth = math.min(420.0, widthLimit);
        final stackPickers =
            hasMode && hasWorkspace && (largeText || contentWidth < 264);
        Widget pickerBar(List<Widget> pickers) {
          if (!hasMode || !hasWorkspace || stackPickers) {
            return Wrap(
              alignment: WrapAlignment.center,
              spacing: 8,
              runSpacing: 8,
              children: pickers,
            );
          }
          return Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Flexible(flex: 11, child: pickers[0]),
              const SizedBox(width: 8),
              Flexible(flex: 9, child: pickers[1]),
            ],
          );
        }

        final greetingLineHeight =
            MediaQuery.textScalerOf(context).scale(24) * 1.25;
        // Reserve two greeting lines, brand, grid gaps, picker and scroll padding.
        final chromeHeight =
            16 +
            36 +
            16 +
            2 * greetingLineHeight +
            (onStarter == null ? 0 : 12 + 16) +
            (hasMode || hasWorkspace ? 8 + 48 : 0) +
            (stackPickers ? 8 + 48 : 0);
        final preferredSize = math.min(
          148.0,
          (availableHeight - chromeHeight) / 2,
        );
        const regularPadding = 16.0;
        const regularIcon = 28.0;
        const labelGap = 8.0;
        final regularMin =
            regularPadding * 2 + regularIcon + labelGap + labelSize * 2;
        // A short viewport should shed padding before hiding the mode pill.
        final tight =
            largeText ||
            bounds.maxWidth < 300 ||
            math.min(preferredSize, (widthLimit - 8) / 2) < regularMin;
        final starterPadding = tight ? 8.0 : regularPadding;
        final starterIcon = tight ? 24.0 : regularIcon;
        final contentHeight =
            starterPadding * 2 +
            starterIcon +
            labelGap +
            labelSize * (largeText ? 4 : 2);
        final cardLimit = math.max(
          math.max(80.0, contentHeight),
          preferredSize,
        );
        final gridWidth = math.min(contentWidth, cardLimit * 2 + 8);
        final cardSize = math.max(0.0, (gridWidth - 8) / 2);
    // 键盘弹出后可用高度骤减，卡片会被压到 label 放不下两行的地步（实测：
    // 80dp 卡片里「无障碍操作」折成两行，「作」被裁掉一半）。图标是装饰、文案是信息，
    // 空间不够时先让图标让位，别去截断用户要读的字。
    final labelNeeds =
        starterPadding * 2 + labelGap + labelSize * 2;
    // 留一点余量：正好等于「图标 + 两行字 + 内边距」时，卡片实际会被字宽挤成三行，
    // 末行被裁掉（设备实测「无障碍操作」只剩「无障碍操」）。宁可少个图标。
    final iconFits = cardSize >= labelNeeds + starterIcon + 8;
        return Padding(
          padding: padding,
          child: SingleChildScrollView(
            key: const ValueKey('welcome-scroll-viewport'),
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: math.max(0.0, availableHeight - 16),
              ),
              child: Center(
                child: ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: contentWidth),
                  child: Padding(
                    padding: EdgeInsets.only(
                      top: math.min(
                        32.0,
                        math.max(
                              0.0,
                              availableHeight - (cardSize * 2 + chromeHeight),
                            ) /
                            2,
                      ),
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Center(
                          child: Semantics(
                            label: 'DeepSeek',
                            child: SizedBox(
                              key: const ValueKey('welcome-brand'),
                              width: 48,
                              height: 36,
                              child: CustomPaint(
                                painter: _DeepSeekWhalePainter(
                                  DshPalette.of(context).sendBlue,
                                ),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Center(
                          child: ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 280),
                            child: _HourlyGreeting(now: now),
                          ),
                        ),
                        if (onStarter != null) ...[
                          const SizedBox(height: 12),
                          for (var i = 0; i < _starters.length; i += 2)
                            Align(
                              alignment: Alignment.center,
                              child: SizedBox(
                                width: gridWidth,
                                child: Padding(
                                  padding: const EdgeInsets.only(bottom: 8),
                                  child: Row(
                                    children: [
                                      Expanded(
                                        child: _starter(
                                          context,
                                          _starters[i],
                                          cardSize,
                                          padding: starterPadding,
                                          iconSize: starterIcon,
                                          showIcon: iconFits,
                                        ),
                                      ),
                                      const SizedBox(width: 8),
                                      Expanded(
                                        child: _starter(
                                          context,
                                          _starters[i + 1],
                                          cardSize,
                                          padding: starterPadding,
                                          iconSize: starterIcon,
                                          showIcon: iconFits,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                        ],
                        if (hasMode || hasWorkspace) ...[
                          const SizedBox(height: 8),
                          Align(
                            alignment: Alignment.center,
                            child: pickerBar([
                              if (hasMode)
                                PopupMenuButton<String>(
                                  key: const ValueKey('welcome-agent-preset'),
                                  enabled:
                                      onSelect != null &&
                                      presets.any((p) => p.broken == null),
                                  tooltip: '选择对话模式',
                                  color: Colors.transparent,
                                  surfaceTintColor: Colors.transparent,
                                  shadowColor: Colors.transparent,
                                  elevation: 0,
                                  padding: EdgeInsets.zero,
                                  position: PopupMenuPosition.under,
                                  onSelected: (id) async {
                                    if (await onSelect?.call(id) == true ||
                                        !context.mounted)
                                      return;
                                    ScaffoldMessenger.maybeOf(
                                      context,
                                    )?.showSnackBar(
                                      const SnackBar(content: Text('未能切换对话模式')),
                                    );
                                  },
                                  itemBuilder: (_) => [
                                    _WelcomePresetMenu(
                                      presets: presets,
                                      selected: selected,
                                    ),
                                  ],
                                  child: Container(
                                    key: const ValueKey(
                                      'welcome-agent-capsule',
                                    ),
                                    constraints: const BoxConstraints(
                                      minHeight: 48,
                                      maxWidth: 320,
                                    ),
                                    padding: EdgeInsets.symmetric(
                                      vertical: 10,
                                      horizontal: hasWorkspace ? 12 : 16,
                                    ),
                                    decoration: BoxDecoration(
                                      color: DshPalette.of(context).surface,
                                      border: Border.all(
                                        color: DshPalette.of(context).line,
                                      ),
                                      borderRadius: BorderRadius.circular(24),
                                    ),
                                    child: Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Flexible(
                                          child: Text(
                                            '对话模式：' +
                                                (activePreset?.label ??
                                                    selected ??
                                                    '未选择'),
                                            maxLines:
                                                hasWorkspace && !stackPickers
                                                ? 1
                                                : 2,
                                            softWrap:
                                                !hasWorkspace || stackPickers,
                                            overflow: TextOverflow.ellipsis,
                                          ),
                                        ),
                                        const SizedBox(width: 8),
                                        const Icon(
                                          Icons.keyboard_arrow_down_rounded,
                                          size: 20,
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              if (hasWorkspace) _workspacePicker(context),
                            ]),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _workspacePicker(BuildContext context) => NativeWorkspacePicker(
    workspaces: workspaceOptions,
    selected: selectedWorkspaceId,
    onSelect: onSelectWorkspace!,
    onSaveDefault: onSaveDefaultWorkspace,
    onClearDefault: onClearDefaultWorkspace,
    onRetry: onRetryWorkspace,
    status: workspaceStatus,
  );

  Widget _starter(
    BuildContext context,
    (String, IconData, String, String) item,
    double cardSize, {
    required double padding,
    required double iconSize,
    bool showIcon = true,
  }) {
    final palette = DshPalette.of(context);
    final color = switch (item.$1) {
      'code' => palette.terminal,
      'chat' => palette.thought,
      'plan' => palette.edit,
      _ => palette.warn,
    };
    return SizedBox(
      height: cardSize,
      child: OutlinedButton(
        key: ValueKey('welcome-suggestion-' + item.$1),
        style: OutlinedButton.styleFrom(
          minimumSize: Size.zero,
          padding: EdgeInsets.all(padding),
          foregroundColor: palette.ink,
          backgroundColor: palette.surface,
          side: BorderSide(color: palette.line),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
        onPressed: () => onStarter?.call(item.$4),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            if (showIcon) ...[
              Icon(item.$2, size: iconSize, color: color),
              const SizedBox(height: 8),
            ],
            Flexible(
              child: Text(
                item.$3,
                textAlign: TextAlign.center,
                maxLines: 3,
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// Whale path adapted from app/src/main/res/drawable/ic_launcher_foreground.xml.
class _DeepSeekWhalePainter extends CustomPainter {
  const _DeepSeekWhalePainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 56.2, size.height / 41.36);
    final path = Path()
      ..moveTo(55.6128, 3.4712)
      ..relativeCubicTo(-.5953, -.2917, -.8517, .2642, -1.1998, .5466)
      ..relativeCubicTo(-.1191, .0911, -.2198, .2095, -.3206, .3188)
      ..relativeCubicTo(-.8701, .9292, -1.8867, 1.5398, -3.2148, 1.4668)
      ..relativeCubicTo(-1.9417, -.1094, -3.5995, .5012, -5.065, 1.9863)
      ..relativeCubicTo(-.3114, -1.8313, -1.3463, -2.9248, -2.9217, -3.6262)
      ..relativeCubicTo(-.8242, -.3645, -1.6577, -.729, -2.2348, -1.5217)
      ..relativeCubicTo(-.403, -.5647, -.5129, -1.1934, -.7144, -1.813)
      ..relativeCubicTo(-.1283, -.3735, -.2565, -.7563, -.687, -.8201)
      ..relativeCubicTo(-.4671, -.0728, -.6503, .3188, -.8335, .647)
      ..relativeCubicTo(-.7327, 1.3394, -1.0166, 2.8154, -.9892, 4.3096)
      ..relativeCubicTo(.0641, 3.3621, 1.4838, 6.0406, 4.3047, 7.9449)
      ..relativeCubicTo(.3206, .2187, .403, .4372, .3023, .7563)
      ..relativeCubicTo(-.1924, .656, -.4214, 1.2937, -.6228, 1.9497)
      ..relativeCubicTo(-.1283, .4192, -.3207, .5103, -.7694, .3279)
      ..relativeCubicTo(-1.5479, -.6467, -2.8852, -1.6035, -4.0667, -2.7605)
      ..relativeCubicTo(-2.0058, -1.9407, -3.8193, -4.0818, -6.0815, -5.7583)
      ..relativeCubicTo(-.5312, -.3918, -1.0625, -.7561, -1.6121, -1.1025)
      ..relativeCubicTo(-2.3081, -2.2412, .3023, -4.0818, .9068, -4.3003)
      ..relativeCubicTo(.6319, -.2278, .2198, -1.0115, -1.8227, -1.0022)
      ..relativeCubicTo(-2.0425, .009, -3.9109, .6924, -6.2922, 1.6035)
      ..relativeCubicTo(-.348, .1367, -.7145, .2368, -1.09, .3188)
      ..relativeCubicTo(-2.1615, -.4099, -4.4055, -.5012, -6.7502, -.2368)
      ..relativeCubicTo(-4.4147, .4919, -7.9408, 2.5784, -10.5328, 6.1409)
      ..cubicTo(.1914, 13.1289, -.5413, 17.9941, .3563, 23.0691)
      ..relativeCubicTo(.9434, 5.3481, 3.6727, 9.7761, 7.8676, 13.2385)
      ..relativeCubicTo(4.3506, 3.5896, 9.3606, 5.3481, 15.0758, 5.011)
      ..relativeCubicTo(3.4713, -.2004, 7.3364, -.665, 11.6961, -4.355)
      ..relativeCubicTo(1.099, .5467, 2.2531, .7652, 4.1674, .9292)
      ..relativeCubicTo(1.4746, .1367, 2.8943, -.0728, 3.9933, -.3005)
      ..relativeCubicTo(1.7219, -.3645, 1.6029, -1.959, .9801, -2.2505)
      ..relativeCubicTo(-5.0466, -2.3506, -3.9385, -1.394, -4.9459, -2.1685)
      ..relativeCubicTo(2.5645, -3.0339, 6.4297, -6.1865, 7.9409, -16.4001)
      ..relativeCubicTo(.119, -.8108, .0183, -1.3211, 0, -1.9771)
      ..relativeCubicTo(-.0092, -.4008, .0824, -.5556, .5404, -.6013)
      ..relativeCubicTo(1.2639, -.1458, 2.4912, -.4919, 3.6178, -1.1115)
      ..relativeCubicTo(3.2698, -1.7857, 4.5886, -4.7195, 4.9, -8.2364)
      ..relativeCubicTo(.0459, -.5376, -.0091, -1.0935, -.577, -1.3757)
      ..close()
      ..moveTo(27.119, 35.123)
      ..relativeCubicTo(-4.8909, -3.8447, -7.263, -5.1113, -8.2431, -5.0566)
      ..relativeCubicTo(-.9159, .0547, -.751, 1.1025, -.5496, 1.7859)
      ..relativeCubicTo(.2107, .6741, .4855, 1.1389, .8701, 1.731)
      ..relativeCubicTo(.2656, .3918, .4489, .9748, -.2655, 1.4123)
      ..relativeCubicTo(-1.5754, .9749, -4.314, -.3281, -4.4423, -.3918)
      ..relativeCubicTo(-3.1872, -1.877, -5.8525, -4.3553, -7.7302, -7.7444)
      ..relativeCubicTo(-1.8135, -3.262, -2.8667, -6.7605, -3.0408, -10.4961)
      ..relativeCubicTo(-.0458, -.9019, .2198, -1.221, 1.1174, -1.3848)
      ..relativeCubicTo(1.1815, -.2187, 2.3997, -.2644, 3.5812, -.0913)
      ..relativeCubicTo(4.9918, .729, 9.2415, 2.9612, 12.8043, 6.4963)
      ..relativeCubicTo(2.0333, 2.0135, 3.572, 4.419, 5.1566, 6.7696)
      ..relativeCubicTo(1.6852, 2.4963, 3.4987, 4.8745, 5.8068, 6.8242)
      ..relativeCubicTo(.8151, .6833, 1.4654, 1.2026, 2.0882, 1.5854)
      ..relativeCubicTo(-1.8775, .2095, -5.01, .2552, -7.1532, -1.4397)
      ..close()
      ..moveTo(29.4637, 20.0442)
      ..relativeCubicTo(0, -.4009, .3206, -.7197, .7237, -.7197)
      ..relativeCubicTo(.0916, 0, .174, .018, .2473, .0453)
      ..relativeCubicTo(.1008, .0366, .1924, .0913, .2656, .1731)
      ..relativeCubicTo(.1283, .1277, .2015, .3098, .2015, .5012)
      ..relativeCubicTo(0, .4009, -.3205, .7197, -.7234, .7197)
      ..relativeCubicTo(-.4029, 0, -.7145, -.3188, -.7145, -.7197)
      ..close()
      ..moveTo(36.7452, 23.7798)
      ..relativeCubicTo(-.4671, .1914, -.9342, .3552, -1.383, .3735)
      ..relativeCubicTo(-.6961, .0364, -1.4563, -.2461, -1.8684, -.5923)
      ..relativeCubicTo(-.6411, -.5376, -1.0991, -.8381, -1.2914, -1.7766)
      ..relativeCubicTo(-.0825, -.4009, -.0367, -1.0205, .0367, -1.3757)
      ..relativeCubicTo(.1648, -.7654, -.0184, -1.2573, -.5587, -1.7039)
      ..relativeCubicTo(-.4397, -.3645, -.9984, -.4646, -1.6121, -.4646)
      ..relativeCubicTo(-.229, 0, -.4395, -.1003, -.5953, -.1823)
      ..relativeCubicTo(-.2565, -.1275, -.467, -.4464, -.2656, -.8382)
      ..relativeCubicTo(.0641, -.1274, .3756, -.4373, .4489, -.4919)
      ..relativeCubicTo(.8335, -.4739, 1.7952, -.3189, 2.6836, .0364)
      ..relativeCubicTo(.8244, .3371, 1.4472, .9567, 2.3447, 1.8313)
      ..relativeCubicTo(.9159, 1.0568, 1.0807, 1.3486, 1.6028, 2.1411)
      ..relativeCubicTo(.4123, .6196, .7878, 1.2573, 1.0442, 1.9863)
      ..relativeCubicTo(.1557, .4556, -.0458, .8291, -.5862, 1.0569)
      ..close();
    canvas.drawPath(path, Paint()..color = color);
  }

  @override
  bool shouldRepaint(covariant _DeepSeekWhalePainter oldDelegate) =>
      oldDelegate.color != color;
}

class _WelcomePresetMenu extends PopupMenuEntry<String> {
  const _WelcomePresetMenu({required this.presets, required this.selected});

  final List<EngineAgentPreset> presets;
  final String? selected;

  @override
  double get height => presets.length * kMinInteractiveDimension;

  @override
  bool represents(String? value) => presets.any((preset) => preset.id == value);

  @override
  State<_WelcomePresetMenu> createState() => _WelcomePresetMenuState();
}

class _WelcomePresetMenuState extends State<_WelcomePresetMenu> {
  @override
  Widget build(BuildContext context) => GlassPanel(
    key: const ValueKey('welcome-agent-glass'),
    radius: 16,
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final preset in widget.presets)
          PopupMenuItem<String>(
            value: preset.id,
            enabled: preset.broken == null,
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    preset.label,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (preset.id == widget.selected)
                  const Icon(Icons.check_rounded, size: 18),
              ],
            ),
          ),
      ],
    ),
  );
}

class _HourlyGreeting extends StatefulWidget {
  const _HourlyGreeting({required this.now});

  final DateTime Function() now;

  @override
  State<_HourlyGreeting> createState() => _HourlyGreetingState();
}

class _HourlyGreetingState extends State<_HourlyGreeting>
    with WidgetsBindingObserver {
  Timer? _timer;
  late int _hour;
  bool _foreground = false;

  @override
  void initState() {
    super.initState();
    final binding = WidgetsBinding.instance;
    binding.addObserver(this);
    _foreground =
        binding.lifecycleState == null ||
        binding.lifecycleState == AppLifecycleState.resumed;
    _hour = widget.now().hour;
    _refresh();
  }

  @override
  void didUpdateWidget(covariant _HourlyGreeting oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.now != oldWidget.now) _refresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _refresh();
  }

  void _refresh() {
    _timer?.cancel();
    _timer = null;
    if (!_foreground) return;
    final now = widget.now();
    if (_hour != now.hour) setState(() => _hour = now.hour);
    final intoHour = Duration(
      minutes: now.minute,
      seconds: now.second,
      milliseconds: now.millisecond,
      microseconds: now.microsecond,
    );
    _timer = Timer(const Duration(hours: 1) - intoHour, _refresh);
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Text(
    _greetings[_hour],
    key: const ValueKey('welcome-greeting'),
    textAlign: TextAlign.center,
    style: TextStyle(
      fontSize: 24,
      height: 1.25,
      color: DshPalette.of(context).ink,
    ),
  );
}

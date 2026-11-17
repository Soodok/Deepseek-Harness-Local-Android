import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';

/// The two icons under a person's own bubble: copy and retract.
///
/// They appear on the bubble the person can actually act on, so the row is
/// shown for durable messages only — a message the Host has not recorded has
/// no seq to retract and no stable text to copy.
class UserMessageActions extends StatelessWidget {
  const UserMessageActions({
    super.key,
    required this.onCopy,
    required this.onRetract,
    this.copied = false,
    this.keySuffix = '',
  });

  final VoidCallback onCopy;
  final VoidCallback? onRetract;
  final bool copied;

  /// Distinguishes the rows of one transcript; the empty default keeps the
  /// single-row case (and its tests) readable.
  final String keySuffix;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _bar(
          context,
          key: ValueKey('message-copy' + keySuffix),
          tooltip: copied ? '已复制' : '复制',
          icon: copied ? Icons.check_rounded : Icons.copy_rounded,
          onPressed: onCopy,
        ),
        const SizedBox(width: 2),
        _bar(
          context,
          key: ValueKey('message-retract' + keySuffix),
          tooltip: onRetract == null ? '这条消息还没有可撤回的记录' : '撤回',
          icon: Icons.undo_rounded,
          onPressed: onRetract,
        ),
      ],
    );
  }
}

/// The five actions under a finished answer.
class AssistantMessageActions extends StatelessWidget {
  const AssistantMessageActions({
    super.key,
    required this.onCopy,
    required this.onLike,
    required this.onDislike,
    required this.onRegenerate,
    required this.onShare,
    this.rating,
    this.copied = false,
    this.busy = false,
    this.keySuffix = '',
  });

  final VoidCallback onCopy;
  final VoidCallback onLike;
  final VoidCallback onDislike;
  final VoidCallback? onRegenerate;
  final VoidCallback onShare;

  /// True = 赞, false = 踩, null = 未评价.
  final bool? rating;
  final bool copied;
  final bool busy;
  final String keySuffix;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _bar(
          context,
          key: ValueKey('answer-like' + keySuffix),
          tooltip: '有帮助',
          icon: rating == true
              ? Icons.thumb_up_rounded
              : Icons.thumb_up_outlined,
          tint: rating == true ? palette.ice : null,
          onPressed: busy ? null : onLike,
        ),
        const SizedBox(width: 2),
        _bar(
          context,
          key: ValueKey('answer-dislike' + keySuffix),
          tooltip: '没帮助',
          icon: rating == false
              ? Icons.thumb_down_rounded
              : Icons.thumb_down_outlined,
          tint: rating == false ? palette.warn : null,
          onPressed: busy ? null : onDislike,
        ),
        const SizedBox(width: 2),
        _bar(
          context,
          key: ValueKey('answer-copy' + keySuffix),
          tooltip: copied ? '已复制' : '复制',
          icon: copied ? Icons.check_rounded : Icons.copy_rounded,
          onPressed: onCopy,
        ),
        const SizedBox(width: 2),
        _bar(
          context,
          key: ValueKey('answer-regenerate' + keySuffix),
          tooltip: onRegenerate == null ? '暂时无法重新输出' : '重新输出（回滚这一轮的文件改动）',
          icon: Icons.refresh_rounded,
          onPressed: onRegenerate,
        ),
        const SizedBox(width: 2),
        _bar(
          context,
          key: ValueKey('answer-share' + keySuffix),
          tooltip: '分享',
          icon: Icons.ios_share_rounded,
          onPressed: onShare,
        ),
      ],
    );
  }
}

Widget _bar(
  BuildContext context, {
  required Key key,
  required String tooltip,
  required IconData icon,
  required VoidCallback? onPressed,
  Color? tint,
}) {
  final palette = DshPalette.of(context);
  return Tooltip(
    message: tooltip,
    child: IconButton(
      key: key,
      tooltip: tooltip,
      onPressed: onPressed,
      visualDensity: VisualDensity.compact,
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints(minWidth: 36, minHeight: 36),
      icon: Icon(icon, size: 17, color: tint ?? palette.inkFaint),
    ),
  );
}
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../model/turn_notice.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';

/// Compact, structured turn status. Details stay collapsed until requested so
/// routine failures do not interrupt the transcript with a large red panel.
class TurnNoticeCard extends StatefulWidget {
  const TurnNoticeCard({super.key, required this.notice});

  final TurnNotice notice;

  @override
  State<TurnNoticeCard> createState() => _TurnNoticeCardState();
}

class _TurnNoticeCardState extends State<TurnNoticeCard> {
  bool _expanded = false;

  TurnNotice get _notice => widget.notice;

  (IconData, Color) _state(DshColors colors) => switch (_notice.kind) {
    TurnNoticeKind.failed => (Icons.error_outline_rounded, colors.bad),
    TurnNoticeKind.signedOut => (Icons.person_off_outlined, colors.bad),
    TurnNoticeKind.interrupted => (
      Icons.power_settings_new_rounded,
      colors.warn,
    ),
    TurnNoticeKind.maxTokens => (Icons.warning_amber_rounded, colors.warn),
    TurnNoticeKind.blocked => (Icons.block_rounded, colors.warn),
    TurnNoticeKind.forked => (Icons.call_split_rounded, colors.inkSoft),
    TurnNoticeKind.stopped => (Icons.stop_circle_outlined, colors.inkSoft),
    TurnNoticeKind.other => (Icons.info_outline_rounded, colors.inkSoft),
  };

  String get _copyText => [
    _notice.title,
    _notice.message,
    if (_notice.nextStep.isNotEmpty) _notice.nextStep,
    if (_notice.code != null) '代码：' + _notice.code!,
    if (_notice.detail.isNotEmpty) '详情：' + _notice.detail,
  ].join(String.fromCharCode(10));

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _copyText));
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)
        ?.showSnackBar(const SnackBar(content: Text('已复制这一轮的结束原因')));
  }

  void _toggle() => setState(() => _expanded = !_expanded);

  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final (icon, tone) = _state(colors);
    final expanded = _expanded;
    final hasDetails =
        _notice.message.isNotEmpty ||
        _notice.nextStep.isNotEmpty ||
        _notice.code != null ||
        _notice.detail.isNotEmpty;
    return Padding(
      padding: const EdgeInsets.only(top: 10, bottom: 4),
      child: Semantics(
        container: true,
        label: _notice.message.isEmpty
            ? _notice.title
            : _notice.title + '：' + _notice.message,
        expanded: expanded,
        button: hasDetails,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            key: const ValueKey('turn-notice'),
            onTap: hasDetails ? _toggle : null,
            borderRadius: BorderRadius.circular(10),
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: DshDisplayScope.minTargetOf(context),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 5),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(top: 1),
                      child: Icon(icon, size: 16, color: tone),
                    ),
                    const SizedBox(width: 7),
                    Expanded(
                      child: expanded
                          ? _ExpandedNotice(
                              notice: _notice,
                              colors: colors,
                              onCopy: _copy,
                            )
                          : Text(
                              _notice.message.isEmpty
                                  ? _notice.title
                                  : _notice.message,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: colors.inkSoft,
                                fontSize: 12.5,
                                height: 1.3,
                              ),
                            ),
                    ),
                    if (hasDetails)
                      Padding(
                        padding: const EdgeInsets.only(left: 5, top: 1),
                        child: Icon(
                          expanded
                              ? Icons.expand_less_rounded
                              : Icons.expand_more_rounded,
                          size: 17,
                          color: colors.inkFaint,
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
}

class _ExpandedNotice extends StatelessWidget {
  const _ExpandedNotice({
    required this.notice,
    required this.colors,
    required this.onCopy,
  });

  final TurnNotice notice;
  final DshColors colors;
  final VoidCallback onCopy;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      // The state name stays with the details: the collapsed row only carries
      // the reason, so the ending itself must still be readable once opened.
      Text(
        notice.title,
        key: const ValueKey('turn-notice-title'),
        style: TextStyle(
          color: colors.ink,
          fontSize: 13,
          height: 1.35,
          fontWeight: FontWeight.w600,
        ),
      ),
      if (notice.message.isNotEmpty) ...[
        const SizedBox(height: 3),
        Text(
          notice.message,
          style: TextStyle(color: colors.inkSoft, fontSize: 13, height: 1.45),
        ),
      ],
      if (notice.nextStep.isNotEmpty) ...[
        const SizedBox(height: 5),
        Text(
          notice.nextStep,
          key: const ValueKey('turn-notice-next-step'),
          style: TextStyle(
            color: colors.ink,
            fontSize: 13,
            height: 1.4,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
      if (notice.code != null) ...[
        const SizedBox(height: 5),
        Text(
          '代码：' + notice.code!,
          key: const ValueKey('turn-notice-code'),
          style: TextStyle(
            color: colors.inkFaint,
            fontSize: 12,
            height: 1.3,
            fontFamily: 'monospace',
          ),
        ),
      ],
      if (notice.detail.isNotEmpty) ...[
        const SizedBox(height: 5),
        Container(
          key: const ValueKey('turn-notice-detail'),
          width: double.infinity,
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: colors.paper.withValues(alpha: .38),
            borderRadius: BorderRadius.circular(8),
          ),
          child: SelectableText(
            notice.detail,
            style: TextStyle(
              color: colors.inkSoft,
              fontSize: 12,
              height: 1.45,
              fontFamily: 'monospace',
            ),
          ),
        ),
      ],
      Align(
        alignment: AlignmentDirectional.centerEnd,
        child: TextButton.icon(
          key: const ValueKey('turn-notice-copy'),
          onPressed: onCopy,
          style: TextButton.styleFrom(
            minimumSize: DshDisplayScope.targetSize(context),
            padding: const EdgeInsets.symmetric(horizontal: 8),
          ),
          icon: const Icon(Icons.copy_rounded, size: 16),
          label: const Text('复制详情'),
        ),
      ),
    ],
  );
}

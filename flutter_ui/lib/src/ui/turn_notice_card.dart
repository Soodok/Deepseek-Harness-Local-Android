import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../model/turn_notice.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';

/// One turn ending, drawn as its own row exactly like the DSH turn-error node:
/// a state dot, a title, the readable reason, the real failure code, and the
/// original payload behind a disclosure. It is never model speech, so it has
/// no bubble, no markdown and no streaming marker.
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

  String get _copyText {
    final code = _notice.code;
    return [
      _notice.title,
      _notice.message,
      if (_notice.nextStep.isNotEmpty) _notice.nextStep,
      if (code != null) '代码：' + code,
      if (_notice.detail.isNotEmpty) '详情：' + _notice.detail,
    ].join(String.fromCharCode(10));
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _copyText));
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)
        ?.showSnackBar(const SnackBar(content: Text('已复制这一轮的结束原因')));
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final (icon, tone) = _state(colors);
    final code = _notice.code;
    return Padding(
      padding: const EdgeInsets.only(top: 14, bottom: 6),
      child: Container(
        key: const ValueKey('turn-notice'),
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
        decoration: BoxDecoration(
          color: tone.withValues(alpha: _notice.isFailure ? .08 : .05),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: tone.withValues(alpha: _notice.isFailure ? .35 : .22),
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Icon(icon, size: 18, color: tone),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _notice.title,
                        style: TextStyle(
                          color: colors.ink,
                          fontSize: 14,
                          height: 1.35,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      if (_notice.message.isNotEmpty) ...[
                        const SizedBox(height: 3),
                        Text(
                          _notice.message,
                          style: TextStyle(
                            color: colors.inkSoft,
                            fontSize: 13.5,
                            height: 1.5,
                          ),
                        ),
                      ],
                      if (_notice.nextStep.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          _notice.nextStep,
                          key: const ValueKey('turn-notice-next-step'),
                          style: TextStyle(
                            color: colors.ink,
                            fontSize: 13.5,
                            height: 1.5,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                      // The code sits under the reason instead of beside it:
                      // a long failure code and a 2x system font cannot share
                      // one row on a narrow phone without clipping.
                      if (code != null) ...[
                        const SizedBox(height: 6),
                        Align(
                          alignment: AlignmentDirectional.centerStart,
                          child: Container(
                            key: const ValueKey('turn-notice-code'),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 7,
                              vertical: 3,
                            ),
                            decoration: BoxDecoration(
                              color: tone.withValues(alpha: .12),
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              code,
                              style: TextStyle(
                                color: tone,
                                fontSize: 12,
                                height: 1.2,
                                fontFamily: 'monospace',
                              ),
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
            // Actions stay honest: the disclosure only exists when there is
            // an original payload, and copy is always available.
            Row(
              children: [
                if (_notice.detail.isNotEmpty)
                  TextButton(
                    key: const ValueKey('turn-notice-detail-toggle'),
                    style: TextButton.styleFrom(
                      minimumSize: DshDisplayScope.targetSize(context),
                      padding: const EdgeInsets.symmetric(horizontal: 10),
                    ),
                    onPressed: () => setState(() => _expanded = !_expanded),
                    child: Text(_expanded ? '收起详情' : '详情'),
                  ),
                const Spacer(),
                IconButton(
                  key: const ValueKey('turn-notice-copy'),
                  tooltip: '复制结束原因',
                  style: IconButton.styleFrom(
                    minimumSize: DshDisplayScope.targetSize(context),
                  ),
                  onPressed: _copy,
                  icon: Icon(
                    Icons.copy_rounded,
                    size: 17,
                    color: colors.inkSoft,
                  ),
                ),
              ],
            ),
            if (_expanded && _notice.detail.isNotEmpty)
              Container(
                key: const ValueKey('turn-notice-detail'),
                width: double.infinity,
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: colors.paper,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: SelectableText(
                  _notice.detail,
                  style: TextStyle(
                    color: colors.inkSoft,
                    fontSize: 12,
                    height: 1.5,
                    fontFamily: 'monospace',
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

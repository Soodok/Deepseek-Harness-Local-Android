import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';
import '../theme/display_scale.dart';
import 'chat_shell.dart' show ChatLayout;
import 'glass_panel.dart';

/// Floating input geometry shared by every conversation, including short views.
class ComposerViewport extends StatelessWidget {
  const ComposerViewport({
    super.key,
    required this.child,
    this.availableHeight,
    this.scrollKey,
  });
  final Widget child;
  final double? availableHeight;
  final Key? scrollKey;

  @override
  Widget build(BuildContext context) {
    final gutter = ChatLayout.gutter(MediaQuery.sizeOf(context).width);
    return ConstrainedBox(
      constraints: BoxConstraints(
        maxHeight: (availableHeight ?? MediaQuery.sizeOf(context).height) * .75,
      ),
      child: SingleChildScrollView(
        key: scrollKey,
        reverse: true,
        child: Padding(
          padding: EdgeInsets.fromLTRB(gutter - 2, 8, gutter - 2, 12),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: ChatLayout.maxColumn),
              child: child,
            ),
          ),
        ),
      ),
    );
  }
}

/// Shared input material and field. Callers provide only supported controls.
/// Draft ownership and send/receipt guards remain with each conversation.
class ComposerSurface extends StatelessWidget {
  const ComposerSurface({
    super.key,
    required this.textController,
    required this.inputKey,
    required this.hintText,
    required this.controls,
    this.focusNode,
    this.onSubmitted,
    this.onChanged,
    this.leading = const [],
  });
  final TextEditingController textController;
  final Key inputKey;
  final String hintText;
  final List<Widget> controls, leading;
  final FocusNode? focusNode;
  final VoidCallback? onSubmitted;

  /// Reports typing so the owning draft can follow it. Null keeps the field's
  /// text private to the widget.
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) => GlassPanel(
    radius: 22,
    composerGlass: true,
    child: Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 6, 6),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ...leading,
          TextField(
            key: inputKey,
            controller: textController,
            focusNode: focusNode,
            enabled: true,
            minLines: 1,
            maxLines: 6,
            keyboardType: TextInputType.multiline,
            cursorColor: DshPalette.of(context).ink,
            style: TextStyle(
              fontSize: 17,
              height: 1.5,
              color: DshPalette.of(context).ink,
            ),
            decoration: InputDecoration(
              hintText: hintText,
              hintStyle: TextStyle(color: DshPalette.of(context).inkFaint),
              border: InputBorder.none,
              enabledBorder: InputBorder.none,
              focusedBorder: InputBorder.none,
              disabledBorder: InputBorder.none,
              filled: false,
              isDense: true,
              contentPadding: const EdgeInsets.fromLTRB(4, 4, 4, 4),
            ),
            onChanged: onChanged,
            onSubmitted: onSubmitted == null ? null : (_) => onSubmitted!(),
          ),
          const SizedBox(height: 14),
          Row(children: controls),
        ],
      ),
    ),
  );
}

/// One slot that is either the send or the stop action. The position never
/// moves, so a running turn never grows a second button next to the first.
///
/// A stop is offered only when the transport can actually cancel this turn and
/// the draft is empty; as soon as text or an attachment exists the same slot
/// becomes send, which follows the real steering/queue protocol. Reading the
/// draft here (not in the caller) keeps every conversation identical.
class ComposerActionButton extends StatelessWidget {
  const ComposerActionButton({
    super.key,
    required this.sendKey,
    required this.stopKey,
    required this.running,
    required this.canStop,
    required this.stopInFlight,
    required this.canSend,
    required this.hasContent,
    required this.onSend,
    required this.onStop,
    this.busyLabel = '排队到下一轮',
    this.noStopLabel = '当前会话不支持停止',
  });

  final Key sendKey;
  final Key stopKey;
  final bool running;
  final bool canStop;
  final bool stopInFlight;
  final bool canSend;
  final bool hasContent;
  final VoidCallback onSend;
  final VoidCallback onStop;
  final String busyLabel;
  final String noStopLabel;

  /// The stop wins this slot only when a turn runs, nothing is drafted and the
  /// transport can actually cancel it. A pending stop keeps the stop icon (the
  /// slot does not jump back to send) but is no longer pressable.
  bool get _stopSlot => running && !hasContent && (canStop || stopInFlight);

  @override
  Widget build(BuildContext context) {
    if (_stopSlot) {
      return IconButton.filled(
        key: stopKey,
        tooltip: stopInFlight ? '正在停止' : '停止执行',
        style: IconButton.styleFrom(
          backgroundColor: Colors.transparent,
          foregroundColor: DshPalette.of(context).ink,
          disabledBackgroundColor: Colors.transparent,
          disabledForegroundColor: DshPalette.of(context).inkFaint,
          minimumSize: DshDisplayScope.targetSize(context),
          padding: EdgeInsets.zero,
        ),
        onPressed: canStop ? onStop : null,
        icon: stopInFlight
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: DshPalette.of(context).ink.withValues(alpha: .10),
                  shape: BoxShape.circle,
                ),
                alignment: Alignment.center,
                child: const Icon(Icons.stop_rounded, size: 20),
              ),
      );
    }
    final waitingOnlyStop = running && !hasContent;
    return Tooltip(
      message: waitingOnlyStop ? noStopLabel : (running ? busyLabel : '发送'),
      child: IconButton.filled(
        key: sendKey,
        onPressed: !waitingOnlyStop && canSend && hasContent ? onSend : null,
        style: IconButton.styleFrom(
          backgroundColor: Colors.transparent,
          foregroundColor: Colors.white,
          disabledBackgroundColor: Colors.transparent,
          disabledForegroundColor: DshPalette.of(context).inkFaint,
          minimumSize: DshDisplayScope.targetSize(context),
          padding: EdgeInsets.zero,
        ),
        icon: Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: !waitingOnlyStop && canSend && hasContent
                ? DshPalette.of(context).sendBlue
                : DshPalette.of(context).sendBlueMuted,
            shape: BoxShape.circle,
          ),
          alignment: Alignment.center,
          child: const Icon(Icons.arrow_upward_rounded, size: 20),
        ),
      ),
    );
  }
}

/// Compact model capsule: a 34dp painted surface inside a 48dp touch target,
/// so it reads lighter than the send button without shrinking the tap area.
///
/// The painted surface is content-sized and pinned to the leading edge: the old
/// centred one floated to the middle of the free row space, which pushed the
/// capsule away from the attach button. [semanticLabel] keeps the untruncated
/// model name available even though only the short display label is painted.
class ComposerModelPill extends StatelessWidget {
  const ComposerModelPill({
    super.key,
    required this.label,
    required this.onPressed,
    this.enabled = true,
    this.pillKey,
    this.semanticLabel,
  });

  static const double surfaceHeight = 34;
  static const double touchHeight = 48;

  final String label;
  final VoidCallback? onPressed;
  final bool enabled;
  final Key? pillKey;

  /// Full model name for accessibility and long-press, when [label] is short.
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    // The canvas scales the painted stadium and the type; the touch target is
    // expressed in real dp so the compact size cannot shrink it below 48dp.
    final touch = DshDisplayScope.atLeastTarget(context, touchHeight);
    final foreground = enabled ? palette.ink : palette.inkFaint;
    final capsule = Container(
      key: const ValueKey('composer-model-surface'),
      height: surfaceHeight,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: palette.ink.withValues(alpha: .06),
        borderRadius: BorderRadius.circular(surfaceHeight / 2),
        border: Border.all(
          color: palette.ink.withValues(alpha: .13),
          width: 0.8,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Flexible(
            child: MediaQuery.withClampedTextScaling(
              // The canvas already scales the capsule and its type by the
              // same factor, so this cap only limits how far the *system* font
              // setting may blow the one-line label out of the stadium.
              maxScaleFactor: 1.2,
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                softWrap: false,
                textHeightBehavior: const TextHeightBehavior(
                  leadingDistribution: TextLeadingDistribution.even,
                  applyHeightToFirstAscent: true,
                  applyHeightToLastDescent: true,
                ),
                style: TextStyle(
                  fontSize: 13,
                  height: 1,
                  color: foreground,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ),
          const SizedBox(width: 3),
          Icon(
            Icons.expand_more_rounded,
            size: 16,
            color: enabled ? palette.inkSoft : palette.inkFaint,
          ),
        ],
      ),
    );
    final full = semanticLabel ?? label;
    // The real tap target is exactly the capsule plus the 48dp minimum, pinned
    // to the leading edge of the free row space. It is deliberately not the
    // full expanded width: the key, the semantics node and the ink well must
    // cover the same pixels, so a tap on its centre always lands.
    return Align(
      alignment: AlignmentDirectional.centerStart,
      // Tight around the target: the widget wrapper, the semantics node and the
      // ink well all cover the same pixels, so a tap at the widget's centre is
      // a tap on the capsule.
      widthFactor: 1,
      child: SizedBox(
        key: pillKey,
        height: touch,
        child: ConstrainedBox(
          constraints: BoxConstraints(minWidth: touch, minHeight: touch),
          child: Semantics(
            label: full,
            button: enabled,
            enabled: enabled,
            child: Tooltip(
              message: full,
              child: Material(
                type: MaterialType.transparency,
                child: InkWell(
                  onTap: enabled ? onPressed : null,
                  borderRadius: BorderRadius.circular(touch / 2),
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    widthFactor: 1,
                    child: capsule,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class ComposerSendButton extends StatelessWidget {
  const ComposerSendButton({
    super.key,
    required this.buttonKey,
    required this.tooltip,
    required this.onPressed,
  });
  final Key buttonKey;
  final String tooltip;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => IconButton.filled(
    key: buttonKey,
    tooltip: tooltip,
    style: IconButton.styleFrom(
      backgroundColor: DshPalette.of(context).sendBlue,
      foregroundColor: Colors.white,
      disabledBackgroundColor: DshPalette.of(context).sendBlueMuted,
      disabledForegroundColor: DshPalette.of(context).inkFaint,
      minimumSize: DshDisplayScope.targetSize(context),
    ),
    onPressed: onPressed,
    icon: const Icon(Icons.arrow_upward_rounded, size: 26),
  );
}

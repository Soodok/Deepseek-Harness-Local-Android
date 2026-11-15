import 'package:material_ui/material_ui.dart';

import '../theme/dsh_theme.dart';
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
    this.leading = const [],
  });
  final TextEditingController textController;
  final Key inputKey;
  final String hintText;
  final List<Widget> controls, leading;
  final FocusNode? focusNode;
  final VoidCallback? onSubmitted;

  @override
  Widget build(BuildContext context) => GlassPanel(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 8, 8),
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
            onSubmitted: onSubmitted == null ? null : (_) => onSubmitted!(),
          ),
          const SizedBox(height: 14),
          Row(children: controls),
        ],
      ),
    ),
  );
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
      minimumSize: const Size(48, 48),
    ),
    onPressed: onPressed,
    icon: const Icon(Icons.arrow_upward_rounded, size: 26),
  );
}

import 'package:material_ui/material_ui.dart';

import '../model/engine_workspace.dart';
import '../theme/dsh_theme.dart';
import 'glass_panel.dart';
import 'chat_strings.dart';
import '../bridge/native_workspace_directories.dart';

enum _WorkspaceAction { home, privateDirectory, sharedDirectory }

/// Provides directory actions through the existing picker, without welcome-page plumbing.
class NativeWorkspaceDirectoryScope extends InheritedWidget {
  const NativeWorkspaceDirectoryScope({
    super.key,
    required super.child,
    required this.selectedDirectory,
    required this.onPickPrivate,
    required this.onPickShared,
  });
  final NativeWorkspaceDirectory? selectedDirectory;
  final Future<bool> Function()? onPickPrivate, onPickShared;
  static NativeWorkspaceDirectoryScope? maybeOf(BuildContext context) => context
      .dependOnInheritedWidgetOfExactType<NativeWorkspaceDirectoryScope>();
  @override
  bool updateShouldNotify(NativeWorkspaceDirectoryScope oldWidget) =>
      selectedDirectory != oldWidget.selectedDirectory ||
      onPickPrivate != oldWidget.onPickPrivate ||
      onPickShared != oldWidget.onPickShared;
}

/// Secondary-control type size on the welcome page. The pills read as actions
/// under the card grid, not as a second headline.
const double kWelcomePickerTextSize = 13.0;

/// The short value the capsule shows and the welcome grid measures, plus the
/// full meaning assistive tech and the menu speak.
///
/// Both surfaces call this one function: the picker paints [name] and
/// [fullName], the welcome layout measures [name] before deciding whether the
/// two pills share a row. A second copy of this rule would drift silently.
({String name, String fullName}) workspaceCapsuleLabels(
  BuildContext context, {
  required List<EngineWorkspace> workspaces,
  required String? selected,
  required String? status,
  required bool confirmed,
}) {
  final directoryScope = NativeWorkspaceDirectoryScope.maybeOf(context);
  final active = workspaces.where((row) => row.id == selected).firstOrNull;
  final directory = directoryScope?.selectedDirectory;
  // A freshly picked directory has no catalogue row yet, so it must still win
  // over the Home placeholder until the Host group shows up.
  final picked = directory != null && directory.path == active?.path;
  final pending = directory != null && active == null;
  final strings = DshChatStrings.of(context);
  final unavailable = status != null && !confirmed && selected == null;
  if (unavailable) {
    return (name: strings.workspaceUnavailable, fullName: strings.workspaceUnavailable);
  }
  if (picked || pending) {
    return (name: directory.name, fullName: directory.path);
  }
  if (selected == null) {
    return (name: strings.workspaceHome, fullName: strings.workspaceHome);
  }
  final stale = strings.workspaceStale;
  return (name: active?.title ?? stale, fullName: active?.path ?? stale);
}

/// Shared by the welcome page and the empty-catalogue recovery surface.
class NativeWorkspacePicker extends StatelessWidget {
  const NativeWorkspacePicker({
    super.key,
    required this.workspaces,
    required this.selected,
    required this.onSelect,
    this.onRetry,
    this.status,
    this.onPickPrivate,
    this.onPickShared,
    this.confirmed = true,
  });
  final List<EngineWorkspace> workspaces;
  final String? selected, status;

  /// Whether the persisted default has actually been read back. A null
  /// [selected] only means Home when this is true; otherwise the picker must
  /// not claim Home is the active choice.
  final bool confirmed;
  final Future<bool> Function(String?) onSelect;
  final VoidCallback? onRetry;
  final Future<bool> Function()? onPickPrivate, onPickShared;

  @override
  Widget build(BuildContext context) {
    final palette = DshPalette.of(context);
    final directoryScope = NativeWorkspaceDirectoryScope.maybeOf(context);
    final pickPrivate = onPickPrivate ?? directoryScope?.onPickPrivate;
    final pickShared = onPickShared ?? directoryScope?.onPickShared;
    // The capsule shows the short value; the semantic label keeps the full
    // meaning (and full directory path) readable to assistive tech.
    final labels = workspaceCapsuleLabels(
      context,
      workspaces: workspaces,
      selected: selected,
      status: status,
      confirmed: confirmed,
    );
    final name = labels.name;
    final fullName = labels.fullName;
    final strings = DshChatStrings.of(context);
    final picker = PopupMenuButton<Object>(
      tooltip: strings.selectWorkspace,
      color: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      shadowColor: Colors.transparent,
      elevation: 0,
      padding: EdgeInsets.zero,
      position: PopupMenuPosition.under,
      onSelected: (value) async {
        if (value == _WorkspaceAction.privateDirectory) {
          await pickPrivate?.call();
          return;
        }
        if (value == _WorkspaceAction.sharedDirectory) {
          await pickShared?.call();
          return;
        }
        String failure;
        try {
          if (await onSelect(value is EngineWorkspace ? value.id : null))
            return;
          failure = strings.workspaceSaveFailed;
        } on Object catch (error) {
          failure = strings.workspaceUnavailable + ' · ' + error.toString();
        }
        if (context.mounted) {
          ScaffoldMessenger.maybeOf(context)
              ?.showSnackBar(SnackBar(content: Text(failure)));
        }
      },
      itemBuilder: (_) => [
        _WorkspaceMenu(
          workspaces: workspaces,
          selected: selected,
          confirmed: confirmed,
          onPickPrivate: pickPrivate,
          onPickShared: pickShared,
        ),
      ],
      child: Semantics(
        button: true,
        // Exclude the visible short text so the announced label is exactly the
        // full meaning, not the abbreviated value plus the full one.
        excludeSemantics: true,
        label: strings.workspaceCapsule(fullName),
        child: Container(
          key: const ValueKey('welcome-workspace-capsule'),
          constraints: const BoxConstraints(minHeight: 48, maxWidth: 240),
          padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 12),
          decoration: BoxDecoration(
            color: palette.surface,
            border: Border.all(color: palette.line),
            borderRadius: BorderRadius.circular(24),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Flexible(
                child: Text(
                  strings.workspaceCapsule(name),
                  maxLines: 1,
                  softWrap: false,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: kWelcomePickerTextSize),
                ),
              ),
              const SizedBox(width: 4),
              const Icon(Icons.keyboard_arrow_down_rounded, size: 20),
            ],
          ),
        ),
      ),
    );
    // The retry affordance belongs to the failure panel only; a healthy
    // picker must not carry a second read action next to the capsule.
    if (status == null) return picker;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        picker,
        Text(status!, style: TextStyle(color: palette.inkSoft, fontSize: 12)),
        if (onRetry != null)
          TextButton(
            key: const ValueKey('workspace-preference-retry'),
            onPressed: onRetry,
            child: Text(strings.retry),
          ),
      ],
    );
  }
}

class _WorkspaceMenu extends PopupMenuEntry<Object> {
  const _WorkspaceMenu({
    required this.workspaces,
    required this.selected,
    required this.confirmed,
    this.onPickPrivate,
    this.onPickShared,
  });
  final List<EngineWorkspace> workspaces;
  final String? selected;

  /// A check mark claims the value is the saved default, so it only appears
  /// once the preference has actually been read back.
  final bool confirmed;
  final Future<bool> Function()? onPickPrivate, onPickShared;
  @override
  double get height =>
      (workspaces.length +
          1 +
          (onPickPrivate == null ? 0 : 1) +
          (onPickShared == null ? 0 : 1)) *
      kMinInteractiveDimension;
  @override
  bool represents(Object? value) => false;
  @override
  State<_WorkspaceMenu> createState() => _WorkspaceMenuState();
}

class _WorkspaceMenuState extends State<_WorkspaceMenu> {
  @override
  Widget build(BuildContext context) {
    final strings = DshChatStrings.of(context);
    return GlassPanel(
      key: const ValueKey('welcome-workspace-glass'),
      radius: 16,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          PopupMenuItem<Object>(
            value: _WorkspaceAction.home,
            child: Row(
              children: [
                Expanded(child: Text(strings.workspaceHome)),
                if (widget.selected == null && widget.confirmed)
                  const Icon(Icons.check_rounded, size: 18),
              ],
            ),
          ),
          for (final row in widget.workspaces)
            PopupMenuItem<Object>(
              value: row,
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      row.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  if (row.id == widget.selected)
                    const Icon(Icons.check_rounded, size: 18),
                ],
              ),
            ),
          if (widget.onPickPrivate != null)
            PopupMenuItem<Object>(
              key: const ValueKey('workspace-pick-private'),
              value: _WorkspaceAction.privateDirectory,
              child: Text(strings.pickPrivateDirectory),
            ),
          if (widget.onPickShared != null)
            PopupMenuItem<Object>(
              key: const ValueKey('workspace-pick-shared'),
              value: _WorkspaceAction.sharedDirectory,
              child: Text(strings.pickSharedDirectory),
            ),
        ],
      ),
    );
  }
}
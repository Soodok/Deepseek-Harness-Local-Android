import 'package:material_ui/material_ui.dart';

import '../../state/settings_controller.dart';

class SettingsScope extends InheritedNotifier<SettingsController> {
  const SettingsScope({
    super.key,
    required SettingsController controller,
    required super.child,
  }) : super(notifier: controller);
  static SettingsController? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<SettingsScope>()?.notifier;
  static SettingsController of(BuildContext context) => maybeOf(context)!;
}

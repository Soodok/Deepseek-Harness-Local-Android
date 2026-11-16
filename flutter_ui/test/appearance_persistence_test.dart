import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:dsh_native_chat/main.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';

void main() {
  testWidgets('appearance persists through host bridge and fresh app state', (
    tester,
  ) async {
    const channel = MethodChannel('app.dsh.mobile/native_chat');
    final values = <String, Object?>{'mode': 'light', 'mood': 'sand'};
    final writes = <Map<Object?, Object?>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'readAppearance')
            return Map<String, Object?>.of(values);
          if (call.method == 'writeAppearance') {
            final args = Map<Object?, Object?>.from(call.arguments as Map);
            writes.add(args);
            values['mode'] = args['mode'];
            values['mood'] = args['mood'];
            return true;
          }
          return null;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    await tester.pumpWidget(const DshApp());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    DshAppearance appearance() =>
        tester.widget<DshAppearance>(find.byType(DshAppearance));
    expect(appearance().mode, ThemeMode.light);
    expect(appearance().mood, DshMood.sand);
    appearance().onChanged(ThemeMode.dark);
    await tester.pump();
    appearance().onMoodChanged!(DshMood.plum);
    await tester.pump();
    expect(writes.last, {'mode': 'dark', 'mood': 'plum'});
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpWidget(const DshApp());
    await tester.pump();
    expect(appearance().mode, ThemeMode.dark);
    expect(appearance().mood, DshMood.plum);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}

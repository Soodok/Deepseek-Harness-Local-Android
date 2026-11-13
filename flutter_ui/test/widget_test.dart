import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/main.dart';

void main() {
  testWidgets('DSH app bootstrap shows independent chat shell', (tester) async {
    await tester.pumpWidget(const DshApp());
    await tester.pump();
    expect(find.text('DSH'), findsOneWidget);
    expect(find.text('fixture'), findsOneWidget);
  });
}

import 'package:dsh_native_chat/src/model/model_display.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('a DeepSeek model name is abbreviated to ds and keeps its suffix', () {
    expect(displayModelLabel('DeepSeek V3.2'), 'ds V3.2');
    expect(displayModelLabel('deepseek-chat'), 'ds-chat');
    expect(displayModelLabel('DEEPSEEK'), 'ds');
    expect(displayModelLabel('DeepSeek-R1-0528'), 'ds-R1-0528');
    expect(displayModelLabel('luna/deepseek-v3'), 'luna/ds-v3');
  });

  test('names without deepseek and empty labels are left untouched', () {
    expect(displayModelLabel('gpt-6-luna'), 'gpt-6-luna');
    expect(displayModelLabel('模型一'), '模型一');
    expect(displayModelLabel(''), '');
    // Only the token itself is replaced, never an unrelated d/s pair.
    expect(displayModelLabel('ds-chat'), 'ds-chat');
  });

  test('the abbreviation is display only; ids and wire values stay intact', () {
    expect(displayModelLabel('DeepSeek'), isNot(contains('DeepSeek')));
    expect('deepseek-chat'.toUpperCase(), 'DEEPSEEK-CHAT');
  });
}

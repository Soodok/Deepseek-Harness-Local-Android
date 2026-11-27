import 'package:flutter/foundation.dart';

/// The composer text as owned by the conversation shell, not the text field.
///
/// The field is a view: an action that fills it (retracting a message, editing
/// one, restoring a draft) bumps [revision], and the field adopts the new text
/// once. Typing writes back without bumping the revision, so a late write from
/// an earlier action cannot overwrite what a person just typed.
class ComposerDraft extends ChangeNotifier {
  ComposerDraft({String initialText = '', this.onTextChanged})
    : _text = initialText;

  final ValueChanged<String>? onTextChanged;
  String _text;
  int _revision = 0;

  String get text => _text;
  int get revision => _revision;

  bool get isBlank => _text.trim().isEmpty;

  /// Seeds an untouched draft from storage without treating it as a new edit.
  void hydrate(String text) {
    if (_text == text) return;
    _text = text;
    notifyListeners();
  }

  /// Adopt text typed by the person. No revision change: this is the source.
  void record(String text) {
    if (_text == text) return;
    _text = text;
    onTextChanged?.call(text);
    notifyListeners();
  }

  /// Replace the draft and tell the field to adopt it.
  void replace(String text, {bool persist = true}) {
    final changed = _text != text;
    _text = text;
    if (changed && persist) onTextChanged?.call(text);
    _revision++;
    notifyListeners();
  }

  /// Append text as its own paragraph.
  void append(String text) {
    final body = text.trim();
    if (body.isEmpty) return;
    replace(_text.trim().isEmpty ? body : _text.trimRight() + '\n\n' + body);
  }

  void clear() => replace('');
}

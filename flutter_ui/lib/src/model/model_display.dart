/// Model names are shown to the user, but the identifier, provider and wire
/// values never change. Only the DeepSeek brand token is shortened so a long
/// catalog name still fits the composer capsule.
String displayModelLabel(String label) {
  if (label.isEmpty) return label;
  return label.replaceAll(RegExp('deepseek', caseSensitive: false), 'ds');
}

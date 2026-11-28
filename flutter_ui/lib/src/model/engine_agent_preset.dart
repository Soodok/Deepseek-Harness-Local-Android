/// An actual Host Agent composition, not a local composer mode.
class EngineAgentPreset {
  const EngineAgentPreset({
    required this.id,
    required this.isDefault,
    this.name,
    this.description,
    this.broken,
  });

  final String id;
  final bool isDefault;
  final String? name, description, broken;

  String get label => name?.trim().isNotEmpty == true ? name!.trim() : id;

  static List<EngineAgentPreset> fromResponse(Object? value) {
    if (value is! Map || value['presets'] is! List) {
      throw const FormatException('Invalid DSH Agent preset roster');
    }
    return List.unmodifiable(
      (value['presets'] as List).map((row) {
        if (row is! Map ||
            row['id'] is! String ||
            (row['id'] as String).isEmpty ||
            row['isDefault'] is! bool) {
          throw const FormatException('Invalid DSH Agent preset');
        }
        return EngineAgentPreset(
          id: row['id'] as String,
          isDefault: row['isDefault'] as bool,
          name: row['name'] as String?,
          description: row['description'] as String?,
          broken: row['broken'] as String?,
        );
      }),
    );
  }
}

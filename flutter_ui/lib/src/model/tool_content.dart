/// Structured UI output, independent of an engine wire schema.
enum StepState { pending, running, completed, failed, cancelled }

enum AgentState { running, waiting, completed, failed, cancelled }

enum ResourceKind { image, link, file }

class PlanStep {
  const PlanStep({
    required this.id,
    required this.title,
    this.status = StepState.pending,
  });
  final String id;
  final String title;
  final StepState status;
}

class AgentTask {
  const AgentTask({
    required this.id,
    required this.name,
    required this.task,
    this.status = AgentState.running,
    this.detail = '',
  });
  final String id;
  final String name;
  final String task;
  final AgentState status;
  final String detail;
}

class OutputResource {
  const OutputResource({
    required this.id,
    required this.title,
    required this.uri,
    required this.kind,
    this.description = '',
    this.mediaType = '',
    this.byteSize,
    this.assetPath,
  });
  final String id;
  final String title;
  final String uri;
  final ResourceKind kind;
  final String description;
  final String mediaType;
  final int? byteSize;
  final String? assetPath;
  Uri? get openableUri {
    final value = Uri.tryParse(uri);
    return value != null &&
            (value.scheme == 'http' || value.scheme == 'https') &&
            value.host.isNotEmpty &&
            value.userInfo.isEmpty
        ? value
        : null;
  }
}

class ToolContent {
  ToolContent({
    required this.title,
    List<PlanStep> steps = const [],
    List<AgentTask> agents = const [],
    List<OutputResource> resources = const [],
  }) : steps = List.unmodifiable(steps),
       agents = List.unmodifiable(agents),
       resources = List.unmodifiable(resources);
  final String title;
  final List<PlanStep> steps;
  final List<AgentTask> agents;
  final List<OutputResource> resources;
}

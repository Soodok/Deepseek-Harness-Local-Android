import 'subagent_model.dart';

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
    this.address,
  });
  final SubagentAddress? address;
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

class CommandResult {
  const CommandResult({
    required this.command,
    this.cwd = '',
    this.stdout = '',
    this.stderr = '',
    this.exitCode,
  });
  final String command, cwd, stdout, stderr;
  final int? exitCode;
}

class CodeEdit {
  const CodeEdit({
    required this.path,
    this.before = '',
    this.after = '',
    this.unifiedDiff = '',
    this.language = '',
  });
  final String path, before, after, unifiedDiff, language;
}

class ToolContent {
  ToolContent({
    required this.title,
    this.reasoning = '',
    this.command,
    List<CodeEdit> edits = const [],
    List<PlanStep> steps = const [],
    List<AgentTask> agents = const [],
    List<OutputResource> resources = const [],
  }) : edits = List.unmodifiable(edits),
       steps = List.unmodifiable(steps),
       agents = List.unmodifiable(agents),
       resources = List.unmodifiable(resources);
  final String title;
  final String reasoning;
  final CommandResult? command;
  final List<CodeEdit> edits;
  final List<PlanStep> steps;
  final List<AgentTask> agents;
  final List<OutputResource> resources;
}

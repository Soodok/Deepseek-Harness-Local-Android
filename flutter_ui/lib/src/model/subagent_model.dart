enum SubagentMode { oneShot, continuable, unknown }

class SubagentAddress {
  const SubagentAddress({
    required this.parentSessionId,
    required this.childSessionId,
    required this.mode,
  });
  final String parentSessionId, childSessionId;
  final SubagentMode mode;
  bool get canSend =>
      mode == SubagentMode.continuable &&
      parentSessionId.isNotEmpty &&
      childSessionId.isNotEmpty;
  String get wireMode => switch (mode) {
    SubagentMode.oneShot => 'one-shot',
    SubagentMode.continuable => 'continuable',
    SubagentMode.unknown => 'unknown',
  };
  Map<String, Object?> toWire() => {
    'kind': 'subagent',
    'parentSessionId': parentSessionId,
    'childSessionId': childSessionId,
    'mode': wireMode,
  };
  @override
  bool operator ==(Object other) =>
      other is SubagentAddress &&
      other.parentSessionId == parentSessionId &&
      other.childSessionId == childSessionId &&
      other.mode == mode;
  @override
  int get hashCode => Object.hash(parentSessionId, childSessionId, mode);
}

class SubagentMessage {
  const SubagentMessage({
    required this.id,
    required this.role,
    required this.text,
  });
  final String id, role, text;
}

class SubagentConversation {
  SubagentConversation({
    required this.address,
    required List<SubagentMessage> messages,
    required this.revision,
    this.running = false,
  }) : messages = List.unmodifiable(messages);
  final SubagentAddress address;
  final List<SubagentMessage> messages;
  final int revision;
  final bool running;
}

class SubagentReceipt {
  const SubagentReceipt({required this.requestId, required this.delivery});
  final String requestId, delivery;
}

/// A child address comes from the parent's observed subagent catalog.
abstract interface class SubagentTransport {
  Future<SubagentConversation> readSubagent(SubagentAddress address);
  Stream<SubagentConversation> watchSubagent(SubagentAddress address);
  Future<SubagentReceipt> sendSubagent(
    SubagentAddress address,
    String text, {
    required String requestId,
  });
}

import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../model/subagent_model.dart';
import '../model/tool_content.dart';
import '../state/chat_controller.dart';
import '../state/subagent_controller.dart';
import '../theme/dsh_theme.dart';
import '../transport/dsh_transport.dart';

const _meta = TextStyle(color: DshPalette.inkSoft, fontSize: 12.5, height: 1.4);
const _body = TextStyle(color: DshPalette.ink, fontSize: 15, height: 1.55);

/// Opens the subagent catalog observed by the parent conversation.
///
/// Agents are read only from the tool contents of the parent state. A later
/// entry with the same id, or the same parent/child session pair, replaces
/// the earlier entry while keeping its list position. The mode is not part of
/// the identity.
Future<void> showSubagentPanel(
  BuildContext context, {
  required ChatController controller,
}) {
  final navigator = Navigator.of(context);
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: DshPalette.paperDeep,
    constraints: BoxConstraints(
      maxHeight: MediaQuery.sizeOf(context).height * 0.85,
    ),
    builder: (_) =>
        _SubagentSheet(controller: controller, parentNavigator: navigator),
  );
}

bool _hasSession(SubagentAddress? address) =>
    address != null &&
    address.parentSessionId.isNotEmpty &&
    address.childSessionId.isNotEmpty;

/// Deduplicated agents in first-seen order; later observations win.
List<AgentTask> collectSubagents(ChatController controller) {
  final slots = <int, AgentTask>{};
  final byId = <String, int>{};
  final byAddress = <(String, String), int>{};
  var nextSlot = 0;
  for (final tool in controller.state.tools) {
    for (final agent in tool.content?.agents ?? const <AgentTask>[]) {
      final address = agent.address;
      final pair = _hasSession(address)
          ? (address!.parentSessionId, address.childSessionId)
          : null;
      final matches = <int>{
        if (byId[agent.id] case final int slot) slot,
        if (pair != null && byAddress[pair] != null) byAddress[pair]!,
      };
      final slot = matches.isEmpty
          ? nextSlot++
          : matches.reduce((a, b) => a < b ? a : b);
      for (final merged in matches.where((value) => value != slot)) {
        slots.remove(merged);
        byId.updateAll((_, value) => value == merged ? slot : value);
        byAddress.updateAll((_, value) => value == merged ? slot : value);
      }
      slots[slot] = agent;
      byId[agent.id] = slot;
      if (pair != null) byAddress[pair] = slot;
    }
  }
  return slots.values.toList(growable: false);
}

String? _unavailableReason(DshTransport transport, AgentTask agent) {
  if (transport is! SubagentTransport) return '不可用：当前连接不支持子会话。';
  if (!_hasSession(agent.address)) return '不可用：缺少完整子会话地址。';
  return null;
}

class _SubagentSheet extends StatelessWidget {
  const _SubagentSheet({
    required this.controller,
    required this.parentNavigator,
  });

  final ChatController controller;
  final NavigatorState parentNavigator;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: AnimatedBuilder(
        animation: controller,
        builder: (context, _) {
          final agents = collectSubagents(controller);
          return ListView.builder(
            key: const ValueKey('subagent-panel'),
            shrinkWrap: true,
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
            itemCount: agents.length + 1,
            itemBuilder: (context, index) {
              if (index == 0) {
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '子智能体',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      controller.transport.isFixture
                          ? 'fixture 演示：以下是演示子会话，不连接真实子智能体。'
                          : '查看工作进展，或进入独立对话。',
                      style: _meta,
                    ),
                    const SizedBox(height: 12),
                    if (agents.isEmpty)
                      Text(
                        controller.transport.isFixture
                            ? '演示：当前会话还没有子智能体'
                            : '当前会话还没有子智能体',
                        style: _body,
                      ),
                  ],
                );
              }
              final agent = agents[index - 1];
              final address = agent.address;
              final reason = _unavailableReason(controller.transport, agent);
              return _AgentRow(
                key: ValueKey<Object>(
                  _hasSession(address)
                      ? (address!.parentSessionId, address.childSessionId)
                      : agent.id,
                ),
                agent: agent,
                unavailableReason: reason,
                onOpen: reason == null ? () => _open(context, agent) : null,
              );
            },
          );
        },
      ),
    );
  }

  void _open(BuildContext sheetContext, AgentTask agent) {
    final transport = controller.transport;
    if (transport is! SubagentTransport || !_hasSession(agent.address)) return;
    // Remove the catalog route so back returns to the parent conversation.
    Navigator.of(sheetContext).pop();
    unawaited(
      parentNavigator.push(
        MaterialPageRoute<void>(
          builder: (_) => SubagentChatPage(
            agent: agent,
            service: transport as SubagentTransport,
          ),
        ),
      ),
    );
  }
}

class _AgentRow extends StatelessWidget {
  const _AgentRow({
    super.key,
    required this.agent,
    required this.onOpen,
    this.unavailableReason,
  });

  final AgentTask agent;
  final VoidCallback? onOpen;
  final String? unavailableReason;

  @override
  Widget build(BuildContext context) {
    return Padding(
      key: ValueKey('subagent-' + agent.id),
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(agent.name, style: Theme.of(context).textTheme.titleMedium),
          Text(_statusLabel(agent.status), style: _meta),
          const SizedBox(height: 4),
          Text(
            agent.task,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: _body,
          ),
          if (agent.detail.isNotEmpty)
            Text(
              agent.detail,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: _meta,
            ),
          ExpansionTile(
            key: ValueKey('subagent-expand-' + agent.id),
            tilePadding: EdgeInsets.zero,
            shape: const Border(),
            collapsedShape: const Border(),
            title: const Text('工作详情', style: _meta),
            childrenPadding: const EdgeInsets.only(bottom: 8),
            expandedCrossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(agent.task, style: _body),
              if (agent.detail.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(agent.detail, style: _body),
              ],
            ],
          ),
          if (unavailableReason != null) Text(unavailableReason!, style: _meta),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              key: ValueKey('subagent-open-' + agent.id),
              style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
              onPressed: onOpen,
              icon: const Icon(Icons.chat_bubble_outline, size: 18),
              label: const Text('进入对话'),
            ),
          ),
          const Divider(),
        ],
      ),
    );
  }
}

String _statusLabel(AgentState state) => switch (state) {
  AgentState.running => '运行中',
  AgentState.waiting => '等待中',
  AgentState.completed => '已完成',
  AgentState.failed => '失败',
  AgentState.cancelled => '已取消',
};

String _receiptLabel(String delivery) => switch (delivery) {
  'queue' || 'queued' => '已排队',
  'delivered' => '已送达',
  _ => '回执状态：' + delivery,
};

PreferredSizeWidget _bar(
  BuildContext context,
  String title, {
  bool busy = false,
}) => AppBar(
  backgroundColor: DshPalette.paperDeep,
  leading: IconButton(
    key: const ValueKey('subagent-back'),
    tooltip: '返回母会话',
    onPressed: () => Navigator.of(context).maybePop(),
    icon: const Icon(Icons.arrow_back),
  ),
  title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
  actions: [
    if (busy)
      const Padding(
        key: ValueKey('subagent-busy'),
        padding: EdgeInsets.only(right: 16),
        child: SizedBox(
          width: 18,
          height: 18,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      ),
  ],
);

/// Independent child conversation. Message text comes only from the
/// subagent read/watch results; a delivery receipt is shown separately.
class SubagentChatPage extends StatefulWidget {
  const SubagentChatPage({
    super.key,
    required this.agent,
    required this.service,
  });

  final AgentTask agent;
  final SubagentTransport service;

  @override
  State<SubagentChatPage> createState() => _SubagentChatPageState();
}

class _SubagentChatPageState extends State<SubagentChatPage> {
  SubagentController? _child;
  final _input = TextEditingController();
  int _draftVersion = 0;
  String _draftText = '';

  @override
  void initState() {
    super.initState();
    _input.addListener(_draftChanged);
    _bind();
  }

  @override
  void didUpdateWidget(SubagentChatPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.agent.address != widget.agent.address ||
        !identical(oldWidget.service, widget.service)) {
      _unbind();
      final previous = oldWidget.agent.address;
      final next = widget.agent.address;
      final sameSession =
          _hasSession(previous) &&
          _hasSession(next) &&
          previous!.parentSessionId == next!.parentSessionId &&
          previous.childSessionId == next.childSessionId;
      if (!sameSession || !identical(oldWidget.service, widget.service)) {
        _input.removeListener(_draftChanged);
        _input.clear();
        _draftText = '';
        _draftVersion++;
        _input.addListener(_draftChanged);
      }
      _bind();
    }
  }

  void _bind() {
    final address = widget.agent.address;
    if (!_hasSession(address)) return;
    final child = SubagentController(
      service: widget.service,
      address: address!,
    );
    _child = child;
    // start() notifies synchronously; listen afterwards so no setState runs
    // while this State is still initialising or updating.
    unawaited(child.start());
    child.addListener(_changed);
  }

  void _unbind() {
    _child?.removeListener(_changed);
    _child?.dispose();
    _child = null;
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  void _draftChanged() {
    if (_input.text == _draftText) return;
    _draftText = _input.text;
    _draftVersion++;
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _unbind();
    _input.removeListener(_draftChanged);
    _input.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final child = _child;
    if (child == null) return;
    final version = _draftVersion;
    final ok = await child.send(_input.text);
    if (!mounted || !identical(child, _child)) return;
    if (ok && version == _draftVersion) _input.clear();
  }

  String? get _restriction {
    final address = widget.agent.address;
    if (!_hasSession(address)) return '不可用：缺少完整子会话地址，未发送任何内容。';
    return switch (address!.mode) {
      SubagentMode.continuable => null,
      SubagentMode.oneShot => '只读：一次性子智能体不能继续发送消息。',
      SubagentMode.unknown => '只读：无法确认子会话可继续，已禁止发送。',
    };
  }

  @override
  Widget build(BuildContext context) {
    final child = _child;
    final restriction = _restriction;
    final service = widget.service;
    final fixture =
        service is DshTransport && (service as DshTransport).isFixture;
    final messages = child?.conversation?.messages ?? const <SubagentMessage>[];
    final busy =
        child != null &&
        (child.loading || child.sending || child.conversation?.running == true);
    final maxBubble = MediaQuery.sizeOf(context).width * 0.82;
    final header = <Widget>[
      if (fixture) const Text('fixture 演示：以下为演示子会话内容。', style: _meta),
      Text(
        child?.conversation == null
            ? _statusLabel(widget.agent.status)
            : child!.conversation!.running
            ? '运行中'
            : restriction == null
            ? '可继续对话'
            : '历史对话',
        style: _meta,
      ),
      if (restriction != null)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(restriction, style: _body),
        ),
      if (child != null && child.loading && child.conversation == null)
        const Padding(
          key: ValueKey('subagent-loading'),
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Text('正在读取子会话…', style: _meta),
        ),
      if (child?.error != null)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 8,
            children: [
              Text(
                child!.error!,
                style: const TextStyle(color: DshPalette.bad),
              ),
              TextButton(
                key: const ValueKey('subagent-retry'),
                style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
                onPressed: child.loading ? null : child.start,
                child: const Text('重试'),
              ),
            ],
          ),
        ),
    ];
    final receipt = child?.lastReceipt;
    final tail = receipt == null ? 0 : 1;
    return Scaffold(
      backgroundColor: DshPalette.paper,
      appBar: _bar(context, widget.agent.name, busy: busy),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              key: const ValueKey('subagent-messages'),
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
              itemCount: header.length + messages.length + tail,
              itemBuilder: (context, index) {
                if (index < header.length) return header[index];
                final i = index - header.length;
                if (i < messages.length) {
                  return _MessageBubble(
                    message: messages[i],
                    maxWidth: maxBubble,
                  );
                }
                return Padding(
                  key: const ValueKey('subagent-receipt'),
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_receiptLabel(receipt!.delivery), style: _meta),
                );
              },
            ),
          ),
          if (restriction == null && child != null)
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 6, 8, 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Expanded(
                      child: TextField(
                        key: const ValueKey('subagent-input'),
                        controller: _input,
                        minLines: 1,
                        maxLines: 5,
                        keyboardType: TextInputType.multiline,
                        style: _body,
                        decoration: InputDecoration(
                          hintText: '发给' + widget.agent.name,
                          filled: true,
                          fillColor: DshPalette.surface,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(20),
                            borderSide: BorderSide.none,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 6),
                    SizedBox(
                      width: 48,
                      height: 48,
                      child: IconButton(
                        key: const ValueKey('subagent-send'),
                        tooltip: child.sending ? '发送中' : '发送',
                        onPressed:
                            child.canSend && _input.text.trim().isNotEmpty
                            ? _send
                            : null,
                        style: IconButton.styleFrom(
                          backgroundColor: DshPalette.sendBlue,
                          disabledBackgroundColor: DshPalette.sendBlueMuted,
                        ),
                        icon: const Icon(
                          Icons.arrow_upward,
                          color: Colors.white,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message, required this.maxWidth});

  final SubagentMessage message;
  final double maxWidth;

  @override
  Widget build(BuildContext context) {
    final mine = message.role == 'user';
    final text = Text(message.text, style: _body);
    if (!mine) {
      return Padding(padding: const EdgeInsets.only(top: 12), child: text);
    }
    return Align(
      alignment: Alignment.centerRight,
      child: Container(
        margin: const EdgeInsets.only(top: 12),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
        constraints: BoxConstraints(maxWidth: maxWidth),
        decoration: BoxDecoration(
          color: DshPalette.userWash,
          borderRadius: BorderRadius.circular(16),
        ),
        child: text,
      ),
    );
  }
}

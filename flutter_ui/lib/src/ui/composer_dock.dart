import 'dart:async';
import 'dart:math' as math;

import 'package:material_ui/material_ui.dart' hide StepState;

import '../model/chat_model.dart';
import '../model/tool_content.dart';
import '../state/chat_controller.dart';
import '../theme/display_scale.dart';
import '../theme/dsh_theme.dart';
import 'action_cards.dart';

/// Active tasks and requests, kept outside the paged conversation list.
class ComposerDock extends StatefulWidget {
  const ComposerDock({
    super.key,
    required this.controller,
    this.availableHeight,
    this.showApproval = true,
  });

  final ChatController controller;
  final double? availableHeight;
  final bool showApproval;

  @override
  State<ComposerDock> createState() => _ComposerDockState();
}

class _ComposerDockState extends State<ComposerDock> {
  static const _readingTime = Duration(seconds: 2);
  static const _motionTime = Duration(milliseconds: 260);

  Timer? _readingTimer;
  ToolRun? _plan;
  final Set<String> _knownPlanIds = {};
  final Map<String, QuestionAnswer> _questionDrafts = {};
  void _saveQuestionDraft(String? key, QuestionAnswer draft) {
    if (key == null) return;
    _questionDrafts.remove(key);
    _questionDrafts[key] = draft;
    while (_questionDrafts.length > 8) {
      _questionDrafts.remove(_questionDrafts.keys.first);
    }
  }

  int? _generation;
  bool _planExpanded = true;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onControllerChanged);
    _readPlan();
  }

  @override
  void didUpdateWidget(covariant ComposerDock oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onControllerChanged);
      _readingTimer?.cancel();
      _readingTimer = null;
      _plan = null;
      _knownPlanIds.clear();
      _questionDrafts.clear();
      _generation = null;
      widget.controller.addListener(_onControllerChanged);
      _readPlan();
    }
  }

  void _onControllerChanged() {
    _readPlan();
    setState(() {});
  }

  void _readPlan() {
    final state = widget.controller.state;
    if (_generation != state.generation) {
      _readingTimer?.cancel();
      _readingTimer = null;
      _plan = null;
      _knownPlanIds.clear();
      _generation = state.generation;
    }

    final plans = state.tools
        .where((tool) => tool.content?.steps.isNotEmpty ?? false)
        .toList(growable: false);
    final latest = plans.lastOrNull;
    // A same-generation history page may omit the active tool, or contain
    // only plans already observed before it. Neither replaces the live plan.
    if (latest == null) return;
    final historyOnly =
        _plan != null &&
        !plans.any((tool) => tool.id == _plan!.id) &&
        _knownPlanIds.contains(latest.id);
    _knownPlanIds.addAll(plans.map((tool) => tool.id));
    if (historyOnly) return;
    final isNewPlan = _plan?.id != latest.id;
    _plan = latest;
    if (!isNewPlan) return;

    _readingTimer?.cancel();
    _planExpanded = true;
    final planId = latest.id;
    final generation = state.generation;
    _readingTimer = Timer(_readingTime, () {
      if (!mounted || _generation != generation || _plan?.id != planId) return;
      _readingTimer = null;
      setState(() => _planExpanded = false);
    });
  }

  void _togglePlan() {
    // Any manual toggle owns the disclosure from now on. Ordinary status
    // updates never arm another timer for this plan.
    _readingTimer?.cancel();
    _readingTimer = null;
    setState(() => _planExpanded = !_planExpanded);
  }

  @override
  void dispose() {
    _readingTimer?.cancel();
    widget.controller.removeListener(_onControllerChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final state = controller.state;
    final question = state.question;
    final approval = widget.showApproval ? state.approval : null;
    final plan = _plan;
    if (plan == null && question == null && approval == null) {
      return const SizedBox.shrink();
    }

    final media = MediaQuery.of(context);
    final duration = media.disableAnimations ? Duration.zero : _motionTime;
    final availableHeight =
        widget.availableHeight ??
        math.max(
          0.0,
          media.size.height - media.viewInsets.bottom - media.padding.vertical,
        );
    final children = <Widget>[
      if (question != null)
        _RequestDisclosure(
          key: ValueKey((
            controller,
            state.generation,
            'question',
            question.requestId,
          )),
          toggleKey: const ValueKey('dock-question-toggle'),
          icon: Icons.chat_bubble_outline_rounded,
          label: '问题',
          title: question.title,
          duration: duration,
          child: QuestionCard(
            request: question,
            initialDraft: _questionDrafts[question.draftKey],
            onDraftChanged: (draft) =>
                _saveQuestionDraft(question.draftKey, draft),
            busy: controller.actionBusy(question.requestId),
            onSubmit: (answer) => controller.answer(question.requestId, answer),
            onStructuredSubmit: controller.supportsStructuredQuestions
                ? (answer) =>
                      controller.answerQuestion(question.requestId, answer)
                : null,
          ),
        ),
      if (approval != null)
        _RequestDisclosure(
          key: ValueKey((
            controller,
            state.generation,
            'approval',
            approval.requestId,
          )),
          toggleKey: const ValueKey('dock-approval-toggle'),
          icon: Icons.shield_outlined,
          label: '确认',
          title: approval.title,
          duration: duration,
          child: ApprovalCard(
            request: approval,
            busy: controller.actionBusy(approval.requestId),
            onApprove: () => controller.approve(approval.requestId),
            onStop: controller.stop,
            onReject: controller.canRejectApproval
                ? () => controller.rejectApproval(approval.requestId)
                : null,
          ),
        ),
      if (plan != null) _buildPlan(plan, duration),
    ];

    return LayoutBuilder(
      builder: (context, constraints) => ConstrainedBox(
        key: const ValueKey('composer-dock'),
        constraints: BoxConstraints(
          maxHeight: math.min(availableHeight * .42, constraints.maxHeight),
        ),
        child: SingleChildScrollView(
          key: const ValueKey('composer-dock-scroll'),
          primary: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var i = 0; i < children.length; i++) ...[
                if (i > 0) const SizedBox(height: 6),
                children[i],
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPlan(ToolRun tool, Duration duration) {
    final steps = tool.content!.steps;
    final completed = steps
        .where((s) => s.status == StepState.completed)
        .length;
    final running = steps
        .where((s) => s.status == StepState.running)
        .firstOrNull;
    final summary = running != null
        ? '正在进行 · ${running.title}'
        : completed == steps.length
        ? '全部完成'
        : '${steps.length - completed} 项待完成';

    return KeyedSubtree(
      key: ValueKey((_generation, tool.id)),
      child: Material(
        key: const ValueKey('dock-plan'),
        color: DshPalette.of(context).surfaceRaised,
        borderRadius: BorderRadius.circular(12),
        clipBehavior: Clip.antiAlias,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _DockHeader(
              toggleKey: const ValueKey('dock-plan-toggle'),
              icon: Icons.checklist_rounded,
              label: '任务',
              title: summary,
              count: '$completed/${steps.length} 已完成',
              expanded: _planExpanded,
              onTap: _togglePlan,
            ),
            _DisclosureBody(
              expanded: _planExpanded,
              duration: duration,
              child: ConstrainedBox(
                key: const ValueKey('dock-plan-body'),
                constraints: const BoxConstraints(maxHeight: 200),
                child: SingleChildScrollView(
                  primary: false,
                  padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [for (final step in steps) _PlanRow(step: step)],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DockHeader extends StatelessWidget {
  const _DockHeader({
    required this.toggleKey,
    required this.icon,
    required this.label,
    required this.title,
    required this.expanded,
    required this.onTap,
    this.count,
  });

  final Key toggleKey;
  final IconData icon;
  final String label;
  final String title;
  final String? count;
  final bool expanded;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    expanded: expanded,
    label: '$label，${count ?? title}',
    child: InkWell(
      key: toggleKey,
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: SizedBox(
        height: DshDisplayScope.minTargetOf(context),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Row(
            children: [
              Icon(icon, size: 17, color: DshPalette.of(context).inkSoft),
              const SizedBox(width: 8),
              Text(
                label,
                style: TextStyle(
                  color: DshPalette.of(context).ink,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
              if (count != null) ...[
                const SizedBox(width: 8),
                Text(
                  count!,
                  style: TextStyle(
                    color: DshPalette.of(context).inkSoft,
                    fontSize: 12,
                  ),
                ),
              ],
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: DshPalette.of(context).inkSoft,
                    fontSize: 12,
                  ),
                ),
              ),
              const SizedBox(width: 4),
              Icon(
                expanded
                    ? Icons.keyboard_arrow_up_rounded
                    : Icons.keyboard_arrow_down_rounded,
                size: 18,
                color: DshPalette.of(context).inkSoft,
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _PlanRow extends StatelessWidget {
  const _PlanRow({required this.step});

  final PlanStep step;

  @override
  Widget build(BuildContext context) {
    final color = switch (step.status) {
      StepState.completed => DshPalette.of(context).good,
      StepState.running => DshPalette.of(context).ink,
      StepState.failed => DshPalette.of(context).bad,
      StepState.pending ||
      StepState.cancelled => DshPalette.of(context).inkFaint,
    };
    final icon = switch (step.status) {
      StepState.completed => Icons.check_rounded,
      StepState.running => Icons.more_horiz_rounded,
      StepState.failed => Icons.close_rounded,
      StepState.cancelled => Icons.remove_rounded,
      StepState.pending => Icons.circle_outlined,
    };
    return Padding(
      key: ValueKey('dock-plan-step-${step.id}'),
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Icon(icon, size: 14, color: color),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              step.title,
              softWrap: true,
              style: TextStyle(
                color: DshPalette.of(context).ink,
                fontSize: 13,
                height: 1.45,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _RequestDisclosure extends StatefulWidget {
  const _RequestDisclosure({
    super.key,
    required this.toggleKey,
    required this.icon,
    required this.label,
    required this.title,
    required this.duration,
    required this.child,
  });

  final Key toggleKey;
  final IconData icon;
  final String label;
  final String title;
  final Duration duration;
  final Widget child;

  @override
  State<_RequestDisclosure> createState() => _RequestDisclosureState();
}

class _RequestDisclosureState extends State<_RequestDisclosure> {
  bool _expanded = true;

  @override
  Widget build(BuildContext context) => Material(
    color: DshPalette.of(context).surface,
    borderRadius: BorderRadius.circular(12),
    clipBehavior: Clip.antiAlias,
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _DockHeader(
          toggleKey: widget.toggleKey,
          icon: widget.icon,
          label: widget.label,
          title: _expanded ? '' : widget.title,
          expanded: _expanded,
          onTap: () => setState(() => _expanded = !_expanded),
        ),
        _DisclosureBody(
          expanded: _expanded,
          duration: widget.duration,
          child: widget.child,
        ),
      ],
    ),
  );
}

/// Clip the content continuously as it folds, then take it offstage without
/// disposing a question's selection, text controller, or inner scroll state.
class _DisclosureBody extends StatelessWidget {
  const _DisclosureBody({
    required this.expanded,
    required this.duration,
    required this.child,
  });

  final bool expanded;
  final Duration duration;
  final Widget child;

  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
    tween: Tween<double>(begin: expanded ? 1 : 0, end: expanded ? 1 : 0),
    duration: duration,
    curve: Curves.easeInOutCubic,
    child: child,
    builder: (context, factor, child) => ExcludeFocus(
      excluding: !expanded,
      child: IgnorePointer(
        ignoring: !expanded,
        child: Visibility(
          visible: factor > 0,
          maintainState: true,
          child: ClipRect(
            child: Align(
              alignment: Alignment.topCenter,
              heightFactor: factor,
              child: child,
            ),
          ),
        ),
      ),
    ),
  );
}

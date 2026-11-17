import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

import '../../model/settings_model.dart';
import '../../theme/display_scale.dart';
import '../../theme/dsh_theme.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({
    super.key,
    required this.profile,
    required this.usage,
    required this.onLogin,
    required this.onLogout,
    required this.onOpen,
    required this.onRefresh,
    this.loading = false,
    this.busy = false,
    this.error,
    this.isDemo = false,
    this.onOpenUsage,
  });

  final AccountProfile? profile;
  final UsageHistory? usage;
  final VoidCallback onLogin, onLogout;
  final VoidCallback? onOpenUsage;
  final ValueChanged<SettingsDestination> onOpen;
  final Future<void> Function() onRefresh;
  final bool loading, busy, isDemo;
  final String? error;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _search = TextEditingController();
  String _query = '';
  String? _refreshError;
  DateTime? _selectedDate;
  bool _refreshing = false;
  bool _usageExpanded = false;
  int _attempt = 0;

  bool get _blocked => widget.busy || widget.loading || _refreshing;

  @override
  void didUpdateWidget(covariant SettingsPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.profile?.id != widget.profile?.id) {
      _attempt++;
      _refreshing = false;
      _refreshError = null;
      _selectedDate = null;
    }
  }

  @override
  void dispose() {
    _attempt++;
    _search.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_blocked) return;
    final attempt = ++_attempt;
    setState(() {
      _refreshing = true;
      _refreshError = null;
    });
    try {
      await widget.onRefresh();
    } catch (_) {
      if (mounted && attempt == _attempt) {
        setState(() => _refreshError = '刷新失败，请稍后重试');
      }
    } finally {
      if (mounted && attempt == _attempt) {
        setState(() => _refreshing = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshPalette.of(context);
    final query = _query.trim().toLowerCase();
    final matches = SettingsDestination.values
        .where(
          (destination) =>
              query.isEmpty ||
              destination.title.toLowerCase().contains(query) ||
              destination.subtitle.toLowerCase().contains(query),
        )
        .toSet();
    return Scaffold(
      backgroundColor: colors.paper,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final inset = constraints.maxWidth < 400 ? 16.0 : 24.0;
            return Align(
              alignment: Alignment.topCenter,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 760),
                child: SingleChildScrollView(
                  key: const PageStorageKey('settings-scroll'),
                  padding: EdgeInsets.fromLTRB(inset, 6, inset, 28),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: _action(
                          key: const ValueKey('settings-back'),
                          label: '返回',
                          onTap: () {
                            Navigator.of(context).maybePop();
                          },
                          child: Icon(
                            Icons.arrow_back_rounded,
                            color: colors.ink,
                          ),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.fromLTRB(4, 2, 4, 14),
                        child: Semantics(
                          header: true,
                          child: Text(
                            '设置',
                            style: TextStyle(
                              color: colors.ink,
                              fontSize: 28,
                              height: 1.15,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.6,
                            ),
                          ),
                        ),
                      ),
                      _searchField(colors),
                      const SizedBox(height: 14),
                      _profileSection(colors),
                      const SizedBox(height: 14),
                      _usageSection(colors),
                      if (matches.isEmpty)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 32),
                          child: Semantics(
                            liveRegion: true,
                            child: Text(
                              '没有找到相关设置',
                              textAlign: TextAlign.center,
                              style: TextStyle(color: colors.inkSoft),
                            ),
                          ),
                        ),
                      for (final group in _groups)
                        if (group.destinations.any(matches.contains))
                          _group(group, matches, colors),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _searchField(DshColors colors) => TextField(
    key: const ValueKey('settings-search'),
    controller: _search,
    onChanged: (value) => setState(() => _query = value),
    textInputAction: TextInputAction.search,
    style: TextStyle(color: colors.ink, fontSize: 15),
    decoration: InputDecoration(
      hintText: '搜索设置',
      hintStyle: TextStyle(color: colors.inkSoft),
      prefixIcon: Icon(Icons.search_rounded, color: colors.inkSoft),
      suffixIcon: _query.isEmpty
          ? null
          : _action(
              key: const ValueKey('settings-clear-search'),
              label: '清除搜索',
              onTap: () {
                _search.clear();
                setState(() => _query = '');
              },
              child: Icon(Icons.close_rounded, color: colors.inkSoft),
            ),
      filled: true,
      fillColor: colors.surfaceRaised,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: colors.ice, width: 2),
      ),
    ),
  );

  Widget _profileSection(DshColors colors) {
    final profile = widget.profile;
    final name = profile == null
        ? '登录 DeepSeek'
        : (profile.displayName.trim().isEmpty
              ? 'DeepSeek 账户'
              : profile.displayName);
    final subtitle = profile == null
        ? '连接账户，查看个人资料'
        : (profile.email.trim().isEmpty ? '已连接 DeepSeek 账户' : profile.email);
    final identity = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          _avatar(colors),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  style: TextStyle(
                    color: colors.ink,
                    fontSize: 18,
                    height: 1.25,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  subtitle,
                  style: TextStyle(
                    color: colors.inkSoft,
                    fontSize: 13,
                    height: 1.45,
                  ),
                ),
              ],
            ),
          ),
          if (profile == null) ...[
            const SizedBox(width: 8),
            Icon(Icons.chevron_right_rounded, color: colors.inkSoft, size: 22),
          ],
        ],
      ),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (profile == null)
          _action(
            key: const ValueKey('settings-login'),
            label: '$name，$subtitle',
            enabled: !_blocked,
            onTap: () {
              if (!_blocked) widget.onLogin();
            },
            child: identity,
          )
        else
          identity,
        if (profile != null)
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: _action(
              key: const ValueKey('settings-logout'),
              label: '退出登录',
              enabled: !_blocked,
              onTap: () {
                if (!_blocked) widget.onLogout();
              },
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Text(
                  '退出登录',
                  style: TextStyle(color: colors.inkSoft, fontSize: 13),
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _avatar(DshColors colors) {
    final uri = widget.profile?.avatarUrl;
    final valid =
        uri != null &&
        (uri.scheme.toLowerCase() == 'https' ||
            uri.scheme.toLowerCase() == 'http') &&
        uri.host.isNotEmpty &&
        uri.userInfo.isEmpty;
    Widget fallback() => SizedBox.expand(
      key: const ValueKey('settings-avatar-fallback'),
      child: Icon(Icons.person_rounded, size: 26, color: colors.ice),
    );
    return Semantics(
      image: true,
      label: 'DeepSeek 个人资料头像',
      child: ExcludeSemantics(
        child: Container(
          width: 52,
          height: 52,
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(
            color: colors.userWash,
            shape: BoxShape.circle,
          ),
          child: valid
              ? Image.network(
                  uri.toString(),
                  fit: BoxFit.cover,
                  width: 52,
                  height: 52,
                  cacheWidth: 156,
                  errorBuilder: (context, error, stackTrace) => fallback(),
                )
              : fallback(),
        ),
      ),
    );
  }

  Widget _usageSection(DshColors colors) {
    final history = widget.usage;
    final available = history != null && history.available;
    final end = _date(history?.through ?? DateTime.now());
    final start = DateTime(end.year, end.month, end.day - 83);
    final entries = <DateTime, TokenUsageDay>{};
    if (history != null && history.available) {
      final from = _date(history.from);
      for (final record in history.days) {
        final date = _date(record.date);
        if (date.isBefore(start) || date.isAfter(end) || date.isBefore(from))
          continue;
        final previous = entries[date];
        entries[date] = TokenUsageDay(
          date: date,
          inputTokens: (previous?.inputTokens ?? 0) + record.inputTokens,
          outputTokens: (previous?.outputTokens ?? 0) + record.outputTokens,
        );
      }
    }
    final selected =
        _selectedDate != null &&
            !_selectedDate!.isBefore(start) &&
            !_selectedDate!.isAfter(end)
        ? _selectedDate!
        : end;
    final record = entries[selected];
    final total = entries.values.fold<int>(
      0,
      (sum, day) => sum + day.totalTokens,
    );
    final peak = entries.values.fold<int>(
      1,
      (value, day) => math.max(value, day.totalTokens),
    );
    final error = _refreshError ?? widget.error;
    return Material(
      color: colors.surface,
      borderRadius: BorderRadius.circular(16),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: 8,
              children: [
                Semantics(
                  header: true,
                  child: Text(
                    'Token 记录',
                    style: TextStyle(
                      color: colors.ink,
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                _action(
                  key: const ValueKey('settings-refresh'),
                  label: _refreshing || widget.loading
                      ? '正在刷新 Token 记录'
                      : '刷新 Token 记录',
                  enabled: !_blocked,
                  onTap: _refresh,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.refresh_rounded,
                          size: 18,
                          color: colors.inkSoft,
                        ),
                        const SizedBox(width: 6),
                        Text(
                          _refreshing || widget.loading ? '刷新中' : '刷新',
                          style: TextStyle(color: colors.inkSoft, fontSize: 13),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
            if (widget.onOpenUsage != null)
              Align(
                alignment: AlignmentDirectional.centerStart,
                child: TextButton.icon(
                  key: const ValueKey('official-usage'),
                  onPressed: widget.onOpenUsage,
                  icon: const Icon(Icons.open_in_new_rounded, size: 16),
                  label: const Text('DeepSeek 官方用量'),
                ),
              ),
            if (widget.isDemo)
              Align(
                alignment: AlignmentDirectional.centerStart,
                child: Container(
                  margin: const EdgeInsets.only(bottom: 8),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: colors.userWash,
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    '演示数据',
                    style: TextStyle(color: colors.inkSoft, fontSize: 11),
                  ),
                ),
              ),
            Text(
              available ? '最近 12 周 · ${history.sourceLabel}' : '暂无 Token 记录',
              style: TextStyle(
                color: colors.inkSoft,
                fontSize: 13,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 8),
            if (entries.isNotEmpty) ...[
              Text(
                _number(total),
                style: TextStyle(
                  color: colors.ink,
                  fontSize: 24,
                  height: 1.25,
                  fontWeight: FontWeight.w600,
                ),
              ),
              Text(
                '已记录日的输入 + 输出',
                style: TextStyle(color: colors.inkSoft, fontSize: 12),
              ),
            ] else if (available)
              Text(
                '所选时段暂无记录',
                style: TextStyle(color: colors.inkSoft, fontSize: 14),
              ),
            if (error != null && error.trim().isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Semantics(
                  liveRegion: true,
                  child: Text(
                    error,
                    style: TextStyle(
                      color: colors.bad,
                      fontSize: 13,
                      height: 1.5,
                    ),
                  ),
                ),
              ),
            if (available) ...[
              const SizedBox(height: 18),
              if (_usageExpanded)
                Text(
                  '左右滑动查看 12 周，点按格点查看当日用量',
                  style: TextStyle(
                    color: colors.inkSoft,
                    fontSize: 12,
                    height: 1.5,
                  ),
                ),
              const SizedBox(height: 6),
              if (!_usageExpanded)
                _action(
                  key: const ValueKey('settings-usage-overview'),
                  label: '12周 Token 概览，展开每日明细',
                  onTap: () => setState(() => _usageExpanded = true),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Row(
                      children: [
                        for (int week = 0; week < 12; week++)
                          Expanded(
                            child: Column(
                              children: [
                                for (int day = 0; day < 7; day++)
                                  Container(
                                    height: 16,
                                    margin: const EdgeInsets.all(2),
                                    decoration: BoxDecoration(
                                      color: _usageColor(
                                        entries[DateTime(
                                              start.year,
                                              start.month,
                                              start.day + week * 7 + day,
                                            )]
                                            ?.totalTokens,
                                        peak,
                                        colors,
                                      ),
                                      borderRadius: BorderRadius.circular(3),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              if (_usageExpanded)
                LayoutBuilder(
                  builder: (context, constraints) {
                    // Every date keeps a separate 48dp target, including on narrow screens.
                    final width = math.max(576.0, constraints.maxWidth);
                    return SingleChildScrollView(
                      key: const PageStorageKey('settings-usage-weeks'),
                      scrollDirection: Axis.horizontal,
                      reverse: true,
                      child: SizedBox(
                        width: width,
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: List.generate(
                            12,
                            (week) => SizedBox(
                              width: width / 12,
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: List.generate(7, (day) {
                                  final date = DateTime(
                                    start.year,
                                    start.month,
                                    start.day + week * 7 + day,
                                  );
                                  return _usageDay(
                                    date,
                                    entries[date],
                                    selected == date,
                                    peak,
                                    colors,
                                  );
                                }),
                              ),
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              const SizedBox(height: 10),
              Wrap(
                spacing: 16,
                runSpacing: 8,
                children: [
                  _legend('无记录', colors.surfaceRaised, colors, outlined: true),
                  _legend('已记录 0', colors.line, colors),
                  _legend(
                    '低',
                    Color.lerp(colors.surface, colors.ice, 0.28)!,
                    colors,
                  ),
                  _legend('高', colors.ice, colors),
                ],
              ),
              TextButton.icon(
                key: const ValueKey('settings-usage-expand'),
                onPressed: () =>
                    setState(() => _usageExpanded = !_usageExpanded),
                icon: Icon(
                  _usageExpanded
                      ? Icons.expand_less_rounded
                      : Icons.calendar_view_month_rounded,
                  size: 18,
                ),
                label: Text(_usageExpanded ? '收起每日明细' : '查看每日明细'),
              ),
              if (_usageExpanded)
                Semantics(
                  liveRegion: true,
                  child: Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: colors.paper,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _iso(selected),
                          style: TextStyle(
                            color: colors.ink,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 6),
                        if (record == null)
                          Text(
                            '该日暂无记录',
                            style: TextStyle(
                              color: colors.inkSoft,
                              fontSize: 13,
                            ),
                          )
                        else
                          Wrap(
                            spacing: 16,
                            runSpacing: 6,
                            children: [
                              Text(
                                '输入 ${_number(record.inputTokens)}',
                                style: TextStyle(
                                  color: colors.inkSoft,
                                  fontSize: 13,
                                ),
                              ),
                              Text(
                                '输出 ${_number(record.outputTokens)}',
                                style: TextStyle(
                                  color: colors.inkSoft,
                                  fontSize: 13,
                                ),
                              ),
                              Text(
                                '总量 ${_number(record.totalTokens)}',
                                style: TextStyle(
                                  color: colors.ink,
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ],
                          ),
                      ],
                    ),
                  ),
                ),
            ] else ...[
              const SizedBox(height: 12),
              Text(
                '连接账户或刷新后查看可用记录。未记录的日期不会计为零。',
                style: TextStyle(
                  color: colors.inkSoft,
                  fontSize: 13,
                  height: 1.5,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Color _usageColor(int? total, int peak, DshColors colors) => total == null
      ? colors.surfaceRaised
      : total == 0
      ? colors.line
      : Color.lerp(
          colors.surface,
          colors.ice,
          0.28 + 0.72 * (total / peak).clamp(0.0, 1.0),
        )!;

  Widget _usageDay(
    DateTime date,
    TokenUsageDay? record,
    bool selected,
    int peak,
    DshColors colors,
  ) {
    final total = record?.totalTokens;
    final color = _usageColor(total, peak, colors);
    final label = record == null
        ? '${_iso(date)}，暂无记录'
        : '${_iso(date)}，输入 ${_number(record.inputTokens)}，输出 ${_number(record.outputTokens)}，总量 ${_number(record.totalTokens)} Token';
    return Semantics(
      key: ValueKey('settings-usage-day-${_iso(date)}'),
      button: true,
      enabled: true,
      selected: selected,
      label: label,
      onTap: () => setState(() => _selectedDate = date),
      child: ExcludeSemantics(
        child: SizedBox(
          height: DshDisplayScope.minTargetOf(context),
          width: double.infinity,
          child: InkWell(
            onTap: () => setState(() => _selectedDate = date),
            borderRadius: BorderRadius.circular(8),
            splashFactory: MediaQuery.disableAnimationsOf(context)
                ? NoSplash.splashFactory
                : null,
            child: Center(
              child: Container(
                width: 20,
                height: 20,
                decoration: BoxDecoration(
                  color: color,
                  borderRadius: BorderRadius.circular(5),
                  border: Border.all(
                    color: selected ? colors.ink : colors.line,
                    width: selected ? 2 : 1,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _legend(
    String label,
    Color color,
    DshColors colors, {
    bool outlined = false,
  }) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      ExcludeSemantics(
        child: Container(
          width: 10,
          height: 10,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(3),
            border: outlined ? Border.all(color: colors.line) : null,
          ),
        ),
      ),
      const SizedBox(width: 5),
      Text(label, style: TextStyle(color: colors.inkSoft, fontSize: 11)),
    ],
  );

  Widget _group(
    _SettingsGroup group,
    Set<SettingsDestination> matches,
    DshColors colors,
  ) {
    final rows = group.destinations.where(matches.contains).toList();
    return Padding(
      padding: const EdgeInsets.only(top: 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 0, 8, 6),
            child: Semantics(
              header: true,
              child: Text(
                group.title,
                style: TextStyle(
                  color: colors.inkSoft,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 0.2,
                ),
              ),
            ),
          ),
          Material(
            color: colors.surface,
            clipBehavior: Clip.antiAlias,
            borderRadius: BorderRadius.circular(16),
            child: Column(
              children: [
                for (var index = 0; index < rows.length; index++) ...[
                  _destination(rows[index], colors),
                  if (index + 1 < rows.length)
                    Padding(
                      padding: const EdgeInsetsDirectional.only(start: 54),
                      child: Divider(
                        height: 1,
                        thickness: 0.5,
                        color: colors.line,
                      ),
                    ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _destination(SettingsDestination destination, DshColors colors) {
    final (icon, tint) = switch (destination) {
      SettingsDestination.models => (Icons.tune_rounded, colors.terminal),
      SettingsDestination.plugins => (Icons.extension_rounded, colors.thought),
      SettingsDestination.extensions => (Icons.code_rounded, colors.edit),
      SettingsDestination.containers => (Icons.layers_rounded, colors.warn),
      SettingsDestination.language => (Icons.language_rounded, colors.terminal),
      SettingsDestination.appearance => (Icons.palette_rounded, colors.thought),
      SettingsDestination.permissions => (Icons.shield_outlined, colors.good),
      SettingsDestination.webUi => (
        Icons.open_in_browser_rounded,
        colors.terminal,
      ),
      SettingsDestination.diagnostics => (
        Icons.monitor_heart_outlined,
        colors.edit,
      ),
      SettingsDestination.backup => (Icons.backup_outlined, colors.warn),
      SettingsDestination.about => (Icons.info_outline_rounded, colors.inkSoft),
    };
    return _action(
      key: ValueKey('settings-destination-${destination.name}'),
      label: '${destination.title}，${destination.subtitle}',
      enabled: !_blocked,
      onTap: () {
        if (!_blocked) widget.onOpen(destination);
      },
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 56),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          child: Row(
            children: [
              Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: tint.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(9),
                ),
                child: Icon(icon, size: 18, color: tint),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      destination.title,
                      style: TextStyle(
                        color: colors.ink,
                        fontSize: 15,
                        height: 1.3,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 1),
                    Text(
                      destination.subtitle,
                      style: TextStyle(
                        color: colors.inkSoft,
                        fontSize: 12,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Icon(
                Icons.chevron_right_rounded,
                size: 18,
                color: colors.inkSoft,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _action({
    required Key key,
    required String label,
    required VoidCallback onTap,
    required Widget child,
    bool enabled = true,
  }) => Semantics(
    key: key,
    button: true,
    enabled: enabled,
    label: label,
    onTap: enabled ? onTap : null,
    child: ExcludeSemantics(
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: enabled ? onTap : null,
          borderRadius: BorderRadius.circular(12),
          splashFactory: MediaQuery.disableAnimationsOf(context)
              ? NoSplash.splashFactory
              : null,
          child: ConstrainedBox(
            // The canvas scales what is painted; the tap target stays a real
            // 48dp on screen, so the compact size cannot arm a 43.2dp target.
            constraints: BoxConstraints(
              minHeight: DshDisplayScope.minTargetOf(context),
              minWidth: DshDisplayScope.minTargetOf(context),
            ),
            child: child,
          ),
        ),
      ),
    ),
  );
}

class _SettingsGroup {
  const _SettingsGroup(this.title, this.destinations);
  final String title;
  final List<SettingsDestination> destinations;
}

const _groups = [
  _SettingsGroup('运行与能力', [
    SettingsDestination.models,
    SettingsDestination.plugins,
    SettingsDestination.extensions,
    SettingsDestination.containers,
  ]),
  _SettingsGroup('显示与个性化', [
    SettingsDestination.language,
    SettingsDestination.appearance,
  ]),
  _SettingsGroup('系统与数据', [
    SettingsDestination.permissions,
    SettingsDestination.webUi,
    SettingsDestination.diagnostics,
    SettingsDestination.backup,
  ]),
  _SettingsGroup('更多', [SettingsDestination.about]),
];

DateTime _date(DateTime value) => DateTime(value.year, value.month, value.day);
String _iso(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}-'
    '${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';
String _number(int value) => value.toString().replaceAllMapped(
  RegExp(r'(\d)(?=(\d{3})+(?!\d))'),
  (match) => '${match[1]},',
);

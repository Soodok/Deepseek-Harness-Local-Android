import 'package:material_ui/material_ui.dart';

import '../model/engine_session.dart';

class EngineSessionPicker extends StatefulWidget {
  const EngineSessionPicker({
    super.key,
    required this.load,
    required this.onOpen,
  });
  final Future<List<EngineSession>> Function() load;
  final Future<void> Function(EngineSession) onOpen;
  @override
  State<EngineSessionPicker> createState() => _EngineSessionPickerState();
}

class _EngineSessionPickerState extends State<EngineSessionPicker> {
  late Future<List<EngineSession>> _rows;
  bool _opening = false;
  @override
  void initState() {
    super.initState();
    _rows = Future.sync(widget.load);
  }

  Future<void> _open(EngineSession row) async {
    if (_opening || !row.openable) return;
    setState(() => _opening = true);
    try {
      await widget.onOpen(row);
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('会话未打开：$e')));
    } finally {
      if (mounted) setState(() => _opening = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('本机引擎会话'),
      actions: [
        IconButton(
          tooltip: '刷新',
          onPressed: _opening
              ? null
              : () => setState(() => _rows = Future.sync(widget.load)),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: FutureBuilder<List<EngineSession>>(
      future: _rows,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done)
          return const Center(child: CircularProgressIndicator());
        if (snapshot.hasError)
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('读取失败：' + snapshot.error.toString()),
                  TextButton(
                    onPressed: () =>
                        setState(() => _rows = Future.sync(widget.load)),
                    child: const Text('重试'),
                  ),
                ],
              ),
            ),
          );
        final rows = snapshot.data ?? const <EngineSession>[];
        if (rows.isEmpty) return const Center(child: Text('暂时没有可打开的主会话'));
        return ListView.builder(
          itemCount: rows.length,
          itemBuilder: (context, index) {
            final row = rows[index];
            return ListTile(
              key: ValueKey('engine-session-' + row.id),
              minVerticalPadding: 12,
              title: Text(row.id, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(
                row.openable
                    ? (row.running ? '正在运行' : '可打开') +
                          (row.cwd.isEmpty ? '' : ' · ' + row.cwd)
                    : '需要迁移，请在 Web 版处理',
              ),
              trailing: const Icon(Icons.chevron_right),
              enabled: row.openable && !_opening,
              onTap: () => _open(row),
            );
          },
        );
      },
    ),
  );
}

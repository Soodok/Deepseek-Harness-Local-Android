import 'package:material_ui/material_ui.dart';

import '../bridge/native_workspace_directories.dart';

/// Browse only the app-owned workspace root. It never opens arbitrary app data.
Future<NativeWorkspaceDirectory?> showPrivateWorkspaceDirectorySheet(
  BuildContext context, NativeWorkspaceDirectories directories,
) => showModalBottomSheet<NativeWorkspaceDirectory>(
  context: context, isScrollControlled: true, useSafeArea: true,
  builder: (_) => _PrivateDirectories(directories: directories),
);

class _PrivateDirectories extends StatefulWidget {
  const _PrivateDirectories({required this.directories});
  final NativeWorkspaceDirectories directories;
  @override
  State<_PrivateDirectories> createState() => _PrivateDirectoriesState();
}

class _PrivateDirectoriesState extends State<_PrivateDirectories> {
  NativePrivateWorkspaceListing? _listing;
  String? _error;
  bool _busy = true;
  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load([String? path]) async {
    setState(() { _busy = true; _error = null; });
    try {
      final value = await widget.directories.listPrivate(path);
      if (mounted) setState(() => _listing = value);
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _select() async {
    final listing = _listing;
    if (_busy || listing == null) return;
    setState(() { _busy = true; _error = null; });
    try {
      final selected = await widget.directories.selectPrivate(listing.path);
      if (mounted) Navigator.of(context).pop(selected);
    } on Object catch (error) {
      if (mounted) setState(() { _busy = false; _error = error.toString(); });
    }
  }

  Future<void> _create() async {
    final listing = _listing;
    if (_busy || listing == null) return;
    var draftName = '';
    final name = await showDialog<String>(context: context, builder: (context) => AlertDialog(
      title: const Text('新建私有文件夹'),
      content: TextField(autofocus: true, maxLength: 128,
        onChanged: (value) => draftName = value,
        decoration: const InputDecoration(labelText: '文件夹名称'),
        onSubmitted: (value) => Navigator.of(context).pop(value)),
      actions: [TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.of(context).pop(draftName), child: const Text('新建'))],
    ));
    if (!mounted || name == null) return;
    setState(() { _busy = true; _error = null; });
    try {
      final selected = await widget.directories.createPrivate(listing.path, name);
      if (mounted) Navigator.of(context).pop(selected);
    } on Object catch (error) {
      if (mounted) setState(() { _busy = false; _error = error.toString(); });
    }
  }

  @override
  Widget build(BuildContext context) {
    final listing = _listing;
    return SizedBox(height: MediaQuery.sizeOf(context).height * .72,
      child: Padding(padding: const EdgeInsets.all(20), child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('应用私有工作区', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          const Text('选用已有文件夹，或在这里新建。卸载应用会清除此处的文件。'),
          if (listing != null) ...[
            const SizedBox(height: 12),
            Text(listing.path, maxLines: 3, overflow: TextOverflow.ellipsis),
            if (listing.path != listing.root)
              TextButton.icon(onPressed: _busy ? null : () {
                final end = listing.path.lastIndexOf('/');
                _load(listing.path.substring(0, end));
              }, icon: const Icon(Icons.arrow_upward_rounded), label: const Text('上一级')),
          ],
          if (_busy) const LinearProgressIndicator(),
          if (_error != null) Padding(padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
          Expanded(child: listing == null
            ? Center(child: TextButton(onPressed: _busy ? null : () => _load(), child: const Text('重试读取目录')))
            : listing.directories.isEmpty
              ? const Center(child: Text('这里还没有文件夹，可直接选用当前目录或新建'))
              : ListView.builder(itemCount: listing.directories.length, itemBuilder: (context, index) {
                final row = listing.directories[index];
                return ListTile(leading: const Icon(Icons.folder_outlined), title: Text(row.name),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: _busy ? null : () => _load(row.path));
              })),
          Wrap(alignment: WrapAlignment.end, spacing: 12, runSpacing: 8, children: [
            OutlinedButton.icon(onPressed: _busy || listing == null ? null : _create,
              icon: const Icon(Icons.create_new_folder_outlined), label: const Text('新建文件夹')),
            FilledButton(onPressed: _busy || listing == null ? null : _select, child: const Text('使用此目录')),
          ]),
        ],
      )),
    );
  }
}

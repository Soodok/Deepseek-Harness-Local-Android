import 'dart:async';

import 'composer_draft.dart';

/// Reads the text saved for one Session id; null when nothing was ever saved.
typedef SessionDraftRead = Future<String?> Function(String id);

/// Stores [text] for one Session id; false when the storage refused it.
typedef SessionDraftWrite = Future<bool> Function(String id, String text);

/// The draft text of every Session, kept outside the text field.
///
/// One [ComposerDraft] per Session id, read once from the injected backend
/// (preferences, a Host channel, a file — the store does not care which) and
/// written back through [flush]. Three rules make it safe to call from a screen
/// that can switch Sessions at any moment:
///
/// * One write runs at a time across every Session, so a single-file or
///   single-channel backend never sees two writes overlap.
/// * A flush asked for while a write is running joins that write instead of
///   starting a second one; the run then stores the newest text.
/// * A refused or failed write never drops text: it stays in memory, the
///   Session counts as unsaved, and the next flush retries it.
///
/// The store does not guess when the text changed: [flush] stores the current
/// text only when it differs from the last stored one, so a caller may flush as
/// often as it likes (a Session switch, the app going to the background, every
/// keystroke) and an unchanged text costs nothing.
///
/// [attach] allows a screen to use the same draft before storage has answered.
/// Any text entered during that read wins over the stored text.
class SessionDrafts {
  SessionDrafts({
    required this.read,
    required this.write,
    this.autosave = false,
  });

  final bool autosave;

  /// Loads the saved text of a Session.
  final SessionDraftRead read;

  /// Stores the text of a Session; false means "not stored, keep it in memory".
  final SessionDraftWrite write;

  final Map<String, ComposerDraft> _drafts = <String, ComposerDraft>{};
  final Map<String, Future<ComposerDraft>> _loading =
      <String, Future<ComposerDraft>>{};
  final Map<String, String> _saved = <String, String>{};
  final Map<String, String> _wanted = <String, String>{};
  final Map<String, Future<bool>> _running = <String, Future<bool>>{};
  final Set<ComposerDraft> _owned = <ComposerDraft>{};
  Future<void> _tail = Future<void>.value();
  bool _disposed = false;

  /// The draft of [id], loaded from the backend the first time it is asked for.
  ///
  /// Asking twice returns the same object, and two asks while the read is still
  /// in flight share that one read, so a rebuild cannot race a load. A saved
  /// draft that cannot be read opens empty rather than refusing to type.
  Future<ComposerDraft> open(String id) {
    final draft = attach(id);
    return _loading[id] ?? Future<ComposerDraft>.value(draft);
  }

  /// Returns the stable draft immediately and starts its first read once.
  ComposerDraft attach(String id) {
    if (_disposed) throw StateError('SessionDrafts was disposed');
    final existing = _drafts[id];
    if (existing != null) return existing;
    var edited = false;
    final draft = ComposerDraft(onTextChanged: (_) {
      edited = true;
      if (autosave && !_loading.containsKey(id)) unawaited(flush(id));
    });
    _drafts[id] = draft;
    _owned.add(draft);
    _loading[id] = _load(id, draft, () => edited);
    return draft;
  }

  /// Stores the current text of [id] when it differs from the stored one.
  ///
  /// True once that text (or newer text the person typed meanwhile) is stored.
  /// A refused write leaves the text in memory and the Session unsaved, so a
  /// later flush retries it; nothing is dropped on a failure.
  Future<bool> flush(String id) {
    if (_disposed) return Future<bool>.value(false);
    final draft = _drafts[id];
    if (draft == null) return Future<bool>.value(true);
    final loading = _loading[id];
    if (loading != null) return loading.then((_) => flush(id));
    _wanted[id] = draft.text;
    final running = _running[id];
    if (running != null) return running;
    final completer = Completer<bool>();
    final future = completer.future;
    _running[id] = future;
    _drive(id, completer);
    return future;
  }

  /// Stores every open Session. False when at least one text was not stored.
  Future<bool> flushAll() async {
    var stored = true;
    for (final id in _drafts.keys.toList(growable: false)) {
      if (!await flush(id)) stored = false;
    }
    return stored;
  }

  /// Whether [id] holds text that is not stored.
  bool hasUnsaved(String id) {
    final draft = _drafts[id];
    if (draft == null) return false;
    return _saved[id] != draft.text;
  }

  /// Stores and forgets [id]. False when its text could not be stored, in which
  /// case the Session stays open so the text is not lost.
  Future<bool> close(String id) async {
    final loading = _loading[id];
    if (loading != null) {
      try {
        await loading;
      } on Object {
        // A failed load has no text worth keeping; there is nothing to store.
        return true;
      }
    }
    final draft = _drafts[id];
    if (draft == null) return true;
    if (!await flush(id)) return false;
    _drafts.remove(id);
    _saved.remove(id);
    _wanted.remove(id);
    return true;
  }

  /// Releases every draft this store created.
  ///
  /// Only a draft created here is disposed here: a draft the shell created
  /// itself is still in use elsewhere.
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    for (final draft in _owned) {
      draft.dispose();
    }
    _owned.clear();
    _drafts.clear();
    _saved.clear();
    _wanted.clear();
    _loading.clear();
  }

  Future<ComposerDraft> _load(
    String id,
    ComposerDraft draft,
    bool Function() edited,
  ) async {
    var saved = '';
    try {
      saved = await Future<String?>.sync(() => read(id)) ?? '';
    } on Object {
      // A draft that cannot be read must not keep the person from typing: start
      // empty, and let the next stored text replace whatever is on disk.
      saved = '';
    }
    if (_disposed) return draft;
    _loading.remove(id);
    _saved[id] = saved;
    if (!edited()) {
      draft.hydrate(saved);
    } else if (autosave) {
      unawaited(flush(id));
    }
    return draft;
  }

  /// Writes [id] until no newer text is waiting, then reports the outcome.
  Future<void> _drive(String id, Completer<bool> completer) async {
    var stored = true;
    try {
      while (true) {
        final text = _wanted[id];
        if (text == null) break;
        _wanted.remove(id);
        if (_saved[id] == text) continue;
        if (!await _persist(id, text)) {
          stored = false;
          break;
        }
        _saved[id] = text;
      }
    } finally {
      // No await runs between the last look at _wanted and this point, so any
      // flush arriving after it starts a fresh run instead of being dropped.
      _running.remove(id);
      if (!completer.isCompleted) completer.complete(stored);
    }
  }

  /// One write, behind every other write of this store.
  Future<bool> _persist(String id, String text) {
    final done = Completer<bool>();
    _tail = _tail.then((_) async {
      var ok = true;
      try {
        ok = await write(id, text);
      } on Object {
        ok = false; // Keep the text in memory; the next flush retries it.
      }
      if (!done.isCompleted) done.complete(ok);
    });
    return done.future;
  }
}

import 'dart:async';

import 'package:flutter_test/flutter_test.dart';

import 'package:dsh_native_chat/src/state/composer_draft.dart';
import 'package:dsh_native_chat/src/state/session_drafts.dart';

typedef _Write = ({String id, String text});

/// A backend that keeps drafts in memory and lets a test hold a read or a write
/// open, so the cases that only happen while one is in flight can be described.
class _Backend {
  _Backend([Map<String, String>? seed]) {
    if (seed != null) texts.addAll(seed);
  }

  final Map<String, String> texts = <String, String>{};
  final List<_Write> writes = <_Write>[];
  Future<void>? readGate;
  Future<void>? writeGate;
  bool accept = true;
  bool failRead = false;
  bool failWrite = false;
  int reads = 0;
  int inFlight = 0;
  int maxInFlight = 0;

  Future<String?> read(String id) async {
    reads++;
    final gate = readGate;
    if (gate != null) await gate;
    if (failRead) throw StateError('draft unreadable');
    return texts[id];
  }

  Future<bool> write(String id, String text) async {
    writes.add((id: id, text: text));
    inFlight++;
    if (inFlight > maxInFlight) maxInFlight = inFlight;
    try {
      final gate = writeGate;
      if (gate != null) await gate;
      if (failWrite) throw StateError('draft not writable');
      if (!accept) return false;
      texts[id] = text;
      return true;
    } finally {
      inFlight--;
    }
  }
}

void main() {
  test('opens the draft saved for that Session', () async {
    final backend = _Backend({'a': '还没发出去的半句'});
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);

    final draft = await store.open('a');

    expect(draft.text, '还没发出去的半句');
    // Adopting a saved draft is not an edit: no revision bump, no write back.
    expect(draft.revision, 0);
    expect(backend.writes, isEmpty);
  });

  test('hands the same draft object back for an open Session', () async {
    final backend = _Backend({'a': '保存过的'});
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);

    final first = await store.open('a');
    final second = await store.open('a');

    expect(identical(first, second), isTrue);
    expect(backend.reads, 1);
  });

  test('two opens while the read is still running share one read', () async {
    final backend = _Backend({'a': '慢读出来的'});
    final gate = Completer<void>();
    backend.readGate = gate.future;
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);

    final first = store.open('a');
    final second = store.open('a');
    gate.complete();
    final a = await first;
    final b = await second;

    expect(identical(a, b), isTrue);
    expect(backend.reads, 1);
    expect(a.text, '慢读出来的');
  });

  test('keeps one draft per Session id', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);

    final a = await store.open('a');
    final b = await store.open('b');
    a.record('写给 a 的');

    expect(store.hasUnsaved('a'), isTrue);
    expect(store.hasUnsaved('b'), isFalse);
    expect(await store.flush('a'), isTrue);

    expect(a.text, '写给 a 的');
    expect(b.text, '');
    expect(backend.texts, {'a': '写给 a 的'});
    expect(backend.writes.map((write) => write.id).toSet(), {'a'});
  });

  test('saves typing, appending and clearing', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final draft = await store.open('a');
    expect(backend.writes, isEmpty);

    draft.record('打');
    expect(await store.flush('a'), isTrue);
    expect(backend.texts['a'], '打');

    draft.append('补一句');
    expect(await store.flush('a'), isTrue);
    expect(backend.texts['a'], '打\n\n补一句');

    draft.clear();
    expect(await store.flush('a'), isTrue);
    expect(backend.texts['a'], '');
    expect(store.hasUnsaved('a'), isFalse);
  });

  test('does not save the same text twice', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final draft = await store.open('a');

    draft.record('同样的话');
    expect(await store.flush('a'), isTrue);
    final saved = backend.writes.length;

    draft.record('同样的话');
    expect(await store.flush('a'), isTrue);

    expect(backend.writes.length, saved);
    expect(backend.texts['a'], '同样的话');
  });

  test('writes one at a time and stores the newest text', () async {
    final backend = _Backend();
    final gate = Completer<void>();
    backend.writeGate = gate.future;
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final draft = await store.open('a');

    draft.record('一');
    final first = store.flush('a');
    await pumpEventQueue();
    expect(backend.writes.length, 1);

    // Asking again while the first write is in flight must join it, not start
    // a second write on top of it; the run then stores the newest text.
    draft.record('三');
    final second = store.flush('a');
    await pumpEventQueue();
    expect(backend.writes.length, 1);

    gate.complete();
    expect(await first, isTrue);
    expect(await second, isTrue);

    expect(backend.maxInFlight, 1);
    expect(backend.texts['a'], '三');
    expect(backend.writes.last.text, '三');
  });

  test('two Sessions never write at the same time', () async {
    final backend = _Backend();
    final gate = Completer<void>();
    backend.writeGate = gate.future;
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final a = await store.open('a');
    final b = await store.open('b');

    a.record('甲');
    b.record('乙');
    // Both flushes start before either write can finish: the second must wait
    // for the first instead of reaching the backend at the same time.
    final alpha = store.flush('a');
    final beta = store.flush('b');
    await pumpEventQueue();
    expect(backend.writes.length, 1);
    expect(backend.maxInFlight, 1);

    gate.complete();
    expect(await alpha, isTrue);
    expect(await beta, isTrue);

    expect(backend.maxInFlight, 1);
    expect(backend.texts, {'a': '甲', 'b': '乙'});
  });

  test('a refused write keeps the text and flush retries it', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final draft = await store.open('a');

    backend.accept = false;
    draft.record('别忘了这句');
    expect(await store.flush('a'), isFalse);
    expect(draft.text, '别忘了这句');
    expect(backend.texts['a'], isNull);
    expect(store.hasUnsaved('a'), isTrue);

    backend.accept = true;
    expect(await store.flush('a'), isTrue);
    expect(backend.texts['a'], '别忘了这句');
    expect(store.hasUnsaved('a'), isFalse);
  });

  test('a write that throws is a refusal, not a save', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final draft = await store.open('a');

    backend.failWrite = true;
    draft.record('会失败的一句');
    expect(await store.flush('a'), isFalse);
    expect(draft.text, '会失败的一句');
    expect(backend.texts, isEmpty);

    backend.failWrite = false;
    expect(await store.flush('a'), isTrue);
    expect(backend.texts['a'], '会失败的一句');
  });

  test('flushAll reports one refusal among several Sessions', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final a = await store.open('a');
    final b = await store.open('b');
    a.record('a 的');
    b.record('b 的');

    backend.accept = false;
    expect(await store.flushAll(), isFalse);
    backend.accept = true;
    expect(await store.flushAll(), isTrue);
    expect(backend.texts, {'a': 'a 的', 'b': 'b 的'});
  });

  test('an unreadable saved draft opens empty and can still be typed', () async {
    final backend = _Backend({'a': '读不出来的'});
    backend.failRead = true;
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);

    final draft = await store.open('a');
    expect(draft.text, '');

    backend.failRead = false;
    draft.record('新的开头');
    expect(await store.flush('a'), isTrue);
    expect(backend.texts['a'], '新的开头');
  });

  test('a new store over the same backend reopens the last draft', () async {
    final backend = _Backend();
    final first = SessionDrafts(read: backend.read, write: backend.write);
    final draft = await first.open('a');
    draft.record('写了一半，面板先被关掉');
    expect(await first.flush('a'), isTrue);
    first.dispose();

    final second = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(second.dispose);
    final reopened = await second.open('a');

    expect(reopened.text, '写了一半，面板先被关掉');
    expect(reopened.revision, 0);
    expect(backend.writes.length, 1);
  });

  test('closing a Session saves it and forgets the object', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final draft = await store.open('a');
    draft.record('关掉之前写的');

    expect(await store.close('a'), isTrue);
    expect(backend.texts['a'], '关掉之前写的');

    final reopened = await store.open('a');
    expect(identical(reopened, draft), isFalse);
    expect(reopened.text, '关掉之前写的');
  });

  test('a Session whose text cannot be saved stays open', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final draft = await store.open('a');
    draft.record('还不能丢');

    backend.accept = false;
    expect(await store.close('a'), isFalse);
    expect(await store.open('a'), same(draft));
    expect(draft.text, '还不能丢');

    backend.accept = true;
    expect(await store.close('a'), isTrue);
    expect(backend.texts['a'], '还不能丢');
  });

  test('editing an old message never touches the saved draft', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);
    final draft = await store.open('a');
    draft.record('我的草稿');
    expect(await store.flush('a'), isTrue);

    // Editing an old message types into its own buffer, not the store's draft.
    final edit = ComposerDraft();
    addTearDown(edit.dispose);
    edit.replace('那条旧消息的原文');

    expect(edit.text, '那条旧消息的原文');
    expect(store.hasUnsaved('a'), isFalse);
    expect(await store.flush('a'), isTrue);
    expect(backend.texts['a'], '我的草稿');
    expect(backend.writes.last.text, '我的草稿');
    expect(await store.open('a'), same(draft));
  });

  test('flush before the Session is open stores nothing', () async {
    final backend = _Backend();
    final store = SessionDrafts(read: backend.read, write: backend.write);
    addTearDown(store.dispose);

    expect(await store.flush('a'), isTrue);
    expect(backend.writes, isEmpty);
    expect(await store.close('a'), isTrue);
    expect(backend.reads, 0);
  });
}

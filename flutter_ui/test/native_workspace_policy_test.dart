import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_native_chat/src/bridge/native_workspace_policy.dart';
import 'package:dsh_native_chat/src/model/engine_workspace.dart';

const alpha = WorkspaceTarget.workspace('alpha', '/engine/alpha');
const bravo = WorkspaceTarget.workspace('bravo', '/engine/bravo');
const home = WorkspaceTarget.home();
const rows = [
  EngineWorkspace(
    id: 'alpha',
    title: 'Alpha',
    path: '/engine/alpha',
    sessionIds: [],
  ),
  EngineWorkspace(
    id: 'bravo',
    title: 'Bravo',
    path: '/engine/bravo',
    sessionIds: [],
  ),
];
void main() {
  Map<String, Object?> receipt(
    WorkspaceTarget? target, {
    String domain = 'domain-a',
    String generation = 'boot-1',
  }) => {
    'domain': domain,
    'generation': generation,
    'target': target?.toWire(),
  };
  NativeWorkspacePolicy policy({
    Future<Object?> Function()? read,
    Future<bool> Function(WorkspaceTarget)? write,
    Future<bool> Function()? remove,
    String domain = 'domain-a',
  }) => NativeWorkspacePolicy(
    domain: domain,
    generation: 'boot-1',
    read: read ?? () async => receipt(alpha),
    write: write ?? (_) async => true,
    remove: remove ?? () async => false,
  );
  test(
    'precedence explicit Home or group exceeds override and persistent default',
    () async {
      final p = policy();
      await p.refresh();
      p.override = bravo;
      final group = p.resolve(rows: rows, online: true, explicit: alpha);
      expect(group.workspace, rows.first);
      p.consume(group);
      expect(p.override, bravo);
      final h = p.resolve(rows: rows, online: true, explicit: home);
      expect(h.workspace, isNull);
      p.consume(h);
      expect(p.override, bravo);
      final next = p.resolve(rows: rows, online: true);
      expect(next.workspace, rows.last);
      p.consume(next);
      expect(p.override, isNull);
      expect(p.resolve(rows: rows, online: true).workspace, rows.first);
      expect(p.resolve(rows: rows, online: true).workspace, rows.first);
    },
  );
  test(
    'explicit one-time Home is distinct from none and failure retains it',
    () async {
      final p = policy();
      await p.refresh();
      p.override = home;
      final first = p.resolve(rows: rows, online: true);
      expect(first.workspace, isNull);
      expect(p.resolve(rows: rows, online: true).workspace, isNull);
      p.consume(first);
      expect(p.resolve(rows: rows, online: true).workspace, rows.first);
      expect(p.defaultTarget, alpha);
    },
  );
  test('only the actual override snapshot is consumed', () async {
    final p = policy();
    await p.refresh();
    p.override = alpha;
    final request = p.resolve(rows: rows, online: true);
    p.override = bravo;
    p.consume(request);
    expect(p.override, bravo);
  });
  test('pending preferences and catalogue fail closed but explicit Home remains reachable', () async {
    final wait = Completer<Object?>();
    final p = policy(read: () => wait.future);
    final loading = p.refresh();
    expect(() => p.resolve(rows: rows, online: true), throwsStateError);
    expect(
      p.resolve(rows: [], online: false, explicit: home).workspace,
      isNull,
    );
    wait.complete(receipt(alpha));
    await loading;
    expect(() => p.resolve(rows: rows, online: false), throwsStateError);
    expect(() => p.resolve(rows: [], online: true), throwsStateError);
    expect(
      () => p.resolve(
        rows: [
          const EngineWorkspace(
            id: 'alpha',
            title: 'moved',
            path: '/moved',
            sessionIds: [],
          ),
        ],
        online: true,
      ),
      throwsStateError,
    );
  });
  test(
    'confirmed unset is different from failed or unknown preferences',
    () async {
      final p = policy(read: () async => receipt(null));
      await p.refresh();
      expect(p.known, isTrue);
      expect(p.resolve(rows: rows, online: true).workspace, isNull);
      final bad = policy(read: () async => null);
      await bad.refresh();
      expect(bad.known, isFalse);
      expect(() => bad.resolve(rows: rows, online: true), throwsStateError);
      final unknown = policy(domain: '');
      await unknown.refresh();
      expect(unknown.known, isFalse);
      expect(await unknown.save(alpha), isFalse);
    },
  );
  test(
    'same id in another data domain and stale generation receipts are rejected',
    () async {
      for (final value in [
        receipt(alpha, domain: 'domain-b'),
        receipt(alpha, generation: 'old-boot'),
      ]) {
        final p = policy(read: () async => value);
        await p.refresh();
        expect(p.known, isFalse);
        expect(() => p.resolve(rows: rows, online: true), throwsStateError);
      }
    },
  );
  test('save requires successful write and identical readback; cold instance rereads', () async {
    WorkspaceTarget? disk;
    final p = policy(
      read: () async => receipt(disk),
      write: (v) async {
        disk = v;
        return true;
      },
    );
    await p.refresh();
    expect(await p.save(alpha), isTrue);
    expect(p.defaultTarget, alpha);
    final cold = policy(read: () async => receipt(disk));
    await cold.refresh();
    expect(cold.resolve(rows: rows, online: true).workspace, rows.first);
    final failed = policy(
      read: () async => receipt(null),
      write: (_) async => false,
    );
    expect(await failed.save(alpha), isFalse);
    expect(failed.known, isFalse);
    final mismatch = policy(read: () async => receipt(bravo));
    expect(await mismatch.save(alpha), isFalse);
    expect(mismatch.known, isFalse);
  });
  test('clear uses one remove zero writes and one authoritative reread without consuming override', () async {
    WorkspaceTarget? disk = alpha;
    var reads = 0, removes = 0, writes = 0;
    final p = policy(
      read: () async {
        reads++;
        return receipt(disk);
      },
      write: (_) async {
        writes++;
        return true;
      },
      remove: () async {
        removes++;
        disk = null;
        return true;
      },
    );
    await p.refresh();
    p.override = bravo;
    final before = p.override;
    expect(await p.clear(), isTrue);
    expect([removes, writes, reads], [1, 0, 2]);
    expect(p.known, isTrue);
    expect(p.defaultTarget, isNull);
    expect(identical(p.override, before), isTrue);
    final cold = policy(read: () async => receipt(disk));
    await cold.refresh();
    expect(cold.known, isTrue);
    expect(cold.defaultTarget, isNull);
  });

  test('untrusted clear makes zero remove write and read calls', () async {
    var reads = 0, removes = 0, writes = 0;
    final p = policy(
      domain: '',
      read: () async {
        reads++;
        return receipt(null);
      },
      write: (_) async {
        writes++;
        return true;
      },
      remove: () async {
        removes++;
        return true;
      },
    );
    expect(await p.clear(), isFalse);
    expect([removes, writes, reads], [0, 0, 0]);
    expect(p.known, isFalse);
  });

  test('old clear acknowledgement cannot override a newer authoritative preference read', () async {
    var reads = 0;
    final old = Completer<bool>();
    final p = policy(
      read: () async {
        reads++;
        return receipt(bravo);
      },
      remove: () => old.future,
    );
    final clearing = p.clear();
    await p.refresh();
    old.complete(true);
    expect(await clearing, isFalse);
    expect(reads, 1);
    expect(p.known, isTrue);
    expect(p.defaultTarget, bravo);
  });

  // Source contract only: real SharedPreferences execution needs Android validation.
  test('native clear source contract removes only scoped key with commit and absent readback', () {
    final source = File(
      '../app/src/main/java/app/dsh/mobile/NativeChatActivity.kt',
    ).readAsStringSync();
    final bridge = source.substring(
      source.indexOf('"readDefaultWorkspace",'),
      source.indexOf('"readAppearance" ->'),
    );
    expect(bridge, contains('"clearDefaultWorkspace"'));
    expect(bridge, contains('val key = "defaultWorkspace.v1." + scope.domain'));
    final start = bridge.indexOf('} else if (clearing) {');
    expect(start, greaterThanOrEqualTo(0));
    final branch = bridge.substring(
      start,
      bridge.indexOf('} else {', start + 1),
    );
    expect(branch, contains('prefs.edit().remove(key).commit()'));
    expect(branch, contains('committed && !prefs.contains(key) &&'));
    expect(
      branch,
      contains('supervisorOrNull()?.nativeWorkspaceScope() == scope'),
    );
    expect(bridge.split('.remove(').length - 1, 1);
    expect(bridge.split('.putString(').length - 1, 1);
    expect(bridge, isNot(contains('.clear(')));
    expect(
      bridge.indexOf(
        'check(supervisorOrNull()?.nativeWorkspaceScope() == scope)',
      ),
      lessThan(bridge.indexOf('prefs.edit()')),
    );
  });

  test('old read completion cannot replace newer readback', () async {
    final old = Completer<Object?>();
    var reads = 0;
    final p = policy(
      read: () => reads++ == 0 ? old.future : Future.value(receipt(bravo)),
    );
    final loading = p.refresh();
    expect(await p.save(bravo), isTrue);
    old.complete(receipt(alpha));
    await loading;
    expect(p.defaultTarget, bravo);
  });
}

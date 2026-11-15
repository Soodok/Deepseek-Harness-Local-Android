import 'dart:async';

import 'package:dsh_native_chat/src/model/subagent_model.dart';
import 'package:dsh_native_chat/src/model/chat_model.dart';
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/transport/dsh_rpc_connection.dart';
import 'package:dsh_native_chat/src/transport/native_subagent_transport.dart';
import 'package:flutter_test/flutter_test.dart';

import 'dsh_rpc_connection_test.dart' show LoopbackDshHost, RpcRequest;

Map<String, Object?> _request(RpcRequest r) => Map<String, Object?>.from(
  ((r.envelope['payload'] as Map)['args'] as Map)['request'] as Map,
);
Map<String, Object?> _record(
  int seq,
  String type,
  Map<String, Object?> data, {
  Object surfaceOp = 'append',
}) => {
  'type': 'event',
  'event': {
    'type': type,
    'seq': seq,
    'time': seq * 1000,
    'data': data,
    'surfaceOp': surfaceOp,
  },
};
Map<String, Object?> _user(int seq, String text) =>
    _record(seq, 'user/message', {
      'id': 'u$seq',
      'source': {'kind': 'user'},
      'content': [
        {'type': 'text', 'text': text},
      ],
    });
Map<String, Object?> _assistant(int seq, String text) =>
    _record(seq, 'assistant/message', {
      'turn': 1,
      'step': 1,
      'message': {
        'id': 'a$seq',
        'content': [
          {'type': 'text', 'text': text},
        ],
      },
    });
Map<String, Object?> _snapshot(
  int cursor,
  List<Map<String, Object?>> records, {
  Map<String, Object?>? assistant,
}) => {
  'type': 'snapshot',
  'header': {'version': 1, 'id': 'child', 'createdAt': 0, 'isSeeded': false},
  'cursor': cursor,
  'records': records,
  'hasMore': false,
  'projections': {'seq': cursor, 'values': {}},
  'assistantStream': assistant ?? {'revision': 0},
};

void main() {
  const address = SubagentAddress(
    parentSessionId: 'parent',
    childSessionId: 'child',
    mode: SubagentMode.continuable,
  );
  late LoopbackDshHost host;
  late DshRpcConnection rpc;
  late NativeSubagentTransport service;
  setUp(() async {
    host = LoopbackDshHost();
    await host.start();
    rpc = DshRpcConnection(webUrl: host.webUrl);
    service = NativeSubagentTransport(rpc);
    host.onCall = (r) async {
      if (r.endpoint == 'session/state')
        return {
          'cursor': 0,
          'active': false,
          'running': false,
          'queue': null,
          'pendingInput': [],
        };
      return null;
    };
  });
  tearDown(() async {
    await rpc.close();
    await host.close();
  });

  Future<void> withActive(
    Future<void> Function(
      StreamIterator<SubagentConversation>,
      void Function(Map<String, Object?>),
    )
    check, {
    bool code = false,
  }) async {
    final iterator = StreamIterator(service.watchSubagent(address));
    final first = iterator.moveNext();
    final open = await host.waitFrame((r) => r.frame['type'] == 'open');
    host.item(
      open,
      _snapshot(
        1,
        [_user(1, 'question')],
        assistant: {
          'revision': 1,
          'activeAttempt': {
            'attemptId': 'guard',
            'startedAfterSeq': 1,
            'turn': 1,
            'step': 1,
            'nextIndex': 1,
            'stream': [
              {
                'type': code ? 'code-chunks' : 'text-chunks',
                'time0': 1,
                'index': 0,
                'dt': [],
                'texts': [code ? 'print(' : 'LIVE'],
                if (code) 'language': 'dart',
              },
            ],
          },
        },
      ),
    );
    await first;
    await check(iterator, (frame) => host.item(open, frame));
    await iterator.cancel();
  }

  Map<String, Object?> frame(
    String type,
    int revision, {
    Map<String, Object?>? outcome,
    Map<String, Object?>? chunk,
  }) => {
    'type': 'assistant-stream',
    'frame': {
      'type': type,
      'attemptId': 'guard',
      'revision': revision,
      'index': 1,
      'outcome': ?outcome,
      'chunk': ?chunk,
    },
  };
  test('child guard rejects a committed receipt for a user event', () async {
    await withActive((iterator, emit) async {
      final rejected = expectLater(
        iterator.moveNext(),
        throwsA(
          isA<DshRpcException>().having(
            (e) => e.code,
            'code',
            'subagent/sequence-gap',
          ),
        ),
      );
      emit(
        frame(
          'end',
          2,
          outcome: {'kind': 'committed', 'eventType': 'user/message', 'seq': 1},
        ),
      );
      await rejected;
    });
  });
  test('child guard keeps live text for an unrelated durable assistant and rejects its receipt', () async {
    await withActive((iterator, emit) async {
      emit(
        _record(2, 'assistant/message', {
          'turn': 2,
          'step': 1,
          'message': {
            'id': 'other',
            'content': [
              {'type': 'text', 'text': 'OTHER'},
            ],
          },
        }),
      );
      await iterator.moveNext();
      expect(iterator.current.messages.map((m) => m.text), contains('LIVE'));
      final rejected = expectLater(
        iterator.moveNext(),
        throwsA(isA<DshRpcException>()),
      );
      emit(
        frame(
          'end',
          2,
          outcome: {
            'kind': 'committed',
            'eventType': 'assistant/message',
            'seq': 2,
          },
        ),
      );
      await rejected;
    });
  });
  test(
    'child guard rejects a second start before the active attempt ends',
    () async {
      await withActive((iterator, emit) async {
        final rejected = expectLater(
          iterator.moveNext(),
          throwsA(
            isA<DshRpcException>().having(
              (e) => e.code,
              'code',
              'subagent/sequence-gap',
            ),
          ),
        );
        emit({
          'type': 'assistant-stream',
          'frame': {
            'type': 'start',
            'revision': 2,
            'attemptId': 'other',
            'startedAfterSeq': 1,
            'turn': 1,
            'step': 2,
          },
        });
        await rejected;
      });
    },
  );
  test(
    'child guard preserves baseline and incremental code as assistant content',
    () async {
      await withActive((iterator, emit) async {
        expect(iterator.current.messages.last.role, 'assistant');
        expect(
          iterator.current.messages.last.text,
          contains('dart' + String.fromCharCode(10) + 'print('),
        );
        emit(
          frame(
            'chunk',
            2,
            chunk: {'type': 'code-delta', 'index': 0, 'text': '1);'},
          ),
        );
        await iterator.moveNext();
        expect(iterator.current.messages.last.role, 'assistant');
        expect(
          iterator.current.messages.last.text,
          contains('dart' + String.fromCharCode(10) + 'print(1);'),
        );
      }, code: true);
    },
  );
  test('child guard rejects an empty nonempty-cursor history while allowing bounded suffixes', () async {
    host.onCall = (r) async => r.endpoint == 'session/state'
        ? {'running': false}
        : {
            'cursor': 5,
            'hasMore': true,
            'records': [_assistant(5, 'bounded')],
          };
    expect(
      (await service.readSubagent(address)).messages.single.text,
      'bounded',
    );
    host.onCall = (r) async => r.endpoint == 'session/state'
        ? {'running': false}
        : {'cursor': 5, 'hasMore': false, 'records': []};
    await expectLater(
      service.readSubagent(address),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'subagent/sequence-gap',
        ),
      ),
    );
  });
  test('child guard refuses a self-address before sending', () {
    expect(
      const SubagentAddress(
        parentSessionId: 'same',
        childSessionId: 'same',
        mode: SubagentMode.continuable,
      ).canSend,
      isFalse,
    );
  });

  test('real tool calls, failed results and reasoning keep structured detail and original ownership', () async {
    host.onCall = (r) async => r.endpoint == 'session/state'
        ? {'running': false}
        : {
            'cursor': 4,
            'hasMore': false,
            'records': [
              _record(1, 'assistant/message', {
                'message': {
                  'id': 'answer',
                  'content': [
                    {'type': 'text', 'text': '正在核对'},
                    {'type': 'reasoning', 'text': '真实思考内容'},
                  ],
                },
              }),
              _record(2, 'tool/call', {
                'callId': 'shell',
                'name': 'bash',
                'arguments': '{"command":"pwd","workdir":"/ws"}',
              }),
              _assistant(3, '后来的回复不应重新绑定工具'),
              _record(4, 'tool/result', {
                'message': {
                  'source': {'callId': 'shell'},
                  'isError': true,
                  'content': [
                    {'type': 'text', 'text': '真实失败输出'},
                  ],
                },
                'meta': {
                  'stdout': '真实stdout',
                  'stderr': '真实stderr',
                  'exitCode': 7,
                },
              }),
            ],
          };
    final view = await service.readSubagent(address);
    expect(view.messages.map((m) => m.text), ['正在核对', '后来的回复不应重新绑定工具']);
    final reasoning = view.tools.singleWhere(
      (t) => t.content?.reasoning == '真实思考内容',
    );
    expect(reasoning.messageId, 'answer');
    final tool = view.tools.singleWhere((t) => t.id == 'shell');
    expect(tool.messageId, 'answer');
    expect(tool.status, ToolStatus.failed);
    expect(tool.elapsedMs, 2000);
    expect(tool.detail, '真实失败输出');
    expect(tool.content!.command!.command, 'pwd');
    expect(tool.content!.command!.cwd, '/ws');
    expect(tool.content!.command!.stdout, '真实stdout');
    expect(tool.content!.command!.stderr, '真实stderr');
    expect(tool.content!.command!.exitCode, 7);
  });

  test('surface replacements remove old tool records and bounded results stay visible', () async {
    host.onCall = (r) async => r.endpoint == 'session/state'
        ? {'running': false}
        : {
            'cursor': 4,
            'hasMore': false,
            'records': [
              _assistant(1, '旧正文'),
              _record(2, 'tool/call', {
                'callId': 'old',
                'name': 'bash',
                'arguments': '{}',
              }),
              _record(
                3,
                'assistant/message',
                {
                  'message': {
                    'id': 'new',
                    'content': [
                      {'type': 'text', 'text': '替换正文'},
                    ],
                  },
                },
                surfaceOp: {'op': 'replace', 'startSeq': 1, 'endSeq': 2},
              ),
              _record(4, 'tool/result', {
                'message': {
                  'source': {'callId': 'bounded'},
                  'content': [
                    {'type': 'text', 'text': '页首以前的工具结果'},
                  ],
                },
              }),
            ],
          };
    final view = await service.readSubagent(address);
    expect(view.messages.single.text, '替换正文');
    expect(view.tools.map((t) => t.id), ['bounded']);
    expect(view.tools.single.name, '未知工具');
    expect(view.tools.single.detail, '页首以前的工具结果');
  });

  test('live tool deltas keep complete arguments and are replaced by the durable call', () async {
    final iterator = StreamIterator(service.watchSubagent(address));
    final first = iterator.moveNext();
    final open = await host.waitFrame((r) => r.frame['type'] == 'open');
    host.item(
      open,
      _snapshot(
        1,
        [_user(1, '核对工具')],
        assistant: {
          'revision': 1,
          'activeAttempt': {
            'attemptId': 'live-tool',
            'turn': 1,
            'step': 1,
            'startedAfterSeq': 1,
            'nextIndex': 1,
            'stream': [
              {
                'type': 'tool-call-chunks',
                'index': 0,
                'id': 'call-live',
                'name': 'bash',
                'args': ['{"command":'],
              },
            ],
          },
        },
      ),
    );
    await first;
    expect(iterator.current.tools.single.id, 'call-live');
    expect(iterator.current.tools.single.status, ToolStatus.running);
    host.item(open, {
      'type': 'assistant-stream',
      'frame': {
        'type': 'chunk',
        'attemptId': 'live-tool',
        'revision': 2,
        'index': 1,
        'chunk': {
          'type': 'tool-call-delta',
          'index': 0,
          'argumentsDelta': '"pwd"}',
        },
      },
    });
    await iterator.moveNext();
    expect(iterator.current.tools.single.content!.command!.command, 'pwd');
    host.item(
      open,
      _record(2, 'assistant/message', {
        'turn': 1,
        'step': 1,
        'message': {
          'id': 'committed',
          'content': [
            {
              'type': 'tool-call',
              'id': 'call-live',
              'name': 'bash',
              'arguments': '{"command":"pwd"}',
            },
          ],
        },
      }),
    );
    await iterator.moveNext();
    host.item(
      open,
      _record(3, 'tool/call', {
        'callId': 'call-live',
        'name': 'bash',
        'arguments': '{"command":"pwd"}',
      }),
    );
    await iterator.moveNext();
    expect(iterator.current.tools, hasLength(1));
    expect(iterator.current.tools.single.id, 'call-live');
    await iterator.cancel();
  });

  test(
    'embedded tool calls keep command and edit data through a later result',
    () async {
      host.onCall = (r) async => r.endpoint == 'session/state'
          ? {'running': false}
          : {
              'cursor': 3,
              'hasMore': false,
              'records': [
                _record(1, 'assistant/message', {
                  'message': {
                    'id': 'owner',
                    'content': [
                      {'type': 'text', 'text': '核对变更'},
                      {
                        'type': 'tool-call',
                        'id': 'embedded',
                        'name': 'edit',
                        'arguments': '{"command":"git diff","file_path":"/ws/a.dart","old_string":"before","new_string":"after"}',
                      },
                    ],
                  },
                }),
                _record(2, 'tool/result', {
                  'message': {
                    'source': {'callId': 'embedded'},
                    'content': [
                      {'type': 'text', 'text': '保留结果'},
                    ],
                  },
                  'meta': {'stdout': 'DIFF', 'exitCode': 0},
                }),
                _assistant(3, '后续正文'),
              ],
            };
      final tool = (await service.readSubagent(address)).tools.single;
      expect(tool.messageId, 'owner');
      expect(tool.status, ToolStatus.complete);
      expect(tool.content!.command!.command, 'git diff');
      expect(tool.content!.command!.stdout, 'DIFF');
      expect(tool.content!.edits.single.path, '/ws/a.dart');
      expect(tool.content!.edits.single.before, 'before');
      expect(tool.content!.edits.single.after, 'after');
    },
  );

  test(
    'opaque blocks do not steal tool ownership from the real assistant message',
    () async {
      host.onCall = (r) async => r.endpoint == 'session/state'
          ? {'running': false}
          : {
              'cursor': 2,
              'hasMore': false,
              'records': [
                _record(1, 'assistant/message', {
                  'message': {
                    'id': 'owner',
                    'content': [
                      {'type': 'reasoning', 'text': '仅思考的真实消息'},
                      {'type': 'future-block', 'value': '保留未知块'},
                    ],
                  },
                }),
                _record(2, 'tool/call', {
                  'callId': 'owned',
                  'name': 'read',
                  'arguments': '{}',
                }),
              ],
            };
      final view = await service.readSubagent(address);
      expect(view.tools.map((t) => t.messageId), everyElement('owner'));
      expect(view.messages.single.role, 'event');
      expect(view.messages.single.text, contains('保留未知块'));
    },
  );

  test('catalog resolves unknown identity, keeps external rows unaddressable and requires durable completion', () async {
    host.onCall = (r) async {
      final id = _request(r)['sessionId'];
      if (r.endpoint == 'session/state')
        return {
          'running': id == 'running',
          'active': true,
          'cursor': 10,
          'queue': [],
          'pendingInput': [],
        };
      if (id == 'parent')
        return {
          'kind': 'sequenced',
          'seq': 10,
          'values': {
            'subagentCatalog': [
              {
                'id': 'child',
                'createdAt': 1,
                'mode': 'unknown',
                'label': 'historical',
              },
              {
                'id': 'running',
                'createdAt': 2,
                'mode': 'one-shot',
                'label': 'busy',
              },
              {
                'id': 'waiting',
                'createdAt': 3,
                'mode': 'continuable',
                'label': 'ready',
              },
              {
                'id': 'external',
                'createdAt': 4,
                'mode': 'external',
                'name': 'remote',
                'label': 'external job',
              },
            ],
          },
        };
      return {
        'kind': 'sequenced',
        'seq': 9,
        'values': {
          'subagent': {
            'mode': id == 'child' ? 'continuable' : 'one-shot',
            'label': 'resolved',
            'seq': 1,
          },
          'subagentTiming': {
            'settledMs': 1000,
            if (id == 'child') 'lastTurnCompleted': true,
            if (id == 'waiting') 'lastTurnCompleted': false,
          },
        },
      };
    };
    final tasks = await service.readCatalog('parent');
    expect(tasks.map((t) => t.id), ['child', 'running', 'waiting', 'external']);
    expect(tasks.map((t) => t.status), [
      AgentState.completed,
      AgentState.running,
      AgentState.waiting,
      AgentState.waiting,
    ]);
    expect(tasks.first.address!.mode, SubagentMode.continuable);
    expect(tasks.first.address!.parentSessionId, 'parent');
    expect(tasks.last.address, isNull);
    expect(tasks.last.name, 'remote');
    final stateIds = host.requests
        .where((r) => r.endpoint == 'session/state')
        .map((r) => _request(r)['sessionId']);
    expect(stateIds, unorderedEquals(['child', 'running', 'waiting']));
    expect(host.requests.any((r) => r.endpoint.contains('activate')), isFalse);
  });

  test('events uses the addressed request and real user/message and assistant/message fields', () async {
    host.onCall = (r) async {
      if (r.endpoint == 'session/state') return {'running': false};
      return {
        'cursor': 4,
        'hasMore': false,
        'records': [
          _record(4, 'future/event', {'text': 'opaque event'}),
          _assistant(3, 'real reply'),
          _record(2, 'user/message', {
            'id': 'context',
            'source': {'kind': 'agent-instructions'},
            'content': [
              {'type': 'text', 'text': 'injected'},
            ],
          }),
          _user(1, 'human'),
        ],
      };
    };
    final conversation = await service.readSubagent(address);
    expect(conversation.messages.map((m) => m.role), [
      'user',
      'context',
      'assistant',
      'event',
    ]);
    expect(conversation.messages.map((m) => m.text).take(3), [
      'human',
      'injected',
      'real reply',
    ]);
    expect(conversation.messages.last.text, contains('future/event'));
    expect(conversation.messages.last.text, contains('opaque event'));
    final call = host.requests.singleWhere(
      (r) => r.endpoint == 'session/events',
    );
    expect(_request(call), {'address': address.toWire(), 'maxMessages': 500});
    expect(conversation.running, isFalse);
  });

  test('unknown assistant blocks remain readable without inventing an assistant reply', () async {
    host.onCall = (r) async => r.endpoint == 'session/state'
        ? {'running': false}
        : {
            'cursor': 1,
            'hasMore': false,
            'records': [
              _record(1, 'assistant/message', {
                'message': {
                  'id': 'opaque',
                  'content': [
                    {'type': 'future-block', 'text': 'not a text block'},
                  ],
                },
              }),
            ],
          };
    final conversation = await service.readSubagent(address);
    expect(conversation.messages.single.role, 'event');
    expect(conversation.messages.single.text, contains('future-block'));
  });

  test(
    'watch stays passive, replaces snapshots and drops duplicate seq',
    () async {
      final iterator = StreamIterator(service.watchSubagent(address));
      final first = iterator.moveNext();
      final open = await host.waitFrame((r) => r.frame['type'] == 'open');
      expect(open.frame['endpoint'], 'session/follow');
      expect(open.frame['payload'], {
        'args': {
          'request': {
            'address': address.toWire(),
            'assistantStream': true,
            'maxMessages': 500,
            'turnWindow': {'minMessages': 50, 'minTurns': 2},
          },
        },
      });
      host.item(
        open,
        _snapshot(2, [_user(1, 'old'), _assistant(2, 'old reply')]),
      );
      expect(await first, isTrue);
      expect(iterator.current.messages, hasLength(2));
      host.item(open, _snapshot(8, [_user(8, 'replacement')]));
      expect(await iterator.moveNext(), isTrue);
      expect(iterator.current.messages.single.text, 'replacement');
      host.item(open, _user(8, 'duplicate'));
      host.item(open, _assistant(9, 'new reply'));
      expect(await iterator.moveNext(), isTrue);
      expect(iterator.current.messages.map((m) => m.text), [
        'replacement',
        'new reply',
      ]);
      await iterator.cancel();
      await host.waitFrame((r) => r.frame['type'] == 'cancel');
      expect(
        host.requests.any(
          (r) =>
              r.endpoint.contains('activate') ||
              r.endpoint.contains('interrupt'),
        ),
        isFalse,
      );
    },
  );

  test('a durable seq gap becomes a visible stream error', () async {
    final iterator = StreamIterator(service.watchSubagent(address));
    final first = iterator.moveNext();
    final open = await host.waitFrame((r) => r.frame['type'] == 'open');
    host.item(open, _snapshot(5, [_user(5, 'before')]));
    await first;
    final next = expectLater(
      iterator.moveNext(),
      throwsA(
        isA<DshRpcException>().having(
          (e) => e.code,
          'code',
          'subagent/sequence-gap',
        ),
      ),
    );
    host.item(open, _assistant(7, 'missing seq 6'));
    await next;
    await iterator.cancel();
  });

  test(
    'assistant baseline and deltas settle into the durable message once',
    () async {
      final iterator = StreamIterator(service.watchSubagent(address));
      final first = iterator.moveNext();
      final open = await host.waitFrame((r) => r.frame['type'] == 'open');
      host.item(
        open,
        _snapshot(
          1,
          [_user(1, 'question')],
          assistant: {
            'revision': 3,
            'activeAttempt': {
              'attemptId': 'attempt',
              'startedAfterSeq': 1,
              'turn': 1,
              'step': 1,
              'nextIndex': 1,
              'stream': [
                {
                  'type': 'text-chunks',
                  'time0': 10,
                  'index': 0,
                  'dt': [],
                  'texts': ['par'],
                },
              ],
            },
          },
        ),
      );
      await first;
      expect(iterator.current.messages.last.text, 'par');
      final previousRevision = iterator.current.revision;
      host.item(open, {
        'type': 'assistant-stream',
        'frame': {
          'type': 'chunk',
          'attemptId': 'attempt',
          'revision': 4,
          'index': 1,
          'time': 11,
          'chunk': {'type': 'text-delta', 'index': 0, 'text': 'tial'},
        },
      });
      await iterator.moveNext();
      expect(iterator.current.messages.last.text, 'partial');
      expect(iterator.current.revision, greaterThan(previousRevision));
      host.item(open, _assistant(2, 'partial'));
      await iterator.moveNext();
      expect(
        iterator.current.messages.where((m) => m.role == 'assistant'),
        hasLength(1),
      );
      host.item(open, {
        'type': 'assistant-stream',
        'frame': {
          'type': 'end',
          'attemptId': 'attempt',
          'revision': 5,
          'index': 2,
          'outcome': {
            'kind': 'committed',
            'eventType': 'assistant/message',
            'seq': 2,
          },
        },
      });
      await iterator.moveNext();
      expect(
        iterator.current.messages.where((m) => m.role == 'assistant'),
        hasLength(1),
      );
      await iterator.cancel();
    },
  );

  test('surface replacement removes prior messages without classifying unknown data as replies', () async {
    host.onCall = (r) async => r.endpoint == 'session/state'
        ? {'running': false}
        : {
            'cursor': 3,
            'hasMore': false,
            'records': [
              _user(1, 'remove'),
              _assistant(2, 'remove too'),
              _record(
                3,
                'user/message',
                {
                  'id': 'new',
                  'source': {'kind': 'compact-checkpoint'},
                  'content': [
                    {'type': 'text', 'text': 'checkpoint'},
                  ],
                },
                surfaceOp: {'op': 'replace', 'startSeq': 1, 'endSeq': 2},
              ),
            ],
          };
    final conversation = await service.readSubagent(address);
    expect(conversation.messages.single.role, 'context');
    expect(conversation.messages.single.text, 'checkpoint');
  });

  test('prompt acknowledges inbox delivery only and rejects read-only modes before RPC', () async {
    host.onCall = (r) async => {'messageId': 'inbox-id'};
    final receipt = await service.sendSubagent(
      address,
      'continue',
      requestId: 'request-1',
    );
    expect(receipt.requestId, 'request-1');
    expect(receipt.delivery, 'queue');
    expect(host.requests.single.endpoint, 'subagents/prompt');
    expect(_request(host.requests.single), {
      'requestId': 'request-1',
      'childSessionId': 'child',
      'delivery': 'queue',
      'content': [
        {'type': 'text', 'text': 'continue'},
      ],
    });
    for (final mode in [SubagentMode.oneShot, SubagentMode.unknown]) {
      await expectLater(
        service.sendSubagent(
          SubagentAddress(
            parentSessionId: 'parent',
            childSessionId: 'child',
            mode: mode,
          ),
          'blocked',
          requestId: 'blocked',
        ),
        throwsA(isA<DshRpcException>()),
      );
    }
    expect(host.requests, hasLength(1));
    expect(host.frames, isEmpty);
  });

  test('empty history accepts the protocol minus-one cursor', () async {
    host.onCall = (r) async => r.endpoint == 'session/state'
        ? {'running': false}
        : {'cursor': -1, 'hasMore': false, 'records': []};
    final conversation = await service.readSubagent(address);
    expect(conversation.messages, isEmpty);
  });

  test(
    'unknown read resolves its wire mode while preserving caller identity',
    () async {
      const unknown = SubagentAddress(
        parentSessionId: 'parent',
        childSessionId: 'child',
        mode: SubagentMode.unknown,
      );
      host.onCall = (r) async {
        if (r.endpoint == 'session/projections')
          return {
            'kind': 'sequenced',
            'seq': 1,
            'values': {
              'subagent': {
                'mode': 'continuable',
                'label': 'resolved',
                'seq': 0,
              },
            },
          };
        if (r.endpoint == 'session/state') return {'running': false};
        return {
          'cursor': 0,
          'hasMore': false,
          'records': [_user(0, 'hello')],
        };
      };
      final conversation = await service.readSubagent(unknown);
      expect(conversation.address, unknown);
      final events = host.requests.singleWhere(
        (r) => r.endpoint == 'session/events',
      );
      expect((_request(events)['address'] as Map)['mode'], 'continuable');
    },
  );

  test('late read cannot overwrite a newer watched conversation', () async {
    final entered = Completer<void>();
    final release = Completer<Object?>();
    host.onCall = (r) async {
      if (r.endpoint == 'session/events') {
        entered.complete();
        return release.future;
      }
      return {'running': false};
    };
    final oldRead = service.readSubagent(address);
    await entered.future;
    final iterator = StreamIterator(service.watchSubagent(address));
    final next = iterator.moveNext();
    final open = await host.waitFrame((r) => r.frame['type'] == 'open');
    host.item(
      open,
      _snapshot(2, [_user(1, 'new question'), _assistant(2, 'new answer')]),
    );
    await next;
    final watched = iterator.current;
    release.complete({
      'cursor': 1,
      'hasMore': false,
      'records': [_user(1, 'old question')],
    });
    expect((await oldRead).revision, lessThan(watched.revision));
    await iterator.cancel();
  });

  test(
    'malformed acceptance is rejected instead of fabricated as a receipt',
    () async {
      host.onCall = (r) async => {'accepted': true};
      await expectLater(
        service.sendSubagent(address, 'continue', requestId: 'request-2'),
        throwsA(isA<DshRpcException>()),
      );
    },
  );
}

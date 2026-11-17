import '../transport/dsh_rpc_connection.dart';
import '../transport/dsh_transport.dart';

/// A Host failure said in words a person can act on.
///
/// The turn-rewind plugin answers business failures as bare kebab codes
/// (`rejected("session-busy")` carries no message), and the RPC layer wraps
/// them as `DshRpcException(rewind/session-busy)`. Printing that at someone who
/// just tapped 撤回 is machine noise, so known codes become a sentence.
///
/// An unknown code keeps its raw text: inventing a plausible reason would be
/// worse than showing a failure nobody has seen before.
String readableFailure(Object error) {
  final code = switch (error) {
    DshRpcException(:final code) => code.split('/').last,
    TransportException(:final message) => message,
    _ => '',
  };
  final known = _failureText[code];
  if (known != null) return known;
  final raw = switch (error) {
    DshRpcException(:final message) => message,
    TransportException(:final message) => message,
    _ => error.toString(),
  };
  if (raw.isEmpty || raw == code) return '操作没有完成（$code）';
  return raw;
}

/// Known failure code → one sentence. Nothing here may leak the code itself.
const _failureText = <String, String>{
  'session-busy': '这一轮还在进行，等它停下来再试。',
  'session-not-found': '找不到这个会话了，可能已经被归档。',
  'target-not-found': '没有找到这条消息对应的记录，可能已经被撤回。',
  'target-not-in-turn': '这条消息不在任何一轮里，没法从这里开始撤回。',
  'no-prefix': '这一条之前没有可以撤回的内容。',
  'query-unavailable': '暂时读不到会话记录，稍后再试。',
  'fork-unavailable': '宿主暂时不能开新分支，稍后再试。',
  'no-cwd': '这个会话没有可用的工作目录，文件无法回滚。',
  'bad-request': '这次请求不完整，请重新操作一次。',
};

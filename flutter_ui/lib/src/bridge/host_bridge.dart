import 'dart:async';

import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:flutter/services.dart';

import '../model/tool_content.dart';
import '../model/settings_model.dart';
import 'resource_web_stub.dart'
    if (dart.library.js_interop) 'resource_web.dart';

class HostInfo {
  const HostInfo({
    required this.webUrl,
    required this.state,
    required this.available,
    this.runtimeMode = '',
    this.runtimeResolved = false,
    this.runtimeReason = '',
    this.distro = '',
    this.runtimeRestartPending = false,
    this.workspaceDomain = '',
    this.workspaceGeneration = '',
  });

  final String webUrl;
  final String state;
  final bool available;
  final String runtimeMode;
  final bool runtimeResolved;
  final String runtimeReason;
  final String distro;

  /// Captured from the running local Host launch, never from selected mode/URL.
  final String workspaceDomain, workspaceGeneration;

  /// 模式已经切过去了，但引擎进程还在用上一代模式跑 —— 重启后才真正生效。
  ///
  /// 宿主在检测到 webUrl 换了一代（每次启动的 token 必然不同）时自动清掉这个
  /// 标记，所以它不会永久挂在那里；面板只在它为真时多显示一行说明。
  final bool runtimeRestartPending;
  static const preview = HostInfo(
    webUrl: '',
    state: 'preview',
    available: false,
    runtimeMode: 'native',
    runtimeResolved: true,
  );
  factory HostInfo.fromMap(Map<Object?, Object?> raw) => HostInfo(
    webUrl: raw['webUrl'] is String ? raw['webUrl'] as String : '',
    state: raw['state'] is String ? raw['state'] as String : 'unknown',
    available: raw['available'] == true,
    runtimeMode: raw['runtimeMode'] is String
        ? raw['runtimeMode'] as String
        : '',
    runtimeResolved: raw['runtimeResolved'] == true,
    runtimeReason: raw['runtimeReason'] is String
        ? raw['runtimeReason'] as String
        : '',
    distro: raw['distro'] is String ? raw['distro'] as String : '',
    runtimeRestartPending: raw['runtimeRestartPending'] == true,
    workspaceDomain: raw['workspaceDomain'] is String
        ? raw['workspaceDomain'] as String
        : '',
    workspaceGeneration: raw['workspaceGeneration'] is String
        ? raw['workspaceGeneration'] as String
        : '',
  );
  bool hasSameSnapshot(HostInfo? other) =>
      other != null &&
      webUrl == other.webUrl &&
      state == other.state &&
      available == other.available &&
      runtimeMode == other.runtimeMode &&
      runtimeResolved == other.runtimeResolved &&
      runtimeReason == other.runtimeReason &&
      distro == other.distro &&
      runtimeRestartPending == other.runtimeRestartPending &&
      workspaceDomain == other.workspaceDomain &&
      workspaceGeneration == other.workspaceGeneration;

  String get runtimeLabel {
    if (!runtimeResolved) return '检测中';
    return switch (runtimeMode) {
      'native' || 'native_fallback' => 'Bionic Node',
      'linux' =>
        distro.toLowerCase().contains('alpine') ? 'DSH Alpine' : 'DSH Linux',
      'ssh_windows' => 'SSH Windows',
      'ssh_linux' => 'SSH Linux',
      _ => '未知环境',
    };
  }

  String get runtimeDetail => runtimeMode == 'native_fallback'
      ? '兼容模式 · ' + runtimeReason
      : (runtimeMode == 'linux' ? '本机 Linux 容器' : '本机 Android');
}

/// 一次「运行模式切换」的宿主回执。
///
/// [applied] 是宿主**权威回读**出来的模式，不是我们请求的值 —— 两者不同时
/// [ok] 一定为 false，并且 [reason] 说明为什么没切过去（例如 rootfs 未部署、
/// 容器不可用）。界面必须按 [applied] 重新对齐高亮，不能按点击的那个目标。
class RuntimeModeSwitchResult {
  const RuntimeModeSwitchResult({
    required this.ok,
    required this.target,
    required this.applied,
    required this.previous,
    this.restartRequired = false,
    this.reason = '',
  });

  final bool ok;
  final String target;
  final String applied;
  final String previous;

  /// 模式已切，但引擎进程仍在旧模式下跑，重启后生效。
  final bool restartRequired;

  /// 失败原因：宿主给的原始技术原因，或本地化的短码（如 busy）。
  final String reason;

  /// 没有宿主（Web 预览 / 老版本）：不是失败，是「这条通道不存在」。
  static const unsupported = RuntimeModeSwitchResult(
    ok: false,
    target: '',
    applied: '',
    previous: '',
    reason: 'unsupported',
  );

  factory RuntimeModeSwitchResult.fromWire(Object? raw) {
    if (raw is! Map) return unsupported;
    String read(String key) => raw[key] is String ? raw[key] as String : '';
    return RuntimeModeSwitchResult(
      ok: raw['ok'] == true,
      target: read('target'),
      applied: read('applied'),
      previous: read('previous'),
      restartRequired: raw['restartRequired'] == true,
      reason: read('reason'),
    );
  }
}

class HostBridge {
  const HostBridge._();

  static const MethodChannel _channel = MethodChannel(
    'app.dsh.mobile/native_chat',
  );

  static Future<HostInfo?> readEngineInfo() async {
    try {
      final raw = await _channel.invokeMethod<Object?>('engineInfo');
      if (raw is! Map) return null;
      return HostInfo.fromMap(raw);
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  static Future<bool> openResource(OutputResource resource) async {
    final uri = resource.openableUri;
    if (uri == null) return false;
    final browser = await openBrowserResource(uri.toString());
    if (browser != null) return browser;
    return _open('openResource', {'uri': uri.toString()});
  }

  /// 宿主报告"引擎已把第一帧画到屏幕上"的时刻。
  ///
  /// Flutter 自己的 initState / post-frame 在冷启时不可靠：这一页起来要 5–8 秒，
  /// 那时任何几百毫秒的渐显都已经跑完，屏幕上第一眼就是不透明整帧。所以入场淡化
  /// 必须等宿主这个信号。返回的 Future 在信号到达时完成；若宿主已经显示过了
  /// （订阅得晚），consumeFirstFrame 会立刻告诉我们是这种情况。
  static Future<void> firstFrameShown() async {
    final completer = _firstFrame ??= Completer<void>();
    // 先挂监听再问"显示过没有"：反过来的话，信号可能正好落在两步之间丢失。
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'firstFrameShown' && !completer.isCompleted) {
        completer.complete();
      }
      return null;
    });
    try {
      final already = await _channel.invokeMethod<bool>('consumeFirstFrame');
      if (already == true && !completer.isCompleted) completer.complete();
    } on MissingPluginException {
      // 没有宿主（Web 预览）：没有可等待的显示信号，直接放行。
      if (!completer.isCompleted) completer.complete();
    } on PlatformException {
      if (!completer.isCompleted) completer.complete();
    }
    return completer.future;
  }

  /// 宿主在点击那一刻拍下的 Web 那一屏（PNG 字节），用来当入场底图。
  ///
  /// 返回 null 表示没有底图（拍图失败、老系统、Web 预览）。
  static Future<Uint8List?> readBackdrop() async {
    try {
      final raw = await _channel.invokeMethod<Uint8List>("backdropImage");
      return raw;
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  /// 告诉宿主：Flutter 已经在自己那层画出同一张底图了，可以把覆盖层撤掉。
  static Future<void> releaseBackdrop() async {
    try {
      await _channel.invokeMethod<bool>("releaseBackdrop");
    } on MissingPluginException {
      // 没有宿主：什么都不用做。
    } on PlatformException {
      // 同上。
    }
  }

  static Completer<void>? _firstFrame;

  /// 只给测试用：清掉上一次跨用例遗留的「首帧已显示」状态。
  @visibleForTesting
  static void debugResetFirstFrame() {
    _firstFrame = null;
  }

  /// Missing/failed responses stay unknown; only a receipt with target:null means unset.
  static Future<Object?> readDefaultWorkspace(HostInfo host) =>
      _channel.invokeMethod<Object?>('readDefaultWorkspace', {
        'domain': host.workspaceDomain,
        'generation': host.workspaceGeneration,
      });

  static Future<bool> writeDefaultWorkspace(
    HostInfo host,
    Map<String, Object?> target,
  ) async =>
      await _channel.invokeMethod<bool>('writeDefaultWorkspace', {
        'domain': host.workspaceDomain,
        'generation': host.workspaceGeneration,
        'target': target,
      }) ==
      true;

  /// Independent removal intent; unset is not the concrete Home target.
  static Future<bool> clearDefaultWorkspace(HostInfo host) async =>
      await _channel.invokeMethod<bool>('clearDefaultWorkspace', {
        'domain': host.workspaceDomain,
        'generation': host.workspaceGeneration,
      }) ==
      true;

  static Future<Map<String, String>?> readAppearance() async {
    try {
      final raw = await _channel.invokeMethod<Object?>('readAppearance');
      if (raw is! Map) return null;
      return {
        for (final entry in raw.entries)
          if (entry.key is String && entry.value is String)
            entry.key as String: entry.value as String,
      };
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  static Future<bool> writeAppearance(String mode, String mood) =>
      _open('writeAppearance', {'mode': mode, 'mood': mood});

  static Future<String?> readLocale() async {
    try {
      final raw = await _channel.invokeMethod<Object?>('readLocale');
      return raw is String && raw.isNotEmpty ? raw : null;
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  /// The in-app display size lives beside the appearance keys in the same
  /// "native_chat_ui" preferences file. A missing value is null, never a guess.
  static Future<String?> readDisplaySize() async {
    try {
      final raw = await _channel.invokeMethod<Object?>('readDisplaySize');
      return raw is String && raw.isNotEmpty ? raw : null;
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  static Future<String?> readSessionDraft(String id) async {
    try {
      return await _channel.invokeMethod<String>('readSessionDraft', {
        'id': id,
      });
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    } on Object {
      return null;
    }
  }

  static Future<bool> writeSessionDraft(String id, String text) =>
      _open('writeSessionDraft', {'id': id, 'text': text});

  static Future<bool> writeDisplaySize(String size) =>
      _open('writeDisplaySize', {'size': size});

  static Future<bool> openRuntimeSettings([String? target]) =>
      _open('openRuntimeSettings', target == null ? null : {'target': target});

  /// 在面板内真正切换运行模式：宿主先验可用性，再落偏好，再权威回读。
  ///
  /// 与 [openRuntimeSettings] 的区别是「不跳页面、不伪造成功」：返回的是宿主
  /// 回读出来的模式，而不是「我把请求发出去了」。没有宿主时返回
  /// [RuntimeModeSwitchResult.unsupported]。
  static Future<RuntimeModeSwitchResult> selectRuntimeMode(String mode) async {
    try {
      final raw = await _channel.invokeMethod<Object?>('selectRuntimeMode', {
        'mode': mode,
      });
      return RuntimeModeSwitchResult.fromWire(raw);
    } on MissingPluginException {
      // 真的没有宿主（Web 预览 / 老包）：这条通道不存在。
      return RuntimeModeSwitchResult.unsupported;
    } on PlatformException catch (e) {
      // 宿主**答了**，只是拒了这个请求（值域校验、通道内部错误）。这已经不是
      //「没有通道」：退回外部页面会让人以为「跳一下就能切」，成功与否都不成立。
      return RuntimeModeSwitchResult(
        ok: false,
        target: mode,
        applied: '',
        previous: '',
        reason: e.code.isEmpty ? 'channel-error' : e.code,
      );
    } on Object catch (e) {
      return RuntimeModeSwitchResult(
        ok: false,
        target: mode,
        applied: '',
        previous: '',
        reason: e.runtimeType.toString(),
      );
    }
  }

  static Future<bool> openSettingsSection(SettingsDestination destination) =>
      _open('openSettingsSection', {'section': destination.name});
  static Future<bool> openSshManager(String platform) =>
      _open('openSshManager', {'platform': platform});
  static Future<bool> _open(
    String method, [
    Map<String, String>? arguments,
  ]) async {
    try {
      return await _channel.invokeMethod<bool>(method, arguments) == true;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Hand text to the platform share sheet. False means no chooser opened, so
  /// the caller can offer the local preview/download path instead.
  static Future<bool> shareText(String text, {String? subject}) async {
    if (text.trim().isEmpty) return false;
    try {
      return await _channel.invokeMethod<bool>('shareText', {
            'text': text,
            if (subject != null && subject.trim().isNotEmpty)
              'subject': subject,
          }) ==
          true;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Open a workspace file with another Android application. The native side
  /// validates the FileProvider root and grants read-only access only.
  static Future<bool> openFile(String absolutePath, String mime) async {
    if (absolutePath.trim().isEmpty) return false;
    return _open('openFile', {'path': absolutePath, 'mime': mime});
  }

  /// Share a workspace file through Android's chooser with read-only access.
  static Future<bool> shareFile(String absolutePath, String mime) async {
    if (absolutePath.trim().isEmpty) return false;
    return _open('shareFile', {'path': absolutePath, 'mime': mime});
  }

  /// Stage the exact DSH bytes in Android's private share cache. The native
  /// side gives receiving apps a read-only content URI for this copy.
  static Future<bool> openFileBytes(
    Uint8List bytes, {
    required String name,
    required String mime,
  }) => _dispatchFileBytes('openFileBytes', bytes, name, mime);

  static Future<bool> shareFileBytes(
    Uint8List bytes, {
    required String name,
    required String mime,
  }) => _dispatchFileBytes('shareFileBytes', bytes, name, mime);

  static Future<bool> _dispatchFileBytes(
    String method,
    Uint8List bytes,
    String name,
    String mime,
  ) async {
    if (name.trim().isEmpty || bytes.length > 32 * 1024 * 1024) return false;
    try {
      return await _channel.invokeMethod<bool>(method, {
            'bytes': bytes,
            'name': name,
            'mime': mime,
          }) ==
          true;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Ask the platform's file picker to save text where the person chooses.
  static Future<bool> saveText(String text, {String? suggestedName}) async {
    if (text.trim().isEmpty) return false;
    try {
      return await _channel.invokeMethod<bool>('saveText', {
            'text': text,
            if (suggestedName != null && suggestedName.trim().isNotEmpty)
              'suggestedName': suggestedName,
          }) ==
          true;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// The Session the Android side already had open, if it came from the Web
  /// shell. Null means "no handoff": the caller must not invent one.
  static Future<String?> readInitialSessionId() async {
    try {
      final raw = await _channel.invokeMethod<Object?>('initialSession');
      return raw is String && raw.trim().isNotEmpty ? raw : null;
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  /// Tell the Android side which Session this screen is showing, so switching
  /// back to the Web shell opens that same Session instead of the latest one.
  static Future<bool> reportSession(String sessionId) =>
      _open('setSession', {'sessionId': sessionId});

  static Future<bool> openWebFallback() async {
    try {
      await _channel.invokeMethod<void>('openWebFallback');
      return true;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }
}

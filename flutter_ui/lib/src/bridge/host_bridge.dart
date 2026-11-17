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
  });

  final String webUrl;
  final String state;
  final bool available;
  final String runtimeMode;
  final bool runtimeResolved;
  final String runtimeReason;
  final String distro;
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
  );
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

  static Future<bool> writeDisplaySize(String size) =>
      _open('writeDisplaySize', {'size': size});

  static Future<bool> openRuntimeSettings([String? target]) =>
      _open('openRuntimeSettings', target == null ? null : {'target': target});
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
            if (subject != null && subject.trim().isNotEmpty) 'subject': subject,
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
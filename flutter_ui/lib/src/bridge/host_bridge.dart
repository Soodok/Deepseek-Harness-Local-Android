import 'package:flutter/services.dart';

class HostInfo {
  const HostInfo({
    required this.webUrl,
    required this.state,
    required this.available,
  });

  final String webUrl;
  final String state;
  final bool available;
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
      final webUrl = raw['webUrl'];
      final state = raw['state'];
      final available = raw['available'];
      return HostInfo(
        webUrl: webUrl is String ? webUrl : '',
        state: state is String ? state : 'unknown',
        available: available == true,
      );
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

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

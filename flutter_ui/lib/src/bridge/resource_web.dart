import 'dart:js_interop';

@JS('window.open')
external _ResourceWindow? _open(JSString uri, JSString target);

extension type _ResourceWindow(JSObject _) implements JSObject {
  external set opener(JSAny? value);
}

Future<bool?> openBrowserResource(String uri) async {
  final window = _open(uri.toJS, '_blank'.toJS);
  if (window == null) return false;
  window.opener = null;
  return true;
}

import sys,pathlib,json
sys.path.insert(0,r"E:/tmp/native-chat-web-preview")
import beta36_display_flow_20261117 as f
f.tap("session-a417e50d")
f.find("Bionic Node")
f.shot("beta36-cold-restored-compact")
prefs=f.u.adb("exec-out","run-as","app.dsh.mobile","cat","shared_prefs/native_chat_ui.xml")
assert b"compact" in prefs
out=pathlib.Path(r"E:/tmp/wt-flutter-native-chat/docs/reports/assets/2026-11-17-display-errors")
(out/"native_chat_ui-after-cold-start.xml").write_bytes(prefs)
crash=f.u.adb("logcat","-b","crash","-d")
(out/"beta36-device-crash-buffer.txt").write_bytes(crash)
print(json.dumps({"coldRestoredSize":"compact","realSession":"session-a417e50d-81a2-48fe-92c8-231d197ee6e9","crashBufferBytes":len(crash),"fontScale":f.u.adb("shell","settings","get","system","font_scale").decode().strip()}))

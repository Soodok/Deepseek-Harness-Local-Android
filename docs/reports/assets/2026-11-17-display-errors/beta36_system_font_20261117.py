import sys,json
sys.path.insert(0,r"E:/tmp/native-chat-web-preview")
import beta36_display_flow_20261117 as f
original=f.u.adb("shell","settings","get","system","font_scale").decode().strip()
try:
 f.u.adb("shell","settings","put","system","font_scale","2.0")
 f.shot("beta36-system-font-2x")
 f.tap("session-a417e50d")
 f.find("Bionic Node")
 f.shot("beta36-system-font-2x-chat")
 print(json.dumps({"temporaryFontScale":f.u.adb("shell","settings","get","system","font_scale").decode().strip(),"original":original}))
finally:
 if original=="null": f.u.adb("shell","settings","delete","system","font_scale")
 else: f.u.adb("shell","settings","put","system","font_scale",original)
 f.shot("beta36-system-font-restored")
 print(json.dumps({"restored":f.u.adb("shell","settings","get","system","font_scale").decode().strip()}))

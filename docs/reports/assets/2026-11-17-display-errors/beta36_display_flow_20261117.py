import sys,pathlib,json,xml.etree.ElementTree as ET,re,contextlib,io
sys.path.insert(0,r"E:/tmp/native-chat-web-preview")
import android_ui as u
def nodes():
 return list(ET.fromstring(u.tree()).iter("node"))
def find(prefix):
 for _ in range(3):
  for n in nodes():
   if n.get("content-desc","").startswith(prefix) or n.get("text","").startswith(prefix): return n
 raise RuntimeError("Missing prefix "+prefix)
def tap(prefix):
 n=find(prefix); b=list(map(int,re.findall("[0-9]+",n.get("bounds",""))));u.adb("shell","input","tap",str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def shot(name):
 with contextlib.redirect_stdout(io.StringIO()): u.snapshot(name)
def switch(size):
 tap("打开会话抽屉");tap("DeepSeek 账号");tap("设置")
 found=False
 for _ in range(4):
  if any(n.get("content-desc","").startswith("个性化主题") for n in nodes()): found=True;break
  u.adb("shell","input","swipe","930","2040","930","670","450")
 if not found: raise RuntimeError("Personalization not visible")
 tap("个性化主题");tap({"compact":"紧凑","standard":"标准","relaxed":"宽松"}[size])
 shot("beta36-personalization-"+size+"-with-draft")
 u.adb("shell","input","keyevent","4");u.adb("shell","input","keyevent","4")
 shot("beta36-draft-preserved-"+size)
 draft=find("DISPLAY_SIZE_DRAFT")
 if draft.get("text")!="DISPLAY_SIZE_DRAFT": raise RuntimeError("Draft did not survive size change")
 b=list(map(int,re.findall("[0-9]+",draft.get("bounds",""))));u.adb("shell","input","tap",str((b[0]+b[2])//2),str((b[1]+b[3])//2))
 shot("beta36-keyboard-"+size+"-with-draft")
 print(json.dumps({"size":size,"draftPreserved":True,"prefs":u.adb("exec-out","run-as","app.dsh.mobile","cat","shared_prefs/native_chat_ui.xml").decode()},ensure_ascii=False))
 u.adb("shell","input","keyevent","4")
if __name__=="__main__":
 u.adb("shell","input","keyevent","4")
 switch(sys.argv[1])

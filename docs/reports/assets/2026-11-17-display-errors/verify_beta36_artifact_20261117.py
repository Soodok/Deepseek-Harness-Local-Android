import hashlib,json,pathlib,zipfile,subprocess
root=pathlib.Path(r"E:/tmp/native-chat-web-preview")
apk=root/"dsh-native-chat-beta36-x86_64-full.apk"
def sha(path):
 h=hashlib.sha256()
 with path.open("rb") as f:
  for b in iter(lambda:f.read(1024*1024),b""): h.update(b)
 return h.hexdigest()
with zipfile.ZipFile(apk) as z:
 libs=sorted({n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")})
 runtimes=[n for n in z.namelist() if n.startswith("assets/") and n.endswith(".zip") and "runtime" in n]
 runtime=[]
 for name in runtimes:
  h=hashlib.sha256()
  with z.open(name) as f:
   for b in iter(lambda:f.read(1024*1024),b""): h.update(b)
  runtime.append({"entry":name,"size":z.getinfo(name).file_size,"sha256":h.hexdigest()})
result={"file":str(apk),"size":apk.stat().st_size,"sha256":sha(apk),"abis":libs,"runtime":runtime,"versionName":"1.6.0-beta.36","versionCode":411,"variant":"full x86_64 debug","device":"emulator-5570"}
out=pathlib.Path(r"E:/tmp/wt-flutter-native-chat/docs/reports/assets/2026-11-17-display-errors/beta36-artifact.json")
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps(result,ensure_ascii=False))

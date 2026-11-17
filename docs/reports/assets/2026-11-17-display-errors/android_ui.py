import subprocess,sys,pathlib,xml.etree.ElementTree as ET,re,json,time
sys.stdout.reconfigure(encoding='utf-8')
ADB=r'E:/Android/Sdk/platform-tools/adb.exe'
SERIAL='emulator-5570'
OUT=pathlib.Path(r'E:/tmp/native-chat-web-preview/android-evidence')
OUT.mkdir(exist_ok=True)
def adb(*args):
 p=subprocess.run([ADB,'-s',SERIAL,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=35)
 if p.returncode: raise RuntimeError(str(args)+': '+p.stderr.decode('utf-8','replace'))
 return p.stdout
def tree():
 target='/sdcard/dsh-flutter-ui-'+str(time.time_ns())+'.xml'
 result=adb('shell','uiautomator','dump','--compressed',target)
 if b'dumped to' not in result: raise RuntimeError('No fresh UI hierarchy: '+result.decode('utf-8','replace'))
 return adb('exec-out','cat',target)
def snapshot(name):
 xml=tree(); (OUT/(name+'.xml')).write_bytes(xml)
 (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 rows=[]
 for n in ET.fromstring(xml).iter('node'):
  if n.get('text') or n.get('content-desc'): rows.append({k:n.get(k) for k in ['text','content-desc','resource-id','class','clickable','bounds']})
 print(json.dumps(rows,ensure_ascii=False))
def tap(label):
 xml=tree()
 for n in ET.fromstring(xml).iter('node'):
  if label in [n.get('text'),n.get('content-desc')]:
   nums=list(map(int,re.findall(r'[0-9]+',n.get('bounds',''))));x=(nums[0]+nums[2])//2;y=(nums[1]+nums[3])//2
   adb('shell','input','tap',str(x),str(y));print(json.dumps({'tapped':label,'x':x,'y':y},ensure_ascii=False));return
 raise RuntimeError('Missing UI target '+label)
if __name__=='__main__':
 if sys.argv[1]=='shot':snapshot(sys.argv[2])
 elif sys.argv[1]=='tap':tap(sys.argv[2])
 elif sys.argv[1]=='key':print(adb('shell','input','keyevent',sys.argv[2]).decode())
 elif sys.argv[1]=='xy':print(adb('shell','input','tap',sys.argv[2],sys.argv[3]).decode())

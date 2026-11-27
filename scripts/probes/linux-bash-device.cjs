/* Run under the app uid. --app enters via the existing Linux bridge and uses
 * the installed runner by default; pass an explicit runner/rootfs/interp only for
 * an isolated comparison. The interpreter is read from /dev/linux/status, so the
 * probe does not assume x86_64 on arm64. No config, runner, or package is changed. */

const fs = require('fs');
const http = require('http');
const assert = require('assert/strict');
const {spawnSync} = require('child_process');
const appRoot = '/data/user/0/app.dsh.mobile';
const quote = s => "'" + s.replace(/'/g, "'\"'\"'") + "'";
const python = [
'import ctypes,errno,http.server,json,os,subprocess,threading,urllib.request',
'c=ctypes.CDLL(None,use_errno=True);c.syscall.restype=ctypes.c_long',
'ctypes.set_errno(0);rc=c.syscall(435,0,0);assert rc == -1 and ctypes.get_errno() == errno.ENOSYS',
'assert os.getpgrp() == os.getpgid(0)',
"p=subprocess.run(['/bin/bash','-c','printf pipe_ok | tr a-z A-Z'],capture_output=True,text=True,timeout=5)",
"assert (p.returncode,p.stdout,p.stderr) == (0,'PIPE_OK',''), (p.returncode,p.stdout,p.stderr)",
"resolved=subprocess.check_output(['/bin/bash','-c','command -v bash; command -v node'],text=True).splitlines()",
"assert resolved[0] in ('/usr/bin/bash','/bin/bash'), resolved",
"assert resolved[1].endswith('/node'), resolved",
'events=[]',
'class Handler(http.server.BaseHTTPRequestHandler):',
' def do_GET(self):',
"  events.append(self.path);self.send_response(200);self.end_headers();self.wfile.write(b'dsh_linux_preview_ok')",
' def log_message(self,*args): pass',
"server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)",
'worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()',
'assert worker.is_alive()',
"url='http://127.0.0.1:'+str(server.server_port)+'/'",
].join('\n') + '\n' + [
 'try:',
 " for i in range(3): assert urllib.request.urlopen(url+str(i),timeout=5).read() == b'dsh_linux_preview_ok'",
 ' assert len(events) == 3 and worker.is_alive()',
 " print(json.dumps({'bash_pipe':'PIPE_OK','clone3_errno':errno.ENOSYS,'threads':True,'http_status':200,'http_requests':len(events),'body':'dsh_linux_preview_ok','path':os.environ['PATH']}),flush=True)",
 'finally:',
 ' server.shutdown();server.server_close();worker.join(3)',
 'assert not worker.is_alive()',
].join('\n');
function bridge(path,body,token) { return new Promise((resolve,reject)=>{
 const data=body===undefined?undefined:JSON.stringify(body);
 const req=http.request({hostname:'127.0.0.1',port:3083,path,method:data?'POST':'GET',headers:{'X-DSH-Token':token,...(data?{'Content-Type':'application/json','Content-Length':Buffer.byteLength(data)}:{})}},res=>{
  let text='';res.on('data',b=>text+=b);res.on('end',()=>{try{resolve({status:res.statusCode,body:JSON.parse(text)});}catch{reject(Error('bridge returned invalid JSON'));}});
 });
 req.setTimeout(25000,()=>req.destroy(Error('bridge timeout')));req.on('error',reject);req.end(data);
}); }
async function main() {
 const [mode,loaderArg,rootfsArg,interpArg] = process.argv.slice(2);
 const loader = loaderArg || '/data/user/0/app.dsh.mobile/files/linux/runner/dsh_alpine';
 assert(loader.startsWith('/'), 'provide an absolute runner path');
 if(mode==='--run') {
  const seccomp=fs.readFileSync('/proc/self/status','utf8').split('\n').find(l=>l.startsWith('Seccomp:'));
  assert(/2$/.test(seccomp.trim()), 'test must inherit Android app seccomp');
  assert(rootfsArg && interpArg, '--run requires rootfs and interpreter');
  const result=spawnSync(loader,['-m','/eng:'+appRoot+'/files/engine','-m','/data/user/0:/data/user/0','-m','/data/data:/data/data',rootfsArg,interpArg,'/bin/bash','-c','python3 -c '+quote(python)],{encoding:'utf8',timeout:18000,maxBuffer:1024*1024,env:{...process.env,HOME:'/root',TMPDIR:'/tmp',DSH_ANDROID_CONTAINER:'1',DSH_NODE:interpArg.includes('musl')?'/usr/bin/node':'/opt/node/bin/node'}});
  console.log('APP_DOMAIN',seccomp.trim());
  console.log('GUEST_EXIT',result.status);
  if(result.stdout)process.stdout.write(result.stdout);
  if(result.stderr)process.stderr.write(result.stderr);
  assert.equal(result.error,undefined);assert.equal(result.status,0,'guest Bash/threads/HTTP regression failed');
  return;
 }
 assert.equal(mode,'--app');
 let token;
 for(const pid of fs.readdirSync('/proc')) {
  if(!/^[0-9]+$/.test(pid))continue;
  try {const env=Object.fromEntries(fs.readFileSync('/proc/'+pid+'/environ','utf8').split('\0').filter(Boolean).map(e=>{const i=e.indexOf('=');return[e.slice(0,i),e.slice(i+1)];}));if(env.DSH_BRIDGE_TOKEN&&env.DSH_HOME){token=env.DSH_BRIDGE_TOKEN;break;}}catch{}
 }
 assert(token,'no running app engine found');
 const status=await bridge('/dev/linux/status',undefined,token);
 assert.equal(status.status,200);assert(status.body.ok&&status.body.deployed);
 const dynamicLinker=interpArg || status.body.interp;
 assert(dynamicLinker,'status did not expose a guest interpreter; pass it explicitly');
 const guestArgs=['-m','/eng:'+appRoot+'/files/engine','-m','/data/user/0:/data/user/0','-m','/data/data:/data/data',status.body.rootfs_host_path,dynamicLinker,'/bin/bash','-c','python3 -c '+quote(python)];
 const command='PATH=/opt/node/bin:/usr/bin:/bin:/eng/linux-bin LD_LIBRARY_PATH='+quote(appRoot+'/files/engine/lib')+' '+[loader,...guestArgs].map(quote).join(' ');
 const result=await bridge('/dev/linux/exec',{cmd:command,timeout_ms:22000},token);
 console.log('BRIDGE',result.status,'DISTRO',status.body.family,'INTERP',dynamicLinker);
 console.log(JSON.stringify({exit_code:result.body.exit_code,timed_out:result.body.timed_out,stdout:result.body.stdout,stderr:result.body.stderr}));
 assert.equal(result.status,200);assert.equal(result.body.timed_out,false);assert.equal(result.body.exit_code,0,'App-domain probe failed');
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});

import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {readFile,writeFile,mkdtemp,rm} from 'node:fs/promises';
import path from 'node:path';
import {tmpdir} from 'node:os';
const root=path.resolve('build/web');
const out=path.resolve(process.argv[2]||'viewport-verified.png');
const requestedWidth=Number(process.argv.find(a=>a.startsWith('--width='))?.split('=')[1]||432);
const requestedHeight=960;
const profile=await mkdtemp(path.join(tmpdir(),'dsh-ui-cdp-'));
const types={'.html':'text/html','.js':'text/javascript','.json':'application/json','.wasm':'application/wasm','.otf':'font/otf','.ttf':'font/ttf','.png':'image/png','.md':'text/markdown; charset=utf-8'};
const server=createServer(async(req,res)=>{try {const name=decodeURIComponent(new URL(req.url,'http://local').pathname); const file=path.resolve(root,'.'+(name==='/'?'/index.html':name)); if(!file.startsWith(root+path.sep))throw Error('path');const data=await readFile(file);res.writeHead(200,{'Content-Type':types[path.extname(file)]||'application/octet-stream'});res.end(data);}catch{res.writeHead(404);res.end('not found');}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const url='http://127.0.0.1:'+server.address().port+'/';
const chrome=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+profile,'--window-size=432,960','about:blank'],{stdio:'ignore'});
let ws;
const delay=ms=>new Promise(r=>setTimeout(r,ms));
try {
 let port;
 for(let i=0;i<100;i++){try{port=Number((await readFile(path.join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]);if(port)break;}catch{}await delay(100);}
 if(!port)throw Error('Chrome DevTools did not start');
 const pages=await (await fetch('http://127.0.0.1:'+port+'/json/list')).json();
 const page=pages.find(p=>p.type==='page'); if(!page)throw Error('No Chrome page');
 ws=new WebSocket(page.webSocketDebuggerUrl);
 await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
 let seq=0;const pending=new Map();const events=[];
 ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.method)events.push(m);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}}};
 const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
 const evaluate=async expression=>(await cdp('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true})).result.value;
 const before=await evaluate('({width:innerWidth,height:innerHeight,dpr:devicePixelRatio})');
 await cdp('Page.enable');
 await cdp('Network.enable');
 await cdp('Emulation.setDeviceMetricsOverride',{width:requestedWidth,height:requestedHeight,deviceScaleFactor:1,mobile:true});
 await cdp('Page.navigate',{url});
 let ready=false;
 for(let i=0;i<150;i++){ready=await evaluate('!!document.querySelector("flutter-view,flt-glass-pane")');if(ready)break;await delay(100);}
 if(!ready)throw Error('Flutter view did not mount');
 await delay(2000);
 const after=await evaluate('({width:innerWidth,height:innerHeight,dpr:devicePixelRatio,visualWidth:visualViewport.width,bodyWidth:document.body.scrollWidth})');
 if(after.width!==requestedWidth||after.height!==requestedHeight||after.dpr!==1)throw Error('Incorrect viewport '+JSON.stringify(after));
 if(process.argv.includes('--drawer')) {await cdp('Input.dispatchMouseEvent',{type:'mousePressed',x:32,y:30,button:'left',clickCount:1});await cdp('Input.dispatchMouseEvent',{type:'mouseReleased',x:32,y:30,button:'left',clickCount:1});await delay(600);}
 if(process.argv.includes('--send')) {
  const click=async(x,y)=>{await cdp('Input.dispatchMouseEvent',{type:'mousePressed',x,y,button:'left',clickCount:1});await cdp('Input.dispatchMouseEvent',{type:'mouseReleased',x,y,button:'left',clickCount:1});};
  await click(100,requestedHeight-88); await cdp('Input.insertText',{text:'继续检查项目'}); await delay(200);
  await click(requestedWidth-48,requestedHeight-44); await delay(600);
 }
 const click=async(x,y)=>{await cdp('Input.dispatchMouseEvent',{type:'mousePressed',x,y,button:'left',clickCount:1});await cdp('Input.dispatchMouseEvent',{type:'mouseReleased',x,y,button:'left',clickCount:1});};
 if(process.argv.includes('--showcase')) {await click(32,39);await delay(500);await click(125,254);await delay(700);}
 if(process.argv.includes('--resources')) {await click(210,535);await delay(500);}
 if(process.argv.includes('--plan')) {await click(180,390);await delay(350);await click(190,474);await delay(500);}
 if(process.argv.includes('--preview')) {await click(72,663);await delay(800);await cdp('Input.dispatchMouseEvent',{type:'mouseWheel',x:220,y:650,deltaY:330,deltaX:0});await delay(600);const images=events.filter(e=>e.method==='Network.responseReceived'&&e.params.response.url.includes('tool-demo.png'));if(!images.some(e=>e.params.response.status===200))throw Error('Bundled image was not loaded successfully');console.log(JSON.stringify({imageLoaded:true,responses:images.map(e=>({url:e.params.response.url,status:e.params.response.status}))}));}
 if(process.argv.includes('--open-file')) {await click(75,762);await delay(700);const targets=(await cdp('Target.getTargets')).targetInfos;const file=targets.find(t=>t.type==='page'&&t.url.includes('tool-report.md'));if(!file)throw Error('File did not open in a browser target');console.log(JSON.stringify({fileOpened:true,url:file.url}));}
 if(process.argv.includes('--draft')) {await click(100,requestedHeight-88);await cdp('Input.insertText',{text:'继续检查项目'});await delay(350);}
 if(process.argv.includes('--readonly')) {await click(requestedWidth-104,requestedHeight-44);await delay(500);await click(160,867);await delay(500);}
 if(process.argv.includes('--models')) {await click(135,requestedHeight-44);await delay(700);}
 if(process.argv.includes('--permissions')) {await click(requestedWidth-104,requestedHeight-44);await delay(700);}
 if(process.argv.includes('--attachment')) {
   await cdp('Page.setInterceptFileChooserDialog',{enabled:true});
   await click(45,requestedHeight-44);
   let chooser;
   for(let i=0;i<50;i++){chooser=events.find(e=>e.method==='Page.fileChooserOpened');if(chooser)break;await delay(100);}
   if(!chooser)throw Error('File chooser did not open from plus button');
   await cdp('DOM.setFileInputFiles',{files:[path.resolve('composer-demo.txt')],backendNodeId:chooser.params.backendNodeId});
   await delay(700);
 }
  if(process.argv.includes('--runtime')) {await click(requestedWidth-95,39);await delay(700);}
 const shot=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
 await writeFile(out,Buffer.from(shot.data,'base64'));
 await writeFile(out+'.json',JSON.stringify({before,after,url,output:out,source:'Flutter web; explicit CDP mobile viewport; fixture data'},null,2));
 console.log(JSON.stringify({before,after,output:out}));
 await cdp('Browser.close').catch(()=>{});
} finally {
 ws?.close(); chrome.kill();await new Promise(r=>server.close(r));
 await delay(300);await rm(profile,{recursive:true,force:true,maxRetries:3,retryDelay:200}).catch(()=>{});
}

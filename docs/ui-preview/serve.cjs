const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),url=require('node:url');
const ROOT='E:/Deepeek harness/dsh-android/docs/ui-preview';
const PORT=Number(process.env.PORT||52901);
const MIME={'.html':'text/html; charset=utf-8','.png':'image/png','.jpg':'image/jpeg','.svg':'image/svg+xml','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.json':'application/json; charset=utf-8','.md':'text/markdown; charset=utf-8'};
const server=http.createServer((req,res)=>{
  if(req.method!=='GET'&&req.method!=='HEAD'){res.writeHead(405,{'Allow':'GET, HEAD','Content-Type':'text/plain; charset=utf-8'});res.end('405 Method Not Allowed: this is a static file server, use GET');return;}
  let p=decodeURIComponent(url.parse(req.url).pathname);
  if(p==='/')p='/2026-11-12-linux-workbench-redesign.html';
  const abs=path.join(ROOT,p);
  if(!abs.startsWith(path.resolve(ROOT))){res.writeHead(403);res.end('403');return;}
  fs.stat(abs,(e,st)=>{
    if(e||!st.isFile()){res.writeHead(404,{'Content-Type':'text/plain; charset=utf-8'});res.end('404 '+p);return;}
    res.writeHead(200,{'Content-Type':MIME[path.extname(abs).toLowerCase()]||'application/octet-stream','Content-Length':st.size,'Cache-Control':'no-store'});
    if(req.method==='HEAD'){res.end();return;}
    fs.createReadStream(abs).pipe(res);
  });
});
server.listen(PORT,'127.0.0.1',()=>{
  const info={url:'http://127.0.0.1:'+PORT+'/',root:ROOT,pid:process.pid,startedAt:new Date().toISOString()};
  fs.writeFileSync(ROOT+'/.preview-server.json',JSON.stringify(info,null,2));
  console.log(JSON.stringify(info));
});

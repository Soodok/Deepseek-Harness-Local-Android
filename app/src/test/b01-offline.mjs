import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
if (app.replaceAll('\\','/').toLowerCase() !== 'e:/tmp/wt-flutter-native-chat/app') throw new Error('wrong worktree');
const name = process.argv[2];
if (!/^[a-z0-9-]+$/.test(name ?? '')) throw new Error('supply unique evidence name');
const dir = path.join(app, 'src/test/b01-evidence', name);
fs.mkdirSync(path.dirname(dir), {recursive:true});
fs.mkdirSync(dir, {recursive:false});
const cache = 'C:/Users/Qt/.gradle/caches/modules-2/files-2.1/';
const jars = [
'org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.24/78dab849090e6c5e2eadb6e59a11cdc28fb67a08/kotlin-compiler-embeddable-1.9.24.jar',
'org.jetbrains.kotlin/kotlin-stdlib/1.9.24/9928532f12c66ad816a625b3f9984f8368ca6d2b/kotlin-stdlib-1.9.24.jar',
'org.jetbrains.kotlin/kotlin-script-runtime/1.9.24/96771497da90fbc5af1c90fce148db2595a62502/kotlin-script-runtime-1.9.24.jar',
'org.jetbrains.kotlin/kotlin-reflect/1.6.10/1cbe9c92c12a94eea200d23c2bbaedaf3daf5132/kotlin-reflect-1.6.10.jar',
'org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar',
'org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar',
'junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar',
'org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar',
].map(p => cache + p);
for (const p of jars) if (!fs.existsSync(p)) throw new Error('missing offline cache: '+p);
const java='C:/Users/Qt/.jwmv/candidates/java/17.35-tem/bin/java.exe';
const tests=['NativeChatColdEntryTest','NativeChatLaunchHandshakeTest'];
const helper=path.join(app,'src/main/java/app/dsh/mobile/coldentry');
const sources=tests.map(n=>path.join(app,'src/test/java/app/dsh/mobile',n+'.kt'));
if(fs.existsSync(helper)) for(const n of fs.readdirSync(helper)) if(n.endsWith('.kt')) sources.push(path.join(helper,n));
const extra=path.join(app,'src/test/java/app/dsh/mobile/coldentry');
if(fs.existsSync(extra)) for(const n of fs.readdirSync(extra)) if(n.endsWith('.kt')) {sources.push(path.join(extra,n));if(!n.endsWith('Cases.kt'))tests.push('coldentry.'+n.slice(0,-3));}
// Compile the exact production State declaration, not a hand-written duplicate or Android stub.
const supervisor=fs.readFileSync(path.join(app,'src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt'),'utf8');
if(supervisor.includes('fun coldEntrySnapshot()')) {
 const start=supervisor.indexOf('    sealed interface State {');
 const end=supervisor.indexOf('    private val _state',start);
 if(start<0||end<0)throw new Error('State extraction anchor missing');
 const f=path.join(dir,'SupervisorState.kt');
 fs.writeFileSync(f,'package app.dsh.mobile.engine\nimport app.dsh.mobile.coldentry.*\nclass EngineTriage { class Diagnosis }\nclass EngineSupervisor {\n'+supervisor.slice(start,end)+'}\n');sources.push(f);
}
const staged=sources.map(p=>{const target=path.join(dir,path.basename(p));if(path.resolve(p)!==path.resolve(target))fs.copyFileSync(p,target);return target;});
for(const n of ['MainActivity','engine/EngineSupervisor']) fs.copyFileSync(path.join(app,'src/main/java/app/dsh/mobile',n+'.kt'),path.join(dir,n.replace('/','-')+'.source.txt'));
const mutations={
 'allow-all':['ColdEntryCoordinator.kt','!snapshot.epoch.isNullOrBlank() && snapshot.hasLoadableUrl && (snapshot.phase == HostPhase.Healthy || snapshot.phase == HostPhase.SafeMode)','true'],
 'deny-all':['ColdEntryCoordinator.kt','!snapshot.epoch.isNullOrBlank() && snapshot.hasLoadableUrl && (snapshot.phase == HostPhase.Healthy || snapshot.phase == HostPhase.SafeMode)','false'],
 'result-ignore-code':['EntryResultRoutes.kt','if (owned.code != requestCode) return false','if (false) return false'],
 'result-reuse-code':['EntryResultRoutes.kt','val code = nextCode++','val code = FIRST_CODE'],
 'restore-stale-route':['EntryResultRoutes.kt','if (restored) return','if (false) return'],
 'writer-unlink-early':['ColdEntryCoordinator.kt','released && !writing && !deleted','released && !deleted'],
 'capture-ignore-owner':['ColdEntryCoordinator.kt','if (attempt.backdrop !== target) return false','if (false) return false'],
 'state-fixed-boot':['SupervisorState.kt','HostSnapshot(bootId,','HostSnapshot("fixed",'],
};
const mutant=process.argv[3];
if(mutant){const spec=mutations[mutant];if(!spec)throw new Error('unknown mutant');const p=path.join(dir,spec[0]);const t=fs.readFileSync(p,'utf8');if(!t.includes(spec[1]))throw new Error('mutation anchor absent');fs.writeFileSync(p,t.replaceAll(spec[1],spec[2]));}
fs.writeFileSync(path.join(dir,'inputs.json'),JSON.stringify({scope:'Offline Kotlin/JVM helpers + exact extracted State values and source adapter contracts; NOT Android/Flutter/device execution',mutant:mutant??null,inputs:staged.map(p=>({file:path.basename(p),sha256:crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')}))},null,2));
const opts=['-XX:-UsePerfData','-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+dir];
function execute(label,args){const r=spawnSync(java,[...opts,...args],{cwd:app,encoding:'utf8',timeout:120000,maxBuffer:4*1024*1024});const receipt={executable:java,args:[...opts,...args],exitCode:r.status,signal:r.signal,error:r.error?.message??null,stdout:r.stdout??'',stderr:r.stderr??''};fs.writeFileSync(path.join(dir,label+'.json'),JSON.stringify(receipt,null,2));console.log(label+' exit='+r.status+'\n'+receipt.stdout+receipt.stderr);return r.status;}
const jar=path.join(dir,'tests.jar');
let exit=execute('compile',['-cp',jars.join(';'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',jars.slice(1).join(';'),'-d',jar,...staged]);
if(exit===0)exit=execute('tests',['-cp',[jar,...jars.slice(1)].join(';'),'org.junit.runner.JUnitCore',...tests.map(n=>'app.dsh.mobile.'+n)]);
process.exitCode=exit??2;

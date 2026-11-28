import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
if (app.replaceAll('\\', '/').toLowerCase() !== 'e:/tmp/wt-flutter-native-chat/app') throw new Error('wrong worktree');
const name = process.argv[2];
if (!/^[a-z0-9-]+$/.test(name ?? '')) throw new Error('supply unique evidence name');
const dir = path.join(app, 'src/test/engine-info-evidence', name);
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
'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar',
].map(p => cache + p);
const classes = path.join(app, 'build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes');
const deps = [...jars.slice(1), classes,
    path.join(app, 'build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
    path.join(app, 'build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar'),
    'E:/Android/Sdk/platforms/android-36/android.jar'];
for (const p of [...jars, ...deps]) if (!fs.existsSync(p)) throw new Error('missing offline cache: '+p);
const inputs = [
 'src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt',
 'src/main/java/app/dsh/mobile/engine/NativeWorkspaceScope.kt',
 'src/main/java/app/dsh/mobile/engine/EngineModeSelector.kt',
 'src/main/java/app/dsh/mobile/coldentry/ColdEntryCoordinator.kt',
 'src/test/java/app/dsh/mobile/engine/NativeHostInfoSnapshotTest.kt',
 'src/test/java/app/dsh/mobile/engine/NativeWorkspaceScopeTest.kt',
 'src/test/java/app/dsh/mobile/engine/NativeWorkspacePublicationTest.kt',
 'src/test/java/app/dsh/mobile/NativeChatRuntimeSwitchContractTest.kt',
];
const hash = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const snapshots = inputs.map(p => {const source=path.join(app,p),target=path.join(dir,path.basename(p));fs.copyFileSync(source,target);return {source,target,sha256:hash(source)};});
const activitySource=path.join(app,'src/main/java/app/dsh/mobile/NativeChatActivity.kt');
const activityTarget=path.join(dir,'NativeChatActivity.source.kt');
fs.copyFileSync(activitySource,activityTarget);
snapshots.push({source:activitySource,target:activityTarget,sha256:hash(activitySource)});
const mutation=process.argv[3]??null;
function once(text,oldText,newText) {if(text.split(oldText).length!==2)throw new Error('mutation requires unique anchor: '+oldText);return text.replace(oldText,newText);}
if(mutation) {
 const p=snapshots[0].target;
 let s=fs.readFileSync(p,'utf8');
 let a=fs.readFileSync(activityTarget,'utf8');
 if(mutation==='deny-all') s=once(s,'val available: Boolean get() = webUrl.isNotEmpty()','val available: Boolean get() = false');
 else if(mutation==='scope-required') s=once(s,'val available: Boolean get() = webUrl.isNotEmpty()','val available: Boolean get() = webUrl.isNotEmpty() && workspaceScope != null');
 else if(mutation==='snapshot-unlocked') s=once(s,'fun nativeHostSnapshot(): NativeHostSnapshot = synchronized(entryPublicationLock)','fun nativeHostSnapshot(): NativeHostSnapshot = run');
 else if(mutation==='state-reread') s=once(s,'NativeHostSnapshot(publishedState, webUrl, workspaceScope)','NativeHostSnapshot(_state.value, webUrl, workspaceScope)');
 else if(mutation==='scope-generation') s=once(s,'launchedWorkspaceScope?.takeIf { it.generation == bootId }\n        } else null','launchedWorkspaceScope\n        } else null');
 else if(mutation==='owner-bypass') s=once(s,'&& entryBootId == bootId) {','&& true) {');
 else if(mutation==='safe-denied') s=once(s,'val available: Boolean get() = webUrl.isNotEmpty()','val available: Boolean get() = webUrl.isNotEmpty() && state !is State.SafeMode');
 else if(mutation==='bridge-available-reread') a=once(a,'"available" to (hostSnapshot?.available == true)','"available" to (supervisor?.hasLoadableUrl() == true)');
 else if(mutation==='bridge-state-reread') a=once(a,'"state" to (hostSnapshot?.state?.let { it::class.simpleName ?: "unknown" } ?: "uninitialized")','"state" to engineState(supervisor)');
 else if(mutation==='bridge-old') {const old=path.join(app,'src/test/engine-info-evidence/red-before-fix/NativeChatActivity.source.kt');const prior=fs.readFileSync(old,'utf8');const head='    private fun engineInfo(): Map<String, Any> {';const start=a.indexOf(head),end=a.indexOf('\n    /**',start);const ps=prior.indexOf(head),pe=prior.indexOf('\n    /**',ps);if([start,end,ps,pe].some(x=>x<0))throw new Error('bridge anchors absent');a=a.slice(0,start)+prior.slice(ps,pe)+a.slice(end);}
 else throw new Error('unknown mutation: '+mutation);
 fs.writeFileSync(p,s);fs.writeFileSync(activityTarget,a);
}
const activity=fs.readFileSync(activityTarget,'utf8');
function section(start,end) {const from=activity.indexOf(start),until=activity.indexOf(end,from+start.length);if(from<0||until<0)throw new Error('projection source anchors absent');return activity.slice(from,until);}
const info=section('    private fun engineInfo(): Map<String, Any> {','\n    /**');
const state=section('    private fun engineState(','\n    private fun openRuntimeSettings(');
const projection=path.join(dir,'NativeEngineInfoProjection.kt');
fs.writeFileSync(projection,'package app.dsh.mobile.engine\nclass NativeEngineInfoProjection(private val supervisor: EngineSupervisor?) {\n var pendingRuntimeTarget: String? = null\n var pendingRuntimeUrl = ""\n private fun supervisorOrNull() = supervisor\n private fun currentRuntimeWire() = "fixture-runtime"\n fun read(): Map<String, Any> = engineInfo()\n'+info+'\n'+state+'\n}\n');
const projectionTests=path.join(dir,'NativeEngineInfoProjectionTest.kt');
fs.writeFileSync(projectionTests, "package app.dsh.mobile.engine\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport org.junit.Assert.*\nimport org.junit.Test\nclass NativeEngineInfoProjectionTest {\n    private fun race(domain: String, starting: Boolean) {\n        val f = NativeHostInfoSnapshotTest.Fixture()\n        val b = NativeWorkspaceScope(domain, \"boot-B\")\n        var reads = 0\n        val state = object : MutableStateFlow<EngineSupervisor.State> by f.state {\n            override var value: EngineSupervisor.State\n                get() {\n                    if (reads++ > 0) {\n                        if (starting) f.starting(b) else f.ready(b, safe = true, url = \"endpoint-B\")\n                    }\n                    return f.state.value\n                }\n                set(value) { f.state.value = value }\n        }\n        f.set(\"_state\", state); f.set(\"state\", state)\n        val info = NativeEngineInfoProjection(f.supervisor).read()\n        assertTrue(\"endpoint is from A\", info[\"webUrl\"] == \"endpoint-A\")\n        assertEquals(\"Healthy\", info[\"state\"])\n        assertEquals(true, info[\"available\"])\n        assertEquals(f.a.domain, info[\"workspaceDomain\"])\n        assertEquals(f.a.generation, info[\"workspaceGeneration\"])\n        assertEquals(\"exact native info body must read one Host publication\", 1, reads)\n    }\n    @Test fun sameDomainRolloverCannotMixAEndpointWithBIdentity() = race(\"storage-a\", false)\n    @Test fun crossDomainRolloverCannotMixAEndpointWithBIdentity() = race(\"storage-b\", false)\n    @Test fun startingBDoesNotChangeAnAlreadyCapturedAResponse() = race(\"storage-b\", true)\n    @Test fun bothReadyModesAllowOrdinaryChatWithoutPersistenceScope() {\n        for (safe in listOf(false, true)) {\n            val f = NativeHostInfoSnapshotTest.Fixture(); f.ready(f.a, safe)\n            f.set(\"launchedWorkspaceScope\", null)\n            val info = NativeEngineInfoProjection(f.supervisor).read()\n            assertEquals(true, info[\"available\"])\n            assertTrue(\"ready endpoint is retained\", info[\"webUrl\"] == \"endpoint-A\")\n            assertEquals(if (safe) \"SafeMode\" else \"Healthy\", info[\"state\"])\n            assertEquals(\"\", info[\"workspaceDomain\"]); assertEquals(\"\", info[\"workspaceGeneration\"])\n        }\n    }\n    @Test fun startingBAndReadyBAreProjectedFromTheirOwnPublications() {\n        for (domain in listOf(\"storage-a\", \"storage-b\")) for (safe in listOf(false, true)) {\n            val f = NativeHostInfoSnapshotTest.Fixture(); val p = NativeEngineInfoProjection(f.supervisor)\n            val b = NativeWorkspaceScope(domain, \"boot-B\"); f.starting(b)\n            val waiting = p.read()\n            assertEquals(\"Starting\", waiting[\"state\"]); assertEquals(false, waiting[\"available\"])\n            assertTrue(\"starting response has no old endpoint\", waiting[\"webUrl\"] == \"\")\n            assertEquals(\"\", waiting[\"workspaceDomain\"]); assertEquals(\"\", waiting[\"workspaceGeneration\"])\n            f.ready(b, safe, \"endpoint-B\")\n            val ready = p.read()\n            assertEquals(if (safe) \"SafeMode\" else \"Healthy\", ready[\"state\"])\n            assertEquals(true, ready[\"available\"])\n            assertTrue(\"B endpoint is retained\", ready[\"webUrl\"] == \"endpoint-B\")\n            assertEquals(b.domain, ready[\"workspaceDomain\"]); assertEquals(b.generation, ready[\"workspaceGeneration\"])\n        }\n    }\n    @Test fun pendingRestartUsesTheVeryEndpointReturnedAndAllExistingFieldsRemain() {\n        val f = NativeHostInfoSnapshotTest.Fixture(); val p = NativeEngineInfoProjection(f.supervisor)\n        p.pendingRuntimeTarget = \"linux\"; p.pendingRuntimeUrl = \"endpoint-A\"\n        val a = p.read(); assertEquals(true, a[\"runtimeRestartPending\"])\n        assertEquals(setOf(\"webUrl\", \"available\", \"state\", \"runtimeMode\", \"runtimeResolved\", \"runtimeReason\", \"distro\", \"runtimeRestartPending\", \"workspaceDomain\", \"workspaceGeneration\"), a.keys)\n        assertEquals(\"fixture-runtime\", a[\"runtimeMode\"])\n        assertEquals(EngineModeSelector.resolved, a[\"runtimeResolved\"])\n        assertEquals(EngineModeSelector.lastReason, a[\"runtimeReason\"])\n        assertEquals(EngineModeSelector.lastDistro, a[\"distro\"])\n        f.ready(NativeWorkspaceScope(\"storage-b\", \"boot-B\"), url = \"endpoint-B\")\n        val b = p.read(); assertEquals(false, b[\"runtimeRestartPending\"])\n        assertNull(p.pendingRuntimeTarget); assertTrue(\"pending endpoint cleared\", p.pendingRuntimeUrl.isEmpty())\n    }\n    @Test fun absentSupervisorPreservesUninitializedWireState() {\n        val info = NativeEngineInfoProjection(null).read()\n        assertEquals(\"uninitialized\", info[\"state\"]); assertEquals(false, info[\"available\"])\n        assertTrue(\"uninitialized has no endpoint\", info[\"webUrl\"] == \"\")\n        assertEquals(\"\", info[\"workspaceDomain\"]); assertEquals(\"\", info[\"workspaceGeneration\"])\n    }\n}\n");
for(const s of snapshots)s.compiledSha256=hash(s.target);
const java='C:/Users/Qt/.jwmv/candidates/java/17.35-tem/bin/java.exe';
const opts=['-XX:-UsePerfData','-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+dir];
function execute(label,args) {const r=spawnSync(java,[...opts,...args],{cwd:app,encoding:'utf8',timeout:120000,maxBuffer:4*1024*1024});const result={executable:java,args:[...opts,...args],exitCode:r.status,signal:r.signal,error:r.error?.message??null,stdout:r.stdout??'',stderr:r.stderr??''};fs.writeFileSync(path.join(dir,label+'.json'),JSON.stringify(result,null,2));fs.writeFileSync(path.join(dir,label+'.log'),result.stdout+result.stderr);console.log(label+' exit='+r.status+'\n'+result.stdout+result.stderr);return r.status;}
const jar=path.join(dir,'tests.jar');
const compile=execute('compile',['-cp',jars.join(';'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-Xskip-metadata-version-check','-Xskip-prerelease-check','-jvm-target','17','-classpath',deps.join(';'),'-d',jar,...snapshots.slice(0,-1).map(s=>s.target),projection,projectionTests]);
let tests=null;
if(compile===0)tests=execute('tests',['-Dengine.info.activity.source='+activityTarget,'-Dworkspace.supervisor.source='+snapshots[0].target,'-cp',[jar,...deps].join(';'),'org.junit.runner.JUnitCore','app.dsh.mobile.engine.NativeHostInfoSnapshotTest','app.dsh.mobile.engine.NativeEngineInfoProjectionTest','app.dsh.mobile.engine.NativeWorkspaceScopeTest','app.dsh.mobile.engine.NativeWorkspacePublicationTest','app.dsh.mobile.NativeChatRuntimeSwitchContractTest']);
const generated=[projection,projectionTests].map(p=>({path:p,sha256:hash(p)}));
fs.writeFileSync(path.join(dir,'receipt.json'),JSON.stringify({scope:'Offline cached Kotlin/JVM: complete production supervisor + real snapshot calls; exact native engineInfo/engineState source bodies executed in a JVM projection shell (currentRuntimeWire fixture). Static MethodChannel dispatch binding only; NOT Android Activity or MethodChannel execution.',mutation,compileExit:compile,testExit:tests,inputs:snapshots,generated,runner:{path:fileURLToPath(import.meta.url),sha256:hash(fileURLToPath(import.meta.url))},sourceStillMatches:snapshots.every(s=>hash(s.source)===s.sha256),androidRun:false,methodChannelRun:false,deviceTouched:false},null,2));
process.exitCode=compile!==0?compile??2:tests??2;

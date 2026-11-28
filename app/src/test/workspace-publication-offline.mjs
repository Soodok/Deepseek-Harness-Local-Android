import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
if (app.replaceAll('\\', '/').toLowerCase() !== 'e:/tmp/wt-flutter-native-chat/app') throw new Error('wrong worktree');
const name = process.argv[2];
if (!/^[a-z0-9-]+$/.test(name ?? '')) throw new Error('supply unique evidence name');
const dir = path.join(app, 'src/test/workspace-publication-evidence', name);
fs.mkdirSync(path.dirname(dir), { recursive: true });
fs.mkdirSync(dir, { recursive: false });
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
for (const p of [...jars, ...deps]) if (!fs.existsSync(p)) throw new Error('missing offline cache: ' + p);
const inputs = [
    'src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt',
    'src/main/java/app/dsh/mobile/engine/NativeWorkspaceScope.kt',
    'src/test/java/app/dsh/mobile/engine/NativeWorkspaceScopeTest.kt',
    'src/test/java/app/dsh/mobile/engine/NativeWorkspacePublicationTest.kt',
];
const hash = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const snapshots = inputs.map(p => {
    const source = path.join(app, p), target = path.join(dir, path.basename(p));
    fs.copyFileSync(source, target);
    return { source, target, sha256: hash(source) };
});
const mutation = process.argv[3] ?? null;
const stagedSource = snapshots[0].target;
if (mutation) {
    const source = fs.readFileSync(stagedSource, 'utf8');
    const start = source.indexOf('    fun nativeWorkspaceScope():');
    const end = source.indexOf('    /** 启动前摘掉的插件名', start);
    if (start < 0 || end < 0) throw new Error('scope mutation anchors missing');
    let block = source.slice(start, end);
    const accessorEnd = block.indexOf('    /** A late spawn');
    let accessor = block.slice(0, accessorEnd), publisher = block.slice(accessorEnd);
    const once = (text, oldText, newText) => {
        if (text.split(oldText).length !== 2) throw new Error('mutation anchor must occur once: ' + oldText);
        return text.replace(oldText, newText);
    };
    switch (mutation) {
        case 'old-accessor': accessor = '    fun nativeWorkspaceScope(): NativeWorkspaceScope? {\n        val proc = process ?: return null\n        if (stopping || proc.exitFuture.isDone || !hasLoadableUrl()) return null\n        return launchedWorkspaceScope\n    }\n\n'; break;
        case 'read-unlocked': accessor = once(accessor, 'synchronized(entryPublicationLock)', 'run').replaceAll('return@synchronized', 'return@run'); break;
        case 'no-scope-generation': accessor = once(accessor, 'launchedWorkspaceScope?.takeIf { it.generation == bootId }', 'launchedWorkspaceScope'); break;
        case 'no-ready-owner': accessor = once(accessor, ' || entryBootId != bootId', ''); break;
        case 'no-url-check': accessor = once(accessor, '!ready.hasLoadableUrl || ', ''); break;
        case 'publish-unlocked': publisher = once(publisher, 'synchronized(entryPublicationLock)', 'run'); break;
        case 'publish-stale': publisher = once(publisher, 'entryBootId == bootId && ', ''); break;
        case 'publish-stopped': publisher = once(publisher, ' && !stopping', ''); break;
        case 'publish-maintenance': publisher = once(publisher, ' && !maintenanceStop', ''); break;
        case 'linux-unwired': break;
        case 'native-unwired': break;
        case 'unguarded-reset': break;
        default: throw new Error('unknown mutation: ' + mutation);
    }
    let mutated = source.slice(0, start) + accessor + publisher + source.slice(end);
    if (mutation === 'linux-unwired') mutated = once(mutated, 'publishWorkspaceScope(bootId, spec.nativeWorkspaceDomain)', 'Unit');
    if (mutation === 'native-unwired') mutated = once(mutated, 'publishWorkspaceScope(bootId, nativeDomain)', 'Unit');
    if (mutation === 'unguarded-reset') mutated = once(mutated, 'publishWorkspaceScope(bootId, null)', 'launchedWorkspaceScope = null');
    if (source === mutated) throw new Error('mutation made no change');
    fs.writeFileSync(stagedSource, mutated);
}
for (const s of snapshots) s.compiledSha256 = hash(s.target);
const java = 'C:/Users/Qt/.jwmv/candidates/java/17.35-tem/bin/java.exe';
const opts = ['-XX:-UsePerfData', '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + dir];
function execute(label, args) {
    const r = spawnSync(java, [...opts, ...args], { cwd: app, encoding: 'utf8', timeout: 120000, maxBuffer: 4 * 1024 * 1024 });
    const receipt = { executable: java, args: [...opts, ...args], exitCode: r.status, signal: r.signal, error: r.error?.message ?? null, stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
    fs.writeFileSync(path.join(dir, label + '.json'), JSON.stringify(receipt, null, 2));
    fs.writeFileSync(path.join(dir, label + '.log'), receipt.stdout + receipt.stderr);
    console.log(label + ' exit=' + r.status + '\n' + receipt.stdout + receipt.stderr);
    return r.status;
}
const jar = path.join(dir, 'tests.jar');
const compile = execute('compile', ['-cp', jars.join(';'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-Xskip-metadata-version-check', '-Xskip-prerelease-check', '-jvm-target', '17', '-classpath', deps.join(';'),
    '-d', jar, ...snapshots.map(s => s.target)]);
let tests = null;
if (compile === 0) tests = execute('tests', ['-Dworkspace.supervisor.source=' + stagedSource, '-cp', [jar, ...deps].join(';'), 'org.junit.runner.JUnitCore',
    'app.dsh.mobile.engine.NativeWorkspaceScopeTest', 'app.dsh.mobile.engine.NativeWorkspacePublicationTest']);
fs.writeFileSync(path.join(dir, 'receipt.json'), JSON.stringify({ scope: 'Independent cached Kotlin/JVM compilation of complete current EngineSupervisor and real accessor tests; Android API/class caches only for linking, NOT Android runtime, Gradle, Flutter or device validation', mutation, compileExit: compile, testExit: tests, inputs: snapshots, sourceStillMatches: snapshots.every(s => hash(s.source) === s.sha256), androidRun: false, deviceTouched: false }, null, 2));
process.exitCode = compile !== 0 ? compile ?? 2 : tests ?? 2;

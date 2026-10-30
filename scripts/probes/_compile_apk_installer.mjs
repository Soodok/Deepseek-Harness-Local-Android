import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

// This checks the real Java classes and the exact modified Kotlin method against
// Android API signatures. It is NOT a full APK build or an instrumentation test.
const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.SOURCE_ROOT ?? path.resolve(here, '../..');
const androidJar = process.env.ANDROID_JAR;
const kotlinLib = process.env.KOTLIN_LIB;
if (!androidJar || !kotlinLib) throw new Error('Set ANDROID_JAR and KOTLIN_LIB (Kotlin compiler + stdlib + coroutines jars).');
const work = fs.mkdtempSync(path.join(os.tmpdir(),'dsh-apk-api-compile-'));
const javaRoot = path.join(root,'app/src/main/java');
function run(program,args) {
  const r = spawnSync(program,args,{encoding:'utf8'});
  if (r.error || r.status !== 0) throw new Error(`${program} failed: ${r.error ?? ''}\n${r.stdout}\n${r.stderr}`);
  if (r.stderr) process.stderr.write(r.stderr);
}
const classes = path.join(work,'classes');
run(process.env.JAVAC ?? 'javac',['--release','17','-cp',androidJar,'-d',classes,
  path.join(javaRoot,'app/dsh/mobile/engine/ApkInstallStatus.java'),
  path.join(javaRoot,'app/dsh/mobile/ExtensionApkInstallActivity.java')]);
const manager = fs.readFileSync(path.join(javaRoot,'app/dsh/mobile/engine/ExtensionManager.kt'),'utf8');
const start = manager.indexOf('    private suspend fun installApkTask(');
const end = manager.indexOf('    private fun urllibRequest(',start);
if (start < 0 || end < 0) throw new Error('Kotlin source contract drift: exact method not found');
const fixture = `package app.dsh.mobile.engine
import android.os.Build
import android.util.Log
import java.io.File
class CompileOnlyInstaller(private val ctx: android.content.Context) {
    data class Extension(val id: String, val name: String, val apkPackage: String)
    enum class TaskState { DONE }
    data class ExtTask(val state: TaskState, val progress: Float, val stage: String)
    private fun updateTask(id: String, f: (ExtTask) -> ExtTask) {}
    private fun urllibRequest(url: String) = java.net.URL(url).openConnection()
    companion object { const val TAG = "CompileOnly" }
${manager.slice(start,end)}
}
`;
const file = path.join(work,'CompileOnlyInstaller.kt'); fs.writeFileSync(file,fixture);
const jars = fs.readdirSync(kotlinLib).filter(n => /^kotlin-stdlib-.*\.jar$|^kotlinx-coroutines-core-jvm-.*\.jar$/.test(n));
if (jars.length < 2) throw new Error('Missing Kotlin stdlib/coroutines compiler dependencies');
const classpath = [androidJar,classes,...jars.map(j=>path.join(kotlinLib,j))].join(path.delimiter);
run(process.env.JAVA ?? 'java',['-cp',path.join(kotlinLib,'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-jvm-target','17','-no-stdlib','-no-reflect','-classpath',classpath,'-d',path.join(work,'kotlin-classes'),file]);
console.log(JSON.stringify({javaAndroidApiCompile:'passed', exactKotlinMethodTypecheck:'passed',
  fullAndroidBuild:'not-run', deviceInstrumentation:'not-run'},null,2));

import test, { before } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = process.env.SOURCE_ROOT ?? path.resolve(here, '../..');
const javaRoot = path.join(root, 'app/src/main/java');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-apk-callback-tests-'));
const sources = {
  'android/os/Bundle.java': 'package android.os; public class Bundle {}',
  'android/content/pm/PackageInstaller.java': `package android.content.pm; public final class PackageInstaller {
    public static final int STATUS_PENDING_USER_ACTION=-1, STATUS_SUCCESS=0, STATUS_FAILURE=1, STATUS_FAILURE_ABORTED=3;
    public static final String EXTRA_STATUS="status", EXTRA_STATUS_MESSAGE="message";
  }`,
  'android/content/Intent.java': `package android.content; import java.util.HashMap; public class Intent {
    public static final String EXTRA_INTENT="confirmation"; private final HashMap<String,Object> extras=new HashMap<>();
    public Intent putExtra(String key,Object value){ extras.put(key,value);return this; }
    public boolean hasExtra(String key){ return extras.containsKey(key); }
    public int getIntExtra(String key,int fallback){ Object value=extras.get(key);return value instanceof Integer?(Integer)value:fallback; }
    public String getStringExtra(String key){ return (String)extras.get(key); }
    @SuppressWarnings("unchecked") public <T> T getParcelableExtra(String key){ return (T)extras.get(key); }
  }`,
  'android/app/Activity.java': `package android.app; import android.os.Bundle; import android.content.Intent; public class Activity {
    private Intent intent; public boolean finished; public Intent started; public boolean failLaunch;
    protected void onCreate(Bundle state){} protected void onNewIntent(Intent value){}
    public Intent getIntent(){ return intent; } public void setIntent(Intent value){intent=value;}
    public void finish(){finished=true;} public void startActivity(Intent value){if(failLaunch)throw new IllegalStateException("synthetic launch denied");started=value;}
  }`,
  'app/dsh/mobile/CallbackTest.java': `package app.dsh.mobile;
    import android.content.Intent; import android.content.pm.PackageInstaller; import app.dsh.mobile.engine.ApkInstallStatus;
    public class CallbackTest {
      static void check(boolean condition,String why){if(!condition)throw new AssertionError(why);}
      static void await(java.util.concurrent.CountDownLatch gate){try{gate.await();}catch(InterruptedException e){throw new AssertionError(e);}}
      static void join(Thread thread){try{thread.join();}catch(InterruptedException e){throw new AssertionError(e);}}
      static Intent result(int id,int status){return new Intent().putExtra(ExtensionApkInstallActivity.EXTRA_OWNED_SESSION,id).putExtra(PackageInstaller.EXTRA_STATUS,status);}
      static ExtensionApkInstallActivity run(Intent intent){ExtensionApkInstallActivity activity=new ExtensionApkInstallActivity();activity.setIntent(intent);activity.onCreate(null);check(activity.finished,"callback must finish");return activity;}
      public static void main(String[] args){String mode=args[0];ApkInstallStatus.begin(12);
        switch(mode){
          case "pending": {Intent confirmation=new Intent();ExtensionApkInstallActivity a=run(result(12,-1).putExtra(Intent.EXTRA_INTENT,confirmation));check(a.started==confirmation,"must launch exact confirmation");check(ApkInstallStatus.get(12)==null,"pending is not done");break;}
          case "missing-confirmation": {run(result(12,-1));check(!ApkInstallStatus.get(12).success,"missing intent fails");break;}
          case "launch-denied": {ExtensionApkInstallActivity a=new ExtensionApkInstallActivity();a.failLaunch=true;a.setIntent(result(12,-1).putExtra(Intent.EXTRA_INTENT,new Intent()));a.onCreate(null);check(a.finished,"failure must finish");check(ApkInstallStatus.get(12).message.contains("synthetic launch denied"),"precise launch failure");break;}
          case "success": {run(result(12,0));check(ApkInstallStatus.get(12).success,"success callback");break;}
          case "cancelled": {run(result(12,3).putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,"synthetic cancelled"));check(!ApkInstallStatus.get(12).success,"cancelled must fail");check(ApkInstallStatus.get(12).message.equals("synthetic cancelled"),"preserve failure details");break;}
          case "unknown-status": {run(result(12,999));check(!ApkInstallStatus.get(12).success,"unknown status fails closed");break;}
          case "missing-status": {run(new Intent().putExtra(ExtensionApkInstallActivity.EXTRA_OWNED_SESSION,12));check(ApkInstallStatus.get(12)==null,"no invented status");break;}
          case "missing-session": {run(new Intent().putExtra(PackageInstaller.EXTRA_STATUS,0));check(ApkInstallStatus.get(12)==null,"no invented session");break;}
          case "null-intent": {run(null);check(ApkInstallStatus.get(12)==null,"null ignored");break;}
          case "isolation": {ApkInstallStatus.begin(13);run(result(13,0));check(ApkInstallStatus.get(12)==null,"separate session intact");check(ApkInstallStatus.get(13).success,"right session complete");break;}
          case "duplicate": {run(result(12,3));run(result(12,0));check(!ApkInstallStatus.get(12).success,"first terminal wins");break;}
          case "late": {ApkInstallStatus.clear(12);run(result(12,0));check(ApkInstallStatus.get(12)==null,"late result cannot leak");break;}
          case "late-pending": {ApkInstallStatus.clear(12);ExtensionApkInstallActivity a=run(result(12,-1).putExtra(Intent.EXTRA_INTENT,new Intent()));check(a.started==null,"cleared session cannot reopen confirmation");break;}
          case "terminal-then-pending": {run(result(12,3));ExtensionApkInstallActivity a=run(result(12,-1).putExtra(Intent.EXTRA_INTENT,new Intent()));check(a.started==null,"terminal session cannot reopen confirmation");check(!ApkInstallStatus.get(12).success,"terminal result preserved");break;}
          case "new-intent": {ExtensionApkInstallActivity a=new ExtensionApkInstallActivity();a.onNewIntent(result(12,0));check(a.finished&&ApkInstallStatus.get(12).success,"onNewIntent handled");break;}
          case "invalid-session": {run(result(-2,0));check(ApkInstallStatus.get(12)==null,"negative ignored");break;}
          case "untracked-pending": {ExtensionApkInstallActivity a=run(result(13,-1).putExtra(Intent.EXTRA_INTENT,new Intent()));check(a.started==null,"no worker owns confirmation (including after process death)");check(ApkInstallStatus.get(13)==null,"must not create tracking state");break;}
          case "untracked-terminal": {run(result(13,0));check(ApkInstallStatus.get(13)==null,"unowned result ignored");break;}
          case "duplicate-begin": {boolean rejected=false;try{ApkInstallStatus.begin(12);}catch(IllegalStateException expected){rejected=true;}check(rejected&&ApkInstallStatus.isPending(12),"duplicate owner rejected without replacing state");break;}
          case "invalid-begin": {boolean rejected=false;try{ApkInstallStatus.begin(-2);}catch(IllegalArgumentException expected){rejected=true;}check(rejected&&!ApkInstallStatus.isPending(-2),"negative owner rejected");break;}
          case "concurrent-terminal": {
            for(int i=0;i<100;i++){
              int id=100+i;ApkInstallStatus.begin(id);
              java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1);
              Thread success=new Thread(()->{await(gate);ApkInstallStatus.complete(id,true,"success");});
              Thread failure=new Thread(()->{await(gate);ApkInstallStatus.complete(id,false,"failure");});
              success.start();failure.start();gate.countDown();join(success);join(failure);
              ApkInstallStatus.Result winner=ApkInstallStatus.get(id);check(winner!=null,"terminal callback visible across threads");
              check(winner.message.equals(winner.success?"success":"failure"),"one complete immutable result");
              ApkInstallStatus.complete(id,!winner.success,"late");check(ApkInstallStatus.get(id)==winner,"first concurrent terminal retained");ApkInstallStatus.clear(id);
            }break;
          }
          case "concurrent-clear": {
            for(int i=0;i<100;i++){
              int id=100+i;ApkInstallStatus.begin(id);
              java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1);
              Thread callback=new Thread(()->{await(gate);ApkInstallStatus.complete(id,true,"late");});
              Thread cleanup=new Thread(()->{await(gate);ApkInstallStatus.clear(id);});
              callback.start();cleanup.start();gate.countDown();join(callback);join(cleanup);
              check(ApkInstallStatus.get(id)==null&&!ApkInstallStatus.isPending(id),"cleanup race cannot resurrect entry");
            }break;
          }
          default: throw new AssertionError("unknown case");
        }
        System.out.println("PASS "+mode);
      }
    }`,
};
function command(program, args) {
  const result = spawnSync(program, args, { encoding: 'utf8' });
  assert.ifError(result.error);
  assert.equal(result.status, 0, `${program} failed: ${result.stdout}\n${result.stderr}`);
  return result;
}
before(() => {
  const files = [];
  for (const [name, text] of Object.entries(sources)) {
    const file = path.join(work, 'fixtures', name); fs.mkdirSync(path.dirname(file), { recursive:true }); fs.writeFileSync(file,text); files.push(file);
  }
  files.push(path.join(javaRoot,'app/dsh/mobile/ExtensionApkInstallActivity.java'), path.join(javaRoot,'app/dsh/mobile/engine/ApkInstallStatus.java'));
  command(process.env.JAVAC ?? 'javac', ['--release', '17', '-d', path.join(work,'classes'), ...files]);
});
for (const mode of ['pending','missing-confirmation','launch-denied','success','cancelled','unknown-status','missing-status','missing-session','null-intent','isolation','duplicate','late','late-pending','terminal-then-pending','new-intent','invalid-session','untracked-pending','untracked-terminal','duplicate-begin','invalid-begin','concurrent-terminal','concurrent-clear']) {
  test(`actual Java callback with synthetic Android lifecycle: ${mode}`, () => {
    const result = command(process.env.JAVA ?? 'java', ['-cp',path.join(work,'classes'),'app.dsh.mobile.CallbackTest',mode]);
    assert.match(result.stdout,new RegExp(`PASS ${mode}`));
  });
}
const manager = fs.readFileSync(path.join(javaRoot,'app/dsh/mobile/engine/ExtensionManager.kt'),'utf8');
const manifest = fs.readFileSync(path.join(root,'app/src/main/AndroidManifest.xml'),'utf8');
test('callback is non-exported and not the generic launcher', () => {
  assert.match(manifest, /android:name="\.ExtensionApkInstallActivity"\s+android:exported="false"/);
  assert.match(manager, /Intent\(ctx, app\.dsh\.mobile\.ExtensionApkInstallActivity::class\.java\)/);
});
test('explicit mutable intent sender permits framework result extras on API 31+', () => {
  assert.match(manager, /Build\.VERSION\.SDK_INT >= 31\) android\.app\.PendingIntent\.FLAG_MUTABLE/);
});
test('task success requires terminal status, not a previously installed package', () => {
  const method = manager.slice(manager.indexOf('private suspend fun installApkTask('),manager.indexOf('private fun urllibRequest('));
  assert.match(method,/check\(result\.success\)/);
  assert.doesNotMatch(method,/getInstalledPackages/);
  assert.match(method,/SystemClock\.elapsedRealtime\(\)/);
});
test('session and tracker cleanup is in finally', () => {
  assert.match(manager, /finally \{\s+ApkInstallStatus\.clear\(sessionId\)/);
  assert.match(manager, /pi\.abandonSession\(sessionId\)/);
  assert.match(manager, /pending\?\.cancel\(\)/);
});
test('issue 47 parser retains all three APK fields and APK-first routing', () => {
  for (const field of ['apkUrl','apkSha256','apkPackage']) assert.match(manager,new RegExp(`${field} = o\\.optString\\("${field}"`));
  assert.match(manager,/if \(ext\.apkUrl\.isNotBlank\(\)\) \{\s+installApkTask/);
});

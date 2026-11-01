#!/usr/bin/env python3
"""索引签名失败的诊断文案（issue #49 P2 第三项）。

原先 `publish-update-index.sh` 与 `.github/workflows/android-build.yml` 都用
`openssl pkcs12 ... 2>/dev/null` 丢掉 stderr，口令输错时用户看到的是后面
`sign-runtime-index.py` 对空 PEM 的报错（"openssl failed (1): "），
完全看不出是口令问题。

本探针干两件事：
  1. 静态：两个文件都必须把 openssl 的 stderr 收进变量、按 `invalid password`
     分流、且在 PEM 里没有私钥时单独报错；
  2. 真跑：用真 keystore（存在才跑）验证「正确口令 → 成功」「错误口令 → 报口令
     错误而不是泛泛失败」，避免只有字符串检查却从没被执行过。
"""
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SH = os.path.join(ROOT, "scripts", "publish-update-index.sh")
WF = os.path.join(ROOT, ".github", "workflows", "android-build.yml")

checks = 0
failures = []


def check(cond, msg):
    global checks
    checks += 1
    if not cond:
        failures.append(msg)


def static_checks(path, label):
    if not os.path.isfile(path):
        check(False, "%s: file missing" % label)
        return
    s = io.open(path, encoding="utf-8").read()
    # 1) 必须把 stderr 收进变量并分流（而不是 2>/dev/null 闷掉）
    check("KERR=" in s or "ERR=" in s, "%s: openssl stderr not captured" % label)
    check("invalid password" in s, "%s: no password-specific branch" % label)
    check("PRIVATE KEY" in s, "%s: no 'PEM has no private key' guard" % label)
    # 2) legacy 回退只能在「非口令问题」之后，不能先回退再报错
    legacy = s.find("-legacy")
    pwd = s.find("invalid password")
    check(legacy > 0 and pwd > 0, "%s: legacy/password branches missing" % label)
    if legacy > 0 and pwd > 0:
        check(pwd < legacy,
              "%s: password branch must come BEFORE the -legacy fallback" % label)
    # 3) 不能对证书导出这一步继续吞掉 stderr
    check(re.search(r"-clcerts \\\n?\s*-passin[^\n]*2>/dev/null", s) is None,
          "%s: cert export still swallows stderr with 2>/dev/null" % label)


static_checks(SH, "publish-update-index.sh")
static_checks(WF, "android-build.yml")

# --- 真跑：有 openssl + keytool 才构造临时 keystore ---
openssl = shutil.which("openssl")
keystore = os.environ.get("DSH_TEST_PKCS12")
if openssl and not keystore:
    # 不在仓库里造 keystore（会进 git）；用临时目录里的临时 key 试
    jbr = os.environ.get("JAVA_HOME", "")
    keytool = os.path.join(jbr, "bin", "keytool.exe") if jbr else ""
    if not os.path.isfile(keytool):
        keytool = shutil.which("keytool") or ""
    if keytool:
        tmp = tempfile.mkdtemp(prefix="dsh-p12-")
        p12 = os.path.join(tmp, "t.p12")
        r = subprocess.run(
            [keytool, "-genkeypair", "-alias", "dsh", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "1", "-storetype", "PKCS12", "-keystore", p12,
             "-storepass", "rightpass", "-keypass", "rightpass", "-dname", "CN=test"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if r.returncode == 0:
            keystore = p12

if keystore and os.path.isfile(keystore) and openssl:
    def run_pwd(password):
        p = subprocess.run(
            [openssl, "pkcs12", "-in", keystore, "-nocerts", "-nodes",
             "-passin", "pass:" + password, "-out", os.devnull],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return p.returncode, p.stderr.decode("utf-8", "replace")

    rc_ok, err_ok = run_pwd("rightpass")
    check(rc_ok == 0, "correct password should succeed, got rc=%d (%s)" % (rc_ok, err_ok[:80]))
    rc_bad, err_bad = run_pwd("definitely-wrong-password")
    check(rc_bad != 0, "wrong password must fail, got rc=0")
    check("invalid password" in err_bad.lower(),
          "openssl's wrong-password stderr should contain 'invalid password', got %r"
          % err_bad[:120])
    # 这正是分流依据：脚本靠这个子串区分「口令错」与「格式不支持」
    check(re.search(r"invalid password", err_bad, re.I) is not None,
          "the branch predicate must actually match openssl's real stderr")
else:
    print("skip behaviour checks (no openssl/keytool/keystore available)")

print("index signing diagnostics: %d checks, %d failed" % (checks, len(failures)))
for f in failures:
    print("  FAIL:", f)
sys.exit(1 if failures else 0)

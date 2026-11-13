import io, os, re, sys
ROOT = os.getcwd()
ok = 0; bad = 0
def check(name, cond, detail=""):
    global ok, bad
    if cond:
        ok += 1; print("PASS  " + name)
    else:
        bad += 1; print("FAIL  " + name + ("  -> " + detail if detail else ""))

cat_src = io.open(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxRootfsCatalog.kt"), encoding="utf-8").read()
spl = io.open(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ui/SplashController.kt"), encoding="utf-8").read()

check("Family 有 libc 属性", "val libc: String" in cat_src)
check("Ubuntu 判为 glibc", "UBUNTU -> \"glibc\"" in cat_src)
check("其余判为 musl", "else -> \"musl\"" in cat_src)
check("启动页读 active().family.libc",
      "LinuxRootfsCatalog.active(activity).family.libc" in spl,
      "不读它就会一直显示写死的 musl")
check("启动页仍保留原 subRes 分支（原生模式走它）",
      "R.string.splash_mode_native_sub" in spl)
check("副标题用的是 subText 而不是 subRes",
      "modeSub.setText(subText)" in spl and "modeSub.setText(subRes)" not in spl)
check("六语种都有新键",
      all(("splash_mode_linux_sub_libc" in io.open(os.path.join(ROOT, "app/src/main/res", d, "strings.xml"), encoding="utf-8").read())
          for d in ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-ru", "values-th"]))
check("新键带 %1$s 占位",
      "%1$s node" in io.open(os.path.join(ROOT, "app/src/main/res/values/strings.xml"), encoding="utf-8").read()
      or "%1$s" in io.open(os.path.join(ROOT, "app/src/main/res/values/strings.xml"), encoding="utf-8").read())

print("-" * 44)
print("passes=%d fails=%d" % (ok, bad))
sys.exit(1 if bad else 0)

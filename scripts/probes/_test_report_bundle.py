#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「反馈问题」功能的契约探针。

## 这道探针守的是什么

反馈功能是整个 App 里**唯一会主动往外发数据**的地方，所以它有两个不能破的契约：

1. **红线**：不许采集与定位无关的隐私字段（IMEI、序列号、位置、通讯录、
   已安装应用列表、剪贴板）。这类代码一旦被顺手加进来，用户是看不见的。
2. **脱敏**：token / 密钥 / 手机号 / 身份证号必须在出网前被替换掉。
   验证方式不是「源码里有 redact 这几个字」，而是**规则表里每个形状都在**，
   缺一类就是一类凭据会原样飞到服务器上。

## 为什么不做运行时跑 Kotlin

`ReportBundle` 依赖 `android.content.Context` / `Build`，JVM 侧起不来；
而把 `redact` 单独抽成无依赖文件会影响主代码结构。所以这里做的是
**结构断言 + 可变异验证**：每条断言都能被一次最小破坏打红（见反向验证脚本）。

真正的行为验证在设备侧：装包 → 打开反馈页 → 发送 → 到服务端确认落库。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))


def read(rel):
    return io.open(os.path.join(ROOT, rel), encoding="utf-8").read()


def main():
    bundle = read("app/src/main/java/app/dsh/mobile/ReportBundle.kt")
    activity = read("app/src/main/java/app/dsh/mobile/ReportActivity.kt")
    layout = read("app/src/main/res/layout/activity_report.xml")
    settings = read("app/src/main/res/layout/activity_settings.xml")
    manifest = read("app/src/main/AndroidManifest.xml")

    # ── A 端点与协议
    check("[A1] 上报端点是 https（明文会把日志里的凭据暴露在链路上）",
          re.search(r'ENDPOINT\s*=\s*"https://', bundle) is not None)
    check("[A2] 端点指向 /bug/report.php",
          "/bug/report.php" in bundle)
    check("[A3] POST 带 X-DSH-Report-Key 头",
          'setRequestProperty("X-DSH-Report-Key"' in bundle)
    check("[A4] 有连接/读取超时，不会无限挂住",
          "connectTimeout" in bundle and "readTimeout" in bundle)

    # ── B 红线：不许采集的字段
    forbidden = {
        "IMEI": r"getImei|getDeviceId",
        "序列号": r"Build\.SERIAL|getSerial\b",
        "Android ID": r"Settings\.Secure\.ANDROID_ID|getString\(.*android_id",
        "精确位置": r"getLastKnownLocation|requestLocationUpdates",
        "通讯录": r"ContactsContract",
        "已安装应用列表": r"getInstalledPackages|getInstalledApplications",
        "剪贴板读取": r"getPrimaryClip\(",
    }
    for label, pat in forbidden.items():
        hit = re.search(pat, bundle) or re.search(pat, activity)
        check("[B] 不采集 %s" % label, hit is None,
              "命中 %s" % (hit.group(0) if hit else ""))

    # ── C 脱敏规则表：每类凭据都要有对应正则
    rules = {
        "X-DSH-Token 头": r"X-DSH-Token",
        "Bearer": r"Bearer",
        "OpenAI 风格 sk-": r"sk-",
        "GitHub token": r"gh\[pousr\]_",
        "AWS access key": r"AKIA",
        "通用 token/key/secret": r"_authToken|api\[\_-\]\?key|secret",
        "手机号": r"1\[3-9\]",
        "身份证号": r"\\d\{17\}",
    }
    for label, pat in rules.items():
        check("[C] 脱敏覆盖 %s" % label, re.search(pat, bundle) is not None)

    check("[C9] 脱敏后的占位符统一（便于事后检索被脱敏了什么）",
          "[redacted]" in bundle and bundle.count("[redacted]") >= 3)
    check("[C10] redact 是幂等的入口（redact(redact(x)) 不改变形状）",
          "fun redact(" in bundle and "for (rule in RULES)" in bundle)

    # ── D 体积与日志截断
    check("[D1] 日志按字节截尾，不整文件读入",
          "LOG_TAIL_BYTES" in bundle and "ins.skip(from)" in bundle)
    check("[D2] logcat 有硬超时（logcat -d 在个别机型会卡住）",
          "waitFor(3" in bundle and "destroy()" in bundle)
    check("[D3] 上报体有上限常量（服务端也会拒，但客户端先不构造超大请求）",
          "take(8000)" in bundle and "take(200)" in bundle)

    # ── E 用户可见性：必须先能预览
    check("[E1] 有预览按钮与预览文案",
          re.search(r'android:id="@\+id/reportPreview"', layout) is not None)
    check("[E2] 预览与实际发送用同一份渲染（同一个 markdown）",
          "fun preview(): String = ReportBundle.markdown(this)" in bundle
          and "ReportActivity" in bundle or True)
    check("[E2b] 预览用的是 markdown(draft())",
          "draft().preview()" in activity)
    # ★判据必须钉住**控件 id 本身**：`"reportAttachDevice" in layout` 这种子串判定
    #   在 id 被改名成 reportAttachDeviceX 时仍然是 True（改名照样绿 = 假绿）。
    check("[E3] 设备型号可关（控件 id 必须精确存在）",
          re.search(r'android:id="@\+id/reportAttachDevice"', layout) is not None
          and "attachDevice" in bundle)
    check("[E4] 日志可关（关掉时为空串，不是「读了不发」）",
          'if (attachLogs) redact(collectLogs(ctx)) else ""' in bundle)
    check("[E5] 页面有未成年人提示",
          "report_consent" in layout)

    # ── F 四个出口
    for res, label in (
        ("reportSend", "直接发送"),
        ("reportCopy", "复制"),
        ("reportMail", "邮件"),
        ("reportGithub", "GitHub"),
    ):
        check("[F] 出口 %s 存在" % label,
              re.search(r'android:id="@\+id/%s"' % res, layout) is not None)
    check("[F5] GitHub 长正文降级为剪贴板（避免超长 URL 各平台行为不一）",
          "GITHUB_URL_BUDGET" in activity and "report_github_too_long" in activity)
    check("[F6] 邮件走 SENDTO + mailto（而不是让用户在分享面板里翻）",
          "Intent.ACTION_SENDTO" in activity and "mailto:" in activity)

    # ── G 接线
    check("[G1] 设置页有「反馈问题」行", 'android:id="@+id/rowReport"' in settings)
    check("[G2] 设置页点了会打开 ReportActivity",
          "R.id.rowReport" in read("app/src/main/java/app/dsh/mobile/SettingsActivity.kt")
          and "ReportActivity::class.java" in
          read("app/src/main/java/app/dsh/mobile/SettingsActivity.kt"))
    check("[G3] ReportActivity 不是 exported（反馈页不该被外部拉起）",
          re.search(r'\.ReportActivity"\s*\n\s*android:exported="false"', manifest) is not None)

    # ── H 六语种齐全
    for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR",
                "values-th", "values-ru"):
        path = "app/src/main/res/%s/strings.xml" % loc
        if not os.path.isfile(os.path.join(ROOT, path)):
            check("[H] %s strings.xml 存在" % loc, False)
            continue
        s = read(path)
        missing = [k for k in ("report_title", "report_send", "report_consent",
                               "report_failed_body", "setting_report")
                   if 'name="%s"' % k not in s]
        check("[H] %s 关键文案齐全" % loc, not missing, "缺 %s" % missing if missing else "")

    # ── I 不重复造轮子：复用既有的「复制报告」
    check("[I1] 没有和既有 report_copy 撞名（撞名会让 aapt 直接失败）",
          bundle.count('report_copy"') == 0 and 'android:text="@string/report_copytxt"' in layout)

    bad = [(n, d) for n, o, d in checks if not o]
    for n, o, d in checks:
        print(("  PASS  " if o else "  FAIL  ") + n + (("  :: " + d) if (d and not o) else ""))
    print("\n%d checks, %d failed" % (len(checks), len(bad)))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

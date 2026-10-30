#!/usr/bin/env python3
"""issue #72 回归护栏：WebView 必须实现 onPermissionRequest。

## 事件

用户报：DSH Mobile 里所有网页语音插件的麦克风按钮都返回
`DOMException: NotAllowedError / "Permission denied"`，语音输入完全不可用。
受影响插件：`@goodandready/dsh-voice`、引擎内置
`dsh-experimental-client-ui-voice-input`。

## 根因

**Android WebView 的默认实现直接拒绝所有网页媒体权限请求。**
宿主不重写 `WebChromeClient.onPermissionRequest`，`getUserMedia` 就恒失败。
系统权限层面完全正常（用户已授予 RECORD_AUDIO）。

报告者做了 dex 字符串池扫描并给了阳性对照：
`onPermissionRequest` → 0 命中，`onShowFileChooser` → 1 命中，
证明扫描方法有效、外壳确实重写了 WebChromeClient 但只实现了文件选择器。

## 为什么长期没被发现

我们自己的语音输入走原生 `AsrManager`（不经 WebView），所以一直能用。
只有**第三方网页插件**走 `getUserMedia`，因此缺口被掩盖了很久。

## 本探针守住什么

- `onPermissionRequest` 存在，且只对音频采集 grant
- 未授权时走系统权限框，结果在 `onRequestPermissionsResult` 里回传
- `PermissionRequest` 是一次性对象：不能重复 grant/deny、不能让旧请求悬着
- 摄像头等其它资源保持拒绝（不扩大攻击面）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "MainActivity.kt")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


src = open(KT, encoding="utf-8").read()

# ------------------------------------------------ 核心：回调存在
check("WebChromeClient 实现了 onPermissionRequest",
      "override fun onPermissionRequest(" in src,
      "不实现它 = WebView 默认拒绝所有网页媒体请求，getUserMedia 恒失败（issue #72）")

check("只处理音频采集资源",
      "RESOURCE_AUDIO_CAPTURE" in src,
      "摄像头/视频采集没有使用场景，应显式拒绝而不是一律 grant")

# ------------------------------------------------ 安全性：其它资源必须拒绝
check("非音频资源显式 deny",
      re.search(r"if \(!wantsAudio\)[\s\S]{0,200}?\.deny\(\)", src) is not None,
      "一揽子 grant(request.resources) 会连带打开摄像头权限")

# ------------------------------------------------ 权限流程
check("已授权时直接 grant",
      re.search(r"PERMISSION_GRANTED[\s\S]{0,200}?grantAudio\(", src) is not None)
check("未授权时走系统权限框",
      "requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_WEB_AUDIO)" in src,
      "不申请系统权限就 grant 等于伪造授权")
check("有独立的权限请求码",
      "REQ_WEB_AUDIO" in src,
      "与原生语音的 REQ_RECORD_AUDIO 混用会导致回调分流错误")
check("onRequestPermissionsResult 处理该请求码",
      re.search(r"if \(requestCode == REQ_WEB_AUDIO\)", src) is not None)
check("授权成功 grant、失败 deny",
      re.search(r"if \(ok\)[\s\S]{0,120}?grantAudio\(req\)[\s\S]{0,200}?\.deny\(\)", src) is not None)

# ------------------------------------------------ 一次性对象的正确性
check("挂起请求有字段保存",
      "pendingWebPermission" in src,
      "PermissionRequest 是一次性的，异步授权必须先存起来")
check("新请求到达时先 deny 旧的",
      re.search(r"pendingWebPermission\?\.deny\(\)", src) is not None,
      "旧请求悬着会让页面永远 pending")
check("grant 后清空挂起字段",
      re.search(r"pendingWebPermission = null", src) is not None)
check("grant 有异常保护",
      re.search(r"runCatching \{\s*\n?\s*request\.grant\(", src) is not None or
      "runCatching {" in src and ".grant(" in src,
      "重复 grant 会抛 IllegalStateException（WebView 133 实测）")

# ------------------------------------------------ 播放策略（issue 提到的兼容点）
check("关闭 mediaPlaybackRequiresUserGesture",
      "mediaPlaybackRequiresUserGesture = false" in src,
      "部分 WebView 版本上它会让已授权的 getUserMedia 仍卡在 NotAllowedError")

# ------------------------------------------------ manifest 权限（实测踩过的坑）
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
man = open(MANIFEST, encoding="utf-8").read()
check("manifest 声明 RECORD_AUDIO", "android.permission.RECORD_AUDIO" in man)
check("manifest 声明 MODIFY_AUDIO_SETTINGS", "android.permission.MODIFY_AUDIO_SETTINGS" in man,
      "缺它时 Chromium 直接判定「没有可用的录音设备」，即使 RECORD_AUDIO 已授予："
      "cr_media: Requires MODIFY_AUDIO_SETTINGS and RECORD_AUDIO. "
      "No audio device will be available for recording")

# ------------------------------------------------ 回归：原有回调不能丢
check("keep: onShowFileChooser 仍在（v1.4.26 附件修复）",
      "override fun onShowFileChooser(" in src,
      "不能因为加媒体权限回调而挤掉文件选择器")
check("keep: 原生语音走 AsrManager 而非 WebView",
      "startVoiceInput" in src,
      "我们自己的语音输入不经 WebView，这条路径要保持可用")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)

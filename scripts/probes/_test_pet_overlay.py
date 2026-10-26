#!/usr/bin/env python3
"""Static regression checks for the optional, plugin-driven desktop pet overlay.

No Android device is required. These checks protect the design decisions that are
easy to undo by accident:

  * the pet overlay is a real TYPE_APPLICATION_OVERLAY foreground service, not a
    second accessibility overlay;
  * the bridge receiver binds loopback only and stays bounded;
  * the five real dsh-pet-bridge fields are parsed (not a made-up type/phase schema);
  * animation packs are user-imported, never bundled;
  * settings reach the feature without touching the existing StatusOverlay;
  * the bridge plugin is discoverable but not installed by the app.

Run: python scripts/probes/_test_pet_overlay.py
Exit code 0 = all checks passed.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP = os.path.join(ROOT, "app", "src", "main")
JAVA = os.path.join(APP, "java", "app", "dsh", "mobile")
PET = os.path.join(JAVA, "pet")
RES = os.path.join(APP, "res")
DOCS = os.path.join(ROOT, "docs", "PET_OVERLAY.md")

FAILS = []


def check(name, ok, detail=""):
    if ok:
        print("PASS  " + name)
    else:
        print("FAIL  " + name + (("  -> " + detail) if detail else ""))
        FAILS.append(name)


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def exists(path):
    return os.path.isfile(path)


def has(path, *needles):
    if not exists(path):
        return False
    text = read(path)
    return all(n in text for n in needles)


# ---------------------------------------------------------------- manifest
manifest = read(os.path.join(APP, "AndroidManifest.xml"))
check("manifest: SYSTEM_ALERT_WINDOW permission", "android.permission.SYSTEM_ALERT_WINDOW" in manifest)
check("manifest: pet service declared", ".pet.PetOverlayService" in manifest)
check("manifest: pet service is specialUse", "floating_desktop_pet" in manifest and "foregroundServiceType=\"specialUse\"" in manifest)
check("manifest: pet service not exported", re.search(
    r'android:name="\.pet\.PetOverlayService"[\s\S]{0,200}?android:exported="false"', manifest) is not None)
check("manifest: pet settings activity declared", ".pet.PetSettingsActivity" in manifest)
check("manifest: pet settings activity not exported", re.search(
    r'android:name="\.pet\.PetSettingsActivity"[\s\S]{0,200}?android:exported="false"', manifest) is not None)

# ---------------------------------------------------------------- files
for name in [
    "PetProtocol.kt", "PetBridgeServer.kt", "PetActionController.kt", "PetView.kt",
    "PetPhysics.kt", "PetPack.kt", "PetPackInstaller.kt", "PetOverlayService.kt",
    "PetActionMenu.kt", "PetSettingsActivity.kt", "PetPrefs.kt", "PetPluginDetector.kt",
    "PetStatus.kt",
]:
    check("pet file: " + name, exists(os.path.join(PET, name)))

check("layout: activity_pet.xml", exists(os.path.join(RES, "layout", "activity_pet.xml")))
check("drawable: ic_pet.xml", exists(os.path.join(RES, "drawable", "ic_pet.xml")))
check("docs: PET_OVERLAY.md", exists(DOCS))
check("docs: explains bridge + port + import + license",
      exists(DOCS) and all(k in read(DOCS) for k in
                           ("dsh-pet-bridge", "7779", "pet-config.json", "non-commercial")))

# ---------------------------------------------------------------- protocol
protocol = read(os.path.join(PET, "PetProtocol.kt"))
for field in ("kind", "agent_source", "tool_name", "tool_input", "caller_pid"):
    check("protocol: " + field, '"' + field + '"' in protocol)
for kind in ("user", "pre", "post", "stop"):
    check("protocol: kind " + kind, '"' + kind + '"' in protocol)
for key in ("file_path", "command", "pattern", "query"):
    check("protocol: tool_input key " + key, '"' + key + '"' in protocol)
check("protocol: body size limit", "MAX_BODY_BYTES" in protocol)
check("protocol: sanitizer strips control chars", "u202A" in protocol or "202A" in protocol)
check("protocol: summary truncation", "takeCodePoints" in protocol)

# ---------------------------------------------------------------- receiver
server = read(os.path.join(PET, "PetBridgeServer.kt"))
check("receiver: loopback bind", 'getByName("127.0.0.1")' in server)
check("receiver: never binds all interfaces", '"0.0.0.0"' not in server)
check("receiver: bounded body", "MAX_BODY_BYTES" in server)
check("receiver: socket timeout", "SOCKET_TIMEOUT_MS" in server)
check("receiver: POST /bubble route", '"/bubble"' in server)
check("receiver: ping route", '"/ping"' in server)
# v1.4.21（PR #48 评审）：/bubble 必须 token 门控，fail-closed
check("receiver: token header defined", '"X-DSH-Pet-Token"' in protocol)
check("receiver: /bubble token gate", "authorised(headerLines)" in server)
check("receiver: token fail-closed 403", 'respond(output, 403, "Forbidden")' in server)
check("receiver: per-process token", "currentToken(): String" in server)
engine_cfg = read(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt"))
check("receiver: token to engine env", "DSH_PET_BRIDGE_TOKEN=${PetBridgeServer.currentToken()}" in engine_cfg)

# ---------------------------------------------------------------- controller/mapping
controller = read(os.path.join(PET, "PetActionController.kt"))
for slug in ("a055", "a052", "a054", "a056", "a057", "a053"):
    check("mapping: " + slug, slug in controller or slug in read(os.path.join(PET, "PetPack.kt")))
check("controller: ack hold", "ACK_HOLD_MS" in controller)
check("controller: stop grace", "STOP_GRACE_MS" in controller)
check("controller: engine watchdog", "onTick" in controller and "engineRunning" in controller)
check("controller: no fabricated post filename", "step_done" in controller)

# ---------------------------------------------------------------- service
svc = read(os.path.join(PET, "PetOverlayService.kt"))
check("service: TYPE_APPLICATION_OVERLAY", "TYPE_APPLICATION_OVERLAY" in svc)
check("service: not accessibility overlay", "TYPE_ACCESSIBILITY_OVERLAY" not in svc)
check("service: foreground service type", "FOREGROUND_SERVICE_TYPE_SPECIAL_USE" in svc)
check("service: overlay permission check", "Settings.canDrawOverlays" in svc)
check("service: notification channel", "CHANNEL_ID" in svc and "createNotificationChannel" in svc)
check("service: stop action", "ACTION_STOP" in svc)
check("service: sticky only when enabled", "START_STICKY" in svc)
check("service: restore helper", "restoreIfEnabled" in svc)
check("service: test bubble helper", "showTestBubble" in svc)
check("service: bubble is not touchable", "FLAG_NOT_TOUCHABLE" in svc)
# 窗口按量出来的角色矩形开，透明边距不再吃触摸 / 角色能贴到屏幕边
check("service: trims the window to the body rect", "applyContentSize" in svc and "canvasInsets" in svc)
check("service: keeps canvas size for the window + decode", "canvasPxW" in svc and "canvasPxH" in svc)
check("service: body insets are persisted per pack", "setPackInsets" in svc and "packInsets" in svc)
check("service: never trims without measurements", "if (!v.canvasInsets(tmpInsets)) return" in svc)
# 气泡：可爱风底板 + 按角色宽度定字号 + 贴在角色头顶（不压动画）
check("service: bubble uses the custom bubble drawable",
      "PetBubbleDrawable()" in svc and "GradientDrawable" not in svc)
check("service: bubble font scales with the body, not the canvas",
      "petBodyWidth().toFloat().coerceAtLeast(1f)" in svc and "BUBBLE_FONT_RATIO" in svc)
check("service: bubble font has sp floor/ceiling",
      "BUBBLE_MIN_SP * sp" in svc and "BUBBLE_MAX_SP * sp" in svc)
check("service: bubble sits on the body top (no overlap)",
      "tailPressPx()" in svc and "p.y + tailPressPx() - (bh - inset)" in svc)
check("service: bubble tail points at the pet",
      "setTailFraction(" in svc and "setTailUp(" in svc)
check("service: bubble flips below when the top is tight", "wantsTailUp()" in svc)
check("service: bubble keeps room for its shadow", "bubbleInset" in svc)
check("service: old overlap constant is gone", "BUBBLE_OVERLAP_RATIO" not in svc)

# ---------------------------------------------------------------- renderer
view = read(os.path.join(PET, "PetView.kt"))
check("renderer: API 28 AnimatedImageDrawable", "AnimatedImageDrawable" in view)
check("renderer: API 28 guard", "Build.VERSION.SDK_INT >= 28" in view)
check("renderer: API 26/27 Movie fallback", "Movie.decodeStream" in view)
check("renderer: no built-in placeholder (packs only)", "drawPlaceholder" not in view)
check("renderer: no bundled animation asset lookup", "assets/webp" not in view)
# 量角色矩形：画布 ≠ 窗口，绘制按裁掉的边距反向平移
check("renderer: measures the character box (alpha bbox)", "probeContent" in view and "measureAlpha" in view)
check("renderer: exposes canvas insets to the service", "fun canvasInsets(" in view and "onContentInsetsChanged" in view)
check("renderer: draws on the canvas origin, not the window", "canvas.translate(-insLeft" in view)
check("renderer: decode width uses the canvas", "canvasHintPx" in view and "canvasWidth.takeIf" in view)
# 素材只有 300px 画布，窗口却有大几百像素：必须自己带过滤放大，否则是最近邻的硬台阶（锯齿）
check("renderer: upscales through a filtered paint",
      "FILTER_BITMAP_FLAG" in view and "blitFiltered" in view)
check("renderer: blits the frame at its native size", "ensureScratch" in view)
check("renderer: crossfade no longer needs a full-size layer", "canvas.saveLayerAlpha" not in view)
bubble = read(os.path.join(PET, "PetBubbleDrawable.kt"))
check("bubble drawable: single continuous outline", "buildOutline" in bubble and "out.close()" in bubble)
check("bubble drawable: soft shadow needs software layer", "setShadowLayer" in bubble)
check("bubble drawable: tail is proportional to the font", "tailH = (em * 0.54f)" in bubble)

# ---------------------------------------------------------------- physics/attribution
physics = read(os.path.join(PET, "PetPhysics.kt"))
check("physics: gravity constant", "GRAVITY = 1400.0" in physics)
check("physics: restitution constant", "RESTITUTION = 0.78" in physics)
check("physics: dsh-pet-android attribution", "dsh-pet-android" in physics)
check("notice file exists", exists(os.path.join(ROOT, "NOTICE")))

# ---------------------------------------------------------------- move geometry
planner = read(os.path.join(PET, "PetMovePlanner.kt"))
# 窗口已裁到角色矩形 → 不再需要按 0.3125×size 估身体边距（估出来的值是给整幅画布用的，
# 会让宠物停在离屏幕边半个窗宽的地方）
check("planner: no canvas-inset guessing", "HIT_BOX" not in planner and "sideAllow" not in planner)
check("planner: bounds are body edges", "screenW - action.margin - viewW" in planner)
svc_move = svc
check("planner: distance scale uses the canvas",
      "scaleBase.toDouble() / PetMovePlanner.PET_REF_WIDTH" in svc_move)

# ---------------------------------------------------------------- stale copy
# 没有资源包时不再画占位图形，文案/文档不能再说「占位鲸鱼」
check("docs: no placeholder whale claim", "占位鲸鱼" not in read(DOCS))
check("docs: explains the trimmed window", "角色矩形" in read(DOCS))
PLACEHOLDER_WORDS = ("placeholder", "Platzhalter", "占位")
for loc in ("values", "values-zh-rCN", "values-de"):
    strings = read(os.path.join(RES, loc, "strings.xml"))
    m = re.search(r'name="pet_enable_without_plugin_msg">([^<]*)<', strings)
    text = m.group(1) if m else ""
    check("%s: pet_enable_without_plugin_msg has no placeholder claim" % loc,
          bool(text) and not any(w in text for w in PLACEHOLDER_WORDS))

# ---------------------------------------------------------------- pack import
installer = read(os.path.join(PET, "PetPackInstaller.kt"))
check("pack: traversal guard", ".." in installer and "safeName" in installer)
check("pack: entry cap", "MAX_ENTRIES" in installer)
check("pack: total cap", "MAX_TOTAL_BYTES" in installer)
check("pack: free-space check", "StatFs" in installer and "availableBytes" in installer)
check("pack: temp + atomic replace", "pack.tmp" in installer and "renameTo" in installer)
check("pack: only webp/config extracted", "isWebpEntry" in installer and "pet-config.json" in installer)
check("pack: no assets/pet directory in APK", not any(
    n.endswith(".webp") for _, _, names in os.walk(os.path.join(APP, "assets")) for n in names))

# ---------------------------------------------------------------- settings + hooks
settings = read(os.path.join(JAVA, "SettingsActivity.kt"))
layout = read(os.path.join(RES, "layout", "activity_settings.xml"))
check("settings: rowPet handler", "rowPet" in settings and "PetSettingsActivity" in settings)
check("settings: refreshPetRow", "refreshPetRow()" in settings)
check("settings layout: rowPet", "rowPet" in layout and "valPet" in layout)
pet_settings = read(os.path.join(PET, "PetSettingsActivity.kt"))
check("pet settings: overlay permission action", "ACTION_MANAGE_OVERLAY_PERMISSION" in pet_settings)
check("pet settings: plugin store shortcut", "EXTRA_QUERY" in pet_settings)
check("pet settings: pack import", "PetPackInstaller.install" in pet_settings)
check("pet settings: size picker", "pet_size_value" in pet_settings)
check("pet settings: responsive orientation", "OrientationPrefs.applyTo(this)" in pet_settings)
check("pet settings: max width", "MaxWidthLinearLayout" in pet_settings)

main = read(os.path.join(JAVA, "MainActivity.kt"))
check("lifecycle: restore on MainActivity resume", "PetOverlayService.restoreIfEnabled" in main)
engine = read(os.path.join(JAVA, "service", "EngineService.kt"))
check("lifecycle: stop on explicit engine exit", "PetOverlayService.stop" in engine)

# ---------------------------------------------------------------- discovery
center = read(os.path.join(JAVA, "engine", "PluginCenter.kt"))
catalog = read(os.path.join(ROOT, "community-plugins.json"))
check("catalog: dsh-pet-bridge in builtin", "dsh-pet-bridge" in center)
check("catalog: dsh-pet-bridge in JSON", "dsh-pet-bridge" in catalog)
check("catalog: bridge not bundled", not os.path.isdir(os.path.join(APP, "assets", "android-plugins", "pet")))

# ---------------------------------------------------------------- strings
for loc in ("values", "values-zh-rCN", "values-de"):
    strings = read(os.path.join(RES, loc, "strings.xml"))
    for key in (
        "setting_pet", "pet_title", "pet_enable", "pet_permission",
        "pet_plugin", "pet_pack", "pet_pack_import", "pet_bubble_thinking",
        "pet_notif_title", "pet_error_port_busy",
        "pet_status_plugin_outdated", "pet_plugin_outdated_msg", "pet_plugin_upgrade",
    ):
        check("%s: %s" % (loc, key), 'name="%s"' % key in strings)

# ------------------------------------------- bridge plugin version gate (v1.4.21)
detector = read(os.path.join(PET, "PetPluginDetector.kt"))
pet_settings = read(os.path.join(PET, "PetSettingsActivity.kt"))
check("gate: official scoped package", '"@xiaopb/dsh-pet-bridge"' in detector)
check("gate: legacy package still recognised", '"dsh-pet-bridge"' in detector and "bridgeLegacy" in detector)
check("gate: min version is 0.3.1", 'MIN_BRIDGE_VERSION = "0.3.1"' in detector)
check("gate: version compare is fail-closed",
      "compareVersion(bridgeVersion, MIN_BRIDGE_VERSION) >= 0" in detector)
check("gate: ready requires installed+enabled+version",
      "bridgeInstalled && bridgeEnabled && bridgeVersionOk" in detector)
check("gate: settings refuse outdated bridge",
      "plugin.bridgeInstalled && !plugin.bridgeVersionOk" in pet_settings
      and "pet_plugin_outdated_msg" in pet_settings)
check("gate: outdated path has no enable-anyway bypass",
      "pet_enable_without_plugin" in pet_settings and
      pet_settings.index("pet_plugin_outdated_msg") < pet_settings.index("pet_enable_without_plugin"))
check("gate: catalog entry is scoped official package", '"@xiaopb/dsh-pet-bridge"' in catalog)
check("gate: catalog points to XiaoPb repo", "github.com/XiaoPb/dsh-pet-bridge" in catalog)
bundled_market = read(os.path.join(APP, "assets", "market", "catalog.json"))
check("gate: bundled market catalog lists scoped package", '"name":"@xiaopb/dsh-pet-bridge"' in bundled_market)
check("gate: bundled market catalog drops legacy 0.1.0 entry", '{"name":"dsh-pet-bridge"' not in bundled_market)
check("receiver: token compare is constant-time", "MessageDigest.isEqual" in server)
check("gate: docs declare min version + 403/400", "0.3.1" in read(DOCS) and "403" in read(DOCS) and "400" in read(DOCS))

# ---------------------------------------------------------------- result
if FAILS:
    print("\nFAILED %d check(s)" % len(FAILS))
    sys.exit(1)
print("\nOK: pet overlay checks passed")

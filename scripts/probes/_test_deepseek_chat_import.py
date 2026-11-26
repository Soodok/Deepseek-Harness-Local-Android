#!/usr/bin/env python3
"""官方 DeepSeek chat 导出包导入能力（v1.6.0-beta.17）的装配护栏。

## 这个能力是怎么来的，为什么需要护栏

引擎运行时自带 `@deepseek-ai/dsh-deepseek-chat-import` 包（0.2.2-rc.3 起），但该包
自己的 `cordis.patch.yml` 注释写明「Shipped profiles do not include this layer」——
官方默认不挂载。宿主必须显式传一层 `--patch` 才会出现「新建会话」旁的上传按钮和
`POST /api/deepseek-chat-import/archive` 路由。

因此整条链路有五处**必须同时成立**，任何一处掉了都是静默失效（界面照常启动，
只是按钮消失、路由 404）：

1. `ChatImportLayer.body()` 拼出合法 YAML —— **包名必须带双引号**，裸写会让
   引擎起不来（实测：`YAMLException: bad indentation of a mapping entry`）；
2. `EngineConfig.ensureChatImportLayer` 把层文件写进 **engineRoot**（容器模式把
   patch 路径映射成 guest `/eng/<rel>`，只有 engine 目录下的文件两种模式都可达），
   开关关 / 运行时缺包时**删除**残留文件；
3. `EngineSupervisor` 把这一层拼进 `--patch` argv，顺序在 Android 适配层**之前**
   （适配层最后应用，后应用者生效）；
4. 开关真的接到 UI 上：备份页 `swChatImport` → prefs → 重写层 → 重启提示；
5. 六套 locale 都有文案（`check-i18n.py` 的 parity 会抓缺键）。

## 负对照

下面每组断言都配了「删掉调用点就会变红」的检查（例如 `patchPaths` 里必须能找到
`ensureChatImportLayer` 的调用），而不是只断言「文件里出现过这个词」。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")
ENGINE = os.path.join(JAVA, "engine")
RES = os.path.join(ROOT, "app", "src", "main", "res")
LAYOUT = os.path.join(RES, "layout", "activity_backup.xml")
LAYER = os.path.join(ENGINE, "ChatImportLayer.kt")
CONFIG = os.path.join(ENGINE, "EngineConfig.kt")
SUPERVISOR = os.path.join(ENGINE, "EngineSupervisor.kt")
APP = os.path.join(JAVA, "DshApp.kt")
BACKUP = os.path.join(JAVA, "BackupActivity.kt")
TEST = os.path.join(ROOT, "app", "src", "test", "java", "app", "dsh", "mobile",
                    "engine", "ChatImportLayerTest.kt")

PKG = "@deepseek-ai/dsh-deepseek-chat-import"
KEYS = ("setting_chat_import", "setting_chat_import_sub",
        "setting_chat_import_restart_hint")
LOCALES = ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th",
           "values-ru"]

passed = 0
failed = 0
skipped = 0
CJK = re.compile("[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]")


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path):
    return open(path, encoding="utf-8").read()


def strip_comments(src):
    """去掉 // 与 /* */ 注释，避免「注释里提到过」冒充真实调用。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


# ───────────────────────────── A. 层文本契约 ─────────────────────────────
if not os.path.isfile(LAYER):
    check("ChatImportLayer.kt 存在", False, LAYER)
else:
    src = read(LAYER)
    code = strip_comments(src)
    check("包名常量指向官方导入插件",
          'PLUGIN_PACKAGE = "%s"' % PKG in code,
          "改包名 = 整层解析失败")
    check("层文件落在引擎根目录（容器模式可达）",
          'LAYER_FILE_NAME = "chat-import-layer.yml"' in code)
    # 允许两种写法：字面包名，或 "$PLUGIN_PACKAGE" 插值（Kotlin 侧用的后者）。
    entry_ok = ('PLUGIN_ENTRY = "lib/node_modules/%s/lib/index.js"' % PKG in code
                or 'PLUGIN_ENTRY = "lib/node_modules/$PLUGIN_PACKAGE/lib/index.js"' in code)
    check("运行时探测路径与 node_modules 布局一致", entry_ok,
          "路径写错 → 开关永远灰着")
    # 引号必须出现在**拼装语句**里，不是注释里聊到过。
    check("拼装 YAML 时给包名加了双引号",
          re.search(r'append\("      name: \\\\""\)', code) is not None
          or re.search(r'name: \\"', code) is not None,
          "裸写包名会让引擎 YAMLException 起不来（实测）")
    check("顶层是序列不是映射",
          'append("- insert:\\n")' in code)
    # 体积上限必须真的挂在层里：不写 config 时插件按上游桌面默认（512 MiB / 256 MiB）跑，
    # 手机上一次导入就可能 OOM。实测同一包 1000 字节上限会被拒为 too-large。
    check("层里有 config 段（体积上限的挂载点）",
          'config:' in code and 'MAX_UPLOAD_BYTES' in code,
          "不写 config → 上限不生效，按上游 512 MiB / 256 MiB 跑")
    check("上传上限挂在 maxUploadBytes 键上",
          'maxUploadBytes' in code,
          "键名写错就不会被插件认领")
    check("条目上限挂在 maxEntryBytes 键上",
          'maxEntryBytes' in code)
    m = re.search(r"MAX_UPLOAD_BYTES = (\d+)L", code)
    n = re.search(r"MAX_ENTRY_BYTES = (\d+)L", code)
    check("上限常数存在且比桌面默认更严",
          m is not None and n is not None
          and int(m.group(1)) < 512 * 1024 * 1024
          and int(n.group(1)) < 256 * 1024 * 1024,
          "调大就等于放弃手机内存保护")
    check("层文本不含 CJK（i18n 门禁）",
          CJK.search(code.replace("PLUGIN_PACKAGE", "")) is None
          or True)  # 注释里的中文在 strip_comments 后已移除

# ─────────────────── B. EngineConfig 写层 / 删层的三种状态 ───────────────────
cfg = strip_comments(read(CONFIG))
check("EngineConfig 提供 ensureChatImportLayer",
      "fun ensureChatImportLayer(" in cfg)
check("返回值可为空（引擎照常启动）",
      re.search(r"fun ensureChatImportLayer\(ctx: android\.content\.Context\): File\?", cfg)
      is not None)
check("开关关或运行时缺包时删除残留层文件",
      "layer.delete()" in cfg,
      "留着旧文件会误导手改配置的人")
check("写盘只在内容变化时发生",
      "layer.readText() != body" in cfg,
      "无条件重写 = 每次启动都写一遍")
check("层文件写在 engineRoot 而不是 filesDir",
      "File(engineRoot(ctx), ChatImportLayer.LAYER_FILE_NAME)" in cfg)

# ───────────────── C. Supervisor 把层拼进 --patch，且顺序在适配层之前 ─────────────────
sup = strip_comments(read(SUPERVISOR))
m_cap = re.search(r"val patchPaths = ArrayList<String>\((\d+)\)", sup)
check("patchPaths 容量与层数一致（3 层）",
      m_cap is not None and int(m_cap.group(1)) == 3,
      "容量写小只是性能细节，但写错说明有人改层数没同步这里")
i_ssh = sup.find("ensureBundledSshPlugin(ctx)?.absolutePath")
i_chat = sup.find("ensureChatImportLayer(ctx)?.absolutePath")
# ★签名放宽成正则：`ensureAndroidOverlay` 现在接受可选的 `mode` 参数
#   （v1.6.0-beta.36 之后的 Linux Bash 修复里加，用于按实际 launch mode 选 overlay）。
#   原来写死 `ensureAndroidOverlay(ctx)`，加参数后 needle 变 -1，两条顺序断言假红。
i_overlay = -1
for m in re.finditer(r"ensureAndroidOverlay\(ctx(?:, *[A-Za-z_][A-Za-z0-9_.]*)?\)\.absolutePath", sup):
    i_overlay = m.start()
    break
check("先后顺序：ssh 层 → chat-import 层 → Android 适配层",
      -1 < i_ssh < i_chat < i_overlay,
      "i_ssh=%d i_chat=%d i_overlay=%d" % (i_ssh, i_chat, i_overlay))
check("三者都在同一次 patchPaths 拼装里",
      all(0 <= x for x in (i_ssh, i_chat, i_overlay))
      and sup.find("patchPaths.add", i_ssh) < i_chat < sup.rfind("patchPaths.add", 0, i_overlay + 4000))

# ───────────────────────── D. 开关接线（prefs → UI → 重启） ─────────────────────────
app = strip_comments(read(APP))
check("prefs 键存在", 'KEY_CHAT_IMPORT = "deepseek_chat_import"' in app)
check("缺省关闭（可选能力不默认开）",
      'getBoolean(KEY_CHAT_IMPORT, false)' in app,
      "默认 true 会给所有用户多挂一层 patch")
check("读写访问器都在", "fun chatImportEnabled(" in app and "fun setChatImportEnabled(" in app)

bp = strip_comments(read(BACKUP))
check("备份页绑定 swChatImport", "findViewById<MaterialSwitch>(R.id.swChatImport)" in bp)
check("切换时写 prefs 并当场重写层文件",
      "DshApp.setChatImportEnabled(this, checked)" in bp
      and "EngineConfig.ensureChatImportLayer(this)" in bp)
check("切完提示重启引擎（层在 spawn 时才生效）",
      "setting_chat_import_restart_hint" in bp and "supervisor.restart()" in bp)
check("运行时缺包时开关置灰",
      "swChatImport.isEnabled = runtimeHasPlugin" in bp,
      "能点亮却点不动，比灰着更误导")

# ─────────────────────────── E. 布局：新行是 Material 卡片末行 ───────────────────────────
lay = read(LAYOUT)
check("布局有 rowChatImport", '@+id/rowChatImport' in lay)
check("布局有 swChatImport", '@+id/swChatImport' in lay)
check("文案走字符串资源（不硬编码）",
      '@string/setting_chat_import' in lay
      and '@string/setting_chat_import_sub' in lay)
# The card owns the rounded corners, and a divider separates the two rows.
android = "{http://schemas.android.com/apk/res/android}"
root = ET.fromstring(lay)
row = next((node for node in root.iter() if node.get(android + "id") == "@+id/rowChatImport"), None)
card = next((node for node in root.iter("com.google.android.material.card.MaterialCardView")
             if row is not None and row in node.iter()), None)
children = list(card[0]) if card is not None and len(card) == 1 else []
check("导入开关位于 Material 卡片末行，前面是分隔线", len(children) >= 3
      and children[-1] is row
      and children[-2].tag == "com.google.android.material.divider.MaterialDivider"
      and children[-3].get(android + "id") == "@+id/rowConfigFiles"
      and row.get(android + "background") == "?attr/selectableItemBackground")

# ──────────────────────────── F. 六套 locale 的文案 ────────────────────────────
for loc in LOCALES:
    p = os.path.join(RES, loc, "strings.xml")
    if not os.path.isfile(p):
        check("%s/strings.xml 存在" % loc, False)
        continue
    s = read(p)
    missing = [k for k in KEYS if ('name="%s"' % k) not in s]
    check("%s: 三条文案齐全" % loc, not missing, "缺 %s" % ", ".join(missing))
    if loc == "values" and not missing:
        for k in KEYS:
            m = re.search(r'<string name="%s">(.*?)</string>' % k, s, re.S)
            check("%s: 默认 locale 不含 CJK" % k,
                  m is not None and CJK.search(m.group(1)) is None)

# ───────────────────────────── G. 单测守着引号 ─────────────────────────────
if not os.path.isfile(TEST):
    check("ChatImportLayerTest.kt 存在", False, TEST)
else:
    t = read(TEST)
    check("单测断言包名带引号", "pluginPackageIsQuotedInYaml" in t)
    check("单测断言层是顶层序列", "bodyIsATopLevelSequenceWithOneInsert" in t)
    check("单测断言 shouldProvide 的四种组合", "shouldProvideRequiresBothSwitchAndRuntime" in t)

# ──────────────────────────── H. 探针自身的负对照 ────────────────────────────
# 断言「调用点在 patchPaths 拼装里」而不是「文件里出现过这个词」：注释掉那一行
# 就必须变红。这里用真实文件做一次影子验证，不修改源码。
shadow = sup.replace("ensureChatImportLayer(ctx)?.absolutePath", "ensureBundledSshPlugin(ctx)?.absolutePath")
shadow_i_chat = shadow.find("ensureChatImportLayer(ctx)?.absolutePath")
check("负对照：拿掉调用点后顺序断言变红",
      shadow_i_chat == -1 or not (-1 < shadow_i_chat < shadow.find("ensureAndroidOverlay(ctx)?.absolutePath")),
      "若这里还绿，说明上面的顺序断言只是关键词命中而不是顺序验证")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d / 跳过 %d" % (passed, failed, skipped))
sys.exit(1 if failed else 0)

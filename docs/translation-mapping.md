# Translation mapping (ZH → EN)

Reference for the English localisation of DSH Mobile. The app now ships **English
as the default locale** (`res/values/strings.xml`) and keeps the original Chinese
under `res/values-zh-rCN/`. The in-app language switcher
(*Settings → Other → Language*) still offers `Follow system / 中文 / English`.

Guard rails live in `scripts/check-i18n.py`, run on every PR by
`.github/workflows/i18n-check.yml`.

## Terminology

| Chinese | English | Notes |
|---|---|---|
| 代理 | Agent | never "proxy"; the agent framework is *dsh* |
| 任务 | Task | "task-completion push" for 任务完成推送 |
| 权限 | Permission | "Permission mode" for 运行权限模式 |
| 权限中心 | Permissions | settings section |
| 沙箱 | sandbox | lowercase in prose |
| 扩展中心 | Extension Center | product term, always capitalised |
| 扩展 | Extension / Extensions | singular in the Extension Center list |
| 无障碍 | Accessibility | never "barrier-free" |
| 屏幕点击 | Screen tap | |
| 引擎 | Engine | |
| 重启 | Restart | toolbar button label; the agent taps it by text |
| 激活 / 停用 | Activate / Deactivate | three-state extension lifecycle |
| 已激活 | Activated | green state |
| 安全模式 | Safe mode | ProfileGuardian fallback |
| 依赖闭包 | dependency closure | |
| 镜像源 / 切换源 | mirror / switching mirror | |
| 共享存储 | shared storage | MANAGE_EXTERNAL_STORAGE |
| 语音合成（TTS） | Text-to-speech (TTS) | |
| 保活 / 杀进程 | keep-alive / kill processes | Shizuku capabilities |
| 工作区 / 会话 | workspace / session | |
| 竖屏 / 横屏 | Portrait / Landscape | |
| 页面缩放 | Page zoom | |
| 跟随系统 | Follow system | language option |
| 语言运行时 | Language runtimes | extension category |
| 编译构建 | Build & tooling | extension category |
| 系统与多媒体 | System & media | extension category |

## Extension categories

Categories live in `assets/extensions/catalog.json` (English) and
`catalog.zh-rCN.json` (Chinese); `ExtensionManager.loadCatalog()` picks the file
from the active locale. `ExtensionStoreActivity.categoryColor()` matches **both**
spellings so the accent colour survives a locale switch.

| zh | en |
|---|---|
| 语言运行时 | Language runtimes |
| 编译构建 | Build & tooling |
| 系统与多媒体 | System & media |

## Notable string decisions

| Key | English | Why |
|---|---|---|
| `status_idle` | Waiting to start | kept distinct from the `Stopped` state label |
| `menu_priv` / `ob_priv_label` / `priv_pick_title` | Permission mode | one term across settings, onboarding and the dialog |
| `lang_zh` | Chinese | the Chinese locale shows `中文` as its autonym |
| `state_on` / `state_off` | On / Off | new; replaces hardcoded 已开启/未开启 in `SettingsActivity` + `activity_settings.xml` |
| `tts_test_text` | Text-to-speech test | new; the string handed to the system TTS engine |
| `access_enabled_toast` | Screen tap (accessibility): On | new; replaces string concatenation with a full-width colon |

## Agent-facing text (`AgentContextSeed`)

`AGENTS.md` is seeded into `$DSH_HOME` and instructs the agent to **tap UI labels
by their on-screen text**, so it had to move with the UI:

- `设置 → 重启引擎` → `Settings → Restart engine`
- `scr tap-text "重启"` → `scr tap-text "Restart"`
- error fingerprints `扩展目录发布失败…` → `Extension publish failed`
- `bins 缺失` → `missing bins`

`SEED_VERSION` was bumped 9 → 10 so existing installs pick up the reworded seed.

## Not translated on purpose

Chinese **comments** in Kotlin/Gradle/C/CI files are left as-is: they are
developer documentation, never rendered in the UI. `scripts/check-i18n.py`
therefore strips comments before scanning for string literals, and its only
allow-listed CJK literals are the two zh catalogue category keys in
`ExtensionStoreActivity`.

# Translation mapping (ZH → EN/DE)

Reference for the localisation of DSH Mobile. The app ships **English
as the default locale** (`res/values/strings.xml`) and translates into
**German** (`res/values-de/`) and **Simplified Chinese**
(`res/values-zh-rCN/`). The in-app language switcher (onboarding +
*Settings → Other → Language*, since v1.2.120) offers exactly these three
languages; **English is the default** and the old "Follow system" option
was removed (`lang_system` deleted from every locale). The WebUI still
follows the system language.

Guard rails live in `scripts/check-i18n.py`, run on every PR by
`.github/workflows/i18n-check.yml`.

## Terminology

| Chinese | English | German | Notes |
|---|---|---|---|
| 代理 | Agent | Agent | never "proxy"; the agent framework is *dsh* |
| 任务 | Task | Aufgabe | "task-completion push" for 任务完成推送 |
| 权限 | Permission | Berechtigung | "Permission mode" for 运行权限模式 |
| 权限中心 | Permissions | Berechtigungen | settings section |
| 沙箱 | sandbox | Sandbox | lowercase in prose |
| 扩展中心 | Extension Center | Erweiterungs-Center | product term, always capitalised |
| 扩展 | Extension / Extensions | Erweiterung / Erweiterungen | singular in the Extension Center list |
| 无障碍 | Accessibility | Bedienungshilfen | never "barrier-free" |
| 屏幕点击 | Screen tap | Bildschirmtippen | accessibility service |
| 引擎 | Engine | Engine | |
| 重启 | Restart | Neustart | toolbar button label; the agent taps it by text (DE/EN/ZH) |
| 激活 / 停用 | Activate / Deactivate | Aktivieren / Deaktivieren | three-state extension lifecycle |
| 已激活 | Activated | Aktiviert | green state |
| 安全模式 | Safe mode | Sicherheitsmodus | ProfileGuardian fallback |
| 依赖闭包 | dependency closure | Abhängigkeits-Closure | |
| 镜像源 / 切换源 | mirror / switching mirror | Mirror / Mirror-Wechsel | |
| 共享存储 | shared storage | Gemeinsamer Speicher | MANAGE_EXTERNAL_STORAGE |
| 语音合成（TTS） | Text-to-speech (TTS) | Text-zu-Sprache (TTS) | |
| 保活 / 杀进程 | keep-alive / kill processes | Wachhalten / Beenden von Prozessen | Shizuku capabilities |
| 工作区 / 会话 | workspace / session | Workspace / Sitzung | |
| 竖屏 / 横屏 / 自动 | Portrait / Landscape / Auto | Hochformat / Querformat / Automatisch | tri-state since v1.2.120, stored in `dsh_ui` key `orientation` (migrated from boolean `landscape`) |
| 页面缩放 | Page zoom | Seitenzoom | |
| 跟随系统 | Follow system | Systemsprache folgen | **removed in v1.2.120** (no longer a language option) |
| 语言运行时 | Language runtimes | Sprach-Laufzeiten | extension category |
| 编译构建 | Build & tooling | Build & Werkzeuge | extension category |
| 系统与多媒体 | System & media | System & Multimedia | extension category |

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
| `lang_de` | German (DE: `Deutsch`) | added v1.2.120 together with the third locale |
| `setting_orient_auto` / `ob_orient_auto` | Auto | new v1.2.120; the third orientation value |
| `lang_system` | — | **removed** v1.2.120; "follow system" is no longer offered |

## Screen orientation (v1.2.120)

Orientation moved from a boolean (`dsh_ui/landscape`) to the tri-state string
pref `dsh_ui/orientation` with values `portrait` / `landscape` / `auto`
(`OrientationPrefs.kt`). Existing installs are migrated on first read: a
missing `orientation` key falls back to the legacy boolean; with neither key
present, v1.4.18 defaults large screens (smallest width >= 600dp) to `auto`
and phones to `portrait`. Native settings pages apply the same pref via
`OrientationPrefs.applyTo()`. Semantics:

- `portrait` — `SCREEN_ORIENTATION_PORTRAIT`, mobile rendering;
- `landscape` — `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, desktop rendering
  (desktop UA, 1280px viewport, pinch zoom);
- `auto` — `SCREEN_ORIENTATION_USER` (follows the sensor, respects the
  system rotation lock) and desktop rendering follows the **actual**
  orientation, re-evaluated in `MainActivity.onConfigurationChanged`.

`setDesktop()` is now also called during `onCreate`; previously it only ran
in the `onResume` change branch, so a cold start in landscape mode rendered
the mobile layout until the preference changed again.

## Agent-facing text (`AgentContextSeed`)

`AGENTS.md` is seeded into `$DSH_HOME` and instructs the agent to **tap UI labels
by their on-screen text**, so it had to move with the UI:

- `设置 → 重启引擎` → `Settings → Restart engine` → `Einstellungen → Engine neu starten`
- `scr tap-text "重启"` → `scr tap-text "Restart"` → `scr tap-text "Neustart"`
- error fingerprints `扩展目录发布失败…` → `Extension publish failed`

`SEED_VERSION` was bumped 9 → 10 so existing installs pick up the reworded seed,
and again 27 → 28 (v1.2.120) so the German labels above are taught.

## Not translated on purpose

Chinese **comments** in Kotlin/Gradle/C/CI files are left as-is: they are
developer documentation, never rendered in the UI. `scripts/check-i18n.py`
therefore strips comments before scanning for string literals, and its only
allow-listed CJK literals are the two zh catalogue category keys in
`ExtensionStoreActivity`.

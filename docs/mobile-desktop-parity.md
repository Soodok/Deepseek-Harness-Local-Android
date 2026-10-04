# DSH Mobile and DSH Desktop feature parity

This is a maintained implementation checklist, not a claim that the two apps
have identical capabilities. DSH Mobile targets a phone running Android 8+
(the primary device is a Moto G84 on Android 15, without root) and must work
without a PC. Desktop-only operating-system integration is replaced where
possible with Android-native flows. Local AI models are intentionally outside
the mobile scope.

Status labels:

- **Android-Alternative** — available through an Android-specific UI or API;
  behavior can differ from desktop.
- **Noch offen** — desktop capability or expected parity still needs work or
  device validation.
- **Nicht relevant** — deliberately excluded from a phone app.

## Capabilities

| DSH Desktop area | Mobile status | Mobile implementation / remaining work |
| --- | --- | --- |
| Harness chat and web interface | Android-Alternative | Reuses the local DSH WebUI in a native WebView; Android supplies engine lifecycle and device bridges. |
| Local engine and offline runtime | Android-Alternative | Node runtime and DSH run inside the app sandbox; install and runtime recovery still need Moto G84 validation. |
| Configured remote model providers | Android-Alternative | Configure supported providers through DSH; credentials remain in app-private data and can be included in encrypted backups. |
| Local/on-device AI models | Nicht relevant | Explicitly not required; no local model download, inference, or model-management UI is planned. |
| Workspaces | Android-Alternative | App-private workspaces can be created, selected, renamed, deleted, backed up, and used as the engine working directory. No arbitrary PC filesystem workspace is assumed. |
| DSH profile and configuration editing | Android-Alternative | The DSH WebUI remains the primary editor; native recovery can quarantine problematic profile/configuration data. |
| Custom Agent preset packages (`.dshpreset`) | Android-Alternative | Native preview, trusted-source warning, import, activation, export, and removal; imports support the documented v1 package files only and require an engine restart. Referenced plugin/skill files are not bundled. |
| Preset marketplace / online registry | Noch offen | Native discovery, compatibility information, and safe update flows are not implemented. |
| Agent task queue and feedback | Android-Alternative | Native task management and feedback endpoints; persisted tasks recover after process restart, with interrupted running work returned to the queue. |
| Desktop filesystem, terminal, and process control | Android-Alternative | Engine operations are sandboxed by default. Android capabilities are individually gated; root and Shizuku are optional, never prerequisites for the primary use case. |
| Screen automation and device actions | Android-Alternative | Android accessibility service, notifications, speech, and other native bridges; permission and OS restrictions differ from Windows. |
| Desktop browser/OS integrations | Noch offen | Each integration needs a capability-by-capability Android assessment and a documented native alternative or explicit exclusion. |
| Encrypted user-data backup and restore | Android-Alternative | Passphrase-encrypted backup includes DSH user data and private workspaces; Android Storage Access Framework is used for file selection. Device privileges are not restored. |
| Safe-mode diagnosis and recovery | Android-Alternative | Persistent safe-mode marker, recovery actions, and a redacted support report; archive restoration and recovery need hardware validation. |
| Desktop installer, Windows services, and Windows-specific paths | Nicht relevant | Replaced by Android package installation, app sandbox, foreground service, and Android lifecycle handling. |
| Desktop multi-window and keyboard-centric UX | Nicht relevant | Replaced by a single-activity, touch-first phone interface; external keyboard support can be considered separately. |
| Sharing a mobile engine with other devices | Noch offen | Pairing UI exists, but the complete external-client pairing and secure remote-access flow is not implemented. |
| Update and compatibility management | Noch offen | Runtime/app update strategy, rollback UX, and compatibility warnings need a complete Android-specific design. |
| Audit log and granular capabilities | Android-Alternative | Native audit viewer and permission gates exist; capability toggles need a complete user-facing settings flow. |

## Verification checklist

- [ ] Run Android unit tests and both ABI builds in GitHub Actions for each
  release candidate.
- [ ] On Moto G84 / Android 15 / no root: install, start, stop, and recover the
  engine without a computer.
- [ ] Verify unauthorized and malformed loopback bridge requests are rejected
  while authorized engine requests still work.
- [ ] Verify task persistence across app process death and engine restart.
- [ ] Exercise encrypted backup export/import, wrong passphrases, interrupted
  restore, and workspace recovery using Android's document picker.
- [ ] Import, activate, export, and remove a trusted `.dshpreset`; confirm that
  duplicate IDs and unsafe archives cannot overwrite or escape app storage.
- [ ] Verify Safe Mode remains active until explicitly exited and that exported
  diagnostics redact credentials.
- [ ] For each remaining desktop feature, record a tested Android alternative
  or mark it **Nicht relevant** with a reason before claiming parity.

## Upstream reference

The preset package contract follows the DSH Desktop
[preset package documentation](https://github.com/dataelement/dsh-desktop/blob/main/docs/preset-packages.md).
Desktop behavior and this checklist should be revisited when that contract or
the upstream feature set changes.

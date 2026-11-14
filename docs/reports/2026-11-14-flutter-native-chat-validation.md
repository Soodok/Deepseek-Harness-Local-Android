# Flutter Native Chat validation report

## Composer controls and local glass surfaces — 2026-11-14 13:58

- Scope: preserve accepted charcoal conversation; add real plus-button file selection, model picker, tool-permission picker in the former voice slot, and clipped blur/edge/shadow on composer and sheets. Version declaration: 1.6.0-beta.12/code387. Earlier beta.11/code386 Web build at 13:40 preceded the review fixes; final build at 13:45 includes them.
- Contracts: optional ComposerTransport exposes available models and sendSubmission. PromptSubmission freezes model, permission and immutable attachment bytes per queued turn. Legacy transports keep their existing sendPrompt API; unsupported options are unavailable rather than discarded. Fixture choices explicitly say demo; no live model or Android system permission is changed.
- Files: file_selector 1.1.0 invokes the platform picker; up to 5 files and 20 MiB total. Both reported size and streamed chunks are checked. Cancellation/read failure preserves drafts. Attachments can be removed or sent without text.
- Verification: dart format lib test, flutter test --reporter expanded (26 passed), flutter analyze (No issues found, 4.9s), and git diff --check exited 0. Tests cover production theme, 360/390/432 widths, 48dp controls, picker selection/cancel/remove, binary bytes and caps, file-only submit, queued snapshots, failed-send editing and reconnect retry.
- Independent read-only review a8b7d314-f612-4315-b232-c5c2aadccf3c found queue dispatch races and disabled failed drafts. A delayed-transport regression failed with 2 calls instead of 1; controller-level in-flight guard now preserves the second queued item. Text remains editable after submission failure; the existing explicit reconnect action remains available and is tested with preserved bytes. A bounded temporary byte copy remains intentionally for defensive immutability under the 20 MiB cap.
- The new completed-turn interaction test exposed lingering isStreaming flags: regression failed (expected false, actual true), then reducer.turnComplete was fixed to finish those messages. All 26 tests passed afterwards.
- Final flutter build web --release exited 0 in 55.3s. Fresh headless Chrome instances at CDP widths 360/390/432, height960, DPR1 reported bodyWidth equal to viewport width. Actual clicks opened model and permission sheets; actual plus click produced Page.fileChooserOpened and DOM.setFileInputFiles selected composer-demo.txt, whose name rendered in the attachment chip. Each temporary Chrome/profile/server closed after capture.
- Evidence assets: [432 view](assets/2026-11-14-composer/composer-glass-432.png), [390 view](assets/2026-11-14-composer/composer-glass-390.png), [360 view](assets/2026-11-14-composer/composer-glass-360.png), [model sheet](assets/2026-11-14-composer/composer-glass-models.png), [permission sheet](assets/2026-11-14-composer/composer-glass-permissions.png), [selected file](assets/2026-11-14-composer/composer-glass-attachment.png).
- SHA256 main: 153a00051f471f9a9c0464f3543c4b61e2ac11e9e2904e5ad2e1ae3d3ee7b6fa; model: b284d8108fde37732006430def8ad027974ace20dc126e411f344156dbaa6947; permission: f79065912850eca582c296a3d3f87c2fcaafa5a0c6708c445106ab49ec6aa5b8; attachment: 70c7c42d2cf2ebfe7bf8880183a013c7b7a96e7175d1bd98da9f5dbdc1ff1432.
- Companion gallery: .superpowers/brainstorm/1440-1794625625/content/composer-controls-final.html. Authenticated root and all six /files image resources returned HTTP200. Retained the existing local companion for user review; removed unused golden/failure test images after verifying no Dart tests reference them.
- Boundary: this validates fixture Web rendering and controller/UI flows. Android APK build/install/device behavior and real engine model/attachment/permission protocols remain unverified; the known Android dependency blockers below are unchanged.


## Latest minimal conversation pass — 2026-11-14 13:11

- User replaced the workstation concept and blue capsules with a minimal Claude-style charcoal conversation. Implementation is in the isolated worktree; version declaration is 1.6.0-beta.10/code385. It has not been installed or published.
- Removed blue capsules, decorative assistant identity, nonfunctional attachment chips, and prose-derived file/change cards. Preserved lifecycle, tool expansion, approval/question, stop/queue, retry and fallback callbacks.
- dart format lib test, flutter analyze and flutter test --reporter expanded exited 0; analyzer: No issues found (13.3s); full suite: 17 passed. Geometry tests use production theme and measure send/stop/queued dispatch minimum 48dp at 360/390/432 widths. Fallback test verifies the callback fired once.
- Actual rendered send exposed fixture history IDs colliding with first prompt delta IDs. The identity regression failed before the fix, then passed after using distinct history IDs; actual screenshot confirms separate bubbles.
- Background read-only review bb136445-4c4a-4c94-a838-e10d3e8caaca found queue dispatch too small; actual widget geometry was 40dp. Set explicit 48dp and added three-width regression; restored 17/17. Other reported review findings: no definite functional regression; named checker invocation timed out without report.
- Final flutter build web --release exited 0 in 60.7s. CDP captured 360x960, 390x960 and 432x960 at DPR1, with bodyWidth equal to viewport width. Input and send were clicked in rendered Flutter and approval state captured. This proves fixture Web rendering and interaction, not Android/live transport.
- Final main image: E:/tmp/native-chat-web-preview/minimal-chat-final-432.png; SHA256 87c9f830ab0663a7a42f6e5bea716da8c89d2c30ec31612dfe1b446b1d586ce1. Action image: E:/tmp/native-chat-web-preview/minimal-chat-final-action.png; SHA256 7813251d6986f042ab49ab450380e94d2625966cdb97a211622c534fde701654.
- Companion screenshot gallery: E:/tmp/wt-flutter-native-chat/.superpowers/brainstorm/1440-1794625625/content/minimal-chat-final.html; static images only. Earlier visual passes below are historical and superseded.


Date: 2026-11-14 (Asia/Shanghai)
Worktree: /e/tmp/wt-flutter-native-chat
Branch: feat/flutter-native-chat
Commit: 4599c181728917dddef8dcc1d576f87e043cda7b
Baseline: 99de8534d11702c8dd85d1bf2899ebb9dc816867
Validation version: v1.6.0-beta.8, versionCode 383

## Scope

This worktree adds an independent Flutter chat surface for DSH Mobile. The UI is fixture-first and covers the complete local lifecycle: empty/history, user and assistant messages, streaming, tool start/finish, approval, question, queued prompt, stop, disconnect, resync, reconnect, and Web fallback. It does not reuse dsh go source, assets, copy, branding, layout, or interaction composition.

## Source changes

- Flutter state and reducer: flutter_ui/lib/src/model/chat_model.dart, flutter_ui/lib/src/state/chat_state.dart, flutter_ui/lib/src/state/chat_reducer.dart, flutter_ui/lib/src/state/chat_controller.dart.
- Transport boundary: flutter_ui/lib/src/transport/dsh_transport.dart, fixture_transport.dart, http_ws_transport.dart. The HTTP/WS wire contract remains unverified and is intentionally conservative.
- UI: flutter_ui/lib/src/theme/dsh_theme.dart and flutter_ui/lib/src/ui/{chat_shell,session_drawer,message_list,tool_run_card,action_cards,composer}.dart.
- Host bridge: flutter_ui/lib/src/bridge/host_bridge.dart and app/src/main/java/app/dsh/mobile/NativeChatActivity.kt.
- Android wiring: settings.gradle.kts, build.gradle.kts, gradle.properties, app/build.gradle.kts, app/src/main/AndroidManifest.xml, activity_main.xml, MainActivity.kt, ic_native_chat.xml, and six localized strings.xml files. NativeChatActivity is reachable from the existing top toolbar; WebView remains the default launcher surface. AGP is 9.1.1; the external Kotlin 1.9.24 plugin was removed because AGP 9 built-in Kotlin otherwise registered the same extension and failed with Cannot add extension with name kotlin. JVM target remains 17 through built-in Kotlin compilerOptions.
- Clean CI wiring: .github/workflows/android-build.yml now pins Gradle 9.5.0 and installs Flutter 3.50.0 in jvm-behaviour and build-apk. Each job generates a temporary Flutter module only when the ignored flutter_ui/.android wrapper is absent, copies its .android directory, runs flutter pub get, and asserts the required generated files without overwriting Dart sources.

## Dart and interaction evidence

- dart format lib test: exit 0.
- flutter analyze: exit 0, No issues found (7.5s) after removing the obsolete golden test import.
- Full flutter test --reporter expanded exited 0 with 17 passing tests. This includes reducer generation/sequence gates and immutable state, fixture transport events, complete lifecycle, approval/question/stop, FIFO queue dispatch, disconnect/reconnect/resync/error, bootstrap, and mobile geometry/hit testing at 360, 390, and 432 logical pixels.
- The first post-redesign targeted run exposed a real Flutter Material error: a DecoratedBox background wrapped ListTile and hid its ink/background. The process section was changed to a Material with a rounded shape; the targeted suite then exited 0. The same run exposed tests tapping a disabled question submit and a queued dispatch while the turn was waiting; tests now scroll the question control into view and assert queue dispatch is disabled until stop, then verify FIFO state transitions.
- The obsolete Ahem golden assertion and empty test artifacts were removed; visual evidence is captured separately with real fonts.

## Visual evidence

- Temporary preview: E:/tmp/native-chat-web-preview. flutter build web --release exited 0 in 48.6s using Flutter 3.50.0.
- CDP capture E:/tmp/native-chat-web-preview/capture-verified.mjs explicitly set width 432, height 960, deviceScaleFactor 1, mobile true. Both captures reported before {width:500,height:865,dpr:1} and after {width:432,height:960,dpr:1,visualWidth:432,bodyWidth:432}.
- Earlier warm-paper evidence: E:/tmp/native-chat-web-preview/claude-redesign-432.png (25,823 bytes, SHA-256 253c89774b3553470180026ebb777096918cac66b17dcd1e22af0f43914a742f1) and drawer (22,611 bytes, SHA-256 281e38dbdf37cb7bf2c18b7d981310dfe19b5855f369089c263043a8dd0c18d1); these are superseded visually by the Blueprint pass.
- Blueprint pass build: flutter build web --release exited 0 in 54.1s using Flutter 3.50.0. Fresh CDP captures reported before {width:500,height:865,dpr:1} and after {width:432,height:960,dpr:1,visualWidth:432,bodyWidth:432}.
- Fresh Blueprint main screenshot: E:/tmp/native-chat-web-preview/blueprint-refined-432.png, 432x960 PNG, 35,110 bytes, SHA-256 0ac5cbe64c4fb2a9451726beb3e2dbd3561df8d9d267de0cb08c59209f7f9ecc. Drawer: E:/tmp/native-chat-web-preview/blueprint-refined-drawer-432.png, 432x960 PNG, 26,360 bytes, SHA-256 61786ebc4762d1318749c5e1ad3a6dd0cd8fabf6b33dac0505d6b9d288aa65af.
- The fresh screenshots show the dark Blueprint palette, session/host capsules, custom-painted DshMark, low-contrast user bubble, prose-first assistant text, tool-aware composer and mobile drawer. Screenshot capture verifies rendered web geometry and visual presence only; it does not prove Android rendering or live transport.


- The worktree root has no gradlew. The only wrapper is flutter_ui/.android/gradlew.bat.
- The wrapper requires Gradle 9.5.0 and its download from services.gradle.org timed out with java.net.SocketException before project configuration.
- A pre-existing local Gradle 9.3.1 distribution was used without changing global settings. With AGP 8.5.2, Flutter 3.50 rejected the project because its minimum AGP is 9.1.1. After the isolated AGP/built-in Kotlin migration, Gradle configuration completed.
- compileDebugKotlin -Ppackage=noruntime reached app:prepareRuntimeAssets and app:processDebugNavigationResources. The no-runtime branch generated build/generated/runtimeAssets/runtime/MANIFEST.json and did not embed runtime.zip. The task then failed because the current environment could not resolve required Maven artifacts, including org.jetbrains.kotlin:kotlin-stdlib:2.2.10, kotlinx-coroutines-android:1.8.1, org.java-websocket:Java-WebSocket:1.6.0, and Flutter AndroidX dependencies.
- Full package mode was not attempted beyond the project gate because the worktree has no runtime zip; the build script explicitly requires -PruntimeZip for full mode.
- Therefore Kotlin compilation, APK creation, installation, device behavior, real engine transport, and screenshot verification remain unverified.
- The clean CI workflow changes were statically inspected in the worktree but were not executed by GitHub Actions in this session.

## Review boundary

- The named checker review attempt timed out after 600000 ms and no checker result was available. A fallback api-gpt review found P0/P1 lifecycle, protocol, and clean-CI issues; each finding was reproduced against the source and remediated before this report.
- The live HTTP/WS transport remains unused by default because its event schema was not captured from a real engine. The app starts the deterministic fixture and labels it as fixture; HostBridge still exposes an explicit Web fallback.

## Cleanup boundary

After the final formatter, analyzer, explicit tests, and test-directory run, the following verified worktree-only intermediates were removed: /e/tmp/wt-flutter-native-chat/flutter_ui/build, /e/tmp/wt-flutter-native-chat/flutter_ui/.dart_tool, /e/tmp/wt-flutter-native-chat/build, /e/tmp/wt-flutter-native-chat/app/build, and /e/tmp/wt-flutter-native-chat/.gradle. No files outside this worktree were touched.

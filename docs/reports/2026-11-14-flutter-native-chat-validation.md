# Flutter Native Chat validation report

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

## Dart evidence

- dart format lib test: exit 0; the subsequent dart format --output=none --set-exit-if-changed lib test check also exited 0.
- flutter analyze: exit 0, No issues found.
- Explicit final tests: chat_reducer_test.dart 7/7, transport_fixture_test.dart 3/3, chat_screen_test.dart 3/3, and widget_test.dart 1/1.
- Multi-target flutter test invocation covering all four files exited 0 with 14 passing test cases. flutter test test also exited 0; its progress reporter displayed 14 passing cases.
- Mutation checks: changing the generation gate from event.generation < state.generation to event.generation <= state.generation made chat_reducer_test.dart fail at the sequence, stale-event, and action assertions; restoring < returned the reducer suite to green. Earlier red/green checks also covered queued prompt order, queue preservation across snapshots, stale/gapped snapshots, and immutable state lists.
- The first final-check attempt used the unsupported flutter format subcommand and exited 1; no source was lost, and the corrected Dart SDK formatter run above is the final formatter evidence.

## Android evidence and limits

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

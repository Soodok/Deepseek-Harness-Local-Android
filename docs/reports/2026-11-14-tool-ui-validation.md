# Runtime and structured tool UI validation — 2026-11-14

Scope: isolated worktree E:/tmp/wt-flutter-native-chat, branch feat/flutter-native-chat, starting HEAD 545f2a130d1ee1c45eb7aa7400eeb154814728f9. The main Android worktree was not used for implementation. Version declaration: 1.6.0-beta.16/code391 (not an installed APK).

## Result

The accepted minimal charcoal conversation is preserved. Plans, subagent tasks, questions with single/multiple choices and optional text, approvals, image/link/file outputs use explicit structured fields. Default tool rows stay compact. The fixture drawer contains a labeled demonstration; it does not execute real work.

- ToolContent defensively freezes collections; toolUpdated replaces content by stable tool ID and preserves messageId through completion. Tools stay attached to their originating message.
- The conversation uses ListView.builder with stable row keys. Collapsed logs are not built; previews stop at 1600 characters, and full output uses a separate lazy chunked viewer with explicit copy-all.
- Only the active question keeps its local state alive when scrolled away. Request changes clear the old draft. Single/multiple choice labels and optional free text submit only on the explicit button; empty answers and busy actions are disabled. Controller guards also prevent duplicate and stale requests.
- Resource open/copy/preview is explicit. Only HTTP(S) URLs with a host and no user information are launched. Android uses ACTION_VIEW and Web opens a new window and removes its opener. Failed actions show feedback. Images load only after preview and use ResizeImagePolicy.fit with both decode dimensions limited to 1600; the original URL remains available through Open. This is a configured native decoding bound, not a measured Android memory result.
- The floating runtime selector reports resolved local mode. Management navigation closes the old sheet, and the distro label reads EngineModeSelector.lastDistro recorded at startup resolution rather than the changed next-start preference. SSH entries still open the existing Web manager; they do not claim a remote engine has been selected.

## Executed checks

- dart format lib test: exit 0 (40 Dart files; final run changed 0).
- flutter test --reporter expanded: 66 passed, exit 0.
- flutter analyze: No issues found, 18.8s, exit 0.
- A 70-row lazy-list regression first failed with expected 保留草稿 / actual empty string; AutomaticKeepAliveClientMixin on the active question restored the test.
- An oversized decode-limit mutation first failed with expected <=1600 / actual8640 at a 3x DPR viewport. Restoring the 1600 bound passed in the final full suite. The test uses the installed ResizeImage.imageProvider API and checks both dimensions and fit policy.
- Long-output regression initially overflowed and lacked a full-view action. The bounded viewer passes under the real scrollable context. Resource failures, clipboard errors, unsafe schemes, duplicate operations, stale callback revisions, plan/agent states and 48dp controls are covered.

## Follow-up styling request

- User requested a blue send button and the original DSH permission icons. The send button is #3B82F6 with a white arrow; disabled input uses a muted blue. Permission trigger and menu use the original DSH 16x16 vector artwork, drawn with CustomPainter and no new dependencies.
- Artwork source: extracted @deepseek-ai/dsh-web-frontend/dist/assets/index-ghOpC9uV.js:129, PermissionIconReadOnlyRegular (pb), PermissionIconWorkspaceWriteRegular (hb), PermissionIconFullAccessRegular (mb). SVG coordinates were preserved; DeepSeek copyright and MIT license are included in flutter_ui/lib/src/ui/permission_icon.dart.
- Only existing fixture permission behavior (ask/readOnly) is exposed; appearance does not pretend unsupported live workspace/full-access modes are connected.
- Existing composer, runtime and mobile geometry suites: 14 passed, exit0. Analyzer No issues found (76.5s), exit0. These follow the final 66-test suite for the functional implementation; no dedicated test was added for a reversible color adjustment.

## Independent review

- Runtime review 6269ea75-5c30-4415-8ffe-6e1e22820b15 identified the stale modal and preference-versus-runtime distro issues. Both were checked against source and corrected.
- Structured UI review 8ee13fe2-d45b-424d-befa-9dc522155e2d found no established P0/P1. Its two P2 findings (question eviction loses draft; unlimited image decode) were reproduced or guarded with the failing regressions above and fixed. Reviewers were read-only and did not run builds/tests. Final tests and captures are Lead evidence.

## Build and browser evidence

- beta.14 preview before the two final review fixes: flutter build web --release exit0 in57.4s.
- beta.15 review-fix preview: flutter build web --release exit0 in64.3s. Final beta.16 adds user-requested blue send styling and exact original DSH permission artwork; final beta.16 flutter build web --release exited0 in101.3s.
- Capture harness E:/tmp/native-chat-web-preview/capture-verified.mjs creates a separate temporary HTTP server and Chrome profile, sets CDP width360/390/432, height960, DPR1, and closes the browser/profile/server afterwards. The existing DSH GUI was not replaced.
- Actual drawer clicks open the tool showcase; actual expansion clicks open the plan and resource rows. Image preview requests the bundled PNG with HTTP200 only after clicking Preview. File Open creates a new browser page with the report URL. These validate fixture Web interaction, not native Android launching.
- Final beta.16 captures are archived in [assets/2026-11-14-tool-ui](assets/2026-11-14-tool-ui/manifest.json) with PNG byte sizes/SHA256, CDP viewport JSON sidecars and the exact capture harness. All three widths reported bodyWidth equal to360/390/432 at height960, DPR1.
- [Blue enabled send](assets/2026-11-14-tool-ui/tool-ui-blue-send.png): SHA256 b21da7d8c355fc386f9f3210a9d4955df49d639193a586f94d3f39d8635aed3f. [Permission shields](assets/2026-11-14-tool-ui/tool-ui-permissions.png): SHA256 620aba71074c4cd0699a85514e9a1a1c9c8667f2227b2692fab30f3643be4520.
- [Tool summaries](assets/2026-11-14-tool-ui/tool-ui-showcase.png), [resources](assets/2026-11-14-tool-ui/tool-ui-resources.png), [complete plan](assets/2026-11-14-tool-ui/tool-ui-plan.png), [loaded image](assets/2026-11-14-tool-ui/tool-ui-image-preview.png) and [runtime panel](assets/2026-11-14-tool-ui/tool-ui-runtime.png) were captured by real click/scroll actions on the same build. File-open checks found the report page target; image checks observed HTTP200.
- Latest companion page tool-ui-final.html is a screenshot gallery. It is not an interactive engine or Android app.

## Unverified boundaries

Android Kotlin compilation/APK/install/device behavior and real engine HTTP/WS integration remain unverified. The prior Android Gradle/Maven dependency blockers remain; no new APK was installed or published. SSH Windows compatibility and a native SSH manager deep link are not validated. No frame-rate or Android peak-memory benchmark was performed. Test failures in temporary preview harnesses are not treated as device failures.

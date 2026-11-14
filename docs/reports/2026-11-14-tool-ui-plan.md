# Structured tool UI implementation plan

Goal: keep the accepted minimal charcoal conversation while preserving work capabilities and responsive rendering. Default rows show summaries; details open on demand.

- Add immutable ToolContent with PlanStep, AgentTask and OutputResource data; keep text-only tools compatible. Bind each tool to its originating message, update by stable ID, and preserve content through completion. Add reducer tests before implementation for progress, order, immutability and stale frames.
- Render plan progress, agent state, image previews, links and deliverable files from explicit structured fields. Never infer files from prose. Collapsed logs build only when opened and long details are bounded with an explicit full-view action. Images load on demand with loading/error states; unsafe URI schemes do not launch.
- Extend ActionRequest choices and multiSelect. In a disjoint background scope, add single/multi-choice + free text questions and busy-state approval controls; preserve existing keys and callbacks. Main controller guards repeated actions and stale request IDs.
- Add a labeled fixture showcase reachable from the session drawer; explicit fixture data drives real reducers and renderers. Actual preview images/files are bundled as small local fixtures. This is internal UI validation, not a claimed live engine wire contract.
- Run Flutter tests/analyze serially; build one release preview after review fixes; capture 360/390/432 and tools/actions/assets; update AI_CONTEXT and validation report, then commit only isolated changes.

Write boundaries: background action agent owns only ui/action_cards.dart + test/action_cards_test.dart. Background result renderer owns only ui/tool_content_view.dart + test/tool_content_view_test.dart. Main owns models, reducer, fixture, bridge, shell/drawer integration and other tests.

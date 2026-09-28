# LLM-PLAYER Gemini Development Rules

This file contains project-specific Kotlin/Android/Native footgun checks derived from maxrave-dev/kotlin-footguns.

Use these checks whenever modifying code in LLM-PLAYER. Do not mechanically apply every check to every change; apply the relevant section(s) based on the files and behavior being changed.

## 1. StateFlow conflation

Relevant areas: Agent, Talk, Generation, UI state.

- Do not assume every intermediate StateFlow value will be observed by the UI.
- Check whether multiple rapid `MutableStateFlow.value = ...` updates can be conflated.
- For important state transitions such as Thinking -> Tool -> Tool Result -> Answer, prefer a state representation that remains correct if intermediate emissions are skipped.
- If the UI must observe every event, use an event/stream mechanism appropriate for that requirement rather than relying on StateFlow.

## 2. Job lifecycle and duplicate generation

Relevant areas: generation, Agent steps, model execution, memory retrieval.

- Ensure one logical operation has one clearly owned Job.
- Cancel an existing generation/operation before starting a replacement when concurrent execution is not intended.
- Verify Stop/cancel paths terminate all related coroutine work.
- Verify leaving a screen or destroying its ViewModel cannot leave orphaned work running.
- Model changes must not leave work targeting the previous model alive.

## 3. Read-only StateFlow boundaries

Relevant areas: Repository -> ViewModel -> Compose.

- Do not expose MutableStateFlow outside the owner.
- Prefer private MutableStateFlow + public StateFlow/asStateFlow.
- Check stateIn/shareIn scope and SharingStarted behavior.
- Avoid accidentally restarting expensive upstream work every time a screen is recreated.

## 4. flatMapLatest and composite keys

Relevant areas: model selection, conversation selection, future memory retrieval.

- When a result depends on multiple inputs, model the complete input key explicitly.
- Use flatMapLatest when a new request must invalidate/cancel the previous expensive request.
- Verify old model/conversation/memory results cannot arrive after the active key changes and overwrite current state.
- Use distinctUntilChanged at the appropriate boundary to avoid unnecessary re-execution.

## 5. Per-item cancellation and duplicate work

Relevant areas: Agent steps, memory retrieval, model-specific operations.

- Check that the same logical item is not processed repeatedly because of UI/state recomposition.
- When a new request supersedes an old request for the same logical key, cancel or invalidate the old work.
- Keep reset/reload ordering explicit.
- Prefer stable IDs/keys over comparing large mutable objects when determining whether work is already active.

## 6. Combining boolean gates

Relevant areas: Agent, Thinking, Tool execution, generation state.

- When execution depends on multiple flags, derive a single explicit gate/state where practical.
- Avoid separate collectors that can independently trigger the same operation.
- Verify transitions from enabled -> disabled cancel or prevent work as intended.
- Prevent duplicate execution caused by multiple Boolean flows changing close together.

## 7. ViewModel/UI separation

Relevant areas: AgentScreen, TalkScreen, Settings screens.

- Keep UI rendering separate from long-running generation/tool/repository logic.
- Do not put blocking work in ViewModel initialization or UI composition.
- Avoid runBlocking on UI/ViewModel initialization paths.
- Keep UI state and actions explicit and testable.
- Do not make Compose recomposition itself start expensive work unless the lifecycle is explicitly controlled.

## 8. Persistence: async vs blocking

Relevant areas: settings, conversations, memory.

- Normal user actions should not block the UI while persisting data.
- Critical shutdown/finalization paths must not silently exit before required persistence completes.
- Avoid maintaining duplicated sync and async implementations of the same persistence logic when one underlying implementation can serve both.
- Verify cancellation behavior of persistence operations.

## 9. Repository/Flow boundaries

Relevant areas: TalkRepository and future MemoryRepository.

- Keep data access and persistence inside repositories rather than UI code.
- Run blocking I/O on an appropriate dispatcher.
- Expose stable Flow/StateFlow contracts to consumers.
- Keep repository APIs independent of specific Compose/UI implementation details.
- Represent errors explicitly rather than silently converting failures into empty/default data.

## 10. Defensive structural parsing

Relevant areas: Agent ToolCallParser and streamed model output.

- Never treat the presence of a marker such as <tool_call> as sufficient proof that a valid tool call exists.
- Do not parse tool calls that occur inside <think> content unless the parser's protocol explicitly allows it.
- Validate the complete expected structure before executing a tool.
- Validate required JSON fields, types, tool name, and arguments before execution.
- Malformed tool calls must fail safely and observably.
- Do not silently discard malformed parser results with mapNotNull or equivalent logic when the failure matters to Agent correctness.
- Add regression tests for tool-call-like text inside thinking content.

## 11. Unknown/error is not a valid result

Relevant areas: Agent, Tool results, parser, benchmarks.

- Distinguish success, failure, unavailable, and unknown states.
- Never convert parser failure into an empty successful result.
- Do not use ordinary values such as 0, false, or empty string to represent an unknown state when that value is also a valid result.
- Preserve enough error information to diagnose why an Agent step failed.

## 12. Enum and persisted-data compatibility

Relevant areas: settings, Agent modes, sampling settings, future persisted state.

- Never persist enum.ordinal as a stable external representation.
- Use explicit stable values/strings for persisted enum state.
- Unknown future/legacy values must have a safe fallback.
- Adding an enum value must not make existing settings unreadable.
- Test loading old/default/malformed settings.

## 13. Dependent settings must remain coherent

Relevant areas: Agent, Thinking, Tool settings.

- Identify parent/child relationships between settings.
- If a parent feature is disabled, verify dependent features cannot accidentally execute.
- Decide explicitly whether dependent settings are merely ignored at runtime or automatically reset in persisted settings.
- Avoid impossible combinations such as an inactive Agent executing Agent-only tools.

## 14. ARM64 native dependency audit

Relevant areas: JNI, llama.cpp, native libraries, release APK.

- Verify every required native .so exists for arm64-v8a.
- Do not assume that because llama.cpp builds, every other native dependency is compatible.
- Inspect the final APK/AAB contents for required native libraries.
- Perform a release-build native load/init smoke test.
- Verify JNI method names/signatures and native initialization paths after native changes.
- Treat "Gradle build succeeded" and "native code works on the target device" as separate checks.

## 15. Upstream native-library workaround discipline

Relevant areas: llama.cpp and other third-party native code.

- Avoid modifying third_party/llama.cpp unless there is a concrete reason.
- Before adding a workaround, document the upstream issue/behavior that requires it.
- Keep Android/LLM-PLAYER-specific workarounds outside upstream code when practical.
- Record whether the workaround affects performance, correctness, or portability.
- If a third-party source modification is unavoidable, document the upstream commit/version and the condition under which the patch can be removed.
- Re-check upstream before carrying a workaround forward across llama.cpp updates.

## Change-specific checklist

### Agent changes
- [ ] StateFlow intermediate-state/conflation behavior checked
- [ ] Job ownership and cancellation checked
- [ ] Boolean execution gates checked
- [ ] ToolCallParser structurally validates output
- [ ] Parser failure is distinguishable from valid empty results
- [ ] Duplicate Agent-step execution ruled out

### Talk/Generation changes
- [ ] Generation Job has a single owner
- [ ] Stop/cancel path verified
- [ ] StateFlow updates remain correct if intermediate emissions are skipped
- [ ] Repository/dispatcher boundaries checked
- [ ] UI does not start duplicate expensive work

### Memory changes
- [ ] Composite request key defined
- [ ] Stale requests/results cannot overwrite current state
- [ ] Duplicate retrieval prevented
- [ ] Persistence and Flow boundaries checked
- [ ] Legacy persisted data compatibility checked

### JNI/llama.cpp changes
- [ ] arm64-v8a native dependencies audited
- [ ] Final APK native libraries checked
- [ ] Native load/init smoke test performed
- [ ] JNI boundary checked
- [ ] Upstream workaround necessity documented

## Working rule for Gemini

Before implementing a non-trivial change:

1. Identify which sections above are relevant.
2. Inspect the existing architecture before changing it.
3. Implement the smallest change consistent with the existing design.
4. Run targeted tests/build checks.
5. Explicitly report any relevant footgun checks that could not be verified.

Do not rewrite unrelated code merely to satisfy this checklist.

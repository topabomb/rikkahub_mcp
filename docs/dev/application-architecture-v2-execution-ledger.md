# Application Architecture V2 Execution Ledger

This ledger records evidence required by the V2 execution plan. It is a development record; the
current architectural contract remains in `docs/references/`.

**Status (2026-08-28):** Phase B–F are re-frozen. The MCP scope reopened after catalog ownership and
publication defects were found outside the original slot tests. The final
catalog/session/turn-snapshot implementation, independent review and complete Gradle gate are recorded
below. Device evidence remains separate from JVM/build evidence.

## Media Capability Authority Fix (2026-08-28)

Restored `Model.inputModalities` as the sole configuration fact for model image input capability.
Removed the endpoint-host veto that silently dropped IMAGE capability for custom
OpenAI-compatible gateways; `RequestMediaCapabilities` is now strictly an internal derived result
of (model capability × selected provider protocol × serializer container mapping).

| File | Change |
|------|--------|
| `ai/.../provider/Provider.kt` | `requestMediaCapabilities()` made abstract; default `NONE` removed so no new Provider can silently drop IMAGE |
| `ai/.../openai/OpenAIEndpointProfile.kt` | Removed `COMPATIBLE` early-return; `userImages` derived unconditionally from `model.inputModalities`; generic `OPENAI_COMPATIBLE` Responses profile now sets `supportsMultimodalFunctionOutput = true` |
| `app/.../tools/GenerationToolSetFactory.kt` | `shouldInjectAttachmentInspection()` now checks all three source containers (USER/ASSISTANT/Tool.output); `OPAQUE_REPLAY_ONLY` is not full coverage; removed `runCatching` swallow and second host veto |
| `app/.../tools/AttachmentInspectionTool.kt` | Construction-time capture of inspection model/provider/capabilities; `executeInspection()` no longer re-resolves from Settings or returns `inspection_model_unavailable` based on capability |
| `app/.../service/MasterTurnCoordinator.kt` | Resolves one immutable run media contract shared by projection and tool injection; Master tools and `GenerationMemoryContext` refresh from current Settings at each Provider step |
| `app/.../runtime/DelegationCoordinator.kt` | Removed the frozen-inspection special branch; captures a generic run-start tool-name ceiling, shares the run media contract, and applies the Target Memory enable/namespace ceiling |
| `app/.../ai/GenerationLoop.kt` | Requires the Coordinator-owned media contract; one step Memory context controls prompt/schema/owner with a write-time guard; `buildToolIndex()` rejects blank/duplicate names and missing tools durably return `tool_not_available` |

Tests: `ChatCompletionsAPIMessageTest`, `ResponseAPIMessageTest`, `ShouldInjectAttachmentInspectionTest`,
`AttachmentInspectionToolTest`, `GenerationLoopFlowTest`, `MemoryToolsTest`, `TurnEngineTest`,
`SubAssistantRunPolicyTest`, and `ApprovalContinuationMissingToolIntegrationTest` cover serializer
shape, media ownership, Memory revocation/owner policy, Target name ceiling, same-handle approval
continuation, and the real Room `STARTED -> FAILED` tool-execution transition. The Android integration
test drives `TurnEngine.start`, the production `applyToolApprovalDecision`/`UpdateToolApproval`
command, continuation-worker installation, `TurnEngine.continueActive`, and `GenerationLoop` with a
tool removed between approval and continuation. It verifies that no side effect runs and that one
original turn owns one durable failed execution. On the final test implementation, the filtered
`:app:connectedDebugAndroidTest` run passed this class, then the unfiltered repository
`connectedDebugAndroidTest` gate passed 62 tests with no failures, errors, or skips on the Android
17 `Pixel_10_Pro_Fold` emulator. The final repository
`test assembleDebug lintDebug assembleRelease` gate also passed after this test implementation;
no precomputed pass count is authoritative.

## Phase B: retired historical repairs

### Orphaned turn execution

`ReconcileOrphanedTurnExecution` was a repair protocol for a state that current command commits
cannot create: a non-terminal `turn_execution` whose owning assistant message is absent. The V2
implementation removes the command, reducer, runtime and coordinator branches, together with the
legacy-only test. `TurnRecoveryTest` now asserts that this condition is an explicit recovery
failure and does not write a compensating command.

### Settings image Artifact adoption

The historical writer in 0.0.17 created a Settings avatar/background payload under `files/upload`
before asynchronously registering its Artifact metadata. A process death between those two writes
could leave a Settings root whose file existed but had no `ACTIVE` Artifact row. The 0.0.18
`adoptSettingsOwnedImages` startup repair covered that known gap.

The V2 boundary does not recreate that repair. `ArtifactStore.reconcileStartup` never guesses or
inserts missing metadata. A Settings background/avatar whose payload or `ACTIVE` metadata is absent
cannot render, so recovery persists the existing `ArtifactReferencePolicy.detach` fallback before
publishing Ready. The lifecycle tests cover background, assistant avatar, and user avatar.

The migration lesson is explicit: a file payload, its metadata and its Settings root form one
ownership handoff, not three eventually-consistent writes. The current writer stages through
`ArtifactUseCase` and publishes only after the Settings root is committed. Historical repair was
appropriate only at the released 0.0.18 boundary that could still encounter the old writer's
interruption window; after that coverage was verified, retaining a general startup scanner would
turn corruption into a second owner and conceal future writer defects.

`CheckpointWriteAmplificationTest` also injects a `TurnExecutionDAO.insert` failure after the
assistant slot write in a real in-memory Room transaction and verifies that no message node remains.
This keeps the current writer's “slot and running turn fact are one atomic commit” invariant under
failure rather than only asserting the coordinator's arguments.

### Real upgrade sample

On 2026-08-26, a clean Debug 0.0.17 installation created an assistant avatar through the normal
crop flow on `emulator-5554`; it produced one `files/upload` image and its Artifact metadata. The
same package data was upgraded in place to Debug 0.0.18. After the upgraded app started, the
payload still existed and the copied Room database contained its matching `ACTIVE`, `USER` Artifact
row. The sampled database had no `turn_execution` records, so it contained no orphaned execution.

To execute the 0.0.18 adoption branch rather than only observe its no-op case, a copy of that
real upgraded database was made while the app was stopped, the one matching Artifact row was
removed with `foreign_key_check` clean, and the modified database was installed back into the same
Debug package without changing Settings or the payload. Restarting the real 0.0.18 app retained the
payload and recreated exactly one `ACTIVE`, `USER` row for the Settings root. The Settings JSON
representation also preserved avatar/background fields.

The same 0.0.18 device session then created a globally visible sub-assistant through the normal
UI, force-stopped and restarted the app, and confirmed the persisted sub-assistant still appeared
in the filtered assistant list. Selecting and cropping a photo for that sub-assistant created a
new app-private upload payload. This covers the user-facing Settings image ownership write and
restart path; the final V2 package still requires separate device acceptance for chat attachment,
background assignment, deletion and GC as required by the Phase B exit matrix.

This proves the normal persisted 0.0.17 -> 0.0.18 path preserves Settings and Artifact facts, and
that the released 0.0.18 repair closes the historical missing-metadata state. It does not
reinterpret an interrupted pre-0.0.18 producer window as a legal persistent format. If such
corruption is encountered after V2, background/avatar roots with either Artifact half missing are
cleared through the normal Settings owner because retaining them has no recovery value. This is not
a compatibility scanner: it never adopts a payload or creates metadata.

### Current Phase B Debug check

The pre-fallback V2 worktree `app-x86_64-debug.apk` was installed on `emulator-5554` and first
started successfully with cleared test-only Debug data. Reinstalling it over the historical test
fixture then displayed the recovery gate for a Settings root without `ACTIVE` Artifact metadata,
proving that snapshot did not invoke adoption. At that later check the named payload was no longer
present, so this is evidence for the retired fail-closed behavior only, not a second proof that a
payload-only missing-metadata state is retained. The historical proof above remains the adoption
branch evidence. The current Settings fallback is covered by lifecycle tests; the subsequent
normal-path checks record avatar/background coverage. Sent-message attachment preview, deletion
and GC remain open.

After clearing the synthetic Debug fixture, the same current APK completed the ordinary assistant
avatar picker and crop flow. It created `files/upload/1cf9bb3e-70a1-4072-9edc-adbfae9a1eb5.png`
and its matching `artifact` row (`ACTIVE`, `USER`); force-stop/restart returned to the normal chat
screen. From File Management, the current APK then previewed a user image, selected the default
assistant, and confirmed “set as background.” That transaction created
`upload/9b744313-cd6c-42ed-893b-fd5d558f38da.png`; after force-stop/restart the persisted assistant
still referenced it and each of the three upload rows was `ACTIVE`, `USER`. This proves the current
binary avatar/background ownership and restart paths. Sent-message attachment preview, deletion
and GC remain open device cases.

The current APK's chat overflow photo action also copied a selected image into a second upload
payload and displayed its `41.png` attachment chip in the new-chat composer. No model was
configured, so this records only the attachment selection/copy presentation path, not sent-message
preview, deletion or GC behavior.

File Management's delete action was then invoked for a background-referenced payload. The current
APK presented the exact impact (“used as one assistant background”) and warned that removed
attachments become unavailable; cancelling the dialog, force-stopping and restarting retained all
three upload payloads and returned to normal chat. This proves the guarded delete presentation and
cancel path.

The same session then selected the synthetic, unreferenced `41.png` chat-composer attachment for
deletion. Its confirmation named that exact file and reported no reference count; accepting it
removed only that payload. A force-stop/restart retained the avatar and background payloads and
returned to normal chat. To exercise real GC rather than only the user-delete state machine, the
test then selected the persisted avatar source as the default assistant's new background. The
background service created `upload/b60cd81c-19f0-4f35-912c-c600b4255bbf.png`, atomically updated
the Settings root, and collected the superseded synthetic background. Before and after a further
force-stop/restart, only the avatar and new background payloads existed; the copied Room database
contained exactly their two `ACTIVE`, `USER` Artifact rows. This covers the current-binary delete,
GC, root-protection, image-preview action, and restart paths without touching user data.

### Preserved compatibility

Room migrations 1 through 8, their schemas, `SettingsOcrMigration`, search-selection DataStore
migration, and legacy backup JSON decoding remain intact. None of these are historical-error
repairs; they are published persistence or import contracts.

## Phase C: configuration aggregate and managed snapshot

`PreferencesStore.kt`, `SettingsCommitCoordinator.kt`, and the replaceable
`SettingsWritePolicy`/`AllowAll` path are removed. Their responsibilities now have one public
owner: `SettingsStore`. It publishes only `effectiveSettings`; its same-package internal
collaborators have non-overlapping duties: `EffectiveSettingsResolver` merges Built-in, Local shadow
and verified managed data, `SettingsWriteRules` rejects locked Local changes, `commitSettings`
normalizes, persists, materializes and only then publishes, and `ManagedConfigurationStorage`
owns the signed envelope, LKG and atomic generation files. No consumer reads a raw Local flow or
merges configuration itself.

The DataStore name, existing keys, Room schemas and backup format are unchanged. The one allowed
DataStore migration reads the historical `search_selected` index once, writes
`selectedSearchServiceId`, and removes the old key; runtime index fallback and dual-read code are
absent. Local writes continue to change only the Local shadow. A managed overlay may lock a path,
but it neither overwrites the Local shadow nor creates another writer; expiry retains the active
overlay and locks until a newer signed generation explicitly changes them.

The Settings local/effective group is 1,249 physical Kotlin lines (limit 1,250):
`DefaultProviders.kt` 49, `EffectiveSettings.kt` 168, `SettingsCommit.kt` 17,
`SettingsNormalization.kt` 77, `SettingsOcrMigration.kt` 96, `SettingsStore.kt` 778 and
`SettingsWriteRules.kt` 64. `ManagedConfiguration.kt` is reported separately at 558 lines, as the
plan permits for the future managed document aggregate; it remains an internal collaborator, not a
second Store or Flow.

The automated Phase C evidence covers normalized local persistence and no publish-on-failure,
lock rejection without shadow mutation, managed signature/tenant/generation/graph validation, LKG
retention, managed-state publication, search identity migration, all affected Settings consumers,
and user-visible managed-lock feedback. The cross-owner Workspace deletion path also uses the same
`SettingsStore.updateLocal` contract: before physical deletion it can compensate a journaled
Assistant binding; after physical deletion starts it remains `BROKEN` and retains its journal until
both filesystem cleanup and a one-row Room deletion are confirmed. Its failure tests cover normal,
retry and startup recovery paths.

The required device matrix for Settings, Search, MCP configuration input and managed UI has not
been rerun for this Phase C commit. JVM tests and builds do not constitute device acceptance.

## Architecture review after Phase B and C

V2 is still the right design. V1 failed at subtraction because it kept dual planners, dual
runtimes, and historical repair as permanent branches. Phase B and C already prove the
correct order: retire dead repair first, then give one owner a complete protocol. They do
not yet prove the 10% line-count gate; that gate lives in Phase D and E.

Phase B is architecturally complete. Orphan-turn repair and Settings-image adoption are
gone from the writer, recovery, and tests. Room 1→8 and legal Settings/backup migrations
remain. The follow-up `5ccad20b` is a boundary clarification, not a second owner: a
Settings avatar/background with a missing Artifact half is cleared through the existing
Settings write chain. Device evidence covers avatar/background ownership, delete, GC and
restart; sent-message attachment preview remains an open Phase D/F matrix item, not a
Phase B writer defect.

Phase C is the right aggregate and the wrong place to hunt Conversation-line subtraction.
One `SettingsStore`, one `effectiveSettings` snapshot, internal envelope/resolver files,
search index→id migration, and deletion of `AllowAll` all match the plan. The local
Settings group sits at 1,249 / 1,250 lines. `ManagedConfiguration.kt` at 558 lines is the
planned exception and is why Phase C is allowed to grow. The remaining Phase C gap is
operational, not structural: the Settings/Search/MCP/managed UI device matrix has not been
rerun.

The remaining subtraction is still where V1 left it. Conversation/Turn started Phase D at
18 files / 6,811 lines against a 5,800 cap. MCP is unchanged at 1,890 / 1,450. Workspace is
unchanged, including a separate terminal query type. The first D cut replaced
`ConversationReducer` + `ConversationMutationBuilder` + Runtime persist callbacks with one
`ConversationTransition` and `ConversationRepository.commit`. The multi-map turn runtime
(`generationJob`, `activeTurnId`, `cancelReasons`, public `processingStatus`) is still the
next D cut. Splitting D into “planner this week, runtime next week” would recreate the V1
dual-path window, so ActiveTurnRuntime, GenerationLoop and presentation merge stay in the
same phase.

Feasibility holds if D does not grow a compatibility facade. `ConversationRuntimeTest`
must move off `submit`/`startTurn` persist callbacks in the same delivery. Line-count
recovery is expected from deleting MutationBuilder, Runtime command mutex/classification,
checkpoint events, the three presentation files, and the lease registry — not from
compressing `TurnEngine` or `DelegationCoordinator`. If a correct ActiveTurnRuntime plus
capability types cannot meet 5,800 / 4,362, stop and revise the budget; do not keep
`submit` as a test-only second writer.

## Phase D: request media capabilities and runtime subtraction

The unique planner, Coordinator serial gate, `GenerationLoop.run(GenerationRequest)`,
private `ActiveTurnRuntime`, Registry `installAndStartActiveRequest`,
`SendMessageReceipt.turnId`, and merged `ConversationPresentation` are in the current
worktree. Processing text is request-scoped; Chat UI reads
`ConversationPresentation.processingText` instead of a second Query Flow.

Request media capability is now a closed Provider fact:
`RequestMediaCapabilities` / `RequestImageSupport` live in `Provider.kt`,
`GenerationLoop` resolves them once, and `AttachmentProjectionTransformer` uses that
value instead of `model.inputModalities` alone. Official OpenAI Chat never natives
ASSISTANT/TOOL_OUTPUT images. Responses unknown compatible hosts stay
`OPAQUE_REPLAY_ONLY` for assistant replay and `NONE` for tool-output images. Serializer
paths fail closed on leftover native images; `Image output omitted` and empty-text
encode fallbacks are gone. `SubAssistantRunLeaseRegistry` is private Gate state.
`GenerationChunk.Checkpoint` / `TurnEvent.Checkpoint` no longer exist as UI events;
durability remains `onCheckpoint`.

Line-count status against the original §11.7 numbers could not be met by a correct
unique-owner implementation. Current physical counts: Conversation/Turn 15 files /
7,217 lines (the earlier 7,157 omitted `ConversationOperationLocks`); attachment/wire
slice 9 files / 4,934 lines. Production `src/main/**/*.kt` versus HEAD is +372 tracked
plus untracked `ConversationPresentation.kt` (202) = +574. `e19ae595` already lacked
`ConversationMutationBuilder`; Transition absorbed planner + mutation + facts, so
file count fell while lines rose. Request-level `RequestMediaCapabilities` plus
serializer fail-closed also grew the wire slice above the original 4,362 (that number
was already below the true 4,780 baseline).

The duplicated Master/Delegation `prepareFinalize` lambdas are now one
`TurnEngine` path that always calls `TurnFinalization.prepareOwnedTurnMessagesForFailure`
for Cancelled/Failed. Mutation is command-semantic: last-node, node-id, truncate,
delete-with-reindex, and `ReplaceMessageTree` full-tree only for whole-tree
commands. Header patches come from the command, not old/new field enumeration.

Approval continuation now has a Registry operation
`installAndStartApprovalContinuation` that revalidates awaiting identity under the
conversation lock and refuses a stale handle without cancelling a newer START.
Master no longer uses the generic install path for CONTINUE_APPROVAL. Child
`ask_user` wait uses the same `AWAITING_APPROVAL` request phase and resumes with
`markRunning` on the same worker. Unused `SupersededTurnBarrier`,
`beginSupersedingTurn`, `installContinuation`, `markPreparingContinuation` and
`appendTargetMessageId` are deleted.

Superseded workers keep their cancel reason after replacement; `TurnEngine`
treats only `user_stop` as cancelled-by-user. `stopTurn` captures one request
identity via `captureAndRequestStop`. Ordinary `UIMessagePart.Image` parts no
longer inherit Responses opaque-replay native markers.

§11.7 was revised rather than compressing `TurnEngine`/`DelegationCoordinator` or
keeping a second writer: Conversation/Turn ≤15/7,250; wire ≤9/4,950; core ≤56/14,820;
full `src/main/**/*.kt` ≤125,200. Independent Phase D net-reduction versus HEAD was
also revised: unique-owner plus fail-closed media cannot net-reduce without
compression. Device matrix for New Chat through TTS is user-owned; this ledger does
not treat launch-only emulator evidence as device acceptance.

`ActiveTurnRuntime` is a private inner class of `ConversationRuntime`. Callers receive
intent APIs and `ActiveRequestPresentationFacts`; they never hold the request object.
`execute()` maps identity/not-found conflicts to `ConversationCommandResult.Conflict`
and transaction errors to `Failure`. `ownedRequests` retains a superseded request until
its worker completes so cancel reason cannot fall back to `user_stop`; a completed
turnId is a no-op cancel target. Request identity APIs are `internal`. START validates
turnId identity first, then consumes `nextTurnEpoch()` only for the committed command.
`planHeader` and `plan` share `headerPatchFromCommand`; they are not a second header
protocol.

Matching JVM tests on 2026-08-27:
`ConversationCommandCoordinatorTest`, `ConversationRuntimeTest`, `TurnFinalizationTest`,
`TurnEngineTest` passed. Full gate `test assembleDebug lintDebug assembleRelease`
passed in 6m 57s. Phase D code is complete; device matrix remains user-owned and
overall V2 is not complete until E/F.

## Phase E: MCP, file domain, and Workspace subtraction

MCP no longer keeps parallel `clients`, `connectedConfigs`, reconnect/dormant/auth
jobs, attempt counters, or `getServerLock`. Each server is one private
`ConnectionSlot` with fingerprint, client, status, jobs, and mutex. Effective
revision, foreground, network, and the renamed `refreshConnections()` all call
the same `reconcile`. `McpConnectionKey.kt` is gone; fingerprint comparison lives
in `McpManager.kt`. Stale reconnect jobs are generation-scoped and cannot publish
onto a replaced slot. Token refresh and interactive authorization persist
only through `persistOAuthStateFor`; a URL change during the network round
trip discards the token instead of attaching the old resource bearer to a
new endpoint. `syncingStatus` is a Flow projection: each slot writes only
its own key (or removes it on teardown), so concurrent servers cannot
rebuild a stale whole-map snapshot over a neighbor. The dead `McpJson`
declaration is deleted.

Concurrency ownership is part of the unique slot, not a second protocol:
`mutex.withLock` never returns via a non-local label that skips unlock;
reconnect and dormant jobs increment generation before closing the previous
client; a transport callback captured at connect time is rejected after that
increment; `teardownLocked` re-reads desired config after `close()` and, if
the server was re-added during the close window, reconnects in place instead
of orphaning the mapped slot. OAuth writes go through `persistOAuthStateFor`
and refuse a token when the canonical resource changed. Domain after those
correctness fixes: 7 files / 1,890 → 6 / 1,915. The first slot rewrite was
1,738; the +177 is the exempt concurrency repair recorded in §11.7 (cap 1,930).

Skill single-directory save/import/delete share one private `mutateSkillTree`
that owns the only staging → mutate → validate → atomic publish → cleanup
protocol; callers keep their typed results via `SkillFileSaveResult` mapping.
The skills-root bundle import stays a separate transaction. Artifact adoption
remains absent; the four Artifact boundaries are unchanged. Domain:
14 / 2,681 → 14 / 2,690 (+13): the merge is protocol unification and is
line-neutral by construction because copy/validate/publish helpers were already
shared; the +13 includes the exempt 5ccad20b dangling-root fix accounted in
Phase B follow-up.

Workspace terminal read projection moved into `WorkspaceQueryService.observeTerminal`.
`WorkspaceTerminalQueryService.kt` and its DI registration are deleted. Shell and
interactive terminal both consume `ProotLaunchSpec`; they no longer hand-write
argv/env/bind. Terminal now uses the same app bind set as shell (`/skills`,
`/tool_outputs`, `/upload`) and the same `USER`/`SHELL` env facts — the missing
shared launch fact, not a product fork; the shell env gains `USER`/`SHELL`.
Domain: 13 / 2,402 baseline → 13 / 2,445. The whitelist-mandated
`ProotLaunchSpec` (+131) is the unique launch owner; cap 2,460.

Phase E production totals versus HEAD (`e80eafbe`): 609 `src/main/**/*.kt`
files → 608; current physical total 127,940 lines (cap 128,000). Core file
count is 56. Repo-wide lines are +6 versus HEAD because slot concurrency
repairs (mutex unlock, generation-before-close, close-window re-add, OAuth
resource guard) exceed the first slot rewrite's deletion. Plan rule 1 was
revised so Phase E is accepted on file subtraction plus §11.7 caps, not by
compressing OAuth/backoff. §11.7 caps were revised per plan rule 6; the
original MCP 1,450 / core 14,820 / repo 125,200 assumed deletion amplitude
that contradicts §11.8 fidelity plus the required slot concurrency repairs.

Matching JVM tests on 2026-08-27: `McpManagerTest` (stale transport after
reconnect, remove/re-add during `close()`, OAuth resource guard, refresh
cancellation, and independent two-server status). Full gate
`test assembleDebug lintDebug assembleRelease` passed after the per-key
status publication change (7m 8s). Device matrix for MCP settings/Picker,
Skill files, and Terminal remains user-owned. JVM/build success is not
device acceptance.

### MCP Catalog Commit follow-up (reopened 2026-08-28)

The follow-up audit found that Phase E unified connection ownership but did not separate four
facts: Server definition, user tool policy, remote catalog, and current-turn model exposure.
`syncTools()` still writes remote schema through `SettingsStore.updateLocal`; this serializes
multi-server discovery behind the global Settings mutex, emits revisions that fan out to every
slot, and cannot correctly update a managed-only MCP record. The Manager reads only the first
`tools/list` page. Its public `Connected` status does not prove a non-empty, complete catalog was
committed. Generation and UI read Settings tools without checking runtime availability.

The original `McpManagerTest` seam hid the defect: fake `listTools()` returned an empty list as a
successful connection, and fake `updateLocal` transformed the effective snapshot as if it were the
Local shadow. It did not cover non-empty discovery, real Local/Managed separation, pagination,
catalog commit failure, context stability or atomic catalog visibility.

Phase E/F are reopened with these required results:

- Settings owns only `McpServerDefinition` and policy-only `McpToolPolicy`; remote schema is removed;
- `McpCatalogStore` atomically owns last-known-good non-empty catalog revisions and digest;
- a per-server `McpServerRuntime` publishes Available only after full pagination, validation and catalog
  commit for the same generation/definition/client lease;
- empty, partial, failed or cancelled discovery preserves LKG but never enters a new turn;
- notification refresh is conflated, unchanged digest is a no-op, and stale generation cannot commit;
- Master/Target use one immutable `TurnMcpCapabilitySnapshot`; execution revalidates local explicit
  revocation before side effects, while disconnect preserves schema and fails through the tool result;
- UI consumes one `McpServerPresentation`; no Composable/ViewModel owns Coordinator, Client or connection
  Job, and RetryScheduled/WaitingNetwork are not loading;
- Android observes the default validated network; enabled is distinct from activated/session,
  startup does not connect every registered server, and fixed 60-second × 30 Dormant polling is removed.

Completion evidence must include the 20-tool fixture, Local/Managed separation,
same-name import preservation, pagination, empty rejection, notification/coalescing, cancellation,
two-server parallel activation, turn snapshot stability, execution-time revocation, UI state mapping,
full Gradle gates and an independent subagent review. Real-device validation remains a distinct
acceptance gate and cannot be inferred from the fixture or build.

### 2026-08-28 implementation checkpoint

Implemented in the reopened MCP scope: policy-only Settings data, `McpCatalogStore`, complete cursor
pagination, non-empty catalog activation, digest/revision commits, same-definition durable LKG,
conflated list-change refresh, bounded four-server connection concurrency, validated default-network
observation, immutable Master/Target run-start snapshots, application/query UI ports, effective
Managed presentation, import preservation and Assistant-reference cleanup. Connect/discover/refresh/
call I/O runs outside the server-runtime mutex under AppScope ownership; generation/client/definition/catalog
leases reject stale operation completion, while caller cancellation only cancels its waiter. All MCP
UI writes use the typed application service, and tool counts come from Catalog plus policy rather than
transport status.

The final MCP production slice replaces the monolithic Manager with named single-owner components and
dedicated application/query ports. It does not
retain a facade, Settings schema cache, dormant poller, duplicate registry, or UI write path. The V2
budget table records this actual topology instead of treating file consolidation as an architecture goal.

The final determinism follow-up adds a Catalog head token across commit/no-op/rejection and rollback,
preserves the pre-refresh status plus active catalog as one lease, and freezes each turn from one slot
capability view. OAuth writes use transport/canonical-resource/static-headers plus revision CAS; matching refreshes are AppScope
single-flight while a changed trust boundary/revision owns a distinct lease. Replacement authorization waits for the previous Job
and seals its revision before starting. Settings read/write share first-wins
MCP id/name/policy normalization. Provider-visible tool-name collisions fail closed at assembly. Setting,
Assistant picker, Files picker and conversation readiness consume the joined `McpServerPresentation`;
authorization, reconnect and discovery remain distinct states rather than one loading state.
Background refresh and all connection-health changes now keep an existing LKG active; only first
discovery without LKG is busy. Durable Catalog snapshots hydrate runtime after process restart without
eagerly connecting all configured servers. New conversations activate only Assistant-selected runtimes;
foreground/network recovery touches only previously activated runtimes. A server does not publish
Connecting until it owns one of four I/O permits, preventing the 20-server loading queue seen in the
incident. Call admission, turn capture and UI query consume one atomic runtime capability rather
than independently reading the Catalog DataStore flow. Managed source/lock metadata is part of the
typed MCP presentation, so the settings page no longer reaches through `SettingVM` for Settings state.

Explicit catalog changes and incidental failures have separate rules. User refresh waits a real
operation receipt; `notifications/tools/list_changed` is debounced and single-flight, then performs a
full discovery and atomically changes only future run snapshots. The current run retains its catalog
revision and may still call an old server tool; server protocol output decides that call. User disable/
remove/definition/policy tightening is revalidated before invocation commitment. Disconnect, timeout, 5xx,
background and retry state never withdraw the LKG. Tool failure is represented by
`ToolExecutionFailure` with only `status + reason + necessary message`: remote `isError` preserves content
and structured content, missing session is `unavailable/server_unavailable`, missing tools capability is
rejected before commitment as `failed/protocol_incompatible`, and post-commit timeout/transport failure is
`unknown/outcome_unknown`. Transport internals and retry flags do not enter Agent context. A received
success remains authoritative even when session/configuration changes afterward.

The independent architecture review first found an admission/dispatch gap, then correctly rejected
an `UNDISPATCHED` follow-up because coroutine entry cannot prove that the first HTTP byte was sent.
The final contract uses an observable irrevocable invocation commitment instead. Typed local
definition/policy mutations and that commitment share one short
`configurationInvocationCommitMutex` order. If the mutation wins, final admission rejects; if the
call wins, it is in-flight and later configuration affects only subsequent calls. Because the SDK
does not expose the exact network-send boundary, any post-commit transport/timeout failure is
conservatively unknown and never replayed. Neither remote I/O nor a server-runtime mutex is held by this gate,
so it does not serialize server connections or tool-call duration. Deterministic tests cover both
orderings, including a call suspended inside the SDK seam before its remote responder runs.

Recovery uses three fast equal-jitter attempts followed by five foreground-only maintenance attempts,
with delays capped at five minutes. Offline/background waits are event-driven and consume no attempt;
exhaustion enters `Error` while retaining the catalog. Tool call, foreground, validated-network event,
single-server retry and user refresh reset recovery. The current MCP Kotlin SDK does not expose
`Retry-After` headers on `StreamableHttpError`; when it does, that server minimum delay belongs in this
same scheduler rather than a second timer.

Targeted tests now include legacy Settings/v3-backup catalog migration, migration-versus-restore ordering, a 20-tool catalog, 20 startup definitions without eager connection, durable
LKG process restart, empty rejection, pagination, manual-refresh receipt and future-snapshot update,
list-change serialization, disconnect/recovery with stable disclosure, remote `isError`, post-commit
unknown outcome, waiter cancellation, definition replacement, duplicate callbacks, OAuth CAS/replacement ordering and UI
mapping. Real-device foreground/background, Wi-Fi/mobile, Doze, OAuth and MCP UI acceptance remain
separate from JVM/build evidence.

After the final independent-review fixes, the complete gate
`gradlew.bat test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` passed in 7m 24s
with 831 actionable tasks. `git diff --check` is run again after the final documentation freeze. This is
build/JVM evidence, not device acceptance.

## Phase F: tests, names, and reference freeze

`SingleWriterContractTest` keeps forbidden-import and single-writer seals.
Recent-conversation summaries are sealed by `ConversationListRecord` having
no message/node fields, not by slicing `getRecentConversationRecords`.
MCP cancellation is sealed by `McpRuntimeCoordinatorTest` (`token refresh cancellation
is not swallowed`), not by searching production source for a rethrow string.
`UpstreamBatch13BoundaryTest` keeps UI/port import bans, locale placeholders,
and the absence of a second terminal preparation mutex; it no longer asserts
constructor field text. `McpConnectionKey.kt` and
`WorkspaceTerminalQueryService.kt` remain physically absent. Current
architecture references describe `ConversationTransition`,
`refreshAllRegisteredServers`, `observeTerminal`, and `ProotLaunchSpec`.
`docs/dev/mcp-lifecycle-analysis.md` records the lifecycle analysis, final owner split and completion gates;
current implementation contracts remain in `docs/references/mcp-architecture.md`.

The original Phase F freeze added no production Kotlin and its recorded full gate passed. The MCP
catalog follow-up has now removed the old Settings schema path, passed its complete gate and completed
independent review, so Phase F is re-frozen on the current implementation. This ledger still does not
treat JVM/build success as device acceptance.

# Application Architecture V2 Execution Ledger

This ledger records evidence required by the V2 execution plan. It is a development record; the
current architectural contract remains in `docs/references/`.

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

## Phase D in progress: request media capabilities and runtime subtraction

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

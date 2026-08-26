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

The V2 boundary does not recreate that repair. `ArtifactStore.reconcileStartup` requires every
managed Settings root to already have an `ACTIVE` Artifact row. A missing row is an integrity
failure: the root and payload are retained for diagnosis, but no metadata is guessed or inserted.
The lifecycle test covers both avatar and background on a sub-assistant record.

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
corruption is encountered after V2, recovery remains fail-closed rather than installing a new
compatibility scanner.

### Current Phase B Debug check

The current-worktree `app-x86_64-debug.apk` was installed on `emulator-5554` and first started
successfully with cleared test-only Debug data. Reinstalling it over the historical test fixture
then displayed the recovery gate for a Settings root without `ACTIVE` Artifact metadata, proving
the current binary does not invoke adoption. At that later check the named payload was no longer
present, so this is evidence for the current fail-closed gate only, not a second proof that a
payload-only missing-metadata state is retained. The historical proof above remains the adoption
branch evidence.

After clearing the synthetic Debug fixture, the same current APK completed the ordinary assistant
avatar picker and crop flow. It created `files/upload/1cf9bb3e-70a1-4072-9edc-adbfae9a1eb5.png`
and its matching `artifact` row (`ACTIVE`, `USER`); force-stop/restart returned to the normal chat
screen. From File Management, the current APK then previewed a user image, selected the default
assistant, and confirmed “set as background.” That transaction created
`upload/9b744313-cd6c-42ed-893b-fd5d558f38da.png`; after force-stop/restart the persisted assistant
still referenced it and each of the three upload rows was `ACTIVE`, `USER`. This proves the current
binary avatar/background ownership and restart paths.

The current APK's chat overflow photo action also copied a selected image into a second upload
payload and displayed its `41.png` attachment chip in the new-chat composer. No model was
configured, so this records the attachment selection/copy presentation path, not a sent-message
preview.

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

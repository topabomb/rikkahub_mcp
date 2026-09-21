# Enterprise defaults and budget refresh plan

## Scope

- Correct the Android usage/budget presentation so an in-flight refresh is not reported as a failed refresh.
- Extend the single managed `Policy` contract from five to ten optional defaults by adding fast, title, attachment-inspection, suggestion, and compaction model references.
- Keep Snapshot schema version 4 and the current database schema. The platform has not been released, so there is no migration, compatibility layer, backfill, or dual-read path.

## Contract and ownership

- Core `ManagedPolicy` remains the only authored and published owner.
- All ten defaults are optional. Missing or cleared fields mean “enterprise default not set”; clients must not choose the first resource implicitly.
- Every non-null model default must reference an enabled model in the same Draft/Snapshot. Attachment inspection additionally requires IMAGE input capability.
- Admin groups primary defaults separately from auxiliary-model defaults while persisting the same typed Policy object.
- Android maps the five new wire fields into the existing `EnterpriseDefaults` slots and validates the reference closure before publishing a candidate.

## Refresh state correction

- A refresh with a last-known-good budget keeps showing that value with a neutral refreshing indicator.
- `stale=true` is set only after the refresh completes with a failure. The original diagnostic remains visible and copyable.
- A later successful refresh clears stale/failure state. Runtime-triggered refresh coalescing remains owned by `EnterpriseVM`.

## Verification

- Contract generation/drift checks and Core validation/compiler tests for all-set, all-unset, disabled/missing, and attachment-without-image cases.
- Admin component and production-browser operation: set all ten defaults, validate/preview/save; clear the five new fields and confirm unset semantics.
- Android mapper/resolver/configuration-details tests for all ten fields plus missing optional fields.
- Android UI test for successful, refreshing, and failed-last-known-good budget states; full JVM/build/lint/release and connected-device gates proportional to the change.


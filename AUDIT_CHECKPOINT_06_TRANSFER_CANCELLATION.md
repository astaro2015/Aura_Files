# Audit checkpoint 06 — Transfer cancellation semantics

## Finding fixed
MEDIUM/HIGH DATA-OPERATION ROBUSTNESS: same-backend MOVE fast path wrapped the suspend `backend.move()` call in `runCatching`. Kotlin `runCatching` catches `CancellationException`, so cancellation during a server-side move could be swallowed and interpreted as “fast move unsupported/failed”, after which TransferCore could fall back to copy+delete instead of stopping.

Fix: explicit try/catch now immediately rethrows `CancellationException`; only ordinary backend failures fall back to copy+delete.

## Reviewed invariants
- Copy uses temporary destination objects and verifies known positive byte counts.
- Move only deletes source after a successful copy result.
- Directory self/descendant moves are rejected.
- Link-like backend entries are not recursively followed by TransferCore.
- Conflict replacement uses backup/rollback mechanics rather than blind overwrite.

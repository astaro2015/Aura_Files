# Audit checkpoint 12B — trash / restore / delete transactional safety

State: completed and fixed.

Focus:
- move to `.AuraTrash`;
- restore / Undo;
- permanent delete / empty trash;
- provider returning an ambiguous result after delete;
- crash between physical move and metadata persistence;
- malicious/accidental `.AuraTrash` filesystem symlink.

Findings and fixes:
1. The old copy-then-delete fallback rolled back the completed trash copy whenever `DocumentFile.delete()` returned false. A provider can be ambiguous after mutation; deleting the safety copy in that situation can theoretically remove the only surviving copy. New rule: rollback only when the source is positively still present. If state is ambiguous, keep the completed trash copy.
2. Restore had the mirror-image risk: completed restored copy could be deleted after an ambiguous trash-source delete. New rule: rollback restored copy only when the trash source is positively still present. Ambiguous state keeps the restored copy and keeps trash metadata, preferring a duplicate over loss.
3. Permanent delete and single delete now use a centralized delete-state check and never blindly chain extra destructive cleanup after an unconfirmed provider result.
4. Empty Trash now commits metadata for successfully deleted entries even if a later entry fails, instead of leaving all metadata stale after a partial pass.
5. Trash metadata is now written synchronously (`SharedPreferences.commit`) because it is the only mapping back to the original location. This reduces the crash window after a physical move.
6. `listTrash()` now recovers physical objects that exist in `.AuraTrash` but have no metadata (e.g. process/power loss after move and before metadata persisted). Recovered entries are surfaced instead of becoming invisible; fallback restore target is the attached root because the original parent cannot be reconstructed.
7. `.AuraTrash` itself is rejected if it resolves to a local filesystem symlink, preventing writes/deletes through a service-folder link.
8. Normal browser entry into a local filesystem symlink directory is blocked; this complements recursive safety from checkpoint 12A.

Safety policy used here:
- when the app can prove the old/source copy still exists, rollback of the new copy is safe;
- when deletion is confirmed, proceed normally;
- when state is ambiguous, retain the completed copy/metadata rather than risk deleting the only surviving bytes.

Validation:
- `git diff --check`: PASS.
- Kotlin parser smoke check for FileRepository.kt: PASS.
- Full Android/Gradle compilation remains deferred to the final stage / Windows builder.

Next checkpoint: rename/move crash recovery, temporary `.aura-*` artifacts, and stale SAF handles.

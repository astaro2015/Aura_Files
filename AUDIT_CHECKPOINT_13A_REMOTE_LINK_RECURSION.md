# Audit checkpoint 13A — remote symlink / recursion safety

State: completed and fixed.

Focus:
- SFTP symlink classification without following the target;
- FTP symbolic-link classification;
- recursive copy/delete behavior through remote links;
- pathological remote directory depth;
- cancellation of same-backend fast MOVE.

Findings and fixes:
1. SFTP `statBlocking()` used `statExistence()`, which follows symbolic links. A symlink to a directory could therefore be represented as an ordinary directory and recursively traversed by transfer/delete. SFTP listing/stat now classify links from link attributes and use `lstat()` through a NO_SUCH_FILE-aware helper.
2. SFTP create/open collision checks now use non-following lstat semantics, so a dangling link is not mistaken for an absent path.
3. FTP `StorageItem` now carries `FTPFile.isSymbolicLink`. `stat()` resolves the object from its parent directory listing instead of listing the object path itself, avoiding servers that follow a directory symlink for LIST.
4. Remote link objects remain non-directory/link-like to the transfer core. Recursive delete unlinks the link itself rather than descending into its target.
5. `BackendTransferCore` now has a hard remote tree depth ceiling (256) in measure/copy/count paths, limiting stack/pathological-server recursion even if a backend misclassifies a cycle.
6. Same-backend fast MOVE explicitly rethrows `CancellationException`; cancellation can no longer silently fall back to copy+delete.

Validation:
- `git diff --check`: PASS.
- SSHJ 0.40.0 API verified: `SFTPClient.lstat()` exists and is non-following; missing-file detection uses `SFTPException.statusCode`.
- Apache Commons Net API verified: `FTPFile.isSymbolicLink()` exists.

Next: 13B — remote temp/backup transactional cleanup, ambiguous rename/delete outcomes, stale connections.

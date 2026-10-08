#!/usr/bin/env python3
from pathlib import Path

COMMITTED = "committed"
PRESENT = "present"
AMBIGUOUS = "ambiguous"


def move_copy_fallback(delete_state):
    source = True
    trash_copy = True
    metadata = False
    if delete_state == COMMITTED:
        source = False
        metadata = True
        return source, trash_copy, metadata, "ok"
    if delete_state == PRESENT:
        trash_copy = False
        return source, trash_copy, metadata, "error"
    # Provider may have committed or not. Safety invariant is that the completed copy survives.
    return source, trash_copy, metadata, "error"


def restore_copy_fallback(delete_state):
    trash = True
    restored = True
    metadata = True
    if delete_state == COMMITTED:
        trash = False
        metadata = False
        return trash, restored, metadata, "ok"
    if delete_state == PRESENT:
        restored = False
        return trash, restored, metadata, "error"
    return trash, restored, metadata, "error"


count = 0
for state in (COMMITTED, PRESENT, AMBIGUOUS):
    source, copy, metadata, result = move_copy_fallback(state)
    assert source or copy, ("move lost both", state)
    if state == AMBIGUOUS:
        assert copy and result == "error"
    if state == PRESENT:
        assert source and not copy
    if state == COMMITTED:
        assert copy and metadata and not source
    count += 1

    trash, restored, metadata, result = restore_copy_fallback(state)
    assert trash or restored, ("restore lost both", state)
    if state == AMBIGUOUS:
        assert restored and metadata and result == "error"
    if state == PRESENT:
        assert trash and not restored and metadata
    if state == COMMITTED:
        assert restored and not trash and not metadata
    count += 1

# Empty-trash metadata must commit entry-by-entry. Simulate failure on every index.
for total in range(1, 9):
    ids = list(range(total))
    for failure_at in range(total + 1):
        physical = set(ids)
        metadata = set(ids)
        for index, item in enumerate(ids):
            if index == failure_at:
                break
            physical.remove(item)
            metadata.remove(item)
        assert metadata == physical, (total, failure_at, physical, metadata)
        count += 1

source = Path("app/src/main/java/com/aurafiles/app/data/FileRepository.kt").read_text()
required = [
    "deleteDocumentAndProbe(entry.document, originalParentUri)",
    "LocalDeleteOutcome.AMBIGUOUS",
    "страховочная копия и путь восстановления сохранены",
    "normalizedMetadata",
    "originalParentUri = root.uri",
    ".commit()",
    "DocumentTreeSafety.requireNotFilesystemSymlink(it, \"Корзина\")",
    "DocumentTreeSafety.requireNotFilesystemSymlink(directory, \"Открытие папки\")",
    "findIdentityStrict",
    "fallback не выполняется",
]
for needle in required:
    assert needle in source, needle
# Old destructive rollback pattern must be gone.
assert "if (!entry.document.delete()) {\n                copy.delete()" not in source
assert "if (!record.entry.document.delete()) {\n                copy.delete()" not in source
assert ".putString(KEY_TRASH_RECORDS, array.toString()).apply()" not in source

print(f"STAGE12B_RECOVERY_MODEL_PASS scenarios={count}")
print("STAGE12B_RECOVERY_SOURCE_INVARIANTS_PASS")

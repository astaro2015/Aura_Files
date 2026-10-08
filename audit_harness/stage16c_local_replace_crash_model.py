#!/usr/bin/env python3
from dataclasses import dataclass
from pathlib import Path

PHASES = [
    "PREPARED", "BACKUP_PENDING", "BACKUP_DONE", "FINAL_PENDING",
    "FINAL_DONE", "CLEANUP_PENDING", "ROLLBACK_PENDING",
]

@dataclass
class State:
    final: str | None = "old"
    temp: str | None = "new"
    backup: str | None = None
    phase: str = "PREPARED"
    journal: bool = True


def recover(s: State) -> State:
    if not s.journal:
        return s
    # Matches production recovery branches in TransferEngine.
    if s.final is not None and s.temp is None and s.backup is None:
        s.journal = False
        return s
    if s.final is None and s.backup is not None:
        s.final, s.backup = s.backup, None
        s.journal = False
        return s
    if s.final is not None and s.temp is None and s.backup is not None:
        if s.phase in ("FINAL_DONE", "CLEANUP_PENDING"):
            s.backup = None
        # If final commit happened before its durable phase marker, preserve old backup.
        s.journal = False
        return s
    if s.final is not None and s.temp is not None and s.backup is None:
        s.journal = False
        return s
    raise RuntimeError("ambiguous-safe-stop")


def assert_safe(s: State, label: str):
    # old/new bytes can be in final/temp/backup, but at least one complete version must survive.
    values = {x for x in (s.final, s.temp, s.backup) if x}
    assert values & {"old", "new"}, (label, s)


def forward_states():
    s = State()
    yield "journal-created", State(**s.__dict__)
    s.phase = "BACKUP_PENDING"; yield "backup-phase", State(**s.__dict__)
    s.backup, s.final = s.final, None; yield "after-old-to-backup", State(**s.__dict__)
    s.phase = "BACKUP_DONE"; yield "backup-done-phase", State(**s.__dict__)
    s.phase = "FINAL_PENDING"; yield "final-pending-phase", State(**s.__dict__)
    s.final, s.temp = s.temp, None; yield "after-temp-to-final", State(**s.__dict__)
    s.phase = "FINAL_DONE"; yield "final-done-phase", State(**s.__dict__)
    s.phase = "CLEANUP_PENDING"; yield "cleanup-pending-phase", State(**s.__dict__)
    s.backup = None; yield "after-backup-delete", State(**s.__dict__)
    s.journal = False; yield "journal-cleared", State(**s.__dict__)

count = 0
for label, crashed in forward_states():
    assert_safe(crashed, label)
    recovered = recover(crashed)
    assert_safe(recovered, label + ":recovered")
    if recovered.journal is False:
        # Every ordinary crash boundary must regain a visible final name.
        assert recovered.final in ("old", "new"), (label, recovered)
    count += 1

# Lost ACK after old->backup: phase still BACKUP_PENDING, physical mutation committed.
s = State(final=None, temp="new", backup="old", phase="BACKUP_PENDING")
r = recover(s); assert r.final == "old" and r.temp == "new" and not r.journal; count += 1

# Lost ACK after temp->final: phase still FINAL_PENDING. Keep old backup instead of guessing/deleting.
s = State(final="new", temp=None, backup="old", phase="FINAL_PENDING")
r = recover(s); assert r.final == "new" and r.backup == "old" and not r.journal; count += 1

# Lost ACK after backup cleanup: durable FINAL_DONE/CLEANUP phase, physical backup already absent.
for phase in ("FINAL_DONE", "CLEANUP_PENDING"):
    s = State(final="new", temp=None, backup=None, phase=phase)
    r = recover(s); assert r.final == "new" and not r.journal; count += 1

# Failed final rename followed by rollback. Crash both before and after rollback mutation.
s = State(final=None, temp="new", backup="old", phase="ROLLBACK_PENDING")
r = recover(s); assert r.final == "old" and r.temp == "new" and not r.journal; count += 1
s = State(final="old", temp="new", backup=None, phase="ROLLBACK_PENDING")
r = recover(s); assert r.final == "old" and r.temp == "new" and not r.journal; count += 1

# Truly contradictory state must stop without deleting anything.
s = State(final="other", temp="new", backup="old", phase="BACKUP_PENDING")
try:
    recover(s)
except RuntimeError:
    assert s.final == "other" and s.temp == "new" and s.backup == "old" and s.journal
    count += 1
else:
    raise AssertionError("ambiguous state should stop")

engine = Path("app/src/main/java/com/aurafiles/app/transfer/TransferEngine.kt").read_text()
store = Path("app/src/main/java/com/aurafiles/app/transfer/LocalReplaceJournalStore.kt").read_text()
vm = Path("app/src/main/java/com/aurafiles/app/ui/FileManagerViewModel.kt").read_text()
for needle in [
    "localReplaceJournalStore.create(",
    "LocalReplacePhase.BACKUP_PENDING",
    "LocalReplacePhase.FINAL_PENDING",
    "LocalReplacePhase.FINAL_DONE",
    "LocalReplacePhase.CLEANUP_PENDING",
    "LocalReplacePhase.ROLLBACK_PENDING",
    "recoverPendingLocalTransactions()",
    "synchronized(LOCAL_REPLACE_TRANSACTION_LOCK)",
    "Aura ничего не удаляет и оставляет журнал",
]:
    assert needle in engine, needle
for needle in ["AtomicFile", "output.fd.sync()", "appContext.noBackupFilesDir"]:
    haystack = store if needle != "appContext.noBackupFilesDir" else engine
    assert needle in haystack, needle
assert "transferEngine.recoverPendingLocalTransactions()" in vm

print(f"STAGE16C_LOCAL_REPLACE_CRASH_MODEL_PASS scenarios={count}")
print("STAGE16C_SOURCE_INVARIANTS_PASS")

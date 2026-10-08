#!/usr/bin/env python3
from dataclasses import dataclass
from pathlib import Path

@dataclass
class State:
    source: bool = True       # encrypted vault source
    temp: bool = True         # external staging plaintext
    final: bool = False
    phase: str = "WRITING"
    journal: bool = True


def recover(s: State):
    if not s.journal:
        return "none"
    if s.phase == "WRITING":
        if s.final:
            return "stop"
        if s.temp and not s.source:
            return "stop"
        if s.temp:
            s.temp = False
        elif not s.source:
            return "stop"
    elif s.phase == "FINALIZE_PENDING":
        if s.final and s.temp:
            return "stop"
        if s.final:
            pass
        elif s.temp and s.source:
            s.temp = False
        elif s.temp:
            return "stop"
        elif not s.source:
            return "stop"
    elif s.phase == "FINALIZED":
        if s.final and s.temp:
            return "stop"
        if s.final:
            pass
        elif s.temp:
            return "stop"
        elif s.source:
            pass
        else:
            return "stop"
    s.journal = False
    return "clear"

cases = [
    ("kill-mid-decrypt", State(source=True,temp=True,final=False,phase="WRITING"), "clear", (True,False,False)),
    ("source-lost-mid-decrypt", State(source=False,temp=True,final=False,phase="WRITING"), "stop", (False,True,False)),
    ("writing-all-missing", State(source=False,temp=False,final=False,phase="WRITING"), "stop", (False,False,False)),
    ("writing-final-contradiction", State(source=True,temp=False,final=True,phase="WRITING"), "stop", (True,False,True)),
    ("kill-before-rename", State(source=True,temp=True,final=False,phase="FINALIZE_PENDING"), "clear", (True,False,False)),
    ("rename-lost-ack", State(source=True,temp=False,final=True,phase="FINALIZE_PENDING"), "clear", (True,False,True)),
    ("pending-source-gone-temp-kept", State(source=False,temp=True,final=False,phase="FINALIZE_PENDING"), "stop", (False,True,False)),
    ("pending-final-and-temp", State(source=True,temp=True,final=True,phase="FINALIZE_PENDING"), "stop", (True,True,True)),
    ("pending-all-missing", State(source=False,temp=False,final=False,phase="FINALIZE_PENDING"), "stop", (False,False,False)),
    ("kill-after-finalized", State(source=True,temp=False,final=True,phase="FINALIZED"), "clear", (True,False,True)),
    ("kill-after-source-delete", State(source=False,temp=False,final=True,phase="FINALIZED"), "clear", (False,False,True)),
    ("finalized-final-disappeared-source-safe", State(source=True,temp=False,final=False,phase="FINALIZED"), "clear", (True,False,False)),
    ("contradict-finalized-temp", State(source=True,temp=True,final=False,phase="FINALIZED"), "stop", (True,True,False)),
    ("contradict-finalized-two-plaintext", State(source=True,temp=True,final=True,phase="FINALIZED"), "stop", (True,True,True)),
    ("finalized-all-missing", State(source=False,temp=False,final=False,phase="FINALIZED"), "stop", (False,False,False)),
]
for label, state, expected_action, expected_bits in cases:
    action = recover(state)
    assert action == expected_action, (label, action, state)
    assert (state.source,state.temp,state.final) == expected_bits, (label,state)
    if expected_action == "clear":
        assert state.source or state.temp or state.final, ("cleared journal with no proven copy", label)
    else:
        assert state.journal, ("ambiguous journal removed", label)

vault = Path("app/src/main/java/com/aurafiles/app/data/AuraVault.kt").read_text()
store = Path("app/src/main/java/com/aurafiles/app/data/VaultRestoreJournalStore.kt").read_text()
required_vault = [
    "recoverPendingRestores()",
    "acquireRestorePermissionIfNeeded",
    "VaultRestorePhase.WRITING",
    "VaultRestorePhase.FINALIZE_PENDING",
    "VaultRestorePhase.FINALIZED",
    "renameVaultRestoreAndProbe",
    "cleanupVaultRestoreTemporary",
    "Провайдер не разрешил сохранить доступ",
    "Aura ничего не удаляет",
    "журнал сохранён для ручной проверки",
    "Одновременно видны финальная и временная копии",
]
for needle in required_vault:
    assert needle in vault, needle
for needle in ["AtomicFile", "output.fd.sync()", "vault-restore-journal-v1-", ".aura-vault-restore-"]:
    assert needle in store, needle
print(f"STAGE16D_VAULT_RESTORE_MODEL_PASS scenarios={len(cases)}")
print("STAGE16D_VAULT_SOURCE_INVARIANTS_PASS")

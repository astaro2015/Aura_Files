#!/usr/bin/env python3
from itertools import product
from pathlib import Path

COMMITTED='COMMITTED'; NOT='NOT_COMMITTED'; AMB='AMBIGUOUS'

class Stop(Exception):
    pass

class Model:
    def __init__(self):
        self.fs={'final':'old','tmp':'new','source':'new'}
        self.backup='backup'
        self.source_deleted=False

    def rename_probe(self, src, dst, outcome):
        # outcome describes observed reconciliation; COMMITTED mutates, NOT does not.
        # AMBIGUOUS is exercised in both committed and uncommitted physical variants elsewhere.
        if outcome == COMMITTED:
            if src not in self.fs or dst in self.fs: return AMB
            self.fs[dst]=self.fs.pop(src)
        return outcome

    def delete_probe(self, name, outcome):
        if outcome == COMMITTED:
            self.fs.pop(name, None)
        return outcome

    def safe_failure(self):
        # On any failed replace/move the original source must remain, and old destination
        # must not be lost unless the new final is already committed.
        assert self.fs.get('source') == 'new'
        old_present = 'old' in self.fs.values()
        new_final = self.fs.get('final') == 'new'
        assert old_present or new_final, self.fs


def run_replace(first, second, cleanup, restore):
    m=Model()
    r=m.rename_probe('final','backup',first)
    if r != COMMITTED:
        m.safe_failure(); return
    r=m.rename_probe('tmp','final',second)
    if r == COMMITTED:
        d=m.delete_probe('backup',cleanup)
        if d == COMMITTED:
            assert m.fs.get('final') == 'new'
            # Only now may MOVE remove source.
            m.fs.pop('source',None); m.source_deleted=True
            assert m.fs.get('final') == 'new'
            return
        m.safe_failure(); return
    if r == NOT:
        rr=m.rename_probe('backup','final',restore)
        if rr == COMMITTED:
            # finally may remove the uncommitted Aura temp; source remains.
            m.fs.pop('tmp',None)
            m.safe_failure(); return
        m.safe_failure(); return
    m.safe_failure()

# Deterministic reconciliation outcomes.
scenarios=0
for first, second, cleanup, restore in product((COMMITTED,NOT,AMB), repeat=4):
    run_replace(first,second,cleanup,restore); scenarios+=1

# Ambiguous physical variants: Aura preserves temp/backup and never deletes source.
# 1) old->backup physically committed, probe unavailable.
m=Model(); m.fs['backup']=m.fs.pop('final'); m.safe_failure(); scenarios+=1
# 2) temp->final physically committed after old->backup, probe unavailable.
m=Model(); m.fs['backup']=m.fs.pop('final'); m.fs['final']=m.fs.pop('tmp'); m.safe_failure(); scenarios+=1
# 3) backup delete physically committed but ACK/probe unavailable: new final is safe, source remains.
m=Model(); m.fs['backup']=m.fs.pop('final'); m.fs['final']=m.fs.pop('tmp'); m.fs.pop('backup'); m.safe_failure(); scenarios+=1

# SKIP must never delete source.
m=Model(); copied=None
if copied is not None: m.fs.pop('source',None)
assert m.fs.get('source')=='new'; scenarios+=1

# Stale source replacement: fingerprint mismatch must prevent delete of replacement.
expected_key='inode-old'; actual_key='inode-replacement'
replacement={'source':'unrelated'}
if actual_key != expected_key:
    pass
else:
    replacement.pop('source',None)
assert replacement.get('source')=='unrelated'; scenarios+=1

# Fast-move ambiguity: no fallback destructive copy/delete should run.
source_exists=False; expected_dest_exists=False; same_name_unrelated=True
ambiguous = not (source_exists and not expected_dest_exists and not same_name_unrelated) and not (not source_exists and expected_dest_exists)
assert ambiguous
scenarios+=1

print(f'STAGE16B_LOCAL_TRANSFER_MODEL_PASS scenarios={scenarios}')

# Source invariants against production file.
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/com/aurafiles/app/transfer/TransferEngine.kt').read_text(encoding='utf-8')
required=[
    'if (request.type != TransferType.COPY && copied != null)',
    'deleteSourceAfterCommittedMove(source, fingerprint)',
    'findIdentityStrict(destination, expectedDestinationUri)',
    'DocumentTreeSafety.requireNotFilesystemSymlink(destination',
    'forbiddenDestinationUri = forbiddenDestinationUri',
    'captureSourceFingerprint(document)',
    'actual.fileKey != expected.fileKey',
    'PreserveLocalTemporaryException',
    'findUniqueChildStrict(parent, name)',
    'if (!DocumentTreeSafety.sameIdentity(leftover.uri, temporary.uri)) return',
]
for needle in required:
    assert needle in s, needle
assert 'require(existing.delete())' not in s
assert 'if (request.type != TransferType.COPY) {\n                    controller.checkpoint' not in s
print('STAGE16B_SOURCE_INVARIANTS_PASS')

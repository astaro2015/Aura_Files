from dataclasses import dataclass
from enum import Enum

class Phase(Enum):
    PREPARE='PREPARE'
    FINALIZE='FINALIZE'
    ROLLBACK_STAGE='ROLLBACK_STAGE'
    ROLLBACK_RESTORE='ROLLBACK_RESTORE'

@dataclass(frozen=True)
class E:
    ident: str
    original: str
    temp: str
    rollback: str
    target: str

@dataclass
class J:
    phase: Phase
    entries: list[E]

class FS:
    def __init__(self, entries):
        self.by_name={e.original:e.ident for e in entries}
        self.steps=0
    def clone(self):
        x=object.__new__(FS); x.by_name=dict(self.by_name); x.steps=self.steps; return x
    def rename(self, src,dst, crash_after=None, lost_ack=False):
        assert src in self.by_name, (src,self.by_name)
        assert dst not in self.by_name, (dst,self.by_name)
        ident=self.by_name.pop(src); self.by_name[dst]=ident; self.steps+=1
        if crash_after is not None and self.steps==crash_after:
            raise Crash()
        if lost_ack:
            return False
        return True
    def names_for(self, ident):
        return [n for n,i in self.by_name.items() if i==ident]

class Crash(Exception): pass

def locate(fs,e,allowed):
    hits=[n for n in allowed if fs.by_name.get(n)==e.ident]
    if len(hits)!=1:
        raise AssertionError(('ambiguous', e.ident, hits, fs.by_name))
    return hits[0]

def rename_reconcile(fs, src,dst, crash_after=None, lost_ack=False):
    # Production behaviour: if provider says false after commit, inspect and accept commit.
    try:
        ok=fs.rename(src,dst,crash_after=crash_after,lost_ack=lost_ack)
    except Crash:
        raise
    if ok:
        return
    source_after = src in fs.by_name
    target_after = dst in fs.by_name
    if not source_after and target_after:
        return
    raise AssertionError(('rename failed unexpectedly',src,dst,fs.by_name))

def rollback_prepare(fs,j,crash_after=None):
    for e in j.entries:
        cur=locate(fs,e,{e.original,e.temp})
        if cur==e.temp:
            rename_reconcile(fs,e.temp,e.original,crash_after)
    assert_original(fs,j)
    return None

def complete_finalize(fs,j,crash_after=None):
    for e in j.entries:
        cur=locate(fs,e,{e.temp,e.target})
        if cur==e.temp:
            rename_reconcile(fs,e.temp,e.target,crash_after)
    assert_final(fs,j)
    return None

def rollback_finalizing(fs,j,crash_after=None, crash_phase_switch=False):
    j.phase=Phase.ROLLBACK_STAGE
    for e in j.entries:
        cur=locate(fs,e,{e.temp,e.target,e.rollback})
        if cur!=e.rollback:
            rename_reconcile(fs,cur,e.rollback,crash_after)
    if crash_phase_switch:
        raise Crash()
    j.phase=Phase.ROLLBACK_RESTORE
    for e in j.entries:
        cur=locate(fs,e,{e.rollback,e.original})
        if cur==e.rollback:
            rename_reconcile(fs,e.rollback,e.original,crash_after)
    assert_original(fs,j)
    return None

def recover(fs,j,crash_after=None):
    if j.phase is Phase.PREPARE:
        return rollback_prepare(fs,j,crash_after)
    if j.phase is Phase.FINALIZE:
        return complete_finalize(fs,j,crash_after)
    if j.phase is Phase.ROLLBACK_STAGE:
        # Resume stage then switch durably to restore.
        for e in j.entries:
            cur=locate(fs,e,{e.temp,e.target,e.rollback})
            if cur!=e.rollback: rename_reconcile(fs,cur,e.rollback,crash_after)
        j.phase=Phase.ROLLBACK_RESTORE
        return recover(fs,j,crash_after)
    if j.phase is Phase.ROLLBACK_RESTORE:
        for e in j.entries:
            cur=locate(fs,e,{e.rollback,e.original})
            if cur==e.rollback: rename_reconcile(fs,e.rollback,e.original,crash_after)
        assert_original(fs,j)
        return None

def assert_ids_once(fs,j):
    ids=list(fs.by_name.values())
    assert len(ids)==len(j.entries), fs.by_name
    assert len(set(ids))==len(ids), fs.by_name
    assert set(ids)=={e.ident for e in j.entries}

def assert_original(fs,j):
    assert_ids_once(fs,j)
    for e in j.entries:
        assert fs.by_name.get(e.original)==e.ident, (e,fs.by_name)
    assert not any(n.startswith('.aura-') for n in fs.by_name)

def assert_final(fs,j):
    assert_ids_once(fs,j)
    for e in j.entries:
        assert fs.by_name.get(e.target)==e.ident, (e,fs.by_name)
    assert not any(n.startswith('.aura-') for n in fs.by_name)

def make_cycle(n):
    originals=[f'f{i}.txt' for i in range(n)]
    # item i takes item i+1's original name => full cycle
    targets=originals[1:]+originals[:1]
    return [E(f'id{i}', originals[i], f'.aura-rename-{i}', f'.aura-rollback-{i}', targets[i]) for i in range(n)]

def restart_until_stable(fs,j,max_restarts=100):
    # Normal recovery itself is idempotent; no injected crash here.
    recover(fs,j)
    assert_ids_once(fs,j)

def test_main_crash_matrix():
    cases=0
    for n in range(2,8):
        entries=make_cycle(n)
        # Crash before first rename and after every PREPARE rename.
        for cut in range(0,n+1):
            fs=FS(entries); j=J(Phase.PREPARE,entries)
            if cut:
                try:
                    for e in entries:
                        rename_reconcile(fs,e.original,e.temp,crash_after=cut)
                except Crash:
                    pass
            recover(fs,j)
            assert_original(fs,j); cases+=1
        # Crash after FINALIZE phase is durable, before or after each final rename.
        for cut_final in range(0,n+1):
            fs=FS(entries); j=J(Phase.PREPARE,entries)
            for e in entries: rename_reconcile(fs,e.original,e.temp)
            j.phase=Phase.FINALIZE
            if cut_final:
                base=fs.steps
                try:
                    for e in entries:
                        rename_reconcile(fs,e.temp,e.target,crash_after=base+cut_final)
                except Crash:
                    pass
            recover(fs,j)
            assert_final(fs,j); cases+=1
    return cases

def test_rollback_crash_matrix():
    cases=0
    for n in range(2,8):
        entries=make_cycle(n)
        # Enter a representative partial FINALIZE state with floor(n/2) committed targets.
        basefs=FS(entries); basej=J(Phase.PREPARE,entries)
        for e in entries: rename_reconcile(basefs,e.original,e.temp)
        basej.phase=Phase.FINALIZE
        for e in entries[:max(1,n//2)]: rename_reconcile(basefs,e.temp,e.target)
        # First calculate number of rollback renames: n stage + n restore.
        total=2*n
        for relcut in range(1,total+1):
            fs=basefs.clone(); j=J(Phase.ROLLBACK_STAGE,list(entries)); base=fs.steps
            crashed=False
            try:
                # Resume exact production rollback-stage semantics.
                for e in entries:
                    cur=locate(fs,e,{e.temp,e.target,e.rollback})
                    if cur!=e.rollback: rename_reconcile(fs,cur,e.rollback,crash_after=base+relcut)
                j.phase=Phase.ROLLBACK_RESTORE
                for e in entries:
                    cur=locate(fs,e,{e.rollback,e.original})
                    if cur==e.rollback: rename_reconcile(fs,e.rollback,e.original,crash_after=base+relcut)
            except Crash:
                crashed=True
            if crashed:
                # Journal phase at a real crash: if stage completed, phase switch would have been durable
                # before restore. Infer that from whether every identity is staged.
                if all(fs.by_name.get(e.rollback)==e.ident for e in entries):
                    j.phase=Phase.ROLLBACK_RESTORE
                recover(fs,j)
            assert_original(fs,j); cases+=1
    return cases

def test_phase_boundaries_and_mixed():
    cases=0
    for n in range(2,8):
        entries=make_cycle(n)
        # Crash after every item is staged but before ROLLBACK_RESTORE is persisted.
        fs=FS(entries); j=J(Phase.PREPARE,entries)
        for e in entries: rename_reconcile(fs,e.original,e.temp)
        j.phase=Phase.FINALIZE
        for e in entries[:max(1,n//2)]: rename_reconcile(fs,e.temp,e.target)
        j.phase=Phase.ROLLBACK_STAGE
        for e in entries:
            cur=locate(fs,e,{e.temp,e.target,e.rollback})
            if cur!=e.rollback: rename_reconcile(fs,cur,e.rollback)
        recover(fs,j); assert_original(fs,j); cases+=1

        # Same physical state, but phase switch was persisted before the crash.
        fs=FS(entries); j=J(Phase.PREPARE,entries)
        for e in entries: rename_reconcile(fs,e.original,e.temp)
        j.phase=Phase.FINALIZE
        for e in entries[:max(1,n//2)]: rename_reconcile(fs,e.temp,e.target)
        j.phase=Phase.ROLLBACK_STAGE
        for e in entries:
            cur=locate(fs,e,{e.temp,e.target,e.rollback})
            if cur!=e.rollback: rename_reconcile(fs,cur,e.rollback)
        j.phase=Phase.ROLLBACK_RESTORE
        recover(fs,j); assert_original(fs,j); cases+=1

    # No-op names and a cycle + no-op member must still preserve identity.
    entries=[E('a','A.txt','.aura-rename-a','.aura-rollback-a','A.txt'),
             E('b','B.txt','.aura-rename-b','.aura-rollback-b','B.txt')]
    fs=FS(entries); j=J(Phase.PREPARE,entries)
    for e in entries: rename_reconcile(fs,e.original,e.temp)
    j.phase=Phase.FINALIZE; recover(fs,j); assert_final(fs,j); cases+=1

    entries=[E('a','A.txt','.aura-rename-a','.aura-rollback-a','B.txt'),
             E('b','B.txt','.aura-rename-b','.aura-rollback-b','A.txt'),
             E('c','C.txt','.aura-rename-c','.aura-rollback-c','C.txt')]
    fs=FS(entries); j=J(Phase.PREPARE,entries)
    for e in entries: rename_reconcile(fs,e.original,e.temp)
    j.phase=Phase.FINALIZE; recover(fs,j); assert_final(fs,j); cases+=1
    return cases

def test_lost_ack():
    entries=make_cycle(3); fs=FS(entries); j=J(Phase.PREPARE,entries)
    # Each rename commits but reports false; reconciliation must never replay.
    for e in entries: rename_reconcile(fs,e.original,e.temp,lost_ack=True)
    j.phase=Phase.FINALIZE
    for e in entries: rename_reconcile(fs,e.temp,e.target,lost_ack=True)
    assert_final(fs,j)
    return 6

if __name__=='__main__':
    a=test_main_crash_matrix()
    b=test_rollback_crash_matrix()
    c=test_lost_ack()
    d=test_phase_boundaries_and_mixed()
    print(f'BATCH_RENAME_12C_CRASH_MATRIX_PASS main={a} rollback={b} lost_ack_steps={c} boundaries_mixed={d} total={a+b+c+d}')

package com.aurafiles.app.data

import java.util.concurrent.CancellationException

private class ProcessCrash : Error("simulated process death")

private class FakeNameStore(
    private val items: List<BatchRenameJournalItem>,
) : BatchRenameNameStore {
    private val names = linkedMapOf<Pair<String, String>, String>()
    var renameCount: Int = 0
    var crashAfterRename: Int? = null
    var commitButReportFalse: Boolean = false
    var cancelOnRename: Int? = null
    private var crashOnProbe = false

    init {
        items.forEach { item -> names[item.parentKey to item.originalName] = item.originalUri }
    }

    fun setState(resolveName: (BatchRenameJournalItem) -> String) {
        names.clear()
        items.forEach { item ->
            val key = item.parentKey to resolveName(item)
            check(names.put(key, item.originalUri) == null) { "duplicate simulated name: $key" }
        }
    }

    override fun exists(parentKey: String, name: String): Boolean {
        if (crashOnProbe) {
            crashOnProbe = false
            throw ProcessCrash()
        }
        return names.containsKey(parentKey to name)
    }

    override fun rename(parentKey: String, fromName: String, toName: String): Boolean {
        val next = renameCount + 1
        if (cancelOnRename == next) throw CancellationException("simulated cancel")
        val from = parentKey to fromName
        val to = parentKey to toName
        val id = names[from] ?: return false
        if (to in names) return false
        names.remove(from)
        names[to] = id
        renameCount = next
        if (crashAfterRename == next) crashOnProbe = true
        return !commitButReportFalse
    }

    fun assertFinal() {
        items.forEach { item -> check(names[item.parentKey to item.targetName] == item.originalUri) }
        assertIdentitySet()
        check(names.keys.none { (_, name) -> name.startsWith(".aura-") })
    }

    fun assertOriginal() {
        items.forEach { item -> check(names[item.parentKey to item.originalName] == item.originalUri) }
        assertIdentitySet()
        check(names.keys.none { (_, name) -> name.startsWith(".aura-") })
    }

    private fun assertIdentitySet() {
        check(names.size == items.size) { "unexpected name count: $names" }
        check(names.values.toSet() == items.map { it.originalUri }.toSet()) { "identity loss/duplication: $names" }
    }
}

private fun cycleItems(size: Int): List<BatchRenameJournalItem> {
    val originals = List(size) { "f$it.txt" }
    val targets = originals.drop(1) + originals.first()
    return List(size) { index ->
        BatchRenameJournalItem(
            parentKey = "parent",
            originalUri = "id$index",
            originalName = originals[index],
            temporaryName = ".aura-rename-$index",
            targetName = targets[index],
            rollbackName = ".aura-rollback-$index",
        )
    }
}

private fun initialJournal(items: List<BatchRenameJournalItem>) = BatchRenameJournal(
    operationId = "op",
    phase = BatchRenamePhase.PREPARE,
    step = 0,
    items = items,
)

private fun runForwardCrashMatrix(): Int {
    var cases = 0
    for (size in 2..7) {
        val items = cycleItems(size)
        for (crashRename in 1..(size * 2)) {
            val store = FakeNameStore(items).apply { crashAfterRename = crashRename }
            var durable = initialJournal(items)
            val persist: (BatchRenameJournal) -> Unit = { durable = it }
            try {
                BatchRenameJournalEngine.continueForward(durable, store, persist)
                error("crash $crashRename did not fire")
            } catch (_: ProcessCrash) {
                // Simulated process restart: remove crash injection and continue from durable state.
            }
            store.crashAfterRename = null
            val recovered = BatchRenameJournalEngine.continueForward(durable, store, persist)
            check(recovered.phase == BatchRenamePhase.FINALIZE && recovered.step == items.size)
            store.assertFinal()
            cases++
        }
    }
    return cases
}

private fun runRollbackCrashMatrix(): Int {
    var cases = 0
    for (size in 2..7) {
        val items = cycleItems(size)
        val finalized = maxOf(1, size / 2)
        for (crashRename in 1..(size * 2)) {
            val store = FakeNameStore(items)
            // State corresponding to FINALIZE/step=finalized: first entries are at targets,
            // the rest are still at their unique temporary names.
            val indexById = items.mapIndexed { index, item -> item.originalUri to index }.toMap()
            store.setState { item ->
                if (requireNotNull(indexById[item.originalUri]) < finalized) item.targetName else item.temporaryName
            }
            var durable = BatchRenameJournal(
                operationId = "op",
                phase = BatchRenamePhase.FINALIZE,
                step = finalized,
                items = items,
            )
            val persist: (BatchRenameJournal) -> Unit = { durable = it }
            durable = BatchRenameJournalEngine.beginRollback(durable, persist)
            store.crashAfterRename = crashRename
            try {
                BatchRenameJournalEngine.continueRollback(durable, store, persist)
                error("rollback crash $crashRename did not fire")
            } catch (_: ProcessCrash) {
                // Restart from the last fsync'd journal state.
            }
            store.crashAfterRename = null
            val recovered = BatchRenameJournalEngine.continueRollback(durable, store, persist)
            check(recovered.phase == BatchRenamePhase.ROLLBACK_RESTORE && recovered.step == items.size)
            store.assertOriginal()
            cases++
        }
    }
    return cases
}

private fun runLostAckMatrix(): Int {
    var cases = 0
    for (size in 2..7) {
        val items = cycleItems(size)
        val store = FakeNameStore(items).apply { commitButReportFalse = true }
        var durable = initialJournal(items)
        val final = BatchRenameJournalEngine.continueForward(durable, store) { durable = it }
        check(final.phase == BatchRenamePhase.FINALIZE && final.step == items.size)
        store.assertFinal()
        cases++
    }
    return cases
}

private fun runCancellationCheck(): Int {
    val items = cycleItems(3)
    val store = FakeNameStore(items).apply { cancelOnRename = 1 }
    var durable = initialJournal(items)
    try {
        BatchRenameJournalEngine.continueForward(durable, store) { durable = it }
        error("cancellation was swallowed")
    } catch (_: CancellationException) {
        check(durable.phase == BatchRenamePhase.PREPARE && durable.step == 0)
        store.assertOriginal()
    }
    return 1
}

private fun runBoundaryChecks(): Int {
    var cases = 0
    for (size in 2..7) {
        val items = cycleItems(size)
        // PREPARE already fully committed but phase transition was not persisted.
        val storePrepareBoundary = FakeNameStore(items)
        storePrepareBoundary.setState { it.temporaryName }
        var durable = initialJournal(items).copy(step = items.size)
        val final1 = BatchRenameJournalEngine.continueForward(durable, storePrepareBoundary) { durable = it }
        check(final1.phase == BatchRenamePhase.FINALIZE && final1.step == items.size)
        storePrepareBoundary.assertFinal()
        cases++

        // ROLLBACK_STAGE fully committed but ROLLBACK_RESTORE transition not persisted.
        val storeRollbackBoundary = FakeNameStore(items)
        storeRollbackBoundary.setState { it.rollbackName }
        durable = BatchRenameJournal(
            operationId = "op",
            phase = BatchRenamePhase.ROLLBACK_STAGE,
            step = items.size,
            items = items,
            rollbackSourcePhase = BatchRenamePhase.FINALIZE,
            rollbackSourceStep = maxOf(1, size / 2),
        )
        val restored = BatchRenameJournalEngine.continueRollback(durable, storeRollbackBoundary) { durable = it }
        check(restored.phase == BatchRenamePhase.ROLLBACK_RESTORE && restored.step == items.size)
        storeRollbackBoundary.assertOriginal()
        cases++
    }
    return cases
}

fun main() {
    val forward = runForwardCrashMatrix()
    val rollback = runRollbackCrashMatrix()
    val lostAck = runLostAckMatrix()
    val cancellation = runCancellationCheck()
    val boundaries = runBoundaryChecks()
    println(
        "BATCH_RENAME_12C_PRODUCTION_ENGINE_PASS " +
            "forward=$forward rollback=$rollback lostAck=$lostAck cancellation=$cancellation boundaries=$boundaries total=${forward + rollback + lostAck + cancellation + boundaries}"
    )
}

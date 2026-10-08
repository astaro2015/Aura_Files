package com.aurafiles.app.data

import java.io.IOException
import java.util.concurrent.CancellationException

internal enum class BatchRenamePhase {
    PREPARE,
    FINALIZE,
    ROLLBACK_STAGE,
    ROLLBACK_RESTORE,
}

internal data class BatchRenameJournalItem(
    val parentKey: String,
    val originalUri: String,
    val originalName: String,
    val temporaryName: String,
    val targetName: String,
    val rollbackName: String,
)

internal data class BatchRenameJournal(
    val operationId: String,
    val phase: BatchRenamePhase,
    /** Index of the next rename in [phase]. */
    val step: Int,
    val items: List<BatchRenameJournalItem>,
    val favoriteUris: Set<String> = emptySet(),
    /** Original forward phase/step captured before entering rollback. */
    val rollbackSourcePhase: BatchRenamePhase? = null,
    val rollbackSourceStep: Int = 0,
)

/**
 * Minimal name-based filesystem surface used by the crash journal.
 * Implementations must never overwrite [toName]. A false return is re-probed by the engine.
 */
internal interface BatchRenameNameStore {
    fun exists(parentKey: String, name: String): Boolean
    fun rename(parentKey: String, fromName: String, toName: String): Boolean
}

/**
 * Durable state machine for two-phase batch rename.
 *
 * `persist` MUST synchronously store the complete journal before returning. Every destructive
 * rename is bracketed by a persisted pre-step and post-step state, so a process death after the
 * provider mutation but before the post-step write can be reconciled from actual names.
 */
internal object BatchRenameJournalEngine {
    fun continueForward(
        journal: BatchRenameJournal,
        store: BatchRenameNameStore,
        persist: (BatchRenameJournal) -> Unit,
    ): BatchRenameJournal {
        require(journal.phase == BatchRenamePhase.PREPARE || journal.phase == BatchRenamePhase.FINALIZE) {
            "Journal ${journal.operationId} is already rolling back"
        }
        var state = journal
        if (state.phase == BatchRenamePhase.PREPARE) {
            state = runForwardPhase(
                state = state,
                store = store,
                persist = persist,
                fromName = BatchRenameJournalItem::originalName,
                toName = BatchRenameJournalItem::temporaryName,
            )
            state = state.copy(phase = BatchRenamePhase.FINALIZE, step = 0)
            persist(state)
        }
        if (state.phase == BatchRenamePhase.FINALIZE) {
            state = runForwardPhase(
                state = state,
                store = store,
                persist = persist,
                fromName = BatchRenameJournalItem::temporaryName,
                toName = BatchRenameJournalItem::targetName,
            )
        }
        return state
    }

    fun beginRollback(
        journal: BatchRenameJournal,
        persist: (BatchRenameJournal) -> Unit,
    ): BatchRenameJournal {
        if (journal.phase == BatchRenamePhase.ROLLBACK_STAGE || journal.phase == BatchRenamePhase.ROLLBACK_RESTORE) {
            return journal
        }
        require(journal.phase == BatchRenamePhase.PREPARE || journal.phase == BatchRenamePhase.FINALIZE)
        return journal.copy(
            phase = BatchRenamePhase.ROLLBACK_STAGE,
            step = 0,
            rollbackSourcePhase = journal.phase,
            rollbackSourceStep = journal.step,
        ).also(persist)
    }

    fun continueRollback(
        journal: BatchRenameJournal,
        store: BatchRenameNameStore,
        persist: (BatchRenameJournal) -> Unit,
    ): BatchRenameJournal {
        require(journal.phase == BatchRenamePhase.ROLLBACK_STAGE || journal.phase == BatchRenamePhase.ROLLBACK_RESTORE) {
            "Journal ${journal.operationId} is not in rollback"
        }
        var state = journal
        if (state.phase == BatchRenamePhase.ROLLBACK_STAGE) {
            val sourcePhase = requireNotNull(state.rollbackSourcePhase) { "Rollback source phase missing" }
            val sourceStep = state.rollbackSourceStep.coerceIn(0, state.items.size)
            for (index in state.step.coerceIn(0, state.items.size) until state.items.size) {
                state = state.copy(step = index)
                persist(state)
                val item = state.items[index]
                if (!store.exists(item.parentKey, item.rollbackName)) {
                    val currentName = rollbackCurrentName(item, index, sourcePhase, sourceStep, store)
                    renameAndConfirm(store, item.parentKey, currentName, item.rollbackName, "подготовить откат ${item.originalName}")
                }
                state = state.copy(step = index + 1)
                persist(state)
            }
            state = state.copy(phase = BatchRenamePhase.ROLLBACK_RESTORE, step = 0)
            persist(state)
        }
        if (state.phase == BatchRenamePhase.ROLLBACK_RESTORE) {
            for (index in state.step.coerceIn(0, state.items.size) until state.items.size) {
                state = state.copy(step = index)
                persist(state)
                val item = state.items[index]
                val rollbackExists = store.exists(item.parentKey, item.rollbackName)
                val originalExists = store.exists(item.parentKey, item.originalName)
                when {
                    rollbackExists && !originalExists ->
                        renameAndConfirm(store, item.parentKey, item.rollbackName, item.originalName, "вернуть ${item.originalName}")
                    !rollbackExists && originalExists -> Unit // step already committed before process death
                    rollbackExists && originalExists -> throw IOException(
                        "Нельзя завершить откат ${item.originalName}: исходное имя занято, страховочный объект сохранён как ${item.rollbackName}"
                    )
                    else -> throw IOException(
                        "Нельзя завершить откат ${item.originalName}: не найден ни ${item.rollbackName}, ни исходный объект"
                    )
                }
                state = state.copy(step = index + 1)
                persist(state)
            }
        }
        return state
    }

    private fun runForwardPhase(
        state: BatchRenameJournal,
        store: BatchRenameNameStore,
        persist: (BatchRenameJournal) -> Unit,
        fromName: (BatchRenameJournalItem) -> String,
        toName: (BatchRenameJournalItem) -> String,
    ): BatchRenameJournal {
        var current = state
        for (index in current.step.coerceIn(0, current.items.size) until current.items.size) {
            current = current.copy(step = index)
            persist(current)
            val item = current.items[index]
            val from = fromName(item)
            val to = toName(item)
            val sourceExists = store.exists(item.parentKey, from)
            val targetExists = store.exists(item.parentKey, to)
            when {
                sourceExists && !targetExists ->
                    renameAndConfirm(store, item.parentKey, from, to, "переименовать ${item.originalName}")
                !sourceExists && targetExists -> Unit // rename committed, post-step journal write was lost
                sourceExists && targetExists -> throw IOException(
                    "Нельзя безопасно продолжить пакетное переименование ${item.originalName}: одновременно существуют $from и $to"
                )
                else -> throw IOException(
                    "Нельзя безопасно продолжить пакетное переименование ${item.originalName}: не найден ни $from, ни $to"
                )
            }
            current = current.copy(step = index + 1)
            persist(current)
        }
        return current
    }

    private fun rollbackCurrentName(
        item: BatchRenameJournalItem,
        index: Int,
        sourcePhase: BatchRenamePhase,
        sourceStep: Int,
        store: BatchRenameNameStore,
    ): String {
        return when (sourcePhase) {
            BatchRenamePhase.PREPARE -> when {
                index < sourceStep -> item.temporaryName
                index > sourceStep -> item.originalName
                store.exists(item.parentKey, item.temporaryName) -> item.temporaryName
                else -> item.originalName
            }
            BatchRenamePhase.FINALIZE -> when {
                index < sourceStep -> item.targetName
                index > sourceStep -> item.temporaryName
                store.exists(item.parentKey, item.temporaryName) -> item.temporaryName
                else -> item.targetName
            }
            BatchRenamePhase.ROLLBACK_STAGE,
            BatchRenamePhase.ROLLBACK_RESTORE -> error("Nested rollback source phase")
        }
    }

    private fun renameAndConfirm(
        store: BatchRenameNameStore,
        parentKey: String,
        fromName: String,
        toName: String,
        description: String,
    ) {
        val reported = try {
            store.rename(parentKey, fromName, toName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        val sourceExists = store.exists(parentKey, fromName)
        val targetExists = store.exists(parentKey, toName)
        if (!sourceExists && targetExists) return
        if (sourceExists && !targetExists) {
            throw IOException("Не удалось $description")
        }
        val providerHint = if (reported) "Провайдер сообщил успех, но состояние неоднозначно." else "Провайдер не подтвердил операцию."
        throw IOException("$providerHint Не удалось безопасно подтвердить шаг: $fromName → $toName")
    }
}

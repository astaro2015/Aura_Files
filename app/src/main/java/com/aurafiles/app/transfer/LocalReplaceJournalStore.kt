package com.aurafiles.app.transfer

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

enum class LocalReplacePhase {
    PREPARED,
    BACKUP_PENDING,
    BACKUP_DONE,
    FINAL_PENDING,
    FINAL_DONE,
    CLEANUP_PENDING,
    ROLLBACK_PENDING,
}

data class LocalReplaceJournal(
    val transactionId: String,
    val operationId: String,
    val parentUri: String,
    val temporaryName: String,
    val finalName: String,
    val backupName: String,
    val phase: LocalReplacePhase,
    val createdAt: Long,
)

/** Durable, no-backup journal for one local REPLACE finalization. */
class LocalReplaceJournalStore(private val directory: File) {
    fun create(
        operationId: String,
        parentUri: String,
        temporaryName: String,
        finalName: String,
        backupName: String,
    ): LocalReplaceJournal {
        val record = LocalReplaceJournal(
            transactionId = UUID.randomUUID().toString(),
            operationId = operationId,
            parentUri = parentUri,
            temporaryName = temporaryName,
            finalName = finalName,
            backupName = backupName,
            phase = LocalReplacePhase.PREPARED,
            createdAt = System.currentTimeMillis(),
        )
        write(record)
        return record
    }

    fun update(record: LocalReplaceJournal, phase: LocalReplacePhase): LocalReplaceJournal {
        val updated = record.copy(phase = phase)
        write(updated)
        return updated
    }

    fun list(): List<LocalReplaceJournal> {
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        }?.sortedBy(File::getName).orEmpty()
        return files.map(::read)
    }

    fun remove(record: LocalReplaceJournal) {
        val file = fileFor(record.transactionId)
        if (file.exists() && !file.delete()) {
            throw IOException("Не удалось удалить завершённый журнал локальной замены ${record.transactionId}")
        }
    }

    private fun write(record: LocalReplaceJournal) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Не удалось создать каталог журнала локальной замены")
        }
        val atomic = AtomicFile(fileFor(record.transactionId))
        val payload = JSONObject()
            .put("version", VERSION)
            .put("transactionId", record.transactionId)
            .put("operationId", record.operationId)
            .put("parentUri", record.parentUri)
            .put("temporaryName", record.temporaryName)
            .put("finalName", record.finalName)
            .put("backupName", record.backupName)
            .put("phase", record.phase.name)
            .put("createdAt", record.createdAt)
            .toString()
            .toByteArray(Charsets.UTF_8)
        var output = atomic.startWrite()
        try {
            output.write(payload)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun read(file: File): LocalReplaceJournal {
        val payload = try {
            JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("Повреждён журнал локальной замены ${file.name}", error)
        }
        if (payload.optInt("version", -1) != VERSION) {
            throw IOException("Неизвестная версия журнала локальной замены ${file.name}")
        }
        val transactionId = payload.optString("transactionId")
        if (transactionId.isBlank() || file != fileFor(transactionId)) {
            throw IOException("Некорректный идентификатор журнала локальной замены ${file.name}")
        }
        val parentUri = payload.optString("parentUri")
        val temporaryName = payload.optString("temporaryName")
        val finalName = payload.optString("finalName")
        val backupName = payload.optString("backupName")
        if (parentUri.isBlank() || temporaryName.isBlank() || finalName.isBlank() || backupName.isBlank()) {
            throw IOException("Неполный журнал локальной замены ${file.name}")
        }
        if (!temporaryName.startsWith(".aura-part-") && !temporaryName.startsWith(".aura-dir-")) {
            throw IOException("Журнал содержит небезопасное временное имя")
        }
        if (!backupName.startsWith(".aura-backup-")) {
            throw IOException("Журнал содержит небезопасное имя страховочной копии")
        }
        val phase = try {
            LocalReplacePhase.valueOf(payload.getString("phase"))
        } catch (error: Exception) {
            throw IOException("Некорректная фаза журнала локальной замены ${file.name}", error)
        }
        return LocalReplaceJournal(
            transactionId = transactionId,
            operationId = payload.optString("operationId"),
            parentUri = parentUri,
            temporaryName = temporaryName,
            finalName = finalName,
            backupName = backupName,
            phase = phase,
            createdAt = payload.optLong("createdAt", 0L),
        )
    }

    private fun fileFor(transactionId: String): File {
        if (!SAFE_ID.matches(transactionId)) throw IOException("Некорректный идентификатор журнала")
        return File(directory, "$FILE_PREFIX$transactionId$FILE_SUFFIX")
    }

    private companion object {
        const val VERSION = 1
        const val FILE_PREFIX = "local-replace-journal-v1-"
        const val FILE_SUFFIX = ".json"
        val SAFE_ID = Regex("[0-9a-fA-F-]{36}")
    }
}

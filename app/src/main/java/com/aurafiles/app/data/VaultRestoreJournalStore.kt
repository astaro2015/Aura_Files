package com.aurafiles.app.data

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal enum class VaultRestorePhase { WRITING, FINALIZE_PENDING, FINALIZED }

internal data class VaultRestoreJournal(
    val transactionId: String,
    val destinationUri: String,
    val temporaryName: String,
    val finalName: String,
    val vaultFileName: String,
    val releasePersistedPermission: Boolean,
    val phase: VaultRestorePhase,
)

internal class VaultRestoreJournalStore(private val directory: File) {
    fun create(
        destinationUri: String,
        temporaryName: String,
        finalName: String,
        vaultFileName: String,
        releasePersistedPermission: Boolean,
    ): VaultRestoreJournal {
        val record = VaultRestoreJournal(
            transactionId = UUID.randomUUID().toString(),
            destinationUri = destinationUri,
            temporaryName = temporaryName,
            finalName = finalName,
            vaultFileName = vaultFileName,
            releasePersistedPermission = releasePersistedPermission,
            phase = VaultRestorePhase.WRITING,
        )
        write(record)
        return record
    }

    fun update(record: VaultRestoreJournal, phase: VaultRestorePhase): VaultRestoreJournal {
        val updated = record.copy(phase = phase)
        write(updated)
        return updated
    }

    fun list(): List<VaultRestoreJournal> = directory.listFiles { file ->
        file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)
    }?.sortedBy(File::getName)?.map(::read).orEmpty()

    fun remove(record: VaultRestoreJournal) {
        val file = fileFor(record.transactionId)
        if (file.exists() && !file.delete()) throw IOException("Не удалось удалить журнал восстановления Vault")
    }

    private fun write(record: VaultRestoreJournal) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Не удалось создать каталог журналов Vault")
        val atomic = AtomicFile(fileFor(record.transactionId))
        val bytes = JSONObject()
            .put("version", VERSION)
            .put("transactionId", record.transactionId)
            .put("destinationUri", record.destinationUri)
            .put("temporaryName", record.temporaryName)
            .put("finalName", record.finalName)
            .put("vaultFileName", record.vaultFileName)
            .put("releasePersistedPermission", record.releasePersistedPermission)
            .put("phase", record.phase.name)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun read(file: File): VaultRestoreJournal {
        val json = try {
            JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("Повреждён журнал восстановления Vault ${file.name}", error)
        }
        if (json.optInt("version", -1) != VERSION) throw IOException("Неизвестная версия журнала Vault")
        val id = json.optString("transactionId")
        if (!SAFE_ID.matches(id) || file != fileFor(id)) throw IOException("Некорректный ID журнала Vault")
        val destination = json.optString("destinationUri")
        val temporary = json.optString("temporaryName")
        val finalName = json.optString("finalName")
        val vaultFileName = json.optString("vaultFileName")
        if (destination.isBlank() || !temporary.startsWith(TEMP_PREFIX) || finalName.isBlank() ||
            !vaultFileName.endsWith(".avf", ignoreCase = true)
        ) throw IOException("Небезопасные данные в журнале восстановления Vault")
        val phase = try {
            VaultRestorePhase.valueOf(json.getString("phase"))
        } catch (error: Exception) {
            throw IOException("Некорректная фаза журнала восстановления Vault", error)
        }
        return VaultRestoreJournal(
            transactionId = id,
            destinationUri = destination,
            temporaryName = temporary,
            finalName = finalName,
            vaultFileName = vaultFileName,
            releasePersistedPermission = json.optBoolean("releasePersistedPermission", false),
            phase = phase,
        )
    }

    private fun fileFor(id: String): File {
        if (!SAFE_ID.matches(id)) throw IOException("Некорректный ID журнала Vault")
        return File(directory, "$PREFIX$id$SUFFIX")
    }

    private companion object {
        const val VERSION = 1
        const val PREFIX = "vault-restore-journal-v1-"
        const val SUFFIX = ".json"
        const val TEMP_PREFIX = ".aura-vault-restore-"
        val SAFE_ID = Regex("[0-9a-fA-F-]{36}")
    }
}

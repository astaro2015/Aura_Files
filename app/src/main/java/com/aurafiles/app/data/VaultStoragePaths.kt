package com.aurafiles.app.data

import java.io.File
import java.io.IOException
import java.nio.file.Files

internal object VaultStoragePaths {
    const val FOLDER = ".AuraSafe"
    const val LEGACY_FOLDER = ".AuraVault"

    fun isVaultFolder(name: String): Boolean = name == FOLDER || name == LEGACY_FOLDER

    fun isVaultPath(path: String): Boolean = path.split('/', '\\', ':').any(::isVaultFolder)

    fun dataDirectory(storageRoot: File, create: Boolean): File? {
        val current = File(storageRoot, FOLDER)
        val legacy = File(storageRoot, LEGACY_FOLDER)
        rejectSymlink(current)
        rejectSymlink(legacy)
        if (current.exists() && !current.isDirectory) throw IOException("$FOLDER не является папкой")
        if (legacy.exists() && !legacy.isDirectory) throw IOException("$LEGACY_FOLDER не является папкой")
        if (legacy.exists()) {
            val legacyData = File(legacy, "data")
            rejectSymlink(legacyData)
            if (current.exists()) {
                throw IOException("Найдены обе папки $LEGACY_FOLDER и $FOLDER; данные сохранены, нужен ручной разбор")
            }
            if (!legacy.renameTo(current) || legacy.exists() || !current.isDirectory) {
                throw IOException("Не удалось переименовать $LEGACY_FOLDER в $FOLDER; данные не изменены")
            }
        }
        val data = File(current, "data")
        rejectSymlink(data)
        if (data.exists() && !data.isDirectory) throw IOException("$FOLDER/data не является папкой")
        if (create && !data.exists() && !data.mkdirs()) return null
        return data.takeIf { it.isDirectory }
    }

    private fun rejectSymlink(path: File) {
        if (Files.isSymbolicLink(path.toPath())) throw IOException("Символическая ссылка ${path.name} не допускается")
    }
}

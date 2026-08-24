package com.aurafiles.app.data

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.SystemSoundType
import com.aurafiles.app.model.isAudioFile
import java.io.IOException

class SystemSoundRepository(private val context: Context) {
    fun assign(entry: FileEntry, type: SystemSoundType): String {
        require(entry.isAudioFile() && !entry.isDirectory) { "Выберите звуковой файл" }
        val ringtoneType = when (type) {
            SystemSoundType.Ringtone -> RingtoneManager.TYPE_RINGTONE
            SystemSoundType.Alarm -> RingtoneManager.TYPE_ALARM
        }
        val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copyToMediaStore(entry, type)
        } else {
            entry.uri
        }
        RingtoneManager.setActualDefaultRingtoneUri(context, ringtoneType, targetUri)
        return when (type) {
            SystemSoundType.Ringtone -> "${entry.name} назначен мелодией звонка"
            SystemSoundType.Alarm -> "${entry.name} назначен звуком будильника"
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyToMediaStore(entry: FileEntry, type: SystemSoundType): android.net.Uri {
        val resolver = context.contentResolver
        val directory = when (type) {
            SystemSoundType.Ringtone -> "Ringtones/Aura Files"
            SystemSoundType.Alarm -> "Alarms/Aura Files"
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, entry.name)
            put(MediaStore.Audio.Media.MIME_TYPE, entry.mimeType ?: "audio/*")
            put(MediaStore.Audio.Media.RELATIVE_PATH, directory)
            put(MediaStore.Audio.Media.IS_RINGTONE, type == SystemSoundType.Ringtone)
            put(MediaStore.Audio.Media.IS_ALARM, type == SystemSoundType.Alarm)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = resolver.insert(collection, values)
            ?: throw IOException("Android не разрешил добавить звук в системную медиатеку")
        try {
            val input = resolver.openInputStream(entry.uri)
                ?: throw IOException("Не удалось прочитать ${entry.name}")
            input.use { source ->
                resolver.openOutputStream(target, "w")?.use { output -> source.copyTo(output) }
                    ?: throw IOException("Не удалось сохранить системный звук")
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return target
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
    }
}

package com.aurafiles.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.index.StorageIndexer
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.tools.FileHashService
import com.aurafiles.app.tools.FileHashes
import com.aurafiles.app.tools.FolderSizeCalculator
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnhancedPropertiesActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var container: LinearLayout
    private lateinit var hashContainer: LinearLayout
    private lateinit var uri: Uri
    private var name = ""
    private var directory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: run { finish(); return }
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        directory = intent.getBooleanExtra(EXTRA_DIR, false)
        buildUi()
    }

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        container.addView(TextView(this).apply { text = name; textSize = 23f })
        container.addView(TextView(this).apply { text = uri.toString(); setTextIsSelectable(true); textSize = 11f })
        status = TextView(this).apply { setPadding(0, dp(18), 0, dp(12)); text = if (directory) "Размер папки ещё не рассчитан" else "Хэши ещё не рассчитаны" }
        container.addView(status)
        action = Button(this).apply {
            text = if (directory) "Рассчитать размер" else "Рассчитать MD5 / SHA-1 / SHA-256"
            setOnClickListener { if (activeJob?.isActive == true) activeJob?.cancel() else if (directory) calculateFolder() else calculateHashes() }
        }
        container.addView(action)
        hashContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(hashContainer)
        container.addView(Button(this).apply { text = "Закрыть"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(container) })
    }

    private fun calculateFolder() {
        val document = documentFromUri(uri) ?: run { status.text = "Папка недоступна"; return }
        action.text = "Отменить расчёт"
        activeJob = scope.launch {
            try {
                val indexedHint = withContext(Dispatchers.IO) {
                    val repo = FileRepository(this@EnhancedPropertiesActivity)
                    val root = repo.restoreRoot()
                    if (root?.uri == document.uri) {
                        StorageIndexer(this@EnhancedPropertiesActivity).load(root)?.let { analysis ->
                            analysis.totalBytes to analysis.files.size.toLong()
                        }
                    } else null
                }
                indexedHint?.let { (bytes, files) ->
                    status.text = "Свежий индекс Aura: ${formatBytes(bytes)} · $files файлов\nУточняем точное число папок…"
                }
                // The index stores files but not a reliable directory count. Always finish
                // with an exact cancellable walk so Properties never reports “0 folders”
                // merely because an index was available.
                val result = withContext(Dispatchers.IO) {
                    FolderSizeCalculator(this@EnhancedPropertiesActivity).calculate(document) { progress ->
                        runOnUiThread {
                            status.text = "${formatBytes(progress.bytes)} · ${progress.files} файлов · ${progress.directories} папок\n${progress.currentName}"
                        }
                    }
                }
                status.text = "${formatBytes(result.bytes)}\n${result.files} файлов\n${result.directories} папок" +
                    if (indexedHint != null) "\nИндекс использован для мгновенной предварительной оценки; итог пересчитан точно" else ""
            } catch (_: CancellationException) {
                status.text = "Расчёт отменён"
            } catch (error: Throwable) {
                status.text = "Ошибка: ${error.message.orEmpty()}"
            } finally {
                action.text = "Рассчитать заново"
                activeJob = null
            }
        }
    }

    private fun calculateHashes() {
        val document = documentFromUri(uri) ?: run { status.text = "Файл недоступен"; return }
        val entry = FileEntry(document, name.ifBlank { document.name ?: "Файл" }, uri, false, document.type, document.length(), document.lastModified())
        action.text = "Отменить расчёт"
        activeJob = scope.launch {
            try {
                val hashes = withContext(Dispatchers.IO) {
                    FileHashService(this@EnhancedPropertiesActivity).calculate(entry) { done, total ->
                        runOnUiThread { status.text = "Хэширование: ${formatBytes(done)} / ${formatBytes(total)}" }
                    }
                }
                showHashes(hashes)
            } catch (_: CancellationException) {
                status.text = "Хэширование отменено"
            } catch (error: Throwable) {
                status.text = "Ошибка: ${error.message.orEmpty()}"
            } finally {
                action.text = "Рассчитать заново"
                activeJob = null
            }
        }
    }

    private fun showHashes(hashes: FileHashes) {
        status.text = "Готово"
        hashContainer.removeAllViews()
        listOf("MD5" to hashes.md5, "SHA-1" to hashes.sha1, "SHA-256" to hashes.sha256).forEach { (label, value) ->
            hashContainer.addView(TextView(this).apply {
                text = "$label\n$value"
                setTextIsSelectable(true)
                setPadding(0, dp(10), 0, dp(3))
            })
            hashContainer.addView(Button(this).apply {
                text = "Скопировать $label"
                setOnClickListener { copy(label, value) }
            })
        }
    }

    private fun copy(label: String, value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun documentFromUri(uri: Uri): DocumentFile? = if (uri.scheme == ContentResolver.SCHEME_FILE) {
        uri.path?.let(::File)?.let(DocumentFile::fromFile)
    } else {
        runCatching { DocumentFile.fromTreeUri(this, uri) }.getOrNull()
            ?: runCatching { DocumentFile.fromSingleUri(this, uri) }.getOrNull()
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.coerceAtLeast(0L).toDouble(); var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return if (unit == 0) "${bytes.coerceAtLeast(0L)} ${units[unit]}" else "%.2f %s".format(value, units[unit])
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URI = "properties_uri"
        private const val EXTRA_NAME = "properties_name"
        private const val EXTRA_DIR = "properties_dir"
        fun start(context: Context, entry: FileEntry) {
            context.startActivity(
                Intent(context, EnhancedPropertiesActivity::class.java)
                    .putExtra(EXTRA_URI, entry.uri.toString())
                    .putExtra(EXTRA_NAME, entry.name)
                    .putExtra(EXTRA_DIR, entry.isDirectory)
            )
        }
    }
}

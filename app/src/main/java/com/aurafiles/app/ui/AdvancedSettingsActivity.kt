package com.aurafiles.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.tools.CacheInspector
import com.aurafiles.app.tools.AuraCacheStats
import java.util.Locale

/**
 * 0.14 settings hub. It keeps existing preference storage intact and groups
 * related controls instead of mixing cache/trash/network settings together.
 */
class AdvancedSettingsActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var cacheText: TextView
    private val files by lazy { FileRepository(this) }
    private val cache by lazy { CacheInspector(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshCache()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
        }
        root.addView(TextView(this).apply { text = "Настройки Aura Files"; textSize = 24f })
        section("Внешний вид")
        note("Светлая/тёмная тема следует системной теме Android.")

        section("Файлы")
        toggle("Показывать скрытые файлы", files.showHiddenFiles(), files::setShowHiddenFiles)
        toggle("Показывать папки thumbnails", files.showThumbnailFiles(), files::setShowThumbnailFiles)
        toggle("Миниатюры в сетке", files.showGridThumbnails(), files::setShowGridThumbnails)
        toggle("Избранное на главном экране", files.showFavoritesOnHome(), files::setShowFavoritesOnHome)

        section("Просмотр")
        note("Изображения: соседние файлы, EXIF, свайп, масштаб 100 %, поворот просмотра.")
        note("Видео: жесты ±10 с, яркость/громкость, скорость, дорожки, субтитры и PiP.")
        note("Аудио: MediaSession, уведомление/экран блокировки и продолжение позиции.")

        section("Сеть")
        button("Универсальные панели Local / SMB / FTP / SFTP") {
            startActivity(Intent(this, BackendWorkspaceActivity::class.java))
        }
        note("SFTP проверяет SHA-256 fingerprint сервера и хранит пароль/ключ через CredentialStore.")

        section("Читалка")
        note("PDF Reflow использует существующий движок Чтеца и не переписывается с нуля.")

        section("Анализ")
        button("Похожие фотографии (экспериментально)") {
            startActivity(Intent(this, SimilarPhotosActivity::class.java))
        }
        note("Похожие фотографии и точные дубликаты — разные режимы. Автоудаления нет.")

        section("Кэш")
        cacheText = TextView(this)
        root.addView(cacheText)
        button("Очистить PDF Reflow-кэш") { cache.clearPdfReflow(); refreshCache() }
        button("Очистить весь кэш Aura") { cache.clearAll(); refreshCache() }
        note("Корзина не относится к кэшу и этими кнопками не очищается.")

        section("Безопасность")
        note("Release signing берётся из внешнего keystore.properties или environment variables; ключи и пароли не должны попадать в Git.")
        note("SMB1, root-доступ и обход Android/data не добавляются.")

        section("О программе")
        note("Aura Files ${com.aurafiles.app.BuildConfig.VERSION_NAME} — Remote & Media.")
        button("Закрыть") { finish() }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Suppress("DEPRECATION")
    private fun toggle(label: String, checked: Boolean, save: (Boolean) -> Unit) {
        root.addView(Switch(this).apply {
            text = label
            isChecked = checked
            setPadding(0, dp(5), 0, dp(5))
            setOnCheckedChangeListener { _, value -> save(value) }
        })
    }

    private fun section(title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setPadding(0, dp(20), 0, dp(7))
        })
    }

    private fun note(textValue: String) {
        root.addView(TextView(this).apply {
            text = textValue
            textSize = 13f
            alpha = 0.78f
            setPadding(0, dp(3), 0, dp(5))
        })
    }

    private fun button(label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshCache() {
        if (!::cacheText.isInitialized) return
        val stats = cache.stats()
        cacheText.text = "Кэш Aura: ${formatBytes(stats.totalBytes)}\nPDF Reflow: ${formatBytes(stats.pdfReflowBytes)}\nПрочее: ${formatBytes(stats.thumbnailsAndSharesBytes)}"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index += 1 }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[index.coerceAtLeast(0)])
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, AdvancedSettingsActivity::class.java))
    }
}

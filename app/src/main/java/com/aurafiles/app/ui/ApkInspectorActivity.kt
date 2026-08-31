package com.aurafiles.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.tools.ApkInfo
import com.aurafiles.app.tools.ApkInspector
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApkInspectorActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var temporary: File? = null
    private lateinit var originalUri: Uri
    private var displayName: String = "app.apk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: run { finish(); return }
        displayName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "app.apk" }
        val loading = TextView(this).apply { text = "Чтение APK…"; gravity = Gravity.CENTER; textSize = 18f }
        setContentView(loading)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ApkInspector(this@ApkInspectorActivity).inspect(originalUri, displayName) } }
                .onSuccess { result ->
                    temporary = result.temporaryFile
                    showInfo(result.info)
                }
                .onFailure { error ->
                    loading.text = "Не удалось прочитать APK\n${error.message.orEmpty()}"
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // Do not delete the shared APK here: Package Installer or a chosen app may
        // still be reading the granted FileProvider URI after this Activity closes.
        super.onDestroy()
    }

    private fun showInfo(info: ApkInfo) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }
        info.icon?.let { icon ->
            content.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        content.addView(TextView(this).apply { text = info.label; textSize = 25f; gravity = Gravity.CENTER_HORIZONTAL })
        addRow(content, "Package", info.packageName)
        addRow(content, "Версия", "${info.versionName} (${info.versionCode})")
        addRow(content, "SDK", "min ${info.minSdk} · target ${info.targetSdk}")
        addRow(content, "Размер", formatBytes(info.size))
        addRow(content, "SHA-256 APK", info.apkSha256)
        info.certificates.forEachIndexed { index, cert ->
            addRow(content, "Сертификат ${index + 1}", cert.subject)
            addRow(content, "Издатель", cert.issuer)
            addRow(content, "Серийный номер", cert.serialNumber)
            addRow(content, "Действует", "${date(cert.validFrom)} — ${date(cert.validUntil)}")
            addRow(content, "SHA-256 сертификата", cert.sha256)
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(actionButton("Установить") { install() })
        buttons.addView(actionButton("Открыть в…") { openExternal() })
        buttons.addView(actionButton("Поделиться") { share() })
        buttons.addView(actionButton("Скопировать package name") { copy("Package", info.packageName) })
        buttons.addView(actionButton("Скопировать SHA-256") { copy("SHA-256", info.apkSha256) })
        content.addView(buttons)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun install() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            runCatching { startActivity(settings) }
                .onSuccess { Toast.makeText(this, "Разрешите установку из Aura и нажмите «Установить» ещё раз", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this, "Не удалось открыть разрешение установки: ${it.message}", Toast.LENGTH_LONG).show() }
            return
        }
        val uri = temporaryUri() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newUri(contentResolver, displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Android не открыл установщик: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternal() {
        val uri = temporaryUri() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Открыть APK")) }
    }

    private fun share() {
        val uri = temporaryUri() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Поделиться APK"))
    }

    private fun temporaryUri(): Uri? = temporary?.takeIf(File::exists)?.let {
        FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
    }

    private fun copy(label: String, value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun addRow(parent: LinearLayout, label: String, value: String) {
        parent.addView(TextView(this).apply {
            text = "$label\n$value"
            textSize = 14f
            setPadding(0, dp(9), 0, dp(9))
            setTextIsSelectable(true)
        })
    }

    private fun actionButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        setOnClickListener { action() }
    }

    private fun date(millis: Long): String = DateFormat.getDateInstance().format(Date(millis))
    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit += 1 }
        return if (unit == 0) "$bytes ${units[unit]}" else "%.2f %s".format(value, units[unit])
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URI = "apk_uri"
        private const val EXTRA_NAME = "apk_name"
        fun start(context: Context, entry: FileEntry) {
            context.startActivity(
                Intent(context, ApkInspectorActivity::class.java)
                    .putExtra(EXTRA_URI, entry.uri.toString())
                    .putExtra(EXTRA_NAME, entry.name)
            )
        }
    }
}

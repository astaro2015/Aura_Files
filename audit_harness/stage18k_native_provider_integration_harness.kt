import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import com.aurafiles.app.AuraFileProvider
import java.io.File
import java.nio.file.Files

private class FakeContext(private val cache: File) : Context() {
    override fun getPackageName(): String = "com.aurafiles.app"
    override fun getCacheDir(): File = cache
}

fun main() {
    val temp = Files.createTempDirectory("aura-native-provider").toFile().canonicalFile
    val cache = File(temp, "cache").apply { mkdirs() }.canonicalFile
    val external = File(temp, "external").apply { mkdirs() }.canonicalFile
    Environment.setExternalStorageDirectory(external)
    val ctx = FakeContext(cache)
    val shares = File(cache, "shares").apply { mkdirs() }
    val apk = File(shares, "Тест APK [1].apk").apply { writeBytes(ByteArray(4097) { (it % 251).toByte() }) }.canonicalFile

    val uri = AuraFileProvider.uriForFile(ctx, apk)
    check(uri.scheme == "content")
    check(uri.authority == "com.aurafiles.app.fileprovider")
    check(uri.pathSegments.first() == "temporary_shares")
    check(uri.pathSegments.last() == apk.name)

    val provider = AuraFileProvider()
    provider.attachTestContext(ctx)
    check(provider.onCreate())
    val cursor = provider.query(uri, null, null, null, null) as MatrixCursor
    check(cursor.count == 1)
    val cols = cursor.columns.toList()
    val row = cursor.getRow(0)
    check(row[cols.indexOf("_display_name")] == apk.name)
    check(row[cols.indexOf("_size")] == apk.length())
    check(provider.getType(uri) == "application/octet-stream")
    check(provider.openFile(uri, "r").file.canonicalFile == apk)
    check(runCatching { provider.openFile(uri, "rw") }.isFailure)

    val traversal = Uri.Builder().scheme("content").authority(uri.authority)
        .appendPath("temporary_shares").appendPath("..").appendPath("outside.apk").build()
    check(runCatching { provider.openFile(traversal, "r") }.isFailure)
    val badAuthority = Uri.Builder().scheme("content").authority("evil.provider")
        .appendPath("temporary_shares").appendPath(apk.name).build()
    check(runCatching { provider.openFile(badAuthority, "r") }.isFailure)

    // External root path also round-trips.
    val extApk = File(external, "Download/ext.apk").apply { parentFile.mkdirs(); writeText("apk") }.canonicalFile
    val extUri = AuraFileProvider.uriForFile(ctx, extApk)
    check(extUri.pathSegments.first() == "shared_storage")
    check(provider.openFile(extUri, "r").file.canonicalFile == extApk)

    temp.deleteRecursively()
    println("STAGE18K_NATIVE_PROVIDER_INTEGRATION_PASS")
}

"""Execute AuraVault's permission methods against Android's exact grant contract.

No phone is required. Only the Android URI/grant boundary is substituted; the
acquire/release methods are compiled directly from the production Kotlin source.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(os.environ.get("PUBLIC", r"C:\Users\Public")) / "AuraBuildTools"
CACHE = TOOLS / "gradle-home/caches/modules-2/files-2.1"
JAVA = TOOLS / "jdk17/bin/java.exe"


def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).rglob("*.jar"))


def method(source, name):
    start = source.index("    private fun " + name + "(")
    body = source.index("{", start)
    depth = 1
    end = body + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


source = (ROOT / "app/src/main/java/com/aurafiles/app/data/AuraVault.kt").read_text(encoding="utf-8")
methods = [method(source, name) for name in (
    "acquireRestorePermissionIfNeeded", "releaseRestorePermissionIfOwned"
)]
if "private fun restorePermissionUri(" in source:
    methods.append(method(source, "restorePermissionUri"))

fixture = r'''
import java.io.IOException
import java.net.URI

data class Uri(private val raw: String) {
    val scheme: String? get() = URI(raw).scheme
    val authority: String? get() = URI(raw).rawAuthority
    val path: String? get() = URI(raw).rawPath
    override fun toString() = raw
    companion object { fun parse(raw: String) = Uri(raw) }
}
object DocumentsContract {
    fun isTreeUri(uri: Uri) = uri.path?.startsWith("/tree/") == true
    fun getTreeDocumentId(uri: Uri): String = java.net.URLDecoder.decode(
        uri.path!!.removePrefix("/tree/").substringBefore('/'), "UTF-8")
    fun buildTreeDocumentUri(authority: String, id: String) = Uri.parse(
        "content://$authority/tree/" + java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20"))
}
object Intent {
    const val FLAG_GRANT_READ_URI_PERMISSION = 1
    const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
}
data class Permission(val uri: Uri, val isWritePermission: Boolean, val isReadPermission: Boolean = true)
class Resolver(val offered: Uri?) {
    val persistedUriPermissions = mutableListOf<Permission>()
    fun takePersistableUriPermission(uri: Uri, flags: Int) {
        // Android looks up the EXACT offered URI, even for prefix grants.
        if (uri != offered || flags != 3) throw SecurityException("No persistable permission grants found")
        persistedUriPermissions.add(Permission(uri, true))
    }
    fun releasePersistableUriPermission(uri: Uri, flags: Int) {
        check(flags == 3)
        if (!persistedUriPermissions.removeIf { it.uri == uri }) throw SecurityException("No permission grants found")
    }
}
class Subject(val resolver: Resolver) {
    fun acquire(uri: Uri) = acquireRestorePermissionIfNeeded(uri)
    fun release(uri: Uri, owned: Boolean) = releaseRestorePermissionIfOwned(uri, owned)
__METHODS__
}

fun main() {
    val grant = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
    val document = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload")
    val nested = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fphotos")
    for (destination in listOf(document, nested, grant)) {
        val resolver = Resolver(grant)
        val subject = Subject(resolver)
        val owned = subject.acquire(destination)
        check(owned) { "Fresh picker grant must be persisted for recovery" }
        check(resolver.persistedUriPermissions.single().uri == grant)
        // A newly created subject represents recovery after a process restart.
        Subject(resolver).release(destination, owned)
        check(resolver.persistedUriPermissions.isEmpty()) { "Recovery must release its own tree grant" }
    }
    val existing = Resolver(grant).apply { persistedUriPermissions.add(Permission(grant, true)) }
    val subject = Subject(existing)
    val owned = subject.acquire(document)
    check(!owned) { "Existing folder access must be reused, not owned by Vault" }
    subject.release(document, owned)
    check(existing.persistedUriPermissions.single().uri == grant) { "Vault must preserve existing access" }

    val denied = Resolver(null)
    val failure = runCatching { Subject(denied).acquire(document) }.exceptionOrNull()
    check(failure is IOException && failure.cause is SecurityException)
    check(denied.persistedUriPermissions.isEmpty())

    val direct = Resolver(null)
    check(!Subject(direct).acquire(Uri.parse("file:///storage/emulated/0/Download")))
    check(direct.persistedUriPermissions.isEmpty())
    println("VAULT_RESTORE_PERMISSION_PASS scenarios=6 (picker, nested, tree, existing, denied, filesystem)")
}
'''.replace("__METHODS__", "\n".join(methods))

stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21")
compiler_jars = [
    jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.3.21"),
    stdlib,
    jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.3.21"),
    jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    jar("org.jetbrains", "annotations", "13.0"),
]
with tempfile.TemporaryDirectory(prefix="aura-vault-permission-") as directory:
    folder = Path(directory)
    harness = folder / "PermissionHarness.kt"
    harness.write_text(fixture, encoding="utf-8")
    out = folder / "classes"
    subprocess.run([
        str(JAVA), "-cp", os.pathsep.join(map(str, compiler_jars)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", str(stdlib), "-d", str(out), str(harness),
    ], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join((str(out), str(stdlib))), "PermissionHarnessKt"], check=True)

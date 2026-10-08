import com.aurafiles.app.AuraFileProviderPathPolicy
import java.io.File
import java.nio.file.Files

fun main() {
    val temp = Files.createTempDirectory("aura-provider-policy").toFile().canonicalFile
    val cache = File(temp, "cache").apply { mkdirs() }.canonicalFile
    val shares = File(cache, "shares").apply { mkdirs() }.canonicalFile
    val outside = File(temp, "outside").apply { mkdirs() }.canonicalFile
    val roots = listOf(
        AuraFileProviderPathPolicy.Root("cache", cache.path),
        AuraFileProviderPathPolicy.Root("shares", shares.path),
    )

    val apk = File(shares, "A B [1] ü.apk").apply { writeBytes(byteArrayOf(1,2,3)) }.canonicalFile
    check(AuraFileProviderPathPolicy.resolveIncoming("shares", apk.name, roots) == apk.path)

    // A less-specific overlapping root must not be usable to bypass the route selected at export.
    check(runCatching {
        AuraFileProviderPathPolicy.resolveIncoming("cache", "shares/${apk.name}", roots)
    }.isFailure)

    // Traversal outside every allowed root must be rejected after canonicalization.
    check(runCatching {
        AuraFileProviderPathPolicy.resolveIncoming("shares", "../../outside/evil.apk", roots)
    }.isFailure)

    // Traversal that canonicalizes to a different allowed root must still be rejected as root mismatch.
    check(runCatching {
        AuraFileProviderPathPolicy.resolveIncoming("shares", "../other.apk", roots)
    }.isFailure)

    // Symlink escape: if supported by the host, canonicalization must reveal and reject it.
    val link = File(shares, "escape")
    runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.onSuccess {
        File(outside, "secret.apk").writeText("x")
        check(runCatching {
            AuraFileProviderPathPolicy.resolveIncoming("shares", "escape/secret.apk", roots)
        }.isFailure)
    }

    // Fuzz relative paths that contain traversal. None may escape the declared root.
    repeat(2000) { i ->
        val relative = when (i % 5) {
            0 -> "../outside/$i.apk"
            1 -> "sub/../../outside/$i.apk"
            2 -> "./../outside/$i.apk"
            3 -> "folder/$i.apk"
            else -> "folder-$i/file.apk"
        }
        val result = runCatching {
            AuraFileProviderPathPolicy.resolveIncoming("shares", relative, roots)
        }
        if (".." in relative) check(result.isFailure)
        else if (result.isSuccess) check(result.getOrThrow().startsWith(shares.path + File.separator))
    }

    temp.deleteRecursively()
    println("STAGE18J_PROVIDER_ROUTE_POLICY_PASS cases=2005+")
}

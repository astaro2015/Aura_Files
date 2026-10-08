import com.aurafiles.app.AuraFileProviderPathPolicy

fun main() {
    val roots = listOf(
        AuraFileProviderPathPolicy.Root("external", "/storage/emulated/0"),
        AuraFileProviderPathPolicy.Root("shares", "/data/user/0/com.aurafiles.app/cache/shares"),
        AuraFileProviderPathPolicy.Root("cache", "/data/user/0/com.aurafiles.app/cache"),
    )

    check(AuraFileProviderPathPolicy.resolve(
        "/data/user/0/com.aurafiles.app/cache/shares/app.apk", roots
    ) == AuraFileProviderPathPolicy.Match("shares", "app.apk"))

    check(AuraFileProviderPathPolicy.resolve(
        "/storage/emulated/0/Download/Aura/app.apk", roots
    ) == AuraFileProviderPathPolicy.Match("external", "Download/Aura/app.apk"))

    check(AuraFileProviderPathPolicy.resolveOrNull(
        "/data/user/0/com.aurafiles.app/files/private.apk", roots
    ) == null)

    check(AuraFileProviderPathPolicy.resolveOrNull(
        "/storage/emulated/01/not-child.apk", roots
    ) == null)

    check(AuraFileProviderPathPolicy.resolveOrNull(
        "/data/user/0/com.aurafiles.app/cache/shares", roots
    ) == AuraFileProviderPathPolicy.Match("cache", "shares"))

    check(AuraFileProviderPathPolicy.resolveOrNull(
        "/only/root", listOf(AuraFileProviderPathPolicy.Root("only", "/only/root"))
    ) == null)

    println("PASS: AuraFileProviderPathPolicy")
}

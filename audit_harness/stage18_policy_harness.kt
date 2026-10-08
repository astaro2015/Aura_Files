import com.aurafiles.app.tools.UniversalApkExportPolicy

fun main() {
    check(UniversalApkExportPolicy.shareLabel(true, false) == "Поделиться APK…")
    check(UniversalApkExportPolicy.shareLabel(true, true) == "Готовим APK…")
    check(
        UniversalApkExportPolicy.outputFileName("Test / App 🔥", "com.example.test", "1.2 beta", 12) ==
            "installed-Test _ App-1.2_beta.apk"
    )
    check(UniversalApkExportPolicy.outputFileName("", "com.example.test", "", 12) == "installed-test-12.apk")
    println("STAGE18_POLICY_PASS")
}

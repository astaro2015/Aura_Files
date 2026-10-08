import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun releaseSetting(propertyName: String, environmentName: String): String? =
    keystoreProperties.getProperty(propertyName)?.trim()?.takeIf(String::isNotEmpty)
        ?: System.getenv(environmentName)?.trim()?.takeIf(String::isNotEmpty)

val releaseStoreFile = releaseSetting("storeFile", "AURA_KEYSTORE_FILE")
val releaseStorePassword = releaseSetting("storePassword", "AURA_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSetting("keyAlias", "AURA_KEY_ALIAS")
val releaseKeyPassword = releaseSetting("keyPassword", "AURA_KEY_PASSWORD")

val yandexOAuthClientId = providers.gradleProperty("AURA_YANDEX_CLIENT_ID").orNull
    ?.trim()?.takeIf(String::isNotEmpty)
    ?: System.getenv("AURA_YANDEX_CLIENT_ID")?.trim()?.takeIf(String::isNotEmpty)
    ?: ""

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

// ARSCLib bundles Android and XmlPull API stubs inside its desktop JAR.
// Packaging them lets R8 rename android.util.AttributeSet in Media3 XML view
// constructors, so Android's inflater cannot find the real SDK signature.
val arscLibRaw = configurations.create("arscLibRaw") {
    isTransitive = false
}
val sanitizedArscLib = tasks.register<Jar>("sanitizeArscLib") {
    archiveFileName.set("ARSCLib-1.4.0-android.jar")
    destinationDirectory.set(layout.buildDirectory.dir("sanitized-libs"))
    inputs.property("excludedFrameworkStubs", "android/**;org/xmlpull/v1/**")
    from({ zipTree(arscLibRaw.singleFile) }) {
        exclude("android/**")
        exclude("org/xmlpull/v1/**")
    }
}

android {
    namespace = "com.aurafiles.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aurafiles.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 149
        versionName = "1.3.19"
        buildConfigField("String", "YANDEX_OAUTH_CLIENT_ID", buildConfigString(yandexOAuthClientId))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            ndkBuild {
                arguments += "APP_PLATFORM=android-26"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    if (hasReleaseSigning) {
        signingConfigs.create("externalRelease") {
            storeFile = rootProject.file(requireNotNull(releaseStoreFile))
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Without an external key the release variant stays unsigned; debug builds remain unaffected.
            signingConfigs.findByName("externalRelease")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Several JVM libraries bundle the same legal/OSGi metadata. These files are
    // not used by Android at runtime and otherwise collide in mergeDebugJavaResource.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/DEPENDENCIES.txt",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/license.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/notice.txt",
                "META-INF/license.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA",
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
}

// Keep javac deprecation diagnostics precise. If a new deprecated Java API slips in,
// the build log names the exact source file and line instead of only printing the generic note.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")
    implementation("commons-net:commons-net:3.13.0")
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("org.codelibs:jcifs:2.1.40")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation("org.apache.sshd:sshd-sftp:2.19.0")
    // SMBJ 0.15.0 publishes bcprov 1.85.2, while the matching bcpkix artifact
    // currently exists as 1.85 (there is no bcpkix 1.85.2 in Maven Central).
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Split APK fusion engine: APKEditor-compatible merge primitives without its CLI/smali stack.
    add("arscLibRaw", "io.github.reandroid:ARSCLib:1.4.0")
    implementation(files(sanitizedArscLib))
    // Runtime signing/verifying of fused APK exports on-device.
    // Android-safe port: upstream AOSP apksig is explicitly host-side / outside-device oriented.
    implementation("com.github.MuntashirAkon:apksig-android:4.4.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.tukaani:xz:1.12")
    implementation("com.github.junrar:junrar:8.1.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

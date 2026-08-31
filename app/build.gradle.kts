import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile

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
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.aurafiles.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aurafiles.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 100
        versionName = "1.0"

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
    // SSHJ 0.40.0 was published with Bouncy Castle 1.80; pin a patched current line.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:8.1.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

import java.security.KeyStore
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Compose compiler only. Kotlin itself is AGP 9's built-in Kotlin, see the root build file.
    alias(libs.plugins.kotlin.compose)
}

// Release signing, PKCS12. Locally: keystore.properties in the repository root (gitignored) with
// storeFile, storePassword and optionally keyAlias and keyPassword; storeFile is absolute, or
// relative to the repository root, since the keystore itself belongs outside the repository. On CI
// the same values arrive as environment variables. With neither, assembleRelease still runs and
// produces an unsigned APK, which cannot be installed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
// Set by the release workflow from the tag name; absent everywhere else.
val releaseVersionName = (findProperty("mivolumeenhanced.versionName") as String?)?.takeIf { it.isNotBlank() }
val releaseVersionCode = (findProperty("mivolumeenhanced.versionCode") as String?)?.toIntOrNull()

val keystoreFile = signingValue("storeFile", "KEYSTORE_FILE")?.let { rootProject.file(it) }
val keystorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
// A .p12 written by PowerShell's Export-PfxCertificate takes its entry name from the certificate's
// friendly name, which is not always what you expect and cannot be inspected without keytool. With
// no alias configured, read the store's only alias instead of making anyone guess.
val keystoreAlias = signingValue("keyAlias", "KEY_ALIAS") ?: keystoreFile?.takeIf { it.isFile }?.let { f ->
    KeyStore.getInstance("PKCS12").run {
        f.inputStream().use { load(it, keystorePassword?.toCharArray()) }
        aliases().toList().firstOrNull()
    }
}

android {
    namespace = "io.github.lcebot.mivolumeenhanced"
    // 37.1 because the Compose 1.13 pre-releases that Material 3 1.5.0 depends on declare it, and
    // AGP's checkAarMetadata fails the build below it. The CI installs the matching
    // platforms;android-37.1 package. compileSdk only decides which APIs the compiler can see; it
    // does not change behaviour on any device, which is targetSdk's job.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.lcebot.mivolumeenhanced"
        // The hooks target the HyperOS volume plugin and MiSound, which only exist on HyperOS, and
        // every HyperOS release the hooks know runs Android 14 or newer.
        minSdk = 34
        targetSdk = 36
        // The only native code in the APK is Compose's dependency androidx.graphics:graphics-path,
        // which loads its library on every API level, so it cannot be dropped. Only arm64-v8a is
        // packaged, since the phones this module is for (HyperOS, rooted, with LSPosed) are 64-bit
        // ARM. A device without arm64-v8a cannot install the APK at all, hooks included.
        ndk {
            abiFilters += "arm64-v8a"
        }
        // Both come from a git tag, which the workflow parses and passes in as -P: a release uses
        // its own tag, any other build uses the newest one plus the short commit in versionName
        // (1.0.3+a1b2c3d) while keeping that tag's versionCode. The values below are only the
        // fallback for a build with no tag in reach (a fresh clone with no tags, or a local build).
        //
        // Tags are vA.B.C with A and B one digit and C up to two, and versionCode is
        // A*1000 + B*100 + C, so 1.0.0 is 1000, 1.0.12 is 1012 and 1.1.0 is 1100. Android only
        // requires that the number never decreases, which that ordering guarantees.
        versionCode = releaseVersionCode ?: 1000
        versionName = releaseVersionName ?: "1.0"
    }

    signingConfigs {
        if (keystoreAlias != null) create("release") {
            storeFile = keystoreFile
            storeType = "PKCS12"
            storePassword = keystorePassword
            keyAlias = keystoreAlias
            // PKCS12 keeps one password for the whole store; Export-PfxCertificate never sets a
            // separate one, so the store password is the key password unless told otherwise.
            keyPassword = signingValue("keyPassword", "KEY_PASSWORD") ?: keystorePassword
            // v1 is JAR signing, only consulted below API 24 and dead weight at this minSdk. v3
            // carries the proof-of-rotation lineage: without a v3 block in the installed APK there
            // is no supported way to ever move this app to a different key. v4 only buys
            // incremental `adb install` and needs its own .idsig file alongside the APK.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            if (keystoreAlias != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        // libxposed api 102 uses sealed interfaces and records, which need Java 17. Kotlin's
        // jvmTarget follows targetCompatibility, so both languages emit the same bytecode level.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.libxposed.api)                  // libxposed API 102, the only entry (hook.HookEntry)

    val composeBom = platform(libs.compose.bom.beta)
    implementation(composeBom)
    implementation(libs.compose.material3)            // 1.5.0-beta02 through the beta BOM
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    // Also the source of androidx.annotation. Pinning annotation separately collides with AGP's
    // consistent runtime and compile resolution. The Kotlin extensions live in core itself;
    // core-ktx is an empty artifact kept for compatibility.
    implementation(libs.core)
    implementation(libs.coroutines.android)
}

import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePath = providers.environmentVariable("AF_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("AF_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("AF_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("AF_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
val testOptimizedRelease = providers.gradleProperty("afTestOptimizedRelease").orNull.toBoolean()

android {
    namespace = "com.affilemanager.app"
    compileSdk = 36
    ndkVersion = "27.3.13750724"
    testBuildType = if (testOptimizedRelease) "release" else "debug"

    defaultConfig {
        applicationId = "com.affilemanager.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "0.16.0"

        buildConfigField("String", "UPDATE_REPOSITORY", "\"sinegard/AF-File-Manager\"")

        testInstrumentationRunner = if (testOptimizedRelease) {
            "com.affilemanager.app.network.OptimizedSftpInstrumentation"
        } else {
            "androidx.test.runner.AndroidJUnitRunner"
        }
        testProguardFiles("test-proguard-rules.pro")
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.connectbot:termlib:0.1.0")

    implementation("com.github.mwiede:jsch:2.28.6")
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("commons-net:commons-net:3.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    implementation("com.github.junrar:junrar:7.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.register("verifyReleaseDynamicRuntimeClasses") {
    group = "verification"
    description = "Checks runtime-resolved classes in the exact optimized release APK."
    dependsOn("assembleRelease")

    doLast {
        val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(releaseApk.isFile && releaseApk.length() > 0L) {
            "Optimized release APK was not produced: ${releaseApk.absolutePath}"
        }

        val requiredDescriptors = listOf(
            "Lcom/affilemanager/app/network/SftpRuntimeVerifier;",
            "Lcom/jcraft/jsch/JSchException;",
            "Lcom/jcraft/jsch/UserAuthPassword;",
            "Lcom/jcraft/jsch/DH25519;",
            "Lcom/jcraft/jsch/jce/AES256CTR;",
            "Lcom/jcraft/jsch/jce/SignatureEd25519;",
        )
        val dexContents = ZipFile(releaseApk).use { archive ->
            archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("classes\\d*\\.dex")) }
                .map { entry -> archive.getInputStream(entry).use { it.readBytes() } }
                .toList()
        }
        check(dexContents.isNotEmpty()) { "Release APK contains no DEX files" }

        fun ByteArray.containsBytes(needle: ByteArray): Boolean {
            if (needle.isEmpty()) return true
            if (needle.size > size) return false
            for (offset in 0..size - needle.size) {
                var matches = true
                for (index in needle.indices) {
                    if (this[offset + index] != needle[index]) {
                        matches = false
                        break
                    }
                }
                if (matches) return true
            }
            return false
        }

        val missing = requiredDescriptors.filterNot { descriptor ->
            val encoded = descriptor.toByteArray(Charsets.US_ASCII)
            dexContents.any { it.containsBytes(encoded) }
        }
        check(missing.isEmpty()) {
            "Optimized release APK is missing runtime-resolved classes: ${missing.joinToString()}"
        }
    }
}

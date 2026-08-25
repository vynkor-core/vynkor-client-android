import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "dev.vynkor.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.vynkor.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // R-26: per-ABI APKs (~1/3 the download); a universal APK is still built
    // so existing install scripts keep working.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            // Credentials arrive from the environment/CI — never committed.
            val ksPath = System.getenv("VYNKOR_RELEASE_STORE") ?: ""
            if (ksPath.isNotBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("VYNKOR_RELEASE_STORE_PASS")
                keyAlias = System.getenv("VYNKOR_RELEASE_KEY_ALIAS") ?: "vynkor"
                keyPassword = System.getenv("VYNKOR_RELEASE_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            // R-21: R8 + resource shrinking. Keep rules live in
            // app/proguard-rules.pro (UniFFI/JNA, sherpa-onnx JNI).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        // R-31: generated bindings instead of findViewById.
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    androidResources {
        // Keep .onnx model files uncompressed so sherpa-onnx can read them
        // straight from the asset manager.
        noCompress += "onnx"
    }

    sourceSets {
        getByName("main") {
            // generated Kotlin bindings + jniLibs land here
            java.srcDir("$projectDir/build/generated/uniffi/kotlin")
            jniLibs.srcDir("$projectDir/build/rustLibs")
        }
    }
}

dependencies {
    // UniFFI-generated Kotlin bindings use JNA to reach the Rust cdylib.
    // Use the AAR, not the JAR: the JAR's native libjnidispatch.so never
    // lands in the APK (libjnidispatch is packaged per-ABI in the AAR).
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.markwon.core)
    // Rich AI-answer rendering: tables, strikethrough, auto-links and
    // prism4j syntax highlighting for fenced code blocks.
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.linkify)
    // prism4j pulls legacy annotations-java5 which duplicates
    // org.jetbrains:annotations classes -> checkDebugDuplicateClasses.
    implementation(libs.markwon.syntax.highlight) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    kapt(libs.prism4j.bundler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    // App-entry gate: BiometricPrompt (fingerprint) with PIN fallback.
    implementation(libs.androidx.biometric)
    // QR pairing: scan the host's `vynkor://pair` QR to fill a profile.
    implementation(libs.zxing.android.embedded)
    // On-device speech-to-text (local models in app/src/main/assets/stt/).
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))

    // R-30: JVM unit tests. Robolectric fakes the Android framework so
    // PairingPayload (Uri), HostProfile and ChatStore (org.json, prefs) are
    // testable without a device.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// Build the Rust core with cargo-ndk and copy the .so files next to the
// generated bindings. Declares inputs/outputs (R-26) so Gradle skips the
// multi-ABI release rebuild when rust/ sources are unchanged.
tasks.register<Exec>("cargoNdkBuild") {
    workingDir = File(rootProject.projectDir, "rust")
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/.android-sdk")
    val outDir = File(projectDir, "build/rustLibs")
    inputs.files(fileTree("$rootDir/rust/src"))
    inputs.file("$rootDir/rust/Cargo.toml")
    outputs.dir(outDir)
    doFirst {
        outDir.mkdirs()
    }
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", outDir.absolutePath,
        "build", "--release",
    )
}

tasks.named("preBuild") {
    dependsOn("cargoNdkBuild")
}

// Generate the Kotlin bindings from the compiled cdylib. Runs from the rust/
// crate dir so uniffi finds Cargo.toml + uniffi.toml.
tasks.register<Exec>("uniffiBindgen") {
    val soDir = File(projectDir, "build/rustLibs/arm64-v8a")
    val outDir = File(projectDir, "build/generated/uniffi/kotlin")
    workingDir = File(rootProject.projectDir, "rust")
    dependsOn("cargoNdkBuild")
    doFirst {
        outDir.mkdirs()
    }
    commandLine(
        "uniffi-bindgen", "generate",
        "--library", File(soDir, "libvynkor_agent_core.so").absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
    )
}

tasks.named("preBuild") {
    dependsOn("uniffiBindgen")
}

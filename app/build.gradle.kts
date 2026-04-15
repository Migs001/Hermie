plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hermie.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hermie.assistant"
        minSdk = 28          // Android 9+ (for foreground services, usage stats)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Build for arm64 (most phones) — llama.cpp native lib targets arm64
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Native build is in the :lib module (llama.cpp from source with full optimizations)

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Avoid duplicate .so conflicts between sherpa-onnx and llama.cpp
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
            // Extract native libs to disk so ggml_backend_load_all_from_path() can find them
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // ── Compose BOM (keeps all Compose versions aligned) ──
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // ── Core Android ──
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ── Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── Archive extraction (for espeak-ng-data tar.bz2) ──
    implementation("org.apache.commons:commons-compress:1.27.1")

    // ── LLM inference (llama.cpp built from source — supports Qwen 3, latest optimizations) ──
    implementation(project(":lib"))

    // ── TFLite (MiniLM-L6-v2 embedding engine for memory retrieval) ──
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // ── PdfBox-Android (PDF text extraction for Study module) ──
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // ── Sherpa-ONNX (offline Whisper STT + Piper TTS) ──
    // AAR downloaded from https://github.com/k2-fsa/sherpa-onnx/releases
    implementation(files("libs/sherpa-onnx.aar"))

    // ── Debug tooling ──
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

import com.android.build.api.dsl.ManagedVirtualDevice
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.paparazzi)
}

// Pinned NDK version (android.ndkVersion + referenced by the OpenCL CI toolchain step).
val ndkVersionPin = "27.2.12479018"

// GPU backend = OpenCL (Qualcomm/Adreno-native), enabled per-ABI (arm64 only) in
// src/main/cpp/CMakeLists.txt. The OpenCL headers + the Khronos ICD loader
// (libOpenCL.so) are installed into the NDK sysroot by the "Set up OpenCL
// toolchain" CI step, so find_package(OpenCL) resolves with no build.gradle
// wiring; libOpenCL.so is also copied into jniLibs for runtime. (This replaces the
// earlier Vulkan path, which crashed on Adreno 830.)

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    namespace = "com.druk.lmplayground"

    defaultConfig {
        applicationId = "com.druk.lmplayground"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        val versionProps = Properties().apply {
            rootProject.file("version.properties").inputStream().use { load(it) }
        }
        val major = versionProps.getProperty("major").toInt()
        val minor = versionProps.getProperty("minor").toInt()
        val patch = versionProps.getProperty("patch").toInt()
        versionName = "$major.$minor.$patch"
        // versionCode = base × 1000 + CI run number. Local builds use 0.
        // Keeps ~1000 CI builds per patch before needing a version bump.
        versionCode = (major * 10000 + minor * 100 + patch) * 1000 +
            (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                // GGML_OPENCL is enabled per-ABI (arm64 only) in CMakeLists.txt;
                // the OpenCL headers + libOpenCL.so are staged into the NDK
                // sysroot by the "Set up OpenCL toolchain" CI step, so a build
                // without that step fails at find_package(OpenCL) — the two land
                // together. (The GPU is opt-in behind the Settings toggle.)
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // Shared libs + dynamic CPU backend loading. Builds one
                // libggml-cpu-<variant>.so per ARM feature level (NEON,
                // dotprod, i8mm, SVE, ...) and picks the best match at
                // runtime via dlopen. Big prompt-eval speedups on
                // Snapdragon 8 Gen 1+, Dimensity 9000+, Tensor G3+.
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
            }
        }

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    ndkVersion = ndkVersionPin

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }

    // ggml_backend_load_all_from_path uses opendir on the JNI lib dir to
    // pick the best CPU variant, so the .so files must be extracted to a
    // real filesystem path (the modern default keeps them packed inside
    // the APK). Pairs with android:extractNativeLibs="true" in the
    // manifest.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        // We use a bundled debug keystore, to allow debug builds from CI to be upgradable
        val userKeystore = File(System.getProperty("user.home"), ".android/keystore.jks")
        val localKeystore = rootProject.file("debug.keystore")
        val hasKeyInfo = userKeystore.exists()
        named("debug") {
            storeFile = if (hasKeyInfo) userKeystore else localKeystore
            storePassword = if (hasKeyInfo) System.getenv("STORE_PASSWORD") else "android"
            keyAlias = if (hasKeyInfo) System.getenv("LM_PLAYGROUND_KEY_ALIAS") else "androiddebugkey"
            keyPassword = if (hasKeyInfo) System.getenv("LM_PLAYGROUND_KEY_PASSWORD") else "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isJniDebuggable = false
            // Use a distinct applicationId so debug + androidTest installs
            // coexist with any Play Store / release-signed build on the device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    testOptions {
        managedDevices {
            allDevices {
                maybeCreate<ManagedVirtualDevice>("mvdApi35").apply {
                    device = "Pixel"
                    apiLevel = 35
                    systemImageSource = "google"
                    require64Bit = true
                }
                maybeCreate<ManagedVirtualDevice>("mvdTablet7Api35").apply {
                    device = "7in WSVGA (Tablet)"
                    apiLevel = 35
                    systemImageSource = "google"
                    require64Bit = true
                }
            }
        }
    }

    packaging.resources {
        // Multiple dependency bring these files in. Exclude them to enable
        // our test APK to build (has no effect on our AARs)
        excludes += "/META-INF/AL2.0"
        excludes += "/META-INF/LGPL2.1"
    }
}

// ── Play Store screenshot organization ───────────────────────────────
// Paparazzi writes files like "pkg_StoreScreenshots_scene0_hero[French].png"
// Play Store expects fastlane/metadata/android/{locale}/images/phoneScreenshots/{n}_{name}.png
val paparazziLocaleToPlayStore = mapOf(
    "English" to "en-US", "Spanish" to "es-ES", "Portuguese" to "pt-BR",
    "French" to "fr-FR", "German" to "de-DE", "Italian" to "it-IT",
    "Polish" to "pl-PL", "Ukrainian" to "uk", "Romanian" to "ro",
    "Turkish" to "tr-TR", "Arabic" to "ar", "Chinese" to "zh-CN",
    "Japanese" to "ja-JP", "Korean" to "ko-KR", "Indonesian" to "id",
    "Hindi" to "hi-IN", "Vietnamese" to "vi",
    "Thai" to "th", "Dutch" to "nl-NL", "Hebrew" to "iw-IL",
    "Czech" to "cs-CZ", "Swedish" to "sv-SE", "Bengali" to "bn-BD",
    "Malay" to "ms", "Filipino" to "fil", "Norwegian" to "nb-NO",
    "Danish" to "da-DK", "Finnish" to "fi-FI"
)

val sceneRenames = mapOf(
    "scene0_hero" to "0_hero",
    "scene1_chooseModel" to "1_choose_model",
    "scene2_chat" to "2_chat",
    "scene3_generationParams" to "3_generation_params",
    "scene4_modelsDownload" to "4_models_download",
    "scene5_systemPrompts" to "5_system_prompts",
    "scene6_tools" to "6_tools",
    "scene7_notification" to "7_notification"
)

// TabletStoreScreenshots emits per-variant English-only landscape shots
// into fastlane's tablet-specific subdirectories. The variant displayName
// (`SevenInch` / `TenInch`) appears as the `[...]` suffix in the
// Paparazzi filename and routes the file to the right subfolder.
val tabletVariantToSubdir = mapOf(
    "SevenInch" to "sevenInchScreenshots",
    "TenInch" to "tenInchScreenshots",
)

tasks.register("organizeScreenshotsForPlayStore") {
    group = "play store"
    description = "Renames Paparazzi snapshots into fastlane/metadata phone + tablet layouts."

    val snapshotsDir = file("src/test/snapshots/images")
    val fastlaneDir = rootProject.file("fastlane/metadata/android")
    val phoneRegex = Regex("""_StoreScreenshots_(scene\d_\w+)\[(\w+)]\.png""")
    // Tablet filename has the form `[<VariantName>_<LocaleName>]`,
    // e.g. `[SevenInch_French]` — the test class composes both into
    // the parameterized name. Capture each piece separately so we
    // can route to `<locale>/images/<variant>Screenshots/`.
    val tabletRegex = Regex("""_TabletStoreScreenshots_(scene\d_\w+)\[(\w+)_(\w+)]\.png""")

    inputs.dir(snapshotsDir)
    outputs.dir(fastlaneDir)

    doLast {
        if (!snapshotsDir.exists()) {
            logger.warn("No Paparazzi snapshots found at $snapshotsDir — run recordPaparazziDebug first.")
            return@doLast
        }
        var copied = 0
        snapshotsDir.listFiles()?.forEach { src ->
            // Tablet files are checked first so the phone regex (which is
            // a substring match) doesn't accidentally pick them up if the
            // class names ever drift.
            tabletRegex.find(src.name)?.let { match ->
                val scene = sceneRenames[match.groupValues[1]] ?: return@let
                val subdir = tabletVariantToSubdir[match.groupValues[2]] ?: return@let
                val locale = paparazziLocaleToPlayStore[match.groupValues[3]] ?: return@let
                val destDir = File(fastlaneDir, "$locale/images/$subdir").apply { mkdirs() }
                src.copyTo(File(destDir, "$scene.png"), overwrite = true)
                copied++
                return@forEach
            }
            phoneRegex.find(src.name)?.let { match ->
                val scene = sceneRenames[match.groupValues[1]] ?: return@let
                val locale = paparazziLocaleToPlayStore[match.groupValues[2]] ?: return@let
                val destDir = File(fastlaneDir, "$locale/images/phoneScreenshots").apply { mkdirs() }
                src.copyTo(File(destDir, "$scene.png"), overwrite = true)
                copied++
            }
        }
        logger.lifecycle("Organized $copied screenshots into $fastlaneDir")
    }
}

tasks.matching { it.name == "recordPaparazziDebug" }.configureEach {
    finalizedBy("organizeScreenshotsForPlayStore")
}

// Paparazzi 2.0.0-alpha04 + current Gradle ships a transitive
// reporting extension that references an `org/gradle/reporting/
// HtmlWriterTools` class that no longer exists. The HTML report
// step crashes after tests succeed, marking the build FAILED and
// blocking the finalizedBy above from firing. We don't need the
// HTML test report for Paparazzi snapshot tests anyway — the
// snapshots themselves are the artifact. Disabling it lets a
// single `recordPaparazziDebug` invocation finish cleanly and
// auto-trigger the organize task.
tasks.withType<Test>().configureEach {
    reports.html.required.set(false)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.lifecycle.viewModelCompose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.google.android.material)

    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialWindow)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.util)
    implementation(libs.androidx.compose.ui.googlefonts)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp3)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation("androidx.javascriptengine:javascriptengine:1.0.0-beta01")

    // LiteRT-LM (Google AI Edge): a SECOND on-device engine beside llama.cpp, for
    // running Gemma 4 .litertlm models with their built-in MTP speculative-decoding
    // drafter (the real 2-3x decode speedup llama.cpp self-MTP couldn't deliver).
    // Prebuilt AAR with native .so bundled (arm64-v8a + x86_64, 16KB-aligned),
    // Apache-2.0, minSdk 23. Pinned: re-verify the native ABI + API before bumping.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.jlatexmath.android)

    implementation(libs.haze)
    implementation(libs.coil.kt.compose)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

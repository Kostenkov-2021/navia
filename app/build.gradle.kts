import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// Release signing. Reads from keystore.properties (repo root, git-ignored — never commit real
// secrets) rather than hardcoding the store/key passwords here, since this file IS committed. The
// existence check means a checkout without that file (any other machine, CI without secrets, etc.)
// still builds fine — it just can't produce a *signed* release build, which is the correct failure
// mode rather than a hard Gradle-sync error.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.wikiponder.touchmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wikiponder.touchmap"
        minSdk = 26
        // Must stay within Google Play's "target a recent API level" window (they currently require
        // API 35+ for new uploads). The two enforced behaviour changes this crosses were both checked
        // on a real Android 15 device:
        //   - API 35+ forces edge-to-edge (content draws behind the system bars). No layout change was
        //     needed here, because BaseActivity already runs every screen in IMMERSIVE_STICKY with
        //     both bars hidden (isFullScreen defaults to true and onWindowFocusChanged re-applies
        //     hideSystemUI on every focus gain), so there are no visible system bars to inset against
        //     and getRootWindowInsets() reports zero for systemBars() once they're hidden. Verified by
        //     comparing button bounds before/after the bump: identical. Do NOT add system-bar inset
        //     padding here — an earlier attempt did, and it briefly applied real insets on the first
        //     dispatch (top=80/bottom=144) before hideSystemUI zeroed them, i.e. a visible jump for
        //     no benefit. If the app ever stops hiding the bars, that padding becomes necessary.
        //   - API 36 turns on predictive back. The app already routes Back through AndroidX
        //     OnBackPressedCallback (MapActivity's aiming/search cancel, BaseActivity's
        //     double-back-to-exit), which is the predictive-back-compatible API.
        targetSdk = 36
        // versionCode 1 was consumed by an upload Play rejected for its 64-bit requirement — the
        // Google VR SDK vendored at the time shipped x86 with no x86_64. That SDK (and its
        // ndk.abiFilters ARM-only workaround) is gone now — see the dependencies block's
        // lifecycle-process comment and AudioClient.playPointDirectional — and a debug build of
        // the current tree has zero .so files in it, so the 64-bit requirement is moot either way.
        // Bumped past 2 (which would also have been valid) only because that value was reachable
        // by a separate, already-pushed change before this history was known.
        // Bumped from 3/"1.0" past a versionCode 4/"1.1" build that was installed on a test S21 but
        // never tracked in this checkout's history — it's what was on-device when the stuck-at-98%/
        // cancel-doesn't-dismiss box-download bug (see BoxImporter/ImportService/MapActivity's
        // cancelled-flag and ImportState.finish() handling) was diagnosed and fixed.
        versionCode = 12
        versionName = "1.1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 shrink/obfuscate/optimize. Play's DEX-optimization check flagged obfuscation at 2%
            // (needs >=25%). The app has no reflection, JS interfaces or serialization of its own
            // (grepped), so the default rules suffice; Crashlytics uploads the mapping file itself.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    // -PtestRelease runs androidTest against the release build, for devices that carry a
    // release-signed install: a debug install fails there with a signature mismatch, and
    // uninstalling first would wipe the user's maps and edits.
    if (project.hasProperty("testRelease")) {
        testBuildType = "release"
        // The test runner expects the app to provide the libraries both APKs share, which R8
        // would otherwise strip from the app.
        buildTypes.getByName("release").apply {
            proguardFile("proguard-test-release.pro")
            isShrinkResources = false
            // Lets `adb shell run-as` read the app's private files (e.g. saved place photos).
            isDebuggable = true
        }
    }
    compileOptions {
        //sourceCompatibility = JavaVersion.VERSION_1_8
        //targetCompatibility = JavaVersion.VERSION_1_8
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint {
        // AGP's own "lint vital" pass (which runs automatically on every release build, separate
        // from the full `lint`/`lintRelease` task) crashes on this project on Windows: its
        // GradleDetector tries to check a dependency's cached-newer-version by parsing a path, and
        // the group id it builds contains a stray quote character, which java.nio's Windows path
        // parser rejects outright (InvalidPathException) — an AGP/lint tooling bug, not anything
        // about this project's actual dependencies. Confirmed by validateSigningRelease and every
        // real compile/dex/package task passing fine; only this specific lint-vital check crashes.
        // Disabling it here only skips that automatic release-build gate — `./gradlew lintDebug` /
        // `lintRelease` still run the real, useful lint suite on demand.
        checkReleaseBuilds = false
    }
}

dependencies {
    // Crash reporting (see classes/CrashReporting) — reports land in the Firebase console for the
    // touchmap-testlab project, automatically, without any action from the crashing user.
    implementation(platform("com.google.firebase:firebase-bom:${libs.versions.firebaseBom.get()}"))
    implementation("com.google.firebase:firebase-crashlytics")

    implementation("org.msgpack:msgpack-core:0.9.12")
    // NOTE: this app used to vendor Google's VR/Resonance audio engine (GvrAudioEngine) here for a
    // spatialized point-touch earcon. It was removed because its native audio path is hard-wired
    // to USAGE_MEDIA with no API to route it to the Accessibility stream — see AudioClient's
    // playPointDirectional, which replaced it with 18 pre-rendered binaural clips (one per 20
    // degrees, tools/generate_sounds.py's build_point_dir_set) played through the app's own
    // Accessibility-routed SoundPool instead.
    implementation(libs.preference)

    // NOTE: this module was forked from an "AICam" project (hence @style/Theme.AICam) and inherited
    // its entire ML Kit stack — text recognition in 5 scripts, barcode scanning, translation, image
    // labeling, object detection — plus androidx.palette, constraintlayout, navigation and the
    // lifecycle ViewModel/LiveData artifacts. None of them are referenced anywhere in this app's
    // sources (verified by import-scanning every .java and every layout), and between them they were
    // pulling in ~150MB of unused native libraries (libtranslate_jni.so, libmlkit_google_ocr_pipeline.so,
    // libmlkitcommonpipeline.so, ... per ABI). Deliberately NOT re-added without a real call site.

    // lifecycle-process IS a real call site (MyApplication's ProcessLifecycleOwner observer, which
    // plays the welcome chime). Distinct from — and much lighter than — the ViewModel/LiveData
    // artifacts excluded just above: it needs only lifecycle-common/-runtime transitively, no
    // ViewModel/LiveData/Fragment machinery. ProcessLifecycleOwner fires ON_START exactly once per
    // real "app came to the foreground" (true cold start, or reopening from Home/Recents/a
    // notification — all cases a user would call "opening the app"), and — critically — does NOT
    // fire again during a same-process Activity recreation (MapActivity's locale-change recreate(),
    // or its permission-retry recreate()): the whole-process started-activity count never actually
    // reaches zero during those, only during a real backgrounding. A hand-rolled
    // Application.ActivityLifecycleCallbacks counter doesn't get this right for free — telling
    // "recreate" apart from "backgrounded then reopened" needs the same debounce logic this artifact
    // already implements.
    implementation(libs.lifecycle.process)

    implementation(libs.appcompat)
    // Still required: the app theme resolves to a Material theme, so AlertDialog buttons etc. are
    // MaterialButton at runtime even though no layout names a Material widget directly.
    implementation(libs.material)
    // Declared explicitly rather than leaning on material's transitive copy — NodeListActivity and
    // activity_node_list.xml both use RecyclerView directly, so it's a first-order dependency here.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation(libs.androidx.core)
    implementation(libs.androidx.media)

    // androidx.credentials + googleid are deliberately absent: the Google sign-in feature they
    // backed was removed (it gated no functionality — it only displayed who was signed in — while
    // adding a Play Data Safety declaration burden).
    implementation(libs.play.services.location)
    implementation("androidx.annotation:annotation:1.9.1")
    implementation(libs.androidx.activity)
    implementation(libs.constraintlayout)

    // Visual place recognition (experimental, classes/vpr): MegaLoc place descriptors run with ONNX
    // Runtime (model exported by tools/vpr/export_megaloc.py), camera frames from CameraX.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
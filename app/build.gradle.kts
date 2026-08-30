import java.util.Properties

// Upload-key credentials, kept out of the repo. Absent on a machine that has
// never needed to sign a release — `assembleRelease` there produces an
// unsigned APK rather than failing, which is what CI and a fresh clone want.
val uploadKeyProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.rust)
}

android {
    namespace = "dev.heyari.ari"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.heyari.ari"
        minSdk = 29
        targetSdk = 36
        // Bump by hand, once per upload to Play, and never for a local build.
        // Play refuses a versionCode it has already seen and there is no way
        // back down, so a scheme that derives this from anything is a scheme
        // that can silently collide: the APK is half `ari-engine`, and a
        // commit count here would repeat itself every time only the Rust
        // moved. Unrelated to versionName, which is the string users see —
        // five test uploads in a week is 1..5 against a versionName that
        // never budges.
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Must match the Rust targets built by `androidRust` below
            // (arm64 + x86_64). Advertising an ABI we don't build a Rust
            // `ari-ffi` .so for ships a slice that crashes on load — so no
            // armeabi-v7a until the Rust build produces one.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    ndkVersion = "28.0.13004108"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        // A debug keystore committed to the repo, so every machine and CI
        // runner signs debug builds with the SAME certificate.
        //
        // Android App Links are bound to the signing certificate. With the
        // per-machine keystore the SDK generates by default, every new dev
        // machine produces a fingerprint that heyari.dev's assetlinks.json has
        // never heard of — Android then reports `heyari.dev: legacy_failure`
        // and silently refuses to hand the OAuth callback to the app, so
        // Home Assistant sign-in dead-ends in the browser with nothing to
        // explain itself. One shared key means one fingerprint to publish.
        //
        // Committing a debug key is normal practice and it protects nothing:
        // the point is reproducible signing, not secrecy. The trade-off is
        // real though — the fingerprint is public, so anyone can build a debug
        // APK that App Links will trust for heyari.dev. The OAuth flow uses
        // PKCE (S256), so an intercepted authorization code is not redeemable
        // without the verifier that never leaves the real app. Drop this
        // fingerprint from the production assetlinks.json once release and
        // F-Droid signing exist; until then it is the only way in.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // The upload key. Play re-signs with its own app signing key before
        // anything reaches a device, so this proves "Keith uploaded it" and
        // nothing more — and Play Console can reset it if it's ever lost.
        // Sideloaded release builds are a different matter: those carry THIS
        // certificate, so its SHA-256 is what heyari.dev's assetlinks.json
        // needs for App Links to work off-store.
        if (uploadKeyProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(uploadKeyProperties.getProperty("storeFile"))
                storePassword = uploadKeyProperties.getProperty("storePassword")
                keyAlias = uploadKeyProperties.getProperty("keyAlias")
                keyPassword = uploadKeyProperties.getProperty("keyPassword")
                // v3 carries a rotation lineage, so this certificate can one
                // day vouch for its replacement instead of the new one looking
                // like a different app. v2 is spelled out beside it because
                // AGP only infers the scheme set while you set none of it by
                // hand — enable v3 alone and v2 drops off silently.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidRust {
    module("ari-ffi") {
        path = file("../../ari-engine/crates/ari-ffi")

        buildType("debug") {
            profile = "dev"
            targets = listOf("arm64", "x86_64")
        }
        buildType("release") {
            profile = "release"
            targets = listOf("arm64", "x86_64")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation(files("libs/sherpa-onnx-1.12.35.aar"))
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui.material3)
    implementation(libs.play.services.location)
    implementation(libs.androidx.media)
    implementation(libs.ramani.maplibre)

    testImplementation(libs.junit)
    // Real org.json for unit tests — the Android stub returns null from
    // optString etc. when `isReturnDefaultValues = true`, which breaks any
    // parser that relies on the real "" / JSONObject.NULL semantics.
    testImplementation("org.json:json:20251224")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

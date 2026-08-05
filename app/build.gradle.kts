plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.rust)
}

android {
    namespace = "dev.heyari.ari"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.heyari.ari"
        minSdk = 29
        targetSdk = 36
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
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
    implementation(libs.androidx.security.crypto)
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

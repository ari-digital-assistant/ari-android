import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

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
        versionCode = 6
        versionName = "0.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Whether this build went to a human who might file a bug report, as
        // opposed to a human who just wants to use Ari. Gates the bug-report
        // FAB, the onboarding testing-build warning, and the capture defaults.
        // Deliberately NOT BuildConfig.DEBUG: the build testers get is
        // release-signed and not debuggable, so DEBUG is false there and
        // gating on it would hide all three from the only people who need them.
        buildConfigField("boolean", "ARI_TESTING", "false")

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
            buildConfigField("boolean", "ARI_TESTING", "true")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // What testers get. Release-signed and not debuggable, so it behaves
        // like the shipped app and Play would accept it — but NOT minified,
        // because an R8-mangled stack trace in a bug report is worth nothing
        // and retracing it server-side is work we don't need yet. The price:
        // this is not byte-identical to what ships, so it is a build for
        // finding bugs, not for final performance numbers.
        create("beta") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "ARI_TESTING", "true")
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

    packaging {
        jniLibs {
            // llama.cpp's "common" helpers come out as a 4 MB library that
            // nothing in Ari links against (ari-android#32).
            excludes += "**/libllama-common.so"
        }
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
        // Optimised Rust, same as release — the readable-stack-trace argument
        // is about R8 on the Kotlin side and says nothing about the native lib.
        buildType("beta") {
            profile = "release"
            targets = listOf("arm64", "x86_64")
        }
    }
}

// On arm64, llama.cpp builds as shared libraries: libllama, libggml and
// libggml-base, which androidRust packages along with libari_ffi.so, and one
// libggml-cpu per ARM generation, of which the engine loads the best the
// phone supports (ari-engine/crates/ari-llm/src/cpu_build.rs, ari-android#32).
// Those stay in llama-cpp-sys-2's build output, so this copies them into a
// jniLibs folder of their own. The other CPU builds llama.cpp makes (ARMv8.2
// without half precision, ARMv9) stay out until a phone shows they beat these.
val llamaCpuBuilds = listOf("android_armv8.0_1", "android_armv8.2_2", "android_armv8.6_1")
mapOf("debug" to "debug", "release" to "release", "beta" to "release").forEach { (buildType, profileDir) ->
    val cap = buildType.replaceFirstChar { it.uppercase() }
    val llamaLibs = layout.buildDirectory.dir("generated/llamaLibs/$buildType")
    val cargoBuild = layout.buildDirectory.dir(
        "intermediates/rust/$buildType/lib_ari-ffi/aarch64-linux-android/$profileDir/build",
    )
    val copyLlamaLibs = tasks.register("copy${cap}LlamaLibs") {
        dependsOn("build${cap}Ari-ffiRust[arm64-v8a]")
        val dest = llamaLibs.map { it.dir("arm64-v8a") }
        outputs.dir(dest)
        // A few MB of copying, and Cargo decides what changed, not Gradle.
        outputs.upToDateWhen { false }
        doLast {
            val build = cargoBuild.get().asFile
            // The newest llama-cpp-sys-2 output: a changed feature or env
            // leaves the old one behind under another hash. Looked up here,
            // at run time, because the Rust build above is what creates it.
            val out = build
                .listFiles { dir -> dir.name.startsWith("llama-cpp-sys-2-") }
                ?.map { File(it, "out") }
                ?.filter { File(it, "lib/libllama.so").exists() }
                ?.maxByOrNull { File(it, "lib/libllama.so").lastModified() }
                ?: throw GradleException("no llama-cpp-sys-2 build output under $build")
            val libs = llamaCpuBuilds.map { File(out, "backends/libggml-cpu-$it.so") }
            val target = dest.get().asFile
            target.deleteRecursively()
            target.mkdirs()
            libs.forEach { it.copyTo(File(target, it.name)) }
        }
    }
    // A File, not the provider: AGP's source sets refuse providers.
    android.sourceSets.getByName(buildType).jniLibs.srcDir(llamaLibs.get().asFile)
    tasks.matching { it.name == "merge${cap}JniLibFolders" }.configureEach { dependsOn(copyLlamaLibs) }
}

// LiteRT-LM's C API library, which the engine opens at run time to run
// .litertlm and .task models (ari-engine/crates/ari-llm/src/litert.rs,
// ari-android#52). Google publishes it only inside this 123 MB release zip,
// so the zip is fetched once into Gradle's cache, checked against the SHA-256
// GitHub lists for the asset, and its two Android builds copied into a
// jniLibs folder of their own. Both are 16 KB page aligned.
val liteRtLmZipUrl = "https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.19.1/litert_lm_c_api-1.0.0.zip"
val liteRtLmZipSha256 = "1d430d80eaf536f696a4922270ba55eb3d112189ac198c3066d487a45e3b4d4c"
val liteRtLmLibs = layout.buildDirectory.dir("generated/liteRtLmLibs")
val fetchLiteRtLm = tasks.register("fetchLiteRtLm") {
    val zip = gradle.gradleUserHomeDir.resolve("caches/ari/${liteRtLmZipUrl.substringAfterLast('/')}")
    val abis = mapOf("android_arm64" to "arm64-v8a", "android_x86_64" to "x86_64")
    inputs.property("sha256", liteRtLmZipSha256)
    outputs.dir(liteRtLmLibs)
    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!zip.isFile || sha256(zip) != liteRtLmZipSha256) {
            zip.parentFile.mkdirs()
            val part = File(zip.path + ".part")
            URI(liteRtLmZipUrl).toURL().openStream().use { input ->
                part.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256(part)
            if (actual != liteRtLmZipSha256) {
                part.delete()
                throw GradleException("$liteRtLmZipUrl has SHA-256 $actual, expected $liteRtLmZipSha256")
            }
            if (!part.renameTo(zip)) throw GradleException("couldn't move ${part.name} to $zip")
        }
        val out = liteRtLmLibs.get().asFile
        out.deleteRecursively()
        ZipFile(zip).use { archive ->
            abis.forEach { (dir, abi) ->
                val entry = archive.getEntry("lib/$dir/liblitert-lm.so")
                    ?: throw GradleException("no lib/$dir/liblitert-lm.so in ${zip.name}")
                val target = File(out, "$abi/liblitert-lm.so").apply { parentFile.mkdirs() }
                archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
android.sourceSets.getByName("main").jniLibs.srcDir(liteRtLmLibs.get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(fetchLiteRtLm)
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
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
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

import org.gradle.api.file.DirectoryProperty

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release signing comes only from the environment, never from a file in the tree:
// OFFLINKA_KEYSTORE, OFFLINKA_KEYSTORE_PASSWORD, OFFLINKA_KEY_ALIAS, OFFLINKA_KEY_PASSWORD.
// A release build without them fails (checkReleaseSigning); debug builds use the SDK's debug key.
val releaseSigningEnv = listOf("OFFLINKA_KEYSTORE", "OFFLINKA_KEYSTORE_PASSWORD", "OFFLINKA_KEY_ALIAS", "OFFLINKA_KEY_PASSWORD")
    .associateWith { System.getenv(it)?.takeIf(String::isNotEmpty) }
val releaseSigningMissing = releaseSigningEnv.filterValues { it == null }.keys

// CI (GitHub Actions sets CI=true) or -Pofflinka.unsignedRelease=true builds an UNSIGNED release to check
// that R8 and resource shrinking work. Never on a developer machine by accident: there it must fail.
val unsignedReleaseAllowed = System.getenv("CI") == "true" ||
    (project.findProperty("offlinka.unsignedRelease") as String?) == "true"

android {
    namespace = "cz.offlinka.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.offlinka.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        // Instrumented tests run the real app with a folder as the drive (src/androidTest: FixtureApp, FolderDrive).
        testInstrumentationRunner = "cz.offlinka.app.FixtureRunner"
        // Only the ABIs the Rust core is built for (scripts/build-android-core.sh). JNA would otherwise add
        // armeabi/mips/x86 copies of its dispatcher and such a phone would install an app without the core.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    androidResources {
        // Czech is the default (values/), plus Slovak and English. Drops other locales from libraries.
        localeFilters += listOf("cs", "sk", "en")
        generateLocaleConfig = true
        // The bundled guides book is stored uncompressed and copied once per app version to noBackupFilesDir.
        noCompress += "zim"
    }

    signingConfigs {
        if (releaseSigningMissing.isEmpty()) {
            create("release") {
                storeFile = file(releaseSigningEnv.getValue("OFFLINKA_KEYSTORE")!!)
                storePassword = releaseSigningEnv.getValue("OFFLINKA_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("OFFLINKA_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("OFFLINKA_KEY_PASSWORD")
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never falls back to the debug key: without the env vars checkReleaseSigning stops the build.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // One universal APK for the drive (all ABIs); Play gets the AAB and splits it itself.
    splits {
        abi { isEnable = false }
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
        jniLibs {
            // Native libs stay uncompressed and page-aligned in the APK (faster start, 16 KB pages).
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module", "DebugProbesKt.bin")
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        lintConfig = rootProject.file("lint.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "2g"
                val hostLib = System.getenv("OFFLINKA_HOST_CORE")
                    ?: rootProject.projectDir.parentFile.resolve("core/target/host/linux-x86-64/libofflinka_core.so").path
                test.systemProperty("uniffi.component.offlinka_core.libraryOverride", hostLib)
                test.systemProperty("offlinka.hostCore", hostLib)
                test.systemProperty(
                    "offlinka.driveFiles",
                    System.getenv("OFFLINKA_DRIVE_FILES") ?: "/srv/offlinka/drive/release/2026-11.3/files",
                )
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.jna)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.intents)
}

// ---- Design system from ../design: launcher icons (mipmaps) and the reader's assets ----------------
val designDir: File = rootProject.projectDir.parentFile.resolve("design")

abstract class DesignAppRes : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val androidOut: DirectoryProperty
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val out = output.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        androidOut.get().asFile.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") }!!
            .forEach { it.copyRecursively(out.resolve(it.name)) }
    }
}

/**
 * The reader's stylesheet and hook from design/reader plus the Archivo fonts, as assets/reader/
 * (served as https://offlinka.local/_offlinka/reader.css, inject.js, fonts/…).
 */
abstract class DesignReaderAssets : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reader: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: DirectoryProperty
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val out = output.get().asFile
        out.deleteRecursively()
        val dir = out.resolve("reader").apply { mkdirs() }
        reader.get().asFile.resolve("offlinka-reader.css").copyTo(dir.resolve("reader.css"))
        reader.get().asFile.resolve("inject.js").copyTo(dir.resolve("inject.js"))
        val fontDir = dir.resolve("fonts").apply { mkdirs() }
        fonts.get().asFile.listFiles { f -> f.extension == "ttf" }!!.forEach { it.copyTo(fontDir.resolve(it.name)) }
    }
}

val designReaderAssets = tasks.register<DesignReaderAssets>("designReaderAssets") {
    reader.set(designDir.resolve("reader"))
    fonts.set(designDir.resolve("fonts"))
    output.set(layout.buildDirectory.dir("generated/design/assets"))
}

val designAppRes = tasks.register<DesignAppRes>("designAppRes") {
    androidOut.set(designDir.resolve("out/android"))
    output.set(layout.buildDirectory.dir("generated/design/res"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(designAppRes, DesignAppRes::output)
        variant.sources.assets?.addGeneratedSourceDirectory(designReaderAssets, DesignReaderAssets::output)
    }
}

// A release without the Rust core would install and then fail to open any book: refuse to build it.
abstract class CheckNativeLibs : DefaultTask() {
    @get:Internal
    abstract val jniLibs: DirectoryProperty

    @TaskAction
    fun run() {
        val missing = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            .filterNot { jniLibs.get().asFile.resolve("$it/libofflinka_core.so").isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("libofflinka_core.so missing for $missing: run scripts/build-android-core.sh first")
        }
    }
}

val checkNativeLibs = tasks.register<CheckNativeLibs>("checkNativeLibs") {
    jniLibs.set(layout.projectDirectory.dir("src/main/jniLibs"))
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkNativeLibs) }

// A release (APK or AAB) without the signing env vars fails loudly instead of producing an unsigned or
// debug-signed build. Unit tests, lint and debug builds do not need them.
val checkReleaseSigning by tasks.registering {
    val missing = releaseSigningMissing.toList()
    val unsignedOk = unsignedReleaseAllowed
    doLast {
        if (missing.isNotEmpty() && unsignedOk) {
            logger.warn("offlinka: CI build, release is UNSIGNED (not installable, not for distribution)")
        } else if (missing.isNotEmpty()) {
            throw GradleException("Release signing: set ${missing.joinToString()} (the key never lives in the repository).")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

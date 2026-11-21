import java.util.Properties
import org.gradle.api.file.DirectoryProperty

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release signing never lives in git: env vars win, else a keystore.properties file
// (path in OFFLINKA_KEYSTORE_PROPERTIES, default android/keystore.properties, which is git-ignored)
// with storeFile, storePassword, keyAlias, keyPassword.
val keystoreProps = Properties().apply {
    val f = System.getenv("OFFLINKA_KEYSTORE_PROPERTIES")?.let(::File) ?: rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use(::load)
}
fun signing(prop: String, env: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
val releaseStoreFile = signing("storeFile", "OFFLINKA_KEYSTORE")

android {
    namespace = "cz.offlinka.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.offlinka.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only the ABIs the Rust core is built for (scripts/build-android-core.sh). JNA would otherwise add
        // armeabi/mips/x86 copies of its dispatcher and such a phone would install an app without the core.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    androidResources {
        // Czech is the default (values/), plus Slovak and English. Drops other locales from libraries.
        localeFilters += listOf("cs", "sk", "en")
        generateLocaleConfig = true
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signing("storePassword", "OFFLINKA_KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "OFFLINKA_KEY_ALIAS")
                keyPassword = signing("keyPassword", "OFFLINKA_KEY_PASSWORD")
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("offlinka: no release keystore configured, release is signed with the debug key")
            }
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

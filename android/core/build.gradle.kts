// UniFFI Kotlin bindings of the Rust core (generated into src/main/kotlin by scripts/gen-bindings.sh).
// The native libraries (libofflinka_core.so per ABI) are packaged by :app from app/src/main/jniLibs.
import org.gradle.api.file.DirectoryProperty

plugins {
    alias(libs.plugins.android.library)
}

val repoRoot: File = rootProject.projectDir.parentFile

android {
    namespace = "cz.offlinka.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Generated code: lint findings belong to the generator (lint.xml ignores the bindings).
        lintConfig = rootProject.file("lint.xml")
        ignoreWarnings = true
    }
    testOptions {
        unitTests.all { test ->
            // Host-JVM integration tests: the core built for linux-x86_64 (cargo build --release in core/)
            // and the real ZIMs of the current edition. Tests skip themselves when either is missing.
            val hostLib = System.getenv("OFFLINKA_HOST_CORE")
                ?: repoRoot.resolve("core/target/host/linux-x86-64/libofflinka_core.so").path
            test.systemProperty("uniffi.component.offlinka_core.libraryOverride", hostLib)
            test.systemProperty("offlinka.hostCore", hostLib)
            test.systemProperty(
                "offlinka.driveFiles",
                System.getenv("OFFLINKA_DRIVE_FILES") ?: "/srv/offlinka/drive/release/2026-11.3/files",
            )
            test.maxHeapSize = "1g"
        }
    }
}

dependencies {
    api(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// The generated bindings import com.sun.jna.Library (unused), which shadows the core's own `Library`
// class in Kotlin. Compile a copy without that import instead of the raw file.
abstract class FixBindings : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val out = output.get().asFile
        out.deleteRecursively()
        val src = source.get().asFile
        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val target = out.resolve(f.relativeTo(src).path)
            target.parentFile.mkdirs()
            target.writeText(f.readText().replace(Regex("(?m)^import com\\.sun\\.jna\\.Library\\s*$"), ""))
        }
    }
}

val fixBindings = tasks.register<FixBindings>("fixBindings") {
    source.set(layout.projectDirectory.dir("src/main/kotlin"))
    output.set(layout.buildDirectory.dir("generated/bindings/kotlin"))
}

android.sourceSets.getByName("main") {
    kotlin.setSrcDirs(emptyList<Any>())
    java.setSrcDirs(emptyList<Any>())
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(fixBindings, FixBindings::output)
    }
}

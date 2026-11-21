// UniFFI Kotlin bindings of the Rust core (generated into src/main/kotlin by scripts/gen-bindings.sh).
// The native libraries (libofflinka_core.so per ABI) are packaged by :app from app/src/main/jniLibs.
plugins {
    alias(libs.plugins.android.library)
}

val repoRoot: File = rootProject.projectDir.parentFile

android {
    namespace = "cz.offlinka.core"
    compileSdk = 36
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

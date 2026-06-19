plugins {
    // Foojay toolchain resolver — lets Gradle download the JDK 25 toolchain
    // (declared via jvmToolchain(25) in build.gradle.kts) when it is not already
    // installed locally. Without it, a machine lacking JDK 25 fails the build
    // instead of provisioning it. This is a settings plugin, so it is declared
    // here with an explicit version rather than through the `libs` catalog.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The Gradle project / artifact name. `scripts/init.ps1` stamps the real name in.
rootProject.name = "processkit"

// Type-safe dependency catalog. gradle/libs.versions.toml is picked up
// automatically as the `libs` catalog — no extra wiring needed here.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

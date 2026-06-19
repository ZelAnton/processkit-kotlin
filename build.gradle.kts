plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    // Publishing to Maven Central via the Sonatype Central Portal. This plugin
    // applies `maven-publish` + `signing` for you, builds the sources/javadoc
    // jars, and talks the Central Portal upload protocol (plain `maven-publish`
    // cannot). Remove it (and the `mavenPublishing` block below) for an app or
    // internal-only library.
    alias(libs.plugins.maven.publish)
    // Public-API tracking (opt-in). Uncomment, then run `./gradlew apiDump` once
    // and commit the generated `api/` directory. `apiCheck` then runs as part of
    // `build` and fails on any unintended public-API change — the natural
    // companion to explicitApi(). Left off by default so the template builds
    // green before a baseline exists.
    // alias(libs.plugins.binary.compatibility.validator)

    // Code coverage (opt-in, Kover). Uncomment to add koverHtmlReport /
    // koverVerify. Left off by default: Kover's plugin classpath can clash with
    // the Kotlin Gradle Plugin's embedded compiler on a bleeding-edge Kotlin
    // (e.g. 2.3), failing the build with a "Kotlin version mismatch". Enable once
    // a Kover release that matches your Kotlin version is available.
    // alias(libs.plugins.kover)
}

group = "net.zelanton"
// Version comes from `-Pversion=<x.y.z>` (the release workflow passes it) and
// falls back to 0.1.0 for local builds. Assigning project.version explicitly
// keeps `publishToMavenLocal` and the Central Portal coordinates in sync.
version = providers.gradleProperty("version").getOrElse("0.1.0")
description = "Kotlin/JVM child-process management: kill-on-close trees, streaming, pipelines, supervision."

kotlin {
    // Build and target JDK 25. Gradle provisions the toolchain via the foojay
    // resolver (settings.gradle.kts) when it isn't installed locally, so the
    // build doesn't depend on whatever JDK happens to be on PATH. Both the Kotlin
    // and Java tasks target 25, so the published artifact requires a JDK 25+
    // runtime — lower jvmToolchain(...) to support older JREs (and update the
    // README requirements to match).
    jvmToolchain(25)

    // Explicit API mode (strict): every public declaration must spell out its
    // visibility and return type. Recommended for libraries; drop to `warning`
    // or remove for an application.
    explicitApi()

    compilerOptions {
        // Warnings are build failures — the Kotlin analogue of the .NET
        // templates' TreatWarningsAsErrors.
        allWarningsAsErrors = true
    }
}

dependencies {
    // kotlin("test") routes to the JUnit Platform (Jupiter) backend because
    // junit-jupiter is on the test classpath and `useJUnitPlatform()` is set.
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // processkit binds platform APIs (Job Object / libc) through the Foreign
    // Function & Memory API; grant native access so the JVM doesn't warn (and,
    // on a future JDK, doesn't refuse). Consumers pass the same flag.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Supply-chain hygiene: ktlint 1.5.0 pulls logback 1.3.14 (CVE-2024-12798 /
// CVE-2024-12801). Force the patched 1.3.x onto ktlint's own classpath only —
// logback is a build-tool dependency, never on the library's compile/runtime
// classpath and never shipped in the published artifact. Remove this once a
// ktlint release brings a patched logback.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy {
        force(
            "ch.qos.logback:logback-classic:1.5.25",
            "ch.qos.logback:logback-core:1.5.25",
        )
    }
}

// ---------------------------------------------------------------------------
// Publishing — Maven Central via the Sonatype Central Portal.
//
// Applies when the project ships a library. For an app or internal-only library,
// delete this block and the `com.vanniktech.maven.publish` plugin alias above.
//
// Credentials are read from Gradle properties / environment variables (the
// release workflow provides them as ORG_GRADLE_PROJECT_* — see
// .github/workflows/release.yml):
//   mavenCentralUsername / mavenCentralPassword  - Central Portal user token
//   signingInMemoryKey / signingInMemoryKeyPassword - GPG key + passphrase
//
// `./gradlew build` and `publishToMavenLocal` do NOT require any of these; only
// the `publish*ToMavenCentral` tasks do.
// ---------------------------------------------------------------------------
mavenPublishing {
    // The plugin auto-detects the Kotlin/JVM project and produces the main jar
    // plus the sources and (empty) javadoc jars that Maven Central requires. To
    // ship real API docs, apply the Dokka plugin and call
    // `configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaHtml")))`.

    // Upload to the Central Portal. `automaticRelease = false` uploads a staging
    // deployment you release from the Portal UI; set it to true to auto-release
    // once validation passes.
    publishToMavenCentral(automaticRelease = false)

    // Central requires signed artifacts, but signing must NOT break key-free
    // builds: `publishToMavenLocal` (and any publish task) would otherwise fail
    // with "no configured signatory". Sign only when a key is provided — the
    // release workflow sets ORG_GRADLE_PROJECT_signingInMemoryKey, which Gradle
    // exposes as the `signingInMemoryKey` project property.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), rootProject.name, version.toString())

    pom {
        name = rootProject.name
        description = project.description
        url = "https://github.com/ZelAnton/processkit-kotlin"

        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                name = "Anton Zhelezniakou"
            }
        }
        scm {
            url = "https://github.com/ZelAnton/processkit-kotlin"
            connection = "scm:git:https://github.com/ZelAnton/processkit-kotlin.git"
            developerConnection = "scm:git:ssh://git@github.com/ZelAnton/processkit-kotlin.git"
        }
    }
}

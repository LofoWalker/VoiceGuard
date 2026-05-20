plugins {
    kotlin("jvm")
}

group = "com.voiceguard"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// validateEngine — Story 3.4: Gradle-triggered validation workflow
//
// Streams a local dataset directory through the full engine pipeline and
// prints an accuracy / FPR / latency report to stdout.
//
// Usage:
//   ./gradlew :voiceguard-engine:validateEngine -PdatasetPath=/path/to/dataset
//
// Dataset directory must contain `real/` (or `human/`) and `fake/` (or `ai/`)
// subdirectories holding 16-bit mono 16 kHz WAV files.
//
// Scope: JVM only — no Android Gradle plugin or instrumented test dependency.
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("validateEngine") {
    group = "verification"
    description = "Runs the dataset validation harness and prints detection KPI metrics."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.voiceguard.harness.ValidationRunner")

    val datasetPath: String? = findProperty("datasetPath") as String?
    if (datasetPath != null) {
        args(datasetPath)
    }
    // If not provided, ValidationRunner.main() will print a clear error and exit.

    dependsOn(tasks.named("classes"))
}


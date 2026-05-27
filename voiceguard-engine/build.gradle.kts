plugins {
    kotlin("jvm")
}

group = "com.voiceguard"
version = "0.3.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // MP3 decoding via javax.sound.sampled SPI — mp3spi registers itself automatically;
    // AudioSystem.getAudioInputStream(File) handles both WAV and MP3 transparently.
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")

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
//   ./gradlew :voiceguard-engine:validateEngine -PdatasetPath=/path/to/dataset -Pverbose
//
// Options:
//   -PdatasetPath=<dir>  (required) Root directory containing real/ and fake/ subdirectories.
//   -Pverbose            (optional) Print full list of misclassified files with per-rule detail.
//                        Without this flag only the count is shown.
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

    // Forward -Pverbose as a JVM system property so ValidationRunner can read it.
    // Bare -Pverbose yields an empty string; map that to "true". Explicit -Pverbose=false
    // propagates "false" so the runner can correctly disable verbose mode.
    val verboseProp = findProperty("verbose")
    if (verboseProp != null) {
        systemProperty("verbose", verboseProp.toString().ifEmpty { "true" })
    }

    dependsOn(tasks.named("classes"))
}

// ---------------------------------------------------------------------------
// sweepWeights — Weight-space exploration
//
// Runs N random weight combinations through the full pipeline and reports the
// best-performing configuration together with a ranked table of all trials.
// Use this to recalibrate rule weights after a dataset change without hand-tuning.
//
// Usage:
//   ./gradlew :voiceguard-engine:sweepWeights -PdatasetPath=/path/to/dataset
//   ./gradlew :voiceguard-engine:sweepWeights -PdatasetPath=/path/to/dataset \
//       -PnRuns=100 -Pstep=0.05 -Pobjective=FPR_CONSTRAINED_RECALL -Pseed=42
//
// Options:
//   -PdatasetPath=<dir>      (required) Root directory with real/ and fake/ subdirectories.
//   -PnRuns=<int>            (optional) Number of weight combinations to try. Default: 50.
//   -Pstep=<float>           (optional) Discretisation step for each weight in (0, 1].
//                            Smaller step → finer grid, more distinct combinations. Default: 0.05.
//   -Pparallelism=<int>      (optional) Concurrent workers. Default: physical core count (8 on
//                            this machine). Each worker runs a full ValidationRunner independently.
//   -Pobjective=<name>       (optional) Optimisation criterion:
//                              FPR_CONSTRAINED_RECALL  maximise recall with FPR ≤ 5% (default)
//                              ACCURACY                maximise raw accuracy
//                              F1                      maximise F1 score
//   -Pseed=<long>            (optional) PRNG seed for reproducibility. Default: 42.
//
// The report prints the best weight config and a Kotlin snippet ready to paste
// into RuleWeightConfig.PRODUCTION.
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("sweepWeights") {
    group = "verification"
    description = "Sweeps rule weights over N random combinations and reports the best config."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.voiceguard.harness.WeightSweepRunner")

    val datasetPath: String? = findProperty("datasetPath") as String?
    if (datasetPath != null) {
        args(datasetPath)
    }
    // If not provided, WeightSweepRunner.main() will print a clear error and exit.

    // Forward sweep parameters as JVM system properties.
    listOf("nRuns", "step", "objective", "seed", "parallelism", "aiThreshold").forEach { prop ->
        findProperty(prop)?.let { systemProperty(prop, it.toString()) }
    }

    dependsOn(tasks.named("classes"))
}


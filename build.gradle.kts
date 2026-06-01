plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "net.maizegenetics"
version = "0.4.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.apache.logging.log4j:log4j-api:2.24.3")
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.biokotlin:biokotlin:1.0.0")
    implementation("com.google.guava:guava:33.1.0-jre")
    implementation("com.github.samtools:htsjdk:4.0.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
}

application {
    mainClass.set("net.maizegenetics.MainKt")
}

// ---------------------------------------------------------------------------
// Three-tier test layout:
//   test            - fast unit tests with no external binaries (excludes
//                     "integration" and "e2e" tags).
//   integrationTest - per-step tests that shell out to AnchorWave/PHG/etc.;
//                     runs @Tag("integration") only.
//   e2eTest         - orchestrate smoke test against tiny fixtures; runs
//                     @Tag("e2e") only.
//
// Both heavy tiers auto-skip when run outside the seq-sim-dev Docker
// container (see IntegrationGuard), so they're safe to run anywhere.
// ---------------------------------------------------------------------------
tasks.test {
    useJUnitPlatform {
        excludeTags("integration", "e2e")
    }
}

// Heavy test tiers spawn full pipelines that shell out to subprocess
// Gradle daemons (MLImpute) and native tools (AnchorWave / minimap2 /
// python). The kernel OOM killer (exit 137 / SIGKILL) fires when total
// CONTAINER memory is exceeded -- not the JVM heap. We fork per test
// class so memory is released between classes; the heap itself is left
// at Gradle's default (which is enough for smallseq).
fun Test.applyHeavyTestConfig() {
    // Each end-to-end test runs a full pipeline; isolate them so the
    // JVM frees process-wide resources (classloaders, threadpools,
    // native handles) between classes.
    setForkEvery(1L)
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs per-step integration tests against real external binaries (requires seq-sim-dev container)."
    group = "verification"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    // Restrict Gradle's class-file scan so `forkEvery = 1` only spawns
    // JVMs for the integration test classes. Without this, the heavy
    // test config forks one JVM per test class in the entire test
    // source set (~20 classes), which is both wasteful and flaky --
    // any single fork's startup failure surfaces as
    // "Gradle Test Executor N finished with non-zero exit value 1"
    // and aborts the whole task.
    include("**/*IntegrationTest.class")

    useJUnitPlatform {
        includeTags("integration")
    }

    applyHeavyTestConfig()

    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
}

val e2eTest = tasks.register<Test>("e2eTest") {
    description = "Runs the orchestrate end-to-end smoke test against the mini fixtures (requires seq-sim-dev container)."
    group = "verification"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    // See the comment on `integrationTest` above -- restrict scanning so
    // `forkEvery = 1` only forks JVMs for `*E2ETest` classes instead of
    // the entire test source set.
    include("**/*E2ETest.class")

    useJUnitPlatform {
        includeTags("e2e")
    }

    applyHeavyTestConfig()

    shouldRunAfter(integrationTest)
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(integrationTest, e2eTest)
}

kotlin {
    jvmToolchain(21)
}

plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "net.maizegenetics"
version = "0.2.10"

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

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs per-step integration tests against real external binaries (requires seq-sim-dev container)."
    group = "verification"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    useJUnitPlatform {
        includeTags("integration")
    }

    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
}

val e2eTest = tasks.register<Test>("e2eTest") {
    description = "Runs the orchestrate end-to-end smoke test against the mini fixtures (requires seq-sim-dev container)."
    group = "verification"

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    useJUnitPlatform {
        includeTags("e2e")
    }

    shouldRunAfter(integrationTest)
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(integrationTest, e2eTest)
}

kotlin {
    jvmToolchain(21)
}

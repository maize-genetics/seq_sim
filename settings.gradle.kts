plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "seq_sim"

// Client-side Kotlin/JS web app that builds orchestrate YAML files.
include(":webapp")
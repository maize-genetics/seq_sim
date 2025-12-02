plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "seq_sim"

// Include the editor subproject
include(":editor")
include(":editor:shared")
include(":editor:backend")
include(":editor:frontend")

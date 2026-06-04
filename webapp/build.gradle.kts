plugins {
    // Version omitted: the Kotlin Gradle plugin (2.2.20) is already on the
    // build classpath via the root project, so re-declaring a version here
    // would conflict.
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "webapp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                // Kotlin/JS React wrappers. The BOM pins a set of mutually
                // compatible wrapper versions; 2025.9.11 is built against
                // Kotlin 2.2.20 (matching the root project).
                implementation(project.dependencies.platform("org.jetbrains.kotlin-wrappers:kotlin-wrappers-bom:2025.9.11"))
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom")
            }
        }
    }
}

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "net.maizegenetics.editor"
version = "0.1"

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
            binaries.executable()
        }
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":editor:shared"))
                
                // Kotlin React wrappers
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react:18.3.1-pre.811")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom:18.3.1-pre.811")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-emotion:11.13.3-pre.811")
                
                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                
                // Coroutines for JS
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.9.0")
            }
        }
    }
}

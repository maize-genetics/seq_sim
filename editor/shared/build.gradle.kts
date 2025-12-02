plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "net.maizegenetics.editor"
version = "0.1"

kotlin {
    jvm()
    js(IR) {
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting
        val jsMain by getting
    }
}


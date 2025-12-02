plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("multiplatform") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    application
}

group = "net.maizegenetics"
version = "0.1"

repositories {
    mavenCentral()
}

// Configure repositories for all subprojects
subprojects {
    repositories {
        mavenCentral()
    }
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.apache.logging.log4j:log4j-api:2.24.3")
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("net.maizegenetics.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

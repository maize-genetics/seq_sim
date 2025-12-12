plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "net.maizegenetics"
version = "0.2.3"

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

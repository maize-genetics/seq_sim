plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "net.maizegenetics.editor"
version = "0.1"

val ktorVersion = "2.3.12"

dependencies {
    implementation(project(":editor:shared"))
    
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    
    // YAML parsing
    implementation("org.yaml:snakeyaml:2.3")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

application {
    mainClass.set("net.maizegenetics.editor.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Task to copy frontend distribution to backend resources
tasks.register<Copy>("copyFrontend") {
    dependsOn(":editor:frontend:jsBrowserDistribution")
    from(project(":editor:frontend").layout.buildDirectory.dir("dist/js/productionExecutable"))
    into(layout.buildDirectory.dir("resources/main/static"))
}

tasks.named("processResources") {
    dependsOn("copyFrontend")
}


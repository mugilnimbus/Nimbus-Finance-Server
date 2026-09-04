plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

group = "com.nimbus.finance"
version = "1.0.0"

application {
    mainClass.set("com.nimbus.finance.server.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.5.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.5.2")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.5.2")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.5.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("de.mkammerer:argon2-jvm:2.11")
    implementation("com.google.zxing:core:3.5.4")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.5.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("buildFatJar") {
    group = "build"
    description = "Builds the self-contained Nimbus Finance server JAR"
    archiveFileName.set("nimbus-finance-server.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

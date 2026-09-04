plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
}

group = "com.nimbus.finance"
version = "1.0.0"

allprojects {
    dependencyLocking { lockAllConfigurations() }
}

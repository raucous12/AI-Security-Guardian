import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Bundled, versioned threat-intel data plus its loader. Data changes only through app updates.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.lian.source.policy)
    alias(libs.plugins.lian.intel.data)
}

sourcePolicy {
    pureKotlin = true
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    api(project(":core:engine"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

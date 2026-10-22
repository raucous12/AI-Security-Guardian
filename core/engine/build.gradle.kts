import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: facts in, findings out. No Android, no I/O, no clock, no randomness (Gate C enforces).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.lian.source.policy)
}

sourcePolicy {
    pureKotlin = true
    deterministic = true
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
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The engine must behave identically whatever the machine's locale (Turkish dotless-i, Phase 4.9).
    systemProperty("user.language", "tr")
    systemProperty("user.country", "TR")
}

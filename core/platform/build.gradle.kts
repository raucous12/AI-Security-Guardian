// The only module that talks to Android system APIs; it turns them into plain facts.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.lian.source.policy)
}

android {
    namespace = "lian.core.platform"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":core:engine"))
}

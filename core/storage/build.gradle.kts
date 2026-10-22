// Encrypted at-rest storage: Keystore keys, sealed files, Watch fingerprints (Phase 3.8.1).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.lian.source.policy)
}

android {
    namespace = "lian.core.storage"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(libs.junit4)
}

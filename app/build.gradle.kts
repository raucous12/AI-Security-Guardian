import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.lian.source.policy)
    alias(libs.plugins.lian.privacy.gates)
}

// versionCode lives in git so a given commit always builds the same APK (reproducible builds).
val release = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

android {
    namespace = "lian.app"
    compileSdk = 37

    defaultConfig {
        applicationId = providers.gradleProperty("lian.applicationId").get()
        minSdk = 30
        targetSdk = 36   // Google Play requirement since 31 Aug 2026
        versionCode = release.getProperty("versionCode").toInt()
        versionName = release.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "EXPECTED_SIGNERS", "\"${providers.gradleProperty("lian.expectedSigners").get()}\"")
    }

    signingConfigs {
        // Upload key for Play (Phase 5.3). Secrets exist only in the CI release environment.
        create("upload") {
            providers.environmentVariable("UPLOAD_KEYSTORE").orNull?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("UPLOAD_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("UPLOAD_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("UPLOAD_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Upload key when CI provides it; otherwise, only on request (-Plian.signReleaseWithDebugKey=true),
            // the SDK debug key so a minified release can be installed for testing. Play rejects debug-signed uploads.
            signingConfig = signingConfigs.getByName("upload").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug").takeIf { providers.gradleProperty("lian.signReleaseWithDebugKey").orNull == "true" }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false     // no opaque signing-block blob: reproducible and F-Droid clean
        includeInBundle = true   // Play's SDK Index warnings (dependency names only)
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin") }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = true
        // Version-freshness checks need the network and are handled by Renovate; OldTargetApi: Play
        // requires 36 (37 becomes mandatory in 2027).
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
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

privacyGates {
    // Every entry is a reviewed decision (Phase 5.7). None of these grants network access.
    allowedPermissions.addAll(
        "android.permission.QUERY_ALL_PACKAGES",               // app audit; Play-restricted, declared as a security app
        "android.permission.ACCESS_NETWORK_STATE",             // VPN / Private DNS status only
        "android.permission.QUERY_ADVANCED_PROTECTION_MODE",   // normal, API 36+
        "android.permission.POST_NOTIFICATIONS",               // runtime, asked only when Watch is turned on
        "android.permission.RECEIVE_BOOT_COMPLETED",           // keeps the persisted Watch job across reboots
        "android.permission.PACKAGE_USAGE_STATS",              // "working unseen"; only the user can grant it, in Settings
        "{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",   // androidx.core, signature-level
    )
    allowedExportedComponents.addAll(
        "lian.app.MainActivity",
        "lian.app.shield.ShieldListenerService",              // bindable only by the system (BIND_NOTIFICATION_LISTENER_SERVICE)
        "androidx.profileinstaller.ProfileInstallReceiver",   // guarded by android.permission.DUMP (shell/system only)
    )
    allowedDependencyGroups.addAll(
        "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains", "org.jspecify",
        "androidx.*",
        "com.google.guava",   // listenablefuture only, pulled in by AndroidX
    )
    // LIAN's own audited linear models (format VGM1, SHA-256 in the intel manifest). Every other model
    // format, and any other .vgm, still fails Gate D.
    allowedModelFiles.addAll("lian/intel/message.vgm", "lian/intel/domain.vgm")
}

dependencies {
    implementation(project(":core:intel"))
    implementation(project(":core:platform"))
    implementation(project(":core:storage"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

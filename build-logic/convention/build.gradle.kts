plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
}

gradlePlugin {
    plugins {
        register("privacyGates") {
            id = "lian.privacy-gates"
            implementationClass = "lian.build.PrivacyGatesPlugin"
        }
        register("sourcePolicy") {
            id = "lian.source-policy"
            implementationClass = "lian.build.SourcePolicyPlugin"
        }
        register("intelData") {
            id = "lian.intel-data"
            implementationClass = "lian.build.IntelDataPlugin"
        }
    }
}

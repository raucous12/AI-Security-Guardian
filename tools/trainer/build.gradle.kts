import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Offline model training (maintainer tool). Never part of the app: nothing here is on :app's classpath.
// It reuses the engine's featurizers, so training and on-device inference can't disagree.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(project(":core:intel"))
}

val corpusDir = layout.projectDirectory.dir("corpus")
val modelDir = rootProject.layout.projectDirectory.dir("core/intel/src/main/resources/lian/intel")

// Downloads the pinned datasets (network) and writes the derived training corpus into corpus/.
tasks.register<JavaExec>("fetchCorpus") {
    group = "ml"
    description = "Download pinned training datasets (SHA-256 verified) and build the corpus files."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "lian.tools.trainer.MainKt"
    maxHeapSize = "3g"
    args(
        "fetch",
        layout.projectDirectory.file("datasets.properties").asFile.absolutePath,
        layout.buildDirectory.dir("download").get().asFile.absolutePath,
        corpusDir.asFile.absolutePath,
        providers.gradleProperty("ml.acceptNewHashes").getOrElse("false"),
    )
}

// Trains both models from corpus/ (offline, deterministic) and writes them into :core:intel plus the model card.
tasks.register<JavaExec>("trainModels") {
    group = "ml"
    description = "Train the message and domain models from corpus/ and write .vgm files and docs/ml-model-card.md."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "lian.tools.trainer.MainKt"
    maxHeapSize = "4g"
    args(
        "train",
        layout.projectDirectory.file("datasets.properties").asFile.absolutePath,
        corpusDir.asFile.absolutePath,
        modelDir.asFile.absolutePath,
        rootProject.layout.projectDirectory.file("docs/ml-model-card.md").asFile.absolutePath,
    )
}

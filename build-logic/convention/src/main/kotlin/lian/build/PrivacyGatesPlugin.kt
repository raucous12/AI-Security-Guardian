package lian.build

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.SetProperty
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Allowlists for the privacy gates. Every entry is a reviewed decision: adding one is a code change
 * that shows up in review, which is the point.
 *
 * `{applicationId}` in a permission name is replaced with the variant's application ID.
 */
abstract class PrivacyGatesExtension {
    abstract val allowedPermissions: SetProperty<String>
    abstract val allowedExportedComponents: SetProperty<String>
    abstract val allowedDependencyGroups: SetProperty<String>
    /** Extra groups tolerated only in debuggable builds (e.g. Compose tooling). */
    abstract val allowedDebugDependencyGroups: SetProperty<String>
    /** Exact APK paths of LIAN's own audited model files (.vgm). Third-party model formats are never allowed. */
    abstract val allowedModelFiles: SetProperty<String>
}

/**
 * Gate A (merged manifest), Gate B (runtime dependency allowlist) and Gate D (packaged APK contents).
 * `assemble*`, `bundle*` and `check` depend on them, so no artifact can be produced that fails a gate.
 */
class PrivacyGatesPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val gates = extensions.create<PrivacyGatesExtension>("privacyGates")
        val reports = layout.buildDirectory.dir("reports/privacy")

        extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
            val v = variant.name.replaceFirstChar(Char::uppercase)
            val debug = variant.buildType == "debug"

            val manifestGate = tasks.register<ManifestGateTask>("privacyManifestGate$v") {
                group = "verification"
                description = "Gate A: permissions, exported components and backup/network flags in the merged manifest."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                applicationId.set(variant.applicationId)
                allowedPermissions.set(gates.allowedPermissions)
                allowedExported.set(gates.allowedExportedComponents)
                report.set(reports.map { it.file("${variant.name}-manifest.txt") })
            }

            val dependencyGate = tasks.register<DependencyGateTask>("privacyDependencyGate$v") {
                group = "verification"
                description = "Gate B: every runtime dependency must come from a reviewed group."
                rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                allowedGroups.set(gates.allowedDependencyGroups)
                if (debug) allowedGroups.addAll(gates.allowedDebugDependencyGroups)
                report.set(reports.map { it.file("${variant.name}-dependencies.txt") })
            }

            val apkGate = tasks.register<ApkGateTask>("privacyApkGate$v") {
                group = "verification"
                description = "Gate D: no third-party ML model files, only allow-listed .vgm models; native libraries 16 KB-aligned."
                apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                allowedModelFiles.set(gates.allowedModelFiles)
                report.set(reports.map { it.file("${variant.name}-apk.txt") })
            }

            tasks.matching { it.name == "assemble$v" || it.name == "bundle$v" }
                .configureEach { dependsOn(manifestGate, dependencyGate) }
            tasks.matching { it.name == "assemble$v" }.configureEach { dependsOn(apkGate) }
            tasks.matching { it.name == "check" }.configureEach { dependsOn(manifestGate, dependencyGate) }
        }
    }
}

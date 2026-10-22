package lian.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** Gate A: the merged manifest may only contain reviewed permissions and exported components. */
@CacheableTask
abstract class ManifestGateTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val allowedPermissions: SetProperty<String>
    @get:Input abstract val allowedExported: SetProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val doc = DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)

        fun elements(tag: String): List<Element> =
            doc.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

        val appId = applicationId.get()
        fun resolve(name: String) = name.replace("{applicationId}", appId)

        val permissions = (elements("uses-permission") + elements("uses-permission-sdk-23"))
            .map { it.getAttributeNS(ANDROID_NS, "name") }.toSortedSet()
        val exported = listOf("activity", "activity-alias", "service", "receiver", "provider")
            .flatMap(::elements)
            .filter { it.getAttributeNS(ANDROID_NS, "exported") == "true" }
            .map { it.getAttributeNS(ANDROID_NS, "name") }
            .toSortedSet()
        val application = elements("application").single()

        val problems = buildList {
            (permissions - allowedPermissions.get().map(::resolve).toSet()).forEach { add("forbidden permission: $it") }
            (exported - allowedExported.get().map(::resolve).toSet()).forEach { add("unexpected exported component: $it") }
            if (application.getAttributeNS(ANDROID_NS, "allowBackup") != "false") {
                add("android:allowBackup must be \"false\"")
            }
            if (!application.hasAttributeNS(ANDROID_NS, "dataExtractionRules")) {
                add("android:dataExtractionRules is missing (device-to-device transfer must be excluded explicitly)")
            }
            if (!application.hasAttributeNS(ANDROID_NS, "networkSecurityConfig")) {
                add("android:networkSecurityConfig is missing (the deny-all backstop)")
            }
            if (application.getAttributeNS(ANDROID_NS, "usesCleartextTraffic") == "true") {
                add("android:usesCleartextTraffic must not be true")
            }
        }

        report.get().asFile.writeText(
            buildString {
                appendLine("permissions (${permissions.size}):")
                permissions.forEach { appendLine("  $it") }
                appendLine("exported components (${exported.size}):")
                exported.forEach { appendLine("  $it") }
                appendLine(if (problems.isEmpty()) "RESULT: PASS" else "RESULT: FAIL")
                problems.forEach { appendLine("  - $it") }
            },
        )
        if (problems.isNotEmpty()) {
            throw GradleException(
                "PRIVACY GATE A FAILED (merged manifest)\n" + problems.joinToString("\n") { "  - $it" },
            )
        }
    }
}

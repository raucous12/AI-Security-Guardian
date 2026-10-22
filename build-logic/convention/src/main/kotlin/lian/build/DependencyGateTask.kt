package lian.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Gate B: walks the variant's resolved runtime graph and fails on any module whose group is not on
 * the allowlist. Groups ending in `.*` match that prefix and its subgroups.
 */
abstract class DependencyGateTask : DefaultTask() {
    @get:Input abstract val rootComponent: Property<ResolvedComponentResult>
    @get:Input abstract val allowedGroups: SetProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val modules = sortedSetOf<String>()
        val visited = HashSet<ComponentIdentifier>()
        val queue = ArrayDeque(listOf(rootComponent.get()))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!visited.add(component.id)) continue
            (component.id as? ModuleComponentIdentifier)?.let { modules += "${it.group}:${it.module}:${it.version}" }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }

        val allowed = allowedGroups.get()
        fun permitted(group: String) = allowed.any { rule ->
            if (rule.endsWith(".*")) group == rule.dropLast(2) || group.startsWith(rule.dropLast(1)) else group == rule
        }
        val unreviewed = modules.filterNot { permitted(it.substringBefore(':')) }

        report.get().asFile.writeText(
            buildString {
                appendLine("runtime modules (${modules.size}):")
                modules.forEach { appendLine("  $it") }
                appendLine(if (unreviewed.isEmpty()) "RESULT: PASS" else "RESULT: FAIL")
                unreviewed.forEach { appendLine("  - unreviewed: $it") }
            },
        )
        if (unreviewed.isNotEmpty()) {
            throw GradleException(
                "PRIVACY GATE B FAILED (runtime dependencies not on the allowlist)\n" +
                    unreviewed.joinToString("\n") { "  - $it" },
            )
        }
    }
}

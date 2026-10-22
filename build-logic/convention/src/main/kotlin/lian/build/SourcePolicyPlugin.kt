package lian.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

abstract class SourcePolicyExtension {
    /** Module must not import android.* (engine, intel). */
    abstract val pureKotlin: Property<Boolean>
    /** Module must not read clocks, randomness or the default locale (engine). */
    abstract val deterministic: Property<Boolean>
}

/**
 * Gate C: scans the module's production sources for banned APIs. A line may carry
 * `privacy-gate: allow <rule-id> (<reason>)` to record a reviewed exception; every exception is
 * listed in the report so it stays visible.
 */
class SourcePolicyPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val policy = extensions.create<SourcePolicyExtension>("sourcePolicy").apply {
            pureKotlin.convention(false)
            deterministic.convention(false)
        }
        val gate = tasks.register<SourcePolicyTask>("verifySourcePolicy") {
            group = "verification"
            description = "Gate C: banned APIs in production sources."
            sources.from(fileTree("src/main") { include("**/*.kt", "**/*.java") })
            pureKotlin.set(policy.pureKotlin)
            deterministic.set(policy.deterministic)
            report.set(layout.buildDirectory.file("reports/privacy/source-policy.txt"))
        }
        tasks.matching { it.name in setOf("check", "preBuild", "compileKotlin") }.configureEach { dependsOn(gate) }
    }
}

@CacheableTask
abstract class SourcePolicyTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input abstract val pureKotlin: Property<Boolean>
    @get:Input abstract val deterministic: Property<Boolean>
    @get:OutputFile abstract val report: RegularFileProperty

    private class Ban(val id: String, val pattern: Regex, val why: String)

    @TaskAction
    fun verify() {
        val bans = buildList {
            add(Ban("net.socket", Regex("""\bjava\.net\.(Socket|ServerSocket|DatagramSocket|HttpURLConnection|URLConnection)\b|\b(Server|Datagram)?Socket\s*\("""), "raw network access"))
            add(Ban("net.open", Regex("""\.openConnection\s*\(|\.openStream\s*\("""), "URL fetching"))
            add(Ban("net.dns", Regex("""InetAddress\.getByName|InetAddress\.getAllByName"""), "DNS lookups; IPs are parsed by the engine instead"))
            add(Ban("net.client", Regex("""\b(okhttp3|retrofit2|io\.ktor\.client|com\.android\.volley|java\.net\.http)\b"""), "HTTP client libraries"))
            add(Ban("web.view", Regex("""\bandroid\.webkit\.|\bandroidx\.browser\."""), "WebView / Custom Tabs fetch content on the app's behalf"))
            add(Ban("code.dynamic", Regex("""\bdalvik\.system\.\w*ClassLoader|\bClass\.forName\s*\("""), "dynamic code loading"))
            add(Ban("ai.ml", Regex("""\b(org\.tensorflow|com\.google\.mlkit|com\.google\.ai|com\.google\.mediapipe|ai\.onnxruntime|org\.pytorch)\b"""), "AI/ML runtimes (rule R1)"))
            add(Ban("crypto.weak", Regex(""""AES/ECB|/CBC/|"DES|"DESede|"RC4|"MD5"|"SHA-?1"|\.setSeed\s*\(|SecureRandom\s*\(\s*[^)\s]|IvParameterSpec\s*\(\s*ByteArray\s*\("""), "weak or misused cryptography"))
            add(Ban("log", Regex("""\bandroid\.util\.Log\b|\bprintln\s*\(|\.printStackTrace\s*\("""), "logs end up in bug reports"))
            add(Ban("work.data", Regex("""\bworkDataOf\s*\(|\bData\.Builder\s*\("""), "WorkManager persists Data unencrypted (defect #6)"))
            if (pureKotlin.get()) add(Ban("pure.android", Regex("""^\s*import\s+android\."""), "pure-Kotlin module"))
            if (deterministic.get()) {
                add(Ban("determinism", Regex("""System\.currentTimeMillis|System\.nanoTime|Instant\.now|Clock\.system|java\.util\.Random|kotlin\.random|UUID\.randomUUID|Locale\.getDefault|\.toLowerCase\s*\(\s*\)|\.toUpperCase\s*\(\s*\)"""), "the engine must be a pure function of its inputs"))
            }
        }

        val violations = mutableListOf<String>()
        val exceptions = mutableListOf<String>()
        sources.files.sortedBy { it.path }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                for (ban in bans) {
                    if (!ban.pattern.containsMatchIn(line)) continue
                    val where = "${file.name}:${index + 1}"
                    if (line.contains("privacy-gate: allow ${ban.id}")) exceptions += "$where [${ban.id}] ${line.trim()}"
                    else violations += "$where [${ban.id}] ${ban.why}: ${line.trim()}"
                }
            }
        }

        report.get().asFile.writeText(
            buildString {
                appendLine("files scanned: ${sources.files.size}")
                appendLine("reviewed exceptions (${exceptions.size}):")
                exceptions.forEach { appendLine("  $it") }
                appendLine(if (violations.isEmpty()) "RESULT: PASS" else "RESULT: FAIL")
                violations.forEach { appendLine("  - $it") }
            },
        )
        if (violations.isNotEmpty()) {
            throw GradleException("PRIVACY GATE C FAILED (banned APIs in sources)\n" + violations.joinToString("\n") { "  - $it" })
        }
    }
}

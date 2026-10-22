package lian.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * The intel data pipeline (Phase 3.9).
 *
 * - `updateIntel` (run by a maintainer, needs network): downloads each upstream source at a pinned
 *   URL, verifies the SHA-256 recorded in `intel-sources.properties`, compiles it into the compact
 *   resource format and writes it into src/. The result is committed, so builds never download data.
 * - `generateIntelManifest` (every build, offline): records the SHA-256 of every packaged data file;
 *   the runtime loader refuses data that doesn't match.
 */
class IntelDataPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val dataDir = layout.projectDirectory.dir("src/main/resources/lian/intel")

        tasks.register<UpdateIntelTask>("updateIntel") {
            group = "intel"
            description = "Download pinned upstream data (PSL, Unicode confusables), verify SHA-256, compile into src/."
            sourcesFile.set(layout.projectDirectory.file("intel-sources.properties"))
            resourcesDir.set(dataDir)
            testResourcesDir.set(layout.projectDirectory.dir("src/test/resources/lian/intel"))
            acceptNewHashes.set(providers.gradleProperty("intel.acceptNewHashes").map { it.toBoolean() }.orElse(false))
        }

        val manifest = tasks.register<IntelManifestTask>("generateIntelManifest") {
            group = "intel"
            description = "SHA-256 manifest of the packaged intel data."
            dataFiles.from(fileTree(dataDir) { exclude("manifest.tsv") })
            outputDir.set(layout.buildDirectory.dir("generated/intelManifest"))
        }
        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.getByType<SourceSetContainer>().named("main") {
                resources.srcDir(manifest.flatMap { it.outputDir })
            }
        }
    }
}

@CacheableTask
abstract class IntelManifestTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val dataFiles: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val lines = dataFiles.files.sortedBy { it.name }.map { file ->
            val bytes = file.readBytes()
            "${file.name}\t${sha256(bytes)}\t${bytes.size}"
        }
        val out = outputDir.get().file("lian/intel/manifest.tsv").asFile
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n", prefix = "# name\tsha256\tbytes\n", postfix = "\n"))
    }
}

abstract class UpdateIntelTask : DefaultTask() {
    @get:Internal abstract val sourcesFile: RegularFileProperty
    @get:Internal abstract val resourcesDir: DirectoryProperty
    @get:Internal abstract val testResourcesDir: DirectoryProperty
    @get:Input abstract val acceptNewHashes: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun update() {
        val propsFile = sourcesFile.get().asFile
        val props = propsFile.readLines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER).build()
        val newHashes = mutableMapOf<String, String>()

        fun fetch(key: String): ByteArray {
            val url = props["$key.url"] ?: throw GradleException("intel-sources.properties: $key.url missing")
            require(url.startsWith("https://")) { "$key.url must be https" }
            val response = http.send(
                HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(120)).header("User-Agent", "lian-intel-updater").build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            if (response.statusCode() != 200) throw GradleException("$url -> HTTP ${response.statusCode()}")
            val body = response.body()
            val actual = sha256(body)
            val pinned = props["$key.sha256"].orEmpty()
            if (pinned != actual) {
                if (!acceptNewHashes.get()) {
                    throw GradleException("$key: SHA-256 mismatch (pinned '$pinned', downloaded $actual). Review the upstream change, then rerun with -Pintel.acceptNewHashes=true")
                }
                newHashes["$key.sha256"] = actual
            }
            logger.lifecycle("intel: $key ${body.size} bytes sha256=$actual")
            return body
        }

        val res = resourcesDir.get().asFile.apply { mkdirs() }
        val testRes = testResourcesDir.get().asFile.apply { mkdirs() }

        val psl = fetch("psl").toString(Charsets.UTF_8)
        File(res, "psl.txt").writeText(compilePsl(psl, props.getValue("psl.url")))
        File(testRes, "test_psl.txt").writeBytes(fetch("psl.tests"))

        val confusables = fetch("confusables").toString(Charsets.UTF_8)
        File(res, "confusables.tsv").writeText(compileConfusables(confusables, props.getValue("confusables.url")))

        val indicators = compileIndicators(fetch("stalkerware").toString(Charsets.UTF_8), "stalkerware") +
            compileIndicators(fetch("watchware").toString(Charsets.UTF_8), "watchware")
        File(res, "stalkerware.tsv").writeText(
            "# Echap stalkerware-indicators (CC BY 4.0), ${props.getValue("stalkerware.url").substringBeforeLast('/')}\n" +
                "# format: type\\tname\\tpackages\\tcertificate SHA-1s\\twebsites (comma-separated)\n" +
                indicators.joinToString("\n", postfix = "\n"),
        )

        val status = fetch("attestation.status").toString(Charsets.UTF_8)
        File(res, "attestation_revoked.tsv").writeText(compileAttestationStatus(status, props.getValue("attestation.status.url")))

        if (newHashes.isNotEmpty()) {
            val updated = propsFile.readLines().map { line ->
                val key = line.substringBefore('=').trim()
                newHashes[key]?.let { "$key=$it" } ?: line
            }
            propsFile.writeText(updated.joinToString("\n", postfix = "\n"))
            logger.lifecycle("intel: recorded new hashes for ${newHashes.keys}")
        }
    }

    /** PSL → one rule per line, prefixed I (ICANN section) or P (PRIVATE section). */
    private fun compilePsl(text: String, source: String): String {
        var section = ""
        val rules = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.contains("===BEGIN ICANN DOMAINS===") -> section = "I"
                line.contains("===BEGIN PRIVATE DOMAINS===") -> section = "P"
                line.isEmpty() || line.startsWith("//") -> Unit
                section.isNotEmpty() -> rules += "$section\t${line.split(' ', '\t').first().lowercase()}"
            }
        }
        if (rules.size < 5_000) throw GradleException("PSL looks truncated (${rules.size} rules)")
        return "# Public Suffix List (MPL-2.0), compiled by :core:intel:updateIntel from $source\n" +
            "# format: <I|P>\\t<rule>   I = ICANN section, P = PRIVATE section (shared hosting)\n" +
            rules.joinToString("\n", postfix = "\n")
    }

    /**
     * The indicator YAML (a list of `- name:` entries with `packages:`, `certificates:` and `websites:` lists)
     * → one TSV row per product. Only the small, fixed subset of YAML that the files use is read; nested
     * maps such as `c2:` are skipped.
     */
    private fun compileIndicators(yaml: String, defaultType: String): List<String> {
        val entries = mutableListOf<MutableMap<String, MutableList<String>>>()
        var key: String? = null
        for (raw in yaml.lines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            when {
                raw.startsWith("- name:") -> {
                    entries += mutableMapOf("name" to mutableListOf(unquote(raw.substringAfter(':'))))
                    key = null
                }
                raw.startsWith("  ") && !raw.startsWith("   ") && entries.isNotEmpty() -> {
                    val line = raw.substring(2)
                    if (line.startsWith("- ")) {
                        key?.let { entries.last().getOrPut(it) { mutableListOf() } += unquote(line.substring(2)) }
                    } else if (':' in line) {
                        val k = line.substringBefore(':').trim()
                        val v = unquote(line.substringAfter(':'))
                        if (v.isEmpty()) { key = k; entries.last()[k] = mutableListOf() } else { entries.last()[k] = mutableListOf(v); key = null }
                    }
                }
            }
        }
        if (entries.size < 10) throw GradleException("$defaultType indicators look truncated (${entries.size} entries)")
        fun field(e: Map<String, List<String>>, k: String) = e[k].orEmpty().map { it.replace('\t', ' ').replace(',', ' ').trim() }.filter { it.isNotEmpty() }
        return entries.map { e ->
            listOf(
                e["type"]?.firstOrNull() ?: defaultType,
                field(e, "name").first(),
                field(e, "packages").joinToString(","),
                field(e, "certificates").map { it.lowercase() }.filter { c -> c.length == 40 && c.all { it in "0123456789abcdef" } }.joinToString(","),
                field(e, "websites").map { it.lowercase() }.joinToString(","),
            ).joinToString("\t")
        }
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    /** Google's attestation status JSON → `<serial hex>\t<status>`; only the serial and status are kept. */
    private fun compileAttestationStatus(json: String, source: String): String {
        val entry = Regex("\"([0-9a-f]+)\"\\s*:\\s*\\{([^}]*)\\}")
        val statusField = Regex("\"status\"\\s*:\\s*\"(\\w+)\"")
        val rows = entry.findAll(json).mapNotNull { m ->
            statusField.find(m.groupValues[2])?.let { "${m.groupValues[1]}\t${it.groupValues[1]}" }
        }.sorted().toList()
        if (rows.size < 100) throw GradleException("attestation status list looks truncated (${rows.size} entries)")
        return "# Android attestation key status (revoked/suspended certificate serials), from $source\n" +
            "# format: <serial, lowercase hex>\\t<REVOKED|SUSPENDED>\n" + rows.joinToString("\n", postfix = "\n")
    }

    /** confusables.txt → `<source hex>\t<prototype hex sequence>`; header date kept for provenance. */
    private fun compileConfusables(text: String, source: String): String {
        val date = text.lineSequence().firstOrNull { it.startsWith("# Date:") }?.removePrefix("# Date:")?.trim().orEmpty()
        val rows = text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val fields = line.split(';').map { it.trim() }
                "${fields[0].lowercase()}\t${fields[1].lowercase()}"
            }
            .toList()
        if (rows.size < 5_000) throw GradleException("confusables.txt looks truncated (${rows.size} rows)")
        return "# Unicode confusables (UTS #39), date $date, from $source\n" +
            "# Unicode License v3: https://www.unicode.org/license.txt\n" +
            "# format: <code point hex>\\t<prototype code points hex, space-separated>\n" +
            rows.joinToString("\n", postfix = "\n")
    }
}

/** Locale-independent hex; must match the runtime loader in :core:intel byte for byte. */
internal fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (b in digest) append(hex[(b.toInt() shr 4) and 0xF]).append(hex[b.toInt() and 0xF])
    }
}

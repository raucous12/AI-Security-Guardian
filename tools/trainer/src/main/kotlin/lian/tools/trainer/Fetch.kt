package lian.tools.trainer

import lian.core.engine.link.Ipv4
import lian.core.engine.link.Ipv4Result
import lian.core.engine.link.PublicSuffixList
import lian.core.intel.IntelPack
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

/** Downloads the pinned datasets and derives the two training corpora. */
class Fetch(private val props: Props, private val downloadDir: File, private val corpusDir: File, private val acceptNewHashes: Boolean) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)   // safe: content is pinned by SHA-256
        .build()
    private val newHashes = LinkedHashMap<String, String>()

    fun run() {
        downloadDir.mkdirs()
        corpusDir.mkdirs()
        writeMessages()
        writeDomains()
        props.rewrite(newHashes)
        if (newHashes.isNotEmpty()) println("recorded new hashes: ${newHashes.keys}")
    }

    private fun writeMessages() {
        val rows = ArrayList<Triple<Int, String, String>>()
        unzip(fetch("uci"), "SMSSpamCollection").toString(Charsets.UTF_8).lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) rows += Triple(if (line.substring(0, tab) == "spam") 1 else 0, "uci", line.substring(tab + 1))
        }
        val mishra = unzip(fetch("mishra"), "Dataset_5971.csv").toString(Charsets.UTF_8)
        Csv.parse(mishra).drop(1).forEach { cols ->
            if (cols.size >= 2) {
                val label = when (cols[0].trim().lowercase()) {
                    "ham" -> 0
                    "spam", "smishing" -> 1
                    else -> -1
                }
                if (label >= 0) rows += Triple(label, "mishra", cols[1])
            }
        }
        // Identical messages appear in both datasets; keep one copy, and drop texts labelled both ways.
        val byKey = LinkedHashMap<String, MutableList<Triple<Int, String, String>>>()
        rows.forEach { byKey.getOrPut(Train.messageKey(it.third)) { ArrayList() } += it }
        val kept = byKey.values.filter { group -> group.map { it.first }.distinct().size == 1 }.map { it.first() }
        writeGzip(File(corpusDir, "messages.tsv.gz"), kept.map { "${it.first}\t${it.second}\t${clean(it.third)}" })
        println("messages: ${rows.size} rows → ${kept.size} unique (${kept.count { it.first == 1 }} scam/spam)")
    }

    private fun writeDomains() {
        val psl = IntelPack.load().publicSuffixList
        val benign = LinkedHashMap<String, String>()   // label → registrable
        val benignRegistrables = HashSet<String>()
        val majestic = fetch("majestic").toString(Charsets.UTF_8)
        majestic.lineSequence().drop(1).forEach { line ->
            val domain = line.split(',').getOrNull(2)?.trim()?.lowercase() ?: return@forEach
            val registrable = registrable(psl, domain) ?: return@forEach
            benignRegistrables += registrable
            benign.putIfAbsent(registrable.substringBefore('.'), registrable)
        }
        val phishing = LinkedHashMap<String, String>()
        var compromised = 0
        fetch("phishdb").toString(Charsets.UTF_8).lineSequence().forEach { line ->
            val host = line.trim().lowercase()
            if (host.isEmpty() || host.startsWith("#")) return@forEach
            val registrable = registrable(psl, host) ?: return@forEach
            // A popular domain on a phishing list was compromised or abused, not registered to deceive.
            if (registrable in benignRegistrables) { compromised++; return@forEach }
            phishing.putIfAbsent(registrable.substringBefore('.'), registrable)
        }
        val conflicts = benign.keys intersect phishing.keys
        val lines = benign.filterKeys { it !in conflicts }.keys.map { "0\tmajestic\t$it" } +
            phishing.filterKeys { it !in conflicts }.keys.map { "1\tphishdb\t$it" }
        writeGzip(File(corpusDir, "domains.tsv.gz"), lines)
        println("domains: ${benign.size} benign labels, ${phishing.size} phishing labels, $compromised compromised hosts skipped, ${conflicts.size} conflicts dropped")
    }

    private fun registrable(psl: PublicSuffixList, host: String): String? {
        if (host.isEmpty() || Ipv4.parse(host) !is Ipv4Result.NotIpv4) return null
        if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return null
        return psl.match(host)?.registrable?.takeIf { it.substringBefore('.').isNotEmpty() }
    }

    private fun fetch(key: String): ByteArray {
        val url = props["$key.url"]
        require(url.startsWith("https://")) { "$key.url must be https" }
        val cache = File(downloadDir, key)
        val pinned = props.opt("$key.sha256")
        if (cache.exists() && pinned.isNotEmpty() && sha256(cache.readBytes()) == pinned) return cache.readBytes()
        val response = http.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).header("User-Agent", "lian-trainer").build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(response.statusCode() == 200) { "$url -> HTTP ${response.statusCode()}" }
        val body = response.body()
        val actual = sha256(body)
        if (actual != pinned) {
            check(acceptNewHashes) { "$key: SHA-256 mismatch (pinned '$pinned', downloaded $actual). Review, then rerun with -Pml.acceptNewHashes=true" }
            newHashes["$key.sha256"] = actual
        }
        cache.writeBytes(body)
        println("$key: ${body.size} bytes sha256=$actual")
        return body
    }

    private fun unzip(zip: ByteArray, name: String): ByteArray {
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name.substringAfterLast('/') == name) return input.readBytes()
            }
        }
        error("$name not found in archive")
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()

    private fun writeGzip(file: File, lines: List<String>) {
        // Fixed gzip header (no timestamp/name): the same corpus always produces the same bytes.
        GZIPOutputStream(file.outputStream()).use { out -> out.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)) }
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** RFC 4180 CSV: quoted fields, doubled quotes, newlines inside quotes. */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }
}

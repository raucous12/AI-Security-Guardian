package lian.tools.trainer

import java.io.File
import kotlin.system.exitProcess

/**
 * Maintainer tool, never shipped.
 *  - `fetch <datasets.properties> <downloadDir> <corpusDir> <acceptNewHashes>`: download pinned sources,
 *    verify SHA-256, write the derived corpus (gzip TSV) into corpusDir.
 *  - `train <datasets.properties> <corpusDir> <modelDir> <modelCard>`: train both models deterministically
 *    from the corpus, write `message.vgm` / `domain.vgm` and the model card.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "fetch" -> Fetch(Props(File(args[1])), File(args[2]), File(args[3]), args[4].toBoolean()).run()
        "train" -> Train(Props(File(args[1])), File(args[2]), File(args[3]), File(args[4])).run()
        else -> {
            System.err.println("usage: fetch|train …")
            exitProcess(2)
        }
    }
}

/** A `key=value` file that can be rewritten in place (to record newly accepted hashes). */
class Props(val file: File) {
    private val values: Map<String, String> = file.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    operator fun get(key: String): String = values[key] ?: error("${file.name}: $key missing")
    fun opt(key: String): String = values[key].orEmpty()

    fun rewrite(updates: Map<String, String>) {
        if (updates.isEmpty()) return
        file.writeText(
            file.readLines().joinToString("\n", postfix = "\n") { line ->
                val key = line.substringBefore('=').trim()
                if (!line.trimStart().startsWith("#") && key in updates) "$key=${updates.getValue(key)}" else line
            },
        )
    }
}

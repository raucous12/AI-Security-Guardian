package lian.core.engine.ml

/** Formats a prediction's reasons for evidence rows: words in quotes, signals as their bracketed ids. */
object MlText {
    fun reasons(p: LinearModel.Prediction): String =
        p.reasons.joinToString(", ") { if (it.term.startsWith("[")) it.term else "\"${it.term.take(40)}\"" }.ifEmpty { "(overall wording)" }
}

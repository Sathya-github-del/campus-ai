package dev.studyapp.app.core

/** Tidies on-device OCR output before it goes into the message box for the student to edit. */
object OcrText {
    /** Long enough for a full textbook question; stops a whole photographed page from becoming one huge (costly) message. */
    const val MAX_CHARS = 3000

    fun clean(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS).trimEnd() + "…"
    }

    /** Adds recognised text after whatever is already typed (e.g. a subject starter). */
    fun appendTo(input: String, recognised: String): String = when {
        recognised.isBlank() -> input
        input.isBlank() -> recognised
        input.endsWith("\n") -> input + recognised
        else -> input.trimEnd() + "\n" + recognised
    }
}
